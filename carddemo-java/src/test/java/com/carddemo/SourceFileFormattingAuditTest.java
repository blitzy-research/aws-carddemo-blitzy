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

/**
 * Holds every Java source file in the module to one line-ending convention, by reading the bytes.
 *
 * <p><strong>Why this file exists.</strong> A source whose last line carries no terminator, and a source
 * that is a licence header and a package statement with no type at all, are both invisible to the
 * compiler: a class file is produced either way, so a zero-warning build says nothing about them. They are
 * not invisible to everything else. A file without a final newline makes every later diff of it re-state
 * its last line, makes {@code cat} of two files run one into the other, and is malformed under POSIX's
 * definition of a text line; a file with no type declares nothing and reads, to anyone who opens it, as a
 * contract that was meant to arrive and did not.
 *
 * <p><strong>Why a test rather than a build plugin.</strong> The module's build is pinned artefact by
 * artefact and compiles under {@code -Xlint:all -Werror}; adding a formatter or a style plugin would extend
 * the dependency graph the vulnerability gate scans and would put the rule in a place the test tier cannot
 * see. Reading the bytes costs one unit test, runs in the same tier as every other invariant this module
 * pins by reading its own sources, and names the offending path when it fails.
 *
 * <p><strong>What is deliberately out of scope.</strong> Only {@code .java} files are examined. The fixture
 * resources under {@code src/test/resources/fixtures} are byte-exact goldens: a statement record is eighty
 * bytes, an HTML statement record is one hundred, a report line is one hundred and thirty-three and a
 * reject record is four hundred and thirty, with no separator of any kind between records. Appending a
 * newline to one of those would not tidy it, it would break the comparison it exists to serve, so this
 * audit must not reach them. {@code mvnw.cmd} keeps its carriage returns for the same reason in reverse: a
 * Windows command interpreter requires them.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. It reads files from the module
 * directory the build runs tests from and asserts a floor on the number of files read first, because an
 * absence assertion over an empty walk is vacuous.
 *
 * @since 1.0.0
 */
@DisplayName("source formatting :: every Java source in the module ends the way a text file ends")
final class SourceFileFormattingAuditTest {

    /** The production source tree, relative to the module directory the build runs tests from. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /** The test source tree, held to the same rule because a reader opens both. */
    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");

    /**
     * A floor on the number of production sources, so an absence assertion cannot pass over an empty walk.
     * Deliberately far below the real count, which grows.
     */
    private static final int MINIMUM_PRODUCTION_SOURCES = 200;

    /** The same floor for the test tree. */
    private static final int MINIMUM_TEST_SOURCES = 200;

    /** The single line terminator every source in this module uses. */
    private static final String LINE_FEED = "\n";

    /** The carriage return that must not appear, because a mixed tree makes every diff report noise. */
    private static final String CARRIAGE_RETURN = "\r";

    /** One source file, paired with the raw bytes read from it. */
    private record SourceFile(Path path, byte[] content) { }

    /**
     * Reads every {@code .java} file beneath one root.
     *
     * <p>The bytes are read rather than the characters, because the question is about terminators and a
     * character reader is permitted to normalise them.
     *
     * @param  root the tree to walk
     * @return every Java source beneath the root, in walk order
     */
    private static List<SourceFile> sourcesBeneath(final Path root) {
        final List<SourceFile> sources = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root)) {
            for (final Path candidate : tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList()) {
                sources.add(new SourceFile(candidate, Files.readAllBytes(candidate)));
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the module's own source tree must be readable", unreadable);
        }
        return List.copyOf(sources);
    }

    /**
     * Names every source that does not end with exactly one line feed.
     *
     * @param  sources the sources to examine
     * @return the offending paths, as strings a reader can open
     */
    private static List<String> notEndingWithOneLineFeed(final List<SourceFile> sources) {
        final List<String> offenders = new ArrayList<>();
        for (final SourceFile source : sources) {
            final byte[] content = source.content();
            if (content.length == 0) {
                offenders.add(source.path() + " (empty: a compilation unit that declares nothing)");
                continue;
            }
            final int last = content.length - 1;
            if (content[last] != '\n') {
                offenders.add(source.path() + " (no final line feed)");
            } else if (last > 0 && content[last - 1] == '\n') {
                offenders.add(source.path() + " (trailing blank line)");
            }
        }
        return List.copyOf(offenders);
    }

    @Nested
    @DisplayName("The production tree")
    class ProductionTree {

        @Test
        @DisplayName("ends every source with exactly one line feed, so a later diff of a file restates "
                + "only what changed in it")
        void endsEverySourceWithOneLineFeed() {
            final List<SourceFile> sources = sourcesBeneath(PRODUCTION_SOURCE_ROOT);

            assertThat(sources)
                    .as("the walk must have found the production tree, or the absence assertion below "
                            + "would pass over nothing")
                    .hasSizeGreaterThan(MINIMUM_PRODUCTION_SOURCES);
            assertThat(notEndingWithOneLineFeed(sources))
                    .as("a source that does not end with one line feed")
                    .isEmpty();
        }

        @Test
        @DisplayName("uses the line feed alone, so no source carries a carriage return")
        void usesTheLineFeedAlone() {
            final List<String> offenders = new ArrayList<>();
            for (final SourceFile source : sourcesBeneath(PRODUCTION_SOURCE_ROOT)) {
                if (new String(source.content(), StandardCharsets.UTF_8).contains(CARRIAGE_RETURN)) {
                    offenders.add(source.path().toString());
                }
            }

            assertThat(offenders)
                    .as("a source carrying %s rather than %s alone", "CR", "LF")
                    .isEmpty();
        }

        @Test
        @DisplayName("declares a type in every compilation unit, so no file is a licence header and a "
                + "package statement standing in for a contract")
        void declaresATypeInEveryCompilationUnit() {
            final List<String> typeless = new ArrayList<>();
            for (final SourceFile source : sourcesBeneath(PRODUCTION_SOURCE_ROOT)) {
                if (!declaresAType(source)) {
                    typeless.add(source.path().toString());
                }
            }

            assertThat(typeless)
                    .as("a compilation unit that declares no class, interface, enum, record or "
                            + "annotation is a placeholder, whatever its header says")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The test tree")
    class TestTree {

        @Test
        @DisplayName("ends every source with exactly one line feed, because a reader opens both trees")
        void endsEverySourceWithOneLineFeed() {
            final List<SourceFile> sources = sourcesBeneath(TEST_SOURCE_ROOT);

            assertThat(sources)
                    .as("the walk must have found the test tree")
                    .hasSizeGreaterThan(MINIMUM_TEST_SOURCES);
            assertThat(notEndingWithOneLineFeed(sources))
                    .as("a source that does not end with one line feed")
                    .isEmpty();
        }

        @Test
        @DisplayName("declares a type in every compilation unit, so an empty test file cannot pass for a "
                + "suite that runs")
        void declaresATypeInEveryCompilationUnit() {
            final List<String> typeless = new ArrayList<>();
            for (final SourceFile source : sourcesBeneath(TEST_SOURCE_ROOT)) {
                if (!declaresAType(source)) {
                    typeless.add(source.path().toString());
                }
            }

            assertThat(typeless)
                    .as("an empty or typeless test source runs nothing while appearing to be a suite")
                    .isEmpty();
        }
    }

    /**
     * Reports whether a source declares a type.
     *
     * <p>The test is deliberately coarse - a line that is not a comment and that names one of the five
     * type keywords - because the question being asked is whether the file has any declaration at all,
     * not whether a particular declaration is well formed. The compiler answers the second question and a
     * finer test here could only disagree with it.
     *
     * @param  source the source to examine
     * @return {@code true} when the file declares at least one type
     */
    private static boolean declaresAType(final SourceFile source) {
        for (final String line : new String(source.content(), StandardCharsets.UTF_8)
                .split(LINE_FEED)) {
            final String text = line.strip();
            if (text.startsWith("*") || text.startsWith("//") || text.startsWith("/*")) {
                continue;
            }
            if (text.contains("class ") || text.contains("interface ") || text.contains("enum ")
                    || text.contains("record ") || text.contains("@interface ")) {
                return true;
            }
        }
        return false;
    }
}
