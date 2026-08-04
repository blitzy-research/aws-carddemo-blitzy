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
package com.carddemo.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.util.StatementHtmlTemplates;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Executes the committed 100-byte HTML statement oracle, {@code fixtures/expected/statement-html.txt}
 * so that the fourth golden file is a build-enforced contract rather than a correct but inert artefact.
 *
 * <h2>Why this class exists separately</h2>
 * The sibling {@link ExpectedOutputFixtureContractTest} covers the 430-byte reject dataset, the
 * 133-byte transaction report and the 80-byte statement. This class covers the remaining oracle - the
 * HTML statement stream emitted by {@code app/cbl/CBSTM03A.CBL} - because that stream is structurally
 * unlike the other three: it is not a flat sequence of one record type but fifty concatenated complete
 * HTML documents, each assembled from thirty-four invariant literals interleaved with ten composed
 * lines, and its correctness is as much about record ORDER as about record content.
 *
 * <h2>Which side is which, stated explicitly</h2>
 * <strong>The expected side is always the committed fixture bytes, and nothing else.</strong> No
 * expected value below is produced by calling the code under test, no output is snapshotted and the
 * fixture is never regenerated. {@link StatementHtmlTemplates} appears only on the actual side. Where a
 * value has to be recovered in order to ask the emitter to lay it out - an account identifier, a name,
 * an address, a balance, a transaction description - it is sliced out of the committed bytes by this
 * class's own field arithmetic, which shares no code with the emitter.
 *
 * <h2>What is proved, and how far it goes</h2>
 * <ul>
 *   <li><strong>The record contract.</strong> Every record is exactly one hundred encoded bytes, closed
 *       by one line feed and by no carriage return, with no header, comment or metadata prefix byte;
 *       every trailing space is contractual and is compared.</li>
 *   <li><strong>The document structure.</strong> Each of the fifty documents is reassembled from the
 *       production templates plus the fixture's own composed lines and compared record by record, so a
 *       reordered, missing or duplicated record fails. The write order is 22 header records, 34
 *       name/address/detail records, 11 records per transaction and an 8-record tail.</li>
 *   <li><strong>The four malformed literals.</strong> The double space inside the table opener, the
 *       missing space before {@code background-color} in the four {@code colspan} variants against its
 *       presence in the six width variants, the table-data opener that is declared and never emitted,
 *       and the document scaffold that repeats once per account rather than once per file. Each is
 *       asserted as the contract it is, because a formatter that "fixed" any of them would fail byte
 *       parity.</li>
 *   <li><strong>Every composed line, re-emitted.</strong> The account heading, the name line, the three
 *       address lines, the three basic-detail lines and the three lines per transaction are all
 *       re-emitted through the production template class and compared on raw bytes.</li>
 *   <li><strong>Cross-oracle agreement.</strong> Account identifier, balance, credit score, name,
 *       addresses and the ordered transaction triples are compared field by field against the 80-byte
 *       statement oracle, including the one place the two are REQUIRED to differ.</li>
 * </ul>
 *
 * <h2>The one divergence, pinned rather than smoothed</h2>
 * The legacy program encoded nothing, so the committed oracle carries markup-significant characters
 * raw - twelve apostrophes, in one customer surname and eleven transaction descriptions - and carries
 * no character reference at all. The production emitter deliberately escapes those characters, which is
 * a security decision documented on {@link StatementHtmlTemplates#escapeText(String)} and not a parity
 * defect. Both facts are asserted here: the fixture stays raw, and for exactly those records the
 * emitter's output is required to equal the fixture record with the value replaced by its reference
 * form. Nothing is relaxed on either side, and a change to either behaviour fails this class.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The emitting program is
 * {@code app/cbl/CBSTM03A.CBL}, whose file description declares the record as a hundred-byte
 * alphanumeric image; the job stream {@code app/jcl/CREASTMT.JCL} declares the same data definition at
 * eighty bytes in its delete step and at one hundred in the step that actually runs the program, and
 * one hundred is what the program writes. No legacy source line is transcribed here - only widths,
 * offsets, counts and emitted output literals, which are contract rather than source.
 */
@DisplayName("Expected-output fixtures: the 100-byte HTML statement oracle, executed")
final class ExpectedHtmlStatementFixtureContractTest {

    /** Classpath location of the committed HTML statement oracle. */
    private static final String HTML_FIXTURE = "/fixtures/expected/statement-html.txt";

    /** Classpath location of the committed 80-byte statement oracle, for cross-oracle agreement. */
    private static final String TEXT_FIXTURE = "/fixtures/expected/statement.txt";

    /** Encoded width of one HTML statement record. */
    private static final int HTML_WIDTH = StatementHtmlTemplates.HTML_RECORD_LENGTH;

    /** Encoded width of one plain-text statement record. */
    private static final int TEXT_WIDTH = 80;

    /** Record separator: one line feed per record, as a fixed-block dataset is carried in a file. */
    private static final byte LINE_FEED = 0x0A;

    /** Carriage return, which must never appear. */
    private static final byte CARRIAGE_RETURN = 0x0D;

    /** Documents in the fixture: the scaffold repeats once per account. */
    private static final int DOCUMENTS = 50;

    /** Transaction blocks across the whole fixture. */
    private static final int TRANSACTION_BLOCKS = 312;

    /** Records the account-heading paragraph writes. */
    private static final int HEADER_RECORDS = 22;

    /** Records the name, address and basic-detail paragraph writes. */
    private static final int DETAIL_RECORDS = 34;

    /** Records the transaction paragraph writes for one transaction. */
    private static final int TRANSACTION_RECORDS = 11;

    /** Records the closing paragraph writes. */
    private static final int TAIL_RECORDS = 8;

    /** Records in a document that carries no transaction at all. */
    private static final int FIXED_RECORDS_PER_DOCUMENT =
            HEADER_RECORDS + DETAIL_RECORDS + TAIL_RECORDS;

    /** Zero-based position of the account-heading record inside a document. */
    private static final int ACCOUNT_HEADING_AT = 10;

    /** Zero-based position of the customer-name record inside a document. */
    private static final int NAME_AT = HEADER_RECORDS;

    /** Zero-based position of the first of the three address records inside a document. */
    private static final int FIRST_ADDRESS_AT = HEADER_RECORDS + 1;

    /** Address records per document. */
    private static final int ADDRESS_RECORDS = 3;

    /** Zero-based position of the first of the three basic-detail records inside a document. */
    private static final int FIRST_DETAIL_AT = HEADER_RECORDS + 13;

    /** Zero-based position of the first transaction block inside a document. */
    private static final int FIRST_TRANSACTION_AT = HEADER_RECORDS + DETAIL_RECORDS;

    /** Width of the account identifier slot in the heading and in the basic-detail line. */
    private static final int ACCOUNT_FIELD_WIDTH = StatementHtmlTemplates.ACCOUNT_LINE_ACCOUNT_LENGTH;

    /** Offset of the account identifier slot inside the account-heading record. */
    private static final int ACCOUNT_FIELD_AT = StatementHtmlTemplates.ACCOUNT_LINE_PREFIX_LENGTH;

    /** Offset at which the customer name begins inside the name record. */
    private static final int NAME_FIELD_AT = StatementHtmlTemplates.NAME_LINE_PREFIX_LENGTH;

    /** The opening paragraph tag every composed line other than the name line starts with. */
    private static final String PARAGRAPH_OPEN = "<p>";

    /** The closing paragraph tag every composed line ends with. */
    private static final String PARAGRAPH_CLOSE = "</p>";

    /** The two spaces the name and address lines re-append after the delimited transfer. */
    private static final String TRANSFER_DELIMITER = "  ";

    /** Label of the account-identifier detail line, without its opening tag. */
    private static final String ACCOUNT_LABEL = "Account ID         : ";

    /** Label of the current-balance detail line, without its opening tag. */
    private static final String BALANCE_LABEL = "Current Balance    : ";

    /** Label of the credit-score detail line, without its opening tag. */
    private static final String SCORE_LABEL = "FICO Score         : ";

    /** Width of the edited current-balance field: nine digits, a point, two digits and a sign slot. */
    private static final int BALANCE_FIELD_WIDTH = 13;

    /** Width of the credit-score slot. */
    private static final int SCORE_FIELD_WIDTH = 20;

    /** Width of the transaction identifier slot. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** Width of the transaction description slot. */
    private static final int TRANSACTION_DESCRIPTION_WIDTH = 49;

    /** Width of the edited transaction amount slot. */
    private static final int TRANSACTION_AMOUNT_WIDTH = 13;

    /** Offset of the composed identifier record inside a transaction block. */
    private static final int BLOCK_ID_AT = 2;

    /** Offset of the composed description record inside a transaction block. */
    private static final int BLOCK_DESCRIPTION_AT = 5;

    /** Offset of the composed amount record inside a transaction block. */
    private static final int BLOCK_AMOUNT_AT = 8;

    /**
     * Composed records whose recovered value carries a markup-significant character, so the emitter
     * escapes it while the committed oracle - faithful to a program that encoded nothing - does not.
     * One customer surname and eleven transaction descriptions carry an apostrophe.
     */
    private static final int ESCAPED_DIVERGENCES = 12;

    /** The five character references the emitter can introduce and the legacy program never emitted. */
    private static final List<String> CHARACTER_REFERENCES =
            List.of("&amp;", "&lt;", "&gt;", "&quot;", "&#39;");

    /** Classpath directory the four fixed-width oracles are published in, flat. */
    private static final String ORACLE_DIRECTORY = "/fixtures/expected/";

    /**
     * The four oracle names and the record width each one publishes. The set is closed on purpose: a
     * fifth oracle, a renamed one or an aliased duplicate changes the published contract and has to be
     * made here deliberately rather than arrive unnoticed.
     */
    private static final Map<String, Integer> ORACLE_WIDTHS = Map.of(
            "statement.txt", TEXT_WIDTH,
            "statement-html.txt", HTML_WIDTH,
            "transaction-report.txt", 133,
            "daily-reject.txt", 430);

    /** Filesystem root of the module's committed fixtures, for the aliased-duplicate search. */
    private static final String FIXTURE_ROOT = "src/test/resources/fixtures";

    // ---------------------------------------------------------------------------------------------
    // Fixture access. Nothing here calls the code under test.
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads a committed fixture whole.
     *
     * @param  resource the classpath location
     * @return the fixture's bytes
     * @throws IOException if the fixture cannot be read
     */
    private static byte[] fixtureBytes(final String resource) throws IOException {
        try (InputStream stream =
                ExpectedHtmlStatementFixtureContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("%s must be on the test classpath", resource).isNotNull();
            return stream.readAllBytes();
        }
    }

    /**
     * Splits a line-feed separated fixed-width fixture into records, asserting the stride as it goes so
     * that a lost or spurious terminator cannot pass.
     *
     * @param  resource the classpath location
     * @param  width    the record width in encoded bytes
     * @return the records, each decoded as US-ASCII and exactly {@code width} characters long
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> records(final String resource, final int width) throws IOException {
        final byte[] all = fixtureBytes(resource);
        final int stride = width + 1;
        assertThat(all.length % stride)
                .as("%s must hold whole %d-byte records each closed by one line feed", resource, width)
                .isZero();
        final List<String> out = new ArrayList<>(all.length / stride);
        for (int start = 0; start < all.length; start += stride) {
            assertThat(all[start + width])
                    .as("record at byte %d of %s must be closed by one line feed", start, resource)
                    .isEqualTo(LINE_FEED);
            out.add(new String(all, start, width, StandardCharsets.US_ASCII));
        }
        return out;
    }

    /**
     * Splits the fixture into its per-account documents on the document-type declaration, which the
     * emitting program writes once per account.
     *
     * @param  all every record of the fixture, in order
     * @return the documents, in fixture order
     */
    private static List<List<String>> documents(final List<String> all) {
        final List<Integer> starts = new ArrayList<>();
        for (int index = 0; index < all.size(); index++) {
            if (all.get(index).equals(StatementHtmlTemplates.HTML_L01)) {
                starts.add(index);
            }
        }
        assertThat(starts).as("the scaffold repeats once per account").hasSize(DOCUMENTS);
        assertThat(starts.get(0)).as("the fixture must open with a document, with no prefix").isZero();
        final List<List<String>> out = new ArrayList<>(starts.size());
        for (int document = 0; document < starts.size(); document++) {
            final int end = document + 1 < starts.size() ? starts.get(document + 1) : all.size();
            out.add(List.copyOf(all.subList(starts.get(document), end)));
        }
        return out;
    }

    /**
     * Returns a document's transaction count, derived from its record count rather than assumed.
     *
     * @param  document one document's records
     * @return the number of transaction blocks the document carries
     */
    private static int transactionCount(final List<String> document) {
        final int variable = document.size() - FIXED_RECORDS_PER_DOCUMENT;
        assertThat(variable)
                .as("a document holds %d fixed records plus a whole number of %d-record blocks",
                        FIXED_RECORDS_PER_DOCUMENT, TRANSACTION_RECORDS)
                .isNotNegative();
        assertThat(variable % TRANSACTION_RECORDS)
                .as("a document's variable part must be whole transaction blocks")
                .isZero();
        return variable / TRANSACTION_RECORDS;
    }

    /**
     * Fits a value to the record width with fixed-width move semantics - right-pad with spaces, or
     * truncate. Deliberately reimplemented here rather than borrowed, so the comparison is independent.
     *
     * @param  value the composed content
     * @return the content as one record-width image
     */
    private static String fit(final String value) {
        if (value.length() >= HTML_WIDTH) {
            return value.substring(0, HTML_WIDTH);
        }
        final StringBuilder image = new StringBuilder(value);
        while (image.length() < HTML_WIDTH) {
            image.append(' ');
        }
        return image.toString();
    }

    /**
     * Takes a field out of a committed record.
     *
     * @param  record the record
     * @param  offset zero-based offset of the field
     * @param  width  width of the field
     * @return the field's bytes as text, untrimmed
     */
    private static String slice(final String record, final int offset, final int width) {
        return record.substring(offset, offset + width);
    }

    /**
     * Recovers the value a delimited-transfer line carries: everything between the opening prefix and
     * the two delimiter spaces the statement re-appends before the closing tag.
     *
     * @param  record the committed record
     * @param  prefix width of the opening prefix
     * @return the transferred value, with no padding
     */
    private static String delimitedValue(final String record, final int prefix) {
        final String tail = TRANSFER_DELIMITER + PARAGRAPH_CLOSE;
        final int closesAt = record.indexOf(tail, prefix);
        assertThat(closesAt)
                .as("a delimited line must close with two spaces and the closing tag: |%s|", record)
                .isNotNegative();
        return record.substring(prefix, closesAt);
    }

    /**
     * Returns the content up to, but not including, the first pair of adjacent spaces - which is where
     * the legacy delimited transfer stops - or the whole value when there is no such pair.
     *
     * @param  field the sending field, padded to its declared width
     * @return the characters the transfer would move
     */
    private static String upToFirstDoubleSpace(final String field) {
        final int delimiterAt = field.indexOf(TRANSFER_DELIMITER);
        return delimiterAt < 0 ? field : field.substring(0, delimiterAt);
    }

    /**
     * Reassembles one document from the production templates plus the fixture's own composed records,
     * so that comparing it to the committed document proves the write order and every invariant literal
     * at once. The composed records are taken from the committed bytes, never generated.
     *
     * @param  document the committed document, the source of its own composed records
     * @return the document as the write order says it must be laid out
     */
    private static List<String> reassemble(final List<String> document) {
        final int transactions = transactionCount(document);
        final List<String> expected = new ArrayList<>(document.size());
        // The account-heading paragraph.
        expected.add(StatementHtmlTemplates.HTML_L01);
        expected.add(StatementHtmlTemplates.HTML_L02);
        expected.add(StatementHtmlTemplates.HTML_L03);
        expected.add(StatementHtmlTemplates.HTML_L04);
        expected.add(StatementHtmlTemplates.HTML_L05);
        expected.add(StatementHtmlTemplates.HTML_L06);
        expected.add(StatementHtmlTemplates.HTML_L07);
        expected.add(StatementHtmlTemplates.HTML_L08);
        expected.add(StatementHtmlTemplates.HTML_LTRS);
        expected.add(StatementHtmlTemplates.HTML_L10);
        expected.add(document.get(ACCOUNT_HEADING_AT));
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_LTRE);
        expected.add(StatementHtmlTemplates.HTML_LTRS);
        expected.add(StatementHtmlTemplates.HTML_L15);
        expected.add(StatementHtmlTemplates.HTML_L16);
        expected.add(StatementHtmlTemplates.HTML_L17);
        expected.add(StatementHtmlTemplates.HTML_L18);
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_LTRE);
        expected.add(StatementHtmlTemplates.HTML_LTRS);
        expected.add(StatementHtmlTemplates.HTML_L22_35);
        // The name, address and basic-detail paragraph.
        expected.add(document.get(NAME_AT));
        for (int address = 0; address < ADDRESS_RECORDS; address++) {
            expected.add(document.get(FIRST_ADDRESS_AT + address));
        }
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_LTRE);
        expected.add(StatementHtmlTemplates.HTML_LTRS);
        expected.add(StatementHtmlTemplates.HTML_L30_42);
        expected.add(StatementHtmlTemplates.HTML_L31);
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_LTRE);
        expected.add(StatementHtmlTemplates.HTML_LTRS);
        expected.add(StatementHtmlTemplates.HTML_L22_35);
        for (int detail = 0; detail < ADDRESS_RECORDS; detail++) {
            expected.add(document.get(FIRST_DETAIL_AT + detail));
        }
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_LTRE);
        expected.add(StatementHtmlTemplates.HTML_LTRS);
        expected.add(StatementHtmlTemplates.HTML_L30_42);
        expected.add(StatementHtmlTemplates.HTML_L43);
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_LTRE);
        expected.add(StatementHtmlTemplates.HTML_LTRS);
        expected.add(StatementHtmlTemplates.HTML_L47);
        expected.add(StatementHtmlTemplates.HTML_L48);
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_L50);
        expected.add(StatementHtmlTemplates.HTML_L51);
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_L53);
        expected.add(StatementHtmlTemplates.HTML_L54);
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_LTRE);
        // One block per transaction.
        for (int block = 0; block < transactions; block++) {
            final int base = FIRST_TRANSACTION_AT + block * TRANSACTION_RECORDS;
            expected.add(StatementHtmlTemplates.HTML_LTRS);
            expected.add(StatementHtmlTemplates.HTML_L58);
            expected.add(document.get(base + BLOCK_ID_AT));
            expected.add(StatementHtmlTemplates.HTML_LTDE);
            expected.add(StatementHtmlTemplates.HTML_L61);
            expected.add(document.get(base + BLOCK_DESCRIPTION_AT));
            expected.add(StatementHtmlTemplates.HTML_LTDE);
            expected.add(StatementHtmlTemplates.HTML_L64);
            expected.add(document.get(base + BLOCK_AMOUNT_AT));
            expected.add(StatementHtmlTemplates.HTML_LTDE);
            expected.add(StatementHtmlTemplates.HTML_LTRE);
        }
        // The closing paragraph.
        expected.add(StatementHtmlTemplates.HTML_LTRS);
        expected.add(StatementHtmlTemplates.HTML_L10);
        expected.add(StatementHtmlTemplates.HTML_L75);
        expected.add(StatementHtmlTemplates.HTML_LTDE);
        expected.add(StatementHtmlTemplates.HTML_LTRE);
        expected.add(StatementHtmlTemplates.HTML_L78);
        expected.add(StatementHtmlTemplates.HTML_L79);
        expected.add(StatementHtmlTemplates.HTML_L80);
        return expected;
    }

    /**
     * Asserts one composed record against the production emitter's output for the value the record
     * carries, allowing for - and requiring - the documented escaping divergence.
     *
     * @param  committed the committed record
     * @param  value     the value recovered from that record
     * @param  emitted   what the production emitter produced for that value
     * @param  prefix    everything the record holds before the value
     * @param  suffix    everything the record holds after the value
     * @return {@code true} when the value forced the escaping divergence
     */
    private static boolean assertReemitted(final String committed, final String value,
            final String emitted, final String prefix, final String suffix) {
        final String escaped = StatementHtmlTemplates.escapeText(value);
        if (escaped.equals(value)) {
            assertThat(emitted)
                    .as("the emitter must reproduce the committed record exactly: |%s|", committed)
                    .isEqualTo(committed);
            return false;
        }
        assertThat(committed)
                .as("the committed oracle carries the value raw, as the legacy program wrote it")
                .isEqualTo(fit(prefix + value + suffix));
        assertThat(emitted)
                .as("the emitter escapes the value and changes nothing else: |%s|", committed)
                .isEqualTo(fit(prefix + escaped + suffix));
        return true;
    }

    @Nested
    @DisplayName("the record contract: one hundred bytes, one line feed, no prefix byte")
    final class TheRecordContract {

        @Test
        @DisplayName("every record is exactly 100 bytes, closed by one line feed and no carriage "
                + "return, with no trailing blank record")
        void everyRecordIsExactlyOneHundredBytes() throws IOException {
            final byte[] all = fixtureBytes(HTML_FIXTURE);
            final List<String> committed = records(HTML_FIXTURE, HTML_WIDTH);

            assertThat(committed)
                    .as("the fixture carries %d documents whose fixed part is %d records, plus %d "
                            + "transaction blocks of %d records",
                            DOCUMENTS, FIXED_RECORDS_PER_DOCUMENT, TRANSACTION_BLOCKS,
                            TRANSACTION_RECORDS)
                    .hasSize(DOCUMENTS * FIXED_RECORDS_PER_DOCUMENT
                            + TRANSACTION_BLOCKS * TRANSACTION_RECORDS);
            assertThat(all)
                    .as("the byte count is the record count times the record stride")
                    .hasSize(committed.size() * (HTML_WIDTH + 1));
            assertThat(committed).allSatisfy(record -> assertThat(record.getBytes(
                    StandardCharsets.US_ASCII)).hasSize(HTML_WIDTH));
            assertThat(new String(all, StandardCharsets.US_ASCII).indexOf(CARRIAGE_RETURN))
                    .as("the oracle is line-feed only; a carriage return would widen every record")
                    .isEqualTo(-1);
            assertThat(all[all.length - 1])
                    .as("the last record is closed, and no blank record follows")
                    .isEqualTo(LINE_FEED);
            assertThat(committed.get(committed.size() - 1))
                    .as("the fixture ends on the closing document-type record")
                    .isEqualTo(StatementHtmlTemplates.HTML_L80);
        }

        @Test
        @DisplayName("the fixture carries no header, comment, ruler, provenance or metadata prefix "
                + "byte of any kind")
        void theFixtureCarriesNoPrefixByte() throws IOException {
            final List<String> committed = records(HTML_FIXTURE, HTML_WIDTH);
            final String whole = String.join("", committed);

            assertThat(committed.get(0))
                    .as("the very first record is the document-type declaration")
                    .isEqualTo(StatementHtmlTemplates.HTML_L01);
            assertThat(whole)
                    .as("an HTML comment would be a licence banner in disguise and would shift "
                            + "every record")
                    .doesNotContain("<!--")
                    .doesNotContain("Copyright")
                    .doesNotContain("Licensed under")
                    .doesNotContain("7756d895ffeb65f7ea72aaa609e356d9899afcec")
                    .doesNotContain("CardDemo_v1.0-15-g27d6c6f-68");
            assertThat(whole.chars().allMatch(character -> character >= 0x20 && character <= 0x7E))
                    .as("every byte is printable US-ASCII, so no byte can come from a locale or a "
                            + "platform default charset")
                    .isTrue();
        }

        @Test
        @DisplayName("the record width is the program's file description, not the delete step's")
        void theRecordWidthIsOneHundred() {
            assertThat(HTML_WIDTH)
                    .as("the job stream declares this data definition at 80 in its delete step and "
                            + "at 100 in the step that runs the program, which writes 100")
                    .isEqualTo(100)
                    .isNotEqualTo(TEXT_WIDTH);
        }

        @Test
        @DisplayName("the four oracles are published under exactly these four names, flat, each at its "
                + "own width, and the HTML oracle has no aliased or duplicated copy")
        void theFourOraclesArePublishedUnderExactlyTheseNames() throws IOException {
            for (final Map.Entry<String, Integer> oracle : ORACLE_WIDTHS.entrySet()) {
                final String resource = ORACLE_DIRECTORY + oracle.getKey();
                assertThat(records(resource, oracle.getValue()))
                        .as("%s must be present, flat under the expected directory, and hold whole "
                                + "%d-byte records", resource, oracle.getValue())
                        .isNotEmpty();
            }

            // This oracle's content is HTML but its name must end .txt, because the registered scope
            // pattern for these artefacts is a text extension. An alias under an HTML extension - or a
            // second copy anywhere beneath the fixtures - would let a future consumer bind to the wrong
            // bytes while every assertion above still passed against the right ones.
            final Path root = Paths.get(FIXTURE_ROOT);
            assertThat(Files.isDirectory(root)).as("%s must exist", FIXTURE_ROOT).isTrue();
            final List<Path> found;
            try (Stream<Path> tree = Files.walk(root)) {
                found = tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().contains("statement"))
                        .sorted()
                        .toList();
            }
            assertThat(found)
                    .as("exactly two statement oracles exist beneath the fixtures, both flat in the "
                            + "expected directory, and neither is an HTML-extension alias")
                    .hasSize(2)
                    .allSatisfy(path -> {
                        assertThat(path.getFileName().toString()).endsWith(".txt");
                        assertThat(path.getParent().getFileName().toString()).isEqualTo("expected");
                    })
                    .extracting(path -> path.getFileName().toString())
                    .containsExactlyInAnyOrder("statement.txt", "statement-html.txt");
        }
    }

    @Nested
    @DisplayName("the document structure: fifty concatenated documents in strict write order")
    final class TheDocumentStructure {

        @Test
        @DisplayName("the complete scaffold repeats once per account, so the file holds 50 "
                + "concatenated complete documents rather than one")
        void theScaffoldRepeatsOncePerAccount() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));

            assertThat(all).hasSize(DOCUMENTS);
            assertThat(all).allSatisfy(document -> {
                assertThat(document.get(0)).isEqualTo(StatementHtmlTemplates.HTML_L01);
                assertThat(document.get(document.size() - 3))
                        .isEqualTo(StatementHtmlTemplates.HTML_L78);
                assertThat(document.get(document.size() - 2))
                        .isEqualTo(StatementHtmlTemplates.HTML_L79);
                assertThat(document.get(document.size() - 1))
                        .isEqualTo(StatementHtmlTemplates.HTML_L80);
            });
        }

        @Test
        @DisplayName("every document lays out 22 heading records, 34 detail records, 11 records per "
                + "transaction and an 8-record tail, in that exact order")
        void everyDocumentFollowsTheWriteOrder() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));
            int transactions = 0;

            for (int index = 0; index < all.size(); index++) {
                final List<String> document = all.get(index);
                final int count = transactionCount(document);
                transactions += count;
                assertThat(document)
                        .as("document %d carries %d transactions", index, count)
                        .hasSize(FIXED_RECORDS_PER_DOCUMENT + count * TRANSACTION_RECORDS)
                        .containsExactlyElementsOf(reassemble(document));
            }
            assertThat(transactions)
                    .as("the transaction blocks across the whole oracle")
                    .isEqualTo(TRANSACTION_BLOCKS);
        }

        @Test
        @DisplayName("the per-document template census follows the source: the three grouped-cell "
                + "literals appear twice each and the row and cell tags scale with the transactions")
        void thePerDocumentTemplateCensusHolds() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));

            for (int index = 0; index < all.size(); index++) {
                final List<String> document = all.get(index);
                final int count = transactionCount(document);
                final Map<String, Integer> census = new LinkedHashMap<>();
                for (final String record : document) {
                    census.merge(record, 1, Integer::sum);
                }
                assertThat(census.getOrDefault(StatementHtmlTemplates.HTML_L10, 0))
                        .as("document %d", index).isEqualTo(2);
                assertThat(census.getOrDefault(StatementHtmlTemplates.HTML_L22_35, 0))
                        .as("document %d", index).isEqualTo(2);
                assertThat(census.getOrDefault(StatementHtmlTemplates.HTML_L30_42, 0))
                        .as("document %d", index).isEqualTo(2);
                assertThat(census.getOrDefault(StatementHtmlTemplates.HTML_LTRS, 0))
                        .as("document %d opens 3 rows in the heading, 4 in the detail block, one "
                                + "per transaction and one in the tail", index)
                        .isEqualTo(8 + count);
                assertThat(census.getOrDefault(StatementHtmlTemplates.HTML_LTRE, 0))
                        .as("document %d closes as many rows as it opens", index)
                        .isEqualTo(8 + count);
                assertThat(census.getOrDefault(StatementHtmlTemplates.HTML_LTDE, 0))
                        .as("document %d closes 2 cells in the heading, 7 in the detail block, 3 "
                                + "per transaction and one in the tail", index)
                        .isEqualTo(10 + 3 * count);
            }
        }
    }

    @Nested
    @DisplayName("the malformed literals, reproduced rather than repaired")
    final class TheMalformedLiterals {

        @Test
        @DisplayName("the table opener keeps its double space, which is the canary for any formatter "
                + "having touched the oracle")
        void theTableOpenerKeepsItsDoubleSpace() throws IOException {
            final List<String> committed = records(HTML_FIXTURE, HTML_WIDTH);

            assertThat(StatementHtmlTemplates.HTML_L08)
                    .as("two spaces between the element name and the first attribute, not one")
                    .startsWith("<table  align=");
            assertThat(committed.stream()
                    .filter(record -> record.equals(StatementHtmlTemplates.HTML_L08)).count())
                    .as("once per document")
                    .isEqualTo(DOCUMENTS);
            assertThat(committed).noneSatisfy(record ->
                    assertThat(record).startsWith("<table align="));
        }

        @Test
        @DisplayName("the four grouped-cell literals omit the space before the colour property and "
                + "the six proportional-cell literals keep it; neither is normalised")
        void theBackgroundColourSpacingIsInconsistentOnPurpose() throws IOException {
            final List<String> committed = records(HTML_FIXTURE, HTML_WIDTH);
            final List<String> grouped = List.of(
                    StatementHtmlTemplates.HTML_L10, StatementHtmlTemplates.HTML_L15,
                    StatementHtmlTemplates.HTML_L22_35, StatementHtmlTemplates.HTML_L30_42);
            final List<String> proportional = List.of(
                    StatementHtmlTemplates.HTML_L47, StatementHtmlTemplates.HTML_L50,
                    StatementHtmlTemplates.HTML_L53, StatementHtmlTemplates.HTML_L58,
                    StatementHtmlTemplates.HTML_L61, StatementHtmlTemplates.HTML_L64);

            assertThat(grouped).hasSize(4).allSatisfy(literal -> assertThat(literal)
                    .contains("5px;background-color").doesNotContain("5px; background-color"));
            assertThat(proportional).hasSize(6).allSatisfy(literal -> assertThat(literal)
                    .contains("5px; background-color").doesNotContain("5px;background-color"));
            assertThat(committed.stream()
                    .filter(record -> record.contains("5px;background-color")).count())
                    .as("two grouped headings, one bank block, two panel headings and two centred "
                            + "headings per document")
                    .isEqualTo(7L * DOCUMENTS);
            assertThat(committed.stream()
                    .filter(record -> record.contains("5px; background-color")).count())
                    .as("three column headings per document and three cells per transaction")
                    .isEqualTo(3L * DOCUMENTS + 3L * TRANSACTION_BLOCKS);
        }

        @Test
        @DisplayName("the bare cell opener is declared but never emitted, so no record is one")
        void theBareCellOpenerIsNeverEmitted() throws IOException {
            final List<String> committed = records(HTML_FIXTURE, HTML_WIDTH);

            assertThat(StatementHtmlTemplates.HTML_LTDS)
                    .as("the template exists, because the legacy program declares it")
                    .isEqualTo(fit("<td>"));
            assertThat(committed)
                    .as("and is set nowhere, so the oracle must not carry it")
                    .doesNotContain(StatementHtmlTemplates.HTML_LTDS);
            assertThat(StatementHtmlTemplates.fixedTemplates())
                    .as("all thirty-four invariant templates are published, including the dead one")
                    .hasSize(StatementHtmlTemplates.FIXED_TEMPLATE_COUNT)
                    .contains(StatementHtmlTemplates.HTML_LTDS);
        }

        @Test
        @DisplayName("the name line comes from the delimited transfer and closes its paragraph, so "
                + "the 76-byte staging group with no closing tag is never emitted")
        void theNameLineIsNotTheStagingGroup() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));

            assertThat(all).allSatisfy(document -> {
                final String record = document.get(NAME_AT);
                assertThat(record).startsWith("<p style=\"font-size:16px\">");
                assertThat(record.stripTrailing())
                        .as("the staging group carries no closing tag; the emitted record does")
                        .endsWith(TRANSFER_DELIMITER + PARAGRAPH_CLOSE);
                assertThat(record.stripTrailing().length())
                        .as("and it is never the 76-byte staging width")
                        .isNotEqualTo(StatementHtmlTemplates.NAME_LINE_DECLARED_LENGTH);
            });
        }

        @Test
        @DisplayName("an amount below one unit loses its entire integer part to zero suppression, and "
                + "an interest amount that truncates to zero still emits its transaction row")
        void zeroSuppressionRemovesTheWholeIntegerPart() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));
            final List<String> suppressed = new ArrayList<>();

            for (final List<String> document : all) {
                final int count = transactionCount(document);
                for (int block = 0; block < count; block++) {
                    final int base = FIRST_TRANSACTION_AT + block * TRANSACTION_RECORDS;
                    final String amount = slice(document.get(base + BLOCK_AMOUNT_AT),
                            PARAGRAPH_OPEN.length(), TRANSACTION_AMOUNT_WIDTH);
                    if (amount.stripLeading().startsWith(".")) {
                        suppressed.add(amount);
                    }
                }
            }
            assertThat(suppressed)
                    .as("two fractional interest accruals and one fractional purchase")
                    .hasSize(3)
                    .contains(" ".repeat(9) + ".00 ")
                    .allSatisfy(amount -> assertThat(amount).matches(" {9}\\.\\d{2}[ \\-]"));
        }

        @Test
        @DisplayName("no character reference appears anywhere, because the legacy program encoded "
                + "nothing and the oracle is faithful to it")
        void theOracleCarriesNoCharacterReference() throws IOException {
            final String whole = String.join("", records(HTML_FIXTURE, HTML_WIDTH));

            for (final String reference : CHARACTER_REFERENCES) {
                assertThat(whole)
                        .as("%s must not appear: escaping is the emitter's hardening, not the "
                                + "legacy contract", reference)
                        .doesNotContain(reference);
            }
            assertThat(whole.chars().filter(character -> character == '\'').count())
                    .as("the markup-significant characters the data does carry, carried raw")
                    .isEqualTo(ESCAPED_DIVERGENCES);
        }
    }

    @Nested
    @DisplayName("every composed line, re-emitted through the production template class")
    final class TheComposedLines {

        @Test
        @DisplayName("the account heading is the 34-byte opening literal, the identifier in its "
                + "20-byte slot and the 5-byte closing literal, padded to the record")
        void theAccountHeadingIsReemitted() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));

            for (final List<String> document : all) {
                final String record = document.get(ACCOUNT_HEADING_AT);
                final String account = slice(record, ACCOUNT_FIELD_AT, ACCOUNT_FIELD_WIDTH);

                assertThat(record.stripTrailing().length())
                        .as("the composed group is 59 bytes before the record padding")
                        .isEqualTo(StatementHtmlTemplates.ACCOUNT_LINE_DECLARED_LENGTH);
                assertThat(account)
                        .as("an eleven-digit identifier moved into a twenty-byte alphanumeric slot "
                                + "keeps nine interior spaces before the closing literal")
                        .matches("\\d{11} {9}");
                assertThat(StatementHtmlTemplates.accountNumberLine(account)).isEqualTo(record);
            }
        }

        @Test
        @DisplayName("the name line and the three address lines are re-emitted, and the transfer "
                + "stops at the first pair of adjacent spaces")
        void theNameAndAddressLinesAreReemitted() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));
            int divergences = 0;

            for (final List<String> document : all) {
                final String nameRecord = document.get(NAME_AT);
                final String name = delimitedValue(nameRecord, NAME_FIELD_AT);
                assertThat(name)
                        .as("the transferred name stops before any pair of adjacent spaces")
                        .doesNotContain(TRANSFER_DELIMITER)
                        .hasSizeLessThanOrEqualTo(StatementHtmlTemplates.NAME_LINE_NAME_LENGTH);
                if (assertReemitted(nameRecord, name,
                        StatementHtmlTemplates.customerNameLine(name),
                        "<p style=\"font-size:16px\">", TRANSFER_DELIMITER + PARAGRAPH_CLOSE)) {
                    divergences++;
                }

                for (int address = 0; address < ADDRESS_RECORDS; address++) {
                    final String record = document.get(FIRST_ADDRESS_AT + address);
                    final String value = delimitedValue(record, PARAGRAPH_OPEN.length());
                    assertThat(value).doesNotContain(TRANSFER_DELIMITER);
                    if (assertReemitted(record, value,
                            StatementHtmlTemplates.addressWorkLine(value),
                            PARAGRAPH_OPEN, TRANSFER_DELIMITER + PARAGRAPH_CLOSE)) {
                        divergences++;
                    }
                }
            }
            assertThat(divergences)
                    .as("one customer surname carries an apostrophe; no address line does")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the three basic-detail lines are re-emitted, and their delimiter keeps the "
                + "field padding inside the paragraph")
        void theBasicDetailLinesAreReemitted() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));

            for (final List<String> document : all) {
                final String accountRecord = document.get(FIRST_DETAIL_AT);
                final String balanceRecord = document.get(FIRST_DETAIL_AT + 1);
                final String scoreRecord = document.get(FIRST_DETAIL_AT + 2);
                final int valueAt = PARAGRAPH_OPEN.length() + ACCOUNT_LABEL.length();
                final String account = slice(accountRecord, valueAt, ACCOUNT_FIELD_WIDTH);
                final String balance = slice(balanceRecord, valueAt, BALANCE_FIELD_WIDTH);
                final String score = slice(scoreRecord, valueAt, SCORE_FIELD_WIDTH);

                assertThat(account)
                        .as("this delimiter is absent from the data, so the whole padded field "
                                + "transfers and the trailing spaces stay inside the paragraph")
                        .matches("\\d{11} {9}");
                assertThat(balance)
                        .as("nine printed digits with no zero suppression, a point, two digits and "
                                + "a sign slot that is a space when the balance is not negative")
                        .matches("\\d{9}\\.\\d{2}[ \\-]");
                assertThat(score).matches("\\d{3} {17}");
                assertThat(StatementHtmlTemplates.basicDetailsWorkLine(ACCOUNT_LABEL, account))
                        .isEqualTo(accountRecord);
                assertThat(StatementHtmlTemplates.basicDetailsWorkLine(BALANCE_LABEL, balance))
                        .isEqualTo(balanceRecord);
                assertThat(StatementHtmlTemplates.basicDetailsWorkLine(SCORE_LABEL, score))
                        .isEqualTo(scoreRecord);
            }
        }

        @Test
        @DisplayName("the three lines of every transaction block are re-emitted, and only the "
                + "descriptions that carry an apostrophe diverge, by escaping alone")
        void theTransactionLinesAreReemitted() throws IOException {
            final List<List<String>> all = documents(records(HTML_FIXTURE, HTML_WIDTH));
            int divergences = 0;
            int blocks = 0;

            for (final List<String> document : all) {
                final int count = transactionCount(document);
                for (int block = 0; block < count; block++) {
                    final int base = FIRST_TRANSACTION_AT + block * TRANSACTION_RECORDS;
                    blocks++;
                    divergences += reemitBlock(document, base);
                }
            }
            assertThat(blocks).isEqualTo(TRANSACTION_BLOCKS);
            assertThat(divergences)
                    .as("eleven transaction descriptions carry an apostrophe")
                    .isEqualTo(ESCAPED_DIVERGENCES - 1);
        }

        /**
         * Re-emits the three composed records of one transaction block.
         *
         * @param  document the committed document
         * @param  base     zero-based position of the block inside the document
         * @return how many of the three records forced the escaping divergence
         */
        private int reemitBlock(final List<String> document, final int base) {
            int divergences = 0;
            final int valueAt = PARAGRAPH_OPEN.length();
            final String idRecord = document.get(base + BLOCK_ID_AT);
            final String descriptionRecord = document.get(base + BLOCK_DESCRIPTION_AT);
            final String amountRecord = document.get(base + BLOCK_AMOUNT_AT);
            final String identifier = slice(idRecord, valueAt, TRANSACTION_ID_WIDTH);
            final String description =
                    slice(descriptionRecord, valueAt, TRANSACTION_DESCRIPTION_WIDTH);
            final String amount = slice(amountRecord, valueAt, TRANSACTION_AMOUNT_WIDTH);

            assertThat(identifier)
                    .as("a sixteen-byte identifier fills its slot exactly, with no padding")
                    .matches("\\d{16}");
            assertThat(amount)
                    .as("leading zeros ARE suppressed here - the whole integer part disappears when "
                            + "it is zero - and the two decimals always print, because no "
                            + "blank-when-zero clause exists anywhere in the estate")
                    .matches(" *\\d*\\.\\d{2}[ \\-]");
            divergences += assertReemitted(idRecord, identifier,
                    StatementHtmlTemplates.transactionWorkLine(identifier),
                    PARAGRAPH_OPEN, PARAGRAPH_CLOSE) ? 1 : 0;
            divergences += assertReemitted(descriptionRecord, description,
                    StatementHtmlTemplates.transactionWorkLine(description),
                    PARAGRAPH_OPEN, PARAGRAPH_CLOSE) ? 1 : 0;
            divergences += assertReemitted(amountRecord, amount,
                    StatementHtmlTemplates.transactionWorkLine(amount),
                    PARAGRAPH_OPEN, PARAGRAPH_CLOSE) ? 1 : 0;
            return divergences;
        }
    }

    @Nested
    @DisplayName("cross-oracle agreement with the 80-byte statement, including where they must differ")
    final class CrossOracleAgreement {

        /** Offset of the value in the three labelled 80-byte detail records. */
        private static final int TEXT_VALUE_AT = 20;

        /** Fixed records a plain-text statement block carries besides its transaction records. */
        private static final int TEXT_FIXED_RECORDS = 19;

        /** Zero-based position of the first transaction record inside a plain-text block. */
        private static final int TEXT_FIRST_TRANSACTION_AT = 16;

        /** Declared width of the plain-text name field, which is wider than the HTML staging field. */
        private static final int TEXT_NAME_WIDTH = 75;

        /** Declared width of the two plain-text address fields that are moved rather than composed. */
        private static final int TEXT_ADDRESS_WIDTH = 50;

        /**
         * Splits the 80-byte oracle into per-account blocks on its opening banner.
         *
         * @param  all every record of the plain-text oracle
         * @return the blocks, in fixture order
         */
        private List<List<String>> blocks(final List<String> all) {
            final String opening = "*".repeat(31) + "START OF STATEMENT" + "*".repeat(31);
            final String closing = "*".repeat(32) + "END OF STATEMENT" + "*".repeat(32);
            final List<List<String>> out = new ArrayList<>();
            int index = 0;
            while (index < all.size()) {
                assertThat(all.get(index)).as("block at record %d opens with its banner", index)
                        .isEqualTo(opening);
                int end = index + 1;
                while (!all.get(end).equals(closing)) {
                    end++;
                }
                out.add(List.copyOf(all.subList(index, end + 1)));
                index = end + 1;
            }
            return out;
        }

        @Test
        @DisplayName("both oracles describe the same fifty accounts in the same order, with the same "
                + "transaction counts")
        void bothOraclesDescribeTheSameAccountsInTheSameOrder() throws IOException {
            final List<List<String>> html = documents(records(HTML_FIXTURE, HTML_WIDTH));
            final List<List<String>> text = blocks(records(TEXT_FIXTURE, TEXT_WIDTH));

            assertThat(text).as("the plain-text oracle carries the same account count").hasSize(
                    html.size());
            for (int index = 0; index < html.size(); index++) {
                final int htmlCount = transactionCount(html.get(index));
                final int textCount = text.get(index).size() - TEXT_FIXED_RECORDS;
                assertThat(textCount)
                        .as("account %d carries the same transactions in both oracles", index)
                        .isEqualTo(htmlCount);
            }
        }

        @Test
        @DisplayName("every field both oracles emit agrees byte for byte, and the name and address "
                + "lines differ exactly and only by the delimited transfer")
        void everySharedFieldAgrees() throws IOException {
            final List<List<String>> html = documents(records(HTML_FIXTURE, HTML_WIDTH));
            final List<List<String>> text = blocks(records(TEXT_FIXTURE, TEXT_WIDTH));

            for (int index = 0; index < html.size(); index++) {
                final List<String> document = html.get(index);
                final List<String> block = text.get(index);
                final int valueAt = PARAGRAPH_OPEN.length() + ACCOUNT_LABEL.length();

                assertThat(slice(document.get(ACCOUNT_HEADING_AT),
                        ACCOUNT_FIELD_AT, ACCOUNT_FIELD_WIDTH))
                        .as("account %d heading identifier", index)
                        .isEqualTo(slice(block.get(8), TEXT_VALUE_AT, ACCOUNT_FIELD_WIDTH));
                assertThat(slice(document.get(FIRST_DETAIL_AT), valueAt, ACCOUNT_FIELD_WIDTH))
                        .as("account %d detail identifier", index)
                        .isEqualTo(slice(block.get(8), TEXT_VALUE_AT, ACCOUNT_FIELD_WIDTH));
                assertThat(slice(document.get(FIRST_DETAIL_AT + 1), valueAt, BALANCE_FIELD_WIDTH))
                        .as("account %d balance", index)
                        .isEqualTo(slice(block.get(9), TEXT_VALUE_AT, BALANCE_FIELD_WIDTH));
                assertThat(slice(document.get(FIRST_DETAIL_AT + 2), valueAt, SCORE_FIELD_WIDTH))
                        .as("account %d credit score", index)
                        .isEqualTo(slice(block.get(10), TEXT_VALUE_AT, SCORE_FIELD_WIDTH));

                // The one required divergence: the plain-text oracle emits the whole padded field,
                // the HTML oracle emits the name narrowed to its 50-byte staging field and then cut
                // at the first pair of adjacent spaces.
                final String textName = slice(block.get(1), 0, TEXT_NAME_WIDTH);
                assertThat(delimitedValue(document.get(NAME_AT), NAME_FIELD_AT))
                        .as("account %d name", index)
                        .isEqualTo(upToFirstDoubleSpace(textName.substring(0,
                                StatementHtmlTemplates.NAME_LINE_NAME_LENGTH)));
                assertThat(delimitedValue(document.get(FIRST_ADDRESS_AT), PARAGRAPH_OPEN.length()))
                        .as("account %d address line 1", index)
                        .isEqualTo(upToFirstDoubleSpace(slice(block.get(2), 0, TEXT_ADDRESS_WIDTH)));
                assertThat(delimitedValue(document.get(FIRST_ADDRESS_AT + 1),
                        PARAGRAPH_OPEN.length()))
                        .as("account %d address line 2", index)
                        .isEqualTo(upToFirstDoubleSpace(slice(block.get(3), 0, TEXT_ADDRESS_WIDTH)));
                assertThat(delimitedValue(document.get(FIRST_ADDRESS_AT + 2),
                        PARAGRAPH_OPEN.length()))
                        .as("account %d address line 3", index)
                        .isEqualTo(upToFirstDoubleSpace(block.get(4)));

                assertTransactionsAgree(index, document, block);
            }
        }

        /**
         * Compares one account's transaction triples across the two oracles.
         *
         * @param index    the account's position, for assertion messages
         * @param document the HTML oracle's document for that account
         * @param block    the plain-text oracle's block for that account
         */
        private void assertTransactionsAgree(final int index, final List<String> document,
                final List<String> block) {
            final int count = transactionCount(document);
            final int valueAt = PARAGRAPH_OPEN.length();
            for (int transaction = 0; transaction < count; transaction++) {
                final int base = FIRST_TRANSACTION_AT + transaction * TRANSACTION_RECORDS;
                final String line = block.get(TEXT_FIRST_TRANSACTION_AT + transaction);
                assertThat(slice(document.get(base + BLOCK_ID_AT), valueAt, TRANSACTION_ID_WIDTH))
                        .as("account %d transaction %d identifier", index, transaction)
                        .isEqualTo(slice(line, 0, TRANSACTION_ID_WIDTH));
                assertThat(slice(document.get(base + BLOCK_DESCRIPTION_AT), valueAt,
                        TRANSACTION_DESCRIPTION_WIDTH))
                        .as("account %d transaction %d description", index, transaction)
                        .isEqualTo(slice(line, 17, TRANSACTION_DESCRIPTION_WIDTH));
                assertThat(slice(document.get(base + BLOCK_AMOUNT_AT), valueAt,
                        TRANSACTION_AMOUNT_WIDTH))
                        .as("account %d transaction %d amount", index, transaction)
                        .isEqualTo(slice(line, 67, TRANSACTION_AMOUNT_WIDTH));
            }
        }

        @Test
        @DisplayName("both oracles carry the markup-significant characters raw and identically, so "
                + "neither has been escaped, masked or normalised")
        void bothOraclesCarryTheSameRawCharacters() throws IOException {
            final String html = String.join("", records(HTML_FIXTURE, HTML_WIDTH));
            final String text = String.join("", records(TEXT_FIXTURE, TEXT_WIDTH));

            assertThat(html.chars().filter(character -> character == '\'').count())
                    .as("the same twelve apostrophes reach both oracles")
                    .isEqualTo(text.chars().filter(character -> character == '\'').count())
                    .isEqualTo(ESCAPED_DIVERGENCES);
        }
    }
}
