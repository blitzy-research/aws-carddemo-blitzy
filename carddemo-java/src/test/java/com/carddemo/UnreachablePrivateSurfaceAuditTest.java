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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forbids, by reading every production source file in the module, a private method that nothing in its
 * own file reaches.
 *
 * <p><strong>Why this file exists.</strong> A private method is visible to one file, so that one file is
 * the whole of its reachability question and the question is answerable by reading. An unreached one
 * cannot fail, and that is precisely the problem: unreachable code cannot be wrong about behaviour, only
 * about intent, so it costs documentation rather than bytes. A maintainer has no way to tell an
 * intentionally general helper from a withdrawn one except by finding the caller, and a javadoc on an
 * uncalled method goes on describing a design the rest of the file has left. This audit removes the
 * question.
 *
 * <p><strong>Why the rule is a rule rather than a review habit.</strong> Nothing else in the module asks
 * it. {@code javac} has no lint for an unused private method, not even under {@code -Xlint:all}; the
 * coverage floor is satisfied by the other ninety-nine percent of a large class; and a reviewer reading a
 * four-thousand-line service does not hold every private name in mind. A file-scoped reachability check
 * does, at unit-tier cost, with no container.
 *
 * <p><strong>The check is deliberately sound rather than complete.</strong> It counts a name as reached
 * if the file calls it or references it as a method reference, and it does not compare argument counts.
 * That under-reports: a dead overload of a live name is invisible to it, which is exactly how the
 * date-range identity helper hid behind its own no-argument sibling. Comparing arities would require
 * resolving each call's argument list through generics, lambdas and nested calls, and a mistake there
 * reports a live method as dead - a failure that stops a correct change and teaches a reader to distrust
 * the audit. Under-reporting only lets a rarer case through, so the rule is written to have no false
 * positives at all and the arity case is left to review, which is where it was found.
 *
 * <p><strong>Three constructs are read as reached, each for a reason inherent to it.</strong> A private
 * constructor is never counted, because the standard way to say a class is not instantiable is to declare
 * one and call it from nowhere. A private method carrying any annotation is counted as reached, because an
 * annotation on a private method is a declaration that something outside this file may invoke it
 * reflectively - a persistence callback, a lifecycle hook - and the module's entities do exactly that,
 * one visibility level up. And a name that occurs only in prose is not counted: a {@code @link} in a
 * javadoc documents a method, it does not call it, so a method whose only mention is documentation is
 * dead and this audit says so.
 *
 * <p><strong>The analyser is itself under test.</strong> Every rule below runs against the real tree, and
 * the nested class beneath them runs the same analyser against fabricated sources that contain a dead
 * method, a method reached only through a method reference, a method named only in a comment, an
 * annotated method and a private constructor. An audit that reports nothing is indistinguishable from an
 * audit that examines nothing, and the module has no other way to tell those apart.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. It reads files from the module
 * directory the build runs tests from, and asserts a floor on what it read before asserting an absence,
 * because an absence assertion over an empty walk is vacuous.
 *
 * <p>Decision log DL-314 records the three methods, the two documentation paragraphs they anchored, and
 * why the surface they belonged to is documented rather than reduced.
 *
 * @since 1.0.0
 */
@DisplayName("unreachable private surface :: every private method is reached from its own file")
final class UnreachablePrivateSurfaceAuditTest {

    /** The production source tree, relative to the module directory the build runs tests from. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * A floor on the number of production sources, so an absence assertion cannot pass over an empty walk.
     * Deliberately far below the real count, which grows.
     */
    private static final int MINIMUM_PRODUCTION_SOURCES = 200;

    /**
     * A floor on the number of private methods examined, for the same reason: a walk that found the files
     * but matched no declaration would also report no violation.
     */
    private static final int MINIMUM_PRIVATE_METHODS = 1500;

    /**
     * A private method declaration on one line: the modifier, an optional {@code static} and
     * {@code final}, a return type that may carry generics, an array or a qualified name, then the method
     * name and its opening parenthesis.
     *
     * <p>A private <em>constructor</em> does not match, and cannot: it has no return type, so there is no
     * second token before the name. That exclusion is required rather than convenient - a non-instantiable
     * class declares a private constructor precisely so that nothing calls it.
     */
    private static final Pattern PRIVATE_METHOD = Pattern.compile(
            "^\\s*private\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>,\\[\\]\\.\\s?]+?\\s+(\\w+)\\s*\\(");

    /** One private method declaration: its name, the one-based line it is declared on, and whether it
     * carries an annotation that lets something outside the file reach it. */
    private record Declaration(String name, int line, boolean annotated) { }

    /** One file's verdict: the path, how many private methods it declares, and the unreached ones. */
    private record FileVerdict(Path path, int declared, List<String> unreached) { }

    /**
     * Replaces every comment body and every string, character and text-block literal with spaces, leaving
     * code and line structure in place.
     *
     * <p>Written as a state machine over characters rather than as a regular expression because the naive
     * expression is wrong on this very module: a route pattern such as {@code "/api/**"} contains the two
     * characters that open a block comment, so a stripper that removes comments before literals treats the
     * rest of the file up to the next {@code *}{@code /} as a comment and hides whatever is in between.
     * Every replacement preserves length and newlines so that a reported line number is the real one.
     *
     * @param source the source text
     * @return the same text with literals and comments blanked
     */
    private static String codeOnly(final String source) {
        final StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            final char character = source.charAt(index);
            if (character == '/' && next(source, index) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    code.append(' ');
                    index++;
                }
            } else if (character == '/' && next(source, index) == '*') {
                code.append("  ");
                index += 2;
                while (index < source.length()
                        && !(source.charAt(index) == '*' && next(source, index) == '/')) {
                    code.append(source.charAt(index) == '\n' ? '\n' : ' ');
                    index++;
                }
                if (index < source.length()) {
                    code.append("  ");
                    index += 2;
                }
            } else if (source.startsWith("\"\"\"", index)) {
                code.append("   ");
                index += 3;
                while (index < source.length() && !source.startsWith("\"\"\"", index)) {
                    code.append(source.charAt(index) == '\n' ? '\n' : ' ');
                    index++;
                }
                if (index < source.length()) {
                    code.append("   ");
                    index += 3;
                }
            } else if (character == '"' || character == '\'') {
                code.append(' ');
                index++;
                while (index < source.length() && source.charAt(index) != character) {
                    if (source.charAt(index) == '\\') {
                        code.append(' ');
                        index++;
                    }
                    if (index < source.length()) {
                        code.append(source.charAt(index) == '\n' ? '\n' : ' ');
                        index++;
                    }
                }
                if (index < source.length()) {
                    code.append(' ');
                    index++;
                }
            } else {
                code.append(character);
                index++;
            }
        }
        return code.toString();
    }

    /**
     * The character after the given index, or a space when there is none.
     *
     * @param source the source text
     * @param index the current index
     * @return the following character
     */
    private static char next(final String source, final int index) {
        return index + 1 < source.length() ? source.charAt(index + 1) : ' ';
    }

    /**
     * Names every private method the file declares that the same file neither calls nor references.
     *
     * @param source the source text of one file
     * @return one {@code name:line} entry per unreached declaration, in declaration order
     */
    private static List<String> unreachedPrivateMethodsIn(final String source) {
        final List<String> unreached = new ArrayList<>();
        final String code = codeOnly(source);
        final List<Declaration> declared = privateMethodsIn(source, code);
        for (final Declaration declaration : declared) {
            if (declaration.annotated()) {
                continue;
            }
            final String name = Pattern.quote(declaration.name());
            final long calls = Pattern.compile("\\b" + name + "\\s*\\(").matcher(code).results().count();
            final long references = Pattern.compile("::\\s*" + name + "\\b").matcher(code)
                    .results().count();
            final long declarations = declared.stream()
                    .filter(other -> other.name().equals(declaration.name()))
                    .count();
            if (references == 0 && calls <= declarations) {
                unreached.add(declaration.name() + ":" + declaration.line());
            }
        }
        return List.copyOf(unreached);
    }

    /**
     * Every private method declaration in the file, paired with whether an annotation precedes it.
     *
     * @param source the source text, read for the annotation that may precede a declaration
     * @param code the same text with literals and comments blanked, read for the declaration itself
     * @return the declarations, in declaration order
     */
    private static List<Declaration> privateMethodsIn(final String source, final String code) {
        final List<Declaration> declarations = new ArrayList<>();
        final String[] codeLines = code.split("\n", -1);
        final String[] sourceLines = source.split("\n", -1);
        for (int index = 0; index < codeLines.length; index++) {
            final Matcher matcher = PRIVATE_METHOD.matcher(codeLines[index]);
            if (matcher.find()) {
                declarations.add(new Declaration(matcher.group(1), index + 1,
                        isAnnotated(sourceLines, index)));
            }
        }
        return List.copyOf(declarations);
    }

    /**
     * Reports whether the declaration on the given line carries an annotation.
     *
     * <p>Walks back over blank lines and over lines that begin a comment, and answers on the first line
     * that is neither. An annotation on a private method is read as a declaration that something outside
     * this file may reach it reflectively, which is what a persistence or lifecycle callback is.
     *
     * @param lines the file's lines
     * @param declarationIndex the zero-based index of the declaration line
     * @return {@code true} when an annotation immediately precedes the declaration
     */
    private static boolean isAnnotated(final String[] lines, final int declarationIndex) {
        for (int index = declarationIndex - 1; index >= 0; index--) {
            final String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("//")
                    || line.startsWith("/*")) {
                continue;
            }
            return line.startsWith("@");
        }
        return false;
    }

    /**
     * Reads the production tree and analyses every file in it.
     *
     * @return one verdict per source file, in walk order
     */
    private static List<FileVerdict> productionVerdicts() {
        assertThat(PRODUCTION_SOURCE_ROOT)
                .as("the production source tree must be present for this audit to mean anything")
                .isDirectory();
        final List<FileVerdict> verdicts = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> {
                        final String source = read(path);
                        verdicts.add(new FileVerdict(path,
                                privateMethodsIn(source, codeOnly(source)).size(),
                                unreachedPrivateMethodsIn(source)));
                    });
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Could not read " + PRODUCTION_SOURCE_ROOT, unreadable);
        }
        return List.copyOf(verdicts);
    }

    /**
     * Reads one file.
     *
     * @param path the file to read
     * @return its text
     */
    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Could not read " + path, unreadable);
        }
    }

    @Test
    @DisplayName("no production file declares a private method that nothing in that file reaches")
    void noProductionFileDeclaresAnUnreachedPrivateMethod() {
        final List<String> violations = new ArrayList<>();
        for (final FileVerdict verdict : productionVerdicts()) {
            for (final String unreached : verdict.unreached()) {
                violations.add(verdict.path() + ":" + unreached);
            }
        }

        assertThat(violations)
                .as("a private method is visible to one file, so a file that does not reach it is the "
                        + "whole of the evidence: delete it, or make the caller that needs it. Reported "
                        + "as path:name:line")
                .isEmpty();
    }

    @Test
    @DisplayName("the audit read the whole production tree, so its silence means something")
    void theAuditReadTheWholeProductionTree() {
        final List<FileVerdict> verdicts = productionVerdicts();
        final int declared = verdicts.stream().mapToInt(FileVerdict::declared).sum();

        assertThat(verdicts)
                .as("the production tree the audit walked")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_PRODUCTION_SOURCES);
        assertThat(declared)
                .as("the private method declarations the audit examined")
                .isGreaterThanOrEqualTo(MINIMUM_PRIVATE_METHODS);
    }

    @Test
    @DisplayName("the two methods this audit was written for would be reported today")
    void theTwoMethodsThisAuditWasWrittenForWouldBeReported() {
        // Reconstructed shapes, not the removed text: what matters is that each is the pattern the
        // delivered module carried - a helper reached by nothing, and a dead overload of a live name -
        // and that the first is reported while the second is the case this audit deliberately leaves to
        // review rather than claiming to catch.
        final String materialisingHelper = """
                final class Bridge {
                    private static List<String> deduplicationIds(String id, int count) {
                        return List.of(id);
                    }

                    private static void validate(String id, int count) {
                        deduplicationId(id, count);
                    }

                    void publish(String id, int count) {
                        validate(id, count);
                    }
                }
                """;
        final String deadOverload = """
                final class Bridge {
                    private static String newSubmissionId(String from, String to) {
                        return from + to;
                    }

                    public static String newSubmissionId() {
                        return "x";
                    }

                    void use() {
                        newSubmissionId();
                    }
                }
                """;

        assertThat(unreachedPrivateMethodsIn(materialisingHelper))
                .as("the helper nothing calls")
                .containsExactly("deduplicationIds:2");
        assertThat(unreachedPrivateMethodsIn(deadOverload))
                .as("a dead overload hides behind its live sibling: this audit is sound, not complete, "
                        + "and says so rather than resolving argument lists")
                .isEmpty();
    }

    @Nested
    @DisplayName("the analyser itself, against sources written to exercise it")
    class TheAnalyser {

        @Test
        @DisplayName("reports a private method nothing calls")
        void reportsAPrivateMethodNothingCalls() {
            final String source = """
                    final class Sample {
                        private int unused() {
                            return 1;
                        }
                    }
                    """;

            assertThat(unreachedPrivateMethodsIn(source)).containsExactly("unused:2");
        }

        @Test
        @DisplayName("does not report a private method reached only through a method reference")
        void doesNotReportAMethodReachedOnlyByAMethodReference() {
            final String source = """
                    final class Sample {
                        void run() {
                            list.forEach(this::handle);
                        }

                        private void handle(String value) {
                        }
                    }
                    """;

            assertThat(unreachedPrivateMethodsIn(source)).isEmpty();
        }

        @Test
        @DisplayName("reports a private method whose only mention is documentation, because prose is not "
                + "a call")
        void reportsAMethodMentionedOnlyInProse() {
            final String source = """
                    final class Sample {
                        /**
                         * See {@link #orphan()} for the rule, and orphan() for the shape.
                         */
                        void run() {
                        }

                        private void orphan() {
                        }
                    }
                    """;

            assertThat(unreachedPrivateMethodsIn(source)).containsExactly("orphan:8");
        }

        @Test
        @DisplayName("does not report an annotated private method, which a framework may reach "
                + "reflectively")
        void doesNotReportAnAnnotatedPrivateMethod() {
            final String source = """
                    final class Sample {
                        /**
                         * Normalises before the row is written.
                         */
                        @PrePersist
                        @PreUpdate
                        private void normalize() {
                        }
                    }
                    """;

            assertThat(unreachedPrivateMethodsIn(source)).isEmpty();
        }

        @Test
        @DisplayName("does not report a private constructor, which a non-instantiable class declares so "
                + "that nothing calls it")
        void doesNotReportAPrivateConstructor() {
            final String source = """
                    final class Utility {
                        private Utility() {
                        }
                    }
                    """;

            assertThat(unreachedPrivateMethodsIn(source)).isEmpty();
        }

        @Test
        @DisplayName("is not fooled by a route pattern that opens a block comment")
        void isNotFooledByARoutePatternThatOpensABlockComment() {
            // The naive stripper removes comments before literals, so "/api/**" begins a comment that
            // runs to the next close and swallows the call below it. This is the real shape of the
            // module's security configuration, and it is why codeOnly is a state machine.
            final String source = """
                    final class Routes {
                        void configure() {
                            registry.requestMatchers("/api/**").permitAll();
                            reached();
                        }

                        /** Documented. */
                        private void reached() {
                        }
                    }
                    """;

            assertThat(unreachedPrivateMethodsIn(source)).isEmpty();
        }

        @Test
        @DisplayName("is not fooled by a name that appears inside a string literal")
        void isNotFooledByANameInsideAStringLiteral() {
            final String source = """
                    final class Sample {
                        void run() {
                            log("orphan() was removed");
                        }

                        private void orphan() {
                        }
                    }
                    """;

            assertThat(unreachedPrivateMethodsIn(source)).containsExactly("orphan:6");
        }
    }
}
