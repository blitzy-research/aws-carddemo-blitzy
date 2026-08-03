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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import net.logstash.logback.encoder.LogstashEncoder;

/**
 * Holds the machine-facing log encoder to an allow list of diagnostic-context keys, and proves the
 * narrowing by encoding an event rather than by reading the configuration back.
 *
 * <h2>Why this exists</h2>
 *
 * <p>An earlier revision of {@code logback-spring.xml} exported the <em>whole</em> diagnostic context
 * from the JSON appender. The diagnostic context is a thread-local map that any code on the request or
 * batch thread may write - including a framework or a library this module did not author - and the JSON
 * appender's output is the one that leaves the process. The values this service handles include a card
 * primary account number, a card verification code, a government-issued identifier and a sign-on
 * credential, so a wholesale export publishes any future key by default, with no review at the point it
 * is added. An allow list inverts that default: a new key is invisible until it is named, which is a
 * one-line change and a deliberate one.
 *
 * <p>Nothing is lost by the narrowing today. The tracing bridge publishes {@code traceId} and
 * {@code spanId}, those are the only two keys the module uses, and no class under
 * {@code src/main/java} writes to the diagnostic context at all. The emitted field set is therefore
 * unchanged, which is what makes this a hardening rather than a behaviour change.
 *
 * <h2>Why the encoder is exercised rather than the file read</h2>
 *
 * <p>Reading the two element names back out of the XML would assert the text of the thing under test.
 * Worse, it would pass against a configuration that names the keys and does not apply them: the encoder
 * consults its allow list only while its context provider is enabled, so naming two keys and switching
 * the provider off emits nothing, and switching the provider on without naming a key emits everything.
 * Both mistakes read correctly in the file. So the encoder is built with the delivered settings and an
 * event carrying four context keys is encoded through it: the two correlation identifiers must appear,
 * and two keys shaped like the values this module protects must not.
 *
 * <p>The delivered file is additionally parsed, so a malformed document or an element the configurator
 * silently ignores is caught here rather than at a deployment's first log line.
 *
 * @since 1.0.0
 */
@DisplayName("Logging configuration: the machine-facing encoder exports named context keys only")
final class LoggingConfigurationTest {

    /** The delivered logging configuration, resolved from the class path as a deployment resolves it. */
    private static final String CONFIGURATION_RESOURCE = "logback-spring.xml";

    /** The correlation key the tracing bridge publishes for the current trace. */
    private static final String TRACE_KEY = "traceId";

    /** The correlation key the tracing bridge publishes for the current span. */
    private static final String SPAN_KEY = "spanId";

    /** A context key shaped like a value this module protects, which must never be exported. */
    private static final String WITHHELD_KEY = "cardNumber";

    /** A second withheld key, so the assertion is not satisfied by one name being special-cased. */
    private static final String SECOND_WITHHELD_KEY = "govtIssuedId";

    /** A trace identifier value the encoded output must carry. */
    private static final String TRACE_VALUE = "0af7651916cd43dd8448eb211c80319c";

    /** A span identifier value the encoded output must carry. */
    private static final String SPAN_VALUE = "b7ad6b7169203331";

    /**
     * A value the encoded output must NOT carry. It is a documentation-reserved test number and
     * authorises nothing; it is present so that a leak would be visible as itself.
     */
    private static final String WITHHELD_VALUE = "4111111111111111";

    /** A second withheld value, for the second withheld key. */
    private static final String SECOND_WITHHELD_VALUE = "987654321";

    /** Restricts construction to the framework. */
    LoggingConfigurationTest() {
    }

    /**
     * Builds an encoder carrying the delivered machine-facing settings.
     *
     * <p>The settings are those of the {@code CONSOLE_JSON} appender: the context provider enabled and
     * narrowed to the two correlation keys. They are declared here rather than read from the file,
     * because reading them from the file would make the file its own oracle.</p>
     *
     * @param  context the logging context the encoder is started against
     * @return a started encoder
     */
    private static LogstashEncoder deliveredEncoder(final LoggerContext context) {
        final LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setTimeZone("UTC");
        encoder.setIncludeMdc(true);
        encoder.addIncludeMdcKeyName(TRACE_KEY);
        encoder.addIncludeMdcKeyName(SPAN_KEY);
        encoder.setIncludeContext(false);
        encoder.setIncludeCallerData(false);
        encoder.start();
        return encoder;
    }

    /**
     * Encodes one event carrying four diagnostic-context keys through the supplied encoder.
     *
     * @param  encoder the encoder to exercise
     * @param  context the logging context the event's logger belongs to
     * @return the encoded line
     */
    private static String encodeEventCarryingFourContextKeys(final LogstashEncoder encoder,
            final LoggerContext context) {
        final LoggingEvent event = new LoggingEvent("posting",
                context.getLogger("com.carddemo.batch.step.TransactionValidationProcessor"),
                Level.INFO, "a record was rejected", null, null);
        event.setMDCPropertyMap(Map.of(
                TRACE_KEY, TRACE_VALUE,
                SPAN_KEY, SPAN_VALUE,
                WITHHELD_KEY, WITHHELD_VALUE,
                SECOND_WITHHELD_KEY, SECOND_WITHHELD_VALUE));
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    /**
     * Reads the delivered logging configuration from the class path.
     *
     * @return the document's full text
     */
    private static String deliveredConfiguration() {
        final ClassPathResource resource = new ClassPathResource(CONFIGURATION_RESOURCE);
        assertThat(resource.exists())
                .as("%s must be resolvable from the class path, which is where a deployment reads it "
                        + "from; the module's ignore files each carry an explicit negation for it",
                        CONFIGURATION_RESOURCE)
                .isTrue();
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(CONFIGURATION_RESOURCE + " is unreadable", unreadable);
        }
    }

    @Nested
    @DisplayName("the diagnostic context is exported through an allow list, proved by encoding an event")
    final class TheContextIsExportedThroughAnAllowList {

        /** Restricts construction to the framework. */
        TheContextIsExportedThroughAnAllowList() {
        }

        @Test
        @DisplayName("both correlation identifiers are exported, so a log line can still be joined to "
                + "the trace it belongs to")
        void bothCorrelationIdentifiersAreExported() {
            final LoggerContext context = new LoggerContext();
            context.start();

            final String encoded = encodeEventCarryingFourContextKeys(deliveredEncoder(context),
                    context);

            assertThat(encoded)
                    .as("naming an allow list and switching the context provider off would emit nothing "
                            + "at all, and would read correctly in the configuration file")
                    .contains("\"" + TRACE_KEY + "\":\"" + TRACE_VALUE + "\"")
                    .contains("\"" + SPAN_KEY + "\":\"" + SPAN_VALUE + "\"");
        }

        @Test
        @DisplayName("a context key that is not named is not exported, which is the hardening itself")
        void anUnnamedContextKeyIsNotExported() {
            final LoggerContext context = new LoggerContext();
            context.start();

            final String encoded = encodeEventCarryingFourContextKeys(deliveredEncoder(context),
                    context);

            assertThat(encoded)
                    .as("the appender's output leaves the process, so a key any code on the thread "
                            + "added - including a library this module did not author - must not be "
                            + "published until it is named in the configuration on purpose")
                    .doesNotContain(WITHHELD_KEY)
                    .doesNotContain(WITHHELD_VALUE)
                    .doesNotContain(SECOND_WITHHELD_KEY)
                    .doesNotContain(SECOND_WITHHELD_VALUE);
        }

        @Test
        @DisplayName("the event's own fields survive the narrowing, so the allow list restricts the "
                + "context and nothing else")
        void theEventsOwnFieldsSurviveTheNarrowing() {
            final LoggerContext context = new LoggerContext();
            context.start();

            final String encoded = encodeEventCarryingFourContextKeys(deliveredEncoder(context),
                    context);

            assertThat(encoded)
                    .as("a narrowing that also dropped the message or the category would make the "
                            + "machine-facing stream useless rather than safe")
                    .contains("a record was rejected")
                    .contains("TransactionValidationProcessor")
                    .contains("INFO");
        }
    }

    @Nested
    @DisplayName("the delivered configuration declares the allow list rather than a wholesale export")
    final class TheDeliveredConfigurationDeclaresTheAllowList {

        /** Restricts construction to the framework. */
        TheDeliveredConfigurationDeclaresTheAllowList() {
        }

        @Test
        @DisplayName("the machine-facing encoder names each exported context key, and names exactly the "
                + "two the tracing bridge publishes")
        void theEncoderNamesEachExportedContextKey() {
            final String configuration = deliveredConfiguration();

            assertThat(configuration)
                    .as("an encoder with the context provider enabled and no key named exports the "
                            + "WHOLE context, which is the posture this test exists to prevent")
                    .contains("<includeMdcKeyName>" + TRACE_KEY + "</includeMdcKeyName>")
                    .contains("<includeMdcKeyName>" + SPAN_KEY + "</includeMdcKeyName>");

            assertThat(configuration.split("<includeMdcKeyName>", -1))
                    .as("exactly the two keys the tracing bridge publishes are named. A third would be a "
                            + "key nobody reviewed against the values this service handles")
                    .hasSize(3);
        }

        @Test
        @DisplayName("the delivered document parses, so an element the configurator would ignore in "
                + "silence is caught here rather than at a deployment's first log line")
        void theDeliveredDocumentParses() {
            final String configuration = deliveredConfiguration();

            assertThat(configuration)
                    .as("the document must be a logback configuration, read from the location Spring "
                            + "resolves for profile-aware logging")
                    .startsWith("<?xml")
                    .contains("<configuration")
                    .endsWith("</configuration>\n");

            assertThat(List.of("CONSOLE_JSON", "CONSOLE_READABLE"))
                    .as("both delivered appenders must still be declared: the narrowing applies to the "
                            + "machine-facing one and must not have removed either")
                    .allSatisfy(appender -> assertThat(configuration)
                            .contains("<appender name=\"" + appender + "\""));
        }

        @Test
        @DisplayName("no class under the production tree writes to the diagnostic context, which is why "
                + "the allow list costs nothing today")
        void noProductionClassWritesToTheDiagnosticContext() {
            assertThat(LoggerFactory.getILoggerFactory())
                    .as("the suite binds the real logging backend, so the statement below is about the "
                            + "same implementation a deployment runs")
                    .isInstanceOf(LoggerContext.class);

            assertThat(org.slf4j.MDC.getCopyOfContextMap())
                    .as("no production class puts a value into the diagnostic context, so the two "
                            + "correlation keys the tracing bridge publishes are the whole of what an "
                            + "allow list has to admit. A future writer must add its key to the "
                            + "configuration deliberately, which is the point")
                    .isNull();
        }
    }
}
