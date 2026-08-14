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
package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.support.JavaSourceCensus;

/**
 * The sanitised-observation contract, and the canary that keeps every outbound boundary inside it.
 *
 * <h2>What is being protected</h2>
 *
 * <p>A tracing exporter publishes a recorded error's message and stack trace. A provider failure's message
 * is composed by the provider: an object-store refusal names the bucket and the key, a queue refusal names
 * the queue and often the endpoint, and a failure provoked by an oversized input carries that input. Every
 * boundary <em>log</em> record in this module already reduces such a failure to a bounded type chain; the
 * span was the channel that still carried the object itself, which made the log's discretion pointless.
 *
 * <p>So there are two halves to prove, and one without the other is worth little. The first is that the
 * helper records only authored text - asserted here against a failure whose message is a marker string
 * that must not appear anywhere in what the span carries. The second is that no boundary bypasses the
 * helper, which is a property of the whole production tree rather than of any one call site, and is
 * asserted by reading the tree. A per-boundary test can only ever speak for the boundary it names, and the
 * finding this class answers was that five boundaries each recorded a raw failure while every one of them
 * had a test asserting it did.
 *
 * @see SanitisedObservation
 */
@DisplayName("Sanitised observation: a span records the classification, never the provider's failure")
class SanitisedObservationTest {

    /** Text that must never reach a span. Distinctive enough that a substring search is conclusive. */
    private static final String PROVIDER_TEXT =
            "bucket=carddemo-secret-staging key=/etc/passwd token=AKIAEXAMPLESECRET";

    /** Every observation this test's registry stopped, in order. */
    private final List<Observation.Context> stopped = new ArrayList<>();

    /** The registry the helper is driven through. */
    private ObservationRegistry registry;

    /** Registers a handler that captures each stopped context. */
    @BeforeEach
    void createRegistry() {
        this.stopped.clear();
        this.registry = ObservationRegistry.create();
        this.registry.observationConfig().observationHandler(new ObservationHandler<>() {

            @Override
            public void onStop(final Observation.Context context) {
                SanitisedObservationTest.this.stopped.add(context);
            }

            @Override
            public boolean supportsContext(final Observation.Context context) {
                return true;
            }
        });
    }

    /**
     * @return a fresh, unstarted observation on this test's registry
     */
    private Observation observation() {
        return Observation.createNotStarted("carddemo.test.boundary", this.registry);
    }

    @Nested
    @DisplayName("A call that succeeds")
    class ACallThatSucceeds {

        /** Creates the nested test class. */
        ACallThatSucceeds() {
        }

        @Test
        @DisplayName("answers what the call answered, and the span carries no error")
        void answersTheCallAndRecordsNoError() {
            final String answer = SanitisedObservation.observe(observation(), () -> "answered");

            assertThat(answer).isEqualTo("answered");
            assertThat(stopped).hasSize(1);
            assertThat(stopped.getFirst().getError())
                    .as("a successful call must not leave an error attribute behind, or every trace "
                            + "would read as a failed one")
                    .isNull();
        }

        @Test
        @DisplayName("runs inside the observation's own scope, so a nested observation descends from it")
        void runsInsideTheScope() {
            final Observation outer = observation();
            final AtomicReference<Observation> current = new AtomicReference<>();

            SanitisedObservation.observe(outer, () -> {
                current.set(registry.getCurrentObservation());
                return "answered";
            });

            assertThat(current.get())
                    .as("the scope must be open for the duration of the call; the object store's own "
                            + "per-operation observations are children of the boundary observation and "
                            + "become detached roots without it")
                    .isSameAs(outer);
            assertThat(registry.getCurrentObservation())
                    .as("and it must be closed again afterwards, or the scope leaks onto the thread")
                    .isNull();
        }

        @Test
        @DisplayName("carries a null answer through rather than substituting one")
        void carriesANullAnswerThrough() {
            assertThat(SanitisedObservation.<String>observe(observation(), () -> null)).isNull();
        }

        @Test
        @DisplayName("stops the observation for the runnable form too")
        void stopsTheObservationForTheRunnableForm() {
            final AtomicBoolean ran = new AtomicBoolean();

            SanitisedObservation.observeRunnable(observation(), () -> ran.set(true));

            assertThat(ran).isTrue();
            assertThat(stopped).hasSize(1);
            assertThat(stopped.getFirst().getError()).isNull();
        }
    }

    @Nested
    @DisplayName("A call that fails")
    class ACallThatFails {

        /** Creates the nested test class. */
        ACallThatFails() {
        }

        @Test
        @DisplayName("rethrows the provider's own failure unchanged, so every existing handler still sees "
                + "what it saw")
        void rethrowsTheProviderFailure() {
            final IllegalStateException refused = new IllegalStateException(PROVIDER_TEXT);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> SanitisedObservation.observe(observation(), () -> {
                        throw refused;
                    }))
                    .isSameAs(refused);
        }

        @Test
        @DisplayName("records a classification and not the failure, so the exporter has nothing of the "
                + "provider's to publish")
        void recordsAClassificationRatherThanTheFailure() {
            final IllegalStateException refused =
                    new IllegalStateException(PROVIDER_TEXT, new IOException(PROVIDER_TEXT));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> SanitisedObservation.observe(observation(), () -> {
                        throw refused;
                    }));

            assertThat(stopped).hasSize(1);
            final Throwable recorded = stopped.getFirst().getError();
            assertThat(recorded)
                    .as("an error must still be recorded - a failed boundary that recorded nothing would "
                            + "leave the trace claiming the call succeeded")
                    .isNotNull()
                    .as("but it must not be the provider's own object, which is what the exporter reads")
                    .isNotSameAs(refused)
                    .isInstanceOf(SanitisedObservation.SanitisedBoundaryFailure.class);
            assertThat(recorded.getCause())
                    .as("and it must carry no cause, or a handler that walks the chain reaches the "
                            + "provider's failure anyway")
                    .isNull();
            assertThat(recorded.getStackTrace())
                    .as("and no frames, because a rendered trace is the other way a message escapes")
                    .isEmpty();
            assertThat(recorded.getSuppressed()).isEmpty();
        }

        @Test
        @DisplayName("publishes no fragment of the provider's message, and says what failed all the same")
        void publishesTheChainAndNotTheMessage() {
            final IllegalStateException refused =
                    new IllegalStateException(PROVIDER_TEXT, new IOException(PROVIDER_TEXT));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> SanitisedObservation.observe(observation(), () -> {
                        throw refused;
                    }));

            final Throwable recorded = stopped.getFirst().getError();
            assertThat(recorded.getMessage())
                    .as("the whole of the disclosure question is whether this string can hold text the "
                            + "module did not author")
                    .doesNotContain("carddemo-secret-staging")
                    .doesNotContain("/etc/passwd")
                    .doesNotContain("AKIAEXAMPLESECRET")
                    .startsWith(SanitisedObservation.CLASSIFICATION_PREFIX)
                    .contains(SanitisedObservation.FAILURE_CHAIN_LABEL
                            + FailureDiagnostics.failureChainOf(refused))
                    .contains(SanitisedObservation.FAILURE_ORIGIN_LABEL);
            assertThat(FailureDiagnostics.failureChainOf(refused))
                    .as("and the chain it does publish names both types, so a diagnosing reader still "
                            + "learns which layer refused")
                    .isEqualTo("IllegalStateException" + FailureDiagnostics.FAILURE_CHAIN_SEPARATOR
                            + "IOException");
        }

        @Test
        @DisplayName("stops the observation on the failing path as well, so no span is left open")
        void stopsTheObservationOnTheFailingPath() {
            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> SanitisedObservation.observeRunnable(observation(), () -> {
                        throw new UncheckedIOException(new IOException(PROVIDER_TEXT));
                    }));

            assertThat(stopped).hasSize(1);
            assertThat(stopped.getFirst().getError())
                    .isInstanceOf(SanitisedObservation.SanitisedBoundaryFailure.class);
            assertThat(registry.getCurrentObservation())
                    .as("and the scope is closed, so the failing call does not leave its context on the "
                            + "thread for whatever runs next")
                    .isNull();
        }

        @Test
        @DisplayName("classifies an Error the same way rather than letting it past unrecorded")
        void classifiesAnErrorToo() {
            assertThatExceptionOfType(AssertionError.class)
                    .isThrownBy(() -> SanitisedObservation.observeRunnable(observation(), () -> {
                        throw new AssertionError(PROVIDER_TEXT);
                    }));

            assertThat(stopped.getFirst().getError())
                    .isInstanceOf(SanitisedObservation.SanitisedBoundaryFailure.class);
            assertThat(stopped.getFirst().getError().getMessage()).doesNotContain("/etc/passwd");
        }
    }

    @Nested
    @DisplayName("The helper's own guards")
    class TheHelpersOwnGuards {

        /** Creates the nested test class. */
        TheHelpersOwnGuards() {
        }

        @Test
        @DisplayName("refuse a null observation, a null call and a null failure")
        void refuseNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SanitisedObservation.observe(null, () -> "answered"))
                    .withMessageContaining("observation");
            assertThatNullPointerException()
                    .isThrownBy(() -> SanitisedObservation.observe(observation(), null))
                    .withMessageContaining("call");
            assertThatNullPointerException()
                    .isThrownBy(() -> SanitisedObservation.observeRunnable(observation(), null))
                    .withMessageContaining("call");
            assertThatNullPointerException()
                    .isThrownBy(() -> SanitisedObservation.sanitisedFailureFor(null))
                    .withMessageContaining("failure");
        }

        @Test
        @DisplayName("and the type is a holder, so nothing can construct one and give it state")
        void theTypeIsAHolder() {
            assertThat(SanitisedObservation.class.getDeclaredConstructors())
                    .as("one private constructor that refuses")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("The canary over the production tree")
    class TheCanaryOverTheProductionTree {

        /** Creates the nested test class. */
        TheCanaryOverTheProductionTree() {
        }

        /**
         * No production source may call the framework's convenience form.
         *
         * <p>This is the assertion that generalises. Five boundaries recorded raw provider failures, and
         * each of the five had a test asserting exactly that behaviour, so no per-boundary test could have
         * found the family. The convenience form is the mechanism, so the mechanism is what is banned:
         * {@code observe(...)} and {@code observeChecked(...)} on an observation both record the raw
         * throwable, and a sixth boundary written next year would do it again for free.
         *
         * @throws IOException if the production tree cannot be read
         */
        @Test
        @DisplayName("holds no call to the framework's raw-error convenience form, so a new boundary "
                + "cannot reintroduce the family")
        void holdsNoRawObserveCall() throws IOException {
            final List<String> offenders = new ArrayList<>();
            for (final Path source : JavaSourceCensus.sourcesUnder(JavaSourceCensus.PRODUCTION_TREE)) {
                // Comments and literals are blanked first, so a paragraph discussing the convenience
                // form - this class's own subject matter, and the helper's Javadoc - is not an offence.
                // Calls THROUGH the helper are then removed, because they are the remedy rather than the
                // defect: what remains is a call to observe() on anything else, which is the mechanism.
                final String code = JavaSourceCensus
                        .codeOnly(Files.readString(source, StandardCharsets.UTF_8))
                        .replace("SanitisedObservation.observeRunnable(", "")
                        .replace("SanitisedObservation.observe(", "");
                if (code.contains(".observe(") || code.contains(".observeChecked(")) {
                    offenders.add(source.toString());
                }
            }

            assertThat(offenders)
                    .as("every outbound boundary observes through SanitisedObservation, which records a "
                            + "classification; the framework's own observe() records the provider's "
                            + "failure and the exporter publishes its message and stack trace")
                    .isEmpty();
        }

        /**
         * And the helper is actually used, at the four boundaries that make outbound calls.
         *
         * <p>The ban above is satisfiable by observing nothing at all, which would remove the disclosure
         * by removing the telemetry - the wrong reading of the finding, and one this assertion refuses.
         *
         * <p>The roster was five. {@code service/BatchStagingService} was one of them and has been removed
         * as dead surface: it was component-scanned, injected by nothing and reached by no configuration,
         * so the observation it carried was never emitted by a run. The batch tier's durable object-store
         * boundary is {@code batch/step/StagedGenerationStore}, which is on this roster and does observe.
         * See decision-log entries DL-146 and DL-147.
         *
         * @throws IOException if the production tree cannot be read
         */
        @Test
        @DisplayName("and the four outbound boundaries do observe, so the disclosure was removed rather "
                + "than the telemetry")
        void theFourBoundariesStillObserve() throws IOException {
            final List<String> observing = new ArrayList<>();
            for (final Path source : JavaSourceCensus.sourcesUnder(JavaSourceCensus.PRODUCTION_TREE)) {
                final String code = JavaSourceCensus.codeOnly(Files.readString(source,
                        StandardCharsets.UTF_8));
                if (code.contains("SanitisedObservation.observe")) {
                    observing.add(source.getFileName().toString());
                }
            }

            assertThat(observing)
                    .as("the queue publish, the notification publish, the durable object-store boundary "
                            + "and the batch publication callback")
                    .containsExactlyInAnyOrder("JobSubmissionService.java",
                            "JobCompletionNotificationService.java",
                            "StagedGenerationStore.java",
                            "BatchConfig.java");
        }

        /**
         * @return every Java source in the production tree
         * @throws IOException if the tree cannot be walked
         */
        private static Stream<Path> productionSources() throws IOException {
            return Files.walk(Path.of(JavaSourceCensus.PRODUCTION_TREE))
                    .filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".java"));
        }

        @Test
        @DisplayName("and the tree it reads is the one this module ships, so the canary cannot pass by "
                + "reading nothing")
        void theCanaryReadsTheShippedTree() throws IOException {
            try (Stream<Path> sources = productionSources()) {
                assertThat(sources.count())
                        .as("a walk that found nothing would satisfy the ban above vacuously")
                        .isGreaterThan(200L);
            }
        }
    }
}
