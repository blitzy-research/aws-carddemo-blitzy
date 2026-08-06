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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Executes the delivered Gate-3 item-read PromQL against populated Spring Batch-shaped metrics.
 *
 * <p>A string-only dashboard test can prove that a label is spelled consistently and still miss a
 * query that returns no series. This integration test gives a real Prometheus server two item-read
 * samples carrying the exact Spring Batch 5.2.6 labels, substitutes the dashboard variables, and
 * requires both committed queries to return a populated vector grouped by job and step.</p>
 */
@DisplayName("Gate-3 item-read dashboard queries executed by Prometheus")
class MonitoringQueriesIT {

    private static final Path DASHBOARD_PATH =
            Path.of("config/grafana/dashboards/carddemo-overview.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PROMETHEUS_IMAGE =
            "prom/prometheus:v3.5.0@sha256:"
                    + "63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996";
    private static final int PROMETHEUS_PORT = 9090;
    private static final String SCRAPE_JOB = "carddemo-app";
    private static final String BATCH_JOB = "postTransactionJob";
    private static final String BATCH_STEP = "postTransactionValidationStep";
    private static final String ITEM_READ_METRIC = "spring_batch_item_read_seconds_count";
    private static final String ITEM_READ_JOB_LABEL = "spring_batch_item_read_job_name";
    private static final String ITEM_READ_STEP_LABEL = "spring_batch_item_read_step_name";

    private static final String ACTIVE_JOB_METRIC = "spring_batch_job_active_seconds_count";
    private static final String RUNNING_BATCH_JOB = "postTransactionJob";
    private static final String IDLE_BATCH_JOB = "interestCalculationJob";

    /**
     * The exposition a Micrometer {@code LongTaskTimer} actually publishes for
     * {@code spring.batch.job.active}: a summary family whose {@code _count} is the number of
     * currently active tasks. No {@code _active_count} suffix exists, which is precisely the naming
     * trap that left the delivered panel permanently empty.
     */
    // Locale.ROOT is pinned, and String.format is used rather than the String.formatted shorthand,
    // because that shorthand has no locale-accepting overload and resolves the ambient default -
    // which LocaleDeterminismAuditTest bans outright across this module for that reason.
    private static final String ACTIVE_JOB_EXPOSITION = String.format(Locale.ROOT, """
            # HELP spring_batch_job_active_seconds Active batch job executions.
            # TYPE spring_batch_job_active_seconds summary
            spring_batch_job_active_seconds_count{spring_batch_job_active_name="%1$s"} 1
            spring_batch_job_active_seconds_sum{spring_batch_job_active_name="%1$s"} 0.75
            spring_batch_job_active_seconds_max{spring_batch_job_active_name="%1$s"} 0.75
            spring_batch_job_active_seconds_count{spring_batch_job_active_name="%2$s"} 0
            spring_batch_job_active_seconds_sum{spring_batch_job_active_name="%2$s"} 0.0
            spring_batch_job_active_seconds_max{spring_batch_job_active_name="%2$s"} 0.0
            """, RUNNING_BATCH_JOB, IDLE_BATCH_JOB);

    private static final Pattern GROUPING_LABEL =
            Pattern.compile("by\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)");
    private static final Pattern LEGEND_PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

    @TempDir
    private Path temporaryDirectory;

    /** Creates the test class. */
    MonitoringQueriesIT() {
    }

    @Test
    @DisplayName("both committed item-read expressions return the populated job and step series")
    void itemReadQueriesReturnPopulatedSeries() throws Exception {
        try (MetricEndpoint metrics = MetricEndpoint.start(MetricEndpoint::itemReadExposition)) {
            withPrometheusScraping(metrics, queryEndpoint -> {
                for (final String dashboardExpression : itemReadExpressions()) {
                    final JsonNode result = awaitPopulatedResult(
                            queryEndpoint, resolveDashboardVariables(dashboardExpression));
                    assertThat(result).hasSize(1);
                    final JsonNode labels = result.get(0).path("metric");
                    assertThat(labels.path(ITEM_READ_JOB_LABEL).asText()).isEqualTo(BATCH_JOB);
                    assertThat(labels.path(ITEM_READ_STEP_LABEL).asText()).isEqualTo(BATCH_STEP);
                }
            });
        }
    }

    /**
     * Executes the committed active-job expression against a real Prometheus server.
     *
     * <p>The delivered panel queried {@code spring_batch_job_active_seconds_active_count}, a suffix
     * the exporter never publishes, so it read "No data" under every condition. A name-only contract
     * test closes half of that hole: it proves each name exists in the exposition, but it cannot
     * prove the expression selects anything, and it cannot prove the {@code sum by (...)} grouping
     * label matches the label the legend interpolates. A grouping label that drifts by one character
     * still returns a series - an unlabelled one, which renders as a blank legend entry. This test
     * therefore requires Prometheus itself to return one populated series per job name, each
     * carrying the very label the panel's {@code legendFormat} names.</p>
     */
    @Test
    @DisplayName("the committed active-job expression returns one labelled series per job name")
    void activeJobQueryReturnsOneLabelledSeriesPerJobName() throws Exception {
        final JsonNode target = activeJobTarget();
        final String expression = target.path("expr").asText();
        final String legendLabel = soleCapture(
                LEGEND_PLACEHOLDER, target.path("legendFormat").asText(), "legend placeholder");
        assertThat(soleCapture(GROUPING_LABEL, expression, "grouping label"))
                .as("the panel must group by the same label its legend interpolates")
                .isEqualTo(legendLabel);

        try (MetricEndpoint metrics =
                MetricEndpoint.start(ignoredScrapeCount -> ACTIVE_JOB_EXPOSITION)) {
            withPrometheusScraping(metrics, queryEndpoint -> {
                final JsonNode result = awaitPopulatedResult(
                        queryEndpoint, resolveDashboardVariables(expression));
                final Map<String, String> activeCountByJob = new LinkedHashMap<>();
                for (final JsonNode series : iterable(result)) {
                    final String jobName = series.path("metric").path(legendLabel).asText();
                    assertThat(jobName)
                            .as("every returned series must carry the interpolated legend label")
                            .isNotEmpty();
                    activeCountByJob.put(jobName, series.path("value").path(1).asText());
                }
                assertThat(activeCountByJob)
                        .as("one series per job name, reading the number of active executions")
                        .containsExactlyInAnyOrderEntriesOf(
                                Map.of(RUNNING_BATCH_JOB, "1", IDLE_BATCH_JOB, "0"));
            });
        }
    }

    private void withPrometheusScraping(
            final MetricEndpoint metrics, final QueryAssertions assertions) throws Exception {
        Testcontainers.exposeHostPorts(metrics.port());
        final Path configuration = writePrometheusConfiguration(metrics.port());

        try (GenericContainer<?> prometheus =
                new GenericContainer<>(DockerImageName.parse(PROMETHEUS_IMAGE))
                        .withCopyToContainer(
                                MountableFile.forHostPath(configuration),
                                "/etc/prometheus/prometheus.yml")
                        .withExposedPorts(PROMETHEUS_PORT)
                        .waitingFor(Wait.forHttp("/-/ready")
                                .forPort(PROMETHEUS_PORT)
                                .withStartupTimeout(Duration.ofSeconds(30)))) {
            prometheus.start();

            assertions.verify(URI.create("http://"
                    + prometheus.getHost() + ":"
                    + prometheus.getMappedPort(PROMETHEUS_PORT)
                    + "/api/v1/query?query="));
        }
    }

    private static JsonNode activeJobTarget() throws IOException {
        final List<JsonNode> targets = new ArrayList<>();
        final JsonNode dashboard = JSON.readTree(DASHBOARD_PATH.toFile());
        for (final JsonNode panel : iterable(dashboard.path("panels"))) {
            final JsonNode panelTargets = panel.path("targets");
            if (!panelTargets.isArray()) {
                continue;
            }
            for (final JsonNode target : iterable(panelTargets)) {
                if (target.path("expr").asText().contains(ACTIVE_JOB_METRIC)) {
                    targets.add(target);
                }
            }
        }
        assertThat(targets)
                .as("exactly one dashboard target reads the active-job family")
                .hasSize(1);
        return targets.get(0);
    }

    private static String soleCapture(
            final Pattern pattern, final String text, final String description) {
        final Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).as("%s present in %s", description, text).isTrue();
        final String captured = matcher.group(1);
        assertThat(matcher.find()).as("exactly one %s in %s", description, text).isFalse();
        return captured;
    }

    private Path writePrometheusConfiguration(final int metricsPort) throws IOException {
        final Path configuration = this.temporaryDirectory.resolve("prometheus.yml");
        // Locale.ROOT is pinned because the port is rendered with %d into a file Prometheus parses. The
        // shorthand String.formatted accepts no locale and would have taken its digits from the ambient
        // default, so under a locale whose numbering system is not Latin the scrape target would have
        // named a port number Prometheus cannot read - and the failure would have surfaced as a target
        // that never came up rather than as a formatting defect.
        Files.writeString(configuration, String.format(Locale.ROOT, """
                global:
                  scrape_interval: 1s
                  evaluation_interval: 1s
                scrape_configs:
                  - job_name: carddemo-app
                    metrics_path: /actuator/prometheus
                    static_configs:
                      - targets:
                          - host.testcontainers.internal:%d
                """, metricsPort), StandardCharsets.UTF_8);
        return configuration;
    }

    private static JsonNode awaitPopulatedResult(
            final URI queryEndpoint, final String expression) throws Exception {
        final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JsonNode lastResponse = JSON.createObjectNode();
        for (int attempt = 0; attempt < 20; attempt++) {
            final URI requestUri = URI.create(queryEndpoint
                    + URLEncoder.encode(expression, StandardCharsets.UTF_8));
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(requestUri)
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(response.statusCode()).isEqualTo(200);
            lastResponse = JSON.readTree(response.body());
            final JsonNode result = lastResponse.path("data").path("result");
            if (result.isArray() && !result.isEmpty()) {
                return result;
            }
            Thread.sleep(500L);
        }
        throw new AssertionError(
                "Prometheus returned no series for expression " + expression + ": " + lastResponse);
    }

    private static String resolveDashboardVariables(final String expression) {
        return expression
                .replace("$__rate_interval", "1m")
                .replace("$__range", "5m")
                .replace("$itemReadJob", ".*")
                .replace("$step", ".*")
                .replace("$job", SCRAPE_JOB);
    }

    private static List<String> itemReadExpressions() throws IOException {
        final List<String> result = new ArrayList<>();
        final JsonNode dashboard = JSON.readTree(DASHBOARD_PATH.toFile());
        for (final JsonNode panel : iterable(dashboard.path("panels"))) {
            final JsonNode targets = panel.path("targets");
            if (!targets.isArray()) {
                continue;
            }
            for (final JsonNode target : iterable(targets)) {
                final String expression = target.path("expr").asText();
                if (expression.contains(ITEM_READ_METRIC)) {
                    result.add(expression);
                }
            }
        }
        assertThat(result).hasSize(2);
        return List.copyOf(result);
    }

    private static Iterable<JsonNode> iterable(final JsonNode array) {
        assertThat(array.isArray()).isTrue();
        return () -> StreamSupport.stream(array.spliterator(), false).iterator();
    }

    /** Assertions executed while a Prometheus server is scraping the local endpoint. */
    @FunctionalInterface
    private interface QueryAssertions {

        /**
         * Runs the assertions.
         *
         * @param queryEndpoint the instant-query endpoint, ending in {@code ?query=}
         * @throws Exception when an assertion or the query transport fails
         */
        void verify(URI queryEndpoint) throws Exception;
    }

    /** A local scrape endpoint whose counter increases once per Prometheus collection. */
    private static final class MetricEndpoint implements AutoCloseable {

        private static final long RECORDS_PER_SCRAPE = 350L;

        private final HttpServer server;
        private final AtomicLong count = new AtomicLong();
        private final LongFunction<String> exposition;

        private MetricEndpoint(final HttpServer server, final LongFunction<String> exposition) {
            this.server = server;
            this.exposition = exposition;
        }

        private static MetricEndpoint start(final LongFunction<String> exposition)
                throws IOException {
            final HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            final MetricEndpoint endpoint = new MetricEndpoint(server, exposition);
            server.createContext("/actuator/prometheus", endpoint::writeMetrics);
            server.start();
            return endpoint;
        }

        private int port() {
            return this.server.getAddress().getPort();
        }

        /**
         * Renders a monotonically increasing item-read counter, so that the rate and increase
         * expressions the dashboard commits have two distinct samples to work from.
         *
         * @param recordsRead the cumulative record count observed by this scrape
         * @return the exposition text for that scrape
         */
        private static String itemReadExposition(final long recordsRead) {
            // Locale.ROOT is pinned because %d renders the sample value into an exposition body a real
            // Prometheus scrapes. The shorthand String.formatted accepts no locale, so under a default
            // locale whose numbering system is not Latin this stub would have served a sample Prometheus
            // rejects, and the assertion below would have failed on an empty result rather than on the
            // digits that caused it.
            return String.format(Locale.ROOT, """
                    # HELP spring_batch_item_read_seconds Item read calls.
                    # TYPE spring_batch_item_read_seconds summary
                    %s{%s="%s",%s="%s"} %d
                    """,
                            ITEM_READ_METRIC,
                            ITEM_READ_JOB_LABEL,
                            BATCH_JOB,
                            ITEM_READ_STEP_LABEL,
                            BATCH_STEP,
                            recordsRead);
        }

        private void writeMetrics(final HttpExchange exchange) throws IOException {
            final byte[] body = this.exposition
                    .apply(this.count.addAndGet(RECORDS_PER_SCRAPE))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            this.server.stop(0);
        }
    }
}