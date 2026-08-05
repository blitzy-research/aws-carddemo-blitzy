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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.domain.enums.KeyAction;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link CardDetailRequest}, the body submitted to legacy transaction
 * {@code CCDL}.
 */
@DisplayName("CardDetailRequest :: the body-only CCDL turn")
class CardDetailRequestTest {

    private static final String ACCOUNT_ID = "00000000011";

    private static final String CARD_NUMBER = "4111111111111111";

    private static NavigationContext navigation() {
        return new NavigationContext(
                "CCLI", "COCRDLIC", "CCDL", "COCRDSLC", "FORGED01", "A",
                NavigationContext.ProgramContext.REENTER, null, null, null, null,
                ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDLIA", "COCRDLI");
    }

    @Nested
    @DisplayName("declared shape")
    class DeclaredShape {

        @Test
        @DisplayName("the four components are declared in transmitted screen order")
        void componentsAreInScreenOrder() {
            assertThat(Arrays.stream(CardDetailRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .containsExactly(
                            "accountIdFilter", "cardNumberFilter", "keyAction",
                            "navigationContext");
        }

        @Test
        @DisplayName("the two filters carry their own legacy widths")
        void filtersCarryTheirLegacyWidths() throws NoSuchMethodException {
            final Size accountBound = CardDetailRequest.class
                    .getDeclaredMethod("accountIdFilter")
                    .getAnnotation(Size.class);
            final Size cardBound = CardDetailRequest.class
                    .getDeclaredMethod("cardNumberFilter")
                    .getAnnotation(Size.class);

            assertThat(CardDetailRequest.ACCOUNT_ID_FILTER_LENGTH).isEqualTo(11);
            assertThat(CardDetailRequest.CARD_NUMBER_FILTER_LENGTH).isEqualTo(16);
            assertThat(accountBound).isNotNull();
            assertThat(accountBound.max()).isEqualTo(11);
            assertThat(cardBound).isNotNull();
            assertThat(cardBound.max()).isEqualTo(16);
        }

        @Test
        @DisplayName("the attention key is typed and navigation validation cascades")
        void typedKeyAndNavigationCascadeAreDeclared() throws NoSuchMethodException {
            assertThat(CardDetailRequest.class.getDeclaredMethod("keyAction").getReturnType())
                    .isEqualTo(KeyAction.class);
            assertThat(CardDetailRequest.class.getDeclaredMethod("navigationContext")
                    .getAnnotation(Valid.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("values and validation")
    class ValuesAndValidation {

        @Test
        @DisplayName("all supplied values cross unchanged")
        void suppliedValuesCrossUnchanged() {
            final NavigationContext state = navigation();
            final CardDetailRequest request =
                    new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER, KeyAction.ENTER, state);

            assertThat(request.accountIdFilter()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumberFilter()).isEqualTo(CARD_NUMBER);
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext()).isSameAs(state);
        }

        @Test
        @DisplayName("an absent body becomes the fully empty first-entry turn")
        void emptyFactoryProducesFirstEntry() {
            assertThat(CardDetailRequest.empty())
                    .isEqualTo(new CardDetailRequest(null, null, null, null));
        }

        @Test
        @DisplayName("over-wide filters are reported against their own components")
        void overWideFiltersAreReported() {
            final CardDetailRequest request = new CardDetailRequest(
                    "0".repeat(CardDetailRequest.ACCOUNT_ID_FILTER_LENGTH + 1),
                    "4".repeat(CardDetailRequest.CARD_NUMBER_FILTER_LENGTH + 1),
                    KeyAction.ENTER,
                    null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(request))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("accountIdFilter", "cardNumberFilter");
            }
        }

        @Test
        @DisplayName("values at the exact widths and wholly absent values are accepted")
        void exactWidthsAndAbsenceAreAccepted() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(
                        new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER, null, null))).isEmpty();
                assertThat(factory.getValidator().validate(CardDetailRequest.empty())).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("both identifiers are withheld while useful turn state remains visible")
        void identifiersAreRedacted() {
            final CardDetailRequest request =
                    new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER, KeyAction.PFK07, navigation());

            assertThat(request.toString())
                    .contains("accountIdFilter=***REDACTED***")
                    .contains("cardNumberFilter=***REDACTED***")
                    .contains("keyAction=PFK07")
                    .contains("navigationContext=NavigationContext[")
                    .doesNotContain(ACCOUNT_ID, CARD_NUMBER);
        }

        @Test
        @DisplayName("redaction does not alter the accessor values")
        void redactionIsConfinedToRendering() {
            final CardDetailRequest request =
                    new CardDetailRequest(ACCOUNT_ID, CARD_NUMBER, null, null);

            assertThat(request.accountIdFilter()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumberFilter()).isEqualTo(CARD_NUMBER);
        }
    }
}
