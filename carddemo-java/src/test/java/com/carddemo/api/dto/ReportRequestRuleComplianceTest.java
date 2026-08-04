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
 * one-character selector positions - {@code MONTHLYI} at symbolic-map line 60, {@code YEARLYI} at
 * line 66 and {@code CUSTOMI} at line 72 - and {@code CORPT00C} tests them in the fixed order
 * monthly, yearly, custom at lines 214, 240 and 256, acting on the first non-blank one and falling to
 * its catch-all at line 437 when none is marked. All three are carried as three separately markable
 * one-character components. An earlier revision collapsed them into a single enumerated component, on
 * the grounds that the positions are mutually exclusive and that a multiply-marked state should be
 * unrepresentable; neither premise holds. Three independently markable fields mean a submission
 * carrying two or three marks is a state the 3270 screen can actually produce, and the program does
 * not treat it as an error - it resolves it by that fixed order. A single value cannot express
 * "monthly and custom were both marked", so it cannot reproduce the resolution either: it forces the
 * client to choose, which relocates the program's own first-match-wins decision onto the caller.
 * Collapsing also discarded the marker characters, which the program never inspects beyond
 * non-blankness and which are therefore carried verbatim. The unmarked state is three blank or absent
 * values rather than a synthesised vocabulary member, so absence stays absence and the catch-all
 * message stays the service's to report. The derived report name is not on this contract either: the
 * program writes it at lines 214, 240 and 433 from the period it resolved, and the service publishes
 * what it resolved on {@link ReportResponse} rather than accepting it here.
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

    /**
     * The character used as a selection mark throughout this file.
     *
     * <p>Any non-blank character is a mark: the program tests only that a marker field is non-blank and
     * never which character it holds, so the specific value is arbitrary and is fixed here only so that
     * every assertion in the file names the same one. It matches the value the service-tier tests use.
     */
    private static final String SELECTION_MARK = "Y";

    /**
     * Returns the month-to-date marker that expresses the given period, or {@code null} when it does not.
     *
     * @param period the period being expressed, which may be {@code null} for the unmarked state
     * @return the mark when the period is month-to-date, otherwise {@code null}
     */
    private static String monthlyMarkerFor(final ReportPeriod period) {
        return period == ReportPeriod.MONTHLY ? SELECTION_MARK : null;
    }

    /**
     * Returns the year-to-date marker that expresses the given period, or {@code null} when it does not.
     *
     * @param period the period being expressed, which may be {@code null} for the unmarked state
     * @return the mark when the period is year-to-date, otherwise {@code null}
     */
    private static String yearlyMarkerFor(final ReportPeriod period) {
        return period == ReportPeriod.YEARLY ? SELECTION_MARK : null;
    }

    /**
     * Returns the operator-range marker that expresses the given period, or {@code null} when it does not.
     *
     * @param period the period being expressed, which may be {@code null} for the unmarked state
     * @return the mark when the period is the operator-supplied range, otherwise {@code null}
     */
    private static String customMarkerFor(final ReportPeriod period) {
        return period == ReportPeriod.CUSTOM ? SELECTION_MARK : null;
    }

    /**
     * Applies the legacy ordered evaluation to a request and reports which period it resolves to.
     *
     * <p>This restates the order the program uses - the month-to-date marker at {@code CORPT00C} line
     * 213, then the year-to-date marker at line 239, then the operator-range marker at line 256, first
     * non-blank winning - so that assertions about what a submission <em>means</em> can be written
     * against the three markers the contract now carries. The resolution itself belongs to the service;
     * this exists so a contract test can show that each arm, including a multiply-marked submission, is
     * expressible.
     *
     * @param request the submission to resolve, never {@code null}
     * @return the period the ordered evaluation selects, or {@code null} when nothing is marked
     */
    private static ReportPeriod resolvedPeriodOf(final ReportRequest request) {
        if (isMarked(request.monthlySelection())) {
            return ReportPeriod.MONTHLY;
        }
        if (isMarked(request.yearlySelection())) {
            return ReportPeriod.YEARLY;
        }
        if (isMarked(request.customSelection())) {
            return ReportPeriod.CUSTOM;
        }
        return null;
    }

    /**
     * Reports whether a marker field counts as marked, which is simply whether it is non-blank.
     *
     * @param marker the marker field as transmitted, which may be {@code null}
     * @return {@code true} when the field holds any non-blank character
     */
    private static boolean isMarked(final String marker) {
        return marker != null && !marker.isBlank();
    }

    /** The one character a marked selector position carried on the legacy screen. */
    private static final String MARKED = "Y";

    /**
     * The three published selector properties, in the order {@code app/cbl/CORPT00C.cbl} tests them:
     * month-to-date at line 214, year-to-date at line 240, operator-supplied range at line 256.
     */
    private static final List<String> SELECTOR_PROPERTIES =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    /**
     * The collapsed property an earlier revision published in place of the three positions, asserted
     * absent because a derived report type on the inbound contract relocates the program's own
     * first-match-wins resolution onto the caller.
     */
    private static final String REMOVED_PERIOD_PROPERTY = "reportPeriod";

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
        return new ReportRequest(null, null, "Y", "01", "01", "2022", "12", "31", "2022", "Y",
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
                monthlyMarkerFor(period), yearlyMarkerFor(period), customMarkerFor(period), null, null, null, null, null, null, null, KeyAction.ENTER, null);
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
        @DisplayName("the request declares twelve components, the three selector positions, two date "
                + "triples, the confirmation flag and two control components")
        void theRequestDeclaresTwelveComponents() {
            final List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("monthlySelection", "yearlySelection",
                    "customSelection", "startMonth", "startDay",
                    "startYear", "endMonth", "endDay", "endYear", "confirm", "keyAction",
                    "navigationContext");
            assertThat(declared).hasSize(12);
            assertThat(declared.subList(0, SELECTOR_PROPERTIES.size()))
                    .as("the three positions lead the contract in the program's own test order, so "
                            + "the resolution rule is visible in the declaration itself")
                    .containsExactlyElementsOf(SELECTOR_PROPERTIES);
        }

        @Test
        @DisplayName("no collapsed period component and no derived report name is carried inbound, "
                + "because the program performs the ordered resolution and derives the name itself")
        void noCollapsedPeriodOrDerivedNameIsCarried() {
            final List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared)
                    .as("a derived report type on the inbound contract would relocate the ordered "
                            + "first-match-wins resolution the program performs onto the caller")
                    .doesNotContain(REMOVED_PERIOD_PROPERTY);
            assertThat(declared)
                    .as("the program derives the mixed-case report name itself, so no name arrives")
                    .doesNotContain("reportName", "reportType", "period", "resolvedPeriod");
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

            assertThat(resolvedPeriodOf(request)).isSameAs(ReportPeriod.CUSTOM);
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
    @DisplayName("the three reporting-period selection markers")
    class TheThreeSelectionMarkers {

        @Test
        @DisplayName("the three markers are three one-character screen components, and the report "
                + "vocabulary the service produces still names exactly the three legacy types with no "
                + "member for the unmarked state")
        void theMarkersAreThreeScreenComponentsAndTheVocabularyIsProducedNotSupplied()
                throws NoSuchMethodException, NoSuchFieldException {
            for (final String marker : SELECTOR_PROPERTIES) {
                assertThat(ReportRequest.class.getDeclaredMethod(marker).getReturnType())
                        .as("%s is the screen character, never a derived vocabulary member", marker)
                        .isEqualTo(String.class);
                assertThat(ReportRequest.class.getDeclaredField(marker).getAnnotation(Size.class)
                        .max())
                        .as("%s is bounded at the one character the mapset declares", marker)
                        .isEqualTo(1);
            }
            assertThat(Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("the resolved period is produced by the service on ReportResponse, so no "
                            + "enumerated component is supplied inbound")
                    .doesNotContain(REMOVED_PERIOD_PROPERTY);
            assertThat(ReportPeriod.values()).hasSize(3);
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .containsExactly("MONTHLY", "YEARLY", "CUSTOM")
                    .doesNotContain("NONE", "UNKNOWN", "OTHER", "INVALID", "DEFAULT");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ReportPeriod.class)
        @DisplayName("every period can be marked on its own, and crosses the wire as the marker "
                + "character on its own position rather than under one collapsed name")
        void everyPeriodCanBeMarkedOnItsOwn(final ReportPeriod period)
                throws JsonProcessingException {
            final ReportRequest request = choosing(period);
            final JsonNode payload = payloadOf(request);

            assertThat(resolvedPeriodOf(request)).isSameAs(period);

            final String markedProperty = SELECTOR_PROPERTIES.get(switch (period) {
                case MONTHLY -> 0;
                case YEARLY -> 1;
                case CUSTOM -> 2;
            });
            assertThat(payload.get(markedProperty).asText())
                    .as("the wire form of a marked screen field is the marker character itself, "
                            + "carried verbatim because the program only tests non-blankness")
                    .isEqualTo(MARKED);
            assertThat(SELECTOR_PROPERTIES.stream()
                    .filter(property -> !property.equals(markedProperty)).toList())
                    .allSatisfy(property -> assertThat(payload.has(property))
                            .as("%s stays absent when it was not marked", property)
                            .isFalse());
            assertThat(period.getValue())
                    .as("the report name the program writes is the carried value, never the identifier")
                    .isNotEqualTo(period.name());
            assertThat(payload.has(REMOVED_PERIOD_PROPERTY))
                    .as("no collapsed period name is written")
                    .isFalse();
        }

        @Test
        @DisplayName("a multiply-marked screen state has a representation on this contract and is a "
                + "valid submission, because the 3270 screen can produce it and the program resolves "
                + "it by order rather than rejecting it")
        void aMultiplyMarkedStateHasARepresentation() {
            final ReportRequest request = new ReportRequest(MARKED, MARKED, MARKED,
                    null, null, null, null, null, null, "Y", KeyAction.ENTER,
                    NavigationContext.empty());

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(request))
                        .as("marking more than one position is a state the screen produces, so the "
                                + "boundary must not declare it invalid - no exclusivity rule applies")
                        .isEmpty();
            }
            assertThat(isMarked(request.monthlySelection()))
                    .as("each position keeps its own mark rather than being overwritten by the last")
                    .isTrue();
            assertThat(isMarked(request.yearlySelection())).isTrue();
            assertThat(isMarked(request.customSelection())).isTrue();
            assertThat(resolvedPeriodOf(request))
                    .as("the tie is broken by the program's own order - month-to-date is tested "
                            + "first at CORPT00C line 214 and therefore wins")
                    .isSameAs(ReportPeriod.MONTHLY);
        }

        @Test
        @DisplayName("a monthly or yearly request carries no dates, because the legacy program derives "
                + "the window itself for those two periods")
        void aMonthlyOrYearlyRequestCarriesNoDates() {
            for (final ReportPeriod period : List.of(ReportPeriod.MONTHLY, ReportPeriod.YEARLY)) {
                final ReportRequest request = new ReportRequest(
                        monthlyMarkerFor(period), yearlyMarkerFor(period), customMarkerFor(period), null, null, null, null, null, null, "Y", KeyAction.ENTER,
                        NavigationContext.empty());

                assertThat(request.startMonth()).isNull();
                assertThat(request.endYear()).isNull();
                assertThat(request.confirm()).isEqualTo("Y");
            }
        }

        @Test
        @DisplayName("an unmarked screen leaves all three positions absent, which is the state a "
                + "first entry to the screen arrives in and the state the catch-all clause answers")
        void anUnmarkedScreenLeavesAllThreePositionsAbsent() throws JsonProcessingException {
            final ReportRequest request = new ReportRequest(null, null, null, null, null, null, null, null, null,
                    null, null, null);
            final JsonNode payload = payloadOf(request);

            assertThat(resolvedPeriodOf(request)).isNull();
            assertThat(SELECTOR_PROPERTIES).allSatisfy(property ->
                    assertThat(payload.has(property))
                            .as("absence of %s is written as an omission, never as a synthesised "
                                    + "member and never as a blank mark", property)
                            .isFalse());
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
            final ReportRequest empty = new ReportRequest(null, null, null, null, null, null, null, null, null,
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
                case "startMonth" -> new ReportRequest(null, null, null, "013", null, null, null, null, null,
                        null, null, null);
                case "startDay" -> new ReportRequest(null, null, null, null, "001", null, null, null, null,
                        null, null, null);
                case "endMonth" -> new ReportRequest(null, null, null, null, null, null, "013", null, null,
                        null, null, null);
                default -> new ReportRequest(null, null, null, null, null, null, null, "001", null, null, null,
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
            final ReportRequest overBound = new ReportRequest(null, null, "Y", "01", "01",
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
            final ReportRequest overBound = new ReportRequest(null, null, null, null, null, null, null, null,
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
            final ReportRequest shortValues = new ReportRequest(null, null, "Y", "1", "1", "22",
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
            final ReportRequest reversed = new ReportRequest(null, null, "Y", "12", "31", "2022",
                    "01", "01", "2022", "Y", KeyAction.ENTER, NavigationContext.empty());

            assertThat(aCustomPeriodRequest()).isNotEqualTo(reversed);
        }

        @Test
        @DisplayName("an absent component is omitted from the payload")
        void anAbsentComponentIsOmittedFromThePayload() throws JsonProcessingException {
            final JsonNode payload = payloadOf(new ReportRequest("Y", null, null, null, null,
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
        @DisplayName("a payload marking a selector position binds it, a payload naming a derived period "
                + "binds nothing, and the period vocabulary still refuses an upper-case value")
        void aPayloadMarkingASelectorBinds() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();

            final ReportRequest fromMarker =
                    mapper.readValue("{\"customSelection\":\"" + MARKED + "\"}",
                            ReportRequest.class);
            assertThat(fromMarker.customSelection())
                    .as("the marker positions are what the wire form carries, one property each")
                    .isEqualTo(MARKED);
            assertThat(resolvedPeriodOf(fromMarker))
                    .as("and the ordered evaluation resolves that submission to the operator range")
                    .isSameAs(ReportPeriod.CUSTOM);

            // An earlier revision accepted a derived period on the request instead of the three
            // markers, which is what made the contract narrower than the screen. A payload naming one
            // now binds nothing: it is tolerated as an unknown property rather than refused, so a
            // client written against the earlier shape is not broken with a message the legacy screen
            // never emits - it simply submits nothing, which the service reports as the unmarked state.
            final ReportRequest fromRemovedPeriod =
                    mapper.readValue("{\"reportPeriod\":\"CUSTOM\"}", ReportRequest.class);
            assertThat(resolvedPeriodOf(fromRemovedPeriod))
                    .as("a derived period is no longer a submission this contract understands")
                    .isNull();
            assertThat(fromRemovedPeriod.monthlySelection()).isNull();
            assertThat(fromRemovedPeriod.yearlySelection()).isNull();
            assertThat(fromRemovedPeriod.customSelection()).isNull();

            // The vocabulary itself is unchanged and is still referenced rather than redeclared: it is
            // what the response publishes once the service has resolved a period.
            assertThat(ReportPeriod.fromValue("Custom"))
                    .as("the carried value is the bare mixed-case report name")
                    .contains(ReportPeriod.CUSTOM);
            assertThat(ReportPeriod.fromValue("CUSTOM"))
                    .as("value lookup never folds case, so the member name is not a carried value")
                    .isEmpty();
        }

        @Test
        @DisplayName("a multiply-marked submission is representable, which is the whole reason three "
                + "markers are carried instead of one derived period")
        void aMultiplyMarkedSubmissionIsRepresentable() {
            final ReportRequest twoMarked = new ReportRequest(MARKED, null, MARKED, "01", "01",
                    "2022", "12", "31", "2022", null, KeyAction.ENTER, NavigationContext.empty());

            assertThat(twoMarked.monthlySelection()).isEqualTo(MARKED);
            assertThat(twoMarked.customSelection()).isEqualTo(MARKED);
            assertThat(resolvedPeriodOf(twoMarked))
                    .as("the month-to-date position is tested first, so it wins the tie")
                    .isSameAs(ReportPeriod.MONTHLY);

            final ReportRequest allThreeMarked = new ReportRequest(MARKED, MARKED, MARKED, null, null,
                    null, null, null, null, null, KeyAction.ENTER, NavigationContext.empty());

            assertThat(resolvedPeriodOf(allThreeMarked))
                    .as("and it wins however many positions are marked")
                    .isSameAs(ReportPeriod.MONTHLY);

            final ReportRequest yearlyAndCustom = new ReportRequest(null, MARKED, MARKED, null, null,
                    null, null, null, null, null, KeyAction.ENTER, NavigationContext.empty());

            assertThat(resolvedPeriodOf(yearlyAndCustom))
                    .as("with the first position blank the second wins, not the third")
                    .isSameAs(ReportPeriod.YEARLY);
        }

        @Test
        @DisplayName("any non-blank character is a mark, because the program tests only for "
                + "non-blankness and never for a particular character")
        void anyNonBlankCharacterIsAMark() {
            for (final String mark : new String[] {"Y", "S", "X", "1", "*"}) {
                final ReportRequest marked = new ReportRequest(null, null, mark, "01", "01", "2022",
                        "12", "31", "2022", null, KeyAction.ENTER, NavigationContext.empty());

                assertThat(marked.customSelection())
                        .as("the character crosses verbatim rather than being normalised to one value")
                        .isEqualTo(mark);
                assertThat(resolvedPeriodOf(marked)).isSameAs(ReportPeriod.CUSTOM);
            }

            final ReportRequest blankMark = new ReportRequest(null, null, " ", null, null, null, null,
                    null, null, null, KeyAction.ENTER, NavigationContext.empty());

            assertThat(blankMark.customSelection())
                    .as("a blank is carried rather than nulled, because the field was transmitted")
                    .isEqualTo(" ");
            assertThat(resolvedPeriodOf(blankMark))
                    .as("but a blank field is not a mark, which is the unmarked state")
                    .isNull();
        }
    }
}
