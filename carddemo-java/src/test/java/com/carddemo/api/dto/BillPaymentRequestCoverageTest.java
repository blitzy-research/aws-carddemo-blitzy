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
import java.util.List;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BillPaymentRequest}, the request body of legacy transaction {@code CB00}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract only: the four declared components, the two published character widths,
 * the single kind of constraint the record declares, the wire form the configured serialisation
 * settings produce for it, and its diagnostic rendering. No validation cascade, no settlement rule
 * and no message text is exercised here, because none of those lives on this type.
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p>The two widths are restated as literals in this file - 11 for the account identifier and 1 for
 * the confirmation character - from the symbolic map {@code app/cpy-bms/COBIL00.CPY} and the mapset
 * {@code app/bms/COBIL00.bms}. They are deliberately not read out of the class under test: a test
 * that sources its expectation from the type it is testing asserts only self-consistency.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>This is a pure unit test. It starts no application context, opens no connection and launches
 * no container. Where the wire shape is the subject, the payload is produced by a mapper built in
 * this file to the four serialisation settings the module declares in
 * {@code src/main/resources/application.yml}: absent members omitted rather than written as null,
 * temporal values as ISO-8601 text rather than epoch numbers, unknown incoming members tolerated,
 * and decimals written in plain rather than scientific notation.
 *
 * <p>That mapper evidences the shape this type takes <em>under those settings</em>, and nothing
 * more. It is not evidence about the mapper a deployed instance holds, and no assertion below is
 * worded as though it were. {@link ApplicationJsonContractTest} - a sibling in this package, not a
 * file outside the boundary - supplies that half: it obtains the mapper from a real Spring context
 * in which the module's own configuration file has been read by the same configuration-data
 * machinery a running instance uses, asserts the four behaviours against the deployed object, and
 * exercises this type through it. An edit to the module's configuration file therefore fails that
 * test rather than silently invalidating this one.
 *
 * <h2>Both carried values are withheld from the diagnostic rendering</h2>
 *
 * <p>This type replaces the record contract's generated rendering, and the class documentation states
 * the reason for each of the two substitutions. The account identifier is withheld because it
 * identifies an account, and one instance exists per submission, so a generated rendering would put an
 * account identifier one interpolation away from every log line on the payment path - and, on the
 * settlement arm, beside the transaction the outbound contract names, which together say what that
 * specific account paid. The confirmation answer is withheld because it is operator input and a
 * rejection must never echo the value it rejected, which is the same reasoning decision log entry
 * D-16 records for the same component on the outbound contract for this screen.
 *
 * <p>What is retained is the attention key and the nested navigation state. The key is the operator's
 * navigation choice and on this screen it is the component that says whether the submission was a
 * settlement attempt, a return, a clear or an unmapped key - the single most useful thing a diagnostic
 * on this transaction can carry. The navigation state is printed by delegation because it withholds
 * its own identifying components; a test below proves the composite rendering discloses none of them,
 * so nesting does not become a disclosure path either.
 *
 * <p>Withholding is confined to the rendering. Both accessors return their component exactly as
 * supplied - the account identifier is compared to a stored key character for character, and the
 * confirmation character must reach the service intact so the program can name a value that is
 * neither acceptable answer - and a test below proves it.
 *
 * <p>Provenance: checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.
 */
@DisplayName("BillPaymentRequest :: bill-payment request contract of legacy transaction CB00")
class BillPaymentRequestCoverageTest {

    /** Declared width of the account-identifier field, restated from the symbolic map. */
    private static final int EXPECTED_ACCOUNT_ID_LENGTH = 11;

    /** Declared width of the confirmation field, restated from the symbolic map. */
    private static final int EXPECTED_CONFIRM_LENGTH = 1;

    /**
     * The four components in declaration order. A change to this list is a change to the REST
     * contract and has to be a deliberate one.
     */
    private static final List<String> EXPECTED_COMPONENTS =
            List.of("accountId", "confirm", "keyAction", "navigationContext");

    /** An account identifier at exactly the declared width. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * The stand-in the rendering substitutes for a withheld component.
     *
     * <p>Restated here rather than read reflectively from the production type. The marker is part of
     * what a reader of a log line sees, so an edit to it is an edit to an observable form and should
     * fail this test rather than be followed silently.
     */
    private static final String PLACEHOLDER = "***REDACTED***";

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
     * Builds a mapper configured to the four settings the module declares.
     *
     * <p>See the class documentation for what this does and does not evidence.
     *
     * @return a mapper carrying the module's declared serialisation settings
     */
    private static ObjectMapper declaredSettingsMapper() {
        return JsonContractSupport.declaredSettingsMapper();
    }

    /**
     * Serialises a request and parses the result back into a tree.
     *
     * @param request the request to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(BillPaymentRequest request) throws JsonProcessingException {
        ObjectMapper mapper = declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Builds a request carrying only the two screen values.
     *
     * @param accountId the account identifier
     * @param confirm the confirmation character
     * @return a request with no attention key and no navigation state
     */
    private static BillPaymentRequest screenValues(String accountId, String confirm) {
        return new BillPaymentRequest(accountId, confirm, null, null);
    }

    /**
     * Builds a string of a single repeated character.
     *
     * @param length the number of characters
     * @param filler the character to repeat
     * @return the repeated string
     */
    private static String repeated(int length, char filler) {
        return String.valueOf(filler).repeat(length);
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the four components are declared in the order the screen submits them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = java.util.Arrays.stream(
                            BillPaymentRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .as("the request contract is the ordered component list, so a reordering or an "
                            + "addition is a contract change")
                    .containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("the two published widths equal the widths the symbolic map declares")
        void publishedWidthsEqualTheMapWidths() {
            assertThat(BillPaymentRequest.ACCOUNT_ID_LENGTH)
                    .as("the account-identifier item of COBIL00 is eleven characters wide")
                    .isEqualTo(EXPECTED_ACCOUNT_ID_LENGTH);
            assertThat(BillPaymentRequest.CONFIRM_LENGTH)
                    .as("the confirmation item of COBIL00 is one character wide")
                    .isEqualTo(EXPECTED_CONFIRM_LENGTH);
        }

        @Test
        @DisplayName("both screen values are carried as characters, never as a number or a flag")
        void screenValuesAreCarriedAsCharacters() {
            RecordComponent[] components = BillPaymentRequest.class.getRecordComponents();

            assertThat(components[0].getType())
                    .as("the identifier is text so that its leading zeroes and its external width "
                            + "both survive the round trip")
                    .isEqualTo(String.class);
            assertThat(components[1].getType())
                    .as("the confirmation is one character rather than a two-state flag, because a "
                            + "third character has to stay reportable")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the attention key is the domain enumeration and the navigation state is the "
                + "shared record, so neither is a loose string")
        void keyAndNavigationAreTypedRatherThanTextual() {
            RecordComponent[] components = BillPaymentRequest.class.getRecordComponents();

            assertThat(components[2].getType()).isEqualTo(KeyAction.class);
            assertThat(components[3].getType()).isEqualTo(NavigationContext.class);
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a request at both declared widths reports no violation")
        void aRequestAtBothWidthsReportsNoViolation() {
            Set<ConstraintViolation<BillPaymentRequest>> violations =
                    validator.validate(screenValues(ACCOUNT_ID, "Y"));

            assertThat(violations)
                    .as("a value exactly at its bound is inside the bound, not over it")
                    .isEmpty();
        }

        @Test
        @DisplayName("an over-long account identifier is reported, and the value itself is left "
                + "exactly as supplied")
        void anOverLongAccountIdentifierIsReportedWithoutBeingAltered() {
            String tooLong = repeated(EXPECTED_ACCOUNT_ID_LENGTH + 1, '9');
            BillPaymentRequest request = screenValues(tooLong, "Y");

            Set<ConstraintViolation<BillPaymentRequest>> violations = validator.validate(request);

            assertThat(violations)
                    .as("exactly one bound applies to this component")
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("accountId");
            assertThat(request.accountId())
                    .as("the bound measures and never alters, because the legacy field is "
                            + "fixed-width and its padding is contract")
                    .isEqualTo(tooLong);
        }

        @Test
        @DisplayName("an over-long confirmation character is reported")
        void anOverLongConfirmationIsReported() {
            Set<ConstraintViolation<BillPaymentRequest>> violations =
                    validator.validate(screenValues(ACCOUNT_ID, "YN"));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("confirm");
        }

        @ParameterizedTest(name = "confirmation character \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"Y", "N", "y", "n", "X", "0", " ", "?"})
        @DisplayName("the confirmation bound restricts length only and never the character, so an "
                + "unacceptable third character reaches the service intact")
        void theConfirmationBoundRestrictsLengthOnly(String confirm) {
            assertThat(validator.validate(screenValues(ACCOUNT_ID, confirm)))
                    .as("the two acceptable characters are a message-bearing service rule; the "
                            + "boundary must not pre-empt the diagnostic that names the third")
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent, empty or blank value is accepted, because each is a state the "
                + "legacy screen genuinely produces")
        void absentEmptyAndBlankValuesAreAccepted() {
            assertThat(validator.validate(screenValues(null, null)))
                    .as("a first pass through the screen carries neither value")
                    .isEmpty();
            assertThat(validator.validate(screenValues("", "")))
                    .as("an empty identifier is precisely the state the emptiness diagnostic exists "
                            + "to report, so the boundary must let it through")
                    .isEmpty();
            assertThat(validator.validate(
                            screenValues(repeated(EXPECTED_ACCOUNT_ID_LENGTH, ' '), " ")))
                    .as("an all-blank fixed-width field is what the terminal transmits for an "
                            + "untouched item")
                    .isEmpty();
        }

        @Test
        @DisplayName("no presence, pattern, character-class or numeric-range constraint is declared "
                + "on either screen value")
        void onlyLengthBoundsAreDeclared() {
            BillPaymentRequest nonNumeric = screenValues("ABCDEFGHIJK", "!");

            assertThat(validator.validate(nonNumeric))
                    .as("every such rule would reject input the legacy transaction accepts, and "
                            + "each of the two checks that do exist is message-bearing and ordered")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("a fully populated request renders all four members under their contract names")
        void aFullyPopulatedRequestRendersAllFourMembers() throws JsonProcessingException {
            BillPaymentRequest request = new BillPaymentRequest(
                    ACCOUNT_ID, "Y", KeyAction.ENTER, NavigationContext.empty());

            JsonNode payload = payloadOf(request);

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
            assertThat(payload.get("keyAction").asText())
                    .as("an enumerated member crosses as its constant name; an ordinal would "
                            + "silently change meaning when a constant is inserted")
                    .isEqualTo("ENTER");
            assertThat(payload.get("navigationContext").isObject()).isTrue();
        }

        @Test
        @DisplayName("absent members are omitted rather than written as null")
        void absentMembersAreOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(screenValues(ACCOUNT_ID, null));

            assertThat(payload.has("accountId")).isTrue();
            assertThat(payload.has("confirm"))
                    .as("a screen field the operator did not touch must be absent rather than "
                            + "present and null, because the two are different states")
                    .isFalse();
            assertThat(payload.has("keyAction")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
            assertThat(payload.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty string is a value and is written, which is what distinguishes it "
                + "from an absent member")
        void anEmptyStringIsWrittenRatherThanOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(screenValues("", ""));

            assertThat(payload.get("accountId").asText()).isEmpty();
            assertThat(payload.get("confirm").asText()).isEmpty();
        }

        @Test
        @DisplayName("surrounding spaces survive the wire round trip, because the legacy items are "
                + "fixed-width and their padding is contract")
        void surroundingSpacesSurviveTheWireRoundTrip() throws JsonProcessingException {
            String padded = "  99999999  ";
            BillPaymentRequest request = new BillPaymentRequest(
                    padded.substring(0, EXPECTED_ACCOUNT_ID_LENGTH), " ", null, null);

            ObjectMapper mapper = declaredSettingsMapper();
            BillPaymentRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), BillPaymentRequest.class);

            assertThat(returned.accountId())
                    .isEqualTo(padded.substring(0, EXPECTED_ACCOUNT_ID_LENGTH));
            assertThat(returned.confirm()).isEqualTo(" ");
        }

        @Test
        @DisplayName("an unknown member a client echoes back is ignored rather than rejected")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String withExtra = "{\"accountId\":\"" + ACCOUNT_ID
                    + "\",\"confirm\":\"Y\",\"unheardOf\":\"whatever\"}";

            BillPaymentRequest returned =
                    declaredSettingsMapper().readValue(withExtra, BillPaymentRequest.class);

            assertThat(returned.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(returned.confirm()).isEqualTo("Y");
        }

        @Test
        @DisplayName("a request round trips through the wire unchanged in every component")
        void aRequestRoundTripsUnchanged() throws JsonProcessingException {
            BillPaymentRequest request = new BillPaymentRequest(
                    ACCOUNT_ID,
                    "N",
                    KeyAction.PFK03,
                    JsonContractSupport.populatedNavigation());

            ObjectMapper mapper = declaredSettingsMapper();
            BillPaymentRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), BillPaymentRequest.class);

            assertThat(returned)
                    .as("a request contract compares by value, so an unchanged round trip is "
                            + "component-by-component equality")
                    .isEqualTo(request);
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        /**
         * Both carried values are replaced by a fixed marker, and the substitution is unconditional.
         *
         * <p>The rendering is asserted whole rather than by fragments, because the shape is the
         * contract: a reader of a log line has to be able to tell that a component was withheld rather
         * than absent, and a marker in the position the component would have occupied is what says so.
         * The second assertion proves the substitution does not depend on the value - a differently
         * populated instance renders identically - which is what makes the control fail closed rather
         * than only for the values a test happened to choose.</p>
         */
        @Test
        @DisplayName("both screen values are withheld from the rendering, unconditionally")
        void theGeneratedRenderingIsReplaced() {
            String rendered = screenValues(ACCOUNT_ID, "Y").toString();

            assertThat(rendered)
                    .as("the account identifier identifies an account and the answer is operator "
                            + "input a rejection must not echo, so both are replaced in place")
                    .isEqualTo("BillPaymentRequest[accountId=" + PLACEHOLDER
                            + ", confirm=" + PLACEHOLDER
                            + ", keyAction=null, navigationContext=null]");
            assertThat(rendered)
                    .as("no carried value survives the substitution")
                    .doesNotContain(ACCOUNT_ID);
            assertThat(screenValues("00000099999", "N").toString())
                    .as("the substitution does not depend on the value, so the control fails closed")
                    .isEqualTo(rendered);
        }

        /**
         * The retained components are the attention key and the nested navigation state.
         *
         * <p>Naming what is retained is as much a part of the contract as naming what is withheld: a
         * diagnostic that withheld everything would be useless, and this assertion is what would fail
         * if a later change widened the withholding until the rendering no longer identified the
         * submission it belongs to.</p>
         */
        @Test
        @DisplayName("the attention key and the navigation state are retained, so the rendering "
                + "still identifies the submission")
        void theNavigationChoiceIsRetained() {
            String rendered = new BillPaymentRequest(
                    ACCOUNT_ID, "Y", KeyAction.PFK03, null).toString();

            assertThat(rendered)
                    .as("the key says whether this was a settlement attempt, a return, a clear or "
                            + "an unmapped key, and identifies nobody")
                    .contains("keyAction=" + KeyAction.PFK03)
                    .contains("navigationContext=null")
                    .startsWith("BillPaymentRequest[");
        }

        @Test
        @DisplayName("nesting the navigation state discloses none of its identifying values, so "
                + "printing it by delegation here is not a disclosure path")
        void nestingTheNavigationStateDisclosesNothingIdentifying() {
            BillPaymentRequest request = new BillPaymentRequest(
                    ACCOUNT_ID,
                    "Y",
                    KeyAction.ENTER,
                    JsonContractSupport.populatedNavigation());

            String rendered = request.toString();

            assertThat(rendered)
                    .as("the nested record withholds its own identifying components, and this type "
                            + "must not become the path by which they surface")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }

        @Test
        @DisplayName("every accessor returns its component exactly as supplied, so nothing is "
                + "masked anywhere outside the rendering path")
        void accessorsReturnTheirComponentsUnaltered() {
            String awkward = "  0000123  ".substring(0, EXPECTED_ACCOUNT_ID_LENGTH);
            BillPaymentRequest request = screenValues(awkward, " ");

            assertThat(request.accountId()).isEqualTo(awkward);
            assertThat(request.confirm()).isEqualTo(" ");
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @ParameterizedTest(name = "identifiers [{0}] and [{1}] are {2}")
        @CsvSource(value = {
            "00000000011|00000000011|equal",
            "00000000011|00000000012|different",
            "00000000011| 000000011 |different",
            "00000000011|0000000011 |different",
        }, delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
        @DisplayName("equality compares every component by value, folds no case and trims no space")
        void equalityComparesEveryComponentByValue(String left, String right, String verdict) {
            BillPaymentRequest first = screenValues(left, "Y");
            BillPaymentRequest second = screenValues(right, "Y");

            if ("equal".equals(verdict)) {
                assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            } else {
                assertThat(first)
                        .as("a shifted or re-padded identifier is a different identifier, because "
                                + "the browse compares it to a retrieved record character for "
                                + "character")
                        .isNotEqualTo(second);
            }
        }

        @Test
        @DisplayName("a request differing only in its confirmation character is a different request")
        void aDifferentConfirmationCharacterYieldsADifferentRequest() {
            assertThat(screenValues(ACCOUNT_ID, "Y"))
                    .as("accept, reject and quoted-back are three distinct outcomes, so the "
                            + "character itself is part of the request's identity")
                    .isNotEqualTo(screenValues(ACCOUNT_ID, "N"));
        }
    }
}
