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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Holds the provenance line every generated gate-evidence file carries to a stated shape.
 *
 * <p>The line exists so that a published bundle can be attributed to the build and the run that produced
 * it. That makes its <em>content</em> the contract rather than its existence: a line that silently
 * dropped the revision, or that reported a revision the build never supplied, would leave the bundle
 * exactly as unattributable as no line at all while looking as though the problem were solved. Each rule
 * below therefore names one identifier and asserts that it reaches the rendered text.
 *
 * <p>Rendering is asserted through the package-private overload, because the ambient environment and the
 * wall clock cannot be set from a test and a rule that could only observe the real ones would be a rule
 * about this machine. The public entry point is asserted separately for the one property it owns: that it
 * reads the build revision from the property the build actually hands over.
 *
 * <p>A pure unit test: no Spring context, no connection, no container.
 *
 * <p>Decision log DL-315.
 *
 * @since 1.0.0
 */
@DisplayName("gate evidence provenance :: every generated file says which build and which run made it")
final class GateEvidenceProvenanceTest {

    /** A revision shaped like the one a real build supplies. */
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    /** A fixed instant, so the rendered text is comparable. */
    private static final Instant AT = Instant.parse("2026-08-10T09:15:42.123456Z");

    /** A machine name, so the Gate 3 figures can be attributed to hardware. */
    private static final String HOST = "runner-7";

    /**
     * The environment a workflow run provides.
     *
     * @return the four variables the run identity is built from
     */
    private static Map<String, String> workflowEnvironment() {
        final Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GITHUB_REPOSITORY", "aws-samples/aws-mainframe-modernization-carddemo");
        environment.put("GITHUB_RUN_ID", "17462390211");
        environment.put("GITHUB_RUN_ATTEMPT", "2");
        environment.put("GITHUB_SHA", "fedcba9876543210fedcba9876543210fedcba98");
        return environment;
    }

    @Nested
    @DisplayName("what the line says when the build is a workflow run")
    class InContinuousIntegration {

        @Test
        @DisplayName("names the revision, the repository, the run, the attempt and the checked-out commit")
        void namesEveryIdentifierAReaderNeedsToFindTheRun() {
            final String stamp =
                    GateEvidenceProvenance.stamp(REVISION, workflowEnvironment(), AT, HOST);

            assertThat(stamp)
                    .as("the revision is what makes the bundle attributable to code")
                    .contains("revision " + REVISION)
                    .as("the repository and run number are how a reader finds the logs")
                    .contains("aws-samples/aws-mainframe-modernization-carddemo#17462390211")
                    .as("a re-run of one commit produces a second bundle, and the attempt tells them "
                            + "apart")
                    .contains("attempt 2")
                    .as("the checked-out commit answers a different question from the build revision")
                    .contains("on commit fedcba9876543210fedcba9876543210fedcba98")
                    .as("a Gate 3 figure is meaningless without the machine")
                    .contains("on " + HOST);
        }

        @Test
        @DisplayName("records the moment in UTC, truncated to the second so two bundles do not differ "
                + "by a fraction")
        void recordsTheMomentInUtcToTheSecond() {
            final String stamp =
                    GateEvidenceProvenance.stamp(REVISION, workflowEnvironment(), AT, HOST);

            assertThat(stamp)
                    .as("the sub-second field is dropped deliberately, so two bundles taken a fraction "
                            + "apart do not differ in a field nobody reads")
                    .contains("recorded 2026-08-10T09:15:42Z")
                    // Anchored on the second rather than on the digits alone: the revision constant
                    // above happens to contain the same digits as the fraction, and a bare needle would
                    // pass or fail on that coincidence rather than on the truncation.
                    .doesNotContain("09:15:42.");
        }

        @Test
        @DisplayName("omits the attempt and the commit when the run does not publish them, rather than "
                + "printing an empty field")
        void omitsAbsentOptionalIdentifiers() {
            final Map<String, String> partial = new LinkedHashMap<>();
            partial.put("GITHUB_REPOSITORY", "owner/repository");
            partial.put("GITHUB_RUN_ID", "9");

            final String stamp = GateEvidenceProvenance.stamp(REVISION, partial, AT, HOST);

            assertThat(stamp)
                    .contains("run owner/repository#9,")
                    .doesNotContain("attempt")
                    .doesNotContain("on commit");
        }

        @Test
        @DisplayName("names the run even when the repository variable is missing, because the run number "
                + "is the identifier that matters")
        void namesTheRunWithoutTheRepository() {
            final Map<String, String> withoutRepository = new LinkedHashMap<>();
            withoutRepository.put("GITHUB_RUN_ID", "42");

            assertThat(GateEvidenceProvenance.stamp(REVISION, withoutRepository, AT, HOST))
                    .contains("run unnamed-repository#42");
        }
    }

    @Nested
    @DisplayName("what the line says when the build is local")
    class OnADeveloperMachine {

        @Test
        @DisplayName("says the run is local rather than inventing an identity")
        void saysTheRunIsLocal() {
            assertThat(GateEvidenceProvenance.stamp(REVISION, Map.of(), AT, HOST))
                    .contains("run " + GateEvidenceProvenance.LOCAL_RUN)
                    .doesNotContain("#");
        }

        @Test
        @DisplayName("says the revision is unsupplied rather than reporting one the build never gave it")
        void saysTheRevisionIsUnsupplied() {
            assertThat(GateEvidenceProvenance.stamp(null, Map.of(), AT, HOST))
                    .as("a local build has no trustworthy commit context and the line must not imply one")
                    .contains("revision " + GateEvidenceProvenance.UNSUPPLIED_REVISION);
            assertThat(GateEvidenceProvenance.stamp("   ", Map.of(), AT, HOST))
                    .as("a blank property is the same absence as a missing one")
                    .contains("revision " + GateEvidenceProvenance.UNSUPPLIED_REVISION);
        }

        @Test
        @DisplayName("says the host is unknown rather than leaving the field empty")
        void saysTheHostIsUnknown() {
            assertThat(GateEvidenceProvenance.stamp(REVISION, Map.of(), AT, null))
                    .contains("on unknown-host.");
        }

        @Test
        @DisplayName("a blank run identifier is an absent one, not an empty run")
        void aBlankRunIdentifierIsAnAbsentOne() {
            assertThat(GateEvidenceProvenance.stamp(REVISION, Map.of("GITHUB_RUN_ID", "  "), AT, HOST))
                    .contains("run " + GateEvidenceProvenance.LOCAL_RUN);
        }
    }

    @Nested
    @DisplayName("the entry point the producers actually call")
    class TheEntryPoint {

        @Test
        @DisplayName("reads the build revision from the property the build hands over")
        void readsTheRevisionFromTheBuildProperty() {
            final String property = GateEvidenceProvenance.BUILD_REVISION_PROPERTY;
            final String original = System.getProperty(property);
            try {
                System.setProperty(property, REVISION);
                assertThat(GateEvidenceProvenance.stamp())
                        .as("the property is the seam between the build and the evidence")
                        .contains("revision " + REVISION);
            } finally {
                if (original == null) {
                    System.clearProperty(property);
                } else {
                    System.setProperty(property, original);
                }
            }
        }

        @Test
        @DisplayName("falls back to the declared unsupplied marker when the property is absent")
        void fallsBackToTheUnsuppliedMarker() {
            final String property = GateEvidenceProvenance.BUILD_REVISION_PROPERTY;
            final String original = System.getProperty(property);
            try {
                System.clearProperty(property);
                assertThat(GateEvidenceProvenance.stamp())
                        .contains("revision " + GateEvidenceProvenance.UNSUPPLIED_REVISION);
            } finally {
                if (original != null) {
                    System.setProperty(property, original);
                }
            }
        }

        @Test
        @DisplayName("renders one line, so it can be prepended to a Markdown table without breaking it")
        void rendersExactlyOneLine() {
            assertThat(GateEvidenceProvenance.stamp())
                    .doesNotContain("\n")
                    .doesNotContain("\r")
                    .startsWith("Build provenance: ")
                    .endsWith(".");
        }
    }

    @Nested
    @DisplayName("the contract with the build that supplies the revision")
    class TheContractWithTheBuild {

        @Test
        @DisplayName("the build hands the integration tier the property this class reads, from the same "
                + "value the generated build information carries")
        void theBuildHandsOverTheProperty() throws IOException {
            final String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

            assertThat(pom)
                    .as("without this the evidence would report every revision as unsupplied, in CI too")
                    .contains("<" + GateEvidenceProvenance.BUILD_REVISION_PROPERTY
                            + ">${build.revision}</" + GateEvidenceProvenance.BUILD_REVISION_PROPERTY
                            + ">")
                    .as("and the default the build declares must be the marker this class expects")
                    .contains("<build.revision>" + GateEvidenceProvenance.UNSUPPLIED_REVISION
                            + "</build.revision>");
        }
    }

    @Nested
    @DisplayName("inputs it refuses rather than papers over")
    class RefusedInputs {

        @Test
        @DisplayName("a null environment is a programming error, not an empty environment")
        void aNullEnvironmentIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> GateEvidenceProvenance.stamp(REVISION, null, AT, HOST))
                    .withMessageContaining("environment");
        }

        @Test
        @DisplayName("a null instant is a programming error, because the alternative is a file with no "
                + "recorded moment")
        void aNullInstantIsRefused() {
            final NullPointerException refused = catchThrowableOfType(NullPointerException.class,
                    () -> GateEvidenceProvenance.stamp(REVISION, Map.of(), null, HOST));

            assertThat(refused).isNotNull();
            assertThat(refused.getMessage()).contains("at");
        }
    }
}
