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
import java.math.BigDecimal;
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
 * Unit tests for {@link TransactionAddRequest}, the request body of legacy transaction {@code CT02}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>The amount dominates what is checked here, because it was the one component whose Java type could
 * not express the states the legacy program is contractually required to report. The map item is
 * {@code TRNAMTI PIC X(12)} - twelve characters of text - and the program subjects it to two ordered
 * lexical tests: an emptiness test at {@code app/cbl/COTRN02C.cbl} line 276 that reports the amount
 * cannot be empty, and a four-position shape test at lines 339 to 347 that reports the required format.
 * A decimal component could represent none of the three failing states: a blank submission collapses
 * into absence, a missing decimal point or a bad sign character either binds silently or fails inside
 * the deserializer, and arbitrary exponent notation binds successfully and produces a value no
 * twelve-column screen field could have produced. The tests below hold the lexeme.
 *
 * <p>The two remaining properties are transitive validation of the nested navigation state, and a
 * rendering that withholds what is the densest set of regulated values any request in this package
 * carries: a card number, an account key, an amount and four merchant items on one line.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("TransactionAddRequest :: transaction-add request contract of legacy transaction CT02")
class TransactionAddRequestTest {

    /** The sixteen record components in the order the symbolic map declares their fields. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "accountId", "cardNumber", "typeCode", "categoryCode", "transactionSource", "description",
            "amount", "originationDate", "processingDate", "merchantId", "merchantName",
            "merchantCity", "merchantZip", "confirm", "keyAction", "navigationContext");

    private static final String ACCOUNT_ID = "00000000011";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String DESCRIPTION = "GROCERIES AT STORE 42";
    private static final String AMOUNT_LEXEME = "-00000123.45";
    private static final String MERCHANT_ID = "999999999";
    private static final String MERCHANT_NAME = "SMITH HARDWARE";
    private static final String MERCHANT_CITY = "SEATTLE";
    private static final String MERCHANT_ZIP = "98101";
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CT02", "COTRN02C", "CT02", "COTRN02C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN2A", "COTRN02");
    }

    private static TransactionAddRequest populated() {
        return new TransactionAddRequest(ACCOUNT_ID, CARD_NUMBER, "01", "0002", "POS TERM  ",
                DESCRIPTION, AMOUNT_LEXEME, "2022-07-19", "2022-07-20", MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, "Y", KeyAction.ENTER, navigation());
    }

    private static TransactionAddRequest withAmount(String amount) {
        return new TransactionAddRequest(null, null, null, null, null, null, amount, null, null, null,
                null, null, null, null, KeyAction.ENTER, null);
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

    private static JsonNode payloadOf(TransactionAddRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    private static List<String> componentNames() {
        return Arrays.stream(TransactionAddRequest.class.getRecordComponents())
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
            return TransactionAddRequest.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return TransactionAddRequest.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static Set<ConstraintViolation<TransactionAddRequest>> violationsOf(
            TransactionAddRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, excluding the delegated navigation
     * state.
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
        @DisplayName("declares exactly the sixteen components in map order")
        void declaresSixteenComponentsInMapOrder() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER)
                    .hasSize(16);
        }

        @Test
        @DisplayName("bounds every text component at its measured map width")
        void boundsEveryTextComponentAtItsMapWidth() {
            assertThat(annotationOn("accountId", Size.class).max()).isEqualTo(11);
            assertThat(annotationOn("cardNumber", Size.class).max()).isEqualTo(16);
            assertThat(annotationOn("typeCode", Size.class).max()).isEqualTo(2);
            assertThat(annotationOn("categoryCode", Size.class).max()).isEqualTo(4);
            assertThat(annotationOn("transactionSource", Size.class).max()).isEqualTo(10);
            assertThat(annotationOn("description", Size.class).max()).isEqualTo(60);
            assertThat(annotationOn("originationDate", Size.class).max()).isEqualTo(10);
            assertThat(annotationOn("processingDate", Size.class).max()).isEqualTo(10);
            assertThat(annotationOn("merchantId", Size.class).max()).isEqualTo(9);
            assertThat(annotationOn("merchantName", Size.class).max()).isEqualTo(30);
            assertThat(annotationOn("merchantCity", Size.class).max()).isEqualTo(25);
            assertThat(annotationOn("merchantZip", Size.class).max()).isEqualTo(10);
            assertThat(annotationOn("confirm", Size.class).max()).isEqualTo(1);
        }

        @Test
        @DisplayName("carries no pattern, nullity, range or digit constraint anywhere")
        void carriesNoConstraintBeyondWidthAndCascade() {
            for (String component : COMPONENTS_IN_MAP_ORDER) {
                assertThat(Arrays.stream(annotationsOn(component))
                        .map(annotation -> annotation.annotationType().getSimpleName()))
                        .as(component)
                        .doesNotContain("Pattern", "NotNull", "NotBlank", "NotEmpty", "Min", "Max",
                                "Digits", "DecimalMin", "DecimalMax", "Positive", "Negative");
            }
        }
    }

    @Nested
    @DisplayName("the amount is a twelve-character lexeme, not a number")
    class AmountIsALexeme {

        @Test
        @DisplayName("is declared as text and not as a decimal")
        void isDeclaredAsText() throws NoSuchFieldException {
            assertThat(TransactionAddRequest.class.getDeclaredField("amount").getType())
                    .isEqualTo(String.class);
            assertThat(TransactionAddRequest.class.getDeclaredField("amount").getType())
                    .isNotEqualTo(BigDecimal.class);
        }

        @Test
        @DisplayName("is bounded at the twelve characters the map declares")
        void isBoundedAtTwelveCharacters() {
            assertThat(annotationOn("amount", Size.class).max())
                    .as("TRNAMTI PIC X(12) at COTRN02.CPY line 96")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("keeps a well-formed lexeme byte for byte, including its leading zeros and sign")
        void keepsAWellFormedLexeme() {
            assertThat(withAmount(AMOUNT_LEXEME).amount()).isEqualTo("-00000123.45");
            assertThat(withAmount("+00000123.45").amount()).isEqualTo("+00000123.45");
        }

        @Test
        @DisplayName("keeps a blank submission representable, which a decimal component could not")
        void keepsABlankSubmissionRepresentable() {
            TransactionAddRequest blank = withAmount("            ");

            assertThat(blank.amount())
                    .as("the emptiness branch at COTRN02C:276 owes this state its own message")
                    .isEqualTo("            ")
                    .hasSize(12);
            assertThat(violationsOf(blank))
                    .as("a blank value is a legitimate submission, not a constraint violation")
                    .isEmpty();
        }

        @Test
        @DisplayName("distinguishes an absent field from a blank one")
        void distinguishesAbsentFromBlank() {
            assertThat(withAmount(null).amount()).isNull();
            assertThat(withAmount("").amount()).isEmpty();
            assertThat(withAmount("   ").amount()).isEqualTo("   ");
        }

        @Test
        @DisplayName("keeps a malformed lexeme intact so the format message can be produced")
        void keepsAMalformedLexemeIntact() {
            assertThat(withAmount("00000123.45").amount())
                    .as("no sign character in position one")
                    .isEqualTo("00000123.45");
            assertThat(withAmount("-0000012345").amount())
                    .as("no decimal point in position ten")
                    .isEqualTo("-0000012345");
            assertThat(withAmount("*00000123.45").amount())
                    .as("a sign character that is neither plus nor minus")
                    .isEqualTo("*00000123.45");
            assertThat(withAmount("-000001AB.45").amount())
                    .as("non-numeric characters inside the integer positions")
                    .isEqualTo("-000001AB.45");
        }

        @Test
        @DisplayName("carries exponent notation as inert text rather than as a magnitude")
        void carriesExponentNotationAsInertText() {
            TransactionAddRequest exponent = withAmount("1e100000");

            assertThat(exponent.amount())
                    .as("nothing here parses the lexeme, so no magnitude is ever materialised")
                    .isEqualTo("1e100000");
            assertThat(violationsOf(exponent))
                    .as("the width bound is satisfied; the sign and point rules are the service's")
                    .isEmpty();
        }

        @Test
        @DisplayName("rejects a lexeme wider than the screen field")
        void rejectsAnOverWideLexeme() {
            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    violationsOf(withAmount("-000000123.45"));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("amount");
        }

        @Test
        @DisplayName("round-trips a lexeme through JSON as a string, never as a number")
        void roundTripsAsAString() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            TransactionAddRequest bound =
                    mapper.readValue("{\"amount\":\"-00000123.45\"}", TransactionAddRequest.class);

            assertThat(bound.amount()).isEqualTo(AMOUNT_LEXEME);
            assertThat(mapper.writeValueAsString(bound)).contains("\"amount\":\"-00000123.45\"");
        }

        @Test
        @DisplayName("declares no decimal type anywhere in the request contract")
        void declaresNoDecimalTypeAnywhere() {
            assertThat(Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                    .map(RecordComponent::getType))
                    .as("the service parses the accepted lexeme; this boundary carries none")
                    .doesNotContain(BigDecimal.class);
        }
    }

    @Nested
    @DisplayName("nested request state is validated transitively")
    class NestedStateIsValidatedTransitively {

        @Test
        @DisplayName("the navigation component is marked for cascading validation")
        void navigationComponentCascades() {
            assertThat(annotationOn("navigationContext", Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("an over-long value inside the navigation state is reported")
        void overLongNavigationValueIsReported() {
            NavigationContext tooWide = new NavigationContext("CT02X", "COTRN02C", "CT02",
                    "COTRN02C", "ADMINUSR", "A", NavigationContext.ProgramContext.REENTER,
                    "000000011", "MARY", "ANN", "SMITH", ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN2A",
                    "COTRN02");
            TransactionAddRequest request = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, KeyAction.ENTER, tooWide);

            Set<ConstraintViolation<TransactionAddRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("a fully populated request violates nothing")
        void fullyPopulatedRequestViolatesNothing() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("accepts an entirely empty submission, which is a legitimate first entry")
        void acceptsAnEmptySubmission() {
            TransactionAddRequest empty = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);

            assertThat(violationsOf(empty)).isEmpty();
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds the card number, the account key, the amount and the merchant")
        void withholdsEveryRegulatedValue() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).doesNotContain(ACCOUNT_ID, CARD_NUMBER, DESCRIPTION, AMOUNT_LEXEME,
                    MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP);
            assertThat(rendered).contains("accountId=" + REDACTED, "cardNumber=" + REDACTED,
                    "description=" + REDACTED, "amount=" + REDACTED, "merchantId=" + REDACTED,
                    "merchantName=" + REDACTED, "merchantCity=" + REDACTED,
                    "merchantZip=" + REDACTED);
        }

        @Test
        @DisplayName("retains the codes, the dates and the interaction state")
        void retainsCodesDatesAndInteractionState() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).contains("typeCode=01", "categoryCode=0002",
                    "transactionSource=POS TERM  ", "originationDate=2022-07-19",
                    "processingDate=2022-07-20", "confirm=Y", "keyAction=ENTER");
        }

        @Test
        @DisplayName("is bracketed by the type name and names every component once")
        void namesEveryComponentOnce() {
            String rendered = ownRendering(populated().toString());

            assertThat(populated().toString()).startsWith("TransactionAddRequest[").endsWith("]");
            for (String component : COMPONENTS_IN_MAP_ORDER.subList(0, 15)) {
                assertThat(rendered.split(component + "=", -1).length - 1)
                        .as(component + " appears once")
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("delegates the navigation state, which withholds its own identifiers")
        void delegatesTheNavigationState() {
            String rendered = populated().toString();

            assertThat(rendered).contains(", navigationContext=NavigationContext[");
            assertThat(rendered).doesNotContain(ACCOUNT_ID, CARD_NUMBER);
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            TransactionAddRequest request = populated();
            request.toString();

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(request.amount()).isEqualTo(AMOUNT_LEXEME);
            assertThat(request.merchantId()).isEqualTo(MERCHANT_ID);
        }

        @Test
        @DisplayName("changes nothing on the wire")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("amount").asText()).isEqualTo(AMOUNT_LEXEME);
            assertThat(payload.get("merchantName").asText()).isEqualTo(MERCHANT_NAME);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("tolerates an instance with nothing populated")
        void toleratesAnEmptyInstance() {
            TransactionAddRequest empty = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);

            assertThat(empty.toString()).contains("cardNumber=" + REDACTED, "keyAction=null",
                    "navigationContext=null");
        }
    }
}
