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
import java.util.Objects;

/**
 * Fixed-width HTML line templates for the account-statement HTML output stream.
 *
 * <p>This class is the single holder of the thirty-four literal HTML lines that the legacy
 * batch statement generator wrote to its HTML output file, together with the two composed
 * sub-group lines and the width rule for the three free-form work lines that share the same
 * record. It holds fixed-width layout knowledge and nothing else: it performs no I/O, makes
 * no emission-sequencing decision, formats no number, and touches no persistent state.</p>
 *
 * <h2>The record</h2>
 *
 * <p>The HTML output file's record is declared as {@code 01 FD-HTMLFILE-REC PIC X(100).}
 * &mdash; a fixed one-hundred-byte record [app/cbl/CBSTM03A.CBL:L47]. The template group
 * {@code 01 HTML-LINES.} opens at [app/cbl/CBSTM03A.CBL:L148] with a
 * {@code 05 HTML-FIXED-LN PIC X(100).} elementary item, and <strong>exactly thirty-four</strong>
 * level-88 condition-name constants are declared against it at
 * [app/cbl/CBSTM03A.CBL:L150-L211]. That count of thirty-four is a mechanically verified
 * figure, not an estimate: the declarations were enumerated programmatically over that line
 * range and the resulting name list is reproduced verbatim in the inventory below. Three
 * subordinate groups follow at [app/cbl/CBSTM03A.CBL:L212-L223].</p>
 *
 * <p>Every constant published here is already padded on the right with ASCII spaces to
 * exactly {@value #HTML_RECORD_LENGTH} encoded bytes, so a caller may hand any of them
 * straight to a writer without further measurement. None of the underlying literals exceeds
 * the record width &mdash; the widest is eighty-five bytes &mdash; so no template is ever
 * truncated.</p>
 *
 * <h2>No line terminator</h2>
 *
 * <p>The hundred-byte image carries <strong>no line terminator of any kind</strong>. The
 * COBOL source file itself uses CRLF endings, but that is a property of the source file and
 * not of the emitted records. Each emitted record is one hundred data bytes; whether and how
 * records are separated on the resulting artefact is the writer's decision and lives in the
 * batch layer. No carriage return, line feed, tab or platform line separator appears inside
 * any constant published here.</p>
 *
 * <p>One of the templates declares {@code <meta charset="utf-8">}. That declaration is
 * <em>content</em>, not configuration. Every literal in this class is pure ASCII, so the
 * declared character set changes neither the encoding nor any width, and every width in this
 * class is measured in US-ASCII encoded bytes regardless.</p>
 *
 * <h2>The thirty-four fixed templates, in exact source order</h2>
 *
 * <p>The order below is the declaration order in the legacy source and is contractual. The
 * ordered accessor {@link #fixedTemplates()} returns the templates in precisely this
 * sequence.</p>
 *
 * <pre>{@code
 *  #  legacy name   source lines   content
 * --  ------------  -------------  --------------------------------------------------------------------------------------
 *  1  HTML-L01      L150           <!DOCTYPE html>
 *  2  HTML-L02      L151           <html lang="en">
 *  3  HTML-L03      L152           <head>
 *  4  HTML-L04      L153           <meta charset="utf-8">
 *  5  HTML-L05      L154           <title>HTML Table Layout</title>
 *  6  HTML-L06      L155           </head>
 *  7  HTML-L07      L156           <body style="margin:0px;">
 *  8  HTML-L08      L157-L158      <table  align="center" frame="box" style="width:70%; font:12px Segoe UI,sans-serif;">
 *  9  HTML-LTRS     L159           <tr>
 * 10  HTML-LTRE     L160           </tr>
 * 11  HTML-LTDS     L161           <td>
 * 12  HTML-LTDE     L162           </td>
 * 13  HTML-L10      L163-L164      <td colspan="3" style="padding:0px 5px;background-color:#1d1d96b3;">
 * 14  HTML-L15      L165-L166      <td colspan="3" style="padding:0px 5px;background-color:#FFAF33;">
 * 15  HTML-L16      L167-L168      <p style="font-size:16px">Bank of XYZ</p>
 * 16  HTML-L17      L169-L170      <p>410 Terry Ave N</p>
 * 17  HTML-L18      L171-L172      <p>Seattle WA 99999</p>
 * 18  HTML-L22-35   L173-L175      <td colspan="3" style="padding:0px 5px;background-color:#f2f2f2;">
 * 19  HTML-L30-42   L176-L178      <td colspan="3" style="padding:0px 5px;background-color:#33FFD1; text-align:center;">
 * 20  HTML-L31      L179-L180      <p style="font-size:16px">Basic Details</p>
 * 21  HTML-L43      L181-L182      <p style="font-size:16px">Transaction Summary</p>
 * 22  HTML-L47      L183-L185      <td style="width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;">
 * 23  HTML-L48      L186-L187      <p style="font-size:16px">Tran ID</p>
 * 24  HTML-L50      L188-L190      <td style="width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;">
 * 25  HTML-L51      L191-L192      <p style="font-size:16px">Tran Details</p>
 * 26  HTML-L53      L193-L195      <td style="width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;">
 * 27  HTML-L54      L196-L197      <p style="font-size:16px">Amount</p>
 * 28  HTML-L58      L198-L200      <td style="width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;">
 * 29  HTML-L61      L201-L203      <td style="width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;">
 * 30  HTML-L64      L204-L206      <td style="width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;">
 * 31  HTML-L75      L207-L208      <h3>End of Statement</h3>
 * 32  HTML-L78      L209           </table>
 * 33  HTML-L79      L210           </body>
 * 34  HTML-L80      L211           </html>
 * }</pre>
 *
 * <h2>Continuation reassembly</h2>
 *
 * <p>Several of the literals above are declared across two source lines using COBOL literal
 * continuation, with a hyphen in the indicator column of the second line. Counting the
 * continuation indicators over [app/cbl/CBSTM03A.CBL:L150-L211] gives <strong>eleven</strong>
 * continued literals &mdash; templates 8, 13, 14, 18, 19, 22, 24, 26, 28, 29 and 30 &mdash;
 * of which <strong>seven</strong> split <em>mid-token</em>:</p>
 *
 * <ul>
 *   <li>template 8 splits {@code styl} / {@code e=}, which rejoins as {@code style=};</li>
 *   <li>templates 22, 24, 26, 28, 29 and 30 split {@code background-} / {@code color:},
 *       which rejoins as {@code background-color:}.</li>
 * </ul>
 *
 * <p>The remaining four continued literals &mdash; templates 13, 14, 18 and 19 &mdash; break
 * at a clean token boundary immediately after {@code padding:0px 5px;}. Reassembly
 * <strong>adds no space at the join and neither inserts nor removes a hyphen</strong>. Every
 * finished literal in this class was diffed byte for byte against the reassembled source
 * literal, and the join integrity was checked explicitly: no {@code styl e=}, no
 * {@code style =} and no {@code background- color:} occurs anywhere.</p>
 *
 * <h2>Style-attribute spacing is inconsistent between the two table-cell families</h2>
 *
 * <p>The inconsistency is contractual and <strong>neither family is normalised toward the
 * other</strong>:</p>
 *
 * <ul>
 *   <li>The {@code colspan="3"} family &mdash; templates 13, 14, 18 and 19 &mdash; has
 *       <strong>no space</strong> between {@code padding:0px 5px;} and
 *       {@code background-color:}.</li>
 *   <li>The explicit-{@code width} family &mdash; templates 22, 24, 26, 28, 29 and 30 &mdash;
 *       has <strong>one space</strong> after {@code width:NN%;}, one after
 *       {@code padding:0px 5px;} and one after the colour value.</li>
 * </ul>
 *
 * <p>No whitespace is normalised anywhere in this class, in either direction.</p>
 *
 * <h2>Colour and width values are contractual bytes, not design choices</h2>
 *
 * <p>No design system, component library, styling framework or design-token set is in scope
 * anywhere in this migration, and no design asset exists for it. The colour and width
 * literals below are legacy bytes reproduced for output parity. They are not replaced by
 * tokens, CSS custom properties, a stylesheet or a theme, they are not canonicalised, not
 * shortened, not expanded, and their case is preserved character for character.</p>
 *
 * <ul>
 *   <li>Colours: {@code #1d1d96b3} (template 13), {@code #FFAF33} (14), {@code #f2f2f2}
 *       (18, 28, 29, 30), {@code #33FFD1} (19) and {@code #33FF5E} (22, 24, 26).</li>
 *   <li>{@code #1d1d96b3} is an <strong>eight-digit</strong> hex value carrying an alpha
 *       channel; every other colour is six-digit.</li>
 *   <li>The case is <strong>mixed and must stay mixed</strong>: {@code #1d1d96b3} and
 *       {@code #f2f2f2} are lower case, while {@code #FFAF33}, {@code #33FFD1} and
 *       {@code #33FF5E} are upper case.</li>
 *   <li>Widths: {@code 70%} (template 8), {@code 25%} (22, 28), {@code 55%} (24, 29) and
 *       {@code 20%} (26, 30).</li>
 * </ul>
 *
 * <h2>Anomaly one: the double space inside the table tag</h2>
 *
 * <p>Template 8 reads {@code <table} followed by <strong>two</strong> space characters and
 * then {@code align}. This was verified by a raw byte read of the source line with control
 * characters exposed &mdash; the bytes are {@code 3C 74 61 62 6C 65 20 20 61 6C 69 67 6E}
 * &mdash; so it is real source content and not an artefact of formatting or of the
 * continuation reassembly. The double space is <strong>emitted</strong>. It is not collapsed,
 * not normalised and not treated as a typographical error to correct: byte-identical output
 * is the requirement, and the parity gate compares bytes.
 * See {@link #HTML_L08} [app/cbl/CBSTM03A.CBL:L157-L158].</p>
 *
 * <h2>Anomaly two: the unclosed paragraph tag on the customer-name line</h2>
 *
 * <p>Two subordinate groups compose a line from a literal plus a substituted value, and they
 * differ in a way that matters:</p>
 *
 * <pre>{@code
 * group      source       composition                                                  declared  closes?
 * ---------  -----------  -----------------------------------------------------------  --------  -------
 * HTML-L11   L212-L216    X(34) "<h3>Statement for Account Number: "  (trailing space)     59     YES
 *                       + L11-ACCT X(20)
 *                       + X(05) "</h3>"
 * HTML-L23   L217-L220    X(26) "<p style="font-size:16px">"                              76     NO
 *                       + L23-NAME X(50)
 * }</pre>
 *
 * <p>{@code HTML-L23} has <strong>no third component</strong>: the group simply ends after
 * the fifty-byte name field, so there is no closing {@code </p>}. Its neighbour
 * {@code HTML-L11} <em>does</em> close, with a five-byte {@code </h3>}, which proves the
 * omission is specific to the customer-name line rather than a general pattern in the
 * program. {@link #customerNameLine(String)} therefore emits the line
 * <strong>unclosed</strong>; no {@code </p>} is appended. The result is technically invalid
 * markup, and that invalid markup is the required output
 * [app/cbl/CBSTM03A.CBL:L217-L220].</p>
 *
 * <p>Both composed groups are shorter than the record &mdash; fifty-nine and seventy-six
 * declared bytes respectively &mdash; so both are space-padded on the right to exactly
 * {@value #HTML_RECORD_LENGTH} bytes when written, exactly as moving a short group into a
 * hundred-byte alphanumeric record would do.</p>
 *
 * <h2>The three free-form work lines</h2>
 *
 * <p>Three further fields share the same hundred-byte record and are declared with no
 * internal structure at [app/cbl/CBSTM03A.CBL:L221-L223]: an address line, a basic-details
 * line and a transaction line, each {@code PIC X(100)}. Their <em>content</em> is composed by
 * the statement-generation service at run time and is deliberately not modelled here; their
 * <em>width</em> is this class's business, which is why {@link #workLine(String)} exists and
 * why {@link #ADDRESS_WORK_LINE_LENGTH}, {@link #BASIC_DETAILS_WORK_LINE_LENGTH} and
 * {@link #TRANSACTION_WORK_LINE_LENGTH} are named. Keeping the hundred-byte figure here and
 * out of the service layer is what stops fixed-width layout knowledge from leaking upward.</p>
 *
 * <h2>Move semantics: truncate and pad, in encoded bytes</h2>
 *
 * <p>Every substitution in this class reproduces the semantics of moving a value into a
 * fixed-width alphanumeric field. A value shorter than its field is <strong>padded on the
 * right with ASCII spaces</strong>; a value longer than its field is
 * <strong>truncated to the field width</strong>. Padding is always the ASCII space character
 * &mdash; never a zero, never a NUL, never a tab.</p>
 *
 * <p>Widths are measured and applied in <strong>US-ASCII encoded bytes</strong>, never in
 * {@code String} character counts, and {@link java.nio.charset.StandardCharsets#US_ASCII} is
 * named explicitly at every such point so that no result can depend on a platform default
 * charset. The distinction is not academic: a supplementary code point occupies two
 * {@code char} values but encodes to a single replacement byte, so a character-based
 * measurement would silently produce a record of the wrong width.</p>
 *
 * <h2>No escaping, and why</h2>
 *
 * <p>Substituted values &mdash; an account identifier and a customer name &mdash; are placed
 * into their fields <strong>raw</strong>, exactly as the legacy move did. This class applies
 * no HTML escaping, no sanitising, no entity encoding, no attribute-quoting normalisation, no
 * tag balancing, no pretty-printing and no minifying. That is deliberate parity, and it is
 * safe in context because the artefact produced here is a fixed-width batch file, not a
 * served web response, so no output-encoding requirement attaches to it at this layer. Were
 * the artefact ever served over HTTP, escaping would belong at that serving boundary and not
 * in this class, because escaping here would change the bytes the parity gate compares.</p>
 *
 * <h2>No templating engine</h2>
 *
 * <p>A templating engine is forbidden for this output, and so is any general-purpose
 * format-string abstraction that could reorder or re-space content. Byte-identical output
 * requires the same literals, in the same order, at the same width; an engine introduces
 * whitespace and ordering variability that the byte-parity comparison fails immediately.
 * Every template here is therefore a plain string literal, padded once at class
 * initialisation.</p>
 *
 * <h2>Related context, implemented elsewhere</h2>
 *
 * <ul>
 *   <li>The statement <em>text</em> stream is a different record width &mdash;
 *       {@code 01 FD-STMTFILE-REC PIC X(80).} [app/cbl/CBSTM03A.CBL:L45] &mdash; and its
 *       eighty-byte line templates live in a separate class. The two are kept strictly
 *       apart: no hundred-byte template may leak into the eighty-byte stream, and no
 *       eighty-byte constant appears here.</li>
 *   <li>The generator's control flow is a hand-rolled dispatcher driven by a DD-name work
 *       field with backward jumps back into the dispatcher; it becomes an explicit state
 *       enumeration driven by a loop over a switch in the statement-generation service.
 *       Which template is emitted, when, and how many times is that service's decision. This
 *       class holds the templates and their order of <em>declaration</em>, never their order
 *       of emission.</li>
 *   <li>The statement job declares the same HTML data definition at {@code LRECL=80} in one
 *       step and {@code LRECL=100} in the immediately following step that runs the generator
 *       [app/jcl/CREASTMT.JCL]. The conflict is resolved to <strong>100 for the HTML
 *       stream</strong>, which agrees with {@code 01 FD-HTMLFILE-REC PIC X(100).}
 *       [app/cbl/CBSTM03A.CBL:L47], and to 80 for the text stream, which agrees with
 *       {@code 01 FD-STMTFILE-REC PIC X(80).} [app/cbl/CBSTM03A.CBL:L45]. That resolution is
 *       what confirms one hundred is the correct width here.</li>
 *   <li>The same job reprojects its sorted input with an output-record specification that
 *       yields a 328-of-350-byte projection &mdash; three hundred and twenty-eight bytes of
 *       the three hundred and fifty in the record are retained &mdash; truncating a
 *       transaction processing timestamp by two bytes [app/jcl/CREASTMT.JCL]. That truncation
 *       is handled in the batch layer and <strong>never here</strong>.</li>
 * </ul>
 *
 * <h2>Translation decisions recorded for this class</h2>
 *
 * <ol>
 *   <li><strong>Anomaly &mdash; the double space inside the table tag.</strong> Template 8
 *       carries two spaces between {@code <table} and {@code align}, verified by a raw byte
 *       read. Reproduced exactly; not collapsed, not normalised, not corrected.</li>
 *   <li><strong>Anomaly &mdash; the unclosed paragraph tag on the customer-name line.</strong>
 *       {@code HTML-L23} is 26 + 50 = 76 declared bytes with no closing {@code </p>}, while
 *       the neighbouring {@code HTML-L11} is 34 + 20 + 5 = 59 declared bytes and does close
 *       with {@code </h3>}. The omission is specific, the output is technically invalid HTML,
 *       and it is reproduced deliberately.</li>
 *   <li><strong>Exactly thirty-four fixed templates, emitted in source order at one hundred
 *       bytes each.</strong> The count and the order are contractual; the count was verified
 *       mechanically over [app/cbl/CBSTM03A.CBL:L150-L211].</li>
 *   <li><strong>Colour and width literals are legacy bytes, not design tokens.</strong> No
 *       design system, component library or design asset is in scope; mixed hex case is
 *       preserved, {@code #1d1d96b3} retains its eight-digit alpha form, and nothing is
 *       canonicalised.</li>
 *   <li><strong>Style-attribute spacing is inconsistent between the two table-cell
 *       families</strong> and neither is normalised toward the other.</li>
 *   <li><strong>No HTML escaping, sanitising or entity encoding is applied</strong>, matching
 *       the legacy move; the artefact is a batch file rather than a served response, and
 *       escaping &mdash; if ever needed &mdash; belongs at a serving boundary outside this
 *       module.</li>
 *   <li><strong>A templating engine is forbidden</strong>; byte-identical output cannot
 *       survive an engine's whitespace and ordering variability.</li>
 *   <li><strong>The hundred-byte image carries no line terminator</strong> even though the
 *       COBOL source file has CRLF endings; record separation is the writer's concern in the
 *       batch layer. The {@code <meta charset="utf-8">} declaration is content, and every
 *       literal is ASCII.</li>
 *   <li><strong>The statement job declares the same HTML data definition at {@code LRECL=80}
 *       and {@code LRECL=100} in consecutive steps</strong> [app/jcl/CREASTMT.JCL]; resolved
 *       to 100 for the HTML stream and 80 for the text stream, matching the two record
 *       declarations.</li>
 *   <li><strong>The continuation counts stated here are the measured ones.</strong> Eleven
 *       literals use continuation and seven of those splits fall mid-token. Earlier prose
 *       described nine and three; the enumeration over
 *       [app/cbl/CBSTM03A.CBL:L150-L211] gives eleven and seven, and a byte-parity artefact
 *       must document the measured figure.</li>
 * </ol>
 *
 * <h2>Provenance</h2>
 *
 * <p>Derived by citation, never by transcription, from the legacy estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is copied into
 * this file; the HTML literals themselves are reproduced because they are the external
 * output contract this class exists to preserve.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is immutable and stateless. It declares only static members, exposes no
 * mutable array or collection, and every method is a pure function of its arguments, so all
 * members are safe for unsynchronised concurrent use.</p>
 */
public final class StatementHtmlTemplates {

    /*
     * ------------------------------------------------------------------------------------
     * Record and component widths.
     *
     * These are factual layout evidence read from the legacy record declarations - record
     * widths, byte offsets and field lengths - and not tuning or capacity figures.
     * ------------------------------------------------------------------------------------
     */

    /**
     * Width in encoded bytes of one HTML output record, from
     * {@code 01 FD-HTMLFILE-REC PIC X(100).} [app/cbl/CBSTM03A.CBL:L47].
     */
    public static final int HTML_RECORD_LENGTH = 100;

    /**
     * Number of fixed HTML line templates declared as level-88 constants at
     * [app/cbl/CBSTM03A.CBL:L150-L211]. Verified mechanically over that line range.
     */
    public static final int FIXED_TEMPLATE_COUNT = 34;

    /**
     * Width of the leading literal of the account-number line {@code HTML-L11}, which is
     * {@code <h3>Statement for Account Number: } including its trailing space
     * [app/cbl/CBSTM03A.CBL:L213-L214].
     */
    public static final int ACCOUNT_LINE_PREFIX_LENGTH = 34;

    /**
     * Width of the substituted account-identifier field {@code L11-ACCT} of the
     * account-number line [app/cbl/CBSTM03A.CBL:L215].
     */
    public static final int ACCOUNT_LINE_ACCOUNT_LENGTH = 20;

    /**
     * Width of the trailing literal of the account-number line, which is the closing
     * {@code </h3>} [app/cbl/CBSTM03A.CBL:L216].
     */
    public static final int ACCOUNT_LINE_SUFFIX_LENGTH = 5;

    /**
     * Declared width of the whole account-number group {@code HTML-L11}, being
     * {@value #ACCOUNT_LINE_PREFIX_LENGTH} + {@value #ACCOUNT_LINE_ACCOUNT_LENGTH} +
     * {@value #ACCOUNT_LINE_SUFFIX_LENGTH}. The group is space-padded to
     * {@value #HTML_RECORD_LENGTH} bytes when written
     * [app/cbl/CBSTM03A.CBL:L212-L216].
     */
    public static final int ACCOUNT_LINE_DECLARED_LENGTH = 59;

    /**
     * Width of the leading literal of the customer-name line {@code HTML-L23}, which is
     * {@code <p style="font-size:16px">} [app/cbl/CBSTM03A.CBL:L218-L219].
     */
    public static final int NAME_LINE_PREFIX_LENGTH = 26;

    /**
     * Width of the substituted customer-name field {@code L23-NAME} of the customer-name
     * line [app/cbl/CBSTM03A.CBL:L220].
     */
    public static final int NAME_LINE_NAME_LENGTH = 50;

    /**
     * Declared width of the whole customer-name group {@code HTML-L23}, being
     * {@value #NAME_LINE_PREFIX_LENGTH} + {@value #NAME_LINE_NAME_LENGTH}. There is
     * deliberately no third component and therefore no closing tag; the group is
     * space-padded to {@value #HTML_RECORD_LENGTH} bytes when written
     * [app/cbl/CBSTM03A.CBL:L217-L220].
     */
    public static final int NAME_LINE_DECLARED_LENGTH = 76;

    /**
     * Width of the free-form address work line, from {@code 05 HTML-ADDR-LN PIC X(100).}
     * [app/cbl/CBSTM03A.CBL:L221]. Its content is composed in the service layer and fitted
     * to width by {@link #workLine(String)}.
     */
    public static final int ADDRESS_WORK_LINE_LENGTH = 100;

    /**
     * Width of the free-form basic-details work line, from
     * {@code 05 HTML-BSIC-LN PIC X(100).} [app/cbl/CBSTM03A.CBL:L222]. Its content is
     * composed in the service layer and fitted to width by {@link #workLine(String)}.
     */
    public static final int BASIC_DETAILS_WORK_LINE_LENGTH = 100;

    /**
     * Width of the free-form transaction work line, from
     * {@code 05 HTML-TRAN-LN PIC X(100).} [app/cbl/CBSTM03A.CBL:L223]. Its content is
     * composed in the service layer and fitted to width by {@link #workLine(String)}.
     */
    public static final int TRANSACTION_WORK_LINE_LENGTH = 100;

    /** The single ASCII byte used for padding: {@code 0x20}, the space. Never zero or NUL. */
    private static final byte ASCII_SPACE = 0x20;

    /*
     * ------------------------------------------------------------------------------------
     * The thirty-four fixed HTML line templates, in exact legacy declaration order
     * [app/cbl/CBSTM03A.CBL:L150-L211].
     *
     * Each is written here as the bare literal, exactly as reassembled from the source, and
     * is padded once at class initialisation to HTML_RECORD_LENGTH encoded bytes by the
     * private fixed(..) helper. Keeping the bare literal in the source is what makes this
     * block diff-able against the legacy declarations; hand-typed trailing spaces would not
     * be. The helper also fails initialisation loudly, naming the offending template, if a
     * literal is ever widened past the record.
     * ------------------------------------------------------------------------------------
     */

    /** Template 1, legacy {@code HTML-L01}: the document type declaration. [app/cbl/CBSTM03A.CBL:L150] */
    public static final String HTML_L01 = fixed("HTML-L01", "<!DOCTYPE html>");

    /** Template 2, legacy {@code HTML-L02}: the opening html element. [app/cbl/CBSTM03A.CBL:L151] */
    public static final String HTML_L02 = fixed("HTML-L02", "<html lang=\"en\">");

    /** Template 3, legacy {@code HTML-L03}: the opening head element. [app/cbl/CBSTM03A.CBL:L152] */
    public static final String HTML_L03 = fixed("HTML-L03", "<head>");

    /**
     * Template 4, legacy {@code HTML-L04}: the character-set declaration. The declared
     * charset is <em>content</em> and does not change this record's encoding, which is ASCII.
     * [app/cbl/CBSTM03A.CBL:L153]
     */
    public static final String HTML_L04 = fixed("HTML-L04", "<meta charset=\"utf-8\">");

    /** Template 5, legacy {@code HTML-L05}: the document title. [app/cbl/CBSTM03A.CBL:L154] */
    public static final String HTML_L05 = fixed("HTML-L05", "<title>HTML Table Layout</title>");

    /** Template 6, legacy {@code HTML-L06}: the closing head element. [app/cbl/CBSTM03A.CBL:L155] */
    public static final String HTML_L06 = fixed("HTML-L06", "</head>");

    /** Template 7, legacy {@code HTML-L07}: the opening body element. [app/cbl/CBSTM03A.CBL:L156] */
    public static final String HTML_L07 = fixed("HTML-L07", "<body style=\"margin:0px;\">");

    /**
     * Template 8, legacy {@code HTML-L08}: the opening table element.
     *
     * <p><strong>Anomaly one lives here.</strong> There are <strong>two</strong> space
     * characters between {@code <table} and {@code align}, verified by a raw byte read of the
     * source with control characters exposed. The double space is emitted as-is; it is not
     * collapsed, not normalised and not corrected.</p>
     *
     * <p>The literal is reassembled from two continuation fragments whose split falls
     * mid-token, {@code styl} then {@code e=}, rejoining as {@code style=} with no space
     * added at the join. The declared width is {@code 70%}.</p>
     *
     * [app/cbl/CBSTM03A.CBL:L157-L158]
     */
    public static final String HTML_L08 = fixed("HTML-L08",
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">");

    /** Template 9, legacy {@code HTML-LTRS}: an opening table row. [app/cbl/CBSTM03A.CBL:L159] */
    public static final String HTML_LTRS = fixed("HTML-LTRS", "<tr>");

    /** Template 10, legacy {@code HTML-LTRE}: a closing table row. [app/cbl/CBSTM03A.CBL:L160] */
    public static final String HTML_LTRE = fixed("HTML-LTRE", "</tr>");

    /** Template 11, legacy {@code HTML-LTDS}: an unstyled opening table cell. [app/cbl/CBSTM03A.CBL:L161] */
    public static final String HTML_LTDS = fixed("HTML-LTDS", "<td>");

    /** Template 12, legacy {@code HTML-LTDE}: a closing table cell. [app/cbl/CBSTM03A.CBL:L162] */
    public static final String HTML_LTDE = fixed("HTML-LTDE", "</td>");

    /**
     * Template 13, legacy {@code HTML-L10}: a spanning table cell in the dark banner colour.
     *
     * <p>Member of the {@code colspan="3"} family, so there is deliberately
     * <strong>no space</strong> between {@code padding:0px 5px;} and
     * {@code background-color:}. The colour {@code #1d1d96b3} is the estate's only
     * eight-digit hex value and carries an alpha channel; its lower case is preserved.</p>
     *
     * [app/cbl/CBSTM03A.CBL:L163-L164]
     */
    public static final String HTML_L10 = fixed("HTML-L10",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">");

    /**
     * Template 14, legacy {@code HTML-L15}: a spanning table cell in the amber bank-header
     * colour. Member of the {@code colspan="3"} family, so no space precedes
     * {@code background-color:}. The upper case of {@code #FFAF33} is preserved.
     * [app/cbl/CBSTM03A.CBL:L165-L166]
     */
    public static final String HTML_L15 = fixed("HTML-L15",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">");

    /** Template 15, legacy {@code HTML-L16}: the bank name. [app/cbl/CBSTM03A.CBL:L167-L168] */
    public static final String HTML_L16 = fixed("HTML-L16",
            "<p style=\"font-size:16px\">Bank of XYZ</p>");

    /** Template 16, legacy {@code HTML-L17}: the bank street address. [app/cbl/CBSTM03A.CBL:L169-L170] */
    public static final String HTML_L17 = fixed("HTML-L17", "<p>410 Terry Ave N</p>");

    /** Template 17, legacy {@code HTML-L18}: the bank city, state and postal code. [app/cbl/CBSTM03A.CBL:L171-L172] */
    public static final String HTML_L18 = fixed("HTML-L18", "<p>Seattle WA 99999</p>");

    /**
     * Template 18, legacy {@code HTML-L22-35}: a spanning table cell in the neutral grey
     * detail colour. Member of the {@code colspan="3"} family, so no space precedes
     * {@code background-color:}. The lower case of {@code #f2f2f2} is preserved.
     * [app/cbl/CBSTM03A.CBL:L173-L175]
     */
    public static final String HTML_L22_35 = fixed("HTML-L22-35",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">");

    /**
     * Template 19, legacy {@code HTML-L30-42}: a centred spanning table cell in the teal
     * section-heading colour. Member of the {@code colspan="3"} family, so there is no space
     * before {@code background-color:} but there <em>is</em> a single space before
     * {@code text-align} &mdash; both spacings are reproduced as found. The upper case of
     * {@code #33FFD1} is preserved. [app/cbl/CBSTM03A.CBL:L176-L178]
     */
    public static final String HTML_L30_42 = fixed("HTML-L30-42",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">");

    /** Template 20, legacy {@code HTML-L31}: the basic-details section heading. [app/cbl/CBSTM03A.CBL:L179-L180] */
    public static final String HTML_L31 = fixed("HTML-L31",
            "<p style=\"font-size:16px\">Basic Details</p>");

    /** Template 21, legacy {@code HTML-L43}: the transaction-summary section heading. [app/cbl/CBSTM03A.CBL:L181-L182] */
    public static final String HTML_L43 = fixed("HTML-L43",
            "<p style=\"font-size:16px\">Transaction Summary</p>");

    /**
     * Template 22, legacy {@code HTML-L47}: the left-aligned transaction-identifier column
     * header cell at {@code 25%}.
     *
     * <p>Member of the explicit-{@code width} family, so there <strong>is</strong> a single
     * space after {@code width:25%;}, after {@code padding:0px 5px;} and after
     * {@code background-color:#33FF5E;}. That differs from the {@code colspan="3"} family
     * above and neither family is normalised toward the other. The literal is reassembled
     * from two continuation fragments split mid-token, {@code background-} then
     * {@code color:}, rejoining as {@code background-color:} with no space added and no
     * hyphen changed.</p>
     *
     * [app/cbl/CBSTM03A.CBL:L183-L185]
     */
    public static final String HTML_L47 = fixed("HTML-L47",
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">");

    /** Template 23, legacy {@code HTML-L48}: the transaction-identifier column label. [app/cbl/CBSTM03A.CBL:L186-L187] */
    public static final String HTML_L48 = fixed("HTML-L48",
            "<p style=\"font-size:16px\">Tran ID</p>");

    /**
     * Template 24, legacy {@code HTML-L50}: the left-aligned transaction-detail column header
     * cell at {@code 55%}. Explicit-{@code width} family spacing; mid-token continuation
     * split {@code background-} / {@code color:}. [app/cbl/CBSTM03A.CBL:L188-L190]
     */
    public static final String HTML_L50 = fixed("HTML-L50",
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">");

    /** Template 25, legacy {@code HTML-L51}: the transaction-detail column label. [app/cbl/CBSTM03A.CBL:L191-L192] */
    public static final String HTML_L51 = fixed("HTML-L51",
            "<p style=\"font-size:16px\">Tran Details</p>");

    /**
     * Template 26, legacy {@code HTML-L53}: the right-aligned amount column header cell at
     * {@code 20%}. Explicit-{@code width} family spacing; mid-token continuation split
     * {@code background-} / {@code color:}. [app/cbl/CBSTM03A.CBL:L193-L195]
     */
    public static final String HTML_L53 = fixed("HTML-L53",
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">");

    /** Template 27, legacy {@code HTML-L54}: the amount column label. [app/cbl/CBSTM03A.CBL:L196-L197] */
    public static final String HTML_L54 = fixed("HTML-L54",
            "<p style=\"font-size:16px\">Amount</p>");

    /**
     * Template 28, legacy {@code HTML-L58}: the left-aligned transaction-identifier data cell
     * at {@code 25%} in the neutral grey row colour. Explicit-{@code width} family spacing;
     * mid-token continuation split {@code background-} / {@code color:}.
     * [app/cbl/CBSTM03A.CBL:L198-L200]
     */
    public static final String HTML_L58 = fixed("HTML-L58",
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">");

    /**
     * Template 29, legacy {@code HTML-L61}: the left-aligned transaction-detail data cell at
     * {@code 55%} in the neutral grey row colour. Explicit-{@code width} family spacing;
     * mid-token continuation split {@code background-} / {@code color:}.
     * [app/cbl/CBSTM03A.CBL:L201-L203]
     */
    public static final String HTML_L61 = fixed("HTML-L61",
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">");

    /**
     * Template 30, legacy {@code HTML-L64}: the right-aligned amount data cell at {@code 20%}
     * in the neutral grey row colour. Explicit-{@code width} family spacing; mid-token
     * continuation split {@code background-} / {@code color:}.
     * [app/cbl/CBSTM03A.CBL:L204-L206]
     */
    public static final String HTML_L64 = fixed("HTML-L64",
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">");

    /**
     * Template 31, legacy {@code HTML-L75}: the end-of-statement banner. Its text stream
     * counterpart is a differently sized banner in the eighty-byte record and is not this
     * class's concern. [app/cbl/CBSTM03A.CBL:L207-L208]
     */
    public static final String HTML_L75 = fixed("HTML-L75", "<h3>End of Statement</h3>");

    /** Template 32, legacy {@code HTML-L78}: the closing table element. [app/cbl/CBSTM03A.CBL:L209] */
    public static final String HTML_L78 = fixed("HTML-L78", "</table>");

    /** Template 33, legacy {@code HTML-L79}: the closing body element. [app/cbl/CBSTM03A.CBL:L210] */
    public static final String HTML_L79 = fixed("HTML-L79", "</body>");

    /** Template 34, legacy {@code HTML-L80}: the closing html element. [app/cbl/CBSTM03A.CBL:L211] */
    public static final String HTML_L80 = fixed("HTML-L80", "</html>");

    /*
     * ------------------------------------------------------------------------------------
     * Literal components of the two composed sub-groups [app/cbl/CBSTM03A.CBL:L212-L220].
     *
     * These are NOT padded to the record width: each is exactly the width its group declares,
     * because they are concatenated with a substituted value before the composed group as a
     * whole is padded to HTML_RECORD_LENGTH. The component(..) helper asserts each width at
     * class initialisation, which is what makes the declared component widths above
     * load-bearing rather than merely documentary.
     * ------------------------------------------------------------------------------------
     */

    /**
     * The thirty-four-byte leading literal of the account-number line, including its
     * <strong>trailing space</strong>, which separates the label from the substituted account
     * identifier and is part of the declared width [app/cbl/CBSTM03A.CBL:L213-L214].
     */
    private static final String ACCOUNT_LINE_PREFIX = component(
            "HTML-L11 leading FILLER PIC X(34)",
            "<h3>Statement for Account Number: ",
            ACCOUNT_LINE_PREFIX_LENGTH);

    /**
     * The five-byte trailing literal of the account-number line: the closing heading tag.
     * Its presence here, contrasted with its absence from the customer-name line, is the
     * evidence that anomaly two is specific rather than systematic
     * [app/cbl/CBSTM03A.CBL:L216].
     */
    private static final String ACCOUNT_LINE_SUFFIX = component(
            "HTML-L11 trailing FILLER PIC X(05)",
            "</h3>",
            ACCOUNT_LINE_SUFFIX_LENGTH);

    /**
     * The twenty-six-byte leading literal of the customer-name line. There is deliberately no
     * matching trailing literal: see anomaly two in the class documentation
     * [app/cbl/CBSTM03A.CBL:L218-L219].
     */
    private static final String NAME_LINE_PREFIX = component(
            "HTML-L23 leading FILLER PIC X(26)",
            "<p style=\"font-size:16px\">",
            NAME_LINE_PREFIX_LENGTH);

    /*
     * ------------------------------------------------------------------------------------
     * The ordered template sequence. Declared last among the fields so that all thirty-four
     * template constants are already initialised when it is built.
     * ------------------------------------------------------------------------------------
     */

    /** Immutable, ordered view of the thirty-four fixed templates in legacy source order. */
    private static final List<String> FIXED_TEMPLATES = orderedTemplates();

    /**
     * Returns the thirty-four fixed HTML line templates in <strong>exact legacy declaration
     * order</strong> [app/cbl/CBSTM03A.CBL:L150-L211], each already padded to
     * {@value #HTML_RECORD_LENGTH} encoded bytes.
     *
     * <p>The order is contractual, so callers and tests may rely on positional identity: the
     * element at index zero is {@link #HTML_L01} and the element at index thirty-three is
     * {@link #HTML_L80}. The returned list is immutable and no mutable array or collection
     * escapes this class, so the sequence cannot be reordered or replaced by a caller.</p>
     *
     * <p>This accessor deliberately says nothing about <em>emission</em> order. Which template
     * is written, when, and how many times is decided by the statement-generation service,
     * which reproduces the legacy dispatcher; this class only preserves the order in which the
     * templates were declared.</p>
     *
     * @return an unmodifiable list of exactly {@value #FIXED_TEMPLATE_COUNT} templates, each
     *         exactly {@value #HTML_RECORD_LENGTH} encoded bytes wide, in legacy source order
     */
    public static List<String> fixedTemplates() {
        return FIXED_TEMPLATES;
    }

    /**
     * Builds the account-number line, legacy group {@code HTML-L11}
     * [app/cbl/CBSTM03A.CBL:L212-L216].
     *
     * <p>The line is composed as a {@value #ACCOUNT_LINE_PREFIX_LENGTH}-byte label literal
     * ending in a space, then the supplied account identifier fitted to
     * {@value #ACCOUNT_LINE_ACCOUNT_LENGTH} bytes, then the
     * {@value #ACCOUNT_LINE_SUFFIX_LENGTH}-byte closing heading tag &mdash; giving the
     * declared group width of {@value #ACCOUNT_LINE_DECLARED_LENGTH} bytes, which is then
     * space-padded on the right to the full {@value #HTML_RECORD_LENGTH}-byte record.</p>
     *
     * <p>This line <strong>does</strong> close its tag. Its unclosed sibling
     * {@link #customerNameLine(String)} is the anomaly; this method is the control that proves
     * the anomaly is specific.</p>
     *
     * <p>The identifier is substituted <strong>raw</strong>: it is neither escaped, sanitised
     * nor entity-encoded, matching the legacy move. An identifier longer than its field is
     * truncated in encoded bytes; a shorter one is padded on the right with spaces.</p>
     *
     * @param accountId the account identifier to substitute into the twenty-byte field; must
     *                  not be {@code null}, may be empty, and is truncated or space-padded to
     *                  the field width
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator
     * @throws NullPointerException  if {@code accountId} is {@code null}
     * @throws IllegalStateException if the assembled record is not exactly
     *                               {@value #HTML_RECORD_LENGTH} encoded bytes, which would
     *                               indicate the component widths in this class no longer
     *                               agree with the legacy group
     */
    public static String accountNumberLine(final String accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        final String group = ACCOUNT_LINE_PREFIX
                + fitToWidth(accountId, ACCOUNT_LINE_ACCOUNT_LENGTH)
                + ACCOUNT_LINE_SUFFIX;
        requireExactWidth("HTML-L11 composed group", group, ACCOUNT_LINE_DECLARED_LENGTH);
        return requireExactWidth("HTML-L11 record",
                fitToWidth(group, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Builds the customer-name line, legacy group {@code HTML-L23}
     * [app/cbl/CBSTM03A.CBL:L217-L220].
     *
     * <p><strong>Anomaly two lives here.</strong> The legacy group has exactly two
     * components &mdash; a {@value #NAME_LINE_PREFIX_LENGTH}-byte opening paragraph literal
     * and a {@value #NAME_LINE_NAME_LENGTH}-byte name field, giving the declared width of
     * {@value #NAME_LINE_DECLARED_LENGTH} bytes &mdash; and <strong>no third component</strong>,
     * so the paragraph element is never closed. No {@code </p>} is appended here. The
     * resulting markup is technically invalid, and that invalid markup is the required output;
     * appending a closing tag would change the bytes the parity gate compares.</p>
     *
     * <p>The declared group is space-padded on the right to the full
     * {@value #HTML_RECORD_LENGTH}-byte record.</p>
     *
     * <p>The name is substituted <strong>raw</strong>: it is neither escaped, sanitised nor
     * entity-encoded, matching the legacy move. A name longer than its field is truncated in
     * encoded bytes; a shorter one is padded on the right with spaces.</p>
     *
     * @param customerName the customer name to substitute into the fifty-byte field; must not
     *                     be {@code null}, may be empty, and is truncated or space-padded to
     *                     the field width
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator and no closing paragraph tag
     * @throws NullPointerException  if {@code customerName} is {@code null}
     * @throws IllegalStateException if the assembled record is not exactly
     *                               {@value #HTML_RECORD_LENGTH} encoded bytes, which would
     *                               indicate the component widths in this class no longer
     *                               agree with the legacy group
     */
    public static String customerNameLine(final String customerName) {
        Objects.requireNonNull(customerName, "customerName must not be null");
        // Two components only. There is no closing tag component in the legacy group, and
        // none is synthesised here: see anomaly two in the class documentation.
        final String group = NAME_LINE_PREFIX
                + fitToWidth(customerName, NAME_LINE_NAME_LENGTH);
        requireExactWidth("HTML-L23 composed group", group, NAME_LINE_DECLARED_LENGTH);
        return requireExactWidth("HTML-L23 record",
                fitToWidth(group, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Fits already-composed content to one HTML output record.
     *
     * <p>This is the width authority for the three free-form work lines declared with no
     * internal structure at [app/cbl/CBSTM03A.CBL:L221-L223] &mdash; the address line, the
     * basic-details line and the transaction line, each {@code PIC X(100)}. Their content is
     * composed by the statement-generation service, which knows the business meaning of each
     * field; their width belongs here, so that the hundred-byte figure lives in exactly one
     * place. The three call sites are self-documenting through
     * {@link #ADDRESS_WORK_LINE_LENGTH}, {@link #BASIC_DETAILS_WORK_LINE_LENGTH} and
     * {@link #TRANSACTION_WORK_LINE_LENGTH}, all of which equal
     * {@value #HTML_RECORD_LENGTH}.</p>
     *
     * <p>The semantics are those of moving a value into a hundred-byte alphanumeric field:
     * content shorter than the record is padded on the right with ASCII spaces, and content
     * longer than the record is truncated at {@value #HTML_RECORD_LENGTH}
     * <strong>encoded bytes</strong>. Nothing is escaped, sanitised or entity-encoded, and no
     * line terminator is added.</p>
     *
     * @param content the composed work-line content; must not be {@code null} and may be
     *                empty, shorter than the record, or longer than the record
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator
     * @throws NullPointerException  if {@code content} is {@code null}
     * @throws IllegalStateException if the fitted record is not exactly
     *                               {@value #HTML_RECORD_LENGTH} encoded bytes
     */
    public static String workLine(final String content) {
        Objects.requireNonNull(content, "content must not be null");
        return requireExactWidth("HTML free-form work line",
                fitToWidth(content, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /*
     * ------------------------------------------------------------------------------------
     * Private helpers. All width arithmetic below is performed on US-ASCII encoded bytes.
     * ------------------------------------------------------------------------------------
     */

    /**
     * Pads a fixed template literal to the full record width, failing initialisation loudly
     * if the literal has been widened past the record.
     *
     * @param legacyName the legacy condition-name of the template, used in the failure
     *                   message so that a misedited literal is identifiable by name
     * @param literal    the bare literal exactly as reassembled from the legacy source
     * @return the literal right-padded with ASCII spaces to {@value #HTML_RECORD_LENGTH}
     *         encoded bytes
     * @throws IllegalStateException if {@code literal} exceeds {@value #HTML_RECORD_LENGTH}
     *                               encoded bytes; the platform exception is used deliberately
     *                               because a template-width violation is a programming error
     *                               in this class, not a business or file-status condition
     */
    private static String fixed(final String legacyName, final String literal) {
        final int declared = encodedLength(literal);
        if (declared > HTML_RECORD_LENGTH) {
            throw new IllegalStateException("HTML template " + legacyName + " is " + declared
                    + " encoded bytes, which exceeds the " + HTML_RECORD_LENGTH
                    + "-byte HTML statement record; the literal must not be widened");
        }
        return fitToWidth(literal, HTML_RECORD_LENGTH);
    }

    /**
     * Verifies that a composed-group component literal is exactly the width its legacy group
     * declares, and returns it unchanged.
     *
     * @param description a human-readable identification of the component, used in the
     *                    failure message
     * @param literal     the bare literal exactly as reassembled from the legacy source
     * @param width       the width the legacy group declares for this component
     * @return {@code literal}, unchanged
     * @throws IllegalStateException if the literal is not exactly {@code width} encoded bytes
     */
    private static String component(final String description, final String literal, final int width) {
        return requireExactWidth(description, literal, width);
    }

    /**
     * Builds the ordered, immutable template sequence and verifies its cardinality.
     *
     * @return an unmodifiable list of the thirty-four templates in legacy source order
     * @throws IllegalStateException if the list does not hold exactly
     *                               {@value #FIXED_TEMPLATE_COUNT} elements
     */
    private static List<String> orderedTemplates() {
        final List<String> templates = List.of(
                HTML_L01, HTML_L02, HTML_L03, HTML_L04, HTML_L05, HTML_L06, HTML_L07, HTML_L08,
                HTML_LTRS, HTML_LTRE, HTML_LTDS, HTML_LTDE,
                HTML_L10, HTML_L15, HTML_L16, HTML_L17, HTML_L18, HTML_L22_35, HTML_L30_42,
                HTML_L31, HTML_L43, HTML_L47, HTML_L48, HTML_L50, HTML_L51, HTML_L53, HTML_L54,
                HTML_L58, HTML_L61, HTML_L64, HTML_L75, HTML_L78, HTML_L79, HTML_L80);
        if (templates.size() != FIXED_TEMPLATE_COUNT) {
            throw new IllegalStateException("Expected " + FIXED_TEMPLATE_COUNT
                    + " fixed HTML templates but assembled " + templates.size());
        }
        return templates;
    }

    /**
     * Fits a value to an exact width using fixed-width move semantics, working entirely in the
     * encoded-byte domain.
     *
     * <p>The value is encoded to US-ASCII first, then copied into a byte image of exactly
     * {@code width} bytes that has been pre-filled with the ASCII space. A value longer than
     * the field is therefore truncated at a byte boundary and a shorter one is space-padded on
     * the right. Operating on bytes rather than on {@code char} values is what makes the result
     * exact: a supplementary code point occupies two {@code char} values but encodes to a
     * single byte, so character-based arithmetic would produce the wrong width.</p>
     *
     * @param value the value to fit; must not be {@code null}
     * @param width the exact field width in encoded bytes; must not be negative
     * @return a string whose US-ASCII encoding is exactly {@code width} bytes long
     */
    private static String fitToWidth(final String value, final int width) {
        final byte[] source = value.getBytes(StandardCharsets.US_ASCII);
        final byte[] image = new byte[width];
        Arrays.fill(image, ASCII_SPACE);
        System.arraycopy(source, 0, image, 0, Math.min(source.length, width));
        return new String(image, StandardCharsets.US_ASCII);
    }

    /**
     * Verifies an exact encoded-byte width and returns the value unchanged.
     *
     * @param description a human-readable identification of the value, used in the failure
     *                    message
     * @param value       the value to measure
     * @param expected    the required width in encoded bytes
     * @return {@code value}, unchanged
     * @throws IllegalStateException if the value's US-ASCII encoding is not exactly
     *                               {@code expected} bytes long
     */
    private static String requireExactWidth(final String description, final String value, final int expected) {
        final int actual = encodedLength(value);
        if (actual != expected) {
            throw new IllegalStateException(description + " must be exactly " + expected
                    + " encoded bytes but was " + actual);
        }
        return value;
    }

    /**
     * Measures a value in US-ASCII encoded bytes.
     *
     * <p>Every width check in this class routes through this method rather than through
     * {@code String.length()}, so that no measurement can be distorted by surrogate pairs or
     * by a platform default charset.</p>
     *
     * @param value the value to measure
     * @return the length of the value's US-ASCII encoding, in bytes
     */
    private static int encodedLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Not instantiable: this class is a stateless holder of fixed-width layout constants.
     *
     * @throws AssertionError always, if invoked
     */
    private StatementHtmlTemplates() {
        throw new AssertionError("StatementHtmlTemplates is a constant holder and is not instantiable");
    }
}
