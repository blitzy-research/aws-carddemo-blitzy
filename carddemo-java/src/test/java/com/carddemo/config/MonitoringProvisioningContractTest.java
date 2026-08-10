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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Protects the two monitoring contracts that neither Java compilation nor application startup reads.
 *
 * <p>The item-read panels must use the label names that Spring Batch 5.2.6 publishes through its
 * Observation API. Grafana provisioning must also reconcile its persistent database when a
 * version-controlled data source is removed. Both defects otherwise fail silently: the dashboard
 * loads with empty panels, or a deleted data source survives indefinitely in the named volume.</p>
 */
@DisplayName("monitoring provisioning: executable Batch labels and persistent data-source cleanup")
final class MonitoringProvisioningContractTest {

    private static final Path DASHBOARD_PATH =
            Path.of("config/grafana/dashboards/carddemo-overview.json");
    private static final Path DATASOURCE_PATH =
            Path.of("config/grafana/provisioning/datasources/datasource.yml");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static final String ITEM_READ_METRIC = "spring_batch_item_read_seconds_count";
    private static final String ITEM_READ_JOB_LABEL = "spring_batch_item_read_job_name";
    private static final String ITEM_READ_STEP_LABEL = "spring_batch_item_read_step_name";
    private static final String ITEM_READ_STATUS_LABEL = "spring_batch_item_read_status";
    private static final String EXPECTED_GROUPING =
            "sum by (" + ITEM_READ_JOB_LABEL + ", " + ITEM_READ_STEP_LABEL + ", "
                    + ITEM_READ_STATUS_LABEL + ")";
    private static final String EXPECTED_SELECTOR =
            "job=\"$job\", " + ITEM_READ_JOB_LABEL + "=~\"$itemReadJob\", "
                    + ITEM_READ_STEP_LABEL + "=~\"$step\"";
    private static final String EXPECTED_LEGEND =
            "{{" + ITEM_READ_JOB_LABEL + "}} / {{" + ITEM_READ_STEP_LABEL + "}} / {{"
                    + ITEM_READ_STATUS_LABEL + "}}";

    /** Creates the test class. */
    MonitoringProvisioningContractTest() {
    }

    @Test
    @DisplayName("both Gate-3 item-read panels group, filter and label series with the published tags")
    void itemReadPanelsUseTheSpringBatchObservationLabels() throws IOException {
        final List<JsonNode> targets = itemReadTargets();

        assertThat(targets)
                .as("the dashboard must retain the throughput and selected-window item-read panels")
                .hasSize(2);
        for (final JsonNode target : targets) {
            final String expression = target.path("expr").asText();
            assertThat(expression)
                    .contains(EXPECTED_GROUPING)
                    .contains(ITEM_READ_METRIC + "{" + EXPECTED_SELECTOR + "}")
                    .doesNotContain("sum by (job_name, step_name)");
            assertThat(target.path("legendFormat").asText())
                    .isEqualTo(EXPECTED_LEGEND)
                    .doesNotContain("{{job_name}}")
                    .doesNotContain("{{step_name}}");
        }
    }

    @Test
    @DisplayName("the item-read job filter discovers the same published label the panels select")
    void itemReadJobVariableUsesThePublishedJobLabel() throws IOException {
        final JsonNode variable = variableNamed("itemReadJob");
        final String expectedQuery =
                "label_values(" + ITEM_READ_METRIC + "{job=\"carddemo-app\"}, "
                        + ITEM_READ_JOB_LABEL + ")";

        assertThat(variable.path("definition").asText()).isEqualTo(expectedQuery);
        assertThat(variable.path("query").asText()).isEqualTo(expectedQuery);
        assertThat(variable.path("allValue").asText()).isEqualTo(".*");
        assertThat(variable.path("includeAll").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Grafana prunes removed provisioned sources and explicitly deletes legacy Jaeger")
    void datasourceProvisioningReconcilesPersistentState() throws IOException {
        final JsonNode root = YAML.readTree(DATASOURCE_PATH.toFile());

        assertThat(root.path("apiVersion").asInt()).isEqualTo(1);
        assertThat(root.path("prune").asBoolean())
                .as("without pruning, removed provisioned sources survive in the named volume")
                .isTrue();

        final JsonNode deletions = root.path("deleteDatasources");
        assertThat(deletions.isArray()).isTrue();
        assertThat(deletions).hasSize(1);
        assertThat(deletions.get(0).path("name").asText()).isEqualTo("Jaeger");
        assertThat(deletions.get(0).path("orgId").asInt()).isEqualTo(1);

        final JsonNode active = root.path("datasources");
        assertThat(active.isArray()).isTrue();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).path("name").asText()).isEqualTo("Prometheus");
        assertThat(active.get(0).path("uid").asText()).isEqualTo("carddemo-prometheus");
        assertThat(active.get(0).path("type").asText()).isEqualTo("prometheus");
    }

    private static List<JsonNode> itemReadTargets() throws IOException {
        final List<JsonNode> result = new ArrayList<>();
        for (final JsonNode panel : iterable(dashboard().path("panels"))) {
            final JsonNode targets = panel.path("targets");
            if (!targets.isArray()) {
                continue;
            }
            for (final JsonNode target : iterable(targets)) {
                if (target.path("expr").asText().contains(ITEM_READ_METRIC)) {
                    result.add(target);
                }
            }
        }
        return List.copyOf(result);
    }

    private static JsonNode variableNamed(final String name) throws IOException {
        for (final JsonNode variable :
                iterable(dashboard().path("templating").path("list"))) {
            if (name.equals(variable.path("name").asText())) {
                return variable;
            }
        }
        throw new AssertionError("Dashboard variable not found: " + name);
    }

    private static JsonNode dashboard() throws IOException {
        assertThat(DASHBOARD_PATH).isRegularFile();
        return JSON.readTree(DASHBOARD_PATH.toFile());
    }

    private static Iterable<JsonNode> iterable(final JsonNode array) {
        assertThat(array.isArray()).isTrue();
        return () -> StreamSupport.stream(array.spliterator(), false).iterator();
    }
}
