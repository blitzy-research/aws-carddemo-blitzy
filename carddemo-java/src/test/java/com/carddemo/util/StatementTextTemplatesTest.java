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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link StatementTextTemplates}, the holder of the plain-text account statement
 * layout.
 *
 * <p><strong>What this test is for.</strong> The statement text record is exactly 80 US-ASCII bytes
 * wide and the layout has exactly seventeen line groups, every one of which sums to those 80 bytes.
 * The emitted statement file is compared byte for byte against a golden fixture, so this class is a
 * byte-parity surface: a template that is one space out, or that picks the wrong amount mask,
 * produces output that looks entirely plausible in a diff viewer and still fails the comparison.
 * Every assertion below is therefore made on the {@link StandardCharsets#US_ASCII} encoded image
 * rather than on a trimmed or normalised string, and every width is measured in encoded bytes rather
 * than in string characters.</p>
 *
 * <p><strong>The expectations are an independent oracle.</strong> Every expected record is
 * hand-written from the verified component widths and caption literals of the seventeen line groups.
 * No expectation is produced by calling a constant or a method of the class under test, no
 * expectation is a captured snapshot of previous output, and no assertion compares a value to
 * itself. A dedicated test asserts the geometry of the oracle itself, so a typing slip in an
 * expectation fails loudly here instead of silently agreeing with a wrong implementation.</p>
 *
 * <p><strong>The three hazards this test exists to pin down.</strong>
 *
 * <ol>
 *   <li><strong>The two banners use different splits.</strong> The start banner is 31 asterisks, an
 *       18-byte text and 31 asterisks. The end banner is 32 asterisks, a 16-byte text and 32
 *       asterisks. Both total 80 and both are correct; they are simply not the same split, because
 *       the two texts differ in length by two. The two are asserted independently, and their
 *       inequality is asserted, so no future tidy-up can unify them.</li>
 *   <li><strong>The pad counts inside the captions are contractual.</strong> One trailing pad byte
 *       after the basic-details heading, one trailing space inside the transaction-summary heading,
 *       35 trailing spaces after the detail column heading, two leading spaces before the amount
 *       column heading, and nine, four and nine spaces respectively before the colons of the
 *       account-id, current-balance and credit-score labels. None of them is trimmed, collapsed or
 *       re-derived here.</li>
 *   <li><strong>Two 13-character trailing-minus masks coexist.</strong> Mask A prints leading zeros
 *       and serves the current-balance line; mask B suppresses them and serves the transaction
 *       amount and the total. Each is exercised through its own name, and the two are shown to
 *       differ for the same zero-suppressible input.</li>
 * </ol>
 *
 * <p><strong>Shared mask semantics asserted here.</strong> The sign is trailing in both masks: a
 * negative value ends in a minus and a non-negative value ends in a space. A plus sign is never
 * emitted, and its absence is asserted by byte value. A blank-when-zero clause appears nowhere in
 * the legacy estate, so a zero value always renders its decimal point and two fraction digits
 * rather than an empty field. Values arrive already at scale two with truncation applied upstream;
 * this test never re-scales, never selects a rounding mode and never performs arithmetic on an
 * amount. A value at another scale (decision D-05) and a value whose integer part needs more than
 * nine digits (decision D-06) are both rejected rather than quietly adjusted, and both rejections
 * are asserted on type and on message content.</p>
 *
 * <p><strong>How the expectations are built.</strong> Every expected record and every expected mask
 * below is a hand-written literal, mirroring the production decision to hold this layout as literal
 * constants rather than render it through a templating engine (decision D-27): a template introduces
 * whitespace and ordering variability that a byte-for-byte comparison cannot absorb, so the literals
 * are the contract. For the same reason no format-string abstraction, no locale-sensitive number
 * formatting and no text-block reflow is used to build an expectation.</p>
 *
 * <p><strong>Deliberately not covered here.</strong> The order in which records are emitted, the
 * fact that the rule line occupies three distinct positions in that order, page structure, and the
 * mapping of the altered 350-byte transaction record all belong to the batch tier and the statement
 * generation service; this test asserts only that the rule line is a single reusable constant. The
 * 100-byte HTML statement stream is a separate record width in a separate holder, and the job stream
 * declares the same data-definition name at 80 in one step and at 100 in the next - a conflict
 * resolved to 80 for the text stream and 100 for the HTML stream (decision D-44), which is why 80 is
 * what is asserted here. The two-byte timestamp truncation introduced by the job's re-projection is
 * likewise outside this class.</p>
 */
@DisplayName("StatementTextTemplates :: eighty-byte plain-text statement line templates")
class StatementTextTemplatesTest {

    // ASCII byte values used for byte-level inspection, each given as a decimal code point rather
    // than a character escape, so that no escape sequence appears anywhere in this file - in
    // particular none that could be mistaken for an emitted line terminator or tab.

    /** ASCII horizontal tab, 9. Detected and asserted absent; never emitted. */
    private static final byte ASCII_TAB_BYTE = 9;

    /** ASCII line feed, 10. Detected and asserted absent; never emitted. */
    private static final byte ASCII_LINE_FEED_BYTE = 10;

    /** ASCII carriage return, 13. Detected and asserted absent; never emitted. */
    private static final byte ASCII_CARRIAGE_RETURN_BYTE = 13;

    /** ASCII space, 32. The one and only pad byte of every character field. */
    private static final byte ASCII_SPACE_BYTE = 32;

    /** ASCII dollar sign, 36. Present on two line groups and deliberately absent from a third. */
    private static final byte ASCII_DOLLAR_BYTE = 36;

    /** ASCII asterisk, 42. The fill byte of both banner runs. */
    private static final byte ASCII_ASTERISK_BYTE = 42;

    /** ASCII plus sign, 43. Asserted absent everywhere: the trailing sign is a minus or a space. */
    private static final byte ASCII_PLUS_BYTE = 43;

    /** ASCII hyphen-minus, 45. The fill byte of the rule line and the trailing negative sign. */
    private static final byte ASCII_HYPHEN_BYTE = 45;

    /** ASCII full stop, 46. The literal decimal point of both amount masks. */
    private static final byte ASCII_DECIMAL_POINT_BYTE = 46;

    /** ASCII colon, 58. Closes each of the three twenty-byte labels. */
    private static final byte ASCII_COLON_BYTE = 58;

    // Expected geometry, written out as literals so that every number this test asserts against
    // is visible at the point of use rather than borrowed from the class under test.

    /** Expected width of one statement text record, in encoded bytes. */
    private static final int EXPECTED_RECORD_LENGTH = 80;

    /** Expected number of line groups in the statement layout. */
    private static final int EXPECTED_LINE_GROUP_COUNT = 17;

    /** Expected rendered width of either amount mask, in encoded bytes. */
    private static final int EXPECTED_MASK_LENGTH = 13;

    /** Expected asterisk run per side of the start banner. */
    private static final int EXPECTED_START_BANNER_ASTERISK_RUN = 31;

    /** Expected text width of the start banner. */
    private static final int EXPECTED_START_BANNER_TEXT_WIDTH = 18;

    /** Expected asterisk run per side of the end banner. One more per side than the start banner. */
    private static final int EXPECTED_END_BANNER_ASTERISK_RUN = 32;

    /** Expected text width of the end banner. Two fewer than the start banner's. */
    private static final int EXPECTED_END_BANNER_TEXT_WIDTH = 16;

    // The eight wholly fixed line groups. Composition of each, hand-derived from the declared
    // component widths:
    //   start banner  31 asterisk + 18 text          + 31 asterisk
    //   rule line     80 hyphen
    //   basic details 33 space    + 14 field         + 33 space   (13-char literal, ONE pad byte)
    //   summary       30 space    + 20 field         + 30 space   (19 caps, ONE trailing space)
    //   columns       16 field    + 51 field         + 13 field   (35 pad; TWO leading spaces)
    //   end banner    32 asterisk + 16 text          + 32 asterisk

    /** Start banner: 31 asterisks, the 18-byte start text, 31 asterisks. */
    private static final String E_ST_LINE0_START_BANNER =
            "*******************************START OF STATEMENT*******************************";

    /** Rule line: 80 hyphens and nothing else. */
    private static final String E_RULE_LINE =
            "--------------------------------------------------------------------------------";

    /** Basic-details heading: 33 spaces, the 13-character heading, ONE pad byte, 33 spaces. */
    private static final String E_ST_LINE6_BASIC_DETAILS_HEADING =
            "                                 Basic Details                                  ";

    /** Transaction-summary heading: 30 spaces, 19 capitals, ONE trailing space, 30 spaces. */
    private static final String E_ST_LINE11_TRANSACTION_SUMMARY_HEADING =
            "                              TRANSACTION SUMMARY                               ";

    /** Column headings: heading plus 9 spaces, heading plus 4 then 35 spaces, TWO spaces plus heading. */
    private static final String E_ST_LINE13_TRANSACTION_COLUMN_HEADINGS =
            "Tran ID         Tran Details                                         Tran Amount";

    /** End banner: 32 asterisks, the 16-byte end text, 32 asterisks. */
    private static final String E_ST_LINE15_END_BANNER =
            "********************************END OF STATEMENT********************************";

    // The nine line groups that carry substituted values. Character fields follow a fixed-width
    // move: a shorter value is padded on the right with spaces and a longer value is truncated on
    // the right, both measured in encoded bytes.

    /** Name line: a 13-character name padded to 75, then the 5-byte filler. */
    private static final String E_ST_LINE1_SHORT_NAME =
            "JOHN Q PUBLIC                                                                   ";

    /** Name line: a name of exactly 75 bytes, unchanged, then the 5-byte filler. */
    private static final String E_ST_LINE1_EXACT_NAME =
            "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX     ";

    /** Name line: a 90-byte name cut on the right to 75, then the 5-byte filler. */
    private static final String E_ST_LINE1_TRUNCATED_NAME =
            "YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY     ";

    /** Name line: an empty name, so the whole record is spaces. */
    private static final String E_ST_LINE1_EMPTY_NAME =
            "                                                                                ";

    /** First address line: a 15-character value padded to 50, then the 30-byte filler. */
    private static final String E_ST_LINE2_ADDRESS_LINE_1 =
            "123 MAIN STREET                                                                 ";

    /** First address line: a 65-byte value cut on the right to 50, then the 30-byte filler. */
    private static final String E_ST_LINE2_TRUNCATED =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA                              ";

    /** Second address line: a 6-character value padded to 50, then the 30-byte filler. */
    private static final String E_ST_LINE3_ADDRESS_LINE_2 =
            "APT 4B                                                                          ";

    /** Second address line: a value of exactly 50 bytes, unchanged, then the 30-byte filler. */
    private static final String E_ST_LINE3_EXACT =
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB                              ";

    /** Third address line: a 20-character value padded across the whole 80-byte field. */
    private static final String E_ST_LINE4_ADDRESS_LINE_3 =
            "SEATTLE WA USA 98101                                                            ";

    /** Third address line: a value of exactly 80 bytes, filling the record with no filler. */
    private static final String E_ST_LINE4_EXACT =
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";

    /** Third address line: a 95-byte value cut on the right to 80. */
    private static final String E_ST_LINE4_TRUNCATED =
            "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";

    /** Account-id line: label with NINE spaces before the colon, an 11-byte value padded to 20, 40 spaces. */
    private static final String E_ST_LINE7_ACCOUNT_ID =
            "Account ID         :00000000011                                                 ";

    /** Account-id line: a value of exactly 20 bytes, unchanged. */
    private static final String E_ST_LINE7_EXACT =
            "Account ID         :11111111111111111111                                        ";

    /** Account-id line: a 30-byte value cut on the right to 20. */
    private static final String E_ST_LINE7_TRUNCATED =
            "Account ID         :99999999999999999999                                        ";

    /** Current-balance line: label with FOUR spaces before the colon, mask A, 7 spaces, 40 spaces, NO dollar sign. */
    private static final String E_ST_LINE8_CURRENT_BALANCE =
            "Current Balance    :000001234.56                                                ";

    /** Current-balance line, negative: mask A ends in a minus. */
    private static final String E_ST_LINE8_NEGATIVE =
            "Current Balance    :000001234.56-                                               ";

    /** Current-balance line, zero: mask A prints nine zeros and the fraction still prints. */
    private static final String E_ST_LINE8_ZERO =
            "Current Balance    :000000000.00                                                ";

    /** Current-balance line at the mask's full capacity: nine significant integer digits. */
    private static final String E_ST_LINE8_MAXIMUM =
            "Current Balance    :999999999.99                                                ";

    /** Credit-score line: label with NINE spaces before the colon, a 3-byte value padded to 20, 40 spaces. */
    private static final String E_ST_LINE9_FICO_SCORE =
            "FICO Score         :789                                                         ";

    /** Transaction line: 16-byte id, one space, 49-byte detail, dollar sign, mask B. */
    private static final String E_ST_LINE14_TRANSACTION =
            "0000000000000001 PURCHASE AT STORE                                $       42.99 ";

    /** Transaction line, negative: mask B ends in a minus. */
    private static final String E_ST_LINE14_NEGATIVE =
            "0000000000000002 RETURN                                           $       42.99-";

    /** Transaction line: a 24-byte id cut to 16 and a 60-byte detail cut to 49. */
    private static final String E_ST_LINE14_TRUNCATED =
            "9999999999999999 DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD$        5.00 ";

    /** Transaction line, zero: mask B suppresses all nine integer positions and still prints the fraction. */
    private static final String E_ST_LINE14_ZERO =
            "0000000000000004 ZERO VALUE                                       $         .00 ";

    /** Total line: the 10-byte label, 56 spaces, dollar sign, mask B. */
    private static final String E_ST_LINE14A_TOTAL =
            "Total EXP:                                                        $     1234.56 ";

    /** Total line, negative: mask B ends in a minus. */
    private static final String E_ST_LINE14A_NEGATIVE =
            "Total EXP:                                                        $     1234.56-";

    /** Total line, zero: mask B suppresses the integer positions and still prints the fraction. */
    private static final String E_ST_LINE14A_ZERO =
            "Total EXP:                                                        $         .00 ";

    // The two 13-character trailing-minus masks. Geometry of both: 9 integer positions, a literal
    // decimal point, 2 fraction positions and a trailing sign position. Mask A prints leading
    // zeros; mask B replaces them with spaces up to but never past the decimal point. Fraction
    // digits are never suppressed by either mask.

    /** Mask A for one point two three: eight zeros, a nine, the point, the fraction, a space. */
    private static final String E_MASK_A_ONE_POINT_TWO_THREE = "000000001.23 ";

    /** Mask B for one point two three: eight spaces where mask A prints eight zeros. */
    private static final String E_MASK_B_ONE_POINT_TWO_THREE = "        1.23 ";

    /** Mask A for one cent: nine printed zeros before the point. */
    private static final String E_MASK_A_ONE_CENT = "000000000.01 ";

    /** Mask B for one cent: nine spaces before the point, the fraction still printed. */
    private static final String E_MASK_B_ONE_CENT = "         .01 ";

    /** Mask A for zero: the fraction always prints, and the trailing sign is a space. */
    private static final String E_MASK_A_ZERO = "000000000.00 ";

    /** Mask B for zero: nine leading spaces, the fraction still prints, trailing space. */
    private static final String E_MASK_B_ZERO = "         .00 ";

    /** Mask A for minus one cent: the trailing sign is a minus. */
    private static final String E_MASK_A_NEGATIVE_ONE_CENT = "000000000.01-";

    /** Mask B for minus one cent: suppressed integer positions and a trailing minus. */
    private static final String E_MASK_B_NEGATIVE_ONE_CENT = "         .01-";

    /** Mask A for minus one thousand two hundred and thirty-four point five six. */
    private static final String E_MASK_A_NEGATIVE = "000001234.56-";

    /** Mask B for the same negative value: five spaces where mask A prints five zeros. */
    private static final String E_MASK_B_NEGATIVE = "     1234.56-";

    /** Mask A at full capacity: nine significant integer digits, nothing to suppress. */
    private static final String E_MASK_A_MAXIMUM = "999999999.99 ";

    /** Mask B at full capacity: identical to mask A, because there is no leading zero to suppress. */
    private static final String E_MASK_B_MAXIMUM = "999999999.99 ";

    /** Mask A at full capacity, negative. */
    private static final String E_MASK_A_MAXIMUM_NEGATIVE = "999999999.99-";

    /** Mask B at full capacity, negative: again identical to mask A. */
    private static final String E_MASK_B_MAXIMUM_NEGATIVE = "999999999.99-";


    // The oracle checks itself first. If a hand-written expectation above has one space too many
    // or too few, this test fails before any comparison against the class under test runs, which
    // is what stops a mistyped expectation from quietly agreeing with a wrong implementation.

    @Test
    @DisplayName("the hand-written oracle is itself well formed: every expected record is eighty "
            + "encoded bytes and every expected mask is thirteen")
    void handWrittenOracleHasTheGeometryItClaims() {
        assertThat(asciiLength(E_ST_LINE0_START_BANNER)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_RULE_LINE)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE6_BASIC_DETAILS_HEADING)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE11_TRANSACTION_SUMMARY_HEADING))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE13_TRANSACTION_COLUMN_HEADINGS))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE15_END_BANNER)).isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(asciiLength(E_ST_LINE1_SHORT_NAME)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE1_EXACT_NAME)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE1_TRUNCATED_NAME)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE1_EMPTY_NAME)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE2_ADDRESS_LINE_1)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE2_TRUNCATED)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE3_ADDRESS_LINE_2)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE3_EXACT)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE4_ADDRESS_LINE_3)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE4_EXACT)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE4_TRUNCATED)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE7_ACCOUNT_ID)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE7_EXACT)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE7_TRUNCATED)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE8_CURRENT_BALANCE)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE8_NEGATIVE)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE8_ZERO)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE8_MAXIMUM)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE9_FICO_SCORE)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE14_TRANSACTION)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE14_NEGATIVE)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE14_TRUNCATED)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE14_ZERO)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE14A_TOTAL)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE14A_NEGATIVE)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(E_ST_LINE14A_ZERO)).isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(asciiLength(E_MASK_A_ONE_POINT_TWO_THREE)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_B_ONE_POINT_TWO_THREE)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_A_ONE_CENT)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_B_ONE_CENT)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_A_ZERO)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_B_ZERO)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_A_NEGATIVE_ONE_CENT)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_B_NEGATIVE_ONE_CENT)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_A_NEGATIVE)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_B_NEGATIVE)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_A_MAXIMUM)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_B_MAXIMUM)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_A_MAXIMUM_NEGATIVE)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(E_MASK_B_MAXIMUM_NEGATIVE)).isEqualTo(EXPECTED_MASK_LENGTH);
    }

    // Record width :: every fixed constant and every builder result is exactly eighty bytes.

    @Test
    @DisplayName("every wholly fixed line group is exactly eighty encoded bytes")
    void everyFixedLineGroupIsExactlyEightyEncodedBytes() {
        assertThat(asciiLength(StatementTextTemplates.ST_LINE0_START_BANNER))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.RULE_LINE))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.ST_LINE5_RULE))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.ST_LINE6_BASIC_DETAILS_HEADING))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.ST_LINE10_RULE))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.ST_LINE11_TRANSACTION_SUMMARY_HEADING))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.ST_LINE12_RULE))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.ST_LINE13_TRANSACTION_COLUMN_HEADINGS))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.ST_LINE15_END_BANNER))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
    }

    @Test
    @DisplayName("every builder returns exactly eighty encoded bytes for representative input")
    void everyBuilderReturnsExactlyEightyEncodedBytes() {
        assertThat(asciiLength(StatementTextTemplates.stLine1CustomerName("JOHN Q PUBLIC")))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.stLine2AddressLine1("123 MAIN STREET")))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.stLine3AddressLine2("APT 4B")))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.stLine4AddressLine3("SEATTLE WA USA 98101")))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.stLine7AccountId("00000000011")))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(
                StatementTextTemplates.stLine8CurrentBalance(new BigDecimal("1234.56"))))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.stLine9FicoScore("789")))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(StatementTextTemplates.stLine14Transaction(
                "0000000000000001", "PURCHASE AT STORE", new BigDecimal("42.99"))))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(
                StatementTextTemplates.stLine14aTotalExpenditure(new BigDecimal("1234.56"))))
                .isEqualTo(EXPECTED_RECORD_LENGTH);
    }

    // TRAP ONE :: the two banners use different asterisk splits.

    @Test
    @DisplayName("the start banner is thirty-one asterisks, an eighteen-byte text and thirty-one "
            + "asterisks")
    void startBannerHasThirtyOneAsterisksEitherSideOfAnEighteenByteText() {
        byte[] banner = asciiBytes(StatementTextTemplates.ST_LINE0_START_BANNER);

        assertThat(banner).isEqualTo(asciiBytes(E_ST_LINE0_START_BANNER));
        assertThat(banner).hasSize(EXPECTED_RECORD_LENGTH);

        int leadingAsterisks = leadingRun(banner, ASCII_ASTERISK_BYTE);
        int trailingAsterisks = trailingRun(banner, ASCII_ASTERISK_BYTE);
        assertThat(leadingAsterisks).isEqualTo(EXPECTED_START_BANNER_ASTERISK_RUN);
        assertThat(trailingAsterisks).isEqualTo(EXPECTED_START_BANNER_ASTERISK_RUN);
        assertThat(banner.length - leadingAsterisks - trailingAsterisks)
                .isEqualTo(EXPECTED_START_BANNER_TEXT_WIDTH);
        assertThat(slice(banner, leadingAsterisks, EXPECTED_START_BANNER_TEXT_WIDTH))
                .isEqualTo(asciiBytes("START OF STATEMENT"));
    }

    @Test
    @DisplayName("the end banner is thirty-two asterisks, a sixteen-byte text and thirty-two "
            + "asterisks")
    void endBannerHasThirtyTwoAsterisksEitherSideOfASixteenByteText() {
        byte[] banner = asciiBytes(StatementTextTemplates.ST_LINE15_END_BANNER);

        assertThat(banner).isEqualTo(asciiBytes(E_ST_LINE15_END_BANNER));
        assertThat(banner).hasSize(EXPECTED_RECORD_LENGTH);

        int leadingAsterisks = leadingRun(banner, ASCII_ASTERISK_BYTE);
        int trailingAsterisks = trailingRun(banner, ASCII_ASTERISK_BYTE);
        assertThat(leadingAsterisks).isEqualTo(EXPECTED_END_BANNER_ASTERISK_RUN);
        assertThat(trailingAsterisks).isEqualTo(EXPECTED_END_BANNER_ASTERISK_RUN);
        assertThat(banner.length - leadingAsterisks - trailingAsterisks)
                .isEqualTo(EXPECTED_END_BANNER_TEXT_WIDTH);
        assertThat(slice(banner, leadingAsterisks, EXPECTED_END_BANNER_TEXT_WIDTH))
                .isEqualTo(asciiBytes("END OF STATEMENT"));
    }

    @Test
    @DisplayName("the two banners are deliberately not the same split: thirty-one against "
            + "thirty-two asterisks per side, and the asymmetry must never be unified, corrected "
            + "or computed from one another")
    void theTwoBannersAreNotEqualAndTheirAsteriskRunsDiffer() {
        byte[] startBanner = asciiBytes(StatementTextTemplates.ST_LINE0_START_BANNER);
        byte[] endBanner = asciiBytes(StatementTextTemplates.ST_LINE15_END_BANNER);

        assertThat(StatementTextTemplates.ST_LINE0_START_BANNER)
                .isNotEqualTo(StatementTextTemplates.ST_LINE15_END_BANNER);
        assertThat(startBanner).isNotEqualTo(endBanner);

        assertThat(leadingRun(startBanner, ASCII_ASTERISK_BYTE))
                .isNotEqualTo(leadingRun(endBanner, ASCII_ASTERISK_BYTE));
        assertThat(StatementTextTemplates.ST_LINE0_ASTERISK_WIDTH)
                .isEqualTo(EXPECTED_START_BANNER_ASTERISK_RUN);
        assertThat(StatementTextTemplates.ST_LINE15_ASTERISK_WIDTH)
                .isEqualTo(EXPECTED_END_BANNER_ASTERISK_RUN);
        assertThat(StatementTextTemplates.ST_LINE0_ASTERISK_WIDTH)
                .isNotEqualTo(StatementTextTemplates.ST_LINE15_ASTERISK_WIDTH);

        // Both splits nevertheless total the record width, which is why neither is a defect.
        assertThat(EXPECTED_START_BANNER_ASTERISK_RUN * 2 + EXPECTED_START_BANNER_TEXT_WIDTH)
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(EXPECTED_END_BANNER_ASTERISK_RUN * 2 + EXPECTED_END_BANNER_TEXT_WIDTH)
                .isEqualTo(EXPECTED_RECORD_LENGTH);
    }

    // The rule line :: one constant, three emitted positions.

    @Test
    @DisplayName("the rule line is exactly eighty hyphens and nothing else")
    void ruleLineIsExactlyEightyHyphens() {
        byte[] rule = asciiBytes(StatementTextTemplates.RULE_LINE);

        assertThat(rule).isEqualTo(asciiBytes(E_RULE_LINE));
        assertThat(rule).hasSize(EXPECTED_RECORD_LENGTH);
        assertThat(leadingRun(rule, ASCII_HYPHEN_BYTE)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(StatementTextTemplates.RULE_LINE_WIDTH).isEqualTo(EXPECTED_RECORD_LENGTH);
    }

    @Test
    @DisplayName("the eighty-hyphen rule line is a single constant, referenced three times by the "
            + "caller for the three positions the legacy program emits it at")
    void ruleLineIsOneConstantReferencedByThreePositionNames() {
        assertThat(StatementTextTemplates.ST_LINE5_RULE)
                .isSameAs(StatementTextTemplates.RULE_LINE);
        assertThat(StatementTextTemplates.ST_LINE10_RULE)
                .isSameAs(StatementTextTemplates.RULE_LINE);
        assertThat(StatementTextTemplates.ST_LINE12_RULE)
                .isSameAs(StatementTextTemplates.RULE_LINE);

        assertThat(asciiBytes(StatementTextTemplates.ST_LINE5_RULE))
                .isEqualTo(asciiBytes(E_RULE_LINE));
        assertThat(asciiBytes(StatementTextTemplates.ST_LINE10_RULE))
                .isEqualTo(asciiBytes(E_RULE_LINE));
        assertThat(asciiBytes(StatementTextTemplates.ST_LINE12_RULE))
                .isEqualTo(asciiBytes(E_RULE_LINE));
    }


    // TRAP TWO :: the pad counts inside the fixed captions are contractual content.

    @Test
    @DisplayName("the basic-details heading is thirty-three spaces, a fourteen-byte field holding a "
            + "thirteen-character heading with exactly ONE trailing pad byte, and thirty-three "
            + "spaces")
    void basicDetailsHeadingCarriesExactlyOnePadByteInsideItsFourteenByteField() {
        byte[] heading = asciiBytes(StatementTextTemplates.ST_LINE6_BASIC_DETAILS_HEADING);

        assertThat(heading).isEqualTo(asciiBytes(E_ST_LINE6_BASIC_DETAILS_HEADING));
        assertThat(heading).hasSize(EXPECTED_RECORD_LENGTH);

        int leadingSpaces = leadingRun(heading, ASCII_SPACE_BYTE);
        assertThat(leadingSpaces).isEqualTo(33);
        assertThat(slice(heading, leadingSpaces, 13)).isEqualTo(asciiBytes("Basic Details"));

        // Thirty-three declared trailing spaces plus the single pad byte of the fourteen-byte field.
        assertThat(trailingRun(heading, ASCII_SPACE_BYTE)).isEqualTo(34);
        assertThat(trailingRun(heading, ASCII_SPACE_BYTE) - 33).isEqualTo(1);
        assertThat(StatementTextTemplates.ST_LINE6_TEXT_WIDTH).isEqualTo(14);
        assertThat(StatementTextTemplates.ST_LINE6_LEADING_FILLER_WIDTH).isEqualTo(33);
        assertThat(StatementTextTemplates.ST_LINE6_TRAILING_FILLER_WIDTH).isEqualTo(33);
    }

    @Test
    @DisplayName("the transaction-summary heading is thirty spaces, a twenty-byte field holding "
            + "nineteen capitals followed by its own single trailing space, and thirty spaces")
    void transactionSummaryHeadingCarriesNineteenCapitalsAndOneTrailingSpace() {
        byte[] heading = asciiBytes(StatementTextTemplates.ST_LINE11_TRANSACTION_SUMMARY_HEADING);

        assertThat(heading).isEqualTo(asciiBytes(E_ST_LINE11_TRANSACTION_SUMMARY_HEADING));
        assertThat(heading).hasSize(EXPECTED_RECORD_LENGTH);

        int leadingSpaces = leadingRun(heading, ASCII_SPACE_BYTE);
        assertThat(leadingSpaces).isEqualTo(30);
        assertThat(slice(heading, leadingSpaces, 19)).isEqualTo(asciiBytes("TRANSACTION SUMMARY"));

        // Thirty declared trailing spaces plus the one trailing space carried by the literal itself.
        assertThat(trailingRun(heading, ASCII_SPACE_BYTE)).isEqualTo(31);
        assertThat(trailingRun(heading, ASCII_SPACE_BYTE) - 30).isEqualTo(1);
        assertThat(StatementTextTemplates.ST_LINE11_TEXT_WIDTH).isEqualTo(20);
        assertThat(StatementTextTemplates.ST_LINE11_LEADING_FILLER_WIDTH).isEqualTo(30);
        assertThat(StatementTextTemplates.ST_LINE11_TRAILING_FILLER_WIDTH).isEqualTo(30);
    }

    @Test
    @DisplayName("the column headings are a sixteen-byte caption, a fifty-one-byte caption whose "
            + "sixteen-character literal is followed by thirty-five pad spaces, and a "
            + "thirteen-byte caption with TWO leading spaces")
    void columnHeadingsCarryThirtyFivePadSpacesAndTwoLeadingSpaces() {
        byte[] headings = asciiBytes(StatementTextTemplates.ST_LINE13_TRANSACTION_COLUMN_HEADINGS);

        assertThat(headings).isEqualTo(asciiBytes(E_ST_LINE13_TRANSACTION_COLUMN_HEADINGS));
        assertThat(headings).hasSize(EXPECTED_RECORD_LENGTH);

        // First caption: the seven-character heading and its nine trailing spaces, sixteen bytes.
        assertThat(slice(headings, 0, 7)).isEqualTo(asciiBytes("Tran ID"));
        assertThat(runLength(headings, 7, ASCII_SPACE_BYTE)).isEqualTo(9);

        // Second caption, sixteen through sixty-six: a sixteen-character literal, being the
        // twelve-character heading and four spaces, padded out to fifty-one bytes. Those four
        // declared spaces plus the thirty-five pad bytes give thirty-nine spaces to the field end.
        assertThat(slice(headings, 16, 12)).isEqualTo(asciiBytes("Tran Details"));
        assertAllSpaces(headings, 28, 4);
        assertAllSpaces(headings, 32, 35);

        // Third caption, sixty-seven through seventy-nine: exactly TWO leading spaces before the
        // eleven-character heading.
        assertAllSpaces(headings, 67, 2);
        assertThat(headings[69]).isNotEqualTo(ASCII_SPACE_BYTE);
        assertThat(slice(headings, 69, 11)).isEqualTo(asciiBytes("Tran Amount"));

        // The space run does not stop at the field boundary: the thirty-nine spaces closing the
        // second caption and the two opening the third are contiguous in the record image. That is
        // precisely why the pad counts are asserted per field above rather than as one run.
        assertThat(runLength(headings, 28, ASCII_SPACE_BYTE)).isEqualTo(41);

        assertThat(StatementTextTemplates.ST_LINE13_TRAN_ID_WIDTH).isEqualTo(16);
        assertThat(StatementTextTemplates.ST_LINE13_TRAN_DETAILS_WIDTH).isEqualTo(51);
        assertThat(StatementTextTemplates.ST_LINE13_TRAN_AMOUNT_WIDTH).isEqualTo(13);
    }

    // The value-carrying line groups.

    @Test
    @DisplayName("the name line is a seventy-five-byte name field followed by a five-byte filler")
    void nameLineIsSeventyFiveByteNameThenFiveSpaces() {
        byte[] line = asciiBytes(StatementTextTemplates.stLine1CustomerName("JOHN Q PUBLIC"));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE1_SHORT_NAME));
        assertThat(slice(line, 0, 13)).isEqualTo(asciiBytes("JOHN Q PUBLIC"));
        assertThat(runLength(line, 13, ASCII_SPACE_BYTE)).isEqualTo(67);
        assertThat(StatementTextTemplates.ST_LINE1_NAME_WIDTH).isEqualTo(75);
        assertThat(StatementTextTemplates.ST_LINE1_FILLER_WIDTH).isEqualTo(5);
    }

    @Test
    @DisplayName("the first address line is a fifty-byte address field followed by a thirty-byte "
            + "filler")
    void firstAddressLineIsFiftyByteAddressThenThirtySpaces() {
        byte[] line = asciiBytes(StatementTextTemplates.stLine2AddressLine1("123 MAIN STREET"));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE2_ADDRESS_LINE_1));
        assertThat(slice(line, 0, 15)).isEqualTo(asciiBytes("123 MAIN STREET"));
        assertThat(runLength(line, 15, ASCII_SPACE_BYTE)).isEqualTo(65);
        assertThat(StatementTextTemplates.ST_LINE2_ADDRESS_WIDTH).isEqualTo(50);
        assertThat(StatementTextTemplates.ST_LINE2_FILLER_WIDTH).isEqualTo(30);
    }

    @Test
    @DisplayName("the second address line is a fifty-byte address field followed by a thirty-byte "
            + "filler, declared separately from the first even though the composition matches")
    void secondAddressLineIsFiftyByteAddressThenThirtySpaces() {
        byte[] line = asciiBytes(StatementTextTemplates.stLine3AddressLine2("APT 4B"));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE3_ADDRESS_LINE_2));
        assertThat(slice(line, 0, 6)).isEqualTo(asciiBytes("APT 4B"));
        assertThat(runLength(line, 6, ASCII_SPACE_BYTE)).isEqualTo(74);
        assertThat(StatementTextTemplates.ST_LINE3_ADDRESS_WIDTH).isEqualTo(50);
        assertThat(StatementTextTemplates.ST_LINE3_FILLER_WIDTH).isEqualTo(30);
    }

    @Test
    @DisplayName("the third address line spans the whole eighty-byte record with no filler")
    void thirdAddressLineSpansTheWholeRecord() {
        byte[] line = asciiBytes(StatementTextTemplates.stLine4AddressLine3("SEATTLE WA USA 98101"));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE4_ADDRESS_LINE_3));
        assertThat(slice(line, 0, 20)).isEqualTo(asciiBytes("SEATTLE WA USA 98101"));
        assertThat(runLength(line, 20, ASCII_SPACE_BYTE)).isEqualTo(60);
        assertThat(StatementTextTemplates.ST_LINE4_ADDRESS_WIDTH).isEqualTo(EXPECTED_RECORD_LENGTH);
    }

    @Test
    @DisplayName("the account-id line has NINE spaces between the label and its colon, then a "
            + "twenty-byte value field, then forty spaces")
    void accountIdLineHasNineSpacesBeforeItsColon() {
        byte[] line = asciiBytes(StatementTextTemplates.stLine7AccountId("00000000011"));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE7_ACCOUNT_ID));
        assertThat(slice(line, 0, 20)).isEqualTo(asciiBytes("Account ID         :"));
        assertThat(slice(line, 0, 10)).isEqualTo(asciiBytes("Account ID"));
        assertThat(runLength(line, 10, ASCII_SPACE_BYTE)).isEqualTo(9);
        assertThat(line[19]).isEqualTo(ASCII_COLON_BYTE);
        assertThat(slice(line, 20, 11)).isEqualTo(asciiBytes("00000000011"));

        // Nine pad bytes of the twenty-byte value field, then the forty-byte filler.
        assertThat(trailingRun(line, ASCII_SPACE_BYTE)).isEqualTo(49);
        assertThat(StatementTextTemplates.ST_LINE7_LABEL_WIDTH).isEqualTo(20);
        assertThat(StatementTextTemplates.ST_LINE7_ACCOUNT_ID_WIDTH).isEqualTo(20);
        assertThat(StatementTextTemplates.ST_LINE7_FILLER_WIDTH).isEqualTo(40);
    }

    @Test
    @DisplayName("the current-balance line has FOUR spaces before its colon, renders mask A, then "
            + "a seven-byte and a forty-byte filler, and carries NO dollar sign")
    void currentBalanceLineHasFourSpacesBeforeItsColonAndNoCurrencySymbol() {
        byte[] line = asciiBytes(
                StatementTextTemplates.stLine8CurrentBalance(new BigDecimal("1234.56")));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE8_CURRENT_BALANCE));
        assertThat(slice(line, 0, 20)).isEqualTo(asciiBytes("Current Balance    :"));
        assertThat(slice(line, 0, 15)).isEqualTo(asciiBytes("Current Balance"));
        assertThat(runLength(line, 15, ASCII_SPACE_BYTE)).isEqualTo(4);
        assertThat(line[19]).isEqualTo(ASCII_COLON_BYTE);

        // Mask A occupies the thirteen bytes that follow the label, leading zeros printed.
        assertThat(slice(line, 20, EXPECTED_MASK_LENGTH)).isEqualTo(asciiBytes("000001234.56 "));

        // The currency symbol is deliberately absent from this line and present on the other two.
        assertThat(containsByte(line, ASCII_DOLLAR_BYTE)).isFalse();

        // The mask's trailing sign space, then the seven-byte filler, then the forty-byte filler.
        assertThat(trailingRun(line, ASCII_SPACE_BYTE)).isEqualTo(48);
        assertThat(StatementTextTemplates.ST_LINE8_LABEL_WIDTH).isEqualTo(20);
        assertThat(StatementTextTemplates.ST_LINE8_FILLER_1_WIDTH).isEqualTo(7);
        assertThat(StatementTextTemplates.ST_LINE8_FILLER_2_WIDTH).isEqualTo(40);
    }

    @Test
    @DisplayName("the credit-score line has NINE spaces between the label and its colon, then a "
            + "twenty-byte value field, then forty spaces")
    void creditScoreLineHasNineSpacesBeforeItsColon() {
        byte[] line = asciiBytes(StatementTextTemplates.stLine9FicoScore("789"));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE9_FICO_SCORE));
        assertThat(slice(line, 0, 20)).isEqualTo(asciiBytes("FICO Score         :"));
        assertThat(slice(line, 0, 10)).isEqualTo(asciiBytes("FICO Score"));
        assertThat(runLength(line, 10, ASCII_SPACE_BYTE)).isEqualTo(9);
        assertThat(line[19]).isEqualTo(ASCII_COLON_BYTE);
        assertThat(slice(line, 20, 3)).isEqualTo(asciiBytes("789"));

        // Seventeen pad bytes of the twenty-byte value field, then the forty-byte filler.
        assertThat(trailingRun(line, ASCII_SPACE_BYTE)).isEqualTo(57);
        assertThat(StatementTextTemplates.ST_LINE9_LABEL_WIDTH).isEqualTo(20);
        assertThat(StatementTextTemplates.ST_LINE9_FICO_SCORE_WIDTH).isEqualTo(20);
        assertThat(StatementTextTemplates.ST_LINE9_FILLER_WIDTH).isEqualTo(40);
    }

    @Test
    @DisplayName("the transaction line is a sixteen-byte id, one space, a forty-nine-byte detail, "
            + "a dollar sign and mask B")
    void transactionLineIsIdSpaceDetailDollarAndMaskB() {
        byte[] line = asciiBytes(StatementTextTemplates.stLine14Transaction(
                "0000000000000001", "PURCHASE AT STORE", new BigDecimal("42.99")));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE14_TRANSACTION));
        assertThat(slice(line, 0, 16)).isEqualTo(asciiBytes("0000000000000001"));
        assertThat(line[16]).isEqualTo(ASCII_SPACE_BYTE);
        assertThat(slice(line, 17, 17)).isEqualTo(asciiBytes("PURCHASE AT STORE"));

        // Thirty-two pad bytes complete the forty-nine-byte detail field.
        assertThat(runLength(line, 34, ASCII_SPACE_BYTE)).isEqualTo(32);
        assertThat(line[66]).isEqualTo(ASCII_DOLLAR_BYTE);
        assertThat(containsByte(line, ASCII_DOLLAR_BYTE)).isTrue();

        // Mask B occupies the final thirteen bytes, leading zeros suppressed to spaces.
        assertThat(slice(line, 67, EXPECTED_MASK_LENGTH)).isEqualTo(asciiBytes("       42.99 "));

        assertThat(StatementTextTemplates.ST_LINE14_TRAN_ID_WIDTH).isEqualTo(16);
        assertThat(StatementTextTemplates.ST_LINE14_SEPARATOR_WIDTH).isEqualTo(1);
        assertThat(StatementTextTemplates.ST_LINE14_TRAN_DETAIL_WIDTH).isEqualTo(49);
        assertThat(StatementTextTemplates.ST_LINE14_CURRENCY_WIDTH).isEqualTo(1);
    }

    @Test
    @DisplayName("the total line is a ten-byte label, fifty-six spaces, a dollar sign and mask B")
    void totalLineIsLabelFiftySixSpacesDollarAndMaskB() {
        byte[] line = asciiBytes(
                StatementTextTemplates.stLine14aTotalExpenditure(new BigDecimal("1234.56")));

        assertThat(line).isEqualTo(asciiBytes(E_ST_LINE14A_TOTAL));
        assertThat(slice(line, 0, 10)).isEqualTo(asciiBytes("Total EXP:"));
        assertThat(line[9]).isEqualTo(ASCII_COLON_BYTE);
        assertThat(runLength(line, 10, ASCII_SPACE_BYTE)).isEqualTo(56);
        assertThat(line[66]).isEqualTo(ASCII_DOLLAR_BYTE);
        assertThat(containsByte(line, ASCII_DOLLAR_BYTE)).isTrue();

        // Mask B occupies the final thirteen bytes, leading zeros suppressed to spaces.
        assertThat(slice(line, 67, EXPECTED_MASK_LENGTH)).isEqualTo(asciiBytes("     1234.56 "));

        assertThat(StatementTextTemplates.ST_LINE14A_LABEL_WIDTH).isEqualTo(10);
        assertThat(StatementTextTemplates.ST_LINE14A_FILLER_WIDTH).isEqualTo(56);
        assertThat(StatementTextTemplates.ST_LINE14A_CURRENCY_WIDTH).isEqualTo(1);
    }


    // TRAP THREE :: two thirteen-character trailing-minus masks, differing only in whether the
    // leading integer positions are suppressed. Each is exercised through its own name; neither
    // is selected here by a flag, and the two are never merged.

    @Test
    @DisplayName("mask A does NOT suppress leading zeros: they are printed as zero characters")
    void maskAPrintsLeadingZeros() {
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("1.23"))))
                .isEqualTo(asciiBytes(E_MASK_A_ONE_POINT_TWO_THREE));
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("0.01"))))
                .isEqualTo(asciiBytes(E_MASK_A_ONE_CENT));
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("-1234.56"))))
                .isEqualTo(asciiBytes(E_MASK_A_NEGATIVE));
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("999999999.99"))))
                .isEqualTo(asciiBytes(E_MASK_A_MAXIMUM));
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("-999999999.99"))))
                .isEqualTo(asciiBytes(E_MASK_A_MAXIMUM_NEGATIVE));

        // No integer position of mask A is ever blank, whatever the magnitude.
        byte[] oneCent = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("0.01")));
        assertThat(runLength(oneCent, 0, ASCII_SPACE_BYTE)).isEqualTo(0);
        assertThat(oneCent[9]).isEqualTo(ASCII_DECIMAL_POINT_BYTE);
    }

    @Test
    @DisplayName("mask B DOES suppress leading zeros: the leading integer positions become spaces "
            + "up to but never past the decimal point")
    void maskBSuppressesLeadingZeros() {
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(new BigDecimal("1.23"))))
                .isEqualTo(asciiBytes(E_MASK_B_ONE_POINT_TWO_THREE));
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(new BigDecimal("0.01"))))
                .isEqualTo(asciiBytes(E_MASK_B_ONE_CENT));
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(
                        new BigDecimal("-1234.56"))))
                .isEqualTo(asciiBytes(E_MASK_B_NEGATIVE));
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(
                        new BigDecimal("999999999.99"))))
                .isEqualTo(asciiBytes(E_MASK_B_MAXIMUM));
        assertThat(asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(
                        new BigDecimal("-999999999.99"))))
                .isEqualTo(asciiBytes(E_MASK_B_MAXIMUM_NEGATIVE));

        // A value below one blanks all nine integer positions, and suppression stops at the point.
        byte[] oneCent = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(new BigDecimal("0.01")));
        assertThat(runLength(oneCent, 0, ASCII_SPACE_BYTE)).isEqualTo(9);
        assertThat(oneCent[9]).isEqualTo(ASCII_DECIMAL_POINT_BYTE);
    }

    @Test
    @DisplayName("the two masks produce DIFFERENT output for the same zero-suppressible value: "
            + "picking the wrong one is a silent byte-parity failure no compiler can catch, which "
            + "is why each has its own name and they are never merged behind a flag")
    void theTwoMasksDifferForTheSameZeroSuppressibleValue() {
        BigDecimal zeroSuppressible = new BigDecimal("1.23");

        String withoutSuppression =
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(zeroSuppressible);
        String withSuppression =
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(zeroSuppressible);

        assertThat(asciiBytes(withoutSuppression)).isEqualTo(asciiBytes(E_MASK_A_ONE_POINT_TWO_THREE));
        assertThat(asciiBytes(withSuppression)).isEqualTo(asciiBytes(E_MASK_B_ONE_POINT_TWO_THREE));
        assertThat(withoutSuppression).isNotEqualTo(withSuppression);
        assertThat(asciiBytes(withoutSuppression)).isNotEqualTo(asciiBytes(withSuppression));

        // Both are nevertheless the same width, so the difference is invisible to a length check.
        assertThat(asciiLength(withoutSuppression)).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(asciiLength(withSuppression)).isEqualTo(EXPECTED_MASK_LENGTH);
    }

    @Test
    @DisplayName("the trailing sign of a negative value is a minus under both masks")
    void negativeValuesEndInAMinusUnderBothMasks() {
        byte[] maskA = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("-0.01")));
        byte[] maskB = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(new BigDecimal("-0.01")));

        assertThat(maskA).isEqualTo(asciiBytes(E_MASK_A_NEGATIVE_ONE_CENT));
        assertThat(maskB).isEqualTo(asciiBytes(E_MASK_B_NEGATIVE_ONE_CENT));
        assertThat(maskA[EXPECTED_MASK_LENGTH - 1]).isEqualTo(ASCII_HYPHEN_BYTE);
        assertThat(maskB[EXPECTED_MASK_LENGTH - 1]).isEqualTo(ASCII_HYPHEN_BYTE);

        // The same trailing minus appears at the end of each line group that carries an amount.
        assertThat(asciiBytes(
                StatementTextTemplates.stLine8CurrentBalance(new BigDecimal("-1234.56"))))
                .isEqualTo(asciiBytes(E_ST_LINE8_NEGATIVE));
        assertThat(asciiBytes(StatementTextTemplates.stLine14Transaction(
                "0000000000000002", "RETURN", new BigDecimal("-42.99"))))
                .isEqualTo(asciiBytes(E_ST_LINE14_NEGATIVE));
        assertThat(asciiBytes(
                StatementTextTemplates.stLine14aTotalExpenditure(new BigDecimal("-1234.56"))))
                .isEqualTo(asciiBytes(E_ST_LINE14A_NEGATIVE));
    }

    @Test
    @DisplayName("the trailing sign of a non-negative value is a SPACE and never a plus, under "
            + "both masks")
    void nonNegativeValuesEndInASpaceAndNeverAPlusUnderBothMasks() {
        byte[] maskAPositive = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("1.23")));
        byte[] maskBPositive = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(new BigDecimal("1.23")));
        byte[] maskAZero = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("0.00")));
        byte[] maskBZero = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(new BigDecimal("0.00")));

        assertThat(maskAPositive[EXPECTED_MASK_LENGTH - 1]).isEqualTo(ASCII_SPACE_BYTE);
        assertThat(maskBPositive[EXPECTED_MASK_LENGTH - 1]).isEqualTo(ASCII_SPACE_BYTE);
        assertThat(maskAZero[EXPECTED_MASK_LENGTH - 1]).isEqualTo(ASCII_SPACE_BYTE);
        assertThat(maskBZero[EXPECTED_MASK_LENGTH - 1]).isEqualTo(ASCII_SPACE_BYTE);

        assertThat(containsByte(maskAPositive, ASCII_PLUS_BYTE)).isFalse();
        assertThat(containsByte(maskBPositive, ASCII_PLUS_BYTE)).isFalse();
        assertThat(containsByte(maskAZero, ASCII_PLUS_BYTE)).isFalse();
        assertThat(containsByte(maskBZero, ASCII_PLUS_BYTE)).isFalse();

        // No plus sign appears anywhere in any fixed constant or any assembled record either.
        assertNoPlusSign(StatementTextTemplates.ST_LINE0_START_BANNER);
        assertNoPlusSign(StatementTextTemplates.RULE_LINE);
        assertNoPlusSign(StatementTextTemplates.ST_LINE6_BASIC_DETAILS_HEADING);
        assertNoPlusSign(StatementTextTemplates.ST_LINE11_TRANSACTION_SUMMARY_HEADING);
        assertNoPlusSign(StatementTextTemplates.ST_LINE13_TRANSACTION_COLUMN_HEADINGS);
        assertNoPlusSign(StatementTextTemplates.ST_LINE15_END_BANNER);
        assertNoPlusSign(StatementTextTemplates.stLine8CurrentBalance(new BigDecimal("1234.56")));
        assertNoPlusSign(StatementTextTemplates.stLine8CurrentBalance(new BigDecimal("-1234.56")));
        assertNoPlusSign(StatementTextTemplates.stLine14aTotalExpenditure(new BigDecimal("1234.56")));
        assertNoPlusSign(
                StatementTextTemplates.stLine14aTotalExpenditure(new BigDecimal("-1234.56")));
    }

    @Test
    @DisplayName("a zero value always prints its decimal point and two fraction digits: a "
            + "blank-when-zero clause appears nowhere in the legacy estate, so a zero amount is "
            + "never rendered as an empty field")
    void zeroAlwaysPrintsTheDecimalPointAndFraction() {
        byte[] maskAZero = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("0.00")));
        byte[] maskBZero = asciiBytes(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(new BigDecimal("0.00")));

        assertThat(maskAZero).isEqualTo(asciiBytes(E_MASK_A_ZERO));
        assertThat(maskBZero).isEqualTo(asciiBytes(E_MASK_B_ZERO));

        // The point and both fraction digits are present under both masks.
        assertThat(maskAZero[9]).isEqualTo(ASCII_DECIMAL_POINT_BYTE);
        assertThat(maskBZero[9]).isEqualTo(ASCII_DECIMAL_POINT_BYTE);
        assertThat(slice(maskAZero, 9, 3)).isEqualTo(asciiBytes(".00"));
        assertThat(slice(maskBZero, 9, 3)).isEqualTo(asciiBytes(".00"));

        // And on the line groups that carry an amount.
        assertThat(asciiBytes(StatementTextTemplates.stLine8CurrentBalance(new BigDecimal("0.00"))))
                .isEqualTo(asciiBytes(E_ST_LINE8_ZERO));
        assertThat(asciiBytes(StatementTextTemplates.stLine14Transaction(
                "0000000000000004", "ZERO VALUE", new BigDecimal("0.00"))))
                .isEqualTo(asciiBytes(E_ST_LINE14_ZERO));
        assertThat(asciiBytes(
                StatementTextTemplates.stLine14aTotalExpenditure(new BigDecimal("0.00"))))
                .isEqualTo(asciiBytes(E_ST_LINE14A_ZERO));
    }

    @Test
    @DisplayName("a value at the mask's full nine-digit capacity renders every integer position, "
            + "and at that magnitude the two masks coincide because nothing is left to suppress")
    void fullCapacityValueFillsEveryIntegerPosition() {
        BigDecimal fullCapacity = new BigDecimal("999999999.99");

        String withoutSuppression =
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(fullCapacity);
        String withSuppression =
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(fullCapacity);

        assertThat(asciiBytes(withoutSuppression)).isEqualTo(asciiBytes(E_MASK_A_MAXIMUM));
        assertThat(asciiBytes(withSuppression)).isEqualTo(asciiBytes(E_MASK_B_MAXIMUM));

        // At this magnitude there is no leading zero left to suppress, so the two masks coincide.
        // That coincidence is exactly why a masks-differ assertion must use a small value instead.
        assertThat(asciiBytes(withoutSuppression)).isEqualTo(asciiBytes(withSuppression));

        assertThat(asciiBytes(StatementTextTemplates.stLine8CurrentBalance(fullCapacity)))
                .isEqualTo(asciiBytes(E_ST_LINE8_MAXIMUM));
        assertThat(runLength(asciiBytes(withSuppression), 0, ASCII_SPACE_BYTE)).isEqualTo(0);
    }

    // The declared geometry constants.

    @Test
    @DisplayName("the record width is eighty, the line group count is seventeen and the mask width "
            + "is thirteen")
    void declaredGeometryConstantsHaveTheVerifiedValues() {
        assertThat(StatementTextTemplates.STATEMENT_RECORD_LENGTH)
                .isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(StatementTextTemplates.STATEMENT_LINE_GROUP_COUNT)
                .isEqualTo(EXPECTED_LINE_GROUP_COUNT);
        assertThat(StatementTextTemplates.AMOUNT_MASK_LENGTH).isEqualTo(EXPECTED_MASK_LENGTH);
        assertThat(StatementTextTemplates.AMOUNT_MASK_INTEGER_DIGITS).isEqualTo(9);
        assertThat(StatementTextTemplates.AMOUNT_MASK_FRACTION_DIGITS).isEqualTo(2);
        assertThat(StatementTextTemplates.REQUIRED_AMOUNT_SCALE).isEqualTo(2);

        // Nine integer positions, one decimal point, two fraction positions, one trailing sign.
        assertThat(StatementTextTemplates.AMOUNT_MASK_INTEGER_DIGITS
                + 1
                + StatementTextTemplates.AMOUNT_MASK_FRACTION_DIGITS
                + 1)
                .isEqualTo(EXPECTED_MASK_LENGTH);
    }

    @Test
    @DisplayName("every component width of the two banners is declared, and the start banner text "
            + "is two bytes wider than the end banner text")
    void bannerComponentWidthsAreDeclared() {
        assertThat(StatementTextTemplates.ST_LINE0_ASTERISK_WIDTH).isEqualTo(31);
        assertThat(StatementTextTemplates.ST_LINE0_TEXT_WIDTH).isEqualTo(18);
        assertThat(StatementTextTemplates.ST_LINE15_ASTERISK_WIDTH).isEqualTo(32);
        assertThat(StatementTextTemplates.ST_LINE15_TEXT_WIDTH).isEqualTo(16);
        assertThat(StatementTextTemplates.ST_LINE0_TEXT_WIDTH
                - StatementTextTemplates.ST_LINE15_TEXT_WIDTH)
                .isEqualTo(2);
        assertThat(StatementTextTemplates.ST_LINE15_ASTERISK_WIDTH
                - StatementTextTemplates.ST_LINE0_ASTERISK_WIDTH)
                .isEqualTo(1);
    }


    // The value contract: amounts arrive already at scale two with truncation toward zero already
    // applied upstream, because the estate declares no rounding clause anywhere. Nothing here
    // re-scales, selects a rounding mode or performs arithmetic; a value at any other scale is a
    // caller defect and is rejected so that a truncation-policy violation cannot hide inside
    // formatting.

    @Test
    @DisplayName("a value at scale zero is rejected, and the rejection names both the expected and "
            + "the actual scale")
    void scaleZeroIsRejectedNamingExpectedAndActualScale() {
        BigDecimal scaleZero = new BigDecimal("1");
        assertThat(scaleZero.scale()).isEqualTo(0);

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(scaleZero))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must arrive at scale 2")
                .hasMessageContaining("the actual scale was 0")
                .hasMessageContaining("9(9).99-");

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithZeroSuppression(scaleZero))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must arrive at scale 2")
                .hasMessageContaining("the actual scale was 0")
                .hasMessageContaining("Z(9).99-");
    }

    @Test
    @DisplayName("a value at scale one is rejected rather than padded out to scale two")
    void scaleOneIsRejected() {
        BigDecimal scaleOne = new BigDecimal("1.5");
        assertThat(scaleOne.scale()).isEqualTo(1);

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(scaleOne))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must arrive at scale 2")
                .hasMessageContaining("the actual scale was 1");

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithZeroSuppression(scaleOne))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the actual scale was 1");
    }

    @Test
    @DisplayName("a value at scale three is rejected rather than quietly truncated down to scale "
            + "two inside the formatter")
    void scaleThreeIsRejected() {
        BigDecimal scaleThree = new BigDecimal("1.500");
        assertThat(scaleThree.scale()).isEqualTo(3);

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(scaleThree))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must arrive at scale 2")
                .hasMessageContaining("the actual scale was 3");

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithZeroSuppression(scaleThree))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the actual scale was 3");
    }

    @Test
    @DisplayName("a wrong scale is rejected through the line builders too, not only through the "
            + "mask formatters")
    void wrongScaleIsRejectedThroughTheLineBuilders() {
        BigDecimal scaleZero = new BigDecimal("42");

        assertThatThrownBy(() -> StatementTextTemplates.stLine8CurrentBalance(scaleZero))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must arrive at scale 2");

        assertThatThrownBy(() -> StatementTextTemplates.stLine14Transaction(
                "0000000000000001", "PURCHASE AT STORE", scaleZero))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must arrive at scale 2");

        assertThatThrownBy(() -> StatementTextTemplates.stLine14aTotalExpenditure(scaleZero))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must arrive at scale 2");
    }

    @Test
    @DisplayName("an integer part of ten digits is REJECTED rather than truncated on the left, and "
            + "the rejection names the mask and its nine-digit capacity")
    void tenDigitIntegerPartIsRejectedAndNotLeftTruncated() {
        BigDecimal tenIntegerDigits = new BigDecimal("1000000000.00");
        assertThat(tenIntegerDigits.scale()).isEqualTo(2);

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        tenIntegerDigits))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit mask 9(9).99-")
                .hasMessageContaining("the mask provides 9 integer digits")
                .hasMessageContaining("the value needs 10")
                .hasMessageContaining("rejected rather than truncated on the left");

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithZeroSuppression(tenIntegerDigits))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit mask Z(9).99-")
                .hasMessageContaining("the mask provides 9 integer digits")
                .hasMessageContaining("the value needs 10");
    }

    @Test
    @DisplayName("an over-capacity value is rejected for a negative magnitude as well, and through "
            + "the line builders")
    void overCapacityValueIsRejectedWhenNegativeAndThroughTheLineBuilders() {
        BigDecimal negativeOverCapacity = new BigDecimal("-1000000000.00");
        BigDecimal wellOverCapacity = new BigDecimal("12345678901234.99");

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        negativeOverCapacity))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the value needs 10");

        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithZeroSuppression(wellOverCapacity))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the value needs 14");

        assertThatThrownBy(
                () -> StatementTextTemplates.stLine8CurrentBalance(negativeOverCapacity))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit mask 9(9).99-");

        assertThatThrownBy(() -> StatementTextTemplates.stLine14Transaction(
                "0000000000000001", "PURCHASE AT STORE", negativeOverCapacity))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit mask Z(9).99-");

        assertThatThrownBy(
                () -> StatementTextTemplates.stLine14aTotalExpenditure(negativeOverCapacity))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit mask Z(9).99-");
    }

    // Character-field semantics :: right-truncate, right-pad, both measured in encoded bytes.

    @Test
    @DisplayName("a character value shorter than its field is padded on the right with spaces to "
            + "the field width")
    void shortCharacterValuesArePaddedOnTheRight() {
        assertThat(asciiBytes(StatementTextTemplates.stLine1CustomerName("")))
                .isEqualTo(asciiBytes(E_ST_LINE1_EMPTY_NAME));
        assertThat(asciiBytes(StatementTextTemplates.stLine3AddressLine2("APT 4B")))
                .isEqualTo(asciiBytes(E_ST_LINE3_ADDRESS_LINE_2));
        assertThat(asciiBytes(StatementTextTemplates.stLine9FicoScore("789")))
                .isEqualTo(asciiBytes(E_ST_LINE9_FICO_SCORE));

        // The pad byte is the ASCII space and never a zero, a null or a tab.
        byte[] emptyName = asciiBytes(StatementTextTemplates.stLine1CustomerName(""));
        assertThat(leadingRun(emptyName, ASCII_SPACE_BYTE)).isEqualTo(EXPECTED_RECORD_LENGTH);
    }

    @Test
    @DisplayName("a character value exactly filling its field is left unchanged")
    void exactlyFittingCharacterValuesAreUnchanged() {
        String seventyFiveBytes = "X".repeat(75);
        String fiftyBytes = "B".repeat(50);
        String eightyBytes = "C".repeat(80);
        String twentyBytes = "1".repeat(20);
        assertThat(asciiLength(seventyFiveBytes)).isEqualTo(75);
        assertThat(asciiLength(fiftyBytes)).isEqualTo(50);
        assertThat(asciiLength(eightyBytes)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(twentyBytes)).isEqualTo(20);

        assertThat(asciiBytes(StatementTextTemplates.stLine1CustomerName(seventyFiveBytes)))
                .isEqualTo(asciiBytes(E_ST_LINE1_EXACT_NAME));
        assertThat(asciiBytes(StatementTextTemplates.stLine3AddressLine2(fiftyBytes)))
                .isEqualTo(asciiBytes(E_ST_LINE3_EXACT));
        assertThat(asciiBytes(StatementTextTemplates.stLine4AddressLine3(eightyBytes)))
                .isEqualTo(asciiBytes(E_ST_LINE4_EXACT));
        assertThat(asciiBytes(StatementTextTemplates.stLine7AccountId(twentyBytes)))
                .isEqualTo(asciiBytes(E_ST_LINE7_EXACT));
    }

    @Test
    @DisplayName("a character value longer than its field is cut on the right to the field width, "
            + "with no exception, exactly as the legacy fixed-width move does")
    void overLongCharacterValuesAreCutOnTheRightWithoutException() {
        assertThat(asciiBytes(StatementTextTemplates.stLine1CustomerName("Y".repeat(90))))
                .isEqualTo(asciiBytes(E_ST_LINE1_TRUNCATED_NAME));
        assertThat(asciiBytes(StatementTextTemplates.stLine2AddressLine1("A".repeat(65))))
                .isEqualTo(asciiBytes(E_ST_LINE2_TRUNCATED));
        assertThat(asciiBytes(StatementTextTemplates.stLine4AddressLine3("Z".repeat(95))))
                .isEqualTo(asciiBytes(E_ST_LINE4_TRUNCATED));
        assertThat(asciiBytes(StatementTextTemplates.stLine7AccountId("9".repeat(30))))
                .isEqualTo(asciiBytes(E_ST_LINE7_TRUNCATED));

        // The forty-nine-byte detail field is narrower than the description moved into it, so a
        // long description loses its tail here exactly as it does on the mainframe.
        assertThat(asciiBytes(StatementTextTemplates.stLine14Transaction(
                "9".repeat(24), "D".repeat(60), new BigDecimal("5.00"))))
                .isEqualTo(asciiBytes(E_ST_LINE14_TRUNCATED));
    }

    // No line terminator, no tab. The emitted record is eighty data bytes; record separation is
    // the writer's concern in the batch layer.

    @Test
    @DisplayName("no fixed constant and no builder result contains a line feed, a carriage return "
            + "or a tab")
    void noTemplateContainsALineTerminatorOrATab() {
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.ST_LINE0_START_BANNER);
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.RULE_LINE);
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.ST_LINE5_RULE);
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.ST_LINE6_BASIC_DETAILS_HEADING);
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.ST_LINE10_RULE);
        assertFreeOfTerminatorsAndTabs(
                StatementTextTemplates.ST_LINE11_TRANSACTION_SUMMARY_HEADING);
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.ST_LINE12_RULE);
        assertFreeOfTerminatorsAndTabs(
                StatementTextTemplates.ST_LINE13_TRANSACTION_COLUMN_HEADINGS);
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.ST_LINE15_END_BANNER);

        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.stLine1CustomerName("JOHN Q PUBLIC"));
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.stLine2AddressLine1("123 MAIN STREET"));
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.stLine3AddressLine2("APT 4B"));
        assertFreeOfTerminatorsAndTabs(
                StatementTextTemplates.stLine4AddressLine3("SEATTLE WA USA 98101"));
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.stLine7AccountId("00000000011"));
        assertFreeOfTerminatorsAndTabs(
                StatementTextTemplates.stLine8CurrentBalance(new BigDecimal("1234.56")));
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.stLine9FicoScore("789"));
        assertFreeOfTerminatorsAndTabs(StatementTextTemplates.stLine14Transaction(
                "0000000000000001", "PURCHASE AT STORE", new BigDecimal("42.99")));
        assertFreeOfTerminatorsAndTabs(
                StatementTextTemplates.stLine14aTotalExpenditure(new BigDecimal("1234.56")));

        assertFreeOfTerminatorsAndTabs(
                StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                        new BigDecimal("1.23")));
        assertFreeOfTerminatorsAndTabs(
                StatementTextTemplates.formatAmountMaskWithZeroSuppression(new BigDecimal("1.23")));
    }

    // Absent values are rejected. An absent value and an empty value are different things, and
    // the legacy fields were never absent.

    @Test
    @DisplayName("a null argument is rejected for each builder parameter independently")
    void nullArgumentsAreRejectedForEachParameterIndependently() {
        assertThatThrownBy(() -> StatementTextTemplates.stLine1CustomerName(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("customerName");
        assertThatThrownBy(() -> StatementTextTemplates.stLine2AddressLine1(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("addressLine1");
        assertThatThrownBy(() -> StatementTextTemplates.stLine3AddressLine2(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("addressLine2");
        assertThatThrownBy(() -> StatementTextTemplates.stLine4AddressLine3(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("addressLine3");
        assertThatThrownBy(() -> StatementTextTemplates.stLine7AccountId(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("accountId");
        assertThatThrownBy(() -> StatementTextTemplates.stLine8CurrentBalance(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("currentBalance");
        assertThatThrownBy(() -> StatementTextTemplates.stLine9FicoScore(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("ficoScore");
        assertThatThrownBy(
                () -> StatementTextTemplates.stLine14aTotalExpenditure(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("totalTransactionAmount");

        // The three-argument builder is checked one parameter at a time.
        assertThatThrownBy(() -> StatementTextTemplates.stLine14Transaction(
                null, "PURCHASE AT STORE", new BigDecimal("42.99")))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("transactionId");
        assertThatThrownBy(() -> StatementTextTemplates.stLine14Transaction(
                "0000000000000001", null, new BigDecimal("42.99")))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("transactionDetails");
        assertThatThrownBy(() -> StatementTextTemplates.stLine14Transaction(
                "0000000000000001", "PURCHASE AT STORE", null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("transactionAmount");

        // Both mask formatters reject an absent amount as well.
        assertThatThrownBy(
                () -> StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("amount");
        assertThatThrownBy(() -> StatementTextTemplates.formatAmountMaskWithZeroSuppression(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("amount");
        assertThatThrownBy(() -> StatementTextTemplates.toStatementRecordBytes(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("record");
    }

    // Encoding to the record image. The charset decision belongs with the layout, so no writer
    // has to make it, and it is always US-ASCII rather than the platform default.

    @Test
    @DisplayName("a record encodes to exactly eighty US-ASCII bytes, in a freshly allocated array")
    void recordEncodesToExactlyEightyUsAsciiBytes() {
        byte[] first = StatementTextTemplates.toStatementRecordBytes(
                StatementTextTemplates.ST_LINE0_START_BANNER);
        byte[] second = StatementTextTemplates.toStatementRecordBytes(
                StatementTextTemplates.ST_LINE0_START_BANNER);

        assertThat(first).hasSize(EXPECTED_RECORD_LENGTH);
        assertThat(first).isEqualTo(asciiBytes(E_ST_LINE0_START_BANNER));

        // A fresh array per call, so a caller may modify it without affecting the constants.
        assertThat(first).isNotSameAs(second);
        assertThat(first).isEqualTo(second);

        assertThat(StatementTextTemplates.toStatementRecordBytes(
                StatementTextTemplates.stLine14aTotalExpenditure(new BigDecimal("1234.56"))))
                .isEqualTo(asciiBytes(E_ST_LINE14A_TOTAL));
    }

    @Test
    @DisplayName("a record that is not exactly eighty bytes is rejected rather than padded or cut")
    void recordOfTheWrongLengthIsRejected() {
        String seventyNineBytes = "A".repeat(79);
        String eightyOneBytes = "A".repeat(81);
        assertThat(asciiLength(seventyNineBytes)).isEqualTo(79);
        assertThat(asciiLength(eightyOneBytes)).isEqualTo(81);

        assertThatThrownBy(
                () -> StatementTextTemplates.toStatementRecordBytes(seventyNineBytes))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be exactly 80 US-ASCII bytes");
        assertThatThrownBy(() -> StatementTextTemplates.toStatementRecordBytes(eightyOneBytes))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be exactly 80 US-ASCII bytes");
    }

    @Test
    @DisplayName("a record carrying an embedded line feed or carriage return is rejected: the "
            + "image is eighty data bytes and record separation is the writer's concern")
    void recordCarryingAnEmbeddedTerminatorIsRejected() {
        String withLineFeed = eightyByteRecordWith(ASCII_LINE_FEED_BYTE);
        String withCarriageReturn = eightyByteRecordWith(ASCII_CARRIAGE_RETURN_BYTE);
        assertThat(asciiLength(withLineFeed)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(asciiLength(withCarriageReturn)).isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThatThrownBy(() -> StatementTextTemplates.toStatementRecordBytes(withLineFeed))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must contain no line terminator");
        assertThatThrownBy(() -> StatementTextTemplates.toStatementRecordBytes(withCarriageReturn))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must contain no line terminator");
    }

    // Private helpers. Every width and every content comparison in this test goes through these,
    // so no assertion is ever made on a trimmed, normalised or default-charset-encoded value.

    /**
     * Encodes a value to its US-ASCII image. Named explicitly at every boundary so that no
     * assertion in this test can accidentally use the platform default charset.
     *
     * @param value the value to encode
     * @return the freshly allocated US-ASCII image
     */
    private static byte[] asciiBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Measures a value in US-ASCII encoded bytes rather than in string characters.
     *
     * @param value the value to measure
     * @return the encoded length in bytes
     */
    private static int asciiLength(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Copies a window out of an encoded image so that a section of a record can be compared as
     * bytes without ever converting it back to a string.
     *
     * @param image         the encoded record image
     * @param fromInclusive the first byte position of the window
     * @param length        the window length in bytes
     * @return the window as a freshly allocated array
     */
    private static byte[] slice(byte[] image, int fromInclusive, int length) {
        byte[] window = new byte[length];
        System.arraycopy(image, fromInclusive, window, 0, length);
        return window;
    }

    /**
     * Counts the run of a given byte at the start of an image.
     *
     * @param image the encoded record image
     * @param fill  the byte to count
     * @return the number of consecutive occurrences at the start
     */
    private static int leadingRun(byte[] image, byte fill) {
        int run = 0;
        while (run < image.length && image[run] == fill) {
            run++;
        }
        return run;
    }

    /**
     * Counts the run of a given byte at the end of an image.
     *
     * @param image the encoded record image
     * @param fill  the byte to count
     * @return the number of consecutive occurrences at the end
     */
    private static int trailingRun(byte[] image, byte fill) {
        int run = 0;
        while (run < image.length && image[image.length - 1 - run] == fill) {
            run++;
        }
        return run;
    }

    /**
     * Counts the run of a given byte starting at a position, which is how every declared pad count
     * inside a caption is asserted without collapsing or re-deriving it.
     *
     * @param image         the encoded record image
     * @param fromInclusive the position to start counting at
     * @param fill          the byte to count
     * @return the number of consecutive occurrences from that position
     */
    private static int runLength(byte[] image, int fromInclusive, byte fill) {
        int run = 0;
        while (fromInclusive + run < image.length && image[fromInclusive + run] == fill) {
            run++;
        }
        return run;
    }

    /**
     * Asserts that a window of an encoded image is entirely ASCII spaces, position by position.
     *
     * <p>This is how a declared pad count inside one field is asserted without letting the check
     * run past the field boundary into whatever the next field happens to begin with.</p>
     *
     * @param image         the encoded record image
     * @param fromInclusive the first byte position of the window
     * @param length        the window length in bytes
     */
    private static void assertAllSpaces(byte[] image, int fromInclusive, int length) {
        for (int offset = 0; offset < length; offset++) {
            int position = fromInclusive + offset;
            assertThat(image[position])
                    .as("byte at position %s must be an ASCII space", position)
                    .isEqualTo(ASCII_SPACE_BYTE);
        }
    }

    /**
     * Reports whether an encoded image carries a given byte anywhere.
     *
     * @param image     the encoded record image
     * @param candidate the byte to look for
     * @return {@code true} if the byte occurs
     */
    private static boolean containsByte(byte[] image, byte candidate) {
        for (byte imageByte : image) {
            if (imageByte == candidate) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asserts that a record carries no line feed, no carriage return and no tab.
     *
     * @param record the assembled record
     */
    private static void assertFreeOfTerminatorsAndTabs(String record) {
        byte[] image = asciiBytes(record);
        assertThat(containsByte(image, ASCII_LINE_FEED_BYTE))
                .as("a statement record must carry no ASCII line feed")
                .isFalse();
        assertThat(containsByte(image, ASCII_CARRIAGE_RETURN_BYTE))
                .as("a statement record must carry no ASCII carriage return")
                .isFalse();
        assertThat(containsByte(image, ASCII_TAB_BYTE))
                .as("a statement record must carry no ASCII tab")
                .isFalse();
    }

    /**
     * Asserts that a record carries no plus sign: the trailing sign of both masks is a minus or a
     * space, and a plus is never emitted.
     *
     * @param record the assembled record
     */
    private static void assertNoPlusSign(String record) {
        assertThat(containsByte(asciiBytes(record), ASCII_PLUS_BYTE))
                .as("the trailing sign is a minus or a space, never a plus")
                .isFalse();
    }

    /**
     * Builds an eighty-byte record of spaces carrying one given byte, used to prove that an
     * embedded terminator is rejected. The byte is supplied as a decimal code point so that no
     * character escape sequence appears in this file.
     *
     * @param embedded the byte to embed at the midpoint
     * @return a record of exactly eighty US-ASCII bytes
     */
    private static String eightyByteRecordWith(byte embedded) {
        byte[] image = new byte[EXPECTED_RECORD_LENGTH];
        for (int position = 0; position < image.length; position++) {
            image[position] = ASCII_SPACE_BYTE;
        }
        image[EXPECTED_RECORD_LENGTH / 2] = embedded;
        return new String(image, StandardCharsets.US_ASCII);
    }

}
