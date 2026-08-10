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

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Locale;

/**
 * Unit test for {@link StatementTextTemplates}, the holder of the plain-text account statement
 * layout.
 *
 * <p>The statement text record is exactly 80 US-ASCII bytes wide and the layout has exactly
 * seventeen line groups, every one of which sums to those 80 bytes. The emitted file is compared
 * byte for byte against a golden fixture, so a template that is one space out, or that picks the
 * wrong amount mask, produces output that looks entirely plausible in a diff viewer and still fails
 * the comparison. Every assertion is therefore made on the {@link StandardCharsets#US_ASCII}
 * encoded image rather than on a trimmed or normalised string, and every width is measured in
 * encoded bytes rather than in string characters.</p>
 *
 * <p>The expectations are an independent oracle: each is hand-written from the verified component
 * widths and caption literals, none is produced by calling a constant or a method of the class under
 * test, none is a captured snapshot of previous output, and no assertion compares a value to itself.
 * A dedicated test asserts the geometry of the oracle itself, so a typing slip in an expectation
 * fails loudly here instead of silently agreeing with a wrong implementation.</p>
 *
 * <p>Three hazards are pinned. The two banners use different splits - 31 asterisks around an 18-byte
 * text against 32 around a 16-byte text - because the texts differ in length by two; both total 80
 * and both are correct, so their inequality is asserted to stop a later tidy-up unifying them. The
 * pad counts inside the captions are contractual content rather than accidents: one trailing pad
 * byte after the basic-details heading, one trailing space inside the transaction-summary heading,
 * 35 trailing spaces after the detail column heading, two leading spaces before the amount column
 * heading, and nine, four and nine spaces before the colons of the account-id, current-balance and
 * credit-score labels. And two 13-character trailing-minus masks coexist: mask A prints leading
 * zeros and serves the current-balance line, mask B suppresses them and serves the transaction
 * amount and the total, each exercised through its own name.</p>
 *
 * <p>Both masks carry a trailing sign - a negative value ends in a minus, a non-negative value in a
 * space - and a plus sign is never emitted, which is asserted by byte value. A blank-when-zero
 * clause appears nowhere in the legacy estate, so a zero always renders its decimal point and two
 * fraction digits rather than an empty field. Amounts arrive already at scale two with truncation
 * toward zero applied upstream; nothing here re-scales, selects a rounding mode or performs
 * arithmetic. A value at another scale (decision D-05) and one whose integer part needs more than
 * nine digits (decision D-06) are rejected rather than quietly adjusted, on type and on message.</p>
 *
 * <p>Every expectation is a hand-written literal, mirroring the production decision to hold this
 * layout as literal constants rather than render it through a templating engine (decision D-27),
 * which would introduce whitespace and ordering variability that a byte-for-byte comparison cannot
 * absorb. No format-string abstraction and no locale-sensitive number formatting builds one.</p>
 *
 * <p>Out of scope: emission order, the three positions the rule line occupies within it, page
 * structure and the mapping of the 350-byte transaction record all belong to the batch tier and the
 * statement generation service - this test asserts only that the rule line is a single reusable
 * constant. The 100-byte HTML stream is a separate record width in a separate holder; the job stream
 * declares the same data-definition name at 80 in one step and at 100 in the next, a conflict
 * resolved to 80 for text and 100 for HTML (decision D-44), which is why 80 is asserted here. The
 * two-byte timestamp truncation introduced by the job's re-projection is likewise outside this
 * class.</p>
 */
@DisplayName("StatementTextTemplates :: eighty-byte plain-text statement line templates")
class StatementTextTemplatesTest {

    // ASCII byte values used for byte-level inspection, each given as a decimal code point rather
    // than a character escape, so that no escape sequence appears anywhere in this file - in
    // particular none that could be mistaken for an emitted line terminator or tab.

    private static final byte ASCII_TAB_BYTE = 9;

    private static final byte ASCII_LINE_FEED_BYTE = 10;

    private static final byte ASCII_CARRIAGE_RETURN_BYTE = 13;

    private static final byte ASCII_SPACE_BYTE = 32;

    private static final byte ASCII_DOLLAR_BYTE = 36;

    private static final byte ASCII_ASTERISK_BYTE = 42;

    private static final byte ASCII_PLUS_BYTE = 43;

    private static final byte ASCII_HYPHEN_BYTE = 45;

    private static final byte ASCII_DECIMAL_POINT_BYTE = 46;

    private static final byte ASCII_COLON_BYTE = 58;

    private static final byte ASCII_DELETE_BYTE = 127;

    private static final int FIRST_PRINTABLE_CODE_POINT = 32;

    private static final int LAST_PRINTABLE_CODE_POINT = 126;

    /**
     * A distinctive printable token planted in every hostile input so that an assertion can prove a
     * rejection message does not echo the value it rejected. It carries no character escape, so its
     * presence in a diagnostic can only have come from the rejected value itself.
     */
    private static final String INJECTION_MARKER = "QAMARKFORGEDADMIN";

    // Expected geometry, written out as literals so that every number this test asserts against
    // is visible at the point of use rather than borrowed from the class under test.

    private static final int EXPECTED_RECORD_LENGTH = 80;

    private static final int EXPECTED_LINE_GROUP_COUNT = 17;

    private static final int EXPECTED_MASK_LENGTH = 13;

    private static final int EXPECTED_START_BANNER_ASTERISK_RUN = 31;

    private static final int EXPECTED_START_BANNER_TEXT_WIDTH = 18;

    private static final int EXPECTED_END_BANNER_ASTERISK_RUN = 32;

    private static final int EXPECTED_END_BANNER_TEXT_WIDTH = 16;

    private static final String E_ST_LINE0_START_BANNER =
            "*******************************START OF STATEMENT*******************************";

    private static final String E_RULE_LINE =
            "--------------------------------------------------------------------------------";

    private static final String E_ST_LINE6_BASIC_DETAILS_HEADING =
            "                                 Basic Details                                  ";

    private static final String E_ST_LINE11_TRANSACTION_SUMMARY_HEADING =
            "                              TRANSACTION SUMMARY                               ";

    private static final String E_ST_LINE13_TRANSACTION_COLUMN_HEADINGS =
            "Tran ID         Tran Details                                         Tran Amount";

    private static final String E_ST_LINE15_END_BANNER =
            "********************************END OF STATEMENT********************************";

    // The line groups that carry substituted values. A character field is a fixed-width move: a
    // shorter value is right-padded with spaces and a longer one is right-truncated, in encoded bytes.

    private static final String E_ST_LINE1_SHORT_NAME =
            "JOHN Q PUBLIC                                                                   ";

    private static final String E_ST_LINE1_EXACT_NAME =
            "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX     ";

    private static final String E_ST_LINE1_TRUNCATED_NAME =
            "YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY     ";

    private static final String E_ST_LINE1_EMPTY_NAME =
            "                                                                                ";

    private static final String E_ST_LINE2_ADDRESS_LINE_1 =
            "123 MAIN STREET                                                                 ";

    private static final String E_ST_LINE2_TRUNCATED =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA                              ";

    private static final String E_ST_LINE3_ADDRESS_LINE_2 =
            "APT 4B                                                                          ";

    private static final String E_ST_LINE3_EXACT =
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB                              ";

    private static final String E_ST_LINE4_ADDRESS_LINE_3 =
            "SEATTLE WA USA 98101                                                            ";

    private static final String E_ST_LINE4_EXACT =
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";

    private static final String E_ST_LINE4_TRUNCATED =
            "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";

    private static final String E_ST_LINE7_ACCOUNT_ID =
            "Account ID         :00000000011                                                 ";

    private static final String E_ST_LINE7_EXACT =
            "Account ID         :11111111111111111111                                        ";

    private static final String E_ST_LINE7_TRUNCATED =
            "Account ID         :99999999999999999999                                        ";

    private static final String E_ST_LINE8_CURRENT_BALANCE =
            "Current Balance    :000001234.56                                                ";

    private static final String E_ST_LINE8_NEGATIVE =
            "Current Balance    :000001234.56-                                               ";

    private static final String E_ST_LINE8_ZERO =
            "Current Balance    :000000000.00                                                ";

    private static final String E_ST_LINE8_MAXIMUM =
            "Current Balance    :999999999.99                                                ";

    private static final String E_ST_LINE9_FICO_SCORE =
            "FICO Score         :789                                                         ";

    private static final String E_ST_LINE14_TRANSACTION =
            "0000000000000001 PURCHASE AT STORE                                $       42.99 ";

    private static final String E_ST_LINE14_NEGATIVE =
            "0000000000000002 RETURN                                           $       42.99-";

    private static final String E_ST_LINE14_TRUNCATED =
            "9999999999999999 DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD$        5.00 ";

    private static final String E_ST_LINE14_ZERO =
            "0000000000000004 ZERO VALUE                                       $         .00 ";

    private static final String E_ST_LINE14A_TOTAL =
            "Total EXP:                                                        $     1234.56 ";

    private static final String E_ST_LINE14A_NEGATIVE =
            "Total EXP:                                                        $     1234.56-";

    private static final String E_ST_LINE14A_ZERO =
            "Total EXP:                                                        $         .00 ";

    // The two 13-character trailing-minus masks. Geometry of both: 9 integer positions, a literal
    // decimal point, 2 fraction positions and a trailing sign position. Mask A prints leading
    // zeros; mask B replaces them with spaces up to but never past the decimal point. Fraction
    // digits are never suppressed by either mask.

    private static final String E_MASK_A_ONE_POINT_TWO_THREE = "000000001.23 ";

    private static final String E_MASK_B_ONE_POINT_TWO_THREE = "        1.23 ";

    private static final String E_MASK_A_ONE_CENT = "000000000.01 ";

    private static final String E_MASK_B_ONE_CENT = "         .01 ";

    private static final String E_MASK_A_ZERO = "000000000.00 ";

    private static final String E_MASK_B_ZERO = "         .00 ";

    private static final String E_MASK_A_NEGATIVE_ONE_CENT = "000000000.01-";

    private static final String E_MASK_B_NEGATIVE_ONE_CENT = "         .01-";

    private static final String E_MASK_A_NEGATIVE = "000001234.56-";

    private static final String E_MASK_B_NEGATIVE = "     1234.56-";

    private static final String E_MASK_A_MAXIMUM = "999999999.99 ";

    private static final String E_MASK_B_MAXIMUM = "999999999.99 ";

    private static final String E_MASK_A_MAXIMUM_NEGATIVE = "999999999.99-";

    private static final String E_MASK_B_MAXIMUM_NEGATIVE = "999999999.99-";

    // The oracle checks itself first: an expectation with one space too many or too few fails here,
    // before any comparison against the class under test agrees with a wrong implementation.

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

        assertThat(slice(line, 67, EXPECTED_MASK_LENGTH)).isEqualTo(asciiBytes("     1234.56 "));

        assertThat(StatementTextTemplates.ST_LINE14A_LABEL_WIDTH).isEqualTo(10);
        assertThat(StatementTextTemplates.ST_LINE14A_FILLER_WIDTH).isEqualTo(56);
        assertThat(StatementTextTemplates.ST_LINE14A_CURRENCY_WIDTH).isEqualTo(1);
    }

    // TRAP THREE :: two 13-character trailing-minus masks differing only in whether the leading
    // integer positions are suppressed. Each is exercised by name; neither is selected by a flag.

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

    // Amounts arrive at scale two with truncation toward zero already applied upstream, because the
    // estate declares no rounding clause anywhere. Nothing here re-scales, selects a rounding mode or
    // performs arithmetic; a value at any other scale is a caller defect and is rejected so that a
    // truncation-policy violation cannot hide inside formatting.

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
    @DisplayName("an integer part of ten digits is STORED into the mask's nine positions, exactly as "
            + "the legacy MOVE of the ten-digit account balance into this field does")
    void tenDigitIntegerPartIsStoredIntoTheNineDigitMask() {
        // app/cbl/CBSTM03A.CBL line 484 moves ACCT-CURR-BAL - PIC S9(10)V99 - into ST-CURR-BAL, whose
        // mask carries nine integer positions. A COBOL store keeps the low-order positions and the
        // operational sign and drops the rest, so this value must print rather than be refused.
        BigDecimal tenIntegerDigits = new BigDecimal("1000000000.00");
        assertThat(tenIntegerDigits.scale()).isEqualTo(2);

        // 1_000_000_000.00 keeps its low-order nine integer digits, which are all zero.
        assertThat(StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(tenIntegerDigits))
                .isEqualTo("000000000.00 ");
        assertThat(StatementTextTemplates.formatAmountMaskWithZeroSuppression(tenIntegerDigits))
                .isEqualTo("         .00 ");

        // A ten-digit value whose surviving digits are significant shows the truncation directly.
        BigDecimal tenSignificantDigits = new BigDecimal("1234567890.12");
        assertThat(StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                tenSignificantDigits)).isEqualTo("234567890.12 ");
        assertThat(StatementTextTemplates.formatAmountMaskWithZeroSuppression(tenSignificantDigits))
                .isEqualTo("234567890.12 ");
    }

    @Test
    @DisplayName("the store keeps the operational sign and is applied through the line builders too")
    void overCapacityValueKeepsItsSignAndFlowsThroughTheLineBuilders() {
        BigDecimal negativeOverCapacity = new BigDecimal("-1234567890.12");
        BigDecimal wellOverCapacity = new BigDecimal("12345678901234.99");

        // The sign belongs to the receiving field, so it survives a truncation that removes digits.
        assertThat(StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(
                negativeOverCapacity)).isEqualTo("234567890.12-");
        assertThat(StatementTextTemplates.formatAmountMaskWithZeroSuppression(wellOverCapacity))
                .isEqualTo("678901234.99 ");

        // And the same store is in force behind each line builder, so none of them refuses the value.
        assertThat(StatementTextTemplates.stLine8CurrentBalance(negativeOverCapacity))
                .contains("234567890.12-")
                .hasSize(StatementTextTemplates.STATEMENT_RECORD_LENGTH);
        assertThat(StatementTextTemplates.stLine14Transaction(
                "0000000000000001", "PURCHASE AT STORE", negativeOverCapacity))
                .contains("234567890.12-")
                .hasSize(StatementTextTemplates.STATEMENT_RECORD_LENGTH);
        assertThat(StatementTextTemplates.stLine14aTotalExpenditure(negativeOverCapacity))
                .contains("234567890.12-")
                .hasSize(StatementTextTemplates.STATEMENT_RECORD_LENGTH);
    }

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

    // Diagnostic hygiene and the input boundary. DL-041, also recorded as D-16, forbids a rejection
    // message from carrying the value it rejected; D-09 requires a character outside printable
    // US-ASCII to be refused in printed output and names this class as one of its three embodiments.

    @Test
    @DisplayName("no rejection message echoes the value it rejected, and none carries a raw "
            + "terminator: every entry point that takes caller text is swept (DL-041, D-16)")
    void noRejectionMessageEchoesTheValueItRejected() {
        String hostile = hostileText();
        BigDecimal amount = new BigDecimal("1.23");

        assertRejectionIsHygienic(() -> StatementTextTemplates.stLine1CustomerName(hostile));
        assertRejectionIsHygienic(() -> StatementTextTemplates.stLine2AddressLine1(hostile));
        assertRejectionIsHygienic(() -> StatementTextTemplates.stLine3AddressLine2(hostile));
        assertRejectionIsHygienic(() -> StatementTextTemplates.stLine4AddressLine3(hostile));
        assertRejectionIsHygienic(() -> StatementTextTemplates.stLine7AccountId(hostile));
        assertRejectionIsHygienic(() -> StatementTextTemplates.stLine9FicoScore(hostile));
        assertRejectionIsHygienic(
                () -> StatementTextTemplates.stLine14Transaction(hostile, "detail", amount));
        assertRejectionIsHygienic(
                () -> StatementTextTemplates.stLine14Transaction("id", hostile, amount));
        assertRejectionIsHygienic(() -> StatementTextTemplates.toStatementRecordBytes(hostile));
        assertRejectionIsHygienic(() -> StatementTextTemplates.toStatementRecordBytes(
                eightyByteRecordWith(ASCII_LINE_FEED_BYTE)));
        assertRejectionIsHygienic(() -> StatementTextTemplates.toStatementRecordBytes(
                eightyByteRecordWith(ASCII_CARRIAGE_RETURN_BYTE)));
        assertRejectionIsHygienic(() -> StatementTextTemplates.toStatementRecordBytes(
                eightyByteRecordWith(ASCII_TAB_BYTE)));
    }

    @Test
    @DisplayName("every builder that takes caller text refuses a line terminator at the input "
            + "boundary, naming the parameter, the position and the code point (D-09)")
    void everyFreeTextBuilderRefusesATerminatorAtTheInputBoundary() {
        String hostile = hostileText();
        int terminatorPosition = INJECTION_MARKER.length();
        BigDecimal amount = new BigDecimal("1.23");

        assertInputBoundaryRejection(() -> StatementTextTemplates.stLine1CustomerName(hostile),
                "customerName", terminatorPosition, ASCII_CARRIAGE_RETURN_BYTE);
        assertInputBoundaryRejection(() -> StatementTextTemplates.stLine2AddressLine1(hostile),
                "addressLine1", terminatorPosition, ASCII_CARRIAGE_RETURN_BYTE);
        assertInputBoundaryRejection(() -> StatementTextTemplates.stLine3AddressLine2(hostile),
                "addressLine2", terminatorPosition, ASCII_CARRIAGE_RETURN_BYTE);
        assertInputBoundaryRejection(() -> StatementTextTemplates.stLine4AddressLine3(hostile),
                "addressLine3", terminatorPosition, ASCII_CARRIAGE_RETURN_BYTE);
        assertInputBoundaryRejection(() -> StatementTextTemplates.stLine7AccountId(hostile),
                "accountId", terminatorPosition, ASCII_CARRIAGE_RETURN_BYTE);
        assertInputBoundaryRejection(() -> StatementTextTemplates.stLine9FicoScore(hostile),
                "ficoScore", terminatorPosition, ASCII_CARRIAGE_RETURN_BYTE);
        assertInputBoundaryRejection(
                () -> StatementTextTemplates.stLine14Transaction(hostile, "detail", amount),
                "transactionId", terminatorPosition, ASCII_CARRIAGE_RETURN_BYTE);
        assertInputBoundaryRejection(
                () -> StatementTextTemplates.stLine14Transaction("id", hostile, amount),
                "transactionDetails", terminatorPosition, ASCII_CARRIAGE_RETURN_BYTE);
    }

    @Test
    @DisplayName("the input boundary refuses a tab, a delete and a character US-ASCII cannot "
            + "represent, so no substituted byte can reach a record (D-09)")
    void theInputBoundaryRefusesEveryNonPrintableCharacter() {
        assertInputBoundaryRejection(
                () -> StatementTextTemplates.stLine1CustomerName(oneCharacterValue(ASCII_TAB_BYTE)),
                "customerName", 0, ASCII_TAB_BYTE);
        assertInputBoundaryRejection(
                () -> StatementTextTemplates.stLine1CustomerName(
                        oneCharacterValue(ASCII_DELETE_BYTE)),
                "customerName", 0, ASCII_DELETE_BYTE);

        // A character above the US-ASCII range would be silently replaced by a question mark on
        // encode, so the scan runs over characters and refuses it before any encode can happen.
        assertThatThrownBy(() -> StatementTextTemplates.stLine2AddressLine1(
                Character.toString(LAST_PRINTABLE_CODE_POINT + 1)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("addressLine1")
                .hasMessageContaining("code point " + (LAST_PRINTABLE_CODE_POINT + 1));
    }

    @Test
    @DisplayName("the wrong-length rejection reports the measured width and nothing else "
            + "(DL-041)")
    void theWrongLengthRejectionReportsTheMeasuredWidthOnly() {
        String seventyNineBytes = INJECTION_MARKER + "A".repeat(79 - INJECTION_MARKER.length());
        assertThat(asciiLength(seventyNineBytes)).isEqualTo(79);

        assertThatThrownBy(
                () -> StatementTextTemplates.toStatementRecordBytes(seventyNineBytes))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be exactly 80 US-ASCII bytes")
                .hasMessageContaining("measured 79 bytes")
                .satisfies(StatementTextTemplatesTest::assertMessageIsHygienic);
    }

    @Test
    @DisplayName("the terminator rejection names the zero-based position and the code point of the "
            + "offending byte instead of echoing the record (DL-041)")
    void theTerminatorRejectionNamesThePositionAndCodePoint() {
        int midpoint = EXPECTED_RECORD_LENGTH / 2;

        assertThatThrownBy(() -> StatementTextTemplates.toStatementRecordBytes(
                eightyByteRecordWith(ASCII_LINE_FEED_BYTE)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must contain no line terminator")
                .hasMessageContaining("zero-based position " + midpoint)
                .hasMessageContaining("code point " + ASCII_LINE_FEED_BYTE);
        assertThatThrownBy(() -> StatementTextTemplates.toStatementRecordBytes(
                eightyByteRecordWith(ASCII_CARRIAGE_RETURN_BYTE)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must contain no line terminator")
                .hasMessageContaining("zero-based position " + midpoint)
                .hasMessageContaining("code point " + ASCII_CARRIAGE_RETURN_BYTE);
    }

    @Test
    @DisplayName("a record carrying a control byte that is not a terminator is rejected under its "
            + "own distinct message, so the two defects stay distinguishable (D-09)")
    void aRecordCarryingANonTerminatorControlByteIsRejected() {
        int midpoint = EXPECTED_RECORD_LENGTH / 2;

        assertThatThrownBy(() -> StatementTextTemplates.toStatementRecordBytes(
                eightyByteRecordWith(ASCII_TAB_BYTE)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accepts printable US-ASCII only")
                .hasMessageContaining("code points " + FIRST_PRINTABLE_CODE_POINT + " to "
                        + LAST_PRINTABLE_CODE_POINT)
                .hasMessageContaining("zero-based position " + midpoint)
                .hasMessageContaining("code point " + ASCII_TAB_BYTE)
                .satisfies(StatementTextTemplatesTest::assertMessageIsHygienic);
        assertThatThrownBy(() -> StatementTextTemplates.toStatementRecordBytes(
                eightyByteRecordWith(ASCII_DELETE_BYTE)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accepts printable US-ASCII only")
                .hasMessageContaining("code point " + ASCII_DELETE_BYTE);
    }

    @Test
    @DisplayName("the input boundary refuses nothing legitimate: every one of the ninety-five "
            + "printable US-ASCII characters is accepted and reaches its field unchanged")
    void everyPrintableUsAsciiCharacterIsAccepted() {
        String printable = allPrintableUsAscii();
        assertThat(printable).hasSize(
                LAST_PRINTABLE_CODE_POINT - FIRST_PRINTABLE_CODE_POINT + 1);

        // The address field spans the whole record, so ninety-five characters are truncated to
        // eighty exactly as before: the guard admits every printable character and changes no width.
        String record = StatementTextTemplates.stLine4AddressLine3(printable);
        assertThat(asciiLength(record)).isEqualTo(EXPECTED_RECORD_LENGTH);
        assertThat(record).isEqualTo(printable.substring(0, EXPECTED_RECORD_LENGTH));
        assertFreeOfTerminatorsAndTabs(record);

        for (int codePoint = FIRST_PRINTABLE_CODE_POINT;
                codePoint <= LAST_PRINTABLE_CODE_POINT;
                codePoint++) {
            String single = Character.toString(codePoint);
            assertThat(asciiLength(StatementTextTemplates.stLine1CustomerName(single)))
                    .as("code point %s must be admitted", codePoint)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
        }
    }

    // Private helpers. Every width and every content comparison in this test goes through these,
    // so no assertion is ever made on a trimmed, normalised or default-charset-encoded value.

    /**
     * Encodes to US-ASCII, named at every boundary so that no assertion here can fall back to the
     * platform default charset.
     */
    private static byte[] asciiBytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Measures a value in US-ASCII encoded bytes rather than in string characters.
     */
    private static int asciiLength(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Copies a window out of an encoded image so a section is compared as bytes, never as a string.
     */
    private static byte[] slice(byte[] image, int fromInclusive, int length) {
        byte[] window = new byte[length];
        System.arraycopy(image, fromInclusive, window, 0, length);
        return window;
    }

    /**
     * Counts the run of a given byte at the start of an image.
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
     */
    private static int trailingRun(byte[] image, byte fill) {
        int run = 0;
        while (run < image.length && image[image.length - 1 - run] == fill) {
            run++;
        }
        return run;
    }

    /**
     * Counts the run of a given byte from a position, which is how a declared pad count inside a
     * caption is asserted rather than collapsed or re-derived.
     */
    private static int runLength(byte[] image, int fromInclusive, byte fill) {
        int run = 0;
        while (fromInclusive + run < image.length && image[fromInclusive + run] == fill) {
            run++;
        }
        return run;
    }

    /**
     * Asserts that a window is entirely ASCII spaces, position by position, so a declared pad count
     * is checked without running past the field boundary into whatever the next field begins with.
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
     * Asserts no plus sign: the trailing sign of both masks is a minus or a space, never a plus.
     */
    private static void assertNoPlusSign(String record) {
        assertThat(containsByte(asciiBytes(record), ASCII_PLUS_BYTE))
                .as("the trailing sign is a minus or a space, never a plus")
                .isFalse();
    }

    /**
     * Builds the hostile value planted into every entry point that takes caller text: the marker,
     * a carriage return and line feed, then more printable text. Both terminators are given as
     * decimal code points, so the marker can only reach a diagnostic by being echoed.
     */
    private static String hostileText() {
        byte[] terminators = {ASCII_CARRIAGE_RETURN_BYTE, ASCII_LINE_FEED_BYTE};
        return INJECTION_MARKER + new String(terminators, StandardCharsets.US_ASCII)
                + "FORGED AUDIT ENTRY";
    }

    /**
     * Builds a one-character value from a code point, so a non-printable character reaches a builder
     * without an escape sequence appearing in this file.
     */
    private static String oneCharacterValue(byte codePoint) {
        return Character.toString(codePoint);
    }

    /**
     * Builds the whole printable US-ASCII range ascending, to show the input boundary refuses nothing
     * legitimate.
     */
    private static String allPrintableUsAscii() {
        StringBuilder printable = new StringBuilder();
        for (int codePoint = FIRST_PRINTABLE_CODE_POINT;
                codePoint <= LAST_PRINTABLE_CODE_POINT;
                codePoint++) {
            printable.append((char) codePoint);
        }
        return printable.toString();
    }

    /**
     * Asserts that a call is rejected and that its message is hygienic in the sense DL-041 requires:
     * no raw terminator, no raw tab, and no fragment of the value it rejected.
     */
    private static void assertRejectionIsHygienic(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(RuntimeException.class)
                .satisfies(StatementTextTemplatesTest::assertMessageIsHygienic);
    }

    /**
     * Asserts that one rejection message carries no control character and does not echo the rejected
     * value. The marker is printable and appears in no message prose, so finding it in a diagnostic
     * can only mean the value was interpolated; a raw terminator in a log line is an injection
     * primitive, which is why the control-character checks sit beside it.
     */
    private static void assertMessageIsHygienic(Throwable thrown) {
        String message = thrown.getMessage();
        assertThat(message).as("a rejection must carry a message").isNotNull();
        assertThat(message.indexOf((char) ASCII_LINE_FEED_BYTE))
                .as("a diagnostic must carry no raw line feed (DL-041): %s", message)
                .isEqualTo(-1);
        assertThat(message.indexOf((char) ASCII_CARRIAGE_RETURN_BYTE))
                .as("a diagnostic must carry no raw carriage return (DL-041): %s", message)
                .isEqualTo(-1);
        assertThat(message.indexOf((char) ASCII_TAB_BYTE))
                .as("a diagnostic must carry no raw tab (DL-041): %s", message)
                .isEqualTo(-1);
        assertThat(message)
                .as("a diagnostic must not echo the value it rejected (DL-041)")
                .doesNotContain(INJECTION_MARKER);
    }

    /**
     * Asserts that a value is refused at the input boundary rather than at the assembled-record
     * invariant, and that the rejection names the parameter, the offending position and the code
     * point without echoing the value.
     */
    private static void assertInputBoundaryRejection(ThrowingCallable call,
                                                     String expectedField,
                                                     int expectedPosition,
                                                     byte expectedByte) {
        assertThatThrownBy(call)
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField)
                .hasMessageContaining("accepts printable US-ASCII only")
                .hasMessageContaining("zero-based position " + expectedPosition)
                .hasMessageContaining("code point " + expectedByte)
                .satisfies(StatementTextTemplatesTest::assertMessageIsHygienic);
    }

    /**
     * Builds an eighty-byte record of spaces carrying one given byte, so an embedded terminator is
     * rejected without a character escape appearing in this file.
     */
    private static String eightyByteRecordWith(byte embedded) {
        byte[] image = new byte[EXPECTED_RECORD_LENGTH];
        for (int position = 0; position < image.length; position++) {
            image[position] = ASCII_SPACE_BYTE;
        }
        image[EXPECTED_RECORD_LENGTH / 2] = embedded;
        return new String(image, StandardCharsets.US_ASCII);
    }

    /*
     * Locale invariance - decision D-27.
     */

    /**
     * Turkish, whose casing rules fold ASCII {@code i} to a non-ASCII character.
     */
    private static final java.util.Locale TURKISH = java.util.Locale.forLanguageTag("tr-TR");

    /**
     * The locales the amount masks are re-rendered under. Four carry a non-Latin default numbering
     * system, under which {@code String.format(Locale.ROOT, "%03d", 7)} and {@code new DecimalFormat("000")} emit
     * non-ASCII digits; the fifth carries the Turkish casing rules. Between them they cover both ways
     * a default locale could change an emitted byte.
     */
    private static final java.util.List<java.util.Locale> HOSTILE_LOCALES = java.util.List.of(
            TURKISH,
            java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"),
            java.util.Locale.forLanguageTag("fa-IR-u-nu-arabext"),
            java.util.Locale.forLanguageTag("bn-BD-u-nu-beng"),
            java.util.Locale.forLanguageTag("my-MM-u-nu-mymr"));

    /**
     * Evaluates a supplier with the JVM's default locale temporarily replaced.
     *
     * <p>The build pins {@code -Duser.language=en -Duser.country=US}, so no other test in this class
     * can observe a locale defect. The previous default is restored in a {@code finally} block, and
     * the format category is restored explicitly because
     * {@link java.util.Locale#setDefault(java.util.Locale)} overwrites both categories.
     *
     * <p>The setting being replaced belongs to the process rather than to the test, so every caller
     * declares exclusive access through {@link org.junit.jupiter.api.parallel.ResourceLock}, naming
     * both the global resource and the locale. The global one is what actually confers the guarantee:
     * a lock on the locale alone excludes only tests that claim the locale themselves, and every
     * byte-parity test around this one claims nothing while depending on the pinned default. The
     * isolation therefore travels with this code rather than resting on the execution settings
     * happening to run one test at a time.
     *
     * @param locale the locale to install for the duration of the call
     * @param body   the value to compute under that locale
     * @return whatever {@code body} produced
     */
    private static String underLocale(final java.util.Locale locale,
            final java.util.function.Supplier<String> body) {
        final java.util.Locale previousDefault = java.util.Locale.getDefault();
        final java.util.Locale previousFormat =
                java.util.Locale.getDefault(java.util.Locale.Category.FORMAT);
        try {
            java.util.Locale.setDefault(locale);
            return body.get();
        } finally {
            java.util.Locale.setDefault(previousDefault);
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, previousFormat);
        }
    }

    @Test
    @DisplayName("both amount masks render byte-identically under every hostile locale, which is what makes decision D-27 an enforced property rather than an implementation habit")
    // Replaces the JVM-wide default locale, which is process state rather than test state, so the
    // isolation is declared here rather than left to the accident of the current execution settings.
    // This class is flat, so each of the three replacing tests carries the declaration itself.
    //
    // Both locks are needed, and the wider one is the load-bearing one. A lock on the locale alone
    // only excludes tests that themselves claim the locale, and the byte-parity tests that surround
    // this one claim nothing while depending entirely on the en-US pin the build applies - so a narrow
    // lock would leave them free to run alongside the replacement and read a grouping separator or a
    // non-Latin digit into a fixed-width mask. The global lock is what actually excludes them. The
    // narrow lock is kept alongside it because it names the specific resource being written, so a
    // future test that declares a read lock on the locale interlocks with this correctly.
    @ResourceLock(Resources.GLOBAL)
    @ResourceLock(Resources.LOCALE)
    void bothAmountMasksRenderByteIdenticallyUnderEveryHostileLocale() {
        // D-27 forbids BigDecimal.toString, NumberFormat, DecimalFormat and every other
        // locale-sensitive formatter here, because a locale can introduce a grouping separator, a
        // different decimal separator, a different minus glyph or a non-Latin digit set - any one of
        // which is a byte-parity failure in a fixed 13-position mask. The build pins en-US, so only a
        // test that installs another locale can observe a regression against that decision. These
        // values exercise each hazard: a grouping-width magnitude, a fractional part, a negative sign,
        // and a value whose leading zeros are suppressed.
        final java.util.List<BigDecimal> amounts = java.util.List.of(
                new BigDecimal("1234567.89"),
                new BigDecimal("-1234567.89"),
                new BigDecimal("0.01"),
                new BigDecimal("-0.01"),
                new BigDecimal("0.00"),
                // The largest value the mask admits: 9 integer digits, a point, 2 fraction digits and
                // the trailing sign position add up to the 13 characters the mask provides. A tenth
                // integer digit is rejected rather than truncated, which a separate test pins.
                new BigDecimal("999999999.99"),
                new BigDecimal("-999999999.99"));

        for (final BigDecimal amount : amounts) {
            final String pinnedMaskA =
                    StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(amount);
            final String pinnedMaskB =
                    StatementTextTemplates.formatAmountMaskWithZeroSuppression(amount);

            for (final java.util.Locale hostile : HOSTILE_LOCALES) {
                assertThat(underLocale(hostile,
                        () -> StatementTextTemplates.formatAmountMaskWithoutZeroSuppression(amount)))
                        .as("mask A for %s under %s", amount, hostile.toLanguageTag())
                        .isEqualTo(pinnedMaskA);
                assertThat(underLocale(hostile,
                        () -> StatementTextTemplates.formatAmountMaskWithZeroSuppression(amount)))
                        .as("mask B for %s under %s", amount, hostile.toLanguageTag())
                        .isEqualTo(pinnedMaskB);
            }

            // The mask is a fixed-width US-ASCII field, so its width and its byte set are asserted
            // as well: a non-Latin digit would encode to more than one byte and a grouping separator
            // would displace the decimal point.
            for (final String mask : java.util.List.of(pinnedMaskA, pinnedMaskB)) {
                assertThat(mask).hasSize(EXPECTED_MASK_LENGTH);
                assertThat(mask.getBytes(StandardCharsets.US_ASCII)).hasSize(EXPECTED_MASK_LENGTH);
                for (int position = 0; position < mask.length(); position++) {
                    final char rendered = mask.charAt(position);
                    assertThat((int) rendered)
                            .as("mask character at position %d of [%s] must be printable US-ASCII",
                                    position, mask)
                            .isBetween(FIRST_PRINTABLE_CODE_POINT, LAST_PRINTABLE_CODE_POINT);
                }
            }
        }
    }

    @Test
    @DisplayName("every 80-byte record that embeds an amount is byte-identical under every hostile locale")
    @ResourceLock(Resources.GLOBAL)
    @ResourceLock(Resources.LOCALE)
    void everyRecordEmbeddingAnAmountIsByteIdenticalUnderEveryHostileLocale() {
        // The masks are asserted above in isolation; this asserts the three builders that place a
        // mask inside a complete 80-byte record, because a record is what actually reaches a file and
        // a locale defect confined to one builder would otherwise pass.
        final BigDecimal amount = new BigDecimal("-1234.56");
        final String pinnedBalance = StatementTextTemplates.stLine8CurrentBalance(amount);
        final String pinnedTotal = StatementTextTemplates.stLine14aTotalExpenditure(amount);
        final String pinnedTransaction =
                StatementTextTemplates.stLine14Transaction("00000000000000001", "Purchase", amount);

        for (final java.util.Locale hostile : HOSTILE_LOCALES) {
            assertThat(underLocale(hostile,
                    () -> StatementTextTemplates.stLine8CurrentBalance(amount)))
                    .as("stLine8CurrentBalance under %s", hostile.toLanguageTag())
                    .isEqualTo(pinnedBalance);
            assertThat(underLocale(hostile,
                    () -> StatementTextTemplates.stLine14aTotalExpenditure(amount)))
                    .as("stLine14aTotalExpenditure under %s", hostile.toLanguageTag())
                    .isEqualTo(pinnedTotal);
            assertThat(underLocale(hostile, () -> StatementTextTemplates.stLine14Transaction(
                    "00000000000000001", "Purchase", amount)))
                    .as("stLine14Transaction under %s", hostile.toLanguageTag())
                    .isEqualTo(pinnedTransaction);
        }

        for (final String record
                : java.util.List.of(pinnedBalance, pinnedTotal, pinnedTransaction)) {
            assertThat(record.getBytes(StandardCharsets.US_ASCII)).hasSize(EXPECTED_RECORD_LENGTH);
        }
    }

    @Test
    @DisplayName("the record encoder and the input-boundary guard behave identically under a Turkish default locale, where a locale-sensitive case fold would diverge")
    @ResourceLock(Resources.GLOBAL)
    @ResourceLock(Resources.LOCALE)
    void theEncoderAndTheInputGuardAreUnchangedUnderATurkishDefaultLocale() {
        // Turkish would break a fold built on String.toUpperCase: ASCII i folds to U+0130 there, which
        // is not a US-ASCII byte. Nothing in this class folds case, and this test keeps that true for
        // the emitted record and for the guard that inspects incoming text alike - a guard that
        // lower-cased its input before comparing would be as wrong as a builder that upper-cased.
        final String mixedCase = "Ibrahim Iliescu";
        final String pinned = StatementTextTemplates.stLine1CustomerName(mixedCase);

        assertThat(underLocale(TURKISH,
                () -> StatementTextTemplates.stLine1CustomerName(mixedCase)))
                .as("a mixed-case name must be placed verbatim under tr-TR")
                .isEqualTo(pinned)
                .contains(mixedCase);

        // The guard must still reject a terminator under tr-TR, and still refuse to echo it.
        final String hostile = INJECTION_MARKER + "\r\n";
        final String message = underLocale(TURKISH, () -> {
            try {
                StatementTextTemplates.stLine1CustomerName(hostile);
                return "NO REJECTION";
            } catch (final IllegalArgumentException rejected) {
                return rejected.getMessage();
            }
        });

        assertThat(message)
                .as("the input-boundary guard must still fire under tr-TR")
                .contains("code point 13")
                .doesNotContain(INJECTION_MARKER);
        assertThat(message.indexOf('\r')).as("no raw carriage return").isEqualTo(-1);
        assertThat(message.indexOf('\n')).as("no raw line feed").isEqualTo(-1);
    }

}
