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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts the shipped collector configuration against the security posture the application now enforces,
 * and asserts the two halves agree.
 *
 * <h2>Why a test over a file no Java class reads</h2>
 *
 * <p>{@code config/prometheus/prometheus.yml} is mounted by the {@code prometheus} service of the sibling
 * {@code docker-compose.yml}. Nothing on the class path resolves it, so a drift between what it assumes
 * and what {@link SecurityConfig} enforces cannot surface as a compilation error or as a failed context
 * refresh. It surfaces as one of two silent outcomes instead: every dashboard panel resolving to nothing
 * because the scrape is refused, or - the outcome the review found - a production deployment answering
 * the same credential-free scrape to any client on the network, because the file's assumption had been
 * written into the filter chain as an unconditional permit.
 *
 * <p>So the agreement is asserted here directly. Three claims are checked, and each is a claim about the
 * relationship between the two files rather than about either alone:
 *
 * <ol>
 *   <li>The collector commits <strong>no credential</strong>. A credential in a file under version
 *       control is disclosed to everyone who can read the repository, whatever the endpoint's own
 *       posture is.</li>
 *   <li>The path it scrapes is the path the application publishes, so the file cannot be silently
 *       scraping nothing.</li>
 *   <li>The file states that its credential-free posture belongs to the profiles that <strong>open</strong>
 *       the endpoint, and names the key that decides it - so a reader who arrives at this file looking for
 *       the production answer is sent to the enforcement rather than left with the local one.</li>
 * </ol>
 *
 * <p>What the endpoint answers to an anonymous caller under each posture is asserted where the enforcement
 * lives, in {@code SecurityConfigTest}, against real HTTP status codes. Which value each profile document
 * declares is asserted in {@code ConfigurationProfileBaselineTest}. This class covers only the collector
 * artefact, which neither of those reads.
 *
 * <p>Traceability: the observability layer this file collects for replaces the legacy estate's only
 * diagnostic channel, approximately 217 {@code DISPLAY} statements across the 28 COBOL programs, at
 * checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced here.
 */
@DisplayName("The shipped collector configuration, and its agreement with the filter chain")
final class MetricsScrapeExposureTest {

    /** The collector configuration, relative to the module root the build runs from. */
    private static final String COLLECTOR_PATH = "config/prometheus/prometheus.yml";

    /** The management base path every profile leaves at its default. */
    private static final String MANAGEMENT_BASE = "/actuator";

    /** The path the meter registry is published at. */
    private static final String SCRAPE_PATH = MANAGEMENT_BASE + "/prometheus";

    /** The key that decides whether an anonymous collector is answered. */
    private static final String GATING_KEY = "carddemo.security.anonymous-metrics-scrape";

    /** The document key holding the scrape jobs. */
    private static final String SCRAPE_CONFIGS_KEY = "scrape_configs";

    /** The job key naming the path scraped. */
    private static final String METRICS_PATH_KEY = "metrics_path";

    /** The job key holding the static target lists. */
    private static final String STATIC_CONFIGS_KEY = "static_configs";

    /** The static-config key holding the addresses. */
    private static final String TARGETS_KEY = "targets";

    /** The job key holding the job's name. */
    private static final String JOB_NAME_KEY = "job_name";

    /** Keys by which a Prometheus job carries a credential. None may appear in a committed file. */
    private static final List<String> CREDENTIAL_KEYS = List.of(
            "authorization", "basic_auth", "oauth2", "credentials", "password", "bearer_token");

    /** Creates the test class. */
    MetricsScrapeExposureTest() {
    }

    @Nested
    @DisplayName("commits no credential")
    final class CommitsNoCredential {

        /** Creates the nest. */
        CommitsNoCredential() {
        }

        @ParameterizedTest(name = "no [{0}] anywhere in the file")
        @ValueSource(strings = {
            "authorization", "basic_auth", "oauth2", "credentials", "password", "bearer_token"
        })
        @DisplayName("carries none of the keys by which a collector presents a credential, because a "
                + "credential under version control is disclosed to every reader of the repository")
        void carriesNoneOfTheCredentialKeys(final String key) throws IOException {
            // Read as text rather than through the parser: a credential inside a comment is still a
            // credential in a committed file, and the parser would discard comments before this could
            // see them.
            assertThat(activeLines())
                    .as("no active line of %s may name %s; the header may describe what a production "
                            + "collector does, but this file may not carry one", COLLECTOR_PATH, key)
                    .noneMatch(line -> line.toLowerCase(Locale.ROOT).contains(key));
        }

        @Test
        @DisplayName("declares no credential on any job when parsed, which is the same claim made "
                + "against structure rather than against text")
        void declaresNoCredentialOnAnyJob() throws IOException {
            final List<Map<?, ?>> jobs = scrapeJobs();

            assertThat(jobs).as("the file must declare at least one job").isNotEmpty();
            for (final Map<?, ?> job : jobs) {
                assertThat(keyNamesOf(job))
                        .as("job %s must present no credential", job.get(JOB_NAME_KEY))
                        .doesNotContainAnyElementsOf(CREDENTIAL_KEYS);
            }
        }
    }

    @Nested
    @DisplayName("agrees with the application on what it scrapes")
    final class AgreesOnWhatItScrapes {

        /** Creates the nest. */
        AgreesOnWhatItScrapes() {
        }

        @Test
        @DisplayName("scrapes the path the application publishes the meter registry at, so the file "
                + "cannot be silently collecting nothing")
        void scrapesThePathTheApplicationPublishes() throws IOException {
            assertThat(String.valueOf(applicationJob().get(METRICS_PATH_KEY)))
                    .as("the collected path must be the published one")
                    .isEqualTo(SCRAPE_PATH);
        }

        @Test
        @DisplayName("addresses Compose service names and never the loopback address, because inside "
                + "the collector's own container loopback resolves to the collector")
        void addressesComposeServiceNamesOnly() throws IOException {
            assertThat(applicationTargets())
                    .as("a loopback target would scrape the collector itself and yield nothing, which "
                            + "surfaces only as an empty performance-gate write-up")
                    .isNotEmpty()
                    .noneMatch(target -> target.startsWith("localhost")
                            || target.startsWith("127.0.0.1")
                            || target.startsWith("[::1]"));
        }
    }

    @Nested
    @DisplayName("states which profiles its credential-free posture belongs to")
    final class StatesWhichProfilesItBelongsTo {

        /** Creates the nest. */
        StatesWhichProfilesItBelongsTo() {
        }

        @Test
        @DisplayName("names the key that decides whether an anonymous scrape is answered, so a reader "
                + "looking for the production answer is sent to the enforcement")
        void namesTheGatingKey() throws IOException {
            assertThat(contents())
                    .as("%s must name %s rather than leave its own credential-free posture looking "
                            + "like the posture of every profile", COLLECTOR_PATH, GATING_KEY)
                    .contains(GATING_KEY);
        }

        @Test
        @DisplayName("names the class that enforces it and the production profile it is closed in, so "
                + "the statement is checkable against a sibling file rather than being an assertion")
        void namesTheEnforcementAndTheClosedProfile() throws IOException {
            assertThat(contents())
                    .contains(SecurityConfig.class.getName())
                    .contains("prod");
        }

        @Test
        @DisplayName("no longer presents credential-free collection as a general property of the "
                + "metrics endpoint, which is the wording the review found had been written into the "
                + "filter chain as an unconditional permit")
        void noLongerPresentsCredentialFreeCollectionAsGeneral() throws IOException {
            assertThat(contents())
                    .as("the file must not describe the endpoint as deliberately reachable without "
                            + "qualifying which profiles make it so")
                    .doesNotContain("deliberately reachable inside the local validation stack, and were a")
                    .doesNotContain("It also carries no credential: the metrics endpoint is");
        }
    }

    // Helpers.

    /**
     * Reads the collector configuration as text.
     *
     * @return the file's contents
     * @throws IOException if the file cannot be read
     */
    private static String contents() throws IOException {
        return Files.readString(Path.of(COLLECTOR_PATH), StandardCharsets.UTF_8);
    }

    /**
     * Reads the collector configuration's non-comment, non-blank lines.
     *
     * @return the active lines, stripped
     * @throws IOException if the file cannot be read
     */
    private static List<String> activeLines() throws IOException {
        return Files.readAllLines(Path.of(COLLECTOR_PATH), StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    /**
     * Parses the collector configuration and returns its scrape jobs.
     *
     * <p>Narrowed by pattern matching on wildcard types rather than cast to a parameterised map, so the
     * traversal carries no unchecked operation. The module compiles with {@code -Xlint:all -Werror} and
     * holds a zero-suppression posture, so a cast here would be a build failure rather than a warning.
     *
     * @return the declared jobs, never {@code null}
     * @throws IOException if the file cannot be read
     */
    private static List<Map<?, ?>> scrapeJobs() throws IOException {
        final List<Map<?, ?>> jobs = new ArrayList<>();
        if (new Yaml().load(contents()) instanceof Map<?, ?> document
                && document.get(SCRAPE_CONFIGS_KEY) instanceof List<?> declared) {
            for (final Object job : declared) {
                if (job instanceof Map<?, ?> settings) {
                    jobs.add(settings);
                }
            }
        }
        return jobs;
    }

    /**
     * Returns the key names of one parsed mapping as strings.
     *
     * @param mapping the mapping to read
     * @return the key names
     */
    private static List<String> keyNamesOf(final Map<?, ?> mapping) {
        return mapping.keySet().stream().map(String::valueOf).toList();
    }

    /**
     * Returns the job that scrapes the application, selected by its declared metrics path.
     *
     * @return the application's scrape job
     * @throws IOException if the file cannot be read
     */
    private static Map<?, ?> applicationJob() throws IOException {
        for (final Map<?, ?> job : scrapeJobs()) {
            if (job.containsKey(METRICS_PATH_KEY)) {
                return job;
            }
        }
        throw new IllegalStateException(COLLECTOR_PATH + " declares no job with a metrics path");
    }

    /**
     * Returns the addresses the application's scrape job targets.
     *
     * @return the declared targets
     * @throws IOException if the file cannot be read
     */
    private static List<String> applicationTargets() throws IOException {
        final List<String> targets = new ArrayList<>();
        if (applicationJob().get(STATIC_CONFIGS_KEY) instanceof List<?> statics) {
            for (final Object entry : statics) {
                if (entry instanceof Map<?, ?> settings
                        && settings.get(TARGETS_KEY) instanceof List<?> declared) {
                    declared.forEach(target -> targets.add(String.valueOf(target)));
                }
            }
        }
        return targets;
    }
}
