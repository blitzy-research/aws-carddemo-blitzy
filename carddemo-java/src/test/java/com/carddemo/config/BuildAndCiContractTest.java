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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private static final Path SUPPRESSIONS_PATH = Path.of("owasp-suppressions.xml");
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
    @DisplayName("every suppressed finding is scoped, evidenced and self-expiring, and the gate keeps "
            + "its threshold")
    void suppressedFindingsStayScopedAndSelfExpiring() throws IOException {
        final String pom = read(POM_PATH);

        // The three settings that decide how much the gate can be talked out of. A suppression file
        // is only defensible while all three hold, so they are asserted together with it rather than
        // somewhere else where one could be relaxed without the other being noticed.
        assertThat(pom)
                .contains("<dependency-check.failBuildOnCVSS>7.0"
                        + "</dependency-check.failBuildOnCVSS>")
                .contains("<dependency-check.skip>false</dependency-check.skip>")
                .contains("<suppressionFile>${project.basedir}/owasp-suppressions.xml"
                        + "</suppressionFile>")
                .contains("<failBuildOnUnusedSuppressionRule>true"
                        + "</failBuildOnUnusedSuppressionRule>");

        final String suppressions = read(SUPPRESSIONS_PATH);

        // Scoping is asserted against the declarations alone. That file documents the rules it holds
        // itself to, and doing so requires naming the element types it forbids, so counting tokens
        // across the whole text would count the prohibition as an instance of the thing prohibited.
        final String declarations = withoutXmlComments(suppressions);

        // Every rule names an artifact and an identifier. A bare identifier with no artifact scope, a
        // wildcard platform record, or a coordinate regex broad enough to absorb an unexamined future
        // finding would each turn one determination into standing permission.
        final int rules = countOccurrences(declarations, "<suppress>");
        assertThat(rules)
                .as("a suppression file that grows without review is the failure mode this test "
                        + "exists to catch; every rule here is accounted for in DL-159")
                .isEqualTo(1);
        assertThat(countOccurrences(declarations, "<packageUrl"))
                .as("each rule is scoped to named artifacts, so the count of package-URL scopes "
                        + "matches the count of rules")
                .isEqualTo(rules);
        assertThat(countOccurrences(declarations, "<cve>"))
                .as("each rule names exactly one identifier")
                .isEqualTo(rules);
        assertThat(declarations)
                .doesNotContain("<cpe>")
                .doesNotContain("<gav")
                .doesNotContain("<vulnerabilityName");

        // Evidence. The one carried finding is the Tomcat examples-application match, and the rule
        // covers all three embedded jars because the product CPE migrated between them between
        // scans. Both halves of the justification - nothing published to upgrade to, and nothing
        // vulnerable present - have to stay written down beside the rule.
        assertThat(suppressions)
                .contains("<cve>CVE-2026-66299</cve>")
                .contains("^pkg:maven/org\\.apache\\.tomcat\\.embed/"
                        + "tomcat-embed-(core|websocket|el)@.*$")
                .contains("10.1.58")
                .contains("webapps")
                .contains("../docs/decision-log.md DL-159");

        // Disclosure. A determination that only exists in a build file is a silent one, so the
        // operator manual has to carry it where it states the gate result.
        assertThat(read(README_PATH))
                .contains("owasp-suppressions.xml")
                .contains("CVE-2026-66299")
                .contains("failBuildOnUnusedSuppressionRule")
                .doesNotContain("`dependency-check.skipTestScope` is **`true`**");
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

    @Test
    @DisplayName("the operator manual's volume inventory is the one Compose actually declares")
    void readmeVolumeInventoryMatchesCompose() throws IOException {
        // The manual said three named volumes and named three, while Compose declares four. The one
        // omitted was batch-staging - the volume a file-producing job composes its generations in - so an
        // operator reading only the manual would have believed a plain `down` discarded them and would
        // have had no reason to reach for `down -v` when a stale generation confused a later run.
        final JsonNode compose = new ObjectMapper(new YAMLFactory()).readTree(COMPOSE_PATH.toFile());
        final List<String> declared = new ArrayList<>();
        compose.path("volumes").fieldNames().forEachRemaining(declared::add);
        final String readme = read(README_PATH);

        assertThat(declared)
                .as("the fixture for this assertion is Compose itself, so the two cannot diverge")
                .containsExactlyInAnyOrder("postgres-data", "batch-staging", "prometheus-data",
                        "grafana-data");
        assertThat(readme)
                .as("the count the manual states must be the count Compose declares")
                .contains("declares **four** named volumes")
                .doesNotContain("declares three named volumes");
        for (final String volume : declared) {
            assertThat(readme)
                    .as("the manual must name the %s volume, or an operator cannot know what survives a "
                            + "plain down", volume)
                    .contains("`" + volume + "`");
        }
    }

    @Test
    @DisplayName("the operator manual describes the CI gate order the workflow actually runs")
    void readmeGateOrderMatchesTheWorkflow() throws IOException {
        // The manual had the reproducibility gate running after the smoke test. The workflow runs it
        // before, and the order is load-bearing rather than incidental: the jar the smoke test starts is
        // one already proved byte-identical across two clean builds, so a smoke failure cannot be blamed
        // on an unreproducible artifact. Read from the workflow rather than asserted as a constant, so
        // reordering the workflow fails here instead of silently making the manual wrong again.
        final String workflow = read(WORKFLOW_PATH);
        final int reproducibility = workflow.indexOf("name: Reproducible artifact gate");
        final int smoke = workflow.indexOf("name: Smoke test the executable jar");

        assertThat(reproducibility).as("the reproducibility gate step must exist").isNotNegative();
        assertThat(smoke).as("the smoke-test step must exist").isNotNegative();
        assertThat(reproducibility)
                .as("the workflow must run the reproducibility gate before the smoke test")
                .isLessThan(smoke);
        assertThat(read(README_PATH))
                .as("and the manual must describe that order rather than the reverse")
                .contains("the reproducibility gate runs before the smoke test")
                .doesNotContain("smoke-tests the executable jar, then performs a second\n   clean build");
    }

    @Test
    @DisplayName("the port-collision guidance names every published port, the two OTLP ports included")
    void readmePortGuidanceNamesEveryPublishedPort() throws IOException {
        // The guidance listed six ports and the stack publishes eight. The two it omitted are Jaeger's
        // OTLP ports, which are the likeliest of all eight to be already taken, because any other
        // collector on the machine wants exactly 4317 and 4318 too - so the omitted rows were the ones an
        // operator most needed.
        final JsonNode compose = new ObjectMapper(new YAMLFactory()).readTree(COMPOSE_PATH.toFile());
        final List<String> containerPorts = new ArrayList<>();
        for (final JsonNode service : compose.path("services")) {
            for (final JsonNode mapping : service.path("ports")) {
                final String text = mapping.asText();
                containerPorts.add(text.substring(text.lastIndexOf(':') + 1).trim());
            }
        }
        final String readme = read(README_PATH);

        assertThat(containerPorts)
                .as("the stack publishes eight ports; the guidance below is checked against this list")
                .containsExactlyInAnyOrder("8080", "5432", "4566", "9090", "3000", "16686", "4317",
                        "4318");
        for (final String port : containerPorts) {
            assertThat(readme)
                    .as("the port-collision guidance must name port %s", port)
                    .contains(port);
        }
        assertThat(readme)
                .as("and it must name the override variables for the two ports most often already taken")
                .contains("JAEGER_OTLP_GRPC_PORT")
                .contains("JAEGER_OTLP_HTTP_PORT");
    }

    @Test
    @DisplayName("the gate-evidence page exists and is registered, so nothing still calls it unpublished")
    void theGateEvidencePageIsPublishedAndRegistered() throws IOException {
        // The page is mandated, every gate points at it, and the dashboard's own guidance names it as
        // where figures are written up - while it did not exist. Three places in the manual described it
        // as unpublished; a fourth described the audit counts as pending. All four were true statements
        // about an absent file and are false statements about a present one.
        final Path evidence = Path.of("../docs/gate-evidence.md");
        assertThat(evidence).isRegularFile();

        final String page = read(evidence);
        assertThat(page)
                .as("the page must carry the Gate 6 counts, which are a property of the code")
                .contains("Gate 6 — Unsafe and low-level code audit")
                .contains("Reflection")
                .as("and both provenance identifiers, like every other migration document")
                .contains("7756d895ffeb65f7ea72aaa609e356d9899afcec")
                .contains("CardDemo_v1.0-15-g27d6c6f-68")
                .as("and it must state that it holds no threshold, because that is the Gate 3 contract")
                .contains("A measurement is never a threshold");
        assertThat(read(Path.of("../mkdocs.yml")))
                .as("an unregistered page exists on disk and never publishes, because the nav is explicit")
                .contains("gate-evidence.md");
        assertThat(read(README_PATH))
                .as("no sentence may still call the page unpublished")
                .doesNotContain("`docs/gate-evidence.md`, which is **not yet published**")
                .doesNotContain("the recorded counts page is pending");
    }

    @Test
    @DisplayName("every mandated documentation deliverable exists, and no page is left on disk that the "
            + "documentation site's explicit nav does not name")
    void everyMandatedDocumentIsPublishedAndRegistered() throws IOException {
        // WHY THIS IS TWO CHECKS AND NOT ONE. A documentation deliverable can fail in two independent
        // ways, and each is invisible to the other's check. It can be absent, which a reader discovers by
        // following a dead link. Or it can be present and UNREGISTERED - the nav list in mkdocs.yml is
        // explicit rather than directory-driven, so a page the nav does not name exists in the repository
        // and never appears on the published site. The second failure mode is the quieter one: the file is
        // right there, so a reviewer checking the tree concludes it shipped.
        final String nav = read(Path.of("../mkdocs.yml"));
        for (final String page : List.of("index.md", "onboarding-guide.md", "architecture.md",
                "traceability-matrix.md", "decision-log.md", "gate-evidence.md", "project-guide.md",
                "technical-specifications.md")) {
            assertThat(Path.of("../docs").resolve(page))
                    .as("%s is a mandated deliverable and must exist", page)
                    .isRegularFile();
            assertThat(nav)
                    .as("%s must be registered in the explicit nav, or it never publishes", page)
                    .contains(page);
        }

        // The deck is HTML rather than Markdown, so the documentation site serves it as a static asset
        // and it is deliberately NOT a nav entry. It is reached from the documentation index instead.
        final Path deck = Path.of("../docs/presentation/index.html");
        assertThat(deck).as("the migration summary deck is a mandated deliverable").isRegularFile();
        assertThat(read(deck))
                .as("the deck must be self-contained: an external stylesheet, script or font would make "
                        + "its appearance depend on a network no gate requires")
                .doesNotContain("<link rel=\"stylesheet\" href=\"http")
                .doesNotContain("<script src=\"http")
                .as("and it carries both provenance identifiers, like every other migration document")
                .contains("7756d895ffeb65f7ea72aaa609e356d9899afcec")
                .contains("CardDemo_v1.0-15-g27d6c6f-68");
    }

    @Test
    @DisplayName("the documentation index reaches every migration document, so no page is published and "
            + "unreachable")
    void theDocumentationIndexReachesEveryMigrationDocument() throws IOException {
        final String index = read(Path.of("../docs/index.md"));

        for (final String target : List.of("onboarding-guide.md", "architecture.md",
                "traceability-matrix.md", "decision-log.md", "gate-evidence.md",
                "presentation/index.html")) {
            assertThat(index)
                    .as("the documentation landing page must link %s; a registered page nobody links to "
                            + "is found only by someone who already knew it existed", target)
                    .contains("(" + target + ")");
        }
    }

    @Test
    @DisplayName("the estate README describes the Java module, its build and its local stack, and still "
            + "describes the mainframe application it was migrated from")
    void theEstateReadmeDescribesBothImplementations() throws IOException {
        // The repository now carries two implementations side by side. The estate README described only
        // the mainframe one, so a reader arriving at the repository root had no way to discover that a
        // Java module existed at all - and the module's own README is one directory down, which is exactly
        // where a reader who does not know it exists will not look.
        final String estateReadme = read(Path.of("../README.md"));

        assertThat(estateReadme)
                .as("the module, its build command and its local stack must be discoverable from the root")
                .contains("carddemo-java")
                .contains("./mvnw -B clean verify")
                .contains("docker compose up -d")
                .as("and every migration document must be reachable from there")
                .contains("docs/onboarding-guide.md")
                .contains("docs/architecture.md")
                .contains("docs/traceability-matrix.md")
                .contains("docs/decision-log.md")
                .contains("docs/gate-evidence.md")
                .as("while the mainframe narrative it is the authority for stays in place")
                .contains("## Installation on the mainframe")
                .contains("CICS")
                .contains("VSAM");
    }

    @Test
    @DisplayName("the service catalog classifies the component as a service and keeps its identity, "
            + "because a catalog entry is addressed by identity rather than by type")
    void theServiceCatalogClassifiesTheComponentCorrectly() throws IOException {
        final JsonNode catalog =
                new ObjectMapper(new YAMLFactory()).readTree(Path.of("../catalog-info.yaml").toFile());

        assertThat(catalog.path("spec").path("type").asText())
                .as("the delivered artefact is a deployable Spring Boot service, not a website: there is "
                        + "no browser interface anywhere in it, by design")
                .isEqualTo("service");

        // Identity is deliberately NOT changed. The catalog entry is addressed by name, the documentation
        // site publishes from the annotated location, and the entry belongs to a named system - so
        // rewriting any of the three would break the published entry rather than reclassify it.
        assertThat(catalog.path("metadata").path("name").asText())
                .as("the catalog entry is addressed by this name")
                .isEqualTo("blitzy-card-demo");
        assertThat(catalog.path("metadata").path("annotations").path("github.com/project-slug").asText())
                .as("and the source location resolves through this slug")
                .isEqualTo("Blitzy-Sandbox/blitzy-card-demo");
        assertThat(catalog.path("metadata").path("annotations").path("backstage.io/techdocs-ref").asText())
                .as("and the documentation is published from the repository root, which is why the pages "
                        + "live at docs/ rather than inside this module")
                .isEqualTo("dir:.");
        assertThat(catalog.path("spec").path("system").asText())
                .as("the owning system is untouched")
                .isEqualTo("blitzy-typescript");

        final List<String> tags = new ArrayList<>();
        catalog.path("metadata").path("tags").forEach(tag -> tags.add(tag.asText()));
        assertThat(tags)
                .as("the delivered stack is discoverable by tag")
                .contains("java-25", "spring-boot", "spring-batch", "spring-data-jpa", "postgresql",
                        "maven", "aws", "testcontainers")
                .as("and the estate the component was migrated from stays discoverable too")
                .contains("cobol", "migration");
    }

    @Test
    @DisplayName("the jar smoke gate asserts the same health group the container image probes")
    void theSmokeGateAssertsTheGroupTheImageProbes() throws IOException {
        // The step started only a database, ran the local profile and then asserted the AGGREGATE health
        // endpoint. The aggregate includes the object-store, queue and topic contributors, none of which
        // can be UP when the emulator is not started, so the endpoint answered DOWN with status 503 and
        // `curl -fsS` failed - the gate could not pass for any artifact. Measured with the database up and
        // the emulator absent: the aggregate returns {"status":"DOWN"} 503, the liveness group returns
        // {"status":"UP"} 200. The step's own comment claimed it read the same signal the image probes,
        // which is now true rather than aspirational. Decision log DL-183.
        final String workflow = read(WORKFLOW_PATH);
        final String dockerfile = read(DOCKERFILE_PATH);
        final int smokeStep = workflow.indexOf("name: Smoke test the executable jar");
        final int nextStep = workflow.indexOf("name: Validate and scan the container", smokeStep);
        assertThat(smokeStep).as("the smoke step must exist").isNotNegative();
        assertThat(nextStep).as("the following step bounds the region read below").isGreaterThan(smokeStep);
        final String smoke = workflow.substring(smokeStep, nextStep);

        assertThat(dockerfile)
                .as("the image probe is the fixture for this assertion, not a constant restated here")
                .contains("/actuator/health/liveness");
        assertThat(smoke)
                .as("the gate must poll the group the image probes")
                .contains("/actuator/health/liveness")
                .as("and must not gate on the aggregate, which cannot be UP without the emulator")
                .doesNotContain("curl -fsS http://localhost:8080/actuator/health)");
    }

    @Test
    @DisplayName("the dashboard assertion selects the panels whose contract it actually asserts")
    void theDashboardAssertionSelectsThePanelsItAsserts() throws IOException {
        // The jq selected panels 11 and 12 and then required their queries to carry the item-reader label
        // names, which belong to panels 31 and 32. Panels 11 and 12 read the application's own counters
        // and carry six targets each, so the filter failed twice: the length test was wrong against
        // twelve targets and every contains was false. Both the workflow and the dashboard are read here,
        // so the two cannot drift apart again. Decision log DL-184.
        final String workflow = read(WORKFLOW_PATH);
        final JsonNode dashboard = new ObjectMapper()
                .readTree(Path.of("config", "grafana", "dashboards", "carddemo-overview.json").toFile());

        final List<Integer> itemReadPanels = new ArrayList<>();
        final List<Integer> applicationPanels = new ArrayList<>();
        int targetsOnElevenAndTwelve = 0;
        for (final JsonNode panel : dashboard.path("panels")) {
            final int id = panel.path("id").asInt();
            for (final JsonNode target : panel.path("targets")) {
                final String expression = target.path("expr").asText();
                if (expression.contains("spring_batch_item_read_job_name")) {
                    itemReadPanels.add(id);
                }
                if (expression.contains("carddemo_batch_") && expression.contains("_total")) {
                    applicationPanels.add(id);
                    if (id == 11 || id == 12) {
                        targetsOnElevenAndTwelve++;
                    }
                }
            }
        }

        assertThat(itemReadPanels)
                .as("the item-reader label names live on panels 31 and 32, and nowhere else - which is "
                        + "why asserting them against 11 and 12 could never have held")
                .containsOnly(31, 32);
        assertThat(applicationPanels)
                .as("panels 11 and 12 are the throughput pair the filter selects; panel 28 legitimately "
                        + "reads an application counter too, so this is a containment claim and not an "
                        + "exclusive one")
                .contains(11, 12);
        assertThat(targetsOnElevenAndTwelve)
                .as("twelve targets sit on the selected pair, which is the count the filter states")
                .isEqualTo(12);
        assertThat(workflow)
                .as("the item-read contract must be asserted against 31 and 32")
                .contains("select(.id == 31 or .id == 32)")
                .as("the application-counter contract must be asserted against 11 and 12")
                .contains("select(.id == 11 or .id == 12)")
                .contains("contains(\"carddemo_batch_\") and contains(\"_total\")")
                .as("and the exact target count must be the measured one")
                .contains("($application | length) == 12")
                .as("the served dashboard must also be held to the Gate 3 honesty rule")
                .contains("contains(\"GATE 3 RECORDS PER SECOND\")");
    }

    @Test
    @DisplayName("the container scan retains every finding, including the ones upstream has not fixed")
    void theContainerScanRetainsUnfixedFindings() throws IOException {
        // --ignore-unfixed does not merely soften a verdict: it removes those findings from the JSON, so
        // the archived report a reviewer signs off from could not show what had been dropped. A gate
        // calling itself a strict zero-HIGH/CRITICAL gate while a flag deleted an unbounded subset from
        // both the verdict and the evidence was neither strict nor auditable. Decision log DL-185.
        final String workflow = read(WORKFLOW_PATH);

        // Asserted over the executable lines only. The comment above the function names the removed flag
        // in order to explain why it is gone, and a rule that could not tell prose from a command would
        // force that explanation to be deleted - which is the opposite of what the finding asked for.
        assertThat(executableLinesOf(workflow))
                .as("no command may filter findings out of the scan or its report")
                .doesNotContain("--ignore-unfixed");
        assertThat(workflow)
                .as("and the comment must still say why, so the removal survives the next reader")
                .contains("--ignore-unfixed, which does not merely soften the verdict");
        assertThat(workflow)
                .as("the scanner must not decide the verdict, so the breakdown is printed before judging")
                .contains("--exit-code 0")
                .as("the unfixed subset must be counted and reported rather than dropped")
                .contains("select((.FixedVersion // \"\") == \"\")")
                .contains("of them with no fix available")
                .as("a strictly gated image must fail on any HIGH or CRITICAL, fixed or not")
                .contains("if [ \"${verdict}\" = 'strict' ] && [ \"${count}\" -ne 0 ]; then")
                .as("and the failure must enumerate what it is failing on")
                .contains("no fix available")
                .as("third-party Compose images stay inventory-only, which is a different decision")
                .contains("scan_image \"${image}\" inventory")
                .contains("scan_image \"${application_image_id}\" strict");
    }

    @Test
    @DisplayName("one gate inventory: the header, the six banners and the summary all agree")
    void theGateInventoryIsStatedOnceAndAgreesEverywhere() throws IOException {
        // The file carried three disagreeing counts - a header saying five, two banners saying "of 4",
        // three saying "of 5", and a summary listing six - so a reviewer signing the gates off could not
        // tell which was authoritative. Six is the count. The undercount existed because the last gate
        // was the only step with no banner at all, so anyone counting banners counted one short.
        // Decision log DL-186.
        final String workflow = read(WORKFLOW_PATH);
        final List<String> banners = new ArrayList<>();
        for (final String line : workflow.split("\n")) {
            if (line.contains("---- gate ") && line.contains(" of ")) {
                banners.add(line.strip());
            }
        }

        assertThat(banners)
                .as("every gate step announces itself, and there are six of them")
                .hasSize(6);
        for (int gate = 1; gate <= 6; gate++) {
            final String expected = "gate " + gate + " of 6";
            assertThat(banners)
                    .as("banner %s must be present and must state the total as six", expected)
                    .anySatisfy(banner -> assertThat(banner).contains(expected));
        }
        assertThat(workflow)
                .as("no banner may still state a stale total")
                .doesNotContain(" of 4:")
                .doesNotContain(" of 5:")
                .as("the header must state the same count as the banners")
                .contains("SIX linear gates");

        // The summary is the third place the inventory is named, and every name in it must be a name a
        // banner uses, so the two lists cannot describe different sets of gates.
        final List<String> summaryNames = List.of(
                "build, test, coverage and supply chain",
                "locale-hostile re-execution",
                "bootstrap contract",
                "reproducibility",
                "jar deployability",
                "container, Compose and monitoring deployability");
        for (int gate = 1; gate <= summaryNames.size(); gate++) {
            final String name = summaryNames.get(gate - 1);
            assertThat(workflow)
                    .as("the summary must report gate %s under the name the banner uses", gate)
                    .contains("echo 'gate " + gate + " of 6  " + name);
            assertThat(banners.get(gate - 1))
                    .as("and banner %s must carry that same name", gate)
                    .contains(name.equals("container, Compose and monitoring deployability")
                            ? "container, Compose and monitoring deployability"
                            : name);
        }
    }

    /**
     * Returns the workflow with every comment-only line removed, so a rule about a command is not
     * satisfied or broken by prose describing that command.
     *
     * <p>The filter is deliberately narrow: a line is dropped only when its first non-blank character
     * begins a comment. A flag written after a command on the same line is therefore still visible, which
     * is the direction an error should fall in.
     *
     * @param workflow the workflow text
     * @return the executable lines, rejoined
     */
    private static String executableLinesOf(final String workflow) {
        final List<String> executable = new ArrayList<>();
        for (final String line : workflow.split("\n")) {
            if (!line.strip().startsWith("#")) {
                executable.add(line);
            }
        }
        return String.join("\n", executable);
    }

    private static String read(final Path path) throws IOException {
        assertThat(path).isRegularFile();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Counts non-overlapping occurrences of a literal token.
     *
     * <p>Used to compare rule count against scope count in the suppression file, which is the check
     * that catches a rule added without an artifact scope or without an identifier.</p>
     *
     * @param haystack text to search, never {@code null}
     * @param token    literal token to count, never empty
     * @return the number of occurrences, zero when absent
     */
    private static int countOccurrences(final String haystack, final String token) {
        int count = 0;
        int from = haystack.indexOf(token);
        while (from >= 0) {
            count++;
            from = haystack.indexOf(token, from + token.length());
        }
        return count;
    }

    /**
     * Removes XML comments, leaving only declarations.
     *
     * <p>The suppression file carries its governing rules and its evidence as comments, and stating a
     * prohibition means naming the element it prohibits. Counting element tokens over the raw text
     * would therefore count the prohibition as an instance of the thing prohibited, so structural
     * assertions run over the declarations and the evidence assertions run over the whole file.</p>
     *
     * @param xml document text, never {@code null}
     * @return the same text with every {@code <!-- ... -->} span removed
     */
    private static String withoutXmlComments(final String xml) {
        final StringBuilder kept = new StringBuilder(xml.length());
        int cursor = 0;
        int open = xml.indexOf("<!--");
        while (open >= 0) {
            kept.append(xml, cursor, open);
            final int close = xml.indexOf("-->", open + "<!--".length());
            if (close < 0) {
                // An unterminated comment cannot be reasoned about; treat the remainder as commented
                // rather than silently admitting text that the parser would never see as markup.
                return kept.toString();
            }
            cursor = close + "-->".length();
            open = xml.indexOf("<!--", cursor);
        }
        kept.append(xml.substring(cursor));
        return kept.toString();
    }
}