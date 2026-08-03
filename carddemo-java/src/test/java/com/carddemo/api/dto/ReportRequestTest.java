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

import java.util.List;
import java.util.Map;
import java.util.Set;

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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReportRequest}, the inbound contract of legacy transaction {@code CR00}.
 *
 * <p>A pure unit test: no application context, no servlet environment, no container and no database.
 * Every fixture is built by invoking the record's canonical constructor directly, and the only
 * collaborators are the platform's own Bean Validation provider and a locally built object mapper.
 *
 * <h2>What this file pins</h2>
 *
 * <p>The authority is {@code app/cbl/CORPT00C.cbl} - 649 lines across ten procedure paragraphs - with
 * the symbolic map {@code app/cpy-bms/CORPT00.CPY} and the mapset {@code app/bms/CORPT00.bms}. Three
 * shape decisions in the translated record are contract rather than convenience, and each is asserted
 * here rather than merely documented:
 *
 * <ol>
 *   <li><strong>Three independent report-type markers, not one selector.</strong> The map declares
 *       three separate one-character items - {@code MONTHLYI} at line 60, {@code YEARLYI} at line 66
 *       and {@code CUSTOMI} at line 72 - and the program's ordered evaluation, whose header sits at
 *       {@code CORPT00C} line 212 with the monthly clause immediately after it, tests them in the
 *       fixed order monthly, yearly, custom and stops at the first marked one, falling to its
 *       catch-all clause at line 438 when none is marked. That is first-match-wins over three
 *       independent inputs. A 3270 operator can mark two positions and the program silently honours
 *       the earlier one, so a submission marking two is a representable state with a defined outcome;
 *       a single-valued component could not express it at all. All four states are exercised below.</li>
 *   <li><strong>Six split date parts, never merged and never parsed.</strong> The start and end dates
 *       arrive as three items each, in the screen order month, day, year, at widths two, two and four
 *       ({@code SDTMMI}, {@code SDTDDI}, {@code SDTYYYYI} at map lines 78, 84 and 90, and
 *       {@code EDTMMI}, {@code EDTDDI}, {@code EDTYYYYI} at lines 96, 102 and 108). Each is validated
 *       on its own and reports its own message, so a merged value could not say which part failed,
 *       and the leading zero a two-character part carries is contractual.</li>
 *   <li><strong>One explicit confirmation character, never a derived flag.</strong> {@code CONFIRMI}
 *       at map line 114 drives four distinct outcomes rather than two: unmarked prompts and
 *       re-displays; the accept characters proceed at {@code CORPT00C} line 478; the refuse characters
 *       reset the screen with no message text at all at line 480; and any other character is quoted
 *       back to the operator inside its own message, assembled from the fragments at lines 486 and
 *       488. The exact character therefore has to survive as far as the response.</li>
 * </ol>
 *
 * <h2>Why the request declares no presence, pattern or calendar constraint</h2>
 *
 * <p>When the custom period is selected the program runs a strictly ordered, first-failure-wins
 * cascade over the six date parts - six absence stages from line 258, six numeric-and-range stages,
 * then two calendar stages that delegate to the date utility at lines 392 and 412 under a severity
 * test at lines 396 and 416 and a message-number exemption at lines 399 and 419. Bean Validation is
 * unordered and reports every violation at once, so annotating those stages would produce several
 * messages where the legacy screen produces exactly one, in an order the legacy screen never uses.
 * Every one of those checks is therefore a message-bearing, source-ordered service validation, and
 * this record's only constraint is a width bound that measures without altering.
 *
 * <p>The legacy date validation that the service reproduces is an <strong>eleven-paragraph ordered
 * cascade</strong> - year, then month, then day, then the day-month-year combination, then the
 * Language-Environment stage - carried by the procedural copybook {@code app/cpy/CSUTLDPY.cpy}, whose
 * range head sits at line 18 and whose range exit sits at line 329. It lives in the module's
 * date-validation service, which this file never imports. Merging the six parts into one value inside
 * the request would move validation upstream of that cascade and change which error is reported
 * first, which is exactly the regression the split components prevent.
 *
 * <p>The response texts that the cascade and the confirmation branch emit belong to the response
 * contract, so not one of them is declared here. They are cited by position and length only: the
 * 39-character input-error text at line 438, the 34-character acknowledgement fragment at line 450,
 * the 28-character and 10-character confirmation-prompt fragments at lines 466 and 469, the
 * 1-character and 36-character rejection fragments at lines 486 and 488, and the 29-character
 * hand-over failure text at line 531.
 *
 * <h2>How absence is proved here</h2>
 *
 * <p>Nothing below inspects the type's declaration. Absence is established twice over, behaviourally:
 *
 * <ul>
 *   <li>A validation run over deliberately hostile input - every component absent, every component
 *       blank, several markers set at once, a month of thirteen, a day of thirty-two, a non-numeric
 *       year - must report <em>zero</em> violations. That excludes a presence constraint, a
 *       character-class or pattern constraint, a digit constraint and a numeric bound, because any
 *       one of them would have fired on one of those inputs.</li>
 *   <li>The serialized property set must be <em>exactly</em> the twelve components. That single
 *       assertion excludes, in one stroke, a collapsed period component, a merged or formatted date
 *       value, a derived confirmation flag, every piece of screen and response furniture, and every
 *       fragment of the job-submission payload - because any of them would appear as a thirteenth
 *       property.</li>
 * </ul>
 *
 * <p>The job-submission payload is excluded by width as well as by name: the widest bound any
 * component carries is four characters, so no component can hold a fixed-width card image. The image
 * itself, its substitution slots, its terminating sentinel and the resource it is written to belong to
 * the module's utility and service layers, and the half of that contract which requires draining a
 * real queue belongs to the integration and end-to-end tiers, never to this file.
 *
 * <h2>Provenance</h2>
 *
 * <p>Every width, line number and count above was read from the CardDemo COBOL estate at checkout
 * commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The estate under {@code app/} is read-only
 * reference: it is cited by member name, item name, item width and line number only, and no COBOL,
 * screen-map or job-control text is reproduced anywhere in this file.
 */
@DisplayName("ReportRequest :: the inbound contract of legacy transaction CR00")
class ReportRequestTest {

    /**
     * The twelve wire property names, in the order the record declares them, which is the order the
     * symbolic map declares the items they carry. Used as the exact expected property set, so a
     * thirteenth property of any kind - furniture, a merged date, a derived flag, a card image - fails
     * the assertion that references it.
     */
    private static final List<String> COMPONENT_NAMES = List.of(
            "monthlySelection", "yearlySelection", "customSelection",
            "startMonth", "startDay", "startYear",
            "endMonth", "endDay", "endYear",
            "confirm", "keyAction", "navigationContext");

    /**
     * The three report-type markers, in the order the program's evaluation tests them: monthly first,
     * yearly second, custom third.
     */
    private static final List<String> SELECTOR_NAMES =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    /** The six date parts, in screen order: start month, day, year then end month, day, year. */
    private static final List<String> DATE_PART_NAMES =
            List.of("startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear");

    /**
     * The ten text components, being every component except the typed attention key and the echoed
     * navigation state. These are the ten the screen operator types, and the only ten that carry a
     * width bound.
     */
    private static final List<String> TEXT_COMPONENT_NAMES = List.of(
            "monthlySelection", "yearlySelection", "customSelection",
            "startMonth", "startDay", "startYear",
            "endMonth", "endDay", "endYear", "confirm");

    /** A value one character wider than the four-character year parts, the widest bound in the type. */
    private static final String FIVE_CHARACTERS = "12345";

    /**
     * A fixed-width card image, used only to prove that no component tolerates one. Eighty characters
     * is the record width of the resource the legacy program writes its job image to, and that image
     * belongs to the module's utility layer rather than to this request.
     */
    private static final String EIGHTY_CHARACTERS = "C".repeat(80);

    /**
     * The platform's default validation provider, bootstrapped once for the whole class.
     *
     * <p>Deliberately the provider the platform discovers for itself rather than a validator supplied
     * by an application context: the point of every validation assertion below is what the type's own
     * annotations do, and a context-supplied validator could carry group sequences, message
     * interpolation or a constraint mapping the type never declared. Bootstrapping is expensive enough
     * to dominate the run if it were repeated per assertion, and the provider is immutable and
     * thread-safe once built, so one instance serves the class and is released in {@link
     * #releaseTheValidationProvider()}.
     */
    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();

    /**
     * Builds the echoed navigation state used wherever a request needs its nested component present.
     *
     * <p>Every regulated component - customer identifier, the three name parts, account identifier and
     * card number - is deliberately left absent, so no fixture in this file carries a value that
     * resembles cardholder or personal data. The identifiers that are present are the transaction and
     * program names of {@code CR00} itself and a synthetic operator identifier.
     *
     * @return a navigation state whose every present value is within the width its own type declares
     */
    private static NavigationContext navigation() {
        return new NavigationContext("CR00", "CORPT00C", "CR00", "CORPT00C", "TESTUSR1", "A",
                NavigationContext.ProgramContext.REENTER, null, null, null, null, null, null, null,
                "CORPT0A", "CORPT00");
    }

    /**
     * Builds a request in which all twelve components are present.
     *
     * <p>Needed because the module omits an absent component from the payload rather than publishing a
     * null, so an assertion over the complete property set needs a fixture with nothing absent. It
     * marks all three report-type positions on purpose: the screen permits it and the program resolves
     * it by evaluation order rather than by refusal.
     *
     * @return a request with no absent component
     */
    private static ReportRequest everyComponentPresent() {
        return new ReportRequest("Y", "Y", "Y", "07", "01", "2022", "07", "19", "2022", "Y",
                KeyAction.ENTER, navigation());
    }

    /**
     * Builds a request that carries the three report-type markers and nothing else of substance.
     *
     * @param monthly the monthly marker, tested first by the program
     * @param yearly  the yearly marker, tested second
     * @param custom  the custom-range marker, tested third
     * @return a request whose six date parts and confirmation character are all absent
     */
    private static ReportRequest withSelections(String monthly, String yearly, String custom) {
        return new ReportRequest(monthly, yearly, custom, null, null, null, null, null, null, null,
                KeyAction.ENTER, null);
    }

    /**
     * Builds a custom-period request carrying the six date parts exactly as supplied.
     *
     * @param startMonth the start date's month part, two characters on the screen
     * @param startDay   the start date's day part, two characters
     * @param startYear  the start date's year part, four characters
     * @param endMonth   the end date's month part, two characters
     * @param endDay     the end date's day part, two characters
     * @param endYear    the end date's year part, four characters
     * @return a request with the custom marker set and the six parts carried verbatim
     */
    private static ReportRequest withDates(String startMonth, String startDay, String startYear,
            String endMonth, String endDay, String endYear) {
        return new ReportRequest(null, null, "Y", startMonth, startDay, startYear, endMonth, endDay,
                endYear, null, KeyAction.ENTER, null);
    }

    /**
     * Builds a request carrying the confirmation character exactly as supplied.
     *
     * @param confirm the confirmation character, one position on the screen; may be {@code null}
     * @return a request whose only supplied values are the custom marker and the confirmation
     */
    private static ReportRequest withConfirm(String confirm) {
        return new ReportRequest(null, null, "Y", null, null, null, null, null, null, confirm,
                KeyAction.ENTER, null);
    }

    /**
     * Builds a request in which every one of the ten text components carries the same value.
     *
     * <p>Used for the width-bound edges, where the point is to drive one value against all ten bounds
     * at once and read back which of them reacted.
     *
     * @param value the value to place in all ten text components
     * @return a request with the attention key present and the navigation state absent
     */
    private static ReportRequest withEveryTextComponent(String value) {
        return new ReportRequest(value, value, value, value, value, value, value, value, value, value,
                KeyAction.ENTER, null);
    }

    /**
     * Builds the object mapper the module's own configuration produces, without starting the module.
     *
     * <p>It mirrors the four settings the shared configuration baseline declares for the mapper:
     * absent values are omitted rather than published as null, dates are never written as timestamps,
     * an unknown incoming property is tolerated rather than rejected, and a decimal is written in
     * plain notation rather than in scientific notation. No serializer, no deserializer and no
     * per-property override is registered, because a locally customised mapper would test itself
     * rather than the contract.
     *
     * @return a mapper configured exactly as the module configures its own
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
     * Serializes a request and reads the result back as a tree, so assertions can be made about the
     * published shape rather than about the Java declaration.
     *
     * @param request the request to publish
     * @return the published payload as a tree
     * @throws JsonProcessingException if the request cannot be published or re-read
     */
    private static JsonNode payloadOf(ReportRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Returns the property names a request actually publishes, in publication order.
     *
     * @param request the request to publish
     * @return the published property names
     * @throws JsonProcessingException if the request cannot be published or re-read
     */
    private static List<String> propertyNamesOf(ReportRequest request)
            throws JsonProcessingException {
        return payloadOf(request).properties().stream().map(Map.Entry::getKey).toList();
    }

    /**
     * Publishes a request and reads it back into a new instance, so that a round trip can be compared
     * against the original byte for byte.
     *
     * @param request the request to send through the wire form
     * @return the instance rebuilt from the published payload
     * @throws JsonProcessingException if the request cannot be published or rebuilt
     */
    private static ReportRequest roundTripped(ReportRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readValue(mapper.writeValueAsString(request), ReportRequest.class);
    }

    /** Releases the validation provider once every test in this class and its groups has run. */
    @AfterAll
    static void releaseTheValidationProvider() {
        VALIDATOR_FACTORY.close();
    }

    /**
     * Validates a request with the platform's default provider rather than with a framework-supplied
     * validator bean, so the outcome comes from the annotations the type itself declares and from
     * nothing an application context might have contributed.
     *
     * @param request the request to validate
     * @return every violation reported, which for a well-formed submission must be none
     */
    private static Set<ConstraintViolation<ReportRequest>> violationsOf(ReportRequest request) {
        Validator validator = VALIDATOR_FACTORY.getValidator();
        return validator.validate(request);
    }

    /**
     * Returns the property paths the reported violations name, so an assertion can state exactly which
     * components reacted to an input.
     *
     * @param request the request to validate
     * @return the property path of every reported violation
     */
    private static List<String> violatingPathsOf(ReportRequest request) {
        return violationsOf(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .toList();
    }

    @Nested
    @DisplayName("the three report-type markers stay three, and stay independent")
    class TheThreeMarkersStayIndependent {

        @Test
        @DisplayName("state one: no marker supplied, whether absent, empty or blank")
        void stateOneNoMarkerSupplied() throws JsonProcessingException {
            ReportRequest absent = withSelections(null, null, null);
            ReportRequest empty = withSelections("", "", "");
            ReportRequest blank = withSelections(" ", " ", " ");

            assertThat(absent.monthlySelection()).isNull();
            assertThat(absent.yearlySelection()).isNull();
            assertThat(absent.customSelection()).isNull();
            assertThat(empty.monthlySelection()).isEqualTo("");
            assertThat(empty.yearlySelection()).isEqualTo("");
            assertThat(empty.customSelection()).isEqualTo("");
            assertThat(blank.monthlySelection()).isEqualTo(" ");
            assertThat(blank.yearlySelection()).isEqualTo(" ");
            assertThat(blank.customSelection()).isEqualTo(" ");

            // All three states are legitimate submissions: the program answers each of them with the
            // catch-all clause at CORPT00C line 438, which is an input-error message rather than a
            // transport rejection. The three must therefore stay distinguishable across the wire.
            assertThat(roundTripped(absent)).isEqualTo(absent);
            assertThat(roundTripped(empty)).isEqualTo(empty);
            assertThat(roundTripped(blank)).isEqualTo(blank);
            assertThat(violationsOf(absent)).isEmpty();
            assertThat(violationsOf(empty)).isEmpty();
            assertThat(violationsOf(blank)).isEmpty();
        }

        @Test
        @DisplayName("state two: exactly the monthly marker supplied, which the program tests first")
        void stateTwoOnlyTheMonthlyMarkerSupplied() throws JsonProcessingException {
            ReportRequest request = withSelections("Y", null, null);

            assertThat(request.monthlySelection()).isEqualTo("Y");
            assertThat(request.yearlySelection()).isNull();
            assertThat(request.customSelection()).isNull();
            assertThat(roundTripped(request)).isEqualTo(request);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("state two: exactly the yearly marker supplied, which the program tests second")
        void stateTwoOnlyTheYearlyMarkerSupplied() throws JsonProcessingException {
            ReportRequest request = withSelections(null, "Y", null);

            assertThat(request.monthlySelection()).isNull();
            assertThat(request.yearlySelection()).isEqualTo("Y");
            assertThat(request.customSelection()).isNull();
            assertThat(roundTripped(request)).isEqualTo(request);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("state two: exactly the custom marker supplied, which the program tests third")
        void stateTwoOnlyTheCustomMarkerSupplied() throws JsonProcessingException {
            ReportRequest request = withSelections(null, null, "Y");

            assertThat(request.monthlySelection()).isNull();
            assertThat(request.yearlySelection()).isNull();
            assertThat(request.customSelection()).isEqualTo("Y");
            assertThat(roundTripped(request)).isEqualTo(request);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("state three: two markers supplied at once, which the 3270 permits")
        void stateThreeTwoMarkersSupplied() throws JsonProcessingException {
            ReportRequest request = withSelections("Y", "Y", null);

            // The program does not reject this submission - the ordered evaluation honours the monthly
            // clause and never reaches the yearly one - so both marks must reach the service intact.
            assertThat(request.monthlySelection()).isEqualTo("Y");
            assertThat(request.yearlySelection()).isEqualTo("Y");
            assertThat(request.customSelection()).isNull();
            assertThat(roundTripped(request)).isEqualTo(request);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("state three: all three markers supplied at once")
        void stateThreeAllThreeMarkersSupplied() throws JsonProcessingException {
            ReportRequest request = withSelections("Y", "S", "X");

            assertThat(request.monthlySelection()).isEqualTo("Y");
            assertThat(request.yearlySelection()).isEqualTo("S");
            assertThat(request.customSelection()).isEqualTo("X");
            assertThat(roundTripped(request)).isEqualTo(request);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("state four: an unexpected character survives, and its case survives with it")
        void stateFourAnUnexpectedCharacterSurvives() throws JsonProcessingException {
            // The absence test the program applies is "not spaces and not low values", so any printable
            // character marks a position. A lower-case letter and a punctuation mark are therefore
            // ordinary submissions, and neither may be folded, normalised or rewritten in transit.
            ReportRequest lowerCase = withSelections("y", "n", "c");
            ReportRequest punctuation = withSelections("*", "?", "/");

            assertThat(lowerCase.monthlySelection()).isEqualTo("y").isNotEqualTo("Y");
            assertThat(lowerCase.yearlySelection()).isEqualTo("n").isNotEqualTo("N");
            assertThat(lowerCase.customSelection()).isEqualTo("c").isNotEqualTo("C");
            assertThat(punctuation.monthlySelection()).isEqualTo("*");
            assertThat(punctuation.yearlySelection()).isEqualTo("?");
            assertThat(punctuation.customSelection()).isEqualTo("/");

            assertThat(roundTripped(lowerCase)).isEqualTo(lowerCase);
            assertThat(roundTripped(punctuation)).isEqualTo(punctuation);
            assertThat(violationsOf(lowerCase)).isEmpty();
            assertThat(violationsOf(punctuation)).isEmpty();
        }

        @Test
        @DisplayName("publishes the three markers as three separate properties, never as one")
        void publishesThreeSeparateProperties() throws JsonProcessingException {
            JsonNode payload = payloadOf(withSelections("Y", "S", "X"));

            assertThat(payload.get("monthlySelection").asText()).isEqualTo("Y");
            assertThat(payload.get("yearlySelection").asText()).isEqualTo("S");
            assertThat(payload.get("customSelection").asText()).isEqualTo("X");
            assertThat(propertyNamesOf(withSelections("Y", "S", "X")))
                    .containsAll(SELECTOR_NAMES);
        }

        @Test
        @DisplayName("derives no marker from another: setting one leaves the other two untouched")
        void derivesNoMarkerFromAnother() {
            // If any marker were computed from the others, changing one alone could not leave the other
            // two absent, and the three single-marker states below would not be distinguishable.
            assertThat(withSelections("Y", null, null).yearlySelection()).isNull();
            assertThat(withSelections("Y", null, null).customSelection()).isNull();
            assertThat(withSelections(null, "Y", null).monthlySelection()).isNull();
            assertThat(withSelections(null, "Y", null).customSelection()).isNull();
            assertThat(withSelections(null, null, "Y").monthlySelection()).isNull();
            assertThat(withSelections(null, null, "Y").yearlySelection()).isNull();

            assertThat(withSelections("Y", null, null))
                    .isNotEqualTo(withSelections(null, "Y", null))
                    .isNotEqualTo(withSelections(null, null, "Y"))
                    .isNotEqualTo(withSelections("Y", "Y", null));
        }

        @Test
        @DisplayName("applies no mutual-exclusion check and performs no tie-break of its own")
        void appliesNoMutualExclusionCheck() {
            // "Select a report type" and "more than one selected" are decisions the ordered service
            // evaluation makes, and it needs the unresolved submission to make them. A transport-level
            // exclusivity check would refuse input the legacy screen accepted, and a transport-level
            // tie-break would move the evaluation order out of the layer that cites line 213 for it.
            assertThat(violationsOf(withSelections("Y", "Y", "Y"))).isEmpty();
            assertThat(violationsOf(withSelections("Y", "Y", null))).isEmpty();
            assertThat(violationsOf(withSelections(null, "Y", "Y"))).isEmpty();
            assertThat(violationsOf(withSelections("Y", null, "Y"))).isEmpty();
            assertThat(violationsOf(withSelections(null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("bounds each marker at its own single character, independently of the other two")
        void boundsEachMarkerIndependently() {
            assertThat(violatingPathsOf(withSelections("Y", "Y", "Y"))).isEmpty();
            assertThat(violatingPathsOf(withSelections("YY", null, null)))
                    .containsExactly("monthlySelection");
            assertThat(violatingPathsOf(withSelections(null, "YY", null)))
                    .containsExactly("yearlySelection");
            assertThat(violatingPathsOf(withSelections(null, null, "YY")))
                    .containsExactly("customSelection");
            assertThat(violatingPathsOf(withSelections("YY", "YY", "YY")))
                    .containsExactlyInAnyOrderElementsOf(SELECTOR_NAMES);
        }
    }

    @Nested
    @DisplayName("the report name is produced by the program, never submitted to it")
    class TheReportNameIsProducedNotSubmitted {

        @Test
        @DisplayName("carries no collapsed period property under any of its plausible names")
        void carriesNoCollapsedPeriodProperty() throws JsonProcessingException {
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .doesNotContain("reportPeriod", "period", "reportType", "reportName",
                            "selection", "selectedReport");
        }

        @Test
        @DisplayName("publishes none of the three produced report names, whatever the markers say")
        void publishesNoneOfTheThreeProducedReportNames() throws JsonProcessingException {
            // The three literals are the ones the program writes into its ten-character report-name
            // work field at CORPT00C lines 214, 240 and 433. They are bare rather than padded out to
            // that width, because both of the field's read sites - lines 449 and 468 - consume it
            // delimited by space. Establishing their exact form first is what makes the absence check
            // below probe the strings the program actually emits rather than an invented spelling.
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo("Monthly").hasSize(7);
            assertThat(ReportPeriod.YEARLY.getValue()).isEqualTo("Yearly").hasSize(6);
            assertThat(ReportPeriod.CUSTOM.getValue()).isEqualTo("Custom").hasSize(6);

            assertThat(payloadOf(everyComponentPresent()).toString())
                    .doesNotContain(ReportPeriod.MONTHLY.getValue(),
                            ReportPeriod.YEARLY.getValue(),
                            ReportPeriod.CUSTOM.getValue());
        }
    }

    @Nested
    @DisplayName("the six date parts stay split, and stay text")
    class TheSixDatePartsStaySplit {

        @Test
        @DisplayName("carries the start date as three parts at widths two, two and four")
        void carriesTheStartDateAsThreeParts() {
            ReportRequest request = withDates("07", "01", "2022", null, null, null);

            assertThat(request.startMonth()).isEqualTo("07");
            assertThat(request.startDay()).isEqualTo("01");
            assertThat(request.startYear()).isEqualTo("2022");
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("carries the end date as three parts at widths two, two and four")
        void carriesTheEndDateAsThreeParts() {
            ReportRequest request = withDates(null, null, null, "07", "19", "2022");

            assertThat(request.endMonth()).isEqualTo("07");
            assertThat(request.endDay()).isEqualTo("19");
            assertThat(request.endYear()).isEqualTo("2022");
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("keeps a leading zero, which a numeric component type would have dropped")
        void keepsALeadingZero() throws JsonProcessingException {
            ReportRequest request = withDates("07", "01", "2022", "01", "09", "2022");
            JsonNode payload = payloadOf(request);

            assertThat(payload.get("startMonth").asText()).isEqualTo("07").isNotEqualTo("7");
            assertThat(payload.get("startDay").asText()).isEqualTo("01").isNotEqualTo("1");
            assertThat(payload.get("endMonth").asText()).isEqualTo("01").isNotEqualTo("1");
            assertThat(payload.get("endDay").asText()).isEqualTo("09").isNotEqualTo("9");
            assertThat(roundTripped(request)).isEqualTo(request);
        }

        @Test
        @DisplayName("publishes every part as text, never as a number")
        void publishesEveryPartAsText() throws JsonProcessingException {
            JsonNode payload = payloadOf(withDates("07", "01", "2022", "07", "19", "2022"));

            for (String part : DATE_PART_NAMES) {
                assertThat(payload.get(part).isTextual())
                        .withFailMessage("date part %s must be published as text, because a numeric "
                                + "form would drop the leading zero the screen requires", part)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("keeps a blank part exactly as submitted, neither trimmed nor nulled nor emptied")
        void keepsABlankPartExactlyAsSubmitted() throws JsonProcessingException {
            // The absence stages the program runs from line 258 test each part against spaces, so an
            // all-spaces part is a meaningful submission that the service must be able to recognise for
            // itself. Trimming it here, or coercing it to absent, would answer the question early and
            // with the wrong message.
            ReportRequest request = withDates(" ", "  ", "    ", " ", "  ", "    ");

            assertThat(request.startMonth()).isEqualTo(" ").isNotNull().isNotEmpty();
            assertThat(request.startDay()).isEqualTo("  ");
            assertThat(request.startYear()).isEqualTo("    ");
            assertThat(request.endMonth()).isEqualTo(" ");
            assertThat(request.endDay()).isEqualTo("  ");
            assertThat(request.endYear()).isEqualTo("    ");

            assertThat(roundTripped(request)).isEqualTo(request);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("distinguishes a blank part from an empty one and from an absent one")
        void distinguishesBlankFromEmptyFromAbsent() {
            ReportRequest blank = withDates(" ", " ", "    ", " ", " ", "    ");
            ReportRequest empty = withDates("", "", "", "", "", "");
            ReportRequest absent = withDates(null, null, null, null, null, null);

            assertThat(blank.startMonth()).isEqualTo(" ");
            assertThat(empty.startMonth()).isEqualTo("");
            assertThat(absent.startMonth()).isNull();
            assertThat(blank).isNotEqualTo(empty).isNotEqualTo(absent);
            assertThat(empty).isNotEqualTo(absent);
        }

        @Test
        @DisplayName("accepts a month the calendar rejects, because the calendar stage is the service's")
        void acceptsAMonthTheCalendarRejects() throws JsonProcessingException {
            ReportRequest request = withDates("13", "01", "2022", "99", "19", "2022");

            assertThat(request.startMonth()).isEqualTo("13");
            assertThat(request.endMonth()).isEqualTo("99");
            assertThat(violationsOf(request)).isEmpty();
            assertThat(roundTripped(request)).isEqualTo(request);
        }

        @Test
        @DisplayName("accepts a day the calendar rejects, on the same terms")
        void acceptsADayTheCalendarRejects() throws JsonProcessingException {
            ReportRequest request = withDates("07", "32", "2022", "02", "31", "2022");

            assertThat(request.startDay()).isEqualTo("32");
            assertThat(request.endDay()).isEqualTo("31");
            assertThat(violationsOf(request)).isEmpty();
            assertThat(roundTripped(request)).isEqualTo(request);
        }

        @Test
        @DisplayName("accepts a zero-valued and a non-numeric part without complaint")
        void acceptsAZeroValuedAndANonNumericPart() throws JsonProcessingException {
            // The numeric stages the program runs after the absence stages are the ones that reject
            // these, and each of them carries its own message naming its own part. A transport-level
            // digit or bound constraint would report them together and in the wrong order.
            ReportRequest zeroed = withDates("00", "00", "0000", "00", "00", "0000");
            ReportRequest nonNumeric = withDates("ab", "cd", "efgh", "ij", "kl", "mnop");

            assertThat(zeroed.startMonth()).isEqualTo("00");
            assertThat(zeroed.startYear()).isEqualTo("0000");
            assertThat(nonNumeric.startMonth()).isEqualTo("ab");
            assertThat(nonNumeric.endYear()).isEqualTo("mnop");

            assertThat(violationsOf(zeroed)).isEmpty();
            assertThat(violationsOf(nonNumeric)).isEmpty();
            assertThat(roundTripped(zeroed)).isEqualTo(zeroed);
            assertThat(roundTripped(nonNumeric)).isEqualTo(nonNumeric);
        }

        @Test
        @DisplayName("publishes no merged, concatenated, formatted or derived date value")
        void publishesNoMergedOrDerivedDateValue() throws JsonProcessingException {
            ReportRequest request = withDates("07", "01", "2022", "07", "19", "2022");

            // The single-value form the batch tier receives is assembled by the report-request service,
            // and the calendar stages are decided by the date-validation service. Neither shape may
            // appear on the transport boundary, so no property beyond the six parts exists and no
            // separator-joined or unseparated concatenation of them appears in the payload text.
            assertThat(propertyNamesOf(request))
                    .containsAll(DATE_PART_NAMES)
                    .doesNotContain("startDate", "endDate", "dateRange", "reportStartDate",
                            "reportEndDate", "start", "end", "period", "fromDate", "toDate");
            assertThat(payloadOf(request).toString())
                    .doesNotContain("2022-07-01", "2022-07-19", "20220701", "20220719",
                            "07/01/2022", "07/19/2022");
        }

        @Test
        @DisplayName("bounds each part at its own width, and each independently of the other five")
        void boundsEachPartAtItsOwnWidth() {
            assertThat(violatingPathsOf(withDates("07", "01", "2022", "07", "19", "2022"))).isEmpty();
            assertThat(violatingPathsOf(withDates("123", null, null, null, null, null)))
                    .containsExactly("startMonth");
            assertThat(violatingPathsOf(withDates(null, "123", null, null, null, null)))
                    .containsExactly("startDay");
            assertThat(violatingPathsOf(withDates(null, null, FIVE_CHARACTERS, null, null, null)))
                    .containsExactly("startYear");
            assertThat(violatingPathsOf(withDates(null, null, null, "123", null, null)))
                    .containsExactly("endMonth");
            assertThat(violatingPathsOf(withDates(null, null, null, null, "123", null)))
                    .containsExactly("endDay");
            assertThat(violatingPathsOf(withDates(null, null, null, null, null, FIVE_CHARACTERS)))
                    .containsExactly("endYear");
        }
    }

    @Nested
    @DisplayName("the confirmation position is one explicit character, never a two-state flag")
    class TheConfirmationPositionIsOneCharacter {

        @Test
        @DisplayName("carries the accepting character exactly as submitted, in either case")
        void carriesTheAcceptingCharacterVerbatim() throws JsonProcessingException {
            // The accepting clause at CORPT00C line 478 matches the upper-case and the lower-case
            // character alike, so both are ordinary submissions and neither may be folded to the other.
            assertThat(withConfirm("Y").confirm()).isEqualTo("Y");
            assertThat(withConfirm("y").confirm()).isEqualTo("y").isNotEqualTo("Y");
            assertThat(roundTripped(withConfirm("y")).confirm()).isEqualTo("y");
            assertThat(violationsOf(withConfirm("Y"))).isEmpty();
            assertThat(violationsOf(withConfirm("y"))).isEmpty();
        }

        @Test
        @DisplayName("carries the refusing character exactly as submitted, in either case")
        void carriesTheRefusingCharacterVerbatim() throws JsonProcessingException {
            // The refusing clause at line 480 resets the screen and raises the error flag with no
            // message text at all. That silence is observable, and it is a third outcome rather than the
            // negation of the second, which is why the character is carried instead of a flag.
            assertThat(withConfirm("N").confirm()).isEqualTo("N");
            assertThat(withConfirm("n").confirm()).isEqualTo("n").isNotEqualTo("N");
            assertThat(roundTripped(withConfirm("N")).confirm()).isEqualTo("N");
            assertThat(violationsOf(withConfirm("N"))).isEmpty();
            assertThat(violationsOf(withConfirm("n"))).isEmpty();
        }

        @Test
        @DisplayName("carries an unrecognised character, which the response quotes back to the operator")
        void carriesAnUnrecognisedCharacter() throws JsonProcessingException {
            // The catch-all clause quotes the submitted character inside its own message, assembled
            // from the fragments at lines 486 and 488, so the exact character has to survive as far as
            // the response. A flag would have discarded it before the message could be built.
            assertThat(withConfirm("Q").confirm()).isEqualTo("Q");
            assertThat(withConfirm("?").confirm()).isEqualTo("?");
            assertThat(withConfirm("1").confirm()).isEqualTo("1");
            assertThat(roundTripped(withConfirm("?")).confirm()).isEqualTo("?");
            assertThat(violationsOf(withConfirm("Q"))).isEmpty();
            assertThat(violationsOf(withConfirm("?"))).isEmpty();
            assertThat(violationsOf(withConfirm("1"))).isEmpty();
        }

        @Test
        @DisplayName("accepts a space and an empty value, and keeps each exactly as submitted")
        void acceptsASpaceAndAnEmptyValue() throws JsonProcessingException {
            // An unmarked position is the prompt state at line 464 - neither acceptance nor refusal -
            // and the program reaches it by testing the position against spaces, so a space and an
            // absent value are both legitimate and must stay distinguishable from one another.
            ReportRequest spaced = withConfirm(" ");
            ReportRequest emptied = withConfirm("");
            ReportRequest absent = withConfirm(null);

            assertThat(spaced.confirm()).isEqualTo(" ").isNotNull().isNotEmpty();
            assertThat(emptied.confirm()).isEqualTo("");
            assertThat(absent.confirm()).isNull();
            assertThat(spaced).isNotEqualTo(emptied).isNotEqualTo(absent);

            assertThat(roundTripped(spaced)).isEqualTo(spaced);
            assertThat(roundTripped(emptied)).isEqualTo(emptied);
            assertThat(violationsOf(spaced)).isEmpty();
            assertThat(violationsOf(emptied)).isEmpty();
            assertThat(violationsOf(absent)).isEmpty();
        }

        @Test
        @DisplayName("bounds the position at one character")
        void boundsThePositionAtOneCharacter() {
            assertThat(violatingPathsOf(withConfirm("Y"))).isEmpty();
            assertThat(violatingPathsOf(withConfirm("YY"))).containsExactly("confirm");
            assertThat(violatingPathsOf(withConfirm("Yes"))).containsExactly("confirm");
        }

        @Test
        @DisplayName("publishes no derived flag alongside the character")
        void publishesNoDerivedFlag() throws JsonProcessingException {
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .contains("confirm")
                    .doesNotContain("confirmed", "confirmation", "isConfirmed", "accepted",
                            "refused", "acknowledged");
            assertThat(payloadOf(withConfirm("Y")).get("confirm").isTextual()).isTrue();
            assertThat(payloadOf(withConfirm("Y")).get("confirm").isBoolean()).isFalse();
        }
    }

    @Nested
    @DisplayName("every field rule is delegated: the request reports no violation of its own")
    class EveryFieldRuleIsDelegated {

        @Test
        @DisplayName("reports nothing when every component is absent")
        void reportsNothingWhenEveryComponentIsAbsent() {
            ReportRequest nothingSupplied = new ReportRequest(null, null, null, null, null, null,
                    null, null, null, null, null, null);

            assertThat(violationsOf(nothingSupplied)).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when every text component is empty")
        void reportsNothingWhenEveryTextComponentIsEmpty() {
            assertThat(violationsOf(withEveryTextComponent(""))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when every text component is blank")
        void reportsNothingWhenEveryTextComponentIsBlank() {
            assertThat(violationsOf(withEveryTextComponent(" "))).isEmpty();
        }

        @Test
        @DisplayName("declares no presence constraint, which the absent and blank submissions prove")
        void declaresNoPresenceConstraint() {
            // A constraint rejecting an absent value would have fired on the first submission, one
            // rejecting an empty value on the second, and one rejecting a blank value on the third.
            // None fires, so none is declared - the six absence stages the program runs from line 258
            // remain the service's, each with its own message naming its own part.
            assertThat(violationsOf(new ReportRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null))).isEmpty();
            assertThat(violationsOf(withEveryTextComponent(""))).isEmpty();
            assertThat(violationsOf(withEveryTextComponent(" "))).isEmpty();
        }

        @Test
        @DisplayName("declares no pattern, digit or numeric-bound constraint")
        void declaresNoPatternDigitOrNumericBoundConstraint() {
            // Each submission below would have tripped one of the constraints this record must not
            // carry: a non-numeric part trips a digit or character-class constraint, a month of
            // thirteen and a day of thirty-two trip an upper bound, and a zero-valued part trips a
            // lower bound. All three report nothing, so the numeric-and-range stages stay in the
            // service, where they are evaluated in source order and report one message at a time.
            assertThat(violationsOf(withDates("ab", "cd", "efgh", "ij", "kl", "mnop"))).isEmpty();
            assertThat(violationsOf(withDates("13", "32", "9999", "13", "32", "9999"))).isEmpty();
            assertThat(violationsOf(withDates("00", "00", "0000", "00", "00", "0000"))).isEmpty();
            assertThat(violationsOf(withConfirm("*"))).isEmpty();
            assertThat(violationsOf(withSelections("1", "2", "3"))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for a submission that marks more than one report type")
        void reportsNothingForAMultiMarkerSubmission() {
            assertThat(violationsOf(withSelections("Y", "Y", "Y"))).isEmpty();
        }

        @Test
        @DisplayName("accepts a value exactly at each of the ten screen widths")
        void acceptsAValueAtEachOfTheTenScreenWidths() {
            // The fixture is filled to the width of every one of the ten typed positions at once:
            // one character in each of the three markers and the confirmation, two in each month and
            // day part, four in each year part.
            ReportRequest atEveryWidth = everyComponentPresent();

            assertThat(atEveryWidth.monthlySelection()).hasSize(1);
            assertThat(atEveryWidth.yearlySelection()).hasSize(1);
            assertThat(atEveryWidth.customSelection()).hasSize(1);
            assertThat(atEveryWidth.startMonth()).hasSize(2);
            assertThat(atEveryWidth.startDay()).hasSize(2);
            assertThat(atEveryWidth.startYear()).hasSize(4);
            assertThat(atEveryWidth.endMonth()).hasSize(2);
            assertThat(atEveryWidth.endDay()).hasSize(2);
            assertThat(atEveryWidth.endYear()).hasSize(4);
            assertThat(atEveryWidth.confirm()).hasSize(1);
            assertThat(violationsOf(atEveryWidth)).isEmpty();
        }

        @Test
        @DisplayName("rejects one character beyond the width on both four-character year parts")
        void rejectsOneCharacterBeyondOnBothYearParts() {
            assertThat(violatingPathsOf(withDates(null, null, FIVE_CHARACTERS, null, null, null)))
                    .containsExactly("startYear");
            assertThat(violatingPathsOf(withDates(null, null, null, null, null, FIVE_CHARACTERS)))
                    .containsExactly("endYear");
            assertThat(violatingPathsOf(
                    withDates(null, null, FIVE_CHARACTERS, null, null, FIVE_CHARACTERS)))
                    .containsExactlyInAnyOrder("startYear", "endYear");
        }

        @Test
        @DisplayName("rejects one character beyond the width on a two-character part")
        void rejectsOneCharacterBeyondOnATwoCharacterPart() {
            assertThat(violatingPathsOf(withDates("123", null, null, null, null, null)))
                    .containsExactly("startMonth");
        }

        @Test
        @DisplayName("rejects one character beyond the width on every one of the ten typed positions")
        void rejectsOneCharacterBeyondOnEveryTypedPosition() {
            // Every component is exactly one character wider than its own screen width, so every one
            // of the ten bounds is at its first rejecting value simultaneously.
            ReportRequest oneBeyondEverywhere = new ReportRequest("YY", "YY", "YY", "123", "123",
                    FIVE_CHARACTERS, "123", "123", FIVE_CHARACTERS, "YY", KeyAction.ENTER, null);

            assertThat(violatingPathsOf(oneBeyondEverywhere))
                    .containsExactlyInAnyOrderElementsOf(TEXT_COMPONENT_NAMES);
        }

        @Test
        @DisplayName("cascades into the echoed navigation state without adding a constraint of its own")
        void cascadesIntoTheEchoedNavigationState() {
            // The one cascade the record declares makes the widths the nested type states for itself
            // actually evaluated; it introduces no rule the nested type does not already declare, and
            // it cannot pre-empt the ordered service cascade, because an over-wide value inside an
            // echoed context is a state no 3270 submission could have produced.
            NavigationContext overWide = new NavigationContext("CR000", null, null, null, null, null,
                    NavigationContext.ProgramContext.ENTER, null, null, null, null, null, null, null,
                    null, null);
            ReportRequest request = new ReportRequest(null, null, "Y", null, null, null, null, null,
                    null, null, KeyAction.ENTER, overWide);

            assertThat(violatingPathsOf(request))
                    .containsExactly("navigationContext.fromTransactionId");
            assertThat(violationsOf(everyComponentPresent())).isEmpty();
            assertThat(violationsOf(withSelections("Y", null, null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("wire form")
    class WireForm {

        @Test
        @DisplayName("publishes exactly the twelve components, in the order the screen declares them")
        void publishesExactlyTheTwelveComponentsInScreenOrder() throws JsonProcessingException {
            // This single assertion is the file's strongest absence proof: a thirteenth property of any
            // kind - a collapsed period, a merged date, a derived confirmation flag, a piece of screen
            // or response furniture, a card image or a queue name - would fail it.
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .hasSize(12)
                    .containsExactlyElementsOf(COMPONENT_NAMES);
        }

        @Test
        @DisplayName("omits an absent component rather than publishing it as null")
        void omitsAnAbsentComponentRatherThanPublishingNull() throws JsonProcessingException {
            assertThat(propertyNamesOf(withSelections("Y", null, null)))
                    .containsExactly("monthlySelection", "keyAction");
            assertThat(propertyNamesOf(new ReportRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("tolerates an unknown incoming property rather than rejecting the submission")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            String withStrayProperty = "{\"monthlySelection\":\"Y\",\"startMonth\":\"07\","
                    + "\"aPropertyTheContractNeverDeclared\":\"x\"}";

            ReportRequest bound =
                    moduleEquivalentMapper().readValue(withStrayProperty, ReportRequest.class);

            assertThat(bound.monthlySelection()).isEqualTo("Y");
            assertThat(bound.startMonth()).isEqualTo("07");
            assertThat(bound.yearlySelection()).isNull();
            assertThat(bound.keyAction()).isNull();
            assertThat(bound.navigationContext()).isNull();
        }

        @Test
        @DisplayName("round-trips every value byte for byte, untrimmed, unpadded and un-case-folded")
        void roundTripsEveryValueByteForByte() throws JsonProcessingException {
            // Deliberately awkward: a lower-case marker, a blank marker, an empty marker, parts with a
            // leading and with a trailing space, a part shorter than its width, and a lower-case
            // confirmation. Every one of them is a submission the 3270 can produce.
            ReportRequest awkward = new ReportRequest("y", " ", "", "7 ", " 1", "2022", "  ", "19",
                    "  22", "n", KeyAction.PFK03, navigation());

            ReportRequest returned = roundTripped(awkward);

            assertThat(returned.monthlySelection()).isEqualTo("y");
            assertThat(returned.yearlySelection()).isEqualTo(" ");
            assertThat(returned.customSelection()).isEqualTo("");
            assertThat(returned.startMonth()).isEqualTo("7 ");
            assertThat(returned.startDay()).isEqualTo(" 1");
            assertThat(returned.startYear()).isEqualTo("2022");
            assertThat(returned.endMonth()).isEqualTo("  ");
            assertThat(returned.endDay()).isEqualTo("19");
            assertThat(returned.endYear()).isEqualTo("  22");
            assertThat(returned.confirm()).isEqualTo("n");
            assertThat(returned.keyAction()).isEqualTo(KeyAction.PFK03);
            assertThat(returned.navigationContext()).isEqualTo(navigation());
            assertThat(returned).isEqualTo(awkward);
        }

        @Test
        @DisplayName("never pads a value shorter than its screen width up to that width")
        void neverPadsAShortValueUpToItsWidth() throws JsonProcessingException {
            JsonNode payload = payloadOf(withDates("7", "1", "22", "7", "1", "22"));

            assertThat(payload.get("startMonth").asText()).isEqualTo("7").hasSize(1);
            assertThat(payload.get("startDay").asText()).isEqualTo("1").hasSize(1);
            assertThat(payload.get("startYear").asText()).isEqualTo("22").hasSize(2);
            assertThat(payload.get("endYear").asText()).isEqualTo("22").hasSize(2);
        }

        @Test
        @DisplayName("publishes the attention key as its named action rather than as a position")
        void publishesTheAttentionKeyAsItsNamedAction() throws JsonProcessingException {
            // The mapset offers two keys - continue and back, per app/bms/CORPT00.bms line 226 and the
            // key evaluation at CORPT00C line 184 - and every other key is unmapped and reported by the
            // service. A key is a named byte and never an index, so the published form is the name.
            assertThat(payloadOf(withSelections("Y", null, null)).get("keyAction").asText())
                    .isEqualTo("ENTER");
            assertThat(payloadOf(new ReportRequest(null, null, null, null, null, null, null, null,
                    null, null, KeyAction.PFK03, null)).get("keyAction").asText())
                    .isEqualTo("PFK03");
        }

        @Test
        @DisplayName("publishes the echoed navigation state as a nested object, not as a flat prefix")
        void publishesTheEchoedNavigationStateAsANestedObject() throws JsonProcessingException {
            JsonNode nested = payloadOf(everyComponentPresent()).get("navigationContext");

            assertThat(nested.isObject()).isTrue();
            assertThat(nested.get("fromTransactionId").asText()).isEqualTo("CR00");
            assertThat(nested.get("programContext").asText()).isEqualTo("REENTER");
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("exposes each of the twelve components through its own accessor")
        void exposesEachComponentThroughItsOwnAccessor() {
            ReportRequest request = everyComponentPresent();

            assertThat(request.monthlySelection()).isEqualTo("Y");
            assertThat(request.yearlySelection()).isEqualTo("Y");
            assertThat(request.customSelection()).isEqualTo("Y");
            assertThat(request.startMonth()).isEqualTo("07");
            assertThat(request.startDay()).isEqualTo("01");
            assertThat(request.startYear()).isEqualTo("2022");
            assertThat(request.endMonth()).isEqualTo("07");
            assertThat(request.endDay()).isEqualTo("19");
            assertThat(request.endYear()).isEqualTo("2022");
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext()).isEqualTo(navigation());
        }

        @Test
        @DisplayName("is immutable: no accessor can be re-pointed and repeated reads never differ")
        void isImmutableByConstruction() {
            // Demonstrated by construction rather than by inspecting the declaration. The type exposes
            // accessors and no mutator, so the only way to obtain a different value is to construct a
            // different instance; the original is unaffected by that construction, and every read of it
            // returns the same value.
            ReportRequest original = everyComponentPresent();

            ReportRequest derived = new ReportRequest("N", original.yearlySelection(),
                    original.customSelection(), original.startMonth(), original.startDay(),
                    original.startYear(), original.endMonth(), original.endDay(), original.endYear(),
                    original.confirm(), original.keyAction(), original.navigationContext());

            assertThat(original.monthlySelection()).isEqualTo("Y");
            assertThat(original.monthlySelection()).isSameAs(original.monthlySelection());
            assertThat(original.navigationContext()).isSameAs(original.navigationContext());
            assertThat(derived.monthlySelection()).isEqualTo("N");
            assertThat(derived).isNotEqualTo(original);
        }

        @Test
        @DisplayName("treats two requests built from the same values as equal, and hashes them alike")
        void equalInputsAreEqualAndHashAlike() {
            ReportRequest first = everyComponentPresent();
            ReportRequest second = everyComponentPresent();

            assertThat(first).isEqualTo(second).isNotSameAs(second);
            assertThat(first).hasSameHashCodeAs(second);
            assertThat(first).isEqualTo(first);
            assertThat(first).isNotEqualTo("a request is not a string");
        }

        @Test
        @DisplayName("distinguishes a request that differs in any single component")
        void distinguishesARequestDifferingInAnySingleComponent() {
            ReportRequest reference = everyComponentPresent();
            NavigationContext other = NavigationContext.empty();

            assertThat(reference).isNotEqualTo(new ReportRequest("N", "Y", "Y", "07", "01", "2022",
                    "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "N", "Y", "07", "01", "2022",
                    "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "N", "07", "01", "2022",
                    "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "08", "01", "2022",
                    "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "07", "02", "2022",
                    "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "07", "01", "2021",
                    "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "07", "01", "2022",
                    "08", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "07", "01", "2022",
                    "07", "20", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "07", "01", "2022",
                    "07", "19", "2023", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "07", "01", "2022",
                    "07", "19", "2022", "N", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "07", "01", "2022",
                    "07", "19", "2022", "Y", KeyAction.PFK03, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest("Y", "Y", "Y", "07", "01", "2022",
                    "07", "19", "2022", "Y", KeyAction.ENTER, other));
        }

        @Test
        @DisplayName("names the type and every one of its own component values when described")
        void namesTheTypeAndEveryOwnComponentValueWhenDescribed() {
            String described = new ReportRequest("y", "Y", "X", "07", "01", "2022", "12", "31", "2022",
                    "n", KeyAction.PFK03, null).toString();

            assertThat(described).startsWith("ReportRequest[").endsWith("]");
            assertThat(described).contains("monthlySelection=y", "yearlySelection=Y",
                    "customSelection=X", "startMonth=07", "startDay=01", "startYear=2022",
                    "endMonth=12", "endDay=31", "endYear=2022", "confirm=n", "keyAction=PFK03",
                    "navigationContext=null");
        }
    }

    @Nested
    @DisplayName("what the request deliberately does not carry")
    class WhatTheRequestDoesNotCarry {

        @Test
        @DisplayName("carries no screen furniture: no title, transaction, program, date or time item")
        void carriesNoScreenFurniture() throws JsonProcessingException {
            // The program writes all of these itself before sending the screen, and the map declares
            // them ahead of the ten typed positions. A request that carried them would let a client
            // dictate text only the server produces.
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .hasSize(12)
                    .doesNotContain("transactionName", "trnName", "title", "title01", "title02",
                            "screenTitle", "currentDate", "curDate", "currentTime", "curTime",
                            "programName", "pgmName");
        }

        @Test
        @DisplayName("carries no response furniture: no message, cursor, attribute or key legend")
        void carriesNoResponseFurniture() throws JsonProcessingException {
            // The 78-character error line the map declares after the confirmation position, and the
            // 23-character key legend the mapset places on the bottom row, are both response-side.
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .doesNotContain("errorMessage", "errMsg", "message", "infoMessage", "cursor",
                            "cursorPosition", "attribute", "colour", "color", "highlight",
                            "keyLegend", "functionKeys", "fieldErrors", "errorFlag");
        }

        @Test
        @DisplayName("carries nothing of the job-submission payload the program builds from it")
        void carriesNothingOfTheJobSubmissionPayload() throws JsonProcessingException {
            // The fixed image, its substitution slots, its record width, its terminating sentinel and
            // the resource it is written to all belong to the module's utility and service layers, and
            // the half of that contract which requires draining a real queue belongs to the integration
            // and end-to-end tiers. Nothing of it may cross this boundary in either direction.
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .doesNotContain("cards", "cardImages", "jobImage", "jobStream", "jclCards",
                            "queueName", "queue", "destination", "sentinel", "payload",
                            "substitutionSlots", "recordSize");
            assertThat(payloadOf(everyComponentPresent()).toString()).doesNotContain("/*EOF");
        }

        @Test
        @DisplayName("holds no fixed-width card image, because its widest position is four characters")
        void holdsNoFixedWidthCardImage() throws JsonProcessingException {
            JsonNode payload = payloadOf(everyComponentPresent());

            for (String component : TEXT_COMPONENT_NAMES) {
                assertThat(payload.get(component).asText().length())
                        .withFailMessage("typed position %s must stay within four characters, so that "
                                + "no component of this request can hold a fixed-width card image",
                                component)
                        .isLessThanOrEqualTo(4);
            }

            // And the bound is enforced rather than merely observed: an eighty-character value - the
            // record width of the resource the legacy program writes its job image to - is rejected on
            // every one of the ten typed positions at once.
            assertThat(violatingPathsOf(withEveryTextComponent(EIGHTY_CHARACTERS)))
                    .containsExactlyInAnyOrderElementsOf(TEXT_COMPONENT_NAMES);
        }
    }
}
