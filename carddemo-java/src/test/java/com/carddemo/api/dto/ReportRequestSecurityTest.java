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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.domain.enums.ReportPeriod;

/**
 * Unit test for {@link ReportRequest}, the inbound contract of the {@code CR00} report-request screen.
 *
 * <h2>What is actually at risk in a request of this shape</h2>
 *
 * <p>Nothing this request carries is a regulated value: one enumerated reporting period, six date
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
 * <p>The fourth is that the period arrives as <strong>three one-character markers</strong> rather than
 * as one enumerated component. The program tests the three screen positions in a fixed order at
 * {@code app/cbl/CORPT00C.cbl} lines 214, 240 and 256 and acts on the first non-blank one, so a
 * submission marking two or three of them is a state the 3270 screen produces and the program resolves
 * rather than rejects. Carrying one enumerated value instead would make that state unsubmittable, and
 * with it the resolution: the caller would have to choose, which is the program's decision to make.
 * Each marker therefore declares its one-character screen width and nothing else - no presence rule, no
 * pattern restricting which character marks it, and no exclusivity rule against its two peers - because
 * every one of those would be a boundary refusing a submission the legacy screen accepts. No resolved
 * report type is accepted here at all: the service performs the ordered evaluation and publishes what
 * it resolved on the response contract, and the message produced when none is marked belongs there too.
 * The tests assert that each marker is bounded at one and carries no other rule, that the marker
 * character is carried verbatim rather than interpreted, that marking none transports as three
 * omissions, and that a multiply-marked payload is accepted - so no tie-break and no default can be
 * smuggled in at the boundary.</p>
 *
 * <p>No COBOL statement is transcribed.</p>
 */
@DisplayName("ReportRequest - the CR00 inbound contract")
class ReportRequestSecurityTest {

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

    /** The twelve components in declaration order. A change here is a change to the REST contract. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "monthlySelection", "yearlySelection", "customSelection", "startMonth", "startDay",
            "startYear", "endMonth", "endDay", "endYear", "confirm", "keyAction",
            "navigationContext");

    /**
     * The three per-position selectors, in the order {@code app/cbl/CORPT00C.cbl} tests them at lines
     * 214, 240 and 256. Each is one character wide and is carried verbatim.
     */
    private static final List<String> SELECTORS_IN_TEST_ORDER =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    /**
     * The collapsed period component that must never be published in place of the three positions,
     * asserted absent because supplying a resolved report type would move the program's own ordered
     * first-match-wins decision onto the caller.
     */
    private static final String REMOVED_PERIOD_COMPONENT = "reportPeriod";

    /** The seven bounded text components, paired positionally with {@link #BOUNDED_WIDTHS}. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "startMonth", "startDay",
            "startYear", "endMonth", "endDay", "endYear", "confirm");

    /**
     * The measured widths of those seven components, read from {@code app/bms/CORPT00.bms} lines 127,
     * 138, 149, 166, 177 and 188 and from the symbolic map's confirmation field at line 114 rather than
     * from the class under test, so a width edited on the request alone fails here instead of agreeing
     * with itself. The three selection markers are absent from this pairing only because their shared
     * one-character width is asserted together with their other declared rules, not because they are
     * unbounded.
     */
    private static final List<Integer> BOUNDED_WIDTHS = List.of(2, 2, 4, 2, 2, 4, 1);

    /** A realistic custom-range submission with every component populated. */
    private static ReportRequest populated() {
        return new ReportRequest(null, null, "Y", "01", "15", "2024", "03", "31", "2024", "Y",
                KeyAction.ENTER, NavigationContext.empty().withReEntry());
    }

    /** A submission carrying exactly one period and nothing else. */
    private static ReportRequest choosing(ReportPeriod period) {
        return new ReportRequest(
                monthlyMarkerFor(period), yearlyMarkerFor(period), customMarkerFor(period), null, null, null, null, null, null, null, null, null);
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
        @DisplayName("twelve components are declared in the order the symbolic map declares its "
                + "fields")
        void twelveComponentsAreDeclaredInMapOrder() {
            List<String> declared = Arrays.stream(ReportRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_ORDER).hasSize(12);
            assertThat(declared.subList(0, SELECTORS_IN_TEST_ORDER.size()))
                    .as("the three positions lead in the order the program tests them")
                    .containsExactlyElementsOf(SELECTORS_IN_TEST_ORDER);
            assertThat(declared)
                    .as("no collapsed period component is declared, so no caller supplies a resolved "
                            + "report type the program is supposed to derive")
                    .doesNotContain(REMOVED_PERIOD_COMPONENT);
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
        @DisplayName("every selection marker, date part and the confirmation are characters while only "
                + "the attention key and the carried state are typed, so no leading zero and no "
                + "marker character is lost")
        void everySubmittedValueIsCharactersAndOnlyTheClosedValuesAreTyped() {
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

            assertThat(resolvedPeriodOf(request)).isSameAs(ReportPeriod.CUSTOM);
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @ParameterizedTest
        @EnumSource(ReportPeriod.class)
        @DisplayName("each of the three periods round-trips alone and reports no violation, because a "
                + "chosen period is always a well-formed submission at this boundary")
        void eachPeriodRoundTripsAlone(ReportPeriod period) {
            ReportRequest request = choosing(period);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(resolvedPeriodOf(request)).isSameAs(period);
        }

        @Test
        @DisplayName("each marker carries only its one-character screen width and no exclusivity, "
                + "vocabulary or default rule, so no tie-break the program owns can be smuggled in "
                + "at the boundary")
        void eachMarkerCarriesOnlyItsWidthAndNoResolutionRule() throws NoSuchFieldException {
            for (String marker : SELECTORS_IN_TEST_ORDER) {
                Field field = ReportRequest.class.getDeclaredField(marker);

                assertThat(field.getAnnotation(Size.class))
                        .as("%s is a fixed-width screen field and declares that width", marker)
                        .isNotNull();
                assertThat(field.getAnnotation(Size.class).max())
                        .as("%s is one character wide", marker)
                        .isEqualTo(1);
                assertThat(Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName()).toList())
                        .as("%s declares its width and nothing else - no presence rule, no pattern "
                                + "restricting which character marks it, and no exclusivity rule "
                                + "against its two peers", marker)
                        .containsExactly("Size");
            }
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .containsExactly("MONTHLY", "YEARLY", "CUSTOM")
                    .doesNotContain("NONE", "UNKNOWN", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("an unrecognised value resolves to absence without raising, so a hostile payload "
                + "cannot turn a lookup into a thrown failure at the boundary")
        void anUnrecognisedValueResolvesToAbsenceWithoutRaising() {
            assertThat(ReportPeriod.fromValue(null)).isEmpty();
            assertThat(ReportPeriod.fromValue("Y")).isEmpty();
            assertThat(ReportPeriod.fromValue("Monthly\u0000")).isEmpty();
            assertThat(ReportPeriod.fromValue("monthly")).isEmpty();
            assertThat(ReportPeriod.fromValue("MONTHLY")).isEmpty();
            assertThat(ReportPeriod.fromValue("Monthly ")).isEmpty();
        }

        @Test
        @DisplayName("an entirely unmarked submission is accepted, because marking no option is the "
                + "state the screen reports on rather than a malformed submission")
        void anEntirelyUnmarkedSubmissionIsAccepted() {
            ReportRequest request = new ReportRequest(null, null, null, null, null, null, null, null, null, null,
                    null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(resolvedPeriodOf(request)).isNull();
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
            ReportRequest request = new ReportRequest(null, null, null, blank, blank, blank,
                    blank, blank, blank, blank, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.startMonth()).isEqualTo(blank);
            assertThat(request.endYear()).isEqualTo(blank);
            assertThat(request.confirm()).isEqualTo(blank);
        }

        @Test
        @DisplayName("a submission space-filled to each declared width draws no violation, because that "
                + "is the shape a blank report screen transmits")
        void aSpaceFilledSubmissionDrawsNoViolation() {
            ReportRequest spaceFilled = new ReportRequest(null, null, null, "  ", "  ", "    ", "  ",
                    "  ", "    ", " ", null, null);

            assertThat(violationsOf(spaceFilled)).isEmpty();
            assertThat(spaceFilled.confirm()).hasSize(1).isBlank();
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
                    null, null, null,
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
            ReportRequest request = new ReportRequest(null, null, null, null, null, null, null, null, null,
                    null, null, overWidth);

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
            ReportRequest request = new ReportRequest(null, "Y", null, null, null, null, null,
                    null, null, null, null, null);

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
            ReportRequest lower = new ReportRequest(null, null, null, null, null, null, null, null, null,
                    "y", null, null);
            ReportRequest unexpected = new ReportRequest(null, null, null, null, null, null, null, null, null,
                    "Q", null, null);

            assertThat(violationsOf(lower)).isEmpty();
            assertThat(violationsOf(unexpected)).isEmpty();
            assertThat(lower.confirm()).isEqualTo("y");
            assertThat(unexpected.confirm()).isEqualTo("Q");
            assertThat(ReportPeriod.MONTHLY.getValue())
                    .as("the report name the service produces is not folded either, in either "
                            + "direction")
                    .isEqualTo("Monthly")
                    .isNotEqualTo(ReportPeriod.MONTHLY.name());
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
                    .contains("customSelection=Y")
                    .contains("monthlySelection=null")
                    .doesNotContain("reportPeriod=");
            assertThatCode(() -> new ReportRequest(null, null, null, null, null, null, null, null, null, null,
                    null, null).toString()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("equality compares every component, because an in-memory comparison emits nothing")
        void equalityComparesEveryComponent() {
            ReportRequest first = populated();
            ReportRequest same = populated();
            ReportRequest differentWindow = new ReportRequest(null, null, "Y", "01", "15",
                    "2024", "04", "30", "2024", "Y", KeyAction.ENTER,
                    NavigationContext.empty().withReEntry());

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(differentWindow);
        }

        @Test
        @DisplayName("a document naming every component deserializes with each value intact")
        void aDocumentNamingEveryComponentDeserializesIntact() throws JsonProcessingException {
            String document = "{\"customSelection\":\"Y\",\"startMonth\":\"01\","
                    + "\"startDay\":\"15\",\"startYear\":\"2024\",\"endMonth\":\"03\","
                    + "\"endDay\":\"31\",\"endYear\":\"2024\",\"confirm\":\"Y\","
                    + "\"keyAction\":\"ENTER\"}";

            ReportRequest request = moduleEquivalentMapper().readValue(document, ReportRequest.class);

            assertThat(resolvedPeriodOf(request)).isSameAs(ReportPeriod.CUSTOM);
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
            assertThat(payload.has("navigationContext"))
                    .as("a populated navigation state is published rather than suppressed")
                    .isTrue();
            assertThat(payload.has(REMOVED_PERIOD_COMPONENT))
                    .as("no collapsed period name is part of the wire form")
                    .isFalse();
        }
    }
}
