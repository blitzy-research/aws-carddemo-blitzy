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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReportResponse}, the response body of legacy transaction {@code CR00}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>Two properties dominate what is checked here.
 *
 * <p>The first is that the three echoed selection markers stay three, mirroring the request. The screen
 * echoes them at {@code app/cpy-bms/CORPT00.CPY} lines 164, 170 and 176, each one character wide, and
 * {@code app/cbl/CORPT00C.cbl} lines 635 to 638 clear all three together. Collapsing them into a single
 * period value would have made a two-marked echo unrepresentable, so the marks the operator actually
 * made could not have been shown back.
 *
 * <p>The second is that the report's <em>name</em> is a produced value rather than a submitted one. The
 * program holds it in a work field written at lines 214, 240 and 433 from the three selection arms, and
 * consumes it up to its first space when assembling the confirmation and submission texts at lines 449
 * and 468. Its casing - a capital initial letter and a lowercase remainder - and the absence of padding
 * are therefore both contract, and the three published names are pinned byte for byte.
 *
 * <p>The eight focus identities are additionally pinned to the generated map items they name, at most
 * seven characters each, which is what makes the bound on the focus component meaningful and what lets a
 * client read that component the same way on every response in this package.
 *
 * <p>The published message texts and fragments are pinned the same way, because they are external
 * contract under validation gate 5 and a stray edit to a literal would be invisible to a compiler.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("ReportResponse :: report-response contract of legacy transaction CR00")
class ReportResponseTest {

    /** The twenty-four record components in the order the symbolic map declares their fields. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "monthlySelection", "yearlySelection", "customSelection", "reportName", "startMonth",
            "startDay", "startYear", "endMonth", "endDay", "endYear", "confirm", "transactionName",
            "title01", "currentDate", "programName", "title02", "currentTime", "errorMessage",
            "submissionAccepted", "message", "generalError", "focusScreenFieldId", "nextRoute",
            "navigationContext");

    /** The three echoed selection markers, in the order the program evaluates them. */
    private static final List<String> SELECTION_MARKERS_IN_EVALUATION_ORDER =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    private static NavigationContext navigation() {
        return new NavigationContext("CR00", "CORPT00C", "CR00", "CORPT00C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                "00000000011", "Y", "4111111111111111", "CORPT0A", "CORPT00");
    }

    private static ReportResponse everyComponentPresent() {
        return new ReportResponse("Y", "Y", "Y", ReportResponse.REPORT_NAME_CUSTOM, "07", "01",
                "2022", "07", "19", "2022", "Y", "CR00", "CardDemo - Reports", "07/19/22",
                "CORPT00C", "Transaction Reports", "14:30:00", null, true,
                ReportResponse.REPORT_NAME_CUSTOM + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX, false,
                ReportResponse.FIELD_MONTHLY_SELECTION, "/api/reports", navigation());
    }

    private static ReportResponse withSelections(String monthly, String yearly, String custom,
            String reportName) {
        return new ReportResponse(monthly, yearly, custom, reportName, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false, null, false, null, null,
                null);
    }

    private static ReportResponse withFocus(String focusScreenFieldId) {
        return new ReportResponse(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, false, null, true, focusScreenFieldId, null,
                null);
    }

    /** The eight generated map identifiers the program actually places the cursor on. */
    private static List<String> allFieldIdentities() {
        return List.of(ReportResponse.FIELD_MONTHLY_SELECTION, ReportResponse.FIELD_START_MONTH,
                ReportResponse.FIELD_START_DAY, ReportResponse.FIELD_START_YEAR,
                ReportResponse.FIELD_END_MONTH, ReportResponse.FIELD_END_DAY,
                ReportResponse.FIELD_END_YEAR, ReportResponse.FIELD_CONFIRM);
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

    private static JsonNode payloadOf(ReportResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static List<String> componentNames() {
        return Arrays.stream(ReportResponse.class.getRecordComponents())
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
            return ReportResponse.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return ReportResponse.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the twenty-four components the screen shows, in map order")
        void declaresTheTwentyFourComponentsInMapOrder() {
            assertThat(componentNames()).containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("carries the canonical wire names for route and focus")
        void carriesTheCanonicalWireNames() {
            assertThat(componentNames())
                    .contains("title01", "title02", "nextRoute", "focusScreenFieldId",
                            "navigationContext")
                    .doesNotContain("route", "fieldToFocus", "navigation", "screenTitle1",
                            "screenTitle2", "titleLine1", "titleLine2");
        }

        @Test
        @DisplayName("bounds the focus hint at the seven characters a generated map name allows")
        void boundsTheFocusHintAtSeven() {
            assertThat(ReportResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(annotationOn("focusScreenFieldId", Size.class).max())
                    .isEqualTo(ReportResponse.SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("declares the six date parts and the header widths from the map")
        void declaresTheDatePartAndHeaderWidths() {
            assertThat(ReportResponse.MONTH_LENGTH).isEqualTo(2);
            assertThat(ReportResponse.DAY_LENGTH).isEqualTo(2);
            assertThat(ReportResponse.YEAR_LENGTH).isEqualTo(4);
            assertThat(ReportResponse.CONFIRM_LENGTH).isEqualTo(1);
            assertThat(ReportResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(ReportResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(ReportResponse.DATE_LENGTH).isEqualTo(8);
            assertThat(ReportResponse.TIME_LENGTH).isEqualTo(8);
            assertThat(ReportResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(ReportResponse.ERROR_MESSAGE_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("declares the selection width apart from the confirmation width")
        void declaresTheSelectionWidthApartFromTheConfirmationWidth() {
            assertThat(ReportResponse.SELECTION_LENGTH).isEqualTo(1);
            SELECTION_MARKERS_IN_EVALUATION_ORDER.forEach(marker ->
                    assertThat(annotationOn(marker, Size.class).max())
                            .isEqualTo(ReportResponse.SELECTION_LENGTH));
            assertThat(annotationOn("confirm", Size.class).max())
                    .isEqualTo(ReportResponse.CONFIRM_LENGTH);
        }

        @Test
        @DisplayName("leaves the assembled message unbounded, since it is not a map field")
        void leavesTheAssembledMessageUnbounded() {
            assertThat(annotationsOn("message")).isEmpty();
            assertThat(annotationsOn("nextRoute")).isEmpty();
        }

        @Test
        @DisplayName("carries the two explicit flags as primitives rather than inferring them")
        void carriesTheTwoExplicitFlags() {
            assertThat(ReportResponse.class.getRecordComponents()[18].getType())
                    .isEqualTo(boolean.class);
            assertThat(ReportResponse.class.getRecordComponents()[20].getType())
                    .isEqualTo(boolean.class);
        }
    }

    @Nested
    @DisplayName("the three echoed markers stay three")
    class TheThreeEchoedMarkersStayThree {

        @Test
        @DisplayName("declares three independent markers, one per echoed screen position")
        void declaresThreeIndependentMarkers() {
            assertThat(componentNames().subList(0, 3))
                    .containsExactlyElementsOf(SELECTION_MARKERS_IN_EVALUATION_ORDER);
        }

        @Test
        @DisplayName("carries no collapsed period component and no period enumeration")
        void carriesNoCollapsedPeriodComponent() {
            assertThat(componentNames()).doesNotContain("period", "reportPeriod", "reportType");
            assertThat(Arrays.stream(ReportResponse.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .map(Class::getName))
                    .noneMatch(name -> name.contains("ReportPeriod"));
        }

        @Test
        @DisplayName("echoes a submission that marked two positions")
        void echoesASubmissionThatMarkedTwoPositions() {
            ReportResponse twoMarked =
                    withSelections("Y", "Y", null, ReportResponse.REPORT_NAME_MONTHLY);

            assertThat(twoMarked.monthlySelection()).isEqualTo("Y");
            assertThat(twoMarked.yearlySelection()).isEqualTo("Y");
            assertThat(twoMarked.customSelection()).isNull();
        }

        @Test
        @DisplayName("echoes all three markers cleared together, as the program clears them")
        void echoesAllThreeMarkersCleared() {
            ReportResponse cleared = withSelections(null, null, null, null);

            assertThat(List.of(componentNames().get(0), componentNames().get(1),
                    componentNames().get(2)))
                    .containsExactlyElementsOf(SELECTION_MARKERS_IN_EVALUATION_ORDER);
            assertThat(cleared.monthlySelection()).isNull();
            assertThat(cleared.yearlySelection()).isNull();
            assertThat(cleared.customSelection()).isNull();
        }

        @Test
        @DisplayName("distinguishes a cleared marker from a blank one")
        void distinguishesAClearedMarkerFromABlankOne() {
            assertThat(withSelections("", null, null, null).monthlySelection()).isEmpty();
            assertThat(withSelections(null, null, null, null).monthlySelection()).isNull();
        }
    }

    @Nested
    @DisplayName("the report name is produced, not submitted")
    class TheReportNameIsProducedNotSubmitted {

        @Test
        @DisplayName("carries the report name as a bounded component of its own")
        void carriesTheReportNameAsItsOwnComponent() {
            assertThat(componentNames()).contains("reportName");
            assertThat(ReportResponse.REPORT_NAME_LENGTH).isEqualTo(10);
            assertThat(annotationOn("reportName", Size.class).max())
                    .isEqualTo(ReportResponse.REPORT_NAME_LENGTH);
        }

        @Test
        @DisplayName("publishes the three names the program writes, byte for byte")
        void publishesTheThreeNames() {
            assertThat(ReportResponse.REPORT_NAME_MONTHLY).isEqualTo("Monthly");
            assertThat(ReportResponse.REPORT_NAME_YEARLY).isEqualTo("Yearly");
            assertThat(ReportResponse.REPORT_NAME_CUSTOM).isEqualTo("Custom");
        }

        @Test
        @DisplayName("keeps the contractual casing of each name: capital initial, lowercase rest")
        void keepsTheContractualCasing() {
            List.of(ReportResponse.REPORT_NAME_MONTHLY, ReportResponse.REPORT_NAME_YEARLY,
                    ReportResponse.REPORT_NAME_CUSTOM).forEach(name -> {
                        assertThat(name.substring(0, 1))
                                .isEqualTo(name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
                        assertThat(name.substring(1))
                                .isEqualTo(name.substring(1).toLowerCase(java.util.Locale.ROOT));
                    });
        }

        @Test
        @DisplayName("carries no padding on any name, since the text is consumed to its first space")
        void carriesNoPaddingOnAnyName() {
            List.of(ReportResponse.REPORT_NAME_MONTHLY, ReportResponse.REPORT_NAME_YEARLY,
                    ReportResponse.REPORT_NAME_CUSTOM).forEach(name ->
                            assertThat(name).doesNotContain(" ").isEqualTo(name.strip()));
        }

        @Test
        @DisplayName("fits every published name inside the work field's ten characters")
        void fitsEveryNameInsideTheWorkFieldWidth() {
            List.of(ReportResponse.REPORT_NAME_MONTHLY, ReportResponse.REPORT_NAME_YEARLY,
                    ReportResponse.REPORT_NAME_CUSTOM).forEach(name ->
                            assertThat(name.length())
                                    .isLessThanOrEqualTo(ReportResponse.REPORT_NAME_LENGTH));
        }

        @Test
        @DisplayName("carries the name independently of the markers, since the program derives it")
        void carriesTheNameIndependentlyOfTheMarkers() {
            ReportResponse derived =
                    withSelections("Y", null, null, ReportResponse.REPORT_NAME_MONTHLY);

            assertThat(derived.reportName()).isEqualTo(ReportResponse.REPORT_NAME_MONTHLY);
            assertThat(withSelections("Y", null, null, null).reportName()).isNull();
        }
    }

    @Nested
    @DisplayName("the focus identities match the sites the program actually focuses")
    class FocusIdentitiesMatchTheProgram {

        @Test
        @DisplayName("publishes a selection focus identity only for the monthly position")
        void publishesASelectionFocusIdentityOnlyForMonthly() {
            assertThat(ReportResponse.FIELD_MONTHLY_SELECTION).isEqualTo("MONTHLY");
            assertThat(Arrays.stream(ReportResponse.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .filter(name -> name.startsWith("FIELD_")))
                    .containsExactlyInAnyOrder("FIELD_MONTHLY_SELECTION", "FIELD_START_MONTH",
                            "FIELD_START_DAY", "FIELD_START_YEAR", "FIELD_END_MONTH",
                            "FIELD_END_DAY", "FIELD_END_YEAR", "FIELD_CONFIRM");
        }

        @Test
        @DisplayName("publishes no identity for the yearly or custom position, which are never focused")
        void publishesNoIdentityForYearlyOrCustom() {
            assertThat(Arrays.stream(ReportResponse.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName))
                    .doesNotContain("FIELD_YEARLY_SELECTION", "FIELD_CUSTOM_SELECTION",
                            "FIELD_PERIOD");
        }

        @Test
        @DisplayName("names each identity after its generated map item, as the whole package does")
        void namesEachIdentityAfterItsMapItem() {
            assertThat(ReportResponse.FIELD_MONTHLY_SELECTION).isEqualTo("MONTHLY");
            assertThat(ReportResponse.FIELD_START_MONTH).isEqualTo("SDTMM");
            assertThat(ReportResponse.FIELD_START_DAY).isEqualTo("SDTDD");
            assertThat(ReportResponse.FIELD_START_YEAR).isEqualTo("SDTYYYY");
            assertThat(ReportResponse.FIELD_END_MONTH).isEqualTo("EDTMM");
            assertThat(ReportResponse.FIELD_END_DAY).isEqualTo("EDTDD");
            assertThat(ReportResponse.FIELD_END_YEAR).isEqualTo("EDTYYYY");
            assertThat(ReportResponse.FIELD_CONFIRM).isEqualTo("CONFIRM");
        }

        @Test
        @DisplayName("fits every identity inside the focus hint's own bound of seven")
        void fitsEveryIdentityInsideTheFocusBound() {
            assertThat(allFieldIdentities())
                    .allSatisfy(identity -> assertThat(identity.length())
                            .isLessThanOrEqualTo(ReportResponse.SCREEN_FIELD_ID_LENGTH));
        }

        @Test
        @DisplayName("makes every identity a legally bindable value of the focus component itself")
        void makesEveryIdentityBindable() {
            assertThat(allFieldIdentities()).allSatisfy(identity ->
                    assertThat(withFocus(identity).focusScreenFieldId()).isEqualTo(identity));
        }

        @Test
        @DisplayName("shares the seven-character map-identifier vocabulary with its sibling contracts")
        void sharesTheMapIdentifierVocabulary() {
            assertThat(ReportResponse.FIELD_CONFIRM)
                    .isEqualTo(BillPaymentResponse.CONFIRM_FIELD_ID);
            assertThat(ReportResponse.SCREEN_FIELD_ID_LENGTH)
                    .isEqualTo(BillPaymentResponse.ACCOUNT_ID_FIELD_ID.length());
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("publishes every component under its own name")
        void publishesEveryComponentUnderItsOwnName() throws JsonProcessingException {
            assertThat(payloadOf(everyComponentPresent()).fieldNames()).toIterable()
                    .containsExactlyInAnyOrderElementsOf(COMPONENTS_IN_MAP_ORDER.stream()
                            .filter(name -> !name.equals("errorMessage"))
                            .toList());
        }

        @Test
        @DisplayName("publishes the three markers as three independent JSON strings")
        void publishesTheThreeMarkersAsThreeStrings() throws JsonProcessingException {
            JsonNode payload = payloadOf(withSelections("Y", "N", "Y", null));

            assertThat(payload.get("monthlySelection").asText()).isEqualTo("Y");
            assertThat(payload.get("yearlySelection").asText()).isEqualTo("N");
            assertThat(payload.get("customSelection").asText()).isEqualTo("Y");
        }

        @Test
        @DisplayName("publishes the report name as a string beside the markers")
        void publishesTheReportNameAsAString() throws JsonProcessingException {
            JsonNode payload =
                    payloadOf(withSelections("Y", null, null, ReportResponse.REPORT_NAME_MONTHLY));

            assertThat(payload.get("reportName").asText()).isEqualTo("Monthly");
            assertThat(payload.get("reportName").isTextual()).isTrue();
        }

        @Test
        @DisplayName("publishes none of the superseded component spellings")
        void publishesNoneOfTheSupersededSpellings() throws JsonProcessingException {
            assertThat(payloadOf(everyComponentPresent()).toString())
                    .doesNotContain("\"period\"", "\"reportPeriod\"", "\"route\"",
                            "\"fieldToFocus\"", "\"navigation\"");
        }

        @Test
        @DisplayName("publishes the two explicit flags even when false")
        void publishesTheTwoExplicitFlags() throws JsonProcessingException {
            JsonNode payload = payloadOf(withSelections(null, null, null, null));

            assertThat(payload.get("submissionAccepted").asBoolean()).isFalse();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("keeps a leading zero on a date part, which a numeric type would have lost")
        void keepsALeadingZeroOnADatePart() throws JsonProcessingException {
            JsonNode payload = payloadOf(everyComponentPresent());

            assertThat(payload.get("startMonth").asText()).isEqualTo("07");
            assertThat(payload.get("startDay").isTextual()).isTrue();
        }

        @Test
        @DisplayName("omits an absent component rather than publishing a null")
        void omitsAnAbsentComponent() throws JsonProcessingException {
            assertThat(payloadOf(withSelections(null, null, null, null)).fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("submissionAccepted", "generalError");
        }
    }

    @Nested
    @DisplayName("operator diagnostics reproduce the legacy text")
    class OperatorDiagnosticsReproduceLegacyText {

        @Test
        @DisplayName("reproduces the six empty-date-part texts byte for byte")
        void reproducesTheSixEmptyDatePartTexts() {
            assertThat(ReportResponse.MSG_START_DATE_MONTH_EMPTY)
                    .isEqualTo("Start Date - Month can NOT be empty...");
            assertThat(ReportResponse.MSG_START_DATE_DAY_EMPTY)
                    .isEqualTo("Start Date - Day can NOT be empty...");
            assertThat(ReportResponse.MSG_START_DATE_YEAR_EMPTY)
                    .isEqualTo("Start Date - Year can NOT be empty...");
            assertThat(ReportResponse.MSG_END_DATE_MONTH_EMPTY)
                    .isEqualTo("End Date - Month can NOT be empty...");
            assertThat(ReportResponse.MSG_END_DATE_DAY_EMPTY)
                    .isEqualTo("End Date - Day can NOT be empty...");
            assertThat(ReportResponse.MSG_END_DATE_YEAR_EMPTY)
                    .isEqualTo("End Date - Year can NOT be empty...");
        }

        @Test
        @DisplayName("reproduces the six invalid-date-part texts byte for byte")
        void reproducesTheSixInvalidDatePartTexts() {
            assertThat(ReportResponse.MSG_START_DATE_MONTH_INVALID)
                    .isEqualTo("Start Date - Not a valid Month...");
            assertThat(ReportResponse.MSG_START_DATE_DAY_INVALID)
                    .isEqualTo("Start Date - Not a valid Day...");
            assertThat(ReportResponse.MSG_START_DATE_YEAR_INVALID)
                    .isEqualTo("Start Date - Not a valid Year...");
            assertThat(ReportResponse.MSG_END_DATE_MONTH_INVALID)
                    .isEqualTo("End Date - Not a valid Month...");
            assertThat(ReportResponse.MSG_END_DATE_DAY_INVALID)
                    .isEqualTo("End Date - Not a valid Day...");
            assertThat(ReportResponse.MSG_END_DATE_YEAR_INVALID)
                    .isEqualTo("End Date - Not a valid Year...");
        }

        @Test
        @DisplayName("reproduces the two whole-date texts byte for byte")
        void reproducesTheTwoWholeDateTexts() {
            assertThat(ReportResponse.MSG_START_DATE_INVALID)
                    .isEqualTo("Start Date - Not a valid date...");
            assertThat(ReportResponse.MSG_END_DATE_INVALID)
                    .isEqualTo("End Date - Not a valid date...");
        }

        @Test
        @DisplayName("reproduces the no-report-type-selected text of line 438 byte for byte")
        void reproducesTheNoReportTypeSelectedText() {
            assertThat(ReportResponse.MSG_SELECT_REPORT_TYPE)
                    .isEqualTo("Select a report type to print report...");
        }

        @Test
        @DisplayName("reproduces the submission suffix of line 450 byte for byte")
        void reproducesTheSubmissionSuffix() {
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isEqualTo(" report submitted for printing ...");
        }

        @Test
        @DisplayName("reproduces the confirmation prompt fragments of lines 466 and 467")
        void reproducesTheConfirmationPromptFragments() {
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX)
                    .isEqualTo("Please confirm to print the ");
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX).isEqualTo(" report...");
        }

        @Test
        @DisplayName("reproduces the quoted-back invalid-confirmation fragments")
        void reproducesTheQuotedBackInvalidConfirmationFragments() {
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX).isEqualTo("\"");
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX)
                    .isEqualTo("\" is not a valid value to confirm...");
        }

        @Test
        @DisplayName("keeps every assembled text inside the map's message width of seventy-eight")
        void keepsEveryAssembledTextInsideTheMapWidth() {
            int width = ReportResponse.ERROR_MESSAGE_LENGTH;

            assertThat(List.of(
                    ReportResponse.REPORT_NAME_CUSTOM + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX,
                    ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX
                            + ReportResponse.REPORT_NAME_MONTHLY
                            + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX,
                    ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX + "X"
                            + ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX,
                    ReportResponse.MSG_SELECT_REPORT_TYPE,
                    ReportResponse.MSG_START_DATE_MONTH_EMPTY,
                    ReportResponse.MSG_END_DATE_YEAR_INVALID))
                    .allSatisfy(text -> assertThat(text.length()).isLessThanOrEqualTo(width));
        }
    }
}
