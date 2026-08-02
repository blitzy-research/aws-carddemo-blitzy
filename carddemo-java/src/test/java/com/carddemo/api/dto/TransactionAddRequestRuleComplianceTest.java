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
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import com.carddemo.domain.enums.KeyAction;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionAddRequest}, the request body of legacy transaction {@code CT02}
 * implemented by {@code app/cbl/COTRN02C.cbl} over screen {@code app/cpy-bms/COTRN02.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Either key, not both.</strong> The screen accepts an account identifier or a card
 * number and resolves the other through the cross-reference, which is why both are optional at this
 * layer and why the rejection for supplying neither is a whole-screen message rather than a per-field
 * bound. The bound work this record does is width work only; the either-or rule belongs to the
 * service and its message is published on {@link TransactionAddResponse#MESSAGE_KEY_NOT_ENTERED}.
 *
 * <p><strong>No identifier component.</strong> {@code COTRN02C} assigns the transaction identifier
 * itself, by browsing to the highest existing key and adding one, so the request carries no
 * identifier for the caller to propose. That absence is asserted reflectively, because accepting a
 * caller-supplied identifier would be both a feature the legacy system lacks and a way to collide
 * with an existing key.
 *
 * <p><strong>The amount arrives as the operator typed it, not as a number.</strong> The map field
 * {@code TRNAMT} is {@code PIC X(12)} at {@code app/cpy-bms/COTRN02.CPY:96}, and
 * {@code app/cbl/COTRN02C.cbl} checks it twice before anything numeric happens: an emptiness check at
 * line 276 and a four-position shape check at lines 339 to 347. The request therefore carries the
 * twelve-character external form rather than {@link BigDecimal}, so that a blank value, a missing
 * decimal point and a sign character that is neither plus nor minus each stay representable and each
 * stay reportable with the message it is owed. Conversion to an exact decimal is the service's, and it
 * happens after this bound is satisfied - which is also why exponent notation cannot arrive at all:
 * there is no numeric binding here for a client to smuggle {@code 1E+9} through. The stored field
 * {@code TRAN-AMT} is {@code PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L14} and the estate
 * declares no {@code ROUNDED} clause anywhere, so the decimal the service produces is exact; that is
 * asserted where the conversion lives and not here. The payload assertions read the serialized
 * characters rather than a parsed tree, because the wire form of a lexeme is a quoted string and a
 * tree would report it identically to a number it is not.
 */
@DisplayName("TransactionAddRequest - the CT02 transaction-add screen contract")
class TransactionAddRequestRuleComplianceTest {

    /**
     * The placeholder the request's own rendering substitutes for a withheld component.
     *
     * <p>Restated here rather than read reflectively because the production constant is private on
     * purpose: a diagnostic placeholder is not part of the published contract.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /** A representative eleven-digit account identifier drawn from the seeded fixture range. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A representative sixteen-digit card number. */
    private static final String CARD_NUMBER = "4111111111111111";

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
     * Serializes a request and reads the result back as a tree.
     *
     * @param request the request to render
     * @return the rendered payload
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static JsonNode payloadOf(final TransactionAddRequest request)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Serializes a request to the exact characters that go on the wire.
     *
     * @param request the request to render
     * @return the rendered payload as written
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static String wirePayloadOf(final TransactionAddRequest request)
            throws JsonProcessingException {
        return moduleEquivalentMapper().writeValueAsString(request);
    }

    /**
     * Builds a fully populated request, laid out in rows of five so a component cannot silently drift
     * one position out of line past a compiler that sees a wall of interchangeable strings.
     *
     * @param accountId the account key, or {@code null} when the card number is the key
     * @param cardNumber the card key, or {@code null} when the account identifier is the key
     * @param amount the twelve-character amount lexeme to post, as the operator typed it
     * @param confirm the one-character confirmation value
     * @param keyAction the attention key the operator pressed
     * @return a request carrying the supplied values and representative transaction detail
     */
    private static TransactionAddRequest aRequest(final String accountId, final String cardNumber,
            final String amount, final String confirm, final KeyAction keyAction) {
        return new TransactionAddRequest(
                accountId, cardNumber, "01", "0005", "POS TERM",
                "POS PURCHASE - GROCERY", amount, "2022-07-19", "2022-07-19", "123456789",
                "MERCHANT NAME", "SEATTLE", "98101", confirm, keyAction,
                NavigationContext.empty());
    }

    /**
     * Builds a request carrying only the components a single assertion needs.
     *
     * @param accountId the account key
     * @param amount the twelve-character amount lexeme to post, as the operator typed it
     * @param confirm the one-character confirmation value
     * @return a request carrying the supplied values and nothing else
     */
    private static TransactionAddRequest aSparseRequest(final String accountId,
            final String amount, final String confirm) {
        return new TransactionAddRequest(
                accountId, null, null, null, null,
                null, amount, null, null, null,
                null, null, null, confirm, null,
                null);
    }

    /**
     * Reports whether a named component's accessor carries a declared upper bound.
     *
     * <p>The annotation is read from the accessor rather than from the record component, because
     * {@code jakarta.validation.constraints.Size} does not target {@code RECORD_COMPONENT}. A
     * constraint written on a record component is propagated to the backing field, the accessor and
     * the canonical constructor parameter instead of being retained on the component itself.
     *
     * @param componentName the record component to test
     * @return {@code true} when the accessor declares a {@link Size} bound
     */
    private static boolean declaresAnUpperBound(final String componentName) {
        try {
            return TransactionAddRequest.class.getDeclaredMethod(componentName)
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
        final Size size = TransactionAddRequest.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape")
    class TheDeclaredShape {

        @Test
        @DisplayName("the request declares sixteen components in screen order, the two keys, the "
                + "transaction body, the merchant block, the confirmation and the two control "
                + "components")
        void theRequestDeclaresSixteenComponentsInScreenOrder() {
            final List<String> declared = Arrays.stream(
                    TransactionAddRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("accountId", "cardNumber", "typeCode",
                    "categoryCode", "transactionSource", "description", "amount",
                    "originationDate", "processingDate", "merchantId", "merchantName",
                    "merchantCity", "merchantZip", "confirm", "keyAction", "navigationContext");
            assertThat(declared).hasSize(16);
        }

        @Test
        @DisplayName("no component proposes a transaction identifier, because the legacy program "
                + "assigns one by browsing to the highest existing key and adding one")
        void noComponentProposesATransactionIdentifier() {
            final List<String> declared = Arrays.stream(
                    TransactionAddRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).doesNotContain("transactionId", "newTransactionId",
                    "transactionIdFilter");
        }

        @Test
        @DisplayName("both keys are present and both are optional, because the screen accepts either "
                + "one and resolves the other through the cross-reference")
        void bothKeysArePresentAndBothAreOptional() {
            assertThat(aRequest(ACCOUNT_ID, null, "1.00", "Y", KeyAction.ENTER).cardNumber())
                    .isNull();
            assertThat(aRequest(null, CARD_NUMBER, "1.00", "Y", KeyAction.ENTER).accountId())
                    .isNull();
            assertThat(aRequest(ACCOUNT_ID, CARD_NUMBER, "1.00", "Y", KeyAction.ENTER))
                    .satisfies(request -> {
                        assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
                        assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
                    });
        }

        @Test
        @DisplayName("exactly fourteen components carry a declared upper bound, and the two that do "
                + "not are the attention key and the conversation state")
        void exactlyFourteenComponentsCarryAnUpperBound() {
            final List<String> bounded = Arrays.stream(
                    TransactionAddRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(TransactionAddRequestRuleComplianceTest::declaresAnUpperBound).toList();

            assertThat(bounded).hasSize(14);
            assertThat(bounded).doesNotContain("keyAction", "navigationContext");
            assertThat(bounded)
                    .as("the amount is bounded too, because it is a fixed-width map field")
                    .contains("amount");
        }

        @Test
        @DisplayName("the amount is the twelve-character lexeme the map declares and not a numeric "
                + "type, so a blank value and a malformed one both stay representable and reportable")
        void theAmountIsTheTypedLexeme() throws NoSuchMethodException {
            assertThat(TransactionAddRequest.class.getDeclaredMethod("amount").getReturnType())
                    .isEqualTo(String.class);
            assertThat(declaredMaximumLength("amount")).isEqualTo(12);
        }

        @Test
        @DisplayName("no component is a numeric type at all, so no client input is parsed, scaled or "
                + "rounded on the way in and no floating-point value can reach the service")
        void noComponentIsANumericType() {
            assertThat(Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                    .map(component -> component.getType().getName()).toList())
                    .doesNotContain("double", "float", "java.lang.Double", "java.lang.Float",
                            BigDecimal.class.getName(), "java.lang.Long", "java.lang.Integer",
                            "long", "int");
        }

        /**
         * The record declares no width or message constant of its own - only its rendering placeholder.
         *
         * <p>Every width it uses is the stored width, read from the entity that owns the field, and every
         * message belongs to the response rather than to the request, so a constant here would be a second
         * copy of something already declared elsewhere. The one static it does declare is the placeholder
         * its withholding rendering substitutes for a component, which is neither a width nor a message and
         * is not per-instance state.</p>
         *
         * <p>The two are separated by modifier rather than by name, so the census still fails if a mapped
         * component is added and happens to be spelled like a constant.</p>
         */
        @Test
        @DisplayName("the record declares no width or message constant of its own, and its only static is "
                + "the placeholder its rendering substitutes")
        void theRecordDeclaresNoConstantsOfItsOwnBeyondItsPlaceholder() {
            assertThat(Arrays.stream(TransactionAddRequest.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .map(Field::getName).toList())
                    .containsExactlyInAnyOrder("accountId", "cardNumber", "typeCode",
                            "categoryCode", "transactionSource", "description", "amount",
                            "originationDate", "processingDate", "merchantId", "merchantName",
                            "merchantCity", "merchantZip", "confirm", "keyAction",
                            "navigationContext");
            assertThat(Arrays.stream(TransactionAddRequest.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .map(Field::getName).toList())
                    .as("no width and no message, only the rendering placeholder")
                    .containsExactly("REDACTION_PLACEHOLDER");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final TransactionAddRequest request = aRequest(ACCOUNT_ID, CARD_NUMBER,
                    "1234.56", "Y", KeyAction.PFK05);

            assertThat(request.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(request.typeCode()).isEqualTo("01");
            assertThat(request.categoryCode()).isEqualTo("0005");
            assertThat(request.transactionSource()).isEqualTo("POS TERM");
            assertThat(request.description()).isEqualTo("POS PURCHASE - GROCERY");
            assertThat(request.amount()).isEqualTo("1234.56");
            assertThat(request.originationDate()).isEqualTo("2022-07-19");
            assertThat(request.processingDate()).isEqualTo("2022-07-19");
            assertThat(request.merchantId()).isEqualTo("123456789");
            assertThat(request.merchantName()).isEqualTo("MERCHANT NAME");
            assertThat(request.merchantCity()).isEqualTo("SEATTLE");
            assertThat(request.merchantZip()).isEqualTo("98101");
            assertThat(request.confirm()).isEqualTo("Y");
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(request.navigationContext()).isEqualTo(NavigationContext.empty());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared validation bounds")
    class TheDeclaredValidationBounds {

        @Test
        @DisplayName("the two keys carry the stored key widths, eleven digits of account and sixteen "
                + "of card")
        void theTwoKeysCarryTheStoredKeyWidths() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("accountId")).isEqualTo(11);
            assertThat(declaredMaximumLength("cardNumber")).isEqualTo(16);
        }

        @Test
        @DisplayName("the description bound is sixty, the stored width, so an add is not silently "
                + "truncated to the twenty-six the list screen renders")
        void theDescriptionBoundIsTheStoredWidth() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("description")).isEqualTo(60);
            assertThat(declaredMaximumLength("description"))
                    .isGreaterThan(TransactionListResponse.DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("the two date components are ten characters, matching the format the legacy "
                + "message names")
        void theTwoDateComponentsAreTenCharacters() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("originationDate")).isEqualTo(10);
            assertThat(declaredMaximumLength("processingDate")).isEqualTo(10);
            assertThat(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT)
                    .contains("YYYY-MM-DD");
        }

        @Test
        @DisplayName("the confirmation bound is one, so the field can hold Y or N and never a word")
        void theConfirmationBoundIsOne() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("confirm")).isOne();
        }

        @Test
        @DisplayName("a wholly absent request reports no violation, because every bound is an upper "
                + "bound and the mandatory-field rules belong to the service")
        void aWhollyAbsentRequestReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aSparseRequest(null, null, null)))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a fully populated request reports no violation, so the representative fixture is "
                + "itself within every declared bound")
        void aFullyPopulatedRequestReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequest(ACCOUNT_ID, CARD_NUMBER,
                        "1234.56", "Y", KeyAction.ENTER))).isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({
            "accountId,11", "cardNumber,16", "typeCode,2",
            "categoryCode,4", "transactionSource,10", "description,60",
            "amount,12",
            "originationDate,10", "processingDate,10", "merchantId,9",
            "merchantName,30", "merchantCity,25", "merchantZip,10",
            "confirm,1",
        })
        @DisplayName("a bounded component accepts its declared width and rejects one character more")
        void aBoundedComponentAcceptsItsWidthAndRejectsOneMore(final String componentName,
                final int declaredMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(componentName)).isEqualTo(declaredMaximum);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(requestWith(componentName, "X".repeat(declaredMaximum))))
                        .as("%s must accept %d characters", componentName, declaredMaximum)
                        .isEmpty();
                assertThat(factory.getValidator()
                        .validate(requestWith(componentName, "X".repeat(declaredMaximum + 1))))
                        .as("%s must reject %d characters", componentName, declaredMaximum + 1)
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString()).isEqualTo(componentName));
            }
        }

        @Test
        @DisplayName("the amount is bounded by the map width and by nothing else, so the widest and the "
                + "narrowest legitimate lexemes both pass and only a thirteenth character fails")
        void theAmountIsBoundedByTheMapWidthAndNothingElse() throws NoSuchMethodException {
            assertThat(declaresAnUpperBound("amount")).isTrue();
            assertThat(declaredMaximumLength("amount")).isEqualTo(12);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aSparseRequest(null,
                        "999999999.99", null))).isEmpty();
                assertThat(factory.getValidator().validate(aSparseRequest(null,
                        "-999999999.99", null)))
                        .as("thirteen characters breach the map width the bound measures")
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("a malformed amount passes the bound, because the bound measures width and the "
                + "positional cascade, the sign rule and the conversion are all the service's")
        void aMalformedAmountPassesTheBound() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                for (final String lexeme : List.of("            ", "0000010000", "+000010.00",
                        "*00001000.0", "abcdefghijkl", "10.000", "-", ".")) {
                    assertThat(factory.getValidator().validate(aSparseRequest(null, lexeme, null)))
                            .as("[%s] is the service's to reject, not the bound's", lexeme)
                            .isEmpty();
                }
            }
        }

        /**
         * Builds a request carrying a single named component and nothing else.
         *
         * @param componentName the component to populate
         * @param value the value to place in it
         * @return a request carrying only that component
         */
        private TransactionAddRequest requestWith(final String componentName, final String value) {
            return new TransactionAddRequest(
                    valueFor("accountId", componentName, value),
                    valueFor("cardNumber", componentName, value),
                    valueFor("typeCode", componentName, value),
                    valueFor("categoryCode", componentName, value),
                    valueFor("transactionSource", componentName, value),
                    valueFor("description", componentName, value),
                    valueFor("amount", componentName, value),
                    valueFor("originationDate", componentName, value),
                    valueFor("processingDate", componentName, value),
                    valueFor("merchantId", componentName, value),
                    valueFor("merchantName", componentName, value),
                    valueFor("merchantCity", componentName, value),
                    valueFor("merchantZip", componentName, value),
                    valueFor("confirm", componentName, value),
                    null, null);
        }

        /**
         * Returns the value when the position being filled is the requested component.
         *
         * @param position the component this constructor argument fills
         * @param requested the component the caller wants populated
         * @param value the value to place
         * @return the value when the position matches, otherwise {@code null}
         */
        private String valueFor(final String position, final String requested, final String value) {
            return position.equals(requested) ? value : null;
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two requests built from identical values are equal and share a hash code")
        void twoRequestsBuiltFromIdenticalValuesAreEqual() {
            final TransactionAddRequest first = aRequest(ACCOUNT_ID, CARD_NUMBER,
                    "10.00", "Y", KeyAction.ENTER);
            final TransactionAddRequest second = aRequest(ACCOUNT_ID, CARD_NUMBER,
                    "10.00", "Y", KeyAction.ENTER);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("two amount lexemes that differ only in a trailing zero are not equal, because the "
                + "external form is what the operator typed and the shape check reads it positionally")
        void twoAmountLexemesDifferingOnlyInATrailingZeroAreNotEqual() {
            assertThat(aSparseRequest(ACCOUNT_ID, "10.00", null))
                    .isNotEqualTo(aSparseRequest(ACCOUNT_ID, "10.0", null));
        }

        @Test
        @DisplayName("the rendering withholds the two keys, the description, the amount and the four "
                + "merchant components, and names the codes, the dates and the control components")
        void theRenderingWithholdsTheCardholderBearingComponents() {
            final String rendered = aRequest(ACCOUNT_ID, CARD_NUMBER, "10.00", "Y",
                    KeyAction.PFK04).toString();

            assertThat(rendered).startsWith("TransactionAddRequest[");
            assertThat(rendered).contains("accountId=" + REDACTION_PLACEHOLDER,
                    "cardNumber=" + REDACTION_PLACEHOLDER,
                    "description=" + REDACTION_PLACEHOLDER, "amount=" + REDACTION_PLACEHOLDER,
                    "merchantId=" + REDACTION_PLACEHOLDER, "merchantName=" + REDACTION_PLACEHOLDER,
                    "merchantCity=" + REDACTION_PLACEHOLDER, "merchantZip=" + REDACTION_PLACEHOLDER);
            assertThat(rendered).contains("typeCode=01", "categoryCode=0005",
                    "transactionSource=POS TERM", "originationDate=2022-07-19",
                    "processingDate=2022-07-19", "confirm=Y", "keyAction=PFK04");
            assertThat(rendered).doesNotContain(ACCOUNT_ID, CARD_NUMBER, "10.00", "123456789",
                    "98101", "SEATTLE", "MERCHANT NAME", "POS PURCHASE - GROCERY");
            assertThat(rendered).endsWith("]");
        }

        @Test
        @DisplayName("the withholding is unconditional, so an absent value renders as the same "
                + "placeholder a populated one does")
        void theWithholdingIsUnconditional() {
            assertThat(aSparseRequest(null, null, null).toString())
                    .contains("accountId=" + REDACTION_PLACEHOLDER,
                            "amount=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("the payload omits every absent component, so a request that names only one key "
                + "does not carry a null for the other")
        void thePayloadOmitsEveryAbsentComponent() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseRequest(ACCOUNT_ID, null, null));

            assertThat(payload.has("accountId")).isTrue();
            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.has("cardNumber")).isFalse();
            assertThat(payload.has("amount")).isFalse();
            assertThat(payload.has("confirm")).isFalse();
            assertThat(payload.has("keyAction")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
        }

        @Test
        @DisplayName("an amount travels as the quoted lexeme the operator typed, character for "
                + "character, so no notation choice is made anywhere on the wire")
        void anAmountTravelsAsTheQuotedLexeme() throws JsonProcessingException {
            final String wire = wirePayloadOf(
                    aSparseRequest(ACCOUNT_ID, "1000000000.05", null));

            assertThat(wire).contains("\"amount\":\"1000000000.05\"");
            assertThat(wire).doesNotContain("E9").doesNotContain("E+");
        }

        @Test
        @DisplayName("exponent notation cannot arrive at all, because a string binding has no numeric "
                + "parse for a client to route it through")
        void exponentNotationCannotArriveAtAll() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final TransactionAddRequest bound = mapper.readValue(
                    "{\"amount\":\"1E+9\"}", TransactionAddRequest.class);

            assertThat(bound.amount())
                    .as("the characters are carried verbatim for the service to reject")
                    .isEqualTo("1E+9");
        }

        @Test
        @DisplayName("a negative amount keeps its sign on the wire, which the signed legacy field "
                + "requires for a return or a credit")
        void aNegativeAmountKeepsItsSignOnTheWire() throws JsonProcessingException {
            assertThat(wirePayloadOf(aSparseRequest(ACCOUNT_ID, "-42.10", null)))
                    .contains("\"amount\":\"-42.10\"");
        }

        @Test
        @DisplayName("a request survives a round trip through the module-equivalent mapper unchanged, "
                + "the amount lexeme included character for character")
        void aRequestSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final TransactionAddRequest original = aRequest(ACCOUNT_ID, CARD_NUMBER,
                    "1234.56", "Y", KeyAction.PFK05);
            final TransactionAddRequest restored = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionAddRequest.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.amount()).isEqualTo("1234.56")
                    .as("no scaling, padding or reformatting happens on either leg")
                    .hasSize(7);
        }
    }
}
