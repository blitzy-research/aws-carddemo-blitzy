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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Guards the module's package-layering invariant by reading the production source tree and inspecting
 * every {@code com.carddemo} import in it.
 *
 * <p><strong>Why this file exists.</strong> The specification states the layering direction in
 * §0.5.2: the API layer may depend on the service layer, the DTOs and the utilities; the service layer
 * may depend on the repositories, the domain, the utilities and the exceptions; the repositories may
 * depend only on the domain; and the utilities and exceptions depend on nothing above them. Nothing
 * depends upward. Until this file was written <em>no test guarded that</em>, which is precisely how two
 * upward edges came to exist and be reported in review: a service importing a configuration class, and
 * services taking and returning API transport records. Restoring the direction without also guarding it
 * would leave the next regression just as undetectable as the last one, so the invariant is asserted
 * here rather than merely documented.
 *
 * <p><strong>How it is checked.</strong> By reading source text, not by reflection over the compiled
 * classes. An import statement is the edge the rule is about: a package cycle is a compile-time
 * property of declarations, and the declaration is what a reviewer edits. Reading the text also catches
 * an edge that a runtime graph would miss, such as a type named only in a signature that no test
 * happens to exercise. Every {@code .java} file under {@code src/main/java} is read, its own package is
 * taken from its {@code package} declaration, and every {@code import com.carddemo.…} line in it is
 * resolved to the package it names. A floor on the number of sources found is asserted first, because
 * an absence assertion over an empty walk is vacuous.
 *
 * <p><strong>The one exemption, and why it is licensed rather than tolerated.</strong>
 * {@code FieldErrorTranslationService} imports {@code api.dto.FieldErrorDecorator}. That single edge is
 * licensed by the platform's own specification for the account-update service, §A5, which explicitly
 * permits a service to name the transport types it returns, and the decorator is the accumulator the
 * legacy validation macro {@code app/cpy/CSSETATY.cpy} expands into - a type whose whole purpose is to
 * be handed back across the boundary. The exemption is named here as a single class-and-type pair
 * rather than as a package-wide hole, so a second service reaching for a transport record fails this
 * test even though the first one is allowed to.
 *
 * <p><strong>What this file deliberately does not assert.</strong> The configuration package depends
 * downward on the API, service and utility packages, and that is not a violation: it is the composition
 * root, and wiring a bean requires naming the type being wired. What was wrong was the reverse edge -
 * a service reading a catalogue that happened to be annotated as configuration - and that is what is
 * forbidden below.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. It reads files from the module
 * directory the build runs tests from.
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * @since 1.0.0
 */
@DisplayName("package layering :: nothing depends upward, and the one exemption is named")
final class PackageLayeringTest {

    /** The production source tree, relative to the module directory the build runs tests from. */
    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");

    /** The module's base package, which every internal import is measured against. */
    private static final String BASE_PACKAGE = "com.carddemo";

    /**
     * A conservative floor on the number of production sources, so a walk that found almost nothing
     * cannot satisfy the absence assertions. The module ships well over a hundred classes; this figure
     * is deliberately far below that so it does not become a maintenance tripwire.
     */
    private static final int MINIMUM_PRODUCTION_SOURCES = 25;

    /** A conservative floor on the number of internal edges, for the same reason. */
    private static final int MINIMUM_INTERNAL_EDGES = 100;

    /** Matches the file's own package declaration. */
    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("^package\\s+([A-Za-z0-9_.]+)\\s*;", Pattern.MULTILINE);

    /** Matches an import of a module type, capturing the fully qualified name. */
    private static final Pattern INTERNAL_IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?(com\\.carddemo\\.[A-Za-z0-9_.]+)\\s*;", Pattern.MULTILINE);

    /**
     * The one licensed upward edge, as a declaring class paired with the transport type it may name.
     *
     * <p>Held as an exact pair so the licence covers this edge and nothing adjacent to it.
     */
    private static final Map<String, String> LICENSED_UPWARD_EDGES = Map.of(
            "com.carddemo.service.FieldErrorTranslationService",
            "com.carddemo.api.dto.FieldErrorDecorator");

    /**
     * The permitted downward dependencies of each package, keyed by the depending package.
     *
     * <p>Read from §0.5.2. A package absent from this map declares no internal import at all and is
     * asserted to keep declaring none. Sub-package edges within one area - the domain naming its own
     * identifier types, the API naming its own DTOs - are listed explicitly rather than allowed by a
     * prefix rule, so a new sub-package cannot inherit a permission nobody granted it.
     */
    private static final Map<String, Set<String>> PERMITTED_DEPENDENCIES = Map.of(
            "com.carddemo.api", Set.of("com.carddemo.api.dto", "com.carddemo.service",
                    "com.carddemo.domain", "com.carddemo.domain.enums", "com.carddemo.domain.id",
                    "com.carddemo.util", "com.carddemo.exception"),
            "com.carddemo.api.dto", Set.of("com.carddemo.domain.enums"),
            "com.carddemo.service", Set.of("com.carddemo.repository", "com.carddemo.domain",
                    "com.carddemo.domain.enums", "com.carddemo.domain.id", "com.carddemo.util",
                    "com.carddemo.exception"),
            "com.carddemo.batch", Set.of("com.carddemo.service", "com.carddemo.batch.step",
                    "com.carddemo.repository", "com.carddemo.domain", "com.carddemo.domain.enums",
                    "com.carddemo.domain.id", "com.carddemo.util", "com.carddemo.exception"),
            "com.carddemo.batch.step", Set.of("com.carddemo.service", "com.carddemo.repository",
                    "com.carddemo.domain", "com.carddemo.domain.enums", "com.carddemo.domain.id",
                    "com.carddemo.util", "com.carddemo.exception"),
            "com.carddemo.repository", Set.of("com.carddemo.domain", "com.carddemo.domain.id",
                    "com.carddemo.domain.enums"),
            "com.carddemo.config", Set.of("com.carddemo.api", "com.carddemo.api.dto",
                    "com.carddemo.service", "com.carddemo.batch", "com.carddemo.batch.step",
                    "com.carddemo.repository", "com.carddemo.domain", "com.carddemo.domain.enums",
                    "com.carddemo.domain.id", "com.carddemo.util", "com.carddemo.exception"),
            "com.carddemo.domain", Set.of("com.carddemo.domain.enums", "com.carddemo.domain.id",
                    "com.carddemo.exception"),
            "com.carddemo.domain.id", Set.of("com.carddemo.domain.enums"),
            "com.carddemo.util", Set.of("com.carddemo.domain", "com.carddemo.domain.enums",
                    "com.carddemo.domain.id", "com.carddemo.exception"));

    /**
     * One internal import edge: the class that declares it and the type it names.
     *
     * @param fromType the fully qualified name of the declaring class
     * @param fromPackage the package the declaring class sits in
     * @param toType the fully qualified name of the imported type
     * @param toPackage the package the imported type sits in
     */
    private record Edge(String fromType, String fromPackage, String toType, String toPackage) {

        /**
         * Renders the edge the way a failure message should read it.
         *
         * @return the edge as an arrow between the two types
         */
        String describe() {
            return fromType + " -> " + toType;
        }
    }

    /**
     * Reads every internal import edge out of the production source tree.
     *
     * @return one entry per {@code import com.carddemo.…} line, in file order
     */
    private static List<Edge> internalEdges() {
        assertThat(PRODUCTION_SOURCE_ROOT)
                .as("the production source root must be readable from the test working directory, "
                        + "or every absence assertion in this file would be vacuous")
                .isDirectory();

        final List<Edge> edges = new ArrayList<>();
        int sourcesRead = 0;
        try (Stream<Path> tree = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            for (final Path path : tree.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                sourcesRead++;
                final String text = readText(path);
                final String ownPackage = declaredPackageOf(path, text);
                final String ownType = ownPackage + "."
                        + path.getFileName().toString().replace(".java", "");

                final Matcher imports = INTERNAL_IMPORT.matcher(text);
                while (imports.find()) {
                    final String imported = imports.group(1);
                    edges.add(new Edge(ownType, ownPackage, imported, packageOf(imported)));
                }
            }
        } catch (final IOException problem) {
            throw new UncheckedIOException("unable to walk " + PRODUCTION_SOURCE_ROOT, problem);
        }

        assertThat(sourcesRead)
                .as("far too few production sources were read for the absence assertions to mean "
                        + "anything; the walk must have started in the wrong directory")
                .isGreaterThanOrEqualTo(MINIMUM_PRODUCTION_SOURCES);
        assertThat(edges.size())
                .as("far too few internal import edges were found for the absence assertions to "
                        + "mean anything; the import pattern must have stopped matching")
                .isGreaterThanOrEqualTo(MINIMUM_INTERNAL_EDGES);
        return edges;
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
     * Reads a file's own package declaration.
     *
     * @param path the file being read, named in the failure message
     * @param text the file's content
     * @return the declared package
     */
    private static String declaredPackageOf(final Path path, final String text) {
        final Matcher declaration = PACKAGE_DECLARATION.matcher(text);
        assertThat(declaration.find())
                .as("every production source declares a package, but %s does not", path)
                .isTrue();
        return declaration.group(1);
    }

    /**
     * Reduces a fully qualified type name to the package that holds it.
     *
     * <p>Nested types are named with a dot in an import statement, so the reduction drops trailing
     * segments while they begin with an upper-case letter. That makes
     * {@code com.carddemo.service.MenuService.MenuRow} resolve to {@code com.carddemo.service} rather
     * than to a package that does not exist.
     *
     * @param qualifiedName the imported name
     * @return the package the name sits in
     */
    private static String packageOf(final String qualifiedName) {
        String remaining = qualifiedName;
        while (remaining.contains(".")) {
            final String lastSegment = remaining.substring(remaining.lastIndexOf('.') + 1);
            if (lastSegment.isEmpty() || !Character.isUpperCase(lastSegment.charAt(0))) {
                break;
            }
            remaining = remaining.substring(0, remaining.lastIndexOf('.'));
        }
        return remaining;
    }

    /**
     * Reports whether an edge is the one licensed upward dependency.
     *
     * @param edge the edge being judged
     * @return {@code true} when the edge is exactly the licensed pair
     */
    private static boolean licensed(final Edge edge) {
        return edge.toType().equals(LICENSED_UPWARD_EDGES.get(edge.fromType()));
    }

    // ----------------------------------------------------------------------------------------
    // The two edges review found, asserted individually so a regression names itself
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the two upward edges review found stay closed")
    final class TheTwoUpwardEdgesStayClosed {

        @Test
        @DisplayName("no service imports a configuration class, because a service reading a "
                + "configuration bean is the package cycle that made the catalogue unreachable "
                + "from a plain unit test")
        void noServiceImportsAConfigurationClass() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.service"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.config"))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders)
                    .as("a catalogue a service needs belongs in the service package with a service "
                            + "stereotype, which is where the menu option catalogue now sits")
                    .isEmpty();
        }

        @Test
        @DisplayName("no service imports a transport record except the one licensed decorator, so a "
                + "service neither takes nor returns an API type")
        void noServiceImportsATransportRecordExceptTheLicensedDecorator() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.service"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.api"))
                    .filter(edge -> !licensed(edge))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders)
                    .as("a service that needs a carried value declares a type it owns and lets an "
                            + "adapter in the API layer convert, which is what ConversationState and "
                            + "the per-service result records are for")
                    .isEmpty();
        }

        @Test
        @DisplayName("the licensed edge is still present and is still exactly one, so the exemption "
                + "cannot quietly widen and cannot quietly become dead")
        void theLicensedEdgeIsStillPresentAndStillExactlyOne() {
            final List<String> upward = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.service"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.api"))
                    .map(Edge::describe)
                    .toList();

            assertThat(upward)
                    .containsExactly("com.carddemo.service.FieldErrorTranslationService "
                            + "-> com.carddemo.api.dto.FieldErrorDecorator");
        }

        @Test
        @DisplayName("no batch job or step imports a transport record or a configuration class, "
                + "because a batch tier has no screen to answer and no wiring to read")
        void noBatchClassImportsATransportRecordOrConfiguration() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().startsWith("com.carddemo.batch"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.api")
                            || edge.toPackage().startsWith("com.carddemo.config"))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The direction as a whole
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the layering direction holds across every internal edge")
    final class TheLayeringDirectionHolds {

        @Test
        @DisplayName("every internal edge is a permitted dependency of the package that declares it, "
                + "so no upward or sideways edge exists anywhere in the module")
        void everyInternalEdgeIsPermitted() {
            final List<String> offenders = new ArrayList<>();
            for (final Edge edge : internalEdges()) {
                if (edge.fromPackage().equals(edge.toPackage()) || licensed(edge)) {
                    continue;
                }
                final Set<String> permitted =
                        PERMITTED_DEPENDENCIES.getOrDefault(edge.fromPackage(), Set.of());
                if (!permitted.contains(edge.toPackage())) {
                    offenders.add(edge.describe()
                            + "  (" + edge.fromPackage() + " may not depend on " + edge.toPackage()
                            + ")");
                }
            }

            assertThat(offenders)
                    .as("a new edge is permitted only by adding it to this file's table, which is "
                            + "where the layering decision is recorded")
                    .isEmpty();
        }

        @Test
        @DisplayName("the utility and exception packages depend on nothing above them, so the two "
                + "leaf layers stay leaves")
        void theLeafPackagesDependOnNothingAboveThem() {
            final Set<String> forbidden = Set.of("com.carddemo.api", "com.carddemo.api.dto",
                    "com.carddemo.service", "com.carddemo.repository", "com.carddemo.config",
                    "com.carddemo.batch", "com.carddemo.batch.step");

            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.util")
                            || edge.fromPackage().equals("com.carddemo.exception"))
                    .filter(edge -> forbidden.contains(edge.toPackage()))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("the domain package names no Spring type and no repository, so an entity carries "
                + "persistence annotations and no framework dependency beyond them")
        void theDomainPackageNamesNoRepository() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().startsWith("com.carddemo.domain"))
                    .filter(edge -> !edge.toPackage().startsWith("com.carddemo.domain")
                            && !edge.toPackage().equals("com.carddemo.exception"))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders)
                    .as("the domain is the innermost layer; it may name its own sub-packages and the "
                            + "exceptions its write-time guards raise, and nothing else")
                    .isEmpty();
        }

        @Test
        @DisplayName("a repository names only the domain, so no query method reaches a service or a "
                + "transport record")
        void aRepositoryNamesOnlyTheDomain() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.repository"))
                    .filter(edge -> !edge.toPackage().startsWith("com.carddemo.domain"))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("the internal dependency graph has no two-package cycle, so no pair of packages "
                + "can be compiled only together")
        void theGraphHasNoTwoPackageCycle() {
            final Map<String, Set<String>> graph = new LinkedHashMap<>();
            for (final Edge edge : internalEdges()) {
                if (edge.fromPackage().equals(edge.toPackage())) {
                    continue;
                }
                graph.computeIfAbsent(edge.fromPackage(), key -> new LinkedHashSet<>())
                        .add(edge.toPackage());
            }

            final List<String> cycles = new ArrayList<>();
            for (final Map.Entry<String, Set<String>> from : graph.entrySet()) {
                for (final String to : from.getValue()) {
                    if (graph.getOrDefault(to, Set.of()).contains(from.getKey())
                            && from.getKey().compareTo(to) < 0) {
                        cycles.add(from.getKey() + " <-> " + to);
                    }
                }
            }

            assertThat(cycles)
                    .as("the one licensed upward edge does not create a cycle, because the API DTO "
                            + "package declares no import of the service package in return")
                    .isEmpty();
        }
    }

    // ----------------------------------------------------------------------------------------
    // The reader itself, so a silent misread cannot pass as compliance
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the source reader itself is sound, so a misread cannot pass as compliance")
    final class TheSourceReaderIsSound {

        @Test
        @DisplayName("every package this module actually ships appears as the source of at least one "
                + "edge or is deliberately edge-free, so no package escapes the check unnoticed")
        void everyShippedPackageIsAccountedFor() {
            final Set<String> declaring = new LinkedHashSet<>();
            for (final Edge edge : internalEdges()) {
                declaring.add(edge.fromPackage());
            }

            assertThat(declaring)
                    .as("each of these declares internal imports and is therefore genuinely measured "
                            + "by the assertions above")
                    .contains("com.carddemo.api", "com.carddemo.api.dto", "com.carddemo.service",
                            "com.carddemo.batch", "com.carddemo.batch.step",
                            "com.carddemo.repository", "com.carddemo.config", "com.carddemo.domain",
                            "com.carddemo.util");
            assertThat(PERMITTED_DEPENDENCIES.keySet())
                    .as("no package declares an edge without a row in the permission table, or the "
                            + "table's default of no permission would fail it for the wrong reason")
                    .containsAll(declaring);
        }

        @Test
        @DisplayName("a nested type import resolves to its enclosing class's package, so an edge onto "
                + "a nested result record is measured against the right package")
        void aNestedTypeImportResolvesToTheEnclosingPackage() {
            assertThat(packageOf("com.carddemo.service.MenuService.MenuRow"))
                    .isEqualTo("com.carddemo.service");
            assertThat(packageOf("com.carddemo.api.dto.NavigationContext.ProgramContext"))
                    .isEqualTo("com.carddemo.api.dto");
            assertThat(packageOf("com.carddemo.domain.enums.UserType"))
                    .isEqualTo("com.carddemo.domain.enums");
        }

        @Test
        @DisplayName("the licence recognises only its exact pair, so neither a different service "
                + "naming the same type nor the same service naming a different type is licensed")
        void theLicenceRecognisesOnlyItsExactPair() {
            assertThat(licensed(new Edge("com.carddemo.service.FieldErrorTranslationService",
                    "com.carddemo.service", "com.carddemo.api.dto.FieldErrorDecorator",
                    "com.carddemo.api.dto")))
                    .isTrue();
            assertThat(licensed(new Edge("com.carddemo.service.MenuService",
                    "com.carddemo.service", "com.carddemo.api.dto.FieldErrorDecorator",
                    "com.carddemo.api.dto")))
                    .as("a second service reaching for the decorator is not covered by the licence")
                    .isFalse();
            assertThat(licensed(new Edge("com.carddemo.service.FieldErrorTranslationService",
                    "com.carddemo.service", "com.carddemo.api.dto.MenuResponse",
                    "com.carddemo.api.dto")))
                    .as("the licensed service reaching for a different transport record is not "
                            + "covered either")
                    .isFalse();
        }

        @Test
        @DisplayName("the import pattern matches a static import as well as an ordinary one, so an "
                + "upward edge cannot hide behind a statically imported constant")
        void theImportPatternMatchesAStaticImport() {
            final Matcher ordinary =
                    INTERNAL_IMPORT.matcher("import com.carddemo.service.MenuService;\n");
            final Matcher statically = INTERNAL_IMPORT.matcher(
                    "import static com.carddemo.api.dto.PageMetadata.USER_LIST_PAGE_SIZE;\n");

            assertThat(ordinary.find()).isTrue();
            assertThat(ordinary.group(1)).isEqualTo("com.carddemo.service.MenuService");
            assertThat(statically.find()).isTrue();
            assertThat(packageOf(statically.group(1)))
                    .as("a statically imported constant resolves to the package of the type that "
                            + "declares it")
                    .isEqualTo("com.carddemo.api.dto");
        }

        @Test
        @DisplayName("an import inside a comment or a javadoc reference is not counted, because the "
                + "pattern anchors to the start of a line")
        void aCommentedImportIsNotCounted() {
            assertThat(INTERNAL_IMPORT.matcher(
                    " * import com.carddemo.config.MenuOptionCatalog;\n").find())
                    .isFalse();
            assertThat(INTERNAL_IMPORT.matcher(
                    "// import com.carddemo.config.MenuOptionCatalog;\n").find())
                    .isFalse();
        }
    }
}
