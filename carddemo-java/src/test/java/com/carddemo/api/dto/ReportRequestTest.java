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

import java.util.Arrays;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
 *   <li><strong>Three report-type markers, not one enumerated period.</strong> The map declares
 *       three separate one-character items - {@code MONTHLYI} at line 60, {@code YEARLYI} at line 66
 *       and {@code CUSTOMI} at line 72 - and the program's ordered evaluation, whose header sits at
 *       {@code CORPT00C} line 212 with the monthly clause immediately after it, tests them in the fixed
 *       order monthly, yearly, custom at lines 214, 240 and 256, acts on the first non-blank one, and
 *       falls to its catch-all clause at line 437 when none is marked. All three are carried as three
 *       separately markable one-character components. An earlier revision collapsed them into one
 *       enumerated component on the grounds that the positions are mutually exclusive and that a
 *       multiply-marked state should be unrepresentable; neither premise holds. Three independently
 *       markable fields mean a submission carrying two or three marks is a state the 3270 screen can
 *       actually produce, and the program does not reject it - it resolves it by that fixed order. One
 *       value cannot express "monthly and custom were both marked", so it cannot reproduce the
 *       resolution either: it forces the client to choose, relocating the program's own
 *       first-match-wins decision onto the caller. The collapse also discarded the marker characters,
 *       which the program never inspects beyond non-blankness and which are therefore carried verbatim.
 *       The unmarked state is three blank or absent values rather than a synthesised vocabulary member,
 *       so absence stays absence and the catch-all message stays the service's to report, and the
 *       resolved period is published on the response the service produces. Every one of those properties
 *       is exercised below.</li>
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
 *       blank, each period in turn, a month of thirteen, a day of thirty-two, a non-numeric
 *       year - must report <em>zero</em> violations. That excludes a presence constraint, a
 *       character-class or pattern constraint, a digit constraint and a numeric bound, because any
 *       one of them would have fired on one of those inputs.</li>
 *   <li>The serialized property set must be <em>exactly</em> the twelve components. That single
 *       assertion excludes, in one stroke, a collapsed or derived report period, a derived report name,
 *       a merged or formatted date value, a derived confirmation flag, every piece of screen and
 *       response furniture, and every fragment of the job-submission payload - because any of them
 *       would appear as a thirteenth property.</li>
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

    /**
     * The ten wire property names, in the order the record declares them, which is the order the
     * symbolic map declares the items they carry. Used as the exact expected property set, so an
     * eleventh property of any kind - a surviving per-position marker, a derived report name,
     * furniture, a merged date, a derived flag, a card image - fails the assertion that references it.
     */
    private static final List<String> COMPONENT_NAMES = List.of(
            "monthlySelection", "yearlySelection", "customSelection",
            "startMonth", "startDay", "startYear",
            "endMonth", "endDay", "endYear",
            "confirm", "keyAction", "navigationContext");

    /**
     * The three per-position markers, in the order {@code app/cbl/CORPT00C.cbl} tests them: month-to-date
     * at line 214, year-to-date at line 240, operator-supplied range at line 256. Each is carried on its
     * own so that a submission marking more than one - a state the 3270 screen genuinely produces - stays
     * representable and is resolved by that order rather than by the caller.
     */
    private static final List<String> SELECTION_MARKER_NAMES =
            List.of("monthlySelection", "yearlySelection", "customSelection");

    /**
     * The single collapsed property an earlier revision published in place of the three markers.
     * Asserted absent, because accepting a resolved report type here would relocate the program's own
     * first-match-wins decision onto the caller.
     */
    private static final String REMOVED_PERIOD_NAME = "reportPeriod";

    /** The six date parts, in screen order: start month, day, year then end month, day, year. */
    private static final List<String> DATE_PART_NAMES =
            List.of("startMonth", "startDay", "startYear", "endMonth", "endDay", "endYear");

    /**
     * The ten text components, being every component except the typed attention key and the echoed
     * navigation state. These are the ten whose screen items are fixed-width, and the only ten that
     * carry a width bound - the three one-character selection markers among them.
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
     * null, so an assertion over the complete property set needs a fixture with nothing absent. All
     * three selection positions are marked and all six date parts are populated: marking all three is
     * itself a state the 3270 screen can produce, and it is the only way every component is present at
     * once. The ordered service evaluation resolves such a submission to the month-to-date arm, which
     * is asserted where that resolution is the subject rather than here.
     *
     * @return a request with no absent component
     */
    private static ReportRequest everyComponentPresent() {
        return new ReportRequest(SELECTION_MARK, SELECTION_MARK, SELECTION_MARK,
                "07", "01", "2022", "07", "19", "2022", "Y",
                KeyAction.ENTER, navigation());
    }

    /**
     * Builds a request that carries the reporting period and nothing else of substance.
     *
     * @param period the period the operator chose; may be {@code null} for the unmarked state
     * @return a request whose six date parts and confirmation character are all absent
     */
    private static ReportRequest withPeriod(ReportPeriod period) {
        return new ReportRequest(monthlyMarkerFor(period), yearlyMarkerFor(period), customMarkerFor(period), null, null, null, null, null, null, null,
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
        return new ReportRequest(null, null, "Y", startMonth, startDay, startYear, endMonth,
                endDay, endYear, null, KeyAction.ENTER, null);
    }

    /**
     * Builds a request carrying the confirmation character exactly as supplied.
     *
     * @param confirm the confirmation character, one position on the screen; may be {@code null}
     * @return a request whose only supplied values are the custom period and the confirmation
     */
    private static ReportRequest withConfirm(String confirm) {
        return new ReportRequest(null, null, "Y", null, null, null, null, null, null, confirm,
                KeyAction.ENTER, null);
    }

    /**
     * Builds a request in which every one of the ten text components carries the same value.
     *
     * <p>Used for the width-bound edges, where the point is to drive one value against all ten bounds
     * at once and read back which of them reacted. The three one-character selection markers are among
     * them, because each declares the screen width the mapset gives it.
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
    @DisplayName("the three report-type markers stay three markers")
    class TheThreeMarkersStayThreeMarkers {

        @Test
        @DisplayName("state one: no period supplied, which is the unmarked screen the catch-all answers")
        void stateOneNoPeriodSupplied() throws JsonProcessingException {
            ReportRequest absent = withPeriod(null);

            assertThat(resolvedPeriodOf(absent)).isNull();

            // The unmarked screen is a legitimate submission: the program answers it with the catch-all
            // clause at CORPT00C line 438, which is an input-error message rather than a transport
            // rejection. Absence must therefore survive the wire as absence, never as a synthesised
            // member and never as a refusal.
            assertThat(roundTripped(absent)).isEqualTo(absent);
            assertThat(violationsOf(absent)).isEmpty();
            assertThat(SELECTION_MARKER_NAMES).allSatisfy(marker ->
                    assertThat(payloadOf(absent).has(marker))
                            .as("an unmarked screen omits %s rather than publishing a null or a "
                                    + "blank mark", marker)
                            .isFalse());
        }

        @ParameterizedTest(name = "state two: exactly the {0} period supplied")
        @EnumSource(ReportPeriod.class)
        @DisplayName("state two: exactly one period supplied, for each of the three the screen names")
        void stateTwoExactlyOnePeriodSupplied(ReportPeriod period) throws JsonProcessingException {
            ReportRequest request = withPeriod(period);

            assertThat(resolvedPeriodOf(request)).isSameAs(period);
            assertThat(roundTripped(request)).isEqualTo(request);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("state three: a multiply-marked screen is representable and accepted, and the "
                + "program's own test order breaks the tie")
        void stateThreeAMultiplyMarkedScreenIsRepresentableAndAccepted()
                throws JsonProcessingException {
            // Three independently markable one-character fields mean a submission carrying two or three
            // marks is a state the 3270 screen actually produces. CORPT00C does not reject it - it tests
            // the positions in order at lines 214, 240 and 256 and acts on the first non-blank one. The
            // boundary must therefore admit the combination and leave the resolution to the program,
            // rather than forcing the caller to pick which mark counted.
            ReportRequest multiplyMarked = new ReportRequest(
                    SELECTION_MARK, SELECTION_MARK, SELECTION_MARK,
                    null, null, null, null, null, null, null, KeyAction.ENTER,
                    NavigationContext.empty());

            assertThat(violationsOf(multiplyMarked))
                    .as("no exclusivity rule may refuse a submission the legacy screen accepts")
                    .isEmpty();
            assertThat(roundTripped(multiplyMarked))
                    .as("every mark survives the wire on its own position")
                    .isEqualTo(multiplyMarked);
            assertThat(resolvedPeriodOf(multiplyMarked))
                    .as("month-to-date is tested first and therefore wins the tie")
                    .isSameAs(ReportPeriod.MONTHLY)
                    .isNotSameAs(ReportPeriod.YEARLY);
            assertThat(COMPONENT_NAMES)
                    .as("no collapsed period component sits alongside the three markers")
                    .doesNotContain(REMOVED_PERIOD_NAME);
        }

        @Test
        @DisplayName("state four: an unrecognised character resolves to absence without raising, and no "
                + "case folding widens the vocabulary")
        void stateFourAnUnrecognisedCharacterResolvesToAbsence() {
            // The absence test the program applies is "not spaces and not low values", so any printable
            // character marked a position on the 3270. None of those characters is a period value, so
            // recognition must yield absence rather than raising, and the catch-all message at line 438
            // stays the service's to report.
            assertThat(ReportPeriod.fromValue(null)).isEmpty();
            assertThat(ReportPeriod.fromValue("")).isEmpty();
            assertThat(ReportPeriod.fromValue(" ")).isEmpty();
            assertThat(ReportPeriod.fromValue("Y")).isEmpty();
            assertThat(ReportPeriod.fromValue("*")).isEmpty();
            assertThat(ReportPeriod.fromValue("monthly"))
                    .as("recognition is exact; folding case would widen a closed vocabulary")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue("MONTHLY"))
                    .as("the member name is not a carried value")
                    .isEmpty();
            assertThat(ReportPeriod.fromValue("Monthly "))
                    .as("no whitespace is normalised away before the lookup")
                    .isEmpty();
        }

        @Test
        @DisplayName("publishes the mark on its own position and publishes no collapsed period "
                + "property, so the derived report name never crosses inbound")
        void publishesTheMarkOnItsOwnPositionAndNoCollapsedProperty() throws JsonProcessingException {
            JsonNode payload = payloadOf(withPeriod(ReportPeriod.YEARLY));

            assertThat(payload.get("yearlySelection").asText())
                    .as("the wire form of a marked screen field is the marker character itself, "
                            + "carried verbatim because the program only tests non-blankness")
                    .isEqualTo(SELECTION_MARK);
            assertThat(payload.has("monthlySelection")).isFalse();
            assertThat(payload.has("customSelection")).isFalse();
            assertThat(ReportPeriod.YEARLY.getValue())
                    .as("the report name the program writes is the vocabulary's carried value, and "
                            + "the service produces it rather than accepting it here")
                    .isEqualTo("Yearly")
                    .isNotEqualTo(ReportPeriod.YEARLY.name());
            assertThat(propertyNamesOf(withPeriod(ReportPeriod.YEARLY)))
                    .contains("yearlySelection")
                    .doesNotContain(REMOVED_PERIOD_NAME);
        }

        @Test
        @DisplayName("the three periods are mutually distinguishing, so a request naming one is never "
                + "equal to a request naming another")
        void theThreePeriodsAreMutuallyDistinguishing() {
            assertThat(withPeriod(ReportPeriod.MONTHLY))
                    .isNotEqualTo(withPeriod(ReportPeriod.YEARLY))
                    .isNotEqualTo(withPeriod(ReportPeriod.CUSTOM))
                    .isNotEqualTo(withPeriod(null));
            assertThat(withPeriod(ReportPeriod.MONTHLY))
                    .isEqualTo(withPeriod(ReportPeriod.MONTHLY))
                    .hasSameHashCodeAs(withPeriod(ReportPeriod.MONTHLY));
        }

        @Test
        @DisplayName("applies no vocabulary, presence or exclusivity rule of its own, because the "
                + "select-a-report-type decision belongs to the ordered service evaluation")
        void appliesNoVocabularyPresenceOrExclusivityRule() throws NoSuchFieldException {
            // "Select a report type" is a decision the ordered service evaluation makes, and a
            // transport-level presence rule would refuse the unmarked screen the legacy accepted and
            // answered with its own message. An exclusivity rule would likewise refuse the
            // multiply-marked screen the legacy accepted and resolved by order.
            assertThat(violationsOf(withPeriod(null))).isEmpty();
            assertThat(violationsOf(withPeriod(ReportPeriod.MONTHLY))).isEmpty();
            assertThat(violationsOf(withPeriod(ReportPeriod.YEARLY))).isEmpty();
            assertThat(violationsOf(withPeriod(ReportPeriod.CUSTOM))).isEmpty();
            for (String marker : SELECTION_MARKER_NAMES) {
                assertThat(Arrays.stream(
                                ReportRequest.class.getDeclaredField(marker).getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName()).toList())
                        .as("%s carries its screen width and no other declarative rule - no presence "
                                + "rule, no pattern over which character marks it, and no exclusivity "
                                + "rule against its two peers", marker)
                        .containsExactly("Size");
            }
        }

        @Test
        @DisplayName("the vocabulary carries exactly three members and none standing for the unmarked "
                + "state, so absence can never be mistaken for a chosen period")
        void theVocabularyCarriesExactlyThreeMembers() {
            assertThat(ReportPeriod.values()).hasSize(3);
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .containsExactly("MONTHLY", "YEARLY", "CUSTOM")
                    .doesNotContain("NONE", "UNKNOWN", "OTHER", "INVALID", "DEFAULT");
        }
    }

    @Nested
    @DisplayName("the report name is produced by the program, never submitted to it")
    class TheReportNameIsProducedNotSubmitted {

        @Test
        @DisplayName("carries no report-name and no resolved-period property of its own under any of "
                + "its plausible names, because both are produced by the ordered service evaluation")
        void carriesNoReportNameOrResolvedPeriodPropertyOfItsOwn() throws JsonProcessingException {
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .doesNotContain("reportName", "reportType", "selection", "selectedReport",
                            "reportPeriod", "period", "resolvedPeriod", "selectedPeriod");
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .as("the three screen positions themselves are carried, because they are what the "
                            + "operator marks and what the program tests")
                    .contains("monthlySelection", "yearlySelection", "customSelection");
        }

        @Test
        @DisplayName("the three produced report names are the vocabulary's own bare mixed-case values, "
                + "unpadded despite the ten-character work item that holds them")
        void theThreeProducedReportNamesAreTheVocabularysOwnValues() throws JsonProcessingException {
            // The three literals are the ones the program writes into its ten-character report-name
            // work field at CORPT00C lines 214, 240 and 433. They are bare rather than padded out to
            // that width, because both of the field's read sites - lines 449 and 468 - consume it
            // delimited by space.
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo("Monthly").hasSize(7);
            assertThat(ReportPeriod.YEARLY.getValue()).isEqualTo("Yearly").hasSize(6);
            assertThat(ReportPeriod.CUSTOM.getValue()).isEqualTo("Custom").hasSize(6);

            // The name reaches the wire only as the period's value, and under the period's own property
            // name - never as a second, separately submittable component.
            JsonNode payload = payloadOf(everyComponentPresent());
            assertThat(payload.has(REMOVED_PERIOD_NAME))
                    .as("the name reaches the wire only on the response the service produces, never "
                            + "as a component of this request")
                    .isFalse();
            assertThat(payload.size())
                    .as("the name adds no property of its own")
                    .isEqualTo(COMPONENT_NAMES.size());
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
            ReportRequest nothingSupplied = new ReportRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null);

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
            assertThat(violationsOf(new ReportRequest(null, null, null, null, null, null, null, null, null,
                    null, null, null))).isEmpty();
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
        }

        @Test
        @DisplayName("reports nothing for any of the three periods the screen names")
        void reportsNothingForAnyOfTheThreePeriods() {
            assertThat(violationsOf(withPeriod(ReportPeriod.MONTHLY))).isEmpty();
            assertThat(violationsOf(withPeriod(ReportPeriod.YEARLY))).isEmpty();
            assertThat(violationsOf(withPeriod(ReportPeriod.CUSTOM))).isEmpty();
        }

        @Test
        @DisplayName("accepts a value exactly at each of the ten screen widths")
        void acceptsAValueAtEachOfTheTenScreenWidths() {
            // The fixture is filled to the width of every one of the ten bounded positions at once:
            // one character in each selection marker, two in each month and day part, four in each year
            // part, one in the confirmation.
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
        @DisplayName("rejects one character beyond the width on every one of the ten bounded positions")
        void rejectsOneCharacterBeyondOnEveryBoundedPosition() {
            // Every bounded component is exactly one character wider than its own screen width, so every
            // one of the ten bounds is at its first rejecting value simultaneously.
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
            ReportRequest request = new ReportRequest(null, null, "Y", null, null, null, null,
                    null, null, null, KeyAction.ENTER, overWide);

            assertThat(violatingPathsOf(request))
                    .containsExactly("navigationContext.fromTransactionId");
            assertThat(violationsOf(everyComponentPresent())).isEmpty();
            assertThat(violationsOf(withPeriod(ReportPeriod.MONTHLY))).isEmpty();
        }
    }

    @Nested
    @DisplayName("wire form")
    class WireForm {

        @Test
        @DisplayName("publishes exactly the twelve components, in the order the screen declares them")
        void publishesExactlyTheTwelveComponentsInScreenOrder() throws JsonProcessingException {
            // This single assertion is the file's strongest absence proof: a thirteenth property of any
            // kind - a collapsed or derived report period, a derived report name, a merged date, a
            // derived confirmation flag, a piece of screen or response furniture, a card image or a
            // queue name - would fail it.
            assertThat(propertyNamesOf(everyComponentPresent()))
                    .hasSize(12)
                    .containsExactlyElementsOf(COMPONENT_NAMES)
                    .doesNotContain(REMOVED_PERIOD_NAME);
        }

        @Test
        @DisplayName("omits an absent component rather than publishing it as null")
        void omitsAnAbsentComponentRatherThanPublishingNull() throws JsonProcessingException {
            assertThat(propertyNamesOf(withPeriod(ReportPeriod.MONTHLY)))
                    .as("the two positions the operator did not mark are omitted, not published as "
                            + "null and not published as a blank mark")
                    .containsExactly("monthlySelection", "keyAction");
            assertThat(propertyNamesOf(new ReportRequest(null, null, null, null, null, null, null, null, null,
                    null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("tolerates an unknown incoming property rather than rejecting the submission")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            String withStrayProperty = "{\"reportPeriod\":\"MONTHLY\",\"startMonth\":\"07\","
                    + "\"aPropertyTheContractNeverDeclared\":\"x\","
                    + "\"monthlySelection\":\"Y\"}";

            ReportRequest bound =
                    moduleEquivalentMapper().readValue(withStrayProperty, ReportRequest.class);

            assertThat(resolvedPeriodOf(bound)).isSameAs(ReportPeriod.MONTHLY);
            assertThat(bound.startMonth()).isEqualTo("07");
            assertThat(bound.confirm()).isNull();
            assertThat(bound.keyAction()).isNull();
            assertThat(bound.navigationContext()).isNull();
        }

        @Test
        @DisplayName("round-trips every value byte for byte, untrimmed, unpadded and un-case-folded")
        void roundTripsEveryValueByteForByte() throws JsonProcessingException {
            // Deliberately awkward: parts with a leading and with a trailing space, a part shorter than
            // its width, a wholly blank part, and a lower-case confirmation. Every one of them is a
            // submission the 3270 can produce.
            ReportRequest awkward = new ReportRequest(null, null, "Y", "7 ", " 1", "2022", "  ",
                    "19", "  22", "n", KeyAction.PFK03, navigation());

            ReportRequest returned = roundTripped(awkward);

            assertThat(resolvedPeriodOf(returned)).isSameAs(ReportPeriod.CUSTOM);
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
            assertThat(payloadOf(withPeriod(ReportPeriod.MONTHLY)).get("keyAction").asText())
                    .isEqualTo("ENTER");
            assertThat(payloadOf(new ReportRequest(null, null, null, null, null, null, null, null, null, null,
                    KeyAction.PFK03, null)).get("keyAction").asText())
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

            assertThat(request.monthlySelection()).isEqualTo(SELECTION_MARK);
            assertThat(request.yearlySelection()).isEqualTo(SELECTION_MARK);
            assertThat(request.customSelection()).isEqualTo(SELECTION_MARK);
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

            ReportRequest derived = new ReportRequest("Y", null, null, original.startMonth(),
                    original.startDay(), original.startYear(), original.endMonth(),
                    original.endDay(), original.endYear(), original.confirm(), original.keyAction(),
                    original.navigationContext());

            assertThat(original.yearlySelection())
                    .as("constructing the derived instance leaves every marker on the original where "
                            + "it was, including the two the derived instance clears")
                    .isEqualTo(SELECTION_MARK);
            assertThat(original.customSelection()).isEqualTo(SELECTION_MARK);
            assertThat(original.startMonth()).isSameAs(original.startMonth());
            assertThat(original.navigationContext()).isSameAs(original.navigationContext());
            assertThat(derived.yearlySelection()).isNull();
            assertThat(derived.customSelection()).isNull();
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

            assertThat(reference).isNotEqualTo(new ReportRequest("Y", null, null, "07", "01",
                    "2022", "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, "Y", null, "07", "01",
                    "2022", "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, null, "07", "01",
                    "2022", "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "08", "01",
                    "2022", "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "07", "02",
                    "2022", "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "07", "01",
                    "2021", "07", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "07", "01",
                    "2022", "08", "19", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "07", "01",
                    "2022", "07", "20", "2022", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "07", "01",
                    "2022", "07", "19", "2023", "Y", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "07", "01",
                    "2022", "07", "19", "2022", "N", KeyAction.ENTER, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "07", "01",
                    "2022", "07", "19", "2022", "Y", KeyAction.PFK03, navigation()));
            assertThat(reference).isNotEqualTo(new ReportRequest(null, null, "Y", "07", "01",
                    "2022", "07", "19", "2022", "Y", KeyAction.ENTER, other));
        }

        @Test
        @DisplayName("names the type and every one of its own component values when described")
        void namesTheTypeAndEveryOwnComponentValueWhenDescribed() {
            String described = new ReportRequest(null, null, "Y", "07", "01", "2022", "12", "31",
                    "2022", "n", KeyAction.PFK03, null).toString();

            assertThat(described).startsWith("ReportRequest[").endsWith("]");
            assertThat(described).contains("monthlySelection=null", "yearlySelection=null",
                    "customSelection=Y",
                    "startMonth=07", "startDay=01", "startYear=2022",
                    "endMonth=12", "endDay=31", "endYear=2022", "confirm=n", "keyAction=PFK03",
                    "navigationContext=null");
            assertThat(described)
                    .as("a rendering that named a resolved period would put a fragment of the ordered "
                            + "service evaluation on this boundary")
                    .doesNotContain(REMOVED_PERIOD_NAME);
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
            // every one of the ten bounded positions at once.
            assertThat(violatingPathsOf(withEveryTextComponent(EIGHTY_CHARACTERS)))
                    .containsExactlyInAnyOrderElementsOf(TEXT_COMPONENT_NAMES);
        }
    }
}
