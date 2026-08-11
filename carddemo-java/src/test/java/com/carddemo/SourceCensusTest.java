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

import static com.carddemo.support.JavaSourceCensus.declarationsOf;
import static com.carddemo.support.JavaSourceCensus.declaresAType;
import static com.carddemo.support.JavaSourceCensus.declaresAnAbstractClass;
import static com.carddemo.support.JavaSourceCensus.declaresAnExecutableTest;
import static com.carddemo.support.JavaSourceCensus.isDiscoveredByARunner;
import static com.carddemo.support.JavaSourceCensus.sourcesUnder;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.support.JavaSourceCensus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Census of the module's Java sources: every one of them declares a type, and every one the test runners
 * discover declares something the runners can execute.
 *
 * <h2>The defect this exists to remove</h2>
 *
 * <p>A compilation unit carrying nothing but a licence header and a {@code package} declaration is legal
 * Java and compiles without complaint. That legality buys three separate untruths at once:
 *
 * <ul>
 *   <li><strong>The source count overstates the module.</strong> Every figure derived from the compiler's
 *       file count — the zero-warning accounting, the audit denominators, any published file inventory —
 *       then describes a tree that does not exist.</li>
 *   <li><strong>The coverage rule is bypassed rather than met.</strong> The build fails when any class is
 *       wholly untested ({@code jacoco.wholly.untested.classes.maximum} is zero). A source that emits no
 *       class contributes no class to count, so a file with no implementation satisfies the strictest rule
 *       in the build by having nothing in it — the exact inverse of what the rule is for.</li>
 *   <li><strong>A test name promises a test that does not exist.</strong> A name matching the unit
 *       runner's {@code **&#47;*Test.java} include is looked for, found to declare no class, and silently
 *       executes nothing, while a reader auditing coverage by test name counts it.</li>
 * </ul>
 *
 * <p>Such a file is also the ideal shadow of a real one: it can carry the name of a type genuinely
 * implemented in another package, and a reader following an import arrives at the empty one. A census is
 * what makes the whole class of defect visible, because otherwise the next such file goes unnoticed until
 * somebody happens to open it.
 *
 * <h2>How the census is taken</h2>
 *
 * <p>By reading source text, not by loading classes. The property under audit is a property of the
 * <em>declaration</em> — whether a compilation unit declares a type at all — and a type that was never
 * declared cannot be reflected over. Reading text also keeps this class inside the module's zero-reflection
 * constraint, which would be a strange rule to break in the instrument that guards source shape.
 *
 * <p>Comments and string literals are removed before any keyword is looked for, so a declaration keyword
 * mentioned in prose or carried inside a literal — and this suite carries several, since other audits assert
 * on such text — cannot be mistaken for a declaration.
 *
 * <p>A floor is asserted on the number of sources walked before any absence is asserted over them. An
 * absence assertion over an empty walk passes for the wrong reason, which is how a broken walk would
 * otherwise read as compliance.
 *
 * <p>Provenance: this class has no legacy antecedent — the legacy estate carries no test harness of any
 * kind.
 */
@DisplayName("Source census: every Java source declares a type, and every discovered test declares a test")
class SourceCensusTest {

    /** The production tree, read from the shared reader rather than restated beside it. */
    private static final String PRODUCTION_TREE = JavaSourceCensus.PRODUCTION_TREE;

    /** The test tree, from the same owner. */
    private static final String TEST_TREE = JavaSourceCensus.TEST_TREE;

    /**
     * Floor on the production sources the walk must find.
     *
     * <p>A floor rather than an exact count: the exact figure is a property of the delivery and moves
     * whenever a class is added, whereas a walk that finds almost nothing is broken. The figure is set well
     * below the delivered 241 so that this class fails for the reason it names and not for a legitimate
     * addition.
     */
    private static final int PRODUCTION_SOURCE_FLOOR = 200;

    /** Floor on the test sources the walk must find, for the same reason, against a delivered 532. */
    private static final int TEST_SOURCE_FLOOR = 450;

    /**
     * A container image reference written as a string literal: a repository, then a version tag.
     *
     * <p>Deliberately narrow. It matches the two families this module starts - the database server and the
     * cloud emulator - by requiring a tag that begins with a digit, which is what a version tag does and
     * what an ordinary two-part identifier in prose does not. The guard below asserts a floor on the number
     * of references it finds, so a pattern that had stopped matching would fail rather than report
     * compliance over nothing.
     */
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(
            "\"(?:postgres|localstack/localstack|prom/prometheus|grafana/grafana|eclipse-temurin|"
                    + "jaegertracing/all-in-one|testcontainers/ryuk):[0-9v][\\w.+-]*");

    /** How a content digest is introduced in an image reference. */
    private static final String DIGEST_MARKER = "@sha256:";

    /** This class's own file name, excluded from the image audit for the reason given at the exclusion. */
    private static final String OWN_SOURCE_NAME = "SourceCensusTest.java";

    /**
     * Tokens whose presence means a source actually starts a container.
     *
     * <p>The audit is scoped to those sources, and the scoping is what makes it correct rather than merely
     * strict. A text-contract test legitimately holds a readable tag on its own - it exists to assert that
     * the tag portion of a reference appears in the stack definition - and flagging that would be reporting
     * a naming convention as a supply-chain risk. What is actually risky is <em>running</em> against a
     * mutable pointer, and only these constructs do that.
     */
    private static final List<String> CONTAINER_STARTING_TOKENS = List.of(
            "DockerImageName", "PostgreSQLContainer", "LocalStackContainer", "GenericContainer");

    /** Creates the census. */
    SourceCensusTest() {
        // Intentionally empty: this class holds no per-instance state.
    }

    @Nested
    @DisplayName("no Java source is empty")
    class NoSourceIsEmpty {

        /** Creates the nest. */
        NoSourceIsEmpty() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        @Test
        @DisplayName("neither tree carries a blank or zero-byte compilation unit")
        void neitherTreeCarriesABlankSource() throws IOException {
            final List<Path> production = sourcesUnder(PRODUCTION_TREE);
            final List<Path> tests = sourcesUnder(TEST_TREE);
            final List<String> blank = new ArrayList<>();

            for (final Path source : production) {
                if (Files.size(source) == 0L) {
                    blank.add(source.toString());
                }
            }
            for (final Path source : tests) {
                if (Files.size(source) == 0L) {
                    blank.add(source.toString());
                }
            }

            assertThat(production)
                    .as("the production walk must find the module, or the absence asserted below is vacuous")
                    .hasSizeGreaterThanOrEqualTo(PRODUCTION_SOURCE_FLOOR);
            assertThat(tests)
                    .as("and so must the test walk")
                    .hasSizeGreaterThanOrEqualTo(TEST_SOURCE_FLOOR);
            assertThat(blank)
                    .as("a zero-byte source compiles, emits nothing and is counted by every figure derived "
                            + "from the source count. One such file reached review under a test name the "
                            + "unit runner matched, so the runner looked for it and executed nothing. "
                            + "Offending: %s", blank)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("every Java source declares a type")
    class EverySourceDeclaresAType {

        /** Creates the nest. */
        EverySourceDeclaresAType() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        @Test
        @DisplayName("no production source carries a package declaration and nothing else, so no source "
                + "can satisfy the wholly-untested-class rule by emitting no class")
        void noProductionSourceDeclaresNoType() throws IOException {
            final List<String> withoutAType = new ArrayList<>();

            final List<Path> production = sourcesUnder(PRODUCTION_TREE);
            for (final Path source : production) {
                if (!declaresAType(declarationsOf(source))) {
                    withoutAType.add(source.toString());
                }
            }

            assertThat(production).hasSizeGreaterThanOrEqualTo(PRODUCTION_SOURCE_FLOOR);
            assertThat(withoutAType)
                    .as("eleven such files reached review, ten of them carrying the name of a transport "
                            + "record that is genuinely implemented under com.carddemo.api.dto. A source "
                            + "that declares no type emits no class, so the build's rule that no class may "
                            + "be wholly untested cannot see it. Offending: %s", withoutAType)
                    .isEmpty();
        }

        @Test
        @DisplayName("and neither does any test source")
        void noTestSourceDeclaresNoType() throws IOException {
            final List<String> withoutAType = new ArrayList<>();

            final List<Path> tests = sourcesUnder(TEST_TREE);
            for (final Path source : tests) {
                if (!declaresAType(declarationsOf(source))) {
                    withoutAType.add(source.toString());
                }
            }

            assertThat(tests).hasSizeGreaterThanOrEqualTo(TEST_SOURCE_FLOOR);
            assertThat(withoutAType)
                    .as("a test source that declares no type is a name in a report and nothing behind it. "
                            + "Offending: %s", withoutAType)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("every source a runner discovers declares something it can execute")
    class EveryDiscoveredTestDeclaresATest {

        /** Creates the nest. */
        EveryDiscoveredTestDeclaresATest() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        @Test
        @DisplayName("a source named for a runner declares an executable test, unless its type is abstract "
                + "and contributes its tests to subclasses")
        void everyDiscoveredSourceDeclaresAnExecutableTest() throws IOException {
            final List<String> withoutATest = new ArrayList<>();
            int discovered = 0;

            for (final Path source : sourcesUnder(TEST_TREE)) {
                if (!isDiscoveredByARunner(source)) {
                    continue;
                }
                discovered++;
                final String declarations = declarationsOf(source);
                if (!declaresAnExecutableTest(declarations)
                        && !declaresAnAbstractClass(declarations)) {
                    withoutATest.add(source.toString());
                }
            }

            assertThat(discovered)
                    .as("the runners' include patterns must match the suite, or the absence asserted below "
                            + "is vacuous")
                    .isGreaterThanOrEqualTo(TEST_SOURCE_FLOOR / 2);
            assertThat(withoutATest)
                    .as("a name a runner matches and finds nothing in is counted as a test class by every "
                            + "reader and executed by nobody. An abstract base is exempt because its "
                            + "subclasses are what run. Offending: %s", withoutATest)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("every container image the test tree names is pinned by digest")
    class EveryTestImageIsPinnedByDigest {

        /** Creates the nest. */
        EveryTestImageIsPinnedByDigest() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        @Test
        @DisplayName("no source that starts a container names its image by tag alone, because a tag is a "
                + "mutable pointer and the stack definition and the pipeline both pin by digest")
        void noContainerStartingSourceNamesAnImageByTagAlone() throws IOException {
            final List<String> unpinned = new ArrayList<>();
            int auditedSources = 0;
            int references = 0;

            for (final Path source : sourcesUnder(TEST_TREE)) {
                if (source.getFileName().toString().equals(OWN_SOURCE_NAME)) {
                    // This class names the container-starting constructs as pattern data, so it matches its
                    // own scoping rule while starting nothing. Auditing itself would only ever report that
                    // it holds no image reference.
                    continue;
                }
                final String text = Files.readString(source, StandardCharsets.UTF_8);
                if (CONTAINER_STARTING_TOKENS.stream().noneMatch(text::contains)) {
                    continue;
                }
                auditedSources++;
                final java.util.regex.Matcher found = IMAGE_REFERENCE.matcher(text);
                while (found.find()) {
                    references++;
                    // Scoped to the declaration the reference sits in, because the whole reference exceeds
                    // the line budget and is written as a concatenation across two lines. Anything past the
                    // terminating semicolon belongs to a different declaration and cannot vouch for this
                    // one.
                    final int end = text.indexOf(';', found.end());
                    final String declaration = text.substring(found.start(),
                            end < 0 ? text.length() : end);
                    if (!declaration.contains(DIGEST_MARKER)) {
                        unpinned.add(source + " -> " + found.group());
                    }
                }
            }

            assertThat(auditedSources)
                    .as("the guard must find the sources that start containers, or its absence assertion "
                            + "is vacuous")
                    .isPositive();
            assertThat(references)
                    .as("and it must find image references within them")
                    .isGreaterThanOrEqualTo(auditedSources);
            assertThat(unpinned)
                    .as("a container image started from a tag alone can be rebuilt upstream, silently "
                            + "moving the suite onto a server or an emulator the container stack and the "
                            + "pipeline were never verified against. Both of those pin by digest; a source "
                            + "that starts a container must too. Offending: %s", unpinned)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the detectors themselves are sound")
    class TheDetectorsAreSound {

        /** Creates the nest. */
        TheDetectorsAreSound() {
            // Intentionally empty: this nest contributes tests, not state.
        }

        @Test
        @DisplayName("the type detector fires on each declaration form and stays silent on a package-only "
                + "unit, which is the file shape this census was written for")
        void theTypeDetectorFiresOnEachFormAndStaysSilentOnAPackageOnlyUnit() {
            assertThat(declaresAType("package com.carddemo.api;"))
                    .as("a package declaration on its own is not a type declaration")
                    .isFalse();
            assertThat(declaresAType("public final class Account {")).isTrue();
            assertThat(declaresAType("public interface ContractTypeRoster {")).isTrue();
            assertThat(declaresAType("public enum UserType {")).isTrue();
            assertThat(declaresAType("private record Row(String key) { }")).isTrue();
            assertThat(declaresAType("public @interface Marker { }")).isTrue();
        }

        @Test
        @DisplayName("and the contextual use of record as an ordinary identifier is not read as a "
                + "declaration, which is what would otherwise pass a package-only unit in this suite")
        void theContextualRecordKeywordIsNotReadAsADeclaration() {
            assertThat(declaresAType("final String record = reader.read();")).isFalse();
            assertThat(declaresAType("for (final String record : records) {")).isFalse();
            assertThat(declaresAType("images.forEach(record -> writer.write(record));"))
                    .isFalse();
        }

        @Test
        @DisplayName("comments and string literals are removed before a keyword is looked for, so text "
                + "another audit asserts on cannot pass as a declaration")
        void commentsAndLiteralsAreRemovedBeforeMatching(@TempDir final Path owned) throws IOException {
            final Path illustration = owned.resolve("PackageOnly.java");
            Files.writeString(illustration, String.join("\n",
                    "/* class NotADeclaration { */",
                    "package com.carddemo.illustration;",
                    "// enum AlsoNotADeclaration",
                    "// the line below is a literal another audit asserts on",
                    "// \"interface Neither\"",
                    ""), StandardCharsets.UTF_8);

            assertThat(declaresAType(declarationsOf(illustration)))
                    .as("a unit whose only declaration keywords sit in comments declares no type")
                    .isFalse();
        }

        @Test
        @DisplayName("a text block's body is blanked, which the pattern chain this scanner replaced never "
                + "did - and this module writes 354 text-block delimiters")
        void aTextBlockBodyIsBlanked() {
            final String source = String.join("\n",
                    "package com.carddemo.illustration;",
                    "final class Holder {",
                    "    private static final String QUERY = \"\"\"",
                    "            interface NotADeclaration",
                    "            @Test",
                    "            @SuppressWarnings(\"unchecked\")",
                    "            \"\"\";",
                    "}",
                    "");

            final String code = JavaSourceCensus.codeOnly(source);

            assertThat(code)
                    .as("the body of a text block is not code, and a regular expression that forbids an "
                            + "embedded newline in a literal cannot reach it. Every construct written "
                            + "inside one must be gone")
                    .doesNotContain("interface NotADeclaration")
                    .doesNotContain("@Test")
                    .doesNotContain("@SuppressWarnings");
            assertThat(code)
                    .as("while the code around it survives, or the scanner has eaten the file")
                    .contains("final class Holder")
                    .contains("private static final String QUERY");
        }

        @Test
        @DisplayName("a quote inside a comment opens no literal and a slash inside a literal opens no "
                + "comment, which is the ordering defect a single scan cannot have")
        void neitherConstructCanOpenTheOtherOutOfOrder() {
            assertThat(JavaSourceCensus.codeOnly("/* an unpaired \" quote */ class After { "))
                    .as("a lone quote inside a block comment used to open a literal that ran to the next "
                            + "quote in the file, blanking real code between them")
                    .contains("class After");
            assertThat(JavaSourceCensus.codeOnly("String path = \"http://host/p\"; class After { "))
                    .as("and the two slashes inside a literal used to read as a line comment, blanking "
                            + "the rest of a real line")
                    .contains("class After");
            assertThat(JavaSourceCensus.codeOnly("char quote = '\\\\'; class After { "))
                    .as("an escaped backslash ends a character literal at its own closing quote, not at "
                            + "the next one it can find")
                    .contains("class After");
        }

        @Test
        @DisplayName("blanking preserves length and line structure, which is what makes a reported line "
                + "number a line an engineer can open")
        void blankingPreservesLengthAndLineStructure(@TempDir final Path owned) throws IOException {
            final List<String> lines = List.of(
                    "package com.carddemo.illustration;",
                    "/* a block comment",
                    "   spanning three lines",
                    "   and closing here */",
                    "final class Holder {",
                    "    // @SuppressWarnings(\"unchecked\") discussed, never written",
                    "    private static final String NAME = \"Holder\";",
                    "}");
            final String source = String.join("\n", lines) + "\n";
            final Path illustration = owned.resolve("Holder.java");
            Files.writeString(illustration, source, StandardCharsets.UTF_8);

            final String code = JavaSourceCensus.codeOnly(source);
            final List<String> codeLines = JavaSourceCensus.codeOnlyLinesOf(illustration);

            assertThat(code.length())
                    .as("every removed character becomes a space rather than disappearing, so an offset "
                            + "into the result addresses the same character it addresses in the source")
                    .isEqualTo(source.length());
            assertThat(codeLines)
                    .as("and every newline survives, so line n of the result is line n of the file")
                    .hasSize(lines.size() + 1);
            assertThat(codeLines.get(4).trim())
                    .as("line 5 of the file is the class declaration and is still readable as one, "
                            + "although a three-line comment precedes it")
                    .isEqualTo("final class Holder {");
            assertThat(codeLines.get(5))
                    .as("line 6 mentions the annotation in a comment, so the annotation is not on that "
                            + "line as far as an audit over code is concerned")
                    .doesNotContain("@SuppressWarnings")
                    .isBlank();
            assertThat(codeLines.get(6))
                    .as("line 7 keeps its declaration and loses only its literal")
                    .contains("private static final String NAME")
                    .doesNotContain("Holder\"");
        }

        @Test
        @DisplayName("the executable-test detector recognises every discovered form and rejects a class "
                + "that declares none")
        void theExecutableTestDetectorRecognisesEveryForm() {
            assertThat(declaresAnExecutableTest("    @Test")).isTrue();
            assertThat(declaresAnExecutableTest("    @ParameterizedTest(name = \"{0}\")")).isTrue();
            assertThat(declaresAnExecutableTest("    @RepeatedTest(3)")).isTrue();
            assertThat(declaresAnExecutableTest("    @TestFactory")).isTrue();
            assertThat(declaresAnExecutableTest("    @TestTemplate")).isTrue();
            assertThat(declaresAnExecutableTest("    @BeforeEach void seed() { }"))
                    .as("a lifecycle method is not an executable test node")
                    .isFalse();
            assertThat(declaresAnAbstractClass("public abstract class AbstractPostgresIT {"))
                    .isTrue();
        }
    }
}
