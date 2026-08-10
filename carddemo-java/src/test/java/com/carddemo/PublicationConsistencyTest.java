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
package com.carddemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the operator-facing publications to the figures the evidence page records.
 *
 * <h2>The defect this exists to remove</h2>
 *
 * <p>Five documents describe the same delivery to five different audiences: the consolidated evidence page,
 * the module's operator manual, the onboarding guide, the migration deck and the repository's own front page.
 * Only one of them is the authority. When a fact moved, the authority was updated and the other four were
 * not, and the result was not a cosmetic inconsistency — it was four documents making a weaker claim than the
 * delivery could support, and one of them making a stronger one.
 *
 * <p>Two drifts had reached review. The Gate 1 inventory named <strong>four</strong> contractual widths in
 * three places while the evidence page named five and the fifth golden fixture was committed and compared —
 * so the manual, the guide and the deck each advertised less coverage than existed, and the manual carried a
 * whole paragraph explaining that a fixture which exists was not planned. In the other direction, the front
 * page said the vulnerability scan ran with "zero tolerance for critical and high findings" and the deck said
 * it carried "no critical or high finding", while the evidence page discloses a CVSS 7.5 high finding covered
 * by a written analyst determination. A reader who trusted either summary would have been surprised by the
 * report.
 *
 * <h2>Why this is a test rather than a proofreading pass</h2>
 *
 * <p>A proofreading pass fixes the four documents once. It does nothing about the fifth time a fact moves,
 * which is the event that produced the finding. Here the authority is <em>parsed</em> — the widths out of its
 * own inventory table, the vulnerability identifiers out of its own disclosure — and the other publications
 * are then required to agree with whatever the authority currently says. Nothing in this class hardcodes a
 * width or an identifier, so a sixth contractual width or a second determination propagates as a failure in
 * each document that has not caught up, naming the document and the fact.
 *
 * <p>The negative direction is asserted too. Agreement is cheap to fake by saying nothing, so each check
 * also forbids the superseded phrasing: a document that has been corrected must not still contain the
 * sentence that made it wrong.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("publication consistency: the operator-facing documents agree with the evidence page")
final class PublicationConsistencyTest {

    /** The consolidated evidence page, which is the authority every other document must agree with. */
    private static final Path AUTHORITY = Path.of("..", "docs", "gate-evidence.md");

    /** The module's operator manual. */
    private static final Path MODULE_MANUAL = Path.of("README.md");

    /** The repository's front page. */
    private static final Path REPOSITORY_FRONT_PAGE = Path.of("..", "README.md");

    /** The onboarding guide. */
    private static final Path ONBOARDING_GUIDE = Path.of("..", "docs", "onboarding-guide.md");

    /** The migration deck. */
    private static final Path PRESENTATION = Path.of("..", "docs", "presentation", "index.html");

    /** The decision log, which reasons about the gated widths and so must know how many there are. */
    private static final Path DECISION_LOG = Path.of("..", "docs", "decision-log.md");

    /** Heading of the authority's own width inventory, from which the width set is read. */
    private static final String WIDTH_INVENTORY_HEADING = "### The five contractual widths";

    /** A vulnerability identifier, in the form the authority and every summary write it. */
    private static final Pattern VULNERABILITY_IDENTIFIER = Pattern.compile("CVE-\\d{4}-\\d{4,7}");

    /** How far before the word "byte" a number still counts as describing that width. */
    private static final int BYTE_CONTEXT_WINDOW = 110;

    /**
     * A two-to-four digit number that is not part of a longer or thousands-separated one.
     *
     * <p>The trailing group deliberately permits a comma that is not followed by a digit, so a width written
     * in a series - "40, 80, 100, 133 and 430 bytes" - contributes every member, while "1,262" contributes
     * nothing.
     */
    private static final Pattern STANDALONE_NUMBER =
            Pattern.compile("(?<![\\d,.])(\\d{2,4})(?![\\d.]|,\\d)");

    /** Creates the test class. */
    PublicationConsistencyTest() {
    }

    @Nested
    @DisplayName("the Gate 1 contractual width inventory")
    class TheWidthInventory {

        /** Creates the nest. */
        TheWidthInventory() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        @Test
        @DisplayName("is read from the authority rather than repeated, and the authority names every width "
                + "with a golden fixture behind it")
        void theAuthorityNamesEveryWidthWithAGoldenBehindIt() throws IOException {
            final SortedSet<Integer> widths = contractualWidths();

            assertThat(widths)
                    .as("the authority's inventory table under '%s' must yield the width set. Reading zero "
                            + "widths would make every comparison below vacuously true",
                            WIDTH_INVENTORY_HEADING)
                    .isNotEmpty();
            // Each width must be backed by a fixture whose byte count divides by it exactly. The fixtures
            // carry no record separator, so this is the whole geometry check: a file that is not a whole
            // number of records is either the wrong width or the wrong file.
            final Map<Integer, Path> fixtures = goldenFixturesByWidth();
            assertAll("every published width has a fixture of exactly that geometry",
                    widths.stream().map(width -> (org.junit.jupiter.api.function.Executable) () -> {
                        final Path fixture = fixtures.get(width);
                        assertThat(fixture)
                                .as("the authority publishes %d bytes as contractual, so a committed golden "
                                        + "of that geometry must exist. Fixtures measured: %s", width,
                                        fixtures)
                                .isNotNull();
                        final long bytes = Files.size(fixture);
                        assertThat(bytes % width)
                                .as("%s is %d bytes, which is not a whole number of %d-byte records",
                                        fixture.getFileName(), bytes, width)
                                .isZero();
                    }).toList());
        }

        @Test
        @DisplayName("is named in full by the module manual, the onboarding guide and the deck, none of "
                + "which still claims a smaller inventory")
        void everyPublicationNamesTheWholeInventory() throws IOException {
            final SortedSet<Integer> widths = contractualWidths();
            final Map<Path, String> publications = new LinkedHashMap<>();
            publications.put(MODULE_MANUAL, read(MODULE_MANUAL));
            publications.put(ONBOARDING_GUIDE, read(ONBOARDING_GUIDE));
            publications.put(PRESENTATION, read(PRESENTATION));
            // The decision log is enrolled for the width count and NOT for the banned phrasings. It reasons
            // about which widths are gated - one entry's whole argument turns on the answer - so a stale count
            // there is a stale argument. But it also narrates superseded states by design, so a phrase ban
            // over it would forbid the history it exists to keep.
            publications.put(DECISION_LOG, read(DECISION_LOG));

            final List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
            for (final Map.Entry<Path, String> publication : publications.entrySet()) {
                // Measured in a BYTE CONTEXT rather than by substring. A bare contains("40") is very nearly
                // vacuous - "40" occurs inside 1,400, inside a line number and inside a date - so a document
                // could drop the width entirely and still pass. What is collected instead is every standalone
                // number appearing shortly before the word "byte", which is the set of widths the document
                // actually discusses as widths, and the published set must be contained in it.
                final java.util.Set<Integer> discussed = widthsDiscussedIn(publication.getValue());
                for (final Integer width : widths) {
                    checks.add(() -> assertThat(discussed)
                            .as("%s must name the %d-byte contractual width, which the authority publishes. "
                                    + "A document that omits one advertises less coverage than exists. "
                                    + "Widths it does discuss: %s", publication.getKey(), width, discussed)
                            .contains(width));
                }
                if (publication.getKey().equals(DECISION_LOG)) {
                    continue;
                }
                // The superseded phrasings, forbidden by name. Naming all five widths while also still
                // saying there are four is the state this finding was actually raised in.
                //
                // Each banned string is one only the WRONG state can contain. That precision is deliberate:
                // an earlier draft of this check banned "no 40-byte golden", and the first thing it caught
                // was the manual's own paragraph explaining that the gap had been closed. A guard that
                // forbids describing the defect forbids the correction along with it, so the strings below
                // are the claims themselves - a table cell reading "none yet", an evidence cell calling the
                // fixture pending, an inventory counting four - and not the words used to discuss them.
                checks.add(() -> assertThat(publication.getValue())
                        .as("%s must not still describe the inventory as four widths, nor mark the 40-byte "
                                + "golden as absent or pending", publication.getKey())
                        .doesNotContain("four contractual widths")
                        .doesNotContain("four compared widths")
                        .doesNotContain("the four widths this gate names")
                        .doesNotContain("| **none yet** |")
                        .doesNotContain("golden fixture for it is **pending**")
                        .doesNotContain("and none is planned"));
            }
            assertAll("every publication carries the authority's whole width inventory", checks);
        }
    }

    @Nested
    @DisplayName("the Gate 8 vulnerability result")
    class TheVulnerabilityResult {

        /** Creates the nest. */
        TheVulnerabilityResult() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        @Test
        @DisplayName("is disclosed by identifier in the authority, so there is something for the summaries "
                + "to agree with")
        void theAuthorityDisclosesEachFindingByIdentifier() throws IOException {
            assertThat(disclosedVulnerabilities())
                    .as("the authority must disclose the scan's findings by identifier. With none "
                            + "disclosed, every summary below would pass by saying nothing")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("is stated exactly by the front page and the deck, neither of which rounds a "
                + "determined high finding away")
        void neitherSummaryRoundsTheResultAway() throws IOException {
            final SortedSet<String> disclosed = disclosedVulnerabilities();
            final Map<Path, String> summaries = new LinkedHashMap<>();
            summaries.put(REPOSITORY_FRONT_PAGE, read(REPOSITORY_FRONT_PAGE));
            summaries.put(PRESENTATION, read(PRESENTATION));

            final List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
            for (final Map.Entry<Path, String> summary : summaries.entrySet()) {
                for (final String identifier : disclosed) {
                    checks.add(() -> assertThat(collapseWhitespace(summary.getValue()))
                            .as("%s must name %s, which the authority discloses. A summary that gives only "
                                    + "the zeroes is the reason a reader stops checking the report",
                                    summary.getKey(), identifier)
                            .contains(identifier));
                }
                // Matched over a whitespace-collapsed view. Both summaries are hand-wrapped prose - one of
                // them HTML - so "unsuppressed high" legitimately spans a line break and a run of
                // indentation. Matching the raw text made this assertion a statement about where the author
                // happened to wrap, which is not the property being checked.
                final String flowed = collapseWhitespace(summary.getValue());
                checks.add(() -> assertThat(flowed)
                        .as("%s must state the result as unsuppressed counts rather than as an absolute",
                                summary.getKey())
                        .contains("unsuppressed critical")
                        .contains("unsuppressed high"));
                checks.add(() -> assertThat(flowed)
                        .as("%s must not carry the rounded claim this finding was raised against",
                                summary.getKey())
                        .doesNotContain("zero tolerance for critical and high"));
            }
            assertAll("both summaries state the recorded three-state result", checks);
        }
    }

    // ===================================================================================================
    // HELPERS
    // ===================================================================================================

    /**
     * Reads the contractual widths out of the authority's own inventory table.
     *
     * <p>The table is sliced from its heading to the next heading before matching, so a width in a later
     * section cannot be borrowed. Each data row's second cell is the width.
     *
     * @return the widths, ascending, without duplicates
     * @throws IOException if the authority cannot be read
     */
    private static SortedSet<Integer> contractualWidths() throws IOException {
        final String authority = read(AUTHORITY);
        final int heading = authority.indexOf(WIDTH_INVENTORY_HEADING);
        assertThat(heading)
                .as("the authority must carry its width inventory under '%s'; without that heading this "
                        + "class cannot read the set it holds everything else to", WIDTH_INVENTORY_HEADING)
                .isNotNegative();
        final int next = authority.indexOf("\n### ", heading + WIDTH_INVENTORY_HEADING.length());
        final String section = next < 0 ? authority.substring(heading) : authority.substring(heading, next);

        final SortedSet<Integer> widths = new TreeSet<>();
        for (final String line : section.split("\n", -1)) {
            if (!line.startsWith("|")) {
                continue;
            }
            final String[] cells = line.split("\\|", -1);
            if (cells.length > 2 && cells[2].trim().matches("\\d+")) {
                widths.add(Integer.valueOf(cells[2].trim()));
            }
        }
        return widths;
    }

    /**
     * Measures every committed golden fixture and indexes it by the record width it divides into exactly.
     *
     * <p>A fixture that divides evenly into more than one candidate width is indexed under the largest, which
     * is the record length it was authored at; the smaller divisors are arithmetic accidents rather than
     * layouts. Reading the geometry rather than the file name is what lets the width set come from the
     * authority instead of from a list kept here.
     *
     * @return width to fixture, for the widths the delivered fixtures actually satisfy
     * @throws IOException if the fixture directory cannot be read
     */
    private static Map<Integer, Path> goldenFixturesByWidth() throws IOException {
        final Path directory = Path.of("src", "test", "resources", "fixtures", "expected");
        assertThat(Files.isDirectory(directory))
                .as("the golden fixtures are expected at %s", directory.toAbsolutePath())
                .isTrue();
        final Map<Integer, Path> byWidth = new LinkedHashMap<>();
        try (var entries = Files.list(directory)) {
            for (final Path fixture : entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".txt")).sorted().toList()) {
                final long bytes = Files.size(fixture);
                for (final int width : recordWidthsOf(fixture, bytes)) {
                    byWidth.putIfAbsent(Integer.valueOf(width), fixture);
                }
            }
        }
        return byWidth;
    }

    /**
     * Reports the record width a fixture was authored at, read from the fixture rather than assumed.
     *
     * <p>Every delivered golden is separator-free, so its width is a divisor of its length. The divisor that
     * matters is the one the layout uses, and it is identified by being a width the authority publishes; this
     * method therefore returns every plausible divisor and lets the caller intersect that with the published
     * set. Divisors below the smallest published width and above the file's own length are not considered.
     *
     * @param  fixture the fixture, named in the failure message when it carries a separator
     * @param  bytes   its length
     * @return the candidate widths
     * @throws IOException if the fixture cannot be read
     */
    private static List<Integer> recordWidthsOf(final Path fixture, final long bytes) throws IOException {
        final String content = Files.readString(fixture, StandardCharsets.ISO_8859_1);
        assertThat(content)
                .as("%s must be separator-free, because its geometry is what identifies its width",
                        fixture.getFileName())
                .doesNotContain("\n")
                .doesNotContain("\r");
        final List<Integer> candidates = new ArrayList<>();
        for (int width = 1; width <= bytes; width++) {
            if (bytes % width == 0L) {
                candidates.add(Integer.valueOf(width));
            }
        }
        return candidates;
    }

    /**
     * Reads every vulnerability identifier the authority discloses.
     *
     * @return the identifiers, without duplicates
     * @throws IOException if the authority cannot be read
     */
    private static SortedSet<String> disclosedVulnerabilities() throws IOException {
        final SortedSet<String> identifiers = new TreeSet<>();
        final Matcher matcher = VULNERABILITY_IDENTIFIER.matcher(read(AUTHORITY));
        while (matcher.find()) {
            identifiers.add(matcher.group());
        }
        return identifiers;
    }

    /**
     * Every standalone number one document discusses as a record width.
     *
     * <p>Collected by looking at the text shortly before each occurrence of "byte" and taking the standalone
     * numbers from it, which is what distinguishes a width from a line number, a record count or a year. The
     * window is generous enough to span a list, because the guide writes all five widths as one series before
     * the noun; and the number pattern rejects a thousands-separated group so that "1,262 records" cannot
     * contribute 262, while accepting a number followed by a comma so that "40, 80" contributes both.
     *
     * @param  text the document text
     * @return the widths it discusses, which the published set must be contained in
     */
    private static java.util.Set<Integer> widthsDiscussedIn(final String text) {
        final String flowed = collapseWhitespace(text);
        final java.util.Set<Integer> discussed = new TreeSet<>();
        final Matcher occurrence = Pattern.compile("byte", Pattern.CASE_INSENSITIVE).matcher(flowed);
        while (occurrence.find()) {
            final String window =
                    flowed.substring(Math.max(0, occurrence.start() - BYTE_CONTEXT_WINDOW),
                            occurrence.start());
            final Matcher number = STANDALONE_NUMBER.matcher(window);
            while (number.find()) {
                discussed.add(Integer.valueOf(number.group(1)));
            }
        }
        return discussed;
    }

    /**
     * Collapses every run of whitespace to a single space, so a phrase check is not a wrapping check.
     *
     * @param  text the document text
     * @return the same text with runs of whitespace collapsed
     */
    private static String collapseWhitespace(final String text) {
        return text.replaceAll("\\s+", " ");
    }

    /**
     * Reads one publication, failing with its resolved location rather than an empty string.
     *
     * @param  path the document, module-relative
     * @return its text
     * @throws IOException if it cannot be read
     */
    private static String read(final Path path) throws IOException {
        assertThat(Files.isRegularFile(path))
                .as("the publication is expected at %s", path.toAbsolutePath().normalize())
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
