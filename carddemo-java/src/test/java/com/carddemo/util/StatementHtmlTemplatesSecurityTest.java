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
 * composed lines built from a literal plus a substituted value, three composers for the
 * free-form work lines &mdash; the address line, the basic-details line and the transaction
 * line, each reproducing its own legacy {@code STRING} statement &mdash; one published escaping
 * method, and the named record and component widths. Every published record is exactly
 * <strong>one hundred</strong> encoded bytes wide, which is the width of one HTML output
 * record.</p>
 *
 * <p>This is a direct end-to-end byte-parity surface. The migrated batch stream has to be
 * byte-identical to the stream the legacy generator produced, so every assertion below
 * compares <strong>encoded byte arrays</strong> rather than strings, and every width is
 * measured through {@link java.nio.charset.StandardCharsets#US_ASCII} rather than through a
 * character count. The class's own <em>markup</em> is never trimmed, collapsed, re-spaced,
 * re-cased, balanced, re-quoted, entity-encoded or reformatted in any way, and no markup tooling
 * of any kind is used: a tool that silently improved the markup would break the very bytes this
 * test exists to pin down. The <em>data</em> substituted into that markup is escaped, which is
 * the single recorded divergence and is developed below.</p>
 *
 * <h2>The markup-versus-data division</h2>
 *
 * <p>Two categories of byte pass through this class and they are treated differently, which is
 * the point most likely to be misread. The <strong>markup</strong> &mdash; the thirty-four fixed
 * templates, the heading literal, the quoted-attribute paragraph literal and the paragraph tags
 * the three work-line composers own &mdash; is reproduced byte for byte, malformations included.
 * The <strong>data</strong> substituted into it &mdash; the account identifier, the customer
 * name, the address lines, the basic-detail values and the transaction values &mdash; has the
 * five markup-significant characters replaced by their character references.</p>
 *
 * <p>The legacy program escaped nothing, because on the mainframe the data could only have come
 * from a VSAM record written by a sibling batch program in the same estate. That reasoning does
 * not hold here: the same values arrive from a relational store an online maintenance screen
 * writes, and the name and address fields accept free text. A value such as {@code <script>}
 * stored through one of those screens and later rendered into a statement is stored cross-site
 * scripting, and the statement file is written once and read by an unknown viewer later, so
 * there is no serving boundary to defer the escaping to.</p>
 *
 * <p>The divergence is the narrowest available and this test asserts both halves of that claim.
 * Escaping is the <em>identity function</em> on the entire legitimate domain of every field the
 * class touches, so for real data the emitted bytes are unchanged and the hundred-byte parity
 * gate is untouched; it differs only for input that would otherwise inject markup. There is also
 * deliberately <strong>no builder that accepts already-composed markup</strong>: the markup is
 * owned inside the class, which is what makes an unescaped-data path into the output unreachable
 * rather than merely discouraged. Both properties are asserted rather than assumed.</p>
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
 * <h2>Outside this class's contract, and where each belongs instead</h2>
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
 *   <li><strong>Which record follows which.</strong> The emitting program interleaves the fixed
 *       templates and the composed lines in a fixed order, once per account and once per
 *       transaction. That sequencing belongs to the statement-generation service. This test
 *       asserts the declaration order the accessor publishes and the content of each individual
 *       record, and never assembles a document.</li>
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
 * <p>Two divergences here are <em>behavioural</em> rather than layout-preserving, and both are
 * asserted in this file. First, the five markup-significant characters are replaced by their
 * character references in every data position, where the legacy program substituted them raw;
 * this is the identity function on all legitimate data, so no real record changes. Second, a
 * character reference cut by the hundred-byte boundary is blanked with the ASCII space instead of
 * being emitted as a fragment, because a fragment such as {@code &am} can resynchronise against
 * the markup that follows; the field keeps its exact width, and a reference that completes on or
 * before the cut is left untouched.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy estate at checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release
 * stamp CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. The stamp is recorded here as a header
 * note only; it is never asserted against any member of the class under test.</p>
 */
@DisplayName("StatementHtmlTemplates :: hundred-byte HTML statement line templates")
class StatementHtmlTemplatesSecurityTest {

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

    /**
     * The two literal spaces the emitting {@code STRING} appends after the transferred name, hand
     * written from the third sending item at {@code app/cbl/CBSTM03A.CBL:L564}.
     */
    private static final String EXPECTED_NAME_DELIMITER = "  ";

    /**
     * The closing paragraph literal the emitting {@code STRING} appends last, hand written from the
     * fourth sending item at {@code app/cbl/CBSTM03A.CBL:L565}.
     *
     * <p>An earlier reading of this line took its shape from the staging group
     * {@code L23-NAME} rather than from the emitting paragraph, and concluded that the line carried no
     * closing tag and that the name occupied its full fifty bytes. The emitting {@code STRING} at
     * lines 562 to 567 has four sending items: the opening literal delimited by an absent character
     * so it transfers whole, the staging field <strong>delimited by two spaces</strong> so it
     * transfers only up to its first pair of adjacent spaces, two literal spaces, and this closing
     * literal. The record the program writes therefore does close, and the name field is right
     * trimmed rather than padded. The declared group width remains recorded above as the width of the
     * staging group, which is what it describes.
     */
    private static final String EXPECTED_NAME_CLOSING_TAG = "</p>";

    /*
     * ----------------------------------------------------------------------------------------
     * Independently written literals of the three free-form work lines. Each is transcribed by
     * hand from the legacy STRING statement that composes that line, so that the composition
     * asserted below is compared against the source rather than against the class under test.
     * ----------------------------------------------------------------------------------------
     */

    /** The opening paragraph tag every work line begins with [app/cbl/CBSTM03A.CBL:L570]. */
    private static final String EXPECTED_PARAGRAPH_OPEN = "<p>";

    /** The closing paragraph tag every work line ends with [app/cbl/CBSTM03A.CBL:L573]. */
    private static final String EXPECTED_PARAGRAPH_CLOSE = "</p>";

    /**
     * The two literal spaces the address line appends unconditionally, sent
     * {@code DELIMITED BY SIZE} so they arrive whether or not the address filled its field
     * [app/cbl/CBSTM03A.CBL:L572].
     */
    private static final String EXPECTED_ADDRESS_TRAILING_SPACES = "  ";

    /**
     * The address line's transfer delimiter [app/cbl/CBSTM03A.CBL:L571]. COBOL sends the address
     * field only up to, and excluding, the first run of two consecutive spaces.
     */
    private static final String EXPECTED_ADDRESS_DELIMITER = "  ";

    /** Basic-details label one, from {@code '<p>Account ID         : '} with the tag removed. */
    private static final String EXPECTED_ACCOUNT_ID_LABEL = "Account ID         : ";

    /** Basic-details label two, from {@code '<p>Current Balance    : '} with the tag removed. */
    private static final String EXPECTED_CURRENT_BALANCE_LABEL = "Current Balance    : ";

    /** Basic-details label three, from {@code '<p>FICO Score         : '} with the tag removed. */
    private static final String EXPECTED_FICO_SCORE_LABEL = "FICO Score         : ";

    /**
     * The width every legacy basic-details opening literal shares: three bytes of paragraph tag
     * plus a twenty-one-byte label. All three literals are the same width, which is what aligns
     * the values down the emitted column.
     */
    private static final int EXPECTED_BASIC_DETAILS_LABEL_LENGTH = 21;

    /*
     * ----------------------------------------------------------------------------------------
     * Independently written character references. The escaping surface is asserted against
     * these literals rather than against any constant of the class under test.
     * ----------------------------------------------------------------------------------------
     */

    /** The reference for the ampersand, which has to be substituted before any other. */
    private static final String EXPECTED_AMPERSAND_REFERENCE = "&amp;";

    /** The reference for the less-than sign, the character that opens a tag. */
    private static final String EXPECTED_LESS_THAN_REFERENCE = "&lt;";

    /** The reference for the greater-than sign, the character that closes a tag. */
    private static final String EXPECTED_GREATER_THAN_REFERENCE = "&gt;";

    /** The reference for the quotation mark, which could otherwise close a quoted attribute. */
    private static final String EXPECTED_QUOTATION_MARK_REFERENCE = "&quot;";

    /**
     * The reference for the apostrophe. Numeric rather than named: {@code &apos;} is undefined in
     * HTML 4 and a statement file has no controlled viewer, so the numeric form is the only one
     * defined everywhere the artefact may be opened.
     */
    private static final String EXPECTED_APOSTROPHE_REFERENCE = "&#39;";

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

    /**
     * Supplies the three basic-details labels the legacy program carries, each transcribed by
     * hand from its {@code STRING} literal with the paragraph tag removed
     * [app/cbl/CBSTM03A.CBL:L614, L621, L628].
     *
     * @return the three labels, in legacy emission order
     */
    static Stream<Arguments> basicDetailsLabels() {
        return Stream.of(
                Arguments.of(EXPECTED_ACCOUNT_ID_LABEL),
                Arguments.of(EXPECTED_CURRENT_BALANCE_LABEL),
                Arguments.of(EXPECTED_FICO_SCORE_LABEL));
    }

    /**
     * Supplies the five markup-significant characters paired with the character reference each
     * must become. Every reference is a hand-written literal.
     *
     * @return five cases, each a raw character and its expected reference
     */
    static Stream<Arguments> markupCharacterReferences() {
        return Stream.of(
                Arguments.of("&", EXPECTED_AMPERSAND_REFERENCE),
                Arguments.of("<", EXPECTED_LESS_THAN_REFERENCE),
                Arguments.of(">", EXPECTED_GREATER_THAN_REFERENCE),
                Arguments.of("\"", EXPECTED_QUOTATION_MARK_REFERENCE),
                Arguments.of("'", EXPECTED_APOSTROPHE_REFERENCE));
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
     * The ordered accessor. This class publishes declaration order only: it builds no document
     * and asserts no emission sequence, both of which belong to the statement-generation
     * service that reproduces the legacy dispatcher.
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
                .as("the identifier must be moved in unchanged, substitution being the identity "
                        + "function on a numeric identifier, and padded across its twenty bytes")
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
    @DisplayName("the customer-name line stops the name transfer at its first pair of adjacent "
            + "spaces and then closes, so the emitted record is shorter than the declared group "
            + "and does carry a closing tag")
    void theCustomerNameLineIsRightTrimmedAndThenClosed() {
        // The malformation on this line is the right trim, not a missing tag. The staging group is
        // twenty-six plus fifty bytes wide, but the emitting STRING transfers the staging field only
        // up to its first pair of adjacent spaces and then appends two literal spaces and the closing
        // literal, so the record a thirteen-byte name produces is forty-five significant bytes and not
        // seventy-six. The whole significant image is written out by hand below from the four sending
        // items, so no expectation is taken from the class under test.
        final String customerName = "JOHN Q PUBLIC";
        final String record = StatementHtmlTemplates.customerNameLine(customerName);
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("the name line must occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(image)
                .as("the emitted record is the four sending items in order, space padded to the "
                        + "record width")
                .isEqualTo(expectedRecord(EXPECTED_NAME_PREFIX + customerName
                        + EXPECTED_NAME_DELIMITER + EXPECTED_NAME_CLOSING_TAG));

        assertThat(segment(image, 0, EXPECTED_NAME_PREFIX_LENGTH))
                .as("the opening paragraph literal must be present exactly as written")
                .isEqualTo(asciiBytes(EXPECTED_NAME_PREFIX));

        assertThat(segment(image, EXPECTED_NAME_PREFIX_LENGTH,
                        EXPECTED_NAME_PREFIX_LENGTH + asciiWidth(customerName)))
                .as("the name must be moved in unchanged, substitution being the identity function "
                        + "on a name that carries no markup")
                .isEqualTo(asciiBytes(customerName));

        assertSpacePaddedFrom(image, EXPECTED_NAME_PREFIX_LENGTH + asciiWidth(customerName)
                + EXPECTED_NAME_DELIMITER.length() + EXPECTED_NAME_CLOSING_TAG.length());

        // The closing literal is a sending item of the emitting STRING, so it is present - and it is
        // present at the position the trim leaves it at, never at the end of the declared group.
        assertCarriesBytes(record, EXPECTED_NAME_CLOSING_TAG);
        assertThat(indexOfBytes(image, EXPECTED_NAME_CLOSING_TAG))
                .as("the closing literal sits immediately after the trimmed name and its two spaces")
                .isEqualTo(EXPECTED_NAME_PREFIX_LENGTH + asciiWidth(customerName)
                        + EXPECTED_NAME_DELIMITER.length());

        // The contrast that proves the trim is specific rather than systematic: the neighbouring
        // heading pads its field to the declared width and closes at the declared boundary.
        assertCarriesBytes(StatementHtmlTemplates.accountNumberLine("00000000011"),
                EXPECTED_ACCOUNT_SUFFIX);

        // Negative control against a field-padding reading of this line. The padded form is written
        // out here and the record must differ from it, so padding the name field out to fifty bytes
        // could never pass unnoticed.
        final String paddedForm = EXPECTED_NAME_PREFIX
                + new String(expectedField(customerName, EXPECTED_NAME_FIELD_LENGTH),
                        StandardCharsets.US_ASCII)
                + EXPECTED_NAME_CLOSING_TAG;
        assertThat(image)
                .as("the name line must not equal the form a field-padding reading would produce")
                .isNotEqualTo(expectedRecord(paddedForm));
    }

    @Test
    @DisplayName("the customer-name line cuts an over-long name at its fifty-byte field, and because "
            + "the cut leaves no pair of adjacent spaces the whole field transfers and the record "
            + "reaches its widest form of eighty-two significant bytes")
    void customerNameLineCutsAnOverLongNameAtTheFieldWidth() {
        // The widest record this line can produce: twenty-six literal bytes, the full fifty-byte
        // field, two literal spaces and a four-byte closing literal. Eighty-two is inside the
        // hundred-byte record, which is why the trim can never displace the closing literal. A name
        // this long leaves no padding in the staging field, so the delimiter is not found and the
        // whole field transfers - the legacy behaviour when a STRING delimiter is absent.
        final String overLongName = "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJKLMNO";
        final String cutName = "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ";
        final String record = StatementHtmlTemplates.customerNameLine(overLongName);
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("the name line must still occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(asciiWidth(cutName)).as("the cut name is exactly the field width")
                .isEqualTo(EXPECTED_NAME_FIELD_LENGTH);

        assertThat(image)
                .as("the widest emitted form is the four sending items in order, space padded")
                .isEqualTo(expectedRecord(EXPECTED_NAME_PREFIX + cutName
                        + EXPECTED_NAME_DELIMITER + EXPECTED_NAME_CLOSING_TAG));

        assertThat(segment(image, EXPECTED_NAME_PREFIX_LENGTH, EXPECTED_NAME_DECLARED_LENGTH))
                .as("only the first fifty bytes of the name may reach the record")
                .isEqualTo(asciiBytes(cutName));

        assertThat(segment(image, 0, EXPECTED_NAME_PREFIX_LENGTH))
                .as("the opening paragraph literal must be unaffected by the cut")
                .isEqualTo(asciiBytes(EXPECTED_NAME_PREFIX));

        assertSpacePaddedFrom(image, EXPECTED_NAME_DECLARED_LENGTH
                + EXPECTED_NAME_DELIMITER.length() + EXPECTED_NAME_CLOSING_TAG.length());
    }

    @Test
    @DisplayName("an empty name transfers nothing at all, because the staging field then begins with "
            + "a pair of adjacent spaces, so the record carries the two literals alone")
    void customerNameLineWithAnEmptyNameCarriesTheLiteralsAlone() {
        // The staging field is fifty spaces, so the delimiter is found at its very first position and
        // nothing is transferred. The record is then the opening literal, the two literal spaces and
        // the closing literal: thirty-two significant bytes. Reading the staging group instead would
        // have predicted fifty spaces in the middle and no closing literal, and both halves of that
        // prediction are wrong.
        final String record = StatementHtmlTemplates.customerNameLine("");
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("the name line must still occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(image)
                .as("the emitted record is the opening literal, the two spaces and the closing literal")
                .isEqualTo(expectedRecord(EXPECTED_NAME_PREFIX + EXPECTED_NAME_DELIMITER
                        + EXPECTED_NAME_CLOSING_TAG));

        assertSpacePaddedFrom(image, EXPECTED_NAME_PREFIX_LENGTH
                + EXPECTED_NAME_DELIMITER.length() + EXPECTED_NAME_CLOSING_TAG.length());
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
     * The three free-form work lines: address, basic details and transaction.
     *
     * The legacy program declares an address line, a basic-details line and a transaction line,
     * each a hundred bytes, and composes each one with its own STRING statement. Those three
     * statements differ from one another in ways that are load bearing, so each has its own
     * builder here and each builder is asserted against the statement it reproduces:
     *
     *   HTML-ADDR-LN  [L570-L574]  '<p>'  +  field DELIMITED BY '  '  +  '  ' DELIMITED BY SIZE
     *                              +  '</p>'
     *   HTML-BSIC-LN  [L614-L618]  '<p>Account ID         : '  +  value DELIMITED BY '*'
     *                              +  '</p>'          (and two sibling labels)
     *   HTML-TRAN-LN  [L687-L691]  '<p>'  +  value DELIMITED BY '*'  +  '</p>'
     *
     * Two differences matter. First, the address line is delimited by two spaces, so a padded
     * field is right-trimmed and a value carrying a double space in its middle is cut there;
     * the other two are delimited by an asterisk, and because no value contains one the entire
     * padded field transfers and its trailing spaces stay inside the element. Second, the
     * address line appends two literal spaces unconditionally, which the other two never do.
     * Both differences are asserted below in both directions.
     *
     * There is deliberately no builder that accepts already-composed markup. The markup is
     * owned here and the data is escaped here, which is what makes an unescaped-data path into
     * the output unreachable rather than merely discouraged.
     * ========================================================================================
     */

    @Test
    @DisplayName("the address work line reproduces its legacy composition: tag, transferred "
            + "field, two literal spaces, closing tag")
    void addressWorkLineReproducesItsLegacyComposition() {
        final String addressLine = "410 Terry Ave N";
        final String expectedContent = EXPECTED_PARAGRAPH_OPEN + addressLine
                + EXPECTED_ADDRESS_TRAILING_SPACES + EXPECTED_PARAGRAPH_CLOSE;
        final byte[] image = asciiBytes(StatementHtmlTemplates.addressWorkLine(addressLine));

        assertThat(image.length)
                .as("the address line must occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(image)
                .as("the address line must be its four legacy sending items followed by padding")
                .isEqualTo(expectedRecord(expectedContent));

        assertSpacePaddedFrom(image, asciiWidth(expectedContent));
    }

    @Test
    @DisplayName("the address work line transfers the whole field when the two-space delimiter "
            + "is absent")
    void addressWorkLineTransfersTheWholeFieldWhenTheDelimiterIsAbsent() {
        // Single spaces between words are not the delimiter: COBOL stops only at a run of two.
        final String addressLine = "1918 Eighth Avenue Suite 100";

        assertThat(addressLine)
                .as("the fixture must carry no two-space run, or it would not test this case")
                .doesNotContain(EXPECTED_ADDRESS_DELIMITER);

        final byte[] image = asciiBytes(StatementHtmlTemplates.addressWorkLine(addressLine));

        assertThat(image)
                .as("with no delimiter present the entire field must transfer")
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + addressLine
                        + EXPECTED_ADDRESS_TRAILING_SPACES + EXPECTED_PARAGRAPH_CLOSE));
    }

    @Test
    @DisplayName("the address work line right-trims a space-padded field at the padding run, "
            + "and still appends the two literal spaces")
    void addressWorkLineRightTrimsAPaddedFieldAndStillAppendsTheTwoLiteralSpaces() {
        // A COBOL address field arrives space-padded to its declared width. The first two-space
        // run is the start of that padding, so the transfer stops there. This is the ordinary
        // case for every real record.
        final String transferred = "410 Terry Ave N";
        final String paddedField = transferred + " ".repeat(35);
        final byte[] image = asciiBytes(StatementHtmlTemplates.addressWorkLine(paddedField));

        assertThat(image)
                .as("the padding must be excluded from the transfer, and the two literal spaces "
                        + "must still arrive")
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + transferred
                        + EXPECTED_ADDRESS_TRAILING_SPACES + EXPECTED_PARAGRAPH_CLOSE));

        // The closing tag's position is the proof: a trim that dropped the two literal spaces
        // would place it two bytes earlier.
        assertThat(indexOfBytes(image, EXPECTED_PARAGRAPH_CLOSE))
                .as("the closing tag must sit after the two literal spaces, not before them")
                .isEqualTo(asciiWidth(EXPECTED_PARAGRAPH_OPEN) + asciiWidth(transferred)
                        + asciiWidth(EXPECTED_ADDRESS_TRAILING_SPACES));
    }

    @Test
    @DisplayName("the address work line cuts at a two-space run in the middle of the value, "
            + "which is why this is not a right trim")
    void addressWorkLineCutsAtATwoSpaceRunInTheMiddleOfTheValue() {
        // The distinguishing case. A right trim would keep the whole value; COBOL keeps only
        // what precedes the first two-space run, wherever that run falls.
        final String addressLine = "410  Terry Ave N";
        final byte[] image = asciiBytes(StatementHtmlTemplates.addressWorkLine(addressLine));

        assertThat(image)
                .as("the transfer must stop at the first two-space run even mid-value")
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + "410"
                        + EXPECTED_ADDRESS_TRAILING_SPACES + EXPECTED_PARAGRAPH_CLOSE));

        assertLacksBytes(new String(image, StandardCharsets.US_ASCII), "Terry");
    }

    @Test
    @DisplayName("the address work line composes an empty field as the tag, two spaces and the "
            + "closing tag")
    void addressWorkLineComposesAnEmptyFieldAsTagsAroundTwoSpaces() {
        final String expectedContent = EXPECTED_PARAGRAPH_OPEN + EXPECTED_ADDRESS_TRAILING_SPACES
                + EXPECTED_PARAGRAPH_CLOSE;
        final byte[] image = asciiBytes(StatementHtmlTemplates.addressWorkLine(""));

        assertThat(image)
                .as("an empty address must still carry both tags and the two literal spaces")
                .isEqualTo(expectedRecord(expectedContent));

        assertSpacePaddedFrom(image, asciiWidth(expectedContent));
    }

    @ParameterizedTest(name = "[{index}] label [{0}]")
    @MethodSource("basicDetailsLabels")
    @DisplayName("the basic-details work line reproduces its legacy composition for each of the "
            + "three labels")
    void basicDetailsWorkLineReproducesItsLegacyCompositionForEachLabel(final String label) {
        final String value = "00000000011";
        final String expectedContent = EXPECTED_PARAGRAPH_OPEN + label + value
                + EXPECTED_PARAGRAPH_CLOSE;
        final byte[] image = asciiBytes(StatementHtmlTemplates.basicDetailsWorkLine(label, value));

        assertThat(image.length)
                .as("the basic-details line must occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(image)
                .as("the line must be tag, label, value, closing tag, then padding")
                .isEqualTo(expectedRecord(expectedContent));

        assertThat(asciiWidth(label))
                .as("all three legacy labels share one width, which is what aligns the column")
                .isEqualTo(EXPECTED_BASIC_DETAILS_LABEL_LENGTH);

        assertSpacePaddedFrom(image, asciiWidth(expectedContent));
    }

    @Test
    @DisplayName("the basic-details work line keeps a value's trailing spaces inside the element, "
            + "because the legacy delimiter is an asterisk and no value holds one")
    void basicDetailsWorkLineKeepsTrailingSpacesInsideTheElement() {
        // The asymmetry with the address line, asserted in the direction that distinguishes the
        // two regimes: here the whole padded field transfers, so the closing tag sits after the
        // padding rather than before it.
        final String paddedValue = "00000000011" + " ".repeat(5);
        final byte[] image =
                asciiBytes(StatementHtmlTemplates.basicDetailsWorkLine(EXPECTED_ACCOUNT_ID_LABEL,
                        paddedValue));

        assertThat(image)
                .as("the padded value must transfer whole, padding included")
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + EXPECTED_ACCOUNT_ID_LABEL
                        + paddedValue + EXPECTED_PARAGRAPH_CLOSE));

        assertThat(indexOfBytes(image, EXPECTED_PARAGRAPH_CLOSE))
                .as("the closing tag must follow the value's own padding, not precede it; an "
                        + "implementation that trimmed would place it five bytes earlier")
                .isEqualTo(asciiWidth(EXPECTED_PARAGRAPH_OPEN)
                        + asciiWidth(EXPECTED_ACCOUNT_ID_LABEL) + asciiWidth(paddedValue));
    }

    @Test
    @DisplayName("the basic-details work line composes an empty value as label then closing tag")
    void basicDetailsWorkLineComposesAnEmptyValueAsLabelThenClosingTag() {
        final String expectedContent = EXPECTED_PARAGRAPH_OPEN + EXPECTED_FICO_SCORE_LABEL
                + EXPECTED_PARAGRAPH_CLOSE;
        final byte[] image =
                asciiBytes(StatementHtmlTemplates.basicDetailsWorkLine(EXPECTED_FICO_SCORE_LABEL,
                        ""));

        assertThat(image)
                .as("an empty value must leave the label and both tags intact")
                .isEqualTo(expectedRecord(expectedContent));

        assertSpacePaddedFrom(image, asciiWidth(expectedContent));
    }

    @Test
    @DisplayName("the transaction work line reproduces its legacy composition: tag, value, "
            + "closing tag")
    void transactionWorkLineReproducesItsLegacyComposition() {
        final String value = "0000000000000001";
        final String expectedContent = EXPECTED_PARAGRAPH_OPEN + value + EXPECTED_PARAGRAPH_CLOSE;
        final byte[] image = asciiBytes(StatementHtmlTemplates.transactionWorkLine(value));

        assertThat(image.length)
                .as("the transaction line must occupy exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(image)
                .as("the line must be tag, value, closing tag, then padding")
                .isEqualTo(expectedRecord(expectedContent));

        assertSpacePaddedFrom(image, asciiWidth(expectedContent));
    }

    @Test
    @DisplayName("the transaction work line keeps a value's trailing spaces inside the element, "
            + "matching the asterisk-delimited legacy transfer")
    void transactionWorkLineKeepsTrailingSpacesInsideTheElement() {
        final String paddedValue = "2022-07-19" + " ".repeat(6);
        final byte[] image = asciiBytes(StatementHtmlTemplates.transactionWorkLine(paddedValue));

        assertThat(image)
                .as("the padded value must transfer whole, padding included")
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + paddedValue
                        + EXPECTED_PARAGRAPH_CLOSE));

        assertThat(indexOfBytes(image, EXPECTED_PARAGRAPH_CLOSE))
                .as("the closing tag must follow the value's own padding, not precede it")
                .isEqualTo(asciiWidth(EXPECTED_PARAGRAPH_OPEN) + asciiWidth(paddedValue));
    }

    @Test
    @DisplayName("the transaction work line serves all three legacy uses without reformatting "
            + "the identifier, the date or the amount")
    void transactionWorkLineServesAllThreeLegacyUsesWithoutReformatting() {
        // The legacy program emits this one shape three times per transaction. One builder serves
        // all three because the composition is identical; nothing about the value is interpreted,
        // so a signed amount keeps its sign and a date keeps its separators exactly as supplied.
        final List<String> legacyValues = List.of("0000000000000001", "2022-07-19", "-00000123.45");

        for (final String value : legacyValues) {
            assertThat(asciiBytes(StatementHtmlTemplates.transactionWorkLine(value)))
                    .as("the value [%s] must reach the record byte for byte", value)
                    .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + value
                            + EXPECTED_PARAGRAPH_CLOSE));
        }
    }

    @Test
    @DisplayName("the transaction work line composes an empty value as two adjacent tags")
    void transactionWorkLineComposesAnEmptyValueAsTwoAdjacentTags() {
        final String expectedContent = EXPECTED_PARAGRAPH_OPEN + EXPECTED_PARAGRAPH_CLOSE;
        final byte[] image = asciiBytes(StatementHtmlTemplates.transactionWorkLine(""));

        assertThat(image)
                .as("an empty value must leave both tags adjacent and the rest padded")
                .isEqualTo(expectedRecord(expectedContent));

        assertSpacePaddedFrom(image, asciiWidth(expectedContent));
    }

    @Test
    @DisplayName("every work line cuts over-long content at the record width rather than "
            + "rejecting it, and none ever exceeds one record")
    void everyWorkLineCutsOverLongContentAtTheRecordWidth() {
        // The legacy move into a fixed-width field truncates silently; it never raises. Each
        // builder is given a value long enough to overrun the record on its own, and the record
        // must still be exactly one hundred bytes with the overrun absent.
        final String overLong = "A".repeat(EXPECTED_RECORD_LENGTH) + "B".repeat(20);

        final String address = StatementHtmlTemplates.addressWorkLine(overLong);
        final String basicDetails =
                StatementHtmlTemplates.basicDetailsWorkLine(EXPECTED_ACCOUNT_ID_LABEL, overLong);
        final String transaction = StatementHtmlTemplates.transactionWorkLine(overLong);

        for (final String record : List.of(address, basicDetails, transaction)) {
            assertThat(asciiWidth(record))
                    .as("an over-long value must be cut to one record rather than rejected")
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
            assertLacksBytes(record, "B");
        }

        // The cut falls inside the value, so the closing tag cannot survive on any of the three.
        assertLacksBytes(address, EXPECTED_PARAGRAPH_CLOSE);
        assertLacksBytes(basicDetails, EXPECTED_PARAGRAPH_CLOSE);
        assertLacksBytes(transaction, EXPECTED_PARAGRAPH_CLOSE);
    }

    /*
     * ========================================================================================
     * Escaping of the data positions.
     *
     * This is the recorded divergence from byte-for-byte faithfulness. The legacy program moved
     * statement data into the HTML record with no encoding of any kind, because on the mainframe
     * the values could only have come from a VSAM record written by a sibling batch program. In
     * this module the same values arrive from a relational store an online maintenance screen
     * writes, so a name or address field can carry markup that reaches whoever opens the
     * statement. Escaping at the point of composition is the only place that closes it, because
     * the statement file is written once and read by an unknown viewer later.
     *
     * The divergence is the narrowest available: escaping is the identity function on the whole
     * legitimate domain of every field these builders touch, so for real data the emitted bytes
     * are unchanged and the hundred-byte gate is unaffected. It differs only for input that
     * would otherwise inject markup. The tests below assert both halves of that claim: the
     * identity half on legitimate values, and the substitution half on hostile ones. The
     * divergence is recorded in docs/decision-log.md.
     *
     * Every expectation is assembled from the hand-written reference literals declared at the
     * top of this file, never by calling the escaping method to produce its own expectation.
     * ========================================================================================
     */

    @ParameterizedTest(name = "[{index}] [{0}] becomes [{1}]")
    @MethodSource("markupCharacterReferences")
    @DisplayName("each of the five markup-significant characters is substituted before it reaches "
            + "a work line's data position")
    void eachMarkupCharacterIsSubstitutedBeforeItReachesAWorkLine(
            final String raw, final String reference) {
        assertThat(asciiBytes(StatementHtmlTemplates.transactionWorkLine(raw)))
                .as("the record must carry the reference for [%s] and not the character", raw)
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + reference
                        + EXPECTED_PARAGRAPH_CLOSE));
    }

    @ParameterizedTest(name = "[{index}] [{0}] becomes [{1}]")
    @MethodSource("markupCharacterReferences")
    @DisplayName("the escaping method substitutes each of the five characters and nothing else")
    void escapeTextSubstitutesEachOfTheFiveCharactersAndNothingElse(
            final String raw, final String reference) {
        assertThat(StatementHtmlTemplates.escapeText(raw))
                .as("the character [%s] must become its reference", raw)
                .isEqualTo(reference);

        // Surrounded by ordinary text, so the substitution is proved to be positional rather
        // than whole-value.
        assertThat(StatementHtmlTemplates.escapeText("LEFT" + raw + "RIGHT"))
                .as("the substitution must happen in place, leaving the surrounding text alone")
                .isEqualTo("LEFT" + reference + "RIGHT");
    }

    @Test
    @DisplayName("the escaping method passes a line terminator through unchanged, because it is a "
            + "substitution primitive and not a framing guard")
    void escapeTextPassesALineTerminatorThroughUnchanged() {
        // This is the property that explains why the escaping method is the one entry point on this
        // class that accepts a carriage return: its contract is to substitute five markup characters,
        // and a line terminator is not one of them. Asserting the pass-through positively - rather
        // than leaving it as an absence of rejection - is what distinguishes "correct by design" from
        // "a guard someone forgot", which are indistinguishable from the outside.
        final String terminators = "\r\n";
        final String hostile = "FORGED" + terminators + "<script>";

        assertThat(StatementHtmlTemplates.escapeText(terminators))
                .as("a bare terminator pair must pass through byte-identically")
                .isEqualTo(terminators);
        assertThat(StatementHtmlTemplates.escapeText(hostile))
                .as("the markup characters must be substituted while the terminators survive")
                .isEqualTo("FORGED" + terminators + "&lt;script&gt;")
                .contains(terminators);
    }

    @Test
    @DisplayName("no path from the escaping method to an emitted record can carry a line terminator, "
            + "because every composer downstream of it rejects one")
    void noPathFromEscapingToAnEmittedRecordCanCarryALineTerminator() {
        // The escaping method's permissiveness is only safe if nothing can turn its output into a
        // hundred-byte record without a further check. This walks the actual composition path: it
        // escapes a terminator-bearing value and then offers the escaped result to every composer,
        // including the framing-only work line, and requires each one to refuse it. Any composer added
        // later without a guard fails here rather than in production.
        final String escaped = StatementHtmlTemplates.escapeText("FORGED\r\n<script>");

        assertThat(escaped).as("the escaped value must still hold the terminator, or this test would"
                + " prove nothing").contains("\r\n");

        final List<Runnable> composers = List.of(
                () -> StatementHtmlTemplates.accountNumberLine(escaped),
                () -> StatementHtmlTemplates.customerNameLine(escaped),
                () -> StatementHtmlTemplates.addressWorkLine(escaped),
                () -> StatementHtmlTemplates.basicDetailsWorkLine(escaped, "value"),
                () -> StatementHtmlTemplates.basicDetailsWorkLine("label", escaped),
                () -> StatementHtmlTemplates.transactionWorkLine(escaped),
                () -> StatementHtmlTemplates.workLine(escaped));

        for (final Runnable composer : composers) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("every composer must refuse an escaped value that still holds a terminator")
                    .isThrownBy(composer::run)
                    .satisfies(rejected -> {
                        final String message = rejected.getMessage();
                        assertThat(message).as("a rejection must carry a message").isNotNull();
                        // The same no-echo rule the rest of the module follows (D-16 / DL-041): the
                        // diagnostic may not reproduce the value, nor the terminator inside it.
                        assertThat(message).doesNotContain("FORGED");
                        assertThat(message.indexOf('\r')).as("no raw carriage return").isEqualTo(-1);
                        assertThat(message.indexOf('\n')).as("no raw line feed").isEqualTo(-1);
                    });
        }
    }

    @Test
    @DisplayName("the escaping method leaves the entire legitimate domain untouched, which is why "
            + "the hundred-byte parity gate is unaffected for real data")
    void escapeTextLeavesTheLegitimateDomainUntouched() {
        // Every value the three work lines and the two composed lines actually carry in this
        // estate: account identifiers, signed amounts, FICO scores, dates, names and addresses.
        // Escaping must be the identity function on all of them, or the divergence would not be
        // as narrow as it is documented to be.
        final List<String> legitimateValues = List.of(
                "00000000011",
                "-00000123.45",
                "0000000000000001",
                "2022-07-19",
                "0785",
                "JOHN Q PUBLIC",
                "410 Terry Ave N",
                "Seattle WA 98109-5210",
                "O`BRIEN-SMITH, JR.",
                "Suite #100 (Bldg 2) 50% off",
                "Account ID         : ",
                "",
                " ".repeat(50));

        for (final String value : legitimateValues) {
            assertThat(StatementHtmlTemplates.escapeText(value))
                    .as("the legitimate value [%s] must pass through unchanged", value)
                    .isEqualTo(value);
        }
    }

    @Test
    @DisplayName("the escaping method substitutes the ampersand before the references it "
            + "introduces, so no reference is ever doubly escaped")
    void escapeTextSubstitutesTheAmpersandBeforeTheReferencesItIntroduces() {
        // The classic ordering defect: replacing '<' first and '&' afterwards turns "&lt;" into
        // "&amp;lt;". A single pass makes that impossible, and this asserts the result.
        assertThat(StatementHtmlTemplates.escapeText("<"))
                .as("a less-than sign must yield exactly one reference, not a doubly escaped one")
                .isEqualTo(EXPECTED_LESS_THAN_REFERENCE)
                .doesNotContain(EXPECTED_AMPERSAND_REFERENCE + "lt;");

        // An input that already looks like a reference must be escaped once, not left alone and
        // not escaped twice.
        assertThat(StatementHtmlTemplates.escapeText(EXPECTED_AMPERSAND_REFERENCE))
                .as("literal reference text in the data must have its own ampersand substituted")
                .isEqualTo(EXPECTED_AMPERSAND_REFERENCE + "amp;");

        assertThat(StatementHtmlTemplates.escapeText("&&"))
                .as("each ampersand must be substituted exactly once")
                .isEqualTo(EXPECTED_AMPERSAND_REFERENCE + EXPECTED_AMPERSAND_REFERENCE);
    }

    @Test
    @DisplayName("the escaping method returns an empty value for empty input and never shortens "
            + "a value it does not substitute")
    void escapeTextReturnsEmptyForEmptyInput() {
        assertThat(StatementHtmlTemplates.escapeText(""))
                .as("empty input must yield empty output rather than anything else")
                .isEmpty();
    }

    @Test
    @DisplayName("a script payload cannot form a tag in the transaction work line")
    void aScriptPayloadCannotFormATagInTheTransactionWorkLine() {
        final String payload = "<script>alert('x')&\"</script>";
        final String expectedData = EXPECTED_LESS_THAN_REFERENCE + "script"
                + EXPECTED_GREATER_THAN_REFERENCE + "alert(" + EXPECTED_APOSTROPHE_REFERENCE + "x"
                + EXPECTED_APOSTROPHE_REFERENCE + ")" + EXPECTED_AMPERSAND_REFERENCE
                + EXPECTED_QUOTATION_MARK_REFERENCE + EXPECTED_LESS_THAN_REFERENCE + "/script"
                + EXPECTED_GREATER_THAN_REFERENCE;
        final String record = StatementHtmlTemplates.transactionWorkLine(payload);

        assertThat(asciiBytes(record))
                .as("the payload must arrive fully substituted, inside the class's own tags")
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + expectedData
                        + EXPECTED_PARAGRAPH_CLOSE));

        assertThat(asciiWidth(record))
                .as("substitution must not disturb the record width")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        // No tag the payload asked for can exist anywhere in the record.
        assertLacksBytes(record, "<script");
        assertLacksBytes(record, "</script");
        assertLacksBytes(record, "alert('");
        assertLacksBytes(record, "\"");
    }

    @Test
    @DisplayName("a script payload cannot form a tag in the address work line, and the two "
            + "literal spaces still arrive")
    void aScriptPayloadCannotFormATagInTheAddressWorkLine() {
        final String payload = "<script>alert('x')&\"</script>";
        final String expectedData = EXPECTED_LESS_THAN_REFERENCE + "script"
                + EXPECTED_GREATER_THAN_REFERENCE + "alert(" + EXPECTED_APOSTROPHE_REFERENCE + "x"
                + EXPECTED_APOSTROPHE_REFERENCE + ")" + EXPECTED_AMPERSAND_REFERENCE
                + EXPECTED_QUOTATION_MARK_REFERENCE + EXPECTED_LESS_THAN_REFERENCE + "/script"
                + EXPECTED_GREATER_THAN_REFERENCE;
        final String record = StatementHtmlTemplates.addressWorkLine(payload);

        assertThat(asciiBytes(record))
                .as("substitution and the legacy composition must both hold at once")
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + expectedData
                        + EXPECTED_ADDRESS_TRAILING_SPACES + EXPECTED_PARAGRAPH_CLOSE));

        assertLacksBytes(record, "<script");
        assertLacksBytes(record, "</script");
    }

    @Test
    @DisplayName("a script payload cannot form a tag in the basic-details work line, and the "
            + "label is escaped as well as the value")
    void aScriptPayloadCannotFormATagInTheBasicDetailsWorkLine() {
        // The label is escaped too. Escaping is the identity function on all three legacy labels,
        // so nothing changes for real input; escaping it closes the label as a second injection
        // route rather than trusting that a caller will only ever pass a literal.
        final String hostileLabel = "<b>Label: ";
        final String hostileValue = "&<value>";
        final String expectedLabel = EXPECTED_LESS_THAN_REFERENCE + "b"
                + EXPECTED_GREATER_THAN_REFERENCE + "Label: ";
        final String expectedValue = EXPECTED_AMPERSAND_REFERENCE + EXPECTED_LESS_THAN_REFERENCE
                + "value" + EXPECTED_GREATER_THAN_REFERENCE;
        final String record =
                StatementHtmlTemplates.basicDetailsWorkLine(hostileLabel, hostileValue);

        assertThat(asciiBytes(record))
                .as("both the label and the value must be substituted")
                .isEqualTo(expectedRecord(EXPECTED_PARAGRAPH_OPEN + expectedLabel + expectedValue
                        + EXPECTED_PARAGRAPH_CLOSE));

        assertLacksBytes(record, "<b>");
        assertLacksBytes(record, "<value");
    }

    @Test
    @DisplayName("a hostile identifier cannot reach the account-number heading at all, because the "
            + "legacy field moved into that slot is a numeric display item; the heading's own "
            + "literals and the escaping behind the guard are both untouched")
    void aHostileIdentifierCannotReachTheAccountNumberHeading() {
        // Two guards stand in front of this slot and both are asserted, because either alone would be
        // weaker. The outer one is a value-domain guard: the emitting paragraph moves ACCT-ID, declared
        // PIC 9(11), into a twenty-byte alphanumeric slot [app/cbl/CBSTM03A.CBL:L529 with
        // app/cpy/CVACT01Y.cpy:L5], so the only value the legacy could ever place there is a run of
        // zoned digits. A tag delimiter in that position is not a value needing escaping, it is a value
        // that cannot have come from the record, and refusing it is stronger than rendering it safe.
        // The inner one is the substitution itself, which still applies to whatever passes the guard -
        // asserted below by the reference-free digits case and, for the free-text slots this class also
        // composes, by the neighbouring substitution tests.
        final String hostileIdentifier = "<a>&";

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> StatementHtmlTemplates.accountNumberLine(hostileIdentifier))
                .satisfies(refusal -> assertThat(refusal.getMessage())
                        .as("the refusal names the slot and the offending position and never"
                                + " reflects the hostile value back")
                        .contains("account identifier")
                        .contains("position 1")
                        .doesNotContain(hostileIdentifier));

        // What the slot does accept is composed exactly as the group declares, so the guard has not
        // replaced the composition it protects.
        final String record = StatementHtmlTemplates.accountNumberLine("00000000011");
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("an accepted identifier must still produce exactly one record")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, 0, EXPECTED_ACCOUNT_PREFIX_LENGTH))
                .as("the heading's own literal is markup and must not be substituted")
                .isEqualTo(asciiBytes(EXPECTED_ACCOUNT_PREFIX));

        assertThat(segment(image, EXPECTED_ACCOUNT_PREFIX_LENGTH,
                        EXPECTED_ACCOUNT_PREFIX_LENGTH + EXPECTED_ACCOUNT_FIELD_LENGTH))
                .as("the identifier field holds the accepted value, padded to twenty bytes")
                .isEqualTo(expectedField("00000000011", EXPECTED_ACCOUNT_FIELD_LENGTH));

        assertThat(segment(image, EXPECTED_ACCOUNT_DECLARED_LENGTH - EXPECTED_ACCOUNT_SUFFIX_LENGTH,
                        EXPECTED_ACCOUNT_DECLARED_LENGTH))
                .as("the closing tag must keep its position")
                .isEqualTo(asciiBytes(EXPECTED_ACCOUNT_SUFFIX));

        final String dataPosition = new String(
                segment(image, EXPECTED_ACCOUNT_PREFIX_LENGTH,
                        EXPECTED_ACCOUNT_PREFIX_LENGTH + EXPECTED_ACCOUNT_FIELD_LENGTH),
                StandardCharsets.US_ASCII);
        assertThat(dataPosition)
                .as("no tag delimiter may survive in the identifier field")
                .doesNotContain("<")
                .doesNotContain(">");

        // Every character the guard rejects is rejected, not merely the two tag delimiters: an
        // apostrophe, a quotation mark, an ampersand and a letter are each refused too, so the guard
        // is a positive allowlist of digits and spaces rather than a denylist of markup.
        for (final String outsideTheDomain : List.of("'", "\"", "&", "A", "0000000001A")) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the identifier [%s] is outside the numeric domain", outsideTheDomain)
                    .isThrownBy(() -> StatementHtmlTemplates.accountNumberLine(outsideTheDomain))
                    .withMessageContaining("account identifier");
        }
    }

    @Test
    @DisplayName("a hostile customer name cannot close the quoted style attribute the "
            + "surrounding literal opens")
    void aHostileCustomerNameCannotCloseTheQuotedStyleAttribute() {
        // The single most important substitution site in the class. The name is free text an
        // outside party influences, and the literal that precedes it opens a tag carrying a
        // quoted attribute, so an unsubstituted quotation mark could reposition the value into
        // that attribute.
        final String hostileName = "JOHN \"Q\" & <B>";
        final String expectedFieldText = "JOHN " + EXPECTED_QUOTATION_MARK_REFERENCE + "Q"
                + EXPECTED_QUOTATION_MARK_REFERENCE + " " + EXPECTED_AMPERSAND_REFERENCE + " "
                + EXPECTED_LESS_THAN_REFERENCE + "B" + EXPECTED_GREATER_THAN_REFERENCE;
        final String record = StatementHtmlTemplates.customerNameLine(hostileName);
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("substitution must not disturb the record width")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, 0, EXPECTED_NAME_PREFIX_LENGTH))
                .as("the line's own literal, quoted attribute included, must not be substituted")
                .isEqualTo(asciiBytes(EXPECTED_NAME_PREFIX));

        assertThat(image)
                .as("the emitted record is the escaped name, right trimmed, followed by the two "
                        + "literal spaces and the closing literal")
                .isEqualTo(expectedRecord(EXPECTED_NAME_PREFIX + expectedFieldText
                        + EXPECTED_NAME_DELIMITER + EXPECTED_NAME_CLOSING_TAG));

        assertThat(segment(image, EXPECTED_NAME_PREFIX_LENGTH,
                        EXPECTED_NAME_PREFIX_LENGTH + asciiWidth(expectedFieldText)))
                .as("the name position must hold the substituted form")
                .isEqualTo(asciiBytes(expectedFieldText));

        final String dataPosition = new String(
                segment(image, EXPECTED_NAME_PREFIX_LENGTH,
                        EXPECTED_NAME_PREFIX_LENGTH + asciiWidth(expectedFieldText)),
                StandardCharsets.US_ASCII);
        assertThat(dataPosition)
                .as("no quotation mark and no tag delimiter may survive in the name position")
                .doesNotContain("\"")
                .doesNotContain("<")
                .doesNotContain(">");

        // Substitution must not disturb the trim or the closing literal that follows it: the record's
        // own closing tag is the only tag delimiter pair in it beyond the opening literal's.
        assertSpacePaddedFrom(image, EXPECTED_NAME_PREFIX_LENGTH + asciiWidth(expectedFieldText)
                + EXPECTED_NAME_DELIMITER.length() + EXPECTED_NAME_CLOSING_TAG.length());
        assertCarriesBytes(record, EXPECTED_NAME_CLOSING_TAG);
    }

    /*
     * ========================================================================================
     * Entity-safe truncation.
     *
     * Cutting a fixed-width field at a byte boundary is faithful and must not change. But a cut
     * that lands inside a character reference would leave a fragment such as "&am", which a
     * viewer may resynchronise against the markup that follows, turning a truncation into a
     * rendering defect. An unterminated reference at the cut is therefore blanked with the ASCII
     * space and the field keeps its exact width. A reference that completes on or before the cut
     * is left alone, which is why this costs nothing for the thirty-four fixed templates or for
     * any real value.
     *
     * The two arithmetic cases below are built by hand so the cut position is exact:
     *   "<p>" is 3 bytes, so a value's escaped text begins at index 3.
     *   With 95 leading bytes the reference occupies 98..102 and is cut  -> blanked.
     *   With 92 leading bytes it occupies 95..99 and completes at the cut -> kept.
     * ========================================================================================
     */

    @Test
    @DisplayName("a character reference cut by the record boundary is blanked rather than emitted "
            + "as a fragment")
    void aCharacterReferenceCutByTheRecordBoundaryIsBlanked() {
        // 3 bytes of tag + 95 bytes of filler puts the ampersand at index 98, so "&amp;" would
        // run to index 102 and is cut at 100.
        final int fillerWidth = 95;
        final String value = "A".repeat(fillerWidth) + "&" + "B".repeat(20);
        final String record = StatementHtmlTemplates.transactionWorkLine(value);
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("the record must keep its exact width after the fragment is blanked")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(segment(image, 0, asciiWidth(EXPECTED_PARAGRAPH_OPEN)))
                .as("the opening tag must be unaffected")
                .isEqualTo(asciiBytes(EXPECTED_PARAGRAPH_OPEN));

        assertThat(segment(image, asciiWidth(EXPECTED_PARAGRAPH_OPEN),
                        asciiWidth(EXPECTED_PARAGRAPH_OPEN) + fillerWidth))
                .as("every byte before the cut reference must survive")
                .isEqualTo(asciiBytes("A".repeat(fillerWidth)));

        // The two bytes the fragment would have occupied are now ASCII spaces.
        assertSpacePaddedFrom(image, asciiWidth(EXPECTED_PARAGRAPH_OPEN) + fillerWidth);

        assertLacksBytes(record, "&");
        assertLacksBytes(record, "&am");
        assertLacksBytes(record, "B");
    }

    @Test
    @DisplayName("a character reference that completes exactly at the record boundary is kept, "
            + "so the blanking never removes a whole reference")
    void aCharacterReferenceThatCompletesAtTheRecordBoundaryIsKept() {
        // The control for the previous test. 3 bytes of tag + 92 bytes of filler puts the
        // ampersand at index 95, so "&amp;" ends with its semicolon at index 99 -- the last byte
        // of the record. Nothing may be removed.
        final int fillerWidth = 92;
        final String value = "A".repeat(fillerWidth) + "&" + "B".repeat(20);
        final String record = StatementHtmlTemplates.transactionWorkLine(value);
        final byte[] image = asciiBytes(record);

        assertThat(image.length)
                .as("the record must be exactly one record wide")
                .isEqualTo(EXPECTED_RECORD_LENGTH);

        assertThat(image)
                .as("the complete reference must survive at the very end of the record")
                .isEqualTo(asciiBytes(EXPECTED_PARAGRAPH_OPEN + "A".repeat(fillerWidth)
                        + EXPECTED_AMPERSAND_REFERENCE));

        assertCarriesBytes(record, EXPECTED_AMPERSAND_REFERENCE);
        assertLacksBytes(record, "B");
    }

    @Test
    @DisplayName("a truncation with no reference at the cut is a plain byte cut, unchanged from "
            + "the legacy fixed-width move")
    void aTruncationWithNoReferenceAtTheCutIsAPlainByteCut() {
        // The ordinary case, and the proof that the blanking is conditional: nothing is removed
        // when the surviving bytes hold no unterminated reference.
        final String value = "A".repeat(EXPECTED_RECORD_LENGTH);
        final byte[] image = asciiBytes(StatementHtmlTemplates.transactionWorkLine(value));

        assertThat(image)
                .as("the cut must take exactly the first hundred bytes and blank nothing")
                .isEqualTo(asciiBytes(EXPECTED_PARAGRAPH_OPEN
                        + "A".repeat(EXPECTED_RECORD_LENGTH - asciiWidth(EXPECTED_PARAGRAPH_OPEN))));
    }

    @Test
    @DisplayName("every work line carries no line terminator and no tab byte, whether its value "
            + "is present, empty or hostile")
    void everyWorkLineCarriesNoLineTerminatorOrTabByte() {
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.addressWorkLine("410 Terry Ave N")),
                "the address work line");
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.addressWorkLine("")),
                "an empty address work line");
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.basicDetailsWorkLine(EXPECTED_ACCOUNT_ID_LABEL,
                        "00000000011")),
                "the basic-details work line");
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.basicDetailsWorkLine(EXPECTED_FICO_SCORE_LABEL,
                        "")),
                "an empty basic-details work line");
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.transactionWorkLine("-00000123.45")),
                "the transaction work line");
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.transactionWorkLine("")),
                "an empty transaction work line");
        assertFreeOfLineTerminatorAndTabBytes(
                asciiBytes(StatementHtmlTemplates.transactionWorkLine("<script>&\"'</script>")),
                "a transaction work line carrying a substituted payload");
    }

    @Test
    @DisplayName("every builder in the class returns exactly one hundred encoded bytes for every "
            + "value it accepts, which is the parity gate the whole class exists to hold")
    void everyBuilderReturnsExactlyOneRecordForEveryValueItAccepts() {
        // One sweep across every builder and every shape of value: legitimate, empty,
        // space-padded, delimiter-bearing, hostile and over-long. The hundred-byte width is the
        // single invariant that the byte-parity gate checks, so it is asserted for all of them
        // in one place rather than only incidentally inside the shape tests.
        // The free-text slots accept every shape below, because their legacy fields are alphanumeric.
        // The account-number heading is different in kind: the field moved into it is a numeric display
        // item, so its domain is digits and spaces and anything else is refused rather than rendered.
        // The sweep is therefore split along exactly that line, which is what "every value it accepts"
        // means, and the refused shapes are asserted to be refused rather than quietly dropped.
        final List<String> freeTextValues = List.of(
                "00000000011",
                "",
                " ".repeat(60),
                "410  Terry Ave N",
                "<script>alert('x')&\"</script>",
                "A".repeat(EXPECTED_RECORD_LENGTH + 40),
                "A".repeat(95) + "&" + "B".repeat(20));
        final List<String> numericSlotValues = List.of(
                "00000000011",
                "",
                " ".repeat(60),
                "0".repeat(EXPECTED_RECORD_LENGTH + 40),
                "1 2 3");

        for (final String value : freeTextValues) {
            assertThat(asciiWidth(StatementHtmlTemplates.addressWorkLine(value)))
                    .as("addressWorkLine must be one record wide for [%s]", value)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(asciiWidth(
                    StatementHtmlTemplates.basicDetailsWorkLine(EXPECTED_ACCOUNT_ID_LABEL, value)))
                    .as("basicDetailsWorkLine must be one record wide for [%s]", value)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(asciiWidth(StatementHtmlTemplates.transactionWorkLine(value)))
                    .as("transactionWorkLine must be one record wide for [%s]", value)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
            assertThat(asciiWidth(StatementHtmlTemplates.customerNameLine(value)))
                    .as("customerNameLine must be one record wide for [%s]", value)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
        }

        for (final String value : numericSlotValues) {
            assertThat(asciiWidth(StatementHtmlTemplates.accountNumberLine(value)))
                    .as("accountNumberLine must be one record wide for [%s]", value)
                    .isEqualTo(EXPECTED_RECORD_LENGTH);
        }

        for (final String value : freeTextValues) {
            if (numericSlotValues.contains(value)) {
                continue;
            }
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("accountNumberLine must refuse [%s] rather than render it", value)
                    .isThrownBy(() -> StatementHtmlTemplates.accountNumberLine(value))
                    .withMessageContaining("account identifier");
        }
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
    @DisplayName("the address work line refuses a null field")
    void addressWorkLineRefusesANullField() {
        assertThatNullPointerException()
                .as("a null address must be refused rather than moved in as text")
                .isThrownBy(() -> StatementHtmlTemplates.addressWorkLine(null));
    }

    @Test
    @DisplayName("the basic-details work line refuses a null label and a null value separately")
    void basicDetailsWorkLineRefusesANullLabelAndANullValueSeparately() {
        assertThatNullPointerException()
                .as("a null label must be refused")
                .isThrownBy(() -> StatementHtmlTemplates.basicDetailsWorkLine(null, "00000000011"));

        assertThatNullPointerException()
                .as("a null value must be refused")
                .isThrownBy(() ->
                        StatementHtmlTemplates.basicDetailsWorkLine(EXPECTED_ACCOUNT_ID_LABEL, null));
    }

    @Test
    @DisplayName("the transaction work line refuses a null value")
    void transactionWorkLineRefusesANullValue() {
        assertThatNullPointerException()
                .as("a null value must be refused rather than moved in as text")
                .isThrownBy(() -> StatementHtmlTemplates.transactionWorkLine(null));
    }

    @Test
    @DisplayName("the escaping method refuses null text")
    void escapeTextRefusesNullText() {
        assertThatNullPointerException()
                .as("null text must be refused rather than substituted into the literal null")
                .isThrownBy(() -> StatementHtmlTemplates.escapeText(null));
    }

}
