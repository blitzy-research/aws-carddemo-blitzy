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
import java.util.stream.Collectors;

import com.carddemo.domain.enums.KeyAction;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link BillPaymentRequest}, the request body of legacy CICS transaction
 * {@code CB00}.
 *
 * <p>A pure unit test. No application context, no connection, no container, no broker and no
 * run-time introspection: every property below is established either by compile-time binding or by
 * observing behaviour. Nothing under {@code app/} is opened at run time - the legacy estate is cited
 * by member name, field width and line number only.
 *
 * <h2>What this file pins</h2>
 *
 * <p>The request surface, the delegation of every field rule to the service tier, and the three
 * absences that a plausible re-reading of the screen would quietly turn into features.
 *
 * <p><strong>The surface is two operator-typed values plus two protocol components.</strong> The
 * symbolic map {@code app/cpy-bms/COBIL00.CPY} declares ten value items and its output group
 * redefines its input group, so every item appears on both sides at identical width. That parity is a
 * code-generation property of every symbolic map in the estate rather than a statement about
 * direction, and reading it as permission to accept all ten inbound is the easiest mistake available
 * here. Seven of the excluded eight are plainly server-produced screen furniture - the transaction
 * name, two title lines, the program name, the current date and time, and the seventy-eight-character
 * diagnostic line. The eighth is the interesting one and is treated at length below.
 *
 * <p><strong>The fourteen-character balance field is not an input, and the mapset proves it
 * independently of intent.</strong> The map declares the field on the input side for the parity
 * reason above, but {@code app/bms/COBIL00.bms} defines it auto-skip at line 103, so the terminal
 * cursor could never enter it and no operator could ever type into it. The two fields this record
 * does carry are defined unprotected at lines 85 and 115. Accepting the balance inbound would hand
 * the caller control over how much is settled, which the legacy transaction never permits at any
 * width: it always settles the whole outstanding total read from the account record. There is no
 * partial-settlement feature to model, so {@link NoAmountComponent} asserts the absence rather than
 * asserting a type - and asserts it on the wire, where a smuggled amount would actually have to
 * arrive.
 *
 * <p><strong>Nothing here validates.</strong> The legacy checks are message-bearing and strictly
 * ordered - the emptiness test at line 161 of {@code app/cbl/COBIL00C.cbl} runs first and ends the
 * pass, and the confirmation test at line 187 runs only afterwards - whereas Bean Validation reports
 * violations in an unspecified order under its own messages. A declarative constraint could not
 * reproduce a first-error-wins cascade carrying exact legacy text, so {@link DelegatedValidation}
 * asserts that the record reports nothing at all for every input the legacy accepts and every input
 * the legacy rejects alike. The single upper bound each carried value declares measures and never
 * alters, so padding survives it untouched.
 *
 * <h2>How the absence of an annotation is established without introspection</h2>
 *
 * <p>Asking the class which annotations it carries would mean introspecting its declared fields at
 * run time, which this module does not do anywhere: the introspection budget for the migration is
 * zero, and it is that budget which forces the hand-written record mappers elsewhere in the codebase.
 * The properties are therefore established behaviourally, which is the stronger claim in any case. A
 * presence,
 * pattern or range constraint that fires on no null, no blank, no non-numeric identifier and no
 * unexpected confirmation character is indistinguishable from one that is not declared, and every
 * such constraint fires on at least one of those inputs. Bounds are established the same way, by
 * measuring where the reported violation appears rather than by reading the bound back off the
 * annotation.
 */
@DisplayName("BillPaymentRequest :: bill-payment request contract of legacy transaction CB00")
class BillPaymentRequestTest {

    /**
     * The complete property inventory, in the order the symbolic map declares the two operator fields
     * and then the two protocol components.
     */
    private static final List<String> ALL_PROPERTIES =
            List.of("accountId", "confirm", "keyAction", "navigationContext");

    /** An eleven-character account identifier whose leading zeros are contract. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The affirmative confirmation character, upper case as the operator most often types it. */
    private static final String CONFIRM = "Y";

    /** The fixed stand-in the record emits for each withheld component. */
    private static final String REDACTED = "***REDACTED***";

    /**
     * Builds a navigation state that is wholly within its own declared bounds.
     *
     * <p>Sixteen components, in the order the record declares them. The seventh is the nested
     * program-context enumeration rather than text.
     *
     * @return a valid echoed navigation state
     */
    private static NavigationContext navigation() {
        return new NavigationContext("CB00", "COBIL00C", "CB00", "COBIL00C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", "4111111111111111", "COBIL0A", "COBIL00");
    }

    /**
     * Builds a fully populated request whose every component is present and in bounds.
     *
     * @return a settlement submission carrying both operator values, the enter key and the echoed
     *     navigation state
     */
    private static BillPaymentRequest populated() {
        return new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, navigation());
    }

    /**
     * Builds a mapper configured exactly as {@code src/main/resources/application.yml} configures the
     * module's own, so that a shape asserted here is the shape the module actually emits and accepts.
     *
     * <p>All six settings the module declares are mirrored, including the two the contract depends on
     * most sharply. Numbers are refused for enumerated components, because no attention identifier is
     * an ordinal index - each is a named byte and the vocabulary has no catch-all - so a bare number
     * is never a value an operator could have produced. A fractional number is refused for an
     * integral component, because a silently truncated value is a different instruction.
     *
     * <p>Built locally rather than injected: a slicing annotation would start a container, and this
     * test owns no context.
     *
     * @return a mapper equivalent to the module's own
     */
    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    /**
     * Serializes a request and reads it back as a tree, so the emitted shape can be inspected.
     *
     * @param request the request to serialize
     * @return the emitted payload as a tree
     * @throws JsonProcessingException if the payload cannot be written or re-read
     */
    private static JsonNode payloadOf(BillPaymentRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Returns the property names a request actually emits.
     *
     * <p>Read from the emitted payload rather than from the class, so no class-metadata query is made
     * from this file. What reaches a caller is exactly this set, which makes it the right place to
     * assert both the inventory and every absence.
     *
     * @param request the request to serialize
     * @return the emitted property names
     * @throws JsonProcessingException if the payload cannot be written or re-read
     */
    private static Set<String> propertiesOf(BillPaymentRequest request)
            throws JsonProcessingException {
        return payloadOf(request).properties().stream().map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Validates a request with a stock validator built from the default provider.
     *
     * <p>Deliberately not a framework-managed validator bean: obtaining one would require a context.
     * The factory is closed after use so the provider releases its resources.
     *
     * @param request the request to validate
     * @return every reported violation, which for this contract is expected to be none
     */
    private static Set<ConstraintViolation<BillPaymentRequest>> violationsOf(
            BillPaymentRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /**
     * Returns the property paths of every violation reported for a request.
     *
     * @param request the request to validate
     * @return the reported paths, empty when the request reports nothing
     */
    private static List<String> violationPathsOf(BillPaymentRequest request) {
        return violationsOf(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .toList();
    }

    /**
     * Builds a request carrying only an account identifier, leaving every other component absent.
     *
     * @param accountId the identifier to carry
     * @return a request with one component present
     */
    private static BillPaymentRequest withAccountId(String accountId) {
        return new BillPaymentRequest(accountId, null, null, null);
    }

    /**
     * Builds a request carrying only a confirmation character, leaving every other component absent.
     *
     * @param confirm the character to carry
     * @return a request with one component present
     */
    private static BillPaymentRequest withConfirm(String confirm) {
        return new BillPaymentRequest(null, confirm, null, null);
    }

    @Nested
    @DisplayName("request surface")
    class RequestSurface {

        @Test
        @DisplayName("emits exactly four properties and no fifth")
        void emitsExactlyFourProperties() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("the request surface is the two operator-typed fields of map COBIL00 plus "
                            + "the attention key and the echoed navigation state, and nothing else")
                    .containsExactlyInAnyOrderElementsOf(ALL_PROPERTIES)
                    .hasSize(4);
        }

        @Test
        @DisplayName("carries no screen furniture the program writes outbound")
        void carriesNoScreenFurniture() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("the transaction name, both title lines, the program name, the current date "
                            + "and time and the seventy-eight-character diagnostic line of map "
                            + "COBIL00 are server-produced and belong to the response")
                    .doesNotContain("trnName", "transactionName", "title01", "title02", "titleLine1",
                            "titleLine2", "pgmName", "programName", "curDate", "currentDate",
                            "curTime", "currentTime", "errMsg", "errorMessage", "infoMessage",
                            "functionKeys");
        }

        @Test
        @DisplayName("models no generated 3270 plumbing from the symbolic map")
        void modelsNoTerminalPlumbing() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("the per-field length, flag, colour, highlight and attribute items and the "
                            + "leading twelve-byte terminal input/output area filler of the symbolic "
                            + "map are code generation rather than contract")
                    .doesNotContain("filler", "tioa", "length", "flag", "attribute", "colour",
                            "color", "highlight", "position", "row", "column", "cursor");
        }

        @Test
        @DisplayName("binds the two operator values as text and the key as its enumeration")
        void bindsEachComponentAtItsDeclaredType() {
            BillPaymentRequest request = populated();

            String accountId = request.accountId();
            String confirm = request.confirm();
            KeyAction keyAction = request.keyAction();
            NavigationContext navigationContext = request.navigationContext();

            assertThat(accountId).isEqualTo(ACCOUNT_ID);
            assertThat(confirm).isEqualTo(CONFIRM);
            assertThat(keyAction).isEqualTo(KeyAction.ENTER);
            assertThat(navigationContext).isEqualTo(navigation());
        }

        @Test
        @DisplayName("publishes both widths the symbolic map declares")
        void publishesBothDeclaredWidths() {
            assertThat(BillPaymentRequest.ACCOUNT_ID_LENGTH)
                    .as("width of the account-id item at line 60 of app/cpy-bms/COBIL00.CPY")
                    .isEqualTo(11);
            assertThat(BillPaymentRequest.CONFIRM_LENGTH)
                    .as("width of the confirmation item at line 72 of app/cpy-bms/COBIL00.CPY")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("names every property after its own component, with no rename on the wire")
        void namesEveryPropertyAfterItsComponent() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("confirm").asText()).isEqualTo(CONFIRM);
            assertThat(payload.get("keyAction").asText()).isEqualTo(KeyAction.ENTER.name());
            assertThat(payload.get("navigationContext").isObject()).isTrue();
        }
    }

    @Nested
    @DisplayName("the balance field is not a request component")
    class NoAmountComponent {

        @Test
        @DisplayName("emits no amount property under any name the screen or the record might suggest")
        void emitsNoAmountProperty() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("the fourteen-character balance field of map COBIL00 is defined auto-skip at "
                            + "line 103 of app/bms/COBIL00.bms, so no operator could type into it; "
                            + "it is server-produced, display-only and carried by the response")
                    .doesNotContain("curBal", "currentBalance", "balance", "amount", "payAmount",
                            "paymentAmount", "settledAmount", "outstandingBalance", "creditLimit",
                            "cashCreditLimit", "currentCycleCredit", "currentCycleDebit");
        }

        @Test
        @DisplayName("emits no numeric property at all, so no amount can arrive as a number")
        void emitsNoNumericProperty() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.properties())
                    .as("every value this request carries is text, an enumerated name or the nested "
                            + "object; a numeric property could only be a quantity, and the request "
                            + "carries no quantity")
                    .allSatisfy(property -> assertThat(property.getValue().isNumber())
                            .as("property %s is not a number", property.getKey())
                            .isFalse());
        }

        @Test
        @DisplayName("ignores an inbound balance instead of binding it")
        void ignoresAnInboundBalance() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            String body = """
                    {"accountId":"00000000011","confirm":"Y","keyAction":"ENTER",\
                    "curBal":"0000000123.45","currentBalance":"0000000123.45","amount":123.45}""";

            BillPaymentRequest bound = mapper.readValue(body, BillPaymentRequest.class);

            assertThat(bound)
                    .as("an amount offered by a caller is discarded rather than honoured, because "
                            + "the legacy transaction always settles the whole outstanding total "
                            + "read from the account record and has no partial-settlement feature")
                    .isEqualTo(new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, null));
            assertThat(propertiesOf(bound)).containsExactlyInAnyOrder("accountId", "confirm",
                    "keyAction");
        }

        @Test
        @DisplayName("runs no sign test, so the refusal on a non-positive balance stays in the "
                + "service")
        void runsNoSignTest() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("the refusal reported at line 201 of app/cbl/COBIL00C.cbl fires only when "
                            + "the stored total is non-positive and the identifier is non-blank, "
                            + "tested together at line 198; the request holds neither the total nor "
                            + "the outcome")
                    .doesNotContain("nonPositive", "payable", "settleable", "refused", "rejected",
                            "nothingToPay");
        }
    }

    @Nested
    @DisplayName("account identifier")
    class AccountIdentifier {

        @Test
        @DisplayName("preserves leading zeros rather than collapsing them")
        void preservesLeadingZeros() throws JsonProcessingException {
            BillPaymentRequest request = withAccountId("00000000001");

            assertThat(request.accountId()).isEqualTo("00000000001").isNotEqualTo("1");
            assertThat(payloadOf(request).get("accountId").asText())
                    .as("carried as text so that leading zeros and the eleven-character external "
                            + "width both survive; a numeric type would discard both")
                    .isEqualTo("00000000001");
        }

        @Test
        @DisplayName("travels as a JSON string and never as a JSON number")
        void travelsAsAString() throws JsonProcessingException {
            assertThat(payloadOf(withAccountId("00000000001")).get("accountId").isTextual()).isTrue();
            assertThat(payloadOf(withAccountId("00000000001")).get("accountId").isNumber()).isFalse();
        }

        @Test
        @DisplayName("carries an eleven-character value through the wire byte for byte")
        void carriesTheFullWidthByteForByte() throws JsonProcessingException {
            String full = "12345678901";
            ObjectMapper mapper = moduleEquivalentMapper();

            BillPaymentRequest returned = mapper.readValue(
                    mapper.writeValueAsString(withAccountId(full)), BillPaymentRequest.class);

            assertThat(returned.accountId()).hasSize(11).isEqualTo(full);
        }

        @Test
        @DisplayName("neither trims nor pads, so fixed-width padding survives")
        void neitherTrimsNorPads() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            String padded = "0000000001 ";
            String shorter = "1";

            BillPaymentRequest paddedBack = mapper.readValue(
                    mapper.writeValueAsString(withAccountId(padded)), BillPaymentRequest.class);
            BillPaymentRequest shorterBack = mapper.readValue(
                    mapper.writeValueAsString(withAccountId(shorter)), BillPaymentRequest.class);

            assertThat(paddedBack.accountId())
                    .as("the legacy field is fixed width and its padding is part of the value")
                    .isEqualTo(padded).hasSize(11);
            assertThat(shorterBack.accountId())
                    .as("a short value is reported as supplied and is never padded up to the width")
                    .isEqualTo(shorter).hasSize(1);
        }

        @Test
        @DisplayName("accepts a value at the declared width and reports one beyond it")
        void boundsTheValueAtTheDeclaredWidth() {
            assertThat(violationPathsOf(withAccountId("12345678901"))).isEmpty();
            assertThat(violationPathsOf(withAccountId("123456789012")))
                    .as("the bound measures and never alters, reporting only an over-wide value")
                    .containsExactly("accountId");
        }
    }

    @Nested
    @DisplayName("confirmation character")
    class ConfirmationCharacter {

        @Test
        @DisplayName("carries one character of text, never a two-valued flag")
        void carriesOneCharacterOfText() throws JsonProcessingException {
            BillPaymentRequest request = withConfirm("Y");

            String confirm = request.confirm();

            assertThat(confirm).isEqualTo("Y").hasSize(1);
            assertThat(payloadOf(request).get("confirm").isTextual())
                    .as("the legacy evaluation has four outcomes - settle, clear, display only, and "
                            + "an unacceptable character reported at line 187 of "
                            + "app/cbl/COBIL00C.cbl - which a two-valued flag cannot express")
                    .isTrue();
            assertThat(payloadOf(request).get("confirm").isBoolean()).isFalse();
        }

        @Test
        @DisplayName("exposes no derived boolean that would pre-empt the four-outcome decision")
        void exposesNoDerivedBoolean() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("deciding which characters mean confirm belongs to BillPaymentService; a "
                            + "derived flag here could not carry back the unacceptable character the "
                            + "operator actually typed")
                    .doesNotContain("confirmed", "isConfirmed", "confirmation", "accepted",
                            "approved", "shouldSettle");
        }

        @Test
        @DisplayName("does not case fold, so a lower-case answer stays lower case")
        void doesNotCaseFold() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            BillPaymentRequest returned = mapper.readValue(
                    mapper.writeValueAsString(withConfirm("y")), BillPaymentRequest.class);

            assertThat(returned.confirm())
                    .as("the legacy accepts either letter case explicitly, so folding here would "
                            + "silently rewrite what the operator typed")
                    .isEqualTo("y").isNotEqualTo("Y");
        }

        @Test
        @DisplayName("accepts a space, which is the ordinary first pass through the screen")
        void acceptsASpace() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            BillPaymentRequest returned = mapper.readValue(
                    mapper.writeValueAsString(withConfirm(" ")), BillPaymentRequest.class);

            assertThat(returned.confirm())
                    .as("an absent answer reads the account and displays its total without settling "
                            + "anything, which is how the screen is first filled")
                    .isEqualTo(" ").hasSize(1);
            assertThat(violationPathsOf(withConfirm(" "))).isEmpty();
        }

        @Test
        @DisplayName("accepts an empty value and an absent value alike")
        void acceptsEmptyAndAbsent() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            BillPaymentRequest returned = mapper.readValue(
                    mapper.writeValueAsString(withConfirm("")), BillPaymentRequest.class);

            assertThat(returned.confirm()).isEmpty();
            assertThat(withConfirm(null).confirm())
                    .as("the component has no default, so an absent answer stays absent")
                    .isNull();
            assertThat(violationPathsOf(withConfirm(""))).isEmpty();
        }

        @Test
        @DisplayName("accepts a value at the declared width and reports one beyond it")
        void boundsTheValueAtTheDeclaredWidth() {
            assertThat(violationPathsOf(withConfirm("N"))).isEmpty();
            assertThat(violationPathsOf(withConfirm("YN"))).containsExactly("confirm");
        }
    }

    @Nested
    @DisplayName("every field rule is delegated, so the record reports nothing")
    class DelegatedValidation {

        @Test
        @DisplayName("reports nothing when every component is absent")
        void reportsNothingWhenEverythingIsAbsent() {
            assertThat(violationPathsOf(new BillPaymentRequest(null, null, null, null)))
                    .as("an empty identifier is precisely the state the emptiness diagnostic at line "
                            + "161 of app/cbl/COBIL00C.cbl exists to report, so no presence "
                            + "constraint may pre-empt it")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports nothing when every component is blank or empty")
        void reportsNothingWhenEverythingIsBlank() {
            assertThat(violationPathsOf(new BillPaymentRequest("", "", null,
                    NavigationContext.empty())))
                    .isEmpty();
            assertThat(violationPathsOf(new BillPaymentRequest("           ", " ", null,
                    NavigationContext.empty())))
                    .as("an all-space identifier at the full declared width is a real 3270 "
                            + "submission and must reach the service unreported")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports nothing for a non-numeric account identifier")
        void reportsNothingForANonNumericIdentifier() {
            assertThat(violationPathsOf(withAccountId("ABCDEFGHIJK")))
                    .as("no pattern, digit or character-class constraint is declared; the estate has "
                            + "no ordered check and no message for a non-numeric identifier here")
                    .isEmpty();
            assertThat(violationPathsOf(withAccountId("-0000000001"))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for an unexpected confirmation character")
        void reportsNothingForAnUnexpectedConfirmation() {
            assertThat(violationPathsOf(withConfirm("X")))
                    .as("an unacceptable third character must reach the service intact so the "
                            + "diagnostic at line 187 of app/cbl/COBIL00C.cbl can name it")
                    .isEmpty();
            assertThat(violationPathsOf(withConfirm("0"))).isEmpty();
            assertThat(violationPathsOf(withConfirm("?"))).isEmpty();
        }

        @Test
        @DisplayName("reports nothing for any attention key in the vocabulary")
        void reportsNothingForAnyAttentionKey() {
            assertThat(violationPathsOf(
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, null))).isEmpty();
            assertThat(violationPathsOf(
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.PFK03, null))).isEmpty();
            assertThat(violationPathsOf(
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.PFK04, null))).isEmpty();
            assertThat(violationPathsOf(
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.PFK12, null)))
                    .as("the higher function keys are not folded onto the lower twelve on this "
                            + "screen, because COBIL00C compares the raw key identifier itself")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports at most a width breach, and never two errors at once for one field")
        void reportsAtMostAWidthBreach() {
            assertThat(violationPathsOf(new BillPaymentRequest("123456789012", "YN", null, null)))
                    .as("the only declared constraint is an upper bound on length; nothing else can "
                            + "fire, which is why the ordered first-error-wins cascade stays whole "
                            + "in BillPaymentService")
                    .containsExactly("accountId", "confirm");
        }

        @Test
        @DisplayName("never throws while constructing any input a 3270 submission could produce")
        void neverThrowsWhileConstructing() {
            assertThatCode(() -> new BillPaymentRequest(null, null, null, null))
                    .doesNotThrowAnyException();
            assertThatCode(() -> new BillPaymentRequest("", "", KeyAction.CLEAR,
                    NavigationContext.empty()))
                    .as("there is no canonical constructor because there is nothing for one to do: "
                            + "no default, no normalisation and no rejection happens here")
                    .doesNotThrowAnyException();
            assertThatCode(() -> new BillPaymentRequest("123456789012345", "YYY", KeyAction.PA1,
                    navigation()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("nested navigation state is validated transitively")
    class NestedNavigationCascade {

        @Test
        @DisplayName("reports a nested width breach against its nested path")
        void reportsANestedBreachAgainstItsPath() {
            NavigationContext overWide = new NavigationContext("CB000", "COBIL00C", "CB00",
                    "COBIL00C", "ADMINUSR", "A", NavigationContext.ProgramContext.ENTER, null, null,
                    null, null, null, null, null, null, null);

            assertThat(violationPathsOf(
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, overWide)))
                    .as("Bean Validation does not descend into a nested object unless told to, so "
                            + "without the cascade every bound inside the navigation state would be "
                            + "decorative and an over-long value would reach the service unreported")
                    .containsExactly("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("reports nothing when the nested state is within its own bounds")
        void reportsNothingForValidNestedState() {
            assertThat(violationPathsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("reports nothing when the nested state is absent or wholly empty")
        void reportsNothingForAbsentNestedState() {
            assertThat(violationPathsOf(
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, null))).isEmpty();
            assertThat(violationPathsOf(new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER,
                    NavigationContext.empty())))
                    .as("the wholly empty navigation state is a real legacy state rather than a "
                            + "placeholder")
                    .isEmpty();
        }

        @Test
        @DisplayName("cascades a bound without introducing one, so nested paths stay separate")
        void cascadesABoundWithoutIntroducingOne() {
            NavigationContext overWide = new NavigationContext(null, null, null, null,
                    "ADMINUSER1", null, null, null, null, null, null, "123456789012", null, null,
                    null, null);

            assertThat(violationPathsOf(
                    new BillPaymentRequest("123456789012", CONFIRM, KeyAction.ENTER, overWide)))
                    .as("the outer bound and each nested bound are reported independently and under "
                            + "their own paths; cascading a bound is not the same as adding one")
                    .containsExactly("accountId", "navigationContext.accountId",
                            "navigationContext.userId");
        }
    }

    @Nested
    @DisplayName("nothing is derived, identified or synthesized here")
    class NothingIsDerivedHere {

        @Test
        @DisplayName("carries no transaction identifier and no generator hint")
        void carriesNoTransactionIdentifier() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("the program draws the identifier from no generator: it positions on the "
                            + "highest existing key by browsing backwards from the high value and "
                            + "takes the one immediately above it at lines 212 to 219 of "
                            + "app/cbl/COBIL00C.cbl, seeding from zeros when the file is empty at "
                            + "line 488, so the first identifier is the sixteen-character form and "
                            + "never a bare one")
                    .doesNotContain("transactionId", "tranId", "id", "sequence", "sequenceNumber",
                            "nextId", "counter", "seq", "version", "etag", "rowVersion",
                            "lockVersion");
        }

        @Test
        @DisplayName("carries no synthesized description or merchant value")
        void carriesNoSynthesizedDescription() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("the description at line 223 of app/cbl/COBIL00C.cbl, the merchant name at "
                            + "line 227, the source at line 222 and the type, category, merchant "
                            + "identifier, city and postal code are values the program holds itself, "
                            + "so none is client-supplied")
                    .doesNotContain("description", "tranDescription", "merchantName", "merchantId",
                            "merchantCity", "merchantZip", "transactionType", "tranType",
                            "transactionCategory", "tranCategory", "source", "tranSource");
        }

        @Test
        @DisplayName("carries no timestamp and no clock reading")
        void carriesNoTimestamp() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("a single clock reading is stamped into both the origination and the "
                            + "processing timestamp at lines 231 to 232 of app/cbl/COBIL00C.cbl, and "
                            + "building either rendering is the service's work")
                    .doesNotContain("originTimestamp", "processTimestamp", "tranOrigTs",
                            "tranProcTs", "timestamp", "createdAt", "processedAt");
        }

        @Test
        @DisplayName("carries no card number and no verification value")
        void carriesNoCardData() throws JsonProcessingException {
            assertThat(propertiesOf(populated()))
                    .as("this screen asks for an account and a confirmation character; it asks for "
                            + "no card, and masking one that is not carried would be unrequested "
                            + "work")
                    .doesNotContain("cardNumber", "cardNum", "cvv", "cvvCode", "expiryDate",
                            "embossedName");
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("round-trips a fully populated request unchanged")
        void roundTripsAFullyPopulatedRequest() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            BillPaymentRequest returned = mapper.readValue(
                    mapper.writeValueAsString(populated()), BillPaymentRequest.class);

            assertThat(returned).isEqualTo(populated());
            assertThat(returned.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(returned.confirm()).isEqualTo(CONFIRM);
            assertThat(returned.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(returned.navigationContext()).isEqualTo(navigation());
        }

        @Test
        @DisplayName("round-trips a wholly absent request unchanged")
        void roundTripsAWhollyAbsentRequest() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            BillPaymentRequest absent = new BillPaymentRequest(null, null, null, null);

            BillPaymentRequest returned =
                    mapper.readValue(mapper.writeValueAsString(absent), BillPaymentRequest.class);

            assertThat(returned).isEqualTo(absent);
            assertThat(mapper.writeValueAsString(absent)).isEqualTo("{}");
        }

        @Test
        @DisplayName("omits an absent component rather than publishing a null")
        void omitsAnAbsentComponent() throws JsonProcessingException {
            assertThat(propertiesOf(withAccountId(ACCOUNT_ID)))
                    .as("non-null inclusion omits an absent key entirely")
                    .containsExactly("accountId");
            assertThat(propertiesOf(new BillPaymentRequest(ACCOUNT_ID, CONFIRM, null, null)))
                    .containsExactlyInAnyOrder("accountId", "confirm");
        }

        @Test
        @DisplayName("tolerates an unknown incoming property")
        void toleratesAnUnknownProperty() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            String body = """
                    {"accountId":"00000000011","confirm":"Y","unknownField":"ignored"}""";

            BillPaymentRequest bound = mapper.readValue(body, BillPaymentRequest.class);

            assertThat(bound).isEqualTo(new BillPaymentRequest(ACCOUNT_ID, CONFIRM, null, null));
        }

        @Test
        @DisplayName("names the attention key rather than indexing it")
        void namesTheAttentionKey() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            String named = """
                    {"keyAction":"PFK03"}""";

            assertThat(mapper.readValue(named, BillPaymentRequest.class).keyAction())
                    .isEqualTo(KeyAction.PFK03);
        }

        @Test
        @DisplayName("refuses a bare number for the attention key instead of reading an ordinal")
        void refusesANumberForTheAttentionKey() {
            ObjectMapper mapper = moduleEquivalentMapper();
            String numeric = """
                    {"keyAction":8}""";

            assertThatCode(() -> mapper.readValue(numeric, BillPaymentRequest.class))
                    .as("no attention identifier is an index - each is a named byte and the "
                            + "vocabulary has no catch-all branch - so an ordinal is never a value "
                            + "an operator could have produced")
                    .isInstanceOf(JsonProcessingException.class);
        }
    }

    @Nested
    @DisplayName("immutability, equality and diagnostic rendering")
    class ImmutabilityAndRendering {

        @Test
        @DisplayName("returns the value it was built from on every call, and hands the nested state back "
                + "by identity")
        void returnsTheSameValueOnEveryCall() {
            // Every expectation is the value the constructor was handed, restated here rather than read
            // back from the instance: an accessor compared with itself cannot detect a stable but wrong
            // value. The identity claim about the nested state is kept, but it is now anchored to a
            // reference captured before the repeated reads rather than to a second call of the same
            // accessor.
            NavigationContext nested = navigation();
            BillPaymentRequest request =
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, nested);

            for (int read = 0; read < 2; read++) {
                assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
                assertThat(request.confirm()).isEqualTo(CONFIRM);
                assertThat(request.keyAction()).isSameAs(KeyAction.ENTER);
                assertThat(request.navigationContext())
                        .as("the nested state is handed back by identity, so no defensive copy hides a "
                                + "mutation and none is needed: the nested type is deeply immutable")
                        .isSameAs(nested);
            }
        }

        @Test
        @DisplayName("holds no mutable state a caller could reach")
        void holdsNoMutableState() {
            NavigationContext nested = navigation();
            BillPaymentRequest request =
                    new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, nested);

            assertThat(request.navigationContext()).isSameAs(nested);
            assertThat(request)
                    .as("a record, so every component is final and no mutator is declared; there is "
                            + "no setter to call, which the absence of one from this file's "
                            + "compilation establishes without any class-metadata query")
                    .isEqualTo(populated());
        }

        @Test
        @DisplayName("compares every component by value")
        void comparesEveryComponentByValue() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(populated())
                    .isNotEqualTo(new BillPaymentRequest(ACCOUNT_ID, "N", KeyAction.ENTER,
                            navigation()))
                    .isNotEqualTo(new BillPaymentRequest("00000000012", CONFIRM, KeyAction.ENTER,
                            navigation()))
                    .isNotEqualTo(new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.PFK03,
                            navigation()))
                    .isNotEqualTo(new BillPaymentRequest(ACCOUNT_ID, CONFIRM, KeyAction.ENTER, null));
            assertThat(new BillPaymentRequest(null, null, null, null))
                    .isEqualTo(new BillPaymentRequest(null, null, null, null));
        }

        @Test
        @DisplayName("names the type so a diagnostic identifies what it is looking at")
        void namesTheType() {
            assertThat(populated().toString()).startsWith("BillPaymentRequest[").endsWith("]");
        }

        @Test
        @DisplayName("withholds the account identifier and the operator's answer")
        void withholdsBothOperatorValues() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .as("a bill payment always settles the whole outstanding total, so a rendered "
                            + "line naming the account would name the payer of a specific settled "
                            + "amount; and a rejection must never echo the answer it rejected")
                    .contains("accountId=" + REDACTED)
                    .contains("confirm=" + REDACTED)
                    .doesNotContain(ACCOUNT_ID);
        }

        @Test
        @DisplayName("retains the attention key, which identifies nobody")
        void retainsTheAttentionKey() {
            assertThat(populated().toString())
                    .as("on this screen the key is what says whether the submission was a settlement "
                            + "attempt, a return, a clear or an unmapped key")
                    .contains("keyAction=" + KeyAction.ENTER);
        }

        @Test
        @DisplayName("delegates the nested rendering, which withholds its own values")
        void delegatesTheNestedRendering() {
            assertThat(populated().toString())
                    .contains("navigationContext=" + navigation())
                    .doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("renders safely when every component is absent")
        void rendersSafelyWhenEverythingIsAbsent() {
            assertThat(new BillPaymentRequest(null, null, null, null).toString())
                    .isEqualTo("BillPaymentRequest[accountId=" + REDACTED + ", confirm=" + REDACTED
                            + ", keyAction=null, navigationContext=null]");
        }

        @Test
        @DisplayName("withholds only on the rendering path, never on the wire")
        void withholdsOnlyOnTheRenderingPath() throws JsonProcessingException {
            BillPaymentRequest request = populated();
            String ignoredRendering = request.toString();

            assertThat(ignoredRendering).contains(REDACTED);
            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.confirm()).isEqualTo(CONFIRM);
            assertThat(payloadOf(request).get("accountId").asText())
                    .as("the wire is a different channel with a different requirement: the service "
                            + "needs both values intact")
                    .isEqualTo(ACCOUNT_ID);
            assertThat(payloadOf(request).get("confirm").asText()).isEqualTo(CONFIRM);
            assertThat(payloadOf(request).toString()).doesNotContain(REDACTED);
        }
    }
}
