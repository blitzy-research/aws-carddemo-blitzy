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

import com.carddemo.domain.enums.ReportPeriod;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReportResponse}, the response body of legacy transaction {@code CR00}.
 *
 * <p>A pure unit test: no application context, no servlet environment, no container and no database.
 * Every instance is built through the canonical constructor and every observation is made either
 * through an accessor, through the serialized wire form, or through a bean-validation run. Nothing
 * here inspects the type's structure at run time - immutability, component typing and the absence of
 * an unwanted component are all demonstrated by construction and by the wire form, which is both the
 * cheaper proof and the one a client can actually observe.
 *
 * <p><strong>What this file exists to pin.</strong> The report screen carries four distinct operator
 * texts, three of which the legacy program <em>assembles</em> from fragments around a value known
 * only at request time. Two of those three share the word {@code report} and differ from one another
 * by the placement of a single space. A well-meaning simplification that unified them, trimmed a
 * fragment, or derived one from the other would compile, would read better than the original, and
 * would shift observable output by one byte - which is the whole of what the interface-contract gate
 * measures. Each shape is therefore asserted against a literal written out in full in this file
 * rather than assembled by any production helper, so the assertion is an independent oracle.
 *
 * <p><strong>The second thing pinned here is the shape of the echo.</strong> The screen presents
 * three mutually exclusive report-type positions and six separate date components. The three
 * positions collapse into one enumerated period, because the program acts on exactly one of them and
 * a single value therefore loses nothing the screen could mean while making a multiply-marked state
 * unrepresentable. The six date components, by contrast, stay six text components rather than one
 * merged date: that reduction <em>would</em> destroy information the legacy screen carried, namely the
 * leading zero on a single-digit month and the ability to report which part failed. The date
 * components are proven to be text by static typing: every read below is assigned to an explicitly
 * declared {@code String} local, so a future change to a calendar type could not compile rather than
 * merely failing an assertion. The derived report name is the period's own carried value rather than a
 * component of its own, so it can neither drift from the period nor be submitted apart from it.
 *
 * <p><strong>The third is an asymmetry.</strong> An acknowledgement is not an error, and a silent
 * rejection is not a success. The legacy program sets its error flag independently of whether it
 * composed any text - the negative-confirmation branch sets the flag and composes nothing at all -
 * so the flag is carried as its own value and all four combinations of text presence and flag state
 * are asserted to be representable.
 *
 * <p><strong>What is deliberately not here.</strong> Building the fixed-width job image the legacy
 * program handed to the online-to-batch bridge belongs to the utility layer, and publishing it
 * belongs to the service layer. This response acknowledges a submission without describing one, so
 * the wire form is asserted to carry no image, no queue identity, no ordering identity and no
 * sentinel. Draining a real queue to prove the publish contract belongs to the integration and
 * end-to-end tiers, which have a broker; asserting it here against a builder's return value would
 * only restate the builder.
 *
 * <p>Widths, texts and behaviour were read from {@code app/cpy-bms/CORPT00.CPY},
 * {@code app/bms/CORPT00.bms}, {@code app/cbl/CORPT00C.cbl} and {@code app/csd/CARDDEMO.CSD} in the
 * analysed checkout, whose commit is recorded once in the traceability matrix rather than embedded
 * here as a value. Only quoted operator-visible text is reproduced; no legacy grammar appears.
 */
@DisplayName("ReportResponse :: the report-request acknowledgement of legacy transaction CR00")
class ReportResponseTest {

    /*
     * ---------------------------------------------------------------------------------------------
     * INDEPENDENT ORACLES
     * ---------------------------------------------------------------------------------------------
     * Each literal below is written out in full, byte for byte, rather than assembled from the
     * constants published by the contract under test. Deriving an expectation from the thing being
     * tested would make an edit to a published fragment invisible: both sides would move together
     * and the assertion would still pass. The assembled forms are then additionally cross-checked
     * against fragment concatenation, so a divergence between the two is reported rather than
     * silently absorbed.
     * ---------------------------------------------------------------------------------------------
     */

    /** The rejection shown when no report type was selected: 39 characters, three trailing dots. */
    private static final String NO_SELECTION_TEXT = "Select a report type to print report...";

    /** The acceptance text for the monthly report: the bare name then the 34-character fragment. */
    private static final String ACCEPTED_MONTHLY = "Monthly report submitted for printing ...";

    /** The acceptance text for the yearly report. */
    private static final String ACCEPTED_YEARLY = "Yearly report submitted for printing ...";

    /** The acceptance text for the custom-range report. */
    private static final String ACCEPTED_CUSTOM = "Custom report submitted for printing ...";

    /** The confirmation prompt for the monthly report: 28-character prefix, name, 10-character tail. */
    private static final String CONFIRM_MONTHLY = "Please confirm to print the Monthly report...";

    /** The confirmation prompt for the yearly report. */
    private static final String CONFIRM_YEARLY = "Please confirm to print the Yearly report...";

    /** The confirmation prompt for the custom-range report. */
    private static final String CONFIRM_CUSTOM = "Please confirm to print the Custom report...";

    /** The invalid-confirmation rejection for an upper-case character that is neither yes nor no. */
    private static final String INVALID_CONFIRM_UPPER = "\"X\" is not a valid value to confirm...";

    /** The same rejection for a lower-case character, which the legacy program never case folds. */
    private static final String INVALID_CONFIRM_LOWER = "\"q\" is not a valid value to confirm...";

    /**
     * The same rejection when the character contributed nothing.
     *
     * <p>The legacy composition consumes the confirmation field delimited by space, so a field
     * holding a space contributes no characters at all and the two quotes close on nothing. The
     * result is still well formed, which is what this oracle records. The legacy program cannot in
     * fact reach this branch with a blank field, because the blank test runs first and raises the
     * confirmation prompt instead; the value is pinned so that the delimiter semantics stay explicit
     * rather than being rediscovered by whoever next edits the assembling service.
     */
    private static final String INVALID_CONFIRM_BLANK = "\"\" is not a valid value to confirm...";

    /**
     * The text the legacy program showed when the write to the online-to-batch bridge failed: 29
     * characters, three trailing dots.
     *
     * <p><strong>This is a non-fatal condition, and that is the point of pinning it here.</strong>
     * The bridge is defined with its error option set to ignore, so a failed write produced this
     * screen message and the transaction carried on; the operator was offered no exception, no retry
     * and no failure code. In the migrated system the failure is logged and surfaced through the same
     * ordinary message component as every other text, which is exactly what the assertions below
     * demonstrate. The parenthesised name inside the text is the legacy destination named in the
     * operator's own message - it is reproduced because the text is an external contract, and it is
     * not a resource identity that this contract carries as data.
     *
     * <p>Declared here rather than read from the contract under test on purpose: the contract does
     * not publish this text, because the module's job-submission failure type already owns it
     * character for character and a second declaration would be a second source of truth. This test
     * must not reach into that type - it belongs to a layer this package may not depend on - so the
     * expectation is written out here instead.
     */
    private static final String QUEUE_WRITE_FAILURE_TEXT = "Unable to Write TDQ (JOBS)...";

    /** The three bare report names, in the order the program's ordered evaluation reaches them. */
    private static final List<String> REPORT_NAMES_IN_EVALUATION_ORDER =
            List.of("Monthly", "Yearly", "Custom");

    /** The three periods, in the order the program's ordered evaluation reaches them. */
    private static final List<ReportPeriod> PERIODS_IN_EVALUATION_ORDER =
            List.of(ReportPeriod.MONTHLY, ReportPeriod.YEARLY, ReportPeriod.CUSTOM);

    /** Every component the contract publishes, in declaration order. */
    private static final List<String> ALL_COMPONENTS = List.of(
            "reportPeriod", "startMonth",
            "startDay", "startYear", "endMonth", "endDay", "endYear", "confirm", "transactionName",
            "title01", "currentDate", "programName", "title02", "currentTime", "errorMessage",
            "submissionAccepted", "message", "generalError", "focusScreenFieldId", "nextRoute",
            "navigationContext");

    /**
     * The three per-position selector properties and the separate report-name property the collapse
     * removed. Asserted absent, because their survival would reintroduce the multiply-marked state the
     * single component exists to make unrepresentable, and would give the derived name a second, drifting
     * source of truth.
     */
    private static final List<String> REMOVED_COMPONENTS = List.of(
            "monthlySelection", "yearlySelection", "customSelection", "reportName");

    /** A 40-character screen title, at the exact width the symbolic map declares. */
    private static final String TITLE_UPPER = "      AWS Mainframe Modernization       ";

    /** The second 40-character screen title. */
    private static final String TITLE_LOWER = "         Transaction Reports            ";

    /**
     * A response with every one of the twenty-one components populated.
     *
     * <p>Used wherever the assertion is about the wire form as a whole - that every component is
     * published under its own name, that the key count is exactly the component count, and that no
     * extra key appears. The date components carry a leading zero deliberately, and both flags are
     * set so that neither can be mistaken for a default.
     *
     * @return a fully populated response
     */
    private static ReportResponse everyComponentPresent() {
        return new ReportResponse(ReportPeriod.CUSTOM, "07", "01", "2022", "07", "19", "2022",
                "Y", "CR00", TITLE_UPPER, "07/19/22", "CORPT00C", TITLE_LOWER, "19:27:53",
                ACCEPTED_CUSTOM, true, ACCEPTED_CUSTOM, false,
                ReportResponse.FIELD_MONTHLY_SELECTION, "/api/v1/reports", navigation());
    }

    /**
     * A response with every component absent and both flags clear.
     *
     * <p>This is a legitimate state rather than a degenerate one: after an accepted submission the
     * legacy program wipes the three report-type positions, all six date components, the confirmation
     * field and the message field before re-presenting the screen.
     *
     * @return an empty response
     */
    private static ReportResponse everyComponentAbsent() {
        return new ReportResponse(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, false, null, false, null, null, null);
    }

    /**
     * A response carrying only the echoed reporting period.
     *
     * @param period the echoed reporting period, or {@code null} for the unmarked state
     * @return a response carrying that period and nothing else
     */
    private static ReportResponse selecting(ReportPeriod period) {
        return new ReportResponse(period, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, false, null, false, null, null,
                null);
    }

    /**
     * A response carrying only the six echoed date components and the confirmation character.
     *
     * @param startMonth the echoed start-of-range month
     * @param startDay the echoed start-of-range day
     * @param startYear the echoed start-of-range year
     * @param endMonth the echoed end-of-range month
     * @param endDay the echoed end-of-range day
     * @param endYear the echoed end-of-range year
     * @param confirm the echoed confirmation character
     * @return a response carrying the seven values and nothing else
     */
    private static ReportResponse echoing(String startMonth, String startDay, String startYear,
            String endMonth, String endDay, String endYear, String confirm) {
        return new ReportResponse(null, startMonth, startDay, startYear, endMonth,
                endDay, endYear, confirm, null, null, null, null, null, null, null, false, null,
                false, null, null, null);
    }

    /**
     * A response carrying one summary text and the two independent flags.
     *
     * <p>The text is placed in both text components at once, which is the ordinary case: for a text
     * inside the screen width the projected form and the unprojected form are identical, and
     * asserting both proves the pair is not one component wearing two names.
     *
     * @param text the summary text, or {@code null} for the silent case
     * @param submissionAccepted whether the request was handed to the submission bridge
     * @param generalError whether the legacy error flag was set for this turn
     * @return a response carrying the text and the two flags
     */
    private static ReportResponse announcing(String text, boolean submissionAccepted,
            boolean generalError) {
        return new ReportResponse(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, text, submissionAccepted, text, generalError,
                null, null, null);
    }

    /**
     * A navigation state with every identifier populated, including one that leads with zeros.
     *
     * @return an echoed navigation state
     */
    private static NavigationContext navigation() {
        return new NavigationContext("CR00", "CORPT00C", "CR00", "CORPT00C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000001", "MARY", "ANN", "SMITH",
                "00000000001", "Y", "4111111111111111", "CORPT0A", "CORPT00");
    }

    /**
     * Builds a mapper configured exactly as the module's shared configuration configures its own.
     *
     * <p>Four settings are reproduced from {@code src/main/resources/application.yml}: absent values
     * are omitted rather than published as nulls, temporal values are never written as epoch
     * numbers, an unknown incoming property is tolerated rather than rejected, and a decimal is
     * written in plain notation. A locally built mapper is used rather than an injected one because
     * this is a unit test: starting a context to obtain a mapper would test the context.
     *
     * @return a mapper equivalent to the module's own
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes a response and reads it back as a tree.
     *
     * @param response the response to serialize
     * @return the serialized form as a tree
     * @throws JsonProcessingException if serialization or parsing fails
     */
    private static JsonNode payloadOf(ReportResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Returns the property names the serialized form actually publishes.
     *
     * @param response the response to serialize
     * @return the published property names, in publication order
     * @throws JsonProcessingException if serialization or parsing fails
     */
    private static List<String> publishedNames(ReportResponse response)
            throws JsonProcessingException {
        List<String> names = new ArrayList<>();
        payloadOf(response).fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    /**
     * Runs bean validation over a response.
     *
     * <p>A validator built directly from the specification's own entry point, not one obtained from
     * an application context: the constraints under test are declarative and need no container to
     * evaluate.
     *
     * @param response the response to validate
     * @return every violation the declared constraints report
     */
    private static Set<ConstraintViolation<ReportResponse>> violationsOf(ReportResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    @Nested
    @DisplayName("The declared widths are the screen's own widths")
    class DeclaredWidths {

        @Test
        @DisplayName("declares no width for the echoed period, because a member of a closed vocabulary "
                + "is not a fixed-width screen value")
        void declaresNoWidthForTheEchoedPeriod() {
            assertThat(ReportPeriod.values())
                    .as("the three one-character positions collapsed into one closed vocabulary, so "
                            + "their shared width is gone and no selector width remains to declare")
                    .hasSize(3);
            assertThat(PERIODS_IN_EVALUATION_ORDER)
                    .as("the period is carried as a vocabulary member, never as a bounded screen value")
                    .containsExactly(ReportPeriod.MONTHLY, ReportPeriod.YEARLY, ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("declares one character for the confirmation, the only remaining one-wide field")
        void declaresOneCharacterForTheConfirmation() {
            assertThat(ReportResponse.CONFIRM_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("declares two characters for a month, two for a day and four for a year")
        void declaresTheSixDateComponentWidths() {
            assertThat(ReportResponse.MONTH_LENGTH).isEqualTo(2);
            assertThat(ReportResponse.DAY_LENGTH).isEqualTo(2);
            assertThat(ReportResponse.YEAR_LENGTH).isEqualTo(4);
        }

        @Test
        @DisplayName("declares no width for the derived report name, because the name is the period's "
                + "own carried value rather than a component of this contract")
        void declaresNoWidthForTheDerivedReportName() {
            assertThat(PERIODS_IN_EVALUATION_ORDER.stream().map(ReportPeriod::getValue).toList())
                    .as("the vocabulary is the single declaration of the three names, because a second "
                            + "declaration of a contractual literal is a second source of truth")
                    .isEqualTo(REPORT_NAMES_IN_EVALUATION_ORDER);
            assertThat(REPORT_NAMES_IN_EVALUATION_ORDER)
                    .as("all three names are shorter than the ten-character legacy work item")
                    .allSatisfy(name -> assertThat(name.length()).isLessThan(10));
        }

        @Test
        @DisplayName("declares the six header widths the symbolic map's output group carries")
        void declaresTheSixHeaderWidths() {
            assertThat(ReportResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(ReportResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(ReportResponse.DATE_LENGTH).isEqualTo(8);
            assertThat(ReportResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(ReportResponse.TIME_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("declares seventy-eight for the message field and never the card screens' eighty")
        void declaresSeventyEightForTheMessageField() {
            // This screen's message field is genuinely two characters narrower than the one the two
            // card screens carry, and every other aspect of the three is identical - which is exactly
            // how a normalised width gets copied in by mistake.
            assertThat(ReportResponse.ERROR_MESSAGE_LENGTH).isEqualTo(78);
            assertThat(ReportResponse.ERROR_MESSAGE_LENGTH).isNotEqualTo(80);
        }

        @Test
        @DisplayName("declares seven characters for a focus identity, the widest a generated map name is")
        void declaresSevenCharactersForAFocusIdentity() {
            assertThat(ReportResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("Every echoed value round-trips at its own width")
    class EchoedValuesRoundTrip {

        @Test
        @DisplayName("echoes the chosen period exactly as it arrived, for each of the three")
        void echoesTheChosenPeriodExactly() {
            assertThat(selecting(ReportPeriod.MONTHLY).reportPeriod())
                    .isSameAs(ReportPeriod.MONTHLY);
            assertThat(selecting(ReportPeriod.YEARLY).reportPeriod()).isSameAs(ReportPeriod.YEARLY);
            assertThat(selecting(ReportPeriod.CUSTOM).reportPeriod()).isSameAs(ReportPeriod.CUSTOM);
            assertThat(selecting(null).reportPeriod())
                    .as("the unmarked screen echoes absence, never a synthesised member")
                    .isNull();
        }

        @Test
        @DisplayName("echoes the six date components at their exact widths, leading zeros intact")
        void echoesTheSixDateComponentsAtTheirExactWidths() {
            ReportResponse response = echoing("07", "01", "2022", "12", "31", "2023", "Y");

            // Declaring each local as a String is the compile-time half of the assertion: were any of
            // these components ever changed to a calendar type, this method would stop compiling
            // rather than merely failing.
            String startMonth = response.startMonth();
            String startDay = response.startDay();
            String startYear = response.startYear();
            String endMonth = response.endMonth();
            String endDay = response.endDay();
            String endYear = response.endYear();

            assertThat(startMonth).isEqualTo("07").hasSize(ReportResponse.MONTH_LENGTH);
            assertThat(startDay).isEqualTo("01").hasSize(ReportResponse.DAY_LENGTH);
            assertThat(startYear).isEqualTo("2022").hasSize(ReportResponse.YEAR_LENGTH);
            assertThat(endMonth).isEqualTo("12").hasSize(ReportResponse.MONTH_LENGTH);
            assertThat(endDay).isEqualTo("31").hasSize(ReportResponse.DAY_LENGTH);
            assertThat(endYear).isEqualTo("2023").hasSize(ReportResponse.YEAR_LENGTH);
        }

        @Test
        @DisplayName("never pads a short value up to its declared width")
        void neverPadsAShortValueUp() {
            ReportResponse dates = echoing("7", "1", "22", "9", "5", "23", "y");

            assertThat(dates.startMonth()).isEqualTo("7").hasSize(1);
            assertThat(dates.startDay()).isEqualTo("1").hasSize(1);
            assertThat(dates.startYear()).isEqualTo("22").hasSize(2);
            assertThat(dates.endYear()).isEqualTo("23").hasSize(2);

            // The report name is shorter than its ten-character work field and stays that way: both
            // legacy reads consume the field up to its first space, so padding never reached a screen.
            assertThat(selecting(ReportPeriod.MONTHLY).reportPeriod().getValue())
                    .isEqualTo("Monthly").hasSize(7);
        }

        @Test
        @DisplayName("never trims a trailing space from an echoed value")
        void neverTrimsATrailingSpace() {
            ReportResponse dates = echoing("7 ", "1 ", "22  ", " 9", " 5", "  23", " ");

            assertThat(dates.startMonth()).isEqualTo("7 ").hasSize(2);
            assertThat(dates.startDay()).isEqualTo("1 ").hasSize(2);
            assertThat(dates.startYear()).isEqualTo("22  ").hasSize(4);
            assertThat(dates.endMonth()).isEqualTo(" 9").hasSize(2);
            assertThat(dates.endDay()).isEqualTo(" 5").hasSize(2);
            assertThat(dates.endYear()).isEqualTo("  23").hasSize(4);
            assertThat(dates.confirm()).isEqualTo(" ").hasSize(1);
        }

        @Test
        @DisplayName("never case folds an echoed value: a lower-case answer stays lower case")
        void neverCaseFoldsAnEchoedValue() {
            ReportResponse response = echoing(null, null, null, null, null, null, "y");

            assertThat(response.confirm()).isEqualTo("y").isNotEqualTo("Y");
            assertThat(selecting(ReportPeriod.CUSTOM).reportPeriod().getValue()).isEqualTo("Custom")
                    .isNotEqualTo("CUSTOM");
            assertThat(ReportPeriod.fromValue("custom"))
                    .as("recognition never folds case either, in either direction")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries a blank echoed value as the blank it is, distinct from an absent one")
        void carriesABlankValueDistinctlyFromAnAbsentOne() {
            assertThat(echoing("", "", "", "", "", "", "").startMonth()).isEmpty();
            assertThat(echoing(null, null, null, null, null, null, null).startMonth()).isNull();
        }

        @Test
        @DisplayName("carries a calendar-impossible date component through unchanged")
        void carriesACalendarImpossibleComponentUnchanged() {
            // No calendar arithmetic happens in a data-transfer type. A thirteenth month and a
            // thirty-second day are rejected by the validation cascade in the service layer, which
            // needs to receive them intact in order to name them in its rejection text.
            ReportResponse response = echoing("13", "32", "0000", "99", "00", "9999", "?");

            assertThat(response.startMonth()).isEqualTo("13");
            assertThat(response.startDay()).isEqualTo("32");
            assertThat(response.startYear()).isEqualTo("0000");
            assertThat(response.endMonth()).isEqualTo("99");
            assertThat(response.endDay()).isEqualTo("00");
            assertThat(response.endYear()).isEqualTo("9999");
            assertThat(response.confirm()).isEqualTo("?");
        }

        @Test
        @DisplayName("echoes the six header items the map's output group declares")
        void echoesTheSixHeaderItems() {
            // The output group genuinely declares these six alongside the nine input echoes, so the
            // contract carries them. They are pinned at their map widths rather than asserted absent.
            ReportResponse response = everyComponentPresent();

            assertThat(response.transactionName()).isEqualTo("CR00")
                    .hasSize(ReportResponse.TRANSACTION_NAME_LENGTH);
            assertThat(response.title01()).isEqualTo(TITLE_UPPER)
                    .hasSize(ReportResponse.SCREEN_TITLE_LENGTH);
            assertThat(response.title02()).isEqualTo(TITLE_LOWER)
                    .hasSize(ReportResponse.SCREEN_TITLE_LENGTH);
            assertThat(response.currentDate()).isEqualTo("07/19/22")
                    .hasSize(ReportResponse.DATE_LENGTH);
            assertThat(response.programName()).isEqualTo("CORPT00C")
                    .hasSize(ReportResponse.PROGRAM_NAME_LENGTH);
            assertThat(response.currentTime()).isEqualTo("19:27:53")
                    .hasSize(ReportResponse.TIME_LENGTH);
        }

        @Test
        @DisplayName("carries the projected message at the screen width without altering it")
        void carriesTheProjectedMessageWithoutAlteringIt() {
            String atTheWidth = "E".repeat(ReportResponse.ERROR_MESSAGE_LENGTH);
            ReportResponse response = announcing(atTheWidth, false, true);

            assertThat(response.errorMessage()).isEqualTo(atTheWidth)
                    .hasSize(ReportResponse.ERROR_MESSAGE_LENGTH);
            assertThat(violationsOf(response)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The three report-type positions collapse into one echoed period")
    class TheThreePositionsCollapseIntoOnePeriod {

        @Test
        @DisplayName("carries exactly one period, for each of the three the screen names, and nothing "
                + "combining two of them can be built")
        void carriesExactlyOnePeriod() {
            for (ReportPeriod period : PERIODS_IN_EVALUATION_ORDER) {
                assertThat(selecting(period).reportPeriod()).isSameAs(period);
            }
            assertThat(ALL_COMPONENTS)
                    .as("there is no second period component to combine with the first")
                    .doesNotContainAnyElementsOf(REMOVED_COMPONENTS);
        }

        @Test
        @DisplayName("carries the derived report name as the period's own value, so the two cannot "
                + "disagree the way two separate components could")
        void carriesTheDerivedNameAsThePeriodsOwnValue() {
            for (int index = 0; index < PERIODS_IN_EVALUATION_ORDER.size(); index++) {
                assertThat(PERIODS_IN_EVALUATION_ORDER.get(index).getValue())
                        .isEqualTo(REPORT_NAMES_IN_EVALUATION_ORDER.get(index));
            }
            assertThat(ALL_COMPONENTS)
                    .as("no separately settable name component exists to drift from the period")
                    .doesNotContain("reportName");
        }

        @Test
        @DisplayName("clears the period after an accepted request, as the program clears the three "
                + "positions it echoes")
        void clearsThePeriodAfterAnAcceptedRequest() {
            assertThat(selecting(null).reportPeriod()).isNull();
            assertThat(everyComponentAbsent().reportPeriod()).isNull();
        }

        @Test
        @DisplayName("publishes the period as one string carrying the bare mixed-case report name")
        void publishesThePeriodAsOneString() throws JsonProcessingException {
            JsonNode payload = payloadOf(selecting(ReportPeriod.YEARLY));

            assertThat(payload.get("reportPeriod").isTextual()).isTrue();
            assertThat(payload.get("reportPeriod").asText())
                    .as("the wire form of a closed vocabulary is its member identifier")
                    .isEqualTo("YEARLY");
            assertThat(ReportPeriod.YEARLY.getValue())
                    .as("the derived report name is the carried value, a different string")
                    .isEqualTo("Yearly").isNotEqualTo("YEARLY");
            assertThat(payload.size())
                    .as("only the period and the two primitive flags are written")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("publishes no per-position marker and no separate report name beside the period")
        void publishesNoPerPositionMarkerOrSeparateName() throws JsonProcessingException {
            assertThat(publishedNames(everyComponentPresent()))
                    .contains("reportPeriod")
                    .doesNotContainAnyElementsOf(REMOVED_COMPONENTS)
                    .doesNotContain("reportType", "selection", "selectedPeriod");
        }

        @Test
        @DisplayName("resolves an unrecognised value to absence without raising, so the catch-all "
                + "message stays the service's to report")
        void resolvesAnUnrecognisedValueToAbsence() {
            assertThat(ReportPeriod.fromValue(null)).isEmpty();
            assertThat(ReportPeriod.fromValue("")).isEmpty();
            assertThat(ReportPeriod.fromValue("Y")).isEmpty();
            assertThat(ReportPeriod.fromValue("MONTHLY")).isEmpty();
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .containsExactly("MONTHLY", "YEARLY", "CUSTOM")
                    .doesNotContain("NONE", "UNKNOWN", "OTHER", "INVALID", "DEFAULT");
        }
    }

    @Nested
    @DisplayName("The six date components stay split and stay text")
    class TheSixDateComponentsStaySplit {

        @Test
        @DisplayName("publishes six separate date properties and no merged one")
        void publishesSixSeparatePropertiesAndNoMergedOne() throws JsonProcessingException {
            List<String> names = publishedNames(everyComponentPresent());

            assertThat(names).contains("startMonth", "startDay", "startYear", "endMonth", "endDay",
                    "endYear");
            assertThat(names).doesNotContain("startDate", "endDate", "dateRange", "reportDates",
                    "startDateTime", "endDateTime", "dateFrom", "dateTo");
        }

        @Test
        @DisplayName("publishes every date component as a JSON string, never as a number")
        void publishesEveryDateComponentAsAString() throws JsonProcessingException {
            JsonNode payload = payloadOf(echoing("07", "01", "2022", "07", "19", "2022", "Y"));

            assertThat(payload.get("startMonth").isTextual()).isTrue();
            assertThat(payload.get("startDay").isTextual()).isTrue();
            assertThat(payload.get("startYear").isTextual()).isTrue();
            assertThat(payload.get("endMonth").isTextual()).isTrue();
            assertThat(payload.get("endDay").isTextual()).isTrue();
            assertThat(payload.get("endYear").isTextual()).isTrue();
            assertThat(payload.get("startMonth").asText()).isEqualTo("07");
        }

        @Test
        @DisplayName("keeps a leading zero a numeric component would have discarded")
        void keepsALeadingZeroANumericComponentWouldDiscard() throws JsonProcessingException {
            JsonNode payload = payloadOf(echoing("07", "08", "0022", "09", "01", "0023", null));

            assertThat(payload.get("startMonth").asText()).isEqualTo("07");
            assertThat(payload.get("startDay").asText()).isEqualTo("08");
            assertThat(payload.get("startYear").asText()).isEqualTo("0022");
            assertThat(payload.get("endYear").asText()).isEqualTo("0023");
        }

        @Test
        @DisplayName("orders nothing and reformats nothing: the screen order is what is carried")
        void ordersNothingAndReformatsNothing() {
            // The screen presents month, day, year; the submitted job parameter orders them year,
            // month, day. That reordering is the service's, and doing it here would hide a
            // translation rule inside a data carrier where no test would look for it.
            ReportResponse response = echoing("07", "19", "2022", "08", "20", "2023", null);

            assertThat(response.startMonth()).isEqualTo("07");
            assertThat(response.startDay()).isEqualTo("19");
            assertThat(response.startYear()).isEqualTo("2022");
            assertThat(response.endMonth()).isEqualTo("08");
            assertThat(response.endDay()).isEqualTo("20");
            assertThat(response.endYear()).isEqualTo("2023");
        }
    }

    @Nested
    @DisplayName("Message shape one: the standalone missing-selection rejection")
    class MissingSelectionRejection {

        @Test
        @DisplayName("publishes the text at thirty-nine characters, byte for byte")
        void publishesTheTextAtThirtyNineCharacters() {
            assertThat(ReportResponse.MSG_SELECT_REPORT_TYPE).isEqualTo(NO_SELECTION_TEXT)
                    .hasSize(39);
        }

        @Test
        @DisplayName("ends with exactly three dots and no fourth")
        void endsWithExactlyThreeDots() {
            assertThat(ReportResponse.MSG_SELECT_REPORT_TYPE).endsWith("report...");
            assertThat(ReportResponse.MSG_SELECT_REPORT_TYPE).doesNotEndWith("....");
        }

        @Test
        @DisplayName("is carried untrimmed and un-case-folded through the single message component")
        void isCarriedUntrimmedAndUnfolded() {
            ReportResponse response = announcing(NO_SELECTION_TEXT, false, true);

            assertThat(response.message()).isEqualTo(NO_SELECTION_TEXT).hasSize(39);
            assertThat(response.errorMessage()).isEqualTo(NO_SELECTION_TEXT).hasSize(39);
            // Neither an upper-cased nor a sentence-cased rendering is the contract text.
            assertThat(response.message())
                    .isNotEqualTo("SELECT A REPORT TYPE TO PRINT REPORT...");
            assertThat(response.message())
                    .isNotEqualTo("Select A Report Type To Print Report...");
        }

        @Test
        @DisplayName("returns attention to the selector block, as the program's catch-all does")
        void returnsAttentionToTheSelectorBlock() {
            ReportResponse response = new ReportResponse(null, null, null, null, null, null, null, null,
                                              null, null, null, null, null, null,
                                              NO_SELECTION_TEXT, false, NO_SELECTION_TEXT, true,
                                              ReportResponse.FIELD_MONTHLY_SELECTION, null, null);

            assertThat(response.focusScreenFieldId()).isEqualTo("MONTHLY");
            assertThat(response.generalError()).isTrue();
            assertThat(response.submissionAccepted()).isFalse();
        }
    }

    @Nested
    @DisplayName("Message shape two: the composed acceptance text, family A")
    class AcceptanceTextFamilyA {

        @Test
        @DisplayName("publishes a thirty-four character fragment that begins with one space")
        void publishesAThirtyFourCharacterFragmentBeginningWithOneSpace() {
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isEqualTo(" report submitted for printing ...").hasSize(34);
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX).startsWith(" ");
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX).doesNotStartWith("  ");
        }

        @Test
        @DisplayName("keeps the space that sits before its three dots")
        void keepsTheSpaceBeforeItsThreeDots() {
            // The space before the dots is the single byte that distinguishes this fragment from the
            // confirmation prompt's closing fragment.
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX).endsWith(" ...");
        }

        @Test
        @DisplayName("assembles byte for byte for each of the three report names")
        void assemblesForEachOfTheThreeReportNames() {
            assertThat(ReportPeriod.MONTHLY.getValue() + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isEqualTo(ACCEPTED_MONTHLY).hasSize(41);
            assertThat(ReportPeriod.YEARLY.getValue() + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isEqualTo(ACCEPTED_YEARLY).hasSize(40);
            assertThat(ReportPeriod.CUSTOM.getValue() + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isEqualTo(ACCEPTED_CUSTOM).hasSize(40);
        }

        @Test
        @DisplayName("separates the name from the word report by exactly one space")
        void separatesTheNameFromTheWordReportByExactlyOneSpace() {
            // The name is contributed delimited by space, so the fixed-width field's padding is
            // dropped and the fragment's own leading space is the whole separator. Two spaces would
            // mean the padding leaked through; none would mean the leading space was trimmed.
            for (String name : REPORT_NAMES_IN_EVALUATION_ORDER) {
                String assembled = name + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX;

                assertThat(assembled).contains(name + " report");
                assertThat(assembled).doesNotContain(name + "  report");
                assertThat(assembled).doesNotContain(name + "report");
            }
        }

        @Test
        @DisplayName("is carried through the same single message component as every other shape")
        void isCarriedThroughTheSingleMessageComponent() {
            ReportResponse response = announcing(ACCEPTED_MONTHLY, true, false);

            assertThat(response.message()).isEqualTo(ACCEPTED_MONTHLY);
            assertThat(response.errorMessage()).isEqualTo(ACCEPTED_MONTHLY);
        }

        @Test
        @DisplayName("fits inside the screen message width for every report name")
        void fitsInsideTheScreenMessageWidth() {
            assertThat(ACCEPTED_MONTHLY.length())
                    .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
            assertThat(ACCEPTED_YEARLY.length())
                    .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
            assertThat(ACCEPTED_CUSTOM.length())
                    .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
        }
    }

    @Nested
    @DisplayName("Message shape three: the composed confirmation prompt, family B")
    class ConfirmationPromptFamilyB {

        @Test
        @DisplayName("publishes a twenty-eight character prefix that ends with one space")
        void publishesATwentyEightCharacterPrefixEndingWithOneSpace() {
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX)
                    .isEqualTo("Please confirm to print the ").hasSize(28);
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX).endsWith(" ");
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX).doesNotEndWith("  ");
        }

        @Test
        @DisplayName("publishes a ten character tail with one leading space and no space before its dots")
        void publishesATenCharacterTailWithNoSpaceBeforeItsDots() {
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX).isEqualTo(" report...")
                    .hasSize(10);
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX).startsWith(" ");
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX).doesNotStartWith("  ");
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX).doesNotContain(" ...");
        }

        @Test
        @DisplayName("assembles byte for byte for each of the three report names")
        void assemblesForEachOfTheThreeReportNames() {
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX
                    + ReportPeriod.MONTHLY.getValue()
                    + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX)
                    .isEqualTo(CONFIRM_MONTHLY).hasSize(45);
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX
                    + ReportPeriod.YEARLY.getValue()
                    + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX)
                    .isEqualTo(CONFIRM_YEARLY).hasSize(44);
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX
                    + ReportPeriod.CUSTOM.getValue()
                    + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX)
                    .isEqualTo(CONFIRM_CUSTOM).hasSize(44);
        }

        @Test
        @DisplayName("places exactly one space on each side of the report name")
        void placesExactlyOneSpaceOnEachSideOfTheName() {
            for (String name : REPORT_NAMES_IN_EVALUATION_ORDER) {
                String assembled = ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX + name
                        + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX;

                assertThat(assembled).contains("the " + name + " report");
                assertThat(assembled).doesNotContain("the  " + name);
                assertThat(assembled).doesNotContain(name + "  report");
                assertThat(assembled).doesNotContain("the" + name);
            }
        }

        @Test
        @DisplayName("is carried through the same single message component and returns focus to confirm")
        void isCarriedThroughTheSingleMessageComponent() {
            ReportResponse response = new ReportResponse(ReportPeriod.MONTHLY, null, null, null, null,
                                              null, null, null, null, null, null, null, null,
                                              null, CONFIRM_MONTHLY, false, CONFIRM_MONTHLY,
                                              true, ReportResponse.FIELD_CONFIRM, null, null);

            assertThat(response.message()).isEqualTo(CONFIRM_MONTHLY);
            assertThat(response.errorMessage()).isEqualTo(CONFIRM_MONTHLY);
            assertThat(response.focusScreenFieldId()).isEqualTo("CONFIRM");
        }

        @Test
        @DisplayName("fits inside the screen message width for every report name")
        void fitsInsideTheScreenMessageWidth() {
            assertThat(CONFIRM_MONTHLY.length())
                    .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
            assertThat(CONFIRM_YEARLY.length())
                    .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
            assertThat(CONFIRM_CUSTOM.length())
                    .isLessThanOrEqualTo(ReportResponse.ERROR_MESSAGE_LENGTH);
        }
    }

    @Nested
    @DisplayName("Families A and B are never unified and neither is derived from the other")
    class FamiliesAAndBAreNeverUnified {

        @Test
        @DisplayName("assembles two different texts for the same report name")
        void assemblesTwoDifferentTextsForTheSameName() {
            for (String name : REPORT_NAMES_IN_EVALUATION_ORDER) {
                String accepted = name + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX;
                String prompted = ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX + name
                        + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX;

                assertThat(accepted).isNotEqualTo(prompted);
                assertThat(accepted.length()).isNotEqualTo(prompted.length());
            }
        }

        @Test
        @DisplayName("shares only the word report, which does not make the two interchangeable")
        void sharesOnlyTheWordReport() {
            String accepted = "Monthly" + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX;
            String prompted = ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX + "Monthly"
                    + ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX;

            assertThat(accepted).contains(" report");
            assertThat(prompted).contains(" report");
            assertThat(accepted).isNotEqualTo(prompted);
        }

        @Test
        @DisplayName("declares two fragments neither of which contains the other")
        void declaresTwoFragmentsNeitherContainingTheOther() {
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .isNotEqualTo(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX);
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX)
                    .doesNotContain(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX);
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX)
                    .doesNotContain(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX);
        }

        @Test
        @DisplayName("differs in where the space before the dots sits, which is the whole difference")
        void differsInWhereTheSpaceBeforeTheDotsSits() {
            // A's fragment: leading space, and a space before its dots.
            assertThat(ReportResponse.FRAGMENT_SUBMITTED_SUFFIX).startsWith(" ").endsWith(" ...");
            // B's tail: leading space, and no space before its dots.
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX).startsWith(" ")
                    .doesNotEndWith(" ...");
            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_SUFFIX).endsWith("t...");
        }

        @Test
        @DisplayName("gives B a prefix that A has no counterpart for")
        void givesBAPrefixThatAHasNoCounterpartFor() {
            String accepted = "Custom" + ReportResponse.FRAGMENT_SUBMITTED_SUFFIX;

            assertThat(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX).hasSize(28);
            assertThat(accepted).doesNotContain(ReportResponse.FRAGMENT_CONFIRM_PROMPT_PREFIX);
            assertThat(accepted).doesNotStartWith("Please");
        }

        @Test
        @DisplayName("carries the two texts as two independent turns, one an error and one not")
        void carriesTheTwoTextsAsTwoIndependentTurns() {
            ReportResponse accepted = announcing(ACCEPTED_CUSTOM, true, false);
            ReportResponse prompted = announcing(CONFIRM_CUSTOM, false, true);

            assertThat(accepted.message()).isNotEqualTo(prompted.message());
            assertThat(accepted.submissionAccepted()).isTrue();
            assertThat(prompted.submissionAccepted()).isFalse();
            assertThat(accepted.generalError()).isFalse();
            assertThat(prompted.generalError()).isTrue();
        }
    }

    @Nested
    @DisplayName("Message shape four: the composed invalid-confirmation rejection, family C")
    class InvalidConfirmationFamilyC {

        @Test
        @DisplayName("publishes a one-character opening quote and a thirty-six character closing tail")
        void publishesTheOpeningQuoteAndTheClosingTail() {
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX).isEqualTo("\"").hasSize(1);
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX)
                    .isEqualTo("\" is not a valid value to confirm...").hasSize(36);
        }

        @Test
        @DisplayName("carries the closing quote inside the tail, so no second quote is added")
        void carriesTheClosingQuoteInsideTheTail() {
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX).startsWith("\"");
            assertThat(ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX).doesNotStartWith("\"\"");
        }

        @Test
        @DisplayName("quotes the supplied character on both sides")
        void quotesTheSuppliedCharacterOnBothSides() {
            String assembled = ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX + "X"
                    + ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX;

            assertThat(assembled).isEqualTo(INVALID_CONFIRM_UPPER).hasSize(38);
            assertThat(assembled).startsWith("\"");
            assertThat(assembled).contains("\"X\"");
        }

        @Test
        @DisplayName("leaves a lower-case supplied character un-folded")
        void leavesALowerCaseCharacterUnfolded() {
            // The affirmative and negative answers are tested in both cases before this branch is
            // reached, so the character that arrives here is neither; a lower-case one is quoted back
            // exactly as the operator typed it.
            String assembled = ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX + "q"
                    + ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX;

            assertThat(assembled).isEqualTo(INVALID_CONFIRM_LOWER).hasSize(38);
            assertThat(assembled).contains("\"q\"");
            assertThat(assembled).isNotEqualTo(INVALID_CONFIRM_UPPER);
            assertThat(assembled).doesNotContain("\"Q\"");
        }

        @Test
        @DisplayName("still produces a well-formed quoted text when the character contributes nothing")
        void stillProducesAWellFormedTextWhenTheCharacterContributesNothing() {
            // The confirmation field is contributed delimited by space, so a field holding a space
            // adds no characters and the quotes close on nothing. That is well formed rather than
            // broken, and the shape is pinned so the delimiter semantics stay visible.
            String assembled = ReportResponse.FRAGMENT_INVALID_CONFIRM_PREFIX + ""
                    + ReportResponse.FRAGMENT_INVALID_CONFIRM_SUFFIX;

            assertThat(assembled).isEqualTo(INVALID_CONFIRM_BLANK).hasSize(37);
            assertThat(assembled).startsWith("\"\"");
            assertThat(assembled).endsWith("confirm...");
        }

        @Test
        @DisplayName("carries the supplied character beside the text it was quoted into")
        void carriesTheSuppliedCharacterBesideTheText() {
            ReportResponse response = new ReportResponse(ReportPeriod.YEARLY, null, null,
                    null, null, null, null, "q", null, null, null, null, null, null,
                    INVALID_CONFIRM_LOWER, false, INVALID_CONFIRM_LOWER, true,
                    ReportResponse.FIELD_CONFIRM, null, null);

            assertThat(response.confirm()).isEqualTo("q");
            assertThat(response.message()).isEqualTo(INVALID_CONFIRM_LOWER);
            assertThat(response.errorMessage()).isEqualTo(INVALID_CONFIRM_LOWER);
            assertThat(response.generalError()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo("CONFIRM");
        }
    }

    @Nested
    @DisplayName("The non-fatal bridge-write failure is ordinary text, not an exceptional outcome")
    class NonFatalBridgeWriteFailure {

        @Test
        @DisplayName("pins the text at twenty-nine characters, byte for byte")
        void pinsTheTextAtTwentyNineCharacters() {
            assertThat(QUEUE_WRITE_FAILURE_TEXT).isEqualTo("Unable to Write TDQ (JOBS)...")
                    .hasSize(29);
            assertThat(QUEUE_WRITE_FAILURE_TEXT).endsWith("...").doesNotEndWith("....");
        }

        @Test
        @DisplayName("is constructible as an ordinary message, exactly like every other shape")
        void isConstructibleAsAnOrdinaryMessage() {
            ReportResponse response = new ReportResponse(ReportPeriod.MONTHLY, null, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    QUEUE_WRITE_FAILURE_TEXT, false, QUEUE_WRITE_FAILURE_TEXT, true,
                    ReportResponse.FIELD_MONTHLY_SELECTION, null, null);

            assertThat(response.message()).isEqualTo(QUEUE_WRITE_FAILURE_TEXT).hasSize(29);
            assertThat(response.errorMessage()).isEqualTo(QUEUE_WRITE_FAILURE_TEXT).hasSize(29);
            assertThat(response.generalError()).isTrue();
            assertThat(response.submissionAccepted()).isFalse();
            assertThat(violationsOf(response)).isEmpty();
        }

        @Test
        @DisplayName("brings no failure vocabulary onto the wire with it")
        void bringsNoFailureVocabularyOntoTheWire() throws JsonProcessingException {
            ReportResponse response = announcing(QUEUE_WRITE_FAILURE_TEXT, false, true);

            assertThat(publishedNames(response)).doesNotContain("exception", "exceptionType",
                    "cause", "stackTrace", "retry", "retryAfter", "retryable", "attempts",
                    "failureMode", "failureCode", "errorCode", "severity");
        }

        @Test
        @DisplayName("keeps the projected and unprojected forms identical for a text this short")
        void keepsBothTextFormsIdenticalForAShortText() throws JsonProcessingException {
            JsonNode payload = payloadOf(announcing(QUEUE_WRITE_FAILURE_TEXT, false, true));

            assertThat(payload.get("message").asText()).isEqualTo(QUEUE_WRITE_FAILURE_TEXT);
            assertThat(payload.get("errorMessage").asText()).isEqualTo(QUEUE_WRITE_FAILURE_TEXT);
        }
    }

    @Nested
    @DisplayName("The three report names are bare, mixed case and un-normalised")
    class TheThreeReportNames {

        @Test
        @DisplayName("publishes the three names the program writes, byte for byte, from the vocabulary "
                + "that is their single declaration")
        void publishesTheThreeNames() {
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo("Monthly").hasSize(7);
            assertThat(ReportPeriod.YEARLY.getValue()).isEqualTo("Yearly").hasSize(6);
            assertThat(ReportPeriod.CUSTOM.getValue()).isEqualTo("Custom").hasSize(6);
            assertThat(PERIODS_IN_EVALUATION_ORDER.stream().map(ReportPeriod::getValue).toList())
                    .as("this response restates none of them, so neither copy can be corrected alone")
                    .isEqualTo(REPORT_NAMES_IN_EVALUATION_ORDER);
        }

        @Test
        @DisplayName("pads no name out to the ten characters of the legacy work field")
        void padsNoNameOutToTheWorkFieldWidth() {
            for (String name : REPORT_NAMES_IN_EVALUATION_ORDER) {
                assertThat(name.length()).isLessThan(10);
                assertThat(name).doesNotEndWith(" ");
            }
        }

        @Test
        @DisplayName("keeps a capital initial letter and a lowercase remainder on every name")
        void keepsTheContractualCasing() {
            assertThat(ReportPeriod.MONTHLY.getValue()).isNotEqualTo("MONTHLY")
                    .isNotEqualTo("monthly").isNotEqualTo(ReportPeriod.MONTHLY.name());
            assertThat(ReportPeriod.YEARLY.getValue()).isNotEqualTo("YEARLY")
                    .isNotEqualTo("yearly").isNotEqualTo(ReportPeriod.YEARLY.name());
            assertThat(ReportPeriod.CUSTOM.getValue()).isNotEqualTo("CUSTOM")
                    .isNotEqualTo("custom").isNotEqualTo(ReportPeriod.CUSTOM.name());
        }

        @Test
        @DisplayName("round-trips each name through the contract un-case-folded, as the period's value")
        void roundTripsEachNameUnfolded() throws JsonProcessingException {
            for (int index = 0; index < PERIODS_IN_EVALUATION_ORDER.size(); index++) {
                ReportPeriod period = PERIODS_IN_EVALUATION_ORDER.get(index);
                String name = REPORT_NAMES_IN_EVALUATION_ORDER.get(index);
                ReportResponse response = selecting(period);

                assertThat(response.reportPeriod().getValue()).isEqualTo(name);
                assertThat(payloadOf(response).get("reportPeriod").asText())
                        .isEqualTo(period.name())
                        .isNotEqualTo(name);
            }
        }

        @Test
        @DisplayName("derives every name from the period enumeration alone, in evaluation order")
        void derivesEveryNameFromThePeriodEnumerationAlone() {
            assertThat(PERIODS_IN_EVALUATION_ORDER)
                    .extracting(ReportPeriod::getValue)
                    .containsExactlyElementsOf(REPORT_NAMES_IN_EVALUATION_ORDER);
        }

        @Test
        @DisplayName("finds exactly three periods declared and no synthetic fourth")
        void findsExactlyThreePeriodsAndNoSyntheticFourth() {
            // A catch-all constant would offer callers a report type the legacy screen never had: the
            // no-selection case is an input rejection, not a fourth period.
            assertThat(ReportPeriod.values()).containsExactly(ReportPeriod.MONTHLY,
                    ReportPeriod.YEARLY, ReportPeriod.CUSTOM);
            assertThat(ReportPeriod.values()).hasSize(3);
        }

        @Test
        @DisplayName("resolves a name without throwing and reports a miss as an absent result")
        void resolvesANameWithoutThrowing() {
            Optional<ReportPeriod> monthly = ReportPeriod.fromValue("Monthly");
            Optional<ReportPeriod> yearly = ReportPeriod.fromValue("Yearly");
            Optional<ReportPeriod> custom = ReportPeriod.fromValue("Custom");

            assertThat(monthly).contains(ReportPeriod.MONTHLY);
            assertThat(yearly).contains(ReportPeriod.YEARLY);
            assertThat(custom).contains(ReportPeriod.CUSTOM);
            assertThat(ReportPeriod.fromValue(null)).isEmpty();
            assertThat(ReportPeriod.fromValue("")).isEmpty();
            assertThat(ReportPeriod.fromValue("Quarterly")).isEmpty();
        }

        @Test
        @DisplayName("folds no case and normalises no whitespace while resolving a name")
        void foldsNoCaseWhileResolvingAName() {
            assertThat(ReportPeriod.fromValue("MONTHLY")).isEmpty();
            assertThat(ReportPeriod.fromValue("monthly")).isEmpty();
            assertThat(ReportPeriod.fromValue("YEARLY")).isEmpty();
            assertThat(ReportPeriod.fromValue("Monthly   ")).isEmpty();
            assertThat(ReportPeriod.fromValue(" Custom")).isEmpty();
        }
    }

    @Nested
    @DisplayName("An acknowledgement is not an error, and the flag is never derived from the text")
    class AnAcknowledgementIsNotAnError {

        @Test
        @DisplayName("carries the acceptance text with the error flag clear")
        void carriesTheAcceptanceTextWithTheFlagClear() {
            ReportResponse response = announcing(ACCEPTED_MONTHLY, true, false);

            assertThat(response.message()).isEqualTo(ACCEPTED_MONTHLY).isNotEmpty();
            assertThat(response.generalError()).isFalse();
            assertThat(response.submissionAccepted()).isTrue();
        }

        @Test
        @DisplayName("carries the confirmation prompt with the error flag clear as well")
        void carriesTheConfirmationPromptWithTheFlagClear() {
            // The prompt is a request for input rather than a rejection of it, so a client that keys
            // its presentation off the flag must not colour this as a failure.
            ReportResponse response = announcing(CONFIRM_CUSTOM, false, false);

            assertThat(response.message()).isEqualTo(CONFIRM_CUSTOM).isNotEmpty();
            assertThat(response.generalError()).isFalse();
            assertThat(response.submissionAccepted()).isFalse();
        }

        @Test
        @DisplayName("carries each of the three rejection texts with the error flag set")
        void carriesEachRejectionTextWithTheFlagSet() {
            List<String> rejections =
                    List.of(NO_SELECTION_TEXT, INVALID_CONFIRM_UPPER, QUEUE_WRITE_FAILURE_TEXT);

            for (String text : rejections) {
                ReportResponse response = announcing(text, false, true);

                assertThat(response.message()).isEqualTo(text);
                assertThat(response.generalError()).isTrue();
                assertThat(response.submissionAccepted()).isFalse();
            }
        }

        @Test
        @DisplayName("carries a non-empty text beside a clear flag, so presence cannot imply failure")
        void carriesANonEmptyTextBesideAClearFlag() {
            ReportResponse response = announcing(ACCEPTED_YEARLY, true, false);

            assertThat(response.message()).isNotNull().isNotEmpty();
            assertThat(response.generalError()).isFalse();
        }

        @Test
        @DisplayName("carries a set flag with no text at all, the silent rejection the program emits")
        void carriesASetFlagWithNoTextAtAll() {
            // When the operator answers the confirmation prompt negatively the program clears its
            // message field, sets its error flag and re-presents the screen without composing
            // anything. Inferring the flag from text presence would turn this into a success.
            ReportResponse response = announcing(null, false, true);

            assertThat(response.message()).isNull();
            assertThat(response.errorMessage()).isNull();
            assertThat(response.generalError()).isTrue();
            assertThat(response.submissionAccepted()).isFalse();
        }

        @Test
        @DisplayName("represents all four combinations of text presence and flag state")
        void representsAllFourCombinations() {
            assertThat(announcing(ACCEPTED_MONTHLY, true, false).generalError()).isFalse();
            assertThat(announcing(NO_SELECTION_TEXT, false, true).generalError()).isTrue();
            assertThat(announcing(null, false, false).generalError()).isFalse();
            assertThat(announcing(null, false, true).generalError()).isTrue();
        }

        @Test
        @DisplayName("treats an empty text as text, independently of the flag")
        void treatsAnEmptyTextAsTextIndependentlyOfTheFlag() {
            ReportResponse emptyAndClear = announcing("", false, false);
            ReportResponse emptyAndSet = announcing("", false, true);

            assertThat(emptyAndClear.message()).isEmpty();
            assertThat(emptyAndClear.generalError()).isFalse();
            assertThat(emptyAndSet.message()).isEmpty();
            assertThat(emptyAndSet.generalError()).isTrue();
        }

        @Test
        @DisplayName("keeps the two flags independent of one another in every combination")
        void keepsTheTwoFlagsIndependent() {
            assertThat(announcing(null, true, false).submissionAccepted()).isTrue();
            assertThat(announcing(null, false, true).submissionAccepted()).isFalse();
            assertThat(announcing(null, true, true).submissionAccepted()).isTrue();
            assertThat(announcing(null, true, true).generalError()).isTrue();
            assertThat(announcing(null, false, false).submissionAccepted()).isFalse();
            assertThat(announcing(null, false, false).generalError()).isFalse();
        }

        @Test
        @DisplayName("publishes both flags on the wire even when both are clear")
        void publishesBothFlagsEvenWhenClear() throws JsonProcessingException {
            JsonNode payload = payloadOf(everyComponentAbsent());

            assertThat(payload.get("submissionAccepted").isBoolean()).isTrue();
            assertThat(payload.get("generalError").isBoolean()).isTrue();
            assertThat(payload.get("submissionAccepted").asBoolean()).isFalse();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }
    }

    @Nested
    @DisplayName("Exactly one summary text, because the legacy cascade stops at its first failure")
    class ExactlyOneSummaryText {

        @Test
        @DisplayName("carries one text and not a collection of them")
        void carriesOneTextAndNotACollection() throws JsonProcessingException {
            // Assigning to a declared String is the compile-time half: a collection-valued component
            // would not compile here. The wire form is the observable half.
            String message = announcing(NO_SELECTION_TEXT, false, true).message();
            JsonNode payload = payloadOf(announcing(NO_SELECTION_TEXT, false, true));

            assertThat(message).isEqualTo(NO_SELECTION_TEXT);
            assertThat(payload.get("message").isTextual()).isTrue();
            assertThat(payload.get("message").isArray()).isFalse();
        }

        @Test
        @DisplayName("passes all four message shapes through that same one component")
        void passesAllFourShapesThroughTheSameComponent() {
            List<String> everyShape = List.of(NO_SELECTION_TEXT, ACCEPTED_MONTHLY, CONFIRM_MONTHLY,
                    INVALID_CONFIRM_UPPER, QUEUE_WRITE_FAILURE_TEXT);

            for (String text : everyShape) {
                assertThat(announcing(text, false, false).message()).isEqualTo(text);
            }
        }

        @Test
        @DisplayName("publishes no collection-shaped error surface beside the one text")
        void publishesNoCollectionShapedErrorSurface() throws JsonProcessingException {
            assertThat(publishedNames(everyComponentPresent())).doesNotContain("messages", "errors",
                    "fieldErrors", "violations", "details", "errorList", "warnings");
        }

        @Test
        @DisplayName("leaves the unprojected text unbounded so a long text is not silently cut")
        void leavesTheUnprojectedTextUnbounded() {
            String longerThanTheScreen = "L".repeat(ReportResponse.ERROR_MESSAGE_LENGTH + 40);
            ReportResponse response = new ReportResponse(null, null, null, null, null, null, null, null,
                                              null, null, null, null, null, null, null, false,
                                              longerThanTheScreen, true, null, null, null);

            assertThat(response.message()).hasSize(ReportResponse.ERROR_MESSAGE_LENGTH + 40);
            assertThat(violationsOf(response)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Declarative validation bounds sizes and does nothing else")
    class DeclarativeValidationBoundsSizesOnly {

        @Test
        @DisplayName("reports no violation when every component is absent")
        void reportsNoViolationWhenEveryComponentIsAbsent() {
            // Nothing on this contract is mandatory: after an accepted submission the legacy program
            // wipes the whole screen, and a presence constraint would reject that state.
            assertThat(violationsOf(everyComponentAbsent())).isEmpty();
        }

        @Test
        @DisplayName("reports no violation for any of the three periods the screen names")
        void reportsNoViolationForAnyOfTheThreePeriods() {
            for (ReportPeriod period : PERIODS_IN_EVALUATION_ORDER) {
                assertThat(violationsOf(selecting(period)))
                        .as("the %s period is always well formed at this boundary", period)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("reports no violation for calendar-impossible date components")
        void reportsNoViolationForCalendarImpossibleComponents() {
            // The ordered, first-error-wins cascade lives in the service layer. A declarative pattern
            // or range constraint here would fire before that cascade ran and would replace the
            // legacy rejection text with a framework message.
            assertThat(violationsOf(echoing("13", "32", "0000", "99", "00", "9999", "?"))).isEmpty();
        }

        @Test
        @DisplayName("reports no violation for blank or whitespace-only values")
        void reportsNoViolationForBlankOrWhitespaceValues() {
            assertThat(violationsOf(echoing("", "", "", "", "", "", ""))).isEmpty();
            assertThat(violationsOf(echoing(" ", " ", "    ", " ", " ", "    ", " "))).isEmpty();
            assertThat(violationsOf(selecting(null))).isEmpty();
        }

        @Test
        @DisplayName("reports no violation for a fully populated response")
        void reportsNoViolationForAFullyPopulatedResponse() {
            assertThat(violationsOf(everyComponentPresent())).isEmpty();
        }

        @Test
        @DisplayName("reports exactly one size violation for an over-long confirmation, and nothing "
                + "else")
        void reportsExactlyOneSizeViolationForAnOverLongConfirmation() {
            Set<ConstraintViolation<ReportResponse>> violations =
                    violationsOf(echoing(null, null, null, null, null, null, "YY"));

            assertThat(violations).hasSize(1);
            ConstraintViolation<ReportResponse> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath()).hasToString("confirm");
            // The message template names the constraint without inspecting the type's structure.
            assertThat(violation.getMessageTemplate())
                    .isEqualTo("{jakarta.validation.constraints.Size.message}");
        }

        @Test
        @DisplayName("bounds no value at the legacy report-name width, because the derived name is the "
                + "period's own value and cannot be supplied over-long")
        void boundsNoValueAtTheReportNameWidth() {
            for (ReportPeriod period : PERIODS_IN_EVALUATION_ORDER) {
                assertThat(violationsOf(selecting(period))).isEmpty();
                assertThat(period.getValue().length()).isLessThan(10);
            }
        }

        @Test
        @DisplayName("bounds the projected message at the screen width and the focus identity at seven")
        void boundsTheProjectedMessageAndTheFocusIdentity() {
            String tooWide = "E".repeat(ReportResponse.ERROR_MESSAGE_LENGTH + 1);
            ReportResponse overWideMessage = announcing(tooWide, false, true);
            ReportResponse overWideFocus = new ReportResponse(null, null, null, null, null, null, null,
                                                   null, null, null, null, null, null, null,
                                                   null, false, null, true, "TOOLONG1", null,
                                                   null);

            assertThat(violationsOf(overWideMessage)).hasSize(1);
            assertThat(violationsOf(overWideFocus)).hasSize(1);
        }

        @Test
        @DisplayName("accepts every focus identity the program actually uses")
        void acceptsEveryFocusIdentityTheProgramUses() {
            List<String> identities = List.of(ReportResponse.FIELD_MONTHLY_SELECTION,
                    ReportResponse.FIELD_START_MONTH, ReportResponse.FIELD_START_DAY,
                    ReportResponse.FIELD_START_YEAR, ReportResponse.FIELD_END_MONTH,
                    ReportResponse.FIELD_END_DAY, ReportResponse.FIELD_END_YEAR,
                    ReportResponse.FIELD_CONFIRM);

            for (String identity : identities) {
                ReportResponse response = new ReportResponse(null, null, null, null, null, null, null,
                                                  null, null, null, null, null, null, null,
                                                  null, false, null, true, identity, null, null);

                assertThat(identity.length())
                        .isLessThanOrEqualTo(ReportResponse.SCREEN_FIELD_ID_LENGTH);
                assertThat(violationsOf(response)).isEmpty();
                assertThat(response.focusScreenFieldId()).isEqualTo(identity);
            }
        }
    }

    @Nested
    @DisplayName("The navigation state is carried, never re-implemented")
    class TheNavigationStateIsCarried {

        @Test
        @DisplayName("carries the state it was given, as the very same value")
        void carriesTheStateItWasGiven() {
            NavigationContext supplied = navigation();
            ReportResponse response = new ReportResponse(null, null, null, null, null, null, null, null,
                                              null, null, null, null, null, null, null, false,
                                              null, false, null, null, supplied);

            assertThat(response.navigationContext()).isSameAs(supplied);
        }

        @Test
        @DisplayName("preserves an account identifier that leads with zeros")
        void preservesAnAccountIdentifierThatLeadsWithZeros() throws JsonProcessingException {
            ReportResponse response = everyComponentPresent();

            assertThat(response.navigationContext().accountId()).isEqualTo("00000000001")
                    .hasSize(NavigationContext.ACCOUNT_ID_LENGTH);
            assertThat(payloadOf(response).get("navigationContext").get("accountId").asText())
                    .isEqualTo("00000000001");
        }

        @Test
        @DisplayName("preserves every other carried identifier unchanged")
        void preservesEveryOtherIdentifier() {
            NavigationContext carried = everyComponentPresent().navigationContext();

            assertThat(carried.fromTransactionId()).isEqualTo("CR00");
            assertThat(carried.fromProgram()).isEqualTo("CORPT00C");
            assertThat(carried.toTransactionId()).isEqualTo("CR00");
            assertThat(carried.toProgram()).isEqualTo("CORPT00C");
            assertThat(carried.userId()).isEqualTo("ADMINUSR");
            assertThat(carried.userType()).isEqualTo("A");
            assertThat(carried.customerId()).isEqualTo("000000001")
                    .hasSize(NavigationContext.CUSTOMER_ID_LENGTH);
            assertThat(carried.cardNumber()).isEqualTo("4111111111111111")
                    .hasSize(NavigationContext.CARD_NUMBER_LENGTH);
            assertThat(carried.lastMap()).isEqualTo("CORPT0A");
            assertThat(carried.lastMapset()).isEqualTo("CORPT00");
            assertThat(carried.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("tolerates an absent state and omits the key entirely")
        void toleratesAnAbsentState() throws JsonProcessingException {
            assertThat(everyComponentAbsent().navigationContext()).isNull();
            assertThat(publishedNames(everyComponentAbsent())).doesNotContain("navigationContext");
        }

        @Test
        @DisplayName("carries the empty state exactly as supplied, without substituting anything")
        void carriesTheEmptyStateAsSupplied() {
            NavigationContext empty = NavigationContext.empty();
            ReportResponse response = new ReportResponse(null, null, null, null, null, null, null, null,
                                              null, null, null, null, null, null, null, false,
                                              null, false, null, null, empty);

            assertThat(response.navigationContext()).isSameAs(empty);
            assertThat(response.navigationContext().userId()).isNull();
            assertThat(response.navigationContext().accountId()).isNull();
        }

        @Test
        @DisplayName("re-implements no navigation field of its own beside the carried state")
        void reImplementsNoNavigationFieldOfItsOwn() throws JsonProcessingException {
            assertThat(publishedNames(everyComponentPresent())).doesNotContain("userId", "userType",
                    "accountId", "customerId", "cardNumber", "fromProgram", "toProgram", "lastMap",
                    "lastMapset", "commarea");
        }
    }

    @Nested
    @DisplayName("The serialized form omits what is absent and tolerates what it does not know")
    class TheSerializedForm {

        @Test
        @DisplayName("publishes all twenty-four components when all twenty-four are present")
        void publishesAllTwentyFourComponents() throws JsonProcessingException {
            assertThat(publishedNames(everyComponentPresent()))
                    .containsExactlyInAnyOrderElementsOf(ALL_COMPONENTS);
        }

        @Test
        @DisplayName("publishes only the two flags when every other component is absent")
        void publishesOnlyTheTwoFlagsWhenEverythingElseIsAbsent() throws JsonProcessingException {
            assertThat(publishedNames(everyComponentAbsent()))
                    .containsExactlyInAnyOrder("submissionAccepted", "generalError");
        }

        @Test
        @DisplayName("writes no null literal into the payload")
        void writesNoNullLiteral() throws JsonProcessingException {
            assertThat(payloadOf(selecting(ReportPeriod.MONTHLY)).toString())
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("tolerates an unknown incoming property instead of rejecting the body")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            String body = """
                    {"reportPeriod":"MONTHLY","monthlySelection":"Y","reportName":"Monthly",\
                    "generalError":false,\
                    "submissionAccepted":true,"aPropertyThisContractDoesNotDeclare":"ignored"}""";

            ReportResponse bound = moduleEquivalentMapper().readValue(body, ReportResponse.class);

            assertThat(bound.reportPeriod()).isSameAs(ReportPeriod.MONTHLY);
            assertThat(bound.submissionAccepted()).isTrue();
            assertThat(bound.generalError()).isFalse();
        }

        @Test
        @DisplayName("round-trips a fully populated response back to an equal value")
        void roundTripsAFullyPopulatedResponse() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            ReportResponse original = everyComponentPresent();

            ReportResponse restored =
                    mapper.readValue(mapper.writeValueAsString(original), ReportResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.message()).isEqualTo(ACCEPTED_CUSTOM);
            assertThat(restored.navigationContext()).isEqualTo(original.navigationContext());
        }

        @Test
        @DisplayName("is a plain contract body and not a problem document")
        void isAPlainContractBodyAndNotAProblemDocument() throws JsonProcessingException {
            // The module publishes its own error contract rather than the standard problem format, so
            // none of that format's members may appear here.
            assertThat(publishedNames(everyComponentPresent())).doesNotContain("type", "title",
                    "status", "detail", "instance", "problem");
        }

        @Test
        @DisplayName("carries no presentational member of any kind")
        void carriesNoPresentationalMember() throws JsonProcessingException {
            // Colour, highlight and control bytes are terminal concerns that mean nothing to a REST
            // client; severity travels semantically in the two flags instead.
            assertThat(publishedNames(everyComponentPresent())).doesNotContain("colour", "color",
                    "highlight", "attribute", "attributeByte", "cursorRow", "cursorColumn", "row",
                    "column", "mask", "filler", "functionKeys", "keyLegend", "pfKeys");
        }
    }

    @Nested
    @DisplayName("The next route is declarative data, never dispatch")
    class TheNextRouteIsDeclarativeData {

        @Test
        @DisplayName("carries the route as an opaque string the client may call")
        void carriesTheRouteAsAnOpaqueString() throws JsonProcessingException {
            String route = everyComponentPresent().nextRoute();

            assertThat(route).isEqualTo("/api/v1/reports");
            assertThat(payloadOf(everyComponentPresent()).get("nextRoute").isTextual()).isTrue();
        }

        @Test
        @DisplayName("carries an absent route when the request stays on this screen")
        void carriesAnAbsentRouteWhenTheRequestStays() throws JsonProcessingException {
            assertThat(everyComponentAbsent().nextRoute()).isNull();
            assertThat(publishedNames(everyComponentAbsent())).doesNotContain("nextRoute");
        }

        @Test
        @DisplayName("accepts any route value without validating it, since the vocabulary is elsewhere")
        void acceptsAnyRouteValueWithoutValidatingIt() {
            ReportResponse response = new ReportResponse(null, null, null, null, null, null, null, null,
                                              null, null, null, null, null, null, null, false,
                                              null, false, null,
                                              "/api/v1/reports/transaction-report", null);

            assertThat(response.nextRoute()).isEqualTo("/api/v1/reports/transaction-report");
            assertThat(violationsOf(response)).isEmpty();
        }

        @Test
        @DisplayName("publishes no route table, no dispatch member and no transfer-of-control member")
        void publishesNoRouteTableOrDispatchMember() throws JsonProcessingException {
            assertThat(publishedNames(everyComponentPresent())).doesNotContain("routes",
                    "routeTable", "route", "dispatch", "forwardTo", "transferTo", "nextProgram",
                    "nextTransaction", "xctl");
        }
    }

    @Nested
    @DisplayName("Nothing of the submitted job payload crosses this boundary")
    class NothingOfTheJobPayloadCrosses {

        @Test
        @DisplayName("publishes no image, line, count or width member")
        void publishesNoImageOrLineMember() throws JsonProcessingException {
            // The fixed-width job image the legacy program assembled is built in the utility layer and
            // published by the service layer. This response acknowledges the submission and describes
            // nothing about it, so none of the payload's vocabulary appears.
            assertThat(publishedNames(everyComponentPresent())).doesNotContain("cards",
                    "cardImages", "jobCards", "jobStream", "jobImage", "lines", "recordSize",
                    "recordLength", "cardCount", "lineCount", "width", "offset", "sentinel",
                    "terminator", "delimiter");
        }

        @Test
        @DisplayName("publishes no transport identity, destination or ordering identity")
        void publishesNoTransportIdentity() throws JsonProcessingException {
            assertThat(publishedNames(everyComponentPresent())).doesNotContain("queue", "queueName",
                    "queueUrl", "destination", "topic", "topicArn", "bucket", "bucketName", "region",
                    "endpoint", "messageGroupId", "groupId", "deduplicationId", "sequenceNumber");
        }

        @Test
        @DisplayName("publishes no receipt, identifier or acceptance moment for the submission")
        void publishesNoReceiptOrAcceptanceMoment() throws JsonProcessingException {
            // The legacy operator was given a screen message and nothing else - no job number, no
            // receipt and no timestamp - so inventing any of them here would be new behaviour.
            assertThat(publishedNames(everyComponentPresent())).doesNotContain("receipt",
                    "messageId", "jobId", "jobName", "jobNumber", "submittedAt", "acceptedAt",
                    "timestamp", "correlationId", "queueDepth");
        }

        @Test
        @DisplayName("publishes no array-shaped member at all")
        void publishesNoArrayShapedMember() throws JsonProcessingException {
            JsonNode payload = payloadOf(everyComponentPresent());

            assertThat(payload.isObject()).isTrue();
            payload.forEach(member -> assertThat(member.isArray()).isFalse());
        }

        @Test
        @DisplayName("publishes exactly the component count and no twenty-second member")
        void publishesExactlyTheComponentCount() throws JsonProcessingException {
            assertThat(publishedNames(everyComponentPresent())).hasSize(ALL_COMPONENTS.size());
            assertThat(ALL_COMPONENTS).hasSize(21);
        }
    }

    @Nested
    @DisplayName("Immutability and value semantics, demonstrated by construction")
    class ImmutabilityAndValueSemantics {

        @Test
        @DisplayName("returns the same value from every repeated read")
        void returnsTheSameValueFromEveryRepeatedRead() {
            ReportResponse response = everyComponentPresent();

            assertThat(response.message()).isEqualTo(response.message());
            assertThat(response.reportPeriod()).isSameAs(response.reportPeriod());
            assertThat(response.navigationContext()).isSameAs(response.navigationContext());
            assertThat(response.generalError()).isEqualTo(response.generalError());
        }

        @Test
        @DisplayName("offers no mutator, so a changed value is a new instance and the original stands")
        void offersNoMutatorSoAChangedValueIsANewInstance() {
            // There is no setter to call: the only way to express a different value is to construct a
            // second instance, which leaves the first untouched. That is the whole of the proof, and
            // it is a compile-time one.
            ReportResponse original = announcing(ACCEPTED_MONTHLY, true, false);
            ReportResponse changed = announcing(NO_SELECTION_TEXT, false, true);

            assertThat(original.message()).isEqualTo(ACCEPTED_MONTHLY);
            assertThat(original.generalError()).isFalse();
            assertThat(changed).isNotSameAs(original).isNotEqualTo(original);
        }

        @Test
        @DisplayName("compares equal, and hashes equal, for two independently built equal values")
        void comparesEqualForTwoIndependentlyBuiltEqualValues() {
            ReportResponse first = everyComponentPresent();
            ReportResponse second = everyComponentPresent();

            assertThat(first).isEqualTo(second).isNotSameAs(second);
            assertThat(first).hasSameHashCodeAs(second);
            assertThat(first).isEqualTo(first);
        }

        @Test
        @DisplayName("breaks equality when a single component differs")
        void breaksEqualityWhenASingleComponentDiffers() {
            ReportResponse base = announcing(ACCEPTED_CUSTOM, true, false);

            assertThat(announcing(ACCEPTED_YEARLY, true, false)).isNotEqualTo(base);
            assertThat(announcing(ACCEPTED_CUSTOM, false, false)).isNotEqualTo(base);
            assertThat(announcing(ACCEPTED_CUSTOM, true, true)).isNotEqualTo(base);
            assertThat(selecting(ReportPeriod.CUSTOM))
                    .isNotEqualTo(selecting(ReportPeriod.MONTHLY));
        }

        @Test
        @DisplayName("is equal to nothing of another kind and to no absent value")
        void isEqualToNothingOfAnotherKind() {
            ReportResponse response = everyComponentPresent();

            assertThat(response).isNotEqualTo(null);
            assertThat(response).isNotEqualTo(ACCEPTED_CUSTOM);
            assertThat(response).isNotEqualTo(response.navigationContext());
        }

        @Test
        @DisplayName("names the components it carries when rendered, and leaks no carried identifier")
        void namesTheComponentsItCarriesWhenRendered() {
            String rendered = everyComponentPresent().toString();

            assertThat(rendered).startsWith("ReportResponse[");
            assertThat(rendered).contains("reportPeriod=CUSTOM");
            assertThat(rendered).doesNotContain("reportName=");
            assertThat(rendered).contains("submissionAccepted=true");
            assertThat(rendered).contains("generalError=false");
            assertThat(rendered).endsWith("]");
            // The carried navigation state withholds the customer, account and card identifiers from
            // its own rendering, and that withholding survives being nested inside this one.
            assertThat(rendered).doesNotContain("00000000001");
            assertThat(rendered).doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("reads every one of the twenty-one components of a populated response")
        void readsEveryComponentOfAPopulatedResponse() {
            ReportResponse response = everyComponentPresent();

            assertThat(response.reportPeriod()).isSameAs(ReportPeriod.CUSTOM);
            assertThat(response.reportPeriod().getValue()).isEqualTo("Custom");
            assertThat(response.startMonth()).isEqualTo("07");
            assertThat(response.startDay()).isEqualTo("01");
            assertThat(response.startYear()).isEqualTo("2022");
            assertThat(response.endMonth()).isEqualTo("07");
            assertThat(response.endDay()).isEqualTo("19");
            assertThat(response.endYear()).isEqualTo("2022");
            assertThat(response.confirm()).isEqualTo("Y");
            assertThat(response.transactionName()).isEqualTo("CR00");
            assertThat(response.title01()).isEqualTo(TITLE_UPPER);
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.programName()).isEqualTo("CORPT00C");
            assertThat(response.title02()).isEqualTo(TITLE_LOWER);
            assertThat(response.currentTime()).isEqualTo("19:27:53");
            assertThat(response.errorMessage()).isEqualTo(ACCEPTED_CUSTOM);
            assertThat(response.submissionAccepted()).isTrue();
            assertThat(response.message()).isEqualTo(ACCEPTED_CUSTOM);
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo("MONTHLY");
            assertThat(response.nextRoute()).isEqualTo("/api/v1/reports");
            assertThat(response.navigationContext()).isNotNull();
        }

        @Test
        @DisplayName("tolerates an absent value in every one of the twenty-one positions")
        void toleratesAnAbsentValueInEveryPosition() {
            ReportResponse response = everyComponentAbsent();

            assertThat(response.reportPeriod()).isNull();
            assertThat(response.startMonth()).isNull();
            assertThat(response.startDay()).isNull();
            assertThat(response.startYear()).isNull();
            assertThat(response.endMonth()).isNull();
            assertThat(response.endDay()).isNull();
            assertThat(response.endYear()).isNull();
            assertThat(response.confirm()).isNull();
            assertThat(response.transactionName()).isNull();
            assertThat(response.title01()).isNull();
            assertThat(response.currentDate()).isNull();
            assertThat(response.programName()).isNull();
            assertThat(response.title02()).isNull();
            assertThat(response.currentTime()).isNull();
            assertThat(response.errorMessage()).isNull();
            assertThat(response.submissionAccepted()).isFalse();
            assertThat(response.message()).isNull();
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId()).isNull();
            assertThat(response.nextRoute()).isNull();
            assertThat(response.navigationContext()).isNull();
        }
    }
}
