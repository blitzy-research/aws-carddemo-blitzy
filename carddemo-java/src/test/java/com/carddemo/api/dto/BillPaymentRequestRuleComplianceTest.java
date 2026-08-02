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

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BillPaymentRequest}, the request body of legacy transaction {@code CB00}
 * implemented by {@code app/cbl/COBIL00C.cbl} over screen {@code app/cpy-bms/COBIL00.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Four components and no more.</strong> The bill-payment screen collects exactly two
 * operator values - an account identifier and a one-character confirmation - because the payment
 * amount is never entered. {@code COBIL00C} pays the whole outstanding balance and rejects the
 * request outright when {@code ACCT-CURR-BAL <= ZEROS} at {@code app/cbl/COBIL00C.cbl:198}, so there
 * is no amount field on the screen and there must be none on the request. That absence is asserted
 * here reflectively, because adding an amount component would be a feature the legacy system does
 * not have.
 *
 * <p><strong>Eleven digits of account.</strong> {@code ACCT-ID PIC 9(11)} in
 * {@code app/cpy/CVACT01Y.cpy} fixes the account key at eleven characters, and the request bound
 * follows the record rather than the screen so an over-long value is refused before it can reach a
 * repository lookup.
 */
@DisplayName("BillPaymentRequest - the CB00 bill-payment screen contract")
class BillPaymentRequestRuleComplianceTest {

    /** A representative eleven-digit account identifier drawn from the seeded fixture range. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The placeholder this record's withholding rendering substitutes for a component. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Builds a mapper configured exactly as {@code application.yml} configures the module's mapper.
     *
     * @return a mapper carrying the module's four Jackson settings
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
    private static JsonNode payloadOf(final BillPaymentRequest request)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Builds a request positionally over the record's four components.
     *
     * @param accountId the account whose balance is to be paid
     * @param confirm the one-character confirmation value
     * @param keyAction the attention key the operator pressed
     * @param navigationContext the carried conversation state
     * @return a request carrying exactly those values
     */
    private static BillPaymentRequest aRequest(final String accountId, final String confirm,
            final KeyAction keyAction, final NavigationContext navigationContext) {
        return new BillPaymentRequest(accountId, confirm, keyAction, navigationContext);
    }

    /**
     * Reports whether a named component's accessor carries a declared upper bound.
     *
     * <p>The annotation is read from the accessor rather than from the record component, because
     * {@code jakarta.validation.constraints.Size} does not target {@code RECORD_COMPONENT}. A
     * constraint written on a record component is propagated to the backing field, the accessor and
     * the canonical constructor parameter instead of being retained on the component itself, so
     * {@code RecordComponent.getAnnotation} would report nothing for every component.
     *
     * @param componentName the record component to test
     * @return {@code true} when the accessor declares a {@link Size} bound
     */
    private static boolean declaresAnUpperBound(final String componentName) {
        try {
            return BillPaymentRequest.class.getDeclaredMethod(componentName)
                    .getAnnotation(Size.class) != null;
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("no accessor declared for " + componentName, cause);
        }
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
        final Size size = BillPaymentRequest.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published field widths")
    class ThePublishedFieldWidths {

        @Test
        @DisplayName("the account width is eleven, matching ACCT-ID PIC 9(11) in the account record "
                + "rather than any screen rendering of it")
        void theAccountWidthIsEleven() {
            assertThat(BillPaymentRequest.ACCOUNT_ID_LENGTH).isEqualTo(11);
        }

        @Test
        @DisplayName("the confirmation width is one, so the field can hold Y or N and never a word")
        void theConfirmationWidthIsOne() {
            assertThat(BillPaymentRequest.CONFIRM_LENGTH).isOne();
        }

        @Test
        @DisplayName("each declared bound is the width its named constant publishes, so the two "
                + "cannot drift apart")
        void eachDeclaredBoundMatchesItsNamedConstant() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("accountId"))
                    .isEqualTo(BillPaymentRequest.ACCOUNT_ID_LENGTH);
            assertThat(declaredMaximumLength("confirm"))
                    .isEqualTo(BillPaymentRequest.CONFIRM_LENGTH);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape")
    class TheDeclaredShape {

        @Test
        @DisplayName("the request declares four components, the account, the confirmation, the "
                + "attention key and the carried conversation state")
        void theRequestDeclaresFourComponents() {
            final List<String> declared = Arrays.stream(
                    BillPaymentRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("accountId", "confirm", "keyAction",
                    "navigationContext");
            assertThat(declared).hasSize(4);
        }

        @Test
        @DisplayName("no component carries a payment amount, because the legacy screen pays the whole "
                + "balance and never asks how much")
        void noComponentCarriesAPaymentAmount() {
            final List<String> declared = Arrays.stream(
                    BillPaymentRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).noneSatisfy(name ->
                    assertThat(name.toLowerCase(Locale.ROOT)).contains("amount"));
            assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(component -> component.getType().getName()).toList())
                    .doesNotContain("java.math.BigDecimal");
        }

        @Test
        @DisplayName("exactly two components carry a declared upper bound, and the two that do not "
                + "are the attention key and the conversation state")
        void exactlyTwoComponentsCarryAnUpperBound() {
            final List<String> bounded = Arrays.stream(
                    BillPaymentRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(BillPaymentRequestRuleComplianceTest::declaresAnUpperBound).toList();

            assertThat(bounded).containsExactly("accountId", "confirm");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final BillPaymentRequest request = aRequest(ACCOUNT_ID, "Y", KeyAction.ENTER,
                    NavigationContext.empty());

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a wholly absent request is constructible, because a first screen entry carries "
                + "no operator value at all")
        void aWhollyAbsentRequestIsConstructible() {
            final BillPaymentRequest empty = aRequest(null, null, null, null);

            assertThat(empty.accountId()).isNull();
            assertThat(empty.confirm()).isNull();
            assertThat(empty.keyAction()).isNull();
            assertThat(empty.navigationContext()).isNull();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared validation bounds")
    class TheDeclaredValidationBounds {

        @Test
        @DisplayName("a wholly absent request reports no violation, because every bound is an upper "
                + "bound and nothing is mandatory at this layer")
        void aWhollyAbsentRequestReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequest(null, null, null, null)))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("an eleven-character account is accepted and a twelfth character is refused")
        void anElevenCharacterAccountIsAcceptedAndATwelfthIsRefused() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequest("X".repeat(11), null, null, null))).isEmpty();
                assertThat(factory.getValidator()
                        .validate(aRequest("X".repeat(12), null, null, null))).hasSize(1);
            }
        }

        @Test
        @DisplayName("a one-character confirmation is accepted and a second character is refused")
        void aOneCharacterConfirmationIsAcceptedAndASecondIsRefused() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequest(null, "Y", null, null)))
                        .isEmpty();
                assertThat(factory.getValidator().validate(aRequest(null, "YN", null, null)))
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("an over-long account and an over-long confirmation are reported as two separate "
                + "violations, so a screen can mark both fields at once")
        void twoOverLongValuesAreReportedSeparately() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequest("X".repeat(12), "YN", null, null)))
                        .hasSize(2)
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("accountId", "confirm");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "Y", "N", "y", "n", "1"})
        @DisplayName("the bound admits any single character, because the legacy program tests the "
                + "value rather than the shape and reports an invalid value in its own message")
        void theBoundAdmitsAnySingleCharacter(final String confirmValue) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequest(null, confirmValue, null, null)))
                        .isEmpty();
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two requests built from identical values are equal and share a hash code")
        void twoRequestsBuiltFromIdenticalValuesAreEqual() {
            final BillPaymentRequest first = aRequest(ACCOUNT_ID, "Y", KeyAction.ENTER,
                    NavigationContext.empty());
            final BillPaymentRequest second = aRequest(ACCOUNT_ID, "Y", KeyAction.ENTER,
                    NavigationContext.empty());

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a difference in the confirmation alone makes two requests unequal, so the gate "
                + "value is part of identity")
        void aDifferenceInTheConfirmationMakesTwoRequestsUnequal() {
            assertThat(aRequest(ACCOUNT_ID, "Y", null, null))
                    .isNotEqualTo(aRequest(ACCOUNT_ID, "N", null, null));
        }

        /**
         * The rendering withholds the account identifier and the confirmation, and names the rest.
         *
         * <p>Four components is a small record, and it would be easy to argue that none of them is worth
         * withholding. The account identifier settles that: this request pays a bill against a named
         * account, so a rendering that reproduced it would put an account identifier into every log line
         * that ever handled a payment attempt.</p>
         *
         * <p>The confirmation is withheld with it for a different reason. On its own it is a single
         * character, but it is the difference between a payment that was attempted and one that was
         * carried out, and paired with the identifier in the same rendering it would say that a specific
         * account was debited. Withholding both is what keeps the rendering from being a payment ledger.
         * The attention key and the conversation state survive, because a reader needs them to place the
         * record and neither names an account.</p>
         */
        @Test
        @DisplayName("the rendering withholds the account identifier and the confirmation, so a log line "
                + "cannot become a record of which account was debited")
        void theRenderingWithholdsTheAccountAndTheConfirmation() {
            final String rendered = aRequest(ACCOUNT_ID, "Y", KeyAction.PFK03, null).toString();

            assertThat(rendered)
                    .startsWith("BillPaymentRequest[")
                    .contains("accountId=" + REDACTION_PLACEHOLDER)
                    .contains("confirm=" + REDACTION_PLACEHOLDER)
                    .contains("keyAction=PFK03")
                    .contains("navigationContext=null")
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain("confirm=Y");
        }

        @Test
        @DisplayName("the withholding is unconditional, so a request carrying neither still renders both "
                + "as the placeholder rather than betraying which were supplied")
        void theWithholdingIsUnconditional() {
            assertThat(aRequest(null, null, KeyAction.PFK03, null).toString())
                    .contains("accountId=" + REDACTION_PLACEHOLDER)
                    .contains("confirm=" + REDACTION_PLACEHOLDER)
                    .doesNotContain("accountId=null")
                    .doesNotContain("confirm=null");
        }

        @Test
        @DisplayName("the payload omits every absent component")
        void thePayloadOmitsEveryAbsentComponent() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aRequest(ACCOUNT_ID, null, null, null));

            assertThat(payload.has("accountId")).isTrue();
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.has("confirm")).isFalse();
            assertThat(payload.has("keyAction")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("the attention key travels as its enum name rather than as its five-character "
                + "3270 attention identifier, which keeps a trailing-space AID off the wire")
        void theAttentionKeyTravelsAsItsEnumName(final KeyAction keyAction)
                throws JsonProcessingException {
            assertThat(payloadOf(aRequest(null, null, keyAction, null)).get("keyAction").asText())
                    .isEqualTo(keyAction.name());
        }

        @Test
        @DisplayName("a request survives a round trip through the module-equivalent mapper unchanged")
        void aRequestSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final BillPaymentRequest original = aRequest(ACCOUNT_ID, "Y", KeyAction.PFK04,
                    NavigationContext.empty().withReEntry());

            assertThat(mapper.readValue(mapper.writeValueAsString(original),
                    BillPaymentRequest.class)).isEqualTo(original);
        }
    }
}
