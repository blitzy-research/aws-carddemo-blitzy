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
import com.fasterxml.jackson.annotation.JsonProperty;
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
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardUpdateRequest}, the request body of legacy transaction {@code CCUP}.
 *
 * <p>A pure unit test: no application context, no connection, no container. Where the wire shape is
 * what is under test it serialises and deserialises with a locally built mapper configured to match
 * the settings the module declares in {@code application.yml}.
 *
 * <p>Three properties dominate what is checked, and all three were wrong.
 *
 * <p>The first is trust. Two of this screen's seven fields could not be typed into by an operator, and
 * both of them reach the stored card record. The 3270 enforced that by hardware; a request body
 * enforces nothing. The tests below prove that the hidden expiry day can no longer be supplied by a
 * caller at all, and that the account id - which <em>is</em> operator-typed while the record has not
 * been fetched - is instead required to be absent on the one submission that writes.
 *
 * <p>The second is disclosure. The generated rendering printed a full card number beside the
 * cardholder's embossed name and the card's expiry date, which is the most sensitive grouping this
 * package can assemble. The tests prove the values are still carried and returned untouched while the
 * rendering withholds them.
 *
 * <p>The third is transitive validation. The nested navigation state declares widths of its own that
 * were never evaluated, because Bean Validation does not descend into an object graph unless it is
 * told to.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("CardUpdateRequest :: card-update request contract of legacy transaction CCUP")
class CardUpdateRequestTest {

    /**
     * The ten record components in the order they are declared: the seven map fields, the attention
     * key, the echoed navigation state and the sealed concurrency proof.
     *
     * <p>The proof is not a map field. It is the echoed counterpart of the program work area the
     * legacy transaction carries across its pseudo-conversational turns, and it is where the service
     * takes the owning account and the expiry day from on the turn that writes.
     */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "accountId", "cardNumber", "embossedName", "activeStatus", "expiryMonth", "expiryYear",
            "expiryDay", "keyAction", "navigationContext", "concurrencyToken");

    /** The measured map width of each of the seven value components, in the same order. */
    private static final List<Integer> MAP_WIDTHS = List.of(11, 16, 50, 1, 2, 4, 2);

    private static final String ACCOUNT_ID = "00000000011";
    private static final String CARD_NUMBER = "0000000000000011";
    private static final String EMBOSSED_NAME = "MARY ANN o'HARA-smith";
    private static final String EXPIRY_MONTH = "01";
    private static final String EXPIRY_YEAR = "2026";
    private static final String EXPIRY_DAY = "28";
    private static final String REDACTED = "***REDACTED***";

    /** A stand-in for the sealed proof the response issues and a confirming request echoes back. */
    private static final String CONCURRENCY_TOKEN = "CCUP1-sealed-proof-stand-in";

    private static NavigationContext navigation() {
        return new NavigationContext("CCUP", "COCRDUPC", "CCUP", "COCRDUPC", "ADMINUSR", "A", NavigationContext.ProgramContext.REENTER,
                "000000011", "MARY", "ANN", "SMITH", ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA",
                "COCRDUP");
    }

    private static CardUpdateRequest populated() {
        return new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", EXPIRY_MONTH,
                EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(), CONCURRENCY_TOKEN);
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

    private static JsonNode payloadOf(CardUpdateRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    private static List<String> componentNames() {
        return Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotations a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component. Neither the length
     * constraint, the nullity constraint, the cascade marker nor the serialization directive declares
     * {@code RECORD_COMPONENT} among its targets, so the compiler propagates each to the field, the
     * accessor and the constructor parameter but records none against the component itself: asking the
     * component yields an empty array for every component here, and an assertion phrased that way
     * would pass without testing anything.
     *
     * @param name the component name
     * @return the annotations present on the backing field
     */
    private static Annotation[] annotationsOn(String name) {
        try {
            return CardUpdateRequest.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return CardUpdateRequest.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, excluding the delegated navigation
     * state.
     *
     * <p>Necessary because the nested contract names some of the same components - it carries an
     * account id and a card number of its own - and because it prints values, such as a user
     * identifier, that can contain a two-character expiry part as a substring. Splitting the two apart
     * keeps each assertion about the type it is actually testing.
     *
     * @param rendered a full rendering
     * @return the leading segment this record contributed
     */
    private static String ownRendering(String rendered) {
        int delegated = rendered.indexOf(", navigationContext=");
        return (delegated < 0) ? rendered : rendered.substring(0, delegated);
    }

    private static Set<ConstraintViolation<CardUpdateRequest>> violationsOf(CardUpdateRequest request,
                                                                           Class<?>... groups) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request, groups);
        }
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the ten components in declaration order")
        void declaresTenComponentsInDeclarationOrder() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER)
                    .hasSize(10);
        }

        @Test
        @DisplayName("bounds each of the seven value components at its measured map width")
        void boundsEachValueComponentAtItsMapWidth() {
            List<String> valueComponents = COMPONENTS_IN_MAP_ORDER.subList(0, 7);
            for (int index = 0; index < valueComponents.size(); index++) {
                String component = valueComponents.get(index);
                Size bound = annotationOn(component, Size.class);
                if ("expiryDay".equals(component)) {
                    assertThat(bound)
                            .as("the hidden day carries no width constraint")
                            .isNull();
                } else {
                    assertThat(bound).as(component + " carries a width bound").isNotNull();
                    assertThat(bound.max()).as(component + " width").isEqualTo(MAP_WIDTHS.get(index));
                }
            }
        }

        @Test
        @DisplayName("declares the expiry month before the expiry year, as this map does")
        void ordersMonthBeforeYear() {
            assertThat(componentNames().indexOf("expiryMonth"))
                    .isLessThan(componentNames().indexOf("expiryYear"));
        }

        @Test
        @DisplayName("carries the concurrency proof and no screen-artefact or message component")
        void carriesTheProofAndNothingItDeliberatelyExcludes() {
            // The proof is what the legacy screen achieved with a protected attribute byte and a
            // before-image comparison, so it is a declared component rather than an excluded one. It is
            // deliberately not a version number or an entity tag: those are readable state a caller can
            // reason about and forge, whereas the proof is opaque and is verified rather than compared.
            assertThat(componentNames()).contains("concurrencyToken");
            assertThat(componentNames())
                    .doesNotContain("version", "entityTag", "cvv", "cardCvv", "informationMessage",
                            "errorMessage", "fieldErrors", "screenWorkArea");
        }
    }

    @Nested
    @DisplayName("protected terminal fields are not client-supplied")
    class ProtectedFieldsAreNotClientSupplied {

        @Test
        @DisplayName("the hidden expiry day is declared non-bindable")
        void hiddenDayIsNonBindable() {
            JsonProperty directive = annotationOn("expiryDay", JsonProperty.class);
            assertThat(directive).isNotNull();
            assertThat(directive.access()).isEqualTo(JsonProperty.Access.READ_ONLY);
        }

        @Test
        @DisplayName("a body that supplies the hidden expiry day has it discarded")
        void hiddenDaySuppliedInABodyIsDiscarded() throws JsonProcessingException {
            String body = """
                    {"accountId":"00000000011","cardNumber":"0000000000000011",\
                    "embossedName":"MARY ANN","activeStatus":"Y","expiryMonth":"01",\
                    "expiryYear":"2026","expiryDay":"99"}""";

            CardUpdateRequest bound = moduleEquivalentMapper().readValue(body, CardUpdateRequest.class);

            assertThat(bound.expiryDay())
                    .as("the wire cannot reach the stored expiry date through this field")
                    .isNull();
            assertThat(bound.expiryMonth())
                    .as("positive control: a sibling expiry part still binds")
                    .isEqualTo("01");
        }

        @Test
        @DisplayName("the hidden expiry day is still serialised, so the echo survives")
        void hiddenDayIsStillSerialised() throws JsonProcessingException {
            assertThat(payloadOf(populated()).get("expiryDay").asText()).isEqualTo(EXPIRY_DAY);
        }

        @Test
        @DisplayName("the hidden expiry day carries no validation constraint of any kind")
        void hiddenDayCarriesNoConstraint() {
            assertThat(Arrays.stream(annotationsOn("expiryDay")).map(a -> a.annotationType().getName())
                    .filter(name -> name.startsWith("jakarta.validation")))
                    .as("not even a width one")
                    .isEmpty();
            assertThat(annotationsOn("expiryDay"))
                    .as("the serialization directive is the only annotation present")
                    .hasSize(1);
            assertThat(annotationOn("expiryMonth", Size.class))
                    .as("positive control: a sibling expiry part is bounded")
                    .isNotNull();
        }

        @Test
        @DisplayName("the account id stays bindable, because the screen unprotects it before the fetch")
        void accountIdStaysBindable() throws JsonProcessingException {
            assertThat(annotationOn("accountId", JsonProperty.class))
                    .as("no serialization directive: the operator types this as a filter")
                    .isNull();

            CardUpdateRequest bound = moduleEquivalentMapper().readValue(
                    "{\"accountId\":\"00000000011\"}", CardUpdateRequest.class);

            assertThat(bound.accountId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("the account id must be absent on the submission that writes")
        void accountIdMustBeAbsentOnTheWritingSubmission() {
            Set<ConstraintViolation<CardUpdateRequest>> violations =
                    violationsOf(populated(), CardUpdateRequest.ConfirmSave.class);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("accountId");
        }

        @Test
        @DisplayName("a confirming submission without an account id is accepted")
        void confirmingSubmissionWithoutAnAccountIdIsAccepted() {
            CardUpdateRequest confirming = new CardUpdateRequest(null, CARD_NUMBER, EMBOSSED_NAME, "Y",
                    EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(),
                    CONCURRENCY_TOKEN);

            assertThat(violationsOf(confirming, CardUpdateRequest.ConfirmSave.class)).isEmpty();
        }

        @Test
        @DisplayName("the absence rule is inert on the ordinary searching submission")
        void absenceRuleIsInertUnderTheDefaultGroup() {
            assertThat(violationsOf(populated()))
                    .as("the typed filter turn must behave exactly as it did before the group existed")
                    .isEmpty();
        }

        @Test
        @DisplayName("the absence rule is scoped to the writing group alone")
        void absenceRuleIsScopedToTheWritingGroup() {
            Null rule = annotationOn("accountId", Null.class);
            assertThat(rule).isNotNull();
            assertThat(rule.groups()).containsExactly(CardUpdateRequest.ConfirmSave.class);
        }

        @Test
        @DisplayName("the writing group is a bare nested marker with no behaviour")
        void writingGroupIsABareNestedMarker() {
            Class<?> group = CardUpdateRequest.ConfirmSave.class;
            assertThat(group.isInterface()).isTrue();
            assertThat(group.getDeclaredMethods()).isEmpty();
            assertThat(group.getDeclaredFields()).isEmpty();
            assertThat(group.getEnclosingClass()).isEqualTo(CardUpdateRequest.class);
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
        void overLongNestedValueIsReported() {
            NavigationContext tooWide = new NavigationContext("CCUPX", "COCRDUPC", "CCUP",
                    "COCRDUPC", "ADMINUSR", "A", NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                    ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDUPA", "COCRDUP");
            CardUpdateRequest request = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME,
                    "Y", EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, tooWide,
                    CONCURRENCY_TOKEN);

            Set<ConstraintViolation<CardUpdateRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("navigationContext.fromTransactionId");
        }
    }

    @Nested
    @DisplayName("delegated rules are still delegated")
    class DelegatedRulesAreStillDelegated {

        @Test
        @DisplayName("accepts null everywhere, because an empty submission is a real screen state")
        void acceptsNullEverywhere() {
            CardUpdateRequest empty =
                    new CardUpdateRequest(null, null, null, null, null, null, null, null, null,
                            null);

            assertThat(violationsOf(empty)).isEmpty();
        }

        @Test
        @DisplayName("accepts an out-of-range expiry month and year, which the service rejects")
        void acceptsOutOfRangeExpiryParts() {
            CardUpdateRequest outOfRange = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    EMBOSSED_NAME, "Y", "99", "1066", EXPIRY_DAY, KeyAction.PFK05, navigation(),
                    CONCURRENCY_TOKEN);

            assertThat(violationsOf(outOfRange))
                    .as("hoisting either range out of the ordered cascade would change which single"
                            + " message a bad submission produces")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts an embossed name holding embedded spaces")
        void acceptsEmbeddedSpacesInTheEmbossedName() {
            CardUpdateRequest spaced = new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER, "MARY ANN", "Y",
                    EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05, navigation(),
                    CONCURRENCY_TOKEN);

            assertThat(violationsOf(spaced)).isEmpty();
        }

        @Test
        @DisplayName("carries no pattern, nullity or range constraint on any value component")
        void carriesNoValueConstraintBeyondWidth() {
            for (String component : COMPONENTS_IN_MAP_ORDER.subList(0, 7)) {
                assertThat(Arrays.stream(annotationsOn(component))
                        .map(annotation -> annotation.annotationType().getSimpleName()))
                        .as(component + " carries only a width bound and the absence rule")
                        .doesNotContain("Pattern", "NotNull", "NotBlank", "Min", "Max", "Digits",
                                "DecimalMin", "DecimalMax", "Positive");
            }
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds every component, naming the type and disclosing nothing else")
        void withholdsEveryRegulatedValue() {
            String rendered = populated().toString();

            // A whole-object placeholder rather than a component-by-component one, matching the
            // account-update request this pair belongs with. The reason is that this type has no
            // component worth printing once the identifiers, the name, all three expiry parts and the
            // proof are withheld, and an enumerated renderer can leak by omission the moment an
            // eleventh component is added, whereas a whole-object placeholder cannot.
            assertThat(rendered).isEqualTo("CardUpdateRequest[" + REDACTED + "]");
            assertThat(rendered).doesNotContain(CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME, EXPIRY_MONTH,
                    EXPIRY_YEAR, EXPIRY_DAY, CONCURRENCY_TOKEN);
        }

        @Test
        @DisplayName("the delegated navigation state withholds the same identifiers independently")
        void delegatedNavigationStateWithholdsIndependently() {
            String delegated = navigation().toString();

            assertThat(delegated).doesNotContain(CARD_NUMBER, ACCOUNT_ID);
            assertThat(delegated).contains("accountId=" + REDACTED, "cardNumber=" + REDACTED);
        }

        @Test
        @DisplayName("retains no component at all, and alters none of them either")
        void retainsNoComponentAndAltersNone() {
            CardUpdateRequest request = populated();

            String rendered = request.toString();

            assertThat(rendered).startsWith("CardUpdateRequest[").endsWith("]");
            assertThat(rendered).doesNotContain("activeStatus=Y").doesNotContain("keyAction=PFK05");
            assertThat(request.activeStatus())
                    .as("the interaction state a reader would have wanted is still readable through "
                            + "the accessor, which is where a caller reads it")
                    .isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK05);
        }

        @Test
        @DisplayName("prints no navigation state at all, so this type's safety rests on nothing else")
        void printsNoNavigationStateAtAll() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("navigationContext=")
                    .doesNotContain("NavigationContext[");
            assertThat(navigation().toString())
                    .as("the echoed state still withholds its own identifiers wherever a type does "
                            + "render it by delegation")
                    .contains("accountId=" + REDACTED);
        }

        @Test
        @DisplayName("names no component at all, so a component added later cannot leak by omission")
        void namesNoComponentAtAll() {
            String rendered = populated().toString();

            for (String component : COMPONENTS_IN_MAP_ORDER) {
                assertThat(rendered)
                        .as(component + " must not be named in the rendering")
                        .doesNotContain(component + "=");
            }
            assertThat(ownRendering(rendered))
                    .as("there is no delegated tail to separate, because nothing is delegated")
                    .isEqualTo(rendered);
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            CardUpdateRequest request = populated();
            request.toString();

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(request.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(request.expiryMonth()).isEqualTo(EXPIRY_MONTH);
            assertThat(request.expiryYear()).isEqualTo(EXPIRY_YEAR);
            assertThat(request.expiryDay()).isEqualTo(EXPIRY_DAY);
        }

        @Test
        @DisplayName("changes nothing on the wire")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("does not fold the embossed name's letter case anywhere")
        void doesNotFoldEmbossedNameCase() throws JsonProcessingException {
            assertThat(payloadOf(populated()).get("embossedName").asText())
                    .as("a case-only edit must stay undetectable as a change, so the service folds")
                    .isEqualTo(EMBOSSED_NAME);
        }

        @Test
        @DisplayName("equality still compares every component by value")
        void equalityStillComparesByValue() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(populated()).isNotEqualTo(new CardUpdateRequest(ACCOUNT_ID, CARD_NUMBER,
                    EMBOSSED_NAME, "N", EXPIRY_MONTH, EXPIRY_YEAR, EXPIRY_DAY, KeyAction.PFK05,
                    navigation(), CONCURRENCY_TOKEN));
        }
    }
}
