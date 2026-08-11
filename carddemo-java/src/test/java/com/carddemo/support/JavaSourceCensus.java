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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the module's own Java sources and answers four questions about any one of them: does it declare a
 * type, does it declare something a test runner can execute, is it an abstract base that contributes its
 * tests to subclasses, and is it named so that a runner looks inside it at all.
 *
 * <h2>Why one owner for these detectors</h2>
 *
 * <p>Two separate suites need them and need them to agree. The source census asserts that no compilation
 * unit in either tree is blank or typeless and that every name a runner matches has something behind it.
 * The gate audit asserts the same properties of the covering test each traceability row names, because a
 * row whose covering test is an empty file records coverage that does not exist. Two copies of a detector
 * drift apart and the looser copy decides the outcome; this class is the single copy, and the census's own
 * soundness tests are what hold it honest.
 *
 * <h2>Read as text, never loaded</h2>
 *
 * <p>Every question is answered by reading the source rather than by loading the type. The production tree
 * is held to a reflection count of zero, and an instrument that audits that constraint has no business
 * relying on the mechanism it forbids. Reading text also means a source that does not compile still gets a
 * useful answer, which is exactly the state a blank or truncated file is in.
 *
 * <h2>Comments and literals are removed first, by one scanner</h2>
 *
 * <p>Every detector runs over {@link #declarationsOf(String)} output, in which comments, string literals,
 * text blocks and character literals have been blanked. Without that step a suite that legitimately names
 * {@code "public interface Foo"} in an assertion would read as declaring a type, and a class discussing
 * {@code @Test} in prose would read as declaring one.
 *
 * <p>The blanking is done by {@link #codeOnly(String)}, a single left-to-right scan, rather than by a chain
 * of regular expressions. A chain of patterns has two defects a scan cannot have. It matches a
 * string literal with a pattern that forbids an embedded newline, so the body of a text block is never
 * blanked at all - and this module writes 354 text-block delimiters, every JPQL query among them. And it
 * applies its patterns in sequence, so a {@code //} inside a literal blanks the rest of a real line while
 * a quote inside a comment can open a literal that was never there. A single scan decides what each
 * character is once, in the order the compiler reads it, and cannot disagree with itself.
 *
 * <p>Blanking preserves length and line structure: every removed character becomes a space and every
 * newline survives. That is what makes {@link #codeOnlyLinesOf(Path)} line-addressable, so an audit can
 * report the file and line of what it found rather than only a count - and can tell a real annotation from
 * a mention of one in prose, which is the difference between an audit and a grep.
 *
 * <p>The line-addressable view exists for the whole-source warning-suppression audit recorded as
 * {@code DL-317} in {@code docs/decision-log.md}, which needs to name the file and line of a finding and to
 * separate an annotation the compiler reads from a mention of one in prose.
 */
public final class JavaSourceCensus {

    /** The production source tree, module-relative. */
    public static final String PRODUCTION_TREE = "src/main/java";

    /** The test source tree, module-relative. */
    public static final String TEST_TREE = "src/test/java";

    /**
     * A top-level or nested type declaration.
     *
     * <p>{@code class}, {@code interface} and {@code enum} are reserved words and cannot name anything
     * else. {@code record} is a contextual keyword and is used as an ordinary identifier throughout this
     * module, so it is accepted only when an identifier and an argument list follow it, which is the one
     * position an assignment, an enhanced {@code for} header and a lambda parameter can never occupy.
     */
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?:\\b(?:class|interface|enum)\\s+\\w|\\brecord\\s+\\w+\\s*\\(|@interface\\s+\\w)");

    /** An abstract class declaration, which contributes its tests to subclasses rather than running. */
    private static final Pattern ABSTRACT_CLASS = Pattern.compile("\\babstract\\s+class\\s+\\w");

    /**
     * Anything a JUnit runner can execute.
     *
     * <p>Every form the platform discovers as an executable node is listed, so a class that carries only
     * parameterised cases or only a dynamic factory is not reported as empty.
     */
    private static final Pattern EXECUTABLE_TEST = Pattern.compile(
            "@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    /** Not instantiable: this class is a set of static readers over the source trees. */
    private JavaSourceCensus() {
        throw new AssertionError("JavaSourceCensus is a static reader and is never instantiated");
    }

    /**
     * Reads one source with its comments and string literals removed.
     *
     * @param  source the file to read
     * @return its text, with comments and literals blanked out
     */
    public static String declarationsOf(final Path source) {
        return codeOnly(readText(source));
    }

    /**
     * Blanks the comments and literals in one source's text.
     *
     * @param  text the source text
     * @return the same text with every comment, string literal, text block and character literal replaced
     *         by spaces of equal length, newlines retained
     */
    public static String declarationsOf(final String text) {
        return codeOnly(text);
    }

    /**
     * Reads one source as code-only lines, in file order.
     *
     * <p>Line <em>n</em> of the result is line <em>n</em> of the file with its comments and literals
     * blanked, so an index into this list is a line number an engineer can open. That is what lets an
     * absence audit name the site of what it found, and what lets it separate a construct the compiler
     * sees from the same characters written in a comment or asserted on as a literal.
     *
     * @param  source the file to read
     * @return its lines, comments and literals blanked, no separator retained
     */
    public static List<String> codeOnlyLinesOf(final Path source) {
        return List.of(codeOnly(readText(source)).split("\n", -1));
    }

    /**
     * Blanks everything the compiler does not read as code, preserving length and line structure.
     *
     * <p>One left-to-right pass. At each position the scanner decides which of six things it is looking at
     * - a line comment, a block comment, a text block, a string literal, a character literal, or code - and
     * consumes the whole construct before deciding again. Escape sequences are consumed as a pair so that a
     * {@code \\"} cannot be mistaken for the end of a literal, and an unterminated construct consumes to the
     * end of its line or of the file rather than looping.
     *
     * <p>Every blanked character becomes a space and every newline is kept, so offsets and line numbers in
     * the result address the same characters they address in the source.
     *
     * @param  text the source text
     * @return the same text with only its code retained
     */
    public static String codeOnly(final String text) {
        final char[] in = Objects.requireNonNull(text, "text must not be null").toCharArray();
        final char[] out = new char[in.length];
        int at = 0;
        while (at < in.length) {
            if (startsWith(in, at, "//")) {
                at = blankToEndOfLine(in, out, at);
            } else if (startsWith(in, at, "/*")) {
                at = blankBlockComment(in, out, at);
            } else if (startsWith(in, at, "\"\"\"")) {
                at = blankTextBlock(in, out, at);
            } else if (in[at] == '"' || in[at] == '\'') {
                at = blankQuotedLiteral(in, out, at, in[at]);
            } else {
                out[at] = in[at];
                at++;
            }
        }
        return new String(out);
    }

    /**
     * Reports whether one marker begins at a position.
     *
     * @param  in     the characters being scanned
     * @param  at     the position to test
     * @param  marker the marker to look for
     * @return {@code true} when the marker begins at that position
     */
    private static boolean startsWith(final char[] in, final int at, final String marker) {
        if (at + marker.length() > in.length) {
            return false;
        }
        for (int offset = 0; offset < marker.length(); offset++) {
            if (in[at + offset] != marker.charAt(offset)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Blanks a line comment, stopping before the newline that ends it.
     *
     * @param  in  the characters being scanned
     * @param  out the characters being written
     * @param  at  the position of the opening slash
     * @return the position after the comment
     */
    private static int blankToEndOfLine(final char[] in, final char[] out, final int at) {
        int cursor = at;
        while (cursor < in.length && in[cursor] != '\n') {
            out[cursor] = ' ';
            cursor++;
        }
        return cursor;
    }

    /**
     * Blanks a block comment, keeping the newlines inside it.
     *
     * @param  in  the characters being scanned
     * @param  out the characters being written
     * @param  at  the position of the opening slash
     * @return the position after the comment, or the end of the file if it is unterminated
     */
    private static int blankBlockComment(final char[] in, final char[] out, final int at) {
        out[at] = ' ';
        out[at + 1] = ' ';
        int cursor = at + 2;
        while (cursor < in.length) {
            if (startsWith(in, cursor, "*/")) {
                out[cursor] = ' ';
                out[cursor + 1] = ' ';
                return cursor + 2;
            }
            out[cursor] = in[cursor] == '\n' ? '\n' : ' ';
            cursor++;
        }
        return cursor;
    }

    /**
     * Blanks a text block, keeping the newlines inside it.
     *
     * @param  in  the characters being scanned
     * @param  out the characters being written
     * @param  at  the position of the opening delimiter
     * @return the position after the closing delimiter, or the end of the file if it is unterminated
     */
    private static int blankTextBlock(final char[] in, final char[] out, final int at) {
        out[at] = ' ';
        out[at + 1] = ' ';
        out[at + 2] = ' ';
        int cursor = at + 3;
        while (cursor < in.length) {
            if (in[cursor] == '\\' && cursor + 1 < in.length) {
                out[cursor] = ' ';
                out[cursor + 1] = in[cursor + 1] == '\n' ? '\n' : ' ';
                cursor += 2;
                continue;
            }
            if (startsWith(in, cursor, "\"\"\"")) {
                out[cursor] = ' ';
                out[cursor + 1] = ' ';
                out[cursor + 2] = ' ';
                return cursor + 3;
            }
            out[cursor] = in[cursor] == '\n' ? '\n' : ' ';
            cursor++;
        }
        return cursor;
    }

    /**
     * Blanks a string or character literal, which cannot span a line.
     *
     * @param  in    the characters being scanned
     * @param  out   the characters being written
     * @param  at    the position of the opening quote
     * @param  quote the quote character that closes it
     * @return the position after the closing quote, or the end of the line if it is unterminated
     */
    private static int blankQuotedLiteral(final char[] in, final char[] out, final int at,
            final char quote) {
        out[at] = ' ';
        int cursor = at + 1;
        while (cursor < in.length && in[cursor] != quote && in[cursor] != '\n') {
            if (in[cursor] == '\\' && cursor + 1 < in.length && in[cursor + 1] != '\n') {
                out[cursor] = ' ';
                out[cursor + 1] = ' ';
                cursor += 2;
                continue;
            }
            out[cursor] = ' ';
            cursor++;
        }
        if (cursor < in.length && in[cursor] == quote) {
            out[cursor] = ' ';
            cursor++;
        }
        return cursor;
    }

    /**
     * Reads one source, failing loudly rather than returning an empty answer.
     *
     * @param  source the file to read
     * @return its text
     */
    private static String readText(final Path source) {
        Objects.requireNonNull(source, "source must not be null");
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(source.toAbsolutePath() + " could not be read", unreadable);
        }
    }

    /**
     * Reports whether a source declares a type of any kind.
     *
     * <p>A source that declares none emits no class file, which is how a package-only file escapes every
     * rule the build states about classes.
     *
     * @param  declarations the output of {@link #declarationsOf(Path)}
     * @return {@code true} when a class, interface, enum, record or annotation type is declared
     */
    public static boolean declaresAType(final String declarations) {
        return TYPE_DECLARATION.matcher(Objects.requireNonNull(declarations)).find();
    }

    /**
     * Reports whether a source declares something a JUnit runner will execute.
     *
     * @param  declarations the output of {@link #declarationsOf(Path)}
     * @return {@code true} when an executable test node is declared
     */
    public static boolean declaresAnExecutableTest(final String declarations) {
        return EXECUTABLE_TEST.matcher(Objects.requireNonNull(declarations)).find();
    }

    /**
     * Reports whether a source declares an abstract class.
     *
     * @param  declarations the output of {@link #declarationsOf(Path)}
     * @return {@code true} when an abstract class is declared
     */
    public static boolean declaresAnAbstractClass(final String declarations) {
        return ABSTRACT_CLASS.matcher(Objects.requireNonNull(declarations)).find();
    }

    /**
     * Reports whether a source's name is one a test runner discovers.
     *
     * <p>Read from the two runners' own include patterns: the unit runner takes {@code **&#47;*Test.java}
     * and the integration runner takes {@code **&#47;*IT.java} together with the end-to-end names. Their
     * union is every source whose name ends in {@code Test} or {@code IT}.
     *
     * @param  source the source to classify
     * @return {@code true} when a runner will look inside it for tests
     */
    public static boolean isDiscoveredByARunner(final Path source) {
        final String name = Objects.requireNonNull(source, "source must not be null")
                .getFileName().toString();
        return name.endsWith("Test.java") || name.endsWith("IT.java");
    }

    /**
     * Lists every Java source beneath one module-relative tree.
     *
     * @param  tree the tree to walk, module-relative
     * @return its Java sources, in a stable order
     * @throws IOException           if the tree cannot be walked
     * @throws IllegalStateException if the tree is not a directory, because an absent tree would silently
     *                               make every absence assertion over it vacuous
     */
    public static List<Path> sourcesUnder(final String tree) throws IOException {
        final Path root = Path.of(Objects.requireNonNull(tree, "tree must not be null"));
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("no source tree at " + root.toAbsolutePath()
                    + "; the build's working directory is the module, so this path is module-relative");
        }
        try (Stream<Path> walked = Files.walk(root)) {
            return walked.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }
}
