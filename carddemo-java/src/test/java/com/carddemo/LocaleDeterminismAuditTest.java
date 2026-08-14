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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forbids, by reading every source file in the module, the constructs whose behaviour is decided by the
 * ambient default locale rather than by the code.
 *
 * <p><strong>Why this file exists.</strong> The estate's output formats are fixed-width byte images, so a
 * digit that is not an ASCII digit is a parity defect rather than a cosmetic one. The production encoders
 * already knew this: {@code CobolStringUtils}, {@code StatementTextTemplates}, {@code JclCardImageBuilder}
 * and {@code RejectRecordWriter} each carry their own locale-invariance suites, and all forty-nine
 * {@code String.format} calls in the module already name a locale. What no test held to the same rule was
 * the code that builds the fixtures those suites run against. Four of them rendered numbers with
 * {@code String.formatted}, and one is enough to make the point: a page of user identifiers built with
 * {@code "USER%04d"} came out as {@code USER٠٠٠١} under the {@code ar-EG} locale, whose CLDR default
 * numbering system is {@code arab}, so every assertion in that class compared against a string it could
 * never match.
 *
 * <p><strong>Why {@code String.formatted} is banned outright rather than reviewed per call site.</strong>
 * {@code String.formatted} has no overload that accepts a {@link java.util.Locale} - it resolves
 * {@code Locale.getDefault(Locale.Category.FORMAT)} and offers the caller no way to say otherwise. A rule
 * of the form "use the shorthand only when the conversions happen to be locale-independent" would require
 * every author and every reviewer to classify conversions correctly forever, and would still be wrong the
 * first time a {@code %s} is handed a {@link java.util.Formattable} or a {@code %S} is written instead. A
 * bright line has no such failure mode: the shorthand is absent from the module, and
 * {@code String.format(Locale.ROOT, …)} is how a value is rendered.
 *
 * <p><strong>Why the audit reads source text.</strong> The workflow's locale gate re-executes the unit
 * tier under two hostile locales, which is a genuinely strong check, but it can only observe defects in
 * code that tier runs. Three of the four defects fixed alongside this file live in integration tests, which
 * the gate does not re-execute, and one of those renders a port number into a file a real Prometheus
 * parses. Reading the text catches every file in the module at unit-tier cost, with no container and no
 * second execution of anything.
 *
 * <p><strong>Comment lines are skipped, and nothing else is.</strong> A line whose first non-blank
 * character begins a comment is not code, and several files legitimately name these constructs in prose in
 * order to forbid them. The skip is deliberately narrow: it does not attempt to strip comments from lines
 * that also carry code, because a stripper would have to lex string literals correctly and any mistake it
 * made would hide a violation rather than report one. A violation written after code on the same line is
 * therefore still caught.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. It reads files from the module
 * directory the build runs tests from, and asserts a floor on the number of files read first, because an
 * absence assertion over an empty walk is vacuous.
 *
 * <p>Decision log DL-187 records the ban and the four call sites that motivated it. DL-027 records why the
 * amount masks may not use a locale-sensitive formatter at all.
 *
 * @since 1.0.0
 */
@DisplayName("locale determinism :: the ambient default locale decides nothing in this module")
final class LocaleDeterminismAuditTest {

    /** The production source tree, relative to the module directory the build runs tests from. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /** The test source tree, which is held to the same rule because it builds the fixtures. */
    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");

    /**
     * This file, which is the one file the walk skips.
     *
     * <p>It has to be skipped, and the reason is inherent rather than incidental: an audit that forbids a
     * construct has to name that construct, and it names it as a string literal. Every rule below matched
     * its own needle before this exclusion existed. The exclusion is one file wide, asserted to be one file
     * wide by {@link #theOnlySkippedFileIsThisOne()}, so it cannot quietly grow into a licence list.
     */
    private static final Path SELF = TEST_SOURCE_ROOT
            .resolve(Path.of("com", "carddemo", "LocaleDeterminismAuditTest.java"));

    /**
     * A floor on the number of production sources, so an absence assertion cannot pass over an empty walk.
     * Deliberately far below the real count, which grows.
     */
    private static final int MINIMUM_PRODUCTION_SOURCES = 200;

    /** The same floor for the test tree. */
    private static final int MINIMUM_TEST_SOURCES = 200;

    /** One source file, paired with the lines of it that are code rather than prose. */
    private record SourceFile(Path path, List<CodeLine> codeLines) { }

    /** One line of code, carrying its one-based number so a failure names a place a reader can open. */
    private record CodeLine(int number, String text) { }

    /**
     * Reads a tree and returns every {@code .java} file in it with its comment-only lines removed.
     *
     * @param root the tree to read
     * @return one entry per source file, in walk order
     */
    private static List<SourceFile> read(final Path root) {
        assertThat(root).as("the source tree must be present for this audit to mean anything").isDirectory();
        final List<SourceFile> sources = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.equals(SELF))
                    .sorted()
                    .forEach(path -> sources.add(new SourceFile(path, codeLinesOf(path))));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Could not read " + root, unreadable);
        }
        return List.copyOf(sources);
    }

    /**
     * Splits a file into lines and drops the ones that begin a comment.
     *
     * @param path the file to read
     * @return the code lines, each carrying its one-based line number
     */
    private static List<CodeLine> codeLinesOf(final Path path) {
        final List<CodeLine> lines = new ArrayList<>();
        final String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Could not read " + path, unreadable);
        }
        final String[] split = text.split("\n", -1);
        for (int index = 0; index < split.length; index++) {
            final String trimmed = split[index].strip();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            lines.add(new CodeLine(index + 1, split[index]));
        }
        return List.copyOf(lines);
    }

    /**
     * Collects every code line in both trees that contains the given text.
     *
     * @param needle the text to look for
     * @return a readable {@code path:line} description of each occurrence
     */
    private static List<String> occurrencesOf(final String needle) {
        final List<String> found = new ArrayList<>();
        for (final SourceFile source : allSources()) {
            for (final CodeLine line : source.codeLines()) {
                if (line.text().contains(needle)) {
                    found.add(source.path() + ":" + line.number() + " -> " + line.text().strip());
                }
            }
        }
        return found;
    }

    /**
     * Reads both trees.
     *
     * @return every source file in the module
     */
    private static List<SourceFile> allSources() {
        final List<SourceFile> sources = new ArrayList<>(read(PRODUCTION_SOURCE_ROOT));
        sources.addAll(read(TEST_SOURCE_ROOT));
        return List.copyOf(sources);
    }

    /**
     * Extracts the argument list of a call, balancing parentheses from the opening one.
     *
     * @param line the line to read
     * @param callStart the index of the first character of the call name
     * @param callText the call text, whose trailing character is the opening parenthesis
     * @return the arguments as written, or an empty string when the list continues onto another line
     */
    private static String argumentsOf(final String line, final int callStart, final String callText) {
        int depth = 0;
        final int open = callStart + callText.length() - 1;
        for (int index = open; index < line.length(); index++) {
            final char character = line.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return line.substring(open + 1, index);
                }
            }
        }
        return line.substring(open + 1);
    }

    @Test
    @DisplayName("the only file the walk skips is this one, so the exclusion cannot grow into a licence list")
    void theOnlySkippedFileIsThisOne() {
        final List<Path> walked = new ArrayList<>();
        for (final Path root : List.of(PRODUCTION_SOURCE_ROOT, TEST_SOURCE_ROOT)) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .forEach(walked::add);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException("Could not read " + root, unreadable);
            }
        }
        final List<Path> audited = allSources().stream().map(SourceFile::path).toList();

        assertThat(SELF).as("the skipped path must name a file that exists").isRegularFile();
        assertThat(walked).as("and the walk must have found it").contains(SELF);
        assertThat(walked.size() - audited.size())
                .as("exactly one file is exempt from these rules, and it is the file that states them")
                .isEqualTo(1);
        assertThat(audited).as("every other source in the module is audited").doesNotContain(SELF);
    }

    @Test
    @DisplayName("both source trees are read, so every absence asserted below is an absence over real files")
    void bothTreesAreRead() {
        assertThat(read(PRODUCTION_SOURCE_ROOT))
                .as("the production tree must be read for the rules below to be non-vacuous")
                .hasSizeGreaterThan(MINIMUM_PRODUCTION_SOURCES);
        assertThat(read(TEST_SOURCE_ROOT))
                .as("the test tree is held to the same rule, because it builds the fixtures")
                .hasSizeGreaterThan(MINIMUM_TEST_SOURCES);
    }

    @Nested
    @DisplayName("the shorthand that cannot be given a locale")
    class TheShorthand {

        @Test
        @DisplayName("String.formatted appears nowhere, because it resolves the ambient default and offers "
                + "no way to say otherwise")
        void theShorthandIsAbsent() {
            // // The defect, generalised. "USER%04d".formatted(index) renders
            // USER٠٠٠١ under ar-EG. The rule is an absence rather than a per-call-site judgement so that
            // it cannot be widened by an edit to this file.
            assertThat(occurrencesOf(".formatted("))
                    .as("use String.format(Locale.ROOT, …): the shorthand has no locale overload")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the calls that accept a locale must be given one")
    class TheCallsThatAcceptALocale {

        @Test
        @DisplayName("every String.format names a locale in its first argument")
        void everyFormatNamesALocale() {
            final List<String> unlocalised = new ArrayList<>();
            for (final SourceFile source : allSources()) {
                for (final CodeLine line : source.codeLines()) {
                    int from = line.text().indexOf("String.format(");
                    while (from >= 0) {
                        final String arguments = argumentsOf(line.text(), from, "String.format(");
                        final String first = arguments.split(",", 2)[0];
                        if (!first.contains("Locale")) {
                            unlocalised.add(source.path() + ":" + line.number() + " -> "
                                    + line.text().strip());
                        }
                        from = line.text().indexOf("String.format(", from + 1);
                    }
                }
            }

            assertThat(unlocalised)
                    .as("a format without a locale takes its digits, and its case, from the ambient default")
                    .isEmpty();
        }

        @Test
        @DisplayName("every DateTimeFormatter pattern is built with a locale")
        void everyPatternNamesALocale() {
            // ofPattern's single-argument overload uses the ambient FORMAT locale, which decides month and
            // day names and, for some locales, the digits of a numeric field.
            final List<String> unlocalised = new ArrayList<>();
            for (final SourceFile source : allSources()) {
                for (final CodeLine line : source.codeLines()) {
                    int from = line.text().indexOf("ofPattern(");
                    while (from >= 0) {
                        final String arguments = argumentsOf(line.text(), from, "ofPattern(");
                        if (!arguments.isBlank() && !arguments.contains(",")) {
                            unlocalised.add(source.path() + ":" + line.number() + " -> "
                                    + line.text().strip());
                        }
                        from = line.text().indexOf("ofPattern(", from + 1);
                    }
                }
            }

            assertThat(unlocalised)
                    .as("ofPattern must be given a locale, not left to resolve the ambient default")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the constructs with no acceptable form here")
    class TheConstructsWithNoAcceptableForm {

        @Test
        @DisplayName("no case fold is left to the locale, because the Turkish dotless i would change a byte")
        void noLocaleSensitiveCaseFold() {
            // The estate folds through a 26-character ASCII table. String.toUpperCase() under tr-TR maps i
            // to a dotless capital, which is a different byte in a fixed-width field.
            assertThat(occurrencesOf(".toUpperCase()"))
                    .as("use CobolStringUtils.asciiUpperFold, or name a locale explicitly")
                    .isEmpty();
            assertThat(occurrencesOf(".toLowerCase()"))
                    .as("a no-argument fold resolves the ambient default locale")
                    .isEmpty();
        }

        @Test
        @DisplayName("no locale-sensitive number or date formatter is constructed")
        void noLocaleSensitiveFormatterIsConstructed() {
            // Decision D-27 forbids these in the amount masks; the module has never needed one anywhere,
            // and the rule is asserted over both trees so a fixture cannot introduce the first.
            assertThat(occurrencesOf("new DecimalFormat"))
                    .as("DecimalFormat renders digits from the ambient locale's numbering system")
                    .isEmpty();
            assertThat(occurrencesOf("new SimpleDateFormat"))
                    .as("SimpleDateFormat resolves the ambient locale and the ambient time zone")
                    .isEmpty();
            assertThat(occurrencesOf("NumberFormat.get"))
                    .as("every NumberFormat factory resolves a locale")
                    .isEmpty();
        }
    }
}
