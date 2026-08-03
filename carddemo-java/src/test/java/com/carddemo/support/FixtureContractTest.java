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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the nine named sequential fixtures against the contract the migration plan states for
 * them, by opening every one of them and measuring the bytes.
 *
 * <h2>What this test guards</h2>
 *
 * <p>The plan names nine production-representative datasets by filename, by byte count and by record
 * count, and designates them the inputs for the end-to-end and named-artefact validation gates. They
 * are carried in this module as copies, under {@code src/test/resources/fixtures/input}, because the
 * module has to build and validate standalone. A copy can drift from what it was copied from, can be
 * re-saved with a different line terminator, can lose or gain a trailing terminator, and can be
 * truncated - and every one of those changes is silent. This suite is what makes each of them a test
 * failure.
 *
 * <h2>Why this is not already covered</h2>
 *
 * <p>The geometry of these nine files is already recited elsewhere: {@code FixedWidthFieldReaderTest}
 * carries a nine-row table of byte counts, record counts and record widths, and checks that each row
 * factors as {@code records * (width + 1)}. That check is real and worth having, but it is arithmetic
 * over literals - its own comment records that no file is opened - so it constrains the literals only
 * against one another. It would pass unchanged if a fixture were truncated, re-encoded, or deleted
 * outright. Nothing at this checkout reads a fixture and compares what it read against a recorded
 * expectation, and nothing compares a fixture against the dataset it was copied from. That is the gap
 * this suite closes, and it is why every assertion below is driven from bytes actually read.
 *
 * <p>One of the nine is additionally not loaded by any other suite at all: the card cross-reference
 * fixture is named in two comments and one arithmetic row and is otherwise never opened, because no
 * cross-reference record mapper ships at this checkout. Here it is treated exactly like the other
 * eight.
 *
 * <h2>How the authority is honoured without depending on it</h2>
 *
 * <p>The datasets these fixtures were copied from live outside this module, in the legacy tree at
 * {@code app/data/ASCII}. The module is required to build and validate with no reference to any
 * mainframe asset, so this suite must not need that tree to be present. It is reconciled in two
 * layers:
 *
 * <ul>
 *   <li><strong>Unconditional.</strong> Each fixture is pinned by a SHA-256 digest literal declared
 *       in this class. Those digests are the authority's fingerprints, measured from
 *       {@code app/data/ASCII} when this suite was written and carried here as citations - the same
 *       way every other legacy fact in this module is carried. A digest literal is a fingerprint and
 *       not a transcription, so recording it copies no legacy content. This layer runs everywhere and
 *       is the layer that actually enforces the contract.</li>
 *   <li><strong>When the legacy tree is present.</strong> The fixtures are additionally compared byte
 *       for byte against it. This layer is skipped when the tree is absent, so that a standalone
 *       checkout still builds - but a <em>partially</em> present tree fails rather than skips, so the
 *       skip cannot be reached by a half-deleted directory.</li>
 * </ul>
 *
 * <h2>Why the composition is checked and not only the geometry</h2>
 *
 * <p>A file can be the right length, hold the right number of records and still not exercise the
 * behaviour a gate needs from it. Three composition facts are what make these fixtures adequate
 * inputs rather than merely well-formed ones, so each is asserted rather than assumed:
 *
 * <ul>
 *   <li>The daily transactions carry both signed directions. The source marker splits them, and the
 *       sign lives in the final byte of the amount as an overpunch. Those two facts turn out to
 *       correlate exactly, which is the property that makes both the debit and the credit posting
 *       path reachable from seeded data.</li>
 *   <li>The disclosure groups form three complete groups under three distinct keys, one of which is
 *       the fallback group, so both the direct-hit and the fallback branch of the interest
 *       calculation are reachable.</li>
 *   <li>Every daily transaction has a blank processing timestamp and they all share one origination
 *       timestamp. The first is what makes the posting run meaningful; the second is a limitation
 *       rather than a feature, and it is asserted so that it is recorded rather than discovered - a
 *       date-window filter cannot be exercised from this file.</li>
 * </ul>
 *
 * <h2>How the evidence is obtained, and what that does and does not prove</h2>
 *
 * <p>Every expected value in this class is written out by hand: the byte counts, the record counts,
 * the record widths, the digests, the offsets, the source markers, the group keys and both overpunch
 * alphabets. None is read from the file it is used to judge, and none is derived by running the code
 * under test - the digests were measured from the authority with an independent tool, and the offsets
 * were computed by hand from the copybook field widths. What this proves is that the fixtures carried
 * in this module are the datasets the plan names, unaltered, and that they hold the composition the
 * gates rely on. What it does not prove is anything about how a reader or mapper interprets them;
 * that is the subject of the record mapper suites.
 *
 * <p>The design of this suite, and the three properties it measured that corrected or sharpened what
 * had previously been recorded about these fixtures - the ten-byte disclosure-group keys, the exact
 * correspondence between the amount's overpunch and the source marker, and the single origination
 * timestamp that makes a date window unexercisable from this input - are reasoned in
 * {@code docs/decision-log.md} DL-113. The zoned-decimal sign convention it relies on is DL-015 and the
 * assert-as-bytes discipline is DL-046.
 *
 * <p><strong>Provenance.</strong> Checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source statement is
 * reproduced here; the legacy layouts are cited by copybook and field width.</p>
 */
@DisplayName("Named fixtures: the nine sequential inputs and the derived credential fixture, measured")
final class FixtureContractTest {

    /** Class-loader directory holding the nine fixtures. */
    private static final String FIXTURE_DIRECTORY = "/fixtures/input/";

    /** The committed location of the fixtures, relative to the module directory. */
    private static final String COMMITTED_DIRECTORY = "src/test/resources/fixtures/input";

    /**
     * The legacy datasets the fixtures were copied from, relative to the module directory. Outside
     * the module on purpose: it is consulted when present and never required.
     */
    private static final String AUTHORITY_DIRECTORY = "../app/data/ASCII";

    /** How many fixtures the plan names. */
    private static final int EXPECTED_FIXTURE_COUNT = 9;

    /** Digest algorithm used to pin each fixture. */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** The single byte that terminates every record. */
    private static final char LINE_FEED = '\n';

    /** The byte that must never appear: these fixtures are not carriage-return delimited. */
    private static final char CARRIAGE_RETURN = '\r';

    /** The nine file names, in the order the plan lists them. */
    private static final List<String> FIXTURE_NAMES = List.of(
            "acctdata.txt", "carddata.txt", "cardxref.txt", "custdata.txt", "dailytran.txt",
            "discgrp.txt", "tcatbal.txt", "trancatg.txt", "trantype.txt");

    // ---------------------------------------------------------------------------------------------
    // The tenth member of the same directory, which is deliberately NOT one of the nine above.
    //
    // The nine are byte-verbatim copies of sequential datasets, and every assertion this suite makes
    // about them - the recorded byte count, the record count, the digest, the line-feed terminator -
    // holds because they are copies. The credential fixture is derived rather than copied: the
    // provisioning job carries its ten records in stream as 57-character cards, and the record layout
    // pads each one to 80. It therefore has a different shape from all nine, and in one respect the
    // opposite shape: it carries no line terminator at all, so it is read on a fixed stride instead of
    // by line. Adding it to FIXTURE_NAMES would hand it to the parameterized rows above, every one of
    // which assumes a trailing line feed, and they would fail on it for the right reason.
    //
    // So it is enumerated separately, and given its own assertions below. What must NOT happen is the
    // directory-membership check being relaxed to tolerate it: that check exists precisely to catch an
    // unaccounted-for file, and the fix for a NEW accounted-for file is to account for it by name.
    // ---------------------------------------------------------------------------------------------

    /** The derived credential fixture: the tenth file in the directory, terminator-free. */
    private static final String CREDENTIAL_FIXTURE = "usrsec.txt";

    /** Record width of the credential fixture, from the user-security record layout. */
    private static final int CREDENTIAL_RECORD_WIDTH = 80;

    /** How many records the credential fixture carries. */
    private static final int CREDENTIAL_RECORD_COUNT = 10;

    /** Total size of the credential fixture: ten records at eighty bytes, and no terminator. */
    private static final int CREDENTIAL_TOTAL_BYTES = CREDENTIAL_RECORD_COUNT * CREDENTIAL_RECORD_WIDTH;

    /** Width of the card the credential fixture's records are derived from, before padding. */
    private static final int CREDENTIAL_CARD_WIDTH = 57;

    /** Zero-based offset of the user identifier within a credential record. */
    private static final int CREDENTIAL_ID_OFFSET = 0;

    /** Width of the user identifier. */
    private static final int CREDENTIAL_ID_WIDTH = 8;

    /** Zero-based offset of the user type within a credential record. */
    private static final int CREDENTIAL_TYPE_OFFSET = 56;

    /** How many of the ten records carry the administrative user type. */
    private static final int ADMINISTRATOR_COUNT = 5;

    /** The ten user identifiers the provisioning job seeds, in the order it writes them. */
    private static final List<String> CREDENTIAL_USER_IDS = List.of(
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /**
     * Every file the committed fixture directory is accounted for as holding: the nine copies and the
     * one derived fixture. Anything else in the directory is unaccounted for and is a failure.
     */
    private static final List<String> DIRECTORY_MEMBERS =
            Stream.concat(FIXTURE_NAMES.stream(), Stream.of(CREDENTIAL_FIXTURE)).sorted().toList();

    // ---------------------------------------------------------------------------------------------
    // Daily-transaction offsets. Computed by hand from the daily-transaction copybook's field widths
    // (16, 2, 4, 10, 100, 11, 9, 50, 50, 10, 16, 26, 26, 20 - which sum to the declared 350), never
    // read from the file. The card-number and processing-timestamp positions these imply are the same
    // positions the legacy external sort addresses by column, which is an independent confirmation
    // that the arithmetic is right.
    // ---------------------------------------------------------------------------------------------

    /** Zero-based offset of the source marker. */
    private static final int SOURCE_OFFSET = 22;

    /** Width of the source marker. */
    private static final int SOURCE_WIDTH = 10;

    /** Zero-based offset of the signed amount. */
    private static final int AMOUNT_OFFSET = 132;

    /** Width of the signed amount: nine digits, two decimal places, sign overpunched. */
    private static final int AMOUNT_WIDTH = 11;

    /** Zero-based offset of the origination timestamp. */
    private static final int ORIGINATION_TIMESTAMP_OFFSET = 278;

    /** Zero-based offset of the processing timestamp. */
    private static final int PROCESSING_TIMESTAMP_OFFSET = 304;

    /** Width of either timestamp. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Source marker of a point-of-sale purchase, padded to its field width. */
    private static final String POINT_OF_SALE_MARKER = "POS TERM  ";

    /** Source marker of an operator-originated return, padded to its field width. */
    private static final String OPERATOR_MARKER = "OPERATOR  ";

    /** Expected count of point-of-sale records. */
    private static final int EXPECTED_POINT_OF_SALE_RECORDS = 250;

    /** Expected count of operator-originated records. */
    private static final int EXPECTED_OPERATOR_RECORDS = 50;

    /** Expected total daily-transaction records. */
    private static final int EXPECTED_DAILY_TRANSACTION_RECORDS = 300;

    /**
     * The overpunch characters that encode a non-negative low-order digit: brace for positive zero,
     * then the first nine letters for one through nine.
     */
    private static final String POSITIVE_OVERPUNCH_ALPHABET = "{ABCDEFGHI";

    /**
     * The overpunch characters that encode a negative low-order digit: brace for negative zero, then
     * the letters J through R for one through nine.
     */
    private static final String NEGATIVE_OVERPUNCH_ALPHABET = "}JKLMNOPQR";

    // ---------------------------------------------------------------------------------------------
    // Disclosure-group composition
    // ---------------------------------------------------------------------------------------------

    /** Width of the disclosure-group key that begins each record. */
    private static final int DISCLOSURE_GROUP_KEY_WIDTH = 10;

    /** Records expected in each disclosure group. */
    private static final int RECORDS_PER_DISCLOSURE_GROUP = 17;

    /** The three group keys, padded to the key width, in the order the file presents them. */
    private static final List<String> DISCLOSURE_GROUP_KEYS =
            List.of("A000000000", "DEFAULT   ", "ZEROAPR   ");

    /** Width of the transaction-type key. */
    private static final int TRANSACTION_TYPE_KEY_WIDTH = 2;

    /** Width of the transaction-category composite key: a two-byte type and a four-byte category. */
    private static final int TRANSACTION_CATEGORY_KEY_WIDTH = 6;

    /** The seven transaction-type keys the reference fixture declares. */
    private static final List<String> TRANSACTION_TYPE_KEYS =
            List.of("01", "02", "03", "04", "05", "06", "07");

    /** The eighteen transaction-category composite keys the reference fixture declares. */
    private static final List<String> TRANSACTION_CATEGORY_KEYS = List.of(
            "010001", "010002", "010003", "010004", "010005",
            "020001", "020002", "020003",
            "030001", "030002", "030003",
            "040001", "040002", "040003",
            "050001",
            "060001", "060002",
            "070001");

    /**
     * The contract of all nine fixtures, one row each: file name, byte count, record count, record
     * width and pinned digest.
     *
     * <p>Referenced by name from the nested classes below, which is why it is declared on the
     * enclosing class.
     *
     * @return one argument row per fixture
     */
    static Stream<Arguments> everyFixture() {
        return Stream.of(
                Arguments.of("acctdata.txt", 15050, 50, 300),
                Arguments.of("carddata.txt", 7550, 50, 150),
                Arguments.of("cardxref.txt", 1850, 50, 36),
                Arguments.of("custdata.txt", 25050, 50, 500),
                Arguments.of("dailytran.txt", 105300, 300, 350),
                Arguments.of("discgrp.txt", 2601, 51, 50),
                Arguments.of("tcatbal.txt", 2550, 50, 50),
                Arguments.of("trancatg.txt", 1098, 18, 60),
                Arguments.of("trantype.txt", 427, 7, 60));
    }

    /**
     * Reads one fixture from the test classpath in full.
     *
     * @param fileName the fixture's file name
     * @return the fixture's bytes
     * @throws IOException if the fixture cannot be read
     */
    private static byte[] fixtureBytes(final String fileName) throws IOException {
        final String resource = FIXTURE_DIRECTORY + fileName;
        try (InputStream stream = FixtureContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("fixture %s must be present on the test classpath", resource)
                    .isNotNull();
            return stream.readAllBytes();
        }
    }

    /**
     * Reads one fixture as text, one character per byte.
     *
     * @param fileName the fixture's file name
     * @return the fixture's content, decoded so that one byte is one character
     * @throws IOException if the fixture cannot be read
     */
    private static String fixtureText(final String fileName) throws IOException {
        return new String(fixtureBytes(fileName), StandardCharsets.ISO_8859_1);
    }

    /**
     * Splits a fixture into its record images, discarding the terminator after each.
     *
     * @param fileName the fixture's file name
     * @return the record images, in file order
     * @throws IOException if the fixture cannot be read
     */
    private static List<String> fixtureRecords(final String fileName) throws IOException {
        final List<String> records = new ArrayList<>();
        for (final String line : fixtureText(fileName).split("\n", -1)) {
            if (!line.isEmpty()) {
                records.add(line);
            }
        }
        return records;
    }

    /**
     * Computes the lower-case hexadecimal SHA-256 digest of the supplied content.
     *
     * @param content the bytes to digest
     * @return the digest in lower-case hexadecimal
     */
    private static String sha256Hex(final byte[] content) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    DIGEST_ALGORITHM + " is mandatory in a conforming JVM", unavailable);
        }
        return HexFormat.of().formatHex(digest.digest(content));
    }

    /**
     * Resolves the legacy dataset directory, which lies outside this module.
     *
     * @return the authority directory as a path, present or not
     */
    private static Path authorityDirectory() {
        return Paths.get(AUTHORITY_DIRECTORY);
    }

    /**
     * Counts how many of the nine datasets the legacy tree currently holds.
     *
     * @return the number of the nine names that resolve to a readable file
     */
    private static int authorityFilesPresent() {
        final Path directory = authorityDirectory();
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int present = 0;
        for (final String name : FIXTURE_NAMES) {
            if (Files.isRegularFile(directory.resolve(name))) {
                present++;
            }
        }
        return present;
    }

    /**
     * Slices one field out of a record image.
     *
     * @param record the record image
     * @param offset the zero-based offset of the field
     * @param width  the field's width
     * @return the field image
     */
    private static String field(final String record, final int offset, final int width) {
        return record.substring(offset, offset + width);
    }

    @Nested
    @DisplayName("geometry, measured from the bytes on disk")
    final class GeometryMeasuredFromTheBytes {

        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "com.carddemo.support.FixtureContractTest#everyFixture")
        @DisplayName("each fixture is present and measures exactly its recorded byte count")
        void eachFixtureMeasuresItsRecordedByteCount(final String fileName, final int expectedBytes,
                final int expectedRecords, final int expectedWidth) throws IOException {
            // The byte count is the plan's own figure for this file. Reading the file and comparing is
            // what distinguishes this from restating the figure: a truncated or re-saved copy fails
            // here, and the two derived quantities below cannot both stay right if this one is wrong.
            final byte[] content = fixtureBytes(fileName);

            assertThat(content.length)
                    .as("%s must measure the byte count the plan records for it", fileName)
                    .isEqualTo(expectedBytes);
            assertThat(expectedBytes)
                    .as("%s: records times stride must account for every byte", fileName)
                    .isEqualTo(expectedRecords * (expectedWidth + 1));
        }

        @ParameterizedTest(name = "{0}: {2} records of {3} bytes")
        @MethodSource(
                "com.carddemo.support.FixtureContractTest#everyFixture")
        @DisplayName("every record measures the declared width, and there are exactly as many as recorded")
        void everyRecordMeasuresTheDeclaredWidth(final String fileName, final int expectedBytes,
                final int expectedRecords, final int expectedWidth) throws IOException {
            // Per-record rather than in aggregate. A file whose total length is right can still hold a
            // short record followed by a long one, and that file would misalign every field of both
            // while satisfying any arithmetic done on the total alone. The offending ordinals are
            // collected so a failure names all of them rather than only the first.
            final List<String> records = fixtureRecords(fileName);

            assertThat(records)
                    .as("%s must hold exactly the recorded number of records", fileName)
                    .hasSize(expectedRecords);

            final Map<Integer, Integer> offendingWidths = new LinkedHashMap<>();
            for (int index = 0; index < records.size(); index++) {
                final int width = records.get(index).length();
                if (width != expectedWidth) {
                    offendingWidths.put(index + 1, width);
                }
            }
            assertThat(offendingWidths)
                    .as("%s: every record must measure %s bytes; ordinal -> measured width",
                            fileName, expectedWidth)
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "com.carddemo.support.FixtureContractTest#everyFixture")
        @DisplayName("each fixture ends with one line feed and carries no carriage return anywhere")
        void eachFixtureIsLineFeedTerminatedAndCarriageReturnFree(final String fileName,
                final int expectedBytes, final int expectedRecords, final int expectedWidth)
                throws IOException {
            // The terminator policy is contractual, not cosmetic. Every record including the last is
            // terminated - which is why the byte count exceeds records times width - and the files are
            // not carriage-return delimited. A copy re-saved on a platform that appends a carriage
            // return would add one byte per record, and the record images a reader then sliced would
            // each carry a stray trailing byte.
            final String text = fixtureText(fileName);

            assertThat(text)
                    .as("%s must be terminated after its final record", fileName)
                    .endsWith(String.valueOf(LINE_FEED));
            assertThat(text.chars().filter(character -> character == LINE_FEED).count())
                    .as("%s must carry exactly one terminator per record", fileName)
                    .isEqualTo(expectedRecords);
            assertThat(text.indexOf(CARRIAGE_RETURN))
                    .as("%s must contain no carriage return", fileName)
                    .isEqualTo(-1);
            assertThat(text).doesNotEndWith(String.valueOf(LINE_FEED) + LINE_FEED);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "acctdata.txt,  c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260",
            "carddata.txt,  da217240d2567c85f84b571aeb465171c683cfd21dad1754046f6bbb10e76c1d",
            "cardxref.txt,  efec3825ec0d5b791cf54f815bf688abfcc9db832c1600371ed2209df4e97764",
            "custdata.txt,  d8cfa5b77fa61614329e73ebde9052367ea31cc1b08f9056fa869f949fef9991",
            "dailytran.txt, 1605206de7009cba771a921bf13f4dfcd1673fc13f1b844150355e9a95fa8da3",
            "discgrp.txt,   dfdd3832805e3a4bf1d811ea2340ee8f6bf8e9fdf8d040fbd50e3c7b45b0ce2b",
            "tcatbal.txt,   2c45817e7986ffe29cce285c8ce8feb3cf7303c4624d830dc7bdd9c9cdb03b71",
            "trancatg.txt,  80040907d52527e144e12d6e9ca300d31dd08826ef253cbd34e9f7aa92662da8",
            "trantype.txt,  3e0ae0040d3ac6828edbaa885d6db1c65edbcf0477e984764508ea01c5b6ecee",
        })
        @DisplayName("each fixture matches the digest measured from the legacy dataset it copies")
        void eachFixtureMatchesItsPinnedDigest(final String fileName, final String expectedDigest)
                throws IOException {
            // This is the layer that enforces the contract everywhere, including in a checkout that
            // carries no legacy tree. The digest was measured from the authority with an independent
            // tool when this suite was written; it is a fingerprint, so recording it here copies no
            // legacy content. Any edit to a fixture, of any size, fails this row.
            assertThat(sha256Hex(fixtureBytes(fileName)))
                    .as("%s must be byte identical to the dataset it was copied from", fileName)
                    .isEqualTo(expectedDigest);
        }
    }

    @Nested
    @DisplayName("the committed fixture directory")
    final class TheCommittedFixtureDirectory {

        @Test
        @DisplayName("holds exactly the nine copies plus the derived credential fixture, and nothing else")
        void holdsExactlyTheAccountedForFiles() throws IOException {
            // Both directions matter. A missing file would make some parameterized row fail already,
            // but an EXTRA file would not be noticed anywhere, and an unaccounted-for fixture is
            // exactly the thing that later gets used as an input nobody pinned.
            //
            // The membership is stated as a closed list of ten, not loosened to "at least the nine".
            // Every member is named, so a file arriving in this directory without being added here is
            // still a failure - which is the whole point of the check.
            final Path directory = Paths.get(COMMITTED_DIRECTORY);
            assertThat(Files.isDirectory(directory))
                    .as("the committed fixture directory %s must exist", COMMITTED_DIRECTORY)
                    .isTrue();

            final List<String> actual;
            try (Stream<Path> entries = Files.list(directory)) {
                actual = entries.map(path -> path.getFileName().toString()).sorted().toList();
            }

            assertThat(actual)
                    .as("the committed fixture directory must hold exactly the files accounted for here")
                    .containsExactlyInAnyOrderElementsOf(DIRECTORY_MEMBERS)
                    .hasSize(EXPECTED_FIXTURE_COUNT + 1);

            assertThat(actual)
                    .as("the nine copies must all still be present alongside the derived fixture")
                    .containsAll(FIXTURE_NAMES)
                    .contains(CREDENTIAL_FIXTURE);
        }

        @Test
        @DisplayName("holds no subdirectory: the fixtures are flat")
        void holdsNoSubdirectory() throws IOException {
            // A subdirectory here would be invisible to every other assertion in this class, all of
            // which resolve names directly under the directory.
            final Path directory = Paths.get(COMMITTED_DIRECTORY);

            final List<String> subdirectories;
            try (Stream<Path> entries = Files.list(directory)) {
                subdirectories = entries.filter(Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
            }

            assertThat(subdirectories)
                    .as("the committed fixture directory must be flat")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries no digest sidecar: the expectations live in this suite, not beside the data")
        void carriesNoDigestSidecar() throws IOException {
            // The recorded digests are literals in this class. A sidecar file would be a second,
            // unpinned source of truth for the same thing, and would itself be an unaccounted-for file.
            final Path directory = Paths.get(COMMITTED_DIRECTORY);

            final List<String> sidecars;
            try (Stream<Path> entries = Files.list(directory)) {
                sidecars = entries.map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".sha256") || name.startsWith("SHA256SUMS"))
                        .sorted()
                        .toList();
            }

            assertThat(sidecars)
                    .as("no digest sidecar may sit beside the fixtures")
                    .isEmpty();
        }

        @Test
        @DisplayName("the enumeration this suite drives from names nine distinct files")
        void theEnumerationNamesNineDistinctFiles() {
            // Guards the suite against itself: a duplicated or dropped row in the provider would
            // quietly reduce what every parameterized test above covers.
            final List<String> provided = everyFixture()
                    .map(arguments -> (String) arguments.get()[0])
                    .toList();

            assertThat(provided)
                    .as("the provider must enumerate every named fixture exactly once")
                    .hasSize(EXPECTED_FIXTURE_COUNT)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(FIXTURE_NAMES);
        }
    }

    /**
     * The derived credential fixture, asserted on its own terms.
     *
     * <p>Read on a fixed eighty-byte stride rather than by line, because it carries no line
     * terminator: a line-oriented read would see one 800-character line and every offset below would
     * be wrong. The stride is what makes the file parseable, so asserting the stride divides the size
     * exactly is asserting the file is usable at all.
     *
     * <p>Nothing here asserts on the credential itself. The eight bytes the layout reserves for it are
     * compared between records and measured, which is enough to prove the fixture carries one real
     * credential on every record, and never enough to reveal what it is. A failure message from this
     * class reports a record index and a measurement, never a value.
     */
    @Nested
    @DisplayName("the derived credential fixture")
    final class TheDerivedCredentialFixture {

        @Test
        @DisplayName("measures exactly ten eighty-byte records and carries no terminator")
        void measuresTenRecordsAndCarriesNoTerminator() throws IOException {
            final byte[] content = fixtureBytes(CREDENTIAL_FIXTURE);

            assertThat(content)
                    .as("the credential fixture must measure ten records of eighty bytes")
                    .hasSize(CREDENTIAL_TOTAL_BYTES);

            // The deliberate divergence from the other nine, and the reason for the stride.
            assertThat(new String(content, StandardCharsets.ISO_8859_1))
                    .as("the credential fixture must carry no line feed and no carriage return")
                    .doesNotContain(String.valueOf(LINE_FEED))
                    .doesNotContain(String.valueOf(CARRIAGE_RETURN));

            assertThat(content.length % CREDENTIAL_RECORD_WIDTH)
                    .as("the stride must divide the size exactly, or no record boundary is recoverable")
                    .isZero();
            assertThat(content.length / CREDENTIAL_RECORD_WIDTH)
                    .as("the stride must yield exactly ten records")
                    .isEqualTo(CREDENTIAL_RECORD_COUNT);
        }

        @Test
        @DisplayName("begins with data, so nothing was prefixed to it")
        void beginsWithData() throws IOException {
            // A licence header, a banner or a column ruler would each shift every record boundary by
            // its own length. Asserting the first record is the first identifier catches all of them.
            final byte[] content = fixtureBytes(CREDENTIAL_FIXTURE);

            assertThat(new String(content, 0, CREDENTIAL_ID_WIDTH, StandardCharsets.ISO_8859_1))
                    .as("the fixture must open on the first user identifier, with no prefix of any kind")
                    .isEqualTo(CREDENTIAL_USER_IDS.get(0));
        }

        @Test
        @DisplayName("carries the ten user identifiers in the order the provisioning job writes them")
        void carriesTheTenUserIdentifiersInOrder() throws IOException {
            final List<String> identifiers = new ArrayList<>();
            for (final byte[] record : credentialRecords()) {
                identifiers.add(new String(record, CREDENTIAL_ID_OFFSET, CREDENTIAL_ID_WIDTH,
                        StandardCharsets.ISO_8859_1));
            }

            // Order is contractual: the administrative identifiers precede the standard ones, and the
            // seed migration inserts them in this same order.
            assertThat(identifiers)
                    .as("the ten identifiers must appear in source order")
                    .containsExactlyElementsOf(CREDENTIAL_USER_IDS);
        }

        @Test
        @DisplayName("splits five administrators and five standard users, administrators first")
        void splitsFiveAdministratorsAndFiveUsers() throws IOException {
            final List<String> types = new ArrayList<>();
            for (final byte[] record : credentialRecords()) {
                types.add(new String(record, CREDENTIAL_TYPE_OFFSET, 1, StandardCharsets.ISO_8859_1));
            }

            // This census is what the role split is built on, so it is asserted rather than assumed.
            assertThat(types).as("the type byte must be present on all ten records")
                    .hasSize(CREDENTIAL_RECORD_COUNT);
            assertThat(types.subList(0, ADMINISTRATOR_COUNT))
                    .as("the first five records must carry the administrative type")
                    .containsOnly("A");
            assertThat(types.subList(ADMINISTRATOR_COUNT, CREDENTIAL_RECORD_COUNT))
                    .as("the last five records must carry the standard type")
                    .containsOnly("U");
        }

        @Test
        @DisplayName("pads every record from the card width to the record width with spaces")
        void padsEveryRecordWithSpaces() throws IOException {
            // The provisioning job carries 57-character cards; the record layout reserves 80. The
            // difference is filler, and it has to be blank or the trailing field would carry content
            // the layout does not define.
            final String expectedFiller =
                    " ".repeat(CREDENTIAL_RECORD_WIDTH - CREDENTIAL_CARD_WIDTH);

            final List<byte[]> records = credentialRecords();
            for (int index = 0; index < records.size(); index++) {
                final String filler = new String(records.get(index), CREDENTIAL_CARD_WIDTH,
                        CREDENTIAL_RECORD_WIDTH - CREDENTIAL_CARD_WIDTH, StandardCharsets.ISO_8859_1);
                assertThat(filler)
                        .as("record %d: the filler must be blank across its full width", index)
                        .isEqualTo(expectedFiller);
            }
        }

        @Test
        @DisplayName("reserves eight non-blank bytes for the credential, identical on every record")
        void reservesTheCredentialFieldOnEveryRecord() throws IOException {
            // Measured and compared, never read out. The seed migration digests one value ten times
            // with ten different salts, so proving the source carries ONE value on all ten records is
            // what makes ten distinct digests the expected outcome rather than a discrepancy.
            final int credentialOffset = 48;
            final int credentialWidth = 8;

            final Set<String> distinct = new TreeSet<>();
            final List<byte[]> records = credentialRecords();
            for (int index = 0; index < records.size(); index++) {
                final String reserved = new String(records.get(index), credentialOffset,
                        credentialWidth, StandardCharsets.ISO_8859_1);
                assertThat(reserved.isBlank())
                        .as("record %d: the credential field must not be blank", index)
                        .isFalse();
                assertThat(reserved.length())
                        .as("record %d: the credential field must occupy its full width", index)
                        .isEqualTo(credentialWidth);
                distinct.add(reserved);
            }

            assertThat(distinct)
                    .as("all ten records must reserve the same single credential value")
                    .hasSize(1);
        }

        @Test
        @DisplayName("holds no control-language text: it is data, not a copy of the job that wrote it")
        void holdsNoControlLanguageText() throws IOException {
            // The fixture is derived from ten in-stream data cards. The job control surrounding those
            // cards must not have come with them.
            final String content =
                    new String(fixtureBytes(CREDENTIAL_FIXTURE), StandardCharsets.ISO_8859_1);

            assertThat(content)
                    .as("no job-control or utility text may appear in a data fixture")
                    .doesNotContain("//")
                    .doesNotContain("DD ")
                    .doesNotContain("EXEC ")
                    .doesNotContain("DSN=")
                    .doesNotContain("DCB=")
                    .doesNotContain("/*")
                    .doesNotContain("SYSUT")
                    .doesNotContain("SYSPRINT")
                    .doesNotContain("SYSIN")
                    .doesNotContain("NOTIFY")
                    .doesNotContain("IEBGENER")
                    .doesNotContain("IEFBR14")
                    .doesNotContain("IDCAMS")
                    .doesNotContain("REPRO")
                    .doesNotContain("DEFINE CLUSTER")
                    .doesNotContain("RECORDSIZE")
                    .doesNotContain("Ver:");
        }

        @Test
        @DisplayName("is committed, not generated: it is present as a file on disk")
        void isCommittedRatherThanGenerated() throws IOException {
            // The classpath copy could in principle come from a build step. The committed copy is the
            // one that has to exist, and the two have to agree byte for byte.
            final Path committed = Paths.get(COMMITTED_DIRECTORY, CREDENTIAL_FIXTURE);

            assertThat(Files.isRegularFile(committed))
                    .as("%s must be committed under %s", CREDENTIAL_FIXTURE, COMMITTED_DIRECTORY)
                    .isTrue();
            assertThat(Files.size(committed))
                    .as("the committed credential fixture must measure ten eighty-byte records")
                    .isEqualTo(CREDENTIAL_TOTAL_BYTES);
            assertThat(Files.readAllBytes(committed))
                    .as("the classpath copy and the committed copy must agree byte for byte")
                    .isEqualTo(fixtureBytes(CREDENTIAL_FIXTURE));
        }

        /**
         * Splits the credential fixture into records on the fixed stride.
         *
         * @return the ten records, each exactly one record width long
         * @throws IOException if the fixture cannot be read
         */
        private List<byte[]> credentialRecords() throws IOException {
            final byte[] content = fixtureBytes(CREDENTIAL_FIXTURE);
            assertThat(content)
                    .as("the credential fixture must be whole before it can be split")
                    .hasSize(CREDENTIAL_TOTAL_BYTES);

            final List<byte[]> records = new ArrayList<>();
            for (int offset = 0; offset < content.length; offset += CREDENTIAL_RECORD_WIDTH) {
                records.add(Arrays.copyOfRange(content, offset, offset + CREDENTIAL_RECORD_WIDTH));
            }
            return records;
        }
    }

    @Nested
    @DisplayName("agreement with the legacy datasets, when they are present")
    final class AgreementWithTheLegacyDatasets {

        @Test
        @DisplayName("the legacy tree is either wholly present or wholly absent, never partial")
        void theLegacyTreeIsWhollyPresentOrWhollyAbsent() {
            // This is what stops the comparison below from being skippable by accident. A half-deleted
            // legacy tree would otherwise let the byte comparison quietly cover only some files while
            // still reporting as skipped or passing.
            final int present = authorityFilesPresent();

            assertThat(present)
                    .as("the legacy dataset directory %s holds %s of the %s named datasets; a "
                            + "partially present tree is neither a usable authority nor a clean "
                            + "absence", AUTHORITY_DIRECTORY, present, EXPECTED_FIXTURE_COUNT)
                    .isIn(0, EXPECTED_FIXTURE_COUNT);
        }

        @Test
        @DisplayName("every fixture is byte identical to the dataset it was copied from")
        void everyFixtureIsByteIdenticalToItsDataset() throws IOException {
            // The direct comparison. Skipped rather than failed when the legacy tree is absent,
            // because the module is required to build with no reference to it - the digest rows above
            // are what carry this same guarantee in that case. Differences are collected so a failure
            // names every drifted file at once.
            Assumptions.assumeTrue(authorityFilesPresent() == EXPECTED_FIXTURE_COUNT,
                    "the legacy dataset directory is not present in this checkout, so the pinned "
                            + "digests are the operative authority comparison");

            final Path directory = authorityDirectory();
            final Map<String, String> differences = new TreeMap<>();
            for (final String fileName : FIXTURE_NAMES) {
                final byte[] authority = Files.readAllBytes(directory.resolve(fileName));
                final byte[] carried = fixtureBytes(fileName);
                if (!Arrays.equals(authority, carried)) {
                    differences.put(fileName,
                            "authority " + authority.length + " bytes / carried " + carried.length
                                    + " bytes");
                }
            }

            assertThat(differences)
                    .as("each carried fixture must be byte identical to %s; file -> measurement",
                            AUTHORITY_DIRECTORY)
                    .isEmpty();
        }

        @Test
        @DisplayName("the pinned digests are the digests of the legacy datasets themselves")
        void thePinnedDigestsAreTheDigestsOfTheDatasets() throws IOException {
            // Closes the loop between the two layers. The digest rows judge the carried copies; this
            // confirms those same literals describe the authority, so the citation layer and the
            // comparison layer cannot disagree about what the contract is.
            Assumptions.assumeTrue(authorityFilesPresent() == EXPECTED_FIXTURE_COUNT,
                    "the legacy dataset directory is not present in this checkout");

            final Path directory = authorityDirectory();
            final Map<String, String> authorityDigests = new TreeMap<>();
            for (final String fileName : FIXTURE_NAMES) {
                authorityDigests.put(fileName,
                        sha256Hex(Files.readAllBytes(directory.resolve(fileName))));
            }

            final Map<String, String> carriedDigests = new TreeMap<>();
            for (final String fileName : FIXTURE_NAMES) {
                carriedDigests.put(fileName, sha256Hex(fixtureBytes(fileName)));
            }

            assertThat(authorityDigests)
                    .as("the digests of the legacy datasets and of the carried fixtures must agree")
                    .isEqualTo(carriedDigests);
        }
    }

    @Nested
    @DisplayName("daily-transaction composition")
    final class DailyTransactionComposition {

        /** The fixture this group measures. */
        private static final String FIXTURE = "dailytran.txt";

        @Test
        @DisplayName("the source marker splits the file into the recorded purchase and return counts")
        void theSourceMarkerSplitsTheFileAsRecorded() throws IOException {
            // The two markers are the only values this field takes, which matters as much as the
            // counts: a third marker would mean a record whose posting direction this suite has not
            // accounted for.
            final Map<String, Integer> bySource = new TreeMap<>();
            for (final String record : fixtureRecords(FIXTURE)) {
                bySource.merge(field(record, SOURCE_OFFSET, SOURCE_WIDTH), 1, Integer::sum);
            }

            assertThat(bySource)
                    .as("the source field must carry only the two known markers, in the recorded "
                            + "proportions")
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            POINT_OF_SALE_MARKER, EXPECTED_POINT_OF_SALE_RECORDS,
                            OPERATOR_MARKER, EXPECTED_OPERATOR_RECORDS));
            assertThat(EXPECTED_POINT_OF_SALE_RECORDS + EXPECTED_OPERATOR_RECORDS)
                    .isEqualTo(EXPECTED_DAILY_TRANSACTION_RECORDS);
        }

        @Test
        @DisplayName("purchases carry a positive overpunch and returns a negative one, with no overlap")
        void theOverpunchSignPartitionsExactlyAlongTheSourceMarker() throws IOException {
            // This is the property that makes the fixture adequate rather than merely well formed.
            // The sign of a zoned decimal lives in its final byte, and here that byte's alphabet
            // correlates exactly with the source marker - so both the debit and the credit posting
            // path are reachable from seeded data, and neither has to be synthesised. The two
            // alphabets are written out by hand and are disjoint by construction.
            final Map<String, Set<Character>> signsBySource = new TreeMap<>();
            for (final String record : fixtureRecords(FIXTURE)) {
                final String source = field(record, SOURCE_OFFSET, SOURCE_WIDTH);
                final String amount = field(record, AMOUNT_OFFSET, AMOUNT_WIDTH);
                signsBySource
                        .computeIfAbsent(source, key -> new TreeSet<>())
                        .add(amount.charAt(AMOUNT_WIDTH - 1));
            }

            assertThat(POSITIVE_OVERPUNCH_ALPHABET.chars().mapToObj(character -> (char) character))
                    .as("the two overpunch alphabets must not share a character")
                    .doesNotContainAnyElementsOf(NEGATIVE_OVERPUNCH_ALPHABET.chars()
                            .mapToObj(character -> (char) character).toList());

            assertThat(signsBySource.get(POINT_OF_SALE_MARKER))
                    .as("every purchase must carry a non-negative overpunch")
                    .isNotEmpty()
                    .allSatisfy(sign -> assertThat(POSITIVE_OVERPUNCH_ALPHABET)
                            .contains(String.valueOf(sign)));
            assertThat(signsBySource.get(OPERATOR_MARKER))
                    .as("every operator-originated return must carry a negative overpunch")
                    .isNotEmpty()
                    .allSatisfy(sign -> assertThat(NEGATIVE_OVERPUNCH_ALPHABET)
                            .contains(String.valueOf(sign)));
        }

        @Test
        @DisplayName("the leading amount digits are digits, so only the final byte carries the sign")
        void onlyTheFinalAmountByteCarriesTheSign() throws IOException {
            // Confirms the overpunch reading above is the right reading. If a sign were carried
            // separately - leading, or trailing outside the field - then some leading position would
            // not be a digit, and the final byte's alphabet would not be doing the work.
            final Set<Character> leadingCharacters = new TreeSet<>();
            for (final String record : fixtureRecords(FIXTURE)) {
                final String amount = field(record, AMOUNT_OFFSET, AMOUNT_WIDTH);
                for (int index = 0; index < AMOUNT_WIDTH - 1; index++) {
                    leadingCharacters.add(amount.charAt(index));
                }
            }

            assertThat(leadingCharacters)
                    .as("every amount position but the last must be a plain digit")
                    .containsExactly('0', '1', '2', '3', '4', '5', '6', '7', '8', '9');
        }

        @Test
        @DisplayName("every processing timestamp is blank and all records share one origination stamp")
        void everyProcessingTimestampIsBlankAndOriginationIsUniform() throws IOException {
            // The blank processing timestamp is what makes a posting run against this file meaningful:
            // these are unposted transactions. The single origination timestamp is a LIMITATION, and
            // it is asserted so that it is on the record: a date-window filter cannot be exercised
            // from this fixture, because every record would fall on the same side of any window.
            final Set<String> processingStamps = new TreeSet<>();
            final Set<String> originationStamps = new TreeSet<>();
            for (final String record : fixtureRecords(FIXTURE)) {
                processingStamps.add(
                        field(record, PROCESSING_TIMESTAMP_OFFSET, TIMESTAMP_WIDTH));
                originationStamps.add(
                        field(record, ORIGINATION_TIMESTAMP_OFFSET, TIMESTAMP_WIDTH));
            }

            assertThat(processingStamps)
                    .as("every processing timestamp must be blank across its full width")
                    .containsExactly(" ".repeat(TIMESTAMP_WIDTH));
            assertThat(originationStamps)
                    .as("all records share one origination timestamp, so this fixture cannot "
                            + "exercise a date window")
                    .hasSize(1);
            assertThat(originationStamps.iterator().next())
                    .as("the shared origination timestamp must fill its field width")
                    .hasSize(TIMESTAMP_WIDTH)
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("disclosure-group composition")
    final class DisclosureGroupComposition {

        /** The fixture this group measures. */
        private static final String FIXTURE = "discgrp.txt";

        @Test
        @DisplayName("three complete groups of equal size, under the three recorded keys")
        void threeCompleteGroupsUnderTheRecordedKeys() throws IOException {
            // Completeness is the point. The interest calculation looks a group up and falls back to
            // the fallback group when the lookup misses, so a fixture that carried the fallback key
            // but not a full set of rows under it would leave the fallback branch only partly
            // reachable.
            final Map<String, Integer> byGroup = new LinkedHashMap<>();
            for (final String record : fixtureRecords(FIXTURE)) {
                byGroup.merge(field(record, 0, DISCLOSURE_GROUP_KEY_WIDTH), 1, Integer::sum);
            }

            assertThat(byGroup.keySet())
                    .as("the file must hold exactly the three recorded group keys")
                    .containsExactlyElementsOf(DISCLOSURE_GROUP_KEYS);
            assertThat(byGroup.values())
                    .as("each group must be complete and the same size")
                    .allSatisfy(count -> assertThat(count).isEqualTo(RECORDS_PER_DISCLOSURE_GROUP));
            assertThat(DISCLOSURE_GROUP_KEYS.size() * RECORDS_PER_DISCLOSURE_GROUP)
                    .as("three complete groups must account for every record in the file")
                    .isEqualTo(fixtureRecords(FIXTURE).size());
        }

        @Test
        @DisplayName("the groups are contiguous, so a keyed read does not have to span the file")
        void theGroupsAreContiguous() throws IOException {
            // Contiguity is how the file was built and is worth pinning: it means the records of one
            // group are adjacent, which is the layout a keyed sequential read over the legacy cluster
            // would have seen.
            final List<String> keysInFileOrder = new ArrayList<>();
            for (final String record : fixtureRecords(FIXTURE)) {
                final String key = field(record, 0, DISCLOSURE_GROUP_KEY_WIDTH);
                if (keysInFileOrder.isEmpty()
                        || !keysInFileOrder.get(keysInFileOrder.size() - 1).equals(key)) {
                    keysInFileOrder.add(key);
                }
            }

            assertThat(keysInFileOrder)
                    .as("each group key must appear as one unbroken run, in the recorded order")
                    .containsExactlyElementsOf(DISCLOSURE_GROUP_KEYS);
        }
    }

    @Nested
    @DisplayName("reference-table composition")
    final class ReferenceTableComposition {

        @Test
        @DisplayName("the transaction-type fixture declares its seven keys, each once")
        void theTransactionTypeFixtureDeclaresSevenDistinctKeys() throws IOException {
            final List<String> keys = new ArrayList<>();
            for (final String record : fixtureRecords("trantype.txt")) {
                keys.add(field(record, 0, TRANSACTION_TYPE_KEY_WIDTH));
            }

            assertThat(keys)
                    .as("the transaction-type reference table must declare each key exactly once")
                    .doesNotHaveDuplicates()
                    .containsExactlyElementsOf(TRANSACTION_TYPE_KEYS);
        }

        @Test
        @DisplayName("the transaction-category fixture declares its eighteen composite keys, each once")
        void theTransactionCategoryFixtureDeclaresEighteenDistinctKeys() throws IOException {
            final List<String> keys = new ArrayList<>();
            for (final String record : fixtureRecords("trancatg.txt")) {
                keys.add(field(record, 0, TRANSACTION_CATEGORY_KEY_WIDTH));
            }

            assertThat(keys)
                    .as("the transaction-category reference table must declare each composite key "
                            + "exactly once")
                    .doesNotHaveDuplicates()
                    .containsExactlyElementsOf(TRANSACTION_CATEGORY_KEYS);
        }

        @Test
        @DisplayName("every category key begins with a type the transaction-type fixture declares")
        void everyCategoryKeyReferencesADeclaredType() throws IOException {
            // The composite key is a type followed by a category, so the two reference tables have to
            // agree. A category naming an undeclared type would be a dangling reference the seed
            // migration would carry into the schema.
            final Set<String> declaredTypes = new TreeSet<>();
            for (final String record : fixtureRecords("trantype.txt")) {
                declaredTypes.add(field(record, 0, TRANSACTION_TYPE_KEY_WIDTH));
            }

            final Set<String> referencedTypes = new TreeSet<>();
            for (final String record : fixtureRecords("trancatg.txt")) {
                referencedTypes.add(field(record, 0, TRANSACTION_TYPE_KEY_WIDTH));
            }

            assertThat(declaredTypes)
                    .as("the declared transaction types must cover every type a category references")
                    .containsAll(referencedTypes);
        }
    }

    @Nested
    @DisplayName("what distinguishes this suite from arithmetic over literals")
    final class WhatDistinguishesThisFromArithmeticOverLiterals {

        @Test
        @DisplayName("the digest check rejects a single altered byte")
        void theDigestCheckRejectsASingleAlteredByte() throws IOException {
            // Anti-vacuity. Every assertion in this suite reads bytes, so the suite has to demonstrate
            // that reading bytes is what makes it bind. A copy differing in one byte - same length,
            // same record count, same widths, same terminators - passes every geometric check and must
            // still be rejected by the digest.
            final byte[] genuine = fixtureBytes("trantype.txt");
            final byte[] altered = genuine.clone();
            final int lastDataByte = altered.length - 2;
            altered[lastDataByte] = (byte) (altered[lastDataByte] == 'X' ? 'Y' : 'X');

            assertThat(altered.length)
                    .as("the altered copy must be the same length, so only content differs")
                    .isEqualTo(genuine.length);
            assertThat(sha256Hex(altered))
                    .as("a one-byte edit must change the digest")
                    .isNotEqualTo(sha256Hex(genuine));
            assertThat(sha256Hex(genuine))
                    .as("and the genuine content must still match its pinned digest")
                    .isEqualTo("3e0ae0040d3ac6828edbaa885d6db1c65edbcf0477e984764508ea01c5b6ecee");
        }

        @Test
        @DisplayName("a fixture that is absent fails rather than being silently treated as empty")
        void anAbsentFixtureFailsRatherThanReadingAsEmpty() {
            // The failure mode this suite exists to prevent is a check that passes because it looked at
            // nothing. Naming a file that is not there must fail, so that the parameterized rows above
            // cannot degrade into no-ops if the fixtures ever stop being packaged onto the classpath.
            assertThat(FixtureContractTest.class.getResourceAsStream(
                    FIXTURE_DIRECTORY + "no-such-fixture.txt"))
                    .as("an absent fixture must resolve to nothing, not to an empty stream")
                    .isNull();

            assertThat(org.assertj.core.api.Assertions
                    .catchThrowable(() -> fixtureBytes("no-such-fixture.txt")))
                    .as("the reader this suite uses must fail loudly on an absent fixture")
                    .isNotNull();
        }

        @Test
        @DisplayName("each of the nine is opened by this suite, including the one no other suite reads")
        void eachOfTheNineIsOpenedHere() throws IOException {
            // The cross-reference fixture is named in comments and in one arithmetic row elsewhere but
            // is opened by no other suite at this checkout, because no cross-reference record mapper
            // ships. Reading all nine here - and asserting each yields content - is what makes the
            // coverage of this file family complete rather than eight ninths complete.
            final Map<String, Integer> measured = new LinkedHashMap<>();
            for (final String fileName : FIXTURE_NAMES) {
                measured.put(fileName, fixtureBytes(fileName).length);
            }

            assertThat(measured)
                    .as("every named fixture must be readable from this suite")
                    .hasSize(EXPECTED_FIXTURE_COUNT)
                    .allSatisfy((fileName, length) -> assertThat(length)
                            .as("%s must hold content", fileName)
                            .isPositive());
            assertThat(measured.get("cardxref.txt"))
                    .as("the cross-reference fixture is read here even though nothing else reads it")
                    .isEqualTo(1850);
        }
    }
}
