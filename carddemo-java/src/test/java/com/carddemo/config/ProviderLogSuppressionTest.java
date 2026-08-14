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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.yaml.snakeyaml.Yaml;

/**
 * Specification of the four third-party categories this module silences, and of what replaces each.
 *
 * <h2>Why a library's own log is a boundary worth specifying</h2>
 *
 * <p>Every category named here writes something the module would never write itself, and in three of the
 * four cases the offending content and the useful content are the same statement, so no level separates
 * them.
 *
 * <p>The queue client's two send templates format the whole outbound message - payload <em>and</em> headers -
 * into their own text: on the way out at trace level, on success at debug, and on failure at error together
 * with the raw provider exception and the resolved endpoint. The payload of this module's one outbound queue
 * is an eighty-character job-control card, so the message <em>is</em> the submitted job.
 *
 * <p>The trace exporter's two transports compose their failure text out of the <em>collector's</em>
 * response - the HTTP status line and the response body verbatim, or the transport exception's own message.
 * The collector is a remote party this deployment authenticates but does not author, and that text would land
 * in the machine-facing appender, which is the stream that leaves the process.
 *
 * <p>The migration tool's executor announces the JDBC URL, the driver and the database type before any
 * migration runs, so the host, the port, the database name and the exact server and driver versions of the
 * estate are published. The credential is already redacted from that URL, which is why the line reads as
 * safe and is not.
 *
 * <h2>What each nest establishes</h2>
 *
 * <p>The first nest is behavioural and is the substance: the shipped level is applied to the real logging
 * backend and an event is emitted on the provider's own category at the level that provider uses, then the
 * root appender is examined. Nothing captured means nothing reaches any appender, which is the only form of
 * this assertion that cannot be satisfied by a configuration that merely looks right.
 *
 * <p>The second nest asserts what must <em>survive</em>. Silencing the migration executor would be worthless
 * if it also silenced the per-migration lines local validation reads, so the sibling category is emitted on
 * and must be captured. A suppression test that only proves silence proves half of the requirement.
 *
 * <p>The third nest reads the shipped document, because the levels are owned there and a behavioural test
 * that set its own levels would pass whatever the deployment ships.
 *
 * <p>The fourth nest covers the replacement for the exporter's silence: the bounded counter that carries
 * what was lost.
 *
 * <h2>Harness</h2>
 *
 * <p>A surefire unit test. It mutates the real {@link LoggerContext} and restores every level and appender it
 * touched, so a category left raised cannot leak into another class's captured log.
 */
@DisplayName("Third-party log suppression: what is silenced, what survives, and what replaces it")
class ProviderLogSuppressionTest {

    /** The shipped document that owns every level. */
    private static final Path SHARED_DOCUMENT = Path.of("src", "main", "resources", "application.yml");

    /** The queue client's two categories, both of which format the outbound message. */
    private static final List<String> QUEUE_CATEGORIES = List.of(
            "io.awspring.cloud.sqs.operations.AbstractMessagingTemplate",
            "io.awspring.cloud.sqs.operations.SqsTemplate");

    /** The trace exporter's two transports, both of which quote the collector. */
    private static final List<String> EXPORTER_CATEGORIES = List.of(
            "io.opentelemetry.exporter.internal.http.HttpExporter",
            "io.opentelemetry.exporter.internal.grpc.GrpcExporter");

    /** The migration category that announces the estate's topology. */
    private static final String MIGRATION_EXECUTOR_CATEGORY = "org.flywaydb.core.FlywayExecutor";

    /** The migration category local validation reads, which must survive the raise. */
    private static final String MIGRATION_COMMAND_CATEGORY =
            "org.flywaydb.core.internal.command.DbMigrate";

    /** A stand-in for the eighty-character card image that must never be logged by a library. */
    private static final String CARD_IMAGE =
            "//CARDDEMO JOB (ACCT),'REPORT',CLASS=A,MSGCLASS=X,NOTIFY=&SYSUID          ";

    /** Where events would land if a category were not silenced. */
    private ListAppender<ILoggingEvent> rootCapture;

    /** Levels this test changed, so every one can be put back. */
    private final Map<String, Level> restore = new HashMap<>();

    /** The backend, obtained once. */
    private LoggerContext loggerContext;

    @BeforeEach
    void attachRootCapture() {
        this.loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.rootCapture = new ListAppender<>();
        this.rootCapture.setContext(this.loggerContext);
        this.rootCapture.start();
        this.loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(this.rootCapture);
    }

    @AfterEach
    void detachRootCapture() {
        this.loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(this.rootCapture);
        this.rootCapture.stop();
        this.restore.forEach((category, level) ->
                this.loggerContext.getLogger(category).setLevel(level));
        this.restore.clear();
    }

    /**
     * Applies the level the shipped document states for one category, remembering what was there.
     *
     * @param  category the category to configure
     * @return the level that was applied
     */
    private Level applyShippedLevel(final String category) {
        final Logger logger = this.loggerContext.getLogger(category);
        this.restore.putIfAbsent(category, logger.getLevel());
        final Level shipped = Level.toLevel(shippedLevels().get(category), null);
        assertThat(shipped)
                .as("the shipped document must state a level for %s, or this test is asserting its own "
                        + "configuration rather than the deployment's", category)
                .isNotNull();
        logger.setLevel(shipped);
        return shipped;
    }

    /**
     * Emits one record through {@code java.util.logging} - the API the trace exporter's two transports
     * actually use - routed through the same bridge handler the runtime installs.
     *
     * <p>The handler is attached to the single category under test and taken off again rather than
     * installed globally, because the global install replaces the platform's root handlers for the whole
     * process and this test has no business doing that to the rest of the run.
     *
     * @param category the category to emit under
     * @param message  the record text
     */
    private static void emitThroughThePlatformLoggingApi(final String category, final String message) {
        final java.util.logging.Logger platformLogger = java.util.logging.Logger.getLogger(category);
        final SLF4JBridgeHandler bridge = new SLF4JBridgeHandler();
        final boolean delegated = platformLogger.getUseParentHandlers();
        platformLogger.addHandler(bridge);
        platformLogger.setUseParentHandlers(false);
        try {
            platformLogger.warning(message);
        } finally {
            platformLogger.setUseParentHandlers(delegated);
            platformLogger.removeHandler(bridge);
        }
    }

    /**
     * Reads the level map out of the shipped document.
     *
     * @return every category the shared baseline names, and the level it names it at
     */
    private static Map<String, String> shippedLevels() {
        try (InputStream document = Files.newInputStream(SHARED_DOCUMENT)) {
            // Read the two nested mappings through wildcards rather than through an unchecked cast:
            // the module's convention (MetricsScrapeExposureTest) is that a parsed document is
            // navigated with `instanceof Map<?, ?>` so a shape change fails as a readable assertion
            // instead of a ClassCastException, and so no warning has to be suppressed to compile it.
            final Map<String, String> levels = new HashMap<>();
            if (new Yaml().load(document) instanceof Map<?, ?> root
                    && root.get("logging") instanceof Map<?, ?> logging
                    && logging.get("level") instanceof Map<?, ?> configured) {
                configured.forEach((key, value) -> levels.put(String.valueOf(key), String.valueOf(value)));
            }
            return levels;
        } catch (final IOException unreadable) {
            throw new IllegalStateException("the shipped " + SHARED_DOCUMENT + " must be readable",
                    unreadable);
        }
    }

    /** The messages the root appender captured. */
    private List<String> captured() {
        final List<String> messages = new ArrayList<>();
        for (final ILoggingEvent event : this.rootCapture.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    @Nested
    @DisplayName("nothing the silenced categories emit reaches any appender")
    class NothingReachesAnAppender {

        @Test
        @DisplayName("the queue client's failure line is not emitted, which is the line that carried the "
                + "provider exception and the resolved endpoint")
        void theQueueFailureLineIsNotEmitted() {
            for (final String category : QUEUE_CATEGORIES) {
                applyShippedLevel(category);
                LoggerFactory.getLogger(category).error("Error sending message {} to endpoint {}",
                        CARD_IMAGE, "https://sqs.eu-west-2.amazonaws.com/000000000000/JOBS.fifo",
                        new IllegalStateException("provider failure"));
            }

            assertThat(captured())
                    .as("no level of this category keeps the failure and drops the card, because they are "
                            + "the same statement")
                    .isEmpty();
        }

        @Test
        @DisplayName("the queue client's trace line is not emitted either, which is the line that carried "
                + "the whole message including its headers")
        void theQueueTraceLineIsNotEmitted() {
            for (final String category : QUEUE_CATEGORIES) {
                applyShippedLevel(category);
                final org.slf4j.Logger provider = LoggerFactory.getLogger(category);
                provider.trace("Sending message {} to endpoint {}", CARD_IMAGE, "JOBS.fifo");
                provider.debug("Message {} successfully sent to endpoint {} with id {}",
                        CARD_IMAGE, "JOBS.fifo", "message-id");
            }

            assertThat(captured()).isEmpty();
        }

        @Test
        @DisplayName("the exporter's failure line is not emitted, so a collector cannot choose content in "
                + "this deployment's log")
        void theExporterFailureLineIsNotEmitted() {
            for (final String category : EXPORTER_CATEGORIES) {
                applyShippedLevel(category);
                final org.slf4j.Logger provider = LoggerFactory.getLogger(category);
                provider.warn("Failed to export spans. Server responded with HTTP status code 503."
                        + " Error message: <collector chose this text>");
                provider.error("Failed to export spans. The request could not be executed.",
                        new IllegalStateException("<collector chose this too>"));
            }

            assertThat(captured()).isEmpty();
        }

        @Test
        @DisplayName("the exporter's record is silenced along the path it actually travels: both "
                + "transports log through the platform logging API, and the bridge resolves a record to "
                + "the same-named category, so the shipped level is what stops it")
        void theExporterRecordIsSilencedAlongTheBridgedPath() {
            for (final String category : EXPORTER_CATEGORIES) {
                applyShippedLevel(category);
                emitThroughThePlatformLoggingApi(category,
                        "Failed to export spans. Server responded with HTTP status code 503.");
            }

            assertThat(captured())
                    .as("neither transport uses the SLF4J API, so a suppression that only held for direct "
                            + "SLF4J calls would leave the real path wide open")
                    .isEmpty();

            // Not vacuous: the same record, on the same path, arrives once the category is not OFF.
            final String probe = EXPORTER_CATEGORIES.get(0);
            loggerContext.getLogger(probe).setLevel(Level.DEBUG);
            emitThroughThePlatformLoggingApi(probe, "Failed to export spans.");

            assertThat(captured())
                    .as("without this arm the assertion above could be passing because the bridge is "
                            + "absent rather than because the shipped level is OFF")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the migration executor's topology line is not emitted, so the host, port, database "
                + "name and server version of the estate stay inside the process")
        void theMigrationTopologyLineIsNotEmitted() {
            applyShippedLevel(MIGRATION_EXECUTOR_CATEGORY);

            LoggerFactory.getLogger(MIGRATION_EXECUTOR_CATEGORY)
                    .info("Database: jdbc:postgresql://db.internal:5432/carddemo (PostgreSQL 16.14)");

            assertThat(captured())
                    .as("a redacted credential is why the line reads as safe; topology is what a reader "
                            + "needs before a credential is worth anything")
                    .isEmpty();
        }

        @Test
        @DisplayName("the migration executor is raised rather than switched off, so a failed migration "
                + "operation is still heard")
        void theMigrationExecutorIsOnlyRaised() {
            final Level shipped = applyShippedLevel(MIGRATION_EXECUTOR_CATEGORY);

            LoggerFactory.getLogger(MIGRATION_EXECUTOR_CATEGORY)
                    .warn("Migration operation failed");

            assertThat(shipped).isEqualTo(Level.WARN);
            assertThat(captured()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("what has to survive the suppression")
    class WhatHasToSurvive {

        @Test
        @DisplayName("the per-migration lines local validation reads still reach an appender, because they "
                + "come from a different category and it stays at information level")
        void thePerMigrationLinesSurvive() {
            applyShippedLevel(MIGRATION_EXECUTOR_CATEGORY);
            applyShippedLevel("org.flywaydb");
            restore.putIfAbsent(MIGRATION_COMMAND_CATEGORY,
                    loggerContext.getLogger(MIGRATION_COMMAND_CATEGORY).getLevel());
            loggerContext.getLogger(MIGRATION_COMMAND_CATEGORY).setLevel(null);

            LoggerFactory.getLogger(MIGRATION_COMMAND_CATEGORY)
                    .info("Migrating schema \"public\" to version \"4 - seed user security\"");

            assertThat(captured())
                    .as("silencing this would break the cross-file contract the suppression was careful "
                            + "not to touch")
                    .anySatisfy(message -> assertThat(message).contains("Migrating schema"));
        }
    }

    @Nested
    @DisplayName("the shipped document is where the levels live")
    class TheShippedDocument {

        @Test
        @DisplayName("names all four silenced categories and the migration executor, so the behavioural "
                + "assertions above are about the deployment and not about this test")
        void namesEveryCategory() {
            final Map<String, String> levels = shippedLevels();

            for (final String category : QUEUE_CATEGORIES) {
                assertThat(levels).containsEntry(category, "OFF");
            }
            for (final String category : EXPORTER_CATEGORIES) {
                assertThat(levels).containsEntry(category, "OFF");
            }
            assertThat(levels).containsEntry(MIGRATION_EXECUTOR_CATEGORY, "WARN");
        }

        @Test
        @DisplayName("keeps the parent migration category at information level, so the raise is one class "
                + "and not the whole tree")
        void keepsTheParentMigrationCategoryOn() {
            assertThat(shippedLevels()).containsEntry("org.flywaydb", "INFO");
        }

        @Test
        @DisplayName("declares each silenced category in the logging configuration too, so a reader of "
                + "either file finds the decision")
        void declaresEachCategoryInTheLoggingConfiguration() throws IOException {
            final String configuration = Files.readString(
                    Path.of("src", "main", "resources", "logback-spring.xml"));

            for (final String category : QUEUE_CATEGORIES) {
                assertThat(configuration).contains("<logger name=\"" + category + "\"");
            }
            for (final String category : EXPORTER_CATEGORIES) {
                assertThat(configuration).contains("<logger name=\"" + category + "\"");
            }
            assertThat(configuration)
                    .contains("<logger name=\"" + MIGRATION_EXECUTOR_CATEGORY + "\"");
        }
    }

    @Nested
    @DisplayName("what replaces the exporter's silence")
    class WhatReplacesTheSilence {

        /** The registry the counter lands in. */
        private final MeterRegistry registry = new SimpleMeterRegistry();

        /** A provider over that registry, matching the production wiring's lazy resolution. */
        private final ObjectProvider<MeterRegistry> registries = new StubProvider(this.registry);

        @Test
        @DisplayName("counts the spans a collector accepted, so a working exporter is visible as a rate "
                + "rather than as silence")
        void countsAcceptedSpans() {
            final ObservabilityConfig.CountingSpanExporter counting =
                    new ObservabilityConfig.CountingSpanExporter(new FixedOutcomeExporter(true),
                            this.registries);

            counting.export(spans(7));

            assertThat(this.registry.get(ObservabilityConfig.TRACING_EXPORT_METER_NAME)
                            .tag(ObservabilityConfig.EXPORT_OUTCOME_TAG_KEY,
                                    ObservabilityConfig.EXPORT_OUTCOME_SUCCEEDED)
                            .counter().count())
                    .as("the unit is spans, because 'how many did I lose' is the question an operator has")
                    .isEqualTo(7.0d);
        }

        @Test
        @DisplayName("counts the spans a collector lost, which is the signal that replaces the log line "
                + "the collector used to write")
        void countsLostSpans() {
            final ObservabilityConfig.CountingSpanExporter counting =
                    new ObservabilityConfig.CountingSpanExporter(new FixedOutcomeExporter(false),
                            this.registries);

            counting.export(spans(3));
            counting.export(spans(2));

            assertThat(this.registry.get(ObservabilityConfig.TRACING_EXPORT_METER_NAME)
                            .tag(ObservabilityConfig.EXPORT_OUTCOME_TAG_KEY,
                                    ObservabilityConfig.EXPORT_OUTCOME_FAILED)
                            .counter().count())
                    .isEqualTo(5.0d);
        }

        @Test
        @DisplayName("delegates the export itself unchanged, so the decorator cannot alter what is "
                + "exported or when")
        void delegatesTheExport() {
            final FixedOutcomeExporter delegate = new FixedOutcomeExporter(true);
            final ObservabilityConfig.CountingSpanExporter counting =
                    new ObservabilityConfig.CountingSpanExporter(delegate, this.registries);

            counting.export(spans(1));
            counting.flush();
            counting.shutdown();

            assertThat(delegate.exports.get()).isEqualTo(1);
            assertThat(delegate.flushes.get()).isEqualTo(1);
            assertThat(delegate.shutdowns.get()).isEqualTo(1);
            assertThat(counting.delegate()).isSameAs(delegate);
        }

        @Test
        @DisplayName("counts nothing when no registry is published, because telemetry about telemetry must "
                + "not be able to fail a context")
        void countsNothingWithoutARegistry() {
            final ObservabilityConfig.CountingSpanExporter counting =
                    new ObservabilityConfig.CountingSpanExporter(new FixedOutcomeExporter(false),
                            new StubProvider(null));

            counting.export(spans(4));

            assertThat(this.registry.find(ObservabilityConfig.TRACING_EXPORT_METER_NAME).counters())
                    .isEmpty();
        }

        @Test
        @DisplayName("records nothing for an empty batch, so a flush of no spans does not appear as "
                + "traffic")
        void recordsNothingForAnEmptyBatch() {
            final ObservabilityConfig.CountingSpanExporter counting =
                    new ObservabilityConfig.CountingSpanExporter(new FixedOutcomeExporter(true),
                            this.registries);

            counting.export(List.of());

            assertThat(this.registry.find(ObservabilityConfig.TRACING_EXPORT_METER_NAME).counters())
                    .isEmpty();
        }

        /**
         * A batch of the requested size. The spans themselves are never read, only counted.
         *
         * @param  size how many entries
         * @return a batch of that size
         */
        private static Collection<SpanData> spans(final int size) {
            final List<SpanData> batch = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                batch.add(null);
            }
            return batch;
        }
    }

    /** An exporter whose every call reports the outcome it was built with, and counts its calls. */
    private static final class FixedOutcomeExporter implements SpanExporter {

        /** Whether an export reports success. */
        private final boolean succeeds;

        /** How many exports were delegated. */
        private final AtomicInteger exports = new AtomicInteger();

        /** How many flushes were delegated. */
        private final AtomicInteger flushes = new AtomicInteger();

        /** How many shutdowns were delegated. */
        private final AtomicInteger shutdowns = new AtomicInteger();

        /**
         * Builds the double.
         *
         * @param succeeds whether an export reports success
         */
        FixedOutcomeExporter(final boolean succeeds) {
            this.succeeds = succeeds;
        }

        @Override
        public CompletableResultCode export(final Collection<SpanData> spans) {
            this.exports.incrementAndGet();
            return this.succeeds ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
        }

        @Override
        public CompletableResultCode flush() {
            this.flushes.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            this.shutdowns.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }
    }

    /** A provider that answers with one registry, or with none. */
    private static final class StubProvider implements ObjectProvider<MeterRegistry> {

        /** The registry to answer with, or {@code null} for a context that publishes none. */
        private final MeterRegistry registry;

        /**
         * Builds the double.
         *
         * @param registry the registry to answer with, possibly {@code null}
         */
        StubProvider(final MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public MeterRegistry getObject() {
            return this.registry;
        }

        @Override
        public MeterRegistry getObject(final Object... args) {
            return this.registry;
        }

        @Override
        public MeterRegistry getIfAvailable() {
            return this.registry;
        }

        @Override
        public MeterRegistry getIfUnique() {
            return this.registry;
        }
    }
}
