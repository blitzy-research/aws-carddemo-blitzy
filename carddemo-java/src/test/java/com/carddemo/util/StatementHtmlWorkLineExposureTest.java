/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.carddemo.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source census over the production tree, guarding the one published path in
 * {@link StatementHtmlTemplates} that does not escape the data it is given.
 *
 * <h2>What this test guards, and why a census rather than a behavioural assertion</h2>
 *
 * <p>{@link StatementHtmlTemplates} publishes two kinds of line builder. The three work-line
 * composers escape caller-supplied data before composing it, so markup in a customer name or an
 * address cannot form a tag in an emitted record. {@link StatementHtmlTemplates#workLine(String)}
 * does not escape: it is a guarded fitter for content that has <em>already</em> been composed,
 * refusing anything outside printable US-ASCII and enforcing the exact hundred-byte width, but
 * moving markup characters through untouched because it cannot distinguish a legitimate paragraph
 * literal from an injected one.</p>
 *
 * <p>That distinction cannot be enforced by any assertion about the fitter's own behaviour, because
 * the fitter behaves correctly: passing unescaped field data into it is a <em>caller</em> defect.
 * The only thing that can establish the absence of such a caller is an inspection of the callers,
 * which is what this file does. It reads the production sources from disk and fails if any of them
 * calls the raw fitter.</p>
 *
 * <h2>What this evidence does and does not prove</h2>
 *
 * <p>It proves that no source under {@code src/main/java} contains a call to the raw fitter, and it
 * now also proves who does call the escaping composers. When this file was first written the module
 * shipped no statement-generation service, so the raw fitter and the three escaping composers alike
 * had no production call site of any kind, and the second claim was recorded as an emptiness with a
 * note asking a later change to name the caller rather than delete the expectation. That service has
 * since arrived, so the claim is now the stronger one: the composers are reached from exactly one
 * class, and the raw fitter is still reached from none. The service-owned
 * {@code StatementLineSummary} carrier is also required to be present in the scanned tree: it carries
 * statement data but owns no formatting, so it must not become a second composing path. A second
 * record-composing caller appearing anywhere in the production tree fails the build, which is what
 * the standing guard was for.</p>
 *
 * <p>A census can fail in a way a behavioural test cannot: it can pass because it looked at
 * nothing. Every assertion of absence below is therefore paired with an assertion of presence
 * &mdash; that the walk found production sources at all, that it found the class under discussion,
 * and that it found the documentation references to the fitter that are known to exist. If the
 * scan location were ever wrong, those companions fail rather than the absence claim passing
 * vacuously.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy estate at checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release
 * stamp CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19. The legacy generator composed these records
 * from paragraph literals wrapped around display fields with no encoding of any kind
 * [app/cbl/CBSTM03A.CBL:L221-L223]; escaping the data positions is the recorded divergence
 * (decision D-49), and this census is what keeps the unescaped remnant unused.</p>
 */
@DisplayName("StatementHtmlTemplates :: no production source routes field data through the raw fitter")
class StatementHtmlWorkLineExposureTest {

    /** The production source tree, relative to the module directory the build runs tests from. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /** The class that publishes the fitter; its own declaration is not a call site. */
    private static final String TEMPLATE_CLASS_FILE_NAME = "StatementHtmlTemplates.java";

    /**
     * The one production class that composes statement records, and therefore the one class licensed
     * to call an escaping composer.
     *
     * <p>Named rather than left as an absence, exactly as the earlier form of the composer census
     * asked when it recorded that no such class shipped yet. It composes the account heading, the
     * customer name line, the three address lines, the three basic-detail lines and the three
     * transaction cells of {@code [app/cbl/CBSTM03A.CBL:L529, L560-L592, L613-L633, L686-L716]}.
     */
    private static final String STATEMENT_SERVICE_FILE_NAME = "StatementGenerationService.java";

    /**
     * The data-only service carrier produced by the statement service.
     *
     * <p>Its presence is asserted so the census cannot accidentally omit the new service-owned
     * statement shape while still finding the older template and generator files. The exact-caller
     * assertion below then proves that carrying a line did not make this type another formatter.
     */
    private static final String STATEMENT_SUMMARY_FILE_NAME = "StatementLineSummary.java";

    /** The bare method name whose call sites are being counted. */
    private static final String FITTER_NAME = "workLine";

    /**
     * A conservative floor on the number of production sources, so a walk that found almost
     * nothing cannot satisfy the absence assertions. The module ships well over a hundred classes;
     * this figure is deliberately far below that so it does not become a maintenance tripwire.
     */
    private static final int MINIMUM_PRODUCTION_SOURCES = 25;

    /**
     * Reads every {@code .java} file under the production source root.
     *
     * @return one entry per production source file, each carrying its path and its text
     */
    private static List<SourceFile> productionSources() {
        assertThat(PRODUCTION_SOURCE_ROOT)
                .as("the production source root must be readable from the test working directory,"
                        + " or every absence assertion in this file would be vacuous")
                .isDirectory();

        final List<SourceFile> sources = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            for (final Path path : tree.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                    .toList()) {
                sources.add(new SourceFile(path, readText(path)));
            }
        } catch (final IOException problem) {
            throw new UncheckedIOException("unable to walk " + PRODUCTION_SOURCE_ROOT, problem);
        }
        return sources;
    }

    /**
     * Reads one source file as text.
     *
     * @param path the file to read
     * @return the file's content
     */
    private static String readText(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException problem) {
            throw new UncheckedIOException("unable to read " + path, problem);
        }
    }

    /**
     * Reports whether a source line mentioning the fitter is documentation rather than code.
     *
     * <p>Javadoc continuation lines begin with an asterisk, ordinary comments with a double slash,
     * and a cross-reference names the member with {@code @link}, {@code @see} or a {@code #}
     * qualifier. Anything else is treated as code, which is the safe direction to err in: a
     * misclassified comment produces a loud failure that a reader can dismiss, whereas a
     * misclassified call would be an undetected exposure.
     *
     * @param line the source line, exactly as it appears in the file
     * @return {@code true} when the mention is documentation
     */
    private static boolean isDocumentation(final String line) {
        final String trimmed = line.strip();
        return trimmed.startsWith("*")
                || trimmed.startsWith("//")
                || trimmed.startsWith("/*")
                || line.contains("@link")
                || line.contains("@see")
                || line.contains("#" + FITTER_NAME);
    }

    /**
     * Reports whether the fitter's name at the given position is the whole identifier.
     *
     * <p>The name is a suffix of {@code addressWorkLine}, {@code basicDetailsWorkLine} and
     * {@code transactionWorkLine} once case is set aside, so a plain substring search would count
     * the three escaping composers as though they were the raw one. A match therefore only counts
     * when the character before it cannot continue an identifier.
     *
     * @param line  the source line being examined
     * @param index the index at which the name was found
     * @return {@code true} when the match is a whole identifier
     */
    private static boolean isWholeIdentifier(final String line, final int index) {
        if (index == 0) {
            return true;
        }
        final char preceding = line.charAt(index - 1);
        return !Character.isJavaIdentifierPart(preceding);
    }

    /**
     * Reports whether a line declares the fitter rather than calling it.
     *
     * @param line the source line being examined
     * @return {@code true} when the line is the fitter's own declaration
     */
    private static boolean isDeclaration(final String line) {
        return line.contains("String " + FITTER_NAME + "(");
    }

    /**
     * Collects every code call site of the raw fitter across the production tree.
     *
     * @param sources the production sources to examine
     * @return a description of each call site found, empty when there are none
     */
    private static List<String> rawFitterCallSites(final List<SourceFile> sources) {
        final String needle = FITTER_NAME + "(";
        final List<String> callSites = new ArrayList<>();

        for (final SourceFile source : sources) {
            final String[] lines = source.text().split("\n", -1);
            for (int number = 0; number < lines.length; number++) {
                final String line = lines[number];
                if (isDocumentation(line) || isDeclaration(line)) {
                    continue;
                }
                int index = line.indexOf(needle);
                while (index >= 0) {
                    if (isWholeIdentifier(line, index)) {
                        callSites.add(source.path() + ":" + (number + 1) + " -> " + line.strip());
                    }
                    index = line.indexOf(needle, index + 1);
                }
            }
        }
        return callSites;
    }

    /**
     * Counts the documentation references to the fitter across the production tree.
     *
     * @param sources the production sources to examine
     * @return the number of documentation lines mentioning the fitter
     */
    private static int documentationReferences(final List<SourceFile> sources) {
        int references = 0;
        for (final SourceFile source : sources) {
            for (final String line : source.text().split("\n", -1)) {
                if (isDocumentation(line) && line.contains(FITTER_NAME)) {
                    references++;
                }
            }
        }
        return references;
    }

    @Test
    @DisplayName("the census reads the production tree it claims to read, so its absence findings "
            + "cannot pass vacuously")
    void theCensusActuallyReadsTheProductionTree() {
        final List<SourceFile> sources = productionSources();

        assertThat(sources.size())
                .as("the walk must find the production sources, or nothing below means anything")
                .isGreaterThanOrEqualTo(MINIMUM_PRODUCTION_SOURCES);

        assertThat(sources)
                .as("the walk must reach the class that publishes the fitter")
                .anySatisfy(source -> assertThat(source.path().getFileName().toString())
                        .isEqualTo(TEMPLATE_CLASS_FILE_NAME));

        assertThat(sources)
                .as("the walk must reach the service-owned statement carrier, or the exact-caller "
                        + "claim could omit the new data path and pass vacuously")
                .anySatisfy(source -> assertThat(source.path().getFileName().toString())
                        .isEqualTo(STATEMENT_SUMMARY_FILE_NAME));

        assertThat(documentationReferences(sources))
                .as("the fitter is referred to by name in the production javadoc, and finding none"
                        + " would mean this census is matching nothing at all")
                .isPositive();
    }

    @Test
    @DisplayName("no production source calls the raw fitter, so caller-supplied field data has no "
            + "route to an unescaped record")
    void noProductionSourceCallsTheRawFitter() {
        final List<String> callSites = rawFitterCallSites(productionSources());

        // Stated as the full list rather than a count, so a failure names the offending file and
        // line instead of reporting only that the number moved.
        assertThat(callSites)
                .as("the raw fitter escapes nothing, so any production call site is a potential"
                        + " unescaped-data path; compose through addressWorkLine,"
                        + " basicDetailsWorkLine or transactionWorkLine instead, or escape with"
                        + " escapeText before fitting already-composed content")
                .isEmpty();
    }

    @Test
    @DisplayName("the three escaping composers are reached from exactly one production class, the "
            + "statement generation service, and from nothing else")
    void theEscapingComposersAreReachedOnlyFromTheStatementService() {
        // Updated rather than deleted when the statement-generation service arrived, exactly as the
        // earlier form of this test asked while it was still recording an absence. The guard is
        // unchanged in substance and is now stronger than an emptiness claim: the composers may be
        // reached, but only from the one class whose job is to compose statement records, so a second
        // caller appearing anywhere in the production tree still fails the build. The preceding test
        // continues to hold, because that service reaches for an escaping composer and never for the
        // raw fitter.
        final List<SourceFile> sources = productionSources().stream()
                .filter(source -> !source.path().getFileName().toString()
                        .equals(TEMPLATE_CLASS_FILE_NAME))
                .toList();

        final List<String> composerCallSites = new ArrayList<>();
        for (final SourceFile source : sources) {
            final String[] lines = source.text().split("\n", -1);
            for (int number = 0; number < lines.length; number++) {
                final String line = lines[number];
                if (isDocumentation(line)) {
                    continue;
                }
                for (final String composer :
                        List.of("addressWorkLine(", "basicDetailsWorkLine(", "transactionWorkLine(")) {
                    if (line.contains(composer)) {
                        composerCallSites.add(source.path() + ":" + (number + 1));
                    }
                }
            }
        }

        assertThat(composerCallSites)
                .as("the escaping composers must be reached, or the statement service would have"
                        + " composed its records some other way; finding none would mean this census"
                        + " is matching nothing at all")
                .isNotEmpty();

        final List<String> callingFiles = composerCallSites.stream()
                .map(site -> Path.of(site.substring(0, site.lastIndexOf(':'))).getFileName()
                        .toString())
                .distinct()
                .toList();

        assertThat(callingFiles)
                .as("only the statement-generation service composes statement records, so it is the"
                        + " only class licensed to call an escaping composer; any other caller is a"
                        + " new record-composing path that has not been reviewed")
                .containsExactly(STATEMENT_SERVICE_FILE_NAME);
    }

    /**
     * One production source file: its path, and its text.
     *
     * @param path the file's path relative to the module directory
     * @param text the file's full content
     */
    private record SourceFile(Path path, String text) {
    }

}
