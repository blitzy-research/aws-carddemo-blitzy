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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;

/**
 * Unit test for {@link ReportRequest}, the inbound contract of the {@code CR00} report-request screen.
 *
 * <h2>What is actually at risk in a request of this shape</h2>
 *
 * <p>Nothing this request carries is a regulated value: three one-character period markers, six date
 * parts, a one-character confirmation answer, an attention key and the echoed navigation state. No
 * account, no card, no customer and no amount crosses this boundary, and the tests assert that absence
 * directly rather than assuming it, because this screen submits work to a batch tier and a component
 * added here would become a parameter of a job rather than a field on a form.</p>
 *
 * <p>The live risk is therefore <strong>the echoed navigation state</strong>, which declares widths of its
 * own that nothing evaluates unless this contract cascades into it. That is the finding this file exists
 * to hold closed, and it is asserted by observing a nested property path rather than by reading the
 * annotation alone, because an annotation present but not honoured would satisfy the weaker check.</p>
 *
 * <p>The second risk is <strong>a boundary that decides too much</strong>. The report screen runs an
 * ordered multi-stage validation whose message sequence is the observable contract, and a boundary
 * rejection would pre-empt it: a caller submitting a malformed month must receive the screen's own message
 * for that stage, not a generic constraint report. The tests therefore assert the deliberate absence of
 * every presence, format, range and vocabulary rule, and assert positively that a malformed date, an
 * out-of-range month and a blank all transport intact.</p>
 *
 * <p>The third is the date shape itself. Each of the two dates arrives as three separate screen parts
 * rather than one value, and the parts are character fields, so a month of {@code 01} must not become
 * {@code 1}. Every part is asserted to keep its leading zero.</p>
 *
 * <p>The fourth is that the period arrives as <strong>three independent one-character markers</strong>
 * rather than as one closed selector. Nothing here refuses a submission that marks more than one
 * position and nothing folds a marker's case, because the order in which the three positions are tested
 * belongs to the program and the message produced when none of them is marked belongs to the response
 * contract. The tests assert that each marker is bounded by its screen width and by nothing else, that
 * marking two positions is accepted, and that marking none is accepted, so no tie-break and no default
 * can be smuggled in at the boundary.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("ReportRequest - the CR00 inbound contract")
class ReportRequestSecurityTest {

    /** The twelve components in declaration order. A change here is a change to the REST contract. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "monthlySelection", "yearlySelection", "customSelection", "startMonth", "startDay",
            "startYear", "endMonth", "endDay", "endYear", "confirm", "keyAction",
            "navigationContext");

    /** The three period markers, in the order the program tests them. */
    private static final List<String> MARKERS_IN_EVALUATION_ORDER =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    /** The ten bounded text components, paired positionally with {@link #BOUNDED_WIDTHS}. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "monthlySelection", "yearlySelection", "customSelection", "startMonth", "startDay",
            "startYear", "endMonth", "endDay", "endYear", "confirm");

    /**
     * The measured widths of those ten components, read from the symbolic map's three marker fields at
     * lines 60, 66 and 72 and from {@code app/bms/CORPT00.bms} lines 127, 138, 149, 166, 177 and 188
     * rather than from the class under test, so a width edited on the request alone fails here instead
     * of agreeing with itself.
     */
    private static final List<Integer> BOUNDED_WIDTHS = List.of(1, 1, 1, 2, 2, 4, 2, 2, 4, 1);

    /** A realistic custom-range submission with every component populated. */
    private static ReportRequest populated() {
        return new ReportRequest(null, null, "Y", "01", "15", "2024", "03", "31", "2024", "Y",
                KeyAction.ENTER, NavigationContext.empty().withReEntry());
    }

    /** A submission marking exactly one of the three positions and leaving the other two absent. */
    private static ReportRequest markingOnly(String marker) {
        return new ReportRequest(
                "monthlySelection".equals(marker) ? "Y" : null,
                "yearlySelection".equals(marker) ? "Y" : null,
                "customSelection".equals(marker) ? "Y" : null,
                null, null, null, null, null, null, null, null, null);
    }

    /** Names the positions a submission actually marks, in the order the program tests them. */
    private static List<String> markedPositionsOf(ReportRequest request) {
        List<String> marked = new ArrayList<>();
        if (request.monthlySelection() != null) {
            marked.add("monthlySelection");
        }
        if (request.yearlySelection() != null) {
            marked.add("yearlySelection");
        }
        if (request.customSelection() != null) {
            marked.add("customSelection");
        }
        return List.copyOf(marked);
    }

    /**
     * Mirrors the four serialisation settings the module declares in {@code application.yml}, so a
     * payload asserted here is the payload the service actually emits and receives.
     */
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

    private static Set<ConstraintViolation<ReportRequest>> violationsOf(ReportRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("The component set is the report map plus conversation state")
    class TheComponentSetIsTheReportMap {

        @Test
        @DisplayName("twelve components are declared in the order the symbolic map declares its fields")
        void twelveComponentsAreDeclaredInMapOrder() {
            List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_ORDER).hasSize(12);
            assertThat(declared.subList(0, 3)).containsExactlyElementsOf(MARKERS_IN_EVALUATION_ORDER);
        }

        @Test
        @DisplayName("no account, card, customer or amount component is declared, because this screen "
                + "submits a job rather than describing a record")
        void noRegulatedComponentIsDeclared() {
            List<String> lowerCased = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("account"))
                    .noneMatch(name -> name.contains("card"))
                    .noneMatch(name -> name.contains("customer"))
                    .noneMatch(name -> name.contains("amount"))
                    .noneMatch(name -> name.contains("ssn"));
        }

        @Test
        @DisplayName("no job name, dataset name, queue name or card image is declared, because a "
                + "client-supplied job identity would let a caller nominate what runs")
        void noJobIdentityComponentIsDeclared() {
            List<String> lowerCased = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("job"))
                    .noneMatch(name -> name.contains("dataset") || name.contains("dsn"))
                    .noneMatch(name -> name.contains("queue"))
                    .noneMatch(name -> name.contains("card"))
                    .noneMatch(name -> name.contains("program"));
        }

        @Test
        @DisplayName("every marker and every date part is characters and only the attention key is typed, "
                + "so no leading zero is lost and no marker's case is closed off")
        void everySubmittedValueIsCharactersAndOnlyTheKeyIsTyped() {
            for (RecordComponent component : ReportRequest.class.getRecordComponents()) {
                switch (component.getName()) {
                    case "keyAction" -> assertThat(component.getType()).isEqualTo(KeyAction.class);
                    case "navigationContext" ->
                            assertThat(component.getType()).isEqualTo(NavigationContext.class);
                    default -> assertThat(component.getType())
                            .as("component %s", component.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("each date arrives as three separate parts rather than one value, because that is "
                + "how the screen collects it and the parts are validated separately")
        void eachDateArrivesAsThreeSeparateParts() {
            ReportRequest request = populated();

            assertThat(request.startMonth()).isEqualTo("01");
            assertThat(request.startDay()).isEqualTo("15");
            assertThat(request.startYear()).isEqualTo("2024");
            assertThat(request.endMonth()).isEqualTo("03");
            assertThat(request.endDay()).isEqualTo("31");
            assertThat(request.endYear()).isEqualTo("2024");
        }

        @Test
        @DisplayName("every accessor returns exactly what it was constructed with, so no two of the six "
                + "date parts are transposed")
        void everyAccessorReturnsExactlyWhatItWasConstructedWith() {
            ReportRequest request = populated();

            assertThat(request.monthlySelection()).isNull();
            assertThat(request.yearlySelection()).isNull();
            assertThat(request.customSelection()).isEqualTo("Y");
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @ParameterizedTest
        @ValueSource(strings = {"monthlySelection", "yearlySelection", "customSelection"})
        @DisplayName("each of the three markers round-trips alone, because the positions are independent "
                + "and marking one neither implies nor excludes another")
        void eachMarkerRoundTripsAlone(String marker) {
            ReportRequest request = markingOnly(marker);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(markedPositionsOf(request)).containsExactly(marker);
        }

        @Test
        @DisplayName("a submission marking two positions is accepted, because the screen permits it and "
                + "the tie-break belongs to the program's evaluation order rather than to this boundary")
        void aSubmissionMarkingTwoPositionsIsAccepted() {
            ReportRequest request = new ReportRequest("Y", "Y", null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(markedPositionsOf(request))
                    .containsExactly("monthlySelection", "yearlySelection");
        }

        @Test
        @DisplayName("an entirely unmarked submission is accepted, because marking no option is the "
                + "state the screen reports on rather than a malformed submission")
        void anEntirelyUnmarkedSubmissionIsAccepted() {
            ReportRequest request = new ReportRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(markedPositionsOf(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Bounds measure and never alter")
    class BoundsMeasureAndNeverAlter {

        @Test
        @DisplayName("each bounded component carries exactly its screen field's measured width and no "
                + "other constraint")
        void eachBoundedComponentCarriesItsMeasuredWidth() throws NoSuchFieldException {
            for (int index = 0; index < BOUNDED_COMPONENTS.size(); index++) {
                String name = BOUNDED_COMPONENTS.get(index);
                Field field = ReportRequest.class.getDeclaredField(name);
                Size size = field.getAnnotation(Size.class);

                assertThat(size).as("component %s must carry a width bound", name).isNotNull();
                assertThat(size.max()).as("component %s width", name)
                        .isEqualTo(BOUNDED_WIDTHS.get(index));
                assertThat(size.min()).as("component %s must declare no minimum", name).isZero();
                assertThat(field.getAnnotations()).as("component %s carries one annotation", name)
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("the attention key carries no constraint, because a domain constant is already "
                + "closed to any value the enumeration does not declare")
        void theAttentionKeyCarriesNoConstraint() throws NoSuchFieldException {
            Field field = ReportRequest.class.getDeclaredField("keyAction");

            assertThat(field.getType()).isEqualTo(KeyAction.class);
            assertThat(field.getAnnotations()).isEmpty();
        }

        @Test
        @DisplayName("no presence, pattern, digit, range or assertion constraint appears on any "
                + "component, because the report screen's own ordered stages produce the messages")
        void noPresenceOrFormatConstraintAppears() throws NoSuchFieldException {
            for (String name : COMPONENTS_IN_ORDER) {
                Field field = ReportRequest.class.getDeclaredField(name);

                assertThat(Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .toList())
                        .as("component %s", name)
                        .doesNotContain("NotNull", "NotBlank", "NotEmpty", "Pattern", "Digits",
                                "Min", "Max", "Positive", "AssertTrue");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("a blank in every date part transports rather than being rejected, because a blank "
                + "part is the state the screen prompts against rather than refuses")
        void aBlankValueTransportsRatherThanBeingRejected(String blank) {
            ReportRequest request = new ReportRequest(blank, blank, blank, blank, blank, blank,
                    blank, blank, blank, blank, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.monthlySelection()).isEqualTo(blank);
            assertThat(request.startMonth()).isEqualTo(blank);
            assertThat(request.endYear()).isEqualTo(blank);
        }

        @Test
        @DisplayName("a submission space-filled to each declared width draws no violation, because that "
                + "is the shape a blank report screen transmits")
        void aSpaceFilledSubmissionDrawsNoViolation() {
            ReportRequest spaceFilled = new ReportRequest(" ", " ", " ", "  ", "  ", "    ", "  ",
                    "  ", "    ", " ", null, null);

            assertThat(violationsOf(spaceFilled)).isEmpty();
            assertThat(spaceFilled.monthlySelection()).hasSize(1).isBlank();
            assertThat(spaceFilled.startYear()).hasSize(4).isBlank();
        }

        @Test
        @DisplayName("each component one character over its own width is reported exactly once and never "
                + "trimmed")
        void eachComponentOneCharacterOverItsWidthIsReportedExactlyOnce() {
            for (int index = 0; index < BOUNDED_COMPONENTS.size(); index++) {
                String name = BOUNDED_COMPONENTS.get(index);
                String overWidth = "9".repeat(BOUNDED_WIDTHS.get(index) + 1);
                ReportRequest request = requestWithOnly(name, overWidth);

                Set<ConstraintViolation<ReportRequest>> violations = violationsOf(request);

                assertThat(violations).as("component %s", name).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath())
                        .as("component %s", name)
                        .hasToString(name);
            }
        }

        @Test
        @DisplayName("a fully populated valid submission draws no violation at all")
        void aFullyPopulatedValidSubmissionDrawsNoViolation() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        private static ReportRequest requestWithOnly(String component, String value) {
            return new ReportRequest(
                    "monthlySelection".equals(component) ? value : null,
                    "yearlySelection".equals(component) ? value : null,
                    "customSelection".equals(component) ? value : null,
                    "startMonth".equals(component) ? value : null,
                    "startDay".equals(component) ? value : null,
                    "startYear".equals(component) ? value : null,
                    "endMonth".equals(component) ? value : null,
                    "endDay".equals(component) ? value : null,
                    "endYear".equals(component) ? value : null,
                    "confirm".equals(component) ? value : null,
                    null, null);
        }
    }

    @Nested
    @DisplayName("The echoed navigation state is cascaded into")
    class TheEchoedNavigationStateIsCascadedInto {

        @Test
        @DisplayName("the navigation component declares the cascade")
        void theNavigationComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = ReportRequest.class.getDeclaredField("navigationContext");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("a violation inside the echoed navigation state is reported under a nested property "
                + "path, which is the observable proof the cascade reaches it")
        void aViolationInsideTheEchoedStateIsReportedUnderANestedPath() {
            NavigationContext overWidth = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            ReportRequest request = new ReportRequest("Y", null, null, null, null, null, null,
                    null, null, null, null, overWidth);

            Set<ConstraintViolation<ReportRequest>> violations = violationsOf(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .startsWith("navigationContext."));
        }

        @Test
        @DisplayName("an over-width echoed account identifier inside the navigation state is reported "
                + "too, so the cascade covers every nested component rather than the first one")
        void anOverWidthEchoedAccountIdentifierIsReportedToo() {
            NavigationContext overWidth = new NavigationContext(null, null, null, null, null, null,
                    NavigationContext.ProgramContext.REENTER, null, null, null, null,
                    "1".repeat(NavigationContext.ACCOUNT_ID_LENGTH + 1), null, null, null, null);
            ReportRequest request = new ReportRequest(null, null, null, null, null, null, null,
                    null, null, null, null, overWidth);

            Set<ConstraintViolation<ReportRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("navigationContext.accountId");
        }

        @Test
        @DisplayName("a date-part violation and a nested violation are reported together, so neither "
                + "masks the other")
        void aDatePartViolationAndANestedViolationAreReportedTogether() {
            NavigationContext overWidth = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            ReportRequest request = new ReportRequest(null, null, null, "999", null, null, null,
                    null, null, null, null, overWidth);

            Set<ConstraintViolation<ReportRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(2);
            assertThat(violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .toList())
                    .containsExactly("navigationContext.fromProgram", "startMonth");
        }

        @Test
        @DisplayName("an absent navigation state is not a violation, because a first entry carries none")
        void anAbsentNavigationStateIsNotAViolation() {
            ReportRequest request = new ReportRequest(null, "Y", null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext()).isNull();
        }
    }

    @Nested
    @DisplayName("Nothing is transformed on the way through")
    class NothingIsTransformedOnTheWayThrough {

        @Test
        @DisplayName("a leading zero survives in every date part, because each part mirrors a "
                + "fixed-width character field rather than a number")
        void aLeadingZeroSurvivesInEveryDatePart() {
            ReportRequest request = new ReportRequest(null, null, "Y", "01", "02", "0024", "09",
                    "08", "0024", null, null, null);

            assertThat(request.startMonth()).isEqualTo("01");
            assertThat(request.startDay()).isEqualTo("02");
            assertThat(request.startYear()).isEqualTo("0024");
            assertThat(request.endMonth()).isEqualTo("09");
            assertThat(request.endDay()).isEqualTo("08");
            assertThat(request.endYear()).isEqualTo("0024");
        }

        @Test
        @DisplayName("an out-of-range month and day transport intact, because the range check belongs to "
                + "the report screen's own stage and its message must be the one the operator sees")
        void anOutOfRangeMonthAndDayTransportIntact() {
            ReportRequest request = new ReportRequest(null, null, "Y", "13", "45", "2024", "00",
                    "99", "2024", null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.startMonth()).isEqualTo("13");
            assertThat(request.startDay()).isEqualTo("45");
            assertThat(request.endMonth()).isEqualTo("00");
            assertThat(request.endDay()).isEqualTo("99");
        }

        @Test
        @DisplayName("a non-numeric date part transports intact, because the shape check belongs to the "
                + "report screen rather than to this boundary")
        void aNonNumericDatePartTransportsIntact() {
            ReportRequest request = new ReportRequest(null, null, "Y", "AB", "CD", "EFGH", "IJ",
                    "KL", "MNOP", null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.startMonth()).isEqualTo("AB");
            assertThat(request.endYear()).isEqualTo("MNOP");
        }

        @Test
        @DisplayName("an end window earlier than its start transports intact, because ordering is a "
                + "cross-field rule the screen reports rather than the boundary refusing it")
        void anEndWindowEarlierThanItsStartTransportsIntact() {
            ReportRequest request = new ReportRequest(null, null, "Y", "12", "31", "2024", "01",
                    "01", "2024", null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.startYear()).isEqualTo(request.endYear());
        }

        @Test
        @DisplayName("a lower-case answer is not folded and an out-of-vocabulary answer round-trips, "
                + "because accept, reset and quoted-back are three distinct outcomes")
        void theAnswerIsNeitherFoldedNorRestricted() {
            ReportRequest lower = new ReportRequest(null, null, null, null, null, null, null, null,
                    null, "y", null, null);
            ReportRequest unexpected = new ReportRequest(null, null, null, null, null, null, null,
                    null, null, "Q", null, null);
            ReportRequest lowerCaseMarker = markingOnly("monthlySelection");

            assertThat(violationsOf(lower)).isEmpty();
            assertThat(violationsOf(unexpected)).isEmpty();
            assertThat(lower.confirm()).isEqualTo("y");
            assertThat(unexpected.confirm()).isEqualTo("Q");
            assertThat(new ReportRequest("y", null, null, null, null, null, null, null, null, null,
                    null, null).monthlySelection())
                    .as("a marker's case survives too, because the position is what is read")
                    .isEqualTo("y")
                    .isNotEqualTo(lowerCaseMarker.monthlySelection());
        }

        @Test
        @DisplayName("the generated rendering is retained, because no component of this request is a "
                + "regulated value and a date window is what a diagnostic most needs to show")
        void theGeneratedRenderingIsRetained() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .contains("startMonth=01")
                    .contains("startYear=2024")
                    .contains("endDay=31")
                    .contains("customSelection=Y");
            assertThatCode(() -> new ReportRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null).toString()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("equality compares every component, because an in-memory comparison emits nothing")
        void equalityComparesEveryComponent() {
            ReportRequest first = populated();
            ReportRequest same = populated();
            ReportRequest differentWindow = new ReportRequest(null, null, "Y", "01", "15", "2024",
                    "04", "30", "2024", "Y", KeyAction.ENTER,
                    NavigationContext.empty().withReEntry());

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(differentWindow);
        }

        @Test
        @DisplayName("a document naming every component deserializes with each value intact")
        void aDocumentNamingEveryComponentDeserializesIntact() throws JsonProcessingException {
            String document = "{\"monthlySelection\":\"Y\",\"yearlySelection\":\"Y\","
                    + "\"customSelection\":\"Y\",\"startMonth\":\"01\","
                    + "\"startDay\":\"15\",\"startYear\":\"2024\",\"endMonth\":\"03\","
                    + "\"endDay\":\"31\",\"endYear\":\"2024\",\"confirm\":\"Y\","
                    + "\"keyAction\":\"ENTER\"}";

            ReportRequest request = moduleEquivalentMapper().readValue(document, ReportRequest.class);

            assertThat(markedPositionsOf(request))
                    .containsExactlyElementsOf(MARKERS_IN_EVALUATION_ORDER);
            assertThat(request.startMonth()).isEqualTo("01");
            assertThat(request.endDay()).isEqualTo("31");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
        }

        @Test
        @DisplayName("the emitted document names every populated component, so nothing is suppressed "
                + "from the wire by accident")
        void theEmittedDocumentNamesEveryPopulatedComponent() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            JsonNode payload = mapper.readTree(mapper.writeValueAsString(populated()));

            assertThat(payload.get("startMonth").asText()).isEqualTo("01");
            assertThat(payload.get("endYear").asText()).isEqualTo("2024");
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
            assertThat(payload.get("customSelection").asText()).isEqualTo("Y");
            assertThat(payload.has("monthlySelection"))
                    .as("an unmarked position is omitted rather than published as a null")
                    .isFalse();
        }
    }
}
