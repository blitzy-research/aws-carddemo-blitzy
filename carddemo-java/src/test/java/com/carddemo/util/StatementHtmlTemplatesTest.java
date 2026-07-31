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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit test for {@link StatementHtmlTemplates}, the holder of the fixed-width HTML line
 * templates written to the account-statement HTML output stream.
 *
 * <h2>What this test guards</h2>
 *
 * <p>The class under test publishes <strong>thirty-four</strong> fixed line templates, two
 * composed lines built from a literal plus a substituted value, one fit-to-width helper for
 * the three free-form work lines, and the named record and component widths. Every published
 * record is exactly <strong>one hundred</strong> encoded bytes wide, which is the width of one
 * HTML output record.</p>
 *
 * <p>This is a direct end-to-end byte-parity surface. The migrated batch stream has to be
 * byte-identical to the stream the legacy generator produced, so every assertion below
 * compares <strong>encoded byte arrays</strong> rather than strings, and every width is
 * measured through {@link java.nio.charset.StandardCharsets#US_ASCII} rather than through a
 * character count. Nothing here is trimmed, collapsed, re-spaced, re-cased, balanced,
 * re-quoted, entity-encoded or reformatted in any way, and no markup tooling of any kind is
 * used: a tool that silently improved the markup would break the very bytes this test exists
 * to pin down.</p>
 *
 * <h2>The five deliberate malformations that must survive</h2>
 *
 * <ol>
 *   <li><strong>Two consecutive space bytes inside the table start tag.</strong> Template 8
 *       carries two spaces between the tag name and its first attribute. A whitespace
 *       normaliser would collapse them and a minified rewrite would drop them; both are
 *       parity failures, so the two space bytes are asserted by byte index.</li>
 *   <li><strong>Inconsistent spacing before the background colour property.</strong> The four
 *       spanning cells omit the space after {@code padding:0px 5px;} while the six
 *       explicitly-sized cells include it. Neither family is changed toward the other, and
 *       both spacings are asserted as found.</li>
 *   <li><strong>A declared template that is never emitted.</strong> The bare table-cell start
 *       tag is declared but the legacy program never sets it, so it is asserted to exist at
 *       full record width and is deliberately <em>not</em> deleted as unused. Nothing is
 *       asserted about emission, because this class supplies templates and never emits
 *       them.</li>
 *   <li><strong>A line with no closing tag.</strong> The customer-name line is a
 *       twenty-six-byte opening paragraph literal plus a fifty-byte name field and stops
 *       there, so no closing tag is ever appended. Its neighbour, the account-number
 *       heading, is thirty-four plus twenty plus five bytes and does close, which is what
 *       proves the omission is specific rather than systematic.</li>
 *   <li><strong>The scaffold repeats once per account.</strong> In the emitted artefact the
 *       whole document scaffold, from the document type declaration through the closing html
 *       element, appears once for every account. That repetition is the statement-generation
 *       service's decision; this test asserts only the declaration order the accessor
 *       publishes, and builds no document.</li>
 * </ol>
 *
 * <h2>Facts recorded here but deliberately not asserted</h2>
 *
 * <ul>
 *   <li><strong>The legacy source layout.</strong> Some of the thirty-four literals were
 *       written across two physical source lines using literal continuation, and some of those
 *       splits fall inside a token. The reassembled value is the contract; how the literal was
 *       typed is not. No continuation count, split position or source-line count is asserted
 *       anywhere below.</li>
 *   <li><strong>The record-length conflict in the job stream.</strong> The statement job
 *       declares the same HTML data definition at eighty bytes in one step and at one hundred
 *       bytes in the following step, which is the step that actually runs the generator. The
 *       conflict is resolved to <strong>one hundred</strong> for the HTML stream, matching the
 *       record declaration of the emitting program, and one hundred is what this test
 *       asserts.</li>
 *   <li><strong>The assembly delimiter regimes.</strong> The emitting program composes the
 *       customer name and the three address lines with a two-space delimiter, and composes the
 *       basic-detail and transaction lines with an asterisk delimiter so that the entire padded
 *       field width transfers and its trailing spaces are retained inside the element. That
 *       assembly belongs to the statement-generation service; no delimiter regime is
 *       implemented or asserted here.</li>
 *   <li><strong>The initialisation width guard.</strong> The class under test fails
 *       initialisation with {@link IllegalStateException}, naming the offending template, if a
 *       literal is ever widened past the record. That guard is private and cannot be reached
 *       through the published API, and reaching it reflectively is prohibited, so this test
 *       pins the invariant the guard protects instead: every published record is its literal
 *       followed by space padding only, and every literal fits inside the record.</li>
 *   <li><strong>Colour and width literals are not design decisions.</strong> No design system,
 *       component library, styling framework or design-token set exists anywhere in this
 *       migration and no graphical interface is built. The colour and percentage-width bytes
 *       below are legacy output content reproduced for parity; they are never themed,
 *       tokenised, re-cased or aligned to a palette.</li>
 * </ul>
 *
 * <h2>Independent expectations</h2>
 *
 * <p>Every expected value in this file is written out by hand from the verified legacy
 * inventory. No expectation is produced by calling a constant or a method of the class under
 * test, no output is snapshotted, and no assertion compares a result with itself. The padding
 * oracle below rebuilds the hundred-byte image from literals alone.</p>
 *
 * <h2>Recorded divergences</h2>
 *
 * <p>Where faithful translation and idiomatic Java diverge, faithful wins and the divergence is
 * recorded in {@code docs/decision-log.md}. That log is referenced from here and is never edited
 * by a test. The divergences this file pins are: two consecutive space bytes preserved verbatim
 * after a tag name; an inconsistent space before a style property across two families of
 * constants, preserved in both directions rather than made uniform; a constant that is declared
 * and never emitted, retained rather than removed as dead code; a markup line deliberately
 * emitted without a closing tag; literal constants in place of a templating engine, so that no
 * whitespace or ordering variability can enter the output; a record-length conflict in the job
 * stream resolved to one hundred bytes for the HTML stream; and colour and width literals
 * reproduced byte for byte with no design-system involvement of any kind.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy estate at checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release
 * stamp CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. The stamp is recorded here as a header
 * note only; it is never asserted against any member of the class under test.</p>
 */
@DisplayName("StatementHtmlTemplates :: hundred-byte HTML statement line templates")
class StatementHtmlTemplatesTest {

    /*
     * ----------------------------------------------------------------------------------------
     * Independently written widths. These repeat the legacy record and component widths as
     * plain literals so that the assertions never borrow a figure from the class under test.
     * They are factual layout evidence read from the legacy record declarations, not tuning or
     * capacity figures.
     * ----------------------------------------------------------------------------------------
     */

    /** Width of one HTML output record in encoded bytes. */
    private static final int EXPECTED_RECORD_LENGTH = 100;

    /** Number of fixed HTML line templates the legacy program declares. */
    private static final int EXPECTED_TEMPLATE_COUNT = 34;

    /** Width of the account-number heading's leading literal, including its trailing space. */
    private static final int EXPECTED_ACCOUNT_PREFIX_LENGTH = 34;

    /** Width of the substituted account-identifier field of the account-number heading. */
    private static final int EXPECTED_ACCOUNT_FIELD_LENGTH = 20;

    /** Width of the account-number heading's trailing closing tag. */
    private static final int EXPECTED_ACCOUNT_SUFFIX_LENGTH = 5;

    /** Declared width of the whole account-number heading group before record padding. */
    private static final int EXPECTED_ACCOUNT_DECLARED_LENGTH = 59;

    /** Width of the customer-name line's leading literal. */
    private static final int EXPECTED_NAME_PREFIX_LENGTH = 26;

    /** Width of the substituted customer-name field of the customer-name line. */
    private static final int EXPECTED_NAME_FIELD_LENGTH = 50;

    /** Declared width of the whole customer-name group before record padding. */
    private static final int EXPECTED_NAME_DECLARED_LENGTH = 76;

    /*
     * ----------------------------------------------------------------------------------------
     * Independently written literals of the two composed lines.
     * ----------------------------------------------------------------------------------------
     */

    /** The account-number heading's leading literal; the trailing space is part of its width. */
    private static final String EXPECTED_ACCOUNT_PREFIX = "<h3>Statement for Account Number: ";

    /** The account-number heading's closing tag, present because that line does close. */
    private static final String EXPECTED_ACCOUNT_SUFFIX = "</h3>";

    /** The customer-name line's leading literal. There is deliberately no trailing literal. */
    private static final String EXPECTED_NAME_PREFIX = "<p style=\"font-size:16px\">";

    /*
     * ----------------------------------------------------------------------------------------
     * Named byte values. Written as hexadecimal so that no escape sequence for a line
     * terminator or a tab appears anywhere in this source.
     * ----------------------------------------------------------------------------------------
     */

    /** The ASCII space, and the only byte the fixed-width move may use as padding. */
    private static final byte ASCII_SPACE = 0x20;

    /** The ASCII line feed, which must never appear in a record. */
    private static final byte ASCII_LINE_FEED = 0x0A;

    /** The ASCII carriage return, which must never appear in a record. */
    private static final byte ASCII_CARRIAGE_RETURN = 0x0D;

    /** The ASCII horizontal tab, which must never appear in a record nor be used as padding. */
    private static final byte ASCII_HORIZONTAL_TAB = 0x09;

    /** Sentinel returned by the byte search helper when a sequence is absent. */
    private static final int NOT_FOUND = -1;

    /*
     * ----------------------------------------------------------------------------------------
     * The independent oracle: three parallel, hand-written lists in exact legacy declaration
     * order.
     *
     *   EXPECTED_LEGACY_NAMES         - the legacy condition-name of each template, for
     *                                   failure messages.
     *   PUBLISHED_TEMPLATES_IN_ORDER  - the ACTUAL side: the published constants, listed here
     *                                   in their declaration order so that the ordered
     *                                   accessor can be checked against the constants
     *                                   themselves and not only against content.
     *   EXPECTED_TEMPLATE_CONTENTS    - the EXPECTED side: every literal typed out by hand
     *                                   from the verified legacy inventory. Nothing in this
     *                                   list is derived from the class under test.
     *
     * The lists are index-aligned; the fixture provider zips them.
     * ----------------------------------------------------------------------------------------
     */

    /** Legacy condition-names of the thirty-four fixed templates, in declaration order. */
    private static final List<String> EXPECTED_LEGACY_NAMES = List.of(
            "HTML-L01", "HTML-L02", "HTML-L03", "HTML-L04", "HTML-L05", "HTML-L06", "HTML-L07",
            "HTML-L08", "HTML-LTRS", "HTML-LTRE", "HTML-LTDS", "HTML-LTDE", "HTML-L10",
            "HTML-L15", "HTML-L16", "HTML-L17", "HTML-L18", "HTML-L22-35", "HTML-L30-42",
            "HTML-L31", "HTML-L43", "HTML-L47", "HTML-L48", "HTML-L50", "HTML-L51", "HTML-L53",
            "HTML-L54", "HTML-L58", "HTML-L61", "HTML-L64", "HTML-L75", "HTML-L78", "HTML-L79",
            "HTML-L80");

    /** The published templates, listed in the order the class under test declares them. */
    private static final List<String> PUBLISHED_TEMPLATES_IN_ORDER = List.of(
            StatementHtmlTemplates.HTML_L01,
            StatementHtmlTemplates.HTML_L02,
            StatementHtmlTemplates.HTML_L03,
            StatementHtmlTemplates.HTML_L04,
            StatementHtmlTemplates.HTML_L05,
            StatementHtmlTemplates.HTML_L06,
            StatementHtmlTemplates.HTML_L07,
            StatementHtmlTemplates.HTML_L08,
            StatementHtmlTemplates.HTML_LTRS,
            StatementHtmlTemplates.HTML_LTRE,
            StatementHtmlTemplates.HTML_LTDS,
            StatementHtmlTemplates.HTML_LTDE,
            StatementHtmlTemplates.HTML_L10,
            StatementHtmlTemplates.HTML_L15,
            StatementHtmlTemplates.HTML_L16,
            StatementHtmlTemplates.HTML_L17,
            StatementHtmlTemplates.HTML_L18,
            StatementHtmlTemplates.HTML_L22_35,
            StatementHtmlTemplates.HTML_L30_42,
            StatementHtmlTemplates.HTML_L31,
            StatementHtmlTemplates.HTML_L43,
            StatementHtmlTemplates.HTML_L47,
            StatementHtmlTemplates.HTML_L48,
            StatementHtmlTemplates.HTML_L50,
            StatementHtmlTemplates.HTML_L51,
            StatementHtmlTemplates.HTML_L53,
            StatementHtmlTemplates.HTML_L54,
            StatementHtmlTemplates.HTML_L58,
            StatementHtmlTemplates.HTML_L61,
            StatementHtmlTemplates.HTML_L64,
            StatementHtmlTemplates.HTML_L75,
            StatementHtmlTemplates.HTML_L78,
            StatementHtmlTemplates.HTML_L79,
            StatementHtmlTemplates.HTML_L80);

    /**
     * The thirty-four expected literals, hand-written in exact legacy declaration order.
     *
     * <p>Three of them carry a malformation on purpose. Entry 8 has two consecutive spaces
     * after the table tag name. Entries 13, 14, 18 and 19 omit the space before the background
     * colour property, while entries 22, 24, 26, 28, 29 and 30 include it. Entry 11 is the bare
     * table-cell start tag that the legacy program declares and never sets.</p>
     */
    private static final List<String> EXPECTED_TEMPLATE_CONTENTS = List.of(
            "<!DOCTYPE html>",
            "<html lang=\"en\">",
            "<head>",
            "<meta charset=\"utf-8\">",
            "<title>HTML Table Layout</title>",
            "</head>",
            "<body style=\"margin:0px;\">",
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">",
            "<tr>",
            "</tr>",
            "<td>",
            "</td>",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">",
            "<p style=\"font-size:16px\">Bank of XYZ</p>",
            "<p>410 Terry Ave N</p>",
            "<p>Seattle WA 99999</p>",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">",
            "<p style=\"font-size:16px\">Basic Details</p>",
            "<p style=\"font-size:16px\">Transaction Summary</p>",
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">",
            "<p style=\"font-size:16px\">Tran ID</p>",
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">",
            "<p style=\"font-size:16px\">Tran Details</p>",
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">",
            "<p style=\"font-size:16px\">Amount</p>",
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">",
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">",
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">",
            "<h3>End of Statement</h3>",
            "</table>",
            "</body>",
            "</html>");

    /** The style fragment the four spanning cells use: no space before the colour property. */
    private static final String SPANNING_CELL_STYLE_FRAGMENT = "padding:0px 5px;background-color:";

    /** The style fragment the six sized cells use: one space before the colour property. */
    private static final String SIZED_CELL_STYLE_FRAGMENT = "padding:0px 5px; background-color:";

    /*
     * ----------------------------------------------------------------------------------------
     * Fixture providers. Each zips the parallel lists above so that a failure names the legacy
     * template it came from.
     * ----------------------------------------------------------------------------------------
     */

    /**
     * Supplies one case per fixed template: its one-based position, its legacy condition-name,
     * the published record, and the hand-written literal it must carry.
     *
     * @return thirty-four cases in exact legacy declaration order
     */
    static Stream<Arguments> fixedTemplateCases() {
        return IntStream.range(0, EXPECTED_TEMPLATE_CONTENTS.size())
                .mapToObj(index -> Arguments.of(
                        index + 1,
                        EXPECTED_LEGACY_NAMES.get(index),
                        PUBLISHED_TEMPLATES_IN_ORDER.get(index),
                        EXPECTED_TEMPLATE_CONTENTS.get(index)));
    }

    /**
     * Supplies the four spanning table cells, which omit the space before the background colour
     * property.
     *
     * @return four cases naming each template and its published record
     */
    static Stream<Arguments> spanningCellCases() {
        return Stream.of(
                Arguments.of("HTML-L10", StatementHtmlTemplates.HTML_L10),
                Arguments.of("HTML-L15", StatementHtmlTemplates.HTML_L15),
                Arguments.of("HTML-L22-35", StatementHtmlTemplates.HTML_L22_35),
                Arguments.of("HTML-L30-42", StatementHtmlTemplates.HTML_L30_42));
    }

    /**
     * Supplies the six explicitly-sized table cells, which include the space before the
     * background colour property.
     *
     * @return six cases naming each template and its published record
     */
    static Stream<Arguments> sizedCellCases() {
        return Stream.of(
                Arguments.of("HTML-L47", StatementHtmlTemplates.HTML_L47),
                Arguments.of("HTML-L50", StatementHtmlTemplates.HTML_L50),
                Arguments.of("HTML-L53", StatementHtmlTemplates.HTML_L53),
                Arguments.of("HTML-L58", StatementHtmlTemplates.HTML_L58),
                Arguments.of("HTML-L61", StatementHtmlTemplates.HTML_L61),
                Arguments.of("HTML-L64", StatementHtmlTemplates.HTML_L64));
    }

    /*
     * ----------------------------------------------------------------------------------------
     * Byte helpers and the padding oracle. Everything below works on encoded bytes and uses
     * literals only, so no expectation can be borrowed from the class under test. No helper
     * here removes, collapses, re-cases, re-quotes, entity-encodes or otherwise rewrites a
     * single byte.
     * ----------------------------------------------------------------------------------------
     */

    /**
     * Encodes a value to the bytes an HTML record is measured and compared in.
     *
     * @param value the value to encode
     * @return the US-ASCII encoding of the value
     */
    private static byte[] asciiBytes(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Measures a value in encoded bytes, never in character count.
     *
     * @param value the value to measure
     * @return the number of bytes in the value's US-ASCII encoding
     */
    private static int asciiWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Independent padding oracle: rebuilds the hundred-byte record image from a literal by
     * copying its bytes to the left and filling the remainder with the ASCII space.
     *
     * <p>Built from literals alone. It borrows neither the record width nor the padding byte
     * from the class under test.</p>
     *
     * @param content the literal the record must carry
     * @return a hundred-byte image holding the literal followed by space padding
     */
    private static byte[] expectedRecord(final String content) {
        return expectedField(content, EXPECTED_RECORD_LENGTH);
    }

    /**
     * Independent field oracle: reproduces a move into a fixed-width alphanumeric field, so a
     * short value is padded on the right with the ASCII space and a long value is cut at the
     * field width.
     *
     * @param value the value moved into the field
     * @param width the field width in encoded bytes
     * @return an image of exactly {@code width} bytes
     */
    private static byte[] expectedField(final String value, final int width) {
        final byte[] valueBytes = asciiBytes(value);
        final byte[] image = new byte[width];
        Arrays.fill(image, ASCII_SPACE);
        System.arraycopy(valueBytes, 0, image, 0, Math.min(valueBytes.length, width));
        return image;
    }

    /**
     * Extracts a byte range for a positional comparison.
     *
     * @param image        the record image
     * @param from         the inclusive start index
     * @param toExclusive  the exclusive end index
     * @return the requested bytes
     */
    private static byte[] segment(final byte[] image, final int from, final int toExclusive) {
        return Arrays.copyOfRange(image, from, toExclusive);
    }

    /**
     * Finds a byte sequence inside a record image by explicit byte comparison.
     *
     * <p>This is a plain scan over encoded bytes. It performs no matching of any kind beyond
     * byte equality, so a space, a quote or a letter case difference makes the sequence
     * absent.</p>
     *
     * @param image  the record image to search
     * @param needle the literal whose encoded bytes are sought
     * @return the index of the first occurrence, or {@link #NOT_FOUND} if the sequence is absent
     */
    private static int indexOfBytes(final byte[] image, final String needle) {
        final byte[] pattern = asciiBytes(needle);
        for (int start = 0; start + pattern.length <= image.length; start++) {
            boolean matched = true;
            for (int offset = 0; offset < pattern.length && matched; offset++) {
                matched = image[start + offset] == pattern[offset];
            }
            if (matched) {
                return start;
            }
        }
        return NOT_FOUND;
    }

    /**
     * Asserts that a record carries a byte sequence exactly as written.
     *
     * @param record the published record
     * @param needle the literal that must be present
     */
    private static void assertCarriesBytes(final String record, final String needle) {
        assertThat(indexOfBytes(asciiBytes(record), needle))
                .as("the byte sequence [%s] must be present exactly as written", needle)
                .isNotEqualTo(NOT_FOUND);
    }

    /**
     * Asserts that a record does not carry a byte sequence anywhere.
     *
     * @param record the published record
     * @param needle the literal that must be absent
     */
    private static void assertLacksBytes(final String record, final String needle) {
        assertThat(indexOfBytes(asciiBytes(record), needle))
                .as("the byte sequence [%s] must be absent", needle)
                .isEqualTo(NOT_FOUND);
    }

    /**
     * Asserts that every byte from a given index to the end of the image is the ASCII space,
     * which is how the significant width of a composed record is proved without removing
     * anything from it.
     *
     * @param image            the record image
     * @param significantWidth the number of significant bytes the record carries
     */
    private static void assertSpacePaddedFrom(final byte[] image, final int significantWidth) {
        for (int index = significantWidth; index < image.length; index++) {
            assertThat(image[index])
                    .as("the byte at index %d must be the ASCII space", index)
                    .isEqualTo(ASCII_SPACE);
        }
    }

    /**
     * Asserts that a record image carries no line terminator and no tab byte.
     *
     * @param image the record image
     * @param label an identification of the image, used in the failure message
     */
    private static void assertFreeOfLineTerminatorAndTabBytes(final byte[] image, final String label) {
        for (int index = 0; index < image.length; index++) {
            assertThat(image[index])
                    .as("%s must carry no line feed at index %d", label, index)
                    .isNotEqualTo(ASCII_LINE_FEED);
            assertThat(image[index])
                    .as("%s must carry no carriage return at index %d", label, index)
                    .isNotEqualTo(ASCII_CARRIAGE_RETURN);
            assertThat(image[index])
                    .as("%s must carry no tab at index %d", label, index)
                    .isNotEqualTo(ASCII_HORIZONTAL_TAB);
        }
    }

    /*
     * ========================================================================================
     * The thirty-four fixed templates: width, content, padding and cleanliness.
     * ========================================================================================
     */

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("fixedTemplateCases")
    @DisplayName("every fixed template is exactly one hundred encoded bytes and its literal fits the record")
    void eachFixedTemplateIsExactlyOneRecordWide(final int position, final String legacyName,
            final String publishedRecord, final String expectedContent) {

        // The invariant the class's private initialisation guard protects: no literal may be
        // widened past the record. The guard itself raises IllegalStateException naming the
        // template, but it is private and unreachable through the published API, and reaching
        // it reflectively is prohibited, so the invariant is pinned here instead.
        assertThat(asciiWidth(expectedContent))
                .as("legacy literal %d %s must fit inside the record", position, legacyName)
                .isLessThanOrEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(asciiWidth(publishedRecord))
                .as("template %d %s must be exactly one record wide", position, legacyName)
                .isEqualTo(EXPECTED_RECORD_LENGTH);
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("fixedTemplateCases")
    @DisplayName("every fixed template matches its hand-written literal byte for byte")
    void eachFixedTemplateMatchesItsHandWrittenRecordImage(final int position, final String legacyName,
            final String publishedRecord, final String expectedContent) {

        assertThat(asciiBytes(publishedRecord))
                .as("template %d %s must equal its hand-written record image byte for byte",
                        position, legacyName)
                .isEqualTo(expectedRecord(expectedContent));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("fixedTemplateCases")
    @DisplayName("every fixed template is its literal followed by ASCII space padding and nothing else")
    void eachFixedTemplateIsItsLiteralFollowedOnlyBySpacePadding(final int position,
            final String legacyName, final String publishedRecord, final String expectedContent) {

        final byte[] image = asciiBytes(publishedRecord);
        final int significantWidth = asciiWidth(expectedContent);

        assertThat(segment(image, 0, significantWidth))
                .as("template %d %s must open with its literal", position, legacyName)
                .isEqualTo(asciiBytes(expectedContent));

        assertSpacePaddedFrom(image, significantWidth);
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("fixedTemplateCases")
    @DisplayName("no fixed template carries a line terminator or a tab byte")
    void eachFixedTemplateCarriesNoLineTerminatorOrTabByte(final int position,
            final String legacyName, final String publishedRecord, final String expectedContent) {

        // The emitting COBOL source file uses carriage-return plus line-feed endings, but that
        // is a property of that file and not of the emitted records. Record separation belongs
        // to the writer in the batch layer.
        assertFreeOfLineTerminatorAndTabBytes(asciiBytes(expectedContent),
                "literal " + position + " " + legacyName);
        assertFreeOfLineTerminatorAndTabBytes(asciiBytes(publishedRecord),
                "template " + position + " " + legacyName);
    }

    /*
     * ========================================================================================
     * The ordered accessor.
     *
     * MALFORMATION FIVE, recorded and not asserted: in the emitted artefact the whole document
     * scaffold, from the document type declaration through the closing html element, repeats
     * once per account. That repetition is decided by the statement-generation service, which
     * reproduces the legacy dispatcher. This class publishes declaration order only, so no
     * document is built here and no emission sequence is asserted.
     * ========================================================================================
     */

    @Test
    @DisplayName("the ordered accessor returns exactly thirty-four templates in legacy declaration order")
    void orderedAccessorReturnsThirtyFourTemplatesInDeclarationOrder() {
        final List<String> published = StatementHtmlTemplates.fixedTemplates();

        assertThat(published)
                .as("the accessor must publish every declared template and no more")
                .hasSize(EXPECTED_TEMPLATE_COUNT);

        for (int index = 0; index < EXPECTED_TEMPLATE_COUNT; index++) {
            assertThat(asciiBytes(published.get(index)))
                    .as("position %d must be %s", index + 1, EXPECTED_LEGACY_NAMES.get(index))
                    .isEqualTo(expectedRecord(EXPECTED_TEMPLATE_CONTENTS.get(index)));

            // A structural check, not a second expectation: the accessor must publish the very
            // constant declared at this position, so the sequence cannot drift away from the
            // declarations even if two of them ever held equal content.
            assertThat(published.get(index))
                    .as("position %d must be the constant declared %d places into the class",
                            index + 1, index + 1)
                    .isSameAs(PUBLISHED_TEMPLATES_IN_ORDER.get(index));
        }
    }

    @Test
    @DisplayName("the ordered accessor is unmodifiable, so no caller can reorder or replace the scaffold")
    void orderedAccessorIsUnmodifiable() {
        final List<String> published = StatementHtmlTemplates.fixedTemplates();

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("adding to the published sequence must be refused")
                .isThrownBy(() -> published.add("<hr>"));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("replacing an element of the published sequence must be refused")
                .isThrownBy(() -> published.set(0, "<hr>"));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("removing an element of the published sequence must be refused")
                .isThrownBy(() -> published.remove(0));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("clearing the published sequence must be refused")
                .isThrownBy(published::clear);
    }

    @Test
    @DisplayName("the declared scaffold runs from the document type declaration to the closing html element")
    void declaredScaffoldOpensWithTheDocumentTypeAndEndsWithTheClosingHtmlElement() {
        final List<String> published = StatementHtmlTemplates.fixedTemplates();

        assertThat(asciiBytes(published.get(0)))
                .as("the scaffold must open with the document type declaration")
                .isEqualTo(expectedRecord("<!DOCTYPE html>"));

        assertThat(asciiBytes(published.get(EXPECTED_TEMPLATE_COUNT - 1)))
                .as("the scaffold must end with the closing html element")
                .isEqualTo(expectedRecord("</html>"));
    }

    /*
     * ========================================================================================
     * MALFORMATION ONE :: two consecutive space bytes inside the table start tag.
     * ========================================================================================
     */

    @Test
    @DisplayName("Malformation one: the table start tag keeps its two consecutive space bytes, never collapsed to one")
    void tableStartTagKeepsItsTwoConsecutiveSpaceBytes() {
        final byte[] image = asciiBytes(StatementHtmlTemplates.HTML_L08);

        // Byte inspection at fixed indices. The tag name occupies indices 0 to 5, the two space
        // bytes sit at indices 6 and 7, and the first attribute name starts at index 8.
        assertThat(segment(image, 0, 6))
                .as("the tag name must be present exactly as written")
                .isEqualTo(asciiBytes("<table"));

        assertThat(image[6])
                .as("the byte at index 6 must be the ASCII space")
                .isEqualTo(ASCII_SPACE);

        assertThat(image[7])
                .as("the byte at index 7 must also be the ASCII space")
                .isEqualTo(ASCII_SPACE);

        assertThat(image[8])
                .as("the byte at index 8 must begin the attribute name, so there are exactly two spaces")
                .isNotEqualTo(ASCII_SPACE);

        assertThat(segment(image, 0, 13))
                .as("the tag opening must carry both space bytes between the name and the attribute")
                .isEqualTo(asciiBytes("<table  align"));

        // A whitespace normaliser would leave the one-space form, and a minified rewrite would
        // leave no space at all. Neither may appear.
        assertLacksBytes(StatementHtmlTemplates.HTML_L08, "<table align");
        assertLacksBytes(StatementHtmlTemplates.HTML_L08, "<tablealign");

        // Negative control. The record must differ from the image a whitespace normaliser would
        // have produced, which is what proves this comparison is sensitive to exactly the byte
        // at risk rather than passing for an unrelated reason.
        assertThat(image)
                .as("the record must not equal the collapsed one-space form of the same tag")
                .isNotEqualTo(expectedRecord(
                        "<table align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">"));
    }

    /*
     * ========================================================================================
     * MALFORMATION TWO :: the space before the background colour property is present in one
     * table-cell family and absent in the other.
     * ========================================================================================
     */

    @ParameterizedTest(name = "{0}")
    @MethodSource("spanningCellCases")
    @DisplayName("Malformation two, first family: the four spanning cells carry no space "
            + "before the background colour property")
    void spanningCellsCarryNoSpaceBeforeTheBackgroundColourProperty(final String legacyName,
            final String publishedRecord) {

        assertCarriesBytes(publishedRecord, SPANNING_CELL_STYLE_FRAGMENT);
        assertLacksBytes(publishedRecord, SIZED_CELL_STYLE_FRAGMENT);

        assertThat(indexOfBytes(asciiBytes(publishedRecord), "colspan=\"3\""))
                .as("%s must be a spanning cell", legacyName)
                .isNotEqualTo(NOT_FOUND);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sizedCellCases")
    @DisplayName("Malformation two, second family: the six sized cells carry a space "
            + "before the background colour property")
    void sizedCellsCarryASpaceBeforeTheBackgroundColourProperty(final String legacyName,
            final String publishedRecord) {

        assertCarriesBytes(publishedRecord, SIZED_CELL_STYLE_FRAGMENT);
        assertLacksBytes(publishedRecord, SPANNING_CELL_STYLE_FRAGMENT);

        assertThat(indexOfBytes(asciiBytes(publishedRecord), "style=\"width:"))
                .as("%s must be an explicitly sized cell", legacyName)
                .isNotEqualTo(NOT_FOUND);
    }

    @Test
    @DisplayName("Malformation two: the two table-cell families space their style differently "
            + "on purpose and neither is changed toward the other")
    void theTwoTableCellFamiliesSpaceTheirStyleDifferentlyAndNeitherIsChanged() {
        // The inconsistency is contractual. Aligning the families in either direction would
        // alter the emitted bytes, so the spacing of each is reproduced exactly as found.
        assertThat(asciiBytes(SPANNING_CELL_STYLE_FRAGMENT))
                .as("the two style fragments must differ, which is the inconsistency itself")
                .isNotEqualTo(asciiBytes(SIZED_CELL_STYLE_FRAGMENT));

        assertThat(asciiWidth(SIZED_CELL_STYLE_FRAGMENT))
                .as("the sized family's fragment is one byte wider, and that byte is the space")
                .isEqualTo(asciiWidth(SPANNING_CELL_STYLE_FRAGMENT) + 1);

        // One member of each family, asserted against the other family's fragment.
        assertCarriesBytes(StatementHtmlTemplates.HTML_L10, SPANNING_CELL_STYLE_FRAGMENT);
        assertLacksBytes(StatementHtmlTemplates.HTML_L10, SIZED_CELL_STYLE_FRAGMENT);
        assertCarriesBytes(StatementHtmlTemplates.HTML_L47, SIZED_CELL_STYLE_FRAGMENT);
        assertLacksBytes(StatementHtmlTemplates.HTML_L47, SPANNING_CELL_STYLE_FRAGMENT);

        // Negative controls, one per direction. Aligning either family on the other produces a
        // different record, so a change in either direction fails rather than passing quietly.
        assertThat(asciiBytes(StatementHtmlTemplates.HTML_L10))
                .as("the spanning cell must not equal the same style with the space inserted")
                .isNotEqualTo(expectedRecord(
                        "<td colspan=\"3\" style=\"padding:0px 5px; background-color:#1d1d96b3;\">"));

        assertThat(asciiBytes(StatementHtmlTemplates.HTML_L47))
                .as("the sized cell must not equal the same style with the space removed")
                .isNotEqualTo(expectedRecord(
                        "<td style=\"width:25%; padding:0px 5px;background-color:#33FF5E; text-align:left;\">"));
    }

    /*
     * ========================================================================================
     * MALFORMATION THREE :: a declared template the legacy program never sets.
     * ========================================================================================
     */

    @Test
    @DisplayName("Malformation three: the bare table-cell start tag is declared at full record "
            + "width even though the legacy program never emits it")
    void bareTableCellTemplateIsDeclaredAtFullRecordWidth() {
        // The legacy set-flag census over the emitting program: the bare cell start tag is set
        // zero times, the cell end tag thirteen times, the row start tag nine times and the row
        // end tag nine times. A bare cell start tag is therefore never emitted, yet the template
        // is declared, so it is kept rather than removed as unused. Nothing is asserted about
        // emission: this class supplies templates and never writes a record.
        assertThat(asciiWidth(StatementHtmlTemplates.HTML_LTDS))
                .as("the bare cell start tag must still be published at full record width")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(asciiBytes(StatementHtmlTemplates.HTML_LTDS))
                .as("the bare cell start tag must equal its hand-written record image")
                .isEqualTo(expectedRecord("<td>"));

        // Its emitted neighbours are published alongside it and are unaffected by its disuse.
        assertThat(asciiBytes(StatementHtmlTemplates.HTML_LTDE))
                .as("the cell end tag must be published unchanged")
                .isEqualTo(expectedRecord("</td>"));

        assertThat(asciiBytes(StatementHtmlTemplates.HTML_LTRS))
                .as("the row start tag must be published unchanged")
                .isEqualTo(expectedRecord("<tr>"));

        assertThat(asciiBytes(StatementHtmlTemplates.HTML_LTRE))
                .as("the row end tag must be published unchanged")
                .isEqualTo(expectedRecord("</tr>"));

        // The unused template keeps its declared position, eleventh of the thirty-four, rather
        // than being dropped from the sequence as unreferenced.
        assertThat(asciiBytes(StatementHtmlTemplates.fixedTemplates().get(10)))
                .as("the unused template stays at its declared position in the published sequence")
                .isEqualTo(expectedRecord("<td>"));
    }

    /*
     * ========================================================================================
     * MALFORMATION FOUR :: one composed line closes its tag and the other does not.
     *
     * The account-number heading is thirty-four plus twenty plus five bytes and closes. The
     * customer-name line is twenty-six plus fifty bytes and stops there. Both are padded to the
     * hundred-byte record. Every byte of both records is accounted for below by positional
     * comparison, so nothing has to be removed from either record to measure it.
     * ========================================================================================
     */

    @Test
    @DisplayName("Malformation four, the control: the account-number heading closes its tag "
            + "at fifty-nine significant bytes")
    void accountNumberLineClosesItsHeadingAtFiftyNineSignificantBytes() {
        final String accountIdentifier = "00000000011";
        final byte[] image = asciiBytes(StatementHtmlTemplates.accountNumberLine(accountIdentifier));

        assertThat(image.length)
                .as("the heading must occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, 0, EXPECTED_ACCOUNT_PREFIX_LENGTH))
                .as("the leading literal must be present exactly as written, trailing space included")
                .isEqualTo(asciiBytes(EXPECTED_ACCOUNT_PREFIX));

        assertThat(segment(image, EXPECTED_ACCOUNT_PREFIX_LENGTH,
                        EXPECTED_ACCOUNT_PREFIX_LENGTH + EXPECTED_ACCOUNT_FIELD_LENGTH))
                .as("the identifier must be moved in raw and padded across its twenty-byte field")
                .isEqualTo(expectedField(accountIdentifier, EXPECTED_ACCOUNT_FIELD_LENGTH));

        assertThat(segment(image, EXPECTED_ACCOUNT_DECLARED_LENGTH - EXPECTED_ACCOUNT_SUFFIX_LENGTH,
                        EXPECTED_ACCOUNT_DECLARED_LENGTH))
                .as("the significant bytes must end with the five-byte closing tag before the padding")
                .isEqualTo(asciiBytes(EXPECTED_ACCOUNT_SUFFIX));

        assertSpacePaddedFrom(image, EXPECTED_ACCOUNT_DECLARED_LENGTH);

        assertCarriesBytes(StatementHtmlTemplates.accountNumberLine(accountIdentifier),
                EXPECTED_ACCOUNT_SUFFIX);
    }

    @Test
    @DisplayName("the account-number heading cuts an over-long identifier at its twenty-byte field")
    void accountNumberLineCutsAnOverLongIdentifierAtTheFieldWidth() {
        final String overLongIdentifier = "0123456789012345678901234";
        final byte[] image = asciiBytes(StatementHtmlTemplates.accountNumberLine(overLongIdentifier));

        assertThat(image.length)
                .as("the heading must still occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, EXPECTED_ACCOUNT_PREFIX_LENGTH,
                        EXPECTED_ACCOUNT_PREFIX_LENGTH + EXPECTED_ACCOUNT_FIELD_LENGTH))
                .as("only the first twenty bytes of the identifier may reach the record")
                .isEqualTo(asciiBytes("01234567890123456789"));

        assertThat(segment(image, EXPECTED_ACCOUNT_DECLARED_LENGTH - EXPECTED_ACCOUNT_SUFFIX_LENGTH,
                        EXPECTED_ACCOUNT_DECLARED_LENGTH))
                .as("the closing tag must keep its position even when the identifier is cut")
                .isEqualTo(asciiBytes(EXPECTED_ACCOUNT_SUFFIX));

        assertSpacePaddedFrom(image, EXPECTED_ACCOUNT_DECLARED_LENGTH);
    }

    @Test
    @DisplayName("the account-number heading pads an empty identifier across the whole field")
    void accountNumberLinePadsAnEmptyIdentifierAcrossTheWholeField() {
        final byte[] image = asciiBytes(StatementHtmlTemplates.accountNumberLine(""));

        assertThat(image.length)
                .as("the heading must still occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, EXPECTED_ACCOUNT_PREFIX_LENGTH,
                        EXPECTED_ACCOUNT_PREFIX_LENGTH + EXPECTED_ACCOUNT_FIELD_LENGTH))
                .as("an empty identifier must leave a field of twenty ASCII spaces")
                .isEqualTo(expectedField("", EXPECTED_ACCOUNT_FIELD_LENGTH));

        assertThat(segment(image, EXPECTED_ACCOUNT_DECLARED_LENGTH - EXPECTED_ACCOUNT_SUFFIX_LENGTH,
                        EXPECTED_ACCOUNT_DECLARED_LENGTH))
                .as("the closing tag must keep its position when the field is empty")
                .isEqualTo(asciiBytes(EXPECTED_ACCOUNT_SUFFIX));
    }

    @Test
    @DisplayName("Malformation four: the customer-name line is emitted with no closing tag "
            + "at seventy-six significant bytes")
    void customerNameLineIsEmittedWithNoClosingTagAtSeventySixSignificantBytes() {
        final String customerName = "JOHN Q PUBLIC";
        final String record = StatementHtmlTemplates.customerNameLine(customerName);
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("the name line must occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, 0, EXPECTED_NAME_PREFIX_LENGTH))
                .as("the opening paragraph literal must be present exactly as written")
                .isEqualTo(asciiBytes(EXPECTED_NAME_PREFIX));

        assertThat(segment(image, EXPECTED_NAME_PREFIX_LENGTH, EXPECTED_NAME_DECLARED_LENGTH))
                .as("the name must be moved in raw and padded across its fifty-byte field")
                .isEqualTo(expectedField(customerName, EXPECTED_NAME_FIELD_LENGTH));

        assertSpacePaddedFrom(image, EXPECTED_NAME_DECLARED_LENGTH);

        // There is no third component in the legacy group, so no closing tag may be synthesised.
        // Not a paragraph close, and not any end tag at all.
        assertLacksBytes(record, "</p>");
        assertLacksBytes(record, "</");

        // The contrast that proves the omission is specific rather than systematic: the
        // neighbouring heading does close.
        assertCarriesBytes(StatementHtmlTemplates.accountNumberLine("00000000011"),
                EXPECTED_ACCOUNT_SUFFIX);

        // Negative control against a tag balancer. The balanced form is written out here and the
        // record must differ from it, so appending a closing tag could never pass unnoticed.
        final String balancedForm = EXPECTED_NAME_PREFIX
                + new String(expectedField(customerName, EXPECTED_NAME_FIELD_LENGTH),
                        StandardCharsets.US_ASCII)
                + "</p>";
        assertThat(image)
                .as("the name line must not equal the balanced form a tag balancer would produce")
                .isNotEqualTo(expectedRecord(balancedForm));
    }

    @Test
    @DisplayName("the customer-name line cuts an over-long name at its fifty-byte field and stays unclosed")
    void customerNameLineCutsAnOverLongNameAtTheFieldWidthAndStaysUnclosed() {
        final String overLongName = "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJKLMNO";
        final String record = StatementHtmlTemplates.customerNameLine(overLongName);
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("the name line must still occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, EXPECTED_NAME_PREFIX_LENGTH, EXPECTED_NAME_DECLARED_LENGTH))
                .as("only the first fifty bytes of the name may reach the record")
                .isEqualTo(asciiBytes("ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ"));

        assertThat(segment(image, 0, EXPECTED_NAME_PREFIX_LENGTH))
                .as("the opening paragraph literal must be unaffected by the cut")
                .isEqualTo(asciiBytes(EXPECTED_NAME_PREFIX));

        assertSpacePaddedFrom(image, EXPECTED_NAME_DECLARED_LENGTH);
        assertLacksBytes(record, "</");
    }

    @Test
    @DisplayName("the customer-name line pads an empty name across the whole field and stays unclosed")
    void customerNameLinePadsAnEmptyNameAcrossTheWholeFieldAndStaysUnclosed() {
        final String record = StatementHtmlTemplates.customerNameLine("");
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("the name line must still occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, EXPECTED_NAME_PREFIX_LENGTH, EXPECTED_NAME_DECLARED_LENGTH))
                .as("an empty name must leave a field of fifty ASCII spaces")
                .isEqualTo(expectedField("", EXPECTED_NAME_FIELD_LENGTH));

        assertSpacePaddedFrom(image, EXPECTED_NAME_PREFIX_LENGTH);
        assertLacksBytes(record, "</");
    }

    @Test
    @DisplayName("both composed lines carry no line terminator and no tab byte")
    void bothComposedLinesCarryNoLineTerminatorOrTabByte() {
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.accountNumberLine("00000000011")),
                "the account-number heading");
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.customerNameLine("JOHN Q PUBLIC")),
                "the customer-name line");
    }

    @Test
    @DisplayName("the component widths sum to the declared group widths: thirty-four plus "
            + "twenty plus five, and twenty-six plus fifty")
    void componentWidthsSumToTheDeclaredGroupWidths() {
        assertThat(EXPECTED_ACCOUNT_PREFIX_LENGTH + EXPECTED_ACCOUNT_FIELD_LENGTH
                        + EXPECTED_ACCOUNT_SUFFIX_LENGTH)
                .as("the account-number heading's components must sum to its declared width")
                .isEqualTo(EXPECTED_ACCOUNT_DECLARED_LENGTH);

        assertThat(EXPECTED_NAME_PREFIX_LENGTH + EXPECTED_NAME_FIELD_LENGTH)
                .as("the customer-name line's two components must sum to its declared width")
                .isEqualTo(EXPECTED_NAME_DECLARED_LENGTH);

        assertThat(asciiWidth(EXPECTED_ACCOUNT_PREFIX))
                .as("the heading's leading literal must measure its declared width, trailing space included")
                .isEqualTo(EXPECTED_ACCOUNT_PREFIX_LENGTH);

        assertThat(asciiWidth(EXPECTED_ACCOUNT_SUFFIX))
                .as("the heading's closing tag must measure its declared width")
                .isEqualTo(EXPECTED_ACCOUNT_SUFFIX_LENGTH);

        assertThat(asciiWidth(EXPECTED_NAME_PREFIX))
                .as("the name line's leading literal must measure its declared width")
                .isEqualTo(EXPECTED_NAME_PREFIX_LENGTH);

        assertThat(EXPECTED_NAME_DECLARED_LENGTH)
                .as("both declared groups must be shorter than the record, so both are padded")
                .isLessThan(EXPECTED_RECORD_LENGTH);

        assertThat(EXPECTED_ACCOUNT_DECLARED_LENGTH)
                .as("the heading is the shorter of the two declared groups")
                .isLessThan(EXPECTED_NAME_DECLARED_LENGTH);
    }

    /*
     * ========================================================================================
     * Colour and percentage-width bytes.
     *
     * These are legacy output content reproduced for parity, not design decisions. No design
     * system, component library, styling framework or design-token set exists anywhere in this
     * migration, so nothing below is themed, tokenised or aligned to a palette. The case of
     * every colour is reproduced character for character, in both directions: one colour is
     * lower case with an alpha channel, three are upper case, and one is lower case.
     * ========================================================================================
     */

    @Test
    @DisplayName("every colour keeps its exact case, including the eight-digit lower-case value with an alpha channel")
    void colourValuesKeepTheirExactCase() {
        // The eight-digit value with an alpha channel, in lower case.
        assertCarriesBytes(StatementHtmlTemplates.HTML_L10, "#1d1d96b3");
        assertLacksBytes(StatementHtmlTemplates.HTML_L10, "#1D1D96B3");
        assertThat(asciiWidth("#1d1d96b3"))
                .as("the alpha-carrying colour is eight hex digits behind its hash")
                .isEqualTo(9);

        // The upper-case values.
        assertCarriesBytes(StatementHtmlTemplates.HTML_L15, "#FFAF33");
        assertLacksBytes(StatementHtmlTemplates.HTML_L15, "#ffaf33");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L30_42, "#33FFD1");
        assertLacksBytes(StatementHtmlTemplates.HTML_L30_42, "#33ffd1");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L47, "#33FF5E");
        assertLacksBytes(StatementHtmlTemplates.HTML_L47, "#33ff5e");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L50, "#33FF5E");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L53, "#33FF5E");

        // The lower-case value shared by the neutral cells.
        assertCarriesBytes(StatementHtmlTemplates.HTML_L22_35, "#f2f2f2");
        assertLacksBytes(StatementHtmlTemplates.HTML_L22_35, "#F2F2F2");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L58, "#f2f2f2");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L61, "#f2f2f2");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L64, "#f2f2f2");

        // Each six-digit colour is one byte narrower than the alpha-carrying one.
        assertThat(asciiWidth("#FFAF33"))
                .as("the six-digit colours are six hex digits behind their hash")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("every percentage width is reproduced exactly as written")
    void percentageWidthValuesAreReproducedExactly() {
        assertCarriesBytes(StatementHtmlTemplates.HTML_L08, "width:70%");

        assertCarriesBytes(StatementHtmlTemplates.HTML_L47, "width:25%");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L58, "width:25%");

        assertCarriesBytes(StatementHtmlTemplates.HTML_L50, "width:55%");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L61, "width:55%");

        assertCarriesBytes(StatementHtmlTemplates.HTML_L53, "width:20%");
        assertCarriesBytes(StatementHtmlTemplates.HTML_L64, "width:20%");

        // The table width belongs to the table tag alone and to no cell.
        assertLacksBytes(StatementHtmlTemplates.HTML_L47, "width:70%");
    }

    /*
     * ========================================================================================
     * The named record and component widths.
     *
     * The statement job declares the same HTML data definition at eighty bytes in one step and
     * at one hundred in the following step, which is the step that runs the generator. The
     * conflict is resolved to one hundred for the HTML stream, matching the emitting program's
     * record declaration, and one hundred is what is asserted here.
     * ========================================================================================
     */

    @Test
    @DisplayName("the published record width, template count and component widths hold their legacy values")
    void publishedWidthsAndCountsHoldTheirLegacyValues() {
        assertThat(StatementHtmlTemplates.HTML_RECORD_LENGTH)
                .as("the HTML record is one hundred bytes, which resolves the job stream's conflict")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(StatementHtmlTemplates.FIXED_TEMPLATE_COUNT)
                .as("the legacy program declares thirty-four fixed templates")
                .isEqualTo(EXPECTED_TEMPLATE_COUNT);

        assertThat(StatementHtmlTemplates.ACCOUNT_LINE_PREFIX_LENGTH)
                .as("the heading's leading literal is thirty-four bytes")
                .isEqualTo(EXPECTED_ACCOUNT_PREFIX_LENGTH);

        assertThat(StatementHtmlTemplates.ACCOUNT_LINE_ACCOUNT_LENGTH)
                .as("the heading's identifier field is twenty bytes")
                .isEqualTo(EXPECTED_ACCOUNT_FIELD_LENGTH);

        assertThat(StatementHtmlTemplates.ACCOUNT_LINE_SUFFIX_LENGTH)
                .as("the heading's closing tag is five bytes")
                .isEqualTo(EXPECTED_ACCOUNT_SUFFIX_LENGTH);

        assertThat(StatementHtmlTemplates.ACCOUNT_LINE_DECLARED_LENGTH)
                .as("the heading group is fifty-nine bytes")
                .isEqualTo(EXPECTED_ACCOUNT_DECLARED_LENGTH);

        assertThat(StatementHtmlTemplates.NAME_LINE_PREFIX_LENGTH)
                .as("the name line's leading literal is twenty-six bytes")
                .isEqualTo(EXPECTED_NAME_PREFIX_LENGTH);

        assertThat(StatementHtmlTemplates.NAME_LINE_NAME_LENGTH)
                .as("the name line's name field is fifty bytes")
                .isEqualTo(EXPECTED_NAME_FIELD_LENGTH);

        assertThat(StatementHtmlTemplates.NAME_LINE_DECLARED_LENGTH)
                .as("the name line group is seventy-six bytes, with no closing tag inside them")
                .isEqualTo(EXPECTED_NAME_DECLARED_LENGTH);

        assertThat(StatementHtmlTemplates.ADDRESS_WORK_LINE_LENGTH)
                .as("the free-form address line shares the record width")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(StatementHtmlTemplates.BASIC_DETAILS_WORK_LINE_LENGTH)
                .as("the free-form basic-details line shares the record width")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(StatementHtmlTemplates.TRANSACTION_WORK_LINE_LENGTH)
                .as("the free-form transaction line shares the record width")
                .isEqualTo(EXPECTED_RECORD_LENGTH);
    }

    /*
     * ========================================================================================
     * The fit-to-width helper for the three free-form work lines.
     *
     * The legacy program declares an address line, a basic-details line and a transaction line,
     * each a hundred bytes with no internal structure. Their content is composed by the
     * statement-generation service, which assembles the customer name and the address lines with
     * a two-space delimiter and the basic-details and transaction lines with an asterisk
     * delimiter, so that in the second regime the entire padded field width transfers and its
     * trailing spaces are retained inside the element. That assembly is the service's concern;
     * only the width belongs here, so no delimiter regime is implemented or asserted below.
     * ========================================================================================
     */

    @Test
    @DisplayName("the work-line helper pads short content on the right to exactly one record")
    void workLinePadsShortContentToOneRecord() {
        final String content = "<p>410 Terry Ave N</p>";
        final byte[] image = asciiBytes(StatementHtmlTemplates.workLine(content));

        assertThat(image.length)
                .as("a short work line must still occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(image)
                .as("the work line must be the content followed by space padding")
                .isEqualTo(expectedRecord(content));

        assertThat(segment(image, 0, asciiWidth(content)))
                .as("the content must reach the record unaltered")
                .isEqualTo(asciiBytes(content));

        assertSpacePaddedFrom(image, asciiWidth(content));
    }

    @Test
    @DisplayName("the work-line helper cuts over-long content on the right to exactly one record and raises nothing")
    void workLineCutsOverLongContentToOneRecordWithoutRaising() {
        final String overLongContent = "A".repeat(EXPECTED_RECORD_LENGTH) + "B".repeat(20);
        final String record = StatementHtmlTemplates.workLine(overLongContent);
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("over-long content must be cut to exactly one record rather than rejected")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(image)
                .as("only the first hundred bytes of the content may reach the record")
                .isEqualTo(asciiBytes("A".repeat(EXPECTED_RECORD_LENGTH)));

        assertLacksBytes(record, "B");
    }

    @Test
    @DisplayName("the work-line helper passes exact-fit content through unchanged, trailing spaces included")
    void workLinePassesExactFitContentThroughUnchanged() {
        // Built by the oracle so that the input is already exactly one record wide, with its own
        // trailing spaces. Those spaces must survive: they are content at this width, and the
        // helper must not shorten the value in any way.
        final String exactFitContent =
                new String(expectedRecord("<p>Seattle WA 99999</p>"), StandardCharsets.US_ASCII);

        assertThat(asciiWidth(exactFitContent))
                .as("the fixture must be exactly one record wide before the call")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(asciiBytes(StatementHtmlTemplates.workLine(exactFitContent)))
                .as("exact-fit content must pass through byte for byte")
                .isEqualTo(asciiBytes(exactFitContent));
    }

    @Test
    @DisplayName("the work-line helper moves markup characters in raw, with no entity encoding and no quote rewriting")
    void workLineMovesMarkupCharactersInRaw() {
        final String rawContent = "<td>A & B \"quoted\" 'single' <end>";
        final String record = StatementHtmlTemplates.workLine(rawContent);
        final byte[] image = asciiBytes(record);

        assertThat(segment(image, 0, asciiWidth(rawContent)))
                .as("every markup character must reach the record exactly as supplied")
                .isEqualTo(asciiBytes(rawContent));

        assertThat(image)
                .as("the raw content must be padded, and never rewritten, to one record")
                .isEqualTo(expectedRecord(rawContent));

        // No entity encoding of any kind, and no quote rewriting.
        assertLacksBytes(record, "&amp;");
        assertLacksBytes(record, "&lt;");
        assertLacksBytes(record, "&gt;");
        assertLacksBytes(record, "&quot;");
        assertLacksBytes(record, "&apos;");
        assertLacksBytes(record, "&#39;");

        // The characters themselves are all still there.
        assertCarriesBytes(record, "<");
        assertCarriesBytes(record, ">");
        assertCarriesBytes(record, "&");
        assertCarriesBytes(record, "\"");
        assertCarriesBytes(record, "'");
    }

    @Test
    @DisplayName("the work-line helper pads empty content to one record of ASCII spaces")
    void workLinePadsEmptyContentToOneRecordOfSpaces() {
        final byte[] image = asciiBytes(StatementHtmlTemplates.workLine(""));

        assertThat(image.length)
                .as("empty content must still occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertSpacePaddedFrom(image, 0);
    }

    @Test
    @DisplayName("the work-line helper carries no line terminator and no tab byte")
    void workLineCarriesNoLineTerminatorOrTabByte() {
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.workLine("<p>410 Terry Ave N</p>")),
                "a free-form work line");
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.workLine("")),
                "an empty free-form work line");
    }

    /*
     * ========================================================================================
     * Argument rejection. Each parameter is checked on its own.
     * ========================================================================================
     */

    @Test
    @DisplayName("the account-number heading refuses a null identifier")
    void accountNumberLineRefusesANullIdentifier() {
        assertThatNullPointerException()
                .as("a null identifier must be refused rather than moved in as text")
                .isThrownBy(() -> StatementHtmlTemplates.accountNumberLine(null));
    }

    @Test
    @DisplayName("the customer-name line refuses a null name")
    void customerNameLineRefusesANullName() {
        assertThatNullPointerException()
                .as("a null name must be refused rather than moved in as text")
                .isThrownBy(() -> StatementHtmlTemplates.customerNameLine(null));
    }

    @Test
    @DisplayName("the work-line helper refuses null content")
    void workLineRefusesNullContent() {
        assertThatNullPointerException()
                .as("null content must be refused rather than moved in as text")
                .isThrownBy(() -> StatementHtmlTemplates.workLine(null));
    }

}
