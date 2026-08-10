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
 * Fixed HTML markup templates and line builders for the hundred-byte statement HTML record.
 *
 * <p>The HTML statement stream is a fixed-width record of {@value #HTML_RECORD_LENGTH} bytes, and
 * {@value #FIXED_TEMPLATE_COUNT} of its lines are invariant markup declared as condition-name
 * constants against that record in [app/cbl/CBSTM03A.CBL]. This class publishes those templates in
 * declaration order, builds the two lines that interleave markup with substituted data, and fits
 * free-form content to the record width. The statement <em>text</em> stream is a different record
 * width and its templates live in a separate class; the two are kept strictly apart, so no
 * hundred-byte template may leak into the eighty-byte stream and no eighty-byte constant appears
 * here.</p>
 *
 * <p><strong>Every byte of every template is a contractual value, not a design choice.</strong> The
 * colour values, column widths, attribute order, letter casing and internal spacing are reproduced
 * exactly as the legacy source declares them, because the emitted file is compared byte for byte.
 * Casing is mixed across the colour values and must stay mixed. Spacing is inconsistent between the
 * two table-cell families and neither family is normalised toward the other: a cell that spans the
 * table carries no space between the padding declaration and the background colour, while a cell with
 * an explicit column width carries one space after the width, one after the padding and one after the
 * colour. One template also carries two space characters inside its table tag rather than one. All of
 * that is emitted as found &mdash; nothing is collapsed, normalised or corrected.</p>
 *
 * <p><strong>Continuation reassembly.</strong> Several literals are declared across two source lines
 * and some of those splits fall mid-token. Reassembly joins the fragments with no space added at the
 * join and no hyphen inserted or removed, which is the only reading under which every finished
 * literal is well-formed markup.</p>
 *
 * <p><strong>The two composed lines are assembled differently, and the difference is byte-visible.</strong>
 * The account-number heading is emitted from its declared group: a thirty-four-byte opening literal
 * ending in a space, a twenty-byte account-identifier field and a five-byte closing literal, which is
 * the {@link #ACCOUNT_LINE_DECLARED_LENGTH} figure. The customer-name line is not: its staging group is
 * never written, and the emitting paragraph transfers the name only up to the first pair of adjacent
 * spaces and then writes a closing tag the group does not carry. Reading the staging group as if it
 * were the emitted image gets both halves wrong &mdash; it pads the name out to its full fifty bytes
 * when the emission stops earlier, and it omits a tag the emission does write. Byte parity is decided
 * by the emitting paragraph, never by the declaration.</p>
 *
 * <p>That delimiter is load-bearing rather than incidental. The name is assembled upstream from the
 * first, middle and last name, each transferred up to its own first space and each followed by one
 * separator space, so a customer with no middle name yields two adjacent spaces after the first name
 * and the emission stops there. That is visible in real data and must not be "corrected".</p>
 *
 * <p><strong>Move semantics, in encoded bytes.</strong> A value shorter than its field is padded on
 * the right with ASCII spaces; a value longer than its field is truncated at the field width. The pad
 * byte is always the ASCII space &mdash; never a zero, never a NUL. Every width here is measured and
 * applied in US-ASCII encoded bytes rather than {@code String} character counts, and
 * {@link java.nio.charset.StandardCharsets#US_ASCII} is named explicitly at every such point so that
 * no result can depend on a platform default charset.</p>
 *
 * <p>Three further fields share the same hundred-byte record with no internal structure: an address
 * line, a basic-details line and a transaction line. Their content is composed by the
 * statement-generation service, which knows the business meaning of each field, and is deliberately
 * not modelled here; their width is this class's business, which is why the hundred-byte figure lives
 * in exactly one place.</p>
 *
 * <p><strong>Two kinds of builder, differing only in structure.</strong> The three work-line composers
 * &mdash; {@link #addressWorkLine(String)}, {@link #basicDetailsWorkLine(String, String)} and
 * {@link #transactionWorkLine(String)} &mdash; wrap a field value in the paragraph tags its record is
 * defined to carry and then fit the result. {@link #workLine(String)} wraps nothing: it is the fitter
 * for content that has <em>already</em> been composed, so it is the right entry point for a fully
 * assembled line and the wrong one for a bare field value, which would reach a record without its
 * tags. Both kinds move the content itself through untouched and both apply the same refusals, so the
 * distinction is structural and never defensive. A production census asserts that nothing in the
 * application tree reaches {@link #workLine(String)} with field data.</p>
 *
 * <p><strong>Nothing is escaped, encoded or repaired: every value is emitted byte for byte.</strong>
 * The legacy program moves the field's bytes into a {@code PIC X(100)} line and writes the line, so a
 * description reading {@code Purchase at Zulauf-O'Keefe} reaches the file with its apostrophe intact and
 * the closing tag exactly where the move left it. Substituting character references would keep the
 * record a hundred bytes wide while shifting every byte after the substitution, which is a byte-parity
 * defect against the expected-output fixture and against the emitting program alike. Escaping was
 * applied here once and has been removed; see {@code docs/decision-log.md} entry DL-209, which also
 * records the stored-markup exposure that the legacy design carries and that no requirement in scope
 * asks this artefact to close.</p>
 *
 * <p>Two guards remain in front of every substituted value, and both are refusals rather than
 * rewrites: the value must be printable US-ASCII, and the account-number slot is narrowed further to
 * ASCII digits and the ASCII space. A refusal never alters a byte, so neither guard can disturb parity;
 * what they stop is a control byte splitting one fixed-length record into two.
 *
 * <p><strong>Those guards are no longer the first line, and that matters.</strong> A control byte used
 * to be refused only here - which meant a value carrying one was accepted online, stored, and met for
 * the first time by this class during a batch run, failing that run at a point where the only remedy is
 * to correct stored data. The transport now refuses a control character in an inbound text value where
 * it arrives, in {@code WebMvcConfig}, so the online path can no longer deposit one. The guards below
 * are kept rather than withdrawn, because a stored value predating that rule, a value seeded by a
 * fixture, or a value arriving by any route other than the request boundary is still stopped before it
 * can split a record.
 *
 * <p><strong>What is deliberately still not closed, stated plainly.</strong> Printable markup remains
 * storable and is emitted here byte for byte, so a description or an address line carrying angle
 * brackets reaches the HTML artefact as markup rather than as text. That is not an oversight and it is
 * not closed by escaping, because escaping would shift every byte after the substitution and fail the
 * byte comparison this artefact exists to satisfy. The consequence is that the emitted file is a
 * <em>parity artefact</em> and not a document to be served to a browser from untrusted storage; any
 * consumer that renders it inherits the legacy design's exposure and must encode at its own boundary.
 * The residual is recorded in {@code docs/decision-log.md} entries DL-209 and DL-267 alongside this note, and the
 * boundary rule that removes the control-byte half of it is asserted by
 * {@code TransportControlCharacterRefusalTest}.</p>
 *
 * <p><strong>No templating engine.</strong> A templating engine, and any general-purpose format-string
 * abstraction that could reorder or re-space content, is forbidden for this output: either would
 * introduce whitespace and ordering variability that the byte-comparison gate would immediately
 * fail.</p>
 *
 * <p>All state is static and immutable, so the class is safe for concurrent use.</p>
 */
public final class StatementHtmlTemplates {

    /** The width of one HTML statement output record, in encoded bytes. */
    public static final int HTML_RECORD_LENGTH = 100;

    /** The number of invariant markup templates declared against the record. */
    public static final int FIXED_TEMPLATE_COUNT = 34;

    public static final int ACCOUNT_LINE_PREFIX_LENGTH = 34;

    public static final int ACCOUNT_LINE_ACCOUNT_LENGTH = 20;

    public static final int ACCOUNT_LINE_SUFFIX_LENGTH = 5;

    /**
     * Declared width of the account-number heading group: opening literal, account field and closing
     * literal. Unlike the customer-name group, this group is what the paragraph actually emits.
     */
    public static final int ACCOUNT_LINE_DECLARED_LENGTH = 59;

    public static final int NAME_LINE_PREFIX_LENGTH = 26;

    public static final int NAME_LINE_NAME_LENGTH = 50;

    /**
     * Declared width of the customer-name <em>staging</em> group, which is never written. The emitted
     * record stops the name at the first pair of adjacent spaces and adds a closing tag this group does
     * not carry, so {@link #NAME_LINE_MAX_SIGNIFICANT_LENGTH} is the emitted bound. This figure is
     * published only because it is the declared width a reader will find in the source.
     */
    public static final int NAME_LINE_DECLARED_LENGTH = 76;

    public static final int NAME_LINE_DELIMITER_LENGTH = 2;

    public static final int NAME_LINE_CLOSING_TAG_LENGTH = 4;

    /**
     * Emitted upper bound of the customer-name record, reached only by a fifty-byte name containing no
     * pair of adjacent spaces. It is inside the record width, which is what guarantees that fitting the
     * composition to the record can only pad it and can never displace the closing tag.
     */
    public static final int NAME_LINE_MAX_SIGNIFICANT_LENGTH = 82;

    public static final int ADDRESS_WORK_LINE_LENGTH = 100;

    public static final int BASIC_DETAILS_WORK_LINE_LENGTH = 100;

    public static final int TRANSACTION_WORK_LINE_LENGTH = 100;

    /** The single ASCII byte used for padding: {@code 0x20}, the space. Never zero or NUL. */
    private static final byte ASCII_SPACE = 0x20;

    /**
     * Lowest printable US-ASCII code point. A carriage return or line feed below it would split the
     * record in the written file, because the batch writer supplies record separation.
     */
    private static final char FIRST_PRINTABLE_US_ASCII = 0x20;

    /** Highest printable US-ASCII code point; nothing above it has a single-byte image here. */
    private static final char LAST_PRINTABLE_US_ASCII = 0x7E;

    private static final char FIRST_ASCII_DIGIT = '0';

    private static final char LAST_ASCII_DIGIT = '9';

    /** The ASCII space, permitted in the account-number slot because the legacy move pads with it. */
    private static final char ASCII_SPACE_CHARACTER = ' ';

    public static final String HTML_L01 = fixed("HTML-L01", "<!DOCTYPE html>");

    public static final String HTML_L02 = fixed("HTML-L02", "<html lang=\"en\">");

    public static final String HTML_L03 = fixed("HTML-L03", "<head>");

    /** The declared character set is content; it does not change this record's own ASCII encoding. */
    public static final String HTML_L04 = fixed("HTML-L04", "<meta charset=\"utf-8\">");

    public static final String HTML_L05 = fixed("HTML-L05", "<title>HTML Table Layout</title>");

    public static final String HTML_L06 = fixed("HTML-L06", "</head>");

    public static final String HTML_L07 = fixed("HTML-L07", "<body style=\"margin:0px;\">");

    /**
     * Carries two space characters between the element name and its first attribute, verified against
     * the raw source bytes. The double space is emitted as-is: not collapsed, not normalised, not
     * corrected. Reassembled from two fragments split mid-token, rejoined with no space at the join.
     */
    public static final String HTML_L08 = fixed("HTML-L08",
            "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">");

    public static final String HTML_LTRS = fixed("HTML-LTRS", "<tr>");

    public static final String HTML_LTRE = fixed("HTML-LTRE", "</tr>");

    public static final String HTML_LTDS = fixed("HTML-LTDS", "<td>");

    public static final String HTML_LTDE = fixed("HTML-LTDE", "</td>");

    /**
     * Spanning cell in the estate's only eight-digit colour value, which carries an alpha channel; its
     * lower case is preserved. Spanning family, so no space before the background colour.
     */
    public static final String HTML_L10 = fixed("HTML-L10",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">");

    public static final String HTML_L15 = fixed("HTML-L15",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">");

    public static final String HTML_L16 = fixed("HTML-L16",
            "<p style=\"font-size:16px\">Bank of XYZ</p>");

    public static final String HTML_L17 = fixed("HTML-L17", "<p>410 Terry Ave N</p>");

    public static final String HTML_L18 = fixed("HTML-L18", "<p>Seattle WA 99999</p>");

    public static final String HTML_L22_35 = fixed("HTML-L22-35",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">");

    /**
     * Centred spanning cell. Spanning family, so no space before the background colour, but there is a
     * single space before the text alignment. Both spacings are reproduced exactly as found.
     */
    public static final String HTML_L30_42 = fixed("HTML-L30-42",
            "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">");

    public static final String HTML_L31 = fixed("HTML-L31",
            "<p style=\"font-size:16px\">Basic Details</p>");

    public static final String HTML_L43 = fixed("HTML-L43",
            "<p style=\"font-size:16px\">Transaction Summary</p>");

    /**
     * First of the explicit-width cells, and the exemplar for that family: a single space follows the
     * width, the padding and the colour, which differs from the spanning family above and is not
     * normalised toward it. Reassembled from two fragments split mid-token, rejoined with no space
     * added at the join and no hyphen changed. The remaining explicit-width cells follow this pattern.
     */
    public static final String HTML_L47 = fixed("HTML-L47",
            "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">");

    public static final String HTML_L48 = fixed("HTML-L48",
            "<p style=\"font-size:16px\">Tran ID</p>");

    public static final String HTML_L50 = fixed("HTML-L50",
            "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">");

    public static final String HTML_L51 = fixed("HTML-L51",
            "<p style=\"font-size:16px\">Tran Details</p>");

    public static final String HTML_L53 = fixed("HTML-L53",
            "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">");

    public static final String HTML_L54 = fixed("HTML-L54",
            "<p style=\"font-size:16px\">Amount</p>");

    public static final String HTML_L58 = fixed("HTML-L58",
            "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">");

    public static final String HTML_L61 = fixed("HTML-L61",
            "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">");

    public static final String HTML_L64 = fixed("HTML-L64",
            "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">");

    public static final String HTML_L75 = fixed("HTML-L75", "<h3>End of Statement</h3>");

    public static final String HTML_L78 = fixed("HTML-L78", "</table>");

    public static final String HTML_L79 = fixed("HTML-L79", "</body>");

    public static final String HTML_L80 = fixed("HTML-L80", "</html>");

    private static final String ACCOUNT_LINE_PREFIX = component(
            "account-number line leading literal",
            "<h3>Statement for Account Number: ",
            ACCOUNT_LINE_PREFIX_LENGTH);

    private static final String ACCOUNT_LINE_SUFFIX = component(
            "account-number line trailing literal",
            "</h3>",
            ACCOUNT_LINE_SUFFIX_LENGTH);

    private static final String NAME_LINE_PREFIX = component(
            "customer-name line leading literal",
            "<p style=\"font-size:16px\">",
            NAME_LINE_PREFIX_LENGTH);

    /**
     * The two literal space bytes the emitting paragraph writes after the name segment. The same two
     * bytes are also the delimiter that stops the name transfer, so one constant serves both roles and
     * the two can never drift apart.
     */
    private static final String NAME_LINE_DELIMITER = component(
            "customer-name line separator literal",
            "  ",
            NAME_LINE_DELIMITER_LENGTH);

    private static final String NAME_LINE_CLOSING_TAG = component(
            "customer-name line closing literal",
            "</p>",
            NAME_LINE_CLOSING_TAG_LENGTH);

    private static final String PARAGRAPH_OPEN = "<p>";

    private static final String PARAGRAPH_CLOSE = "</p>";

    /**
     * The two literal spaces the address work line appends after the transferred address. Declared
     * explicitly because they are a separate delimited sending item and are therefore appended
     * unconditionally: they are not padding and must not be trimmed away.
     */
    private static final String ADDRESS_LINE_TRAILING_SPACES = "  ";

    private static final String ADDRESS_LINE_DELIMITER = "  ";

    private static final List<String> FIXED_TEMPLATES = orderedTemplates();

    /**
     * Returns the invariant markup templates in legacy declaration order.
     *
     * @return an unmodifiable list of {@value #FIXED_TEMPLATE_COUNT} templates in declaration order
     */
    public static List<String> fixedTemplates() {
        return FIXED_TEMPLATES;
    }

    /**
     * Builds the account-number heading record: opening literal, the account identifier fitted to its
     * twenty-byte field, then the closing literal, padded out to the record width.
     *
     * @param  accountId the account identifier; may be shorter than its field, in which case the move
     *                   pads it, and is narrowed to ASCII digits and the ASCII space
     * @return one record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes, with no line terminator
     * @throws NullPointerException     if {@code accountId} is {@code null}
     * @throws IllegalArgumentException if {@code accountId} carries anything but digits and spaces
     */
    public static String accountNumberLine(final String accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        requirePrintableUsAscii(accountId, "account identifier");
        requireDigitsOrSpaces(accountId, "account identifier");
        final String group = ACCOUNT_LINE_PREFIX
                + fitToWidth(accountId, ACCOUNT_LINE_ACCOUNT_LENGTH)
                + ACCOUNT_LINE_SUFFIX;
        requireExactWidth("HTML-L11 composed group", group, ACCOUNT_LINE_DECLARED_LENGTH);
        return requireExactWidth("HTML-L11 record",
                fitToWidth(group, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Builds the customer-name heading record as the paragraph emits it, not as the staging group
     * declares it: the name is moved into its fifty-byte field as it arrives, the transfer then stops at
     * the first pair of adjacent spaces, and the delimiter and closing tag follow. A name with no adjacent
     * spaces reaches {@link #NAME_LINE_MAX_SIGNIFICANT_LENGTH}, which is inside the record.
     *
     * @param  customerName the composed customer name; must be printable US-ASCII
     * @return one record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes, with no line terminator
     * @throws NullPointerException     if {@code customerName} is {@code null}
     * @throws IllegalArgumentException if {@code customerName} is not printable US-ASCII
     */
    public static String customerNameLine(final String customerName) {
        Objects.requireNonNull(customerName, "customerName must not be null");
        requirePrintableUsAscii(customerName, "customer name");
        // The truncating move into the fifty-byte staging field, over the name exactly as supplied.
        final String nameField = fitToWidth(customerName, NAME_LINE_NAME_LENGTH);
        // The transfer then stops at the first pair of adjacent spaces, so the padding the move
        // introduced is dropped rather than emitted; the right pad below restores the record width.
        final String record = NAME_LINE_PREFIX + nameUpToDelimiter(nameField)
                + NAME_LINE_DELIMITER + NAME_LINE_CLOSING_TAG;
        return requireExactWidth("customer-name record",
                fitToWidth(record, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Returns the field content up to the first pair of adjacent spaces, which is where the legacy
     * transfer stops.
     */
    private static String nameUpToDelimiter(final String nameField) {
        final int delimiterAt = nameField.indexOf(NAME_LINE_DELIMITER);
        return delimiterAt < 0 ? nameField : nameField.substring(0, delimiterAt);
    }

    /**
     * Builds the address work line: opening paragraph tag, the address as it arrives, the two unconditional
     * delimiter spaces, then the closing tag, fitted to the record width.
     *
     * @param  addressLine the address text; must be printable US-ASCII
     * @return one record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes, with no line terminator
     * @throws NullPointerException     if {@code addressLine} is {@code null}
     * @throws IllegalArgumentException if {@code addressLine} is not printable US-ASCII
     */
    public static String addressWorkLine(final String addressLine) {
        Objects.requireNonNull(addressLine, "addressLine must not be null");
        requirePrintableUsAscii(addressLine, "address line");
        final String transferred = upToFirstDoubleSpace(addressLine);
        final String composed = PARAGRAPH_OPEN + transferred
                + ADDRESS_LINE_TRAILING_SPACES + PARAGRAPH_CLOSE;
        return requireExactWidth("HTML-ADDR-LN record",
                fitToWidth(composed, ADDRESS_WORK_LINE_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Builds the basic-details work line by wrapping the label and value, as they arrive, in the tags
     * the legacy statement writes, then fitting the result to the record width.
     *
     * @param  label the detail label; must be printable US-ASCII
     * @param  value the detail value; must be printable US-ASCII
     * @return one record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes, with no line terminator
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is not printable US-ASCII
     */
    public static String basicDetailsWorkLine(final String label, final String value) {
        Objects.requireNonNull(label, "label must not be null");
        requirePrintableUsAscii(label, "work-line label");
        Objects.requireNonNull(value, "value must not be null");
        requirePrintableUsAscii(value, "work-line value");
        final String composed = PARAGRAPH_OPEN + label + value + PARAGRAPH_CLOSE;
        return requireExactWidth("HTML-BSIC-LN record",
                fitToWidth(composed, BASIC_DETAILS_WORK_LINE_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Builds the transaction work line by wrapping the transaction text, as it arrives, in the tags
     * the legacy statement writes, then fitting the result to the record width.
     *
     * @param  value the transaction text; must be printable US-ASCII
     * @return one record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes, with no line terminator
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not printable US-ASCII
     */
    public static String transactionWorkLine(final String value) {
        Objects.requireNonNull(value, "value must not be null");
        requirePrintableUsAscii(value, "work-line value");
        final String composed = PARAGRAPH_OPEN + value + PARAGRAPH_CLOSE;
        return requireExactWidth("HTML-TRAN-LN record",
                fitToWidth(composed, TRANSACTION_WORK_LINE_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Fits already-composed content to one HTML output record, for the three free-form work lines that
     * are declared with no internal structure. Their content is composed by the statement-generation
     * service; their width belongs here, so the record width lives in exactly one place.
     *
     * <p>The semantics are those of a move into a hundred-byte alphanumeric field: shorter content is
     * padded on the right with ASCII spaces, longer content is truncated at the record width in encoded
     * bytes. Nothing is encoded and no line terminator is added.</p>
     *
     * <p>This is a guarded fitter rather than an unchecked sink: content is validated to printable
     * US-ASCII first, so the C0 controls, the delete character and everything outside US-ASCII are
     * refused rather than encoded to a substitute byte. That guard is what keeps the framing intact,
     * because the batch writer supplies record separation and an embedded carriage return or line feed
     * would split one logical record into two and desynchronise every record after it. Markup
     * characters are legitimate content here and pass through unchanged, so this method validates the
     * character set and the width and never the markup structure.</p>
     *
     * <p>It is a framing primitive and nothing else: it receives content that is already composed, so it
     * cannot tell a legitimate paragraph literal from any other text and does not try to. Field values
     * belong in the three named composers, each of which owns its own paragraph tags.</p>
     *
     * @param  content the composed work-line content; must be printable US-ASCII and may be empty
     * @return one record of exactly {@value #HTML_RECORD_LENGTH} encoded bytes, with no line terminator
     * @throws NullPointerException     if {@code content} is {@code null}
     * @throws IllegalArgumentException if {@code content} carries a character that is not printable
     *                                  US-ASCII
     * @throws IllegalStateException    if the fitted record is not exactly the record width
     */
    public static String workLine(final String content) {
        Objects.requireNonNull(content, "content must not be null");
        requirePrintableUsAscii(content, "work-line content");
        return requireExactWidth("HTML free-form work line",
                fitToWidth(content, HTML_RECORD_LENGTH), HTML_RECORD_LENGTH);
    }

    /**
     * Registers one invariant template in declaration order and returns it, so that the published order
     * cannot drift from the declaration order.
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
     * Returns a composed-line component after checking it against its declared width, so a mistyped
     * literal fails at class initialisation rather than in an emitted record.
     */
    private static String component(final String description, final String literal, final int width) {
        return requireExactWidth(description, literal, width);
    }

    /** Returns the registered templates in declaration order, for {@link #fixedTemplates()}. */
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
     * Rejects any value that is not printable US-ASCII. Refusal never alters a byte, so this guard
     * cannot disturb parity.
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
     * Narrows the account-number slot to ASCII digits and the ASCII space, the space being permitted
     * because the legacy move pads with it.
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
     * Fits a value to an exact width with fixed-width move semantics, working entirely in encoded
     * bytes: the value is copied into a space-filled image of exactly {@code width} bytes, so a longer
     * value is truncated at a byte boundary and a shorter one is space-padded on the right. Bytes
     * rather than characters is what makes the result exact, because the record is a byte image and a
     * character count cannot describe one.
     *
     * <p>The cut is at a byte boundary and nothing follows it. A COBOL {@code MOVE} into a shorter
     * alphanumeric field truncates on the right and does nothing else, so no fragment is inspected, no
     * byte behind the cut is reconsidered and no content is blanked back to a delimiter. Any refinement
     * of the truncation would be this module's invention rather than the legacy's behaviour.</p>
     *
     * @param  value the value to fit; must not be {@code null}
     * @param  width the exact field width in encoded bytes; must not be negative
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
