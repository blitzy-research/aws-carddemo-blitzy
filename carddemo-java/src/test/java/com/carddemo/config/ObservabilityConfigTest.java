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

import java.util.Map;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

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
                registry.timer("test.tagged.timer").record(java.time.Duration.ZERO);
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

            assertThat(org.slf4j.MDC.getCopyOfContextMap())
                    .as("the bridge is symmetric: whatever it put in for the span it took back out")
                    .satisfiesAnyOf(
                            residue -> assertThat(residue).isNull(),
                            residue -> assertThat(residue).isEmpty());

            org.slf4j.MDC.clear();
            assertThat(org.slf4j.MDC.getCopyOfContextMap())
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
            org.slf4j.MDC.clear();
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
