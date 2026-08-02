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
 * Unit tests for {@link ReportRequest}, the request body of legacy transaction {@code CR00}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>The three selection markers dominate what is checked here, because they were the components whose
 * shape the review found wrong. The screen offers three independent single-character positions -
 * {@code MONTHLYI}, {@code YEARLYI} and {@code CUSTOMI} at {@code app/cpy-bms/CORPT00.CPY} lines 60, 66
 * and 72 - and an operator can physically mark more than one of them. The program evaluates them in a
 * fixed order and silently honours the earliest marked position, so a submission that marks two is a
 * representable state with a defined outcome. A single-valued component could not express it: two marks
 * would collapse into one before the service ever saw them, and the tie-break the program performs would
 * have moved into a deserializer with no citation for its choice. The tests below hold three markers,
 * three states each, and no collapsing.
 *
 * <p>The remaining assertions hold the twelve-component inventory, the six date-part widths, and the
 * transitive validation of the nested navigation state.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("ReportRequest :: report-request contract of legacy transaction CR00")
class ReportRequestTest {

    /** The twelve record components in the order the symbolic map declares their fields. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "monthlySelection", "yearlySelection", "customSelection", "startMonth", "startDay",
            "startYear", "endMonth", "endDay", "endYear", "confirm", "keyAction",
            "navigationContext");

    /** The three independent selection markers, in the order the program evaluates them. */
    private static final List<String> SELECTION_MARKERS_IN_EVALUATION_ORDER =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    private static NavigationContext navigation() {
        return new NavigationContext("CR00", "CORPT00C", "CR00", "CORPT00C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                "00000000011", "Y", "4111111111111111", "CORPT0A", "CORPT00");
    }

    private static ReportRequest customPeriod() {
        return new ReportRequest(null, null, "Y", "07", "01", "2022", "07", "19", "2022", "Y",
                KeyAction.ENTER, navigation());
    }

    /**
     * Returns a request in which every one of the twelve components is present.
     *
     * <p>Needed because the module omits an absent component from the payload rather than publishing a
     * null, so an inventory assertion made over the wire needs a fixture with nothing absent. It marks
     * all three positions deliberately, which the screen permits and which the program resolves by
     * evaluation order rather than by refusal.
     *
     * @return a request with no absent component
     */
    private static ReportRequest everyComponentPresent() {
        return new ReportRequest("Y", "Y", "Y", "07", "01", "2022", "07", "19", "2022", "Y",
                KeyAction.ENTER, navigation());
    }

    private static ReportRequest withSelections(String monthly, String yearly, String custom) {
        return new ReportRequest(monthly, yearly, custom, null, null, null, null, null, null, null,
                KeyAction.ENTER, null);
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

    private static JsonNode payloadOf(ReportRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    private static List<String> componentNames() {
        return Arrays.stream(ReportRequest.class.getRecordComponents())
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
            return ReportRequest.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return ReportRequest.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static Set<ConstraintViolation<ReportRequest>> violationsOf(ReportRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the twelve components the screen submits, in map order")
        void declaresTheTwelveComponentsInMapOrder() {
            assertThat(componentNames()).containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("declares the six date parts at the widths the screen gives them")
        void declaresTheSixDatePartWidths() {
            assertThat(annotationOn("startMonth", Size.class).max()).isEqualTo(2);
            assertThat(annotationOn("startDay", Size.class).max()).isEqualTo(2);
            assertThat(annotationOn("startYear", Size.class).max()).isEqualTo(4);
            assertThat(annotationOn("endMonth", Size.class).max()).isEqualTo(2);
            assertThat(annotationOn("endDay", Size.class).max()).isEqualTo(2);
            assertThat(annotationOn("endYear", Size.class).max()).isEqualTo(4);
        }

        @Test
        @DisplayName("carries every date part as text so a partial entry stays reportable")
        void carriesEveryDatePartAsText() {
            assertThat(Arrays.stream(ReportRequest.class.getRecordComponents())
                    .filter(component -> component.getName().startsWith("start")
                            || component.getName().startsWith("end"))
                    .map(RecordComponent::getType))
                    .hasSize(6)
                    .containsOnly(String.class);
        }

        @Test
        @DisplayName("bounds the confirmation answer at one character")
        void boundsTheConfirmationAnswer() {
            assertThat(annotationOn("confirm", Size.class).max()).isEqualTo(1);
        }

        @Test
        @DisplayName("carries the attention key as a typed action with no default")
        void carriesTheAttentionKeyAsATypedAction() {
            assertThat(ReportRequest.class.getRecordComponents()[10].getType())
                    .isEqualTo(KeyAction.class);
            assertThat(annotationsOn("keyAction")).isEmpty();
            assertThat(withSelections(null, null, null).keyAction()).isEqualTo(KeyAction.ENTER);
        }

        @Test
        @DisplayName("declares no member beyond the twelve components")
        void declaresNoMemberBeyondTheComponents() {
            assertThat(ReportRequest.class.getDeclaredFields())
                    .hasSize(COMPONENTS_IN_MAP_ORDER.size());
        }
    }

    @Nested
    @DisplayName("the three selection markers stay three")
    class TheThreeSelectionMarkersStayThree {

        @Test
        @DisplayName("declares three independent markers, one per typed screen position")
        void declaresThreeIndependentMarkers() {
            assertThat(componentNames().subList(0, 3))
                    .containsExactlyElementsOf(SELECTION_MARKERS_IN_EVALUATION_ORDER);
        }

        @Test
        @DisplayName("carries no collapsed period component and no period enumeration")
        void carriesNoCollapsedPeriodComponent() {
            assertThat(componentNames())
                    .doesNotContain("reportPeriod", "period", "reportType", "selection");
            assertThat(Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .map(Class::getName))
                    .noneMatch(name -> name.contains("ReportPeriod"));
        }

        @Test
        @DisplayName("carries each marker as one character of text, so its case survives")
        void carriesEachMarkerAsOneCharacterOfText() {
            SELECTION_MARKERS_IN_EVALUATION_ORDER.forEach(marker -> {
                assertThat(annotationOn(marker, Size.class).max()).isEqualTo(1);
                assertThat(annotationsOn(marker)).hasOnlyElementsOfType(Size.class);
            });

            assertThat(withSelections("y", null, null).monthlySelection()).isEqualTo("y");
        }

        @Test
        @DisplayName("represents a submission that marks two positions, which the screen permits")
        void representsASubmissionMarkingTwoPositions() {
            ReportRequest twoMarked = withSelections("Y", "Y", null);

            assertThat(twoMarked.monthlySelection()).isEqualTo("Y");
            assertThat(twoMarked.yearlySelection()).isEqualTo("Y");
            assertThat(twoMarked.customSelection()).isNull();
            assertThat(violationsOf(twoMarked)).isEmpty();
        }

        @Test
        @DisplayName("represents a submission that marks all three positions")
        void representsASubmissionMarkingAllThree() {
            ReportRequest allMarked = withSelections("Y", "Y", "Y");

            assertThat(List.of(allMarked.monthlySelection(), allMarked.yearlySelection(),
                    allMarked.customSelection())).containsExactly("Y", "Y", "Y");
            assertThat(violationsOf(allMarked)).isEmpty();
        }

        @Test
        @DisplayName("distinguishes an unmarked position from a blank one and from an absent one")
        void distinguishesUnmarkedBlankAndAbsent() {
            assertThat(withSelections(null, null, null).monthlySelection()).isNull();
            assertThat(withSelections("", null, null).monthlySelection()).isEmpty();
            assertThat(withSelections(" ", null, null).monthlySelection()).isEqualTo(" ");
        }

        @Test
        @DisplayName("needs no synthetic constant for the state where nothing is marked")
        void needsNoSyntheticConstantForNothingMarked() {
            ReportRequest nothingMarked = withSelections(null, null, null);

            assertThat(violationsOf(nothingMarked)).isEmpty();
            assertThat(nothingMarked.monthlySelection()).isNull();
            assertThat(nothingMarked.yearlySelection()).isNull();
            assertThat(nothingMarked.customSelection()).isNull();
        }

        @Test
        @DisplayName("performs no tie-break of its own between two marked positions")
        void performsNoTieBreakOfItsOwn() {
            ReportRequest twoMarked = withSelections("Y", "Y", null);

            assertThat(twoMarked).isNotEqualTo(withSelections("Y", null, null));
            assertThat(twoMarked).isNotEqualTo(withSelections(null, "Y", null));
        }

        @Test
        @DisplayName("rejects an over-long marker on each of the three positions independently")
        void rejectsAnOverLongMarker() {
            assertThat(violationsOf(withSelections("YY", "YY", "YY")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrderElementsOf(SELECTION_MARKERS_IN_EVALUATION_ORDER);
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
            NavigationContext overWide = new NavigationContext("CR000", "CORPT00C", "CR00",
                    "CORPT00C", "ADMINUSR", "A", NavigationContext.ProgramContext.ENTER, null, null,
                    null, null, null, null, null, null, null);

            assertThat(violationsOf(new ReportRequest(null, null, "Y", null, null, null, null, null,
                    null, null, KeyAction.ENTER, overWide)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("reports nothing when the nested state is within its own bounds")
        void reportsNothingForValidNestedState() {
            assertThat(violationsOf(customPeriod())).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when the nested state is absent")
        void reportsNothingForAbsentNestedState() {
            assertThat(violationsOf(withSelections("Y", null, null))).isEmpty();
        }

        @Test
        @DisplayName("adds no constraint of its own while cascading")
        void addsNoConstraintOfItsOwn() {
            assertThat(annotationsOn("navigationContext")).hasOnlyElementsOfType(Valid.class);
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("publishes every component under its own name")
        void publishesEveryComponentUnderItsOwnName() throws JsonProcessingException {
            assertThat(payloadOf(everyComponentPresent()).fieldNames()).toIterable()
                    .containsExactlyInAnyOrderElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("publishes the three markers as three independent JSON strings")
        void publishesTheThreeMarkersAsThreeStrings() throws JsonProcessingException {
            JsonNode payload = payloadOf(withSelections("Y", "N", "Y"));

            assertThat(payload.get("monthlySelection").asText()).isEqualTo("Y");
            assertThat(payload.get("yearlySelection").asText()).isEqualTo("N");
            assertThat(payload.get("customSelection").asText()).isEqualTo("Y");
        }

        @Test
        @DisplayName("publishes no collapsed period property")
        void publishesNoCollapsedPeriodProperty() throws JsonProcessingException {
            assertThat(payloadOf(customPeriod()).toString())
                    .doesNotContain("\"reportPeriod\"", "\"period\"", "\"reportType\"");
        }

        @Test
        @DisplayName("omits an unmarked position rather than publishing a null")
        void omitsAnUnmarkedPosition() throws JsonProcessingException {
            assertThat(payloadOf(withSelections("Y", null, null)).fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("monthlySelection", "keyAction");
        }

        @Test
        @DisplayName("keeps a leading zero on a date part, which a numeric type would have lost")
        void keepsALeadingZeroOnADatePart() throws JsonProcessingException {
            JsonNode payload = payloadOf(customPeriod());

            assertThat(payload.get("startMonth").asText()).isEqualTo("07");
            assertThat(payload.get("startDay").isTextual()).isTrue();
        }

        @Test
        @DisplayName("round-trips through the wire unchanged, markers included")
        void roundTripsUnchanged() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            ReportRequest twoMarked = withSelections("Y", "Y", null);

            assertThat(mapper.readValue(mapper.writeValueAsString(twoMarked), ReportRequest.class))
                    .isEqualTo(twoMarked);
            assertThat(mapper.readValue(mapper.writeValueAsString(customPeriod()),
                    ReportRequest.class)).isEqualTo(customPeriod());
        }
    }
}
