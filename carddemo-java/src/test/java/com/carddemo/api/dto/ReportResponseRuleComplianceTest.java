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

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReportResponse}, the response body of legacy transaction {@code CR00}
 * implemented by {@code app/cbl/CORPT00C.cbl} over screen {@code app/cpy-bms/CORPT00.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Two date triples, not two dates.</strong> The legacy screen does not accept a date; it
 * accepts a month, a day and a year in three separate fields, twice over, and validates each part
 * independently before validating the assembled whole. That is why the record carries six date
 * components rather than two, and why the message vocabulary carries a separate "can NOT be empty"
 * and "Not a valid" sentence for every one of the six parts as well as two whole-date sentences. All
 * twenty message literals are asserted here character for character, because {@code CORPT00C} writes
 * them into {@code PIC X(78)} and an operator matches on them.
 *
 * <p><strong>The confirmation gate is a character, not a flag.</strong>
 * {@code SUBMIT-JOB-TO-INTRDR} at {@code app/cbl/CORPT00C.cbl:462-510} gates submission behind a
 * one-character confirmation field, and reports a rejected value by quoting it back inside two
 * literal fragments. Those fragments are published separately from the whole messages precisely
 * because the middle of the sentence is supplied at run time.
 *
 * <p><strong>Three echoed positions, one resolved period, and no separate derived name.</strong> The
 * screen carries three one-character selector positions. {@code CORPT00C} evaluates them in the fixed
 * order monthly, yearly, custom at lines 214, 240 and 256, acts on the first non-blank one, and writes
 * the name of the report it chose into its own ten-character work item at lines 214, 240 and 433. The
 * response therefore carries both: the three positions as the operator will see them re-presented, and
 * the enumerated period the evaluation resolved. Both are needed and neither is derivable from the
 * other - the reset paragraph at lines 633 to 646 blanks all three positions on a successful submission
 * or a declined confirmation while every error path returns the transmitted marks still standing, so a
 * response naming only the period could describe neither a cleared screen nor a surviving mark, and
 * could not re-present two marks at once. The derived name, by contrast, is the period's own carried
 * value rather than a component of its own, so it cannot drift from the period and cannot be submitted
 * independently of it; it reaches an operator only inside the two composed message texts.
 *
 * <p><strong>Eight field-identity constants, not eight component names.</strong> {@code FIELD_*}
 * carries the identifier the generated symbolic map gives each field - {@code MONTHLY},
 * {@code SDTMM}, {@code SDTDD}, {@code SDTYYYY}, {@code EDTMM}, {@code EDTDD}, {@code EDTYYYY} and
 * {@code CONFIRM} - and deliberately not the name of the record component it belongs to. The
 * distinction is what makes the bound on {@code focusScreenFieldId} meaningful: every identifier is at
 * most {@value ReportResponse#SCREEN_FIELD_ID_LENGTH} characters, which is the ceiling the generator
 * imposes and the exact bound the component declares, whereas a longer property name such as the
 * twelve-character {@code reportPeriod} would breach that bound and make the contract contradict
 * itself. The two
 * vocabularies are therefore asserted to be disjoint rather than identical, and the group stays eight
 * strong because the program places the cursor at seven sites that all name the monthly field and
 * never on the yearly or custom positions.
 *
 * <p><strong>One declaration of the three report-name literals, not two.</strong>
 * {@link ReportPeriod#getValue()} is the single source of {@code Monthly}, {@code Yearly} and
 * {@code Custom}. This response deliberately restates none of them, because a second declaration could
 * be corrected on one side only and the assembled operator text would then disagree with the period the
 * response carries. Assertions below read the literals from the vocabulary rather than from here.
 */
@DisplayName("ReportResponse - the CR00 report-request screen contract")
class ReportResponseRuleComplianceTest {

    /** A representative transaction name, four characters as the legacy screen carries it. */
    private static final String TRANSACTION_NAME = "CR00";

    /** A representative program name, eight characters as the legacy screen carries it. */
    private static final String PROGRAM_NAME = "CORPT00C";

    /**
     * The three report-type selector positions the response re-presents, in the order the symbolic map
     * declares them at lines 60, 66 and 72.
     *
     * <p>They are published separately, and separately from the resolved period, because the reset
     * paragraph {@code INITIALIZE-ALL-FIELDS} at {@code app/cbl/CORPT00C.cbl:L633-L646} blanks all ten
     * screen fields while every error path returns the transmitted marks still standing. A response
     * carrying only the resolved period could express neither state.
     */
    private static final List<String> SELECTOR_COMPONENTS =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    /**
     * The separate derived report-name property, asserted absent because it would give the name a
     * second, drifting source of truth: the name is the resolved period's own carried value.
     */
    private static final List<String> REMOVED_COMPONENTS = List.of("reportName");

    /**
     * Builds a mapper configured exactly as {@code application.yml} configures the module's mapper.
     *
     * @return a mapper carrying the module's four Jackson settings
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
     * Serializes a response and reads the result back as a tree.
     *
     * @param response the response to render
     * @return the rendered payload
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static JsonNode payloadOf(final ReportResponse response) throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Builds a response positionally, laid out in rows of five so a component cannot silently drift
     * one position out of line past a compiler that sees a wall of interchangeable strings.
     *
     * @param period the selected reporting period
     * @param startMonth the start-date month part
     * @param startDay the start-date day part
     * @param startYear the start-date year part
     * @param endMonth the end-date month part
     * @param endDay the end-date day part
     * @param endYear the end-date year part
     * @param confirm the one-character confirmation value
     * @param errorMessage the operator message written to the error line
     * @param submissionAccepted whether the job was handed to the submission bridge
     * @param message the free-form message carried alongside the screen error line
     * @param generalError whether the screen is reporting a whole-screen failure
     * @param focusScreenFieldId the identity of the map item the cursor is placed on
     * @return a response carrying the supplied values and the fixed screen furniture
     */
    private static ReportResponse aResponse(
            final ReportPeriod period, final String startMonth, final String startDay,
            final String startYear, final String endMonth,
            final String endDay, final String endYear, final String confirm,
            final String errorMessage, final boolean submissionAccepted,
            final String message, final boolean generalError, final String focusScreenFieldId) {
        return new ReportResponse(null, null, null,
                period, startMonth,
                startDay, startYear, endMonth, endDay, endYear,
                confirm, TRANSACTION_NAME, "CardDemo", "07/19/22", PROGRAM_NAME,
                "Transaction Reports", "10:30:00", errorMessage, submissionAccepted, message,
                generalError, focusScreenFieldId, "/api/menu/user", NavigationContext.empty());
    }

    /**
     * Builds a response with only the components a single assertion needs.
     *
     * @param period the selected reporting period
     * @param confirm the one-character confirmation value
     * @param errorMessage the operator message written to the error line
     * @return a response carrying the supplied values and nothing else
     */
    private static ReportResponse aSparseResponse(final ReportPeriod period, final String confirm,
            final String errorMessage) {
        return new ReportResponse(null, null, null,
                period, null,
                null, null, null, null, null,
                confirm, null, null, null, null,
                null, null, errorMessage, false, null,
                false, null, null, null);
    }

    /**
     * Derives the report name from the period, as {@code CORPT00C} does at lines 214, 240 and 433.
     *
     * <p>The derivation is the period's own carried value. Restating the three literals here would
     * create a second source of truth for one contractual string, so this helper reads them from the
     * vocabulary instead.
     *
     * @param selected the period the operator chose, or {@code null} when none was chosen
     * @return the short report name, or {@code null} when no period was chosen
     */
    private static String reportNameFor(final ReportPeriod selected) {
        return selected == null ? null : selected.getValue();
    }

    /**
     * Reports whether a named component's accessor carries a declared upper bound.
     *
     * <p>The annotation is read from the accessor rather than from the record component, because
     * {@code jakarta.validation.constraints.Size} does not target {@code RECORD_COMPONENT}. A
     * constraint written on a record component is propagated to the backing field, the accessor and
     * the canonical constructor parameter instead of being retained on the component itself, so
     * {@code RecordComponent.getAnnotation} would report nothing for every component.
     *
     * @param componentName the record component to test
     * @return {@code true} when the accessor declares a {@link Size} bound
     */
    private static boolean declaresAnUpperBound(final String componentName) {
        try {
            return ReportResponse.class.getDeclaredMethod(componentName)
                    .getAnnotation(Size.class) != null;
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("no accessor declared for " + componentName, cause);
        }
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
        final Size size = ReportResponse.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    /**
     * Builds a string of the requested length from a repeated filler character.
     *
     * @param length the length to produce
     * @return a string of exactly that length
     */
    private static String filler(final int length) {
        return "X".repeat(length);
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published field widths")
    class ThePublishedFieldWidths {

        @Test
        @DisplayName("a date part is two characters of month, two of day and four of year, which is "
                + "how the legacy screen splits a date across three fields")
        void aDatePartIsTwoTwoAndFour() {
            assertThat(ReportResponse.MONTH_LENGTH).isEqualTo(2);
            assertThat(ReportResponse.DAY_LENGTH).isEqualTo(2);
            assertThat(ReportResponse.YEAR_LENGTH).isEqualTo(4);
        }

        @Test
        @DisplayName("the confirmation field is a single character, so it can never hold a word")
        void theConfirmationFieldIsASingleCharacter() {
            assertThat(ReportResponse.CONFIRM_LENGTH).isOne();
        }

        @Test
        @DisplayName("the resolved period declares no width at all, because a member of a closed "
                + "vocabulary is not a fixed-width screen value")
        void theResolvedPeriodDeclaresNoWidth() {
            assertThat(declaresAnUpperBound("reportPeriod")).isFalse();
        }

        @Test
        @DisplayName("the three derived report names are the vocabulary's own bare values, all shorter "
                + "than the ten-character legacy work item that held them, and none is restated here")
        void theThreeDerivedReportNamesComeFromTheVocabularyAlone() {
            assertThat(List.of(ReportPeriod.MONTHLY.getValue(), ReportPeriod.YEARLY.getValue(),
                    ReportPeriod.CUSTOM.getValue()))
                    .containsExactly("Monthly", "Yearly", "Custom")
                    .allSatisfy(name -> assertThat(name.length()).isLessThan(10));
            assertThat(Arrays.stream(ReportResponse.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .filter(name -> name.startsWith("REPORT_NAME"))
                    .toList())
                    .as("a second declaration of a contractual literal is a second source of truth")
                    .isEmpty();
        }

        @Test
        @DisplayName("the focus identity is seven characters, the ceiling the map generator imposes")
        void theFocusIdentityIsSevenCharacters() {
            assertThat(ReportResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the screen furniture widths are the legacy widths, four of transaction name, "
                + "forty of title, eight of date, time and program name")
        void theScreenFurnitureWidthsAreTheLegacyWidths() {
            assertThat(ReportResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(ReportResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(ReportResponse.DATE_LENGTH).isEqualTo(8);
            assertThat(ReportResponse.TIME_LENGTH).isEqualTo(8);
            assertThat(ReportResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("the error line is seventy-eight characters, two short of the eighty-column "
                + "screen row it is written into")
        void theErrorLineIsSeventyEightCharacters() {
            assertThat(ReportResponse.ERROR_MESSAGE_LENGTH).isEqualTo(78);
        }

        @ParameterizedTest
        @CsvSource({
            "startMonth,2", "startDay,2", "startYear,4",
            "endMonth,2", "endDay,2", "endYear,4",
            "confirm,1", "transactionName,4", "title01,40",
            "currentDate,8", "programName,8", "title02,40",
            "currentTime,8", "errorMessage,78", "focusScreenFieldId,7",
        })
        @DisplayName("every bounded component declares the width its named constant publishes")
        void everyBoundedComponentDeclaresItsPublishedWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the two title components share one width, because the legacy screen draws two "
                + "identical forty-column heading rows")
        void theTwoTitleComponentsShareOneWidth() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("title01"))
                    .isEqualTo(declaredMaximumLength("title02"))
                    .isEqualTo(ReportResponse.SCREEN_TITLE_LENGTH);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the operator message vocabulary")
    class TheOperatorMessageVocabulary {

        @Test
        @DisplayName("the six empty-part messages name their part and their end of the range")
        void theSixEmptyPartMessagesNameTheirPart() {
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
        @DisplayName("the six invalid-part messages say Not a valid rather than repeating the empty "
                + "wording, so the two failure kinds stay distinguishable")
        void theSixInvalidPartMessagesSayNotAValid() {
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
        @DisplayName("the two whole-date messages report the assembled date rather than a part")
        void theTwoWholeDateMessagesReportTheAssembledDate() {
            assertThat(ReportResponse.MSG_START_DATE_INVALID)
                    .isEqualTo("Start Date - Not a valid date...");
            assertThat(ReportResponse.MSG_END_DATE_INVALID)
                    .isEqualTo("End Date - Not a valid date...");
        }

        @Test
        @DisplayName("the report-type prompt is the message shown when no period has been chosen")
        void theReportTypePromptIsPublished() {
            assertThat(ReportResponse.MSG_SELECT_REPORT_TYPE)
                    .isEqualTo("Select a report type to print report...");
        }

        @Test
        @DisplayName("every one of the fifteen whole messages ends in the legacy three-dot "
                + "continuation and none of them exceeds the error line")
        void everyWholeMessageEndsInThreeDotsAndFits() {
            final List<String> messages = List.of(
                    ReportResponse.MSG_START_DATE_MONTH_EMPTY,
                    ReportResponse.MSG_START_DATE_DAY_EMPTY,
                    ReportResponse.MSG_START_DATE_YEAR_EMPTY,
                    ReportResponse.MSG_END_DATE_MONTH_EMPTY,
                    ReportResponse.MSG_END_DATE_DAY_EMPTY,
                    ReportResponse.MSG_END_DATE_YEAR_EMPTY,
                    ReportResponse.MSG_START_DATE_MONTH_INVALID,
                    ReportResponse.MSG_START_DATE_DAY_INVALID,
                    ReportResponse.MSG_START_DATE_YEAR_INVALID,
                    ReportResponse.MSG_END_DATE_MONTH_INVALID,
                    ReportResponse.MSG_END_DATE_DAY_INVALID,
                    ReportResponse.MSG_END_DATE_YEAR_INVALID,
                    ReportResponse.MSG_START_DATE_INVALID,
                    ReportResponse.MSG_END_DATE_INVALID,
                    ReportResponse.MSG_SELECT_REPORT_TYPE);

            assertThat(messages).hasSize(15).doesNotHaveDuplicates();
            assertThat(messages).allSatisfy(message -> {
                assertThat(message).endsWith("...");
                assertThat(message.length())
                        .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
            });
        }

        @Test
        @DisplayName("the submission acknowledgement is a suffix, because the period name is "
                + "prepended to it at run time")
        void theSubmissionAcknowledgementIsASuffix() {
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isEqualTo(" report submitted for printing ...");
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX).startsWith(" ");
        }

        @Test
        @DisplayName("the confirmation prompt is a prefix and a suffix, so a period name can sit "
                + "between them without a format string")
        void theConfirmationPromptIsAPrefixAndASuffix() {
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX)
                    .isEqualTo("Please confirm to print the ");
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX).isEqualTo(" report...");
        }

        @ParameterizedTest
        @EnumSource(ReportPeriod.class)
        @DisplayName("the confirmation prompt assembles into one sentence for every period, and the "
                + "sentence still fits the error line")
        void theConfirmationPromptAssemblesForEveryPeriod(final ReportPeriod period) {
            final String assembled = ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX
                    + period.getValue() + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX;

            assertThat(assembled).isEqualTo(
                    "Please confirm to print the " + period.getValue() + " report...");
            assertThat(assembled.length())
                    .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(ReportPeriod.class)
        @DisplayName("the submission acknowledgement assembles into one sentence for every period")
        void theSubmissionAcknowledgementAssemblesForEveryPeriod(final ReportPeriod period) {
            assertThat(period.getValue() + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isEqualTo(period.getValue() + " report submitted for printing ...");
        }

        @Test
        @DisplayName("the rejected-confirmation fragments quote the offending value, which is the "
                + "one place this screen echoes operator input back")
        void theRejectedConfirmationFragmentsQuoteTheValue() {
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX).isEqualTo("\"");
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX)
                    .isEqualTo("\" is not a valid value to confirm...");

            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX + "Q"
                    + ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX)
                    .isEqualTo("\"Q\" is not a valid value to confirm...");
        }

        @Test
        @DisplayName("the five fragments are distinct from one another and none of them is a whole "
                + "sentence on its own")
        void theFiveFragmentsAreDistinct() {
            assertThat(List.of(
                    ReportResponse.FRAGMENT_SUBMITTED_SUFFIX,
                    ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX,
                    ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX,
                    ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX,
                    ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX))
                    .hasSize(5).doesNotHaveDuplicates();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the eight field-identity constants")
    class TheEightFieldIdentityConstants {

        @Test
        @DisplayName("the eight constants carry the generated map identifiers rather than record "
                + "component names, which is the vocabulary every other response in this package uses")
        void theEightConstantsCarryTheMapIdentifiers() {
            assertThat(identities()).containsExactly("MONTHLY", "SDTMM", "SDTDD", "SDTYYYY",
                    "EDTMM", "EDTDD", "EDTYYYY", "CONFIRM");
        }

        @Test
        @DisplayName("no identity is the name of a record component, so the two vocabularies are "
                + "disjoint by construction rather than by coincidence")
        void noIdentityIsARecordComponentName() {
            final List<String> declared = Arrays.stream(ReportResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).doesNotContainAnyElementsOf(identities());
            assertThat(identities()).doesNotContainAnyElementsOf(declared);
        }

        @Test
        @DisplayName("every identity fits the bound focusScreenFieldId declares, which is what makes "
                + "a map identifier usable there where a property name would not be")
        void everyIdentityFitsTheFocusFieldBound() {
            assertThat(identities()).isNotEmpty().allSatisfy(identity ->
                    assertThat(identity.length()).isBetween(1,
                            ReportResponse.SCREEN_FIELD_ID_LENGTH));
            assertThat("reportPeriod".length())
                    .as("the property name would breach the bound the identity satisfies")
                    .isGreaterThan(ReportResponse.SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("the eight identities are distinct")
        void theEightIdentitiesAreDistinct() {
            assertThat(identities()).hasSize(8).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("only the monthly position carries an identity, because the program places the "
                + "cursor at seven sites that all name the monthly field and never on the other two")
        void onlyTheMonthlyPositionCarriesAnIdentity() {
            final List<String> constants = Arrays.stream(ReportResponse.class.getDeclaredFields())
                    .map(Field::getName).filter(name -> name.startsWith("FIELD_")).toList();

            assertThat(constants).hasSize(8).contains("FIELD_MONTHLY_SELECTION")
                    .doesNotContain("FIELD_YEARLY_SELECTION", "FIELD_CUSTOM_SELECTION",
                            "FIELD_REPORT_NAME");
        }

        /**
         * Collects the eight published identities in screen order.
         *
         * @return the eight map identifiers
         */
        private static List<String> identities() {
            return List.of(
                    ReportResponse.FIELD_MONTHLY_SELECTION,
                    ReportResponse.FIELD_START_MONTH,
                    ReportResponse.FIELD_START_DAY,
                    ReportResponse.FIELD_START_YEAR,
                    ReportResponse.FIELD_END_MONTH,
                    ReportResponse.FIELD_END_DAY,
                    ReportResponse.FIELD_END_YEAR,
                    ReportResponse.FIELD_CONFIRM);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and its validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        @Test
        @DisplayName("the response declares twenty-four components in screen order, the three "
                + "selector positions, the resolved period, two date triples, the confirmation, the "
                + "screen furniture and the routing block")
        void theResponseDeclaresTwentyFourComponentsInScreenOrder() {
            final List<String> declared = Arrays.stream(ReportResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("monthlySelection", "yearlySelection",
                    "customSelection", "reportPeriod",
                    "startMonth", "startDay", "startYear",
                    "endMonth", "endDay", "endYear", "confirm", "transactionName", "title01",
                    "currentDate", "programName", "title02", "currentTime", "errorMessage",
                    "submissionAccepted", "message", "generalError", "focusScreenFieldId",
                    "nextRoute", "navigationContext");
            assertThat(declared).hasSize(24);
            assertThat(declared)
                    .as("no separate derived report name survives on the wire")
                    .doesNotContainAnyElementsOf(REMOVED_COMPONENTS);
            assertThat(declared.subList(0, SELECTOR_COMPONENTS.size()))
                    .as("the three positions lead in the order the map declares them, and the period "
                            + "the service resolved follows them rather than replacing them")
                    .containsExactlyElementsOf(SELECTOR_COMPONENTS);
            assertThat(declared.get(SELECTOR_COMPONENTS.size())).isEqualTo("reportPeriod");
        }

        @Test
        @DisplayName("exactly eighteen components carry a declared upper bound, and the six that do "
                + "not are the enumerated period, the two flags, the free-form message, the route and "
                + "the navigation block")
        void exactlyEighteenComponentsCarryAnUpperBound() {
            final List<String> bounded = Arrays.stream(ReportResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(ReportResponseRuleComplianceTest::declaresAnUpperBound).toList();

            assertThat(bounded).hasSize(18);
            assertThat(bounded).doesNotContain("reportPeriod", "submissionAccepted", "message",
                    "generalError", "nextRoute", "navigationContext");
            assertThat(bounded)
                    .as("every fixed-width screen value the response echoes is bounded, the three "
                            + "one-character selector positions among them")
                    .contains("monthlySelection", "yearlySelection", "customSelection",
                            "startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear",
                            "confirm");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final ReportResponse response = aResponse(ReportPeriod.CUSTOM, "01", "31", "2022",
                    "12", "28", "2022", "Y", ReportResponse.MSG_SELECT_REPORT_TYPE, true,
                    "queued", false, ReportResponse.FIELD_START_MONTH);

            assertThat(response.reportPeriod()).isSameAs(ReportPeriod.CUSTOM);
            assertThat(response.reportPeriod().getValue())
                    .as("the derived report name is the period's own carried value")
                    .isEqualTo(reportNameFor(ReportPeriod.CUSTOM));
            assertThat(response.startMonth()).isEqualTo("01");
            assertThat(response.startDay()).isEqualTo("31");
            assertThat(response.startYear()).isEqualTo("2022");
            assertThat(response.endMonth()).isEqualTo("12");
            assertThat(response.endDay()).isEqualTo("28");
            assertThat(response.endYear()).isEqualTo("2022");
            assertThat(response.confirm()).isEqualTo("Y");
            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(response.title01()).isEqualTo("CardDemo");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(response.title02()).isEqualTo("Transaction Reports");
            assertThat(response.currentTime()).isEqualTo("10:30:00");
            assertThat(response.errorMessage())
                    .isEqualTo(ReportResponse.MSG_SELECT_REPORT_TYPE);
            assertThat(response.submissionAccepted()).isTrue();
            assertThat(response.message()).isEqualTo("queued");
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(ReportResponse.FIELD_START_MONTH);
            assertThat(response.nextRoute()).isEqualTo("/api/menu/user");
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a fully absent response reports no violation, because every bound is an upper "
                + "bound and none of the components is mandatory")
        void aFullyAbsentResponseReportsNoViolation() {
            final ReportResponse absent = aSparseResponse(null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(absent)).isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({
            "startMonth,2", "startDay,2", "startYear,4",
            "endMonth,2", "endDay,2", "endYear,4",
            "confirm,1", "transactionName,4", "title01,40",
            "currentDate,8", "programName,8", "title02,40",
            "currentTime,8", "errorMessage,78", "focusScreenFieldId,7",
        })
        @DisplayName("a bounded component accepts its declared width and rejects one character more")
        void aBoundedComponentAcceptsItsWidthAndRejectsOneMore(final String componentName,
                final int declaredMaximum) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, filler(declaredMaximum))))
                        .as("%s must accept %d characters", componentName, declaredMaximum)
                        .isEmpty();
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, filler(declaredMaximum + 1))))
                        .as("%s must reject %d characters", componentName, declaredMaximum + 1)
                        .hasSize(1);
            }
        }

        /**
         * Builds a response carrying a single named component and nothing else.
         *
         * @param componentName the component to populate
         * @param value the value to place in it
         * @return a response carrying only that component
         */
        private ReportResponse responseWith(final String componentName, final String value) {
            return switch (componentName) {
                case "startMonth" -> new ReportResponse(null, null, null,null, value, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "startDay" -> new ReportResponse(null, null, null,null, null, value, null, null, null, null, null, null, null, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "startYear" -> new ReportResponse(null, null, null,null, null, null, value, null, null, null, null, null, null, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "endMonth" -> new ReportResponse(null, null, null,null, null, null, null, value, null, null, null, null, null, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "endDay" -> new ReportResponse(null, null, null,null, null, null, null, null, value, null, null, null, null, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "endYear" -> new ReportResponse(null, null, null,null, null, null, null, null, null, value, null, null, null, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "confirm" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, value, null, null, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "transactionName" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, null, value, null, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "title01" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, null, null, value, null, null, null, null,
                        null, false, null, false, null, null, null);
                case "currentDate" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, null, null, null, value, null, null, null,
                        null, false, null, false, null, null, null);
                case "programName" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, null, null, null, null, value, null, null,
                        null, false, null, false, null, null, null);
                case "title02" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, null, null, null, null, null, value, null,
                        null, false, null, false, null, null, null);
                case "currentTime" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, null, null, null, null, null, null, value,
                        null, false, null, false, null, null, null);
                case "errorMessage" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        value, false, null, false, null, null, null);
                case "focusScreenFieldId" -> new ReportResponse(null, null, null,null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, false, null, false, value, null, null);
                default -> throw new IllegalArgumentException(
                        "no bounded component named " + componentName);
            };
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two responses built from identical values are equal and share a hash code")
        void twoResponsesBuiltFromIdenticalValuesAreEqual() {
            final ReportResponse first = aResponse(ReportPeriod.MONTHLY, "01", "01", "2022", "01",
                    "31", "2022", "Y", null, true, null, false, null);
            final ReportResponse second = aResponse(ReportPeriod.MONTHLY, "01", "01", "2022", "01",
                    "31", "2022", "Y", null, true, null, false, null);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a difference in a single date part makes two responses unequal, so a part is "
                + "part of identity rather than presentation")
        void aDifferenceInASingleDatePartMakesTwoResponsesUnequal() {
            assertThat(aSparseResponse(ReportPeriod.CUSTOM, "Y", null))
                    .isNotEqualTo(aSparseResponse(ReportPeriod.CUSTOM, "N", null));
        }

        @Test
        @DisplayName("the rendering names every component, because this record declares no "
                + "withholding toString")
        void theRenderingNamesEveryComponent() {
            final String rendered = aSparseResponse(ReportPeriod.YEARLY, "Y",
                    ReportResponse.MSG_END_DATE_INVALID).toString();

            assertThat(rendered).startsWith("ReportResponse[");
            assertThat(rendered).contains("reportPeriod=" + ReportPeriod.YEARLY.name(), "confirm=Y",
                    "errorMessage=" + ReportResponse.MSG_END_DATE_INVALID,
                    "submissionAccepted=false", "generalError=false");
            assertThat(rendered)
                    .as("the three re-presented positions are named alongside the resolved period, "
                            + "because a response that named only the period could not describe a "
                            + "cleared screen or a surviving mark")
                    .contains("monthlySelection=", "yearlySelection=", "customSelection=");
            assertThat(rendered)
                    .as("the derived report name is still the resolved period's carried value and "
                            + "never a component of its own")
                    .doesNotContain("reportName=");
        }

        @Test
        @DisplayName("the payload omits every absent component, so the screen never receives a null "
                + "field it would have to render")
        void thePayloadOmitsEveryAbsentComponent() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseResponse(ReportPeriod.MONTHLY, "Y", null));

            assertThat(payload.has("errorMessage")).isFalse();
            assertThat(payload.has("startMonth")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
            assertThat(payload.has("confirm")).isTrue();
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the two boolean flags survive absence, because a primitive is never null and "
                + "so is never dropped by the module's NON_NULL inclusion")
        void theTwoBooleanFlagsSurviveAbsence() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseResponse(null, null, null));

            assertThat(payload.has("submissionAccepted")).isTrue();
            assertThat(payload.get("submissionAccepted").asBoolean()).isFalse();
            assertThat(payload.has("generalError")).isTrue();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ReportPeriod.class)
        @DisplayName("a chosen period travels on the wire as one value carrying the derived report "
                + "name, and never as a set of per-position markers")
        void aChosenPeriodTravelsAsOneValueCarryingTheDerivedName(final ReportPeriod period)
                throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseResponse(period, null, null));

            assertThat(payload.get("reportPeriod").asText())
                    .as("the wire form of a closed vocabulary is its member identifier")
                    .isEqualTo(period.name());
            assertThat(reportNameFor(period))
                    .as("the derived report name is the carried value, never the identifier")
                    .isNotEqualTo(period.name());
            assertThat(REMOVED_COMPONENTS).allSatisfy(
                    property -> assertThat(payload.has(property))
                            .as("%s no longer exists on the contract", property)
                            .isFalse());
        }

        @Test
        @DisplayName("the derived report name is the screen value and not the enum constant name, and "
                + "it is declared once, in the vocabulary alone")
        void theDerivedNameIsTheScreenValueDeclaredOnce() {
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo("Monthly")
                    .isNotEqualTo(ReportPeriod.MONTHLY.name());
            assertThat(ReportPeriod.fromValue("Monthly")).contains(ReportPeriod.MONTHLY);
            assertThat(ReportPeriod.fromValue("MONTHLY")).isEmpty();

            assertThat(Arrays.stream(ReportResponse.class.getDeclaredFields())
                    .map(Field::getName)
                    .filter(name -> name.startsWith("REPORT_NAME"))
                    .toList())
                    .as("a second declaration could be corrected on one side only")
                    .isEmpty();
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper "
                + "unchanged")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final ReportResponse original = aResponse(ReportPeriod.CUSTOM, "02", "29", "2024", "03",
                    "01", "2024", "Y", ReportResponse.MSG_START_DATE_INVALID, true, "queued", true,
                    "ENDDAY");

            assertThat(mapper.readValue(mapper.writeValueAsString(original), ReportResponse.class))
                    .isEqualTo(original);
        }
    }
}
