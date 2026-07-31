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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ReportLineFormatter}, the sole holder of the <strong>133-byte</strong>
 * Daily Transaction Report record layout and of the two fifteen-character numeric-edited amount
 * masks that the legacy report emits.
 *
 * <h2>Why this test exists: Gate 1 byte parity</h2>
 * <p>The report file is compared <em>byte for byte</em> against a golden fixture written at
 * {@code LRECL=133 RECFM=FB}. Nothing in this record is cosmetic. Trailing spaces, interior
 * padding, dot fills, the two literal hyphen separators and the sign position of each mask are
 * all contractual content, so this test never trims, strips, normalises or collapses anything.
 * Every width is measured in <em>US-ASCII encoded bytes</em> and every content comparison is a
 * {@code byte[]} comparison.
 *
 * <h2>The independent oracle</h2>
 * <p>Every expectation in this file is hand-written from the verified layout facts below. No
 * assertion calls a constant or a method of the class under test in order to produce its own
 * expected value, nothing is snapshotted from a previous run, and no assertion has the shape
 * {@code f(x) == f(x)}. Where a published constant is checked, it is checked against a
 * hand-written number, which is the whole point of checking it.
 *
 * <p>The layout facts were read directly from the estate:
 * <ul>
 *   <li>the report-formatting copybook declares all seven groups, and the arithmetic of each was
 *       summed by hand from its component pictures;</li>
 *   <li>the report driver declares the record as a single 133-byte alphanumeric item, the blank
 *       line as 133 spaces, the page size as twenty and the date-parameter structure as
 *       10 + 1 + 10;</li>
 *   <li>the consuming procedure declares the output dataset at {@code LRECL=133 RECFM=FB};</li>
 *   <li>the two description source fields are each fifty bytes wide, and the transaction amount
 *       and the three total accumulators are each {@code PIC S9(09)V99}.</li>
 * </ul>
 *
 * <h2>The seven groups share one record width and nothing else</h2>
 * <pre>
 * #  group            component widths                 native  right pad  total
 * -  ---------------  -------------------------------  ------  ---------  -----
 * 1  name header      38 + 41 + 12 + 10 + 4 + 10          115         18    133
 * 2  detail line      sixteen items, see below            114         19    133
 * 3  column header    17 + 12 + 19 + 35 + 14 + 1 + 16     114         19    133
 * 4  rule line        one field of 133 hyphens            133          0    133
 * 5  page totals      11 + 86 + 15                        112         21    133
 * 6  account totals   13 + 84 + 15                        112         21    133
 * 7  grand totals     11 + 86 + 15                        112         21    133
 * </pre>
 *
 * <p>Group 4 is the only natively-133 group. The other six are shorter and are right-padded.
 *
 * <h2>The five traps this test is built to catch</h2>
 * <ol>
 *   <li><strong>Both hyphen separators always survive.</strong> The driver initialises the detail
 *       group before moving values into it, and an initialise does not touch a filler item
 *       carrying a literal value, so the hyphens at offsets 31 and 52 are present in every detail
 *       line - including one where every caller-supplied value is empty.</li>
 *   <li><strong>Two truncations at two different widths in one line.</strong> The type
 *       description goes from fifty bytes into fifteen and the category description from fifty
 *       into twenty-nine. Both are silent right truncations. One seeded category description is
 *       exactly twenty-nine characters and therefore sits on the boundary.</li>
 *   <li><strong>Three dot-fill widths, each hardcoded.</strong> 86, 84 and 86. The three label
 *       fields are 11, 13 and 11 bytes wide and the fills compensate so that all three amounts
 *       begin at offset 97 and end at offset 111. The arithmetic coincidence must never become a
 *       formula, so this test asserts each fill count on its own and never derives one from
 *       another.</li>
 *   <li><strong>The two masks have opposite non-negative behaviour.</strong> Both place the sign
 *       in a fixed insertion position at the far left, and the sign never shifts rightward to sit
 *       against the first digit. The detail mask emits a space for a non-negative value and never
 *       a plus; the totals mask always emits a sign.</li>
 *   <li><strong>A value of exactly zero blanks the whole fifteen-byte field in both masks.</strong>
 *       This is the opposite of the statement text mask, where the two decimal places always
 *       print, and the rule must not be carried across.</li>
 * </ol>
 *
 * <h2>Scope: this class formats, it does not compute and it does not paginate</h2>
 * <p>The consuming report driver contains <strong>zero</strong> arithmetic-compute statements -
 * only integer additions to the line counter and two integer subtractions of status values - so
 * the report path introduces no arithmetic and neither does the formatter. This test therefore
 * invents none: no expectation anywhere below is arrived at by calculation on an amount.
 *
 * <p>Pagination is likewise not owned here. The line counter, the accumulation and the break
 * decisions all live in the transaction report service. The only pagination fact this test
 * asserts is that the published page-size constant is twenty.
 *
 * <h2>Provenance</h2>
 * <p>Legacy CardDemo estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. This paragraph is a header record of
 * where the facts came from; no test below asserts the stamp on any member. No COBOL, JCL, BMS,
 * copybook or procedure source statement is transcribed anywhere in this file - only field
 * widths, byte offsets, counts and the exact external-contract literals, which are the bytes the
 * class under test is required to emit.
 *
 * <p>This is a pure unit test. It starts no container, no application context, no database and no
 * server, opens no socket and touches no file.
 */
class ReportLineFormatterTest {

    // =============================================================================================
    // Hand-written oracle. Every literal, width and offset below was transcribed by hand from the
    // layout facts in the class documentation. Nothing here reads the class under test.
    // =============================================================================================

    /** A ten-byte start date, carried through the report verbatim as characters. */
    private static final String START_DATE = "2022-01-01";

    /** A ten-byte end date, carried through the report verbatim as characters. */
    private static final String END_DATE = "2022-12-31";

    /**
     * Group 1 expected image. Assembled from the six component widths 38, 41, 12, 10, 4 and 10,
     * then the 18-space right pad. The date caption carries its own single trailing space and is
     * therefore twelve bytes; the separator carries a space on each side and is therefore four.
     */
    private static final String EXPECTED_NAME_HEADER =
            "DALYREPT" + spaces(30)
            + "Daily Transaction Report" + spaces(17)
            + "Date Range: "
            + "2022-01-01"
            + " to "
            + "2022-12-31"
            + spaces(18);

    /**
     * Group 3 expected image. Assembled from the seven component widths 17, 12, 19, 35, 14, 1 and
     * 16, then the 19-space right pad. The seventh component's literal carries eight leading
     * spaces, which is what lands the word on offsets 106 through 111.
     */
    private static final String EXPECTED_COLUMN_HEADER =
            "Transaction ID" + spaces(3)
            + "Account ID" + spaces(2)
            + "Transaction Type" + spaces(3)
            + "Tran Category" + spaces(22)
            + "Tran Source" + spaces(3)
            + spaces(1)
            + spaces(8) + "Amount" + spaces(2)
            + spaces(19);

    /** Group 4 expected image: one field of 133 hyphens, the only group needing no pad. */
    private static final String EXPECTED_RULE_LINE = hyphens(133);

    /** The blank report record: 133 spaces. A real emitted record, not padding. */
    private static final String EXPECTED_BLANK_LINE = spaces(133);

    /**
     * Group 2 expected image for a fully populated detail line. Assembled item by item from the
     * sixteen-item table: 16, 1, 11, 1, 2, 1, 15, 1, 4, 1, 29, 1, 10, 4, 15, 2, then the
     * 19-space right pad.
     */
    private static final String EXPECTED_DETAIL_LINE =
            "0000000000000001"
            + spaces(1)
            + "00000000011"
            + spaces(1)
            + "01"
            + "-"
            + "Purchase" + spaces(7)
            + spaces(1)
            + "0001"
            + "-"
            + "Sales draft credit adjustment"
            + spaces(1)
            + "POS TERM" + spaces(2)
            + spaces(4)
            + spaces(9) + "500.47"
            + spaces(2)
            + spaces(19);

    /**
     * Group 2 expected image when every caller-supplied text value is empty, the category code is
     * zero and the amount is a scale-two zero. Both hyphens are still present, the category code
     * is still zero-filled to four digits and the amount field is fully blank:
     * 31 spaces, a hyphen, 16 spaces, four zero digits, a hyphen, then 80 spaces.
     */
    private static final String EXPECTED_DETAIL_LINE_ALL_EMPTY =
            spaces(31) + "-" + spaces(16) + "0000" + "-" + spaces(80);

    /** Group 5 expected image for a page total of one million two hundred thirty-four thousand. */
    private static final String EXPECTED_PAGE_TOTAL_LINE =
            "Page Total" + spaces(1)
            + dots(86)
            + "+" + spaces(2) + "1,234,567.89"
            + spaces(21);

    /** Group 6 expected image for a negative account total. The 13-byte label needs no pad. */
    private static final String EXPECTED_ACCOUNT_TOTAL_LINE =
            "Account Total"
            + dots(84)
            + "-" + spaces(8) + "603.22"
            + spaces(21);

    /** Group 7 expected image for a zero grand total, where the amount field blanks entirely. */
    private static final String EXPECTED_GRAND_TOTAL_LINE =
            "Grand Total"
            + dots(86)
            + spaces(15)
            + spaces(21);

    // ---------------------------------------------------------------------------------------------
    // Expected mask renderings, written out in full at fifteen characters each.
    //
    // Geometry, hand-derived from the picture: position 0 is the fixed sign, positions 1 to 3 and
    // 5 to 7 and 9 to 11 are the nine zero-suppressed integer digits, positions 4 and 8 are the
    // two group separators, position 12 is the decimal point and positions 13 and 14 are the two
    // decimal digits. 1 + 3 + 1 + 3 + 1 + 3 + 1 + 2 = 15.
    // ---------------------------------------------------------------------------------------------

    /** Detail mask, positive 500.47: nine spaces then the digits. The sign position is a space. */
    private static final String EXPECTED_DETAIL_MASK_POSITIVE = "         500.47";

    /**
     * Detail mask, negative 603.22: a minus in position one, then <em>eight</em> spaces, then the
     * digits. The suppression happens between the sign and the digits, so a rendering that puts
     * the minus immediately in front of the first digit is wrong.
     */
    private static final String EXPECTED_DETAIL_MASK_NEGATIVE = "-        603.22";

    /** Totals mask, positive 500.47: a plus in position one, then eight spaces, then the digits. */
    private static final String EXPECTED_TOTAL_MASK_POSITIVE = "+        500.47";

    /** Totals mask, negative 603.22: a minus in position one, identical in shape to the detail. */
    private static final String EXPECTED_TOTAL_MASK_NEGATIVE = "-        603.22";

    /** Either mask for a value of exactly zero: fifteen spaces, editing characters included. */
    private static final String EXPECTED_MASK_ZERO = "               ";

    /** Detail mask at full width, showing both group separators printed. */
    private static final String EXPECTED_DETAIL_MASK_FULL_WIDTH = " 999,999,999.99";

    /** Totals mask at full width, showing the sign hard against the leading digit. */
    private static final String EXPECTED_TOTAL_MASK_FULL_WIDTH = "+999,999,999.99";

    /** Detail mask where the integer part is entirely zero: twelve spaces, a point, two digits. */
    private static final String EXPECTED_DETAIL_MASK_ZERO_INTEGER_PART = "            .47";

    /** Totals mask where the integer part is entirely zero: a plus, eleven spaces, point, digits. */
    private static final String EXPECTED_TOTAL_MASK_ZERO_INTEGER_PART = "+           .47";

    /** Detail mask where only the first group separator prints. */
    private static final String EXPECTED_DETAIL_MASK_ONE_SEPARATOR = "       1,000.00";

    /** Detail mask where both separators print but the leading digit positions are suppressed. */
    private static final String EXPECTED_DETAIL_MASK_TWO_SEPARATORS = "   1,000,000.00";

    // ---------------------------------------------------------------------------------------------
    // Date-parameter card oracle. 10 + 1 + 10 = 21 structured bytes; the full card is 80 bytes.
    // ---------------------------------------------------------------------------------------------

    /** The 21-byte structured record: start date, one space, end date. */
    private static final String EXPECTED_DATE_PARAMETER_RECORD = "2022-01-01 2022-12-31";

    /** The full 80-byte card: the same 21 structured bytes then 59 trailing spaces. */
    private static final String EXPECTED_DATE_PARAMETER_CARD =
            "2022-01-01 2022-12-31" + spaces(59);

    // ---------------------------------------------------------------------------------------------
    // Characters used to probe the charset guard. Written as explicit code points so that no
    // escape sequence appears in this file and no line terminator can be introduced by accident.
    // ---------------------------------------------------------------------------------------------

    /** A control character below the printable range, which the layout must reject. */
    private static final char BELOW_PRINTABLE_ASCII = (char) 7;

    /** A control character above the printable range, which the layout must reject. */
    private static final char ABOVE_PRINTABLE_ASCII = (char) 127;

    // =============================================================================================
    // Measurement helpers. Width is always the encoded byte count in US-ASCII, never a character
    // count, and content is always compared as bytes.
    // =============================================================================================

    /**
     * Encodes a value to US-ASCII bytes. The charset is named explicitly at every boundary so that
     * no platform default can influence either a width or a comparison.
     *
     * @param value the value to encode
     * @return the encoded bytes
     */
    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Measures a value in US-ASCII encoded bytes.
     *
     * @param value the value to measure
     * @return the encoded byte count
     */
    private static int asciiWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Slices a field window out of a produced record without altering it in any way. No trimming,
     * no normalising and no case change is applied, because the padding inside a window is part of
     * the contract being asserted.
     *
     * @param record the produced record
     * @param offset the field's zero-based offset
     * @param width  the field's declared width
     * @return the field's exact content
     */
    private static String window(String record, int offset, int width) {
        return record.substring(offset, offset + width);
    }

    /**
     * Counts occurrences of one byte in a produced record. Used to assert the three dot-fill
     * counts independently of one another.
     *
     * @param record the produced record
     * @param target the byte to count
     * @return the number of occurrences
     */
    private static int countBytes(String record, char target) {
        int count = 0;
        for (byte encoded : ascii(record)) {
            if (encoded == (byte) target) {
                count++;
            }
        }
        return count;
    }

    /**
     * A run of spaces. The count is always a hand-written number taken from the layout tables.
     *
     * @param count how many spaces
     * @return the run
     */
    private static String spaces(int count) {
        return " ".repeat(count);
    }

    /**
     * A run of hyphens, the rule-line fill.
     *
     * @param count how many hyphens
     * @return the run
     */
    private static String hyphens(int count) {
        return "-".repeat(count);
    }

    /**
     * A run of full stops, the total-line dot fill.
     *
     * @param count how many full stops
     * @return the run
     */
    private static String dots(int count) {
        return ".".repeat(count);
    }

    /**
     * Builds a fully populated detail line from the canonical sample values, so that a test
     * focusing on one field does not have to restate the other seven.
     *
     * @return the produced 133-byte detail record
     */
    private static String sampleDetailLine() {
        return ReportLineFormatter.buildTransactionDetailLine(
                "0000000000000001", "00000000011", "01", "Purchase", 1,
                "Sales draft credit adjustment", "POS TERM", new BigDecimal("500.47"));
    }

    /**
     * Builds a detail line whose type description is the supplied value and whose other fields are
     * blank, isolating the fifteen-byte truncation.
     *
     * @param typeDescription the type description under test
     * @return the produced 133-byte detail record
     */
    private static String detailLineWithTypeDescription(String typeDescription) {
        return ReportLineFormatter.buildTransactionDetailLine(
                "", "", "", typeDescription, 0, "", "", new BigDecimal("0.00"));
    }

    /**
     * Builds a detail line whose category description is the supplied value and whose other fields
     * are blank, isolating the twenty-nine-byte truncation.
     *
     * @param categoryDescription the category description under test
     * @return the produced 133-byte detail record
     */
    private static String detailLineWithCategoryDescription(String categoryDescription) {
        return ReportLineFormatter.buildTransactionDetailLine(
                "", "", "", "", 0, categoryDescription, "", new BigDecimal("0.00"));
    }

    /**
     * Builds a detail line whose category code is the supplied value and whose other fields are
     * blank, isolating the left zero-fill of the only numeric-display field in the layout.
     *
     * @param categoryCode the category code under test
     * @return the produced 133-byte detail record
     */
    private static String detailLineWithCategoryCode(int categoryCode) {
        return ReportLineFormatter.buildTransactionDetailLine(
                "", "", "", "", categoryCode, "", "", new BigDecimal("0.00"));
    }

    // =============================================================================================
    // Group 1 of the test plan: the published widths.
    // =============================================================================================

    @Nested
    @DisplayName("Record and group width constants")
    class RecordAndGroupWidthConstants {

        @Test
        @DisplayName("the report record width is 133 bytes")
        void reportRecordWidthIsOneHundredThirtyThree() {
            assertThat(ReportLineFormatter.REPORT_RECORD_WIDTH).isEqualTo(133);
        }

        @Test
        @DisplayName("the seven native group widths are 115, 114, 114, 133, 112, 112 and 112")
        void sevenNativeGroupWidthsAreEachDeclaredIndividually() {
            assertThat(ReportLineFormatter.NAME_HEADER_NATIVE_WIDTH).isEqualTo(115);
            assertThat(ReportLineFormatter.DETAIL_LINE_NATIVE_WIDTH).isEqualTo(114);
            assertThat(ReportLineFormatter.COLUMN_HEADER_NATIVE_WIDTH).isEqualTo(114);
            assertThat(ReportLineFormatter.RULE_LINE_NATIVE_WIDTH).isEqualTo(133);
            assertThat(ReportLineFormatter.PAGE_TOTAL_LINE_NATIVE_WIDTH).isEqualTo(112);
            assertThat(ReportLineFormatter.ACCOUNT_TOTAL_LINE_NATIVE_WIDTH).isEqualTo(112);
            assertThat(ReportLineFormatter.GRAND_TOTAL_LINE_NATIVE_WIDTH).isEqualTo(112);
        }

        @Test
        @DisplayName("the seven right-pad widths are 18, 19, 19, 0, 21, 21 and 21")
        void sevenRightPadWidthsAreEachDeclaredIndividually() {
            assertThat(ReportLineFormatter.NAME_HEADER_PAD_WIDTH).isEqualTo(18);
            assertThat(ReportLineFormatter.DETAIL_LINE_PAD_WIDTH).isEqualTo(19);
            assertThat(ReportLineFormatter.COLUMN_HEADER_PAD_WIDTH).isEqualTo(19);
            assertThat(ReportLineFormatter.RULE_LINE_PAD_WIDTH).isEqualTo(0);
            assertThat(ReportLineFormatter.PAGE_TOTAL_LINE_PAD_WIDTH).isEqualTo(21);
            assertThat(ReportLineFormatter.ACCOUNT_TOTAL_LINE_PAD_WIDTH).isEqualTo(21);
            assertThat(ReportLineFormatter.GRAND_TOTAL_LINE_PAD_WIDTH).isEqualTo(21);
        }

        @Test
        @DisplayName("the rule line is the only group that is natively the full record width")
        void ruleLineIsTheOnlyNativelyFullWidthGroup() {
            assertThat(ReportLineFormatter.RULE_LINE_NATIVE_WIDTH).isEqualTo(133);
            assertThat(ReportLineFormatter.RULE_LINE_PAD_WIDTH).isEqualTo(0);
            assertThat(ReportLineFormatter.NAME_HEADER_NATIVE_WIDTH).isNotEqualTo(133);
            assertThat(ReportLineFormatter.DETAIL_LINE_NATIVE_WIDTH).isNotEqualTo(133);
            assertThat(ReportLineFormatter.COLUMN_HEADER_NATIVE_WIDTH).isNotEqualTo(133);
            assertThat(ReportLineFormatter.PAGE_TOTAL_LINE_NATIVE_WIDTH).isNotEqualTo(133);
            assertThat(ReportLineFormatter.ACCOUNT_TOTAL_LINE_NATIVE_WIDTH).isNotEqualTo(133);
            assertThat(ReportLineFormatter.GRAND_TOTAL_LINE_NATIVE_WIDTH).isNotEqualTo(133);
        }

        @Test
        @DisplayName("the field widths shared across the layout are 15, 10, 16, 11, 2, 4 and 10")
        void sharedFieldWidthsAreDeclaredAsThePicturesRequire() {
            assertThat(ReportLineFormatter.AMOUNT_MASK_WIDTH).isEqualTo(15);
            assertThat(ReportLineFormatter.DATE_WIDTH).isEqualTo(10);
            assertThat(ReportLineFormatter.TRANSACTION_ID_WIDTH).isEqualTo(16);
            assertThat(ReportLineFormatter.ACCOUNT_ID_WIDTH).isEqualTo(11);
            assertThat(ReportLineFormatter.TYPE_CODE_WIDTH).isEqualTo(2);
            assertThat(ReportLineFormatter.CATEGORY_CODE_WIDTH).isEqualTo(4);
            assertThat(ReportLineFormatter.SOURCE_WIDTH).isEqualTo(10);
        }

        @Test
        @DisplayName("the header block is declared as four records")
        void headerBlockRecordCountIsFour() {
            assertThat(ReportLineFormatter.HEADER_BLOCK_RECORD_COUNT).isEqualTo(4);
        }
    }

    // =============================================================================================
    // Group 2 of the test plan: every builder produces exactly the record width.
    // =============================================================================================

    @Nested
    @DisplayName("Every builder produces exactly 133 encoded bytes")
    class EveryBuilderProducesTheRecordWidth {

        @Test
        @DisplayName("builder 1 of 7, the report name header, is 133 encoded bytes")
        void reportNameHeaderIsTheRecordWidth() {
            assertThat(asciiWidth(ReportLineFormatter.buildReportNameHeader(START_DATE, END_DATE)))
                    .isEqualTo(133);
        }

        @Test
        @DisplayName("builder 2 of 7, the transaction detail line, is 133 encoded bytes")
        void transactionDetailLineIsTheRecordWidth() {
            assertThat(asciiWidth(sampleDetailLine())).isEqualTo(133);
        }

        @Test
        @DisplayName("builder 3 of 7, the column header line, is 133 encoded bytes")
        void columnHeaderLineIsTheRecordWidth() {
            assertThat(asciiWidth(ReportLineFormatter.buildColumnHeaderLine())).isEqualTo(133);
        }

        @Test
        @DisplayName("builder 4 of 7, the rule line, is 133 encoded bytes")
        void ruleLineIsTheRecordWidth() {
            assertThat(asciiWidth(ReportLineFormatter.buildRuleLine())).isEqualTo(133);
        }

        @Test
        @DisplayName("builder 5 of 7, the page total line, is 133 encoded bytes")
        void pageTotalLineIsTheRecordWidth() {
            assertThat(asciiWidth(
                    ReportLineFormatter.buildPageTotalLine(new BigDecimal("1234567.89"))))
                    .isEqualTo(133);
        }

        @Test
        @DisplayName("builder 6 of 7, the account total line, is 133 encoded bytes")
        void accountTotalLineIsTheRecordWidth() {
            assertThat(asciiWidth(
                    ReportLineFormatter.buildAccountTotalLine(new BigDecimal("-603.22"))))
                    .isEqualTo(133);
        }

        @Test
        @DisplayName("builder 7 of 7, the grand total line, is 133 encoded bytes")
        void grandTotalLineIsTheRecordWidth() {
            assertThat(asciiWidth(
                    ReportLineFormatter.buildGrandTotalLine(new BigDecimal("0.00"))))
                    .isEqualTo(133);
        }

        @Test
        @DisplayName("the blank line accessor is 133 encoded bytes")
        void blankLineAccessorIsTheRecordWidth() {
            assertThat(asciiWidth(ReportLineFormatter.buildBlankLine())).isEqualTo(133);
        }
    }

    // =============================================================================================
    // Group 3 of the test plan: the report name header, group 1 of the layout.
    // =============================================================================================

    @Nested
    @DisplayName("Report name header, native width 115 with an 18-space right pad")
    class ReportNameHeaderGroup {

        @Test
        @DisplayName("the whole record matches the hand-written expectation byte for byte")
        void wholeRecordMatchesTheExpectation() {
            String header = ReportLineFormatter.buildReportNameHeader(START_DATE, END_DATE);

            assertThat(ascii(header))
                    .as("report name header, 133-byte image")
                    .isEqualTo(ascii(EXPECTED_NAME_HEADER));
            assertThat(asciiWidth(header)).isEqualTo(133);
        }

        @Test
        @DisplayName("the six component widths are 38, 41, 12, 10, 4 and 10")
        void sixComponentsSitAtTheirDeclaredOffsetsAndWidths() {
            String header = ReportLineFormatter.buildReportNameHeader(START_DATE, END_DATE);

            assertThat(ascii(window(header, 0, 38)))
                    .isEqualTo(ascii("DALYREPT" + spaces(30)));
            assertThat(ascii(window(header, 38, 41)))
                    .isEqualTo(ascii("Daily Transaction Report" + spaces(17)));
            assertThat(ascii(window(header, 79, 12))).isEqualTo(ascii("Date Range: "));
            assertThat(ascii(window(header, 91, 10))).isEqualTo(ascii("2022-01-01"));
            assertThat(ascii(window(header, 101, 4))).isEqualTo(ascii(" to "));
            assertThat(ascii(window(header, 105, 10))).isEqualTo(ascii("2022-12-31"));
        }

        @Test
        @DisplayName("the date caption is exactly twelve bytes including its own trailing space")
        void dateCaptionIsTwelveBytesIncludingItsTrailingSpace() {
            String header = ReportLineFormatter.buildReportNameHeader(START_DATE, END_DATE);
            String caption = window(header, 79, 12);

            assertThat(asciiWidth(caption)).isEqualTo(12);
            assertThat(ascii(caption)).isEqualTo(ascii("Date Range: "));
            assertThat(ascii(caption)[11]).isEqualTo((byte) ' ');
        }

        @Test
        @DisplayName("the separator is exactly four bytes with a space on both sides of the word")
        void separatorIsFourBytesWithASpaceOnEachSide() {
            String header = ReportLineFormatter.buildReportNameHeader(START_DATE, END_DATE);
            String separator = window(header, 101, 4);

            assertThat(asciiWidth(separator)).isEqualTo(4);
            assertThat(ascii(separator)).isEqualTo(ascii(" to "));
            assertThat(ascii(separator)[0]).isEqualTo((byte) ' ');
            assertThat(ascii(separator)[3]).isEqualTo((byte) ' ');
        }

        @Test
        @DisplayName("the two caller-supplied dates land at offsets 91 and 105")
        void callerSuppliedDatesLandAtTheirOffsets() {
            String header = ReportLineFormatter.buildReportNameHeader("1999-06-30", "2001-02-28");

            assertThat(ascii(window(header, 91, 10))).isEqualTo(ascii("1999-06-30"));
            assertThat(ascii(window(header, 105, 10))).isEqualTo(ascii("2001-02-28"));
        }

        @Test
        @DisplayName("the native width of 115 is followed by exactly 18 pad spaces")
        void eighteenPadSpacesFollowTheNativeWidth() {
            String header = ReportLineFormatter.buildReportNameHeader(START_DATE, END_DATE);

            assertThat(asciiWidth(window(header, 0, 115))).isEqualTo(115);
            assertThat(ascii(window(header, 115, 18))).isEqualTo(ascii(spaces(18)));
        }

        @Test
        @DisplayName("a date shorter than ten bytes is space-padded, a longer one is truncated")
        void shortDateIsPaddedAndLongDateIsTruncated() {
            String shortDates = ReportLineFormatter.buildReportNameHeader("2022", "1");
            assertThat(ascii(window(shortDates, 91, 10))).isEqualTo(ascii("2022" + spaces(6)));
            assertThat(ascii(window(shortDates, 105, 10))).isEqualTo(ascii("1" + spaces(9)));

            String longDates =
                    ReportLineFormatter.buildReportNameHeader("2022-01-01X", "2022-12-31Y");
            assertThat(ascii(window(longDates, 91, 10))).isEqualTo(ascii("2022-01-01"));
            assertThat(ascii(window(longDates, 105, 10))).isEqualTo(ascii("2022-12-31"));
            assertThat(asciiWidth(longDates)).isEqualTo(133);
        }
    }


    // =============================================================================================
    // Group 4 of the test plan: the column header, group 3 of the layout.
    // =============================================================================================

    @Nested
    @DisplayName("Column header line, native width 114 with a 19-space right pad")
    class ColumnHeaderGroup {

        @Test
        @DisplayName("the whole record matches the hand-written expectation byte for byte")
        void wholeRecordMatchesTheExpectation() {
            String header = ReportLineFormatter.buildColumnHeaderLine();

            assertThat(ascii(header))
                    .as("column header, 133-byte image")
                    .isEqualTo(ascii(EXPECTED_COLUMN_HEADER));
            assertThat(asciiWidth(header)).isEqualTo(133);
        }

        @Test
        @DisplayName("the seven component widths are 17, 12, 19, 35, 14, 1 and 16")
        void sevenComponentsSitAtTheirDeclaredOffsetsAndWidths() {
            String header = ReportLineFormatter.buildColumnHeaderLine();

            assertThat(ascii(window(header, 0, 17)))
                    .isEqualTo(ascii("Transaction ID" + spaces(3)));
            assertThat(ascii(window(header, 17, 12)))
                    .isEqualTo(ascii("Account ID" + spaces(2)));
            assertThat(ascii(window(header, 29, 19)))
                    .isEqualTo(ascii("Transaction Type" + spaces(3)));
            assertThat(ascii(window(header, 48, 35)))
                    .isEqualTo(ascii("Tran Category" + spaces(22)));
            assertThat(ascii(window(header, 83, 14)))
                    .isEqualTo(ascii("Tran Source" + spaces(3)));
            assertThat(ascii(window(header, 97, 1))).isEqualTo(ascii(spaces(1)));
            assertThat(ascii(window(header, 98, 16)))
                    .isEqualTo(ascii(spaces(8) + "Amount" + spaces(2)));
        }

        @Test
        @DisplayName("the bare component between the source and amount captions is exactly one byte")
        void bareComponentIsExactlyOneByte() {
            String header = ReportLineFormatter.buildColumnHeaderLine();
            String bare = window(header, 97, 1);

            assertThat(asciiWidth(bare)).isEqualTo(1);
            assertThat(ascii(bare)).isEqualTo(ascii(spaces(1)));
            assertThat(ascii(header)[97]).isEqualTo((byte) ' ');
        }

        @Test
        @DisplayName("the amount caption carries eight leading spaces before the word")
        void amountCaptionCarriesEightLeadingSpaces() {
            String header = ReportLineFormatter.buildColumnHeaderLine();
            byte[] caption = ascii(window(header, 98, 16));

            assertThat(caption).hasSize(16);
            for (int index = 0; index < 8; index++) {
                assertThat(caption[index])
                        .as("amount caption byte %d of the eight leading spaces", index)
                        .isEqualTo((byte) ' ');
            }
            assertThat(caption[8]).isEqualTo((byte) 'A');
        }

        @Test
        @DisplayName("the amount caption word occupies report positions 106 through 111")
        void amountCaptionWordOccupiesPositions106Through111() {
            String header = ReportLineFormatter.buildColumnHeaderLine();

            assertThat(ascii(window(header, 106, 6))).isEqualTo(ascii("Amount"));
            assertThat(ascii(header)[105]).isEqualTo((byte) ' ');
            assertThat(ascii(header)[112]).isEqualTo((byte) ' ');
        }

        @Test
        @DisplayName("the native width of 114 is followed by exactly 19 pad spaces")
        void nineteenPadSpacesFollowTheNativeWidth() {
            String header = ReportLineFormatter.buildColumnHeaderLine();

            assertThat(asciiWidth(window(header, 0, 114))).isEqualTo(114);
            assertThat(ascii(window(header, 114, 19))).isEqualTo(ascii(spaces(19)));
        }

        @Test
        @DisplayName("the column header takes no argument and is stable across calls")
        void columnHeaderIsFullyLiteral() {
            assertThat(ascii(ReportLineFormatter.buildColumnHeaderLine()))
                    .isEqualTo(ascii(EXPECTED_COLUMN_HEADER));
            assertThat(ascii(ReportLineFormatter.buildColumnHeaderLine()))
                    .isEqualTo(ascii(EXPECTED_COLUMN_HEADER));
        }
    }

    // =============================================================================================
    // Group 5 of the test plan: the rule line, group 4 of the layout.
    // =============================================================================================

    @Nested
    @DisplayName("Rule line, the only natively-133 group")
    class RuleLineGroup {

        @Test
        @DisplayName("the published rule-line constant is exactly 133 hyphen bytes")
        void ruleLineConstantIsOneHundredThirtyThreeHyphens() {
            assertThat(asciiWidth(ReportLineFormatter.RULE_LINE)).isEqualTo(133);
            assertThat(ascii(ReportLineFormatter.RULE_LINE))
                    .as("rule line, 133-byte image")
                    .isEqualTo(ascii(EXPECTED_RULE_LINE));
            assertThat(countBytes(ReportLineFormatter.RULE_LINE, '-')).isEqualTo(133);
        }

        @Test
        @DisplayName("the rule-line builder returns the same 133 hyphen bytes with no padding")
        void ruleLineBuilderMatchesTheConstant() {
            String rule = ReportLineFormatter.buildRuleLine();

            assertThat(asciiWidth(rule)).isEqualTo(133);
            assertThat(ascii(rule)).isEqualTo(ascii(EXPECTED_RULE_LINE));
            assertThat(countBytes(rule, '-')).isEqualTo(133);
            assertThat(countBytes(rule, ' ')).isEqualTo(0);
        }

        @Test
        @DisplayName("the report rule line is 133 hyphens, not the statement's 80")
        void reportRuleLineIsNotTheStatementRuleLine() {
            assertThat(asciiWidth(ReportLineFormatter.RULE_LINE)).isEqualTo(133);
            assertThat(asciiWidth(ReportLineFormatter.RULE_LINE)).isNotEqualTo(80);
            assertThat(asciiWidth(ReportLineFormatter.RULE_LINE)).isNotEqualTo(100);
        }
    }

    // =============================================================================================
    // Group 6 of the test plan: the blank line, a real emitted record.
    // =============================================================================================

    @Nested
    @DisplayName("Blank line, 133 space bytes and a real emitted record")
    class BlankLineConstant {

        @Test
        @DisplayName("the published blank-line constant is exactly 133 bytes of 0x20")
        void blankLineConstantIsOneHundredThirtyThreeSpaces() {
            byte[] blank = ascii(ReportLineFormatter.BLANK_LINE);

            assertThat(blank).hasSize(133);
            assertThat(ascii(ReportLineFormatter.BLANK_LINE))
                    .isEqualTo(ascii(EXPECTED_BLANK_LINE));
            for (int index = 0; index < 133; index++) {
                assertThat(blank[index])
                        .as("blank line byte %d", index)
                        .isEqualTo((byte) 0x20);
            }
        }

        @Test
        @DisplayName("the blank-line accessor returns the same 133 space bytes")
        void blankLineAccessorMatchesTheConstant() {
            String blank = ReportLineFormatter.buildBlankLine();

            assertThat(asciiWidth(blank)).isEqualTo(133);
            assertThat(ascii(blank)).isEqualTo(ascii(EXPECTED_BLANK_LINE));
            assertThat(countBytes(blank, ' ')).isEqualTo(133);
        }
    }

    // =============================================================================================
    // Group 7 of the test plan: the transaction detail line, group 2 of the layout, and the two
    // traps it carries - the surviving hyphen separators and the two unequal truncations.
    // =============================================================================================

    @Nested
    @DisplayName("Transaction detail line, native width 114 with a 19-space right pad")
    class TransactionDetailGroup {

        @Test
        @DisplayName("the whole record matches the hand-written expectation byte for byte")
        void wholeRecordMatchesTheExpectation() {
            String detail = sampleDetailLine();

            assertThat(ascii(detail))
                    .as("transaction detail line, 133-byte image")
                    .isEqualTo(ascii(EXPECTED_DETAIL_LINE));
            assertThat(asciiWidth(detail)).isEqualTo(133);
        }

        @Test
        @DisplayName("all sixteen items sit at their tabulated offset and width")
        void allSixteenItemsSitAtTheirTabulatedOffsetAndWidth() {
            String detail = sampleDetailLine();

            assertThat(ascii(window(detail, 0, 16))).isEqualTo(ascii("0000000000000001"));
            assertThat(ascii(window(detail, 16, 1))).isEqualTo(ascii(spaces(1)));
            assertThat(ascii(window(detail, 17, 11))).isEqualTo(ascii("00000000011"));
            assertThat(ascii(window(detail, 28, 1))).isEqualTo(ascii(spaces(1)));
            assertThat(ascii(window(detail, 29, 2))).isEqualTo(ascii("01"));
            assertThat(ascii(window(detail, 31, 1))).isEqualTo(ascii("-"));
            assertThat(ascii(window(detail, 32, 15))).isEqualTo(ascii("Purchase" + spaces(7)));
            assertThat(ascii(window(detail, 47, 1))).isEqualTo(ascii(spaces(1)));
            assertThat(ascii(window(detail, 48, 4))).isEqualTo(ascii("0001"));
            assertThat(ascii(window(detail, 52, 1))).isEqualTo(ascii("-"));
            assertThat(ascii(window(detail, 53, 29)))
                    .isEqualTo(ascii("Sales draft credit adjustment"));
            assertThat(ascii(window(detail, 82, 1))).isEqualTo(ascii(spaces(1)));
            assertThat(ascii(window(detail, 83, 10))).isEqualTo(ascii("POS TERM" + spaces(2)));
            assertThat(ascii(window(detail, 93, 4))).isEqualTo(ascii(spaces(4)));
            assertThat(ascii(window(detail, 97, 15))).isEqualTo(ascii(EXPECTED_DETAIL_MASK_POSITIVE));
            assertThat(ascii(window(detail, 112, 2))).isEqualTo(ascii(spaces(2)));
        }

        @Test
        @DisplayName("the amount occupies offsets 97 through 111 inclusive")
        void amountOccupiesOffsets97Through111() {
            String detail = sampleDetailLine();

            assertThat(ascii(window(detail, 97, 15)))
                    .isEqualTo(ascii(EXPECTED_DETAIL_MASK_POSITIVE));
            assertThat(ascii(detail)[111]).isEqualTo((byte) '7');
            assertThat(ascii(detail)[112]).isEqualTo((byte) ' ');
        }

        @Test
        @DisplayName("the native width of 114 is followed by exactly 19 pad spaces")
        void nineteenPadSpacesFollowTheNativeWidth() {
            String detail = sampleDetailLine();

            assertThat(asciiWidth(window(detail, 0, 114))).isEqualTo(114);
            assertThat(ascii(window(detail, 114, 19))).isEqualTo(ascii(spaces(19)));
        }

        @Test
        @DisplayName("TRAP: both literal hyphen separators survive at offsets 31 and 52")
        void bothHyphenSeparatorsArePresentInAPopulatedLine() {
            String detail = sampleDetailLine();

            assertThat(ascii(detail)[31]).as("separator at offset 31").isEqualTo((byte) '-');
            assertThat(ascii(detail)[52]).as("separator at offset 52").isEqualTo((byte) '-');
        }

        @Test
        @DisplayName("TRAP: both hyphen separators survive even when every value supplied is empty")
        void bothHyphenSeparatorsSurviveAnEntirelyEmptyLine() {
            // The driver initialises the group before moving values in, and an initialise leaves a
            // filler item carrying a literal value untouched. The hyphens are fixed layout bytes.
            String detail = ReportLineFormatter.buildTransactionDetailLine(
                    "", "", "", "", 0, "", "", new BigDecimal("0.00"));

            assertThat(ascii(detail)[31]).as("separator at offset 31").isEqualTo((byte) '-');
            assertThat(ascii(detail)[52]).as("separator at offset 52").isEqualTo((byte) '-');
            assertThat(ascii(detail))
                    .as("all-empty detail line, 133-byte image")
                    .isEqualTo(ascii(EXPECTED_DETAIL_LINE_ALL_EMPTY));
            assertThat(asciiWidth(detail)).isEqualTo(133);
            assertThat(countBytes(detail, '-')).isEqualTo(2);
        }

        @Test
        @DisplayName("TRAP: both hyphen separators survive when the amount is negative")
        void bothHyphenSeparatorsSurviveANegativeAmount() {
            String detail = ReportLineFormatter.buildTransactionDetailLine(
                    "0000000000000002", "00000000012", "02", "Payment", 2,
                    "Refund adjustment", "OPERATOR", new BigDecimal("-603.22"));

            assertThat(ascii(detail)[31]).isEqualTo((byte) '-');
            assertThat(ascii(detail)[52]).isEqualTo((byte) '-');
            assertThat(ascii(window(detail, 97, 15)))
                    .isEqualTo(ascii(EXPECTED_DETAIL_MASK_NEGATIVE));
            // Two layout hyphens plus the mask's own minus sign, and nothing else.
            assertThat(countBytes(detail, '-')).isEqualTo(3);
        }

        @Test
        @DisplayName("TRAP: the type description truncates at 15, which is not the other width")
        void typeDescriptionTruncatesAtFifteen() {
            String sixteenCharacters =
                    detailLineWithTypeDescription("ABCDEFGHIJKLMNOP");
            assertThat(ascii(window(sixteenCharacters, 32, 15)))
                    .as("a sixteen-character type description loses exactly its final character")
                    .isEqualTo(ascii("ABCDEFGHIJKLMNO"));

            String fifteenCharacters = detailLineWithTypeDescription("ABCDEFGHIJKLMNO");
            assertThat(ascii(window(fifteenCharacters, 32, 15)))
                    .as("a fifteen-character type description is unchanged")
                    .isEqualTo(ascii("ABCDEFGHIJKLMNO"));

            String shortValue = detailLineWithTypeDescription("Refund");
            assertThat(ascii(window(shortValue, 32, 15)))
                    .as("a shorter type description is space-padded on the right")
                    .isEqualTo(ascii("Refund" + spaces(9)));

            assertThat(ReportLineFormatter.TYPE_DESCRIPTION_WIDTH).isEqualTo(15);
        }

        @Test
        @DisplayName("TRAP: the category description truncates at 29, its own separate width")
        void categoryDescriptionTruncatesAtTwentyNine() {
            String thirtyCharacters =
                    detailLineWithCategoryDescription("Sales draft credit adjustmentZ");
            assertThat(ascii(window(thirtyCharacters, 53, 29)))
                    .as("a thirty-character category description loses exactly its final character")
                    .isEqualTo(ascii("Sales draft credit adjustment"));

            String boundaryValue =
                    detailLineWithCategoryDescription("Sales draft credit adjustment");
            assertThat(ascii(window(boundaryValue, 53, 29)))
                    .as("the twenty-nine-character boundary value fits exactly and unchanged")
                    .isEqualTo(ascii("Sales draft credit adjustment"));

            String shortValue = detailLineWithCategoryDescription("Mixed Case Desc");
            assertThat(ascii(window(shortValue, 53, 29)))
                    .as("a shorter category description is space-padded on the right")
                    .isEqualTo(ascii("Mixed Case Desc" + spaces(14)));

            assertThat(ReportLineFormatter.CATEGORY_DESCRIPTION_WIDTH).isEqualTo(29);
        }

        @Test
        @DisplayName("TRAP: the two truncation widths are 15 and 29 and are never shared")
        void theTwoTruncationWidthsAreIndependent() {
            assertThat(ReportLineFormatter.TYPE_DESCRIPTION_WIDTH).isEqualTo(15);
            assertThat(ReportLineFormatter.CATEGORY_DESCRIPTION_WIDTH).isEqualTo(29);
            assertThat(ReportLineFormatter.TYPE_DESCRIPTION_WIDTH)
                    .isNotEqualTo(ReportLineFormatter.CATEGORY_DESCRIPTION_WIDTH);

            // The same fifty-byte source value reaches the two fields at two different widths in
            // the same line, which is exactly where a shared constant would go unnoticed.
            String fiftyCharacters = "A".repeat(50);
            String detail = ReportLineFormatter.buildTransactionDetailLine(
                    "", "", "", fiftyCharacters, 0, fiftyCharacters, "",
                    new BigDecimal("0.00"));

            assertThat(ascii(window(detail, 32, 15))).isEqualTo(ascii("A".repeat(15)));
            assertThat(ascii(window(detail, 53, 29))).isEqualTo(ascii("A".repeat(29)));
            assertThat(asciiWidth(detail)).isEqualTo(133);
        }

        @Test
        @DisplayName("mixed case in a description is preserved verbatim, never folded")
        void mixedCaseInADescriptionIsPreservedVerbatim() {
            String detail = ReportLineFormatter.buildTransactionDetailLine(
                    "", "", "", "MiXeD TyPe", 0, "MiXeD CaSe DeScRiPtIoN", "",
                    new BigDecimal("0.00"));

            assertThat(ascii(window(detail, 32, 15))).isEqualTo(ascii("MiXeD TyPe" + spaces(5)));
            assertThat(ascii(window(detail, 53, 29)))
                    .isEqualTo(ascii("MiXeD CaSe DeScRiPtIoN" + spaces(7)));
        }

        @Test
        @DisplayName("the category code is left zero-filled across its four-byte window")
        void categoryCodeIsLeftZeroFilled() {
            assertThat(ascii(window(detailLineWithCategoryCode(1), 48, 4)))
                    .isEqualTo(ascii("0001"));
            assertThat(ascii(window(detailLineWithCategoryCode(0), 48, 4)))
                    .isEqualTo(ascii("0000"));
            assertThat(ascii(window(detailLineWithCategoryCode(12), 48, 4)))
                    .isEqualTo(ascii("0012"));
            assertThat(ascii(window(detailLineWithCategoryCode(123), 48, 4)))
                    .isEqualTo(ascii("0123"));
            assertThat(ascii(window(detailLineWithCategoryCode(9999), 48, 4)))
                    .isEqualTo(ascii("9999"));
        }

        @Test
        @DisplayName("a category code outside four unsigned digits is rejected, never truncated")
        void categoryCodeOutsideFourUnsignedDigitsIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> detailLineWithCategoryCode(-1))
                    .withMessageContaining("transactionCategoryCode")
                    .withMessageContaining("9999");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> detailLineWithCategoryCode(10000))
                    .withMessageContaining("transactionCategoryCode")
                    .withMessageContaining("10000");
        }

        @Test
        @DisplayName("the two hyphen-separator offset constants are 31 and 52")
        void hyphenSeparatorOffsetConstantsAre31And52() {
            assertThat(ReportLineFormatter.TYPE_CODE_SEPARATOR_OFFSET).isEqualTo(31);
            assertThat(ReportLineFormatter.CATEGORY_CODE_SEPARATOR_OFFSET).isEqualTo(52);
        }
    }


    // =============================================================================================
    // Group 8 of the test plan: the three total lines, groups 5, 6 and 7 of the layout, and the
    // trap they carry - three dot-fill widths whose arithmetic coincidence must not become a rule.
    // =============================================================================================

    @Nested
    @DisplayName("Total lines, three groups of native width 112 with a 21-space right pad")
    class TotalLineGroups {

        @Test
        @DisplayName("the page total record matches the hand-written expectation byte for byte")
        void pageTotalRecordMatchesTheExpectation() {
            String line = ReportLineFormatter.buildPageTotalLine(new BigDecimal("1234567.89"));

            assertThat(ascii(line))
                    .as("page total line, 133-byte image")
                    .isEqualTo(ascii(EXPECTED_PAGE_TOTAL_LINE));
            assertThat(asciiWidth(line)).isEqualTo(133);
        }

        @Test
        @DisplayName("the account total record matches the hand-written expectation byte for byte")
        void accountTotalRecordMatchesTheExpectation() {
            String line = ReportLineFormatter.buildAccountTotalLine(new BigDecimal("-603.22"));

            assertThat(ascii(line))
                    .as("account total line, 133-byte image")
                    .isEqualTo(ascii(EXPECTED_ACCOUNT_TOTAL_LINE));
            assertThat(asciiWidth(line)).isEqualTo(133);
        }

        @Test
        @DisplayName("the grand total record matches the hand-written expectation byte for byte")
        void grandTotalRecordMatchesTheExpectation() {
            String line = ReportLineFormatter.buildGrandTotalLine(new BigDecimal("0.00"));

            assertThat(ascii(line))
                    .as("grand total line, 133-byte image")
                    .isEqualTo(ascii(EXPECTED_GRAND_TOTAL_LINE));
            assertThat(asciiWidth(line)).isEqualTo(133);
        }

        @Test
        @DisplayName("TRAP: the page-total dot fill is 86 periods, hardcoded and never computed")
        void pageTotalDotFillIsEightySixPeriods() {
            // Hardcoded from the copybook. Not derived from the account fill, not derived from the
            // grand fill and not derived from the shared amount offset less the caption width.
            assertThat(ReportLineFormatter.PAGE_TOTAL_DOT_FILL_WIDTH).isEqualTo(86);

            String line = ReportLineFormatter.buildPageTotalLine(new BigDecimal("1234567.89"));
            assertThat(ascii(window(line, 11, 86))).isEqualTo(ascii(dots(86)));

            String blankAmount = ReportLineFormatter.buildPageTotalLine(new BigDecimal("0.00"));
            assertThat(countBytes(blankAmount, '.'))
                    .as("with a fully blank amount field the only periods left are the dot fill")
                    .isEqualTo(86);
        }

        @Test
        @DisplayName("TRAP: the account-total dot fill is 84 periods, hardcoded and never computed")
        void accountTotalDotFillIsEightyFourPeriods() {
            assertThat(ReportLineFormatter.ACCOUNT_TOTAL_DOT_FILL_WIDTH).isEqualTo(84);

            String line = ReportLineFormatter.buildAccountTotalLine(new BigDecimal("-603.22"));
            assertThat(ascii(window(line, 13, 84))).isEqualTo(ascii(dots(84)));

            String blankAmount = ReportLineFormatter.buildAccountTotalLine(new BigDecimal("0.00"));
            assertThat(countBytes(blankAmount, '.')).isEqualTo(84);
        }

        @Test
        @DisplayName("TRAP: the grand-total dot fill is 86 periods, hardcoded and never computed")
        void grandTotalDotFillIsEightySixPeriods() {
            assertThat(ReportLineFormatter.GRAND_TOTAL_DOT_FILL_WIDTH).isEqualTo(86);

            String line = ReportLineFormatter.buildGrandTotalLine(new BigDecimal("1234567.89"));
            assertThat(ascii(window(line, 11, 86))).isEqualTo(ascii(dots(86)));

            String blankAmount = ReportLineFormatter.buildGrandTotalLine(new BigDecimal("0.00"));
            assertThat(countBytes(blankAmount, '.')).isEqualTo(86);
        }

        @Test
        @DisplayName("TRAP: the three dot-fill widths are three separate values, 86, 84 and 86")
        void theThreeDotFillWidthsAreThreeSeparateValues() {
            assertThat(ReportLineFormatter.PAGE_TOTAL_DOT_FILL_WIDTH).isEqualTo(86);
            assertThat(ReportLineFormatter.ACCOUNT_TOTAL_DOT_FILL_WIDTH).isEqualTo(84);
            assertThat(ReportLineFormatter.GRAND_TOTAL_DOT_FILL_WIDTH).isEqualTo(86);
            assertThat(ReportLineFormatter.ACCOUNT_TOTAL_DOT_FILL_WIDTH)
                    .as("the account fill differs from the other two, which is the whole point")
                    .isNotEqualTo(ReportLineFormatter.PAGE_TOTAL_DOT_FILL_WIDTH);
        }

        @Test
        @DisplayName("the fill character is the period, never a hyphen or an underscore")
        void theFillCharacterIsThePeriod() {
            String line = ReportLineFormatter.buildPageTotalLine(new BigDecimal("0.00"));

            assertThat(countBytes(line, '.')).isEqualTo(86);
            assertThat(countBytes(line, '-')).isEqualTo(0);
            assertThat(countBytes(line, '_')).isEqualTo(0);
        }

        @Test
        @DisplayName("the three caption widths are 11, 13 and 11")
        void theThreeCaptionWidthsAre11And13And11() {
            String page = ReportLineFormatter.buildPageTotalLine(new BigDecimal("0.00"));
            String account = ReportLineFormatter.buildAccountTotalLine(new BigDecimal("0.00"));
            String grand = ReportLineFormatter.buildGrandTotalLine(new BigDecimal("0.00"));

            assertThat(ascii(window(page, 0, 11))).isEqualTo(ascii("Page Total" + spaces(1)));
            assertThat(ascii(window(account, 0, 13))).isEqualTo(ascii("Account Total"));
            assertThat(ascii(window(grand, 0, 11))).isEqualTo(ascii("Grand Total"));

            assertThat(asciiWidth(window(page, 0, 11))).isEqualTo(11);
            assertThat(asciiWidth(window(account, 0, 13))).isEqualTo(13);
            assertThat(asciiWidth(window(grand, 0, 11))).isEqualTo(11);
        }

        @Test
        @DisplayName("all three amounts begin at offset 97 and share their right edge at 111")
        void allThreeAmountsBeginAtOffset97AndEndAtOffset111() {
            assertThat(ReportLineFormatter.AMOUNT_OFFSET).isEqualTo(97);

            String page = ReportLineFormatter.buildPageTotalLine(new BigDecimal("500.47"));
            String account = ReportLineFormatter.buildAccountTotalLine(new BigDecimal("500.47"));
            String grand = ReportLineFormatter.buildGrandTotalLine(new BigDecimal("500.47"));

            assertThat(ascii(window(page, 97, 15)))
                    .isEqualTo(ascii(EXPECTED_TOTAL_MASK_POSITIVE));
            assertThat(ascii(window(account, 97, 15)))
                    .isEqualTo(ascii(EXPECTED_TOTAL_MASK_POSITIVE));
            assertThat(ascii(window(grand, 97, 15)))
                    .isEqualTo(ascii(EXPECTED_TOTAL_MASK_POSITIVE));

            assertThat(ascii(page)[111]).as("page total right edge").isEqualTo((byte) '7');
            assertThat(ascii(account)[111]).as("account total right edge").isEqualTo((byte) '7');
            assertThat(ascii(grand)[111]).as("grand total right edge").isEqualTo((byte) '7');
        }

        @Test
        @DisplayName("the detail amount shares offset 97 and edge 111 with the three totals")
        void theDetailAmountSharesTheColumnWithTheThreeTotals() {
            String detail = sampleDetailLine();
            String page = ReportLineFormatter.buildPageTotalLine(new BigDecimal("500.47"));

            assertThat(ascii(window(detail, 97, 15)))
                    .isEqualTo(ascii(EXPECTED_DETAIL_MASK_POSITIVE));
            assertThat(ascii(window(page, 97, 15)))
                    .isEqualTo(ascii(EXPECTED_TOTAL_MASK_POSITIVE));
            // Both records carry the final decimal digit of 500.47 on offset 111. Each is asserted
            // against the hand-written expectation rather than against the other.
            assertThat(ascii(detail)[111]).as("detail amount right edge").isEqualTo((byte) '7');
            assertThat(ascii(page)[111]).as("page total right edge").isEqualTo((byte) '7');
        }

        @Test
        @DisplayName("each total line's native width of 112 is followed by exactly 21 pad spaces")
        void twentyOnePadSpacesFollowEachNativeWidth() {
            String page = ReportLineFormatter.buildPageTotalLine(new BigDecimal("0.00"));
            String account = ReportLineFormatter.buildAccountTotalLine(new BigDecimal("0.00"));
            String grand = ReportLineFormatter.buildGrandTotalLine(new BigDecimal("0.00"));

            assertThat(asciiWidth(window(page, 0, 112))).isEqualTo(112);
            assertThat(ascii(window(page, 112, 21))).isEqualTo(ascii(spaces(21)));
            assertThat(ascii(window(account, 112, 21))).isEqualTo(ascii(spaces(21)));
            assertThat(ascii(window(grand, 112, 21))).isEqualTo(ascii(spaces(21)));
        }

        @Test
        @DisplayName("all three total lines use the always-signed mask, never the detail mask")
        void allThreeTotalLinesUseTheAlwaysSignedMask() {
            String page = ReportLineFormatter.buildPageTotalLine(new BigDecimal("1.00"));
            String account = ReportLineFormatter.buildAccountTotalLine(new BigDecimal("1.00"));
            String grand = ReportLineFormatter.buildGrandTotalLine(new BigDecimal("1.00"));

            assertThat(ascii(page)[97]).as("page total sign position").isEqualTo((byte) '+');
            assertThat(ascii(account)[97]).as("account total sign position").isEqualTo((byte) '+');
            assertThat(ascii(grand)[97]).as("grand total sign position").isEqualTo((byte) '+');
        }
    }

    // =============================================================================================
    // Group 9 of the test plan: the detail mask. Fifteen characters, a fixed sign position and no
    // plus, ever.
    // =============================================================================================

    @Nested
    @DisplayName("Detail amount mask, fifteen characters, non-negative renders a space")
    class DetailAmountMask {

        @Test
        @DisplayName("TRAP: a positive 500.47 is nine spaces then the digits, asserted byte by byte")
        void positiveValueIsNineSpacesThenTheDigits() {
            String rendered = ReportLineFormatter.renderDetailAmount(new BigDecimal("500.47"));
            byte[] bytes = ascii(rendered);

            assertThat(bytes).hasSize(15);
            for (int index = 0; index < 9; index++) {
                assertThat(bytes[index])
                        .as("detail mask byte %d, expected one of the nine leading spaces", index)
                        .isEqualTo((byte) ' ');
            }
            assertThat(bytes[9]).isEqualTo((byte) '5');
            assertThat(bytes[10]).isEqualTo((byte) '0');
            assertThat(bytes[11]).isEqualTo((byte) '0');
            assertThat(bytes[12]).isEqualTo((byte) '.');
            assertThat(bytes[13]).isEqualTo((byte) '4');
            assertThat(bytes[14]).isEqualTo((byte) '7');
            assertThat(bytes).isEqualTo(ascii(EXPECTED_DETAIL_MASK_POSITIVE));
        }

        @Test
        @DisplayName("TRAP: a negative 603.22 puts the minus in position one, then EIGHT spaces, "
                + "because the sign is a fixed insertion character that never shifts rightward "
                + "against the first digit")
        void negativeValuePutsTheMinusInPositionOneThenEightSpaces() {
            String rendered = ReportLineFormatter.renderDetailAmount(new BigDecimal("-603.22"));
            byte[] bytes = ascii(rendered);

            assertThat(bytes).hasSize(15);
            assertThat(bytes[0])
                    .as("the sign sits in position one, not immediately before the first digit")
                    .isEqualTo((byte) '-');
            for (int index = 1; index < 9; index++) {
                assertThat(bytes[index])
                        .as("detail mask byte %d, expected one of the eight suppressed positions "
                                + "between the sign and the digits", index)
                        .isEqualTo((byte) ' ');
            }
            assertThat(bytes[9]).isEqualTo((byte) '6');
            assertThat(bytes[10]).isEqualTo((byte) '0');
            assertThat(bytes[11]).isEqualTo((byte) '3');
            assertThat(bytes[12]).isEqualTo((byte) '.');
            assertThat(bytes[13]).isEqualTo((byte) '2');
            assertThat(bytes[14]).isEqualTo((byte) '2');
            assertThat(bytes).isEqualTo(ascii(EXPECTED_DETAIL_MASK_NEGATIVE));
        }

        @Test
        @DisplayName("TRAP: the detail mask never emits a plus, for a positive, a zero or a "
                + "negative value")
        void theDetailMaskNeverEmitsAPlus() {
            assertThat(ascii(ReportLineFormatter.renderDetailAmount(new BigDecimal("500.47"))))
                    .doesNotContain((byte) '+');
            assertThat(ascii(ReportLineFormatter.renderDetailAmount(new BigDecimal("0.00"))))
                    .doesNotContain((byte) '+');
            assertThat(ascii(ReportLineFormatter.renderDetailAmount(new BigDecimal("-603.22"))))
                    .doesNotContain((byte) '+');
            assertThat(ascii(sampleDetailLine()))
                    .as("nor does a whole detail record ever carry a plus")
                    .doesNotContain((byte) '+');
        }

        @Test
        @DisplayName("at full width both group separators print and the sign stays in position one")
        void atFullWidthBothGroupSeparatorsPrint() {
            String rendered =
                    ReportLineFormatter.renderDetailAmount(new BigDecimal("999999999.99"));

            assertThat(ascii(rendered)).isEqualTo(ascii(EXPECTED_DETAIL_MASK_FULL_WIDTH));
            assertThat(ascii(rendered)[0]).isEqualTo((byte) ' ');
            assertThat(ascii(rendered)[4]).isEqualTo((byte) ',');
            assertThat(ascii(rendered)[8]).isEqualTo((byte) ',');
            assertThat(asciiWidth(rendered)).isEqualTo(15);
        }

        @Test
        @DisplayName("a wholly suppressed integer part leaves the point and the two decimal digits")
        void aWhollySuppressedIntegerPartLeavesThePointAndTheDecimals() {
            String positive = ReportLineFormatter.renderDetailAmount(new BigDecimal("0.47"));
            assertThat(ascii(positive))
                    .isEqualTo(ascii(EXPECTED_DETAIL_MASK_ZERO_INTEGER_PART));
            assertThat(ascii(positive)).doesNotContain((byte) ',');

            String negative = ReportLineFormatter.renderDetailAmount(new BigDecimal("-0.47"));
            assertThat(ascii(negative)[0]).isEqualTo((byte) '-');
            assertThat(ascii(negative)[12]).isEqualTo((byte) '.');
            assertThat(asciiWidth(negative)).isEqualTo(15);
        }

        @Test
        @DisplayName("a group separator prints only once a digit to its left has printed")
        void aGroupSeparatorPrintsOnlyOnceADigitToItsLeftHasPrinted() {
            String oneSeparator = ReportLineFormatter.renderDetailAmount(new BigDecimal("1000.00"));
            assertThat(ascii(oneSeparator))
                    .isEqualTo(ascii(EXPECTED_DETAIL_MASK_ONE_SEPARATOR));
            assertThat(countBytes(oneSeparator, ',')).isEqualTo(1);

            String twoSeparators =
                    ReportLineFormatter.renderDetailAmount(new BigDecimal("1000000.00"));
            assertThat(ascii(twoSeparators))
                    .isEqualTo(ascii(EXPECTED_DETAIL_MASK_TWO_SEPARATORS));
            assertThat(countBytes(twoSeparators, ',')).isEqualTo(2);

            String noSeparator = ReportLineFormatter.renderDetailAmount(new BigDecimal("999.99"));
            assertThat(countBytes(noSeparator, ',')).isEqualTo(0);
            assertThat(asciiWidth(noSeparator)).isEqualTo(15);
        }
    }

    // =============================================================================================
    // Group 10 of the test plan: the totals mask. Fifteen characters and ALWAYS signed.
    // =============================================================================================

    @Nested
    @DisplayName("Total amount mask, fifteen characters, always signed")
    class TotalAmountMask {

        @Test
        @DisplayName("a positive 500.47 puts a PLUS in position one, then eight spaces")
        void positiveValuePutsAPlusInPositionOne() {
            String rendered = ReportLineFormatter.renderTotalAmount(new BigDecimal("500.47"));
            byte[] bytes = ascii(rendered);

            assertThat(bytes).hasSize(15);
            assertThat(bytes[0]).as("the sign position of a non-negative total")
                    .isEqualTo((byte) '+');
            for (int index = 1; index < 9; index++) {
                assertThat(bytes[index])
                        .as("total mask byte %d, expected a suppressed position", index)
                        .isEqualTo((byte) ' ');
            }
            assertThat(bytes).isEqualTo(ascii(EXPECTED_TOTAL_MASK_POSITIVE));
        }

        @Test
        @DisplayName("a negative 603.22 puts a MINUS in position one, then eight spaces")
        void negativeValuePutsAMinusInPositionOne() {
            String rendered = ReportLineFormatter.renderTotalAmount(new BigDecimal("-603.22"));
            byte[] bytes = ascii(rendered);

            assertThat(bytes).hasSize(15);
            assertThat(bytes[0]).isEqualTo((byte) '-');
            for (int index = 1; index < 9; index++) {
                assertThat(bytes[index]).isEqualTo((byte) ' ');
            }
            assertThat(bytes[9]).isEqualTo((byte) '6');
            assertThat(bytes[14]).isEqualTo((byte) '2');
            assertThat(bytes).isEqualTo(ascii(EXPECTED_TOTAL_MASK_NEGATIVE));
        }

        @Test
        @DisplayName("the sign is never omitted from a non-negative total")
        void theSignIsNeverOmittedFromANonNegativeTotal() {
            assertThat(ascii(ReportLineFormatter.renderTotalAmount(new BigDecimal("1.00")))[0])
                    .isEqualTo((byte) '+');
            assertThat(ascii(ReportLineFormatter.renderTotalAmount(new BigDecimal("999.99")))[0])
                    .isEqualTo((byte) '+');
            assertThat(ascii(
                    ReportLineFormatter.renderTotalAmount(new BigDecimal("999999999.99")))[0])
                    .isEqualTo((byte) '+');
        }

        @Test
        @DisplayName("at full width the sign sits hard against the leading digit")
        void atFullWidthTheSignSitsAgainstTheLeadingDigit() {
            String rendered =
                    ReportLineFormatter.renderTotalAmount(new BigDecimal("999999999.99"));

            assertThat(ascii(rendered)).isEqualTo(ascii(EXPECTED_TOTAL_MASK_FULL_WIDTH));
            assertThat(ascii(rendered)[0]).isEqualTo((byte) '+');
            assertThat(ascii(rendered)[1]).isEqualTo((byte) '9');
            assertThat(asciiWidth(rendered)).isEqualTo(15);
        }

        @Test
        @DisplayName("a wholly suppressed integer part still carries the sign")
        void aWhollySuppressedIntegerPartStillCarriesTheSign() {
            assertThat(ascii(ReportLineFormatter.renderTotalAmount(new BigDecimal("0.47"))))
                    .isEqualTo(ascii(EXPECTED_TOTAL_MASK_ZERO_INTEGER_PART));
            assertThat(ascii(ReportLineFormatter.renderTotalAmount(new BigDecimal("-0.47")))[0])
                    .isEqualTo((byte) '-');
        }
    }


    // =============================================================================================
    // Group 11 of the test plan: what the two masks share, what they must never share, and the two
    // value rejections. The two renderers are always called by their own distinct names here;
    // nothing in this file selects between them with a flag, a parameter or a switch.
    // =============================================================================================

    @Nested
    @DisplayName("Shared mask contract: fifteen bytes, two distinct renderers, two rejections")
    class MaskSharedContract {

        @Test
        @DisplayName("the published mask width is 15")
        void theMaskWidthIsFifteen() {
            assertThat(ReportLineFormatter.AMOUNT_MASK_WIDTH).isEqualTo(15);
        }

        @Test
        @DisplayName("TRAP: a value of exactly zero blanks the whole field in BOTH masks - fifteen "
                + "spaces, no plus, no zero digit and no decimal point, which is the OPPOSITE of "
                + "the statement text mask where the two decimal places always print")
        void zeroBlanksTheWholeFieldInBothMasks() {
            String detail = ReportLineFormatter.renderDetailAmount(new BigDecimal("0.00"));
            String total = ReportLineFormatter.renderTotalAmount(new BigDecimal("0.00"));

            assertThat(ascii(detail)).as("detail mask for zero").isEqualTo(ascii(EXPECTED_MASK_ZERO));
            assertThat(ascii(total)).as("totals mask for zero").isEqualTo(ascii(EXPECTED_MASK_ZERO));

            assertThat(countBytes(detail, ' ')).isEqualTo(15);
            assertThat(countBytes(total, ' ')).isEqualTo(15);
            assertThat(countBytes(detail, '.')).isEqualTo(0);
            assertThat(countBytes(total, '.')).isEqualTo(0);
            assertThat(countBytes(detail, '0')).isEqualTo(0);
            assertThat(countBytes(total, '0')).isEqualTo(0);
            assertThat(countBytes(total, '+')).isEqualTo(0);
            assertThat(countBytes(total, '-')).isEqualTo(0);
        }

        @Test
        @DisplayName("a zero total blanks the amount field inside all three total records")
        void aZeroTotalBlanksTheAmountFieldInsideEachTotalRecord() {
            assertThat(ascii(window(
                    ReportLineFormatter.buildPageTotalLine(new BigDecimal("0.00")), 97, 15)))
                    .isEqualTo(ascii(EXPECTED_MASK_ZERO));
            assertThat(ascii(window(
                    ReportLineFormatter.buildAccountTotalLine(new BigDecimal("0.00")), 97, 15)))
                    .isEqualTo(ascii(EXPECTED_MASK_ZERO));
            assertThat(ascii(window(
                    ReportLineFormatter.buildGrandTotalLine(new BigDecimal("0.00")), 97, 15)))
                    .isEqualTo(ascii(EXPECTED_MASK_ZERO));
            assertThat(ascii(window(
                    detailLineWithCategoryCode(0), 97, 15)))
                    .isEqualTo(ascii(EXPECTED_MASK_ZERO));
        }

        @Test
        @DisplayName("the two renderers, invoked by their own names, differ for the same non-zero "
                + "input and differ only in the fixed sign position")
        void theTwoRenderersDifferForTheSameNonZeroInput() {
            String detail = ReportLineFormatter.renderDetailAmount(new BigDecimal("500.47"));
            String total = ReportLineFormatter.renderTotalAmount(new BigDecimal("500.47"));

            assertThat(ascii(detail)).isEqualTo(ascii(EXPECTED_DETAIL_MASK_POSITIVE));
            assertThat(ascii(total)).isEqualTo(ascii(EXPECTED_TOTAL_MASK_POSITIVE));
            assertThat(ascii(detail)).isNotEqualTo(ascii(total));

            assertThat(ascii(detail)[0]).as("detail sign position").isEqualTo((byte) ' ');
            assertThat(ascii(total)[0]).as("totals sign position").isEqualTo((byte) '+');
            assertThat(ascii(window(detail, 1, 14)))
                    .as("the two masks agree on every byte after the sign position")
                    .isEqualTo(ascii(spaces(8) + "500.47"));
            assertThat(ascii(window(total, 1, 14)))
                    .isEqualTo(ascii(spaces(8) + "500.47"));
        }

        @Test
        @DisplayName("the two renderers agree for a negative value, where both emit a minus")
        void theTwoRenderersAgreeForANegativeValue() {
            assertThat(ascii(ReportLineFormatter.renderDetailAmount(new BigDecimal("-603.22"))))
                    .isEqualTo(ascii(EXPECTED_DETAIL_MASK_NEGATIVE));
            assertThat(ascii(ReportLineFormatter.renderTotalAmount(new BigDecimal("-603.22"))))
                    .isEqualTo(ascii(EXPECTED_TOTAL_MASK_NEGATIVE));
        }

        @Test
        @DisplayName("a scale other than exactly 2 is rejected, never silently re-scaled")
        void aScaleOtherThanTwoIsRejected() {
            // Scale zero, including a scale-zero zero.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.renderDetailAmount(new BigDecimal("5")))
                    .withMessageContaining("scaled to 2 decimal places")
                    .withMessageContaining("scale 0");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.renderTotalAmount(new BigDecimal("0")))
                    .withMessageContaining("scaled to 2 decimal places")
                    .withMessageContaining("scale 0");

            // Scale one.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.renderDetailAmount(new BigDecimal("5.0")))
                    .withMessageContaining("scale 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.renderTotalAmount(new BigDecimal("5.0")))
                    .withMessageContaining("scale 1");

            // Scale three.
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> ReportLineFormatter.renderDetailAmount(new BigDecimal("5.000")))
                    .withMessageContaining("scale 3");
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> ReportLineFormatter.renderTotalAmount(new BigDecimal("5.000")))
                    .withMessageContaining("scale 3");
        }

        @Test
        @DisplayName("a wrong scale is rejected through every builder that carries an amount")
        void aWrongScaleIsRejectedThroughEveryAmountCarryingBuilder() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ReportLineFormatter.buildPageTotalLine(new BigDecimal("1.0")));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ReportLineFormatter.buildAccountTotalLine(new BigDecimal("1.0")));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ReportLineFormatter.buildGrandTotalLine(new BigDecimal("1.0")));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ReportLineFormatter.buildTransactionDetailLine(
                            "", "", "", "", 0, "", "", new BigDecimal("1.0")));
        }

        @Test
        @DisplayName("an integer part beyond nine digits is rejected, never left-truncated")
        void anIntegerPartBeyondNineDigitsIsRejected() {
            // The largest value the picture can hold renders without complaint.
            assertThat(ascii(
                    ReportLineFormatter.renderDetailAmount(new BigDecimal("999999999.99"))))
                    .isEqualTo(ascii(EXPECTED_DETAIL_MASK_FULL_WIDTH));

            // One more integer digit is rejected rather than losing its high-order digits.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.renderDetailAmount(
                            new BigDecimal("1000000000.00")))
                    .withMessageContaining("detailAmount")
                    .withMessageContaining("9 integer digits")
                    .withMessageContaining("2 decimal digits")
                    .withMessageContaining("11 significant digits")
                    .withMessageContaining("left-truncated");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.renderTotalAmount(
                            new BigDecimal("-1000000000.00")))
                    .withMessageContaining("totalAmount")
                    .withMessageContaining("9 integer digits");
        }

        @Test
        @DisplayName("an over-long integer part is rejected through every amount-carrying builder")
        void anOverLongIntegerPartIsRejectedThroughEveryAmountCarryingBuilder() {
            BigDecimal tooLarge = new BigDecimal("1000000000.00");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildPageTotalLine(tooLarge));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildAccountTotalLine(tooLarge));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildGrandTotalLine(tooLarge));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "", "", "", "", 0, "", "", tooLarge));
        }
    }

    // =============================================================================================
    // Group 12 of the test plan: the header block accessor. Four records, one order, unmodifiable.
    // =============================================================================================

    @Nested
    @DisplayName("Header block accessor: four records in one order, unmodifiable")
    class HeaderBlockAccessor {

        @Test
        @DisplayName("the block holds exactly four records")
        void theBlockHoldsExactlyFourRecords() {
            assertThat(ReportLineFormatter.buildHeaderBlock(START_DATE, END_DATE)).hasSize(4);
        }

        @Test
        @DisplayName("the order is name header, then blank line, then column header, then rule line")
        void theOrderIsNameHeaderThenBlankThenColumnHeaderThenRule() {
            List<String> block = ReportLineFormatter.buildHeaderBlock(START_DATE, END_DATE);

            assertThat(ascii(block.get(0)))
                    .as("element 0 is the report name header")
                    .isEqualTo(ascii(EXPECTED_NAME_HEADER));
            assertThat(ascii(block.get(1)))
                    .as("element 1 is the blank line")
                    .isEqualTo(ascii(EXPECTED_BLANK_LINE));
            assertThat(ascii(block.get(2)))
                    .as("element 2 is the column header")
                    .isEqualTo(ascii(EXPECTED_COLUMN_HEADER));
            assertThat(ascii(block.get(3)))
                    .as("element 3 is the rule line")
                    .isEqualTo(ascii(EXPECTED_RULE_LINE));
        }

        @Test
        @DisplayName("every element of the block is exactly 133 encoded bytes")
        void everyElementIsTheRecordWidth() {
            List<String> block = ReportLineFormatter.buildHeaderBlock(START_DATE, END_DATE);

            for (int index = 0; index < 4; index++) {
                assertThat(asciiWidth(block.get(index)))
                        .as("header block element %d", index)
                        .isEqualTo(133);
            }
        }

        @Test
        @DisplayName("the block is unmodifiable: add, set, remove and clear are all refused")
        void theBlockIsUnmodifiable() {
            List<String> block = ReportLineFormatter.buildHeaderBlock(START_DATE, END_DATE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> block.add(EXPECTED_BLANK_LINE));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> block.set(0, EXPECTED_BLANK_LINE));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> block.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(block::clear);
        }

        @Test
        @DisplayName("the caller-supplied dates reach the block's name header")
        void theCallerSuppliedDatesReachTheBlocksNameHeader() {
            List<String> block = ReportLineFormatter.buildHeaderBlock("1999-06-30", "2001-02-28");

            assertThat(ascii(window(block.get(0), 91, 10))).isEqualTo(ascii("1999-06-30"));
            assertThat(ascii(window(block.get(0), 105, 10))).isEqualTo(ascii("2001-02-28"));
        }
    }

    // =============================================================================================
    // Group 13 of the test plan: the 21-byte date-parameter record and its tolerant reader.
    // =============================================================================================

    @Nested
    @DisplayName("Date-parameter record: 10 + 1 + 10 written, 21 or 80 read")
    class DateParameterRecord {

        @Test
        @DisplayName("CROSS-FILE AGREEMENT: these 21 bytes are the leading 21 bytes of card "
                + "fifteen of the job-submission card image, and the two must agree byte for byte")
        void theTwentyOneByteShapeIsTheLeadingPrefixOfCardFifteen() {
            // A documented agreement, not a code dependency: the job-submission card-image builder
            // is neither imported nor invoked from this test. Card fifteen is a 10-byte start date,
            // a single-byte space separator, a 10-byte end date and 59 trailing spaces.
            String record = ReportLineFormatter.buildDateParameterRecord(START_DATE, END_DATE);

            assertThat(asciiWidth(record)).isEqualTo(21);
            assertThat(ascii(record)).isEqualTo(ascii(EXPECTED_DATE_PARAMETER_RECORD));
            assertThat(ascii(window(record, 0, 10))).isEqualTo(ascii("2022-01-01"));
            assertThat(ascii(window(record, 10, 1))).isEqualTo(ascii(spaces(1)));
            assertThat(ascii(window(record, 11, 10))).isEqualTo(ascii("2022-12-31"));
        }

        @Test
        @DisplayName("the builder produces exactly 21 bytes as 10 plus 1 plus 10")
        void theBuilderProducesExactlyTwentyOneBytes() {
            String record = ReportLineFormatter.buildDateParameterRecord(START_DATE, END_DATE);

            assertThat(asciiWidth(record)).isEqualTo(21);
            assertThat(asciiWidth(window(record, 0, 10))).isEqualTo(10);
            assertThat(asciiWidth(window(record, 10, 1))).isEqualTo(1);
            assertThat(asciiWidth(window(record, 11, 10))).isEqualTo(10);
        }

        @Test
        @DisplayName("the separator the builder writes is exactly one byte of 0x20")
        void theSeparatorIsExactlyOneSpaceByte() {
            String record = ReportLineFormatter.buildDateParameterRecord(START_DATE, END_DATE);

            assertThat(ascii(record)[10]).isEqualTo((byte) 0x20);
            assertThat(ascii(record)[9]).as("no second separator before it").isEqualTo((byte) '1');
            assertThat(ascii(record)[11]).as("no second separator after it").isEqualTo((byte) '2');
            assertThat(countBytes(record, ' ')).isEqualTo(1);
        }

        @Test
        @DisplayName("the reader parses both dates from a 21-byte structured record")
        void theReaderParsesBothDatesFromTwentyOneBytes() {
            assertThat(ascii(ReportLineFormatter.readStartDate(EXPECTED_DATE_PARAMETER_RECORD)))
                    .isEqualTo(ascii("2022-01-01"));
            assertThat(ascii(ReportLineFormatter.readEndDate(EXPECTED_DATE_PARAMETER_RECORD)))
                    .isEqualTo(ascii("2022-12-31"));
        }

        @Test
        @DisplayName("the reader parses the same pair from the leading 21 bytes of an 80-byte card")
        void theReaderParsesTheSamePairFromAnEightyByteCard() {
            assertThat(asciiWidth(EXPECTED_DATE_PARAMETER_CARD)).isEqualTo(80);

            assertThat(ascii(ReportLineFormatter.readStartDate(EXPECTED_DATE_PARAMETER_CARD)))
                    .isEqualTo(ascii("2022-01-01"));
            assertThat(ascii(ReportLineFormatter.readEndDate(EXPECTED_DATE_PARAMETER_CARD)))
                    .isEqualTo(ascii("2022-12-31"));
        }

        @Test
        @DisplayName("both accepted widths yield the identical hand-written pair")
        void bothAcceptedWidthsYieldTheIdenticalPair() {
            assertThat(ascii(ReportLineFormatter.readStartDate(EXPECTED_DATE_PARAMETER_RECORD)))
                    .isEqualTo(ascii("2022-01-01"));
            assertThat(ascii(ReportLineFormatter.readStartDate(EXPECTED_DATE_PARAMETER_CARD)))
                    .isEqualTo(ascii("2022-01-01"));
            assertThat(ascii(ReportLineFormatter.readEndDate(EXPECTED_DATE_PARAMETER_RECORD)))
                    .isEqualTo(ascii("2022-12-31"));
            assertThat(ascii(ReportLineFormatter.readEndDate(EXPECTED_DATE_PARAMETER_CARD)))
                    .isEqualTo(ascii("2022-12-31"));
        }

        @Test
        @DisplayName("a width that is neither 21 nor 80 is rejected, naming both and the actual")
        void aWidthThatIsNeitherAcceptedValueIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.readStartDate(
                            "2022-01-01 2022-12-3"))
                    .withMessageContaining("21")
                    .withMessageContaining("80")
                    .withMessageContaining("20");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.readStartDate(
                            "2022-01-01 2022-12-31" + spaces(1)))
                    .withMessageContaining("21")
                    .withMessageContaining("80")
                    .withMessageContaining("22");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.readEndDate(
                            "2022-01-01 2022-12-31" + spaces(58)))
                    .withMessageContaining("21")
                    .withMessageContaining("80")
                    .withMessageContaining("79");
        }

        @Test
        @DisplayName("the two date-parameter width constants are 21 and 80")
        void theTwoDateParameterWidthConstantsAre21And80() {
            assertThat(ReportLineFormatter.DATE_PARAMETER_STRUCTURED_WIDTH).isEqualTo(21);
            assertThat(ReportLineFormatter.DATE_PARAMETER_CARD_WIDTH).isEqualTo(80);
        }

        @Test
        @DisplayName("the date-parameter offsets and separator width are 0, 10, 11 and 1")
        void theDateParameterOffsetsAreDeclaredAsTheStructureRequires() {
            assertThat(ReportLineFormatter.DATE_PARAMETER_START_DATE_OFFSET).isEqualTo(0);
            assertThat(ReportLineFormatter.DATE_PARAMETER_SEPARATOR_OFFSET).isEqualTo(10);
            assertThat(ReportLineFormatter.DATE_PARAMETER_END_DATE_OFFSET).isEqualTo(11);
            assertThat(ReportLineFormatter.DATE_PARAMETER_SEPARATOR_WIDTH).isEqualTo(1);
        }

        @Test
        @DisplayName("dates are carried as raw ten-character strings and never parsed as calendars")
        void datesAreCarriedAsRawTenCharacterStrings() {
            // A value that no calendar would accept is still carried through untouched, because the
            // field is ten characters of text and nothing in this layer validates a date.
            String record = ReportLineFormatter.buildDateParameterRecord("9999-99-99", "0000-00-00");

            assertThat(ascii(record)).isEqualTo(ascii("9999-99-99 0000-00-00"));
            assertThat(ascii(ReportLineFormatter.readStartDate(record)))
                    .isEqualTo(ascii("9999-99-99"));
            assertThat(ascii(ReportLineFormatter.readEndDate(record)))
                    .isEqualTo(ascii("0000-00-00"));
        }

        @Test
        @DisplayName("the reader returns its ten bytes untrimmed, padding included")
        void theReaderReturnsItsTenBytesUntrimmed() {
            String record = ReportLineFormatter.buildDateParameterRecord("2022", "");

            assertThat(ascii(ReportLineFormatter.readStartDate(record)))
                    .isEqualTo(ascii("2022" + spaces(6)));
            assertThat(ascii(ReportLineFormatter.readEndDate(record)))
                    .isEqualTo(ascii(spaces(10)));
            assertThat(asciiWidth(ReportLineFormatter.readEndDate(record))).isEqualTo(10);
        }
    }

    // =============================================================================================
    // Group 14 of the test plan: page-break SUPPORT, not ownership.
    //
    // This formatter holds no state and implements no pagination. The line counter, the running
    // accumulations and every break decision live in the report service, so the only pagination
    // fact asserted here is the published page-size constant. No break position, no page count and
    // no row count is asserted anywhere in this file.
    //
    // Recorded for the service agent that inherits the behaviour, and asserted nowhere here:
    //
    //   * The legacy break test is a modulus of the line counter against a page size of twenty, and
    //     it is a modulus over EVERY written record, not "every twenty detail rows".
    //   * The write routine itself never increments the counter; each caller does. A header block
    //     adds four, a page-total block adds two, an account-total block adds two, a detail record
    //     adds one, and the grand-total record adds nothing.
    //   * Traced from a counter of four immediately after the first header block, page one carries
    //     sixteen detail rows and the steady state settles at fourteen.
    //   * An account-total block's increment of two can step the counter straight past a multiple
    //     of twenty, so a page break is missed entirely. That is a genuine legacy defect to
    //     reproduce faithfully in the service, never to repair here.
    //   * Account totals do not roll into the grand total; only page totals do. There is no
    //     trailing rule record after the grand total.
    //
    // The consuming driver contains ZERO COMPUTE statements: it performs integer additions to the
    // line counter and two integer subtractions of status values, and nothing else. This formatter
    // therefore introduces no arithmetic of any kind. It measures, slices, pads and edits; it never
    // adds, scales or rounds. Rounding, where it happens at all, is the codec's business and is
    // always toward zero because the estate declares no ROUNDED clause anywhere.
    //
    // The driver's paragraph numbering repeats a prefix across several distinct paragraphs. That is
    // a source anomaly recorded in the decision log and it has no bearing on this class, so nothing
    // about it is asserted.
    // =============================================================================================

    @Nested
    @DisplayName("Page-break support: the page-size constant only, because this formatter holds "
            + "no state and implements no pagination")
    class PageBreakSupport {

        @Test
        @DisplayName("the published page size is 20, and that is the only pagination fact this "
                + "class exposes")
        void thePublishedPageSizeIsTwenty() {
            assertThat(ReportLineFormatter.PAGE_SIZE).isEqualTo(20);
        }
    }

    // =============================================================================================
    // Group 15 of the test plan: every caller-supplied reference is rejected independently when it
    // is null, so that a missing value can never reach a fixed-width field as the four characters
    // that spell the absent reference.
    // =============================================================================================

    @Nested
    @DisplayName("Absent references are rejected independently, one parameter at a time")
    class AbsentReferenceRejection {

        @Test
        @DisplayName("the report name header rejects an absent start date and an absent end date")
        void theReportNameHeaderRejectsEachAbsentDate() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildReportNameHeader(null, END_DATE))
                    .withMessageContaining("startDate")
                    .withMessageContaining("report name header");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildReportNameHeader(START_DATE, null))
                    .withMessageContaining("endDate")
                    .withMessageContaining("report name header");
        }

        @Test
        @DisplayName("the detail line rejects an absent transaction id")
        void theDetailLineRejectsAnAbsentTransactionId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            null, "00000000011", "01", "Purchase", 1,
                            "Sales draft credit adjustment", "POS TERM", new BigDecimal("500.47")))
                    .withMessageContaining("transactionId");
        }

        @Test
        @DisplayName("the detail line rejects an absent account id")
        void theDetailLineRejectsAnAbsentAccountId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "0000000000000001", null, "01", "Purchase", 1,
                            "Sales draft credit adjustment", "POS TERM", new BigDecimal("500.47")))
                    .withMessageContaining("accountId");
        }

        @Test
        @DisplayName("the detail line rejects an absent type code")
        void theDetailLineRejectsAnAbsentTypeCode() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "0000000000000001", "00000000011", null, "Purchase", 1,
                            "Sales draft credit adjustment", "POS TERM", new BigDecimal("500.47")))
                    .withMessageContaining("transactionTypeCode");
        }

        @Test
        @DisplayName("the detail line rejects an absent type description")
        void theDetailLineRejectsAnAbsentTypeDescription() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "0000000000000001", "00000000011", "01", null, 1,
                            "Sales draft credit adjustment", "POS TERM", new BigDecimal("500.47")))
                    .withMessageContaining("transactionTypeDescription");
        }

        @Test
        @DisplayName("the detail line rejects an absent category description")
        void theDetailLineRejectsAnAbsentCategoryDescription() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "0000000000000001", "00000000011", "01", "Purchase", 1,
                            null, "POS TERM", new BigDecimal("500.47")))
                    .withMessageContaining("transactionCategoryDescription");
        }

        @Test
        @DisplayName("the detail line rejects an absent source")
        void theDetailLineRejectsAnAbsentSource() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "0000000000000001", "00000000011", "01", "Purchase", 1,
                            "Sales draft credit adjustment", null, new BigDecimal("500.47")))
                    .withMessageContaining("transactionSource");
        }

        @Test
        @DisplayName("the detail line rejects an absent amount")
        void theDetailLineRejectsAnAbsentAmount() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "0000000000000001", "00000000011", "01", "Purchase", 1,
                            "Sales draft credit adjustment", "POS TERM", null))
                    .withMessageContaining("amount")
                    .withMessageContaining("detail line");
        }

        @Test
        @DisplayName("each of the three total records rejects an absent amount, naming its own group")
        void eachTotalRecordRejectsAnAbsentAmount() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildPageTotalLine(null))
                    .withMessageContaining("page total line");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildAccountTotalLine(null))
                    .withMessageContaining("account total line");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildGrandTotalLine(null))
                    .withMessageContaining("grand total line");
        }

        @Test
        @DisplayName("each mask renderer rejects an absent amount, naming its own mask")
        void eachMaskRendererRejectsAnAbsentAmount() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.renderDetailAmount(null))
                    .withMessageContaining("detail amount mask");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.renderTotalAmount(null))
                    .withMessageContaining("total amount mask");
        }

        @Test
        @DisplayName("the header block rejects an absent start date and an absent end date")
        void theHeaderBlockRejectsEachAbsentDate() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildHeaderBlock(null, END_DATE))
                    .withMessageContaining("startDate")
                    .withMessageContaining("header block");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildHeaderBlock(START_DATE, null))
                    .withMessageContaining("endDate")
                    .withMessageContaining("header block");
        }

        @Test
        @DisplayName("the date-parameter builder rejects an absent start date and end date")
        void theDateParameterBuilderRejectsEachAbsentDate() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.buildDateParameterRecord(null, END_DATE))
                    .withMessageContaining("startDate")
                    .withMessageContaining("date parameter record");
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> ReportLineFormatter.buildDateParameterRecord(START_DATE, null))
                    .withMessageContaining("endDate")
                    .withMessageContaining("date parameter record");
        }

        @Test
        @DisplayName("both date-parameter readers reject an absent card")
        void bothDateParameterReadersRejectAnAbsentCard() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.readStartDate(null))
                    .withMessageContaining("dateParameterCard");
            assertThatNullPointerException()
                    .isThrownBy(() -> ReportLineFormatter.readEndDate(null))
                    .withMessageContaining("dateParameterCard");
        }
    }

    // =============================================================================================
    // Group 16 of the test plan: the printable US-ASCII guard. A fixed-width record is a byte
    // window; a control byte inside one would corrupt the record silently, so it is refused at the
    // boundary rather than encoded.
    // =============================================================================================

    @Nested
    @DisplayName("Printable US-ASCII guard on every caller-supplied text field")
    class PrintableAsciiGuard {

        @Test
        @DisplayName("a byte below the printable range is refused, naming the field, the accepted "
                + "range and the offending position")
        void aByteBelowThePrintableRangeIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildReportNameHeader(
                            "2022-01-0" + BELOW_PRINTABLE_ASCII, END_DATE))
                    .withMessageContaining("startDate")
                    .withMessageContaining("printable US-ASCII only")
                    .withMessageContaining("32")
                    .withMessageContaining("126")
                    .withMessageContaining("position 9")
                    .withMessageContaining("code point 7");
        }

        @Test
        @DisplayName("a byte above the printable range is refused")
        void aByteAboveThePrintableRangeIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildReportNameHeader(
                            START_DATE, ABOVE_PRINTABLE_ASCII + "022-12-31"))
                    .withMessageContaining("endDate")
                    .withMessageContaining("printable US-ASCII only")
                    .withMessageContaining("position 0")
                    .withMessageContaining("code point 127");
        }

        @Test
        @DisplayName("both boundary bytes of the printable range are accepted")
        void bothBoundaryBytesOfThePrintableRangeAreAccepted() {
            // A space is code point 32 and a tilde is code point 126: the two ends of the range.
            String header = ReportLineFormatter.buildReportNameHeader("~~~~~~~~~~", spaces(10));

            assertThat(asciiWidth(header)).isEqualTo(133);
            assertThat(ascii(window(header, 91, 10))).isEqualTo(ascii("~~~~~~~~~~"));
            assertThat(ascii(window(header, 105, 10))).isEqualTo(ascii(spaces(10)));
        }

        @Test
        @DisplayName("the detail line refuses a control byte in any of its text fields")
        void theDetailLineRefusesAControlByteInAnyTextField() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            String.valueOf(BELOW_PRINTABLE_ASCII), "", "", "", 0, "", "",
                            new BigDecimal("0.00")))
                    .withMessageContaining("transactionId");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "", String.valueOf(ABOVE_PRINTABLE_ASCII), "", "", 0, "", "",
                            new BigDecimal("0.00")))
                    .withMessageContaining("accountId");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "", "", String.valueOf(BELOW_PRINTABLE_ASCII), "", 0, "", "",
                            new BigDecimal("0.00")))
                    .withMessageContaining("transactionTypeCode");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "", "", "", String.valueOf(BELOW_PRINTABLE_ASCII), 0, "", "",
                            new BigDecimal("0.00")))
                    .withMessageContaining("transactionTypeDescription");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "", "", "", "", 0, String.valueOf(BELOW_PRINTABLE_ASCII), "",
                            new BigDecimal("0.00")))
                    .withMessageContaining("transactionCategoryDescription");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildTransactionDetailLine(
                            "", "", "", "", 0, "", String.valueOf(ABOVE_PRINTABLE_ASCII),
                            new BigDecimal("0.00")))
                    .withMessageContaining("transactionSource");
        }

        @Test
        @DisplayName("the date-parameter builder and both readers refuse a control byte")
        void theDateParameterSurfaceRefusesAControlByte() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildDateParameterRecord(
                            String.valueOf(BELOW_PRINTABLE_ASCII), END_DATE))
                    .withMessageContaining("startDate");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.buildDateParameterRecord(
                            START_DATE, String.valueOf(ABOVE_PRINTABLE_ASCII)))
                    .withMessageContaining("endDate");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.readStartDate(
                            BELOW_PRINTABLE_ASCII + "022-01-01 2022-12-31"))
                    .withMessageContaining("dateParameterCard");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReportLineFormatter.readEndDate(
                            "2022-01-01 2022-12-3" + ABOVE_PRINTABLE_ASCII))
                    .withMessageContaining("dateParameterCard");
        }
    }


}
