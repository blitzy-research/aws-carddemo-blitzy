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

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BillPaymentRequest}, the request body of legacy transaction {@code CB00}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>Two properties dominate what is checked here, because they are the two the review found missing.
 * The first is that the nested navigation state is validated transitively: the record carries a
 * {@link NavigationContext}, that type declares bounds of its own, and without a cascade those bounds
 * were declared and never evaluated on this path. The second is that the rendering withholds the
 * account identifier and the operator's confirmation answer. Both matter more here than on most
 * requests in this package: a bill payment always pays the whole outstanding balance, so a single
 * rendered line that named the account would name the payer of a specific settled amount, and the
 * confirmation answer is operator input that a rejection must never echo.
 *
 * <p>The remaining assertions hold the four-component inventory and the two published widths, which are
 * the eleven-character account identifier of the map at {@code app/cpy-bms/COBIL00.CPY} line 60 and the
 * one-character confirmation field at line 72.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("BillPaymentRequest :: bill-payment request contract of legacy transaction CB00")
class BillPaymentRequestTest {

    /** The four record components in the order the symbolic map declares their fields. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER =
            List.of("accountId", "confirm", "keyAction", "navigationContext");

    private static final String ACCOUNT_ID = "00000000011";
    private static final String CONFIRM = "Y";
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CB00", "COBIL00C", "CB00", "COBIL00C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", "4111111111111111", "COBIL0A", "COBIL00");
    }

    private static BillPaymentRequest populated() {
        return new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, navigation());
    }

    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                                JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static JsonNode payloadOf(BillPaymentRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    private static List<String> componentNames() {
        return Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotations a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component: none of the annotations
     * involved declares {@code RECORD_COMPONENT} among its targets, so asking the component yields an
     * empty array and an assertion phrased that way would pass without testing anything.
     *
     * @param name the component name
     * @return the annotations present on the backing field
     */
    private static Annotation[] annotationsOn(String name) {
        try {
            return BillPaymentRequest.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return BillPaymentRequest.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static Set<ConstraintViolation<BillPaymentRequest>> violationsOf(
            BillPaymentRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, excluding the delegated navigation
     * state.
     *
     * <p>Truncation is correct here because {@code navigationContext} is the last component, so
     * everything this record contributes precedes it.
     *
     * @param rendered a full rendering
     * @return the leading segment this record contributed
     */
    private static String ownRendering(String rendered) {
        int delegated = rendered.indexOf(", navigationContext=");
        return (delegated < 0) ? rendered : rendered.substring(0, delegated);
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the four components the screen submits, in map order")
        void declaresTheFourComponentsInMapOrder() {
            assertThat(componentNames()).containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("publishes the account identifier width of eleven from the map")
        void publishesTheAccountIdentifierWidth() {
            assertThat(BillPaymentRequest.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(annotationOn("accountId", Size.class).max())
                    .isEqualTo(BillPaymentRequest.ACCOUNT_ID_LENGTH);
        }

        @Test
        @DisplayName("publishes the confirmation width of one from the map")
        void publishesTheConfirmationWidth() {
            assertThat(BillPaymentRequest.CONFIRM_LENGTH).isEqualTo(1);
            assertThat(annotationOn("confirm", Size.class).max())
                    .isEqualTo(BillPaymentRequest.CONFIRM_LENGTH);
        }

        @Test
        @DisplayName("carries the account identifier as text so leading zeros survive")
        void carriesTheAccountIdentifierAsText() {
            assertThat(BillPaymentRequest.class.getRecordComponents()[0].getType())
                    .isEqualTo(String.class);
            assertThat(populated().accountId()).isEqualTo(ACCOUNT_ID).startsWith("0");
        }

        @Test
        @DisplayName("carries the confirmation answer as one character of text, never a boolean")
        void carriesTheConfirmationAnswerAsText() {
            assertThat(BillPaymentRequest.class.getRecordComponents()[1].getType())
                    .isEqualTo(String.class);
            assertThat(new BillPaymentRequest(null, "X", KeyAction.ENTER, null).confirm())
                    .isEqualTo("X");
        }

        @Test
        @DisplayName("rejects an over-long account identifier and an over-long confirmation answer")
        void rejectsOverLongText() {
            BillPaymentRequest tooWide =
                    new BillPaymentRequest("000000000112", "YN", KeyAction.ENTER, null);

            assertThat(violationsOf(tooWide))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("accountId", "confirm");
        }

        @Test
        @DisplayName("accepts a blank and an absent value on both text components")
        void acceptsBlankAndAbsentText() {
            assertThat(violationsOf(new BillPaymentRequest("", "", KeyAction.ENTER, null))).isEmpty();
            assertThat(violationsOf(new BillPaymentRequest(null, null, null, null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("nested state is validated transitively")
    class NestedStateIsValidatedTransitively {

        @Test
        @DisplayName("cascades validation into the navigation state")
        void cascadesIntoTheNavigationState() {
            assertThat(annotationOn("navigationContext", Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("reports a nested bound breach against the nested path")
        void reportsANestedBoundBreach() {
            NavigationContext overWide = new NavigationContext("CB000", "COBIL00C", "CB00",
                    "COBIL00C", "ADMINUSR", "A", NavigationContext.ProgramContext.ENTER, null, null,
                    null, null, null, null, null, null, null);

            assertThat(violationsOf(
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, overWide)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("reports nothing when the nested state is within its own bounds")
        void reportsNothingForValidNestedState() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when the nested state is absent")
        void reportsNothingForAbsentNestedState() {
            assertThat(violationsOf(new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER,
                    null))).isEmpty();
        }

        @Test
        @DisplayName("cascades only into the navigation state, not into the key action")
        void cascadesOnlyIntoTheNavigationState() {
            assertThat(annotationsOn("keyAction")).isEmpty();
            assertThat(annotationsOn("accountId")).hasOnlyElementsOfType(Size.class);
            assertThat(annotationsOn("confirm")).hasOnlyElementsOfType(Size.class);
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("names the type so a diagnostic identifies what it is looking at")
        void namesTheType() {
            assertThat(populated().toString()).startsWith("BillPaymentRequest[").endsWith("]");
        }

        @Test
        @DisplayName("withholds the account identifier")
        void withholdsTheAccountIdentifier() {
            assertThat(ownRendering(populated().toString()))
                    .contains("accountId=" + REDACTED)
                    .doesNotContain(ACCOUNT_ID);
        }

        @Test
        @DisplayName("withholds the operator's confirmation answer")
        void withholdsTheConfirmationAnswer() {
            assertThat(ownRendering(populated().toString())).contains("confirm=" + REDACTED);
        }

        @Test
        @DisplayName("retains the key action, which is one of a fixed vocabulary")
        void retainsTheKeyAction() {
            assertThat(populated().toString()).contains("keyAction=" + KeyAction.ENTER);
        }

        @Test
        @DisplayName("delegates the navigation state to its own withholding rendering")
        void delegatesTheNavigationState() {
            assertThat(populated().toString())
                    .contains("navigationContext=" + navigation())
                    .doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("withholds nothing that was absent, and renders safely when everything is")
        void rendersSafelyWhenEverythingIsAbsent() {
            assertThat(new BillPaymentRequest(null, null, null, null).toString())
                    .isEqualTo("BillPaymentRequest[accountId=" + REDACTED + ", confirm=" + REDACTED
                            + ", keyAction=null, navigationContext=null]");
        }

        @Test
        @DisplayName("changes nothing it transports")
        void changesNothingTransported() {
            BillPaymentRequest request = populated();
            request.toString();

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.confirm()).isEqualTo(CONFIRM);
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext()).isEqualTo(navigation());
        }

        @Test
        @DisplayName("changes nothing on the wire, where both withheld values still travel")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("confirm").asText()).isEqualTo(CONFIRM);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("leaves equality and hashing exactly as the record contract generates them")
        void leavesEqualityAndHashingAlone() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(populated())
                    .isNotEqualTo(new BillPaymentRequest(ACCOUNT_ID, "N", KeyAction.ENTER,
                            navigation()));
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("publishes every component under its own name")
        void publishesEveryComponentUnderItsOwnName() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.fieldNames()).toIterable()
                    .containsExactlyInAnyOrderElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("omits an absent component rather than publishing a null")
        void omitsAnAbsentComponent() throws JsonProcessingException {
            JsonNode payload = payloadOf(new BillPaymentRequest(ACCOUNT_ID, null, null, null));

            assertThat(payload.fieldNames()).toIterable().containsExactly("accountId");
        }

        @Test
        @DisplayName("carries the account identifier as a JSON string, not a number")
        void carriesTheAccountIdentifierAsAString() throws JsonProcessingException {
            assertThat(payloadOf(populated()).get("accountId").isTextual()).isTrue();
        }

        @Test
        @DisplayName("round-trips through the wire unchanged")
        void roundTripsUnchanged() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            assertThat(mapper.readValue(mapper.writeValueAsString(populated()),
                    BillPaymentRequest.class)).isEqualTo(populated());
        }
    }
}
