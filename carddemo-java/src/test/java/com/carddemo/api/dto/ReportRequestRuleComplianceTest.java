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

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;
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
 * Unit tests for {@link ReportRequest}, the request body of legacy transaction {@code CR00} implemented
 * by {@code app/cbl/CORPT00C.cbl} over screen {@code app/cpy-bms/CORPT00.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Two date triples, not two dates.</strong> The legacy screen collects a start and an end
 * date as six separate fields - month, day and year for each - because a 3270 map has no date field
 * type. The request reproduces that shape rather than folding each triple into a single date, so that a
 * caller who supplies a month and omits the year reaches the same validation cascade the legacy applies
 * rather than being rejected by a parser before the cascade ever runs. The six components are therefore
 * asserted to be separate and separately bounded.
 *
 * <p><strong>A selector triple, not one enumerated period.</strong> The screen offers three
 * independent one-character selector positions - {@code MONTHLYI} at symbolic-map line 60,
 * {@code YEARLYI} at line 66 and {@code CUSTOMI} at line 72 - and the request carries all three rather
 * than one enumerated period. Two facts make the triple load-bearing. An operator can mark more than
 * one position, so a single value could not represent every submission the screen accepts; and
 * {@code CORPT00C} tests the three positions in the fixed order monthly, yearly, custom at line 213
 * and stops at the first it finds marked, so first-match-wins is a service rule that needs all three
 * inbound values to apply. The derived report name is not on this contract at all: the program writes
 * it at lines 214, 240 and 433 from the selection it just resolved, so it belongs to the response.
 *
 * <p><strong>The confirmation flag is one character wide and is not a boolean.</strong> The legacy
 * program gates submission on a single-character confirmation field at
 * {@code app/cbl/CORPT00C.cbl:462-510}. Modelling it as a boolean would lose the difference between a
 * field the operator left untouched and one they typed a rejecting character into.
 *
 * @see ReportRequest
 */
@DisplayName("ReportRequest - the CR00 report request contract")
class ReportRequestRuleComplianceTest {

    /** The one character a marked selector position carries, the full width of the legacy field. */
    private static final String MARKED = "Y";

    /** The three published selector properties, in the order the legacy evaluation tests them. */
    private static final List<String> SELECTOR_PROPERTIES =
            List.of("monthlySelection", "yearlySelection", "customSelection");

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
    private static JsonNode payloadOf(final ReportRequest request) throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Builds a fully populated custom-range request spanning a whole calendar year.
     *
     * @return the populated request
     */
    private static ReportRequest aCustomPeriodRequest() {
        return new ReportRequest(null, null, MARKED, "01", "01", "2022", "12", "31", "2022", "Y",
                KeyAction.ENTER, NavigationContext.empty());
    }

    /**
     * Builds a request with exactly one selector position marked and nothing else supplied.
     *
     * @param position the position to mark
     * @return a request carrying only that marker and the attention key
     */
    private static ReportRequest markedAt(final ReportPeriod position) {
        return new ReportRequest(
                position == ReportPeriod.MONTHLY ? MARKED : null,
                position == ReportPeriod.YEARLY ? MARKED : null,
                position == ReportPeriod.CUSTOM ? MARKED : null,
                null, null, null, null, null, null, null, KeyAction.ENTER, null);
    }

    /**
     * Reads the marker carried in the position that corresponds to a period.
     *
     * @param request the request to read
     * @param position the position to read
     * @return the marker in that position, or {@code null} when it is unmarked
     */
    private static String markerAt(final ReportRequest request, final ReportPeriod position) {
        return switch (position) {
            case MONTHLY -> request.monthlySelection();
            case YEARLY -> request.yearlySelection();
            case CUSTOM -> request.customSelection();
        };
    }

    /**
     * Names the payload property that carries the marker for a period.
     *
     * @param position the position to name
     * @return the published property name
     */
    private static String propertyFor(final ReportPeriod position) {
        return switch (position) {
            case MONTHLY -> "monthlySelection";
            case YEARLY -> "yearlySelection";
            case CUSTOM -> "customSelection";
        };
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
        final Size size = ReportRequest.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape")
    class TheDeclaredShape {

        @Test
        @DisplayName("the request declares twelve components, the selector triple, two date triples, "
                + "the confirmation flag and two control components")
        void theRequestDeclaresTwelveComponents() {
            final List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("monthlySelection", "yearlySelection",
                    "customSelection", "startMonth", "startDay", "startYear", "endMonth", "endDay",
                    "endYear", "confirm", "keyAction", "navigationContext");
            assertThat(declared).hasSize(12);
            assertThat(declared.subList(0, 3))
                    .as("the three positions lead the contract in the order the legacy tests them")
                    .isEqualTo(SELECTOR_PROPERTIES);
        }

        @Test
        @DisplayName("no enumerated period and no derived report name is carried inbound, because the "
                + "program derives the name from the selection rather than receiving it")
        void noEnumeratedPeriodOrDerivedNameIsCarried() {
            final List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).doesNotContain("reportPeriod", "period", "reportName");
        }

        @Test
        @DisplayName("the six date components are separate strings rather than two date objects, so a "
                + "partly filled triple reaches the validation cascade intact")
        void theSixDateComponentsAreSeparateStrings() {
            assertThat(Arrays.stream(ReportRequest.class.getRecordComponents())
                    .filter(component -> component.getName().startsWith("start")
                            || component.getName().startsWith("end"))
                    .toList())
                    .hasSize(6)
                    .allSatisfy(component -> assertThat(component.getType()).isEqualTo(String.class));
        }

        @Test
        @DisplayName("the confirmation flag is a one-character string and not a boolean, so an untouched "
                + "field is distinguishable from a rejecting one")
        void theConfirmationFlagIsAStringAndNotABoolean() throws NoSuchMethodException {
            assertThat(ReportRequest.class.getDeclaredMethod("confirm").getReturnType())
                    .isEqualTo(String.class);
            assertThat(declaredMaximumLength("confirm")).isOne();
        }

        @Test
        @DisplayName("every component is a reference type, so every field can be absent")
        void everyComponentIsAReferenceType() {
            assertThat(Arrays.stream(ReportRequest.class.getRecordComponents())
                    .filter(component -> component.getType().isPrimitive()).toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final ReportRequest request = aCustomPeriodRequest();

            assertThat(request.monthlySelection()).isNull();
            assertThat(request.yearlySelection()).isNull();
            assertThat(request.customSelection()).isEqualTo(MARKED);
            assertThat(request.startMonth()).isEqualTo("01");
            assertThat(request.startDay()).isEqualTo("01");
            assertThat(request.startYear()).isEqualTo("2022");
            assertThat(request.endMonth()).isEqualTo("12");
            assertThat(request.endDay()).isEqualTo("31");
            assertThat(request.endYear()).isEqualTo("2022");
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext()).isEqualTo(NavigationContext.empty());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the three selector positions")
    class TheThreeSelectorPositions {

        @Test
        @DisplayName("the screen offers exactly three positions, one for each report type the legacy "
                + "screen names, and each is bounded at a single character")
        void theScreenOffersExactlyThreePositions() throws NoSuchMethodException {
            assertThat(SELECTOR_PROPERTIES).hasSize(3)
                    .hasSameSizeAs(ReportPeriod.values());
            for (final String property : SELECTOR_PROPERTIES) {
                assertThat(declaredMaximumLength(property))
                        .as("%s holds one character", property).isOne();
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ReportPeriod.class)
        @DisplayName("every position can be marked on its own, and marking one leaves the other two "
                + "absent on the wire")
        void everyPositionCanBeMarkedOnItsOwn(final ReportPeriod position)
                throws JsonProcessingException {
            final ReportRequest request = markedAt(position);
            final JsonNode payload = payloadOf(request);

            assertThat(markerAt(request, position)).isEqualTo(MARKED);
            assertThat(SELECTOR_PROPERTIES).allSatisfy(property ->
                    assertThat(payload.has(property))
                            .as("%s is %s", property,
                                    property.equals(propertyFor(position)) ? "marked" : "unmarked")
                            .isEqualTo(property.equals(propertyFor(position))));
        }

        @Test
        @DisplayName("more than one position can be marked at once, which is a submission a single "
                + "enumerated period could not have represented")
        void moreThanOnePositionCanBeMarkedAtOnce() {
            final ReportRequest request = new ReportRequest(MARKED, MARKED, MARKED, null, null, null,
                    null, null, null, null, KeyAction.ENTER, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(request))
                        .as("the contract carries the submission; resolving it is the service's job")
                        .isEmpty();
            }
            assertThat(List.of(request.monthlySelection(), request.yearlySelection(),
                    request.customSelection())).containsExactly(MARKED, MARKED, MARKED);
        }

        @Test
        @DisplayName("a monthly or yearly request carries no dates, because the legacy program derives "
                + "the window itself for those two selections")
        void aMonthlyOrYearlyRequestCarriesNoDates() {
            for (final ReportPeriod position : List.of(ReportPeriod.MONTHLY, ReportPeriod.YEARLY)) {
                final ReportRequest request = new ReportRequest(
                        position == ReportPeriod.MONTHLY ? MARKED : null,
                        position == ReportPeriod.YEARLY ? MARKED : null,
                        null, null, null, null, null, null, null, "Y", KeyAction.ENTER,
                        NavigationContext.empty());

                assertThat(request.startMonth()).isNull();
                assertThat(request.endYear()).isNull();
                assertThat(request.confirm()).isEqualTo("Y");
            }
        }

        @Test
        @DisplayName("an unmarked screen carries all three positions as absent, which is the state a "
                + "first entry to the screen arrives in and the state the catch-all clause answers")
        void anUnmarkedScreenCarriesAllThreePositionsAsAbsent() throws JsonProcessingException {
            final ReportRequest request = new ReportRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null);
            final JsonNode payload = payloadOf(request);

            assertThat(List.of(request.monthlySelection() == null,
                    request.yearlySelection() == null, request.customSelection() == null))
                    .containsExactly(true, true, true);
            assertThat(SELECTOR_PROPERTIES).allSatisfy(property ->
                    assertThat(payload.has(property)).isFalse());
        }

        @Test
        @DisplayName("the three report names the selection resolves to are the legacy screen literals "
                + "and are not the constant names, and they are published on the response, not here")
        void theResolvedReportNamesAreTheLegacyScreenLiterals() {
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo("Monthly");
            assertThat(ReportPeriod.YEARLY.getValue()).isEqualTo("Yearly");
            assertThat(ReportPeriod.CUSTOM.getValue()).isEqualTo("Custom");
            assertThat(ReportPeriod.MONTHLY.getValue()).isNotEqualTo(ReportPeriod.MONTHLY.name());
            assertThat(Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("the name is derived downstream, so it is not part of the submission")
                    .doesNotContain("reportName");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared validation bounds")
    class TheDeclaredValidationBounds {

        @ParameterizedTest(name = "{0} bounded at {1}")
        @CsvSource({
            "monthlySelection, 1",
            "yearlySelection, 1",
            "customSelection, 1",
            "startMonth, 2",
            "startDay, 2",
            "startYear, 4",
            "endMonth, 2",
            "endDay, 2",
            "endYear, 4",
            "confirm, 1"
        })
        @DisplayName("each bounded component carries the legacy field width")
        void eachBoundedComponentCarriesTheLegacyWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {

            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the two triples are bounded identically, so a start date cannot accept a value an "
                + "end date rejects")
        void theTwoTriplesAreBoundedIdentically() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("startMonth"))
                    .isEqualTo(declaredMaximumLength("endMonth"));
            assertThat(declaredMaximumLength("startDay"))
                    .isEqualTo(declaredMaximumLength("endDay"));
            assertThat(declaredMaximumLength("startYear"))
                    .isEqualTo(declaredMaximumLength("endYear"));
        }

        @Test
        @DisplayName("a fully populated request passes validation")
        void aFullyPopulatedRequestPassesValidation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aCustomPeriodRequest())).isEmpty();
            }
        }

        @Test
        @DisplayName("a request with nothing supplied passes validation, because a length bound says "
                + "nothing about presence and the cascade that requires a date runs in the service")
        void anEmptyRequestPassesValidation() {
            final ReportRequest empty = new ReportRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(empty)).isEmpty();
            }
        }

        @ParameterizedTest(name = "a three-character {0}")
        @ValueSource(strings = {"startMonth", "startDay", "endMonth", "endDay"})
        @DisplayName("a three-character month or day is reported against that component alone")
        void aThreeCharacterMonthOrDayIsReported(final String componentName) {
            final ReportRequest overBound = switch (componentName) {
                case "startMonth" -> new ReportRequest(null, null, null, "013", null, null, null,
                        null, null, null, null, null);
                case "startDay" -> new ReportRequest(null, null, null, null, "001", null, null, null,
                        null, null, null, null);
                case "endMonth" -> new ReportRequest(null, null, null, null, null, null, "013", null,
                        null, null, null, null);
                default -> new ReportRequest(null, null, null, null, null, null, null, "001", null,
                        null, null, null);
            };

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly(componentName);
            }
        }

        @Test
        @DisplayName("a five-digit year is reported, so a caller cannot smuggle a wider year past the "
                + "four-character screen field")
        void aFiveDigitYearIsReported() {
            final ReportRequest overBound = new ReportRequest(null, null, MARKED, "01", "01",
                    "20222", "12", "31", "20222", "Y", KeyAction.ENTER, NavigationContext.empty());

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("startYear", "endYear");
            }
        }

        @Test
        @DisplayName("a two-character confirmation flag is reported, because the legacy field holds one")
        void aTwoCharacterConfirmationFlagIsReported() {
            final ReportRequest overBound = new ReportRequest(null, null, null, null, null, null,
                    null, null, null, "YY", null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("confirm");
            }
        }

        @Test
        @DisplayName("a single-character value at each bound is accepted, so a legitimate one-digit "
                + "month is not rejected for being short")
        void aValueShorterThanItsBoundIsAccepted() {
            final ReportRequest shortValues = new ReportRequest(null, null, MARKED, "1", "1", "22",
                    "9", "9", "22", "Y", KeyAction.ENTER, NavigationContext.empty());

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(shortValues)).isEmpty();
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two requests built the same way are equal and share a hash code")
        void twoIdenticalRequestsAreEqual() {
            assertThat(aCustomPeriodRequest()).isEqualTo(aCustomPeriodRequest())
                    .hasSameHashCodeAs(aCustomPeriodRequest());
        }

        @Test
        @DisplayName("a start date and an end date swapped produce a different request, because the "
                + "window's direction is part of its identity")
        void aSwappedWindowProducesADifferentRequest() {
            final ReportRequest reversed = new ReportRequest(null, null, MARKED, "12", "31", "2022",
                    "01", "01", "2022", "Y", KeyAction.ENTER, NavigationContext.empty());

            assertThat(aCustomPeriodRequest()).isNotEqualTo(reversed);
        }

        @Test
        @DisplayName("an absent component is omitted from the payload")
        void anAbsentComponentIsOmittedFromThePayload() throws JsonProcessingException {
            final JsonNode payload = payloadOf(new ReportRequest(MARKED, null, null, null, null,
                    null, null, null, null, "Y", KeyAction.ENTER, null));

            assertThat(payload.get("monthlySelection").asText()).isEqualTo(MARKED);
            assertThat(payload.has("yearlySelection")).isFalse();
            assertThat(payload.has("customSelection")).isFalse();
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
            assertThat(payload.has("startMonth")).isFalse();
            assertThat(payload.has("endYear")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
        }

        @Test
        @DisplayName("all six date components are published under their own names, so a client can fill "
                + "one triple without disturbing the other")
        void allSixDateComponentsArePublishedUnderTheirOwnNames() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aCustomPeriodRequest());

            assertThat(payload.get("startMonth").asText()).isEqualTo("01");
            assertThat(payload.get("startDay").asText()).isEqualTo("01");
            assertThat(payload.get("startYear").asText()).isEqualTo("2022");
            assertThat(payload.get("endMonth").asText()).isEqualTo("12");
            assertThat(payload.get("endDay").asText()).isEqualTo("31");
            assertThat(payload.get("endYear").asText()).isEqualTo("2022");
        }

        @Test
        @DisplayName("a request survives a round trip through the module-equivalent mapper unchanged")
        void aRequestSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final ReportRequest original = aCustomPeriodRequest();

            assertThat(mapper.readValue(mapper.writeValueAsString(original), ReportRequest.class))
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("a payload marking a selector position binds, and one naming an enumerated period "
                + "binds nothing at all, which pins where the translation belongs")
        void aPayloadMarkingASelectorPositionBinds() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();

            assertThat(mapper.readValue("{\"customSelection\":\"Y\"}", ReportRequest.class)
                    .customSelection()).isEqualTo(MARKED);

            final ReportRequest fromEnumeratedPeriod =
                    mapper.readValue("{\"reportPeriod\":\"CUSTOM\"}", ReportRequest.class);
            assertThat(List.of(fromEnumeratedPeriod.monthlySelection() == null,
                    fromEnumeratedPeriod.yearlySelection() == null,
                    fromEnumeratedPeriod.customSelection() == null))
                    .as("an enumerated period is not a submission this contract understands")
                    .containsExactly(true, true, true);

            assertThat(ReportPeriod.fromValue("Custom")).contains(ReportPeriod.CUSTOM);
            assertThat(ReportPeriod.fromValue("CUSTOM")).isEmpty();
        }
    }
}
