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
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the Gate 3 dashboard to the metric names and labels the application actually exports.
 */
@DisplayName("Grafana batch throughput panels use exact counters and real labels")
class GrafanaDashboardMetricsContractTest {

    private static final Path DASHBOARD =
            Path.of("config/grafana/dashboards/carddemo-overview.json");

    private static final Pattern COUNTER =
            Pattern.compile("(?:rate|increase)\\(([a-zA-Z0-9_:]+)\\{");

    private static final Set<String> EXACT_COUNTERS = Set.of(
            "carddemo_batch_posting_records_total",
            "carddemo_batch_interest_rows_total",
            "carddemo_batch_report_records_total",
            "carddemo_batch_statement_records_total",
            "carddemo_batch_statement_htmlRecords_records_total",
            "carddemo_batch_fileprobe_records_total");

    private static JsonNode dashboard;

    @BeforeAll
    static void readDashboard() throws IOException {
        dashboard = new ObjectMapper().readTree(DASHBOARD.toFile());
    }

    @Test
    @DisplayName("the records-per-second gate reads every exact application counter through an explicit "
            + "rate window, including the tasklet interest-row counter")
    void exactRatePanelUsesApplicationCounters() {
        final JsonNode panel = panel(11);
        final Set<String> counters = countersOf(panel);

        assertThat(panel.path("title").asText())
                .contains("Exact application records per second", "GATE 3");
        assertThat(counters).containsExactlyInAnyOrderElementsOf(EXACT_COUNTERS);
        assertThat(expressionsOf(panel))
                .allSatisfy(expression -> assertThat(expression)
                        .contains("[$__rate_interval]")
                        .doesNotContain("spring_batch_item_read_seconds_count"));
        assertThat(expressionsOf(panel))
                .anySatisfy(expression -> assertThat(expression)
                        .contains("carddemo_batch_interest_rows_total")
                        .contains("sum by (step)"));
    }

    @Test
    @DisplayName("the selected-window panel reads the same exact counters rather than a timer-call count")
    void exactWindowPanelUsesTheSameCounters() {
        final JsonNode panel = panel(12);

        assertThat(countersOf(panel)).containsExactlyInAnyOrderElementsOf(EXACT_COUNTERS);
        assertThat(expressionsOf(panel))
                .allSatisfy(expression -> assertThat(expression)
                        .contains("[$__range]")
                        .doesNotContain("spring_batch_item_read_seconds_count"));
    }

    @Test
    @DisplayName("the supplementary framework diagnostic uses Spring Batch's sanitized labels and is "
            + "explicitly not presented as the exact record source")
    void frameworkDiagnosticUsesRealLabels() {
        final JsonNode panel = panel(31);
        final String expression = expressionsOf(panel).iterator().next();

        assertThat(expression)
                .contains("spring_batch_item_read_seconds_count")
                .contains("[$__rate_interval]")
                .contains("spring_batch_item_read_job_name")
                .contains("spring_batch_item_read_step_name")
                .contains("spring_batch_item_read_status")
                .doesNotContain("sum by (job_name, step_name)");
        assertThat(panel.path("title").asText()).contains("diagnostic only");
        assertThat(panel.path("description").asText())
                .contains("not the Gate 3 record rate", "terminal call");
    }

    @Test
    @DisplayName("no query retains the nonexistent unprefixed Spring Batch labels")
    void noQueryUsesTheOldLabels() {
        assertThat(dashboard.toString())
                .doesNotContain("sum by (job_name, step_name)")
                .doesNotContain("{{job_name}} / {{step_name}}");
    }

    @Test
    @DisplayName("every queried application counter name is the name the Prometheus registry exports")
    void queriedCounterNamesMatchTheExporter() {
        final PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            Counter.builder("carddemo.batch.posting.records").register(registry).increment();
            Counter.builder("carddemo.batch.interest.rows").register(registry).increment();
            Counter.builder("carddemo.batch.report.records")
                    .baseUnit("records").register(registry).increment();
            Counter.builder("carddemo.batch.statement.records")
                    .baseUnit("records").register(registry).increment();
            Counter.builder("carddemo.batch.statement.htmlRecords")
                    .baseUnit("records").register(registry).increment();
            Counter.builder("carddemo.batch.fileprobe.records").register(registry).increment();

            final String exposition = registry.scrape();
            assertThat(EXACT_COUNTERS)
                    .allSatisfy(counter -> assertThat(exposition).contains(counter));
        } finally {
            registry.close();
        }
    }

    private static JsonNode panel(final int id) {
        for (final JsonNode candidate : dashboard.path("panels")) {
            if (candidate.path("id").asInt() == id) {
                return candidate;
            }
        }
        throw new AssertionError("Dashboard panel " + id + " is missing");
    }

    private static Set<String> expressionsOf(final JsonNode panel) {
        final Set<String> expressions = new LinkedHashSet<>();
        for (final JsonNode target : panel.path("targets")) {
            expressions.add(target.path("expr").asText());
        }
        return expressions;
    }

    private static Set<String> countersOf(final JsonNode panel) {
        final Set<String> counterNames = new LinkedHashSet<>();
        for (final String expression : expressionsOf(panel)) {
            final Matcher matcher = COUNTER.matcher(expression);
            if (!matcher.find()) {
                throw new AssertionError("No rate/increase counter in expression: " + expression);
            }
            counterNames.add(matcher.group(1));
        }
        return counterNames;
    }
}
