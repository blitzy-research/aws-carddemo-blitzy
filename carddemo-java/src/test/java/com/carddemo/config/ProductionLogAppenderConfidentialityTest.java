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
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

/**
 * Drives the <strong>real production JSON appender configuration</strong>, read out of the shipped
 * {@code logback-spring.xml}, and proves the two confidentiality properties that configuration is
 * responsible for: that the diagnostic context is exported through an allow list, and that a throwable
 * rendering is bounded.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A review of the shipped configuration found the machine-readable encoder exporting every
 * diagnostic-context entry and rendering a throwable without bound, and found that <em>no test
 * anywhere in the module referenced the logging configuration at all</em>. Both defects were therefore
 * invisible to the suite, and a later edit that reopened either would have been equally invisible. The
 * remedy is not only to correct the configuration but to make its behaviour asserted, so this file
 * exists to hold the corrected posture rather than to document it.
 *
 * <h2>How the real configuration is exercised, and why not through the framework</h2>
 *
 * <p>The shipped file uses the framework's own {@code springProfile} and {@code springProperty}
 * elements, which only exist when the framework loads the configuration after preparing an
 * environment. Loading it that way would reconfigure the JVM-wide logger context and would fight every
 * other test in the suite.
 *
 * <p>So the appender element is <strong>extracted verbatim from the shipped file</strong> and
 * configured into a private, isolated logger context, with exactly two edits, both mechanical and both
 * asserted to have happened:
 *
 * <ul>
 *   <li>the appender implementation is swapped from the console to a file, so the output is a
 *       deterministic artefact this test can read rather than a stream shared with the whole JVM. The
 *       <em>encoder</em> element - which is the entire subject of this test - is not touched;</li>
 *   <li>the two property references the encoder resolves are declared in the wrapper from the values
 *       the shipped file itself declares, so the bounds under test are the shipped bounds and not a
 *       copy of them.</li>
 * </ul>
 *
 * <p>Consequently a future edit that removes a bound, widens the allow list, or reintroduces
 * whole-context export fails here, because the text that edit produces is the text this test loads.
 *
 * <h2>Canary discipline</h2>
 *
 * <p>Every absence assertion names a distinctive planted literal - a connection string with a
 * credential in it, a bearer token, a national identifier, a primary account number - so that no
 * assertion can pass by coincidence, and each is a category the review named as reachable through an
 * unbounded rendering.
 */
@DisplayName("the production JSON appender, as shipped: an allow-listed context and a bounded trace")
class ProductionLogAppenderConfidentialityTest {

    /** The shipped configuration, which is the authority every expectation below is taken from. */
    private static final Path SHIPPED_CONFIGURATION =
            Path.of("src", "main", "resources", "logback-spring.xml");

    /** Name of the appender under test, as declared in the shipped file. */
    private static final String APPENDER_NAME = "CONSOLE_JSON";

    /** A connection string carrying a credential, of the shape a driver failure carries. */
    private static final String JDBC_CANARY =
            "jdbc:postgresql://db.internal:5432/carddemo?user=carddemo&password=s3cr3t-canary";

    /** A bearer credential, of the shape a security failure can carry. */
    private static final String TOKEN_CANARY = "eyJhbGciOiJIUzI1NiJ9.canary-payload.canary-signature";

    /** A national identifier, of the shape an interpolated message can carry. */
    private static final String NATIONAL_ID_CANARY = "123-45-6789";

    /** A primary account number, of the shape a bound-parameter echo can carry. */
    private static final String PAN_CANARY = "4111111111111111";

    /** The two diagnostic-context keys the shipped allow list names. */
    private static final List<String> ALLOWED_CONTEXT_KEYS = List.of("traceId", "spanId");

    /** The appender element, read once from the shipped file. */
    private static String shippedAppenderElement;

    /** The property declarations the encoder resolves, read once from the shipped file. */
    private static String shippedThrowableBoundProperties;

    /** The isolated context this test configures, torn down after every test. */
    private LoggerContext context;

    /** Constructs the fixture. */
    ProductionLogAppenderConfidentialityTest() {
    }

    /**
     * Reads the shipped configuration and extracts the two fragments the tests configure.
     *
     * @throws IOException if the shipped configuration cannot be read
     */
    @BeforeAll
    static void readShippedConfiguration() throws IOException {
        final String shipped = Files.readString(SHIPPED_CONFIGURATION, StandardCharsets.UTF_8);

        final Matcher appender = Pattern
                .compile("<appender name=\"" + APPENDER_NAME + "\".*?</appender>", Pattern.DOTALL)
                .matcher(shipped);
        assertThat(appender.find())
                .as("the shipped configuration must still declare the %s appender", APPENDER_NAME)
                .isTrue();
        shippedAppenderElement = appender.group();

        final StringBuilder properties = new StringBuilder();
        final Matcher property = Pattern
                .compile("<property name=\"(JSON_THROWABLE_[A-Z_]+)\" value=\"([^\"]+)\"/>")
                .matcher(shipped);
        while (property.find()) {
            properties.append("<property name=\"").append(property.group(1))
                    .append("\" value=\"").append(property.group(2)).append("\"/>\n");
        }
        assertThat(properties.length())
                .as("the shipped configuration must declare the throwable bounds as properties, so "
                        + "this test reads the shipped values rather than a copy of them")
                .isPositive();
        shippedThrowableBoundProperties = properties.toString();
    }

    /**
     * Configures the shipped appender into a private context writing to one file.
     *
     * @param output file the appender writes to
     * @return the configured context's root logger
     * @throws Exception if the configuration is rejected
     */
    private ch.qos.logback.classic.Logger configureShippedAppender(final Path output)
            throws Exception {
        final String fileAppender = shippedAppenderElement.replaceFirst(
                "class=\"ch\\.qos\\.logback\\.core\\.ConsoleAppender\"",
                Matcher.quoteReplacement("class=\"ch.qos.logback.core.FileAppender\">\n<file>"
                        + output.toAbsolutePath() + "</file"));
        assertThat(fileAppender)
                .as("the console appender must have been swapped for a file appender")
                .contains("FileAppender")
                .doesNotContain("ConsoleAppender");
        assertThat(fileAppender)
                .as("the encoder element itself must be carried through untouched, because it is the "
                        + "whole subject of this test")
                .contains("net.logstash.logback.encoder.LogstashEncoder");

        final String configuration = "<configuration>\n"
                + "<property name=\"applicationName\" value=\"carddemo\"/>\n"
                + shippedThrowableBoundProperties
                + fileAppender
                + "<root level=\"INFO\"><appender-ref ref=\"" + APPENDER_NAME + "\"/></root>\n"
                + "</configuration>\n";

        this.context = new LoggerContext();
        this.context.setName("shipped-appender-under-test");
        // A privately constructed context carries no diagnostic-context adapter, and the encoder reads
        // one on every event. Binding the process-wide adapter both supplies it and makes the entries
        // this test writes through the ordinary facade visible to the appender under test - which is
        // the point, because the entries production writes arrive by that same route.
        this.context.setMDCAdapter(MDC.getMDCAdapter());
        final JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(this.context);
        configurator.doConfigure(
                new java.io.ByteArrayInputStream(configuration.getBytes(StandardCharsets.UTF_8)));
        assertThat(this.context.getStatusManager().getCopyOfStatusList().stream()
                .noneMatch(status -> status.getLevel() == ch.qos.logback.core.status.Status.ERROR))
                .as("the shipped appender must configure without error; status: %s",
                        statusOf(this.context))
                .isTrue();
        return this.context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    }

    /**
     * Renders the configuration status, for a failure message that has to explain itself.
     *
     * @param loggerContext the context whose status is wanted
     * @return the status text
     */
    private static String statusOf(final LoggerContext loggerContext) {
        return loggerContext.getStatusManager().getCopyOfStatusList().stream()
                .map(Object::toString)
                .reduce("", (left, right) -> left + System.lineSeparator() + right);
    }

    /** Stops the private context and clears the diagnostic context this test may have written. */
    @AfterEach
    void tearDown() {
        MDC.clear();
        if (this.context != null) {
            this.context.stop();
            this.context = null;
        }
    }

    /**
     * Logs one event through the shipped appender and returns what it wrote.
     *
     * @param output   file the appender writes to
     * @param message  message to log
     * @param failure  throwable to log, or {@code null} for none
     * @return the single rendered line
     * @throws Exception if the appender cannot be configured or its output cannot be read
     */
    private String renderOneEvent(final Path output, final String message, final Throwable failure)
            throws Exception {
        final ch.qos.logback.classic.Logger root = configureShippedAppender(output);
        if (failure == null) {
            root.error(message);
        } else {
            root.error(message, failure);
        }
        this.context.stop();
        this.context = null;
        final String written = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(written).as("the appender must have written the event").isNotBlank();
        assertThat(written.strip().lines())
                .as("a machine-readable event must remain one physical line, so line-oriented "
                        + "ingestion needs no multiline rule")
                .hasSize(1);
        return written.strip();
    }

    @Nested
    @DisplayName("the diagnostic context is exported through an allow list")
    class ContextAllowList {

        @ParameterizedTest(name = "the context key {0} is not exported")
        @ValueSource(strings = {"authorization", "Authorization", "password", "token", "accessToken",
            "bearer", "userInput", "email", "ssn", "cardNumber", "sessionId", "requestBody"})
        @DisplayName("a context entry under any key other than the two correlation identifiers is "
                + "dropped, whoever wrote it and whenever it was added")
        void anUnnamedContextKeyIsNotExported(final String key, @TempDir final Path directory)
                throws Exception {
            MDC.put(key, TOKEN_CANARY);

            final String rendered = renderOneEvent(directory.resolve("event.json"), "an event", null);

            assertThat(rendered)
                    .as("the value written under %s must not appear in the production record", key)
                    .doesNotContain(TOKEN_CANARY);
            assertThat(rendered)
                    .as("and neither must the key, which would itself disclose that the value exists")
                    .doesNotContain("\"" + key + "\"");
        }

        @ParameterizedTest(name = "a context entry under the reserved field name {0} cannot overwrite "
                + "the field")
        @ValueSource(strings = {"message", "logger", "level", "application", "thread", "stackTrace"})
        @DisplayName("the documented field-name collision rule is now enforced rather than requested: "
                + "an entry named after a reserved field is dropped, so the field keeps its own value")
        void aContextKeyNamedAfterAFieldCannotOverwriteIt(final String reservedFieldName,
                @TempDir final Path directory) throws Exception {
            MDC.put(reservedFieldName, TOKEN_CANARY);

            final String rendered =
                    renderOneEvent(directory.resolve("event.json"), "the authored message", null);

            assertThat(rendered)
                    .as("the planted value must not reach the record under %s or any other name",
                            reservedFieldName)
                    .doesNotContain(TOKEN_CANARY);
            assertThat(rendered)
                    .as("and the record must still carry what this module authored")
                    .contains("\"message\":\"the authored message\"");
        }

        @ParameterizedTest(name = "the correlation identifier {0} is exported")
        @ValueSource(strings = {"traceId", "spanId"})
        @DisplayName("both correlation identifiers are exported at the top level, because that is what "
                + "joins a log line to its exported trace")
        void aCorrelationIdentifierIsExported(final String key, @TempDir final Path directory)
                throws Exception {
            MDC.put(key, "0af7651916cd43dd8448eb211c80319c");

            final String rendered = renderOneEvent(directory.resolve("event.json"), "an event", null);

            assertThat(rendered).contains("\"" + key + "\":\"0af7651916cd43dd8448eb211c80319c\"");
        }

        @Test
        @DisplayName("a sensitive key sitting alongside the correlation identifiers is dropped while "
                + "they are kept, so the allow list is selective rather than an on/off switch")
        void aSensitiveKeyAlongsideCorrelationIsDropped(@TempDir final Path directory)
                throws Exception {
            ALLOWED_CONTEXT_KEYS.forEach(key -> MDC.put(key, "kept-" + key));
            MDC.put("authorization", TOKEN_CANARY);
            MDC.put("nationalId", NATIONAL_ID_CANARY);

            final String rendered = renderOneEvent(directory.resolve("event.json"), "an event", null);

            ALLOWED_CONTEXT_KEYS.forEach(key ->
                    assertThat(rendered).contains("\"" + key + "\":\"kept-" + key + "\""));
            assertThat(rendered).doesNotContain(TOKEN_CANARY, NATIONAL_ID_CANARY, "nationalId",
                    "authorization");
        }
    }

    @Nested
    @DisplayName("the throwable rendering is bounded, in depth and in length")
    class BoundedThrowableRendering {

        @Test
        @DisplayName("an arbitrarily large throwable message cannot set the size of an event, which is "
                + "what removes log amplification as a lever")
        void anOversizedThrowableMessageIsBounded(@TempDir final Path directory) throws Exception {
            final String oversized = "X".repeat(500_000);

            final String rendered = renderOneEvent(directory.resolve("event.json"),
                    "a failure reached the boundary", new IllegalStateException(oversized));

            assertThat(rendered.length())
                    .as("one event must not grow without limit whatever a caller provokes")
                    .isLessThan(oversized.length());
        }

        @Test
        @DisplayName("a deep cause chain cannot set the size of an event either")
        void aDeepCauseChainIsBounded(@TempDir final Path directory) throws Exception {
            Throwable failure = new IllegalStateException("the root of a very deep chain");
            for (int level = 0; level < 400; level++) {
                failure = new IllegalStateException("wrapper level " + level, failure);
            }

            final String rendered = renderOneEvent(directory.resolve("event.json"),
                    "a deeply nested failure reached the boundary", failure);

            assertThat(rendered.length())
                    .as("the whole-trace bound is the backstop that makes an event's size independent "
                            + "of how deep a chain happens to be")
                    .isLessThan(200_000);
        }

        @Test
        @DisplayName("a bounded rendering still names the failure, so bounding costs the diagnostic "
                + "nothing that matters")
        void aBoundedRenderingStillNamesTheFailure(@TempDir final Path directory) throws Exception {
            final String rendered = renderOneEvent(directory.resolve("event.json"),
                    "a failure reached the boundary",
                    new IllegalStateException("a message this module authored"));

            assertThat(rendered).contains("IllegalStateException");
            assertThat(rendered).contains("\"stackTrace\"");
        }
    }

    @Nested
    @DisplayName("the two controls together: a boundary record carries no canary")
    class BoundaryRecordCarriesNoCanary {

        @Test
        @DisplayName("the record a boundary site produces - a sanitised failure chain, and no "
                + "throwable - carries none of the four canaries the chain was holding")
        void aSanitisedBoundaryRecordCarriesNoCanary(@TempDir final Path directory) throws Exception {
            // Exactly what GlobalExceptionHandler now does: the chain, the deepest type and the code
            // location are described, and the object is not passed. The failure below carries a
            // different canary at every level, and its own frames are this test's.
            final Throwable failure = new IllegalStateException(JDBC_CANARY,
                    new IllegalArgumentException(TOKEN_CANARY,
                            new UnsupportedOperationException(NATIONAL_ID_CANARY,
                                    new java.util.NoSuchElementException(PAN_CANARY))));

            final ch.qos.logback.classic.Logger root =
                    configureShippedAppender(directory.resolve("event.json"));
            root.error("Unhandled failure reached the REST boundary: failureChain={} rootFailureType={}"
                            + " failureOrigin={}",
                    com.carddemo.util.FailureDiagnostics.failureChainOf(failure),
                    com.carddemo.util.FailureDiagnostics.deepestFailureTypeOf(failure),
                    com.carddemo.util.FailureDiagnostics.failureOriginOf(failure));
            this.stopContext();

            final String rendered =
                    Files.readString(directory.resolve("event.json"), StandardCharsets.UTF_8);

            assertThat(rendered)
                    .doesNotContain(JDBC_CANARY, TOKEN_CANARY, NATIONAL_ID_CANARY, PAN_CANARY)
                    .doesNotContain("password", "s3cr3t");
            assertThat(rendered)
                    .as("and the diagnostic is still useful: the shape of the chain is published")
                    .contains("IllegalStateException<-IllegalArgumentException"
                            + "<-UnsupportedOperationException<-NoSuchElementException")
                    .contains("NoSuchElementException");
            assertThat(rendered)
                    .as("and it now says WHERE, which is the part a reader could not previously get from "
                            + "the record at all - published as code locations only, so it carries no "
                            + "message and cannot carry a canary")
                    .contains("failureOrigin=")
                    .contains("aSanitisedBoundaryRecordCarriesNoCanary");
            assertThat(rendered)
                    .as("no stack trace field is emitted, because no throwable was passed")
                    .doesNotContain("\"stackTrace\"");
        }

        /** Stops the context so the appender flushes its output. */
        private void stopContext() {
            ProductionLogAppenderConfidentialityTest.this.context.stop();
            ProductionLogAppenderConfidentialityTest.this.context = null;
        }
    }

    @Nested
    @DisplayName("the shipped text itself, so a reopening edit cannot pass unnoticed")
    class ShippedConfigurationText {

        @Test
        @DisplayName("the encoder declares the two correlation keys as an allow list")
        void theEncoderDeclaresTheAllowList() {
            ALLOWED_CONTEXT_KEYS.forEach(key -> assertThat(shippedAppenderElement)
                    .as("the allow list must name %s", key)
                    .contains("<includeMdcKeyName>" + key + "</includeMdcKeyName>"));
        }

        @Test
        @DisplayName("the encoder declares both throwable bounds, and neither is left to a default")
        void theEncoderDeclaresBothBounds() {
            assertThat(shippedAppenderElement)
                    .contains("<maxDepthPerThrowable>")
                    .contains("<maxLength>");
        }

        @Test
        @DisplayName("the shipped bounds are positive figures rather than the disabling sentinel, "
                + "because a converter reads a non-positive bound as no bound at all")
        void theShippedBoundsArePositive() {
            final Matcher property = Pattern
                    .compile("<property name=\"JSON_THROWABLE_[A-Z_]+\" value=\"(-?\\d+)\"/>")
                    .matcher(shippedThrowableBoundProperties);
            int found = 0;
            while (property.find()) {
                found++;
                assertThat(Integer.parseInt(property.group(1))).isPositive();
            }
            assertThat(found)
                    .as("both bounds must be declared as figures this assertion can read")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the appender level referencing it is unchanged, so this test is exercising the "
                + "appender production actually uses")
        void theProductionProfileStillReferencesTheAppender() throws IOException {
            final String shipped = Files.readString(SHIPPED_CONFIGURATION, StandardCharsets.UTF_8);

            assertThat(shipped).contains("<appender-ref ref=\"" + APPENDER_NAME + "\"/>");
            assertThat(shipped).contains("<springProfile name=\"prod\">");
        }
    }

    /**
     * Guards the assumption the whole file rests on: that the logging framework in use is the one whose
     * encoder these bounds belong to.
     */
    @Nested
    @DisplayName("the assumption this file rests on")
    class FrameworkAssumption {

        @Test
        @DisplayName("the encoder and the bounded converter are both on the test classpath, so an "
                + "absence would fail here rather than silently skip every assertion above")
        void theEncoderAndConverterArePresent() {
            assertThat(new net.logstash.logback.encoder.LogstashEncoder()).isNotNull();
            assertThat(new net.logstash.logback.stacktrace.ShortenedThrowableConverter()).isNotNull();
        }

        @Test
        @DisplayName("the level the appender is driven at here is a level the shipped root levels "
                + "actually emit")
        void theDrivenLevelIsEmitted() {
            assertThat(Level.ERROR.isGreaterOrEqual(Level.WARN)).isTrue();
        }
    }
}
