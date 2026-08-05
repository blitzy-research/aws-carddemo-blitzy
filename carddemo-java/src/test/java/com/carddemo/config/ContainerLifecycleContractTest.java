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
package com.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.Yaml;

/**
 * Cross-file contract for container termination and optional trace collection.
 *
 * <p>The application, image and Compose file are interpreted by three different runtimes. A Java
 * compilation cannot detect that Compose will send {@code SIGKILL} before Spring's drain interval
 * ends, or that Compose waits for a collector the application itself treats as optional. These tests
 * therefore parse the shipped documents and assert the relationships rather than checking isolated
 * literals.
 *
 * <p>Decision-log entries DL-157 and DL-158 record why the shutdown margin is strict and why Jaeger
 * remains in the default stack without being an application dependency.
 */
@DisplayName("Container lifecycle: the drain is protected and tracing remains optional")
final class ContainerLifecycleContractTest {

    /** Compose definition shipped with the standalone module. */
    private static final Path COMPOSE = Path.of("docker-compose.yml");

    /** Shared Spring configuration carrying the graceful-shutdown phase timeout. */
    private static final Path APPLICATION =
            Path.of("src", "main", "resources", "application.yml");

    /** Runtime-image definition whose exec entrypoint receives the termination signal. */
    private static final Path DOCKERFILE = Path.of("Dockerfile");

    /** Module documentation describing the same operator-visible lifecycle contract. */
    private static final Path README = Path.of("README.md");

    /** Creates the specification. */
    ContainerLifecycleContractTest() {
    }

    @Test
    @DisplayName("Compose waits longer than Spring's complete shutdown phase")
    void composeGraceExceedsTheApplicationPhaseTimeout() throws IOException {
        final Duration applicationTimeout = durationAt(
                APPLICATION, "spring", "lifecycle", "timeout-per-shutdown-phase");
        final Duration composeGrace =
                durationAt(COMPOSE, "services", "app", "stop_grace_period");

        assertThat(applicationTimeout).isEqualTo(Duration.ofSeconds(30));
        assertThat(composeGrace).isEqualTo(Duration.ofSeconds(35));
        assertThat(composeGrace)
                .as("the orchestrator deadline must follow, not coincide with, Spring's deadline")
                .isGreaterThan(applicationTimeout);
    }

    @Test
    @DisplayName("the application waits only for the database and the provisioned AWS emulator")
    void applicationDependsOnlyOnRequiredServices() throws IOException {
        final Map<?, ?> dependencies =
                mappingAt(document(COMPOSE), "services", "app", "depends_on");

        assertThat(keyNamesOf(dependencies))
                .containsExactlyInAnyOrder("postgres", "localstack")
                .doesNotContain("jaeger");
        assertThat(valueAt(dependencies, "postgres", "condition"))
                .isEqualTo("service_healthy");
        assertThat(valueAt(dependencies, "localstack", "condition"))
                .isEqualTo("service_healthy");
    }

    @Test
    @DisplayName("Jaeger stays addressable when present without becoming a startup gate")
    void collectorRemainsConfiguredButOptional() throws IOException {
        final Map<?, ?> compose = document(COMPOSE);
        final Map<?, ?> services = mappingAt(compose, "services");
        final Map<?, ?> dependencies = mappingAt(services, "app", "depends_on");

        assertThat(keyNamesOf(services)).contains("jaeger");
        assertThat(keyNamesOf(dependencies)).doesNotContain("jaeger");
        assertThat(valueAt(services, "app", "environment",
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"))
                .isEqualTo("http://jaeger:4318/v1/traces");
    }

    @Test
    @DisplayName("the image documents the exact shutdown margin its exec entrypoint relies on")
    void dockerfileDocumentsTheExactTerminationContract() throws IOException {
        assertThat(read(DOCKERFILE))
                .contains("configured to drain in-flight work for up to")
                .contains("30 s")
                .contains("shipped Compose stack grants 35 s before SIGKILL")
                .contains("greater-than relationship")
                .contains("ENTRYPOINT [\"java\"");
    }

    @Test
    @DisplayName("the operator guide describes both the margin and the optional collector")
    void readmeDocumentsTheLifecycleContract() throws IOException {
        final String normalizedGuide = read(README).replaceAll("\\s+", " ");

        assertThat(normalizedGuide)
                .contains("Spring allows 30 seconds per shutdown phase")
                .contains("Compose grants 35 seconds before SIGKILL")
                .contains("Those are the application's only Compose start-up dependencies")
                .contains("no dependency edge waits for it");
    }

    /**
     * Reads and parses one YAML document.
     *
     * @param path document path relative to the module root
     * @return the document root
     * @throws IOException if the file cannot be read
     */
    private static Map<?, ?> document(final Path path) throws IOException {
        final Object parsed = new Yaml().load(read(path));
        if (parsed instanceof Map<?, ?> document) {
            return document;
        }
        throw new AssertionError(path + " must contain a YAML mapping");
    }

    /**
     * Resolves a nested mapping without an unchecked cast.
     *
     * @param root mapping to traverse
     * @param keys key path
     * @return the mapping at the path
     */
    private static Map<?, ?> mappingAt(final Map<?, ?> root, final String... keys) {
        final Object value = valueAt(root, keys);
        if (value instanceof Map<?, ?> mapping) {
            return mapping;
        }
        throw new AssertionError(String.join(".", keys) + " must be a mapping");
    }

    /**
     * Resolves one nested YAML value.
     *
     * @param root mapping to traverse
     * @param keys key path
     * @return the resolved value
     */
    private static Object valueAt(final Map<?, ?> root, final String... keys) {
        Object current = root;
        for (final String key : keys) {
            if (!(current instanceof Map<?, ?> mapping) || !mapping.containsKey(key)) {
                throw new AssertionError("Missing YAML path " + String.join(".", keys));
            }
            current = mapping.get(key);
        }
        return current;
    }

    /**
     * Converts wildcard YAML map keys into their textual names without an unchecked cast.
     *
     * @param mapping mapping whose keys are named
     * @return key names in document order
     */
    private static List<String> keyNamesOf(final Map<?, ?> mapping) {
        return mapping.keySet().stream().map(String::valueOf).toList();
    }

    /**
     * Parses a Spring-style duration from one YAML path.
     *
     * @param path document path
     * @param keys YAML key path
     * @return parsed duration
     * @throws IOException if the document cannot be read
     */
    private static Duration durationAt(final Path path, final String... keys) throws IOException {
        return DurationStyle.detectAndParse(String.valueOf(valueAt(document(path), keys)));
    }

    /**
     * Reads one shipped text file.
     *
     * @param path path relative to the module root
     * @return complete file contents
     * @throws IOException if the file cannot be read
     */
    private static String read(final Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
