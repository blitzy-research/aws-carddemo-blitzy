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
import java.util.List;
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
    @DisplayName("the container determination file ships carrying no determination at all")
    void theContainerDeterminationFileShipsEmpty() throws IOException {
        final String file = read(CONTAINER_DETERMINATIONS_PATH);
        final List<String> determinations = file.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertThat(determinations)
                .as("nothing is accepted in the delivered state. A determination here is a reviewed "
                        + "edit, and a file that shipped with one would be accepting a finding nobody "
                        + "reading this repository had reviewed. Actual: %s", determinations)
                .isEmpty();
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
