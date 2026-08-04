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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAspectsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.Lifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link ObservabilityConfig} publishes, what it deliberately does not, and the one behaviour that is
 * both the easiest to break and the most expensive to lose: that a missing trace collector fails nothing.
 *
 * <h2>Why an unreachable collector is asserted rather than assumed</h2>
 *
 * <p>The failure mode this class exists to close is silent in every other kind of check. A span exporter
 * that opens its transport while it is being constructed compiles, passes review and passes any test that
 * happens to run beside a collector &mdash; and then fails start-up on the one machine that has none, which
 * is every machine that runs the suite. The configuration under test avoids it by declaring no exporter
 * bean at all and leaving export to the framework's own condition, and a claim of that shape is only worth
 * anything if something actually starts a context with the collector's address pointed somewhere nothing is
 * listening. That is what the last group below does.
 *
 * <h2>Why one group starts a real application and the others do not</h2>
 *
 * <p>Three of the four groups use the narrow context runner, which is the right instrument for asserting
 * what a configuration class contributes: it registers only the auto-configurations named, so a bean that
 * appears could only have come from the class under test. The correlation assertion cannot use it. Placing
 * the trace and span identifiers into the diagnostic context depends on a listener the framework registers
 * through its own service loading, and the narrow runner deliberately does not perform that step &mdash; so
 * a diagnostic-context assertion made through the runner would fail for a reason that has nothing to do
 * with this module. That group therefore starts an application the way a deployment starts one, with the
 * web tier switched off because none of its claims are about HTTP.
 *
 * <h2>Nothing here asserts a figure, and that is the rule rather than an omission</h2>
 *
 * <p><strong>Every assertion below is about presence and shape. Not one is about a number.</strong> The
 * performance gate this module answers <em>establishes</em> the first baseline for the migrated
 * implementation; it does not compare against an earlier one, because no earlier one exists. No numeric
 * latency, throughput, availability, capacity, memory, pool, thread, time-out, retry or backoff target is
 * documented anywhere in the legacy estate &mdash; not in the programs, not in the job streams, not in the
 * resource definitions &mdash; so any figure a test asserted here would be a figure this suite invented and
 * then congratulated itself for meeting. The measured values are recorded in
 * {@code docs/gate-evidence.md} by the run that measures them, and they are recorded rather than asserted.
 *
 * <p>The prohibition has a precise edge, and both sides of it are exercised below. A percentile
 * <em>histogram</em> is instrumentation: it changes what is published and claims nothing about what the
 * published values ought to be, so it is permitted and the shipped configuration switches it on. A
 * distribution <em>bound</em> or a service-level target is the opposite: a bucket boundary is an
 * expectation, and an expectation is a target the estate never set. So this class asserts that the
 * histogram is switched on and, in the same breath, that no bound of any kind accompanies it &mdash; once
 * from the shipped document, by requiring every declared distribution setting to belong to the histogram
 * family, and once behaviourally, from a timer registered in a registry the customizer has just been
 * applied to.
 *
 * <h2>The diagnostic categories are asserted here and nowhere else</h2>
 *
 * <p>This class is the sole owner of the {@code logback-spring.xml} category assertions in this package.
 * Its siblings own neighbouring concerns and deliberately not this one: one asserts that the machine-facing
 * encoder exports named diagnostic-context keys through an allow list, another asserts the confidentiality
 * of the production appender, and neither names a single logger. The four application categories, the base
 * package, the batch and persistence categories, and above all the migration category are therefore pinned
 * by exact string here, because a category silently renamed or silently quietened produces a green build
 * and an unreadable run.
 *
 * <p>The migration category carries the strongest of those claims. Local validation reads the application's
 * own log to confirm that every delivered migration was applied before anything else about a run is
 * believed, which makes that output gate evidence rather than chatter, so the assertion below refuses not
 * only the obvious silencing but every level above informational as well.
 *
 * <p>Those assertions read the delivered resource as text. They deliberately do not touch the live logging
 * context and do not assert an effective level at run time: an effective level depends on which profile is
 * active and on the order in which the shipped documents were merged, so a run-time assertion would pass or
 * fail on which class in the suite happened to refresh a context first. Reading what the file declares is
 * deterministic, and what the file declares is the contract.
 *
 * <h2>Why the management surface is read from the shipped documents</h2>
 *
 * <p>Three consumers outside this module resolve the management paths literally: the container image's
 * health check, the readiness gate every Compose service waits on, and the scrape configuration at
 * {@code config/prometheus/prometheus.yml}. None of them is a Java caller, so none of them breaks the build
 * when a path moves or an endpoint is withdrawn &mdash; the orchestration simply stops working while the
 * suite stays green. The management assertions below therefore read the shipped documents directly. What is
 * claimed here is the exposure configuration only; whether a published path answers an unauthenticated
 * caller belongs to the security configuration and is asserted with it.
 *
 * <p>Provenance: legacy estate checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced
 * here.
 *
 * @since 1.0.0
 */
@DisplayName("Observability wiring: what it adds, what it refuses to add, and what it survives")
final class ObservabilityConfigTest {

    /**
     * The tag key the shared configuration document also pins, asserted here as a literal so that renaming
     * it in one place and not the other cannot pass.
     */
    private static final String APPLICATION_TAG_KEY = "application";

    /** The configured application name of this module, as the shared configuration document declares it. */
    private static final String APPLICATION_NAME = "carddemo";

    /**
     * An address on the discard port, chosen because nothing can be listening on it.
     *
     * <p>This is the whole point of the last group: it is not a tuning figure and not an endpoint anyone is
     * expected to reach, it is a deliberate dead end that proves the exporter's unreachability is
     * tolerated.</p>
     */
    private static final String UNREACHABLE_COLLECTOR = "http://127.0.0.1:9/v1/traces";

    /** The two identifiers the delivered log encoder exports from the diagnostic context. */
    private static final String TRACE_ID_KEY = "traceId";

    /** The span half of that pair. */
    private static final String SPAN_ID_KEY = "spanId";

    /** The shared configuration document every profile inherits. */
    private static final String SHARED_DOCUMENT = "application.yml";

    /** The production overlay, which restates the management surface rather than inheriting it. */
    private static final String PRODUCTION_DOCUMENT = "application-prod.yml";

    /**
     * The suite overlay, resolved from the class path exactly as a suite run resolves it.
     *
     * <p>Two documents share this name deliberately &mdash; one under the test sources and one under the
     * main sources &mdash; and the test output directory precedes the main one, so a {@code classpath:}
     * read answers with the copy a suite run actually reads. That is the copy whose export posture keeps
     * this build free of collector noise, so it is the copy asserted here.</p>
     */
    private static final String SUITE_DOCUMENT = "application-test.yml";

    /** The delivered logging configuration, read as a resource rather than through the logging context. */
    private static final String LOGGING_DOCUMENT = "logback-spring.xml";

    /** The name a logging configuration must <em>not</em> have, because profile blocks would be ignored. */
    private static final String PROFILE_BLIND_LOGGING_DOCUMENT = "logback.xml";

    /** The published management endpoint list. */
    private static final String KEY_EXPOSURE = "management.endpoints.web.exposure.include";

    /** The management path prefix, which three consumers outside this module resolve literally. */
    private static final String KEY_BASE_PATH = "management.endpoints.web.base-path";

    /** The prefix that covers every management endpoint setting, used to prove a document restates none. */
    private static final String KEY_ENDPOINTS_PREFIX = "management.endpoints.";

    /** The framework generation's current name for the metric exposition switch. */
    private static final String KEY_EXPOSITION_ENABLED = "management.prometheus.metrics.export.enabled";

    /** The name that switch used to carry, kept only so its absence can be asserted. */
    private static final String KEY_WITHDRAWN_EXPOSITION_PREFIX = "management.metrics.export.";

    /** Every distribution setting sits beneath this prefix. */
    private static final String KEY_DISTRIBUTION_PREFIX = "management.metrics.distribution.";

    /** The only family of distribution setting this module permits: instrumentation, not expectation. */
    private static final String KEY_HISTOGRAM_PREFIX = "management.metrics.distribution.percentiles-histogram";

    /** Whether the tracing instrumentation runs, which is independent of whether spans leave the process. */
    private static final String KEY_TRACING_ENABLED = "management.tracing.enabled";

    /** The recording fraction. A sampling decision, and emphatically not a target of any kind. */
    private static final String KEY_SAMPLING_PROBABILITY = "management.tracing.sampling.probability";

    /** The switch that decides whether an exporter bean is created at all. */
    private static final String KEY_EXPORT_ENABLED = "management.otlp.tracing.export.enabled";

    /** The collector address, which is supplied per environment and never written into Java. */
    private static final String KEY_COLLECTOR_ENDPOINT = "management.otlp.tracing.endpoint";

    /** The environment variable every profile resolves the collector address from. */
    private static final String COLLECTOR_ENDPOINT_VARIABLE = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT";

    /** The management path prefix the module ships, and the only one its consumers know. */
    private static final String DEFAULT_BASE_PATH = "/actuator";

    /**
     * The auto-configured timer covering the request tier that stands in for the screen transactions.
     *
     * <p>Named here only so that the shipped document can be asked whether it publishes a histogram for it.
     * The meter itself appears once a request has been served, which belongs to the integration tier.</p>
     */
    private static final String REQUEST_TIMER_NAME = "http.server.requests";

    /**
     * The prefix every bean the container itself registers carries.
     *
     * <p>Used to tell what the wiring under test contributed from what the container brought with it. The
     * framework's own event-listener processor, for one, participates in singleton initialisation, so a
     * naked type lookup would report a start-up participant that has nothing to do with this module.</p>
     */
    private static final String FRAMEWORK_BEAN_PREFIX = "org.springframework.";

    /** The registry the isolated slice supplies, named so its single presence can be asserted exactly. */
    private static final String HARNESS_METER_REGISTRY_BEAN = "meterRegistry";

    /** The observation registry the isolated slice supplies, likewise. */
    private static final String HARNESS_OBSERVATION_REGISTRY_BEAN = "observationRegistry";

    /** The health probe: the container health check and the readiness gate of every Compose service. */
    private static final String HEALTH_ENDPOINT = "health";

    /** The metric exposition: the scrape target named in {@code config/prometheus/prometheus.yml}. */
    private static final String EXPOSITION_ENDPOINT = "prometheus";

    /** The metric browsing surface the performance baseline is read from. */
    private static final String METRICS_ENDPOINT = "metrics";

    /** The build identification surface. */
    private static final String INFO_ENDPOINT = "info";

    /**
     * The endpoints that describe or control a running deployment, none of which production may publish.
     *
     * <p>Each would hand an authorised-but-curious caller something qualitatively different from
     * diagnostics: the resolved environment and the bound configuration include every property this module
     * reads, the bean inventory is a map of the implementation, and a memory or thread capture is a snapshot
     * of live data.</p>
     */
    private static final List<String> INTROSPECTION_ENDPOINTS =
            List.of("env", "beans", "configprops", "heapdump", "threaddump");

    /**
     * The categories the delivered logging configuration must name, spelled exactly as it must spell them.
     *
     * <p>The four application categories are the load-bearing ones. Each stands in for a tier of the
     * migrated estate whose only diagnostic channel was the console: the batch tier and its step tier, the
     * service tier that carries the business outcomes, and the request tier that stands in for the screen
     * transactions. The remaining three are the framework categories whose output an operator reads
     * alongside them.</p>
     */
    private static final List<String> PINNED_CATEGORIES = List.of(
            "com.carddemo",
            "com.carddemo.batch",
            "com.carddemo.batch.step",
            "com.carddemo.service",
            "com.carddemo.api",
            "org.springframework.batch",
            "org.hibernate.SQL");

    /**
     * The migration category, whose visibility is gate evidence rather than a preference.
     *
     * <p>Local validation reads the application's own log to confirm that every delivered migration was
     * applied and that the schema reached its highest delivered version. Silencing this category would
     * remove that evidence and would also hide a start-up that stopped short of the seeds.</p>
     */
    private static final String MIGRATION_CATEGORY = "org.flywaydb";

    /** The level the migration category must carry: informational, so each migration announces itself. */
    private static final String INFORMATIONAL_LEVEL = "INFO";

    /** Every level that would hide a migration announcement, and therefore every level refused. */
    private static final List<String> SILENCING_LEVELS = List.of("OFF", "WARN", "ERROR", "TRACE_OFF");

    /** The human-facing appender, selected by the two developer profiles. */
    private static final String READABLE_APPENDER = "CONSOLE_READABLE";

    /** The machine-facing appender, selected by production and by any profile the document does not name. */
    private static final String JSON_APPENDER = "CONSOLE_JSON";

    /** Both appenders write to the process's own output; nothing writes to a file. */
    private static final String CONSOLE_APPENDER_CLASS = "ch.qos.logback.core.ConsoleAppender";

    /** The structured encoder the machine-facing appender carries. */
    private static final String JSON_ENCODER_CLASS = "net.logstash.logback.encoder.LogstashEncoder";

    /** Matches a pinned category declaration and captures its name and its level. */
    private static final Pattern LOGGER_DECLARATION =
            Pattern.compile("<logger\\s+name=\"([^\"]+)\"\\s+level=\"([^\"]+)\"\\s*/>");

    /** Matches an XML comment and captures its body, so the body can be checked for an illegal sequence. */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL);

    /** The sequence a well-formed XML comment body may not contain. */
    private static final String ILLEGAL_COMMENT_SEQUENCE = "--";

    /** Matches an appender reference, so a profile block's selection can be read back. */
    private static final Pattern APPENDER_REFERENCE =
            Pattern.compile("<appender-ref\\s+ref=\"([^\"]+)\"\\s*/>");

    /**
     * Reads every property a delivered configuration document declares, flattened to its resolvable key.
     *
     * <p>Read from the class path rather than from the source tree, because the class path is where a
     * deployment reads it and because the suite overlay is deliberately shadowed by a second copy of the
     * same name. The values are returned exactly as declared, so an unresolved placeholder arrives here as
     * placeholder text &mdash; which is the point of the assertion that the collector address is never a
     * literal.</p>
     *
     * @param resourceName the document to read, relative to the class-path root
     * @return every declared key and its declared value, in declaration order
     */
    private static Map<String, Object> declaredProperties(final String resourceName) {
        final Map<String, Object> declared = new LinkedHashMap<>();
        try {
            final List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                    .load(resourceName, new ClassPathResource(resourceName));
            for (final PropertySource<?> document : documents) {
                if (document instanceof EnumerablePropertySource<?> enumerable) {
                    for (final String key : enumerable.getPropertyNames()) {
                        declared.putIfAbsent(key, enumerable.getProperty(key));
                    }
                }
            }
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(
                    resourceName + " must be readable from the class path, which is where a deployment "
                            + "reads it", unreadable);
        }
        return declared;
    }

    /**
     * Reads one declared value as text.
     *
     * @param resourceName the document to read
     * @param key          the key to resolve
     * @return the declared value rendered as text, or {@code null} when the document declares none
     */
    private static String declaredText(final String resourceName, final String key) {
        final Object declared = declaredProperties(resourceName).get(key);
        return declared == null ? null : String.valueOf(declared);
    }

    /**
     * Reads the published endpoint list of a document as individual names.
     *
     * @param resourceName the document to read
     * @return the published endpoint names, trimmed, or an empty list when the document publishes none
     */
    private static List<String> publishedEndpoints(final String resourceName) {
        final String declared = declaredText(resourceName, KEY_EXPOSURE);
        if (declared == null || declared.isBlank()) {
            return List.of();
        }
        final List<String> published = new ArrayList<>();
        for (final String entry : declared.split(",")) {
            published.add(entry.trim());
        }
        return published;
    }

    /**
     * Reads every declared key beneath a prefix, with its declared value.
     *
     * @param resourceName the document to read
     * @param prefix       the key prefix to select
     * @return the matching keys and values, in declaration order
     */
    private static Map<String, Object> declaredBeneath(final String resourceName, final String prefix) {
        final Map<String, Object> selected = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> declared : declaredProperties(resourceName).entrySet()) {
            if (declared.getKey().startsWith(prefix)) {
                selected.put(declared.getKey(), declared.getValue());
            }
        }
        return selected;
    }

    /**
     * Reads the delivered logging configuration as text.
     *
     * <p>Text rather than a parsed logging context on purpose: what this class asserts is what the file
     * declares, and a declaration is deterministic where an effective level depends on which profile is
     * active and on the order in which the shipped documents were merged.</p>
     *
     * @return the whole document
     */
    private static String loggingDocument() {
        final ClassPathResource resource = new ClassPathResource(LOGGING_DOCUMENT);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(LOGGING_DOCUMENT + " must be readable from the class path, "
                    + "which is where the framework reads it during start-up", unreadable);
        }
    }

    /**
     * Reads back every category the delivered logging configuration pins, with the level it pins.
     *
     * @return each declared category name mapped to its declared level, in declaration order
     */
    private static Map<String, String> pinnedCategories() {
        final Map<String, String> pinned = new LinkedHashMap<>();
        final Matcher declarations = LOGGER_DECLARATION.matcher(loggingDocument());
        while (declarations.find()) {
            pinned.put(declarations.group(1), declarations.group(2));
        }
        return pinned;
    }

    /**
     * Reads back the body of every XML comment in the delivered logging configuration.
     *
     * @return each comment body, in document order
     */
    private static List<String> loggingDocumentComments() {
        final List<String> bodies = new ArrayList<>();
        final Matcher comments = XML_COMMENT.matcher(loggingDocument());
        while (comments.find()) {
            bodies.add(comments.group(1));
        }
        return bodies;
    }

    /**
     * Slices out the innermost profile block naming a given profile expression.
     *
     * <p>The delivered document groups the profiles that share an appender and nests each profile's own
     * root inside that group, so the block a caller asks for here is a leaf that contains no further
     * profile element. That is what makes a non-greedy slice to the next closing element exact.</p>
     *
     * @param profileExpression the profile expression as the document spells it
     * @return the block's content
     */
    private static String profileBlock(final String profileExpression) {
        final String opening = "<springProfile name=\"" + profileExpression + "\">";
        final String document = loggingDocument();
        final int start = document.indexOf(opening);
        assertThat(start)
                .as("%s must declare a profile block for %s, because appender selection is per profile",
                        LOGGING_DOCUMENT, profileExpression)
                .isNotNegative();
        final int end = document.indexOf("</springProfile>", start);
        assertThat(end)
                .as("the profile block for %s must be closed", profileExpression)
                .isGreaterThan(start);
        return document.substring(start + opening.length(), end);
    }

    /**
     * Reads back every appender a block selects.
     *
     * @param block the block to read
     * @return the referenced appender names, in document order
     */
    private static List<String> selectedAppenders(final String block) {
        final List<String> selected = new ArrayList<>();
        final Matcher references = APPENDER_REFERENCE.matcher(block);
        while (references.find()) {
            selected.add(references.group(1));
        }
        return selected;
    }

    /**
     * Names the beans of a given type that something other than the container itself put in the context.
     *
     * <p>The distinction is what makes a negative claim meaningful. Asking a context for every bean that
     * participates in start-up returns the container's own machinery as well, so an unfiltered assertion
     * that nothing runs at start-up could never pass and would say nothing about the wiring under test.</p>
     *
     * <p>Two exclusions, and each removes a different kind of container bean. The annotation processors the
     * container registers are ordinary definitions under a reserved name prefix, so they are excluded by
     * that prefix. The lifecycle processor, the event multicaster and the environment are not definitions at
     * all &mdash; the container registers them as singletons directly &mdash; so requiring a definition
     * removes them, and requiring one is also the sharper test of what a configuration class
     * <em>declared</em>.</p>
     *
     * @param context the context to read
     * @param type    the type to look for
     * @return the matching bean names that something other than the container itself declared
     */
    private static List<String> contributedBeanNames(final ApplicationContext context, final Class<?> type) {
        final List<String> declared = List.of(context.getBeanDefinitionNames());
        final List<String> contributed = new ArrayList<>();
        for (final String name : context.getBeanNamesForType(type)) {
            if (!name.startsWith(FRAMEWORK_BEAN_PREFIX) && declared.contains(name)) {
                contributed.add(name);
            }
        }
        return contributed;
    }

    /**
     * The metrics half of the wiring, assembled from the framework's own auto-configuration so that the
     * registry, the aspect support and the observation registry are the ones a deployment would get.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class,
                    MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class,
                    MetricsAspectsAutoConfiguration.class,
                    ObservationAutoConfiguration.class))
            .withUserConfiguration(ObservabilityConfig.class)
            .withPropertyValues("spring.application.name=" + APPLICATION_NAME);

    /**
     * The same wiring with no auto-configuration at all behind it, so that a bean found is a bean the
     * configuration under test declared.
     *
     * <p>The two registries the aspects need are supplied directly. Everything else the observability tier
     * could contain &mdash; an exporter, a sampler, a filter, a readiness contributor &mdash; is therefore
     * absent unless this class put it there, which is what turns each assertion in the group below from a
     * statement about the framework into a statement about this module.</p>
     */
    private final ApplicationContextRunner isolated = new ApplicationContextRunner()
            .withUserConfiguration(IsolatedRegistries.class, ObservabilityConfig.class)
            .withPropertyValues("spring.application.name=" + APPLICATION_NAME);

    @Nested
    @DisplayName("the beans it publishes")
    final class TheBeansItPublishes {

        @Test
        @DisplayName("publishes the customizer and both aspects over exactly one registry of each kind, so "
                + "nothing is ambiguous and nothing is duplicated")
        void publishesTheCustomizerAndBothAspects() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                        .hasSingleBean(ObservabilityConfig.class)
                        .hasSingleBean(MeterRegistryCustomizer.class)
                        .hasSingleBean(TimedAspect.class)
                        .hasSingleBean(ObservedAspect.class)
                        .hasSingleBean(MeterRegistry.class)
                        .hasSingleBean(ObservationRegistry.class);
            });
        }

        @Test
        @DisplayName("the framework's own aspect definitions stand down rather than colliding, so switching "
                + "its annotation support on later cannot produce a duplicate bean")
        void theFrameworksOwnAspectDefinitionsStandDown() {
            runner.withPropertyValues("management.observations.annotations.enabled=true").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                        .hasSingleBean(TimedAspect.class)
                        .hasSingleBean(ObservedAspect.class);
            });
        }

        @Test
        @DisplayName("no counting aspect is contributed, and none appears by default either, which is why "
                + "leaving it out costs nothing")
        void noCountingAspectIsContributed() {
            runner.run(context -> assertThat(context.getBeansOfType(CountedAspect.class)).isEmpty());
        }

        @Test
        @DisplayName("contributes no meter filter, proved with no auto-configuration present so that a "
                + "filter found could only have come from the class under test")
        void contributesNoMeterFilter() {
            new ApplicationContextRunner()
                    .withUserConfiguration(IsolatedRegistries.class, ObservabilityConfig.class)
                    .withPropertyValues("spring.application.name=" + APPLICATION_NAME)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context)
                                .hasSingleBean(TimedAspect.class)
                                .hasSingleBean(ObservedAspect.class)
                                .hasSingleBean(MeterRegistryCustomizer.class);
                        assertThat(context.getBeansOfType(MeterFilter.class))
                                .as("a filter here would drop, rename or re-bucket a series")
                                .isEmpty();
                    });
        }

        @Test
        @DisplayName("starts when no application name is configured at all, which is what makes a narrow "
                + "context possible without a configuration document")
        void startsWhenNoApplicationNameIsConfigured() {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            MetricsAutoConfiguration.class,
                            SimpleMetricsExportAutoConfiguration.class,
                            ObservationAutoConfiguration.class))
                    .withUserConfiguration(ObservabilityConfig.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        final MeterRegistry registry = context.getBean(MeterRegistry.class);
                        registry.counter("test.untagged.counter").increment();
                        assertThat(registry.find("test.untagged.counter").counter()).isNotNull();
                        assertThat(registry.find("test.untagged.counter").counter().getId()
                                .getTag(APPLICATION_TAG_KEY))
                                .as("an absent tag, never a tag whose value is empty")
                                .isNull();
                    });
        }
    }

    @Nested
    @DisplayName("the tag that makes a series attributable")
    final class TheTagThatMakesASeriesAttributable {

        @Test
        @DisplayName("reaches every meter of a registry the customizer is applied to")
        void reachesEveryMeterOfTheRegistry() {
            runner.run(context -> {
                final MeterRegistry registry = context.getBean(MeterRegistry.class);
                registry.counter("test.tagged.counter").increment();
                registry.timer("test.tagged.timer").record(Duration.ZERO);
                assertThat(registry.getMeters())
                        .isNotEmpty()
                        .allSatisfy(meter -> assertThat(meter.getId().getTag(APPLICATION_TAG_KEY))
                                .isEqualTo(APPLICATION_NAME));
            });
        }

        @Test
        @DisplayName("is applied once even when the configuration document pins the same common tag, so a "
                + "series is not forked between two spellings of one fact")
        void isAppliedOnceAlongsideThePropertyDrivenTag() {
            runner.withPropertyValues(
                    "management.metrics.tags." + APPLICATION_TAG_KEY + "=" + APPLICATION_NAME)
                    .run(context -> {
                        final MeterRegistry registry = context.getBean(MeterRegistry.class);
                        registry.counter("test.both.sources").increment();
                        assertThat(registry.getMeters()).hasSize(1);
                        assertThat(registry.getMeters().get(0).getId().getTags())
                                .singleElement()
                                .satisfies(tag -> {
                                    assertThat(tag.getKey()).isEqualTo(APPLICATION_TAG_KEY);
                                    assertThat(tag.getValue()).isEqualTo(APPLICATION_NAME);
                                });
                    });
        }

        @Test
        @DisplayName("attributes a registry built outside the property-driven path, which is why the "
                + "invariant is stated in code and not only in a document")
        void attributesADirectlyConstructedRegistry() {
            final MeterRegistry registry = new SimpleMeterRegistry();
            new ObservabilityConfig(APPLICATION_NAME)
                    .applicationTagMeterRegistryCustomizer()
                    .customize(registry);
            registry.counter("test.direct.counter").increment();
            assertThat(registry.find("test.direct.counter").counter().getId()
                    .getTag(APPLICATION_TAG_KEY))
                    .isEqualTo(APPLICATION_NAME);
        }

        @Test
        @DisplayName("adds nothing at all when the name is blank or absent, because an empty value claims "
                + "an attribution it does not carry")
        void addsNothingWhenTheNameIsBlankOrAbsent() {
            for (final String name : new String[] {"", " ", "\t", null}) {
                final MeterRegistry registry = new SimpleMeterRegistry();
                new ObservabilityConfig(name).applicationTagMeterRegistryCustomizer().customize(registry);
                registry.counter("test.blank.counter").increment();
                assertThat(registry.find("test.blank.counter").counter().getId().getTags())
                        .as("name %s must yield no tag", name == null ? "null" : "'" + name + "'")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("carries the name through exactly as configured, so a name with unusual spacing is not "
                + "silently rewritten into a different series")
        void carriesTheNameThroughExactlyAsConfigured() {
            final MeterRegistry registry = new SimpleMeterRegistry();
            new ObservabilityConfig(" carddemo ").applicationTagMeterRegistryCustomizer()
                    .customize(registry);
            registry.counter("test.verbatim.counter").increment();
            assertThat(registry.find("test.verbatim.counter").counter().getId()
                    .getTag(APPLICATION_TAG_KEY))
                    .isEqualTo(" carddemo ");
        }

        @Test
        @DisplayName("adds to a registry rather than rebuilding it: a series already registered keeps both "
                + "its identity and everything recorded into it")
        void leavesAnAlreadyRegisteredSeriesIntact() {
            final MeterRegistry registry = new SimpleMeterRegistry();
            final Counter established = registry.counter("test.established.counter");
            established.increment();
            established.increment();

            new ObservabilityConfig(APPLICATION_NAME).applicationTagMeterRegistryCustomizer()
                    .customize(registry);
            registry.counter("test.subsequent.counter").increment();

            final Counter survivor = registry.find("test.established.counter").counter();
            assertThat(survivor)
                    .as("a customizer that cleared, replaced or filtered the registry would lose every "
                            + "series established before the context finished building, which is exactly "
                            + "the window the auto-configured meter bindings register in")
                    .isNotNull();
            assertThat(survivor.count())
                    .as("the established series must keep what was recorded into it, not merely its name")
                    .isEqualTo(2.0D);
            assertThat(registry.getMeters())
                    .as("both series must be present: nothing was removed to make room for the tag")
                    .hasSize(2);
            assertThat(registry.find("test.subsequent.counter").counter().getId()
                    .getTag(APPLICATION_TAG_KEY))
                    .as("and a series registered after the customizer ran is attributed")
                    .isEqualTo(APPLICATION_NAME);
        }

        @Test
        @DisplayName("attributes a series without shaping its distribution, so instrumentation is added and "
                + "no expectation is smuggled in with it")
        void attributesASeriesWithoutShapingItsDistribution() {
            final MeterRegistry registry = new SimpleMeterRegistry();
            new ObservabilityConfig(APPLICATION_NAME).applicationTagMeterRegistryCustomizer()
                    .customize(registry);

            final Timer timer = registry.timer("test.unshaped.timer");
            timer.record(Duration.ZERO);
            final HistogramSnapshot shape = timer.takeSnapshot();

            assertThat(timer.getId().getTag(APPLICATION_TAG_KEY))
                    .as("the timer is attributed, which is the whole of what the customizer may do")
                    .isEqualTo(APPLICATION_NAME);
            assertThat(shape.histogramCounts())
                    .as("a bucketed series would mean this wiring had decided where the interesting "
                            + "boundaries are, and no such decision is recorded anywhere in the estate the "
                            + "module was migrated from")
                    .isEmpty();
            assertThat(shape.percentileValues())
                    .as("a computed percentile here would be a target expressed as instrumentation; the "
                            + "shipped configuration switches percentile histograms on for the timers the "
                            + "baseline reads, and does so as a document rather than in this wiring")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("instrumentation driven by an annotation")
    final class InstrumentationDrivenByAnAnnotation {

        @Test
        @DisplayName("turns an annotated method into a timer, which is also how the aspect weaving this "
                + "depends on is proved to be genuinely on the class path")
        void turnsAnAnnotatedMethodIntoATimer() {
            runner.withUserConfiguration(AnnotatedBeans.class).run(context -> {
                assertThat(context).hasNotFailed();
                context.getBean(TimedTarget.class).work();

                final MeterRegistry registry = context.getBean(MeterRegistry.class);
                final Timer timer = registry.find(TimedTarget.TIMER_NAME).timer();
                assertThat(timer)
                        .as("without the timing aspect the annotation is silently inert")
                        .isNotNull();
                assertThat(timer.count()).isEqualTo(1L);
                assertThat(timer.getId().getTag(APPLICATION_TAG_KEY)).isEqualTo(APPLICATION_NAME);
            });
        }

        @Test
        @DisplayName("records one measurement per invocation, so the timer counts calls rather than "
                + "registrations")
        void recordsOneMeasurementPerInvocation() {
            runner.withUserConfiguration(AnnotatedBeans.class).run(context -> {
                final TimedTarget target = context.getBean(TimedTarget.class);
                target.work();
                target.work();
                target.work();
                assertThat(context.getBean(MeterRegistry.class).find(TimedTarget.TIMER_NAME).timer().count())
                        .isEqualTo(3L);
            });
        }
    }

    @Nested
    @DisplayName("the management surface three other files resolve literally")
    final class TheManagementSurfaceThreeOtherFilesResolveLiterally {

        @Test
        @DisplayName("publishes all four endpoints something outside this module depends on, so narrowing "
                + "the list cannot pass review by being invisible to the build")
        void publishesAllFourEndpointsSomethingOutsideThisModuleDependsOn() {
            final List<String> published = publishedEndpoints(SHARED_DOCUMENT);

            assertThat(published)
                    .as("the container image's health check issues a request for %s%s, and every Compose "
                            + "service that waits for this one waits on that check; the collector reads "
                            + "%s%s, named in config/prometheus/prometheus.yml; the baseline is read from "
                            + "%s%s; and %s%s is what identifies the running build. None of the four has a "
                            + "Java caller, so withdrawing one breaks orchestration while leaving the "
                            + "build green",
                            DEFAULT_BASE_PATH, "/" + HEALTH_ENDPOINT,
                            DEFAULT_BASE_PATH, "/" + EXPOSITION_ENDPOINT,
                            DEFAULT_BASE_PATH, "/" + METRICS_ENDPOINT,
                            DEFAULT_BASE_PATH, "/" + INFO_ENDPOINT)
                    .contains(HEALTH_ENDPOINT, EXPOSITION_ENDPOINT, METRICS_ENDPOINT, INFO_ENDPOINT);
        }

        @Test
        @DisplayName("keeps the shipped management path, and neither overlay relocates it, because the "
                + "consumers of that path resolve it as a literal string")
        void keepsTheShippedManagementPath() {
            assertThat(declaredText(SHARED_DOCUMENT, KEY_BASE_PATH))
                    .as("the path must be absent, leaving the framework default, or state that default "
                            + "exactly. It is written out in the container image's health check, in the "
                            + "Compose readiness gate and in config/prometheus/prometheus.yml, none of "
                            + "which reads it from here, so moving it breaks all three in one edit")
                    .satisfiesAnyOf(
                            declared -> assertThat(declared).isNull(),
                            declared -> assertThat(declared).isEqualTo(DEFAULT_BASE_PATH));

            for (final String overlay : List.of(PRODUCTION_DOCUMENT, SUITE_DOCUMENT)) {
                assertThat(declaredText(overlay, KEY_BASE_PATH))
                        .as("%s must not relocate the management path: an overlay that moved it would "
                                + "break orchestration for that profile alone, which is the hardest "
                                + "version of this failure to find", overlay)
                        .satisfiesAnyOf(
                                declared -> assertThat(declared).isNull(),
                                declared -> assertThat(declared).isEqualTo(DEFAULT_BASE_PATH));
            }
        }

        @Test
        @DisplayName("production keeps the probe and the exposition reachable while publishing nothing that "
                + "describes or controls the running system")
        void productionKeepsTheProbeAndTheExpositionAndNothingThatDescribesTheSystem() {
            final List<String> published = publishedEndpoints(PRODUCTION_DOCUMENT);

            assertThat(published)
                    .as("withdrawing either of these from production would leave the deployment with no "
                            + "readiness signal or no surface for the baseline to be read from, and the "
                            + "suite would not notice")
                    .contains(HEALTH_ENDPOINT, EXPOSITION_ENDPOINT);

            for (final String withheld : INTROSPECTION_ENDPOINTS) {
                assertThat(published)
                        .as("production publishes %s. That endpoint describes or captures the running "
                                + "system rather than reporting on it, and publishing it is a disclosure "
                                + "whether or not a credential is required to read it", withheld)
                        .doesNotContain(withheld);
            }
        }

        @Test
        @DisplayName("the suite overlay restates no part of the management surface, so a suite run observes "
                + "the shipped posture rather than one written for it")
        void theSuiteOverlayRestatesNoPartOfTheManagementSurface() {
            assertThat(declaredProperties(SUITE_DOCUMENT).keySet())
                    .as("a suite that declared its own management surface could widen it without the "
                            + "shipped documents changing, and every claim made about that surface would "
                            + "then be a claim about the suite. The overlay's own comment records this, "
                            + "and a sibling assertion in this package fails the build if the key comes "
                            + "back")
                    .noneMatch(key -> key.startsWith(KEY_ENDPOINTS_PREFIX));

            assertThat(publishedEndpoints(SHARED_DOCUMENT))
                    .as("so what a suite run publishes is what the shared document publishes, which is "
                            + "what makes the probe and the exposition reachable during a run")
                    .contains(HEALTH_ENDPOINT, EXPOSITION_ENDPOINT);
            assertThat(declaredText(SHARED_DOCUMENT, KEY_BASE_PATH))
                    .as("and it publishes them under the shipped path, inherited rather than restated")
                    .isEqualTo(DEFAULT_BASE_PATH);
        }

        @Test
        @DisplayName("switches the exposition on under the name this framework generation actually reads, "
                + "and carries no trace of the name it used to read")
        void switchesTheExpositionOnUnderTheCurrentName() {
            assertThat(declaredText(SHARED_DOCUMENT, KEY_EXPOSITION_ENABLED))
                    .as("%s is the key this generation binds. Publishing the endpoint without switching "
                            + "the exposition on yields a path that answers with nothing",
                            KEY_EXPOSITION_ENABLED)
                    .isEqualTo("true");

            assertThat(declaredProperties(SHARED_DOCUMENT).keySet())
                    .as("the registry-specific settings were moved out from under %s in an earlier "
                            + "generation. A key left behind there binds nothing and reads as though it "
                            + "does, which is the worst of both", KEY_WITHDRAWN_EXPOSITION_PREFIX)
                    .noneMatch(key -> key.startsWith(KEY_WITHDRAWN_EXPOSITION_PREFIX));
        }

        @Test
        @DisplayName("shapes a distribution only by publishing a percentile histogram, and declares no "
                + "boundary, target or bound alongside it")
        void shapesADistributionOnlyByPublishingAPercentileHistogram() {
            for (final String document : List.of(SHARED_DOCUMENT, PRODUCTION_DOCUMENT, SUITE_DOCUMENT)) {
                assertThat(declaredBeneath(document, KEY_DISTRIBUTION_PREFIX).keySet())
                        .as("every distribution setting in %s must belong to the percentile-histogram "
                                + "family. A histogram publishes what happened; anything else beneath "
                                + "this prefix declares what ought to happen, and no such declaration "
                                + "exists in the estate this module was migrated from, so one written "
                                + "here would be this module's own invention", document)
                        .allSatisfy(key -> assertThat(key).startsWith(KEY_HISTOGRAM_PREFIX));
            }

            final Map<String, Object> histogram =
                    declaredBeneath(SHARED_DOCUMENT, KEY_HISTOGRAM_PREFIX);
            assertThat(histogram)
                    .as("the histogram is what the baseline is read from, so it has to be switched on "
                            + "somewhere, and the shared document is where")
                    .isNotEmpty();
            assertThat(histogram.values())
                    .as("a histogram key switched off would leave the baseline reading a single mean")
                    .allSatisfy(enabled -> assertThat(String.valueOf(enabled)).isEqualTo("true"));
            assertThat(histogram.keySet())
                    .as("including the timer that covers the request tier standing in for the migrated "
                            + "screen transactions")
                    .anySatisfy(key -> assertThat(key).contains(REQUEST_TIMER_NAME));
        }
    }

    @Nested
    @DisplayName("what the wiring refuses to own, so an absent collector cannot fail anything")
    final class WhatTheWiringRefusesToOwn {

        @Test
        @DisplayName("contributes no span exporter and no sampler, which is what leaves the export decision "
                + "with the framework condition that reads the profile's own switch")
        void contributesNoSpanExporterAndNoSampler() {
            isolated.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(contributedBeanNames(context, SpanExporter.class))
                        .as("an exporter declared here, even behind a condition of its own, would move "
                                + "the export decision out of the framework's condition evaluation and "
                                + "into this class, where the next profile to switch export off would go "
                                + "unhonoured and a run with no collector would emit transport noise")
                        .isEmpty();
                assertThat(contributedBeanNames(context, Sampler.class))
                        .as("sampling is settled per profile as a property; a sampler declared here would "
                                + "take that decision away from the document that states it")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("contributes neither registry, so the auto-configured registry that backs the "
                + "exposition endpoint is composed with rather than replaced")
        void contributesNeitherRegistry() {
            isolated.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(contributedBeanNames(context, MeterRegistry.class))
                        .as("a second registry would shadow the one the exposition endpoint reads, and "
                                + "the endpoint would answer with a registry nothing had been recorded "
                                + "into")
                        .containsExactly(HARNESS_METER_REGISTRY_BEAN);
                assertThat(contributedBeanNames(context, ObservationRegistry.class))
                        .as("likewise for observations: the aspect consumes the auto-configured registry "
                                + "and never supplies one")
                        .containsExactly(HARNESS_OBSERVATION_REGISTRY_BEAN);
                assertThat(context.getBeansOfType(MeterFilter.class))
                        .as("and no filter, because a filter is how a bound or a rename would arrive")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("contributes no readiness contributor and nothing that runs at start-up, so a "
                + "deployment whose collector is down has lost its traces and not its service")
        void contributesNoReadinessContributorAndNothingThatRunsAtStartUp() {
            isolated.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(contributedBeanNames(context, HealthIndicator.class))
                        .as("a readiness contributor that reached the collector would turn an unreachable "
                                + "collector into an unhealthy deployment, and the container health check "
                                + "reads the aggregate status, so the container would be restarted for "
                                + "having lost its traces")
                        .isEmpty();
                assertThat(contributedBeanNames(context, InitializingBean.class))
                        .as("nothing may run work of its own once a bean is built")
                        .isEmpty();
                assertThat(contributedBeanNames(context, SmartInitializingSingleton.class))
                        .as("nor once every singleton is built, which is the other hook that would open a "
                                + "transport while the context is still being built")
                        .isEmpty();
                assertThat(contributedBeanNames(context, Lifecycle.class))
                        .as("nor on a lifecycle callback")
                        .isEmpty();
                assertThat(contributedBeanNames(context, ApplicationListener.class))
                        .as("nor on a refresh event")
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("leaves the recording fraction to the document that states it, and reports back "
                + "whatever that document said")
        void leavesTheRecordingFractionToTheDocumentThatStatesIt() {
            final String declared = declaredText(SHARED_DOCUMENT, KEY_SAMPLING_PROBABILITY);

            assertThat(declared)
                    .as("the shared document records every trace, which is what makes a diagnostic "
                            + "session useful on a developer's own machine. This is a recording decision "
                            + "and nothing else: it governs how many traces are kept, it states no "
                            + "expectation about the work being traced, and the production overlay says "
                            + "so in as many words where it lowers the fraction")
                    .isEqualTo("1.0");
            assertThat(declaredText(SHARED_DOCUMENT, KEY_TRACING_ENABLED))
                    .as("and the instrumentation itself is on, which is separate from whether spans leave "
                            + "the process")
                    .isEqualTo("true");

            isolated.withPropertyValues(KEY_SAMPLING_PROBABILITY + "=" + declared).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty(KEY_SAMPLING_PROBABILITY))
                        .as("the wiring reads this value nowhere and rewrites it nowhere, so what the "
                                + "environment reports back is exactly what the document supplied")
                        .isEqualTo(declared);
            });
        }

        @Test
        @DisplayName("takes the collector address from the environment in every profile, so no address is "
                + "correct in one environment and wrong in every other")
        void takesTheCollectorAddressFromTheEnvironmentInEveryProfile() {
            for (final String document : List.of(SHARED_DOCUMENT, PRODUCTION_DOCUMENT, SUITE_DOCUMENT)) {
                final String declared = declaredText(document, KEY_COLLECTOR_ENDPOINT);

                assertThat(declared)
                        .as("%s must state the collector address, so that a deliberate diagnostic session "
                                + "has somewhere to send spans the moment export is switched on", document)
                        .isNotNull();
                assertThat(declared)
                        .as("%s must resolve the address from %s rather than writing one in. An address "
                                + "written into a document is right for the machine it was typed on and "
                                + "wrong everywhere else, and this class writes no address into Java "
                                + "either", document, COLLECTOR_ENDPOINT_VARIABLE)
                        .startsWith("${")
                        .contains(COLLECTOR_ENDPOINT_VARIABLE);
            }
        }

        @Test
        @DisplayName("the suite overlay keeps the instrumentation running and stops only the export, which "
                + "is what keeps a run with no collector quiet")
        void theSuiteOverlayKeepsTheInstrumentationRunningAndStopsOnlyTheExport() {
            assertThat(declaredText(SUITE_DOCUMENT, KEY_TRACING_ENABLED))
                    .as("switching the instrumentation off instead would empty the correlation fields of "
                            + "every line the suite writes, and would leave the packaged overlay "
                            + "exercising a posture no run ever sees")
                    .isEqualTo("true");
            assertThat(declaredText(SUITE_DOCUMENT, KEY_EXPORT_ENABLED))
                    .as("%s is the export control, and it is read before the instrumentation switch when "
                            + "the framework decides whether to create an exporter at all. With it off no "
                            + "exporter exists, nothing opens a transport, and a run with no collector "
                            + "produces no transport diagnostics. Were export live instead, every context "
                            + "the suite refreshes would report a refused connection, and this build "
                            + "treats a warning as a failure", KEY_EXPORT_ENABLED)
                    .isEqualTo("false");
        }
    }

    @Nested
    @DisplayName("a trace collector that is absent or unreachable")
    final class ATraceCollectorThatIsAbsentOrUnreachable {

        @Test
        @DisplayName("does not fail start-up when export is switched off, which is the posture the test "
                + "profile ships")
        void doesNotFailStartUpWhenExportIsSwitchedOff() {
            try (ConfigurableApplicationContext context = start(
                    "management.tracing.enabled=true",
                    "management.otlp.tracing.export.enabled=false")) {
                assertThat(context.isRunning()).isTrue();
                assertThat(context.getBeansOfType(TimedAspect.class)).hasSize(1);
                assertThat(context.getBeansOfType(ObservedAspect.class)).hasSize(1);
            }
        }

        @Test
        @DisplayName("does not fail start-up when export is switched on and nothing is listening, because "
                + "no transport is opened while the context is being built")
        void doesNotFailStartUpWhenNothingIsListening() {
            try (ConfigurableApplicationContext context = start(
                    "management.tracing.enabled=true",
                    "management.otlp.tracing.endpoint=" + UNREACHABLE_COLLECTOR)) {
                assertThat(context.isRunning()).isTrue();
                assertThat(context.getBean(Tracer.class)).isNotNull();
            }
        }

        @Test
        @DisplayName("still places both correlation identifiers into the diagnostic context, so a log line "
                + "written with no collector reachable remains joinable to its trace")
        void stillPlacesBothCorrelationIdentifiersIntoTheDiagnosticContext() {
            try (ConfigurableApplicationContext context = start(
                    "management.tracing.enabled=true",
                    "management.otlp.tracing.endpoint=" + UNREACHABLE_COLLECTOR)) {

                final Map<String, String> diagnosticContext =
                        captureDiagnosticContextInsideASpan(context.getBean(Tracer.class));

                assertThat(diagnosticContext).containsKeys(TRACE_ID_KEY, SPAN_ID_KEY);
                assertThat(diagnosticContext.get(TRACE_ID_KEY)).isNotBlank();
                assertThat(diagnosticContext.get(SPAN_ID_KEY)).isNotBlank();
            }
        }

        @Test
        @DisplayName("leaves no value behind once the span is closed, and the adapter returns to its "
                + "pristine state on request, which is the state a sibling assertion in this package reads")
        void leavesNoValueBehindOnceTheSpanIsClosed() {
            try (ConfigurableApplicationContext context = start(
                    "management.tracing.enabled=true",
                    "management.otlp.tracing.export.enabled=false")) {
                captureDiagnosticContextInsideASpan(context.getBean(Tracer.class));
            }

            assertThat(MDC.getCopyOfContextMap())
                    .as("the bridge is symmetric: whatever it put in for the span it took back out")
                    .satisfiesAnyOf(
                            residue -> assertThat(residue).isNull(),
                            residue -> assertThat(residue).isEmpty());

            MDC.clear();
            assertThat(MDC.getCopyOfContextMap())
                    .as("clearing restores the untouched state, which is what "
                            + "LoggingConfigurationTest reads when it asserts that no production class "
                            + "writes to the diagnostic context - an emptied map is not an untouched one, "
                            + "so this class hands the state back rather than leaving it initialised")
                    .isNull();
        }

        /**
         * Hands the diagnostic context back untouched after every span-based test in this group.
         *
         * <p>Opening a span makes the tracing bridge write the two correlation identifiers, and closing it
         * makes the bridge take them out again - but the adapter it wrote through stays initialised, so what
         * a later reader sees is an empty map rather than nothing at all. One assertion in this package
         * distinguishes those two states deliberately, and the unit tier runs in a single virtual machine,
         * so leaving the adapter initialised would make that assertion depend on which class ran first.
         * Clearing here removes the ordering dependency in both directions.</p>
         */
        @AfterEach
        void handTheDiagnosticContextBackUntouched() {
            MDC.clear();
        }

        /**
         * Opens a span, logs inside it and returns the diagnostic context the log event carried.
         *
         * <p>Read from the event rather than from the current thread, because what matters is what an
         * appender would have seen at the moment the line was written.</p>
         *
         * @param tracer the tracer the started context published
         * @return the diagnostic context of the single captured event
         */
        private Map<String, String> captureDiagnosticContextInsideASpan(final Tracer tracer) {
            final Logger probe = (Logger) LoggerFactory.getLogger(ObservabilityConfigTest.class);
            final ListAppender<ILoggingEvent> captured = new ListAppender<>();
            captured.start();
            probe.addAppender(captured);
            try {
                final Span span = tracer.nextSpan().name("observability-config-test").start();
                try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
                    probe.info("a line written inside a span");
                } finally {
                    span.end();
                }
            } finally {
                probe.detachAppender(captured);
                captured.stop();
            }
            assertThat(captured.list).hasSize(1);
            return captured.list.get(0).getMDCPropertyMap();
        }

        /**
         * Starts an application around the configuration under test, with the web tier switched off.
         *
         * @param properties the settings this particular claim depends on
         * @return the started context, which the caller closes
         */
        private ConfigurableApplicationContext start(final String... properties) {
            final String[] arguments = new String[properties.length + 2];
            arguments[0] = "--spring.main.banner-mode=off";
            arguments[1] = "--spring.application.name=" + APPLICATION_NAME;
            for (int index = 0; index < properties.length; index++) {
                arguments[index + 2] = "--" + properties[index];
            }
            return new SpringApplicationBuilder(TracingHarness.class)
                    .web(WebApplicationType.NONE)
                    .run(arguments);
        }
    }

    /**
     * The categories the migrated tiers report through, which this class owns alone.
     *
     * <h2>Why a category name is a contract and not a label</h2>
     *
     * <p>The estate this module was migrated from had one diagnostic channel: the console display
     * statement. The migration plan records approximately two hundred and seventeen of them across the
     * twenty-eight programs, and approximately is the honest word, because a raw token count over the
     * program sources reports more than a statement-context parse does and a folder-scoped analysis reports
     * fewer again. Nothing here asserts a count. What matters is that the channel is gone and that every
     * diagnostic it carried now arrives under a category name, so a category renamed or quietened by
     * accident does not lose a line of output loudly &mdash; it loses it silently, on a green build.
     *
     * <p>The four application categories below are the ones that carry migrated behaviour. Two stand for the
     * batch tier and its step tier, whose legacy diagnostics followed one repeated shape on every terminal
     * path: the failure was displayed, then the raw two-byte file status, and only then was the run aborted
     * &mdash; visible in the shared status-display and abort paragraphs of the posting program at
     * {@code app/cbl/CBTRN02C.cbl:L714-L727} and {@code app/cbl/CBTRN02C.cbl:L707-L711}, and reached the
     * same way by the interest program's write path at {@code app/cbl/CBACT04C.cbl:L510-L513}. That ordering
     * and that raw code are reproduced deliberately, and they arrive under those two categories. One stands
     * for the service tier that carries the business outcomes, and one for the request tier that stands in
     * for the screen transactions.
     *
     * <p>A fifth fact from the same source explains why runtime configurability is part of this contract at
     * all: the estate turned a diagnostic off by commenting the statement out and recompiling, which
     * {@code app/cbl/CBTRN02C.cbl:L704} still shows. A category whose level a profile can set is what
     * replaces that.
     *
     * <h2>Read as a declaration, deliberately</h2>
     *
     * <p>Every assertion here reads the delivered resource. None touches the live logging context and none
     * asserts an effective level, because an effective level depends on which profile is active and on the
     * order in which the shipped documents were merged &mdash; and the unit tier runs many contexts in one
     * virtual machine, so such an assertion would pass or fail on which class ran first. The declaration is
     * deterministic, and the declaration is what a reviewer is asked to keep true.
     */
    @Nested
    @DisplayName("the categories the migrated tiers report through")
    final class TheCategoriesTheMigratedTiersReportThrough {

        @Test
        @DisplayName("names every category by its exact string, and leaves the four application categories "
                + "open so that raising the tree in a profile still reaches them")
        void namesEveryCategoryByItsExactString() {
            final Map<String, String> pinned = pinnedCategories();

            assertThat(pinned.keySet())
                    .as("%s is where the diagnostic surface of the migrated estate is named. A category "
                            + "missing here is a tier whose output nothing accounts for, and a category "
                            + "misspelled here matches nothing at all while looking configured",
                            LOGGING_DOCUMENT)
                    .containsAll(PINNED_CATEGORIES);

            for (final String category : PINNED_CATEGORIES) {
                assertThat(pinned.get(category))
                        .as("%s must be declared with a level rather than left half-written", category)
                        .isNotBlank();
            }

            assertThat(pinned)
                    .as("the four application categories are declared inherited on purpose: a profile "
                            + "raises the whole application tree in one setting, and a descendant pinned "
                            + "to a fixed level here would silently defeat that for its own tier")
                    .containsEntry("com.carddemo.batch", "INHERITED")
                    .containsEntry("com.carddemo.batch.step", "INHERITED")
                    .containsEntry("com.carddemo.service", "INHERITED")
                    .containsEntry("com.carddemo.api", "INHERITED");
        }

        @Test
        @DisplayName("keeps the migration category informational, and refuses every level that would hide a "
                + "migration announcing itself")
        void keepsTheMigrationCategoryInformational() {
            final String declared = pinnedCategories().get(MIGRATION_CATEGORY);

            assertThat(declared)
                    .as("%s must be named in %s. Local validation reads the application's own log to "
                            + "confirm that every delivered migration was applied and that the schema "
                            + "reached its highest delivered version before anything else about a run is "
                            + "believed, so this output is gate evidence rather than chatter",
                            MIGRATION_CATEGORY, LOGGING_DOCUMENT)
                    .isNotNull();
            assertThat(declared)
                    .as("%s must stay at %s. At that level each migration announces itself by version and "
                            + "description, which is precisely the evidence the gate reads. Anything "
                            + "quieter removes it, and a start-up that stopped short of the seeds would "
                            + "then look like a start-up that completed", MIGRATION_CATEGORY,
                            INFORMATIONAL_LEVEL)
                    .isEqualTo(INFORMATIONAL_LEVEL);
            assertThat(SILENCING_LEVELS)
                    .as("stated the other way round as well, so that the intent survives an edit: none of "
                            + "these levels may ever be the one %s carries", MIGRATION_CATEGORY)
                    .doesNotContain(declared);
        }

        @Test
        @DisplayName("carries the name that makes profile blocks apply, because under the other name they "
                + "would be ignored without a word of complaint")
        void carriesTheNameThatMakesProfileBlocksApply() {
            assertThat(new ClassPathResource(LOGGING_DOCUMENT).exists())
                    .as("%s must be resolvable from the class path, which is where the framework reads it",
                            LOGGING_DOCUMENT)
                    .isTrue();
            assertThat(new ClassPathResource(PROFILE_BLIND_LOGGING_DOCUMENT).exists())
                    .as("%s must not exist beside it. Under that name the logging framework initialises "
                            + "before the environment has been prepared, so every profile block would be "
                            + "ignored silently and one appender layout would serve every profile",
                            PROFILE_BLIND_LOGGING_DOCUMENT)
                    .isFalse();

            final String document = loggingDocument();
            assertThat(document)
                    .as("and the document must actually use the elements that name buys, or the name would "
                            + "be carrying nothing")
                    .contains("<springProfile")
                    .contains("<springProperty");
        }

        @Test
        @DisplayName("carries no comment that would stop the document parsing, which would take the whole "
                + "logging configuration with it")
        void carriesNoCommentThatWouldStopTheDocumentParsing() {
            final List<String> comments = loggingDocumentComments();

            assertThat(comments)
                    .as("the document is heavily commented on purpose, so there is something to check")
                    .isNotEmpty();
            for (final String body : comments) {
                assertThat(body)
                        .as("a comment body may not contain %s: the document would stop being well-formed "
                                + "XML, and a logging configuration that fails to parse leaves the "
                                + "application logging through whatever default it can fall back to",
                                ILLEGAL_COMMENT_SEQUENCE)
                        .doesNotContain(ILLEGAL_COMMENT_SEQUENCE);
            }
        }

        @Test
        @DisplayName("selects the human-facing appender for the two developer profiles and the machine-facing "
                + "one for the rest, one appender per profile and never both")
        void selectsOneAppenderPerProfile() {
            final String document = loggingDocument();

            assertThat(document)
                    .as("the two profiles a person reads share one group, so the readable layout is written "
                            + "once and cannot drift between two copies")
                    .contains("<springProfile name=\"local,test\">")
                    .contains("<appender name=\"" + READABLE_APPENDER + "\"");
            assertThat(document)
                    .as("and their complement shares the other, which is what makes an unnamed profile "
                            + "machine-readable rather than unconfigured")
                    .contains("<springProfile name=\"!local &amp; !test\">")
                    .contains("<appender name=\"" + JSON_APPENDER + "\"");

            assertThat(selectedAppenders(profileBlock("local")))
                    .as("a developer's own machine reads the aligned single-line layout")
                    .containsExactly(READABLE_APPENDER);
            assertThat(selectedAppenders(profileBlock("test")))
                    .as("and so does a suite run, which is what keeps a captured failure legible")
                    .containsExactly(READABLE_APPENDER);
            assertThat(selectedAppenders(profileBlock("prod")))
                    .as("production selects the machine-facing appender and nothing else. A second "
                            + "appender-ref here would emit every event twice, once in a shape the "
                            + "collector cannot parse")
                    .containsExactly(JSON_APPENDER);
            assertThat(selectedAppenders(profileBlock("!prod")))
                    .as("and any other unnamed profile is machine-readable too, so an operator never has "
                            + "to discover which profiles were named")
                    .containsExactly(JSON_APPENDER);
        }

        @Test
        @DisplayName("writes the production log as structured output on the process's own stream, and to no "
                + "file anywhere")
        void writesTheProductionLogAsStructuredOutputOnTheProcessStream() {
            final String document = loggingDocument();

            assertThat(document)
                    .as("the machine-facing appender must write to the process's own output, because the "
                            + "container log driver and the collector both read that and nothing else")
                    .contains("<appender name=\"" + JSON_APPENDER + "\" class=\"" + CONSOLE_APPENDER_CLASS
                            + "\">");
            assertThat(document)
                    .as("and it must carry the structured encoder, or the collector would receive a "
                            + "human-readable line it has to guess at")
                    .contains("class=\"" + JSON_ENCODER_CLASS + "\"");
            assertThat(document)
                    .as("no appender may write to a file. The artefact runs as a container, so a file "
                            + "appender writes into an ephemeral layer that nothing collects, and the "
                            + "diagnostics would be lost exactly when a failure needed explaining")
                    .doesNotContain("FileAppender");
            assertThat(document)
                    .as("and nothing may buffer events off the calling thread: every queueing policy "
                            + "either blocks or discards, and a discarded event is one an output "
                            + "comparison is read to explain")
                    .doesNotContain("AsyncAppender");
            assertThat(document)
                    .as("the human-facing appender writes to the same stream, so the destination is the "
                            + "same under every profile and only the shape differs")
                    .contains("<appender name=\"" + READABLE_APPENDER + "\" class=\"" + CONSOLE_APPENDER_CLASS
                            + "\">");
        }
    }

    /**
     * The tracing half of the wiring, assembled from the framework's own auto-configuration so that the
     * bridge, the exporter's condition and the diagnostic-context listener are the ones a deployment gets.
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        AopAutoConfiguration.class,
        MetricsAutoConfiguration.class,
        CompositeMeterRegistryAutoConfiguration.class,
        SimpleMetricsExportAutoConfiguration.class,
        MetricsAspectsAutoConfiguration.class,
        ObservationAutoConfiguration.class,
        OpenTelemetryAutoConfiguration.class,
        OpenTelemetryTracingAutoConfiguration.class,
        MicrometerTracingAutoConfiguration.class,
        OtlpTracingAutoConfiguration.class })
    @Import(ObservabilityConfig.class)
    static class TracingHarness {
    }

    /** Supplies the two registries directly, so the aspects can be built with no auto-configuration. */
    @Configuration(proxyBeanMethods = false)
    static class IsolatedRegistries {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }

    /** Supplies a bean whose method carries the timing annotation. */
    @Configuration(proxyBeanMethods = false)
    static class AnnotatedBeans {

        @Bean
        TimedTarget timedTarget() {
            return new TimedTarget();
        }
    }

    /** A target the timing aspect can intercept, deliberately not final so that it can be proxied. */
    static class TimedTarget {

        /** The timer name the annotation declares, shared with the assertions so neither can drift. */
        static final String TIMER_NAME = "test.timed.method";

        @Timed(TIMER_NAME)
        void work() {
            // Nothing to compute: what is asserted is that the call was intercepted and measured.
        }
    }
}
