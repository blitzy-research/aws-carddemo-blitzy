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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
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

    @TempDir
    private Path temporaryDirectory;

    /** Creates the test class. */
    MonitoringQueriesIT() {
    }

    @Test
    @DisplayName("both committed item-read expressions return the populated job and step series")
    void itemReadQueriesReturnPopulatedSeries() throws Exception {
        try (MetricEndpoint metrics = MetricEndpoint.start()) {
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

                final URI queryEndpoint = URI.create("http://"
                        + prometheus.getHost() + ":"
                        + prometheus.getMappedPort(PROMETHEUS_PORT)
                        + "/api/v1/query?query=");
                for (final String dashboardExpression : itemReadExpressions()) {
                    final JsonNode result = awaitPopulatedResult(
                            queryEndpoint, resolveDashboardVariables(dashboardExpression));
                    assertThat(result).hasSize(1);
                    final JsonNode labels = result.get(0).path("metric");
                    assertThat(labels.path(ITEM_READ_JOB_LABEL).asText()).isEqualTo(BATCH_JOB);
                    assertThat(labels.path(ITEM_READ_STEP_LABEL).asText()).isEqualTo(BATCH_STEP);
                }
            }
        }
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

    /** A local scrape endpoint whose counter increases once per Prometheus collection. */
    private static final class MetricEndpoint implements AutoCloseable {

        private static final long RECORDS_PER_SCRAPE = 350L;

        private final HttpServer server;
        private final AtomicLong count = new AtomicLong();

        private MetricEndpoint(final HttpServer server) {
            this.server = server;
        }

        private static MetricEndpoint start() throws IOException {
            final HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            final MetricEndpoint endpoint = new MetricEndpoint(server);
            server.createContext("/actuator/prometheus", endpoint::writeMetrics);
            server.start();
            return endpoint;
        }

        private int port() {
            return this.server.getAddress().getPort();
        }

        private void writeMetrics(final HttpExchange exchange) throws IOException {
            final long value = this.count.addAndGet(RECORDS_PER_SCRAPE);
            // Locale.ROOT is pinned because %d renders the sample value into an exposition body a real
            // Prometheus scrapes. The shorthand String.formatted accepts no locale, so under a default
            // locale whose numbering system is not Latin this stub would have served a sample Prometheus
            // rejects, and the assertion below would have failed on an empty result rather than on the
            // digits that caused it.
            final byte[] body = String.format(Locale.ROOT, """
                    # HELP spring_batch_item_read_seconds Item read calls.
                    # TYPE spring_batch_item_read_seconds summary
                    %s{%s="%s",%s="%s"} %d
                    """,
                            ITEM_READ_METRIC,
                            ITEM_READ_JOB_LABEL,
                            BATCH_JOB,
                            ITEM_READ_STEP_LABEL,
                            BATCH_STEP,
                            value)
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