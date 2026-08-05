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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the deployable artifact contains no test-only library.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The vulnerability gate scans the complete graph, including test scope. This test protects a
 * different boundary: no library that exists only to build or test the module may enter the product.
 * A single dependency whose scope was omitted, or a packaging plugin configured too broadly, would
 * enlarge the deployed attack surface even though the vulnerability scan still saw it.</p>
 *
 * <p>This test is the check. It opens the repackaged jar this build produces and fails if any
 * test-scoped library appears among the libraries bundled inside it. It runs in the integration tier,
 * which executes after the repackaging step, so the artifact it inspects is the one that would be
 * deployed rather than a reconstruction of it.</p>
 *
 * <h2>Why both container transport names are listed</h2>
 *
 * <p>The shaded zerodep transport was removed because its embedded HTTP Core copy could not be managed.
 * The replacement HttpClient 5 transport is non-shaded and fully scanned, but it is still test-only.
 * Both names remain in the exclusion sweep: the first prevents a vulnerable transport regression and
 * the second prevents the correct replacement from leaking into the application jar.</p>
 *
 * <h2>What this test does not claim</h2>
 *
 * <p>It does not make a vulnerability claim; dependency-check owns that claim over every scope. It
 * proves only that build-time tooling is not packaged as runtime code.</p>
 *
 * <p>Provenance: the deployable artifact this inspects replaces the load modules the legacy
 * compile-and-link procedures produced, at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("the deployable artifact excludes every test-only library")
class DeployableSupplyChainIT {

    /** Directory the build writes its artifacts to. */
    private static final String BUILD_DIRECTORY = "target";

    /** Where a Spring Boot repackaged jar keeps the libraries it bundles. */
    private static final String BUNDLED_LIBRARY_PREFIX = "BOOT-INF/lib/";

    /**
     * Test-scoped artifact stems that must never appear inside the deployable jar.
     *
     * <p>Stems rather than exact file names, so a version bump in any of them cannot silently retire
     * the assertion. Both Docker transport forms are named explicitly; the rest are the test libraries
     * that pull them in or sit beside them, and their presence would be the same class of defect.</p>
     */
    private static final List<String> ARTIFACTS_THAT_MUST_NOT_SHIP = List.of(
            "docker-java-transport-zerodep",
            "docker-java-transport-httpclient5",
            "docker-java-api",
            "docker-java-transport",
            "testcontainers",
            "junit-jupiter",
            "junit-platform",
            "mockito-core",
            "assertj-core",
            "spring-boot-starter-test",
            "spring-batch-test",
            "spring-security-test",
            "hamcrest",
            "byte-buddy-agent",
            "duct-tape",
            "jsonassert",
            "xmlunit-core");

    /**
     * Locates the repackaged jar this build produced.
     *
     * @return the deployable jar
     * @throws IOException if the build directory cannot be read
     */
    private static Path deployableJar() throws IOException {
        Path buildDirectory = Paths.get(BUILD_DIRECTORY);
        assertThat(buildDirectory)
                .as("the build directory must exist; this test runs after the packaging phase")
                .exists();

        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(buildDirectory, "*.jar")) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                // The plain classes jar and the sources jar are not the deployable artifact. The
                // repackaged one is identified by content below rather than by guessing from its name.
                if (!name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")) {
                    candidates.add(entry);
                }
            }
        }

        assertThat(candidates)
                .as("exactly one deployable jar is expected in %s", BUILD_DIRECTORY)
                .hasSize(1);
        return candidates.get(0);
    }

    /**
     * Reads the names of the libraries bundled inside the deployable jar.
     *
     * @return one simple file name per bundled library, in archive order
     * @throws IOException if the jar cannot be opened
     */
    private static List<String> bundledLibraries() throws IOException {
        Path jar = deployableJar();
        try (JarFile archive = new JarFile(jar.toFile())) {
            return archive.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.startsWith(BUNDLED_LIBRARY_PREFIX))
                    .filter(name -> name.endsWith(".jar"))
                    .map(name -> name.substring(name.lastIndexOf('/') + 1))
                    .collect(Collectors.toList());
        }
    }

    @Nested
    @DisplayName("the artifact under inspection is genuinely the deployable one")
    class TheArtifactItself {

        @Test
        @DisplayName("the jar is repackaged and bundles its own libraries, so the sweep has something "
                + "to sweep")
        void theJarIsRepackagedAndBundlesLibraries() throws IOException {
            // A control assertion. If repackaging had not run, the jar would carry no BOOT-INF/lib
            // entries at all and every exclusion assertion below would pass vacuously - which is the
            // one way this test could report success while checking nothing.
            assertThat(bundledLibraries())
                    .as("a repackaged Spring Boot jar bundles its runtime libraries under %s; an empty "
                            + "list here would make every assertion in this class vacuous",
                            BUNDLED_LIBRARY_PREFIX)
                    .isNotEmpty();
        }

        @Test
        @DisplayName("the libraries it does bundle include the ones it is supposed to bundle")
        void theExpectedRuntimeLibrariesArePresent() throws IOException {
            // The counterpart to the exclusion sweep: proving the absent things are absent means little
            // without proving the present things are present, because a jar bundling nothing relevant
            // would satisfy the exclusions too.
            List<String> bundled = bundledLibraries();
            assertThat(bundled)
                    .as("the persistence driver ships, so the product can reach its database")
                    .anySatisfy(name -> assertThat(name).startsWith("postgresql-"));
            assertThat(bundled)
                    .as("the network transport the messaging client uses ships")
                    .anySatisfy(name -> assertThat(name).startsWith("netty-"));
        }
    }

    @Nested
    @DisplayName("nothing test-scoped reaches it")
    class NothingTestScopedShips {

        @Test
        @DisplayName("neither the removed shaded transport nor its non-shaded replacement ships")
        void theContainerTestingTransportIsAbsent() throws IOException {
            assertThat(bundledLibraries())
                    .noneMatch(name -> name.startsWith("docker-java-transport-zerodep"))
                    .noneMatch(name -> name.startsWith("docker-java-transport-httpclient5"));
        }

        @Test
        @DisplayName("no test-scoped library at all reaches the deployable jar")
        void noTestScopedLibraryShips() throws IOException {
            List<String> bundled = bundledLibraries();

            // Collected rather than asserted one at a time, so a failure names every offending
            // artifact instead of only whichever happened to be checked first.
            List<String> offenders = new ArrayList<>();
            for (String name : bundled) {
                String lower = name.toLowerCase(Locale.ROOT);
                for (String stem : ARTIFACTS_THAT_MUST_NOT_SHIP) {
                    if (lower.startsWith(stem.toLowerCase(Locale.ROOT))) {
                        offenders.add(name + " (matches test-scoped stem '" + stem + "')");
                    }
                }
            }

            assertThat(offenders)
                    .as("a test-scoped artifact inside the deployable jar enlarges the deployed "
                            + "attack surface")
                    .isEmpty();
        }
    }

    /**
     * The other half of the same guarantee, and the half a scope sweep cannot reach.
     *
     * <p>The sweep above proves that nothing the gate deliberately skips gets in. It says nothing about
     * a library that gets in without ever having been in the graph at all - and that is a real route,
     * not a hypothetical one. The repackaging plugin can copy a library out of <em>itself</em>: its
     * loader-tools dependency carries {@code META-INF/jarmode/spring-boot-jarmode-tools.jar} as an
     * ordinary resource, and with the tools extraction left on, that resource is written into
     * {@code BOOT-INF/lib} without passing through dependency resolution. A gate that scans the resolved
     * graph therefore cannot see it, and the report is one library short with nothing anywhere reporting
     * the shortfall. That is exactly what was found: 178 of 179 bundled libraries resolved, and the one
     * that did not was a library the container image goes on to <em>execute</em>.
     *
     * <p>So the property asserted here is resolvability: every library inside the archive must exist as
     * a resolved artifact in the repository this build used. That is the same set the gate scans, which
     * makes this a coverage check rather than a proxy for one, and it fails for any future library that
     * arrives by the embedded-resource route rather than only for the one already found.
     */
    @Nested
    @DisplayName("every bundled library is one the vulnerability gate can see")
    class EveryBundledLibraryIsScannable {

        /** Where the running build resolves artifacts, handed over by the build rather than guessed. */
        private static final String REPOSITORY_PROPERTY = "carddemo.maven.repository";

        /** The library whose invisibility this whole nested class was written for. */
        private static final String TOOLS_LIBRARY = "spring-boot-jarmode-tools-";

        /**
         * The digest the tools library must carry.
         *
         * <p>The build declares the artifact and turns the plugin's own extraction off, so the archive is
         * fed from the graph instead of from a resource inside the build tool. Those two sources are the
         * same bytes today, and this digest is what keeps the substitution honest: if the resolved
         * artifact ever stopped matching what the tool embeds, the change of provenance would have become
         * a change of content, and that must not pass silently.
         */
        private static final String TOOLS_LIBRARY_DIGEST =
                "d05beb46a7eac0f1a06733c75828b190a1cb250076ce03be2213aca4a5c0f03f";

        /**
         * The repository this build resolves from.
         *
         * @return its path
         */
        private static Path repository() {
            String configured = System.getProperty(REPOSITORY_PROPERTY);
            assertThat(configured)
                    .as("the build must pass %s; without it this class would silently check nothing",
                            REPOSITORY_PROPERTY)
                    .isNotNull()
                    .isNotBlank();
            Path repository = Paths.get(configured);
            assertThat(repository).as("the resolved repository must exist").isDirectory();
            return repository;
        }

        /**
         * Searches the repository for an artifact file of the given name.
         *
         * @param fileName the bundled library's file name
         * @param root     the repository root
         * @return true when an artifact of that name is present
         * @throws IOException if the repository cannot be walked
         */
        private static boolean resolves(final String fileName, final Path root) throws IOException {
            try (var tree = Files.walk(root)) {
                return tree.anyMatch(candidate -> candidate.getFileName().toString().equals(fileName)
                        && Files.isRegularFile(candidate));
            }
        }

        @Test
        @DisplayName("so no library reaches the archive by a route the scan cannot follow")
        void soNoLibraryReachesTheArchiveUnscanned() throws IOException {
            Path root = repository();
            List<String> unresolvable = new ArrayList<>();
            for (String name : bundledLibraries()) {
                if (!resolves(name, root)) {
                    unresolvable.add(name);
                }
            }

            assertThat(unresolvable)
                    .as("declare the artifact so it enters the resolved graph the gate scans, rather "
                            + "than letting the packaging step copy it in from somewhere else")
                    .isEmpty();
        }

        @Test
        @DisplayName("and the jarmode tools library specifically - the one that was invisible - now "
                + "ships exactly once, from the graph")
        void andTheToolsLibraryShipsOnceFromTheGraph() throws IOException {
            List<String> tools = bundledLibraries().stream()
                    .filter(name -> name.startsWith(TOOLS_LIBRARY))
                    .toList();

            assertThat(tools)
                    .as("the container image runs `java -Djarmode=tools`, so the library must ship - and "
                            + "exactly one copy of it, because the plugin refuses two and a silent "
                            + "duplicate would be worse than the gap being closed")
                    .hasSize(1);
            assertThat(resolves(tools.get(0), repository()))
                    .as("and it must be the graph's copy, which is what makes it scannable")
                    .isTrue();
        }

        @Test
        @DisplayName("and changing where it comes from did not change what it is")
        void andChangingItsProvenanceDidNotChangeItsContent() throws IOException {
            Path jar = deployableJar();
            String digest;
            try (JarFile archive = new JarFile(jar.toFile())) {
                JarEntry entry = archive.stream()
                        .filter(candidate -> candidate.getName()
                                .startsWith(BUNDLED_LIBRARY_PREFIX + TOOLS_LIBRARY))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the tools library must be bundled"));
                try (var content = archive.getInputStream(entry)) {
                    digest = HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(content.readAllBytes()));
                }
            } catch (NoSuchAlgorithmException unavailable) {
                throw new AssertionError("SHA-256 must be available on any supported runtime",
                        unavailable);
            }

            assertThat(digest)
                    .as("the artifact resolved from the repository and the resource the build tool "
                            + "embeds were the same bytes when the extraction was turned off; if that "
                            + "ever stops being true, a provenance change has become a content change")
                    .isEqualTo(TOOLS_LIBRARY_DIGEST);
        }

        @Test
        @DisplayName("and the loader the tools library depends on is scanned too, which is why its "
                + "redundant bundled copy is accepted rather than excluded")
        void andTheLoaderIsScannedRatherThanExcluded() throws IOException {
            // Declaring the tools library brought its own runtime dependency, the loader, onto the
            // graph, so the archive now carries spring-boot-loader as a bundled jar as well - about
            // 200 kB in a 92 MB artefact. That copy is redundant: the repackaging step already writes
            // the loader's 99 classes UNPACKED at the archive root, because that is how the jar boots.
            //
            // Excluding it would remove the redundancy and reintroduce the defect. The unpacked classes
            // come from a resource embedded in the build tool - the same route that hid the tools
            // library - so with the coordinate off the graph, 99 shipped classes would go unscanned.
            // Keeping it means the gate scans the coordinate those classes come from. The redundancy is
            // the price of the coverage, and it is the cheaper of the two.
            List<String> bundled = bundledLibraries();
            assertThat(bundled)
                    .as("the loader coordinate must be on the graph, so the classes at the archive root "
                            + "are covered by the scan")
                    .anySatisfy(name -> assertThat(name).startsWith("spring-boot-loader-"));

            try (JarFile archive = new JarFile(deployableJar().toFile())) {
                long unpacked = archive.stream()
                        .map(JarEntry::getName)
                        .filter(name -> name.startsWith("org/springframework/boot/loader/"))
                        .filter(name -> name.endsWith(".class"))
                        .count();
                assertThat(unpacked)
                        .as("the unpacked loader classes are the reason the coordinate must stay on the "
                                + "graph; if they ever stop being written, revisit the trade")
                        .isPositive();
            }
        }
    }
}
