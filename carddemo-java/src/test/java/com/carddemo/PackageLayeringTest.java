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
 * <p><strong>There is no exemption.</strong> This file once carried a licence table naming the upward
 * edges that were tolerated; the table is gone because the edges are. Not one class under
 * {@code com.carddemo.service}, {@code com.carddemo.batch}, {@code com.carddemo.batch.step},
 * {@code com.carddemo.repository}, {@code com.carddemo.domain}, {@code com.carddemo.util} or
 * {@code com.carddemo.exception} names a type under {@code com.carddemo.api}, and no class under
 * {@code com.carddemo.api} names one under {@code com.carddemo.batch}. Every value that has to cross
 * the boundary crosses it in an API-layer adapter - {@code ScreenStateAdapter},
 * {@code AccountUpdateContractAdapter}, {@code UserContractAdapter}, {@code SignOnContractAdapter} and
 * {@code ConversationStateAdapter} - and every job launch goes through
 * {@code service.BatchJobLaunchService}. The absence is asserted as an absence rather than measured
 * against a table, which is the only form of the rule that cannot be widened by an edit to this file.
 *
 * <p><strong>The catalog edge is one-way.</strong> Configuration is the composition root and may
 * implement service-owned ports, but services never import configuration. Shared route, refusal and
 * publication contracts live below both configuration and the API boundary, so configuration imports
 * no API type and no reciprocal package pair is introduced.
 *
 * <p>A pure unit test: no Spring context, no connection, no container. It reads files from the module
 * directory the build runs tests from.
 *
 * @since 1.0.0
 */
@DisplayName("package layering :: nothing depends upward, and there is no exemption")
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
     * The classes whose upward edges were closed, named individually so a regression names itself.
     *
     * <h2>Why a list of closed classes and not a table of licensed edges</h2>
     *
     * <p>THE OPPOSITE CONSTRUCT IS NOT AVAILABLE HERE: a map naming each upward edge that is allowed to
     * exist, with a hand-counted total beside it, records upward edges rather than removing them. There are
     * none to record - not a service-to-transport edge and not an API-to-batch edge - so such a table would
     * have nothing to hold, and keeping an empty one would leave the mechanism in place for the next edge
     * to be entered into.
     *
     * <p>What replaces it is an inversion. Instead of naming what may point upward, this names the
     * classes that must never point upward again, and the tests below assert the absence directly. The
     * difference matters: an empty licence table lets an edge return the moment someone adds a row,
     * whereas a class named here cannot regain an edge at all without this list changing, and
     * {@link BothKindsOfUpwardEdgeStayClosed#theClosedServicesAllExist()} fails if a name here stops
     * matching a real production class.
     *
     * <h2>The twelve services and what each took instead</h2>
     *
     * <p>Ten screen services - account view, account update, card detail, card list, card update, bill
     * payment, transaction add, transaction view, transaction list and user management - each took and
     * returned wire records directly. Each of those pairs now exists twice: once as the wire record in
     * {@code api.dto}, with its width bounds, its operation groups and its serialization contract, and
     * once as a service-owned record in {@code com.carddemo.service} with none of that.
     * {@code ScreenNavigationState}, {@code ScreenInputState}, {@code BrowseWindow} and
     * {@code FieldErrorMarks} are the four shared screen carriers; {@code AccountUpdateCommand} and
     * {@code AccountUpdateOutcome} carry the account-update screen; {@code UserCommand} and
     * {@code UserOutcome} carry the four user-administration screens; and
     * {@code ValidationException.FieldError} carries every per-field finding.
     *
     * <p>The remaining two were the last entries in the old licence table, and each was argued there as
     * a design choice rather than a deviation - the reasoning being that a service whose whole job is to
     * produce a transport shape should be allowed to name it. Both are now closed on the same terms as
     * the other ten, because the argument proved to be about convenience rather than about direction:
     * {@code FieldErrorTranslationService} accumulates into the service-owned {@code FieldErrorMarks},
     * which is the twin {@code ScreenStateAdapter} already converted at the boundary for the screen
     * services, and {@code StatementGenerationService} builds the service-owned
     * {@code StatementLineSummary}, a component-for-component twin of {@code api.dto.StatementSummary}
     * carrying the same thirteen fields of {@code [app/cpy/COSTM01.CPY]}. Neither closure changed a
     * field name, a width or an emitted byte.
     *
     * <p>{@code api.ScreenStateAdapter} converts the four shared screen carriers,
     * {@code api.AccountUpdateContractAdapter} converts the account-update pair and
     * {@code api.UserContractAdapter} converts the user-administration pair; those three classes are
     * the only places any of it happens. The statement twin needs no adapter because no controller
     * publishes a statement line - the batch tier is its only consumer, and the batch tier may depend
     * on the service layer.
     *
     * <p>The closure is recorded in {@code docs/decision-log.md}.
     */
    private static final List<String> SERVICES_WITH_CLOSED_UPWARD_EDGES = List.of(
            "com.carddemo.service.AccountUpdateService",
            "com.carddemo.service.AccountViewService",
            "com.carddemo.service.BillPaymentService",
            "com.carddemo.service.CardDetailService",
            "com.carddemo.service.CardListService",
            "com.carddemo.service.CardUpdateService",
            "com.carddemo.service.FieldErrorTranslationService",
            "com.carddemo.service.StatementGenerationService",
            "com.carddemo.service.TransactionAddService",
            "com.carddemo.service.TransactionListService",
            "com.carddemo.service.TransactionViewService",
            "com.carddemo.service.UserManagementService");

    /** How many services the closure covers, counted by hand against the review finding. */
    private static final int CLOSED_SERVICE_COUNT = 12;

    /**
     * How many upward service-to-transport edges may exist. Zero, stated as a named budget so the
     * number appears in the source a reviewer reads rather than only in an emptiness assertion.
     */
    private static final int UPWARD_EDGE_BUDGET = 0;

    /**
     * A floor on the internal edges the service package must declare, so that asserting the absence of
     * an upward one is not satisfied by a walk that reached no service at all. Well over a hundred are
     * declared; the floor is set far below that so ordinary refactoring does not
     * trip it while an empty or misdirected walk still does.
     */
    private static final int MINIMUM_SERVICE_EDGES = 40;

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
                    "com.carddemo.domain.id", "com.carddemo.util", "com.carddemo.exception",
                    "com.carddemo.config"),
            "com.carddemo.batch.step", Set.of("com.carddemo.service", "com.carddemo.repository",
                    "com.carddemo.domain", "com.carddemo.domain.enums", "com.carddemo.domain.id",
                    "com.carddemo.util", "com.carddemo.exception"),
            "com.carddemo.repository", Set.of("com.carddemo.domain", "com.carddemo.domain.id",
                    "com.carddemo.domain.enums"),
            "com.carddemo.config", Set.of("com.carddemo.service",
                    "com.carddemo.batch", "com.carddemo.batch.step",
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


    // ----------------------------------------------------------------------------------------
    // // The two kinds of upward edge this package layering admits - a service naming a transport record, and the batch
    // control surface naming a job configuration - asserted separately so a regression names itself
    // ----------------------------------------------------------------------------------------

    @Nested
    @DisplayName("both kinds of upward edge review found stay closed")
    final class BothKindsOfUpwardEdgeStayClosed {

        @Test
        @DisplayName("no service imports configuration; service-owned ports keep the dependency "
                + "pointing from the composition root toward the service layer")
        void noServiceImportsConfiguration() {
            final List<String> configurationEdges = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.service"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.config"))
                    .map(Edge::describe)
                    .toList();

            assertThat(configurationEdges)
                    .as("MenuOptionSource and the other neutral contracts are owned below "
                            + "configuration, so service code never reaches up to its wiring")
                    .isEmpty();
        }

        @Test
        @DisplayName("configuration imports no API type; transport-owned records and controllers stay "
                + "at the boundary")
        void noConfigurationClassImportsTheApiBoundary() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.config"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.api"))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders)
                    .as("shared routes, refusal rendering and schema rosters are supplied through "
                            + "base-layer contracts rather than controller or DTO imports")
                    .isEmpty();
        }

        @Test
        @DisplayName("no service imports a transport record at all, so no service takes or returns an "
                + "API type and there is no enrolment that could make one legal")
        void noServiceImportsATransportRecord() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.service"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.api"))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders)
                    .as("a service that needs a carried value declares a type it owns and lets an "
                            + "adapter in the API layer convert, which is what ConversationState and "
                            + "the per-service command and outcome records are for")
                    .isEmpty();
        }

        @Test
        @DisplayName("the count of upward service-to-transport edges is zero, stated as a number so a "
                + "reader sees the claim and a failure names every edge that broke it")
        void theUpwardEdgeCountIsZero() {
            // The successor to a test that used to compare the declared edges against a licence table.
            // Twenty-eight of these existed when review reported the finding; the assertion is now that
            // there are none, which is a claim a table cannot express and an edit to a table cannot
            // weaken.
            final List<String> upward = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.service"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.api"))
                    .map(Edge::describe)
                    .toList();

            // Asserted first, because "no service names a transport record" is worth nothing if the walk
            // never reached the service package. This proves it did: services declare internal edges in
            // quantity, and every one of them points somewhere other than the API layer.
            final long serviceEdges = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.service"))
                    .count();
            assertThat(serviceEdges)
                    .as("the walk must really reach the service package, or the absence below is "
                            + "vacuous")
                    .isGreaterThan(MINIMUM_SERVICE_EDGES);
            assertThat(upward)
                    .as("no upward service-to-transport edge exists anywhere in the module; each of "
                            + "the twenty-eight review reported was closed by giving the service a "
                            + "type it owns, never by licensing the edge")
                    .isEmpty();
            assertThat(upward).hasSize(UPWARD_EDGE_BUDGET);
        }


        @Test
        @DisplayName("each of the twelve services whose edges were closed declares no upward edge at "
                + "all, so the closure cannot be undone by re-adding an enrolment")
        void theClosedServicesDeclareNoUpwardEdge() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> SERVICES_WITH_CLOSED_UPWARD_EDGES.contains(edge.fromType()))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.api"))
                    .map(Edge::describe)
                    .sorted()
                    .toList();

            assertThat(offenders)
                    .as("each of these services owns its carriers; a value that has to reach a response "
                            + "crosses through an API-layer adapter and never the other way")
                    .isEmpty();
        }

        @Test
        @DisplayName("the closed list names every service that has an upward edge to lose, so it "
                + "cannot be narrowed to a subset that happens to pass")
        void theClosedListNamesEveryServiceReviewReported() {
            // The list is only as strong as its membership. Review named twelve service classes between
            // them - the ten screen services plus the two the old licence table argued were design
            // choices - and every one must be enrolled, or the test above would assert the absence of an
            // edge for a smaller set than the finding covered while still passing.
            assertThat(SERVICES_WITH_CLOSED_UPWARD_EDGES)
                    .as("the enrolment must cover every service the finding named, and each entry must "
                            + "be distinct")
                    .hasSize(CLOSED_SERVICE_COUNT)
                    .doesNotHaveDuplicates()
                    .allSatisfy(type -> assertThat(type)
                            .as("only a service class can have a service-to-transport edge closed")
                            .startsWith("com.carddemo.service."));
        }

        @Test
        @DisplayName("every one of the twelve is a production class the walk really reaches, so the "
                + "list above cannot silently name something that no longer exists")
        void theClosedServicesAllExist() {
            final List<String> scannedTypes = internalEdges().stream()
                    .map(Edge::fromType)
                    .distinct()
                    .toList();

            assertThat(scannedTypes)
                    .as("a renamed or deleted service must be noticed here rather than turning the "
                            + "closure test into one that asserts nothing")
                    .containsAll(SERVICES_WITH_CLOSED_UPWARD_EDGES);
        }

        @Test
        @DisplayName("no batch job or step imports a transport record, because a batch tier has no "
                + "screen to answer")
        void noBatchClassImportsATransportRecord() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().startsWith("com.carddemo.batch"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.api"))
                    .map(Edge::describe)
                    .toList();

            assertThat(offenders).isEmpty();
        }

        /**
         * A step processor, reader or writer reads no wiring, so the prohibition this test was
         * originally written as still holds over {@code com.carddemo.batch.step} unchanged.
         *
         * <p><strong>Why the sibling job package is no longer covered by it.</strong> When this test
         * was first written the batch tier held only step components, and for those the claim "a batch
         * tier has no wiring to read" is exactly right. The job tier that has since arrived in
         * {@code com.carddemo.batch} is made of configuration classes: a job configuration exists in
         * order to compose beans, and the two things it composes with are the shared batch
         * infrastructure - the boundary listener, the parameter incrementer and the one named
         * condition-code ceiling, all published by {@code config/BatchConfig} for job configurations
         * to use - and the settings records that carry a destination it must not hardcode. Denying it
         * those two would force it either to restate a ceiling that already exists, which is how a
         * second and looser gate form would appear beside the one the plan freezes, or to embed a
         * bucket name, which the no-hardcoded-configuration standard forbids outright.
         *
         * <p>The licence is therefore granted to the job package and withheld from the step package,
         * rather than granted to the whole tier by prefix, so a step component reaching for wiring
         * still fails here even though a job configuration is allowed to read it.
         */
        @Test
        @DisplayName("no batch step component imports a configuration class, because a step reads "
                + "records and not wiring - only the job configurations above it read wiring")
        void noBatchStepComponentImportsAConfigurationClass() {
            final List<String> offenders = internalEdges().stream()
                    .filter(edge -> edge.fromPackage().equals("com.carddemo.batch.step"))
                    .filter(edge -> edge.toPackage().startsWith("com.carddemo.config"))
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
                if (edge.fromPackage().equals(edge.toPackage())) {
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
                    .as("strict downward dependencies admit no reciprocal package pair; a cycle "
                            + "would mean at least one side had acquired an edge the direction table "
                            + "does not permit")
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
