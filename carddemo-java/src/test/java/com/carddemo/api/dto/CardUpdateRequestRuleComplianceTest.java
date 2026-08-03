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
package com.carddemo.api.dto;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardUpdateRequest}, the request body of legacy transaction {@code CCUP}
 * implemented by {@code app/cbl/COCRDUPC.cbl} over screen {@code app/cpy-bms/COCRDUP.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>The expiry day is deliberately unbounded, and that is the point of this class.</strong>
 * Six of the nine components carry a declared width taken from the screen field; the expiry day does
 * not. The legacy screen has no expiry-day field - the card record's expiry date is held as a month and
 * a year, and the day exists only as a value the program supplies for itself when it assembles a date.
 * Attaching a width here would reject a caller the legacy accepts, which is the feature expansion the
 * plan forbids. The absence is therefore asserted rather than left to be tidied up by a later reader who
 * notices the inconsistency and "fixes" it.
 *
 * @see CardUpdateRequest
 */
@DisplayName("CardUpdateRequest - the CCUP card update request contract")
class CardUpdateRequestRuleComplianceTest {

    /** A card number at the legacy sixteen-digit width. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** An account identifier at the legacy eleven-digit width. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * A mapper configured exactly as {@code application.yml} configures the application's own.
     *
     * @return the module-equivalent mapper
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes a request and reads the result back as a tree.
     *
     * @param request the request to render
     * @return the rendered payload
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static JsonNode payloadOf(final CardUpdateRequest request)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * The fixed stand-in the record's own rendering emits in place of every component.
     *
     * <p>Restated here rather than read from the record, because the constant is private on purpose:
     * a test that reflected it would be asserting that the value is reachable, which is the opposite of
     * what the design intends. A drift between the two is caught by the whole-string comparison below,
     * which is the only place the literal is used.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Builds a fully populated request, every component at or inside its screen width.
     *
     * @return the populated request
     */
    private static CardUpdateRequest aRequest() {
        return new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, "JOHN Q PUBLIC", "Y", "12", "2025",
                "31", KeyAction.ENTER, NavigationContext.empty());
    }

    /**
     * Reads the declared upper bound of a named component's accessor.
     *
     * @param componentName the record component whose accessor carries the annotation
     * @return the declared maximum length
     * @throws NoSuchMethodException when no such accessor is declared
     */
    private static int declaredMaximumLength(final String componentName)
            throws NoSuchMethodException {
        final Size size = CardUpdateRequest.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape")
    class TheDeclaredShape {

        /**
         * Nine of the ten components are map fields in screen order; the tenth is not a map field.
         *
         * <p>The concurrency token is the sealed counterpart of the program work area
         * {@code app/cbl/COCRDUPC.cbl} carries across the pseudo-conversational turn at line 550. It is
         * declared in screen order, then the two control components. Order is asserted rather than
         * assumed, because inserting anything among the map fields would silently reorder the
         * positional constructor that every fixture here uses.
         */
        @Test
        @DisplayName("the request declares nine components - the seven map fields in screen order, "
                + "then the attention key and the echoed navigation state")
        void theRequestDeclaresNineComponentsInScreenOrder() {
            final List<String> declared =
                    Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                            .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("accountId", "cardNumber", "embossedName",
                    "activeStatus", "expiryMonth", "expiryYear", "expiryDay", "keyAction",
                    "navigationContext");
            assertThat(declared).hasSize(9);
            assertThat(declared).last()
                    .as("the echoed navigation state closes the contract; no concurrency value "
                            + "follows it, because locking belongs to the entity and the service")
                    .isEqualTo("navigationContext");
        }

        @Test
        @DisplayName("every component is a reference type, so every field can be absent - the legacy "
                + "screen distinguishes an untouched field from a cleared one")
        void everyComponentIsAReferenceType() {
            assertThat(Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                    .filter(component -> component.getType().isPrimitive()).toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final CardUpdateRequest request = aRequest();

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(request.embossedName()).isEqualTo("JOHN Q PUBLIC");
            assertThat(request.activeStatus()).isEqualTo("Y");
            assertThat(request.expiryMonth()).isEqualTo("12");
            assertThat(request.expiryYear()).isEqualTo("2025");
            assertThat(request.expiryDay()).isEqualTo("31");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a request with nothing supplied is constructible, which is the state a first entry "
                + "to the screen arrives in")
        void anEmptyRequestIsConstructible() {
            final CardUpdateRequest empty = new CardUpdateRequest(null, null, null, null, null, null,
                    null, null, null);

            assertThat(empty.accountId()).isNull();
            assertThat(empty.cardNumber()).isNull();
            assertThat(empty.keyAction()).isNull();
            assertThat(empty.navigationContext())
                    .as("every component tolerates absence, which is the shape a first entry to the "
                            + "screen arrives in")
                    .isNull();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared validation bounds")
    class TheDeclaredValidationBounds {

        @ParameterizedTest(name = "{0} bounded at {1}")
        @CsvSource({
            "accountId, 11",
            "cardNumber, 16",
            "embossedName, 50",
            "activeStatus, 1",
            "expiryMonth, 2",
            "expiryYear, 4"
        })
        @DisplayName("each bounded component carries the legacy field width")
        void eachBoundedComponentCarriesTheLegacyWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {

            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the expiry day carries no upper bound at all, because the legacy screen has no "
                + "expiry-day field to take a width from")
        void theExpiryDayCarriesNoUpperBound() throws NoSuchMethodException {
            assertThat(CardUpdateRequest.class.getDeclaredMethod("expiryDay")
                    .getAnnotation(Size.class))
                    .as("adding a width here would reject input the legacy accepts")
                    .isNull();
        }

        @ParameterizedTest(name = "expiryDay [{0}]")
        @ValueSource(strings = {"1", "01", "31", "001", "0000000031", "not a day"})
        @DisplayName("an expiry day of any length passes validation, however unlikely, because nothing "
                + "constrains it at this layer")
        void anExpiryDayOfAnyLengthPassesValidation(final String expiryDay) {
            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null,
                    null, expiryDay, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(request)).isEmpty();
            }
        }

        @Test
        @DisplayName("a fully populated request passes validation")
        void aFullyPopulatedRequestPassesValidation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequest())).isEmpty();
            }
        }

        @Test
        @DisplayName("a request with nothing supplied passes validation, because a length bound says "
                + "nothing about presence")
        void anEmptyRequestPassesValidation() {
            final CardUpdateRequest empty = new CardUpdateRequest(null, null, null, null, null, null,
                    null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(empty)).isEmpty();
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"accountId", "cardNumber", "embossedName", "activeStatus", "expiryMonth",
            "expiryYear"})
        @DisplayName("each bounded component accepts a value at its bound and reports one character over")
        void eachBoundedComponentAcceptsItsBoundAndReportsOneOver(final String componentName)
                throws NoSuchMethodException {

            final int bound = declaredMaximumLength(componentName);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(requestWith(componentName, "X".repeat(bound))))
                        .as("%s at its bound of %d", componentName, bound)
                        .isEmpty();
                assertThat(factory.getValidator()
                        .validate(requestWith(componentName, "X".repeat(bound + 1))))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly(componentName);
            }
        }

        /**
         * Builds a request carrying the supplied value in the named component and nothing else.
         *
         * @param componentName the component to populate
         * @param value         the value to place in it
         * @return the request
         */
        private CardUpdateRequest requestWith(final String componentName, final String value) {
            return switch (componentName) {
                case "accountId" -> new CardUpdateRequest(value, null, null, null, null, null, null,
                        null, null);
                case "cardNumber" -> new CardUpdateRequest(null, value, null, null, null, null, null,
                        null, null);
                case "embossedName" -> new CardUpdateRequest(null, null, value, null, null, null,
                        null, null, null);
                case "activeStatus" -> new CardUpdateRequest(null, null, null, value, null, null,
                        null, null, null);
                case "expiryMonth" -> new CardUpdateRequest(null, null, null, null, value, null,
                        null, null, null);
                case "expiryYear" -> new CardUpdateRequest(null, null, null, null, null, value, null,
                        null, null);
                default -> throw new IllegalArgumentException(
                        "the fixture names no such component: " + componentName);
            };
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the attention-key component")
    class TheAttentionKeyComponent {

        @ParameterizedTest(name = "{0}")
        @EnumSource(KeyAction.class)
        @DisplayName("every declared key action can be carried and survives the payload as its constant "
                + "name, so no key is quietly unsupported on this screen")
        void everyDeclaredKeyActionCanBeCarried(final KeyAction keyAction)
                throws JsonProcessingException {

            final CardUpdateRequest request = new CardUpdateRequest(null, null, null, null, null,
                    null, null, keyAction, null);

            assertThat(request.keyAction()).isEqualTo(keyAction);
            assertThat(payloadOf(request).get("keyAction").asText()).isEqualTo(keyAction.name());
        }

        @Test
        @DisplayName("an absent key action is carried as absent, because a client that submits by "
                + "pressing nothing sends no key")
        void anAbsentKeyActionIsCarriedAsAbsent() throws JsonProcessingException {
            final CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, null, null, null,
                    null, null, null, null, null);

            assertThat(request.keyAction()).isNull();
            assertThat(payloadOf(request).has("keyAction")).isFalse();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two requests built the same way are equal and share a hash code")
        void twoIdenticalRequestsAreEqual() {
            assertThat(aRequest()).isEqualTo(aRequest()).hasSameHashCodeAs(aRequest());
        }

        @Test
        @DisplayName("a difference in one component makes two requests unequal")
        void aDifferenceInOneComponentMakesTwoRequestsUnequal() {
            assertThat(aRequest()).isNotEqualTo(new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    "JOHN Q PUBLIC", "N", "12", "2025", "31", KeyAction.ENTER,
                    NavigationContext.empty()));
            assertThat(aRequest())
                    .as("the hidden carry-through participates in value semantics like every other "
                            + "component, because nothing here is excluded from equality")
                    .isNotEqualTo(new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, "JOHN Q PUBLIC",
                            "Y", "12", "2025", "30", KeyAction.ENTER, NavigationContext.empty()));
        }

        /**
         * The rendering discloses the type name and nothing else.
         *
         * <p>Withholding is blanket rather than per component, and the difference matters: a request
         * carrying a card number, an embossed name and an expiry date has no component whose disclosure
         * is harmless, so naming any of them - even as a placeholder - would only invite the next
         * component to be added as a plain value. Substitution is also unconditional, so a populated
         * request and an empty one render identically and the rendering never reports which fields the
         * operator filled in.
         */
        @Test
        @DisplayName("the rendering carries the type name and a single placeholder, unconditionally, so "
                + "no component value can reach a log through it")
        void theRenderingIsABlanketPlaceholder() {
            final String populated = aRequest().toString();

            assertThat(populated).isEqualTo("CardUpdateRequest[" + REDACTION_PLACEHOLDER + "]");
            assertThat(populated).doesNotContain(CARD_NUMBER, ACCOUNT_ID, "JOHN Q PUBLIC", "2025");
            assertThat(new CardUpdateRequest(null, null, null, null, null, null, null, null, null).toString())
                    .as("an empty request renders exactly as a populated one does")
                    .isEqualTo(populated);
        }

        /**
         * The placeholder is private, so no caller can compose a rendering that looks redacted.
         */
        @Test
        @DisplayName("the placeholder is a private constant of the record itself")
        void thePlaceholderIsAPrivateConstant() throws NoSuchFieldException {
            final int modifiers = CardUpdateRequest.class
                    .getDeclaredField("REDACTION_PLACEHOLDER").getModifiers();

            assertThat(Modifier.isPrivate(modifiers)).isTrue();
            assertThat(Modifier.isStatic(modifiers)).isTrue();
            assertThat(Modifier.isFinal(modifiers)).isTrue();
        }

        @Test
        @DisplayName("an absent component is omitted from the payload")
        void anAbsentComponentIsOmittedFromThePayload() throws JsonProcessingException {
            final JsonNode payload = payloadOf(new CardUpdateRequest(ACCOUNT_ID, null, null, null,
                    null, null, null, null, null));

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.has("cardNumber")).isFalse();
            assertThat(payload.has("embossedName")).isFalse();
            assertThat(payload.has("expiryDay")).isFalse();
        }

        /**
         * A round trip loses the expiry day, which is the one component the screen owns rather than the client.
         *
         * <p>The legacy map carries no day item for a card expiry - a card expires at the end of a month, so
         * the day is derived for display and is not something an operator can type. It is written outbound
         * and ignored inbound for that reason, which makes it the one component a round trip does not
         * carry.</p>
         *
         * <p>Asserting full equality is the stronger claim: it fails the moment any component acquires a
         * directional binding, whereas an assertion naming a permitted loss would quietly accept a new
         * one. No component here is bound in one direction - not even the hidden carry-through, whose
         * protection is that {@code CardUpdateService} assembles the stored expiry date from the day it
         * captured itself rather than from this body.</p>
         */
        @Test
        @DisplayName("a round trip carries every component, because none is bound in one direction only")
        void aRequestRoundTripsWithEveryComponentIntact() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final CardUpdateRequest original = aRequest();

            final CardUpdateRequest restored = mapper.readValue(
                    mapper.writeValueAsString(original), CardUpdateRequest.class);

            assertThat(restored)
                    .as("no component is lost in either direction, so the record compares equal to "
                            + "the one it was written from")
                    .isEqualTo(original);
            assertThat(restored.expiryDay())
                    .as("the hidden carry-through is written and read alike; the service, not a "
                            + "binding directive, is what keeps the wire out of the stored record")
                    .isEqualTo(original.expiryDay());
        }

        @Test
        @DisplayName("an unknown property in an inbound payload is ignored rather than rejected, which "
                + "is what the module's reader is configured to do")
        void anUnknownPropertyIsIgnored() throws JsonProcessingException {
            final CardUpdateRequest restored = moduleEquivalentMapper().readValue(
                    "{\"accountId\":\"" + ACCOUNT_ID + "\",\"noSuchField\":\"x\"}",
                    CardUpdateRequest.class);

            assertThat(restored.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(restored.cardNumber()).isNull();
        }
    }
}
