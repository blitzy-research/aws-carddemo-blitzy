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

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the observability tier: the three cross-cutting collaborators the framework's own
 * auto-configuration does not supply, and the recorded reasons this class supplies nothing else.
 *
 * <h2>What it replaces</h2>
 *
 * <p>The legacy estate has one diagnostic channel and one only: the console display statement. There are
 * approximately two hundred and seventeen of them across the twenty-eight programs, and that figure is
 * deliberately approximate, because a raw token count over {@code app/cbl} reports more than a
 * statement-context parse does. {@code app/cbl/CBTRN02C.cbl} is the shape every batch member follows: a
 * failure branch displays a fixed English phrase, hands the raw two-byte file status to a shared display
 * paragraph ({@code app/cbl/CBTRN02C.cbl:L714-L727}), and then reaches the abort paragraph
 * ({@code app/cbl/CBTRN02C.cbl:L707-L711}), which displays one further phrase and calls the
 * language-environment abort routine. Eighteen separate branches of that one member end that way.
 *
 * <p>Three properties of that channel are worth naming, because each is what a piece of this tier exists
 * to restore. It carries no severity, no timestamp and no identifier by which two lines belonging to one
 * unit of work could be joined, so nothing can be <em>correlated</em>. It has exactly one destination, the
 * job log, and no aggregation of any kind, so nothing can be <em>measured</em>. And its verbosity is fixed
 * when the program is compiled: the display at {@code app/cbl/CBTRN02C.cbl:L704} is commented out, which
 * is how the estate turned a diagnostic off &mdash; by editing the source and recompiling it.
 *
 * <p>The online half is no better instrumented, and this is worth stating rather than assuming.
 * {@code app/csd/CARDDEMO.CSD} defines eighteen transactions, eighteen programs, seventeen screen mapsets,
 * eight file entries and one queue, and declares no monitoring class and no statistics interval on any of
 * them. The single diagnostic attribute present is the region's auxiliary trace flag, carried by all
 * eighteen transaction definitions: an internal trace of the transaction manager's own calls, region-wide
 * and operator-controlled, which is not application instrumentation and yields no metric. Every file entry
 * is defined with no journal whatever, so no record of data access survives either. The queue that carries
 * a submitted job is defined to ignore its own write failures, so a failed submission leaves nothing behind
 * but whatever the program chose to display.
 *
 * <p>That channel becomes structured logging over SLF4J &mdash; whose encoder and appenders are owned by
 * {@code logback-spring.xml} and configured nowhere else &mdash; together with Actuator, Micrometer and
 * OTLP trace export. Runtime-configurable levels replace the recompile, the meter registry replaces the
 * absent aggregation, and the trace and span identifiers this class keeps flowing replace the absent
 * correlation.
 *
 * <h2>This class asserts nothing about performance, and that is a requirement</h2>
 *
 * <p><strong>The performance gate establishes a baseline; it does not test a threshold.</strong> No
 * numeric latency, throughput, availability or capacity target exists anywhere in the estate &mdash; not in
 * the COBOL, not in the job control, not in the resource definitions. There is consequently nothing to
 * compare against, and the measured figures are written up in {@code docs/gate-evidence.md} by the
 * documentation tier rather than asserted here. Decision log entry DL-073 in
 * {@code docs/decision-log.md} records exactly this, so that no later work invents a service level and
 * then tests against its own invention.
 *
 * <p>That makes a prohibition rather than a preference, and it is the first of the two ways this file is
 * easy to get wrong. <strong>No service-level objective, no expected-value bound and no latency bucket may
 * be declared here</strong>, because a bucket boundary <em>is</em> an expectation and an expectation is a
 * service level the estate never documented. Percentile histograms are a different thing and are
 * permitted, being instrumentation rather than expectation &mdash; and they are already switched on for the
 * request timer and the two batch timers by {@code application.yml}, which is the other reason this class
 * does not configure them. For the same reason the connection pool keeps its shipped defaults, no thread
 * pool is sized here, and no rate limiter, circuit breaker or retry policy appears in the module at all:
 * every one of those would encode a figure that the baseline exists to discover.
 *
 * <h2>The management contract is load-bearing, and configuration owns it</h2>
 *
 * <p>The management base path and the exposed endpoint set are settled in the shipped profiles and are
 * never altered from Java, because three independent consumers resolve them literally: the container
 * image's health check, the readiness gate the Compose stack waits on, and the scrape configuration at
 * {@code config/prometheus/prometheus.yml}. Relocating the base path would break container orchestration
 * and metric collection in one edit; withdrawing either the health endpoint or the metric exposition
 * endpoint would break one of them while leaving the build green. The production profile deliberately
 * withholds the introspection endpoints &mdash; environment, beans, configuration properties, heap and
 * thread dumps, log levels and request mappings &mdash; while keeping health and the exposition reachable
 * at the same path, and this class reopens none of them.
 *
 * <p>Nor does it produce a second meter registry. The registry is auto-configured from
 * {@code micrometer-registry-prometheus} 1.15.12, and the way to add a cross-cutting concern to it is a
 * {@link MeterRegistryCustomizer}, which that auto-configuration <em>consults</em> rather than being
 * replaced by. The dashboard at {@code config/grafana/dashboards/carddemo-overview.json} charts the
 * exposition, and it selects series by the label the collector stamps on a scrape rather than by the meter
 * tag registered below: the two identifiers are complementary, and neither substitutes for the other.
 *
 * <p><strong>What "owning" the registry and the trace export means here, since a sibling class defers to
 * this one for both.</strong> It means this is the single place the registry is customised and the export
 * posture is recorded &mdash; not that either bean is declared here. Declaring them is precisely what would
 * break them: a second registry would shadow the exposition endpoint, and an exporter declared by hand would
 * take the export decision away from the condition that reads the property a profile sets. Ownership is
 * therefore exercised by composing with the auto-configuration and by writing down, once and in one place,
 * what may not be changed.
 *
 * <h2>An absent trace collector must not fail anything</h2>
 *
 * <p>This is the second way the file is easy to get wrong, and the more damaging of the two: a span
 * exporter that opens its transport while it is being constructed turns "no collector is running" into a
 * failed start-up and a red build. No unit or integration test runs a collector, and
 * {@code application-test.yml} neutralises export for precisely that reason while leaving the tracing
 * instrumentation switched on, so identifiers still reach the diagnostic context and the correlation fields
 * of a log line still resolve.
 *
 * <p>The way this class honours that is by <strong>declaring no exporter bean at all</strong>. Export is
 * auto-configured, its condition reads the property the test profile sets, and the exporter is therefore
 * simply <em>absent</em> when that property switches it off rather than present and failing. Hand-rolling
 * the bean, even behind a property condition of its own, would move that decision out of the framework's
 * condition evaluation and into this file, where the next profile to disable export would go unhonoured.
 * There is no eager connection here of any other kind either: no post-construction hook, no socket opened
 * while the context builds, and no health indicator that makes readiness depend on the collector &mdash; a
 * deployment whose collector is down has lost its traces, not its service.
 *
 * <p>The collector's address is a property supplied per profile and never a literal in Java, for the
 * ordinary reason that a hard-coded address is wrong in every environment except the one it was typed for.
 * The sampling probability is settled in the same documents and is likewise not overridden here.
 *
 * <h2>What was considered and deliberately left out</h2>
 *
 * <p><strong>No counting aspect.</strong> The batch tier already builds its counters against the injected
 * registry directly &mdash; the reject-record and category tallies are constructed, named and tagged in the
 * components that own them &mdash; so an annotation-driven counter would have no consumer and would add no
 * capability the programmatic interface lacks. It is left out rather than registered unused, and it is the
 * one aspect of the three whose absence costs nothing to reverse: the framework carries a definition of it
 * beside the timing one, so switching on the annotation-support property those definitions share would
 * supply it without an edit here.
 *
 * <p><strong>No batch-step instrumentation from here.</strong> Step timing belongs to the tier that runs
 * the steps: {@code com.carddemo.batch.step} owns the shared step template and each processor and writer
 * times its own work against the same registry, while the job and step observations come from the
 * auto-configured observation registry. All this tier owes them is the registry, which is already a bean, so
 * no additional bean is required. Reaching into the batch packages from a configuration class would also
 * invert the layer direction: configuration wires <em>into</em> the other layers and is never depended upon
 * by them.
 *
 * <p><strong>No filter writing correlation identifiers into the diagnostic context.</strong> An earlier
 * design described exactly that, and it is superseded on three independent grounds. The delivered encoder
 * exports the diagnostic context through an allow list naming the two tracing identifiers and nothing else,
 * so a third key would be dropped silently rather than published. A delivered test asserts that no class
 * under the production tree writes to the diagnostic context at all, which is what makes the allow list a
 * guarantee rather than a hope. And the identifiers do not need to be written by application code in the
 * first place: the tracing bridge places them there for the active span, which is the mechanism that keeps
 * working when export is switched off. Application code writing to the diagnostic context is therefore not
 * merely unnecessary here, it is prohibited.
 *
 * <p><strong>No meter filter.</strong> Nothing here denies, renames or re-buckets a meter. A filter that
 * dropped or renamed a series would empty a dashboard panel and silently starve the baseline of the very
 * measurements it is being taken to establish, and a filter that re-bucketed one would smuggle in the
 * expectation the section above prohibits.
 *
 * <h2>Thread safety and lifecycle</h2>
 *
 * <p>Final, with one field: a string read from configuration when the context is built and never
 * reassigned. There is no mutable static state, nothing is cached, and none of the three beans holds
 * per-request or per-job state, so one instance of each serves every caller without synchronisation.
 *
 * <p>Provenance: legacy estate checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Traceability to the estate is by
 * citation only; no legacy source text is reproduced here.
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public final class ObservabilityConfig {

    /**
     * The tag key that names which service produced a series.
     *
     * <p>The key is fixed because {@code application.yml} already pins a common tag under this exact name;
     * the two have to agree or a series would be attributed twice under different keys. The tag
     * <em>value</em> is never written here &mdash; it is bound from configuration, so this constant is the
     * only part of the pair that is a literal.</p>
     */
    private static final String APPLICATION_TAG_KEY = "application";

    /**
     * This class's own diagnostic channel.
     *
     * <p>Named here rather than obtained on demand for the ordinary reason: the estate's console display
     * statements resolve to calls of this shape, and a logger is what makes their successor levelled,
     * timestamped, correlated and switchable at run time. The reference is {@code static final} and the
     * backend is thread-safe, so this is a constant rather than mutable static state.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(ObservabilityConfig.class);

    /**
     * The configured application name, or an empty string when the deployment configures none. Never
     * {@code null}.
     */
    private final String applicationName;

    /**
     * Binds the one setting this class reads.
     *
     * <p><strong>Why the placeholder carries an empty default.</strong> The shipped
     * {@code application.yml} always supplies the name, so the default is never taken by a running
     * deployment. It exists for the narrow contexts a test builds by registering beans directly, which do
     * not read the shipped documents at all: without a default, such a context would fail on placeholder
     * resolution and a test of this wiring would be unable to start for a reason that has nothing to do
     * with observability. The default is empty rather than a guessed name precisely because guessing one
     * would attribute every series to a service that may not be this one &mdash; an empty value is
     * recognisable as "not configured", and {@link #applicationTagMeterRegistryCustomizer()} acts on that
     * distinction.</p>
     *
     * @param applicationName the value of {@code spring.application.name}, or an empty string when the
     *                        property is not set; a {@code null} is tolerated and normalised, so a
     *                        property source that resolves the placeholder to nothing cannot produce a
     *                        null tag value further down
     */
    public ObservabilityConfig(@Value("${spring.application.name:}") final String applicationName) {
        this.applicationName = applicationName == null ? "" : applicationName;
    }

    /**
     * Tags every meter in every registry with the name of the service that produced it.
     *
     * <p>A customizer rather than a registry: the auto-configuration consults this bean when it builds the
     * registry, so the exposition, the endpoint and the auto-configured meter bindings all survive. Declaring
     * a registry here instead would replace them.</p>
     *
     * <p><strong>It is deliberately idempotent with configuration.</strong> {@code application.yml} pins a
     * common tag under the same key, bound to the same property, so on a running deployment this customizer
     * applies the value that is already there. That is the point: tags are keyed, so applying the same key
     * and the same value twice yields one tag and cannot fork a series, while stating the invariant in code
     * as well as in a document means a registry built outside the property-driven path &mdash; the simple
     * registry a test context supplies, for one &mdash; is attributed too. Applying a <em>different</em>
     * value would be the harmful case, which is why the value is bound and never typed.</p>
     *
     * <p>When no name is configured the customizer adds nothing at all, rather than a tag whose value is
     * empty. An empty value is worse than an absent tag: it claims an attribution it does not carry, and it
     * would sit in the exposition looking like a configured answer. The condition is logged as a warning
     * because it is actionable &mdash; one property restores attribution &mdash; and because a series that
     * cannot be attributed is a gap in the very evidence the baseline is collected for.</p>
     *
     * <p>The name is copied into a local before it is captured, so the returned customizer closes over the
     * value rather than over this configuration object. What it will do is then decided once, when the bean
     * is built, instead of being re-read from a field each time a registry is customised.</p>
     *
     * @return a customizer that adds the service-identifying tag to any registry the context builds, or one
     *         that adds nothing when the application name is not configured; never {@code null}
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> applicationTagMeterRegistryCustomizer() {
        final String tagValue = this.applicationName;
        if (tagValue.isBlank()) {
            LOG.warn("No application name is configured, so meters will carry no \"{}\" tag and a series "
                    + "cannot be attributed to this service; set spring.application.name to restore it",
                    APPLICATION_TAG_KEY);
            return registry -> {
                // Intentionally adds nothing: see the note above on why an empty tag value is worse than
                // an absent tag. Returning a no-op keeps the bean's presence unconditional, so the wiring
                // is the same shape in every profile and only its effect differs.
            };
        }
        LOG.debug("Tagging every meter with {}=\"{}\"", APPLICATION_TAG_KEY, tagValue);
        return registry -> registry.config().commonTags(APPLICATION_TAG_KEY, tagValue);
    }

    /**
     * Makes Micrometer's {@code io.micrometer.core.annotation.Timed} annotation effective on the methods of
     * managed beans.
     *
     * <p>Request timing does not depend on this: the auto-configured server metrics already time every
     * endpoint by URI, method and outcome, with the percentile histogram enabled in {@code application.yml},
     * and batch work is timed by the tier that runs it. What this aspect adds is the ability to time a
     * <em>named method</em> &mdash; a translated paragraph whose cost is worth attributing on its own,
     * inside a service the request timer sees only in aggregate &mdash; by annotating it, without threading a
     * registry through the call site. The timer it creates is an ordinary meter that reaches the exposition
     * by the same route as every other, and it declares no bound of any kind.</p>
     *
     * <p><strong>Why the framework does not already provide it.</strong> The framework carries a definition
     * of this aspect, but gates it on an annotation-support property whose match-if-missing behaviour is off
     * and which none of the shipped documents sets, so that definition does not apply. Leaving it at that
     * would produce the worst outcome available: an annotated method would compile, read as instrumented,
     * and silently produce no meter at all. Declaring the bean here makes the capability unconditional. It
     * does not compete with the framework's definition either, because that definition stands down when the
     * bean is already present &mdash; so switching the property on later changes nothing about this one.</p>
     *
     * <p><strong>The aspect weaving this relies on is present transitively, not declared.</strong> The
     * build file names no aspect-oriented starter; the two artefacts that matter arrive with other starters
     * &mdash; the framework's proxying support with the security starter and the weaver with the aspect
     * library the persistence starter brings &mdash; and the framework's own auto-configuration switches
     * proxying on precisely because the weaver is on the class path. That was verified against the resolved
     * dependency tree rather than assumed, and the build file is deliberately left unchanged. The
     * consequence to know: were either starter dropped, this bean would stop being applied, so a change
     * there has to be made in the knowledge that annotation-driven timing depends on it.</p>
     *
     * @param meterRegistry the auto-configured registry the created timers are registered in, injected
     *                      rather than looked up so that a test can supply a simple registry and read the
     *                      timers back; never {@code null}
     * @return the aspect that intercepts annotated methods; never {@code null}
     */
    @Bean
    public TimedAspect timedAspect(final MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }

    /**
     * Makes Micrometer's {@code io.micrometer.observation.annotation.Observed} annotation effective on the
     * methods of managed beans.
     *
     * <p>This is the counterpart of the timing aspect and not a duplicate of it. An observation produces a
     * timer <em>and</em> a span, so an annotated method both appears in the exposition and becomes a segment
     * of the trace whose identifiers the correlation fields of every log line carry. That is the one thing
     * the request timer cannot give: it measures a call, whereas a span explains where inside the call the
     * time went, and it is the mechanism by which the trace crosses the boundary between the request tier
     * and the batch tier through the tracing bridge.</p>
     *
     * <p>It is registered here for the same reason as the timing aspect, and the reason is the same one
     * exactly: the observation registry is auto-configured unconditionally, whereas the aspect that consumes
     * it is gated on the same annotation-support property that no shipped document sets, so the annotation
     * would be silently inert without this bean. The framework's own definition likewise stands down when
     * the bean already exists, so nothing here can collide with it. It costs nothing where the annotation is
     * unused, since a bean whose methods match no pointcut is never proxied. Whether the resulting spans are
     * exported is a separate matter settled by profile: the instrumentation runs either way, which is what
     * keeps the identifiers flowing into the diagnostic context when no collector is reachable.</p>
     *
     * @param observationRegistry the auto-configured registry the observations are started against,
     *                            carrying whichever handlers the active profile wires; never {@code null}
     * @return the aspect that intercepts annotated methods; never {@code null}
     */
    @Bean
    public ObservedAspect observedAspect(final ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
