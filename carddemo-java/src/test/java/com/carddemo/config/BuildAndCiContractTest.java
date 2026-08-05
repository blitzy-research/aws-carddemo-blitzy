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
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the build and workflow guarantees that are otherwise only executable configuration.
 *
 * <p>The compiler cannot see whether dependency-check excludes test scope, whether a hosted runner
 * floats, whether a Docker build receives the source epoch, or whether CI ever starts the monitoring
 * stack. These assertions make each review finding a build-breaking contract instead of a comment
 * that can drift independently of the commands it describes.</p>
 */
@DisplayName("build and CI contract: full-scope scanning, reproducibility and runtime gates")
final class BuildAndCiContractTest {

    private static final Path POM_PATH = Path.of("pom.xml");
    private static final Path DOCKERFILE_PATH = Path.of("Dockerfile");
    private static final Path COMPOSE_PATH = Path.of("docker-compose.yml");
    private static final Path README_PATH = Path.of("README.md");
    private static final Path GITIGNORE_PATH = Path.of(".gitignore");
    private static final Path GITATTRIBUTES_PATH = Path.of(".gitattributes");
    private static final Path MAIN_PACKAGE_ROOT =
            Path.of("src", "main", "java", "com", "carddemo");
    private static final Path WORKFLOW_PATH =
            Path.of("../.github/workflows/carddemo-java-ci.yml");

    /** Creates the test class. */
    BuildAndCiContractTest() {
    }

    @Test
    @DisplayName("dependency-check scans test scope through a managed non-shaded Docker transport")
    void dependencyCheckCoversTheCompleteBuildGraph() throws IOException {
        final String pom = read(POM_PATH);

        assertThat(pom)
                .contains("<dependency-check.skipTestScope>false"
                        + "</dependency-check.skipTestScope>")
                .contains("<artifactId>docker-java-transport-httpclient5</artifactId>")
                .contains("<artifactId>docker-java-transport-zerodep</artifactId>")
                .contains("<artifactId>httpcore5</artifactId>")
                .contains("<artifactId>httpcore5-h2</artifactId>")
                .contains("<httpcomponents-core5.version>5.4.3"
                        + "</httpcomponents-core5.version>")
                .doesNotContain("<dependency-check.skipTestScope>true"
                        + "</dependency-check.skipTestScope>")
                .doesNotContain("Test scoped artifacts are OUTSIDE the scan");
    }

    @Test
    @DisplayName("Maven, build-info, Docker and Compose share one deterministic source epoch")
    void everyArtifactProducerUsesTheSourceEpoch() throws IOException {
        assertThat(read(POM_PATH))
                .contains("<source.date.epoch>1658188800</source.date.epoch>")
                .contains("<project.build.outputTimestamp>${source.date.epoch}"
                        + "</project.build.outputTimestamp>")
                .contains("<time>${project.build.outputTimestamp}</time>");
        assertThat(read(DOCKERFILE_PATH))
                .contains("ARG SOURCE_DATE_EPOCH=1658188800")
                .contains("-Dsource.date.epoch=\"$SOURCE_DATE_EPOCH\"")
                .contains("SOURCE_DATE_EPOCH did not reach the packaged build time");
        assertThat(read(COMPOSE_PATH))
                .contains("SOURCE_DATE_EPOCH: \"${SOURCE_DATE_EPOCH:-1658188800}\"");
    }

    @Test
    @DisplayName("the workflow pins its runner, exact JDK build, scanner and Docker image inputs")
    void workflowInputsAreImmutable() throws IOException {
        final String workflow = read(WORKFLOW_PATH);

        assertThat(workflow)
                .contains("runs-on: ubuntu-24.04")
                .contains("java-version: '25.0.3+9'")
                .contains("ghcr.io/aquasecurity/trivy:0.62.1@sha256:"
                        + "fc10faf341a1d8fa8256c5ff1a6662ef74dd38b65034c8ce42346cf958a02d5d")
                .contains("postgres:16.14-bookworm@sha256:"
                        + "92620daddcd947f8d5ab5ba66e848702fe443d87fed30c4cea8e389fd78dfc55")
                .contains("localstack/localstack:4.14.0@sha256:"
                        + "3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a")
                .doesNotContain("runs-on: ubuntu-latest")
                .doesNotContain("java-version: '25'\n");
    }

    @Test
    @DisplayName("CI builds and starts the stack, then verifies every monitoring boundary")
    void workflowExercisesTheContainerAndMonitoringRuntime() throws IOException {
        final String workflow = read(WORKFLOW_PATH);

        assertThat(workflow)
                .contains("docker build --check .")
                .contains("--build-arg APP_VERSION=\"${APP_VERSION}\"")
                .contains("--build-arg SOURCE_REVISION=\"${SOURCE_REVISION}\"")
                .contains("--build-arg SOURCE_DATE_EPOCH=\"${SOURCE_DATE_EPOCH}\"")
                .contains("docker compose config --quiet")
                .contains("docker compose up -d --no-build")
                .contains("docker compose down -v --remove-orphans")
                .contains("http://127.0.0.1:8080/actuator/health")
                .contains("/api/datasources/uid/carddemo-prometheus")
                .contains("/api/dashboards/uid/carddemo-overview")
                .contains("/api/datasources/uid/carddemo-jaeger")
                .contains("http://127.0.0.1:9090/api/v1/targets")
                .contains("query=up{job=\"carddemo-app\"}")
                .contains("http://127.0.0.1:16686/api/services")
                .contains("application_image_id=\"$(docker image inspect")
                .contains("docker compose config --images")
                .contains("Upload container vulnerability scan reports");
    }

    @Test
    @DisplayName("CI proves a clean rebuild is byte-identical before publishing the jar")
    void workflowExecutesTheReproducibilityGate() throws IOException {
        final String workflow = read(WORKFLOW_PATH);

        assertThat(workflow)
                .contains("- name: Reproducible artifact gate")
                .contains("-Dsource.date.epoch=\"${SOURCE_DATE_EPOCH}\"")
                .contains("cmp \"${RUNNER_TEMP}/carddemo-java-verified.jar\" \"${rebuilt_jar}\"")
                .contains("The clean rebuild is byte-identical to the verified artifact.");
    }

    @Test
    @DisplayName("workflow commentary describes the actual linear verdict model")
    void workflowCommentaryMatchesItsControlFlow() throws IOException {
        final String workflow = read(WORKFLOW_PATH);

        assertThat(workflow)
                .contains("WHY THE GATES ARE LINEAR")
                .contains("Each gate fails the job at the point of failure")
                .doesNotContain("WHY THE GATES ARE INVOKED SEPARATELY")
                .doesNotContain("the final step reads both results back")
                .doesNotContain("Four of the five steps are allowed to continue");
    }

    @Test
    @DisplayName("repository hygiene excludes recursive secret YAML and preserves fixed-width bytes")
    void repositoryHygieneProtectsSecretsAndFixtures() throws IOException {
        assertThat(read(GITIGNORE_PATH))
                .contains("secrets.yml")
                .contains("secrets.yaml")
                .contains("**/secrets.y*ml")
                .contains("**/*secret*.yml")
                .contains("**/*secret*.yaml")
                .doesNotContain("!.env.example");
        assertThat(read(GITATTRIBUTES_PATH))
                .contains("src/test/resources/fixtures/** -text -whitespace");
    }

    @Test
    @DisplayName("the documented package count is derived from the production source tree")
    void readmePackageCountMatchesTheSourceTree() throws IOException {
        final long subpackageCount;
        try (Stream<Path> tree = Files.walk(MAIN_PACKAGE_ROOT)) {
            subpackageCount = tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::getParent)
                    .distinct()
                    .filter(path -> !path.equals(MAIN_PACKAGE_ROOT))
                    .count();
        }

        assertThat(subpackageCount).isEqualTo(12L);
        assertThat(read(README_PATH))
                .contains("Twelve subpackages under `com.carddemo`")
                .contains("twelve-subpackage map above")
                .doesNotContain("Eleven packages under `com.carddemo`")
                .doesNotContain("eleven-package map above");
    }

    @Test
    @DisplayName("the operator manual names the current queue, staging, identity and scan contracts")
    void readmeMatchesTheDeliveredOperationalContracts() throws IOException {
        assertThat(read(README_PATH))
                .contains("Five further variables are non-secret")
                .contains("the required value is `JOBS.fifo`")
                .contains("729 unit, 134 integration and 33 end-to-end tests")
                .contains("add up to 896 rather than 888")
                .contains("`carddemo.batch.staging-directory`")
                .contains("`CARDDEMO_BATCH_STAGING_DIRECTORY`")
                .contains("`carddemo.batch.combine-transactions.staging-directory`")
                .contains("named `batch-staging` volume")
                .contains("`SOURCE_DATE_EPOCH`")
                .contains("production **and test** scope")
                .contains("Testcontainers'")
                .contains("shaded zerodep transport is excluded")
                .contains("checks and builds the Dockerfile")
                .contains("strict JSON, whose grammar has no comment syntax")
                .contains("health`, `info`, `metrics` and `prometheus")
                .doesNotContain("carddemo-jobs.fifo");
    }

    private static String read(final Path path) throws IOException {
        assertThat(path).isRegularFile();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}