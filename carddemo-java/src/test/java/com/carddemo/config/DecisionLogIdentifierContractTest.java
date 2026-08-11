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

package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the decision log's citation contract: every identifier is unique, and every identifier the module
 * cites resolves to exactly one entry.
 *
 * <h2>Why this test exists</h2>
 * The log had accumulated two hundred and seventy-one headings under two hundred and twenty-one
 * identifiers. Nineteen were shared and one was carried by six unrelated decisions, while nine places in
 * the module's own source cited that one. A citation that resolves to six candidates is worse than no
 * citation at all: it reads as evidence and supplies none, and nothing in the build could see it.
 *
 * <p>The repair itself is recorded as an entry. What is recorded here is the <em>invariant</em>, because a
 * repair that nothing enforces is undone by the next entry somebody appends by copying the heading above
 * it. Both halves are needed and neither implies the other: uniqueness alone still permits a citation of a
 * number nobody ever wrote, and resolvability alone would be satisfied by a log with one entry.
 *
 * <h2>What is read, and what is deliberately not asserted</h2>
 * The log is read as text and only its {@code ### DL-nnn} headings are parsed - not its prose, not its
 * cross-references, not its tables. This test therefore has no opinion on what any decision says; it
 * asserts only that a reader following a citation arrives somewhere, and somewhere singular. The count of
 * entries is not asserted either: entries are added by design, and a test that pinned the total would fail
 * for the one reason that is always legitimate.
 *
 * <p>See {@code docs/decision-log.md} entry DL-245.
 */
@DisplayName("Decision-log citations: every identifier unique, and every cited identifier resolvable")
class DecisionLogIdentifierContractTest {

    /**
     * The log, relative to this module's root.
     *
     * <p>Outside the module on purpose: the log is a repository-level deliverable published by the
     * documentation site, while this test is the module's own guard on it.
     */
    private static final Path DECISION_LOG = Path.of("..", "docs", "decision-log.md");

    /** A decision entry heading, which is the only line this test parses. */
    private static final Pattern HEADING = Pattern.compile("^### (DL-\\d+)\\b", Pattern.MULTILINE);

    /** A citation, in any of the several forms the module's files spell one. */
    private static final Pattern CITATION = Pattern.compile("\\bDL-(\\d{3})\\b");

    /** The trees whose files may cite a decision. */
    private static final List<Path> CITING_TREES = List.of(
            Path.of("src"), Path.of("config"), Path.of("localstack"));

    /** Individual files outside those trees that also cite decisions. */
    private static final List<Path> CITING_FILES = List.of(
            Path.of("pom.xml"), Path.of("docker-compose.yml"), Path.of("Dockerfile"),
            Path.of("README.md"));

    /** Creates the specification. */
    DecisionLogIdentifierContractTest() {
        // Intentionally empty: every input is read per test.
    }

    @Test
    @DisplayName("the log is present and every heading it carries is a well-formed decision identifier")
    void theLogIsPresentAndItsHeadingsAreWellFormed() throws IOException {
        assertThat(DECISION_LOG)
                .as("the decision log is a repository-level deliverable this module cites throughout")
                .isRegularFile();

        final List<String> identifiers = headingIdentifiers();
        assertThat(identifiers)
                .as("a log with no entries would satisfy every other assertion here vacuously")
                .isNotEmpty();
        assertThat(identifiers)
                .as("every identifier is three digits behind the DL prefix, so a citation can be "
                        + "matched by one pattern rather than by several")
                .allSatisfy(identifier -> assertThat(identifier).matches("DL-\\d{3}"));
    }

    @Test
    @DisplayName("no identifier is carried by two entries, because a shared identifier makes every "
            + "citation of it unresolvable")
    void noIdentifierIsCarriedByTwoEntries() throws IOException {
        final Map<String, Integer> occurrences = new TreeMap<>();
        for (final String identifier : headingIdentifiers()) {
            occurrences.merge(identifier, 1, Integer::sum);
        }
        final Map<String, Integer> shared = new TreeMap<>();
        occurrences.forEach((identifier, count) -> {
            if (count > 1) {
                shared.put(identifier, count);
            }
        });

        assertThat(shared)
                .as("each identifier below is carried by more than one entry, so a reader following a "
                        + "citation of it cannot tell which decision was meant. Give the newer decision "
                        + "an identifier of its own and record the move, as entry DL-245 describes")
                .isEmpty();
    }

    @Test
    @DisplayName("every identifier the module cites resolves to exactly one entry, and none names an "
            + "entry that does not exist")
    void everyCitedIdentifierResolvesToExactlyOneEntry() throws IOException {
        final Set<String> declared = new LinkedHashSet<>(headingIdentifiers());
        final Map<String, Set<String>> citations = citationsByIdentifier();

        assertThat(citations)
                .as("this test is only meaningful if the module cites the log at all")
                .isNotEmpty();

        final Map<String, Set<String>> dangling = new TreeMap<>();
        citations.forEach((identifier, sources) -> {
            if (!declared.contains(identifier)) {
                dangling.put(identifier, sources);
            }
        });
        assertThat(dangling)
                .as("each identifier below is cited by the files listed against it but is carried by no "
                        + "entry, so the citation points at nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("the three citations that had to be repointed when identifiers were de-duplicated name "
            + "their renumbered entries, so none silently resolves to the wrong decision")
    void theRepointedCitationsNameTheirRenumberedEntries() throws IOException {
        // These three were the only citations whose meaning moved. Each is asserted by name rather than
        // by pattern, because a pattern would pass again the moment one of them drifted back.
        assertThat(read(Path.of("config", "grafana", "dashboards", "carddemo-overview.json")))
                .as("the active-job panel cites the panel decision, which is now DL-242")
                .contains("DL-242")
                .as("and no longer the diagnostic-fidelity decision that kept DL-152")
                .doesNotContain("DL-152.");
        assertThat(read(Path.of("src", "main", "java", "com", "carddemo", "batch",
                "CategoryBalanceReportJobConfig.java")))
                .as("the edit-mask citation is now DL-243")
                .contains("DL-243");
        assertThat(read(Path.of("pom.xml")))
                .as("the build file keeps DL-159 for the determination it means")
                .contains("DL-159")
                .as("and names DL-244 for the sibling entry it used to call another DL-159")
                .contains("DL-244")
                .as("the phrase that named two entries by one identifier is gone")
                .doesNotContain("sibling DL-159 entry");
    }

    // -----------------------------------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------------------------------

    /**
     * Parses the identifier of every decision heading, in file order and including any repeat.
     *
     * @return the identifiers, one per heading
     * @throws IOException if the log cannot be read
     */
    private static List<String> headingIdentifiers() throws IOException {
        final Matcher headings = HEADING.matcher(read(DECISION_LOG));
        final List<String> identifiers = new ArrayList<>();
        while (headings.find()) {
            identifiers.add(headings.group(1));
        }
        return identifiers;
    }

    /**
     * Collects every identifier the module cites, with the files that cite it.
     *
     * <p>The log itself is excluded: an entry naming another entry is a cross-reference within one
     * document, and the uniqueness assertion above already covers the headings it would resolve against.
     *
     * @return citing files by identifier, both ordered so a failure reads the same way twice
     * @throws IOException if a file cannot be read
     */
    private static Map<String, Set<String>> citationsByIdentifier() throws IOException {
        final List<Path> files = new ArrayList<>(CITING_FILES);
        for (final Path tree : CITING_TREES) {
            if (!Files.isDirectory(tree)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(tree)) {
                files.addAll(walk.filter(Files::isRegularFile).sorted().toList());
            }
        }

        final Map<String, Set<String>> citations = new LinkedHashMap<>();
        for (final Path file : files) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            final Matcher found = CITATION.matcher(read(file));
            while (found.find()) {
                citations.computeIfAbsent("DL-" + found.group(1), key -> new TreeSet<>())
                        .add(file.toString());
            }
        }
        return citations;
    }

    /**
     * Reads one file as text, tolerating a byte that is not valid in the declared encoding.
     *
     * <p>Tolerant on purpose: the trees walked here include dashboard JSON and shell scripts as well as
     * Java, and a citation is plain ASCII in every one of them. A strict decode would fail this test for a
     * reason that has nothing to do with citations.
     *
     * @param  path the file to read
     * @return its text
     * @throws IOException if the file cannot be read at all
     */
    private static String read(final Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
