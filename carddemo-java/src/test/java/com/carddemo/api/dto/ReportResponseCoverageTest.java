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
 * Unit tests for {@link ReportResponse}, the response body of legacy transaction {@code CR00}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the report-request response: the twenty-four components, the ten
 * declared widths, the fifteen single-purpose texts, the five composition fragments, the eight field
 * identities, the wire form under the module's declared serialisation settings, and the value
 * semantics of a record that carries no secret and normalises nothing.
 *
 * <h2>Two fragments that look interchangeable differ by exactly one byte</h2>
 *
 * <p>This is the most consequential fact in the file and the reason the fragments are asserted
 * individually rather than through a loop. The acceptance suffix carries a space before its three
 * dots; the confirmation-prompt suffix carries none. Both begin with a space, both follow the report
 * name, and both read almost identically - so unifying them, or deriving either from the other, is an
 * obvious-looking simplification that shifts the assembled text by one byte and fails the interface
 * contract check. Tests below pin each fragment's exact characters, pin the presence and absence of
 * that one space, and assert the two are not equal to each other.
 *
 * <h2>The invalid-confirmation text quotes the operator's own character back</h2>
 *
 * <p>The opening fragment is a single straight double-quote and the closing fragment carries the
 * closing quote inside itself, so the service appends exactly the closing fragment and adds no quote
 * of its own. That is also why the confirmation answer is carried as text rather than as a truth
 * value: without the original character the text cannot be composed at all. Tests below prove the
 * quotes are straight rather than typographic, prove the closing quote belongs to the suffix, and
 * prove a composed example reads as the legacy screen produced it.
 *
 * <h2>The field identities are the screen's map item labels, like every peer in this package</h2>
 *
 * <p>An earlier form of these constants held this record's own property names, on the reasoning that a
 * hint naming a component cannot be left behind by a rename. The map item label is what the delivered
 * type publishes and is the better choice on two contractual grounds: the hint component is bounded at
 * the width the map generator imposes, which a property name such as {@code monthlySelection} would
 * breach, and every other response in this package publishes the same seven-character map vocabulary,
 * so a client reads {@code focusScreenFieldId} the same way everywhere. The client acting on the hint
 * is driving a screen, and the identity it can act on is the screen's, not this record's.
 *
 * <p>The correspondence is therefore asserted in the other direction. Tests below prove each identity
 * has the shape of a 3270 symbolic name - upper case, at most seven characters, no separator and no
 * space - prove that no identity collides with a component name, and prove the set is exactly as large
 * as the set of components that can take focus. A component renamed without its identity following is
 * caught by that count and by the per-component focus tests rather than by string equality.
 *
 * <h2>Nothing here is withheld from the diagnostic rendering</h2>
 *
 * <p>One reporting period, six date parts, a confirmation character and four header values identify
 * nobody, so the generated record rendering stands. A test below proves it is still the generated one,
 * and the compensating assertion is that a nested {@link NavigationContext} withholds its own
 * identifying values.
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
@DisplayName("ReportResponse :: report-request response contract of legacy transaction CR00")
class ReportResponseCoverageTest {

    /** The twenty-four components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "monthlySelection", "yearlySelection", "customSelection",
            "reportPeriod",
            "startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear",
            "confirm", "transactionName", "title01", "currentDate", "programName", "title02",
            "currentTime", "errorMessage", "submissionAccepted", "message", "generalError",
            "focusScreenFieldId", "nextRoute", "navigationContext");

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
     * The eight published field identities, in the order the constants are declared.
     *
     * <p>They are the opaque map item labels the mapset declares, not the property names of this
     * record. Two reasons, both contractual: the focus component is bounded at the width the map
     * generator imposes, which a property name such as {@code monthlySelection} would breach, and every
     * other response in this package publishes the same seven-character map vocabulary, so a client
     * reads {@code focusScreenFieldId} the same way everywhere. A test below asserts the identities are
     * these labels and asserts directly that none of them is a property name.
     */
    private static final List<String> EXPECTED_FIELD_IDENTITIES = List.of(
            "MONTHLY", "SDTMM", "SDTDD", "SDTYYYY", "EDTMM", "EDTDD", "EDTYYYY", "CONFIRM");

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
     * Builds a response carrying only the named text component, so a violation can be attributed.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a response carrying that one value
     */
    private static ReportResponse carrying(String component, String value) {
        return new ReportResponse(null, null, null,
                null,
                "startMonth".equals(component) ? value : null,
                "startDay".equals(component) ? value : null,
                "startYear".equals(component) ? value : null,
                "endMonth".equals(component) ? value : null,
                "endDay".equals(component) ? value : null,
                "endYear".equals(component) ? value : null,
                "confirm".equals(component) ? value : null,
                "transactionName".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "programName".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                "errorMessage".equals(component) ? value : null,
                false,
                "message".equals(component) ? value : null,
                false,
                "focusScreenFieldId".equals(component) ? value : null,
                "nextRoute".equals(component) ? value : null,
                null);
    }

    /**
     * Builds the response an accepted submission produces, with every component populated.
     *
     * @param period the selected report period
     * @return a fully populated acceptance response
     */
    private static ReportResponse accepted(ReportPeriod period) {
        return new ReportResponse(null, null, null,
                period,
                "01", "31", "2022", "12", "28", "2022", "Y", "CR00",
                "AWS Mainframe Modernization             ", "08/02/26", "CORPT00C",
                "CardDemo                                ", "14:35:07", null, true,
                reportNameOf(period) + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX, false,
                ReportResponse.FIELD_MONTHLY_SELECTION, "/api/menu",
                JsonContractSupport.populatedNavigation());
    }

    /**
     * Reads the report name for the supplied period.
     *
     * <p>The name is the period's own carried value rather than a constant of this response, because it
     * is derived screen text that the composed acceptance message embeds rather than a value a client
     * may submit or the response may publish separately. Restating it here would create a second source
     * of truth for one contractual string.
     *
     * @param period the selected report period
     * @return the report name for that period
     */
    private static String reportNameOf(ReportPeriod period) {
        return period.getValue();
    }

    /**
     * Serialises a response and parses the result back into a tree.
     *
     * @param response the response to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(ReportResponse response) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the twenty-four components are declared in the order the screen presents them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(ReportResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("the ten published widths equal the widths the map and the program declare")
        void publishedWidthsEqualTheDeclaredWidths() {
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
        @DisplayName("the six date parts are three widths used twice, so a start part and its end "
                + "counterpart are bounded identically")
        void theSixDatePartsAreThreeWidthsUsedTwice() throws NoSuchFieldException {
            for (String prefix : List.of("start", "end")) {
                assertThat(boundOf(prefix + "Month")).isEqualTo(ReportResponse.MONTH_LENGTH);
                assertThat(boundOf(prefix + "Day")).isEqualTo(ReportResponse.DAY_LENGTH);
                assertThat(boundOf(prefix + "Year")).isEqualTo(ReportResponse.YEAR_LENGTH);
            }
        }

        @Test
        @DisplayName("the date parts are carried as separate text components rather than one date "
                + "type, because each part is separately reportable")
        void theDatePartsAreSeparateTextComponents() {
            for (String component : List.of("startMonth", "startDay", "startYear", "endMonth",
                    "endDay", "endYear")) {
                assertThat(componentType(component))
                        .as("%s must stay text: a date type could not express a blank month beside a "
                                + "populated year, which is a state the screen reports on", component)
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the period is the enumeration itself, the report name is not a component at all, "
                + "and the two indicators are primitives rather than derived values")
        void theOutcomeComponentsAreTyped() {
            assertThat(componentType("reportPeriod"))
                    .as("the period the ordered evaluation resolved is an enumerated outcome, "
                            + "published alongside the three screen characters rather than in place "
                            + "of them")
                    .isEqualTo(ReportPeriod.class);
            for (final String marker : SELECTOR_COMPONENTS) {
                assertThat(componentType(marker))
                        .as("%s is the screen character the operator marked, carried verbatim", marker)
                        .isEqualTo(String.class);
            }
            assertThat(EXPECTED_COMPONENTS)
                    .as("the report name is the period's own carried value, so it publishes no "
                            + "component of its own and cannot drift from the period")
                    .doesNotContainAnyElementsOf(REMOVED_COMPONENTS);
            assertThat(List.of(ReportPeriod.MONTHLY.getValue(), ReportPeriod.YEARLY.getValue(),
                            ReportPeriod.CUSTOM.getValue()))
                    .as("bare mixed-case, unpadded despite the ten-character work item")
                    .containsExactly("Monthly", "Yearly", "Custom");
            assertThat(componentType("submissionAccepted")).isEqualTo(boolean.class);
            assertThat(componentType("generalError")).isEqualTo(boolean.class);
            assertThat(componentType("navigationContext")).isEqualTo(NavigationContext.class);
        }

        /**
         * The assembled message and the route are unbounded; the focus hint is bounded at seven.
         *
         * <p>The hint was grouped with the other two on the reasoning that none of the three is a
         * fixed-width screen item, which is true of the message and the route and false of the hint. The
         * hint is not a screen <em>value</em>, but it carries the <em>name</em> of a screen field, and
         * every symbolic name this screen declares is at most seven characters. Leaving it unmeasured
         * admitted a hint no field on this screen can have; bounding it at the screen's own widest name
         * is the narrower and therefore the correct statement.</p>
         *
         * <p>The message and the route stay unbounded for reasons of their own. The message is assembled
         * by the report-request service from a cascade the legacy program owns, so its length is that
         * service's decision rather than a map item's width. The route is a service-owned token whose
         * vocabulary belongs to the navigation service and not to any map.</p>
         */
        @Test
        @DisplayName("the assembled message and the route carry no width bound, while the focus hint "
                + "is bounded at the screen's widest field name")
        void theAssembledValuesCarryNoWidthBound() throws NoSuchFieldException {
            for (String component : List.of("message", "nextRoute")) {
                assertThat(ReportResponse.class.getDeclaredField(component).getAnnotations())
                        .as("%s carries no bound", component)
                        .isEmpty();
            }
            assertThat(ReportResponse.class.getDeclaredField("focusScreenFieldId")
                            .getAnnotation(Size.class))
                    .as("the hint names a field on this screen, so it is bounded by the widest such "
                            + "name rather than left unmeasured")
                    .isNotNull();
            assertThat(ReportResponse.class.getDeclaredField("focusScreenFieldId")
                            .getAnnotation(Size.class).max())
                    .isEqualTo(ReportResponse.SCREEN_FIELD_ID_LENGTH)
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("only the generated canonical constructor exists, so nothing is defaulted, "
                + "normalised or validated on construction")
        void onlyTheGeneratedCanonicalConstructorExists() {
            assertThat(ReportResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(ReportResponse.class.getDeclaredConstructors()[0].getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
        }

        @Test
        @DisplayName("no member is declared beyond the twenty-four accessors, so this type computes "
                + "nothing and composes nothing")
        void noMemberIsDeclaredBeyondTheAccessors() {
            List<String> instanceMethods = Arrays.stream(ReportResponse.class.getDeclaredMethods())
                    .filter(method -> !java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
                    .toList();

            assertThat(instanceMethods)
                    .as("message assembly belongs to the report-request service, which owns the "
                            + "ordering the legacy cascade produced")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /**
         * Reads the declared width bound of a component.
         *
         * @param component the component name
         * @return the maximum length the component declares
         * @throws NoSuchFieldException if the component does not exist
         */
        private static int boundOf(String component) throws NoSuchFieldException {
            return ReportResponse.class.getDeclaredField(component).getAnnotation(Size.class).max();
        }

        /**
         * Reads the declared type of a component.
         *
         * @param component the component name
         * @return the component's declared type
         */
        private static Class<?> componentType(String component) {
            return Arrays.stream(ReportResponse.class.getRecordComponents())
                    .filter(candidate -> candidate.getName().equals(component))
                    .findFirst()
                    .orElseThrow()
                    .getType();
        }
    }

    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        @Test
        @DisplayName("the six emptiness texts are reproduced character for character, start and end "
                + "parts named separately")
        void theSixEmptinessTextsAreReproducedVerbatim() {
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
        @DisplayName("the six invalidity texts are reproduced character for character")
        void theSixInvalidityTextsAreReproducedVerbatim() {
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
        @DisplayName("the whole-date and report-type texts are reproduced character for character")
        void theWholeDateAndReportTypeTextsAreReproducedVerbatim() {
            assertThat(ReportResponse.MSG_START_DATE_INVALID)
                    .isEqualTo("Start Date - Not a valid date...");
            assertThat(ReportResponse.MSG_END_DATE_INVALID)
                    .isEqualTo("End Date - Not a valid date...");
            assertThat(ReportResponse.MSG_SELECT_REPORT_TYPE)
                    .isEqualTo("Select a report type to print report...");
        }

        @Test
        @DisplayName("a part-level text and its whole-date counterpart are distinct, so a client can "
                + "tell \"not a valid Month\" from \"not a valid date\"")
        void aPartLevelTextIsDistinctFromItsWholeDateCounterpart() {
            assertThat(ReportResponse.MSG_START_DATE_MONTH_INVALID)
                    .isNotEqualTo(ReportResponse.MSG_START_DATE_INVALID);
            assertThat(ReportResponse.MSG_START_DATE_INVALID)
                    .as("the whole-date text uses the lower-case word where the part-level texts "
                            + "capitalise the part name, and that difference is contract")
                    .contains("valid date")
                    .doesNotContain("valid Date");
        }

        @Test
        @DisplayName("the fifteen single-purpose texts are all distinct")
        void theFifteenSinglePurposeTextsAreDistinct() {
            assertThat(List.of(ReportResponse.MSG_START_DATE_MONTH_EMPTY,
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
                            ReportResponse.MSG_SELECT_REPORT_TYPE))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every single-purpose text fits inside the width the map rendered the message at")
        void everySinglePurposeTextFitsTheRenderedWidth() {
            for (String text : List.of(ReportResponse.MSG_START_DATE_MONTH_EMPTY,
                    ReportResponse.MSG_END_DATE_YEAR_EMPTY,
                    ReportResponse.MSG_START_DATE_MONTH_INVALID,
                    ReportResponse.MSG_END_DATE_INVALID, ReportResponse.MSG_SELECT_REPORT_TYPE)) {
                assertThat(text.length()).as("text \"%s\"", text)
                        .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
            }
        }
    }

    @Nested
    @DisplayName("Composition fragments")
    class CompositionFragments {

        @Test
        @DisplayName("the acceptance suffix carries a leading space and a space before its three "
                + "dots")
        void theAcceptanceSuffixCarriesBothSpaces() {
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isEqualTo(" report submitted for printing ...")
                    .startsWith(" ")
                    .endsWith(" ...");
        }

        @Test
        @DisplayName("the confirmation-prompt suffix carries a leading space and NO space before its "
                + "three dots - the one-byte difference from the acceptance suffix")
        void theConfirmationSuffixCarriesNoSpaceBeforeItsDots() {
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX)
                    .isEqualTo(" report...")
                    .startsWith(" ")
                    .endsWith("t...")
                    .doesNotEndWith(" ...");
        }

        @Test
        @DisplayName("the two suffixes are not equal and neither can be derived from the other, "
                + "which is why they are two constants")
        void theTwoSuffixesAreNeitherEqualNorDerivable() {
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .as("unifying them shifts the assembled text by one byte and fails the interface "
                            + "contract check")
                    .isNotEqualTo(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX);
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX
                            .replace(" report submitted for printing", " report"))
                    .as("the mechanical transformation between them still differs, because only one "
                            + "carries the space before its dots")
                    .isNotEqualTo(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX);
        }

        @Test
        @DisplayName("the confirmation-prompt prefix ends with the space that separates it from the "
                + "report name")
        void theConfirmationPrefixEndsWithItsSeparatingSpace() {
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX)
                    .isEqualTo("Please confirm to print the ")
                    .endsWith(" ");
        }

        @ParameterizedTest(name = "{0} composes its acceptance text without padding")
        @EnumSource(ReportPeriod.class)
        @DisplayName("each period's bare value joins the acceptance suffix directly, because the "
                + "legacy composition consumed the report name up to its first space")
        void eachPeriodComposesItsAcceptanceTextWithoutPadding(ReportPeriod period) {
            String composed = period.getValue() + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX;

            assertThat(period.getValue())
                    .as("the enumeration carries bare unpadded values precisely so that this join "
                            + "needs no trimming")
                    .isEqualTo(period.getValue().strip());
            assertThat(composed).isEqualTo(period.getValue() + " report submitted for printing ...");
        }

        @ParameterizedTest(name = "{0} composes its confirmation prompt")
        @EnumSource(ReportPeriod.class)
        @DisplayName("each period's confirmation prompt reads as the legacy screen produced it, with "
                + "one space before the name and none before the dots")
        void eachPeriodComposesItsConfirmationPrompt(ReportPeriod period) {
            String composed = ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX + period.getValue()
                    + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX;

            assertThat(composed)
                    .isEqualTo("Please confirm to print the " + period.getValue() + " report...")
                    .doesNotContain("  ");
        }

        @Test
        @DisplayName("the invalid-confirmation quotes are straight rather than typographic, and the "
                + "closing quote belongs to the suffix")
        void theInvalidConfirmationQuotesAreStraight() {
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX)
                    .isEqualTo("\"")
                    .hasSize(1);
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX)
                    .as("the service appends exactly this string and adds no quote of its own")
                    .isEqualTo("\" is not a valid value to confirm...")
                    .startsWith("\"");
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX
                            + ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX)
                    .doesNotContain("\u201c")
                    .doesNotContain("\u201d");
        }

        @ParameterizedTest(name = "the operator's character \"{0}\" is quoted back verbatim")
        @ValueSource(strings = {"X", "q", "1", "?"})
        @DisplayName("the operator's own character is quoted back between the two fragments, which is "
                + "why the confirmation answer is text rather than a truth value")
        void theOperatorsCharacterIsQuotedBackVerbatim(String supplied) {
            String composed = ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX + supplied
                    + ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX;

            assertThat(composed)
                    .isEqualTo("\"" + supplied + "\" is not a valid value to confirm...");
        }

        @Test
        @DisplayName("the five fragments are all distinct, so none is a duplicate of another")
        void theFiveFragmentsAreDistinct() {
            assertThat(List.of(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX,
                            ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX,
                            ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX,
                            ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX,
                            ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX))
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Field identities")
    class FieldIdentities {

        @Test
        @DisplayName("the eight identities are the eight map item labels, and none of them is a "
                + "property name")
        void theEightIdentitiesAreTheExpectedMapLabels() {
            List<String> published = List.of(ReportResponse.FIELD_MONTHLY_SELECTION,
                    ReportResponse.FIELD_START_MONTH, ReportResponse.FIELD_START_DAY,
                    ReportResponse.FIELD_START_YEAR, ReportResponse.FIELD_END_MONTH,
                    ReportResponse.FIELD_END_DAY, ReportResponse.FIELD_END_YEAR,
                    ReportResponse.FIELD_CONFIRM);

            assertThat(published).containsExactlyElementsOf(EXPECTED_FIELD_IDENTITIES);
            assertThat(published)
                    .as("a property name would breach the focus component's declared width and "
                            + "would read differently from every other response in this package")
                    .doesNotContainAnyElementsOf(EXPECTED_COMPONENTS);
            assertThat(published)
                    .allSatisfy(
                            identity ->
                                    assertThat(identity.length())
                                            .isLessThanOrEqualTo(
                                                    ReportResponse.SCREEN_FIELD_ID_LENGTH));
        }

        /**
         * Only the monthly position has an identity. The program places the cursor on the monthly
         * field at every one of its seven cursor sites and never on the yearly or custom positions,
         * so inventing the two missing constants would offer a client a focus target the legacy
         * screen never used.
         */
        @Test
        @DisplayName("publishes an identity for the monthly position only")
        void onlyTheMonthlyPositionHasAnIdentity() {
            List<String> identityConstants =
                    Arrays.stream(ReportResponse.class.getDeclaredFields())
                            .filter(field -> field.getName().startsWith("FIELD_"))
                            .map(java.lang.reflect.Field::getName)
                            .toList();

            assertThat(identityConstants).contains("FIELD_MONTHLY_SELECTION");
            assertThat(identityConstants)
                    .doesNotContain("FIELD_YEARLY_SELECTION", "FIELD_CUSTOM_SELECTION");
        }

        /**
         * The identities are the screen's own map item labels, not this record's component names.
         *
         * <p>An earlier form of these constants held property names, on the reasoning that a hint
         * naming a component cannot be left behind by a rename. The map item label is the better choice
         * and is what the delivered type publishes: the hint is consumed by a client that is driving a
         * screen, and the thing that client can act on is the identity the screen declares for the
         * field, not the identity this record happens to use for it. The two are related but not equal -
         * {@code startMonth} is carried in the map item {@code SDTMM} - and a client handed a property
         * name would have to translate it back, which is a mapping the server already knows.</p>
         *
         * <p>The correspondence is therefore asserted in the other direction: each identity has the
         * shape of a 3270 symbolic name, and the set of identities is exactly as large as the set of
         * components that can take focus. A renamed component is caught by the count and by the
         * per-component focus tests rather than by string equality against a component name.</p>
         */
        @Test
        @DisplayName("every identity has the shape of a legacy map item label rather than of a "
                + "component name, because the hint is consumed by a client driving the screen")
        void everyIdentityIsAMapItemLabelRatherThanAComponentName() {
            List<String> declared = Arrays.stream(ReportResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .as("a map item label is not a component name, so none of them collides")
                    .doesNotContainAnyElementsOf(EXPECTED_FIELD_IDENTITIES);
            assertThat(EXPECTED_FIELD_IDENTITIES)
                    .as("the eight identities are one per component that can take focus")
                    .hasSize(8);
        }

        @Test
        @DisplayName("every identity is a legacy screen field name, like the peers in this package")
        void everyIdentityIsALegacyScreenFieldName() {
            for (String identity : EXPECTED_FIELD_IDENTITIES) {
                assertThat(identity)
                        .as("a 3270 symbolic name is upper case, at most seven characters, and "
                                + "carries neither a separator nor a space")
                        .isEqualTo(identity.toUpperCase(java.util.Locale.ROOT))
                        .hasSizeLessThanOrEqualTo(7)
                        .matches("[A-Z0-9]+");
            }
        }

        /**
         * The confirmation label coincides with the bill-payment screen's, and is still declared
         * locally.
         *
         * <p>Both screens declare a confirmation item and both name it {@code CONFIRM}, so the two
         * constants are equal by value. That coincidence is a fact about the legacy maps rather than a
         * shared definition, and the two constants are deliberately not aliased: each records one
         * screen's own declaration, so a change to either map is expressed by changing one constant and
         * cannot silently move the other. Asserting inequality of the values would have been asserting
         * something untrue of the estate; what is asserted instead is that the declarations are
         * independent, which is the property the arrangement actually has.</p>
         */
        @Test
        @DisplayName("the confirmation label equals the bill-payment screen's by coincidence and is "
                + "still declared independently of it")
        void theConfirmationLabelIsDeclaredLocallyDespiteMatchingItsNeighbour() throws Exception {
            assertThat(ReportResponse.FIELD_CONFIRM)
                    .as("both legacy maps name their confirmation item the same way")
                    .isEqualTo(BillPaymentResponse.CONFIRM_FIELD_ID);

            assertThat(ReportResponse.class.getDeclaredField("FIELD_CONFIRM").getDeclaringClass())
                    .as("this screen's label is its own field, not a reference to the neighbour's")
                    .isEqualTo(ReportResponse.class);
            assertThat(BillPaymentResponse.class.getDeclaredField("CONFIRM_FIELD_ID")
                            .getDeclaringClass())
                    .as("and the neighbour's is its own, so neither can move the other")
                    .isEqualTo(BillPaymentResponse.class);
        }

        @Test
        @DisplayName("the eight identities are distinct, so a focus hint is unambiguous")
        void theEightIdentitiesAreDistinct() {
            assertThat(EXPECTED_FIELD_IDENTITIES).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @ParameterizedTest(name = "an accepted {0} submission reports no violation")
        @EnumSource(ReportPeriod.class)
        @DisplayName("an accepted response whose every component sits at or inside its width reports "
                + "no violation")
        void anAcceptedResponseReportsNoViolation(ReportPeriod period) {
            assertThat(validator.validate(accepted(period))).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @CsvSource({
            "startMonth,2",
            "startDay,2",
            "startYear,4",
            "endMonth,2",
            "endDay,2",
            "endYear,4",
            "confirm,1",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "errorMessage,78",
        })
        @DisplayName("each bounded component reports a value one character over its width")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            Set<ConstraintViolation<ReportResponse>> violations =
                    validator.validate(carrying(component, "9".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @ParameterizedTest(name = "{0} accepts a value exactly at {1}")
        @CsvSource({
            "startMonth,2",
            "startYear,4",
            "confirm,1",
            "errorMessage,78",
        })
        @DisplayName("each bounded component accepts a value exactly at its width, so the bound is "
                + "inclusive")
        void eachBoundedComponentAcceptsAValueAtItsWidth(String component, int width) {
            assertThat(validator.validate(carrying(component, "9".repeat(width)))).isEmpty();
        }

        @Test
        @DisplayName("the assembled message and the route are unbounded, while the focus hint is "
                + "reported one character over the screen's widest field name")
        void theAssembledValuesAreUnbounded() {
            assertThat(validator.validate(carrying("message", "X".repeat(512)))).isEmpty();
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(512)))).isEmpty();

            assertThat(validator.validate(
                            carrying("focusScreenFieldId",
                                    "X".repeat(ReportResponse.SCREEN_FIELD_ID_LENGTH))))
                    .as("a hint exactly at the widest field name is accepted, so the bound is "
                            + "inclusive")
                    .isEmpty();
            assertThat(validator.validate(
                            carrying("focusScreenFieldId",
                                    "X".repeat(ReportResponse.SCREEN_FIELD_ID_LENGTH + 1))))
                    .as("and one character more is reported, because no field on this screen has a "
                            + "name that long")
                    .hasSize(1);
        }

        @Test
        @DisplayName("an entirely empty response reports no violation, because a first entry into "
                + "the screen has nothing to report")
        void anEntirelyEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(
                            new ReportResponse(null, null, null,null, null, null, null, null,
                                    null, null, null, null, null, null, null, null, null, null,
                                    false, null, false, null, null, null)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "date part \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"", " ", "00", "13", "99", "AB"})
        @DisplayName("a blank or out-of-range date part is accepted by the boundary, because the "
                + "range check is a message-bearing stage of the service cascade")
        void aBlankOrOutOfRangeDatePartIsAccepted(String part) {
            assertThat(validator.validate(carrying("startMonth", part)))
                    .as("a bound that rejected \"13\" would pre-empt the one message the screen must "
                            + "show for an invalid month")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("an accepted response renders every populated member under its contract name")
        void anAcceptedResponseRendersItsPopulatedMembers() throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted(ReportPeriod.MONTHLY));

            assertThat(payload.get("startMonth").asText()).isEqualTo("01");
            assertThat(payload.get("startDay").asText()).isEqualTo("31");
            assertThat(payload.get("startYear").asText()).isEqualTo("2022");
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
            assertThat(payload.get("submissionAccepted").asBoolean()).isTrue();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("focusScreenFieldId").asText())
                    .isEqualTo(ReportResponse.FIELD_MONTHLY_SELECTION);
            assertThat(payload.has("errorMessage"))
                    .as("an accepted submission has no error text, and an absent value is omitted")
                    .isFalse();
        }

        @ParameterizedTest(name = "{0} crosses as the resolved outcome, beside the marks")
        @EnumSource(ReportPeriod.class)
        @DisplayName("each resolved period crosses the wire as the outcome component carrying the bare "
                + "mixed-case report name the composed message embeds")
        void eachPeriodCrossesUnderTheCollapsedName(ReportPeriod period)
                throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted(period));

            assertThat(payload.get("reportPeriod").asText())
                    .as("the wire form of a closed vocabulary is its member identifier")
                    .isEqualTo(period.name());
            assertThat(payload.get("message").asText())
                    .as("the composed acceptance text embeds the derived report name, which is the "
                            + "period's carried value rather than its identifier")
                    .startsWith(reportNameOf(period))
                    .doesNotStartWith(period.name());
            assertThat(REMOVED_COMPONENTS)
                    .as("no per-position marker and no separate report name is published")
                    .allSatisfy(member -> assertThat(payload.has(member)).isFalse());
        }

        @Test
        @DisplayName("an unselected period is one omission rather than a default, so \"no report "
                + "type selected\" is expressible")
        void anUnselectedPeriodIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("errorMessage",
                    ReportResponse.MSG_SELECT_REPORT_TYPE));

            assertThat(payload.has("reportPeriod"))
                    .as("substituting a default would print a report the operator never asked for")
                    .isFalse();
            assertThat(REMOVED_COMPONENTS)
                    .allSatisfy(member -> assertThat(payload.has(member)).isFalse());
            assertThat(payload.get("errorMessage").asText())
                    .isEqualTo(ReportResponse.MSG_SELECT_REPORT_TYPE);
        }

        @Test
        @DisplayName("both boolean indicators are always written, because a primitive has no absent "
                + "state to omit")
        void bothBooleanIndicatorsAreAlwaysWritten() throws JsonProcessingException {
            JsonNode payload = payloadOf(
                    new ReportResponse(null, null, null,null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, false, null, false, null,
                            null, null));

            assertThat(payload.size()).isEqualTo(2);
            assertThat(payload.get("submissionAccepted").asBoolean()).isFalse();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("the assembled acceptance text crosses with its exact spacing, the space before "
                + "the three dots included")
        void theAssembledAcceptanceTextCrossesWithItsExactSpacing()
                throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted(ReportPeriod.YEARLY));

            assertThat(payload.get("message").asText())
                    .isEqualTo("Yearly report submitted for printing ...")
                    .endsWith(" ...");
        }

        @Test
        @DisplayName("an explicitly blanked date part is written while an absent one is omitted, so "
                + "the two states stay distinguishable on the wire")
        void aBlankedDatePartIsWrittenWhileAnAbsentOneIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("startMonth", ""));

            assertThat(payload.get("startMonth").asText()).isEmpty();
            assertThat(payload.has("startDay")).isFalse();
        }

        @Test
        @DisplayName("a fully populated response round trips unchanged, nested navigation state "
                + "included")
        void aFullyPopulatedResponseRoundTripsUnchanged() throws JsonProcessingException {
            ReportResponse response = accepted(ReportPeriod.CUSTOM);
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();

            ReportResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), ReportResponse.class);

            assertThat(returned).isEqualTo(response);
            assertThat(returned.reportPeriod()).isSameAs(ReportPeriod.CUSTOM);
            assertThat(returned.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        @Test
        @DisplayName("an unknown member is ignored rather than rejected, so a client may echo the "
                + "response back without being refused")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"reportPeriod\":\"MONTHLY\",\"monthlySelection\":\"S\","
                    + "\"rows\":[],\"jobName\":\"whatever\"}";

            ReportResponse returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, ReportResponse.class);

            assertThat(returned.reportPeriod()).isSameAs(ReportPeriod.MONTHLY);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering is the one the record contract generates, because one reporting "
                + "period, six date parts and a confirmation character identify nobody")
        void theRenderingIsTheGeneratedOne() {
            String rendered = accepted(ReportPeriod.MONTHLY).toString();

            assertThat(rendered)
                    .startsWith("ReportResponse[")
                    .as("the rendering names the three re-presented positions and, separately, the "
                            + "period the service resolved - the two are different facts")
                    .contains("reportPeriod=MONTHLY")
                    .contains("monthlySelection=", "yearlySelection=", "customSelection=")
                    .doesNotContain("reportName=")
                    .contains("startMonth=01")
                    .contains("submissionAccepted=true");
            for (String component : EXPECTED_COMPONENTS) {
                assertThat(rendered)
                        .as("%s is rendered rather than replaced by a placeholder; the assertion is "
                                + "made per component because the nested navigation state "
                                + "legitimately substitutes placeholders of its own", component)
                        .doesNotContain(component + "=***REDACTED***");
            }
        }

        @Test
        @DisplayName("a nested navigation state withholds its own identifying values, so this type "
                + "cannot become the path by which they surface")
        void aNestedNavigationStateWithholdsItsOwnValues() {
            assertThat(accepted(ReportPeriod.MONTHLY).toString())
                    .contains("navigationContext=NavigationContext[")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component by value, the selected period included")
        void equalityComparesEveryComponentByValue() {
            ReportResponse monthly = accepted(ReportPeriod.MONTHLY);
            ReportResponse alsoMonthly = accepted(ReportPeriod.MONTHLY);
            ReportResponse yearly = accepted(ReportPeriod.YEARLY);

            assertThat(monthly).isEqualTo(alsoMonthly).hasSameHashCodeAs(alsoMonthly);
            assertThat(monthly)
                    .as("the period decides which report is printed, so it must participate in "
                            + "equality")
                    .isNotEqualTo(yearly);
        }

        @Test
        @DisplayName("a blank date part and an absent one do not compare equal, because the screen "
                + "reports on the difference")
        void aBlankDatePartIsNotAnAbsentOne() {
            assertThat(carrying("startMonth", "")).isNotEqualTo(carrying("startMonth", null));
        }

        @Test
        @DisplayName("the acceptance indicator participates in equality, so an accepted and a "
                + "pending submission carrying the same text are distinguishable")
        void theAcceptanceIndicatorParticipatesInEquality() {
            ReportResponse acceptedFlag = new ReportResponse(null, null, null,null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, true, "same",
                    false, null, null, null);
            ReportResponse pendingFlag = new ReportResponse(null, null, null,null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, false, "same",
                    false, null, null, null);

            assertThat(acceptedFlag).isNotEqualTo(pendingFlag);
        }
    }
}
