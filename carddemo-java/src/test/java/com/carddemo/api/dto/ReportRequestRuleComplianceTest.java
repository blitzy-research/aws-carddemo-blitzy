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
 * <p><strong>One enumerated period, not a selector triple.</strong> The screen offers three
 * one-character selector positions - {@code MONTHLYI} at symbolic-map line 60, {@code YEARLYI} at
 * line 66 and {@code CUSTOMI} at line 72 - but they are mutually exclusive: {@code CORPT00C} tests
 * them in the fixed order monthly, yearly, custom at line 213 and acts on exactly one, falling to its
 * catch-all at line 438 when none is marked. Because the screen can only ever mean one period, the
 * request carries one enumerated component rather than three characters. That makes a multiply-marked
 * state unrepresentable at the boundary instead of leaving every consumer to re-derive which position
 * won, and it relocates nothing: with one value on the wire there is no tie left for the fixed order
 * to break. The vocabulary deliberately holds no member for the unmarked state, so absence stays
 * absence and the catch-all message stays the service's to report. The derived report name is not on
 * this contract either: the program writes it at lines 214, 240 and 433 from the period it just
 * resolved, and it is the period's own carried value rather than a component of its own.
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

    /** The one character a marked selector position carried on the legacy screen. */
    private static final String MARKED = "Y";

    /** The single published property the three collapsed selector positions became. */
    private static final String PERIOD_PROPERTY = "reportPeriod";

    /** The three selector properties the collapse removed, asserted absent from the contract. */
    private static final List<String> REMOVED_SELECTOR_PROPERTIES =
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
        return new ReportRequest(ReportPeriod.CUSTOM, "01", "01", "2022", "12", "31", "2022", "Y",
                KeyAction.ENTER, NavigationContext.empty());
    }

    /**
     * Builds a request carrying exactly one period and nothing else supplied.
     *
     * @param period the period the operator chose
     * @return a request carrying only that period and the attention key
     */
    private static ReportRequest choosing(final ReportPeriod period) {
        return new ReportRequest(
                period, null, null, null, null, null, null, null, KeyAction.ENTER, null);
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
        @DisplayName("the request declares ten components, the collapsed period, two date triples, "
                + "the confirmation flag and two control components")
        void theRequestDeclaresTenComponents() {
            final List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly(PERIOD_PROPERTY, "startMonth", "startDay",
                    "startYear", "endMonth", "endDay", "endYear", "confirm", "keyAction",
                    "navigationContext");
            assertThat(declared).hasSize(10);
            assertThat(declared.get(0))
                    .as("the collapsed period leads the contract, where the three positions used to")
                    .isEqualTo(PERIOD_PROPERTY);
        }

        @Test
        @DisplayName("no per-position selector and no derived report name is carried inbound, because "
                + "the positions are mutually exclusive and the program derives the name itself")
        void noPerPositionSelectorOrDerivedNameIsCarried() {
            final List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).doesNotContainAnyElementsOf(REMOVED_SELECTOR_PROPERTIES);
            assertThat(declared).doesNotContain("reportName", "selection", "selector");
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

            assertThat(request.reportPeriod()).isSameAs(ReportPeriod.CUSTOM);
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
    @DisplayName("the collapsed reporting period")
    class TheCollapsedReportingPeriod {

        @Test
        @DisplayName("the period is one enumerated component carrying exactly the three report types "
                + "the legacy screen names, with no screen width and no member for the unmarked state")
        void thePeriodIsOneEnumeratedComponent() throws NoSuchMethodException {
            assertThat(ReportRequest.class.getDeclaredMethod(PERIOD_PROPERTY).getReturnType())
                    .isEqualTo(ReportPeriod.class);
            assertThat(ReportRequest.class.getDeclaredMethod(PERIOD_PROPERTY)
                    .getAnnotation(Size.class))
                    .as("a member of a closed vocabulary has no screen width to measure")
                    .isNull();
            assertThat(ReportPeriod.values()).hasSize(3);
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .containsExactly("MONTHLY", "YEARLY", "CUSTOM")
                    .doesNotContain("NONE", "UNKNOWN", "OTHER", "INVALID", "DEFAULT");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ReportPeriod.class)
        @DisplayName("every period can be chosen on its own, and crosses the wire under the one "
                + "collapsed name carrying its bare mixed-case value")
        void everyPeriodCanBeChosenOnItsOwn(final ReportPeriod period)
                throws JsonProcessingException {
            final ReportRequest request = choosing(period);
            final JsonNode payload = payloadOf(request);

            assertThat(request.reportPeriod()).isSameAs(period);
            assertThat(payload.get(PERIOD_PROPERTY).asText())
                    .as("the wire form of a closed vocabulary is its member identifier")
                    .isEqualTo(period.name());
            assertThat(period.getValue())
                    .as("the report name the program writes is the carried value, never the identifier")
                    .isNotEqualTo(period.name());
            assertThat(REMOVED_SELECTOR_PROPERTIES).allSatisfy(property ->
                    assertThat(payload.has(property))
                            .as("%s no longer exists on the contract", property)
                            .isFalse());
        }

        @Test
        @DisplayName("a multiply-marked screen state has no representation on this contract, which is "
                + "the point of the collapse: the wrong state cannot be built at all")
        void aMultiplyMarkedStateHasNoRepresentation() {
            final ReportRequest request = choosing(ReportPeriod.MONTHLY);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(request))
                        .as("one chosen period is always a valid submission at the boundary")
                        .isEmpty();
            }
            assertThat(Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("there is no second period component to combine with the first")
                    .doesNotContainAnyElementsOf(REMOVED_SELECTOR_PROPERTIES);
            assertThat(request.reportPeriod())
                    .as("the single component holds one period and cannot hold two")
                    .isSameAs(ReportPeriod.MONTHLY);
        }

        @Test
        @DisplayName("a monthly or yearly request carries no dates, because the legacy program derives "
                + "the window itself for those two periods")
        void aMonthlyOrYearlyRequestCarriesNoDates() {
            for (final ReportPeriod period : List.of(ReportPeriod.MONTHLY, ReportPeriod.YEARLY)) {
                final ReportRequest request = new ReportRequest(
                        period, null, null, null, null, null, null, "Y", KeyAction.ENTER,
                        NavigationContext.empty());

                assertThat(request.startMonth()).isNull();
                assertThat(request.endYear()).isNull();
                assertThat(request.confirm()).isEqualTo("Y");
            }
        }

        @Test
        @DisplayName("an unmarked screen carries the period as absent, which is the state a first "
                + "entry to the screen arrives in and the state the catch-all clause answers")
        void anUnmarkedScreenCarriesThePeriodAsAbsent() throws JsonProcessingException {
            final ReportRequest request = new ReportRequest(null, null, null, null, null, null, null,
                    null, null, null);
            final JsonNode payload = payloadOf(request);

            assertThat(request.reportPeriod()).isNull();
            assertThat(payload.has(PERIOD_PROPERTY))
                    .as("absence is written as an omission, never as a synthesised member")
                    .isFalse();
            assertThat(ReportPeriod.fromValue(MARKED))
                    .as("the legacy screen character is not itself a period value")
                    .isEmpty();
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
                    null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(empty)).isEmpty();
            }
        }

        @ParameterizedTest(name = "a three-character {0}")
        @ValueSource(strings = {"startMonth", "startDay", "endMonth", "endDay"})
        @DisplayName("a three-character month or day is reported against that component alone")
        void aThreeCharacterMonthOrDayIsReported(final String componentName) {
            final ReportRequest overBound = switch (componentName) {
                case "startMonth" -> new ReportRequest(null, "013", null, null, null, null, null,
                        null, null, null);
                case "startDay" -> new ReportRequest(null, null, "001", null, null, null, null,
                        null, null, null);
                case "endMonth" -> new ReportRequest(null, null, null, null, "013", null, null,
                        null, null, null);
                default -> new ReportRequest(null, null, null, null, null, "001", null, null, null,
                        null);
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
            final ReportRequest overBound = new ReportRequest(ReportPeriod.CUSTOM, "01", "01",
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
                    null, "YY", null, null);

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
            final ReportRequest shortValues = new ReportRequest(ReportPeriod.CUSTOM, "1", "1", "22",
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
            final ReportRequest reversed = new ReportRequest(ReportPeriod.CUSTOM, "12", "31", "2022",
                    "01", "01", "2022", "Y", KeyAction.ENTER, NavigationContext.empty());

            assertThat(aCustomPeriodRequest()).isNotEqualTo(reversed);
        }

        @Test
        @DisplayName("an absent component is omitted from the payload")
        void anAbsentComponentIsOmittedFromThePayload() throws JsonProcessingException {
            final JsonNode payload = payloadOf(new ReportRequest(ReportPeriod.MONTHLY, null, null,
                    null, null, null, null, "Y", KeyAction.ENTER, null));

            assertThat(payload.get(PERIOD_PROPERTY).asText()).isEqualTo("MONTHLY");
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
        @DisplayName("a payload naming a period binds it, a payload marking an old selector position "
                + "binds nothing, and the vocabulary itself refuses an upper-case value")
        void aPayloadNamingAPeriodBinds() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();

            assertThat(mapper.readValue("{\"reportPeriod\":\"CUSTOM\"}", ReportRequest.class)
                    .reportPeriod())
                    .as("the member name is what the wire form uses for an enumeration")
                    .isSameAs(ReportPeriod.CUSTOM);

            final ReportRequest fromRemovedSelector =
                    mapper.readValue("{\"customSelection\":\"" + MARKED + "\"}",
                            ReportRequest.class);
            assertThat(fromRemovedSelector.reportPeriod())
                    .as("a per-position marker is no longer a submission this contract understands, "
                            + "and it is tolerated as an unknown property rather than refused")
                    .isNull();

            assertThat(ReportPeriod.fromValue("Custom"))
                    .as("the carried value is the bare mixed-case report name")
                    .contains(ReportPeriod.CUSTOM);
            assertThat(ReportPeriod.fromValue("CUSTOM"))
                    .as("value lookup never folds case, so the member name is not a carried value")
                    .isEmpty();
        }
    }
}
