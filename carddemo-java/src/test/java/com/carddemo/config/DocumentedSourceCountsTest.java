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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds every published count of this module's own source to the source it counts.
 *
 * <p>Three documents state figures about this tree: the architecture page publishes a file count per
 * package and two figures about the service package, and the module README publishes the number of
 * managed-version properties in the POM's security-remediation block together with the properties
 * themselves. Every one of them is the kind of figure that drifts silently, because a document has no way
 * to notice a directory gaining a class or a scan gaining an override.</p>
 *
 * <p>The remedy is not a more careful author. It is to stop transcribing: every figure named above is
 * asserted here against the directory, the POM block or the matrix that decides it, so prose that falls
 * behind the tree breaks the build instead of misinforming a reader. Recorded in
 * {@code docs/decision-log.md} DL-316.</p>
 */
@DisplayName("documented source counts: every published figure is measured, not transcribed")
final class DocumentedSourceCountsTest {

    /** The architecture page, which publishes the package table and the service figures. */
    private static final Path ARCHITECTURE_PAGE = Path.of("..", "docs", "architecture.md");

    /** The traceability matrix, which decides which services carry a COBOL paragraph. */
    private static final Path TRACEABILITY_PAGE = Path.of("..", "docs", "traceability-matrix.md");

    /** The gate evidence page, the one document that may publish a current suite figure. */
    private static final Path GATE_EVIDENCE_PAGE = Path.of("..", "docs", "gate-evidence.md");

    /** The onboarding guide, which publishes the required production variables for an operator. */
    private static final Path ONBOARDING_PAGE = Path.of("..", "docs", "onboarding-guide.md");

    /** The production profile, the authority for which variables carry no fallback. */
    private static final Path PRODUCTION_PROFILE =
            Path.of("src", "main", "resources", "application-prod.yml");

    /**
     * Every further document that states how many values the production profile requires.
     *
     * <p>Held here because a hand-maintained count drifts in every document at once while a derived one
     * cannot — which is the argument for the assertion rather than for another correction. Each writes
     * the figure in the same "resolves all N of its required values" sentence, so one derived check
     * covers them.</p>
     */
    private static final List<Path> DOCUMENTS_STATING_THE_REQUIRED_COUNT = List.of(
            Path.of("README.md"),
            Path.of("docker-compose.yml"),
            Path.of("src", "main", "resources", "application-local.yml"));

    /** A variable the production profile resolves with no fallback. */
    private static final Pattern NO_FALLBACK_REFERENCE =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)\\}");

    /** A variable the production profile resolves with a default. */
    private static final Pattern DEFAULTED_REFERENCE =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):");

    /** The name the profile uses when explaining its own convention rather than applying it. */
    private static final String CONVENTION_PLACEHOLDER = "VARIABLE";

    /** An import from the namespace the Jakarta rename replaced. */
    private static final Pattern JAVAX_IMPORT =
            Pattern.compile("^import\\s+(static\\s+)?javax\\.", Pattern.MULTILINE);

    /** A wildcard import, which would make the audit unperformable by inspection. */
    private static final Pattern WILDCARD_IMPORT =
            Pattern.compile("^import\\s+(static\\s+)?[A-Za-z0-9_.]+\\.\\*;", Pattern.MULTILINE);

    /** A fully-qualified reference into the platform's own cryptography namespace. */
    private static final Pattern QUALIFIED_JAVAX =
            Pattern.compile("\\bjavax\\.[A-Za-z0-9_.]+");

    /**
     * A cast whose target is a parameterised type, in the shape the published audit command matches.
     *
     * <p>The same expression the evidence page publishes as its second audit command, so the figures this
     * class derives and the command a reader runs answer one question rather than two.
     */
    private static final Pattern PARAMETERISED_CAST =
            Pattern.compile("\\(\\s*[A-Za-z_$][\\w.$]*\\s*<[^<>()]*>\\s*\\)\\s*[A-Za-z_$(]");

    /**
     * A statement verb inside a literal, joined to something that is not a literal: the published audit's
     * verb-only SQL shape.
     *
     * <p>Deliberately the loose form, because the point of publishing it is that it returns a false positive
     * a keyword grep cannot avoid. The strict form is {@link #CENSUS_SHAPED_SQL_ASSEMBLY}.
     */
    private static final Pattern VERB_ONLY_SQL_ASSEMBLY = Pattern.compile(
            "(?i)\"[^\"]*\\b(select|insert|update|delete|merge|truncate|drop|alter|create)"
                    + "\\b[^\"]*\"\\s*\\+\\s*[^\"\\s]");

    /**
     * The same shape with a clause keyword required beside the verb, which is how the gated census counts a
     * query and is the command whose published expectation is no output.
     */
    private static final Pattern CENSUS_SHAPED_SQL_ASSEMBLY = Pattern.compile(
            "(?i)\"[^\"]*\\b(select|insert|update|delete|merge|truncate|drop|alter|create)\\b[^\"]*"
                    + "\\b(from|into|set|values|where|table|join|index|sequence)\\b[^\"]*\"\\s*\\+"
                    + "\\s*[^\"\\s]");

    /**
     * The annotation whose raw grep population and gated figure are two different numbers.
     *
     * <p>Assembled from two halves rather than written as one literal, and that is not fussiness. This
     * constant is used to <em>count</em> the lines of both source trees that mention the annotation, and a
     * whole literal here would be one more such line - so the assertion would measure itself, and adding it
     * would falsify the very figure it exists to protect. The population was 12 before this constant and
     * would have been 13 after it. A measurement must not perturb what it measures.
     */
    private static final String SUPPRESSION_ANNOTATION = "@Suppress" + "Warnings";

    /** This module's README, which publishes the security-remediation override table. */
    private static final Path MODULE_README = Path.of("README.md");

    /** The build file, which is the authority for the override block. */
    private static final Path POM = Path.of("pom.xml");

    /** The root of the application source the documented counts describe. */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "carddemo");

    /** A package table row: a backticked dotted package name followed by a file count. */
    private static final Pattern PACKAGE_ROW =
            Pattern.compile("^\\|\\s*`([a-z][a-z.]*)`\\s*\\|\\s*(\\d+)\\s*\\|(.*)$");

    /** A managed-version property line, with the closing tag pinned to the opening one. */
    private static final Pattern VERSION_PROPERTY =
            Pattern.compile("^[ \\t]*<([A-Za-z0-9._-]+\\.version)>([^<]*)</\\1>[ \\t]*$",
                    Pattern.MULTILINE);

    /** A README override table row: a backticked property name followed by its pinned value. */
    private static final Pattern OVERRIDE_ROW =
            Pattern.compile("^\\|\\s*`([A-Za-z0-9._-]+\\.version)`\\s*\\|\\s*([^|]+?)\\s*\\|");

    /** A service class named by the matrix in its Java-class column. */
    private static final Pattern MATRIX_SERVICE =
            Pattern.compile("service\\.([A-Za-z0-9]+Service)\\b");

    /** A backticked service class name, as the support-service paragraph writes them. */
    private static final Pattern PROSE_SERVICE = Pattern.compile("`([A-Za-z0-9]+Service)`");

    /** A trailing count comment inside a fenced shell block, as {@code # 37} is written. */
    private static final Pattern COUNT_COMMENT = Pattern.compile("#\\s*(\\d+)\\s*$",
            Pattern.MULTILINE);

    /**
     * The same count comment where the manual labels the figure, as {@code # 68 - the package total}.
     *
     * <p>Separate from {@link #COUNT_COMMENT} rather than a widening of it: that pattern anchors at the end
     * of the line, and anchoring is what stops it matching a number that happens to sit in a command. The
     * manual's block labels two of its four counts, so it needs the unanchored form, and the block it is
     * applied to is extracted first - which is what keeps the looser shape safe.
     */
    private static final Pattern LABELLED_COUNT_COMMENT = Pattern.compile("#\\s*(\\d+)\\b");

    /**
     * A published count of the measured Gate 3 rows, in any of the shapes the manual writes one.
     *
     * <p>Matched against the emphasis-stripped text, because the manual bolds the figure in two of the three
     * places and the whole sentence in the third — and it was the third that drifted to a count six rows
     * behind the table while the two the assertion named stayed correct.
     */
    private static final Pattern PUBLISHED_ROW_COUNT =
            Pattern.compile("(?i)\\b([a-z-]+)\\s+(?:measured\\s+)?rows?\\s+(?:are\\s+)?recorded");

    /** A published class count, as {@code across 448 classes} is written. */
    private static final Pattern CLASS_COUNT = Pattern.compile("across\\s+([\\d,]+)\\s+classes");

    /** The provisioned Grafana dashboard, the authority for how many panels it carries. */
    private static final Path DASHBOARD =
            Path.of("config", "grafana", "dashboards", "carddemo-overview.json");

    /**
     * The header of the Gate 3 measured-runs table, which is where the published row count is decided.
     *
     * <p>Matched in full rather than by a substring, because the page carries other seven-column tables and
     * a looser anchor would count rows from whichever one appeared first.
     */
    private static final String PERFORMANCE_TABLE_HEADER = "| Date | Machine | Run | Records | "
            + "Elapsed (ms) | Peak heap (bytes) | Records/second |";

    /**
     * A published total of the files the service package holds, in either shape the documents write.
     *
     * <p>"holds **68** files in all" and "the balance of the 68 files" are the two, and both are matched so
     * that neither shape can be the one nobody checks.
     */
    private static final Pattern SERVICE_FILE_TOTAL =
            Pattern.compile("(?:holds|balance of the)\\s+\\*{0,2}(\\d+)\\*{0,2}\\s+files");

    /**
     * A stated count of the package's concrete service classes, in either emphasis the documents use.
     *
     * <p>Matched over the flattened document, so a figure separated from its noun by a line break is still
     * found. The bold markers are optional because the two documents differ on them and neither shape may
     * be the one nobody checks.
     */
    private static final Pattern CONCRETE_SERVICE_FIGURE =
            Pattern.compile("\\*{0,2}(\\d+)\\*{0,2} concrete `\\*Service\\.java`");

    /** A published count of the package's files that are not services. */
    private static final Pattern SERVICE_OWNED_BALANCE =
            Pattern.compile("\\*\\*(\\d+)\\*\\*\\s+(?:of them|service-owned)");

    /** The directory the schema migrations ship from, which every profile resolves. */
    private static final Path SCHEMA_MIGRATIONS =
            Path.of("src", "main", "resources", "db", "migration", "schema");

    /** The sibling directory the seed migrations ship from, which only local and test resolve. */
    private static final Path SEED_MIGRATIONS =
            Path.of("src", "main", "resources", "db", "migration", "seed");

    /**
     * Every current-facing document and profile document that states the delivered migration topology.
     *
     * <p>Two families are deliberately absent. The migration scripts themselves are <em>immutable
     * history</em> — a Flyway checksum covers a script's comments, and every delivered script is applied
     * wherever this module has run — so their headers describe the topology as it stood at their own
     * version and are answered by the README erratum instead, which {@link
     * TheMigrationTopology#theErratumNamesEveryScriptWhoseHeaderStatesASupersededPin()} holds to the
     * delivered scripts. The decision log is absent for the same reason in a different form: it is a
     * historical record whose superseded readings are corrected in place <em>with the correction
     * labelled</em>, so a stale sentence there is evidence rather than a defect. Both exclusions are stated
     * here rather than left for a reader to infer from the list. Recorded in {@code docs/decision-log.md}
     * DL-351.
     *
     * <p><strong>One production source is on this list, and it belongs here.</strong>
     * {@code config/BatchConfig}'s class comment states the delivered migration inventory and the count of
     * migrations that absorb the estate's dataset-definition steps, in current tense, as facts a reader is
     * expected to rely on. That makes it a document about the topology whatever else the file is, and it
     * drifted exactly as an unmeasured document does: it published a count of five while six scripts were
     * delivered, and separately a count of four in a sentence the count check could not see. Both are now
     * measured here. A Javadoc claim has to survive {@link #flattened(Path)}, which collapses whitespace
     * but leaves the leading asterisks in place, so a claim split across two comment lines reads as
     * {@code migration * inventory} and matches nothing — which is why the sentence in that file is written
     * on one line and must stay there.
     */
    private static final List<Path> TOPOLOGY_DOCUMENTS = List.of(
            Path.of("README.md"),
            Path.of("..", "README.md"),
            Path.of("..", "docs", "architecture.md"),
            Path.of("..", "docs", "onboarding-guide.md"),
            Path.of("..", "docs", "gate-evidence.md"),
            Path.of("..", "docs", "index.md"),
            Path.of("docker-compose.yml"),
            Path.of("src", "main", "resources", "application.yml"),
            Path.of("src", "main", "resources", "application-prod.yml"),
            Path.of("src", "main", "resources", "application-local.yml"),
            Path.of("src", "main", "resources", "application-test.yml"),
            Path.of("src", "test", "resources", "application-test.yml"),
            Path.of("src", "main", "java", "com", "carddemo", "config", "BatchConfig.java"));

    /** A delivered migration filename, whose version is the part between the prefix and the separator. */
    private static final Pattern MIGRATION_FILENAME =
            Pattern.compile("V(\\d+(?:_\\d+)*)__[A-Za-z0-9_]+\\.sql");

    /** The production pin a migration header states, written in the property's own shape. */
    private static final Pattern HEADER_STATED_PIN =
            Pattern.compile("spring\\.flyway\\.target:\\s*\"([^\"]+)\"");

    /**
     * A claim about how many migrations the module delivers.
     *
     * <p>The tool's own name is admitted between the count and the noun, because "four Flyway migrations"
     * is the same claim as "four migrations" and the narrower pattern could not see it. Two documents were
     * stating a stale count in exactly that shape while every count the pattern did match was correct.
     */
    private static final Pattern MIGRATION_COUNT_CLAIM = Pattern.compile(
            "(?i)\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)"
                    + "\\s+(?:flyway\\s+)?migrations\\b");

    /** A claim about the delivered inventory, whose window must name the scripts or their count. */
    private static final Pattern INVENTORY_CLAIM = Pattern.compile("(?i)migration inventory");

    /** How far either side of a claim its own sentence is taken to reach. */
    private static final int CLAIM_WINDOW = 130;

    /** How far past an inventory claim the scripts or the count must be named. */
    private static final int INVENTORY_WINDOW = 220;

    /**
     * Wording that marks a version as historical or forward-looking rather than as the current pin.
     *
     * <p>Without it the erratum could not quote the pin it supersedes, and the architecture page could not
     * name the version a further schema script would take. Every member is a phrase that says, in the
     * sentence itself, that the number beside it is not what the module currently carries.
     */
    private static final Pattern HISTORICAL_WORDING = Pattern.compile("(?i)(supersede|withdrawn|erratum"
            + "|historic|earlier revision|earlier reading|moves from|no longer|further schema script"
            + "|next dotted version|had (?:refused|described)|as it stood|was true when)");

    /**
     * The vocabulary that makes a version a migration claim rather than a dependency pin.
     *
     * <p>The front page pins a JDBC driver "one patch above" the version the platform manages, in a table
     * whose neighbouring rows name Flyway. Requiring this vocabulary beside the number is what separates the
     * two claims, and the version shape below — one or two parts, the shape the delivered migration versions
     * take — is what keeps a three-part dependency version out of the comparison entirely.
     */
    private static final Pattern MIGRATION_VOCABULARY =
            Pattern.compile("(?i)(flyway|migrat|schema|seed|ceiling)");

    /** The heading of the README erratum that answers the headers this module may not edit. */
    private static final String ERRATUM_HEADING = "#### Erratum: the earliest migration headers "
            + "describe the topology as it stood at their own version";

    /** The heading of the README section that is the authority for the delivered inventory. */
    private static final String README_MIGRATION_HEADING = "### Database migrations";

    /** The heading of the architecture page's section that publishes the same inventory. */
    private static final String ARCHITECTURE_MIGRATION_HEADING = "## Schema evolution";

    /** The root the compiler's production pass walks. */
    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java");

    /** The root the compiler's test pass walks. */
    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");

    /** A published production-source figure, in either the paired or the standalone form. */
    private static final Pattern PRODUCTION_SOURCE_FIGURE =
            Pattern.compile("([\\d,]+) production (?:and [\\d,]+ test )?sources?");

    /** A published test-source figure. */
    private static final Pattern TEST_SOURCE_FIGURE = Pattern.compile("([\\d,]+) test sources?");

    /**
     * The number words the documents spell out. A count outside this range is a signal that the
     * documents have grown past what a spelled-out number should carry, not something to widen.
     */
    private static final List<String> NUMBER_WORDS = List.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "twenty-one", "twenty-two", "twenty-three",
            "twenty-four", "twenty-five", "twenty-six", "twenty-seven", "twenty-eight",
            "twenty-nine", "thirty");

    /** Creates the test class. */
    DocumentedSourceCountsTest() {
    }

    @Nested
    @DisplayName("the package table on the architecture page")
    class ThePackageTable {

        /** Creates the nested test class. */
        ThePackageTable() {
        }

        @Test
        @DisplayName("every row counts the Java files in the package it names")
        void everyRowCountsTheFilesInTheDirectoryItNames() throws IOException {
            final Map<String, Integer> published = publishedPackageCounts();

            assertThat(published)
                    .as("the package table must publish at least the twelve packages this module "
                            + "has, or the assertions below would hold nothing to account")
                    .hasSizeGreaterThanOrEqualTo(12);

            published.forEach((packageName, count) -> {
                final Path directory = SOURCE_ROOT.resolve(packageName.replace('.', '/'));
                assertThat(Files.isDirectory(directory))
                        .as("the table names package `" + packageName + "`, which is not a "
                                + "directory of this module")
                        .isTrue();
                assertThat(count)
                        .as("the architecture page publishes " + count + " files for package `"
                                + packageName + "`; the directory holds " + javaFileCount(directory)
                                + ". Correct the page rather than the directory")
                        .isEqualTo(javaFileCount(directory));
            });
        }

        @Test
        @DisplayName("every package holding source has a row, so a new package cannot hide")
        void everySourcePackageHasARow() throws IOException {
            final TreeSet<String> documented = new TreeSet<>(publishedPackageCounts().keySet());
            final TreeSet<String> onDisk = new TreeSet<>(packagesHoldingSource());

            assertThat(documented)
                    .as("a package that holds source and has no row would be invisible to every "
                            + "count on the page, which is how the previous drift went unnoticed")
                    .isEqualTo(onDisk);
        }

        @Test
        @DisplayName("the service row states both of its figures, and both are measured")
        void theServiceRowStatesBothOfItsFigures() throws IOException {
            final String row = packageRowText("service");

            assertThat(row)
                    .as("the service row carries two figures, and the concrete-service one is the "
                            + "figure a reader is most likely to mistake for the file count")
                    .contains(concreteServiceCount() + " concrete `*Service.java` classes");
        }
    }

    /**
     * Holds the service figures to the service package.
     *
     * <p>One boundary is deliberate and is recorded rather than papered over. Which services carry a
     * COBOL paragraph is decided by the traceability matrix, and five translation-bearing services
     * are reached there through a copybook or a dispatch graph rather than through a
     * {@code service.X} column entry, so the set of paragraph-bearing services is not fully
     * derivable. The assertions below therefore catch a support service that does not exist, one
     * that the matrix contradicts, and any count that stops adding up — but a deliberate two-place
     * edit that removes a name and adjusts the sum in step remains a reviewer's judgement, because
     * no authority in this repository can decide it.</p>
     */
    @Nested
    @DisplayName("the service figures on the architecture page")
    class TheServiceFigures {

        /** Creates the nested test class. */
        TheServiceFigures() {
        }

        @Test
        @DisplayName("the counted-directly block reports what the directory holds")
        void theCountedDirectlyBlockMatchesTheDirectory() throws IOException {
            final String block = countedDirectlyBlock();
            final List<Integer> reported = new ArrayList<>();
            final Matcher matcher = COUNT_COMMENT.matcher(block);
            while (matcher.find()) {
                reported.add(Integer.parseInt(matcher.group(1)));
            }

            assertThat(reported)
                    .as("the page invites a reader to run two counts and shows their answers; both "
                            + "answers are measured here so neither can go stale")
                    .containsExactly(concreteServiceCount(), javaFileCount(serviceDirectory()));
        }

        /**
         * The design-pattern table's Service Layer row states the same two figures the rest of the page
         * states, and both are measured here.
         *
         * <p><strong>Why this row needed its own assertion.</strong> Every other service figure on the page
         * was already derived — the package row, the prose arithmetic, the counted-directly block — and this
         * one was not, because it sits in a table about patterns rather than about counts and reads as
         * background. It drifted for exactly that reason: it published a total behind the tree while every
         * derived figure beside it stayed correct, and a reader comparing the two rows could not tell which
         * had been measured. That is the failure mode a transcribed count has and a
         * derived one does not.
         *
         * <p>The row is matched by its own leading cell rather than by line number, so it can move on the
         * page. Both of its figures are recomputed here from the directory and from the support-service
         * enumeration, so the row cannot disagree with the package row, the prose sum or the shell block.
         *
         * @throws IOException if the architecture page cannot be read
         */
        @Test
        @DisplayName("the design-pattern table's Service Layer row states the measured pair, not a "
                + "transcribed one")
        void theDesignPatternServiceRowStatesTheMeasuredPair() throws IOException {
            final int concrete = concreteServiceCount();
            final int translationBearing = concrete - namedSupportServices().size();
            final String row = read(ARCHITECTURE_PAGE).lines()
                    .filter(line -> line.startsWith("| Service Layer |"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the architecture page has no Service Layer row in its design-pattern table"));

            assertThat(row)
                    .as("the design-pattern table restates the service figures, so it is measured for "
                            + "the same reason the package row is; it published a stale total while every "
                            + "derived figure beside it was correct")
                    .contains(translationBearing + " translation-bearing services")
                    .contains("of " + concrete + " `*Service.java` in all");
        }

        /**
         * Wherever either document says "<em>N</em> concrete {@code *Service.java}", <em>N</em> is the
         * measured count.
         *
         * <p>Closing the class rather than the instance. The row above was found stale by review, and a
         * sweep for the same figure in the same shape found a second occurrence — in the paragraph that
         * introduces the traceability census — which had drifted for the identical reason and which a
         * row-specific assertion would have left free to drift again. So the figure is measured wherever
         * it is written, in either of the two documents allowed to write it, in bold or in plain text.
         *
         * <p>Deliberately not restricted to a known list of sentences: a new sentence stating this figure
         * is exactly the case that needs catching, and a list would have to be extended by whoever wrote it.
         *
         * @throws IOException if either document cannot be read
         */
        @Test
        @DisplayName("every stated concrete-service figure on the architecture page is the directory's own")
        void everyStatedConcreteServiceFigureIsMeasured() throws IOException {
            final int concrete = concreteServiceCount();
            final List<String> stale = new ArrayList<>();
            final Matcher stated = CONCRETE_SERVICE_FIGURE.matcher(flattened(ARCHITECTURE_PAGE));
            int occurrences = 0;
            while (stated.find()) {
                occurrences++;
                if (Integer.parseInt(stated.group(1)) != concrete) {
                    stale.add(ARCHITECTURE_PAGE + " states `" + stated.group() + "`");
                }
            }

            assertThat(occurrences)
                    .as("%s states this figure in more than one sentence, which is the whole reason for "
                            + "sweeping rather than naming one of them", ARCHITECTURE_PAGE)
                    .isGreaterThanOrEqualTo(2);
            assertThat(stale)
                    .as("the service directory holds %d files ending `Service.java`, so every sentence "
                            + "stating that figure states %d; these state something else", concrete,
                            concrete)
                    .isEmpty();

            // The manual states the same figure in a table cell rather than beside the noun, so its shape
            // is asserted directly rather than by the sweep above. Its shell block is measured separately
            // by TheManualCountedBlock.
            assertThat(read(MODULE_README))
                    .as("%s publishes the same figure in its layer-count table, and a reader comparing "
                            + "the two documents must not find two numbers", MODULE_README)
                    .contains("| Service implementations | **" + concrete + "** |");
        }

        @Test
        @DisplayName("every support service the prose names exists as a class")
        void theSupportServicesNamedInTheProseAllExist() throws IOException {
            final List<String> named = namedSupportServices();

            assertThat(named)
                    .as("the prose enumerates the support services rather than only counting them, "
                            + "so the enumeration is the thing to check")
                    .isNotEmpty();
            named.forEach(service -> assertThat(
                    Files.isRegularFile(serviceDirectory().resolve(service + ".java")))
                    .as("the page names support service `" + service + "`, which is not a class "
                            + "of the service package")
                    .isTrue());
        }

        @Test
        @DisplayName("the prose arithmetic adds up to the concrete services on disk")
        void theProseArithmeticAddsUpToTheDirectory() throws IOException {
            final int support = namedSupportServices().size();
            final int concrete = concreteServiceCount();
            final int translationBearing = concrete - support;

            assertThat(read(ARCHITECTURE_PAGE))
                    .as("the page states its own sum; the sum is recomputed here from the "
                            + "enumeration and the directory so the three cannot disagree")
                    .contains(translationBearing + " + " + support + " = " + concrete)
                    .contains("**" + translationBearing + "** translation-bearing services");
        }

        @Test
        @DisplayName("the support services carry no paragraph, and every service the matrix names "
                + "is a real class")
        void theSupportServicesCarryNoParagraphInTheMatrix() throws IOException {
            final String matrix = read(TRACEABILITY_PAGE);

            namedSupportServices().forEach(service -> assertThat(matrix)
                    .as("`" + service + "` is published as carrying no COBOL paragraph, so the "
                            + "traceability matrix must not map a paragraph onto it")
                    .doesNotContain("service." + service));

            final TreeSet<String> mapped = new TreeSet<>();
            final Matcher matcher = MATRIX_SERVICE.matcher(matrix);
            while (matcher.find()) {
                mapped.add(matcher.group(1));
            }
            assertThat(mapped)
                    .as("a matrix row that names a class this module does not have would make the "
                            + "matrix unfollowable in the direction it promises")
                    .isNotEmpty();
            mapped.forEach(service -> assertThat(
                    Files.isRegularFile(serviceDirectory().resolve(service + ".java")))
                    .as("the traceability matrix maps a paragraph onto `service." + service
                            + "`, which is not a class of the service package")
                    .isTrue());
        }
    }

    /**
     * Holds every published statement of the service package's size to the package itself.
     *
     * <p>Three figures describe that package — the concrete services, the files in all, and the balance of
     * service-owned types that are not services — and each is published more than once: in the architecture
     * page's table and twice more in its prose, and in the module manual's layer table and again in the
     * command block beside it. {@link TheServiceFigures} already measured the table and the architecture
     * page's own counted-directly block, and that was not enough: a sign-on attempt store added three
     * files, the checked places were corrected, and the four unchecked ones went on publishing figures the
     * command beside them contradicted. The store has since been withdrawn (DL-352) and the figures moved
     * again, which is the same lesson from the other direction.</p>
     *
     * <p>So the check is by occurrence rather than by place. Every occurrence of either figure in either
     * document is measured against the directory, and each document must carry at least one of each, so a
     * correction cannot be made by deleting the claim.</p>
     */
    @Nested
    @DisplayName("the service-package inventory, wherever either document states it")
    class TheServicePackageInventory {

        /** Creates the nested test class. */
        TheServicePackageInventory() {
        }

        @Test
        @DisplayName("every published file total is the number of files the package holds")
        void everyPublishedFileTotalIsMeasured() throws IOException {
            final int measured = javaFileCount(serviceDirectory());
            for (final Path document : List.of(Path.of("README.md"), ARCHITECTURE_PAGE)) {
                final List<Integer> published = figuresIn(document, SERVICE_FILE_TOTAL);
                assertThat(published)
                        .as("%s states the service package's size, so the statement must be present to be "
                                + "measured; a figure that vanished is a documentation regression of its "
                                + "own", document)
                        .isNotEmpty();
                assertThat(published)
                        .as("%s publishes the service package as holding %s files; the directory holds %d. "
                                + "Every occurrence is measured here, because it was the unchecked "
                                + "duplicates that drifted", document, published, measured)
                        .containsOnly(measured);
            }
        }

        @Test
        @DisplayName("every published balance is the files that are not services")
        void everyPublishedBalanceIsMeasured() throws IOException {
            final int measured = javaFileCount(serviceDirectory()) - concreteServiceCount();
            for (final Path document : List.of(Path.of("README.md"), ARCHITECTURE_PAGE)) {
                final List<Integer> published = figuresIn(document, SERVICE_OWNED_BALANCE);
                assertThat(published)
                        .as("%s states how many of the package's files are not services, and that "
                                + "statement is what makes its arithmetic checkable", document)
                        .isNotEmpty();
                assertThat(published)
                        .as("%s publishes %s service-owned types beside the services; the directory holds "
                                + "%d files that are not `*Service.java`", document, published, measured)
                        .containsOnly(measured);
            }
        }

        @Test
        @DisplayName("the manual's counted-directly block reports what the tree holds, all four counts")
        void theManualCountedBlockMatchesTheTree() throws IOException {
            final List<Integer> reported = new ArrayList<>();
            final Matcher count = LABELLED_COUNT_COMMENT.matcher(manualCountedBlock());
            while (count.find()) {
                reported.add(Integer.parseInt(count.group(1)));
            }

            assertThat(reported)
                    .as("the manual invites a reader to run four counts and prints their answers; all four "
                            + "are measured here, in the order the block runs them, so none can go stale "
                            + "while the table beside it is corrected")
                    .containsExactly(
                            concreteServiceCount(),
                            javaFileCount(serviceDirectory()),
                            recordMapperCount(),
                            javaFileCount(SOURCE_ROOT.resolve("api").resolve("dto")));
        }
    }

    /**
     * Holds every published statement of the Flyway topology to the topology this module delivers.
     *
     * <p>The topology is stated in eleven places — a compliance row and an inventory table in the module
     * manual, an inventory table on the architecture page, a profile table in the onboarding guide, four
     * Compose comments, and the explanatory comments of four profile documents — and it moved twice after
     * most of them were written: the scripts split into two sibling locations, and two further schema
     * scripts took dotted versions between the indexes and the seeds, which raised the production ceiling.
     * A summary can therefore describe the arrangement <em>before</em> those moves - four scripts flat in
     * one location, production pinned at the version the indexes carry - while the active configuration
     * stays correct throughout, which is exactly why nothing fails.</p>
     *
     * <p>So the figures are derived here instead of transcribed. The inventory comes from the two migration
     * directories and the pin from {@link FlywayConfig#PRODUCTION_TARGET}, and a further schema script
     * therefore cannot be added quietly: it lands in the derived inventory, it raises the pin that
     * {@code FlywayConfigTest} already ties to the delivered scripts, and every document still naming the
     * old inventory or the old pin fails the build until it is corrected — the erratum included, so the
     * erratum cannot become the next stale summary. Recorded in {@code docs/decision-log.md} DL-351.</p>
     */
    @Nested
    @DisplayName("the Flyway topology the current-facing documents publish")
    class TheMigrationTopology {

        /** Creates the nested test class. */
        TheMigrationTopology() {
        }

        @Test
        @DisplayName("every published ceiling names the pin the configuration actually carries")
        void everyPublishedCeilingClaimNamesTheDeliveredPin() throws IOException {
            final List<String> offenders = new ArrayList<>();
            for (final Path document : TOPOLOGY_DOCUMENTS) {
                final String flowed = flattened(document);
                for (final Pattern shape : pinClaimShapes()) {
                    final Matcher claim = shape.matcher(flowed);
                    while (claim.find()) {
                        final String stated = claim.group(1);
                        if (FlywayConfig.PRODUCTION_TARGET.equals(stated) || "latest".equals(stated)) {
                            continue;
                        }
                        final String sentence = around(flowed, claim.start(), claim.end(), CLAIM_WINDOW);
                        if (HISTORICAL_WORDING.matcher(sentence).find()
                                || !MIGRATION_VOCABULARY.matcher(sentence).find()) {
                            continue;
                        }
                        offenders.add(document + " states the ceiling as `" + stated + "`: " + sentence);
                    }
                }
            }

            assertThat(offenders)
                    .as("a document that names a ceiling other than %s is telling an operator to "
                            + "under-migrate or over-reach; the pin is read from FlywayConfig here so the "
                            + "documents follow the configuration rather than the other way round. A "
                            + "genuinely historical mention stays legal by saying so in its own sentence",
                            FlywayConfig.PRODUCTION_TARGET)
                    .isEmpty();
        }

        @Test
        @DisplayName("every published migration count is the number of scripts delivered")
        void everyPublishedMigrationCountNamesTheDeliveredCount() throws IOException {
            final int delivered = deliveredMigrations().size();
            final String spelled = numberWord(delivered);
            final List<String> offenders = new ArrayList<>();

            for (final Path document : TOPOLOGY_DOCUMENTS) {
                final String flowed = flattened(document);
                final Matcher claim = MIGRATION_COUNT_CLAIM.matcher(flowed);
                while (claim.find()) {
                    final String stated = claim.group(1).toLowerCase(Locale.ROOT);
                    if (spelled.equals(stated) || String.valueOf(delivered).equals(stated)) {
                        continue;
                    }
                    final String sentence = around(flowed, claim.start(), claim.end(), CLAIM_WINDOW);
                    if (HISTORICAL_WORDING.matcher(sentence).find()) {
                        continue;
                    }
                    offenders.add(document + " counts " + stated + " migrations: " + sentence);
                }
            }

            assertThat(offenders)
                    .as("%d migrations are delivered, so a document counting a different number is "
                            + "describing a tree that no longer exists — which is how a Compose comment "
                            + "came to promise four start-up migrations beside a manual promising six",
                            delivered)
                    .isEmpty();
        }

        @Test
        @DisplayName("every delivered-inventory claim names every script, or their measured count")
        void everyDeliveredInventoryClaimNamesTheDeliveredScripts() throws IOException {
            final TreeSet<String> delivered = new TreeSet<>(deliveredMigrations().values().stream()
                    .map(TheMigrationTopology::versionToken)
                    .toList());
            final String count = numberWord(delivered.size()) + " scripts";
            final List<String> offenders = new ArrayList<>();

            for (final Path document : TOPOLOGY_DOCUMENTS) {
                final String flowed = flattened(document);
                final Matcher claim = INVENTORY_CLAIM.matcher(flowed);
                while (claim.find()) {
                    final String window = flowed.substring(claim.end(),
                            Math.min(flowed.length(), claim.end() + INVENTORY_WINDOW));
                    final TreeSet<String> missing = new TreeSet<>(delivered);
                    missing.removeAll(namedVersionTokens(window));
                    if (missing.isEmpty() || window.toLowerCase(Locale.ROOT).contains(count)) {
                        continue;
                    }
                    offenders.add(document + " states the inventory without " + missing + ": " + window);
                }
            }

            assertThat(offenders)
                    .as("a sentence that says what the delivered inventory 'stays at' must name every "
                            + "script or state their measured count; naming a subset reads as the whole, "
                            + "which is what two profile documents did while the schema location grew")
                    .isEmpty();
        }

        @Test
        @DisplayName("both inventory tables name exactly the delivered scripts, so a new script cannot "
                + "ship unpublished")
        void bothInventoryTablesNameExactlyTheDeliveredScripts() throws IOException {
            final TreeSet<String> delivered = new TreeSet<>(deliveredMigrations().values());

            final TreeSet<String> manual = namedMigrationFiles(
                    section(read(Path.of("README.md")), README_MIGRATION_HEADING, "### "));
            final TreeSet<String> page = namedMigrationFiles(section(
                    read(Path.of("..", "docs", "architecture.md")),
                    ARCHITECTURE_MIGRATION_HEADING, "## "));

            assertThat(manual)
                    .as("the module manual's migration section is the authority the compliance row points "
                            + "at, so it names every delivered script and no script this module does not "
                            + "deliver")
                    .isEqualTo(delivered);
            assertThat(page)
                    .as("the architecture page publishes the same inventory to the documentation site, so "
                            + "the published table and the delivered directories cannot diverge")
                    .isEqualTo(delivered);
        }

        @Test
        @DisplayName("the erratum names exactly those scripts whose header still states a superseded pin")
        void theErratumNamesEveryScriptWhoseHeaderStatesASupersededPin() throws IOException {
            final TreeSet<String> superseded = new TreeSet<>();
            final TreeSet<String> current = new TreeSet<>();
            for (final String script : deliveredMigrations().values()) {
                final Matcher stated = HEADER_STATED_PIN.matcher(migrationText(script));
                boolean stale = false;
                while (stated.find()) {
                    stale = stale || !FlywayConfig.PRODUCTION_TARGET.equals(stated.group(1));
                }
                if (stale) {
                    superseded.add(script);
                } else {
                    current.add(script);
                }
            }

            assertThat(superseded)
                    .as("the erratum exists because some delivered header states a pin the configuration "
                            + "no longer carries; if none did, the erratum would be describing nothing and "
                            + "should be removed rather than left to rot")
                    .isNotEmpty();

            final String erratum = section(read(Path.of("README.md")), ERRATUM_HEADING, "#");
            final TreeSet<String> named = namedMigrationFiles(erratum);

            assertThat(named)
                    .as("the erratum must name every script whose header states a superseded pin — a "
                            + "header left unnamed reads as current — and must name no other, because "
                            + "calling a correct header superseded is the same defect facing the other way")
                    .isEqualTo(superseded);
            assertThat(current)
                    .as("the scripts written after the pin moved state it correctly, and the erratum "
                            + "deliberately says nothing about them")
                    .isNotEmpty();
        }

        /**
         * Reduces a migration filename to the version token the documents write.
         *
         * @param  filename the delivered filename
         * @return its version token, as {@code V2_2}
         */
        private static String versionToken(final String filename) {
            return filename.substring(0, filename.indexOf("__"));
        }
    }

    @Nested
    @DisplayName("the security remediation overrides in the module README")
    class TheSecurityRemediationOverrides {

        /** Creates the nested test class. */
        TheSecurityRemediationOverrides() {
        }

        @Test
        @DisplayName("the README table names exactly the properties the block declares")
        void theTableNamesExactlyTheBlockProperties() throws IOException {
            assertThat(new TreeSet<>(publishedOverrides().keySet()))
                    .as("an override the block declares and the table omits is an unexplained pin; "
                            + "a table row with no property is a pin that no longer exists")
                    .isEqualTo(new TreeSet<>(declaredOverrides().keySet()));
        }

        @Test
        @DisplayName("the README table pins the same values the block pins")
        void theTablePinsTheSameValues() throws IOException {
            assertThat(publishedOverrides())
                    .as("a table that names the right properties at the wrong versions is worse "
                            + "than no table, because it reads as verified")
                    .isEqualTo(declaredOverrides());
        }

        @Test
        @DisplayName("the README states the measured count, spelled out, in both places it appears")
        void theReadmeStatesTheMeasuredCountInWords() throws IOException {
            final int declared = declaredOverrides().size();
            final String word = numberWord(declared);
            final String readme = read(MODULE_README);

            assertThat(readme)
                    .as("the section heading carries the count, so it is measured too rather than "
                            + "left as the only unchecked statement of it")
                    .contains("### " + capitalise(word) + " versions sit deliberately above the "
                            + "managed floor as security remediation")
                    .contains("There are **" + word + "**.");
        }

        @Test
        @DisplayName("the block holds version overrides only, never a dependency declaration")
        void theBlockHoldsOverridesOnly() throws IOException {
            final String block = overrideBlock();

            assertThat(block)
                    .as("the block is published as carrying managed-version overrides rather than "
                            + "direct declarations, and that is what makes each one uniform across "
                            + "every transitive path")
                    .doesNotContain("<dependency>")
                    .doesNotContain("<dependencies>");

            final List<String> unexplained = new ArrayList<>();
            block.lines().forEach(line -> {
                final String trimmed = line.strip();
                if (!trimmed.isEmpty()
                        && !trimmed.startsWith("<!--")
                        && !trimmed.startsWith("-->")
                        && !trimmed.endsWith("-->")
                        && !VERSION_PROPERTY.matcher(line).matches()
                        && !isCommentContinuation(trimmed)) {
                    unexplained.add(trimmed);
                }
            });
            assertThat(unexplained)
                    .as("every line inside the block is a comment or a version property; anything "
                            + "else has been added without the table being told")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the test suite figures in the gate evidence")
    class TheTestSuiteShape {

        /** Creates the nested test class. */
        TheTestSuiteShape() {
        }

        @Test
        @DisplayName("the counting definition states the build's own inclusion rules")
        void theCountingDefinitionStatesTheInclusionRules() throws IOException {
            assertThat(read(GATE_EVIDENCE_PAGE))
                    .as("a published test figure is unreadable without the scope it was taken "
                            + "under, and the scope is the build's rules rather than a convention")
                    .contains("### How this document counts tests")
                    .contains("`**/*Test.java`")
                    .contains("`**/*IT.java`")
                    .contains("`**/*E2ETest.java`")
                    .contains("`**/e2e/**`")
                    .contains("one test *execution*");
        }

        @Test
        @DisplayName("the published class counts are the classes the two tiers actually run")
        void thePublishedClassCountsMatchTheTree() throws IOException {
            final List<Integer> published = new ArrayList<>();
            final Matcher matcher = CLASS_COUNT.matcher(suiteFiguresSentence());
            while (matcher.find()) {
                published.add(Integer.parseInt(matcher.group(1).replace(",", "")));
            }

            assertThat(published)
                    .as("the two class counts are properties of this tree, so they are measured "
                            + "here; the execution counts beside them are dated evidence a test "
                            + "cannot know without being the run that took them")
                    .containsExactly(unitTierClasses().size(), integrationTierClasses().size());
        }

        @Test
        @DisplayName("a class in the end-to-end package belongs to the integration tier despite "
                + "its name")
        void theTierSplitFollowsTheBuildRatherThanTheSuffix() throws IOException {
            final List<String> integration = integrationTierClasses();
            final List<String> unit = unitTierClasses();

            assertThat(integration)
                    .as("the split is only worth stating because it is counter-intuitive, so the "
                            + "counter-intuitive case must actually be present to be asserted")
                    .anyMatch(name -> name.endsWith("Test.java") && !name.endsWith("E2ETest.java"));
            assertThat(unit)
                    .as("no class the failsafe rules claim may also be counted by the surefire "
                            + "rules, or the total would double-count it")
                    .doesNotContainAnyElementsOf(integration);
        }

        @Test
        @DisplayName("no other document restates a current test figure")
        void theFigureIsPublishedInExactlyOnePlace() throws IOException {
            final List<Path> others = List.of(
                    Path.of("..", "README.md"),
                    MODULE_README,
                    ARCHITECTURE_PAGE,
                    Path.of("..", "docs", "onboarding-guide.md"),
                    Path.of("..", "docs", "presentation", "index.html"));

            for (final Path document : others) {
                assertThat(read(document))
                        .as("%s must point at the gate evidence rather than restate a figure that "
                                + "cannot hear when the suite changes", document)
                        .doesNotContain("unit tests across")
                        .doesNotContain("729 unit, 134 integration and 33 end-to-end tests");
            }
        }
    }

    @Nested
    @DisplayName("the required production variables the operator documents publish")
    class TheRequiredProductionVariables {

        /** Creates the nested test class. */
        TheRequiredProductionVariables() {
        }

        @Test
        @DisplayName("both documents state the measured count of no-fallback variables")
        void bothDocumentsStateTheMeasuredCount() throws IOException {
            final String required = numberWord(requiredProductionVariables().size());

            assertThat(read(MODULE_README))
                    .as("the module manual publishes the count; a wrong one reads as a complete "
                            + "list and sends an operator to production missing a variable")
                    .contains("The list is **" + required + "** entries");
            assertThat(read(ONBOARDING_PAGE))
                    .as("the onboarding guide publishes the same count and must agree with it")
                    .contains(capitalise(required) + " variables are required");
        }

        @Test
        @DisplayName("both documents name exactly the variables the profile requires")
        void bothDocumentsNameExactlyTheRequiredVariables() throws IOException {
            final TreeSet<String> required = requiredProductionVariables();

            for (final Path document : List.of(MODULE_README, ONBOARDING_PAGE)) {
                final String text = read(document);
                required.forEach(variable -> assertThat(text)
                        .as("%s must name required variable %s, or its table is not the list it "
                                + "claims to be", document, variable)
                        .contains("`" + variable + "`"));
            }
        }

        @Test
        @DisplayName("the module manual states the measured count of defaulted variables")
        void theManualStatesTheMeasuredDefaultedCount() throws IOException {
            final String defaulted = numberWord(defaultedProductionVariables().size());
            final String readme = read(MODULE_README);

            assertThat(readme)
                    .as("the secret / non-secret split is the substance of the no-defaulted-secret "
                            + "claim, so the non-secret side is counted too")
                    .contains(capitalise(defaulted) + " further variables are non-secret");
            defaultedProductionVariables().forEach(variable -> assertThat(readme)
                    .as("the manual must name defaulted variable %s; an unnamed one looks like a "
                            + "secret that was forgotten", variable)
                    .contains("`" + variable + "`"));
        }

        @Test
        @DisplayName("every other document stating the count states the measured one, so a correction "
                + "in one place cannot leave the others behind")
        void everyOtherDocumentStatingTheCountStatesTheMeasuredOne() throws IOException {
            final String required = numberWord(requiredProductionVariables().size());

            for (final Path document : DOCUMENTS_STATING_THE_REQUIRED_COUNT) {
                assertThat(read(document))
                        .as("%s tells a reader how many values a production deployment must supply; a "
                                + "stale figure there reads as a complete obligation and is not one",
                                document)
                        .contains("all " + required + " of its required values");
            }
        }

        @Test
        @DisplayName("the architecture page states the measured count and names every required "
                + "variable, so its table cannot omit one")
        void theArchitecturePageStatesTheMeasuredCountAndNamesEveryVariable() throws IOException {
            final TreeSet<String> required = requiredProductionVariables();
            final String page = read(ARCHITECTURE_PAGE);

            assertThat(page)
                    .as("the page publishes the count in its own prose as well as in the standards "
                            + "table, and both must be the measured one")
                    .contains("resolves **" + numberWord(required.size()) + "** values from the "
                            + "environment")
                    .contains("the production profile's **" + numberWord(required.size())
                            + "** no-fallback values");
            required.forEach(variable -> assertThat(page)
                    .as("the architecture page must name required variable %s; the omission this "
                            + "assertion exists for was a variable present in the profile and absent "
                            + "from the table", variable)
                    .contains("`" + variable + "`"));
        }

        @Test
        @DisplayName("the architecture page states the measured count of defaulted variables too")
        void theArchitecturePageStatesTheMeasuredDefaultedCount() throws IOException {
            assertThat(read(ARCHITECTURE_PAGE))
                    .as("the secret / non-secret split is the substance of the claim on both pages "
                            + "that make it, so both sides are counted on both")
                    .contains(capitalise(numberWord(defaultedProductionVariables().size()))
                            + " further production variables");
        }

        @Test
        @DisplayName("the reproduction command reports the raw line count, placeholder included")
        void theReproductionCommandAccountsForThePlaceholder() throws IOException {
            final int required = requiredProductionVariables().size();

            assertThat(read(MODULE_README))
                    .as("the command the manual offers counts one line more than the table, "
                            + "because the profile explains its own convention using a bare "
                            + "placeholder; the manual must say so or the counts look wrong")
                    .contains("# " + (required + 1) + " lines: the " + required + " below, plus "
                            + "${VARIABLE} from an explanatory comment");
        }
    }

    /**
     * Holds the published unsafe-code audit to the source it audits.
     *
     * <p>This nest exists because of a specific miss. The audit table published a zero
     * {@code javax}-import posture as a measured result, nothing asserted it, and a class added later
     * acquired two such imports without anything noticing — a published audit that had quietly
     * stopped being audited. Each budgeted family is now counted here on every build, so the table
     * cannot describe a state the tree has left.</p>
     */
    @Nested
    @DisplayName("the unsafe-code audit counts in the gate evidence")
    class TheUnsafeCodeAuditCounts {

        /** Creates the nested test class. */
        TheUnsafeCodeAuditCounts() {
        }

        @Test
        @DisplayName("the families budgeted at zero measure zero across the audited tree")
        void theZeroBudgetedFamiliesMeasureZero() throws IOException {
            final String tree = auditedSource();

            assertThat(occurrencesOf(tree, "Runtime.getRuntime")).isZero();
            assertThat(occurrencesOf(tree, "ProcessBuilder")).isZero();
            assertThat(occurrencesOf(tree, "java.lang.reflect")).isZero();
            assertThat(occurrencesOf(tree, "Class.forName")).isZero();
            assertThat(occurrencesOf(tree, "createNativeQuery")).isZero();
            assertThat(occurrencesOf(tree, "@SuppressWarnings"))
                    .as("the budget allows three; the delivered count is zero, and the table says so")
                    .isZero();
        }

        @Test
        @DisplayName("no application source imports from the javax namespace")
        void noApplicationSourceImportsFromJavax() throws IOException {
            final List<String> offenders = new ArrayList<>();
            for (final Path source : applicationSources()) {
                if (JAVAX_IMPORT.matcher(read(source)).find()) {
                    offenders.add(source.toString());
                }
            }

            assertThat(offenders)
                    .as("a blanket import ban is the only mechanically checkable form of the rule "
                            + "that every Jakarta EE annotation comes from jakarta.*; the platform's "
                            + "own javax.crypto types are referenced by their qualified names at "
                            + "their use sites instead")
                    .isEmpty();
        }

        @Test
        @DisplayName("no application source uses a wildcard import")
        void noApplicationSourceUsesAWildcardImport() throws IOException {
            final List<String> offenders = new ArrayList<>();
            for (final Path source : applicationSources()) {
                if (WILDCARD_IMPORT.matcher(read(source)).find()) {
                    offenders.add(source.toString());
                }
            }

            assertThat(offenders)
                    .as("every import is explicit so that this audit can be performed by "
                            + "inspection rather than by resolution")
                    .isEmpty();
        }

        @Test
        @DisplayName("the published count of qualified javax uses is the count in the source")
        void thePublishedQualifiedJavaxCountMatchesTheSource() throws IOException {
            int uses = 0;
            final TreeSet<String> classes = new TreeSet<>();
            for (final Path source : applicationSources()) {
                final Matcher matcher = QUALIFIED_JAVAX.matcher(codeOnly(read(source)));
                while (matcher.find()) {
                    uses++;
                    classes.add(source.getFileName().toString().replace(".java", ""));
                }
            }

            final String page = read(GATE_EVIDENCE_PAGE);

            // The noun agrees with the count. When the population fell to a single class the
            // assertion still demanded the plural, so the only text that could satisfy it read
            // "across **one** classes" - an assertion that can be met only by ungrammatical prose
            // is an assertion about the wrong thing. The count and the class names are still
            // matched exactly; only the noun's number follows the figure.
            final int carrying = classes.size();
            assertThat(page)
                    .as("the qualified uses are the exception the zero-import row depends on, so "
                            + "the row states how many there are and where")
                    .contains("**" + capitalise(numberWord(uses)) + "** fully-qualified uses")
                    .contains("across **" + numberWord(carrying) + "** class"
                            + (carrying == 1 ? "" : "es"));
            classes.forEach(name -> assertThat(page)
                    .as("the row must name %s, the class carrying qualified javax references", name)
                    .contains("`" + name + "`"));
        }

        /**
         * Every published cast site names the file and the line the cast is actually on.
         *
         * <p>A published line number is the most perishable figure in this documentation set: it is
         * falsified by any edit above it in the same file, while every count on the page stays correct - so
         * nothing else on the page can signal that it has gone stale. Two of the five had gone stale exactly
         * that way, by classes gaining code above the cast. Deriving the pair means the next such edit
         * either updates the page or breaks the build.
         *
         * <p>The path is written page-relative, as {@code service/MenuService.java:527} is, so the assertion
         * compares the string a reader sees rather than an absolute path they never do.
         *
         * @throws IOException if the tree or the page cannot be read
         */
        @Test
        @DisplayName("every published cast site names the file and line the cast is on, derived from the "
                + "source rather than transcribed")
        void thePublishedCastSitesNameTheirActualLines() throws IOException {
            final List<String> sites = new ArrayList<>();
            for (final Path source : applicationSources()) {
                final List<String> lines = List.of(read(source).split("\n", -1));
                for (int index = 0; index < lines.size(); index++) {
                    if (PARAMETERISED_CAST.matcher(lines.get(index)).find()) {
                        sites.add(SOURCE_ROOT.relativize(source).toString().replace('\\', '/')
                                + ":" + (index + 1));
                    }
                }
            }

            assertThat(sites)
                    .as("the page publishes a cast census, so the tree must carry the casts it counts")
                    .isNotEmpty();
            final String page = read(GATE_EVIDENCE_PAGE);
            for (final String site : sites) {
                assertThat(page)
                        .as("%s carries a cast to a parameterised type, so the published census must name "
                                + "it at that line: a line number is falsified by any edit above it and no "
                                + "other figure on the page can report that it has drifted", site)
                        .contains(site);
            }
        }
    }

    /**
     * Holds the two documents that publish the Gate 6 audit commands to what those commands actually return.
     *
     * <p>A published command carries its expected output in the comment beside it, and that comment is a
     * claim like any other figure on the page. Two of them were false: commands 3 and 4 both told a reader
     * to expect no output, while command 3 returns one line - a 3270 screen prompt that a verb-only grep
     * cannot help matching - and command 4 returns twelve, every one a mention of the annotation rather than
     * a use of it. The evidence page published the true output a few lines below the false expectation, so
     * the page disagreed with itself; the onboarding guide published the false expectation in both its
     * comment and its result table, so it simply disagreed with the tree.
     *
     * <p>Both are corrected, and the correction is held here by running each published pattern over the
     * source it is scoped to and requiring both documents to state that result. Recorded as {@code DL-340}.
     */
    @Nested
    @DisplayName("the published Gate 6 audit commands return what both documents say they return")
    class ThePublishedAuditCommands {

        /** Creates the nested test class. */
        ThePublishedAuditCommands() {
        }

        /**
         * The verb-only SQL shape returns exactly one line, and both documents say so and say what it is.
         *
         * <p>The one line is the legacy menu prompt, and the assertion names the class rather than only the
         * count, because "one line" that moved to a different file would be a different fact.
         *
         * @throws IOException if the tree or either document cannot be read
         */
        @Test
        @DisplayName("the verb-only SQL command returns exactly one line, the screen prompt, and both "
                + "documents publish that rather than an absence")
        void theVerbOnlySqlCommandReturnsTheOneScreenPrompt() throws IOException {
            final List<String> matches = new ArrayList<>();
            for (final Path source : applicationSources()) {
                for (final String line : read(source).split("\n", -1)) {
                    if (VERB_ONLY_SQL_ASSEMBLY.matcher(line).find()) {
                        matches.add(SOURCE_ROOT.relativize(source).toString().replace('\\', '/'));
                    }
                }
            }

            assertThat(matches)
                    .as("the published expectation is one line and the documents name where it is, so a "
                            + "second match or a different file makes both documents wrong")
                    .containsExactly("service/MenuService.java");
            for (final Path document : List.of(GATE_EVIDENCE_PAGE, ONBOARDING_PAGE)) {
                assertThat(flattened(document))
                        .as("%s publishes this command, so it must state that it returns one line rather "
                                + "than none: a reader runs the command and reads the comment first",
                                document)
                        .contains("EXACTLY ONE")
                        .contains("MenuService");
            }
        }

        /**
         * The census-shaped SQL command returns nothing, and both documents publish it as the zero.
         *
         * @throws IOException if the tree or either document cannot be read
         */
        @Test
        @DisplayName("the census-shaped SQL command returns nothing, and both documents name it as the "
                + "command whose expectation is no output")
        void theCensusShapedSqlCommandReturnsNothing() throws IOException {
            final List<String> matches = new ArrayList<>();
            for (final Path source : applicationSources()) {
                for (final String line : read(source).split("\n", -1)) {
                    if (CENSUS_SHAPED_SQL_ASSEMBLY.matcher(line).find()) {
                        matches.add(SOURCE_ROOT.relativize(source).toString().replace('\\', '/')
                                + ": " + line.strip());
                    }
                }
            }

            assertThat(matches)
                    .as("a statement verb AND a clause keyword in one literal, joined to a value, is SQL "
                            + "assembled from strings - the construct Gate 6 budgets at zero")
                    .isEmpty();
            for (final Path document : List.of(GATE_EVIDENCE_PAGE, ONBOARDING_PAGE)) {
                assertThat(flattened(document))
                        .as("%s must publish the census-shaped command as the one whose expectation is no "
                                + "output, so the zero on the page is reproducible from the page", document)
                        .contains("clause keyword");
            }
        }

        /**
         * The suppression grep returns twelve lines over both trees and none over production, and both
         * documents publish both figures.
         *
         * <p>Two numbers rather than one, because a grep cannot tell a mention from a use and the gated
         * figure is the use count. Publishing only the raw population reads as twelve suppressions;
         * publishing only the gated zero leaves a reader who runs the command unable to reconcile it.
         *
         * @throws IOException if either tree or either document cannot be read
         */
        @Test
        @DisplayName("the suppression grep returns twelve lines over both trees and none over production, "
                + "and both documents publish both figures")
        void theSuppressionGrepReturnsItsPublishedPopulation() throws IOException {
            final int bothTrees = suppressionMentions(MAIN_SOURCE_ROOT) + suppressionMentions(TEST_SOURCE_ROOT);
            final int production = suppressionMentions(MAIN_SOURCE_ROOT);

            assertThat(production)
                    .as("the production tree carries no mention of the annotation at all, which is the "
                            + "second figure both documents publish")
                    .isZero();
            for (final Path document : List.of(GATE_EVIDENCE_PAGE, ONBOARDING_PAGE)) {
                assertThat(flattened(document))
                        .as("%s must state that the raw grep returns %d lines over both trees, because it "
                                + "does, and a document telling a reader to expect none is falsified the "
                                + "moment the reader runs it", document, bothTrees)
                        .contains(numberWord(bothTrees));
            }
            assertThat(flattened(ONBOARDING_PAGE))
                    .as("and the guide must say the twelve are mentions rather than annotations, which is "
                            + "the whole reason the gated figure is zero while the grep is not")
                    .contains("Not one is an annotation");
        }
    }

    /**
     * Guards the two mutable inventories the gate-evidence page and the manual publish as figures: how
     * many panels the provisioned dashboard carries, and how many measured runs the Gate 3 table records.
     *
     * <p>Both had drifted, and both drifted for the same reason as every figure DL-316 records: they read
     * as background rather than as claims. The page published 33 panels against a dashboard carrying 46,
     * and the manual published fifteen measured rows against a table carrying eighteen - each correct when
     * written, each falsified by the next panel and the next run. A panel added to a dashboard and a row
     * added to a baseline are the two things this documentation set does most often, so a transcribed total
     * is guaranteed to go stale.
     *
     * <p>The remedy is the same too: the figure is derived from the artefact that decides it - the
     * dashboard JSON's own panel array and the evidence page's own table - so the next panel or the next
     * measured run either updates the prose or breaks the build. Recorded as {@code DL-340}.
     */
    @Nested
    @DisplayName("the mutable inventories: dashboard panels and measured Gate 3 rows")
    class TheMutableInventories {

        /** Creates the nested test class. */
        TheMutableInventories() {
        }

        /**
         * The published panel count is the dashboard's own panel count.
         *
         * <p>Counted over the whole {@code panels} array rather than over the non-row panels, because the
         * page describes the dashboard as carrying that many panels with per-endpoint and per-step views -
         * a claim about the file's contents. Row panels are panels: Grafana renders them, they carry titles
         * and collapse state, and excluding them would publish a figure a reader cannot reproduce by
         * opening the file.
         *
         * @throws IOException if the dashboard or the page cannot be read
         */
        @Test
        @DisplayName("the published dashboard panel count is the count in the provisioned dashboard")
        void thePublishedPanelCountMatchesTheDashboard() throws IOException {
            final JsonNode dashboard = new ObjectMapper().readTree(DASHBOARD.toFile());
            final JsonNode panels = dashboard.path("panels");

            assertThat(panels.isArray())
                    .as("%s must carry a panel array for this figure to mean anything", DASHBOARD)
                    .isTrue();
            final int panelCount = panels.size();
            assertThat(panelCount)
                    .as("a dashboard with no panel is not the artefact the page describes")
                    .isPositive();

            assertThat(read(GATE_EVIDENCE_PAGE))
                    .as("%s names the provisioned dashboard as evidence of the Gate 3 measurement "
                            + "mechanism, and it carries %d panels. A transcribed total is falsified by "
                            + "the next panel, which is why this one is derived", GATE_EVIDENCE_PAGE,
                            panelCount)
                    .contains("\"CardDemo Overview\", " + panelCount + " panels");
        }

        /**
         * The published count of measured Gate 3 rows is the number of rows the table carries.
         *
         * <p>Counted off the page's own table rather than off a constant, and counted the way a reader
         * would: the lines between the measured-runs header and the first line that is not a table row.
         * The manual states the figure in words because that is how it states every count, so the word is
         * derived from the number rather than the number from the word.
         *
         * @throws IOException if the page or the manual cannot be read
         */
        @Test
        @DisplayName("the manual's count of measured Gate 3 rows is the number of rows the evidence table "
                + "carries")
        void theManualsMeasuredRunCountMatchesTheEvidenceTable() throws IOException {
            final int rows = measuredPerformanceRowCount();

            assertThat(rows)
                    .as("Gate 3 is discharged by a recorded measurement, so %s must carry at least one "
                            + "row under %s", GATE_EVIDENCE_PAGE, PERFORMANCE_TABLE_HEADER)
                    .isPositive();
            // Both sentences, because the manual states the figure twice - once in the gate table and once
            // in the sign-off checklist - and a check that reached only the first would leave the second
            // free to drift, which is the failure this whole class exists to stop.
            assertThat(read(MODULE_README))
                    .as("%s publishes how many measured rows the evidence page records, in both its gate "
                            + "table and its sign-off checklist, and the page records %d. The figure is "
                            + "derived from the table so the next measured run either updates both "
                            + "sentences or breaks the build", MODULE_README, rows)
                    .contains("**" + numberWord(rows) + "** measured rows recorded")
                    .contains("**" + numberWord(rows) + "** measured rows are recorded");

            // Those two are the canonical sentences, and naming them was not enough: a third sentence, in
            // the paragraph that tells a reader how to take their own measurement, bolds the whole phrase
            // instead of the figure and had drifted six rows behind the table while both named sentences
            // stayed correct. So every spelled-out row count in the manual is measured, whichever way it is
            // emphasised.
            final List<String> stale = new ArrayList<>();
            final Matcher published = PUBLISHED_ROW_COUNT.matcher(read(MODULE_README).replace("**", ""));
            int occurrences = 0;
            while (published.find()) {
                final String word = published.group(1).toLowerCase(Locale.ROOT);
                if (!NUMBER_WORDS.contains(word)) {
                    continue;
                }
                occurrences++;
                if (!numberWord(rows).equals(word)) {
                    stale.add("`" + published.group() + "`");
                }
            }

            assertThat(occurrences)
                    .as("the manual states this count in more than one place, so a run that found at most "
                            + "one of them has stopped reading the sentences rather than proved them right")
                    .isGreaterThanOrEqualTo(2);
            assertThat(stale)
                    .as("%s must state %s wherever it states the count, because the evidence table carries "
                            + "%d rows; these sentences state something else", MODULE_README,
                            numberWord(rows), rows)
                    .isEmpty();
        }

        /**
         * No document attributes a Gate 3 row to a source revision, because no row carries one.
         *
         * <p>The manual claimed the most recent three rows were attributed to the revision they were taken
         * at. The table's machine column carries a host description and a run label; the recorder cannot
         * write a revision into a row because a run does not know the revision it is running - which is
         * exactly why the emitted evidence files carry a separate {@code Build provenance} line and the
         * table does not. An attribution a reader cannot find is worse than an absent one, so the claim is
         * asserted absent rather than corrected once.
         *
         * @throws IOException if the manual or the page cannot be read
         */
        @Test
        @DisplayName("no document claims a Gate 3 row is attributed to a source revision, because the "
                + "table carries no revision")
        void noDocumentClaimsARevisionAttributionTheTableDoesNotCarry() throws IOException {
            assertThat(read(MODULE_README))
                    .as("%s must not claim a revision attribution the Gate 3 table cannot carry: a run "
                            + "does not know its own revision, which is why the emitted evidence files "
                            + "carry a Build provenance line and the table does not", MODULE_README)
                    .doesNotContain("attributed to the source revision");
            assertThat(read(GATE_EVIDENCE_PAGE))
                    .as("%s must not make the same claim about its own table", GATE_EVIDENCE_PAGE)
                    .doesNotContain("attributed to the source revision");
        }
    }

    /**
     * Guards the two source counts the zero-warning gate publishes. Gate 2's claim is that a stated
     * number of production and test sources compiled without emitting a diagnostic, and the page
     * states those numbers four and three times respectively, in prose and inside a quoted compiler
     * line. Every one of them is a count of files in this tree, so every one is measured here. The
     * page carried a stale pair through several checkpoints precisely because the figures read as
     * background rather than as claims; see decision-log entry DL-316.
     */
    @Nested
    @DisplayName("the compiled source counts in the zero-warning gate")
    class TheCompiledSourceCounts {

        /** Creates the nested test class. */
        TheCompiledSourceCounts() {
        }

        @Test
        @DisplayName("every published production-source figure is the production tree's size")
        void everyPublishedProductionFigureMatchesTheTree() throws IOException {
            final List<Integer> published = published(PRODUCTION_SOURCE_FIGURE);

            assertThat(published)
                    .as("the page states this figure in more than one place, and a reader who "
                            + "finds two different numbers cannot tell which was measured")
                    .isNotEmpty()
                    .allMatch(figure -> figure == compiledSources(MAIN_SOURCE_ROOT),
                            "equal to the " + compiledSources(MAIN_SOURCE_ROOT)
                                    + " java files under src/main/java");
        }

        @Test
        @DisplayName("every published test-source figure is the test tree's size")
        void everyPublishedTestFigureMatchesTheTree() throws IOException {
            final List<Integer> published = published(TEST_SOURCE_FIGURE);

            assertThat(published)
                    .as("the test tree is compiled under the same -Werror settings, so its size "
                            + "is part of the same claim and moves for the same reasons")
                    .isNotEmpty()
                    .allMatch(figure -> figure == compiledSources(TEST_SOURCE_ROOT),
                            "equal to the " + compiledSources(TEST_SOURCE_ROOT)
                                    + " java files under src/test/java");
        }

        @Test
        @DisplayName("the quoted compiler lines name those same two counts")
        void theQuotedCompilerLinesNameTheSameCounts() throws IOException {
            final String page = read(GATE_EVIDENCE_PAGE);

            assertThat(page)
                    .as("the excerpt is quoted as the gate's evidence artefact, so a figure in it "
                            + "that disagrees with the tree makes the quotation the stale part")
                    .contains("Compiling " + compiledSources(MAIN_SOURCE_ROOT)
                            + " source files with javac [debug parameters release 25] "
                            + "to target/classes")
                    .contains("Compiling " + compiledSources(TEST_SOURCE_ROOT)
                            + " source files with javac [debug parameters release 25] "
                            + "to target/test-classes");
        }

        @Test
        @DisplayName("the reproducibility sentence names the pair rather than a rounded shape")
        void theReproducibilitySentenceNamesThePair() throws IOException {
            assertThat(flattened(GATE_EVIDENCE_PAGE))
                    .as("the page claims repeat runs reproduced these counts, which is only "
                            + "checkable if the sentence says which counts it means")
                    .contains("the same " + compiledSources(MAIN_SOURCE_ROOT) + " and "
                            + compiledSources(TEST_SOURCE_ROOT) + " source counts");
        }

        /**
         * Collects every figure the gate-evidence page publishes under one of the source-count
         * forms, reading the page with its line wrapping removed so a figure separated from its
         * noun by a line break is still found.
         *
         * @param form the published form to collect
         * @return the figures, in the order the page states them
         * @throws IOException if the page cannot be read
         */
        private List<Integer> published(final Pattern form) throws IOException {
            final List<Integer> figures = new ArrayList<>();
            final Matcher matcher = form.matcher(flattened(GATE_EVIDENCE_PAGE));
            while (matcher.find()) {
                figures.add(Integer.parseInt(matcher.group(1).replace(",", "")));
            }
            return figures;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Measurement helpers. Each reads the authority rather than a figure about it.
    // ----------------------------------------------------------------------------------------

    /**
     * Lists the delivered migrations, keyed by the version each carries.
     *
     * <p>Read off the two delivery directories rather than declared, so a script added to either one enters
     * every figure this class derives from it without anybody remembering to say so.
     *
     * @return each delivered version, as Flyway orders it, mapped to the filename that carries it
     * @throws IOException if either migration directory cannot be listed
     */
    private static Map<String, String> deliveredMigrations() throws IOException {
        final Map<String, String> delivered = new TreeMap<>();
        for (final Path directory : List.of(SCHEMA_MIGRATIONS, SEED_MIGRATIONS)) {
            assertThat(Files.isDirectory(directory))
                    .as("the delivered migrations ship from two sibling locations; %s is not a directory "
                            + "of this module", directory)
                    .isTrue();
            try (Stream<Path> entries = Files.list(directory)) {
                for (final Path script : entries.filter(Files::isRegularFile).sorted().toList()) {
                    final String filename = script.getFileName().toString();
                    final Matcher versioned = MIGRATION_FILENAME.matcher(filename);
                    assertThat(versioned.matches())
                            .as("%s sits in a migration location without being a versioned migration, so "
                                    + "no figure derived here could account for it", script)
                            .isTrue();
                    delivered.put(versioned.group(1).replace('_', '.'), filename);
                }
            }
        }
        assertThat(delivered)
                .as("a migration set this class could not read would make every assertion below vacuous")
                .isNotEmpty();
        return delivered;
    }

    /**
     * Reads one delivered migration, whichever location it ships from.
     *
     * @param  filename the delivered filename
     * @return the script's text, comments included
     * @throws IOException if the script cannot be read
     */
    private static String migrationText(final String filename) throws IOException {
        final Path schema = SCHEMA_MIGRATIONS.resolve(filename);
        return read(Files.isRegularFile(schema) ? schema : SEED_MIGRATIONS.resolve(filename));
    }

    /**
     * Builds the shapes in which a document states a production ceiling.
     *
     * <p>The version shape is derived rather than fixed: it admits as many dotted parts as the delivered
     * versions themselves use and no more, which is what keeps a three-part dependency version — a driver
     * pinned "one patch above" the managed one, in a table whose neighbouring rows name Flyway — out of a
     * comparison it has no business in.
     *
     * @return one pattern per shape, each capturing the stated version in group one
     * @throws IOException if the delivered versions cannot be read
     */
    private static List<Pattern> pinClaimShapes() throws IOException {
        int parts = 1;
        for (final String version : deliveredMigrations().keySet()) {
            parts = Math.max(parts, version.split("\\.", -1).length);
        }
        final String version = "(\\d+(?:\\.\\d+){0," + (parts - 1) + "}(?!\\.?\\d)|latest)";
        return List.of(
                Pattern.compile("(?i)(?:spring\\.flyway\\.)?target\\s*[:=]\\s*[`\"]?" + version
                        + "[`\"]?"),
                Pattern.compile("(?i)ceiling\\b[^.;]{0,90}?[`\"]" + version + "[`\"]"),
                Pattern.compile("(?i)\\bpin(?:ned|s)?\\b[^.;]{0,70}?[`\"]" + version + "[`\"]"),
                Pattern.compile("(?i)stops? at\\b[^.;]{0,45}?[`\"]" + version + "[`\"]"),
                Pattern.compile("(?i)schema version\\s+[`\"]" + version + "[`\"]"));
    }

    /**
     * Returns the text surrounding one match, which stands in for the sentence the claim was made in.
     *
     * @param  flowed the document with its whitespace flattened
     * @param  from   where the match starts
     * @param  to     where the match ends
     * @param  reach  how far either side to take
     * @return that span of text
     */
    private static String around(final String flowed, final int from, final int to, final int reach) {
        return flowed.substring(Math.max(0, from - reach), Math.min(flowed.length(), to + reach));
    }

    /**
     * Collects the version tokens a span of text names, in the form the documents write them.
     *
     * @param  text the span to read
     * @return those tokens, as {@code V2_2}, without duplicates
     */
    private static TreeSet<String> namedVersionTokens(final String text) {
        final TreeSet<String> named = new TreeSet<>();
        final Matcher token = Pattern.compile("\\bV\\d+(?:_\\d+)*\\b").matcher(text);
        while (token.find()) {
            named.add(token.group());
        }
        return named;
    }

    /**
     * Collects the migration filenames a span of text names.
     *
     * @param  text the span to read
     * @return those filenames, without duplicates
     */
    private static TreeSet<String> namedMigrationFiles(final String text) {
        final TreeSet<String> named = new TreeSet<>();
        final Matcher filename = MIGRATION_FILENAME.matcher(text);
        while (filename.find()) {
            named.add(filename.group());
        }
        return named;
    }

    /**
     * Extracts one section of a document, from its heading to the next heading at or above its level.
     *
     * @param  document the document text
     * @param  heading  the section's heading, in full
     * @param  boundary the heading marker that ends the section
     * @return the section's text, its own heading included
     */
    private static String section(final String document, final String heading, final String boundary) {
        final int start = document.indexOf(heading);
        assertThat(start)
                .as("the document must keep the section headed \"%s\"; a figure derived from a section "
                        + "that has been renamed away is derived from nothing", heading)
                .isNotNegative();
        final int body = start + heading.length();
        final int next = document.indexOf("\n" + boundary, body);
        return next < 0 ? document.substring(start) : document.substring(start, next);
    }

    /**
     * Counts the java files a compiler run over the given source root would compile.
     *
     * @param root the source root
     * @return the number of java files beneath it
     */
    private static int compiledSources(final Path root) {
        try (Stream<Path> tree = Files.walk(root)) {
            return (int) tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .count();
        } catch (final IOException problem) {
            throw new AssertionError("the " + root + " tree must be walkable", problem);
        }
    }

    /**
     * How many lines of a source tree mention the warning-suppression annotation, as the raw grep counts.
     *
     * <p>Counted as {@code grep -rn} counts: one per matching line, over the file as written, with no
     * comment or literal stripping. That is deliberately the number the published command produces rather
     * than the gated number, which the blanked-source measurement in {@code e2e/GateVerificationTest}
     * produces.
     *
     * @param  root the tree to count over
     * @return the number of matching lines
     * @throws IOException if the tree cannot be walked
     */
    private static int suppressionMentions(final Path root) throws IOException {
        int mentions = 0;
        try (Stream<Path> tree = Files.walk(root)) {
            for (final Path source : tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                for (final String line : read(source).split("\n", -1)) {
                    if (line.contains(SUPPRESSION_ANNOTATION)) {
                        mentions++;
                    }
                }
            }
        }
        return mentions;
    }

    /**
     * How many measured runs the Gate 3 table on the evidence page records.
     *
     * <p>Counted off the page the way a reader counts: find the measured-runs header, skip the alignment
     * line beneath it, and count the table lines that follow until one is not a table line. A row whose
     * figures are placeholders is still a line of the table and is still counted, because the figure this
     * feeds is "how many rows the table carries" - the separate question of whether every row is a
     * well-formed measurement belongs to {@code e2e/GateVerificationTest}, which parses the figures.
     *
     * @return the number of data rows beneath the measured-runs header
     * @throws IOException if the page cannot be read
     */
    private static int measuredPerformanceRowCount() throws IOException {
        final List<String> lines = List.of(read(GATE_EVIDENCE_PAGE).split("\n", -1));
        int rows = 0;
        for (int index = 0; index < lines.size(); index++) {
            if (!PERFORMANCE_TABLE_HEADER.equals(lines.get(index).strip())) {
                continue;
            }
            int cursor = index + 2;
            while (cursor < lines.size() && lines.get(cursor).startsWith("|")) {
                rows++;
                cursor++;
            }
            return rows;
        }
        return 0;
    }

    /**
     * Reads a document with every run of whitespace collapsed to one space, so a published figure
     * that a line break separates from the noun it qualifies still reads as one phrase.
     *
     * @param document the document to read
     * @return its text, with whitespace flattened
     * @throws IOException if the document cannot be read
     */
    private static String flattened(final Path document) throws IOException {
        return read(document).replaceAll("\\s+", " ");
    }

    /**
     * Lists every application source file the unsafe-code audit is scoped to.
     *
     * @return those files
     * @throws IOException if the source tree cannot be walked
     */
    private static List<Path> applicationSources() throws IOException {
        try (Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Concatenates the audited source so a family can be counted across the whole tree at once.
     *
     * @return every application source, joined
     * @throws IOException if the source tree cannot be read
     */
    private static String auditedSource() throws IOException {
        final StringBuilder joined = new StringBuilder();
        for (final Path source : applicationSources()) {
            joined.append(read(source)).append('\n');
        }
        return joined.toString();
    }

    /**
     * Counts non-overlapping occurrences of one literal.
     *
     * @param  text    the text to search
     * @param  literal the literal to count
     * @return how many times it occurs
     */
    private static int occurrencesOf(final String text, final String literal) {
        int count = 0;
        int from = text.indexOf(literal);
        while (from >= 0) {
            count++;
            from = text.indexOf(literal, from + literal.length());
        }
        return count;
    }

    /**
     * Blanks comments and literals so a reference is counted only where it is really a reference.
     *
     * <p>A single left-to-right pass rather than successive regular expressions, because a comment
     * marker inside a string and a quote inside a comment each defeat a two-pass stripper.</p>
     *
     * @param  source the source text
     * @return the same text with comment and literal content replaced by blanks
     */
    private static String codeOnly(final String source) {
        final StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        Scan state = Scan.CODE;
        while (index < source.length()) {
            final char current = source.charAt(index);
            final char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        state = Scan.LINE_COMMENT;
                        index += 2;
                    } else if (current == '/' && next == '*') {
                        state = Scan.BLOCK_COMMENT;
                        index += 2;
                    } else if (current == '"' && source.startsWith("\"\"\"", index)) {
                        state = Scan.TEXT_BLOCK;
                        index += 3;
                    } else if (current == '"') {
                        state = Scan.STRING;
                        index++;
                    } else if (current == '\'') {
                        state = Scan.CHARACTER;
                        index++;
                    } else {
                        code.append(current);
                        index++;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = Scan.CODE;
                        code.append('\n');
                    }
                    index++;
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = Scan.CODE;
                        index += 2;
                    } else {
                        code.append(current == '\n' ? '\n' : ' ');
                        index++;
                    }
                }
                case TEXT_BLOCK -> {
                    if (source.startsWith("\"\"\"", index)) {
                        state = Scan.CODE;
                        index += 3;
                    } else {
                        code.append(current == '\n' ? '\n' : ' ');
                        index++;
                    }
                }
                case STRING, CHARACTER -> {
                    final char closing = state == Scan.STRING ? '"' : '\'';
                    if (current == '\\') {
                        index += 2;
                    } else {
                        if (current == closing) {
                            state = Scan.CODE;
                        }
                        index++;
                    }
                    code.append(' ');
                }
                default -> throw new AssertionError("unreachable scanner state " + state);
            }
        }
        return code.toString();
    }

    /** The states the source scanner moves through. */
    private enum Scan {
        /** Ordinary code, where a reference counts. */
        CODE,
        /** Inside a single-line comment. */
        LINE_COMMENT,
        /** Inside a block or documentation comment. */
        BLOCK_COMMENT,
        /** Inside a text block. */
        TEXT_BLOCK,
        /** Inside a string literal. */
        STRING,
        /** Inside a character literal. */
        CHARACTER
    }

    /**
     * Reads the variables the production profile resolves with no fallback.
     *
     * <p>The profile documents its own convention using a bare {@code ${VARIABLE}} in a comment, so
     * that name is excluded: it is prose about the rule rather than an application of it.</p>
     *
     * @return those variable names
     * @throws IOException if the profile cannot be read
     */
    private static TreeSet<String> requiredProductionVariables() throws IOException {
        final TreeSet<String> required = new TreeSet<>();
        final Matcher matcher = NO_FALLBACK_REFERENCE.matcher(read(PRODUCTION_PROFILE));
        while (matcher.find()) {
            required.add(matcher.group(1));
        }
        required.remove(CONVENTION_PLACEHOLDER);
        return required;
    }

    /**
     * Reads the variables the production profile resolves with a default.
     *
     * @return those variable names
     * @throws IOException if the profile cannot be read
     */
    private static TreeSet<String> defaultedProductionVariables() throws IOException {
        final TreeSet<String> defaulted = new TreeSet<>();
        final Matcher matcher = DEFAULTED_REFERENCE.matcher(read(PRODUCTION_PROFILE));
        while (matcher.find()) {
            defaulted.add(matcher.group(1));
        }
        defaulted.remove(CONVENTION_PLACEHOLDER);
        return defaulted;
    }

    /**
     * Extracts the paragraph that publishes the measured suite figures.
     *
     * @return that paragraph
     * @throws IOException if the gate evidence page cannot be read
     */
    private static String suiteFiguresSentence() throws IOException {
        final String page = read(GATE_EVIDENCE_PAGE);
        final int start = page.indexOf("The suite behind those figures");
        assertThat(start)
                .as("the gate evidence page must keep the paragraph that publishes the suite "
                        + "figures; it is the only place in the set that may state them")
                .isNotNegative();
        final int end = page.indexOf("\n\n", start);
        return end < 0 ? page.substring(start) : page.substring(start, end);
    }

    /**
     * Lists the test class file names the unit tier runs, under the build's own rules.
     *
     * @return those file names
     * @throws IOException if the test tree cannot be walked
     */
    private static List<String> unitTierClasses() throws IOException {
        return testClasses(false);
    }

    /**
     * Lists the test class file names the integration tier runs, under the build's own rules.
     *
     * @return those file names
     * @throws IOException if the test tree cannot be walked
     */
    private static List<String> integrationTierClasses() throws IOException {
        return testClasses(true);
    }

    /**
     * Applies the surefire and failsafe inclusion rules to the test tree.
     *
     * <p>Failsafe claims {@code **}{@code /*IT.java}, {@code **}{@code /*E2ETest.java} and anything
     * beneath an {@code e2e} package; surefire claims every other {@code *Test.java}. The rules are
     * expressed here once so the two counts cannot be taken under different readings of them.</p>
     *
     * @param integrationTier whether to list the failsafe tier rather than the surefire tier
     * @return the matching file names
     * @throws IOException if the test tree cannot be walked
     */
    private static List<String> testClasses(final boolean integrationTier) throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("src", "test", "java"))) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> {
                        final String name = path.getFileName().toString();
                        final boolean claimedByFailsafe = name.endsWith("IT.java")
                                || name.endsWith("E2ETest.java")
                                || endToEndPackage(path);
                        return integrationTier
                                ? claimedByFailsafe && (name.endsWith("IT.java")
                                        || name.endsWith("Test.java"))
                                : !claimedByFailsafe && name.endsWith("Test.java");
                    })
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /**
     * Decides whether a path sits beneath the end-to-end package the build excludes wholesale.
     *
     * @param path the file to classify
     * @return whether any directory on the path is the end-to-end package
     */
    private static boolean endToEndPackage(final Path path) {
        for (final Path element : path) {
            if ("e2e".equals(element.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the package table's published counts, keyed by dotted package name.
     *
     * @return the published count for each package the table names
     * @throws IOException if the architecture page cannot be read
     */
    private static Map<String, Integer> publishedPackageCounts() throws IOException {
        final Map<String, Integer> counts = new TreeMap<>();
        read(ARCHITECTURE_PAGE).lines().forEach(line -> {
            final Matcher matcher = PACKAGE_ROW.matcher(line);
            if (matcher.matches()) {
                counts.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
            }
        });
        return counts;
    }

    /**
     * Returns the whole table row for one package, so a claim inside it can be asserted.
     *
     * @param packageName the dotted package name the row names
     * @return the row's text
     * @throws IOException if the architecture page cannot be read
     */
    private static String packageRowText(final String packageName) throws IOException {
        return read(ARCHITECTURE_PAGE).lines()
                .filter(line -> {
                    final Matcher matcher = PACKAGE_ROW.matcher(line);
                    return matcher.matches() && packageName.equals(matcher.group(1));
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the architecture page has no package table row for `" + packageName + "`"));
    }

    /**
     * Finds every package beneath the source root that holds at least one Java file.
     *
     * @return the dotted names of those packages, relative to the source root
     * @throws IOException if the source tree cannot be walked
     */
    private static List<String> packagesHoldingSource() throws IOException {
        try (Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
            return tree.filter(Files::isDirectory)
                    .filter(directory -> !directory.equals(SOURCE_ROOT))
                    .filter(directory -> javaFileCount(directory) > 0)
                    .map(directory -> SOURCE_ROOT.relativize(directory).toString()
                            .replace('\\', '.').replace('/', '.'))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Counts the Java files directly inside one directory, ignoring its subdirectories.
     *
     * @param directory the directory to count
     * @return the number of Java files it holds directly
     */
    private static int javaFileCount(final Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return (int) entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .count();
        } catch (final IOException problem) {
            throw new AssertionError("cannot count the Java files in " + directory, problem);
        }
    }

    /**
     * Returns the service package directory.
     *
     * @return the directory holding the service classes
     */
    private static Path serviceDirectory() {
        return SOURCE_ROOT.resolve("service");
    }

    /**
     * Counts the hand-written fixed-width record mappers, one per verified record layout plus the
     * statement work area.
     *
     * @return the number of {@code *RecordMapper.java} files in the utility package
     */
    private static int recordMapperCount() {
        try (Stream<Path> entries = Files.list(SOURCE_ROOT.resolve("util"))) {
            return (int) entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("RecordMapper.java"))
                    .count();
        } catch (final IOException problem) {
            throw new AssertionError("cannot count the record mappers", problem);
        }
    }

    /**
     * Collects every figure one document publishes in one shape, in the order it publishes them.
     *
     * @param  document the document to read
     * @param  shape    the published shape, capturing the figure in group one
     * @return those figures, one per occurrence
     * @throws IOException if the document cannot be read
     */
    private static List<Integer> figuresIn(final Path document, final Pattern shape) throws IOException {
        final List<Integer> figures = new ArrayList<>();
        final Matcher occurrence = shape.matcher(flattened(document));
        while (occurrence.find()) {
            figures.add(Integer.valueOf(occurrence.group(1)));
        }
        return figures;
    }

    /**
     * Extracts the fenced shell block the module manual offers as the way to count its own layers.
     *
     * @return the block's contents
     * @throws IOException if the manual cannot be read
     */
    private static String manualCountedBlock() throws IOException {
        final String manual = read(Path.of("README.md"));
        final int invitation = manual.indexOf("Count any of them yourself rather than trusting the table:");
        assertThat(invitation)
                .as("the module manual must keep the block that shows how its layer counts are obtained; "
                        + "without it the table is unfalsifiable prose")
                .isNotNegative();
        final int opening = manual.indexOf("```", invitation);
        final int start = manual.indexOf('\n', opening) + 1;
        final int end = manual.indexOf("```", start);
        assertThat(end)
                .as("the counted-directly block in the module manual is unterminated")
                .isNotNegative();
        return manual.substring(start, end);
    }

    /**
     * Counts the concrete service classes, which are the files named for the role they carry.
     *
     * @return the number of {@code *Service.java} files in the service package
     */
    private static int concreteServiceCount() {
        try (Stream<Path> entries = Files.list(serviceDirectory())) {
            return (int) entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("Service.java"))
                    .count();
        } catch (final IOException problem) {
            throw new AssertionError("cannot count the service classes", problem);
        }
    }

    /**
     * Extracts the fenced shell block the page offers as the way to count the services.
     *
     * @return the block's contents
     * @throws IOException if the architecture page cannot be read
     */
    private static String countedDirectlyBlock() throws IOException {
        final String page = read(ARCHITECTURE_PAGE);
        final int invitation = page.indexOf("Counted directly:");
        assertThat(invitation)
                .as("the architecture page must keep the block that shows how its service figures "
                        + "are obtained; without it the figures are unfalsifiable prose")
                .isNotNegative();
        final int opening = page.indexOf("```", invitation);
        final int start = page.indexOf('\n', opening) + 1;
        final int end = page.indexOf("```", start);
        assertThat(end)
                .as("the counted-directly block is unterminated on the architecture page")
                .isNotNegative();
        return page.substring(start, end);
    }

    /**
     * Extracts the support services the page enumerates as carrying no COBOL paragraph.
     *
     * @return those class names, in the order the page names them
     * @throws IOException if the architecture page cannot be read
     */
    private static List<String> namedSupportServices() throws IOException {
        final String page = read(ARCHITECTURE_PAGE);
        final int start = page.indexOf("not itself be:");
        final int end = page.indexOf("The first two are described under", start);
        assertThat(start)
                .as("the architecture page must keep the paragraph that enumerates the support "
                        + "services; a bare count of them could not be checked")
                .isNotNegative();
        assertThat(end)
                .as("the support-service enumeration on the architecture page is unterminated")
                .isGreaterThan(start);

        final List<String> named = new ArrayList<>();
        final Matcher matcher = PROSE_SERVICE.matcher(page.substring(start, end));
        while (matcher.find()) {
            named.add(matcher.group(1));
        }
        return named;
    }

    /**
     * Extracts the POM's delimited security-remediation block.
     *
     * @return the block's contents, comments included
     * @throws IOException if the build file cannot be read
     */
    private static String overrideBlock() throws IOException {
        final String pom = read(POM);
        final int heading = pom.indexOf("Security remediation overrides.");
        assertThat(heading)
                .as("the build file must keep the delimited security-remediation block; the "
                        + "documented override count is defined as that block's size")
                .isNotNegative();
        final int start = pom.indexOf("-->", heading) + "-->".length();
        final int end = pom.indexOf("<!-- ==", start);
        assertThat(end)
                .as("the security-remediation block in the build file is unterminated")
                .isGreaterThan(start);
        return pom.substring(start, end);
    }

    /**
     * Reads every managed-version property the security-remediation block declares.
     *
     * @return each property name mapped to the version it pins
     * @throws IOException if the build file cannot be read
     */
    private static Map<String, String> declaredOverrides() throws IOException {
        final Map<String, String> declared = new TreeMap<>();
        final Matcher matcher = VERSION_PROPERTY.matcher(overrideBlock());
        while (matcher.find()) {
            declared.put(matcher.group(1), matcher.group(2).strip());
        }
        return declared;
    }

    /**
     * Reads the override table the module README publishes.
     *
     * @return each property name mapped to the version the table states
     * @throws IOException if the README cannot be read
     */
    private static Map<String, String> publishedOverrides() throws IOException {
        final Map<String, String> published = new TreeMap<>();
        read(MODULE_README).lines().forEach(line -> {
            final Matcher matcher = OVERRIDE_ROW.matcher(line);
            if (matcher.find()) {
                published.put(matcher.group(1), matcher.group(2).strip());
            }
        });
        return published;
    }

    /**
     * Decides whether a line inside the override block continues a preceding comment.
     *
     * <p>The block's comments wrap across lines, so a continuation carries neither an opening nor a
     * closing marker. Treating those as unexplained content would make the block's own explanations
     * fail the assertion that guards it.</p>
     *
     * @param trimmed the line, already stripped of surrounding whitespace
     * @return whether the line is prose rather than a declaration
     */
    private static boolean isCommentContinuation(final String trimmed) {
        return !trimmed.startsWith("<") && !trimmed.contains("</");
    }

    /**
     * Spells out a count the documents write as a word.
     *
     * @param count the measured count
     * @return the English word for it
     */
    private static String numberWord(final int count) {
        assertThat(count)
                .as("a count outside the range the documents spell out means the prose needs "
                        + "rewriting rather than this range widening. The one range that grows by "
                        + "construction is the measured-run table: every recorded full verify adds "
                        + "three rows to it, so it crosses each ten without any prose changing "
                        + "intent, and the documents state that count in words like every other. "
                        + "Words past twenty are carried for that reason and for no other")
                .isBetween(0, NUMBER_WORDS.size() - 1);
        return NUMBER_WORDS.get(count);
    }

    /**
     * Capitalises a number word for use at the start of a heading.
     *
     * @param word the lower-case word
     * @return the same word with its first letter upper-cased
     */
    private static String capitalise(final String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /**
     * Reads a file as UTF-8 text.
     *
     * @param path the file to read
     * @return its contents
     * @throws IOException if the file cannot be read
     */
    private static String read(final Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
