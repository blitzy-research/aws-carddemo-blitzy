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
 * Fixed-width HTML line templates for the account-statement HTML output stream, and the single holder
 * of the thirty-four literal HTML lines that the legacy batch statement generator wrote to its HTML
 * output file.
 *
 * <p>It also holds the two composed sub-group lines and the width rule for the three free-form work
 * lines that share the same record. It holds fixed-width layout knowledge and nothing else: it performs
 * no input or output, makes no emission-sequencing decision, formats no number and touches no
 * persistent state.
 *
 * <p><strong>The record.</strong> The HTML output file's record is declared as
 * {@code 01 FD-HTMLFILE-REC PIC X(100).} - a fixed one-hundred-byte record
 * [app/cbl/CBSTM03A.CBL:L47]. The template group {@code 01 HTML-LINES.} opens at
 * [app/cbl/CBSTM03A.CBL:L148] with a {@code 05 HTML-FIXED-LN PIC X(100).} elementary item, and
 * <strong>exactly thirty-four</strong> level-88 condition-name constants are declared against it at
 * [app/cbl/CBSTM03A.CBL:L150-L211] - a mechanically verified figure, enumerated programmatically over
 * that line range, with the resulting name list reproduced verbatim in the inventory below. Three
 * subordinate groups follow at [app/cbl/CBSTM03A.CBL:L212-L223]. Every constant published here is
 * already padded on the right with ASCII spaces to exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
 * so a caller may hand any of them straight to a writer without further measurement, and none of the
 * underlying literals exceeds the record width - the widest is eighty-five bytes - so no template is
 * ever truncated.
 *
 * <p>The HTML output file's record is a fixed one-hundred-byte alphanumeric record, declared at
 * [app/cbl/CBSTM03A.CBL:L47]. The template group opens at [app/cbl/CBSTM03A.CBL:L148] over a
 * single one-hundred-byte elementary item, and <strong>exactly thirty-four</strong>
 * condition-name constants are declared against that item at
 * [app/cbl/CBSTM03A.CBL:L150-L211]. That count of thirty-four is a mechanically verified
 * figure, not an estimate: the declarations were enumerated programmatically over that line
 * range and the resulting name list is carried in the inventory below. Three subordinate groups
 * follow at [app/cbl/CBSTM03A.CBL:L212-L223].</p>
 *
 * <p><strong>The thirty-four fixed templates, in exact source order.</strong> The order below is the
 * declaration order in the legacy source and is contractual; the ordered accessor
 * {@link #fixedTemplates()} returns the templates in precisely this sequence.
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
 * <p><strong>Continuation reassembly.</strong> Several of the literals above are declared across two
 * source lines using COBOL literal continuation, with a hyphen in the indicator column of the second
 * line. Counting the continuation indicators over [app/cbl/CBSTM03A.CBL:L150-L211] gives
 * <strong>eleven</strong> continued literals - templates 8, 13, 14, 18, 19, 22, 24, 26, 28, 29 and 30 -
 * of which <strong>seven</strong> split <em>mid-token</em>:
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
 * <p><strong>Style-attribute spacing is inconsistent between the two table-cell families.</strong> The
 * inconsistency is contractual and <strong>neither family is normalised toward the other</strong>:
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
 * <p><strong>Colour and width values are contractual bytes, not design choices.</strong> No design
 * system, component library, styling framework or design-token set is in scope anywhere in this
 * migration and no design asset exists for it. The colour and width literals below are legacy bytes
 * reproduced for output parity: they are not replaced by tokens, CSS custom properties, a stylesheet or
 * a theme, they are not canonicalised, not shortened, not expanded, and their case is preserved
 * character for character.
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
 * <p><strong>Anomaly one: the double space inside the table tag.</strong> Template 8 reads
 * {@code <table} followed by <strong>two</strong> space characters and then {@code align}, verified by
 * a raw byte read of the source line with control characters exposed - the bytes are
 * {@code 3C 74 61 62 6C 65 20 20 61 6C 69 67 6E} - so it is real source content and not an artefact of
 * formatting or of the continuation reassembly. The double space is <strong>emitted</strong>: not
 * collapsed, not normalised and not treated as a typographical error to correct, because byte-identical
 * output is the requirement and the parity gate compares bytes. Carried as row 21 of the source anomaly
 * register. See {@link #HTML_L08} [app/cbl/CBSTM03A.CBL:L157-L158].
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
 * <h2>The two composed lines, and why they are assembled differently</h2>
 *
 * <p>Two subordinate groups each pair a literal with a substituted value, but the program does
 * not emit them the same way, and the difference decides the bytes:</p>
 *
 * <ul>
 *   <li><strong>The account-number heading is emitted from its group.</strong> The group is
 *       declared at [app/cbl/CBSTM03A.CBL:L212-L216] as a
 *       {@value #ACCOUNT_LINE_PREFIX_LENGTH}-byte opening-heading literal ending in a space, a
 *       {@value #ACCOUNT_LINE_ACCOUNT_LENGTH}-byte account-identifier field and a
 *       {@value #ACCOUNT_LINE_SUFFIX_LENGTH}-byte closing-heading literal, giving
 *       {@value #ACCOUNT_LINE_DECLARED_LENGTH} declared bytes. The emitting paragraph moves the
 *       account identifier into the middle field and writes the record <em>from the group</em>
 *       [app/cbl/CBSTM03A.CBL:L529-L530], so the emitted record is the whole group, trailing
 *       spaces of the identifier field included, padded on the right to
 *       {@value #HTML_RECORD_LENGTH} bytes. {@link #accountNumberLine(String)} reproduces
 *       exactly that.</li>
 *   <li><strong>The customer-name line is not emitted from its group.</strong> The group at
 *       [app/cbl/CBSTM03A.CBL:L217-L220] is a {@value #NAME_LINE_PREFIX_LENGTH}-byte opening
 *       paragraph literal plus a {@value #NAME_LINE_NAME_LENGTH}-byte name field, and it carries
 *       no closing literal &mdash; but that group is only a <em>staging area</em>. The emitting
 *       paragraph at [app/cbl/CBSTM03A.CBL:L558-L568] moves the assembled name into the
 *       fifty-byte field, clears the hundred-byte record to spaces, and then <em>composes the
 *       record itself</em> from four pieces: the opening paragraph literal, the name field
 *       transferred only as far as its first pair of adjacent spaces, two literal space bytes,
 *       and a {@value #NAME_LINE_CLOSING_TAG_LENGTH}-byte closing paragraph literal. The record
 *       written is that composition, so <strong>the emitted line does close its paragraph
 *       tag</strong>. {@link #customerNameLine(String)} reproduces the emitted record, not the
 *       staging group.</li>
 * </ul>
 *
 * <p>The distinction matters because reading the staging group as if it were the emitted image
 * gets both halves of the line wrong: it pads the name field out to its full fifty bytes when
 * the emission stops at the first pair of adjacent spaces, and it omits a closing tag that the
 * emission does write. Byte parity is decided by the emitting paragraph, never by the
 * declaration.</p>
 *
 * <p>Because the transfer stops at the first pair of adjacent spaces, the widest record the
 * customer-name line can produce is {@value #NAME_LINE_MAX_SIGNIFICANT_LENGTH} significant
 * bytes &mdash; {@value #NAME_LINE_PREFIX_LENGTH} plus {@value #NAME_LINE_NAME_LENGTH} plus
 * {@value #NAME_LINE_DELIMITER_LENGTH} plus {@value #NAME_LINE_CLOSING_TAG_LENGTH} &mdash;
 * which is inside the {@value #HTML_RECORD_LENGTH}-byte record, so padding can never displace
 * the closing tag. Both composed lines are shorter than the record and are space-padded on the
 * right to exactly {@value #HTML_RECORD_LENGTH} bytes, exactly as moving a short value into a
 * hundred-byte alphanumeric record does.</p>
 *
 * <p>One consequence of the delimiter is visible in real data and must not be "corrected". The
 * name is assembled upstream at [app/cbl/CBSTM03A.CBL:L455-L490] from the first, middle and last
 * name, each transferred up to its own first space and each followed by one separator space, so
 * a customer with no middle name yields two adjacent spaces after the first name. The emission
 * then stops there and the line carries the first name alone. That is the legacy output, and it
 * is reproduced rather than repaired.</p>
 *
 * <p><strong>Move semantics: truncate and pad, in encoded bytes.</strong> Every substitution reproduces
 * the semantics of moving a value into a fixed-width alphanumeric field: a value shorter than its field
 * is <strong>padded on the right with ASCII spaces</strong> and a longer one is <strong>truncated to
 * the field width</strong>. Padding is always the ASCII space character - never a zero, never a NUL,
 * never a tab. Widths are measured and applied in <strong>US-ASCII encoded bytes</strong>, never in
 * {@code String} character counts, and {@link java.nio.charset.StandardCharsets#US_ASCII} is named
 * explicitly at every such point so no result can depend on a platform default charset. The
 * distinction is not academic: a supplementary code point occupies two {@code char} values but encodes
 * to a single replacement byte, so a character-based measurement would silently produce a record of the
 * wrong width.
 *
 * <p>Three further fields share the same hundred-byte record and are declared with no
 * internal structure at [app/cbl/CBSTM03A.CBL:L221-L223]: an address line, a basic-details
 * line and a transaction line, each one hundred bytes wide. Their <em>content</em> is composed by
 * the statement-generation service at run time and is deliberately not modelled here; their
 * <em>width</em> is this class's business, which is why {@link #workLine(String)} exists and
 * why {@link #ADDRESS_WORK_LINE_LENGTH}, {@link #BASIC_DETAILS_WORK_LINE_LENGTH} and
 * {@link #TRANSACTION_WORK_LINE_LENGTH} are named. Keeping the hundred-byte figure here and
 * out of the service layer is what stops fixed-width layout knowledge from leaking upward.</p>
 *
 * <p><strong>No templating engine.</strong> A templating engine is forbidden for this output (decision
 * D-27), and so is any general-purpose format-string abstraction that could reorder or re-space
 * content: byte-identical output requires the same literals, in the same order, at the same width, and
 * an engine introduces whitespace and ordering variability that the byte-parity comparison fails
 * immediately. Every template here is therefore a plain string literal, padded once at class
 * initialisation.
 *
 * <p><strong>Related context, owned elsewhere.</strong> The statement <em>text</em> stream is a
 * different record width - {@code 01 FD-STMTFILE-REC PIC X(80).} [app/cbl/CBSTM03A.CBL:L45] - and its
 * eighty-byte templates live in a separate class; the two are kept strictly apart, so no hundred-byte
 * template may leak into the eighty-byte stream and no eighty-byte constant appears here. The
 * generator's control flow is a hand-rolled dispatcher driven by a data-definition-name work field with
 * backward jumps into the dispatcher, and becomes an explicit state enumeration driven by a loop over a
 * switch in the statement-generation service: which template is emitted, when, and how many times is
 * that service's decision, and this class holds the templates and their order of <em>declaration</em>,
 * never their order of emission. The statement job declares the same HTML data definition at
 * {@code LRECL=80} in one step and {@code LRECL=100} in the next [app/jcl/CREASTMT.JCL], resolved by
 * decision D-44 to one hundred for this stream, which agrees with the record declaration above. The
 * same job reprojects its sorted input into a 328-of-350-byte projection that truncates a transaction
 * processing timestamp by two bytes; that truncation is handled in the batch layer and never here.
 *
 * <p>Widths are measured and applied in <strong>US-ASCII encoded bytes</strong>, never in
 * {@code String} character counts, and {@link java.nio.charset.StandardCharsets#US_ASCII} is
 * named explicitly at every such point so that no result can depend on a platform default
 * charset. The record is a byte image, so the byte domain is the only domain in which an
 * exact width can be asserted. Character-domain arithmetic is not merely less direct, it is
 * wrong: a supplementary code point occupies two {@code char} values, so a character count
 * cannot describe the record it produces. Every caller-supplied value is proven
 * single-byte representable by the guard described below before any width arithmetic runs,
 * so an unmappable value is refused outright and is never quietly turned into a substitute
 * byte.</p>
 *
 * <h2>Escaping: what is escaped, what is not, and why</h2>
 *
 * <p>Every <strong>substituted value</strong> is escaped by {@link #escapeText(String)} before it
 * is fitted to its field. Every <strong>markup literal</strong> &mdash; all thirty-four fixed
 * templates, the leading and trailing literals of the two composed lines, and the paragraph tags
 * of the three work lines &mdash; is emitted exactly as the legacy source declares it, with no
 * escaping, no attribute-quoting normalisation, no tag balancing, no pretty-printing and no
 * minifying. The division is between markup this class owns and data a caller supplies, and it is
 * drawn there because those are the only two categories of byte in the output.</p>
 *
 * <p>Escaping the data half is a deliberate, documented divergence from byte-for-byte
 * faithfulness. It was not the original position taken here. The earlier reasoning was that the
 * artefact is a fixed-width batch file rather than a served response, so no output-encoding
 * requirement attached at this layer and escaping belonged at some later serving boundary. That
 * reasoning does not hold, for a reason specific to this migration: on the mainframe the statement
 * data could only have come from a VSAM record written by another batch program in the same estate,
 * whereas here the customer name and the address lines are free text that online maintenance
 * screens accept. A value such as {@code <script>} submitted through one of those screens is
 * written to the statement file and rendered later to whoever opens it &mdash; stored cross-site
 * scripting, with no serving boundary in between to defer to, because the file is the artefact and
 * its viewer is unknown.</p>
 *
 * <p>The divergence is the narrowest one available: escaping is the identity function on the entire
 * legitimate domain of every field it touches, so for real data the emitted bytes are unchanged and
 * the hundred-byte parity gate is unaffected. It differs only for input that would otherwise inject
 * markup. It is recorded in {@code docs/decision-log.md} rather than left as an unexplained
 * difference from the legacy bytes.</p>
 *
 * <p>There is correspondingly <strong>no method here that accepts composed markup</strong>. The
 * three free-form work lines are built by {@link #addressWorkLine(String)},
 * {@link #basicDetailsWorkLine(String, String)} and {@link #transactionWorkLine(String)}, each of
 * which owns its own paragraph tags and reproduces its own legacy {@code STRING} statement,
 * including the address line's two-space delimiter and the asymmetry that the other two lines have
 * none. Composing the markup here rather than accepting it from a caller is what makes an
 * unescaped-data path into the output unreachable rather than merely discouraged.</p>
 *
 * <p>Escaping is not the only control here, and it is deliberately not asked to carry the whole
 * load. A second, independent guard stands in front of it: every caller-supplied value must be
 * <strong>printable US-ASCII</strong>, and the account-number slot is narrowed further to ASCII
 * digits and the ASCII space to match its {@code PIC 9(11)} source. Refusal never alters a byte,
 * so it cannot disturb parity for any value the legacy system is capable of producing, and it
 * closes two routes that escaping does not address at all. It removes record-framing injection
 * &mdash; the hundred-byte image carries no terminator, so an embedded carriage return or line
 * feed would split one logical record into two in the written file and desynchronise every
 * record after it. And it removes a byte-corrupting parity defect, because a code point outside
 * US-ASCII would otherwise be encoded to a substitute byte, leaving a field of the right width
 * holding the wrong content. The two controls are complementary rather than alternative: the
 * guard bounds the character set, and escaping neutralises the markup metacharacters that live
 * inside that set.</p>
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
 *   <li>The statement <em>text</em> stream is a different record width &mdash; eighty bytes,
 *       declared at [app/cbl/CBSTM03A.CBL:L45] &mdash; and its eighty-byte line templates live
 *       in a separate class. The two are kept strictly apart: no hundred-byte template may leak
 *       into the eighty-byte stream, and no eighty-byte constant appears here.</li>
 *   <li>The generator's control flow is a hand-rolled dispatcher driven by a DD-name work
 *       field with backward jumps back into the dispatcher; it becomes an explicit state
 *       enumeration driven by a loop over a switch in the statement-generation service.
 *       Which template is emitted, when, and how many times is that service's decision. This
 *       class holds the templates and their order of <em>declaration</em>, never their order
 *       of emission.</li>
 *   <li>The statement job declares the same HTML data definition with a record length of eighty
 *       in one step and of one hundred in the immediately following step that runs the generator
 *       [app/jcl/CREASTMT.JCL]. The conflict is resolved to <strong>100 for the HTML
 *       stream</strong>, which agrees with the emitting program's hundred-byte record
 *       declaration [app/cbl/CBSTM03A.CBL:L47], and to 80 for the text stream, which agrees with
 *       its eighty-byte record declaration [app/cbl/CBSTM03A.CBL:L45]. That resolution is what
 *       confirms one hundred is the correct width here.</li>
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
 *   <li><strong>The customer-name line follows the emitting paragraph, not the declaration.
 *       </strong> Its staging group is 26 + 50 = 76 declared bytes and carries no closing
 *       literal, but the group is never written: the paragraph at
 *       [app/cbl/CBSTM03A.CBL:L558-L568] composes the record from the opening literal, the name
 *       field cut at its first pair of adjacent spaces, two literal spaces and a four-byte
 *       closing paragraph literal. The neighbouring account-number heading is different in kind
 *       &mdash; 34 + 20 + 5 = 59 declared bytes written straight from the group
 *       [app/cbl/CBSTM03A.CBL:L529-L530] &mdash; which is why the two builders in this class are
 *       not symmetrical. Treating the staging group as the emitted image would both over-pad the
 *       name and drop a closing tag the legacy program writes.</li>
 *   <li><strong>Exactly thirty-four fixed templates, emitted in source order at one hundred
 *       bytes each.</strong> The count and the order are contractual; the count was verified
 *       mechanically over [app/cbl/CBSTM03A.CBL:L150-L211].</li>
 *   <li><strong>Colour and width literals are legacy bytes, not design tokens.</strong> No
 *       design system, component library or design asset is in scope; mixed hex case is
 *       preserved, {@code #1d1d96b3} retains its eight-digit alpha form, and nothing is
 *       canonicalised.</li>
 *   <li><strong>Style-attribute spacing is inconsistent between the two table-cell
 *       families</strong> and neither is normalised toward the other.</li>
 *   <li><strong>Markup literals are never escaped, normalised or balanced</strong>, matching the
 *       legacy declarations byte for byte; substituted values <em>are</em> escaped, which is the
 *       one documented divergence in this class and is set out under the escaping heading
 *       above.</li>
 *   <li><strong>Refusal stands alongside escaping as the second injection control.</strong> Every
 *       caller-supplied value is required to be printable US-ASCII, and the account-number slot is
 *       narrowed further to digits and spaces to match its {@code PIC 9(11)} source. A value that
 *       is not representable is refused rather than encoded to a substitute byte, which removes a
 *       byte-corrupting parity defect as well as the record-framing hazard. The customer-name slot
 *       and the three work lines are deliberately not narrowed below printable US-ASCII, because
 *       the legacy fields feeding them are alphanumeric and the middle-name component carries no
 *       legacy edits at all.</li>
 *   <li><strong>A templating engine is forbidden</strong>; byte-identical output cannot
 *       survive an engine's whitespace and ordering variability.</li>
 *   <li><strong>The hundred-byte image carries no line terminator</strong> even though the
 *       COBOL source file has CRLF endings; record separation is the writer's concern in the
 *       batch layer. The {@code <meta charset="utf-8">} declaration is content, and every
 *       literal is ASCII.</li>
 *   <li><strong>The statement job declares the same HTML data definition with a record length
 *       of eighty in one step and of one hundred in the next</strong> [app/jcl/CREASTMT.JCL];
 *       resolved to 100 for the HTML stream and 80 for the text stream, matching the two
 *       record declarations.</li>
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
     * Record and component widths.
     *
     * These are factual layout evidence read from the legacy record declarations - record
     * widths, byte offsets and field lengths - and not tuning or capacity figures.
     */

    /**
     * Width in encoded bytes of one HTML output record. The emitting program declares that
     * record as a single fixed one-hundred-byte alphanumeric item at
     * [app/cbl/CBSTM03A.CBL:L47].
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
     * Declared width of the whole customer-name <em>staging</em> group, being
     * {@value #NAME_LINE_PREFIX_LENGTH} + {@value #NAME_LINE_NAME_LENGTH}
     * [app/cbl/CBSTM03A.CBL:L217-L220].
     *
     * <p>This is the width of the declaration, not of the emitted record. The group is never
     * written: the emitting paragraph at [app/cbl/CBSTM03A.CBL:L558-L568] uses only its
     * fifty-byte name field as a staging area and then composes the record from four pieces. The
     * emitted record therefore carries a closing paragraph tag that this group does not, and its
     * name segment stops at the first pair of adjacent spaces rather than running the full fifty
     * bytes. The constant is published because it is the declared figure a reader will find in
     * the source; {@link #NAME_LINE_MAX_SIGNIFICANT_LENGTH} is the emitted bound.</p>
     */
    public static final int NAME_LINE_DECLARED_LENGTH = 76;

    /**
     * Width of the two literal space bytes the emitting paragraph writes after the name segment,
     * which is also the two-space delimiter at which the name transfer stops
     * [app/cbl/CBSTM03A.CBL:L563-L564].
     */
    public static final int NAME_LINE_DELIMITER_LENGTH = 2;

    /**
     * Width of the closing paragraph literal the emitting paragraph writes at the end of the
     * customer-name record [app/cbl/CBSTM03A.CBL:L565].
     */
    public static final int NAME_LINE_CLOSING_TAG_LENGTH = 4;

    /**
     * Widest significant prefix the customer-name record can carry, being
     * {@value #NAME_LINE_PREFIX_LENGTH} + {@value #NAME_LINE_NAME_LENGTH} +
     * {@value #NAME_LINE_DELIMITER_LENGTH} + {@value #NAME_LINE_CLOSING_TAG_LENGTH}, reached
     * only by a fifty-byte name containing no pair of adjacent spaces.
     *
     * <p>It is inside the {@value #HTML_RECORD_LENGTH}-byte record, which is what guarantees
     * that fitting the composition to the record can only pad it and can never displace the
     * closing tag.</p>
     */
    public static final int NAME_LINE_MAX_SIGNIFICANT_LENGTH = 82;

    /**
     * Width of the free-form address work line, declared with no internal structure at
     * [app/cbl/CBSTM03A.CBL:L221]. Its content is composed in the service layer and fitted
     * to width by {@link #workLine(String)}.
     */
    public static final int ADDRESS_WORK_LINE_LENGTH = 100;

    /**
     * Width of the free-form basic-details work line, declared with no internal structure at
     * [app/cbl/CBSTM03A.CBL:L222]. Its content is composed in the service layer and fitted to
     * width by {@link #workLine(String)}.
     */
    public static final int BASIC_DETAILS_WORK_LINE_LENGTH = 100;

    /**
     * Width of the free-form transaction work line, declared with no internal structure at
     * [app/cbl/CBSTM03A.CBL:L223]. Its content is composed in the service layer and fitted to
     * width by {@link #workLine(String)}.
     */
    public static final int TRANSACTION_WORK_LINE_LENGTH = 100;

    /** The single ASCII byte used for padding: {@code 0x20}, the space. Never zero or NUL. */
    private static final byte ASCII_SPACE = 0x20;

    /**
     * The lowest printable US-ASCII code point, the space. Everything below it is a C0 control
     * character, and a carriage return or line feed among them would split the hundred-byte
     * record in the written file.
     */
    private static final char FIRST_PRINTABLE_US_ASCII = 0x20;

    /**
     * The highest printable US-ASCII code point, the tilde. The delete control sits immediately
     * above it, and every code point beyond that is outside US-ASCII and so has no single-byte
     * image in this record.
     */
    private static final char LAST_PRINTABLE_US_ASCII = 0x7E;

    /** The lowest ASCII digit, the start of the account-number slot's permitted range. */
    private static final char FIRST_ASCII_DIGIT = '0';

    /** The highest ASCII digit, the end of the account-number slot's permitted range. */
    private static final char LAST_ASCII_DIGIT = '9';

    /** The ASCII space, permitted in the account-number slot because the legacy move pads with it. */
    private static final char ASCII_SPACE_CHARACTER = ' ';

    /*
     * The thirty-four fixed HTML line templates, in exact legacy declaration order
     * [app/cbl/CBSTM03A.CBL:L150-L211].
     *
     * Each is written here as the bare literal, exactly as reassembled from the source, and
     * is padded once at class initialisation to HTML_RECORD_LENGTH encoded bytes by the
     * private fixed(..) helper. Keeping the bare literal in the source is what makes this
     * block diff-able against the legacy declarations; hand-typed trailing spaces would not
     * be. The helper also fails initialisation loudly, naming the offending template, if a
     * literal is ever widened past the record.
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
     * Literal components of the two composed sub-groups [app/cbl/CBSTM03A.CBL:L212-L220].
     *
     * These are NOT padded to the record width: each is exactly the width its group declares,
     * because they are concatenated with a substituted value before the composed group as a
     * whole is padded to HTML_RECORD_LENGTH. The component(..) helper asserts each width at
     * class initialisation, which is what makes the declared component widths above
     * load-bearing rather than merely documentary.
     */

    /**
     * The thirty-four-byte leading literal of the account-number line, including its
     * <strong>trailing space</strong>, which separates the label from the substituted account
     * identifier and is part of the declared width [app/cbl/CBSTM03A.CBL:L213-L214].
     */
    private static final String ACCOUNT_LINE_PREFIX = component(
            "account-number line leading literal",
            "<h3>Statement for Account Number: ",
            ACCOUNT_LINE_PREFIX_LENGTH);

    /**
     * The five-byte trailing literal of the account-number line: the closing heading tag, which
     * is part of the declared group and is therefore written with it
     * [app/cbl/CBSTM03A.CBL:L216].
     */
    private static final String ACCOUNT_LINE_SUFFIX = component(
            "account-number line trailing literal",
            "</h3>",
            ACCOUNT_LINE_SUFFIX_LENGTH);

    /**
     * The twenty-six-byte leading literal of the customer-name line, taken from the staging
     * group's leading literal [app/cbl/CBSTM03A.CBL:L218-L219] and written first by the emitting
     * paragraph [app/cbl/CBSTM03A.CBL:L562].
     */
    private static final String NAME_LINE_PREFIX = component(
            "customer-name line leading literal",
            "<p style=\"font-size:16px\">",
            NAME_LINE_PREFIX_LENGTH);

    /**
     * The two literal space bytes the emitting paragraph writes after the name segment
     * [app/cbl/CBSTM03A.CBL:L564].
     *
     * <p>The same two bytes are also the delimiter that stops the name transfer
     * [app/cbl/CBSTM03A.CBL:L563], so one constant serves both roles and the two can never drift
     * apart.</p>
     */
    private static final String NAME_LINE_DELIMITER = component(
            "customer-name line separator literal",
            "  ",
            NAME_LINE_DELIMITER_LENGTH);

    /**
     * The four-byte closing paragraph literal the emitting paragraph writes last
     * [app/cbl/CBSTM03A.CBL:L565].
     */
    private static final String NAME_LINE_CLOSING_TAG = component(
            "customer-name line closing literal",
            "</p>",
            NAME_LINE_CLOSING_TAG_LENGTH);

    /*
     * ------------------------------------------------------------------------------------
     * Work-line composition literals and the five HTML character references.
     * ------------------------------------------------------------------------------------
     */

    /** The opening paragraph literal shared by all three free-form work lines. */
    private static final String PARAGRAPH_OPEN = "<p>";

    /** The closing paragraph literal shared by all three free-form work lines. */
    private static final String PARAGRAPH_CLOSE = "</p>";

    /**
     * The two literal spaces the address work line appends after the transferred address.
     *
     * <p>Declared explicitly because they are a separate sending item in the legacy
     * {@code STRING} statement, delimited by {@code SIZE}, and are therefore appended
     * unconditionally &mdash; they are not padding and must not be trimmed away
     * [app/cbl/CBSTM03A.CBL:L572].
     */
    private static final String ADDRESS_LINE_TRAILING_SPACES = "  ";

    /**
     * The two-space run that terminates the address field's transfer in the legacy
     * {@code STRING ... DELIMITED BY '  '} clause.
     */
    private static final String ADDRESS_LINE_DELIMITER = "  ";

    /** Character reference for the ampersand. Replaced first, so it cannot be double-escaped. */
    private static final String AMPERSAND_REFERENCE = "&amp;";

    /** Character reference for the less-than sign, which would otherwise open an element. */
    private static final String LESS_THAN_REFERENCE = "&lt;";

    /** Character reference for the greater-than sign, which would otherwise close one. */
    private static final String GREATER_THAN_REFERENCE = "&gt;";

    /** Character reference for the quotation mark, which would otherwise close an attribute. */
    private static final String QUOTATION_MARK_REFERENCE = "&quot;";

    /**
     * Character reference for the apostrophe. The numeric form is used rather than
     * {@code &apos;} because the numeric reference is defined in every HTML version, whereas the
     * named one is not defined in HTML 4 and a statement file has no controlled viewer.
     */
    private static final String APOSTROPHE_REFERENCE = "&#39;";

    /** The character that opens every character reference this class emits. */
    private static final char REFERENCE_START = '&';

    /** The character that closes every character reference this class emits. */
    private static final char REFERENCE_END = ';';

    /*
     * The ordered template sequence. Declared last among the fields so that all thirty-four
     * template constants are already initialised when it is built.
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
     * <p>This line is emitted <strong>from its declared group</strong>
     * [app/cbl/CBSTM03A.CBL:L529-L530], so the whole group reaches the record, trailing spaces of
     * the identifier field included, and the closing heading tag is part of the group. Its
     * sibling {@link #customerNameLine(String)} is composed by the emitting paragraph instead,
     * which is why the two builders differ.</p>
     *
     * <p>The identifier is escaped before it is fitted, so it cannot carry markup into the
     * heading element; see {@link #escapeText(String)}. Escaping is the identity function on an
     * account identifier, which is eleven digits, so the emitted bytes are unchanged for every real
     * value. An identifier longer than its field is truncated in encoded bytes; a shorter one is
     * padded on the right with spaces.</p>
     *
     * <p>Because no substituted byte may be rewritten, the identifier is instead
     * <strong>validated</strong> before substitution. The legacy field moved into this slot is
     * {@code ACCT-ID PIC 9(11)} [app/cbl/CBSTM03A.CBL:L529], a numeric display item, so this
     * slot accepts only ASCII digits and the ASCII space the move pads with. That refuses
     * nothing the legacy system could emit, and it means no markup character can reach the
     * active heading element this line opens.</p>
     *
     * @param accountId the account identifier to substitute into the twenty-byte field; must
 *                  not be {@code null}, may be empty, must hold only ASCII digits and the ASCII
 *                  space, and is escaped and then truncated or space-padded to the field width
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator
     * @throws NullPointerException     if {@code accountId} is {@code null}
     * @throws IllegalArgumentException if {@code accountId} carries a character that is not
     *                                  printable US-ASCII, or that is printable but is neither
     *                                  an ASCII digit nor an ASCII space
     * @throws IllegalStateException    if the assembled record is not exactly
     *                                  {@value #HTML_RECORD_LENGTH} encoded bytes, which would
     *                                  indicate the component widths in this class no longer
     *                                  agree with the legacy group
     */
    public static String accountNumberLine(final String accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        requirePrintableUsAscii(accountId, "account identifier");
        requireDigitsOrSpaces(accountId, "account identifier");
        final String group = ACCOUNT_LINE_PREFIX
                + fitToWidth(escapeText(accountId), ACCOUNT_LINE_ACCOUNT_LENGTH)
                + ACCOUNT_LINE_SUFFIX;
        requireExactWidth("HTML-L11 composed group", group, ACCOUNT_LINE_DECLARED_LENGTH);
        return requireExactWidth("HTML-L11 record",
                fitToWidth(group, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Builds the customer-name line exactly as the emitting paragraph writes it
     * [app/cbl/CBSTM03A.CBL:L558-L568].
     *
     * <p>The record is <strong>not</strong> the declared staging group. The legacy paragraph
     * performs four steps, and all four are reproduced here in order:</p>
     * <ol>
     *   <li>The assembled name is moved into the {@value #NAME_LINE_NAME_LENGTH}-byte staging
     *       field, so a longer name is truncated to that width and a shorter one is padded with
     *       spaces.</li>
     *   <li>The {@value #HTML_RECORD_LENGTH}-byte record is cleared to spaces, which is why every
     *       byte after the composition is a space and never a residue.</li>
     *   <li>The record is composed from the {@value #NAME_LINE_PREFIX_LENGTH}-byte opening
     *       paragraph literal, then the staging field transferred only <em>up to its first pair
     *       of adjacent spaces</em>, then {@value #NAME_LINE_DELIMITER_LENGTH} literal space
     *       bytes, then the {@value #NAME_LINE_CLOSING_TAG_LENGTH}-byte closing paragraph
     *       literal.</li>
     *   <li>The record is written.</li>
     * </ol>
     *
     * <p>Two consequences follow, and both are contractual. <strong>The line does close its
     * paragraph tag</strong>, because the closing literal is a component of the composition even
     * though it is absent from the declaration. And <strong>the name segment stops at the first
     * pair of adjacent spaces</strong>, so the padding introduced by the staging move never
     * reaches the record: a name of "{@code JOHN Q PUBLIC}" contributes thirteen bytes, not
     * fifty. A customer with no middle name is assembled upstream with two adjacent spaces after
     * the first name [app/cbl/CBSTM03A.CBL:L455-L490], and the line then carries the first name
     * alone. That is the legacy output and it is reproduced, not repaired.</p>
     *
     * <p>The widest possible composition is {@value #NAME_LINE_MAX_SIGNIFICANT_LENGTH} bytes,
     * which is inside the record, so fitting to the record only ever pads on the right and can
     * never displace the closing tag.</p>
     *
     * <p>The name is escaped before it is fitted; see {@link #escapeText(String)}. This is the
     * single most important escaping site in the class, for two reasons. The name is free text that
     * a customer-maintenance screen accepts, so it is the one field on the line whose content an
     * outside party influences; and the opening literal here carries a <em>quoted attribute</em>,
     * so a value able to escape its element would land where an attribute could be closed. The
     * escaped name is then fitted to the {@value #NAME_LINE_NAME_LENGTH}-byte staging field, in
     * encoded bytes, before the delimiter rule below is applied.</p>
     *
     * @param customerName the assembled customer name; must not be {@code null}, may be empty, and
     *                     is escaped and then fitted to the
     *                     {@value #NAME_LINE_NAME_LENGTH}-byte staging field before the delimiter
     *                     is applied
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator
     * @throws NullPointerException  if {@code customerName} is {@code null}
     * @throws IllegalStateException if the assembled record is not exactly
     *                               {@value #HTML_RECORD_LENGTH} encoded bytes, which would
     *                               indicate the component widths in this class no longer agree
     *                               with the legacy emission
     */
    public static String customerNameLine(final String customerName) {
        Objects.requireNonNull(customerName, "customerName must not be null");
        requirePrintableUsAscii(customerName, "customer name");
        // Step one: the truncating move into the fifty-byte staging field, over the escaped name.
        final String nameField = fitToWidth(escapeText(customerName), NAME_LINE_NAME_LENGTH);
        // Step three: the transfer stops at the first pair of adjacent spaces, so the padding the
        // move introduced is dropped rather than emitted. Steps two and four -- clearing the
        // record to spaces and writing it -- are the right pad below and the caller's write.
        final String record = NAME_LINE_PREFIX + nameUpToDelimiter(nameField)
                + NAME_LINE_DELIMITER + NAME_LINE_CLOSING_TAG;
        return requireExactWidth("customer-name record",
                fitToWidth(record, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Returns the part of the staging field that the emitting paragraph actually transfers, being
     * everything before its first pair of adjacent spaces [app/cbl/CBSTM03A.CBL:L563].
     *
     * <p>When the field holds no such pair &mdash; a fifty-byte name with no internal double
     * space and no padding left &mdash; the whole field transfers, which is the legacy behaviour
     * when a delimiter is not found. When the field begins with the pair, nothing transfers and
     * the record carries the literals alone.</p>
     *
     * @param  nameField the staging field, already fitted to
     *                   {@value #NAME_LINE_NAME_LENGTH} bytes
     * @return the transferred segment, which may be empty and may be the whole field
     */
    private static String nameUpToDelimiter(final String nameField) {
        final int delimiterAt = nameField.indexOf(NAME_LINE_DELIMITER);
        return delimiterAt < 0 ? nameField : nameField.substring(0, delimiterAt);
    }

    /**
     * Builds the address work line, legacy group {@code HTML-ADDR-LN}
     * [app/cbl/CBSTM03A.CBL:L570-L576, L578-L584, L586-L592].
     *
     * <p>The legacy composition is a {@code STRING} statement of four sending items:
     * {@code '<p>'}, the address field <strong>delimited by two spaces</strong>, a literal two
     * spaces, and {@code '</p>'}. The delimiter is the load-bearing part: COBOL transfers the
     * address field only up to, and excluding, the first run of two consecutive spaces, which is
     * how a fixed-width padded field is right-trimmed. The two literal spaces are then appended
     * unconditionally, so they appear even when the address filled its whole field. Both
     * behaviours are reproduced here rather than replaced by a trim, because a trim would drop the
     * two literal spaces and would also fail to cut an address that carries a double space in its
     * middle &mdash; which the legacy statement does cut.</p>
     *
     * <p>This method is also the width authority for the three free-form work lines declared with
     * no internal structure at [app/cbl/CBSTM03A.CBL:L221-L223] &mdash; the address line, the
     * basic-details line and the transaction line, each one hundred bytes wide. The hundred-byte
     * figure lives in exactly one place, and the three composers are self-documenting through
     * {@link #ADDRESS_WORK_LINE_LENGTH}, {@link #BASIC_DETAILS_WORK_LINE_LENGTH} and
     * {@link #TRANSACTION_WORK_LINE_LENGTH}, all of which equal
     * {@value #HTML_RECORD_LENGTH}.</p>
     *
     * <p>The address text is escaped; the markup around it is not. See
     * {@link #escapeText(String)} for why that division is where it is.</p>
     *
     * @param addressLine the address field to substitute; must not be {@code null} and may be
     *                    empty or space-padded
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator
     * @throws NullPointerException  if {@code addressLine} is {@code null}
     * @throws IllegalStateException if the assembled record is not exactly
     *                               {@value #HTML_RECORD_LENGTH} encoded bytes
     */
    public static String addressWorkLine(final String addressLine) {
        Objects.requireNonNull(addressLine, "addressLine must not be null");
        requirePrintableUsAscii(addressLine, "address line");
        final String transferred = upToFirstDoubleSpace(addressLine);
        final String composed = PARAGRAPH_OPEN + escapeText(transferred)
                + ADDRESS_LINE_TRAILING_SPACES + PARAGRAPH_CLOSE;
        return requireExactWidth("HTML-ADDR-LN record",
                fitToWidth(composed, ADDRESS_WORK_LINE_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Builds one basic-details work line, legacy group {@code HTML-BSIC-LN}
     * [app/cbl/CBSTM03A.CBL:L613-L619, L620-L626, L627-L633].
     *
     * <p>The legacy composition is three sending items: an opening paragraph literal that already
     * carries the label and its colon &mdash; {@code '<p>Account ID         : '},
     * {@code '<p>Current Balance    : '}, {@code '<p>FICO Score         : '} &mdash; then the value
     * delimited by an asterisk, then {@code '</p>'}. Delimiting by an asterisk on a value that
     * contains none transfers the whole field, padding included, so the value is <em>not</em>
     * trimmed here. That asymmetry with the address line is a real property of the legacy source
     * and is preserved.</p>
     *
     * <p>The label is supplied by the caller because the legacy program carries a different literal
     * on each of the three lines and this class does not own their business meaning. It is escaped
     * along with the value: escaping is the identity function on all three legacy labels, so
     * nothing changes for real input, and escaping it closes the label as a second injection route
     * rather than leaving one open on the assumption that a caller will only ever pass a
     * literal.</p>
     *
     * @param label the label text to place after the opening paragraph tag, colon and separating
     *              spaces included; must not be {@code null}
     * @param value the value to place after the label; must not be {@code null} and may be empty or
     *              space-padded
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if the assembled record is not exactly
     *                               {@value #HTML_RECORD_LENGTH} encoded bytes
     */
    public static String basicDetailsWorkLine(final String label, final String value) {
        Objects.requireNonNull(label, "label must not be null");
        requirePrintableUsAscii(label, "work-line label");
        Objects.requireNonNull(value, "value must not be null");
        requirePrintableUsAscii(value, "work-line value");
        final String composed = PARAGRAPH_OPEN + escapeText(label) + escapeText(value)
                + PARAGRAPH_CLOSE;
        return requireExactWidth("HTML-BSIC-LN record",
                fitToWidth(composed, BASIC_DETAILS_WORK_LINE_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Builds one transaction work line, legacy group {@code HTML-TRAN-LN}
     * [app/cbl/CBSTM03A.CBL:L686-L692, L698-L704, L710-L716].
     *
     * <p>The legacy composition is {@code '<p>'}, the value delimited by an asterisk, and
     * {@code '</p>'}. As on the basic-details line, an asterisk delimiter against a value that
     * holds none transfers the whole field, so padding is carried through and the value is not
     * trimmed. The legacy program emits this shape three times per transaction, for the
     * identifier, the date and the amount; one method serves all three because the composition is
     * identical and only the caller's value differs.</p>
     *
     * @param value the value to place between the paragraph tags; must not be {@code null} and may
     *              be empty or space-padded
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator
     * @throws NullPointerException  if {@code value} is {@code null}
     * @throws IllegalStateException if the assembled record is not exactly
     *                               {@value #HTML_RECORD_LENGTH} encoded bytes
     */
    public static String transactionWorkLine(final String value) {
        Objects.requireNonNull(value, "value must not be null");
        requirePrintableUsAscii(value, "work-line value");
        final String composed = PARAGRAPH_OPEN + escapeText(value) + PARAGRAPH_CLOSE;
        return requireExactWidth("HTML-TRAN-LN record",
                fitToWidth(composed, TRANSACTION_WORK_LINE_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Replaces the five markup-significant characters with their HTML character references.
     *
     * <p><strong>Why this exists.</strong> The legacy program moved statement data into an HTML
     * record with no encoding of any kind, because on the mainframe the data could only have come
     * from a VSAM record written by another batch program in the same estate. In this module the
     * same values arrive from a relational store that an online transaction writes, and the account
     * update and customer maintenance screens accept free text in the name and address fields. A
     * value such as {@code <script>} stored through one of those screens and later rendered into a
     * statement is stored cross-site scripting: the person harmed is whoever opens the statement,
     * not the person who submitted the value, and nothing between the two notices. Escaping at the
     * point of composition is the only place that closes it, because the statement file is written
     * once and read by an unknown viewer later.</p>
     *
     * <p><strong>The parity position.</strong> This is a deliberate, documented divergence from
     * byte-for-byte faithfulness, and it is the narrowest one available. It is the identity function
     * on the entire legitimate domain of every field it touches &mdash; account identifiers are
     * digits, amounts are digits with a sign and a decimal point, dates are digits and hyphens,
     * names and addresses in every fixture in the estate are alphanumerics, spaces and punctuation
     * that is not markup-significant &mdash; so for real data the emitted bytes are unchanged and
     * the hundred-byte parity gate is unaffected. It differs only for input that would otherwise
     * inject markup, which is exactly the input that must differ. The divergence is recorded in
     * {@code docs/decision-log.md} rather than left as an unexplained difference.</p>
     *
     * <p><strong>Why the apostrophe is included.</strong> None of the five is optional. The
     * apostrophe and the quotation mark matter because {@link #NAME_LINE_PREFIX} opens a tag
     * carrying a quoted attribute, and a value that escaped its element could otherwise be
     * positioned to close that attribute. Escaping all five means the emitted record cannot be
     * reinterpreted as markup regardless of which element the value lands in.</p>
     *
     * <p>Published rather than private because the statement-generation service composes values
     * from several fields before they reach a line builder, and it needs the same function rather
     * than a second, possibly divergent one. The line builders in this class apply it themselves, so
     * a caller that passes raw text is already safe; a caller that pre-composes must apply it.</p>
     *
     * @param text the text to escape; must not be {@code null} and may be empty
     * @return the text with {@code &}, {@code <}, {@code >}, {@code "} and {@code '} replaced by
     *         their character references, and every other character unchanged
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String escapeText(final String text) {
        Objects.requireNonNull(text, "text must not be null");

        // The ampersand must be replaced first, or the ampersands this method introduces would
        // themselves be re-escaped. Building in one pass rather than by chained replacement makes
        // that ordering hazard structurally impossible instead of merely avoided.
        final StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            switch (character) {
                case '&' -> escaped.append(AMPERSAND_REFERENCE);
                case '<' -> escaped.append(LESS_THAN_REFERENCE);
                case '>' -> escaped.append(GREATER_THAN_REFERENCE);
                case '"' -> escaped.append(QUOTATION_MARK_REFERENCE);
                case '\'' -> escaped.append(APOSTROPHE_REFERENCE);
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
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
     * <strong>encoded bytes</strong>. Nothing is escaped or entity-encoded, and no line
     * terminator is added.</p>
     *
     * <p>This is a <strong>guarded</strong> fitter rather than an unchecked sink. The content is
     * validated to printable US-ASCII before it is fitted, so the C0 control range, the delete
     * character and every code point outside US-ASCII are refused rather than encoded to a
     * substitute byte. That guard is what keeps the hundred-byte framing intact: the batch
     * writer supplies record separation, so an embedded carriage return or line feed would
     * split one logical record into two in the written file and desynchronise every record
     * after it. Markup characters are legitimate content here and pass through unchanged &mdash;
     * the legacy composes these lines from paragraph literals wrapped around display fields
     * [app/cbl/CBSTM03A.CBL:L614-L618, L687-L691] &mdash; so this method validates the
     * character set and the width and never the markup structure.</p>
     *
     * <p>That last point is the whole reason this method is a framing primitive and not the
     * sanitisation point. It receives content that has already been composed, so it cannot tell
     * a legitimate paragraph literal from an injected one, and it therefore leaves markup
     * untouched by design. Caller-supplied field values must be neutralised before they are
     * composed into that content, which is what {@link #escapeText(String)} is for, and which is
     * what {@link #addressWorkLine(String)}, {@link #basicDetailsWorkLine(String, String)} and
     * {@link #transactionWorkLine(String)} already do for the three lines the statement
     * generator emits. Those three composers are the supported path for raw field data; this
     * fitter exists for content that is already composed and already neutralised.</p>
     *
     * @param content the composed work-line content; must not be {@code null}, must hold only
     *                printable US-ASCII, and may be empty, shorter than the record, or longer
     *                than the record
     * @return one HTML output record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes,
     *         carrying no line terminator
     * @throws NullPointerException     if {@code content} is {@code null}
     * @throws IllegalArgumentException if {@code content} carries a character that is not
     *                                  printable US-ASCII
     * @throws IllegalStateException    if the fitted record is not exactly
     *                                  {@value #HTML_RECORD_LENGTH} encoded bytes
     */
    public static String workLine(final String content) {
        Objects.requireNonNull(content, "content must not be null");
        requirePrintableUsAscii(content, "work-line content");
        return requireExactWidth("HTML free-form work line",
                fitToWidth(content, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /*
     * Private helpers. All width arithmetic below is performed on US-ASCII encoded bytes.
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
     * Rejects a caller-supplied value that carries a character outside printable US-ASCII.
     *
     * <p>The rejected set is the C0 control range, the delete character, and every code point
     * above US-ASCII &mdash; that is, everything below the space and everything above the
     * tilde. Two distinct hazards are closed by the one rule. A carriage return or line feed
     * would split the hundred-byte record in the written file, because the batch writer supplies
     * record separation and the image itself carries no terminator. A code point above US-ASCII
     * has no single-byte image in this record at all, so encoding it would silently substitute a
     * question-mark byte and corrupt the record rather than reporting a problem; refusing it is
     * the faithful outcome.</p>
     *
     * <p>The diagnostic reports the offending position and the code point as a number, never the
     * character itself, so a control byte cannot travel into the message that reports it.</p>
     *
     * @param value     the caller-supplied value, already known to be non-{@code null}
     * @param fieldName a human-readable identification of the field, used in the failure message
     * @throws IllegalArgumentException if any character is not printable US-ASCII
     */
    private static void requirePrintableUsAscii(final String value, final String fieldName) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < FIRST_PRINTABLE_US_ASCII || character > LAST_PRINTABLE_US_ASCII) {
                throw new IllegalArgumentException(fieldName + " must hold printable US-ASCII"
                        + " only, because the HTML statement record is a fixed hundred-byte image"
                        + " with no terminator and this class may not rewrite a substituted byte;"
                        + " the character at position " + (index + 1) + " is code point "
                        + (int) character);
            }
        }
    }

    /**
     * Rejects a value for the account-number slot that is neither an ASCII digit nor an ASCII
     * space.
     *
     * <p>The legacy slot receives {@code ACCT-ID PIC 9(11)} [app/cbl/CBSTM03A.CBL:L529], a
     * numeric display item, so digits are all it can carry and the trailing spaces come from the
     * move into the wider alphanumeric field. Narrowing the slot to that set refuses nothing the
     * legacy system could emit while removing every markup character from the one composed slot
     * whose surrounding literal opens an active element.</p>
     *
     * <p>This check runs after the printable check, so the value is already known to be printable
     * before it is narrowed. The failure message still reports the offending position and its code
     * point rather than the character itself, because a diagnostic that echoes rejected input is a
     * second injection route into whatever reads the log (DL-041).</p>
     *
     * @param value     the caller-supplied value, already known to be printable US-ASCII
     * @param fieldName a human-readable identification of the field, used in the failure message
     * @throws IllegalArgumentException if any character is neither an ASCII digit nor an ASCII
     *                                  space
     */
    private static void requireDigitsOrSpaces(final String value, final String fieldName) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            final boolean acceptable = character == ASCII_SPACE_CHARACTER
                    || (character >= FIRST_ASCII_DIGIT && character <= LAST_ASCII_DIGIT);
            if (!acceptable) {
                throw new IllegalArgumentException(fieldName + " must hold only ASCII digits and"
                        + " spaces, because the legacy field moved into this slot is a numeric"
                        + " display item; the character at position " + (index + 1)
                        + " is code point " + (int) character);
            }
        }
    }

    /**
     * Fits a value to an exact width using fixed-width move semantics, working entirely in the
     * encoded-byte domain.
     *
     * <p>The value is encoded to US-ASCII first, then copied into a byte image of exactly
     * {@code width} bytes that has been pre-filled with the ASCII space. A value longer than
     * the field is therefore truncated at a byte boundary and a shorter one is space-padded on
     * the right. Operating on bytes rather than on {@code char} values is what makes the result
     * exact, because the record is a byte image and a {@code char} count cannot describe one.
     * Every value reaching this helper is already known to be single-byte representable &mdash;
     * the fixed templates are pure ASCII literals and every caller-supplied value has passed
     * {@link #requirePrintableUsAscii(String, String)} &mdash; so the encoding step here can
     * never introduce a substitute byte.</p>
     *
     * <p>One refinement is applied after the byte truncation: if the surviving bytes end in an
     * unterminated character reference, that fragment is dropped and the space padding takes its
     * place. Cutting a fixed-width field at a byte boundary is faithful, but cutting {@code &amp;}
     * into {@code &am} would emit a fragment that a viewer may resynchronise against the following
     * markup, turning a truncation into a rendering defect. Dropping the fragment costs nothing
     * elsewhere, because a value with no unterminated {@code &} at the cut is returned untouched
     * &mdash; which is every one of the thirty-four fixed templates, and all real data.</p>
     *
     * @param value the value to fit; must not be {@code null}
     * @param width the exact field width in encoded bytes; must not be negative
     * @return a string whose US-ASCII encoding is exactly {@code width} bytes long
     */
    private static String fitToWidth(final String value, final int width) {
        final byte[] source = value.getBytes(StandardCharsets.US_ASCII);
        final byte[] image = new byte[width];
        Arrays.fill(image, ASCII_SPACE);
        final int copied = Math.min(source.length, width);
        System.arraycopy(source, 0, image, 0, copied);

        if (source.length > width) {
            blankTrailingReferenceFragment(image, copied);
        }
        return new String(image, StandardCharsets.US_ASCII);
    }

    /**
     * Blanks a partial character reference left at the end of a truncated field image.
     *
     * <p>Scans backwards from the cut for the first reference delimiter. An unterminated
     * {@code &} &mdash; one with no {@code ;} after it within the surviving bytes &mdash; is a
     * fragment, and every byte from it to the cut is overwritten with the ASCII space so that the
     * field keeps its exact width. A {@code ;} encountered first means the last reference completed
     * and nothing needs removing. The scan is bounded by the cut, so it is linear in the width of
     * one field and terminates unconditionally.</p>
     *
     * @param image the field image, already padded and already carrying the truncated bytes
     * @param cut   the number of bytes copied into the image before padding begins
     */
    private static void blankTrailingReferenceFragment(final byte[] image, final int cut) {
        for (int index = cut - 1; index >= 0; index--) {
            if (image[index] == (byte) REFERENCE_END) {
                return;
            }
            if (image[index] == (byte) REFERENCE_START) {
                Arrays.fill(image, index, cut, ASCII_SPACE);
                return;
            }
        }
    }

    /**
     * Returns the leading portion of a value up to, and excluding, the first two-space run.
     *
     * <p>This reproduces the COBOL {@code STRING ... DELIMITED BY '  '} transfer the address work
     * line uses. When the delimiter is absent the whole value is transferred, which is what COBOL
     * does; when it is present in the middle of the value the transfer stops there, which is also
     * what COBOL does and is why this is not a right trim.</p>
     *
     * @param value the sending value; must not be {@code null}
     * @return the transferred portion, possibly the whole value and possibly empty
     */
    private static String upToFirstDoubleSpace(final String value) {
        final int delimiter = value.indexOf(ADDRESS_LINE_DELIMITER);
        return delimiter < 0 ? value : value.substring(0, delimiter);
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
