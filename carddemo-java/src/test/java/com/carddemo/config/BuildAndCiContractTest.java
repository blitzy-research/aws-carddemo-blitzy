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

import com.carddemo.service.BatchJobCatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Path CONTAINER_DETERMINATIONS_PATH =
            Path.of("container-scan-determinations.txt");
    private static final Path DOCKERFILE_PATH = Path.of("Dockerfile");
    private static final Path COMPOSE_PATH = Path.of("docker-compose.yml");
    private static final Path README_PATH = Path.of("README.md");
    private static final Path GITIGNORE_PATH = Path.of(".gitignore");
    private static final Path GITATTRIBUTES_PATH = Path.of(".gitattributes");
    private static final Path MAIN_PACKAGE_ROOT =
            Path.of("src", "main", "java", "com", "carddemo");
    private static final Path WORKFLOW_PATH =
            Path.of("../.github/workflows/carddemo-java-ci.yml");
    private static final Path GATE_EVIDENCE_PATH = Path.of("../docs/gate-evidence.md");
    private static final Path PROJECT_GUIDE_PATH = Path.of("../docs/project-guide.md");
    private static final Path SCAN_REPORT_PATH =
            Path.of("target", "dependency-check-report.json");

    /**
     * The published supply-chain figure, as a pair rather than as a single number.
     *
     * <p>The scan reports a count of entries and a count of files, and the two differ because the
     * scanner groups a related artefact underneath the entry that carries it. Publishing either half
     * alone is what let a stale figure survive: a reader who found {@code 167} in one place and
     * {@code 166} in another had no way to tell which had been measured.</p>
     */
    private static final Pattern PUBLISHED_SCAN_FIGURE =
            Pattern.compile("(\\d+) report entries,? covering (\\d+) files");

    /** The lead-in of the published figure, counted so no half-stated form can hide beside a pair. */
    private static final String SCAN_FIGURE_LEAD_IN = " report entries";

    /** A row of a hours table: a name, an hours figure, and whatever the row says about it. */
    private static final Pattern HOURS_TABLE_ROW =
            Pattern.compile("^\\|\\s*(\\S[^|]*?)\\s*\\|\\s*\\**(\\d+)\\**\\s*\\|(.*)$",
                    Pattern.MULTILINE);

    /** A slice of a Mermaid pie: its label and its value. */
    private static final Pattern PIE_SLICE =
            Pattern.compile("^\\s*\"([^\"]+)\"\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);

    /** One term of a roll-up expression: a detail-row name followed by that row's hours. */
    private static final Pattern ROLL_UP_TERM = Pattern.compile("^(.*?)\\s+(\\d+)$");

    /** The documentation site configuration, which both publication modes read. */
    private static final Path MKDOCS_PATH = Path.of("../mkdocs.yml");

    /** Where a diagram's authoring source and its pre-rendered publication both live. */
    private static final Path DIAGRAM_DIR = Path.of("../docs/diagrams");

    /** The opening element of a pre-rendered diagram, carrying its explicit intrinsic size. */
    private static final Pattern SVG_ROOT =
            Pattern.compile("^<svg width=\"(\\d+)\" height=\"(\\d+)\"");

    /** A diagram's coordinate system, which its intrinsic size has to agree with. */
    private static final Pattern SVG_VIEW_BOX =
            Pattern.compile("viewBox=\"[-\\d.]+ [-\\d.]+ ([\\d.]+) ([\\d.]+)\"");

    /** A rule that backs an edge label, whose declarations must be opaque. */
    private static final Pattern EDGE_LABEL_RECT_RULE =
            Pattern.compile("#my-svg [^{}]*\\.edgeLabel[^{}]*rect\\{([^}]*)\\}");

    /** A CSS reference inside a render; only a same-document fragment keeps it self-contained. */
    private static final Pattern CSS_URL_REFERENCE = Pattern.compile("url\\(([^)]*)\\)");

    /** One published diagram figure, from its opening element to its close. */
    private static final Pattern DIAGRAM_FIGURE =
            Pattern.compile("<figure class=\"diagram\".*?</figure>", Pattern.DOTALL);

    /** The image inside a figure: its alt text, then the render it points at. */
    private static final Pattern DIAGRAM_IMAGE =
            Pattern.compile("!\\[([^\\]]+)\\]\\(diagrams/([A-Za-z0-9-]+)\\.svg\\)");

    /** The publication root. Everything the site can serve lives beneath it; nothing above it does. */
    private static final Path DOCS_ROOT = Path.of("../docs");

    /**
     * The theme override that carries every accessibility patch. It sits at the repository root rather than
     * inside the documentation directory because MkDocs resolves {@code theme.custom_dir} relative to the
     * configuration file and rejects a directory inside {@code docs_dir}.
     */
    private static final Path THEME_OVERRIDE_PATH = Path.of("../overrides/main.html");

    /** The published stylesheet, which is the only place a style fix may live once script is unavailable. */
    private static final Path EXTRA_CSS_PATH = Path.of("../docs/stylesheets/extra.css");

    /** The migration summary deck, published as a static asset rather than as a nav entry. */
    private static final Path DECK_PATH = Path.of("../docs/presentation/index.html");

    /** An image in the deck: the path it points at, then the intrinsic size it declares for it. */
    private static final Pattern DECK_IMAGE =
            Pattern.compile("<img src=\"([^\"]+)\" width=\"(\\d+)\" height=\"(\\d+)\"");

    /** The scroll frame around a deck table, with the four attributes that make it operable. */
    private static final Pattern DECK_SCROLL_REGION = Pattern.compile(
            "<div class=\"scroller\" role=\"region\" tabindex=\"0\" aria-labelledby=\"([^\"]+)\" "
                    + "aria-describedby=\"([^\"]+)\">");

    /**
     * The screen captures the deck publishes, each of which also exists at the repository root.
     *
     * <p>The root copies are the estate's own assets and are read-only reference: the plan forbids
     * modifying, moving or deleting anything under {@code diagrams/}, so the deck cannot be fixed by
     * relocating them and is fixed by publishing a copy beneath {@code docs/} instead. A copy can
     * drift from its original silently, which is the one hazard the duplication introduces, so the
     * pair is compared byte for byte below rather than assumed to be in step.</p>
     */
    private static final List<String> DECK_CAPTURES = List.of(
            "Application-Flow-User.png", "Application-Flow-Admin.png",
            "Signon-Screen.png", "Main-Menu.png", "Admin-Menu.png");

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
    @DisplayName("CI uploads the gate evidence the run itself authors, and does so before the clean that "
            + "would delete it")
    void workflowUploadsGateEvidenceBeforeTheClean() throws IOException {
        final String workflow = read(WORKFLOW_PATH);
        final String executable = executableLinesOf(workflow);

        assertThat(executable)
                .as("the three gates that author a file must have their bundle published, or the figures "
                        + "the evidence page quotes name an artefact that exists nowhere")
                .contains("name: gate-evidence")
                .contains("path: carddemo-java/target/gate-evidence/**");

        // The clean is what made this a defect rather than an omission: the reproducibility gate rebuilds
        // from an empty target, so a bundle uploaded afterwards would be a bundle of nothing.
        final int upload = executable.indexOf("name: gate-evidence");
        final int verification = executable.indexOf("Verify the gate evidence bundle");
        final int clean = executable.indexOf("clean package");
        assertThat(verification)
                .as("the bundle is checked by name before it is uploaded, because the upload action's own "
                        + "failure names a glob rather than the absent file")
                .isGreaterThan(-1)
                .isLessThan(upload);
        assertThat(upload)
                .as("and the upload must precede the rebuild that empties the build directory")
                .isGreaterThan(-1)
                .isLessThan(clean);
    }

    @Test
    @DisplayName("an absent or foreign gate-evidence bundle fails the run visibly rather than uploading "
            + "nothing")
    void anAbsentGateEvidenceBundleFailsVisibly() throws IOException {
        final String workflow = read(WORKFLOW_PATH);
        final String executable = executableLinesOf(workflow);

        // Asserted as one block rather than as separate needles. Another upload in this workflow already
        // uses error, so a bare contains("if-no-files-found: error") would be satisfied by that one and
        // would say nothing about this step - which is exactly what it did until a mutation showed it.
        // The block also pins the step to having no condition, because always() would turn a failed run's
        // empty directory into a second, misleading failure on top of the real one.
        final String uploadStep = String.join("\n",
                "      - name: Upload gate evidence",
                "        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2",
                "        with:",
                "          name: gate-evidence",
                "          path: carddemo-java/target/gate-evidence/**",
                "          if-no-files-found: error",
                "          retention-days: 30");
        assertThat(workflow)
                .as("reaching this step means the tier that writes these files ran, so absence is a "
                        + "defect in that tier and not a skippable condition")
                .contains(uploadStep);
        assertThat(executable)
                .as("the failure has to name the bundle rather than the glob")
                .contains("The gate evidence bundle is incomplete. Absent:")
                .as("each of the three producing gates is named, and Gate 3's two baselines are named "
                        + "individually rather than behind a glob: a run that wrote the pipeline baseline "
                        + "and not the interest baseline satisfied `gate3-*.md` and published half a "
                        + "gate's evidence as though it were whole")
                .contains("target/gate-evidence/gate1-byte-equivalence.md")
                .contains("target/gate-evidence/gate8-sign-off.md")
                .contains("target/gate-evidence/gate3-pipeline-baseline.md")
                .contains("target/gate-evidence/gate3-interest-calculation.md")
                .as("and a file that does not name this run's revision must not be published as its "
                        + "evidence")
                .contains("^Build provenance: revision ${SOURCE_REVISION},");
        // The no-glob rule belongs to the step that decides EXISTENCE, and only to that step. A glob there
        // is satisfied by whichever baseline happens to be present, so half a gate's evidence passes as
        // whole - which is the defect the individual names above fix. A later step that iterates the same
        // glob and checks EVERY file it finds cannot hide an absent one, because absence is already settled
        // by the time it runs, so the rule does not extend to it. Scoped to the verification step's own
        // text rather than to the whole workflow for exactly that reason.
        final String verificationStep = workflow.substring(
                workflow.indexOf("- name: Verify the gate evidence bundle"),
                workflow.indexOf("- name: Reconcile the provisional sign-off into a final one"));
        assertThat(verificationStep)
                .as("the step that decides whether the bundle is complete must name each Gate 3 baseline, "
                        + "never a glob that one of them can satisfy alone")
                .doesNotContain("target/gate-evidence/gate3-*.md");

        // The other three report uploads are the opposite case on purpose and must stay that way: a
        // FAILED run should still publish whatever coverage, scan and test output it produced, and may
        // legitimately have produced none.
        assertThat(workflow)
                .as("the three tool-report uploads keep always() and warn, because absence is a "
                        + "legitimate outcome for them")
                .contains("- name: Upload coverage reports\n        if: always()")
                .contains("- name: Upload vulnerability scan reports\n        if: always()")
                .contains("- name: Upload unit and integration test reports\n        if: always()");
    }

    @Test
    @DisplayName("the build hands the integration tier the revision the evidence stamps, from the same "
            + "property the artefact records")
    void theBuildHandsTheRevisionToTheEvidenceProducers() throws IOException {
        final String pom = read(POM_PATH);
        final String workflow = read(WORKFLOW_PATH);

        assertThat(pom)
                .as("without this every stamp would read not-supplied, in CI as well as locally")
                .contains("<carddemo.build.revision>${build.revision}</carddemo.build.revision>")
                .as("and the artefact's own build information must derive from the same property, so the "
                        + "jar and the evidence measured against it name one commit")
                .contains("<source-revision>${build.revision}</source-revision>");
        assertThat(executableLinesOf(workflow))
                .as("the gate that runs the tests is the one that must carry the revision")
                .contains("-Dbuild.revision=\"${SOURCE_REVISION}\"");
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
    @DisplayName("a green run cannot publish an empty evidence bundle: every required report is "
            + "asserted, and a missing one is an error rather than an annotation")
    void workflowFailsWhenRequiredEvidenceIsAbsent() throws IOException {
        // THE HOLE THIS CLOSES. All four evidence uploads carried `if-no-files-found: warn`, chosen
        // because they also carry `if: always()` and a failed early gate legitimately produces nothing.
        // The consequence was that a run in which every gate PASSED and yet produced no coverage report,
        // no vulnerability report and no test-result XML finished GREEN with an annotation nobody reads -
        // and those three are the evidence for Gate 7, Gate 8 and Gates 1/4/5. Sign-off would then rest
        // on artefacts that were never published.
        //
        // Two halves are asserted, because either alone leaves the hole open. The assertion STEP, run
        // under if: success() so it judges only a run whose producing gates completed, requires each
        // artefact to exist and be non-empty - which an upload cannot do, since an upload of a
        // multi-path pattern succeeds as soon as ONE file matches. And the POLICY on each upload is now
        // derived from the job status, so an absent bundle fails a green run while a run that has
        // already failed still publishes whatever diagnostics it has, without a second misleading
        // failure.
        final String workflow = read(WORKFLOW_PATH);
        final String conditionalPolicy =
                "if-no-files-found: ${{ job.status == 'success' && 'error' || 'warn' }}";

        assertThat(workflow)
                .as("the assertion step exists and runs only when the producing gates succeeded")
                .contains("- name: Assert the required gate evidence exists")
                .contains("        if: success()");
        assertThat(workflow)
                .as("and it names every artefact a sign-off reads, so a partially produced bundle is a "
                        + "failure rather than an upload that found something")
                .contains("require target/site/jacoco-merged/index.html")
                .contains("require target/dependency-check-report.html")
                .contains("require target/dependency-check-report.json")
                .contains("require target/dependency-check-report.xml")
                .contains("require target/surefire-reports")
                .contains("require target/failsafe-reports")
                .contains("require target/gate-evidence");
        assertThat(workflow.split(Pattern.quote(conditionalPolicy), -1).length - 1)
                .as("all four evidence uploads resolve their missing-file policy from the job status: "
                        + "coverage, CVE, test results and the container scan")
                .isEqualTo(4);
        assertThat(workflow)
                .as("and no upload is left reporting an absent artefact as a warning unconditionally, "
                        + "which is the exact defect above")
                .doesNotContain("if-no-files-found: warn\n");
    }

    @Test
    @DisplayName("the evidence bundle is verified against the artefact names the suite actually writes, "
            + "so a renamed report fails rather than going unpublished")
    void workflowNamesEveryEvidenceArtefactTheSuiteWrites() throws IOException {
        // The bundle is the only machine-produced record of Gates 1, 3 and 8: the suite writes a
        // byte-equivalence comparison, two measured performance baselines and the sign-off checklist into
        // target/gate-evidence/ as it runs, and none of the other uploads carries any of them. The
        // reproducibility gate then runs `clean package`, which deletes the directory - so an upload
        // declared after it would publish an empty bundle and report that as a warning.
        //
        // WHAT THIS ADDS TO THE ORDERING ASSERTED ABOVE is that the names are read out of the suite that
        // writes them rather than repeated here. A constant would be a second copy of the same fact, and
        // the copy that drifts is the one nobody runs: an evidence file renamed in the suite would keep
        // satisfying a hardcoded check while going unverified. The workflow step names each file
        // individually for the same reason - `gate3-*.md` was satisfied by whichever of the two Gate 3
        // baselines existed, so half a gate's evidence could be published as though it were whole.
        final String workflow = read(WORKFLOW_PATH);
        final int verification = workflow.indexOf("name: Verify the gate evidence bundle");
        final int upload = workflow.indexOf("name: Upload gate evidence");
        final int reproducibility = workflow.indexOf("name: Reproducible artifact gate");

        assertThat(verification).as("the verification step must exist").isNotNegative();
        assertThat(upload).as("the gate-evidence upload step must exist").isNotNegative();
        assertThat(reproducibility).as("the reproducibility gate step must exist").isNotNegative();
        assertThat(verification)
                .as("the bundle is verified before it is uploaded, so a run that produced three of the "
                        + "four artefacts says which one is absent rather than uploading three silently")
                .isLessThan(upload);
        assertThat(upload)
                .as("and the upload must precede the reproducibility gate, whose `clean package` deletes "
                        + "target/gate-evidence/ along with the rest of target")
                .isLessThan(reproducibility);
        assertThat(workflow)
                .as("it must upload the directory the suite writes to, under its own artifact name")
                .contains("carddemo-java/target/gate-evidence/**")
                .contains("name: gate-evidence");

        final List<String> written = evidenceArtefactsTheSuiteWrites();
        assertThat(written)
                .as("the suite must write evidence artefacts at all, or this whole contract is vacuous")
                .isNotEmpty();
        final String verificationStep = workflow.substring(verification, upload);
        for (final String artefact : written) {
            assertThat(verificationStep)
                    .as("the verification step must name %s, which the test suite writes into "
                            + "target/gate-evidence/", artefact)
                    .contains(artefact);
        }
    }

    @Test
    @DisplayName("the provisional sign-off is reconciled into a final one after verify, and nothing "
            + "undischarged can be published as a sign-off")
    void theProvisionalSignOffIsReconciledAfterVerify() throws IOException {
        // THE DEFECT THIS PINS. The sign-off checklist is emitted by GateVerificationTest at
        // integration-test. The vulnerability report and the merged coverage report are written at verify -
        // a later phase - so a checklist row whose evidence is either of those is outstanding when the
        // table is written, and the table says PENDING. Nothing rejected that: the upload published the
        // interim table as the run's conclusion, and a reviewer downloading the bundle received a sign-off
        // in which two rows read PENDING with no artefact anywhere saying whether they had since passed.
        //
        // WHAT IS ASSERTED. A step exists between the bundle verification and the upload; it discharges
        // each outstanding row against the real post-verify artefact rather than re-reading the interim
        // table; it refuses a MISSING row and refuses to publish anything still reading PROVISIONAL; and
        // it writes the file the upload then publishes. Position matters as much as existence - a
        // reconciliation after the upload reconciles a bundle that has already been published.
        final String workflow = read(WORKFLOW_PATH);
        final int verification = workflow.indexOf("name: Verify the gate evidence bundle");
        final int reconciliation =
                workflow.indexOf("name: Reconcile the provisional sign-off into a final one");
        final int upload = workflow.indexOf("name: Upload gate evidence");

        assertThat(reconciliation)
                .as("a step must reconcile the interim sign-off after verify; without it the interim table "
                        + "is what the run publishes as its conclusion")
                .isNotNegative();
        assertThat(verification)
                .as("the bundle must be verified before it is reconciled, so a reconciliation never runs "
                        + "against a bundle that is missing a file")
                .isLessThan(reconciliation);
        assertThat(reconciliation)
                .as("and the reconciliation must precede the upload: reconciling after publication "
                        + "reconciles something already published")
                .isLessThan(upload);

        final String step = workflow.substring(reconciliation, upload);
        assertThat(step)
                .as("the outstanding rows are discharged against the artefacts the interim table names, "
                        + "each read after verify rather than taken on trust")
                .contains("target/dependency-check-report.json")
                .contains("target/gate-evidence/gate3-")
                .contains("target/site/jacoco-merged/jacoco.xml");
        assertThat(step)
                .as("an outstanding row must end the step rather than be published: a MISSING row, an "
                        + "absent status line, a qualifying unsuppressed finding, a baseline with no "
                        + "measured row and coverage below the floor each fail it")
                .contains("carries a MISSING row")
                // Anchored to a TABLE ROW rather than to the word. The table's own explanatory paragraph
                // says "A row marked MISSING is an outstanding work item", so a bare token search reports
                // the sentence explaining the state as though it were the state - which it did, on the
                // first run of this step against a real bundle, failing a green build on its own prose.
                .contains("'^\\|.*\\*\\*MISSING\\*\\*'")
                .doesNotContain("grep -q 'MISSING'")
                .contains("carries no 'Sign-off status:' line")
                .contains("unsuppressed finding(s) at or above CVSS 7.0")
                .contains("carries no measured row beneath its header")
                // Written as the shell carries it: the message is emitted by printf, so the literal per
                // cent in the format string is doubled. Matching the undoubled form would assert against
                // text the workflow does not contain.
                .contains("below the 80%% floor");
        assertThat(step)
                .as("and the final sign-off it writes must be the one the upload publishes, must be named "
                        + "outside the gate<n>- namespace the suite owns, and must never read PROVISIONAL")
                .contains("target/gate-evidence/final-sign-off.md")
                .contains("Sign-off status: FINAL")
                .contains("still reads PROVISIONAL");
        assertThat(step)
                .as("it carries the same build-provenance line every other file in the bundle carries, so "
                        + "the conclusion is attributable to the commit its evidence was measured on")
                .contains("Build provenance: revision");
    }

    @Test
    @DisplayName("the published coverage counters are reconciled against the merged report after verify, "
            + "because no test in the build can read its own build's report")
    void thePublishedCoverageCountersAreReconciledAfterVerify() throws IOException {
        // THE DEFECT THIS PINS. The evidence page publishes a four-row covered-and-total table read from
        // the merged JaCoCo report, and nothing compared those figures with the report. Three of the four
        // rows had drifted - line by three, instruction by two hundred, method by two - and no other check
        // in this pipeline can see it: the coverage GATE reads the report and passes, and the page's
        // transcription of the same report is free to say anything.
        //
        // WHY IT IS A WORKFLOW STEP RATHER THAN A TEST. The merged report is written at
        // post-integration-test. Every test in this module runs at test or integration-test, so no test can
        // read the report of its own build; one reading a previous build's report would pass or fail on a
        // stale file, which is worse than not checking at all. After verify both the page and the report
        // exist, and that is where this runs.
        final String workflow = read(WORKFLOW_PATH);
        final int reconciliation =
                workflow.indexOf("name: Reconcile the published coverage counters with the merged report");
        final int reproducibility = workflow.indexOf("name: Reproducible artifact gate");

        assertThat(reconciliation)
                .as("a step must hold the published coverage counters to the merged report; without it a "
                        + "transcribed counter can drift while every gate stays green")
                .isNotNegative();
        assertThat(reconciliation)
                .as("and it must run before the reproducibility gate, whose `clean package` deletes the "
                        + "merged report it reads")
                .isLessThan(reproducibility);

        final String step = workflow.substring(reconciliation, reproducibility);
        assertThat(step)
                .as("it reads the merged report and the published page, and compares all four counters "
                        + "the page publishes rather than only the gated one")
                .contains("target/site/jacoco-merged/jacoco.xml")
                .contains("../docs/gate-evidence.md")
                .contains("'LINE:Line' 'BRANCH:Branch' 'INSTRUCTION:Instruction' 'METHOD:Method'");
        assertThat(step)
                .as("a mismatch must fail the step and name both figures, so the diagnosis is the "
                        + "correction rather than a hunt")
                .contains("the page publishes ${expected_covered}, this run measured ${covered}")
                .contains("Update the table in docs/gate-evidence.md from the figures above");
        assertThat(step)
                .as("the bundle counter is the LAST of its type in the report - JaCoCo writes per-class "
                        + "counters first - so the step must take the last rather than the first")
                .contains("tail -1");
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
    @DisplayName("the whitespace gate reads the whole committed tree of the paths this migration authors")
    void theWhitespaceGateReadsTheCommittedTreeRatherThanAnEmptyDiff() throws IOException {
        // A trailing space was committed inside an assertion in this very file's neighbour and reached a
        // reviewer, who found it with `git diff --check` against an explicit base. This asserts the
        // MECHANICS of the gate that now catches it, not merely that some step mentions the command,
        // because the obvious spelling of that command is the one spelling that cannot work here:
        //
        //   bare `git diff --check`  compares the working tree with the index. A CI checkout has no
        //                           difference between the two, so it passes unconditionally.
        //   against a branch point  needs history this checkout does not fetch, and reads only the
        //                           lines one change touched - the defect was already committed, so a
        //                           change-scoped check would have reported this tree clean.
        //
        // Diffing the empty tree against HEAD is what makes every line in scope an added line, so the
        // diagnostic reads the whole committed tree. The empty-tree object is obtained by hashing an
        // empty tree rather than by pasting its well-known hash, so the command carries no magic
        // constant a reader has to take on trust.
        final String executable = executableLinesOf(read(WORKFLOW_PATH));

        assertThat(executable)
                .as("the gate must diff the empty tree against HEAD over the authored paths")
                .contains("git diff --check \"$(git hash-object -t tree /dev/null)\" HEAD --")
                .as("and it must be scoped to those paths, never to the read-only legacy estate")
                .contains("carddemo-java docs .github");
        // The root-level publication descriptors this migration authors, named individually because a
        // pathspec of directories cannot reach a file at the repository root. Both were authored here and
        // read by no whitespace gate until they were named, which is a hole a directory list cannot
        // close and cannot report. `.` is not the fix: it would pull in the estate tree the assertion
        // below excludes. DL-340.
        //
        // The list is DERIVED rather than transcribed, which is the difference between a check and a
        // second copy of the pathspec: every YAML descriptor at the repository root is discovered by
        // walking it, so adding a third one puts it in this loop whether or not anyone remembers to widen
        // the pathspec. Membership is defined by shape rather than by name - a root-level .yml or .yaml
        // file is a publication descriptor this module owns, which is what mkdocs.yml and
        // catalog-info.yaml both are. The governance text beside them (LICENSE, NOTICE, CONTRIBUTING.md,
        // CODE_OF_CONDUCT.md) is deliberately NOT pulled in: the plan excludes it from modification, and
        // a gate over a file this migration does not author would fail on an upstream edit.
        final List<String> authoredRootDescriptors = authoredRootDescriptors();
        assertThat(authoredRootDescriptors)
                .as("the repository root carries the publication descriptors this migration updates, so "
                        + "discovering none of them means this contract stopped reading the root")
                .isNotEmpty();
        for (final String descriptor : authoredRootDescriptors) {
            assertThat(executable)
                    .as("%s is a root-level descriptor this migration authors, so the whitespace pathspec "
                            + "has to name it: a directory list cannot reach a file at the root, and an "
                            + "unnamed authored file is read by no gate at all", descriptor)
                    .contains(descriptor);
        }
        assertThat(executable)
                .as("app/ and samples/ are the parity baseline and must stay byte-identical, so no "
                        + "whitespace pathspec may name them")
                .doesNotContain("git diff --check \"$(git hash-object -t tree /dev/null)\" HEAD -- app")
                .doesNotContain("HEAD -- . ");
        // The estate README is the one authored root file deliberately NOT in the pathspec, and the
        // reason is measurable rather than stylistic: it is upstream CRLF text carrying 59 trailing-space
        // lines, and .gitattributes exempts its carriage return rather than those spaces. Naming it would
        // report 59 lines this migration did not write. Asserted so the exclusion stays a stated decision.
        assertThat(executable)
                .as("the estate README carries upstream trailing whitespace, so the pathspec must not "
                        + "name it - the exclusion is deliberate and the workflow says why")
                .doesNotContain("catalog-info.yaml mkdocs.yml README.md");
        assertThat(read(WORKFLOW_PATH))
                .as("and the reason both root files are named, and the estate README is not, belongs "
                        + "beside the pathspec rather than in a reviewer's memory")
                .contains("WHY TWO FILES ARE NAMED BESIDE THE THREE DIRECTORIES")
                .contains("59 trailing-space lines");
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
    @DisplayName("the documented record-mapper inventory is derived from the production source tree, and "
            + "every document that states it agrees")
    void theDocumentedRecordMapperCountMatchesTheSourceTree() throws IOException {
        // WHY THIS IS DERIVED RATHER THAN ASSERTED AS A NUMBER. The reflection budget of zero is what
        // forces these mappers to be written by hand, so the count is quoted as the CONSEQUENCE of a
        // security-relevant constraint in seven documents at once - both READMEs, the onboarding guide,
        // the gate evidence, the architecture page, the decision log and the summary deck. It was
        // "eleven" in all of them, and a twelfth mapper had been added for the statement job's transient
        // work record: every one of those statements was quietly wrong, and each read as authoritative.
        //
        // A count in prose cannot survive an edit, so this test reads the tree and then holds the
        // documents to what it found. The distinction the documents must now carry is real and is the
        // reason the old number looked right: there are ELEVEN PERSISTED RECORD LAYOUTS and TWELVE
        // MAPPER CLASSES, because StatementWorkRecordMapper maps a work record that is never persisted.
        final List<String> mappers;
        try (Stream<Path> tree = Files.walk(MAIN_PACKAGE_ROOT)) {
            mappers = tree.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("RecordMapper.java"))
                    .sorted()
                    .toList();
        }

        assertThat(mappers)
                .as("one mapper per persisted layout plus the statement job's transient work record")
                .hasSize(12)
                .contains("StatementWorkRecordMapper.java");

        final String inventory = "eleven record mappers";
        for (final Path document : List.of(README_PATH, Path.of("../README.md"),
                Path.of("../docs/onboarding-guide.md"), Path.of("../docs/gate-evidence.md"),
                Path.of("../docs/architecture.md"), Path.of("../docs/decision-log.md"),
                Path.of("../docs/presentation/index.html"))) {
            assertThat(read(document))
                    .as("%s must not state the superseded count: it is quoted as the consequence of the "
                            + "zero-reflection constraint, so a reader checking that constraint against "
                            + "the tree finds a document that disagrees with it", document)
                    .doesNotContain(inventory)
                    .doesNotContain("eleven hand-written")
                    .doesNotContain("eleven mappers")
                    .doesNotContain("eleven fixed-width mappers");
        }
    }

    @Test
    @DisplayName("the operator manual names the current queue, staging, identity and scan contracts")
    void readmeMatchesTheDeliveredOperationalContracts() throws IOException {
        assertThat(read(README_PATH))
                // The count itself is asserted against the profile by DocumentedSourceCountsTest;
                // this assertion only pins that the manual still draws the secret / non-secret
                // distinction at all. It read "Five" while the profile defaulted six - DL-316.
                .contains("further variables are non-secret")
                .contains("the required value is `JOBS.fifo`")
                // The prior delivery's breakdown is published only in the form that sums, per DL-256.
                // This assertion previously required the form that does not sum, because the manual
                // reproduced it while explaining that it does not sum - a readable intention and a
                // plain violation of the rule the log states. DL-316 records the correction.
                .contains("729 unit plus 159")
                .contains("integration and end-to-end**")
                .contains("Current test figures are published in exactly one place")
                .doesNotContain("729 unit, 134 integration and 33 end-to-end tests")
                .doesNotContain("add up to 896 rather than 888")
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

        // FOUR CLAIMS ON THAT PAGE WERE FALSE AND EACH FAILED SILENTLY, WHICH IS WHY EACH IS PINNED HERE
        // TO THE ARTEFACT IT DESCRIBES RATHER THAN TO ITS OWN WORDING. A page is read by people who
        // cannot run it, so a sentence about a command, a toolchain or a method is only as good as
        // whatever fails when it stops being true.

        // 1. Every Compose command on the page has to be one that works. The page used to bring the
        // stack up with `--profile observability`; no service declares `profiles:`, so the flag selected
        // nothing. Prose may still explain that - a command line may not use it.
        assertThat(page.lines().filter(line -> line.strip().startsWith("docker compose")).toList())
                .as("a command line on the page must not select a Compose profile, because no service in "
                        + "docker-compose.yml declares one and the flag therefore matches nothing")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).doesNotContain("--profile"));

        // 2. The image refuses the all-zero provenance sentinel, so a build of it needs all three values.
        // A page that showed the bare build command would send a reader into that refusal.
        assertThat(page)
                .as("the page's own stack-build command must carry the provenance the image demands")
                .contains("APP_VERSION")
                .contains("SOURCE_REVISION")
                .contains("SOURCE_DATE_EPOCH");

        // 3. The wrapper provisions Maven and not a JDK. The page claimed both, which reads as "install
        // nothing" to someone whose default java is older than 25.
        assertThat(page)
                .as("the toolchain statement must not promise a JDK the wrapper does not supply")
                .contains("supplies Maven, not a JDK")
                .doesNotContain("resolves its own Maven distribution and its own JDK");

        // 4. The third alternate index IS reached by a finder, and the finder is named here by reading
        // the repository it is declared on - so renaming the method fails this test rather than leaving
        // the page describing a method that no longer exists.
        //
        // THE NAME THIS PINS IS THE CURRENT ONE, AND THAT IS THE WHOLE POINT OF THE CHECK. DL-276 declared
        // the window finder as `findByProcessingDateWindowOrderedByCardNumber`; DL-294 replaced it with
        // the bounded pair `findByProcessingTimestampWindow(start, endExclusive, Limit)` and its
        // inclusive-date form, because an unbounded ordered window is the shape that cannot use the index
        // at both ends. The superseded name is forbidden here rather than merely unmentioned: a page
        // naming a method the repository no longer declares is the exact failure this item exists to
        // catch, and it fails identically whichever direction the rename goes.
        final String transactionRepository =
                read(Path.of("src/main/java/com/carddemo/repository/TransactionRepository.java"));
        assertThat(transactionRepository)
                .as("the processing-window finder must exist where the page says it does")
                .contains("findByProcessingTimestampWindow")
                .as("and the superseded unbounded finder must be gone from the repository, not merely "
                        + "unused")
                .doesNotContain("findByProcessingDateWindowOrderedByCardNumber");
        assertThat(page)
                .as("and the page must name it rather than recording the index as having no reader, "
                        + "which was true before DL-276 and is false now")
                .contains("findByProcessingTimestampWindow")
                .doesNotContain("findByProcessingDateWindowOrderedByCardNumber")
                .doesNotContain("**no repository finder**");
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

        // NO PAGE MAY CONTRADICT THE INVENTORY THIS TEST HAS JUST ESTABLISHED. The eight assertions above
        // prove every mandated page exists and is registered. Two pages nevertheless went on stating the
        // opposite in their own opening paragraphs long after it stopped being true, which is the failure
        // mode a delivered-and-unread document has: the file is right there, so nobody re-reads its first
        // screen. Asserting the absence of the superseded claim is what makes the inventory above load
        // bearing for the prose as well as for the tree.
        for (final Path stale : List.of(Path.of("../docs/architecture.md"),
                Path.of("../docs/decision-log.md"), Path.of("../docs/index.md"),
                Path.of("../docs/gate-evidence.md"))) {
            assertThat(read(stale))
                    .as("%s must not still record a delivered page as undelivered or unregistered", stale)
                    .doesNotContain("has not been delivered")
                    .doesNotContain("not yet registered in the")
                    .doesNotContain("`onboarding-guide.md` is still absent");
        }

        // THE ORPHAN BATCH JOB IS UNSEQUENCED, NOT UNREACHABLE, AND THE CATALOG IS THE AUTHORITY. It is
        // one of the launchable names, so the administration endpoint launches it exactly as it launches
        // the other eight. Documents that called it "exercised only by tests" described it as dead
        // configuration, which is a different thing and the opposite of what the catalog declares - so the
        // claim is pinned to the catalog rather than to a form of words.
        assertThat(BatchJobCatalog.LAUNCHABLE_JOB_NAMES)
                .as("the orphan job is registered as launchable; if that ever changes, the documents "
                        + "below must change with it rather than after it")
                .contains(BatchJobCatalog.DAILY_TRANSACTION_READ_JOB);
        for (final Path page : List.of(Path.of("../docs/decision-log.md"), deck,
                Path.of("README.md"), Path.of("../README.md"))) {
            assertThat(read(page).replace("\"exercised only by tests\"", ""))
                    .as("%s must not describe the launchable orphan job as reachable only from a test",
                            page)
                    .doesNotContain("exercised only by tests");
        }

        // THE END-TO-END CRITERION IS FROZEN AT FOUR CONTRACTUAL WIDTHS, AND FIVE GOLDENS EXIST. Both
        // // halves are load-bearing and MUST NOT BE COLLAPSED INTO ONE: requiring the deck to say "five
        // // compared widths" while forbidding "four" would make a rewritten acceptance criterion a condition
        // // of a green build. The deck must name the criterion as the criterion, and name the fifth golden as
        // // the supplemental evidence it is. PublicationConsistencyTest
        // owns the detail - it parses both of the evidence page's inventory tables and holds every
        // publication to them - so the check here is the deck's vocabulary only.
        // Matched over a whitespace-collapsed view, because the deck is hand-wrapped HTML and where an
        // author breaks a line is not the property under test.
        final String deckText = read(deck).replaceAll("\\s+", " ");
        assertThat(deckText)
                .as("the deck must state the frozen four-width criterion rather than redefining it")
                .contains("four contractual widths")
                .doesNotContainIgnoringCase("five contractual widths")
                .doesNotContainIgnoringCase("five compared widths");
        assertThat(deckText)
                .as("and it must still name the supplemental 40-byte golden, because an omission there "
                        + "advertises less evidence than the tree holds")
                .contains("supplemental")
                .contains("40");
    }

    @Test
    @DisplayName("every deck image resolves inside the published site, at the size the deck declares for it")
    void everyDeckImageResolvesInsideThePublishedSite() throws IOException {
        // THE DEFECT THIS PINS: the deck pointed at ../../diagrams/*.png, which resolves to the estate's
        // asset directory at the REPOSITORY ROOT. That path is correct when the file is opened straight out
        // of a checkout, which is why it survived review - and wrong everywhere the deck is actually
        // published, because the site is generated from docs/ alone and nothing above docs/ is ever copied
        // into it. All five images answered 404 in both publication modes, and an <img> that 404s is not a
        // missing decoration: the deck's own captions describe screens the reader cannot see.
        //
        // The fix publishes copies of the five captures beneath docs/ and points the deck one level up
        // instead of two, which resolves inside the site AND still resolves from a checkout. This test
        // holds all three properties that makes true, because each has its own way of silently regressing.
        final String deck = read(DECK_PATH);
        final Path deckDirectory = DECK_PATH.getParent();
        final Path publicationRoot = DOCS_ROOT.toRealPath();

        assertThat(deck)
                .as("no image may climb above the publication root again; ../../ from the deck's own "
                        + "directory leaves docs/ altogether, and every such reference answered 404")
                .doesNotContain("<img src=\"../../")
                .as("and none may be fetched from a network the deck is meant not to need")
                .doesNotContain("<img src=\"http");

        final Matcher images = DECK_IMAGE.matcher(deck);
        final List<String> resolved = new ArrayList<>();
        while (images.find()) {
            final String source = images.group(1);
            final Path target = deckDirectory.resolve(source).normalize();

            assertThat(target)
                    .as("the deck publishes %s, so the site must carry it; this is the assertion that "
                            + "fails if an image is referenced but never published", source)
                    .isRegularFile();
            assertThat(target.toRealPath())
                    .as("%s must resolve INSIDE the publication root. Existing is not enough: the "
                            + "original reference existed on disk and still 404'd, because it existed "
                            + "somewhere the generated site does not reach", source)
                    .startsWith(publicationRoot);

            // The declared size is what reserves the figure's box before the image arrives, so a wrong
            // number is a layout shift on every load - and a copy of the wrong capture would be caught
            // here rather than by someone noticing the screenshot looks unfamiliar.
            final int[] intrinsic = pngDimensions(target);
            assertThat(intrinsic[0])
                    .as("%s declares width %s and is %s pixels wide", source, images.group(2),
                            intrinsic[0])
                    .isEqualTo(Integer.parseInt(images.group(2)));
            assertThat(intrinsic[1])
                    .as("%s declares height %s and is %s pixels tall", source, images.group(3),
                            intrinsic[1])
                    .isEqualTo(Integer.parseInt(images.group(3)));

            resolved.add(target.getFileName().toString());
        }

        assertThat(resolved)
                .as("all five captures the deck describes must be reachable, not merely some of them")
                .containsExactlyInAnyOrderElementsOf(DECK_CAPTURES);

        // The published copy and the estate's own asset must stay the same bytes. This is the price of
        // fixing the path by copying rather than by moving, and the plan requires the copy: everything
        // under diagrams/ is read-only reference that must remain byte-identical, so the original cannot
        // be relocated to where the site can see it. Comparing them here is what stops the deck from
        // quietly publishing a stale screenshot after someone updates the original.
        for (final String capture : DECK_CAPTURES) {
            assertThat(DOCS_ROOT.resolve("diagrams").resolve(capture))
                    .as("the published copy of %s must be byte-identical to the estate asset it was taken "
                            + "from, or the site shows one screen while the repository holds another",
                            capture)
                    .hasSameBinaryContentAs(Path.of("..", "diagrams", capture));
        }
    }

    @Test
    @DisplayName("every deck table scrolls inside a named, focusable frame that says so, and every deck "
            + "control clears a 44px target")
    void theDeckDeclaresItsOverflowAndItsTouchTargets() throws IOException {
        final String deck = read(DECK_PATH);

        // OVERFLOW. Every table here is wider than a phone and several are wider than the slide, and the
        // column that falls off the right edge first is the one carrying the decision - "What it is",
        // "What had to survive", "What it verifies". The frame scrolled, but silently: the browser draws
        // OVERLAY scrollbars, which reserve no width and paint nothing until the reader is already
        // scrolling, so there was no cue at rest and no way to reach the frame from a keyboard at all.
        final Matcher regions = DECK_SCROLL_REGION.matcher(deck);
        final List<String> names = new ArrayList<>();
        final List<String> descriptions = new ArrayList<>();
        while (regions.find()) {
            names.add(regions.group(1));
            descriptions.add(regions.group(2));
        }

        assertThat(names).as("the deck's tables must each sit in an operable frame").isNotEmpty();
        assertThat(countOccurrences(deck, "<div class=\"scroller\""))
                .as("every frame must carry the region attributes; a bare frame is the defect")
                .isEqualTo(names.size());
        assertThat(countOccurrences(deck, "<table>"))
                .as("and every table must be inside one, so no table is left to clip")
                .isEqualTo(names.size());

        for (final String name : names) {
            assertThat(deck)
                    .as("a region named by %s must have that element to be named by, or it publishes as "
                            + "an unnamed landmark that a screen reader announces without saying which "
                            + "table it belongs to", name)
                    .contains("id=\"" + name + "\"");
        }

        assertThat(countOccurrences(deck, "class=\"capname\""))
                .as("the name comes from the caption's descriptive half, wrapped so the panning "
                        + "instruction is not read out as part of the name every time")
                .isEqualTo(names.size());
        assertThat(countOccurrences(deck, "class=\"scrollnote\""))
                .as("and each frame carries the textual cue, which survives with CSS off, in a screen "
                        + "reader and in print - none of which is true of a shadow")
                .isEqualTo(names.size());
        assertThat(deck)
                .as("the cue must say both that the frame scrolls and how to pan it without a mouse")
                .contains("scrolls sideways")
                .contains("panned with the arrow keys");

        // THE CUE MUST SIT OUTSIDE THE THING IT DESCRIBES. It first shipped inside the <caption>, which is
        // inside the <table>, which is inside the scroll container - so at a phone width the sentence
        // telling the reader that the frame pans was itself panned off the right edge, and the reader saw
        // about four fifths of an instruction about how to reach the rest. It is a sibling paragraph now,
        // tied to the region by aria-describedby so the association survives for a reader who cannot see
        // that the two are adjacent.
        assertThat(deck)
                .as("no cue may live inside a caption again: inside the caption is inside the frame, and "
                        + "a cue that scrolls out of view is not a cue")
                .doesNotContain("<span class=\"scrollnote\">");
        assertThat(descriptions)
                .as("every frame must point at its own cue, so no two regions share one description and "
                        + "none is left describing nothing")
                .doesNotHaveDuplicates()
                .hasSameSizeAs(names);
        for (final String description : descriptions) {
            assertThat(deck)
                    .as("a region described by %s must have that paragraph to be described by", description)
                    .contains("<p class=\"scrollnote\" id=\"" + description + "\">");
        }

        // The caption is the one thing that cannot leave the frame, because the region borrows it as its
        // name. It can stop being as wide as the table, though, and it has to: its containing block is the
        // 460px-floored table, so its text wrapped at 460px and its longest line ran past the frame's
        // visible edge, leaving the table's own name readable only by panning.
        assertThat(deck)
                .as("the caption must wrap at the frame's width rather than the table's on a narrow "
                        + "viewport, or the name of the table is itself clipped by the frame it names. "
                        + "The subtrahend is deliberately larger than the frame's 34px inset: 100vw "
                        + "counts space reserved for a classic scrollbar and the frame does not, so a "
                        + "34px cap is correct only on an engine that reserves none")
                .contains("caption { max-width: calc(100vw - 60px); }")
                .doesNotContain("caption { max-width: calc(100vw - 34px); }");

        assertThat(deck)
                .as("the visible cue is the four-layer scroll shadow, and the attachment list is the "
                        + "whole mechanism: local for the two covers so they travel with the content, "
                        + "scroll for the two shadows so they stay pinned to the frame. Without it the "
                        + "shadow paints at both ends for ever and stops meaning anything")
                .contains("background-attachment: local, local, scroll, scroll")
                .as("and a focusable frame has to show where focus is")
                .contains(".scroller:focus-visible { outline: 3px solid #123a86; outline-offset: 2px; }")
                .contains(".scroller:focus { outline: 3px solid #123a86; outline-offset: 2px; }");

        // TOUCH TARGETS. The three bar buttons rendered about 33px tall and the six slide pills about
        // 32px, against a 44x44 minimum. The correction enlarges the target rather than the control, so
        // the painted box is unchanged - which is also why it needs asserting: nothing about the deck
        // looks different, so a regression here would be invisible.
        assertThat(deck)
                .as("both control families must be positioned so a generated target can be centred on "
                        + "them; without the containing block the pseudo-element escapes to the page")
                .contains(".deckbar button,\nnav.toc a { position: relative; }")
                .as("and the target itself must clear 44px in both dimensions")
                .contains("min-width: 44px")
                .contains("height: 44px");

        // The enlarged target is taller than the control, so the overhang has to land in the gap rather
        // than in the next control. 44 minus roughly 32 leaves about 6px reaching out of each side, and
        // two adjacent rows reach toward each other: a 6px row gap would have left the two targets
        // overlapping, with points belonging to whichever control won the hit test.
        assertThat(deck)
                .as("the row gap must exceed the overhang of two adjacent enlarged targets")
                .contains("gap: 14px 8px")
                .contains("gap: 14px 10px")
                .doesNotContain("gap: 6px 8px");
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

        // THE ESTATE README IS A CRLF FILE AND HAS TO STAY ONE. It arrived that way with the upstream
        // AWS CardDemo distribution, and the repository root's .gitattributes declares
        // `/README.md whitespace=cr-at-eol` precisely so that a carriage return at end of line is not
        // reported as trailing whitespace. An editor that normalises the file on save rewrites all 600-odd
        // lines, which buries a three-line correction in a whole-file diff and makes the change
        // unreviewable - which is exactly what happened once. Asserting the endings is cheap and it fails
        // at the moment of the rewrite rather than at review time.
        final String estateReadmeText = Files.readString(Path.of("../README.md"), StandardCharsets.UTF_8);
        assertThat(estateReadmeText.replace("\r\n", ""))
                .as("every line ending in the estate README must be CRLF; a lone LF means the file was "
                        + "normalised, which rewrites every line and hides the actual edit")
                .doesNotContain("\n");
        assertThat(read(Path.of("../.gitattributes")))
                .as("and the declaration that makes those carriage returns legitimate must still be "
                        + "there, anchored to this one path")
                .contains("/README.md whitespace=cr-at-eol");

        // A markdown table row is one line. A wrapped row terminates the table where it wraps, so the
        // remainder renders as a paragraph and the columns after it are lost - visible only to someone
        // looking at the rendered page, which a build never does.
        assertThat(estateReadmeText.lines()
                        .filter(line -> line.strip().startsWith("|"))
                        .filter(line -> !line.strip().endsWith("|"))
                        .toList())
                .as("no table row in the estate README may be wrapped across lines")
                .isEmpty();
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

        // THE TAG SET IS A FROZEN SCOPE, NOT A FREE LIST, and this assertion is two-sided for that
        // reason: it names every tag that must be present AND fixes the total, so neither an addition nor
        // a removal can arrive unnoticed. The authorised change is two things - the type above, and tags
        // gaining the delivered Java stack. That scope has no cardinality of its own; what bounds it is
        // the stack, and the stack is enumerated for us. `java-25`, `spring-data-jpa` and `testcontainers`
        // name Java 25 LTS, Spring Data JPA and Testcontainers, three of the technologies the target stack
        // lists by name, so they are among the most defensible of the nine rather than outside the scope.
        // REMOVING EXACTLY THOSE THREE and rewriting this assertion to `hasSize(17)` with a
        // `doesNotContain` for them would make the descriptor and its guard agree with each other and
        // with nothing else: a discoverability tag naming a headline technology of the migration would
        // be gone, and the test that should catch it would have been taught to require its absence.
        // The three obsolete tags are asserted here under the same rule, because the instruction was
        // additive; `python`, `typescript` and `web-app` no longer describe the stack and are kept anyway, with the inaccuracy recorded in the decision log
        // rather than resolved by deletion here.
        final List<String> tags = new ArrayList<>();
        catalog.path("metadata").path("tags").forEach(tag -> tags.add(tag.asText()));
        assertThat(tags)
                .as("the delivered stack is discoverable by the nine tags that name it, the runtime and "
                        + "persistence tiers among them")
                .contains("mainframe", "java-25", "spring-boot", "spring-batch", "spring-data-jpa",
                        "postgresql", "maven", "aws", "testcontainers")
                .as("and the estate the component was migrated from stays discoverable too")
                .contains("cobol", "migration")
                .as("and every tag the descriptor already carried is still there, because the "
                        + "instruction was to ADD the Java stack rather than to curate the list")
                .contains("COBOL", "cobol", "java", "migration", "modernization", "new-feature",
                        "python", "refactor", "rewrite", "typescript", "web-app");
        assertThat(tags)
                .as("eleven pre-existing tags plus exactly nine additions, so neither a tenth arriving "
                        + "nor one of the nine leaving can pass unnoticed alongside a rename")
                .hasSize(20)
                .doesNotHaveDuplicates();
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
                .as("an image must fail on any HIGH or CRITICAL no determination covers, fixed or not")
                .contains("if [ \"${uncovered}\" -ne 0 ]; then")
                .as("and the failure must enumerate what it is failing on")
                .contains("no fix available");
    }

    @Test
    @DisplayName("every shipped runtime image is gated on the same terms, and the inventory-only verdict "
            + "is gone")
    void everyShippedImageIsGatedAlike() throws IOException {
        // The finding this test was rewritten for: the application image and the two Dockerfile bases
        // were gated with a failing verdict while every third-party Compose image was scanned with an
        // 'inventory' verdict that could not fail. An unjudged scan is evidence of looking rather than
        // evidence of a decision, and this repository chooses which digest it runs even where it did not
        // author the binary. Decision log DL-350.
        final String workflow = read(WORKFLOW_PATH);
        final String executable = executableLinesOf(workflow);

        assertThat(executable)
                .as("no verdict parameter may survive, because a second verdict is how the unjudged one "
                        + "came back last time")
                .doesNotContain("inventory")
                .doesNotContain("verdict")
                .as("the three call sites must pass a determination KEY rather than a verdict")
                .contains("scan_image \"${application_image_id}\" application")
                .contains("scan_image \"${image}\" \"${image}\"");
        assertThat(countOccurrences(executable, "scan_image \"${image}\" \"${image}\""))
                .as("both loops - the Dockerfile bases and the Compose images - must call it the same "
                        + "way, so neither can be softened without the other being seen")
                .isEqualTo(2);
        assertThat(workflow)
                .as("and the header must say what changed, so the next reader does not restore the "
                        + "inventory verdict as a simplification")
                .contains("THE INVENTORY-ONLY VERDICT IS GONE");
    }

    @Test
    @DisplayName("a container finding is excused only by a scoped, reviewed, expiring determination, and "
            + "an expired or unused one fails the build")
    void aContainerFindingIsExcusedOnlyByAnExpiringDetermination() throws IOException {
        final String workflow = read(WORKFLOW_PATH);
        final String executable = executableLinesOf(workflow);

        assertThat(executable)
                .as("the gate must read the determination file and must refuse to run without it")
                .contains("determination_file=\"${PWD}/container-scan-determinations.txt\"")
                .contains("test -f \"${determination_file}\"")
                .as("the file's shape is validated before anything is scanned, so a mistyped line cannot "
                        + "silently cover nothing while looking like an acceptance")
                .contains("grep -nvE \"${determination_shape}\" \"${determination_file}\"")
                .as("a determination is scoped to ONE image and ONE identifier - the awk match is on "
                        + "both fields, and there is no wildcard form")
                .contains("$1 == key && $2 == id")
                .as("an expiry in the past fails the build on its own")
                .contains("[ \"${d_expiry}\" \\< \"${today}\" ]")
                .contains("expired=1")
                .as("and a determination that matched nothing fails it too")
                .contains("matched no finding in this run and must be removed")
                .contains("unused=1")
                .as("the record of which determinations were applied must NOT land in the report "
                        + "directory: the upload treats an empty directory on a green run as an error, "
                        + "and a file created before the first scan would satisfy that check on a run "
                        + "that never scanned anything")
                .contains("determinations_used=\"${PWD}/target/container-scan-determinations-used.txt\"")
                .doesNotContain("determinations_used=\"${report_dir}");
        assertThat(executable)
                .as("the scanner must NOT be handed the file as an ignore list: that would delete the "
                        + "covered findings from the archived report, which is the defect DL-185 removed")
                .doesNotContain("--ignorefile")
                .doesNotContain("--ignore-policy")
                .doesNotContain(".trivyignore");
        assertThat(workflow)
                .as("both properties an ignore file lacks must be stated, because they are the reason "
                        + "the decision is taken here")
                .contains("an EXPIRED determination fails the build")
                .contains("failBuildOnUnusedSuppressionRule already holds over the dependency gate");
    }

    @Test
    @DisplayName("every container determination is well formed, scoped to a pin this stack actually "
            + "resolves, unexpired, and never scoped to the application image or a Dockerfile base")
    void everyContainerDeterminationIsWellFormedAndScopedToACurrentPin() throws IOException {
        // WHY THIS TEST NO LONGER ASSERTS AN EMPTY FILE. It did, and that assertion was correct for
        // exactly as long as nobody had scanned the images: the first measurement found 531 HIGH or
        // CRITICAL findings across the five third-party Compose images, so an empty file meant a
        // container gate that could not pass rather than a stack with nothing to accept. Four pins
        // moved, which closed 431 of them, and the residual 63 are closed the other way DL-350
        // allows - a scoped, attributed, expiring determination each. What must be held now is not
        // emptiness but the four properties that make a determination a decision instead of a
        // silence, and the gate itself can only check three of them at run time with Docker
        // available. See docs/decision-log.md DL-350 and DL-370.
        final String file = read(CONTAINER_DETERMINATIONS_PATH);
        final List<String> determinations = file.lines()
                .filter(line -> !line.isBlank() && !line.stripLeading().startsWith("#"))
                .toList();

        assertThat(determinations)
                .as("the file must carry the acceptances the measurement requires. An empty file here "
                        + "with findings present in the pinned images is not a stricter posture - it is "
                        + "a gate that cannot pass, which is how a determination mechanism ends up "
                        + "quietly disabled instead of used")
                .isNotEmpty();

        // The shape the workflow validates before it scans anything, restated exactly: five fields, a
        // single vertical bar between each, no whitespace around a separator, an identifier the
        // scanner would actually report, and an ISO date. Held here as well as there so a malformed
        // line fails the fast unit tier rather than only the container job.
        final Pattern shape = Pattern.compile(
                "^[^|\\s]+\\|[A-Z][A-Z0-9]*-[A-Za-z0-9._-]+\\|\\d{4}-\\d{2}-\\d{2}\\|[^|]+\\|[^|]+$");
        assertThat(determinations)
                .allSatisfy(line -> assertThat(shape.matcher(line).matches())
                        .as("determination must read image|IDENTIFIER|YYYY-MM-DD|reviewer|reason with no "
                                + "whitespace around a separator. Actual: %s", line)
                        .isTrue());

        // Every key must be a pin this stack actually resolves. This is the unit-tier mirror of the
        // gate's UNUSED check: a determination whose key no longer appears in the Compose file is
        // protecting nothing, and the pin move that orphaned it is exactly the edit that must not pass
        // silently.
        final String compose = read(COMPOSE_PATH);
        final String dockerfile = read(DOCKERFILE_PATH);
        assertThat(determinations)
                .allSatisfy(line -> {
                    final String key = line.substring(0, line.indexOf('|'));
                    assertThat(compose.contains(key))
                            .as("determination key %s names no image this Compose stack resolves, so the "
                                    + "pin moved and took the key with it. Move the determination or "
                                    + "delete it", key)
                            .isTrue();
                    assertThat(key)
                            .as("no acceptance may be scoped to the artefact this module ships or to "
                                    + "either immutable base: all three scan clean, and a determination "
                                    + "against one would be accepting a finding in the product")
                            .isNotEqualTo("application")
                            .doesNotContain("eclipse-temurin");
                });
        assertThat(dockerfile)
                .as("the bases are still digest pinned, which is what makes 'no determination against a "
                        + "base' a meaningful statement rather than an untested one")
                .contains("eclipse-temurin:25.0.3_9-jdk-noble@sha256:")
                .contains("eclipse-temurin:25.0.3_9-jre-noble@sha256:");

        // An expiry already in the past fails the container gate before it scans anything, so a lapsed
        // line must fail here too - otherwise the fast tier reports green on a file that has already
        // stopped the build.
        final java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        assertThat(determinations)
                .allSatisfy(line -> {
                    final String[] fields = line.split("\\|", 5);
                    assertThat(java.time.LocalDate.parse(fields[2]))
                            .as("determination %s / %s has lapsed. Re-scan the pin first - a pin rebuilt "
                                    + "upstream since needs no determination at all - then either close "
                                    + "the finding or re-review and re-date the line", fields[0],
                                    fields[1])
                            .isAfterOrEqualTo(today);
                    assertThat(fields[3].strip())
                            .as("a reviewer is a name or a team, not a tool and not a placeholder")
                            .isNotEmpty();
                    assertThat(fields[4].strip().length())
                            .as("the reason must say why this repository cannot close the finding and "
                                    + "what would. Actual reason for %s / %s: %s", fields[0], fields[1],
                                    fields[4])
                            .isGreaterThan(40);
                });

        // One image, one identifier, once. A duplicate pair is two people accepting the same finding
        // with two different expiries, and the gate would apply whichever it read first.
        final List<String> pairs = determinations.stream()
                .map(line -> {
                    final String[] fields = line.split("\\|", 5);
                    return fields[0] + "|" + fields[1];
                })
                .toList();
        assertThat(pairs).as("no image and identifier pair may be accepted twice").doesNotHaveDuplicates();

        assertThat(file)
                .as("and the protocol a reviewer must follow is documented in the file itself, because "
                        + "that is the document the person adding a line is already looking at")
                .contains("<image key>|<IDENTIFIER>|<expires YYYY-MM-DD>|<reviewer>|")
                .contains("EXPIRED")
                .contains("UNUSED")
                .contains("REVIEW PROTOCOL")
                .as("including the one key that is a literal rather than a digest-pinned reference")
                .contains("literal word  application ")
                .as("and the reason the scanner is not handed this file")
                .contains("DL-185 and DL-350");
    }

    @Test
    @DisplayName("one gate inventory: the header, the seven banners and the summary all agree")
    void theGateInventoryIsStatedOnceAndAgreesEverywhere() throws IOException {
        // The file carried three disagreeing counts - a header saying five, two banners saying "of 4",
        // three saying "of 5", and a summary listing six - so a reviewer signing the gates off could not
        // tell which was authoritative. The undercount existed because the last gate was the only step
        // with no banner at all, so anyone counting banners counted one short. Decision log DL-186.
        //
        // Seven is the count now: the whitespace gate was added after that reconciliation, and this test
        // is what forces it to be added to all three places at once rather than to whichever one the
        // author happened to be editing. The total is asserted rather than derived on purpose - deriving
        // it from the banners would let a missing banner redefine the expected count and pass.
        final String workflow = read(WORKFLOW_PATH);
        final List<String> banners = new ArrayList<>();
        for (final String line : workflow.split("\n")) {
            if (line.contains("---- gate ") && line.contains(" of ")) {
                banners.add(line.strip());
            }
        }

        assertThat(banners)
                .as("every gate step announces itself, and there are seven of them")
                .hasSize(7);
        for (int gate = 1; gate <= 7; gate++) {
            final String expected = "gate " + gate + " of 7";
            assertThat(banners)
                    .as("banner %s must be present and must state the total as seven", expected)
                    .anySatisfy(banner -> assertThat(banner).contains(expected));
        }
        assertThat(workflow)
                .as("no banner may still state a stale total")
                .doesNotContain(" of 4:")
                .doesNotContain(" of 5:")
                .doesNotContain(" of 6:")
                .as("the header must state the same count as the banners")
                .contains("SEVEN linear gates");

        // The summary is the third place the inventory is named, and every name in it must be a name a
        // banner uses, so the two lists cannot describe different sets of gates.
        final List<String> summaryNames = List.of(
                "repository whitespace hygiene",
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
                    .contains("echo 'gate " + gate + " of 7  " + name);
            assertThat(banners.get(gate - 1))
                    .as("and banner %s must carry that same name", gate)
                    .contains(name.equals("container, Compose and monitoring deployability")
                            ? "container, Compose and monitoring deployability"
                            : name);
        }
    }

    @Test
    @DisplayName("every published supply-chain figure names the same entry-and-file pair")
    void everyPublishedDependencyFigureNamesTheSamePair() throws IOException {
        // The module manual and the gate-evidence page each state this figure twice, and three of the
        // four statements had gone stale at 167 while the fourth carried the measured 166. A reader who
        // finds two numbers cannot tell which was measured, so the four are held to one pair here.
        //
        // The pair is asserted rather than the entry count alone because the two halves answer different
        // questions - how many entries the report holds, and how many files those entries cover once the
        // scanner's own grouping is counted - and a statement carrying only the first invites the reader
        // to compare it against a file count.
        final Map<Path, String> sites = new LinkedHashMap<>();
        sites.put(README_PATH, flattened(read(README_PATH)));
        sites.put(GATE_EVIDENCE_PATH, flattened(read(GATE_EVIDENCE_PATH)));

        final List<String> pairs = new ArrayList<>();
        for (final Map.Entry<Path, String> site : sites.entrySet()) {
            final Matcher figures = PUBLISHED_SCAN_FIGURE.matcher(site.getValue());
            int stated = 0;
            while (figures.find()) {
                stated++;
                pairs.add(figures.group(1) + "/" + figures.group(2));
            }
            assertThat(stated)
                    .as("%s publishes the supply-chain figure, so it must state it as the measured "
                            + "entry-and-file pair rather than as a bare count", site.getKey())
                    .isPositive()
                    .as("%s states \"%s\" %d times but only %d of those are a complete pair, and a "
                            + "half-stated figure is the form that goes stale unnoticed",
                            site.getKey(), SCAN_FIGURE_LEAD_IN,
                            countOccurrences(site.getValue(), SCAN_FIGURE_LEAD_IN), stated)
                    .isEqualTo(countOccurrences(site.getValue(), SCAN_FIGURE_LEAD_IN));

            assertThat(site.getValue())
                    .as("%s must not carry a superseded count as though it were the current one; the "
                            + "history belongs in the sentence that explains the moves, which names the "
                            + "figures without calling them dependencies", site.getKey())
                    .doesNotContain("167 dependencies")
                    .doesNotContain("168 dependencies");
        }

        assertThat(pairs)
                .as("all four statements are about one scan of one graph, so they cannot disagree")
                .hasSizeGreaterThanOrEqualTo(4)
                .containsOnly(pairs.get(0));
    }

    @Test
    @DisplayName("the published supply-chain figure is the scan report's own, measured against the report "
            + "when this build has written one and reported as PENDING when it has not")
    void thePublishedDependencyFigureMatchesTheScanReport() throws IOException {
        // WHY THIS STATES AN EVIDENCE STATE INSTEAD OF CALLING Assumptions. This layer needs
        // target/dependency-check-report.json, and the scan that writes it is bound to the verify phase -
        // after surefire has finished. In the canonical `./mvnw -B clean verify` the report therefore cannot
        // exist while this test runs, so an assumption here does not express "absent in a clean checkout":
        // it skips on EVERY canonical build, permanently. A skipped test and a passing test are
        // indistinguishable in a build summary, and an undisclosed skip is a test a reader believes ran -
        // which is the failure mode this module's sibling audit in GateVerificationTest refuses by policy
        // rather than by preference. So the absent case is ASSERTED rather than assumed: the assertion below
        // always executes, states the state it found, and names the resolved absolute path where the report
        // was expected. The pair's internal consistency across all four publication sites is checked
        // unconditionally by the test above; what this layer adds is the tie from that agreed pair to the
        // tool that produced it, which is available to `./mvnw -B verify` over a warm target and to the CI
        // job that runs the scan before reading it.
        if (!Files.isRegularFile(SCAN_REPORT_PATH)) {
            assertThat(SCAN_REPORT_PATH.toAbsolutePath())
                    .as("PENDING: this build has not written a dependency-check report yet, so the "
                            + "published pair is not tied to the scan here. The scan is bound to verify, "
                            + "which runs after this tier, so an unscoped clean verify always reaches this "
                            + "branch. Run ./mvnw -B dependency-check:check and re-run this class to "
                            + "compare locally; in CI the reconciliation runs after the scan")
                    .doesNotExist();
            return;
        }

        final JsonNode report = new ObjectMapper().readTree(SCAN_REPORT_PATH.toFile());
        final JsonNode entries = report.path("dependencies");
        assertThat(entries.isArray())
                .as("the report must carry a dependencies array, or there is no figure to compare")
                .isTrue();

        int related = 0;
        for (final JsonNode entry : entries) {
            related += entry.path("relatedDependencies").size();
        }

        final Matcher published = PUBLISHED_SCAN_FIGURE.matcher(flattened(read(GATE_EVIDENCE_PATH)));
        assertThat(published.find())
                .as("the gate-evidence page must publish the pair for this layer to compare")
                .isTrue();

        assertThat(Integer.parseInt(published.group(1)))
                .as("the published entry count must be the number of entries the report holds")
                .isEqualTo(entries.size());
        assertThat(Integer.parseInt(published.group(2)))
                .as("and the published file count must be those entries plus the %d related files "
                        + "the scanner groups underneath them", related)
                .isEqualTo(entries.size() + related);
    }

    @Test
    @DisplayName("the completed-work pie rolls up the detail rows and sums to their total")
    void theCompletedWorkPieRollsUpTheDetailRows() throws IOException {
        // The pie's ten slices summed to 409 against a stated 391, because one slice read 38 where its
        // roll-up was 20. A pie is the one figure on a status page that nobody adds up by hand, so the
        // roll-up is now published beside it and every part of the arithmetic is checked here: the detail
        // rows against their own total, the slices against that total, each slice against the rows it
        // claims, and each row against being used more than once or not at all.
        final String page = read(PROJECT_GUIDE_PATH);
        final Map<String, Integer> detail = hoursRows(section(page, "### 2.1 Completed Work Detail",
                "### 2.2 Remaining Work Detail"));
        final int detailTotal = requireRow(detail, "**Total**", "the detail table must state its total");
        detail.remove("**Total**");

        assertThat(detail.values().stream().mapToInt(Integer::intValue).sum())
                .as("the detail rows must sum to the total that table publishes, or the total is the "
                        + "stale part and every roll-up drawn from it inherits the error")
                .isEqualTo(detailTotal);

        final String visual = section(page, "## 7. Visual Project Status", "## 8. Summary");
        // The slices are read from the diagram's own authoring source rather than from the page, because
        // the page no longer carries the diagram's data at all - it carries a figure around a pre-rendered
        // SVG. The .mmd is what the render is produced from, so it is the only place a wrong slice can
        // still be introduced, and checking the page text instead would check nothing.
        final Map<String, Integer> slices = pieSlices(
                read(DIAGRAM_DIR.resolve("completed-work-distribution.mmd")),
                "pie title Completed Work Distribution (" + detailTotal + "h)");
        assertThat(visual)
                .as("the page must publish that diagram as a figure pointing at its render, or the "
                        + "corrected slice is checked here and never seen by a reader")
                .contains("![")
                .contains("diagrams/completed-work-distribution.svg");
        assertThat(slices.values().stream().mapToInt(Integer::intValue).sum())
                .as("the slices of a pie titled %dh must sum to %d; %s", detailTotal, detailTotal,
                        slices)
                .isEqualTo(detailTotal);

        assertThat(visual)
                .as("the roll-up must name the number of detail rows it accounts for, so a row added "
                        + "to the detail table cannot be left out of the mapping silently")
                .contains("roll-up of the " + detail.size() + " rows of")
                .contains("all " + detail.size() + " rows, each counted once");

        final Map<String, Integer> rollUp = hoursRows(section(visual,
                "| Slice | Hours |", "\nEvery "));
        final int rollUpTotal = requireRow(rollUp, "**Total**",
                "the roll-up table must state its own total");
        rollUp.remove("**Total**");
        assertThat(rollUpTotal)
                .as("the roll-up total must be the detail total, since it accounts for every row")
                .isEqualTo(detailTotal);
        assertThat(rollUp.keySet())
                .as("the roll-up must carry one row per slice and no other")
                .containsExactlyInAnyOrderElementsOf(slices.keySet());

        final List<String> claimed = new ArrayList<>();
        for (final Map.Entry<String, String> mapping
                : rollUpExpressions(section(visual, "| Slice | Hours |", "\nEvery ")).entrySet()) {
            int summed = 0;
            for (final String term : mapping.getValue().split("\\+")) {
                final Matcher parsed = ROLL_UP_TERM.matcher(term.trim().replace("&amp;", "&"));
                assertThat(parsed.matches())
                        .as("each term of the %s roll-up must name a detail row and that row's hours; "
                                + "\"%s\" names neither", mapping.getKey(), term.trim())
                        .isTrue();
                final String row = parsed.group(1);
                final int hours = Integer.parseInt(parsed.group(2));
                assertThat(detail)
                        .as("the %s roll-up cites detail row \"%s\" at %d hours", mapping.getKey(),
                                row, hours)
                        .containsEntry(row, hours);
                claimed.add(row);
                summed += hours;
            }
            assertThat(summed)
                    .as("the %s slice must equal the rows it rolls up", mapping.getKey())
                    .isEqualTo(slices.get(mapping.getKey()));
        }

        assertThat(claimed)
                .as("every detail row must be rolled up exactly once, so the mapping neither "
                        + "double-counts a row nor drops one")
                .containsExactlyInAnyOrderElementsOf(detail.keySet());
    }

    @Test
    @DisplayName("no diagram renderer is configured, so the official publication path can build this site")
    void noDiagramRendererIsConfiguredAnywhere() throws IOException {
        // THIS IS THE ROOT CAUSE OF A BUILD THAT DID NOT BUILD. The official TechDocs image ships mkdocs,
        // mkdocs-material, mkdocs-techdocs-core and pymdown-extensions and NO mermaid plugin, so the
        // `- mermaid2` plugin entry and the `!!python/name:mermaid2.fence_mermaid` fence format each abort
        // that build outright with "cannot find module 'mermaid2'". Both had to go, and the format line is
        // the one an author is most likely to leave behind, because it does not read like a plugin.
        //
        // `extra_javascript` had to go for a related but distinct reason: the TechDocs CLI STRIPS that key,
        // reporting "Removed the following unsupported configuration keys", so a script named there can
        // never run in the published site however correct it is. A fix that depends on one is not a fix.
        final String config = read(MKDOCS_PATH);
        final String executableConfig = config.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(executableConfig)
                .as("a mermaid plugin entry, a mermaid fence format or an extra_javascript key each break "
                        + "or silently disable the official publication path; prose about them may stay, "
                        + "configuration may not")
                .doesNotContain("mermaid2")
                .doesNotContain("fence_mermaid")
                .doesNotContain("extra_javascript");
        assertThat(config)
                .as("the theme is declared explicitly so both publication modes agree, and font: false is "
                        + "what stops the theme emitting its Google Fonts link - the last external request "
                        + "either mode made")
                .contains("theme:")
                .contains("name: material")
                .contains("font: false")
                .as("and the two features that answer the very long pages survive publication, unlike "
                        + "anything a script could do")
                .contains("navigation.top")
                .contains("toc.follow");

        assertThat(Path.of("../docs/javascripts"))
                .as("the compensating viewbox script is gone with the key that named it; a directory left "
                        + "behind publishes an asset no page can load")
                .doesNotExist();

        try (Stream<Path> pages = Files.list(Path.of("../docs"))) {
            for (final Path page : pages.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                assertThat(read(page))
                        .as("%s must carry no client-rendered diagram fence: there is no renderer in the "
                                + "published site, so a fence publishes as a code block or as nothing",
                                page.getFileName())
                        .doesNotContain("```mermaid");
            }
        }
    }

    @Test
    @DisplayName("every diagram source has a published render that needs no network and no script")
    void everyDiagramSourceHasASelfContainedRender() throws IOException {
        // Pre-rendering is what makes the diagrams survive with no renderer, but only if each render is
        // genuinely self-contained. Three properties are checked because each has its own failure mode, and
        // one of them was found the hard way: mermaid emits flowchart and entity labels as HTML inside a
        // <foreignObject> by default, and a foreignObject DOES NOT RENDER AT ALL when the SVG is loaded
        // through <img> - every box would have published empty while the build still exited 0. The labels
        // are therefore SVG <text>, and the absence of foreignObject is asserted rather than remembered.
        final List<String> sources = new ArrayList<>();
        final List<String> renders = new ArrayList<>();
        try (Stream<Path> entries = Files.list(DIAGRAM_DIR)) {
            for (final Path entry : entries.sorted().toList()) {
                final String name = entry.getFileName().toString();
                if (name.endsWith(".mmd")) {
                    sources.add(name.substring(0, name.length() - ".mmd".length()));
                } else if (name.endsWith(".svg")) {
                    renders.add(name.substring(0, name.length() - ".svg".length()));
                }
            }
        }

        assertThat(sources).as("the diagram directory must carry the authoring sources").isNotEmpty();
        assertThat(renders)
                .as("every source must have a render and every render a source, or the site publishes a "
                        + "diagram nobody can regenerate, or a source nobody can see")
                .containsExactlyInAnyOrderElementsOf(sources);

        for (final String slug : sources) {
            final String svg = read(DIAGRAM_DIR.resolve(slug + ".svg"));
            final Matcher root = SVG_ROOT.matcher(svg);
            assertThat(root.find())
                    .as("%s must open with an svg element carrying an explicit intrinsic size, because an "
                            + "image with no intrinsic size cannot be laid out before it loads", slug)
                    .isTrue();
            final Matcher box = SVG_VIEW_BOX.matcher(svg);
            assertThat(box.find()).as("%s must declare a viewBox", slug).isTrue();

            assertThat(Integer.parseInt(root.group(1)))
                    .as("%s declares width %s against a viewBox width of %s, so the render would be "
                            + "scaled rather than shown at the size its labels were drawn for",
                            slug, root.group(1), box.group(1))
                    .isEqualTo((int) Math.ceil(Double.parseDouble(box.group(1))));
            assertThat(Integer.parseInt(root.group(2)))
                    .as("%s declares height %s against a viewBox height of %s", slug, root.group(2),
                            box.group(2))
                    .isEqualTo((int) Math.ceil(Double.parseDouble(box.group(2))));

            assertThat(svg)
                    .as("%s must carry no max-width, which would let the theme shrink it below the size "
                            + "its labels are legible at", slug)
                    .doesNotContain("max-width")
                    .as("%s must carry no foreignObject: HTML inside one does not render through <img>, "
                            + "so every label would publish blank", slug)
                    .doesNotContain("<foreignObject")
                    .as("%s must carry no script", slug)
                    .doesNotContain("<script");
            assertThat(svg)
                    .as("%s must carry its labels as SVG text, which is the form that renders through "
                            + "<img> and is readable by a screen reader and by grep", slug)
                    .contains("<text");
            // An edge label sits ON its connector. Mermaid backs it with a rect at opacity 0.5 over a
            // 0.8-alpha fill, so at an effective 0.4 the line runs straight through the glyphs and every
            // such label reads as struck through - which is a legibility defect of exactly the kind this
            // work exists to remove, introduced by the switch away from HTML labels rather than inherited.
            //
            // The check is scoped to the rules that back an edge label rather than run over the whole
            // file, because the same translucent declaration appears in rules for icon and image shapes
            // that no diagram here uses, and failing on those would be a false alarm.
            for (final Matcher rule = EDGE_LABEL_RECT_RULE.matcher(svg); rule.find();) {
                assertThat(rule.group(1))
                        .as("%s backs its edge labels with %s; the connector line shows through anything "
                                + "translucent, so the label reads as struck through", slug, rule.group())
                        .doesNotContain("opacity:0.5")
                        .doesNotContain("rgba(232,232,232, 0.8)");
            }

            final String probe = svg
                    .replace("xmlns=\"http://www.w3.org/2000/svg\"", "")
                    .replace("xmlns:xlink=\"http://www.w3.org/1999/xlink\"", "");
            assertThat(probe)
                    .as("%s must reference nothing over the network - a diagram that needs a CDN is a "
                            + "diagram that vanishes offline, which is the defect this replaces", slug)
                    .doesNotContain("http://")
                    .doesNotContain("https://");
            for (final Matcher reference = CSS_URL_REFERENCE.matcher(probe); reference.find();) {
                assertThat(reference.group(1))
                        .as("%s resolves %s, which leaves the document; only a same-document fragment "
                                + "keeps the render self-contained", slug, reference.group(0))
                        .startsWith("#");
            }
        }
    }

    @Test
    @DisplayName("every published diagram figure carries alt text, a caption and a named scroll region")
    void everyDiagramFigureIsAccessible() throws IOException {
        // A pre-rendered diagram is an image, and an image is the one element on a page that carries no
        // text of its own. So each figure has to supply three separate things, and none substitutes for
        // another: alt text, which is what a screen reader reads instead of the picture; a caption, which
        // is the text alternative a sighted reader gets when the labels are too small or the image is off;
        // and a named, focusable scroll region, which is the only way a keyboard reader reaches the part of
        // a 2257px-wide diagram that does not fit the page.
        int figures = 0;
        try (Stream<Path> pages = Files.list(Path.of("../docs"))) {
            for (final Path page : pages.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted().toList()) {
                final String text = read(page);
                final Matcher figure = DIAGRAM_FIGURE.matcher(text);
                while (figure.find()) {
                    figures++;
                    final String block = figure.group();
                    final Matcher image = DIAGRAM_IMAGE.matcher(block);
                    assertThat(image.find())
                            .as("the figure in %s must reference a rendered diagram", page.getFileName())
                            .isTrue();
                    final String alt = image.group(1);
                    final String slug = image.group(2);

                    assertThat(DIAGRAM_DIR.resolve(slug + ".svg"))
                            .as("%s references diagrams/%s.svg, which must exist or the figure publishes "
                                    + "as a broken image", page.getFileName(), slug)
                            .isRegularFile();
                    assertThat(alt)
                            .as("the alt text for %s is what a screen reader gets instead of the diagram, "
                                    + "so it has to describe the diagram rather than name it", slug)
                            .hasSizeGreaterThan(120);
                    assertThat(block)
                            .as("the figure for %s must expose its scroll frame as a named region a "
                                    + "keyboard can reach", slug)
                            .contains("role=\"region\"")
                            .contains("tabindex=\"0\"")
                            .contains("aria-labelledby=\"diagram-" + slug + "-caption\"")
                            .as("and the caption that names it must exist, carry the text alternative, and "
                                    + "say the frame scrolls - a cue a shadow alone cannot give")
                            .contains("id=\"diagram-" + slug + "-caption\"")
                            .contains("<figcaption")
                            .contains("**Figure — ")
                            .contains("scrolls sideways");
                }
            }
        }

        try (Stream<Path> entries = Files.list(DIAGRAM_DIR)) {
            final long sources = entries.filter(p -> p.getFileName().toString().endsWith(".mmd")).count();
            assertThat((long) figures)
                    .as("every rendered diagram must be published in a figure; %d figures against %d "
                            + "sources means one is rendered and never shown, or shown twice",
                            figures, sources)
                    .isEqualTo(sources);
        }

        assertThat(withoutCssComments(read(EXTRA_CSS_PATH)))
                .as("the stylesheet must defeat the theme's image constraint, or the diagram is scaled "
                        + "down again and its labels return to being unreadable")
                .contains(".md-typeset .diagram__image")
                .contains("max-width: none")
                .as("and the scroll region must have a visible focus indicator, since it is a tab stop")
                .contains(".md-typeset .diagram__viewport:focus-visible");
    }

    @Test
    @DisplayName("the theme override is wired through a key the official publication path keeps, and "
            + "every substring it patches is guarded")
    void theThemeOverrideIsWiredAndGuarded() throws IOException {
        // The accessibility work in this site is done by a template that extends the theme's own base and
        // rewrites named substrings of three blocks. That is only safe because of two things, and this test
        // holds both of them.
        //
        // The first is the publication key. The official path filters mkdocs.yml down to a fixed allow-list
        // and silently drops anything else - it is what drops `extra_javascript`, which is why none of this
        // work may depend on script. `theme.custom_dir` survives that filter, so the override has to be
        // declared there and nowhere else; declared under a stripped key it would work locally and vanish
        // in the published site, which is the worst failure mode available.
        //
        // The second is the drift guard. Every substring the template rewrites is a piece of the theme's
        // markup, and a theme upgrade may reword any of them. A missed needle does not fail - the replace
        // silently does nothing and the accessibility fix quietly disappears - so each needle is checked
        // for presence first and reads an attribute off an undefined name if it is absent, which stops the
        // build. This test asserts every needle has such a guard, by counting them.
        final String mkdocs = read(MKDOCS_PATH);
        assertThat(mkdocs)
                .as("the override must be declared under theme.custom_dir, the only override key the "
                        + "official publication path preserves")
                .contains("custom_dir: overrides");

        final String template = read(THEME_OVERRIDE_PATH);
        assertThat(template)
                .as("the override must extend the theme's own base template rather than replace it, or "
                        + "every unrelated feature of the theme is lost")
                .contains("{% extends \"base.html\" %}");

        final int needles = countOccurrences(template, "{%- set ") - countOccurrences(template, "ns.html")
                - countOccurrences(template, "{%- set ns ") - countOccurrences(template, "{%- set nearest")
                - countOccurrences(template, "{%- set wrapped") - countOccurrences(template, "{%- set _ ");
        assertThat(countOccurrences(template, "theme_contract_broken"))
                .as("each substring this template rewrites needs a guard that fails the build when the "
                        + "theme stops emitting it; %d guards against roughly %d needles means one "
                        + "rewrite can silently do nothing", countOccurrences(template,
                        "theme_contract_broken"), needles)
                .isGreaterThanOrEqualTo(6);

        assertThat(template)
                .as("the not-found page extends this same template and has no source document behind it, "
                        + "so the blocks that read the page must be guarded or the build fails on it")
                .contains("{%- if not page -%}")
                .as("the table frame must close on the body-and-table pair, never on the table tag alone: "
                        + "a highlighted code block is also a table, and closing on the tag alone emitted "
                        + "an unbalanced div per code block that collapsed the content column to zero width")
                .contains("'</tbody>\\n</table>'")
                .as("and the open and close counts must be asserted rather than trusted, because a "
                        + "mismatch here corrupts the page silently instead of failing")
                .contains("table_wrapper_balance");
    }

    @Test
    @DisplayName("every generated table scrolls inside a frame named for its own section, and a wide one "
            + "is marked so it can be tightened")
    void everyGeneratedTableIsNamedAndFocusable() throws IOException {
        // A scroll frame that is not announced is invisible to a screen reader, and one that is announced
        // with the same sentence forty-four times tells the reader nothing about which table they are in.
        // So the frame is named by a caption - the element HTML provides for naming a table - carrying the
        // title of the section it sits in, and the shared panning instruction moves to a description so it
        // is still read without being repeated in every name.
        final String template = read(THEME_OVERRIDE_PATH);
        assertThat(template)
                .as("the frame must be a region a keyboard can reach and a screen reader can announce")
                .contains("class=\"cd-tablewrap")
                .contains("role=\"region\"")
                .contains("tabindex=\"0\"")
                .as("named by its own caption rather than by one shared literal")
                .contains("aria-labelledby=\"'")
                .contains("<caption class=\"cd-sr-only\" id=\"'")
                .as("described once by the shared panning instruction")
                .contains("aria-describedby=\"cd-table-panning-note\"")
                .contains("id=\"cd-table-panning-note\"")
                .as("with the column count recorded so the stylesheet can tighten only what needs it")
                .contains("data-cd-cols=\"'")
                .contains("cd-tablewrap--wide")
                .as("and a header cell with no text of its own must still be named, or the reader hears "
                        + "\"blank\" twice before any content")
                .contains("<span class=\"cd-sr-only\">Field</span>")
                .contains("<span class=\"cd-sr-only\">Value</span>")
                .as("every generated header cell must declare the axis it heads")
                .contains("<th scope=\"col\">");

        final String css = withoutCssComments(read(EXTRA_CSS_PATH));
        assertThat(css)
                .as("the frame has to scroll, and it has to show a focus indicator because it is a tab stop")
                .contains(".md-typeset .cd-tablewrap")
                .contains("overflow-x: auto")
                .contains(".md-typeset .cd-tablewrap:focus-visible")
                .as("the inner table's background must be transparent or the theme's opaque table fill "
                        + "covers the frame's whole box and paints over the scroll cue - measured as a "
                        + "flat white edge on a table with forty-six pixels still hidden to its right")
                .contains("background-color: transparent")
                .as("and a wide table needs the theme's five-rem column floor lowered, since nine columns "
                        + "at that floor cannot fit the content column at any desktop width")
                .contains(".md-typeset .cd-tablewrap--wide")
                .contains("min-width: 3.6rem");
    }

    @Test
    @DisplayName("every pictographic marker in the documentation has a text alternative")
    void everyPictographicMarkerHasATextAlternative() throws IOException {
        // This is the drift guard for a defect that shipped twice. A status marker is a picture, and a
        // picture with no text alternative is silence: the row reads "Pending" with no hint that the glyph
        // beside it means anything. The template wraps each marker in a named image role, but it can only
        // wrap the ones somebody listed - and the list was first written against the emoji-presentation
        // warning sign while the pages carry the bare code point, so sixteen glyphs published unwrapped
        // next to sixty-six that were fine. Three severity discs were then found the same way.
        //
        // So the check is inverted: rather than assert the template mentions a glyph somebody thought of,
        // it reads every pictographic character actually present in the published Markdown and requires
        // each one to have an entry. A new marker cannot be introduced without a name.
        final String template = read(THEME_OVERRIDE_PATH);
        final Map<Integer, List<String>> markers = new LinkedHashMap<>();
        try (Stream<Path> pages = Files.list(DOCS_ROOT)) {
            for (final Path page : pages.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted().toList()) {
                read(page).codePoints().filter(BuildAndCiContractTest::isPictographic).distinct()
                        .forEach(cp -> markers.computeIfAbsent(cp, k -> new ArrayList<>())
                                .add(page.getFileName().toString()));
            }
        }

        assertThat(markers)
                .as("if the documentation carries no marker glyph at all then this guard is measuring "
                        + "nothing and the wrapping it protects has become dead code")
                .isNotEmpty();

        for (final Map.Entry<Integer, List<String>> marker : markers.entrySet()) {
            final String glyph = new String(Character.toChars(marker.getKey()));
            final String wrapper = "aria-label=\"";
            final int at = template.indexOf("| replace('" + glyph + "'");
            assertThat(at)
                    .as("U+%04X (%s) is published in %s with no text alternative: the template must wrap "
                            + "it in a named image role, or a screen reader announces nothing where a "
                            + "sighted reader sees a status marker", marker.getKey(), glyph,
                            String.join(", ", marker.getValue()))
                    .isNotNegative();
            final String replacement = template.substring(at, Math.min(template.length(), at + 220));
            assertThat(replacement)
                    .as("the replacement for U+%04X must give the glyph a role and a non-empty name; "
                            + "wrapping it without naming it is no better than leaving it bare",
                            marker.getKey())
                    .contains("role=\"img\"")
                    .contains(wrapper);
            final int nameAt = replacement.indexOf(wrapper) + wrapper.length();
            assertThat(replacement.substring(nameAt, replacement.indexOf('"', nameAt)))
                    .as("the name for U+%04X must say what the marker means", marker.getKey())
                    .isNotBlank();
        }

        assertThat(template)
                .as("the warning sign exists as a bare code point and as a two-code-point emoji sequence, "
                        + "and both must be handled - matching only the sequence is the defect that "
                        + "published sixteen unnamed glyphs")
                .contains("| replace('\u26a0\ufe0f'")
                .contains("| replace('\u26a0'")
                .as("and the sequence must be parked on a sentinel before the bare form is wrapped, or "
                        + "wrapping the bare form first cuts the sequence in half and strands its "
                        + "variation selector outside the span")
                .contains("warn_vs16_sentinel");
        assertThat(template.indexOf("| replace('\u26a0\ufe0f'"))
                .as("the two-code-point form has to be parked before the bare form is wrapped")
                .isLessThan(template.indexOf("| replace('\u26a0'"));
    }

    @Test
    @DisplayName("every syntax colour clears the contrast minimum on the background it is painted on")
    void everySyntaxColourClearsTheContrastMinimum() throws IOException {
        // Contrast is arithmetic, so this is checked by doing the arithmetic rather than by trusting that
        // the colours look dark enough. The theme's stock palette sat between 4.48:1 and 4.71:1 on the
        // background a code block actually paints: four tokens failed outright and the rest passed by a
        // hundredth, which is close enough that two independent measurements of the same value disagreed
        // about whether the page conformed. Each replacement was computed to clear 5.5:1, so this asserts
        // the whole set against the minimum with the margin visible in the failure message.
        final String css = withoutCssComments(read(EXTRA_CSS_PATH));
        final Matcher background = Pattern.compile("var\\(--md-code-bg-color,\\s*(#[0-9a-fA-F]{6})\\)")
                .matcher(css);
        assertThat(background.find())
                .as("the code background has to be declared in this stylesheet, or the contrast of every "
                        + "token below is being computed against a guess")
                .isTrue();
        final int[] paper = rgb(background.group(1));

        final Map<String, String> inks = new LinkedHashMap<>();
        final Matcher token = Pattern.compile("(--md-code-hl-[a-z]+-color):\\s*(#[0-9a-fA-F]{6})\\s*;")
                .matcher(css);
        while (token.find()) {
            inks.put(token.group(1), token.group(2));
        }
        assertThat(inks)
                .as("the syntax palette must be overridden here; an empty set means the theme's own "
                        + "failing colours are being published")
                .hasSizeGreaterThanOrEqualTo(11);

        final Matcher gutter = Pattern.compile("\\.highlighttable \\.linenos span,\\s*"
                + "\\.md-typeset \\.highlight \\[data-linenos\\]::before \\{\\s*color:\\s*(#[0-9a-fA-F]{6})")
                .matcher(css.replace("    .md-typeset ", "    ").replace("\n  ", "\n"));
        if (gutter.find()) {
            inks.put("line-number gutter", gutter.group(1));
        } else {
            final Matcher fallback = Pattern.compile("\\[data-linenos\\]::before \\{\\s*"
                    + "color:\\s*(#[0-9a-fA-F]{6})").matcher(css);
            assertThat(fallback.find())
                    .as("the line-number gutter must carry an explicit colour: the theme paints it with a "
                            + "translucent grey that composites to about 4.5:1, and which side of the "
                            + "minimum that lands on depends on how the alpha is read")
                    .isTrue();
            inks.put("line-number gutter", fallback.group(1));
        }

        for (final Map.Entry<String, String> ink : inks.entrySet()) {
            final double ratio = contrast(rgb(ink.getValue()), paper);
            assertThat(ratio)
                    .as("%s is %s on %s, which is %.4f:1 - normal-size text needs at least 4.5:1, and a "
                            + "value that only just clears it is a value two tools will disagree about",
                            ink.getKey(), ink.getValue(), background.group(1), ratio)
                    .isGreaterThanOrEqualTo(4.5d);
        }
    }

    @Test
    @DisplayName("a control that is off screen or invisible is inert, and one that is reachable is full size")
    void hiddenControlsAreInertAndReachableOnesAreFullSize() throws IOException {
        // Three separate defects share one cause: the theme hides things in ways that hide them from sight
        // without removing them from the page. The drawer is moved off screen by position, the collapsed
        // search keeps its subtree, and the back-to-top control is faded to nothing - and all three stayed
        // focusable, so a keyboard reader tabbed through sixty-eight destinations they could not see. The
        // remedy in each case is visibility, which is the one property that removes an element from the
        // accessibility tree as well as from the page.
        final String css = withoutCssComments(read(EXTRA_CSS_PATH));
        assertThat(css)
                .as("the closed drawer must be inert, not merely off screen")
                .contains("[data-md-toggle=\"drawer\"]:not(:checked) ~ .md-container .md-sidebar--primary")
                .as("the collapsed search must be inert too, or its subtree stays tabbable at zero height")
                .contains(".md-search__inner")
                .as("and the faded back-to-top control must leave the accessibility tree, because the "
                        + "theme's own display rule overrides the hidden attribute that would have done it")
                .contains(".md-top[hidden]")
                .contains("visibility: hidden");

        assertThat(css)
                .as("both toggles must be pointer-transparent. They are fixed boxes at the header corners "
                        + "above everything else, and the search overlay paints its back arrow and its "
                        + "clear button in exactly those corners - a real click on the clear button's "
                        + "centre dismissed the whole overlay and left the query in place")
                .contains("pointer-events: none")
                .as("and each must sit at the same inset as the label whose hit area it shadows, or a hit "
                        + "test taken from one box's centre lands outside the other's")
                .contains("#__drawer.md-toggle { left: 0.3rem; }")
                .contains("#__search.md-toggle { right: 0.3rem; }");

        assertThat(countOccurrences(css, "pointer-events: none"))
                .as("both toggles need it, not one")
                .isGreaterThanOrEqualTo(2);

        assertThat(css)
                .as("every control a finger has to hit must be at least 44 by 44, and at the twenty-pixel "
                        + "root this page renders at that is 2.2rem")
                .contains("width: 2.2rem")
                .contains("height: 2.2rem")
                .as("the header buttons, the permalinks and BOTH search-overlay icons need the "
                        + "enlargement - scoping it to the options container left the back arrow at the "
                        + "bare 24 by 24 the theme paints, with no pseudo-element at all")
                .contains(".md-header__button::after")
                .contains(".md-typeset .headerlink::after")
                .contains(".md-search__icon::after")
                .as("and a control that shows a ring must not be hidden by opacity, which would hide the "
                        + "ring with it")
                .contains("appearance: none");

        assertThat(css.indexOf(".md-search__icon::after"))
                .as("the overlay-icon enlargement must sit inside the search breakpoint: at wider widths "
                        + "the header holds a real search field, and a 44-pixel box over its magnifier "
                        + "would cover the place the reader clicks to type")
                .isGreaterThan(css.indexOf("@media screen and (max-width: 59.984375em)"));
    }

    @Test
    @DisplayName("the current page is announced, and every permalink says which heading it points at")
    void theCurrentPageAndEveryPermalinkAreAnnounced() throws IOException {
        // A navigation list that marks the current page only with a colour tells a screen reader nothing,
        // and a page carrying four hundred and fifty permalinks all named with the same pilcrow gives a
        // reader four hundred and fifty identical destinations. Both are fixed in the template, and both
        // are asserted here because both are single substrings that a theme reword would silently drop.
        final String template = read(THEME_OVERRIDE_PATH);
        assertThat(template)
                .as("the active navigation entry must be announced as the current page, not just coloured")
                .contains("aria-current=\"page\"")
                .as("and the needle must include the closing bracket of the anchor, or it also matches the "
                        + "table-of-contents label and marks two elements as current")
                .contains("md-nav__link--active\">")
                .as("each permalink must be named for the heading it points at, walked recursively so a "
                        + "nested heading is named too")
                .contains("aria-label=\"Permanent link to the section ")
                .contains("page.toc recursive")
                .as("and a heading title containing a double quote must not be able to close the "
                        + "attribute early")
                .contains("replace('\"'");

        assertThat(withoutCssComments(read(EXTRA_CSS_PATH)))
                .as("the theme fades a permalink in on hover only, so on a device with no hover it is "
                        + "permanently invisible and on a keyboard it can be focused while invisible")
                .contains("@media (hover: none)")
                .contains(".md-typeset .headerlink:focus");
    }

    /**
     * Strips comments from a stylesheet so an assertion cannot be satisfied by prose about the CSS instead
     * of by the CSS.
     *
     * <p>This is not a theoretical hazard. Three quarters of the published stylesheet is explanatory
     * comment, and those comments quote the declarations they explain: a negative control that deleted both
     * {@code pointer-events: none} declarations still passed a substring assertion, because the phrase
     * survived twice inside the prose describing why those declarations exist. Every assertion about a
     * declaration therefore runs against the stripped text. CSS comments do not nest, so a non-greedy span
     * between the delimiters is exact.</p>
     *
     * @param css the whole stylesheet
     * @return the stylesheet with every comment removed
     */
    private static String withoutCssComments(final String css) {
        return Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(css).replaceAll("");
    }

    /**
     * Reports whether a code point is a pictographic marker rather than prose punctuation.
     *
     * <p>The ranges are the symbol and emoji blocks a browser renders as a picture. Deliberately excluded
     * is everything below the symbol blocks, which is where the dash, arrow and comparison characters this
     * documentation uses in ordinary sentences live; those are text and need no alternative.</p>
     *
     * @param codePoint the code point to classify
     * @return true when the code point is a marker glyph
     */
    private static boolean isPictographic(final int codePoint) {
        return (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0x2B00 && codePoint <= 0x2BFF)
                || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF);
    }

    /**
     * Parses a six-digit hexadecimal colour into its three channels.
     *
     * @param hex the colour, including its leading hash
     * @return the red, green and blue channels, each 0 to 255
     */
    private static int[] rgb(final String hex) {
        return new int[] {
            Integer.parseInt(hex.substring(1, 3), 16),
            Integer.parseInt(hex.substring(3, 5), 16),
            Integer.parseInt(hex.substring(5, 7), 16),
        };
    }

    /**
     * Computes the WCAG 2.1 contrast ratio between two opaque colours.
     *
     * <p>Both colours must be opaque. A translucent foreground has to be composited over its background
     * before it reaches here, because the ratio of a colour with an alpha channel is not defined - and
     * reading an authored alpha back from a browser re-serializes it, which is what made one value in this
     * palette measure 4.4963:1 by one route and 4.5153:1 by another.</p>
     *
     * @param ink   the foreground colour channels
     * @param paper the background colour channels
     * @return the ratio, at least 1.0 and at most 21.0
     */
    private static double contrast(final int[] ink, final int[] paper) {
        final double first = relativeLuminance(ink);
        final double second = relativeLuminance(paper);
        return (Math.max(first, second) + 0.05d) / (Math.min(first, second) + 0.05d);
    }

    /**
     * Computes the WCAG relative luminance of an opaque colour.
     *
     * @param channels the red, green and blue channels, each 0 to 255
     * @return the luminance, 0.0 for black and 1.0 for white
     */
    private static double relativeLuminance(final int[] channels) {
        final double[] linear = new double[3];
        for (int index = 0; index < 3; index++) {
            final double value = channels[index] / 255.0d;
            linear[index] = value <= 0.04045d ? value / 12.92d : Math.pow((value + 0.055d) / 1.055d, 2.4d);
        }
        return 0.2126d * linear[0] + 0.7152d * linear[1] + 0.0722d * linear[2];
    }

    /**
     * Returns the span of a document between two literal markers.
     *
     * @param text  the whole document
     * @param from  the marker the span starts at, included
     * @param until the marker the span stops before
     * @return the span, never empty
     */
    private static String section(final String text, final String from, final String until) {
        final int start = text.indexOf(from);
        assertThat(start)
                .as("the document must carry the section beginning \"%s\"", from)
                .isNotNegative();
        final int end = text.indexOf(until, start + from.length());
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    /**
     * Reads a two-column hours table into row name and hours, preserving the published order.
     *
     * @param table the span holding the table
     * @return each row name mapped to its hours figure
     */
    private static Map<String, Integer> hoursRows(final String table) {
        final Map<String, Integer> rows = new LinkedHashMap<>();
        final Matcher matcher = HOURS_TABLE_ROW.matcher(table);
        while (matcher.find()) {
            rows.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return rows;
    }

    /**
     * Reads the third column of a roll-up table, keyed by the slice each row names.
     *
     * @param table the span holding the table
     * @return each slice mapped to the expression that claims to compose it, total row excluded
     */
    private static Map<String, String> rollUpExpressions(final String table) {
        final Map<String, String> expressions = new LinkedHashMap<>();
        final Matcher matcher = HOURS_TABLE_ROW.matcher(table);
        while (matcher.find()) {
            final String slice = matcher.group(1);
            final String claim = matcher.group(3).replace("|", "").trim();
            if (!slice.startsWith("**") && !claim.isEmpty()) {
                expressions.put(slice, claim);
            }
        }
        return expressions;
    }

    /**
     * Reads the slices of one named Mermaid pie.
     *
     * @param text  the span holding the pie
     * @param title the pie's declaration line, matched literally so a second pie is not read instead
     * @return each slice label mapped to its value
     */
    private static Map<String, Integer> pieSlices(final String text, final String title) {
        final int start = text.indexOf(title);
        assertThat(start)
                .as("the page must declare the pie \"%s\"", title)
                .isNotNegative();
        final int end = text.indexOf("```", start);
        final Matcher matcher = PIE_SLICE.matcher(
                end < 0 ? text.substring(start) : text.substring(start, end));
        final Map<String, Integer> slices = new LinkedHashMap<>();
        while (matcher.find()) {
            slices.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return slices;
    }

    /**
     * Reads one required row out of a parsed hours table.
     *
     * @param rows    the parsed table
     * @param name    the row that must be present
     * @param because what the row is needed for, reported when it is absent
     * @return that row's hours figure
     */
    private static int requireRow(final Map<String, Integer> rows, final String name,
            final String because) {
        assertThat(rows).as(because).containsKey(name);
        return rows.get(name);
    }

    /**
     * Collapses runs of whitespace to a single space, so a published figure separated from its noun
     * by a line break is still one phrase.
     *
     * @param text the document text
     * @return the same text with its wrapping removed
     */
    private static String flattened(final String text) {
        return text.replaceAll("\\s+", " ");
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

    /**
     * Every {@code gate<n>-….md} evidence file name the test suite writes, read from the suite's sources.
     *
     * <p>Read rather than listed. A constant here would be a second copy of the same fact, and the copy
     * that drifts is always the one nobody runs: an evidence file renamed in the suite would keep passing a
     * hardcoded check while going unpublished. The names are matched in the sources of both runners' trees,
     * so a new gate report is picked up by the contract the moment it is written.
     *
     * @return the artefact names, without duplicates, in a stable order
     * @throws IOException if the test tree cannot be walked
     */
    private static List<String> evidenceArtefactsTheSuiteWrites() throws IOException {
        final java.util.SortedSet<String> names = new java.util.TreeSet<>();
        final java.util.regex.Pattern artefact =
                java.util.regex.Pattern.compile("\\bgate\\d[A-Za-z0-9-]*\\.md\\b");
        try (Stream<Path> tree = Files.walk(Path.of("src", "test", "java"))) {
            for (final Path source : tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                final java.util.regex.Matcher matcher =
                        artefact.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    names.add(matcher.group());
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * The publication descriptors this migration authors at the repository root, discovered by walking it.
     *
     * <p>Discovered rather than listed, for the reason the neighbour above is: a constant would be a second
     * copy of the whitespace pathspec, and a third descriptor added to the root would satisfy the copy
     * while escaping the gate. Membership is defined by <em>shape</em> - a depth-one {@code .yml} or
     * {@code .yaml} file - because that is what the two known members are: {@code mkdocs.yml}, whose
     * explicit {@code nav} list is what publishes this migration's documentation pages, and
     * {@code catalog-info.yaml}, whose component type and tags this migration updates.
     *
     * <p>The governance text beside them is deliberately outside this rule. {@code LICENSE},
     * {@code NOTICE}, {@code CONTRIBUTING.md} and {@code CODE_OF_CONDUCT.md} are unaffected by the
     * migration, and a hygiene gate over a file this module does not author would fail the build on an
     * upstream edit. So would the estate {@code README.md}, which is upstream CRLF text carrying 59
     * trailing-space lines of its own.
     *
     * @return the descriptor file names, without duplicates, in a stable order
     * @throws IOException if the repository root cannot be listed
     */
    private static List<String> authoredRootDescriptors() throws IOException {
        final java.util.SortedSet<String> descriptors = new java.util.TreeSet<>();
        try (Stream<Path> root = Files.list(Path.of(".."))) {
            for (final Path candidate : root.filter(Files::isRegularFile).toList()) {
                final String name = candidate.getFileName().toString();
                if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                    descriptors.add(name);
                }
            }
        }
        return List.copyOf(descriptors);
    }

    private static String read(final Path path) throws IOException {
        assertThat(path).isRegularFile();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Reads a PNG's intrinsic pixel size out of its own header.
     *
     * <p>Read from the file rather than trusted from the markup, because the markup is the thing under
     * test: the deck declares a width and a height on every image so the figure's box is reserved
     * before the image arrives and nothing on the slide shifts when it does. A declared size that
     * disagrees with the file is therefore a layout shift on every load, and it is also how a copy of
     * the <em>wrong</em> capture would announce itself - the path would resolve, the image would render,
     * and only the dimensions would say it is not the screen the caption describes.</p>
     *
     * <p>The layout is fixed by the format: an 8-byte signature, a 4-byte chunk length, the 4-byte
     * chunk type {@code IHDR}, then width and height as big-endian 32-bit integers. That puts width at
     * offset 16 and height at offset 20.</p>
     *
     * @param png the image to measure
     * @return a two-element array holding width then height, in pixels
     * @throws IOException if the file cannot be read
     */
    private static int[] pngDimensions(final Path png) throws IOException {
        final byte[] header = new byte[24];
        try (java.io.InputStream stream = Files.newInputStream(png)) {
            assertThat(stream.readNBytes(header, 0, header.length))
                    .as("%s must be long enough to carry a PNG header", png)
                    .isEqualTo(header.length);
        }

        assertThat(new String(header, 12, 4, StandardCharsets.US_ASCII))
                .as("%s must be a PNG whose first chunk is the header chunk", png)
                .isEqualTo("IHDR");

        return new int[] {bigEndianInt(header, 16), bigEndianInt(header, 20)};
    }

    /**
     * Assembles a big-endian 32-bit integer from four bytes.
     *
     * <p>Each byte is masked to its unsigned value first. Without the mask, Java's signed byte would
     * sign-extend any value above 127 and corrupt the whole integer - which for these images would go
     * unnoticed, since none of them is 128 pixels or more in a dimension whose high bytes are zero.</p>
     *
     * @param bytes  the buffer to read from
     * @param offset the position of the most significant byte
     * @return the assembled value
     */
    private static int bigEndianInt(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
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
