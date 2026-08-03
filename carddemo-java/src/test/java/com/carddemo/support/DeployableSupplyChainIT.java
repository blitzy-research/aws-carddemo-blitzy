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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the deployable artifact contains only the graph the vulnerability gate scans.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The vulnerability gate scans the compile and runtime graph and not the test graph, which is the
 * plugin's own default and is stated explicitly in {@code pom.xml}. That narrowing is only honest if
 * the unscanned graph genuinely does not reach the product, and nothing in a Maven build proves that
 * on its own - scope is a declaration, and a declaration is exactly the kind of thing that drifts. A
 * single dependency whose scope was omitted, or a plugin configured to bundle more than it should,
 * would put an unscanned artifact into the shipped jar and the gate would never notice.</p>
 *
 * <p>This test is the check. It opens the repackaged jar this build produces and fails if any
 * test-scoped library appears among the libraries bundled inside it. It runs in the integration tier,
 * which executes after the repackaging step, so the artifact it inspects is the one that would be
 * deployed rather than a reconstruction of it.</p>
 *
 * <h2>Why the container transport is named specifically</h2>
 *
 * <p>One test-scoped artifact is the reason the scope boundary matters at all: the container-testing
 * transport embeds a relocated copy of an HTTP core library carrying two findings at 7.5 HIGH. Those
 * findings are real rather than false matches - the vulnerable classes are physically present in the
 * shaded artifact - and they are unfixable in place, because the copy is relocated beyond the reach of
 * any managed coordinate and the transport is the only one the container-testing library will
 * construct. It is named in its own assertion rather than left to the general sweep so that a failure
 * reports the artifact whose absence the gate's scope claim actually depends on.</p>
 *
 * <h2>What this test does not claim</h2>
 *
 * <p>It does not claim the test graph is free of vulnerabilities; it claims the test graph is not part
 * of the product. The residual exposure - test-scoped code executing on developer machines and hosted
 * runners - is recorded in {@code pom.xml} and in the decision log rather than closed here.</p>
 *
 * <p>Provenance: the deployable artifact this inspects replaces the load modules the legacy
 * compile-and-link procedures produced, at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("the deployable artifact carries only the graph the vulnerability gate scans")
class DeployableSupplyChainIT {

    /** Directory the build writes its artifacts to. */
    private static final String BUILD_DIRECTORY = "target";

    /** Where a Spring Boot repackaged jar keeps the libraries it bundles. */
    private static final String BUNDLED_LIBRARY_PREFIX = "BOOT-INF/lib/";

    /**
     * Test-scoped artifact stems that must never appear inside the deployable jar.
     *
     * <p>Stems rather than exact file names, so a version bump in any of them cannot silently retire
     * the assertion. The first entry is the one the vulnerability gate's scope claim depends on
     * directly; the rest are the test libraries that pull it in or sit beside it, and their presence
     * would be the same class of defect.</p>
     */
    private static final List<String> ARTIFACTS_THAT_MUST_NOT_SHIP = List.of(
            "docker-java-transport-zerodep",
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
        @DisplayName("the container-testing transport whose findings the gate's scope excludes is "
                + "absent, which is what makes that exclusion honest")
        void theContainerTestingTransportIsAbsent() throws IOException {
            assertThat(bundledLibraries())
                    .as("the vulnerability gate scans the compile and runtime graph only; that claim "
                            + "holds exactly as long as this artifact does not ship")
                    .noneMatch(name -> name.startsWith("docker-java-transport-zerodep"));
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
                    .as("a test-scoped artifact inside the deployable jar means the vulnerability "
                            + "gate's scope no longer describes what ships")
                    .isEmpty();
        }
    }
}
