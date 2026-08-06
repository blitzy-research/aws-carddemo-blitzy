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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * Executes a bounded external sort under the delivered runtime policy rather than under the build's.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>The sorter's own unit tests run in the build's JVM, and on a normal build host that JVM is
 * {@code root}. A {@code root} process carries {@code CAP_DAC_OVERRIDE} and therefore resolves names
 * inside a directory that grants it no search permission, so a work area created {@code rw-------}
 * behaves perfectly there and fails for every account that is not {@code root}. That is precisely what
 * happened: four batch jobs could not spill a single sort run in the delivered image, whose runtime
 * account is uid 10001, while the whole unit suite stayed green.
 *
 * <p>Asserting the permission bits closes half of that gap and {@code ExternalStringSorterTest} does so.
 * This test closes the other half by <em>executing</em> the sort as an unprivileged account, on an
 * immutable root filesystem, with a temporary filesystem as the only writable location - the three
 * runtime facts the image and the Compose stack impose. It is deliberately a black-box run: the
 * container receives the compiled classes and a probe entry point, and the assertion reads what the
 * container printed.
 *
 * <h2>Why this image and these settings</h2>
 *
 * <p>The base image is the same digest-pinned runtime layer the module's {@code Dockerfile} ends on, so
 * the JVM, the libc and the filesystem semantics under test are the delivered ones rather than an
 * approximation. The account, the immutable root filesystem and the temporary-filesystem options mirror
 * the {@code app} service of the shipped Compose stack, including the ownership the temporary filesystem
 * is mounted with.
 *
 * <p>The compiled classes are supplied as <strong>read-only bind mounts</strong> rather than copied in,
 * which is the same way the Compose stack supplies its own configuration. A copy is not an option here:
 * the engine refuses to write into the filesystem of a container whose root is already marked read-only,
 * so the immutability under test and a copy are mutually exclusive.
 */
@DisplayName("A bounded external sort runs as the unprivileged container account")
final class ExternalSortNonRootRuntimeIT {

    /**
     * Runtime layer the module's own image ends on, pinned by digest.
     *
     * <p>Kept identical to the {@code runtime} stage of {@code Dockerfile}; a drift between the two would
     * mean this test certifies a runtime the module does not ship.
     */
    private static final String RUNTIME_IMAGE =
            "eclipse-temurin:25.0.3_9-jre-noble@sha256:"
                    + "0b32bb744e7ef5df10b4ca1a6fd24a34cb6298efe435d7abdef1b6a4e48e940f";

    /** Account the delivered image runs as: unprivileged, and the whole point of this test. */
    private static final String RUNTIME_ACCOUNT = "10001:10001";

    /** The only writable location the delivered container has, mounted exactly as Compose mounts it. */
    private static final Map<String, String> RUNTIME_TMPFS = Map.of(
            "/tmp", "rw,noexec,nosuid,nodev,size=64m,uid=10001,gid=10001");

    /** Compiled module classes, which carry the sorter. */
    private static final Path MODULE_CLASSES = Path.of("target", "classes");

    /** Compiled test classes, which carry the probe entry point. */
    private static final Path PROBE_CLASSES = Path.of("target", "test-classes");

    /** Where the module classes are placed inside the container. */
    private static final String CONTAINER_MODULE_CLASSES = "/app/classes";

    /** Where the probe classes are placed inside the container. */
    private static final String CONTAINER_PROBE_CLASSES = "/app/test-classes";

    /** Token the container prints after the run, so the exit status is visible in the log. */
    private static final String EXIT_MARKER = "PROBE-EXIT=";

    /** Creates the specification. */
    ExternalSortNonRootRuntimeIT() {
    }

    @Test
    @DisplayName("spills, merges and emits its runs without an access denial, as uid 10001 on a "
            + "read-only root filesystem")
    void theSorterSpillsAsTheUnprivilegedRuntimeAccount() {
        assertThat(MODULE_CLASSES)
                .as("the compiled module classes are the subject of this run; build before running it")
                .isDirectory();
        assertThat(PROBE_CLASSES)
                .as("the compiled probe carries the run itself; build before running it")
                .isDirectory();
        assertThat(MODULE_CLASSES.resolve("com/carddemo/util/ExternalStringSorter.class")).exists();

        final String log = runProbeInContainer();

        assertThat(log)
                .as("the sort must complete as the unprivileged account; full container output:%n%s",
                        log)
                .contains(NonRootSortProbe.SUCCESS_TOKEN)
                .doesNotContain(NonRootSortProbe.FAILURE_TOKEN)
                .doesNotContain("AccessDeniedException")
                .contains(EXIT_MARKER + "0");
        assertThat(log)
                .as("the work area the unprivileged account observes must carry the search bit, and it "
                        + "must belong to that account; full container output:%n%s", log)
                .contains("work-area-permissions=rwx------")
                .contains("work-area-owner=10001");
    }

    /**
     * Runs the probe once inside a container that reproduces the delivered runtime policy.
     *
     * @return everything the container printed
     */
    private static String runProbeInContainer() {
        try (GenericContainer<?> runtime =
                new GenericContainer<>(DockerImageName.parse(RUNTIME_IMAGE))
                        .withFileSystemBind(absolutePathOf(MODULE_CLASSES),
                                CONTAINER_MODULE_CLASSES, BindMode.READ_ONLY)
                        .withFileSystemBind(absolutePathOf(PROBE_CLASSES),
                                CONTAINER_PROBE_CLASSES, BindMode.READ_ONLY)
                        .withCreateContainerCmdModifier(command -> {
                            command.withUser(RUNTIME_ACCOUNT);
                            Objects.requireNonNull(command.getHostConfig(),
                                            "the container must carry a host configuration")
                                    .withReadonlyRootfs(Boolean.TRUE);
                        })
                        .withTmpFs(RUNTIME_TMPFS)
                        .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
                        .withCommand("sh", "-c", probeCommand())) {
            runtime.start();
            return runtime.getLogs();
        }
    }

    /**
     * Renders one build-relative path as the absolute path the engine needs for a bind mount.
     *
     * @param path path relative to the module root the build runs from
     * @return the absolute, normalised path
     */
    private static String absolutePathOf(final Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * Builds the one-shot command: run the probe, then print its exit status.
     *
     * <p>The status is echoed rather than relied on as the container's own exit code so that a failing
     * run still produces a log the assertion can print, instead of a bare start-up failure.
     *
     * @return the shell command the container runs
     */
    private static String probeCommand() {
        return "java -cp " + CONTAINER_MODULE_CLASSES + ':' + CONTAINER_PROBE_CLASSES + ' '
                + NonRootSortProbe.class.getName() + "; echo \"" + EXIT_MARKER + "$?\"";
    }
}
