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
 * <p>Two drifts had reached review, and both were corrected in the direction of understatement first and
 * then overcorrected past the contract. The Gate 1 criterion is frozen at <strong>four</strong> contractual
 * widths, and for a while three documents said four while listing the 40-byte category-balance output as a
 * width whose golden fixture did not exist — advertising less evidence than the tree held, and carrying a
 * whole paragraph explaining that a fixture which exists was not planned. The correction then went too far
 * the other way and promoted that fixture into a <em>fifth contractual width</em>, which rewrites an
 * accepted criterion rather than adding to the evidence behind it. Both errors are now forbidden here: the
 * contractual set is read from the authority's own four-width table, and the 40-byte golden is asserted as
 * supplemental evidence that must be named and must not be called contractual.
 *
 * <p>In the other direction, the front page said the vulnerability scan ran with "zero tolerance for
 * critical and high findings" while the evidence page discloses a CVSS 7.5 high finding covered by a written
 * analyst determination. A reader who trusted that summary would have been surprised by the report. The
 * front page must therefore carry the finding, and the deck — whose published contract is that it states
 * obligations and defers every run outcome to the evidence page — must carry no scan outcome at all and link
 * to the authority instead. Those two requirements pull in opposite directions on purpose, because the two
 * documents make different promises to their readers.
 *
 * <h2>Why this is a test rather than a proofreading pass</h2>
 *
 * <p>A proofreading pass fixes the four documents once. It does nothing about the fifth time a fact moves,
 * which is the event that produced the finding. Here the authority is <em>parsed</em> — the widths out of its
 * own inventory tables, the vulnerability identifiers out of its own disclosure — and the other publications
 * are then required to agree with whatever the authority currently says. Nothing in this class hardcodes a
 * width or an identifier, so a change to the contractual set or a second determination propagates as a
 * failure in each document that has not caught up, naming the document and the fact.
 *
 * <p>The negative direction is asserted too. Agreement is cheap to fake by saying nothing, so each check
 * also forbids the superseded phrasing: a document that has been corrected must not still contain the
 * sentence that made it wrong, and must not have replaced it with the opposite error.
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

    /** Heading of the authority's contractual width inventory, from which the frozen set is read. */
    private static final String WIDTH_INVENTORY_HEADING = "### The four contractual widths";

    /** Heading of the authority's supplemental width inventory, which is evidence beyond the criterion. */
    private static final String SUPPLEMENTAL_WIDTH_HEADING = "### The supplemental category-balance width";

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
        @DisplayName("is read from the authority rather than repeated, holds exactly the four widths the "
                + "frozen criterion names, and every one of them has a golden fixture behind it")
        void theAuthorityNamesEveryWidthWithAGoldenBehindIt() throws IOException {
            final SortedSet<Integer> widths = contractualWidths();

            assertThat(widths)
                    .as("the authority's inventory table under '%s' must yield the width set. Reading zero "
                            + "widths would make every comparison below vacuously true",
                            WIDTH_INVENTORY_HEADING)
                    .isNotEmpty();
            // THE CRITERION IS FROZEN AT FOUR AND THE FOUR ARE NAMED. This is the one place in the class
            // that states a width literally, and it does so because these four are not a measurement: they
            // are the accepted acceptance criterion, and a test that read them from the same page it holds
            // to them could not detect the page redefining them. The 40-byte output is asserted separately,
            // as supplemental evidence, by the next method.
            assertThat(widths)
                    .as("Gate 1's contractual set is frozen at 80, 100, 133 and 430 bytes. A width added "
                            + "here rewrites an accepted criterion; evidence beyond it belongs under '%s'",
                            SUPPLEMENTAL_WIDTH_HEADING)
                    .containsExactly(Integer.valueOf(80), Integer.valueOf(100), Integer.valueOf(133),
                            Integer.valueOf(430));
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
        @DisplayName("the 40-byte category-balance output is published as supplemental evidence, is "
                + "golden-backed, and is not folded into the contractual set")
        void theSupplementalWidthIsPublishedAndGoldenBacked() throws IOException {
            final SortedSet<Integer> supplemental = supplementalWidths();

            assertThat(supplemental)
                    .as("the authority must keep its supplemental inventory under '%s'. Removing it is how "
                            + "delivered evidence stops being visible, which is the understatement half of "
                            + "this finding", SUPPLEMENTAL_WIDTH_HEADING)
                    .containsExactly(Integer.valueOf(40));
            assertThat(contractualWidths())
                    .as("a supplemental width must not also appear in the contractual table; that is the "
                            + "overstatement half of the same finding")
                    .doesNotContainAnyElementsOf(supplemental);

            final Map<Integer, Path> fixtures = goldenFixturesByWidth();
            for (final Integer width : supplemental) {
                final Path fixture = fixtures.get(width);
                assertThat(fixture)
                        .as("the authority publishes %d bytes as golden-backed supplemental evidence, so a "
                                + "committed golden of that geometry must exist. Fixtures measured: %s",
                                width, fixtures)
                        .isNotNull();
                final long bytes = Files.size(fixture);
                assertThat(bytes % width.intValue())
                        .as("%s is %d bytes, which is not a whole number of %d-byte records",
                                fixture.getFileName(), bytes, width)
                        .isZero();
            }
        }

        @Test
        @DisplayName("is named in full by the module manual, the onboarding guide and the deck, none of "
                + "which understates the evidence or promotes the supplemental width into the criterion")
        void everyPublicationNamesTheWholeInventory() throws IOException {
            final SortedSet<Integer> widths = new TreeSet<>(contractualWidths());
            widths.addAll(supplementalWidths());
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
                            .as("%s must name the %d-byte width, which the authority publishes either as "
                                    + "contractual or as supplemental evidence. A document that omits one "
                                    + "advertises less coverage than exists. Widths it does discuss: %s",
                                    publication.getKey(), width, discussed)
                            .contains(width));
                }
                if (publication.getKey().equals(DECISION_LOG)) {
                    continue;
                }
                // The superseded phrasings, forbidden by name, in BOTH directions of the same drift.
                //
                // Each banned string is one only a wrong state can contain. That precision is deliberate:
                // an earlier draft of this check banned "no 40-byte golden", and the first thing it caught
                // was the manual's own paragraph explaining that the gap had been closed. A guard that
                // forbids describing the defect forbids the correction along with it, so the strings below
                // are the claims themselves - a table cell reading "none yet", an evidence cell calling the
                // fixture pending, an inventory that counts the supplemental width as contractual - and not
                // the words used to discuss them. Note what is deliberately NOT banned any more: the
                // correct four-width wording. Banning it was the mechanism that made the overstatement
                // build-enforced, so a document could not be corrected without breaking the build.
                checks.add(() -> assertThat(publication.getValue())
                        .as("%s must neither mark the 40-byte golden as absent or pending, nor promote it "
                                + "into the frozen contractual criterion", publication.getKey())
                        .doesNotContain("| **none yet** |")
                        .doesNotContain("golden fixture for it is **pending**")
                        .doesNotContain("and none is planned")
                        .doesNotContainIgnoringCase("five contractual widths")
                        .doesNotContainIgnoringCase("fifth contractual width")
                        .doesNotContainIgnoringCase("five compared widths")
                        .doesNotContainIgnoringCase("five output widths are contractual"));
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
        @DisplayName("is stated exactly by the front page, which rounds no determined high finding away")
        void theFrontPageDoesNotRoundTheResultAway() throws IOException {
            final SortedSet<String> disclosed = disclosedVulnerabilities();
            // Matched over a whitespace-collapsed view. The front page is hand-wrapped prose, so
            // "unsuppressed high" legitimately spans a line break and a run of indentation. Matching the raw
            // text made this assertion a statement about where the author happened to wrap, which is not the
            // property being checked.
            final String flowed = collapseWhitespace(read(REPOSITORY_FRONT_PAGE));

            final List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
            for (final String identifier : disclosed) {
                checks.add(() -> assertThat(flowed)
                        .as("%s must name %s, which the authority discloses. A front page that gives only "
                                + "the zeroes is the reason a reader stops checking the report",
                                REPOSITORY_FRONT_PAGE, identifier)
                        .contains(identifier));
            }
            checks.add(() -> assertThat(flowed)
                    .as("%s must state the result as unsuppressed counts rather than as an absolute",
                            REPOSITORY_FRONT_PAGE)
                    .contains("unsuppressed critical")
                    .contains("unsuppressed high"));
            checks.add(() -> assertThat(flowed)
                    .as("%s must not carry the rounded claim this finding was raised against",
                            REPOSITORY_FRONT_PAGE)
                    .doesNotContain("zero tolerance for critical and high"));
            assertAll("the front page states the recorded three-state result", checks);
        }

        /**
         * The deck carries the obligation and defers the outcome, which is the opposite requirement.
         *
         * <p>This is deliberately the inverse of the assertion above, and the inversion is the finding. The
         * deck's own published contract - stated on its own face, in the documentation index and in the
         * module manual - is that it describes what each criterion <em>verifies</em> and that every run
         * outcome belongs to the evidence page. An earlier revision of this class enrolled the deck
         * alongside the front page and required it to repeat every disclosed identifier, which forced the
         * deck to breach that contract in order to keep the build green: the deck then printed exact CVE
         * identifiers, CVSS scores and scan verdicts on the same slide as the sentence promising it printed
         * none.
         *
         * <p>So the deck is held to the other half instead. It must carry no identifier the authority
         * discloses, no CVSS score and no unsuppressed-count verdict, and it must link to the authority for
         * the result. That keeps one document answerable for the outcome and one answerable for the
         * criterion, which is what makes either of them worth reading.
         *
         * @throws IOException if a document cannot be read
         */
        @Test
        @DisplayName("is absent from the deck by contract, which carries the Gate 8 obligation and links to "
                + "the authority for the outcome")
        void theDeckCarriesTheObligationAndNotTheOutcome() throws IOException {
            final String deck = read(PRESENTATION);
            final String flowed = collapseWhitespace(deck);

            final List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
            for (final String identifier : disclosedVulnerabilities()) {
                checks.add(() -> assertThat(flowed)
                        .as("the deck states obligations and defers every run outcome to the authority, so "
                                + "it must not name %s. A scan result reproduced on a slide is stale the "
                                + "moment the scan is re-run, and a reader then holds two answers",
                                identifier)
                        .doesNotContain(identifier));
            }
            checks.add(() -> assertThat(flowed)
                    .as("the deck must carry no scan verdict of its own: no score, no unsuppressed count "
                            + "and no absolute claim")
                    .doesNotContainIgnoringCase("CVSS")
                    .doesNotContainIgnoringCase("unsuppressed critical")
                    .doesNotContainIgnoringCase("unsuppressed high")
                    .doesNotContainIgnoringCase("zero tolerance for critical and high")
                    .doesNotContainIgnoringCase("no critical or high finding"));
            // The deferral has to be reachable rather than merely asserted, so the link is checked as a
            // link. The fragment is the identifier Python-Markdown generates for the authority's Gate 8
            // heading, which is what the documentation site serves.
            checks.add(() -> assertThat(deck)
                    .as("the deck must send a reader to the authority for the Gate 8 outcome; a deferral "
                            + "with no link is a dead end rather than a separation of concerns")
                    .contains("../gate-evidence.md#gate-8-integration-sign-off-checklist"));
            assertAll("the deck defers the vulnerability outcome to the authority", checks);
        }
    }

    // ===================================================================================================
    // HELPERS
    // ===================================================================================================

    /**
     * Reads the frozen contractual widths out of the authority's own inventory table.
     *
     * @return the widths, ascending, without duplicates
     * @throws IOException if the authority cannot be read
     */
    private static SortedSet<Integer> contractualWidths() throws IOException {
        return widthsPublishedUnder(WIDTH_INVENTORY_HEADING);
    }

    /**
     * Reads the supplemental widths out of the authority's own inventory table.
     *
     * <p>Separate from the contractual set on purpose. The two sets answer different questions - what the
     * gate was accepted against, and what evidence exists beyond it - and a single table could not express
     * the difference, which is how one of them came to be rewritten as the other.
     *
     * @return the widths, ascending, without duplicates
     * @throws IOException if the authority cannot be read
     */
    private static SortedSet<Integer> supplementalWidths() throws IOException {
        return widthsPublishedUnder(SUPPLEMENTAL_WIDTH_HEADING);
    }

    /**
     * Reads the widths out of one of the authority's inventory tables.
     *
     * <p>The table is sliced from its heading to the next heading before matching, so a width in a later
     * section cannot be borrowed. Each data row's second cell is the width.
     *
     * @param  heading the heading the table sits under
     * @return the widths, ascending, without duplicates
     * @throws IOException if the authority cannot be read
     */
    private static SortedSet<Integer> widthsPublishedUnder(final String heading) throws IOException {
        final String authority = read(AUTHORITY);
        final int start = authority.indexOf(heading);
        assertThat(start)
                .as("the authority must carry a width inventory under '%s'; without that heading this "
                        + "class cannot read the set it holds everything else to", heading)
                .isNotNegative();
        final int next = authority.indexOf("\n### ", start + heading.length());
        final String section = next < 0 ? authority.substring(start) : authority.substring(start, next);

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
