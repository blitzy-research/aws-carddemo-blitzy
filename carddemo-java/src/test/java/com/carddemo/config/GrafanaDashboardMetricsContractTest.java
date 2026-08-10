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

import com.carddemo.service.JobCompletionNotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the Gate 3 dashboard to the metric names and labels the application actually exports.
 *
 * <h2>Why one of these tests scrapes a registry instead of reading the file</h2>
 *
 * <p>A panel expression is a string in a JSON document that no Java class reads, so a name that the
 * exporter never publishes is not a compilation error, not a failed context refresh and not a broken
 * query - it is a panel that renders "No data" for ever, which is indistinguishable from a quiet system.
 * That is exactly what happened to the active-executions panel: it asked for an {@code _active_count}
 * suffix that Micrometer's Prometheus exporter does not produce for a long-task timer, and every
 * string-comparing test on this dashboard passed anyway because they compared the file with an
 * expectation written from the same misunderstanding.
 *
 * <p>{@link #everyDashboardMetricNameIsPublishedByTheExporter()} therefore takes every metric name out
 * of every panel target <em>and</em> every template query, and requires each one to appear in a single
 * real {@link PrometheusMeterRegistry#scrape()} exposition built from the real meter types and the real
 * framework binders. Nothing about a suffix, a base unit or a name-mangling rule is restated here: the
 * exporter supplies them, so drift on either side of the boundary fails the build.
 */
@DisplayName("Grafana batch throughput panels use exact counters and real labels")
class GrafanaDashboardMetricsContractTest {

    private static final Path DASHBOARD =
            Path.of("config/grafana/dashboards/carddemo-overview.json");

    private static final Pattern COUNTER =
            Pattern.compile("(?:rate|increase)\\(([a-zA-Z0-9_:]+)\\{");

    /**
     * Every metric selector in a PromQL expression: a name immediately followed by a label matcher.
     *
     * <p>Every expression on this dashboard scopes itself with at least {@code job="$job"}, so a
     * selector always carries a brace. A PromQL function name is never followed by one, which is what
     * makes this a metric-name reader rather than a token reader.
     */
    private static final Pattern METRIC_SELECTOR =
            Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*\\{");

    /**
     * A PromQL aggregation grouping, capturing the label list inside {@code by ( ... )}.
     *
     * <p>Reads the grouping and nothing else, so a series selector such as {@code {job="$job"}} - which is
     * a correct use of the scrape label - is not mistaken for an aggregation over it.
     */
    private static final Pattern GROUPING = Pattern.compile("\\bby\\s*\\(([^)]*)\\)");

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
    @DisplayName("the rolling-rate panel reads every exact application counter through an explicit rate "
            + "window, including the tasklet interest-row counter, and does not claim to be the gate figure")
    void exactRatePanelUsesApplicationCounters() {
        final JsonNode panel = panel(11);
        final Set<String> counters = countersOf(panel);

        // The title used to read "GATE 3 RECORDS PER SECOND", and this assertion used to require it. It
        // was a claim the expression could not support: rate() divides by the RATE WINDOW, while Gate 3
        // asks for a run's records over that run's own elapsed time, so a job finishing inside the window
        // reads low. The panel is a sound visualization of throughput and is now labelled as one; the
        // quotable figure is measured per run through support/RunScopedPerformanceRecorder.
        assertThat(panel.path("title").asText())
                .contains("Application records per second", "rolling rate", "not the Gate 3 figure")
                .doesNotContain("GATE 3 RECORDS PER SECOND");
        assertThat(panel.path("description").asText())
                .as("the description must say what the divisor actually is, or the title reads as modesty "
                        + "rather than as a fact about the query")
                .contains("RATE WINDOW")
                .contains("docs/gate-evidence.md");
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
    @DisplayName("no panel presents itself as the source of a Gate 3 figure, because none computes one")
    void noPanelClaimsToBeTheGateFigure() {
        // Three claims of that kind existed and all three were unsupportable: the rolling-rate panel
        // ("GATE 3 RECORDS PER SECOND") divides by the rate window; the windowed-peak panel
        // ("GATE 3 PEAK MEMORY") is a maximum over scrapes, so a peak between two scrapes is invisible;
        // and the JVM row announced that the peak was "read from this row". Each is now labelled a
        // visualization. Stated as a whole-document rule so a fourth cannot be introduced quietly.
        assertThat(dashboard.toString())
                .doesNotContain("GATE 3 RECORDS PER SECOND")
                .doesNotContain("GATE 3 PEAK MEMORY")
                .doesNotContain("GATE 3 PEAK MEMORY is read from this row");

        // Panel 9 joins the three. Its title claimed to CORROBORATE the Gate 3 elapsed figure while the
        // guidance panel correctly stated that no panel here is the run-scoped figure, so the dashboard
        // contradicted itself about one number and a reader following either statement was misinformed by
        // the other. It reads a real per-execution timer, which is why the claim was tempting, but it reads
        // it over the DISPLAY WINDOW while the quoted figure comes from the measured run. See
        // docs/decision-log.md entry DL-313.
        for (final int visualizationOnly : new int[] {9, 11, 12, 17}) {
            assertThat(panel(visualizationOnly).path("title").asText())
                    .as("panel %d shows a figure adjacent to a Gate 3 figure and must say which it is",
                            visualizationOnly)
                    .contains("visualization");
        }
        assertThat(panel(9).path("title").asText())
                .as("the corrected title must also say WHICH figure it is not, or 'visualization' leaves a "
                        + "reader to work out what it is a visualization instead of")
                .contains("not the Gate 3 elapsed figure");
        assertThat(dashboard.toString())
                .as("no panel title or description may claim to corroborate a gate figure; corroboration "
                        + "was the exact word that made a visualization read as evidence")
                .doesNotContain("corroborates the Gate 3")
                .doesNotContain("corroborates the gate");
    }

    @Test
    @DisplayName("the guidance names only panels that exist, and names where each figure really comes from")
    void theGuidanceNamesRealPanelsAndTheRealSource() {
        // The guidance table pointed at a panel called "Records read per second", which no panel on this
        // dashboard is titled - so a reader following the gate's own instructions arrived nowhere. Every
        // panel title the guidance mentions is checked against the titles the document actually carries.
        final String guidance = panel(25).path("options").path("content").asText();
        final Set<String> titles = new LinkedHashSet<>();
        for (final JsonNode candidate : dashboard.path("panels")) {
            titles.add(candidate.path("title").asText());
        }

        assertThat(guidance)
                .as("the panel that never existed must not be named again")
                .doesNotContain("Records read per second");
        for (final String named : List.of("Batch job elapsed time", "Batch step elapsed time",
                "Peak heap across scraped samples", "Application records per second (rolling rate)")) {
            assertThat(guidance).as("the guidance must name %s", named).contains(named);
            assertThat(titles)
                    .as("a panel the guidance sends the reader to must exist; %s matches no title", named)
                    .anySatisfy(title -> assertThat(title).contains(named));
        }
        assertThat(guidance)
                .as("and it must send the reader to the measurement that produces the quotable figures")
                .contains("RunScopedPerformanceRecorder")
                .contains("docs/gate-evidence.md")
                .contains("No panel on this dashboard is the Gate 3 figure");
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

    @Test
    @DisplayName("every metric name on the dashboard - in a panel target or in a template query - is a "
            + "name the Prometheus exporter actually publishes")
    void everyDashboardMetricNameIsPublishedByTheExporter() {
        final Set<String> queried = queriedMetricNames();
        final Set<String> published = publishedMetricNames();

        assertThat(queried)
                .as("the reader must find the dashboard's queries; an empty set would pass vacuously")
                .hasSizeGreaterThan(20);
        assertThat(published)
                .as("a name absent from a real exposition is a panel that renders no data under any "
                        + "condition")
                .containsAll(queried);
        assertThat(queried)
                .as("a long-task timer publishes _count, _sum and _max; there is no _active_count "
                        + "suffix in the exposition at all")
                .noneMatch(name -> name.endsWith("_active_count"));
    }

    /**
     * Every metric name the dashboard queries, from panel targets and from template variables alike.
     *
     * @return the queried metric names
     */
    private static Set<String> queriedMetricNames() {
        final Set<String> names = new LinkedHashSet<>();
        for (final JsonNode panel : dashboard.path("panels")) {
            for (final JsonNode target : panel.path("targets")) {
                collectMetricNames(target.path("expr").asText(), names);
            }
        }
        for (final JsonNode variable : dashboard.path("templating").path("list")) {
            collectMetricNames(variable.path("query").asText(), names);
            collectMetricNames(variable.path("definition").asText(), names);
        }
        return names;
    }

    /**
     * Adds every metric selector of one expression to the accumulating set.
     *
     * @param expression a panel expression or a template query
     * @param names      set the names are added to
     */
    private static void collectMetricNames(final String expression, final Set<String> names) {
        final Matcher matcher = METRIC_SELECTOR.matcher(expression);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
    }

    /**
     * Scrapes one registry carrying a representative meter of every family the dashboard reads.
     *
     * <p>Each meter is registered through the same type the production code or the framework registers -
     * a counter with its base unit, a timer, a long-task timer, a real JVM or processor binder, the real
     * connection-pool tracker - so the exposition's suffixes and name mangling are the exporter's own
     * rather than this test's. Only the base names are stated here, because a base name is the part the
     * dashboard and the code have to agree on.
     *
     * @return every metric name the exposition publishes
     */
    private static Set<String> publishedMetricNames() {
        final PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (HikariDataSource pool = connectionPool(registry)) {
            registerApplicationCounters(registry);
            registerTimers(registry);
            new JvmMemoryMetrics().bindTo(registry);
            new JvmThreadMetrics().bindTo(registry);
            new ProcessorMetrics().bindTo(registry);
            new UptimeMetrics().bindTo(registry);

            // Scraped while the pool is still open: the pool removes its own meters on shutdown, so a
            // scrape taken afterwards would report the connection-pool family as unpublished.
            assertThat(pool.isClosed()).isFalse();
            return expositionNames(registry.scrape());
        } finally {
            registry.close();
        }
    }

    /**
     * Registers the application-owned counters, with the base units the production code declares.
     *
     * @param registry registry to publish into
     */
    private static void registerApplicationCounters(final PrometheusMeterRegistry registry) {
        Counter.builder("carddemo.batch.posting.records").register(registry).increment();
        Counter.builder("carddemo.batch.interest.rows").register(registry).increment();
        Counter.builder("carddemo.batch.report.records")
                .baseUnit("records").register(registry).increment();
        Counter.builder("carddemo.batch.statement.records")
                .baseUnit("records").register(registry).increment();
        Counter.builder("carddemo.batch.statement.htmlRecords")
                .baseUnit("records").register(registry).increment();
        Counter.builder("carddemo.batch.fileprobe.records").register(registry).increment();
        Counter.builder("carddemo.batch.reject.records")
                .baseUnit("records").register(registry).increment();
        // The boundary counters, added when the boundary row was added. Each is registered here through the
        // same TYPE the production code uses, because the type is what decides the exposition's suffixes:
        // a counter publishes _total and a timer publishes _seconds_count, _seconds_sum and _seconds_max,
        // and a panel querying the wrong suffix renders nothing under every condition.
        Counter.builder("carddemo.job.completion.shed").register(registry).increment();
        Counter.builder("carddemo.batch.job.terminal").register(registry).increment();
        Counter.builder("carddemo.http.request.refused").register(registry).increment();
        Counter.builder("carddemo.tracing.export").register(registry).increment();
        Counter.builder("carddemo.management.authentication").register(registry).increment();
    }

    /**
     * Registers every timed family the dashboard reads, framework-owned and application-owned alike.
     *
     * @param registry registry to publish into
     */
    private static void registerTimers(final PrometheusMeterRegistry registry) {
        Timer.builder("http.server.requests").publishPercentileHistogram()
                .register(registry).record(Duration.ofMillis(5));
        Timer.builder("spring.batch.job").register(registry).record(Duration.ofMillis(5));
        Timer.builder("spring.batch.step").register(registry).record(Duration.ofMillis(5));
        Timer.builder("spring.batch.item.read").register(registry).record(Duration.ofMillis(1));
        Timer.builder("carddemo.batch.cobol.step").register(registry).record(Duration.ofMillis(5));
        Timer.builder("carddemo.batch.reject.write").register(registry).record(Duration.ofMillis(1));
        Timer.builder("carddemo.online.signon.turn").register(registry).record(Duration.ofMillis(5));
        // The collection-pause family is registered by its binder only when the first collection
        // notification arrives, which a short test cannot provoke deterministically, so it is stated by
        // base name exactly as the framework families above are. The suffixes remain the exporter's.
        Timer.builder("jvm.gc.pause").register(registry).record(Duration.ofMillis(1));
        LongTaskTimer.builder("spring.batch.job.active").register(registry).start().stop();
        // The observation-derived boundary families. Each reaches the registry as a TIMER, because
        // Micrometer's default observation handler stops an observation into one - which is also why the
        // panels reading them group by the 'error' label the handler adds rather than by an outcome tag the
        // observation would have had to declare.
        Timer.builder("carddemo.job.submission.publish").register(registry).record(Duration.ofMillis(5));
        Timer.builder("carddemo.job.completion.publish").register(registry).record(Duration.ofMillis(5));
        Timer.builder("carddemo.batch.staging").register(registry).record(Duration.ofMillis(5));
        Timer.builder("carddemo.batch.generation").register(registry).record(Duration.ofMillis(5));
        Timer.builder("carddemo.batch.job.publication").register(registry).record(Duration.ofMillis(5));
        Timer.builder("carddemo.batch.joblaunch.request").register(registry).record(Duration.ofMillis(5));
        Timer.builder("carddemo.batch.jobstatus.request").register(registry).record(Duration.ofMillis(5));
        Timer.builder("carddemo.online.reportrequest.turn").register(registry).record(Duration.ofMillis(5));
        // The observation-derived active-job meter, which this module renames off the framework's colliding
        // name. A long-task timer, exactly as the framework's is.
        LongTaskTimer.builder("carddemo.batch.job.observed.active").register(registry).start().stop();
    }

    /**
     * Opens a connection pool that publishes the pool family through the pool's own Micrometer tracker.
     *
     * <p>Initialisation failure is deferred and no connection is ever requested, so no database is
     * involved; the meters are registered when the pool starts, which is all this needs. The caller
     * closes the pool, and must do so only after the scrape.
     *
     * @param registry registry to publish into
     * @return the open pool
     */
    private static HikariDataSource connectionPool(final PrometheusMeterRegistry registry) {
        final HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("dashboard-contract");
        configuration.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/none");
        configuration.setUsername("none");
        configuration.setPassword("none");
        configuration.setMinimumIdle(0);
        configuration.setMaximumPoolSize(1);
        configuration.setInitializationFailTimeout(-1L);
        configuration.setConnectionTimeout(250L);
        configuration.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        return new HikariDataSource(configuration);
    }

    /**
     * Reads the metric names out of one Prometheus exposition.
     *
     * @param exposition the scraped text
     * @return every published metric name, comments and label sets removed
     */
    private static Set<String> expositionNames(final String exposition) {
        final Set<String> names = new LinkedHashSet<>();
        for (final String line : exposition.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            final int labels = line.indexOf('{');
            final int space = line.indexOf(' ');
            final int end = labels >= 0 && (space < 0 || labels < space) ? labels : space;
            names.add(end < 0 ? line : line.substring(0, end));
        }
        return names;
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
    /**
     * Holds the module's batch identity off the label the collector stamps on every series itself.
     *
     * <p>See {@code docs/decision-log.md} entry DL-338.
     */
    @Nested
    @DisplayName("the reserved scrape label is not an application dimension")
    class TheReservedScrapeLabelIsNotAnApplicationDimension {

        /** The label Prometheus stamps onto every series it collects, naming the scrape target. */
        private static final String RESERVED_SCRAPE_LABEL = "job";

        /** The application dimension that replaced it, spelled as this module spells its tag keys. */
        private static final String APPLICATION_BATCH_LABEL = "batchJob";

        /** The root of the application source the tag-key sweep reads. */
        private static final Path MAIN_SOURCE = Path.of("src", "main", "java");

        /** A tag key written as a literal, in any of the four forms this module writes them. */
        private static final Pattern TAG_KEY_LITERAL = Pattern.compile(
                "(?:\\.tag\\(|Tag\\.of\\(|lowCardinalityKeyValue\\(|highCardinalityKeyValue\\("
                        + "|KeyValue\\.of\\(|TAG_[A-Z_]+\\s*=\\s*)\"([A-Za-z0-9_.]+)\"");

        /** Creates the nested test class. */
        TheReservedScrapeLabelIsNotAnApplicationDimension() {
        }

        @Test
        @DisplayName("no application source declares a tag key named after the reserved scrape label, "
                + "which is the whole of the collision")
        void noApplicationSourceDeclaresTheReservedLabel() throws IOException {
            final Map<Path, List<Integer>> offenders = new LinkedHashMap<>();
            try (Stream<Path> sources = Files.walk(MAIN_SOURCE)) {
                for (final Path source : sources.filter(path -> path.toString().endsWith(".java"))
                        .toList()) {
                    final List<String> lines = Files.readAllLines(source);
                    for (int index = 0; index < lines.size(); index++) {
                        final Matcher keys = TAG_KEY_LITERAL.matcher(lines.get(index));
                        while (keys.find()) {
                            if (RESERVED_SCRAPE_LABEL.equals(keys.group(1))) {
                                offenders.computeIfAbsent(source, key -> new ArrayList<>())
                                        .add(Integer.valueOf(index + 1));
                            }
                        }
                    }
                }
            }

            assertThat(offenders)
                    .as("THIS IS THE DEFECT THIS PINS. Prometheus stamps its own '%s' label onto every "
                            + "series it collects, naming the SCRAPE TARGET. Two labels of one name cannot "
                            + "coexist, so with the default honour-labels posture the exporter's value is "
                            + "renamed to 'exported_%s' on collection - and every query grouping by '%s' "
                            + "then collapses to the single target, SILENTLY, with the panel still "
                            + "rendering. The module's batch identity is '%s'. Each entry below is a file "
                            + "and the lines on which it declares the reserved name as an application tag "
                            + "key", RESERVED_SCRAPE_LABEL, RESERVED_SCRAPE_LABEL, RESERVED_SCRAPE_LABEL,
                            APPLICATION_BATCH_LABEL)
                    .isEmpty();
        }

        @Test
        @DisplayName("the two published batch-identity constants carry the application label, so a "
                + "consumer reading them by name gets the same dimension the meters carry")
        void thePublishedConstantsCarryTheApplicationLabel() {
            assertThat(BatchConfig.TAG_JOB)
                    .as("the terminal-verdict counter's job dimension")
                    .isEqualTo(APPLICATION_BATCH_LABEL);
            assertThat(JobCompletionNotificationService.TAG_JOB)
                    .as("the completion-publication observation's job dimension")
                    .isEqualTo(APPLICATION_BATCH_LABEL);
            assertThat(BatchConfig.TAG_JOB_EXECUTION_ID)
                    .as("and the sibling declared on the same observation is camel case too, which is "
                            + "why the replacement is spelled this way rather than with an underscore: "
                            + "one meter must not carry two naming conventions")
                    .isEqualTo("jobExecutionId");
        }

        @Test
        @DisplayName("a real exposition of the module's own batch series carries the application label "
                + "and no reserved one, read out of the exporter rather than asserted about it")
        void theExpositionCarriesTheApplicationLabelAndNoReservedOne() {
            final PrometheusMeterRegistry registry =
                    new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            Timer.builder("carddemo.batch.joblaunch.request")
                    .tag(BatchConfig.TAG_JOB, "postTransactionJob")
                    .tag("outcome", "launched")
                    .register(registry).record(Duration.ofMillis(5));
            Counter.builder("carddemo.batch.job.terminal")
                    .tag(BatchConfig.TAG_JOB, "postTransactionJob")
                    .tag("status", "COMPLETED")
                    .register(registry).increment();

            final List<String> applicationSeries = Stream.of(registry.scrape().split("\n"))
                    .filter(line -> line.startsWith("carddemo_"))
                    .toList();

            assertThat(applicationSeries)
                    .as("the fixture must actually produce series, or the assertion below is vacuous")
                    .isNotEmpty();
            assertThat(applicationSeries)
                    .as("every application series must name its job through the application label")
                    .allMatch(line -> line.contains(APPLICATION_BATCH_LABEL + "=\""));
            assertThat(applicationSeries)
                    .as("and none may present a label named after the scrape label; a collector would "
                            + "rename it and the dimension would be lost before any query saw it")
                    .noneMatch(line -> line.contains("," + RESERVED_SCRAPE_LABEL + "=\"")
                            || line.contains("{" + RESERVED_SCRAPE_LABEL + "=\""));
        }

        @Test
        @DisplayName("every dashboard grouping and legend over an application series names the "
                + "application label, and the reserved one survives only as the target selector")
        void everyApplicationGroupingNamesTheApplicationLabel() {
            final List<String> offendingExpressions = new ArrayList<>();
            final List<String> offendingLegends = new ArrayList<>();
            for (final JsonNode candidate : dashboard.path("panels")) {
                for (final JsonNode target : candidate.path("targets")) {
                    final String expression = target.path("expr").asText();
                    if (!expression.contains("carddemo_")) {
                        continue;
                    }
                    if (GROUPING.matcher(expression).results()
                            .flatMap(match -> Stream.of(match.group(1).split(",")))
                            .map(String::strip)
                            // Compared as a WHOLE label rather than as a substring. The framework's own
                            // sanitized labels - spring_batch_job_name, spring_batch_job_status - contain
                            // the reserved name and are perfectly legitimate groupings; a substring test
                            // would report them and the real offence would be lost in the noise.
                            .anyMatch(RESERVED_SCRAPE_LABEL::equals)) {
                        offendingExpressions.add(expression);
                    }
                    final String legend = target.path("legendFormat").asText();
                    if (legend.contains("{{" + RESERVED_SCRAPE_LABEL + "}}")) {
                        offendingLegends.add(legend);
                    }
                }
            }

            assertThat(offendingExpressions)
                    .as("a grouping by the scrape label collapses every batch job of this module onto "
                            + "the one scrape target, so a per-job panel charts one line labelled with "
                            + "the application's own name")
                    .isEmpty();
            assertThat(offendingLegends)
                    .as("and a legend reading the scrape label prints that target name against every "
                            + "series, which is how the collapse goes unnoticed")
                    .isEmpty();
            assertThat(expressionsOf(panel(41)))
                    .as("the selector that scopes a panel to this stack's own target is CORRECT and is "
                            + "deliberately retained; only the grouping and the legend moved")
                    .allMatch(expression -> expression.contains(
                            RESERVED_SCRAPE_LABEL + "=\\\"$" + RESERVED_SCRAPE_LABEL + "\\\"")
                            || expression.contains(RESERVED_SCRAPE_LABEL + "=\"$"
                                    + RESERVED_SCRAPE_LABEL + "\""));
            assertThat(expressionsOf(panel(41)))
                    .as("and each of that panel's targets groups by the application label")
                    .allMatch(expression -> expression.contains(
                            "by (" + APPLICATION_BATCH_LABEL + ","));
        }
    }

    /**
     * The operational panel inventory, asserted in the direction the contract was missing.
     *
     * <h2>Why one direction was not enough</h2>
     *
     * <p>Until review this class asserted only that every metric the dashboard QUERIES is a metric the
     * exporter PUBLISHES. That catches a panel charting a name that does not exist, which is the failure a
     * reader notices immediately - an empty panel. It cannot catch the opposite and quieter failure: a
     * meter this module registers, pays for and relies on, that no panel reads. The dashboard charted the
     * request surface, the batch steps and the runtime, and charted none of the BOUNDARIES - so a failing
     * queue publish, a shed job-completion notification, a refused management credential, a refused request
     * body and a lost trace batch were all measured and all invisible here.
     *
     * <p>The inventory below is therefore a REQUIREMENT rather than a description. Each entry names a
     * boundary this module owns, and adding a meter for a new boundary without a panel is intended to fail
     * this test. It is deliberately not "every meter the module registers": the per-screen turn timers are
     * seventeen families whose aggregate already appears on the per-endpoint row, and charting each
     * individually would produce a dashboard nobody reads. What it does cover is every boundary where this
     * module talks to something outside itself, plus every refusal that happens before a caller is
     * authenticated.
     *
     * <p>See {@code docs/decision-log.md} entry DL-313.
     */
    @Nested
    @DisplayName("the required operational panel inventory, asserted in both directions")
    class TheOperationalPanelInventory {

        /**
         * Every boundary family a panel must read, as the exposition names it.
         *
         * <p>Base names without a suffix: a family is satisfied by any panel reading any of its series, so
         * a counter charted as a rate and a timer charted as a maximum both count.
         */
        private static final List<String> REQUIRED_FAMILIES = List.of(
                "carddemo_job_submission_publish_seconds",
                "carddemo_job_completion_publish_seconds",
                "carddemo_job_completion_shed_total",
                "carddemo_batch_staging_seconds",
                "carddemo_batch_generation_seconds",
                "carddemo_batch_job_publication_seconds",
                "carddemo_batch_job_terminal_total",
                "carddemo_batch_joblaunch_request_seconds",
                "carddemo_batch_jobstatus_request_seconds",
                "carddemo_online_reportrequest_turn_seconds",
                "carddemo_batch_job_observed_active_seconds",
                "carddemo_http_request_refused_total",
                "carddemo_management_authentication_total",
                "carddemo_tracing_export_total");

        @Test
        @DisplayName("every required boundary family is read by at least one panel, which is the direction "
                + "the contract used to leave open")
        void everyRequiredFamilyIsCharted() {
            final Set<String> queried = queriedMetricNames();

            for (final String family : REQUIRED_FAMILIES) {
                assertThat(queried)
                        .as("%s is a boundary this module measures; no panel reads it, so the measurement "
                                + "exists and nobody can see it", family)
                        .anySatisfy(name -> assertThat(name).startsWith(family));
            }
        }

        @Test
        @DisplayName("every required family is also a name the exporter publishes, so the inventory cannot "
                + "be satisfied by charting something that does not exist")
        void everyRequiredFamilyIsPublished() {
            final Set<String> published = publishedMetricNames();

            for (final String family : REQUIRED_FAMILIES) {
                assertThat(published)
                        .as("the inventory demands a panel for %s, so the exporter must publish it; a "
                                + "required family that does not exist would make the requirement "
                                + "unsatisfiable rather than met", family)
                        .anySatisfy(name -> assertThat(name).startsWith(family));
            }
        }

        @Test
        @DisplayName("each required family is read with an outcome dimension, because a boundary counted "
                + "without its outcome answers how much happened and not how much worked")
        void everyRequiredFamilyIsSplitByOutcome() {
            // The dimension differs by instrument and that difference is the point: an observation-derived
            // timer carries Micrometer's own 'error' label, a hand-registered timer carries the 'outcome'
            // tag its call site declared, and a refusal counter carries 'reason'. What is asserted is that
            // SOME outcome dimension is grouped by, never that every panel uses the same word for it.
            final Map<String, String> dimensionOf = Map.ofEntries(
                    Map.entry("carddemo_job_submission_publish_seconds", "error"),
                    Map.entry("carddemo_job_completion_publish_seconds", "error"),
                    Map.entry("carddemo_job_completion_shed_total", "reason"),
                    Map.entry("carddemo_batch_staging_seconds", "error"),
                    Map.entry("carddemo_batch_generation_seconds", "error"),
                    Map.entry("carddemo_batch_job_publication_seconds", "error"),
                    Map.entry("carddemo_batch_job_terminal_total", "publication"),
                    Map.entry("carddemo_batch_joblaunch_request_seconds", "outcome"),
                    Map.entry("carddemo_batch_jobstatus_request_seconds", "outcome"),
                    Map.entry("carddemo_online_reportrequest_turn_seconds", "outcome"),
                    Map.entry("carddemo_batch_job_observed_active_seconds", "spring_batch_job_status"),
                    Map.entry("carddemo_http_request_refused_total", "reason"),
                    Map.entry("carddemo_management_authentication_total", "outcome"),
                    Map.entry("carddemo_tracing_export_total", "outcome"));

            for (final Map.Entry<String, String> required : dimensionOf.entrySet()) {
                assertThat(expressionsReading(required.getKey()))
                        .as("%s must be read somewhere that groups by %s",
                                required.getKey(), required.getValue())
                        .anySatisfy(expression -> assertThat(expression)
                                .contains(required.getValue()));
            }
        }

        @Test
        @DisplayName("every boundary family is charted with a latency or a rate, so a panel says how much "
                + "as well as whether")
        void everyRequiredFamilyIsChartedAsARateOrALatency() {
            for (final String family : REQUIRED_FAMILIES) {
                assertThat(expressionsReading(family))
                        .as("%s is charted, but as a bare instantaneous value; a counter total or a timer "
                                + "sum reads as a monotonic line nobody can act on", family)
                        .anySatisfy(expression -> assertThat(expression)
                                .containsAnyOf("rate(", "increase(", "_max", "_sum"));
            }
        }

        /**
         * Every panel expression that reads one metric family.
         *
         * @param  family the exposition base name
         * @return the expressions naming it, which may be empty
         */
        private static List<String> expressionsReading(final String family) {
            final List<String> expressions = new ArrayList<>();
            for (final JsonNode panel : dashboard.path("panels")) {
                for (final JsonNode target : panel.path("targets")) {
                    final String expression = target.path("expr").asText();
                    if (expression.contains(family)) {
                        expressions.add(expression);
                    }
                }
            }
            return expressions;
        }
    }

    @Nested
    @DisplayName("the boundary row's own honesty")
    class TheBoundaryRowIsHonestAboutItself {

        @Test
        @DisplayName("the row says its panels are expected to be empty until their path has run, because "
                + "Micrometer creates a meter on first use")
        void theRowSaysItsPanelsStartEmpty() {
            final JsonNode row = panel(34);

            assertThat(row.path("type").asText()).isEqualTo("row");
            assertThat(row.path("description").asText())
                    .contains("EXPECTED")
                    .contains("empty until");
        }

        @Test
        @DisplayName("the row explains where the outcome dimension comes from, so a reader is not left to "
                + "guess why some panels group by an error label and others by an outcome tag")
        void theRowExplainsTheOutcomeDimension() {
            assertThat(panel(34).path("description").asText())
                    .contains("error")
                    .contains("none");
        }
    }
}
