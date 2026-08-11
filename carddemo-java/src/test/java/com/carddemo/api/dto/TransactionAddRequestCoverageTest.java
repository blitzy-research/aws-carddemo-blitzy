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
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionAddRequest}, the request body of legacy transaction {@code CT02}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the transaction-add submission: sixteen components, fourteen declared
 * widths, the wire form under the module's declared serialisation settings, the diagnostic rendering,
 * and the value fidelity the ordered service cascade depends on. The eleven emptiness checks, the
 * three numeric checks, the two calendar checks, the amount-shape check and every diagnostic are
 * ordered, message-bearing service rules and are exercised where they live.
 *
 * <h2>Where the expectations come from</h2>
 *
 * <p>Every width is restated as a literal in this file from the symbolic map
 * {@code app/cpy-bms/COTRN02.CPY} and the mapset {@code app/bms/COTRN02.bms}. Four of them are
 * <em>screen</em> widths rather than record widths and are deliberately the narrower value: the
 * description is 60 on the screen, the merchant name 30 and the merchant city 25, while the persisted
 * transaction record is wider. Carrying the screen width is what reproduces what the operator could
 * actually type.
 *
 * <h2>The amount travels as the typed lexeme, not as a parsed decimal</h2>
 *
 * <p>The screen item is twelve characters wide and the value crossing this contract is those twelve
 * characters, bounded at twelve and nothing more. Parsing it here would be the wrong place: the add
 * program tests the shape itself and its diagnostic names the external form with two decimal places in
 * exact legacy wording, so a boundary that refused a malformed amount as a binding failure would
 * replace that message with a generic one and would lose the operator's keystrokes along with it.
 * Carrying the lexeme is also what preserves a leading sign, a leading zero and a padded blank, all of
 * which a decimal parse discards. Tests below pin that the component is characters, that the only rule
 * on it is the screen width, that a value no parser would accept still crosses, and that the twelve
 * characters survive the wire unchanged.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>A pure unit test: no context, no connection, no container. Payloads come from
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in this package where the
 * module's four declared serialisation settings are written by hand. That evidences the shape this
 * type takes under those settings and makes no claim about the mapper a deployed instance holds;
 * {@link ApplicationJsonContractTest} is the in-boundary evidence for the deployed object.
 *
 * <p>No legacy source text is reproduced.
 */
@DisplayName("TransactionAddRequest :: transaction-add request contract of legacy transaction CT02")
class TransactionAddRequestCoverageTest {

    /** The sixteen components in the order the screen presents the items they mirror. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "accountId", "cardNumber", "typeCode", "categoryCode", "transactionSource",
            "description", "amount", "originationDate", "processingDate", "merchantId",
            "merchantName", "merchantCity", "merchantZip", "confirm",
            "keyAction", "navigationContext");

    /** The declared width of each bounded component, restated from the symbolic map. */
    private static final Map<String, Integer> EXPECTED_WIDTHS = expectedWidths();

    /** The amount component, whose only rule is the screen width the operator typed into. */
    private static final String AMOUNT_COMPONENT = "amount";

    /** An amount exactly as an operator types it, at the screen width. */
    private static final String AMOUNT_LEXEME = "-00000123.45";

    /** The fixed marker the rendering emits in place of each withheld component. */
    private static final String EXPECTED_PLACEHOLDER = "***REDACTED***";

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
     * Restates the fourteen declared widths, in screen order.
     *
     * <p>Ordered so that a reader can compare the sequence against the map without re-sorting, and
     * built by hand rather than read from the class under test.
     *
     * @return the component-to-width table
     */
    private static Map<String, Integer> expectedWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("accountId", 11);
        widths.put("cardNumber", 16);
        widths.put("typeCode", 2);
        widths.put("categoryCode", 4);
        widths.put("transactionSource", 10);
        widths.put("description", 60);
        widths.put("amount", 12);
        widths.put("originationDate", 10);
        widths.put("processingDate", 10);
        widths.put("merchantId", 9);
        widths.put("merchantName", 30);
        widths.put("merchantCity", 25);
        widths.put("merchantZip", 10);
        widths.put("confirm", 1);
        return Map.copyOf(widths);
    }

    /**
     * Supplies the fourteen bounded components with their declared widths.
     *
     * @return one argument pair per bounded component
     */
    static List<org.junit.jupiter.params.provider.Arguments> boundedComponents() {
        return expectedWidths().entrySet().stream()
                .map(entry -> org.junit.jupiter.params.provider.Arguments.of(
                        entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Builds a request whose every bounded component sits exactly at its declared width.
     *
     * @return a request at the declared widths
     */
    private static TransactionAddRequest atDeclaredWidths() {
        return new TransactionAddRequest(
                "9".repeat(11),
                "9".repeat(16),
                "9".repeat(2),
                "9".repeat(4),
                "A".repeat(10),
                "A".repeat(60),
                "-99999999.99",
                "2022-06-10",
                "2022-06-11",
                "9".repeat(9),
                "A".repeat(30),
                "A".repeat(25),
                "9".repeat(10),
                "Y",
                KeyAction.ENTER,
                NavigationContext.empty());
    }

    /**
     * Builds a request carrying only the named component, so a violation can be attributed.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a request carrying that one value
     */
    private static TransactionAddRequest carrying(String component, String value) {
        return new TransactionAddRequest(
                "accountId".equals(component) ? value : null,
                "cardNumber".equals(component) ? value : null,
                "typeCode".equals(component) ? value : null,
                "categoryCode".equals(component) ? value : null,
                "transactionSource".equals(component) ? value : null,
                "description".equals(component) ? value : null,
                "amount".equals(component) ? value : null,
                "originationDate".equals(component) ? value : null,
                "processingDate".equals(component) ? value : null,
                "merchantId".equals(component) ? value : null,
                "merchantName".equals(component) ? value : null,
                "merchantCity".equals(component) ? value : null,
                "merchantZip".equals(component) ? value : null,
                "confirm".equals(component) ? value : null,
                null,
                null);
    }

    /**
     * Builds a request carrying only the supplied amount lexeme.
     *
     * @param amount the amount exactly as the operator typed it
     * @return a request carrying that amount and nothing else
     */
    private static TransactionAddRequest carryingAmount(String amount) {
        return new TransactionAddRequest(null, null, null, null, null, null, amount,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Serialises a request and parses the result back into a tree.
     *
     * @param request the request to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(TransactionAddRequest request) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the sixteen components are declared in screen order, the amount seventh where "
                + "the map presents it")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(
                            TransactionAddRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @ParameterizedTest(name = "{0} is bounded at {1}")
        @MethodSource("com.carddemo.api.dto.TransactionAddRequestCoverageTest#boundedComponents")
        @DisplayName("each bounded component declares exactly the width the symbolic map declares")
        void eachBoundedComponentDeclaresItsMapWidth(String component, int expectedWidth)
                throws NoSuchFieldException {
            Size bound = TransactionAddRequest.class.getDeclaredField(component)
                    .getAnnotation(Size.class);

            assertThat(bound).as("%s mirrors a fixed-width map item", component).isNotNull();
            assertThat(bound.max()).isEqualTo(expectedWidth);
        }

        @Test
        @DisplayName("the amount is characters bounded only at the screen width, because the shape "
                + "rule is a message-bearing service check the boundary must not pre-empt")
        void theAmountIsCharactersBoundedOnlyAtTheScreenWidth() throws NoSuchFieldException {
            assertThat(TransactionAddRequest.class.getDeclaredField(AMOUNT_COMPONENT).getType())
                    .as("the twelve characters the operator typed, not a parse of them: a parse here "
                            + "would discard a leading sign, a leading zero and a padded blank, and "
                            + "would replace the legacy diagnostic with a binding failure")
                    .isEqualTo(String.class);
            assertThat(Arrays.stream(TransactionAddRequest.class
                                    .getDeclaredField(AMOUNT_COMPONENT).getDeclaredAnnotations())
                            .filter(annotation -> annotation.annotationType().getPackageName()
                                    .startsWith("jakarta.validation"))
                            .map(annotation -> annotation.annotationType().getSimpleName()))
                    .as("one rule and one only: the width of the screen field it was typed into. Only"
                            + " validation annotations are counted, because only a validation"
                            + " annotation can pre-empt the service's message; a binding annotation"
                            + " states which JSON shapes reach the component and imposes no rule")
                    .containsExactly("Size");
            assertThat(TransactionAddRequest.class.getDeclaredField(AMOUNT_COMPONENT)
                            .getAnnotation(Size.class).max())
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("no component anywhere on the record is a decimal or a floating-point type, so "
                + "nothing is parsed at this boundary")
        void noComponentIsANumericType() {
            assertThat(Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                            .map(RecordComponent::getType)
                            .filter(type -> type.equals(BigDecimal.class) || type.equals(double.class)
                                    || type.equals(Double.class) || type.equals(Long.class)
                                    || type.equals(Integer.class)))
                    .as("a parse at the boundary would reject a malformed value the add screen must "
                            + "report on itself, in its own words")
                    .isEmpty();
        }

        @Test
        @DisplayName("the three narrower widths are the screen widths rather than the record widths, "
                + "which is what reproduces what the operator could type")
        void theThreeNarrowerWidthsAreScreenWidths() {
            assertThat(EXPECTED_WIDTHS)
                    .containsEntry("description", 60)
                    .containsEntry("merchantName", 30)
                    .containsEntry("merchantCity", 25);
        }

        @Test
        @DisplayName("every screen value including the amount is carried as characters, so no "
                + "leading zero and no padded blank is lost")
        void everyScreenValueIsCarriedAsCharacters() {
            for (RecordComponent component : TransactionAddRequest.class.getRecordComponents()) {
                String name = component.getName();
                if (EXPECTED_WIDTHS.containsKey(name)) {
                    assertThat(component.getType())
                            .as("%s is a fixed-width screen item", name)
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("the attention key is the domain enumeration with no default, and the "
                + "navigation state is the shared record")
        void theTransportComponentsAreTyped() {
            RecordComponent[] components = TransactionAddRequest.class.getRecordComponents();

            assertThat(components[14].getType()).isEqualTo(KeyAction.class);
            assertThat(components[15].getType()).isEqualTo(NavigationContext.class);
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a request whose every component sits exactly at its declared width reports no "
                + "violation")
        void aRequestAtEveryDeclaredWidthReportsNoViolation() {
            assertThat(validator.validate(atDeclaredWidths())).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @MethodSource("com.carddemo.api.dto.TransactionAddRequestCoverageTest#boundedComponents")
        @DisplayName("each bounded component reports a value one character over its width and leaves "
                + "the value exactly as supplied")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            String tooLong = "X".repeat(width + 1);
            TransactionAddRequest request = carrying(component, tooLong);

            Set<ConstraintViolation<TransactionAddRequest>> violations =
                    validator.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @Test
        @DisplayName("an entirely absent request reports no violation, because the eleven emptiness "
                + "checks are ordered service rules with exact legacy wording")
        void anEntirelyAbsentRequestReportsNoViolation() {
            assertThat(validator.validate(new TransactionAddRequest(
                            null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null)))
                    .as("Bean Validation reports in an unspecified order under its own messages, so "
                            + "it cannot reproduce a first-error-wins cascade")
                    .isEmpty();
        }

        @ParameterizedTest(name = "an amount lexeme of {0} is accepted by the boundary")
        @ValueSource(strings = {
            "0", "0.00", "1", "1.5", "1.234567", "-0.01", "-99999999.99",
            "99999999.99", "  ", "-000001AB.45", "*00000123.45", "1E+9",
        })
        @DisplayName("the amount boundary accepts any twelve characters at all, because the external "
                + "shape rule is a message-bearing service check")
        void theAmountBoundaryAcceptsAnyLexemeAtItsWidth(String literal) {
            assertThat(validator.validate(carryingAmount(literal)))
                    .as("the diagnostic that names the external form belongs to the service, and "
                            + "the boundary must not pre-empt it - not even for a value no numeric "
                            + "parser would accept")
                    .isEmpty();
        }

        @Test
        @DisplayName("an amount one character over the screen width is reported, which is the one "
                + "amount rule the boundary does carry")
        void anAmountOverTheScreenWidthIsReported() {
            assertThat(validator.validate(carryingAmount("9".repeat(13))))
                    .as("the operator could not have typed a thirteenth character into a "
                            + "twelve-character field")
                    .hasSize(1);
            assertThat(validator.validate(carryingAmount("9".repeat(12))))
                    .as("a value exactly at the width is inside the bound")
                    .isEmpty();
        }

        @ParameterizedTest(name = "a non-numeric code \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"AB", "  ", "0A", "-1"})
        @DisplayName("the type-code bound restricts width only, because the numeric check runs at a "
                + "later stage of the cascade than the emptiness check")
        void theTypeCodeBoundRestrictsWidthOnly(String typeCode) {
            assertThat(validator.validate(carrying("typeCode", typeCode))).isEmpty();
        }

        @ParameterizedTest(name = "a malformed date \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"2022/06/10", "10-06-2022", "2022-13-45", "          ", "2022-6-1"})
        @DisplayName("neither date bound expresses a shape or a calendar rule, because the shape "
                + "check and the calendar check are separate ordered stages with separate messages")
        void neitherDateBoundExpressesAShapeOrCalendarRule(String date) {
            assertThat(validator.validate(carrying("originationDate", date))).isEmpty();
            assertThat(validator.validate(carrying("processingDate", date))).isEmpty();
        }

        @ParameterizedTest(name = "confirmation character \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"Y", "N", "y", "n", "Q", " "})
        @DisplayName("the confirmation bound restricts width only, so a value that is neither yes "
                + "nor no stays reportable")
        void theConfirmationBoundRestrictsWidthOnly(String confirm) {
            assertThat(validator.validate(carrying("confirm", confirm))).isEmpty();
        }

        @Test
        @DisplayName("no presence constraint exists anywhere, because a lookup by account or by "
                + "card are two alternative first branches and neither key is mandatory")
        void noPresenceConstraintExistsAnywhere() {
            assertThat(validator.validate(carrying("cardNumber", "9".repeat(16))))
                    .as("a submission carrying only the card number is a legitimate first branch")
                    .isEmpty();
            assertThat(validator.validate(carrying("accountId", "9".repeat(11))))
                    .as("a submission carrying only the account identifier is the other one")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("a fully populated request renders all sixteen members under their contract "
                + "names")
        void aFullyPopulatedRequestRendersAllSixteenMembers() throws JsonProcessingException {
            TransactionAddRequest request = new TransactionAddRequest(
                    "00000000011", "4111111111111111", "01", "0001", "POS TERM  ",
                    "Purchase at merchant", "123.45", "2022-06-10", "2022-06-11",
                    "800000000", "Merchant", "City", "12345", "Y",
                    KeyAction.ENTER, JsonContractSupport.populatedNavigation());

            JsonNode payload = payloadOf(request);

            assertThat(payload.size()).isEqualTo(EXPECTED_COMPONENTS.size());
            assertThat(payload.get("typeCode").asText()).isEqualTo("01");
            assertThat(payload.get("categoryCode").asText()).isEqualTo("0001");
            assertThat(payload.get("transactionSource").asText())
                    .as("the ten-character source is space padded on the screen and the padding "
                            + "crosses the wire with it")
                    .isEqualTo("POS TERM  ");
            assertThat(payload.get("keyAction").asText()).isEqualTo("ENTER");
        }

        @Test
        @DisplayName("absent members are omitted rather than written as null")
        void absentMembersAreOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("merchantZip", "12345"));

            assertThat(payload.size()).isEqualTo(1);
            assertThat(payload.get("merchantZip").asText()).isEqualTo("12345");
        }

        @ParameterizedTest(name = "an amount lexeme of {0} crosses the wire as those characters")
        @ValueSource(strings = {"123.45", "0.00", "-0.01", "1E+9", "1.20", "-00000123.45", "   "})
        @DisplayName("the amount crosses the wire as the characters it was given, quoted, because a "
                + "consumer of a fixed-width monetary field reads the operator's keystrokes")
        void theAmountCrossesTheWireAsItsOwnCharacters(String literal)
                throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(carryingAmount(literal));

            assertThat(rendered)
                    .as("the assertion is deliberately made against the emitted characters: had the "
                            + "amount been a decimal, an exponent form and a dropped trailing zero "
                            + "would both be possible, and neither is expressible here")
                    .isEqualTo("{\"amount\":\"" + literal + "\"}");
        }

        @Test
        @DisplayName("an amount survives the wire round trip character for character, trailing zero, "
                + "leading sign and padded blank included")
        void anAmountSurvivesTheRoundTripCharacterForCharacter() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();

            for (String literal : List.of("1.20", "-00000123.45", "00000123.45", " 1.5 ")) {
                TransactionAddRequest returned = mapper.readValue(
                        mapper.writeValueAsString(carryingAmount(literal)),
                        TransactionAddRequest.class);

                assertThat(returned.amount())
                        .as("the persisted field is a two-decimal zoned field, and the codec that "
                                + "writes it needs the operator's characters rather than a value "
                                + "some parser at this boundary decided they meant")
                        .isEqualTo(literal);
            }
        }

        @Test
        @DisplayName("a fully populated request round trips unchanged in every component")
        void aFullyPopulatedRequestRoundTripsUnchanged() throws JsonProcessingException {
            TransactionAddRequest request = atDeclaredWidths();

            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            TransactionAddRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), TransactionAddRequest.class);

            assertThat(returned).isEqualTo(request);
        }

        @Test
        @DisplayName("an unknown member a client echoes back is ignored rather than rejected, which "
                + "is what lets a client echo a whole response back as the next request")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"typeCode\":\"01\",\"newTransactionId\":\"0000000000000001\","
                    + "\"message\":\"Confirm to add this transaction...\"}";

            TransactionAddRequest returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, TransactionAddRequest.class);

            assertThat(returned.typeCode()).isEqualTo("01");
        }
    }

    @Nested
    @DisplayName("Value fidelity and diagnostics")
    class ValueFidelityAndDiagnostics {

        @Test
        @DisplayName("no component is trimmed, padded or folded on the way in")
        void noComponentIsNormalised() {
            TransactionAddRequest request = new TransactionAddRequest(
                    " 0000000011", "4111111111111111 ".substring(0, 16), " 1", "1   ",
                    "pos term  ", "  leading and trailing  ", " 1.5 ",
                    " 2022-06-10", "2022-06-11 ", " 80000000", "  m  ", "  c  ", " 12345    ",
                    " ", null, null);

            assertThat(request.accountId()).isEqualTo(" 0000000011");
            assertThat(request.typeCode()).isEqualTo(" 1");
            assertThat(request.categoryCode()).isEqualTo("1   ");
            assertThat(request.transactionSource()).isEqualTo("pos term  ");
            assertThat(request.description()).isEqualTo("  leading and trailing  ");
            assertThat(request.amount())
                    .as("the amount is stored as supplied; no parse, no scaling, no rounding and no "
                            + "trim happens at this boundary")
                    .isEqualTo(" 1.5 ");
            assertThat(request.confirm()).isEqualTo(" ");
        }

        @Test
        @DisplayName("two amount lexemes that a parser would call equal are different requests, "
                + "which is what makes a lost trailing zero or a stripped sign detectable")
        void twoAmountLexemesAParserWouldEquateAreDifferentRequests() {
            assertThat(carryingAmount("1.2"))
                    .as("string equality is character-for-character, and the characters are the "
                            + "contract here: a decimal component would call these two the same "
                            + "value and lose the difference")
                    .isNotEqualTo(carryingAmount("1.20"));
            assertThat(carryingAmount("00000123.45")).isNotEqualTo(carryingAmount("123.45"));
            assertThat(carryingAmount("+123.45")).isNotEqualTo(carryingAmount("123.45"));
        }

        @Test
        @DisplayName("the rendering withholds the account, the card, the amount, the description and "
                + "the four merchant items, and retains the screen-interaction state")
        void theRenderingWithholdsWhatIdentifiesTheTransaction() {
            TransactionAddRequest request = new TransactionAddRequest(
                    "00000000011", "4111111111111111", "01", "0001", "POS TERM  ", "Purchase",
                    AMOUNT_LEXEME, "2022-06-10", "2022-06-11", "800000000", "M", "C",
                    "12345", "Y", KeyAction.ENTER, JsonContractSupport.populatedNavigation());

            assertThat(request.toString())
                    .as("a diagnostic that printed the card, the amount and the merchant together "
                            + "would put one person's purchase in a log file")
                    .startsWith("TransactionAddRequest[")
                    .contains("accountId=" + EXPECTED_PLACEHOLDER)
                    .contains("cardNumber=" + EXPECTED_PLACEHOLDER)
                    .contains("description=" + EXPECTED_PLACEHOLDER)
                    .contains("amount=" + EXPECTED_PLACEHOLDER)
                    .contains("merchantId=" + EXPECTED_PLACEHOLDER)
                    .contains("merchantName=" + EXPECTED_PLACEHOLDER)
                    .contains("merchantCity=" + EXPECTED_PLACEHOLDER)
                    .contains("merchantZip=" + EXPECTED_PLACEHOLDER)
                    .doesNotContain("4111111111111111")
                    .doesNotContain(AMOUNT_LEXEME)
                    .doesNotContain("00000000011");
            assertThat(request.toString())
                    .as("the codes, the two dates, the confirmation and the attention key name the "
                            + "turn rather than the person, and are what make a diagnostic useful")
                    .contains("typeCode=01")
                    .contains("categoryCode=0001")
                    .contains("transactionSource=POS TERM  ")
                    .contains("originationDate=2022-06-10")
                    .contains("processingDate=2022-06-11")
                    .contains("confirm=Y")
                    .contains("keyAction=ENTER");
        }

        @Test
        @DisplayName("the placeholder does not vary with the value, so neither a length nor a prefix "
                + "of a withheld component is recoverable")
        void thePlaceholderDoesNotVaryWithTheValue() {
            assertThat(carryingAmount("1").toString())
                    .as("a truncated amount beside a retained merchant and date is still a "
                            + "reconstruction of one purchase")
                    .isEqualTo(carryingAmount(AMOUNT_LEXEME).toString());
        }

        @Test
        @DisplayName("nesting the navigation state discloses none of its identifying values, so the "
                + "nested contract cannot become the path by which they surface")
        void nestingTheNavigationStateDisclosesNothingIdentifying() {
            TransactionAddRequest request = new TransactionAddRequest(
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, KeyAction.ENTER, JsonContractSupport.populatedNavigation());

            assertThat(request.toString())
                    .contains("navigationContext=NavigationContext[")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }
    }
}
