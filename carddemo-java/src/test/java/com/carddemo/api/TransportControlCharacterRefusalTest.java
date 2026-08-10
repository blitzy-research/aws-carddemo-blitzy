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
package com.carddemo.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.carddemo.api.dto.AccountUpdateRequest;
import com.carddemo.api.dto.ErrorResponse;
import com.carddemo.api.dto.TransactionAddRequest;
import com.carddemo.config.WebMvcConfig;
import com.carddemo.util.StatementHtmlTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The transport rule that refuses a control byte in an inbound text value, asserted at the boundary
 * where the value arrives rather than at the record writer that used to meet it.
 *
 * <h2>The exposure this file pins closed</h2>
 *
 * <p>Every screen component on this surface is external text of a declared width, and the width was
 * the only property checked. A body carrying {@code VALID}, a newline and {@code POISON} in a
 * transaction description or an address line therefore bound, satisfied every field edit, and reached
 * the store - a newline being a perfectly ordinary character to a width check. The byte was met much
 * later and somewhere else entirely: the statement generator moves a stored field into a fixed-length
 * record and refuses a control byte there, because one embedded newline splits a hundred-byte record
 * in two and destroys the byte-parity contract of the whole artefact. A value accepted online
 * therefore failed a batch run afterwards, at a point where the only remedy is to correct stored
 * data.
 *
 * <h2>Why the rule is on the reader and not on a field</h2>
 *
 * <p>Two components of the account-update contract - the middle name and the second address line -
 * are required to carry <em>no</em> constraint, because the program they reproduce codes no edit for
 * either and any constraint would refuse input the original accepted. A guard written as a per-field
 * annotation could not cover those two without breaking that requirement, and one that skipped them
 * would leave the exposure open on precisely the components with no other check. This file therefore
 * asserts the rule where it is stated: on the mapper's text reader, uniformly, for every body.
 *
 * <p>It also asserts what the rule is <strong>not</strong>. Printable markup still binds and is
 * still emitted byte for byte, because the HTML statement is compared byte for byte against the
 * emitting program's own output and escaping it would fail that comparison. The residual is a
 * property of the legacy design; what is closed here is the control-byte path, which is the half that
 * corrupts a fixed-length record and fails a batch.
 *
 * <h2>Independent expectations</h2>
 *
 * <p>Every expected value below is written out here. No expectation is produced by calling the code
 * under test, and the two unannotated components are named by their contract property names rather
 * than read from any annotation, so a constraint quietly added to either would not be able to make
 * these assertions pass.
 *
 * <p>Provenance: legacy estate at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19, read as read-only
 * reference.
 */
@DisplayName("Transport control characters :: refused where the value arrives, not where it is written")
class TransportControlCharacterRefusalTest {

    /**
     * The deployed reader graph: the module's configuration file, the auto-configured mapper, and the
     * one class that contributes the text rule.
     *
     * <p>The configuration class has to be registered. The rule has no representation in
     * {@code application.yml} - the framework publishes no property for a per-type deserializer - so a
     * slice that omitted the class would be asserting against a mapper strictly more permissive than
     * the one every endpoint uses.
     */
    private static final ApplicationContextRunner DEPLOYED_READER = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(WebMvcConfig.class)
            // WebMvcConfig's body-limit registration now counts a refusal, so this slice needs a
            // registry. Supplied here because the runner registers no metrics auto-configuration,
            // whereas the running application always has one.
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    /** The description a caller submits, carrying a line terminator in the middle of legitimate text. */
    private static final String SPLIT_DESCRIPTION = "VALID\nPOISON";

    /** Printable markup, which must continue to bind because the emitted record reproduces it. */
    private static final String PRINTABLE_MARKUP = "<b>Purchase</b> & Zulauf-O'Keefe";

    /** The fragment a refusal must never place in a response body or a diagnostic. */
    private static final String POISON_FRAGMENT = "POISON";

    /** Contract property name of the transaction description. */
    private static final String DESCRIPTION_PROPERTY = "description";

    /** Contract property name of the account-update component that carries no constraint. */
    private static final String MIDDLE_NAME_PROPERTY = "middleName";

    /** Contract property name of the second account-update component that carries no constraint. */
    private static final String ADDRESS_LINE_2_PROPERTY = "addressLine2";

    /** Address of the probe boundary these specifications post to. */
    private static final String PROBE_PATH = "/api/probe/transport";

    /** Width of one HTML statement record, written out rather than read from the class under test. */
    private static final int HTML_RECORD_WIDTH = 100;

    /** Creates the specification. */
    TransportControlCharacterRefusalTest() {
        // Intentionally empty: every slice builds what it needs.
    }

    /**
     * Builds a body carrying one value for one property of the transaction-add contract.
     *
     * @param  property the contract property to carry the value
     * @param  value    the value, inserted with JSON escaping so the transport carries the byte itself
     * @return the request body
     */
    private static String bodyWith(final String property, final String value) {
        final StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int position = 0; position < value.length(); position++) {
            final char character = value.charAt(position);
            if (character < ' ' || character == '\u007F'
                    || character >= '\u0080' && character <= '\u009F') {
                // The locale is named because the module admits no format that takes its digits from
                // the ambient default; a locale-dependent escape would make the body under test differ
                // between hosts.
                escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else if (character == '"' || character == '\\') {
                escaped.append('\\').append(character);
            } else {
                escaped.append(character);
            }
        }
        return "{\"" + property + "\":\"" + escaped + "\"}";
    }

    /**
     * A boundary over the real transaction-add contract, the real advice and one nominated mapper.
     *
     * <p>The handler records what reached it and answers nothing else, so "the value never reached a
     * service" is observable rather than inferred: a service could not have been called by a handler
     * that was not called either.
     *
     * @param  mapper   the mapper the converter reads bodies with
     * @param  received where a handler that runs records the body it received
     * @return the harness
     */
    private static MockMvc boundaryReading(final ObjectMapper mapper,
            final AtomicReference<TransactionAddRequest> received) {
        return MockMvcBuilders.standaloneSetup(new TransportProbeController(received))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    /** A boundary that binds the real transaction-add contract and records what it received. */
    @RestController
    static final class TransportProbeController {

        /** Where a handler that runs records the body it received. */
        private final AtomicReference<TransactionAddRequest> received;

        /**
         * @param received sink for the received body
         */
        TransportProbeController(final AtomicReference<TransactionAddRequest> received) {
            this.received = received;
        }

        /**
         * Records one bound body and answers without doing anything else.
         *
         * @param  request the bound contract
         * @return the empty acknowledgement
         */
        @PostMapping(path = PROBE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        String bind(@RequestBody final TransactionAddRequest request) {
            this.received.set(request);
            return "{}";
        }
    }

    @Nested
    @DisplayName("what the reader refuses")
    class Refusals {

        /** Creates the slice. */
        Refusals() {
            // Intentionally empty.
        }

        @ParameterizedTest(name = "code point {0}")
        @ValueSource(chars = {'\u0000', '\u0001', '\u0007', '\b', '\t', '\n', '\u000B', '\f', '\r',
            '\u001B', '\u001F', '\u007F', '\u0080', '\u0085', '\u009F'})
        @DisplayName("every C0 code, the delete character and every C1 code is refused, because none of "
                + "them is a character a 3270 field could ever have carried")
        void everyTransportControlCodeIsRefused(final char controlCode) {
            DEPLOYED_READER.run(context -> {
                assertThat(context).hasNotFailed();
                final ObjectMapper mapper = context.getBean(ObjectMapper.class);

                assertThat(refusedBy(mapper, bodyWith(DESCRIPTION_PROPERTY, "A" + controlCode + "B")))
                        .as("a control code embedded in otherwise legitimate text must be refused at "
                                + "the reader; accepted here, it is met by the fixed-length record "
                                + "writer hours later and fails a batch run instead")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("the two components that are required to carry no constraint are covered too, which "
                + "is the whole reason the rule is on the reader rather than on a field")
        void theUnconstrainedComponentsAreCoveredByTheTransportRule() {
            DEPLOYED_READER.run(context -> {
                assertThat(context).hasNotFailed();
                final ObjectMapper mapper = context.getBean(ObjectMapper.class);

                for (final String property : List.of(MIDDLE_NAME_PROPERTY, ADDRESS_LINE_2_PROPERTY)) {
                    boolean refused;
                    try {
                        mapper.readValue(bodyWith(property, SPLIT_DESCRIPTION),
                                AccountUpdateRequest.class);
                        refused = false;
                    } catch (final Exception refusal) {
                        refused = true;
                    }
                    assertThat(refused)
                            .as("%s carries no declarative constraint and must not acquire one, so the "
                                    + "transport rule is the only thing standing between a control byte "
                                    + "and the store", property)
                            .isTrue();
                }
            });
        }

        @Test
        @DisplayName("a refused body is answered 400 naming the offending declared property, and the "
                + "rejected value appears nowhere in the response")
        void aRefusedBodyNamesThePropertyAndEchoesNothing() throws Exception {
            DEPLOYED_READER.run(context -> {
                final ObjectMapper mapper = context.getBean(ObjectMapper.class);
                final AtomicReference<TransactionAddRequest> received = new AtomicReference<>();

                final MvcResult result = boundaryReading(mapper, received)
                        .perform(post(PROBE_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyWith(DESCRIPTION_PROPERTY, SPLIT_DESCRIPTION)
                                        .getBytes(StandardCharsets.UTF_8)))
                        .andReturn();
                final String body = result.getResponse().getContentAsString();

                assertThat(result.getResponse().getStatus())
                        .as("a body the reader refused is a bad request")
                        .isEqualTo(400);
                assertThat(body)
                        .as("the rejected value must not be echoed back, because a response body is a "
                                + "thing that gets logged")
                        .doesNotContain(POISON_FRAGMENT);
                final ErrorResponse refusal = mapper.readValue(body, ErrorResponse.class);
                assertThat(namesOf(refusal))
                        .as("the offending declared property is named, so a caller submitting a "
                                + "forty-three component screen can find the one value at fault")
                        .contains(DESCRIPTION_PROPERTY);
                assertThat(refusal.fieldErrors())
                        .allSatisfy(field -> assertThat(field.state())
                                .as("a value that was present and rejected is INVALID, never MISSING")
                                .isEqualTo(ErrorResponse.FieldState.INVALID));
                assertThat(received.get())
                        .as("the handler was never entered, so no service and no store could have been "
                                + "reached by this body")
                        .isNull();
            });
        }

        /**
         * Reports whether one body is refused by one mapper against the transaction-add contract.
         *
         * @param  mapper the mapper to read with
         * @param  body   the body to read
         * @return {@code true} when the read was refused
         */
        private boolean refusedBy(final ObjectMapper mapper, final String body) {
            try {
                mapper.readValue(body, TransactionAddRequest.class);
                return false;
            } catch (final Exception refusal) {
                return true;
            }
        }

        /**
         * Names the properties a refusal reported.
         *
         * @param  refusal the parsed refusal
         * @return the reported property names
         */
        private List<String> namesOf(final ErrorResponse refusal) {
            final List<String> names = new ArrayList<>();
            for (final ErrorResponse.FieldError field : refusal.fieldErrors()) {
                names.add(field.fieldName());
            }
            return names;
        }
    }

    @Nested
    @DisplayName("what the reader deliberately leaves alone")
    class Acceptances {

        /** Creates the slice. */
        Acceptances() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("printable markup binds unchanged, because the emitted statement record reproduces "
                + "it byte for byte and an escaped value would fail the byte comparison")
        void printableMarkupBindsUnchanged() {
            DEPLOYED_READER.run(context -> {
                final ObjectMapper mapper = context.getBean(ObjectMapper.class);

                final TransactionAddRequest bound = mapper.readValue(
                        bodyWith(DESCRIPTION_PROPERTY, PRINTABLE_MARKUP), TransactionAddRequest.class);

                assertThat(bound.description())
                        .as("not trimmed, not folded, not escaped and not re-encoded: the reader makes "
                                + "one decision, which is to bind or to refuse")
                        .isEqualTo(PRINTABLE_MARKUP);
            });
        }

        @Test
        @DisplayName("an absent property, an explicit null, an empty value and a blank value all bind as "
                + "before, so the emptiness cascades that own the screen messages are still reached")
        void absentNullEmptyAndBlankValuesAreUntouched() {
            DEPLOYED_READER.run(context -> {
                final ObjectMapper mapper = context.getBean(ObjectMapper.class);

                assertThat(mapper.readValue("{}", TransactionAddRequest.class).description())
                        .as("an absent property is still absent")
                        .isNull();
                assertThat(mapper.readValue("{\"" + DESCRIPTION_PROPERTY + "\":null}",
                        TransactionAddRequest.class).description())
                        .as("an explicit null is still null")
                        .isNull();
                assertThat(mapper.readValue(bodyWith(DESCRIPTION_PROPERTY, ""),
                        TransactionAddRequest.class).description())
                        .as("an empty value is still empty")
                        .isEmpty();
                assertThat(mapper.readValue(bodyWith(DESCRIPTION_PROPERTY, "   "),
                        TransactionAddRequest.class).description())
                        .as("a blank screen field is still a blank screen field")
                        .isEqualTo("   ");
            });
        }

        @Test
        @DisplayName("the four load-bearing serialisation settings survive the added reader, because it "
                + "is contributed to the builder the auto-configuration already owns")
        void theLoadBearingSerialisationSettingsSurvive() {
            DEPLOYED_READER.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ObjectMapper.class);
                final ObjectMapper mapper = context.getBean(ObjectMapper.class);

                final String rendered = mapper.writeValueAsString(
                        new BigDecimalCarrier(new BigDecimal("0.00"), null));

                assertThat(rendered)
                        .as("a scale-two decimal must render in plain notation, or every monetary "
                                + "amount on the wire is a corrupted value")
                        .contains("0.00")
                        .doesNotContain("E")
                        .as("and a null-valued property must stay omitted")
                        .doesNotContain("absent");
                final JsonNode reparsed = mapper.readTree(rendered);
                assertThat(reparsed.has("absent"))
                        .as("omission of null-valued properties is part of the response shape")
                        .isFalse();
            });
        }

        /**
         * @param amount a scale-two decimal, which must render in plain notation
         * @param absent a null-valued property, which must be omitted
         */
        record BigDecimalCarrier(BigDecimal amount, String absent) {
        }
    }

    @Nested
    @DisplayName("the record writer the online path can no longer reach")
    class TheLateGuard {

        /** Creates the slice. */
        TheLateGuard() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("the fixed-length composer still refuses the same value, so the late guard remains "
                + "in place for any path that is not this boundary")
        void theFixedLengthComposerStillRefusesTheSameValue() {
            boolean refused;
            try {
                StatementHtmlTemplates.transactionWorkLine(SPLIT_DESCRIPTION);
                refused = false;
            } catch (final IllegalArgumentException refusal) {
                refused = true;
            }

            assertThat(refused)
                    .as("the composer's own refusal is not withdrawn by the boundary rule: a stored "
                            + "value predating the rule, or a value arriving by some other route, is "
                            + "still stopped before it can split a record")
                    .isTrue();
        }

        @Test
        @DisplayName("and it emits printable markup byte for byte at the declared width, which is the "
                + "parity property the boundary rule was written not to disturb")
        void printableMarkupIsEmittedByteForByteAtTheDeclaredWidth() {
            final String record = StatementHtmlTemplates.transactionWorkLine(PRINTABLE_MARKUP);

            assertThat(record.getBytes(StandardCharsets.US_ASCII).length)
                    .as("one HTML statement record is a fixed hundred bytes")
                    .isEqualTo(HTML_RECORD_WIDTH);
            assertThat(record)
                    .as("the value reaches the record exactly as it arrived, angle brackets, ampersand "
                            + "and apostrophe included, because the expected-output fixture holds those "
                            + "bytes and a character reference would shift every byte after it")
                    .contains(PRINTABLE_MARKUP);
        }
    }
}
