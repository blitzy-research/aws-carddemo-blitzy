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
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReportRequest}, the request body of legacy transaction {@code CR00}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the transaction-report submission: the ten declared components,
 * the three-part date geometry, the single reporting period, the single-character confirmation, the
 * wire form under the module's declared serialisation settings, and the value fidelity the ordered
 * service cascade depends on. The fourteen-stage validation, the monthly and yearly range
 * derivation, the three-part-to-single-value date conversion, the confirmation branch and every
 * response text live in the report service and on {@link ReportResponse}, and are deliberately not
 * re-asserted here - a convenience member on this boundary would put a fragment of that behaviour
 * where the service-tier tests would never reach it.
 *
 * <h2>The three period markers stay three markers</h2>
 *
 * <p>The map declares three one-character markers - monthly, yearly and custom - and the program
 * tests them in that order at {@code app/cbl/CORPT00C.cbl} lines 214, 240 and 256, acting on the
 * first non-blank one it finds and reporting at line 437 a submission that marked none. All three
 * are therefore carried as three separately markable one-character components rather than collapsed
 * into a single enumerated value. An earlier revision collapsed them, on the grounds that the screen
 * can only ever mean one period and that a multiply-marked state should be unrepresentable; both
 * premises are wrong. Three independently markable fields mean a 3270 submission carrying two or
 * three marks is a state the screen actually produces, and the program does not reject it - it
 * resolves it by order. A single enumerated value cannot express "monthly and custom were both
 * marked", so it cannot reproduce the resolution either: it forces the client to choose, which moves
 * the program's own first-match-wins decision onto the caller. Collapsing also discarded the marker
 * characters themselves, which the program never inspects beyond non-blankness and which are
 * therefore carried verbatim. Tests below pin all of that: the three markers are the leading
 * components in the program's own test order and are each the screen character bounded at one, the
 * unmarked state renders as three omissions and never as a synthesised member, a multiply-marked
 * submission serialises intact, and no collapsed period component or derived accessor is declared.
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p>Every width is restated as a literal in this file from the symbolic map
 * {@code app/cpy-bms/CORPT00.CPY} and the mapset {@code app/bms/CORPT00.bms}: 2 for a month part, 2
 * for a day part, 4 for a year part and 1 for the confirmation. They are not read out of the class
 * under test. Each selection marker carries a width of 1 for the same reason: it is a fixed-width
 * screen field, not a member of a derived vocabulary.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>A pure unit test: no context, no connection, no container. Payloads come from
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in this package where the
 * module's four declared serialisation settings are written by hand. That evidences the shape this
 * type takes under those settings and makes no claim about the mapper a deployed instance holds;
 * {@link ApplicationJsonContractTest} is the in-boundary evidence for the deployed object.
 *
 * <p>Provenance: checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.
 */
@DisplayName("ReportRequest :: report-request contract of legacy transaction CR00")
class ReportRequestCoverageTest {

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

    /** The twelve components in the order the screen presents the items they mirror. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "monthlySelection", "yearlySelection", "customSelection",
            "startMonth", "startDay", "startYear",
            "endMonth", "endDay", "endYear", "confirm", "keyAction", "navigationContext");

    /**
     * The three selection markers, in the order {@code app/cbl/CORPT00C.cbl} tests them: month-to-date
     * at line 214, yearly at line 240, custom at line 256. Order is asserted rather than incidental
     * because it is the resolution rule for a multiply-marked submission.
     */
    private static final List<String> SELECTION_COMPONENTS =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    /** The component that carries the first-tested marker. */
    private static final String MONTHLY_COMPONENT = "monthlySelection";

    /** The component the collapsed enumeration used to occupy, which must no longer be declared. */
    private static final String REMOVED_PERIOD_COMPONENT = "reportPeriod";

    /**
     * The bare mixed-case report name each period carries, restated here from
     * {@code app/cbl/CORPT00C.cbl} lines 214, 240 and 433 rather than read out of the vocabulary, so a
     * literal edited on the enumeration alone fails here instead of agreeing with itself.
     */
    private static final java.util.Map<ReportPeriod, String> REPORT_NAMES_BY_PERIOD = java.util.Map.of(
            ReportPeriod.MONTHLY, "Monthly",
            ReportPeriod.YEARLY, "Yearly",
            ReportPeriod.CUSTOM, "Custom");

    /** Declared width of a month part, restated from the symbolic map. */
    private static final int EXPECTED_MONTH_WIDTH = 2;

    /** Declared width of a day part, restated from the symbolic map. */
    private static final int EXPECTED_DAY_WIDTH = 2;

    /** Declared width of a year part, restated from the symbolic map. */
    private static final int EXPECTED_YEAR_WIDTH = 4;

    /** Declared width of the confirmation character, restated from the symbolic map. */
    private static final int EXPECTED_CONFIRM_WIDTH = 1;

    /** The six date parts in screen order, start date first. */
    private static final List<String> DATE_PARTS = List.of(
            "startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear");

    /** Shared validator factory, opened once and closed once. */
    private static ValidatorFactory validatorFactory;

    /** Validator drawn from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the validator factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the validator factory opened by {@link #openValidatorFactory()}. */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    /**
     * Builds a custom-period request over the supplied six date parts.
     *
     * @param startMonth the start month part
     * @param startDay the start day part
     * @param startYear the start year part
     * @param endMonth the end month part
     * @param endDay the end day part
     * @param endYear the end year part
     * @return a custom-period request with no confirmation, key or navigation state
     */
    private static ReportRequest customPeriod(String startMonth, String startDay, String startYear,
            String endMonth, String endDay, String endYear) {
        return new ReportRequest(null, null, "Y", startMonth, startDay, startYear,
                endMonth, endDay, endYear, null, null, null);
    }

    /**
     * Builds a request carrying only the named date part, so a violation can be attributed.
     *
     * @param part the date-part component to populate
     * @param value the value to place in it
     * @return a request carrying that one date part
     */
    private static ReportRequest carryingPart(String part, String value) {
        return new ReportRequest(
                null, null, null,
                "startMonth".equals(part) ? value : null,
                "startDay".equals(part) ? value : null,
                "startYear".equals(part) ? value : null,
                "endMonth".equals(part) ? value : null,
                "endDay".equals(part) ? value : null,
                "endYear".equals(part) ? value : null,
                null, null, null);
    }

    /**
     * Serialises a request and parses the result back into a tree.
     *
     * @param request the request to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(ReportRequest request) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the twelve components are declared in screen order, the three selection markers "
                + "first in the order the program tests them and the two transport components last")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @ParameterizedTest(name = "{0} is bounded at {1}")
        @CsvSource({
            "startMonth,2", "startDay,2", "startYear,4",
            "endMonth,2", "endDay,2", "endYear,4", "confirm,1",
        })
        @DisplayName("each bounded component declares exactly the width the symbolic map declares")
        void eachBoundedComponentDeclaresItsMapWidth(String component, int expectedWidth)
                throws NoSuchFieldException {
            Size bound = ReportRequest.class.getDeclaredField(component).getAnnotation(Size.class);

            assertThat(bound).as("%s mirrors a fixed-width map item", component).isNotNull();
            assertThat(bound.max()).isEqualTo(expectedWidth);
        }

        @Test
        @DisplayName("the six date parts are carried as characters so that a two-digit part keeps "
                + "its leading zero and a blank part stays blank")
        void theSixDatePartsAreCarriedAsCharacters() {
            for (String part : DATE_PARTS) {
                RecordComponent component = Arrays.stream(
                                ReportRequest.class.getRecordComponents())
                        .filter(candidate -> candidate.getName().equals(part))
                        .findFirst()
                        .orElseThrow();

                assertThat(component.getType())
                        .as("%s is a screen item, never an integer and never a date type", part)
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the three period markers arrive as three separately markable one-character "
                + "components declared in the order the program tests them, so a multiply-marked "
                + "screen state is expressible at the boundary")
        void theThreePeriodMarkersArriveAsThreeIndependentComponents() {
            RecordComponent[] components = ReportRequest.class.getRecordComponents();

            assertThat(Arrays.stream(components).limit(SELECTION_COMPONENTS.size())
                    .map(RecordComponent::getName).toList())
                    .as("the three markers lead, in the CORPT00C test order 214 then 240 then 256")
                    .containsExactlyElementsOf(SELECTION_COMPONENTS);
            for (RecordComponent component : Arrays.stream(components)
                    .limit(SELECTION_COMPONENTS.size()).toList()) {
                assertThat(component.getType())
                        .as("%s is the screen character itself, never a derived vocabulary member",
                                component.getName())
                        .isEqualTo(String.class);
            }
            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .as("no collapsed enumerated period is declared alongside the markers")
                    .doesNotContain(REMOVED_PERIOD_COMPONENT);
        }

        @Test
        @DisplayName("each selection marker is bounded at the one character the mapset declares, "
                + "because a marker is a fixed-width screen value and not a vocabulary member")
        void eachSelectionMarkerIsBoundedAtOneCharacter() throws NoSuchFieldException {
            for (String marker : SELECTION_COMPONENTS) {
                Size bound = ReportRequest.class.getDeclaredField(marker).getAnnotation(Size.class);

                assertThat(bound)
                        .as("%s is a one-character screen field and declares its width", marker)
                        .isNotNull();
                assertThat(bound.max())
                        .as("%s is bounded at the mapset width", marker)
                        .isEqualTo(1);
            }
        }

        @ParameterizedTest(name = "the {0} period crosses the boundary intact")
        @EnumSource(ReportPeriod.class)
        @DisplayName("each of the three periods is carried intact for the ordered service cascade, "
                + "and none produces a violation")
        void eachPeriodIsCarriedIntact(ReportPeriod period) {
            ReportRequest request = new ReportRequest(monthlyMarkerFor(period), yearlyMarkerFor(period), customMarkerFor(period), null, null, null, null, null, null,
                    null, null, null);

            assertThat(validator.validate(request)).isEmpty();
            assertThat(resolvedPeriodOf(request)).isSameAs(period);
        }

        @Test
        @DisplayName("the vocabulary carries exactly the three screen periods and no member standing "
                + "for the unmarked state, so absence can only be absence")
        void theVocabularyCarriesNoMemberForTheUnmarkedState() {
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .containsExactly("MONTHLY", "YEARLY", "CUSTOM");
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .as("no synthesised member may stand in for a period the operator never chose")
                    .doesNotContain("NONE", "UNKNOWN", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("an unmarked or unrecognised screen character resolves to absence without "
                + "raising, so the catch-all message stays the service's to report")
        void anUnrecognisedCharacterResolvesToAbsenceWithoutRaising() {
            assertThat(ReportPeriod.fromValue(null)).isEmpty();
            assertThat(ReportPeriod.fromValue("")).isEmpty();
            assertThat(ReportPeriod.fromValue(" ")).isEmpty();
            assertThat(ReportPeriod.fromValue("S")).isEmpty();
            assertThat(ReportPeriod.fromValue("monthly"))
                    .as("recognition is exact: no case folding may widen the vocabulary")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue("MONTHLY")).isEmpty();
        }

        @Test
        @DisplayName("no member is declared beyond the twelve components, so no fragment of the "
                + "service cascade - and in particular no derived period accessor - sits on the "
                + "transport boundary")
        void noMemberIsDeclaredBeyondTheTwelveComponents() {
            List<String> declaredMethods = Arrays.stream(ReportRequest.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> !EXPECTED_COMPONENTS.contains(name))
                    .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
                    .toList();

            assertThat(declaredMethods)
                    .as("a convenience member here would be a second implementation of behaviour "
                            + "the parity fixtures measure elsewhere")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a request whose every part sits exactly at its declared width reports no "
                + "violation")
        void aRequestAtEveryDeclaredWidthReportsNoViolation() {
            ReportRequest request = new ReportRequest(
                    null, null, "Y", "12", "31", "2022", "12", "31", "2022", "Y",
                    KeyAction.ENTER, NavigationContext.empty());

            assertThat(validator.validate(request)).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @CsvSource({
            "startMonth,2", "startDay,2", "startYear,4",
            "endMonth,2", "endDay,2", "endYear,4",
        })
        @DisplayName("each date part reports a value one character over its width and leaves the "
                + "value exactly as supplied")
        void eachDatePartReportsAnOverLongValue(String part, int width) {
            String tooLong = "9".repeat(width + 1);
            ReportRequest request = carryingPart(part, tooLong);

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(part);
        }

        @Test
        @DisplayName("an over-long confirmation character is reported")
        void anOverLongConfirmationIsReported() {
            ReportRequest request = new ReportRequest(
                    null, null, null, null, null, null, null, null, null, "YY", null, null);

            Set<ConstraintViolation<ReportRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("confirm");
        }

        @Test
        @DisplayName("an entirely absent request reports no violation, which is the state of a "
                + "submission that marked no report type at all")
        void anEntirelyAbsentRequestReportsNoViolation() {
            assertThat(validator.validate(new ReportRequest(
                            null, null, null, null, null, null, null, null, null, null, null, null)))
                    .as("the program reports that state with its own diagnostic, so the boundary "
                            + "must let it through rather than pre-empting the message")
                    .isEmpty();
        }

        @ParameterizedTest(name = "the boundary accepts month part \"{0}\" and day part \"{1}\"")
        @CsvSource(value = {
            "00|00", "13|32", "99|99", "1|1", "  |  ", "ab|cd", "0 | 0",
        }, delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
        @DisplayName("no part carries a numeric, range or pattern rule, because each such check is "
                + "message-bearing and strictly ordered in the service cascade")
        void noPartCarriesANumericOrRangeRule(String month, String day) {
            ReportRequest request = customPeriod(month, day, "2022", month, day, "2022");

            assertThat(validator.validate(request))
                    .as("an out-of-range or non-numeric part must reach the service intact so the "
                            + "exact legacy diagnostic can name it")
                    .isEmpty();
        }

        @ParameterizedTest(name = "confirmation character \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"Y", "N", "y", "n", "Q", " ", "0"})
        @DisplayName("the confirmation bound restricts width only, because accept, silent reset and "
                + "quoted-back are three distinct outcomes and the character itself must survive")
        void theConfirmationBoundRestrictsWidthOnly(String confirm) {
            ReportRequest request = new ReportRequest(
                    "Y", null, null, null, null, null, null, null, null, confirm, null, null);

            assertThat(validator.validate(request)).isEmpty();
        }

        @Test
        @DisplayName("a start date later than the end date is accepted by the boundary, because "
                + "ordering is a service rule with its own diagnostic")
        void aStartDateLaterThanTheEndDateIsAcceptedByTheBoundary() {
            assertThat(validator.validate(customPeriod("12", "31", "2022", "01", "01", "2020")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @ParameterizedTest(name = "the {0} period crosses the wire as its own marker position")
        @EnumSource(ReportPeriod.class)
        @DisplayName("each period crosses the wire as a mark on its own position rather than under one "
                + "collapsed name, while the bare mixed-case report name stays the vocabulary's "
                + "carried value that only the service produces")
        void eachPeriodCrossesAsAMarkOnItsOwnPosition(ReportPeriod period)
                throws JsonProcessingException {
            JsonNode payload = payloadOf(new ReportRequest(
                    monthlyMarkerFor(period), yearlyMarkerFor(period), customMarkerFor(period), null, null, null, null, null, null, null, null, null));

            String markedComponent = SELECTION_COMPONENTS.get(switch (period) {
                case MONTHLY -> 0;
                case YEARLY -> 1;
                case CUSTOM -> 2;
            });
            assertThat(payload.get(markedComponent).asText())
                    .as("the wire form of a marked screen field is the marker character itself")
                    .isEqualTo(SELECTION_MARK);
            assertThat(payload.has(REMOVED_PERIOD_COMPONENT))
                    .as("no collapsed period name is written, because the boundary supplies marks "
                            + "and the service produces the resolved period")
                    .isFalse();
            assertThat(period.getValue())
                    .as("the report name the program writes is the carried value, and the two are "
                            + "deliberately different strings that must never be substituted")
                    .isNotEqualTo(period.name())
                    .isEqualTo(REPORT_NAMES_BY_PERIOD.get(period));
            assertThat(payload.size())
                    .as("only the one marked position is written when nothing else was supplied")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a period the operator did not mark leaves every marker position absent rather "
                + "than written as null or defaulted to one of the three")
        void anUnmarkedPeriodIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(new ReportRequest(
                    null, null, null, "01", null, null, null, null, null, null, null, null));

            for (String marker : SELECTION_COMPONENTS) {
                assertThat(payload.has(marker))
                        .as("marking none is a state the program reports at its catch-all arm, so "
                                + "%s must not be turned into a marked value by the boundary", marker)
                        .isFalse();
            }
            assertThat(payload.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("a fully populated request renders all twelve members under their contract "
                + "names")
        void aFullyPopulatedRequestRendersAllTwelveMembers() throws JsonProcessingException {
            ReportRequest request = new ReportRequest(
                    SELECTION_MARK, SELECTION_MARK, SELECTION_MARK,
                    "01", "02", "2022", "03", "04", "2023", "Y",
                    KeyAction.PFK05, JsonContractSupport.populatedNavigation());

            JsonNode payload = payloadOf(request);

            assertThat(payload.size()).isEqualTo(EXPECTED_COMPONENTS.size());
            assertThat(payload.get(MONTHLY_COMPONENT).asText())
                    .as("every marked position is written on its own, which is what makes the "
                            + "multiply-marked 3270 state the program resolves by order representable")
                    .isEqualTo(SELECTION_MARK);
            assertThat(payload.get("yearlySelection").asText()).isEqualTo(SELECTION_MARK);
            assertThat(payload.get("customSelection").asText()).isEqualTo(SELECTION_MARK);
            assertThat(payload.get("startMonth").asText()).isEqualTo("01");
            assertThat(payload.get("startDay").asText()).isEqualTo("02");
            assertThat(payload.get("startYear").asText()).isEqualTo("2022");
            assertThat(payload.get("endMonth").asText()).isEqualTo("03");
            assertThat(payload.get("endDay").asText()).isEqualTo("04");
            assertThat(payload.get("endYear").asText()).isEqualTo("2023");
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
            assertThat(payload.get("keyAction").asText()).isEqualTo("PFK05");
        }

        @Test
        @DisplayName("a leading zero on a date part survives the wire round trip, which is why the "
                + "parts are text rather than integers")
        void aLeadingZeroOnADatePartSurvivesTheWireRoundTrip() throws JsonProcessingException {
            ReportRequest request = customPeriod("01", "09", "2022", "01", "09", "2022");

            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            ReportRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), ReportRequest.class);

            assertThat(returned.startMonth()).isEqualTo("01");
            assertThat(returned.startDay()).isEqualTo("09");
            assertThat(returned).isEqualTo(request);
        }

        @Test
        @DisplayName("a blank date part survives as blank, distinct from both absent and empty")
        void aBlankDatePartSurvivesAsBlank() throws JsonProcessingException {
            ReportRequest request = customPeriod("  ", "  ", "    ", null, "", "2022");

            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            ReportRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), ReportRequest.class);

            assertThat(returned.startMonth()).isEqualTo("  ");
            assertThat(returned.startYear()).isEqualTo("    ");
            assertThat(returned.endMonth())
                    .as("an absent part stays absent rather than becoming empty")
                    .isNull();
            assertThat(returned.endDay())
                    .as("an empty part stays empty rather than becoming absent")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unknown member a client echoes back is ignored rather than rejected")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"yearlySelection\":\"Y\",\"confirm\":\"Y\","
                    + "\"reportPeriod\":\"YEARLY\",\"submittedJobName\":\"TRANREPT\"}";

            ReportRequest returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, ReportRequest.class);

            assertThat(resolvedPeriodOf(returned))
                    .as("the mark on the second position resolves the period, and the collapsed name "
                            + "a stale client still sends is an unknown member like any other")
                    .isEqualTo(ReportPeriod.YEARLY);
            assertThat(returned.confirm()).isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("Value fidelity and diagnostics")
    class ValueFidelityAndDiagnostics {

        @Test
        @DisplayName("no component is trimmed, padded, folded or reformatted on the way in")
        void noComponentIsNormalised() {
            ReportRequest request = new ReportRequest(
                    null, null, "Y", " 1", "9 ", " 202", "1 ", " 9", "202 ", "y", null, null);

            assertThat(request.startMonth()).isEqualTo(" 1");
            assertThat(request.startDay()).isEqualTo("9 ");
            assertThat(request.startYear()).isEqualTo(" 202");
            assertThat(request.endMonth()).isEqualTo("1 ");
            assertThat(request.endDay()).isEqualTo(" 9");
            assertThat(request.endYear()).isEqualTo("202 ");
            assertThat(request.confirm())
                    .as("no key fold and no case fold happens here; the fold belongs to the "
                            + "module's key translator and the case check to the service")
                    .isEqualTo("y");
            assertThat(resolvedPeriodOf(request))
                    .as("the period reaches the service exactly as chosen, never re-derived")
                    .isSameAs(ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("the generated rendering is retained, and nesting the navigation state "
                + "discloses none of its identifying values")
        void theGeneratedRenderingDisclosesNothingIdentifying() {
            ReportRequest request = new ReportRequest(
                    "Y", null, null, "01", "01", "2022", "01", "31", "2022", "Y",
                    KeyAction.ENTER, JsonContractSupport.populatedNavigation());

            String rendered = request.toString();

            assertThat(rendered).startsWith("ReportRequest[");
            assertThat(rendered)
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }

        @Test
        @DisplayName("two requests differing only in the chosen period are different requests")
        void twoRequestsDifferingOnlyInTheChosenPeriodAreDifferent() {
            ReportRequest monthly = new ReportRequest(
                    "Y", null, null, null, null, null, null, null, null, null, null, null);
            ReportRequest yearly = new ReportRequest(
                    null, "Y", null, null, null, null, null, null, null, null, null, null);

            assertThat(monthly)
                    .as("the chosen period is part of identity, so a different period is a "
                            + "different submission")
                    .isNotEqualTo(yearly);
            assertThat(monthly).isEqualTo(new ReportRequest(
                    "Y", null, null, null, null, null, null, null, null, null, null, null));
        }
    }
}
