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
import java.util.List;

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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link TransactionViewResponse}, the response body of legacy transaction
 * {@code CT01} implemented by {@code app/cbl/COTRN01C.cbl} over screen
 * {@code app/cpy-bms/COTRN01.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Every width is the stored width, not a screen truncation.</strong> The view screen shows
 * one transaction in full, so its component bounds follow the 350-byte transaction record in
 * {@code app/cpy/CVTRA05Y.cpy} rather than a list rendering. The clearest evidence is the
 * description: sixty characters here against the twenty-six the list screen renders through
 * {@link TransactionListResponse#DESCRIPTION_LENGTH}. That contrast is asserted directly, because a
 * shared constant would have quietly imposed one screen's truncation on the other.
 *
 * <p><strong>The amount is decimal and truncating.</strong> {@code TRAN-AMT} is
 * {@code PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L14} and the estate declares no
 * {@code ROUNDED} clause anywhere, so this response carries {@link BigDecimal}. The payload
 * assertions read the serialized characters rather than a parsed tree, because a tree binds a JSON
 * float to a double by default and would report scientific notation for a plain decimal payload.
 *
 * <p><strong>Two identifiers, deliberately.</strong> {@code searchTransactionId} echoes what the
 * operator asked for and {@code transactionId} reports what was found, so a not-found reply can still
 * redisplay the search key. Collapsing the two would lose the operator's own input on a failed
 * lookup.
 */
@DisplayName("TransactionViewResponse - the CT01 transaction-view screen contract")
class TransactionViewResponseRuleComplianceTest {

    /** A representative sixteen-digit transaction identifier. */
    private static final String TRANSACTION_ID = "0000000000000042";

    /** A representative sixteen-digit card number. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * The fixed stand-in the record's own rendering emits in place of each withheld component.
     *
     * <p>Restated here rather than read from the record, because the production constant is private on
     * purpose: it is a rendering detail and not part of the response contract. Restating it means this
     * test would fail if the production placeholder were changed to something a reader could invert,
     * which is exactly the failure a reviewer wants to see.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

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
    private static JsonNode payloadOf(final TransactionViewResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Serializes a response to the exact characters that go on the wire.
     *
     * @param response the response to render
     * @return the rendered payload as written
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static String wirePayloadOf(final TransactionViewResponse response)
            throws JsonProcessingException {
        return moduleEquivalentMapper().writeValueAsString(response);
    }

    /**
     * Builds a fully populated response, laid out in rows of five so a component cannot silently
     * drift one position out of line past a compiler that sees a wall of interchangeable strings.
     *
     * @param amount the monetary amount the screen renders
     * @param errorMessage the operator message written to the error line
     * @param generalError whether the screen is reporting a whole-screen failure
     * @return a response carrying representative values for every component
     */
    private static TransactionViewResponse aResponse(final BigDecimal amount,
            final String errorMessage, final boolean generalError) {
        return new TransactionViewResponse(
                "CT01", "CardDemo", "07/19/22", "COTRN01C", "View Transaction",
                "10:30:00", TRANSACTION_ID, TRANSACTION_ID, CARD_NUMBER, "01",
                "0005", "POS TERM", "POS PURCHASE - GROCERY", amount, "2022-07-19",
                "2022-07-19", "123456789", "MERCHANT NAME", "SEATTLE", "98101",
                errorMessage, generalError, "TRNIDIN", "/api/transactions", NavigationContext.empty());
    }

    /**
     * Builds a response carrying only the components a single assertion needs.
     *
     * @param searchTransactionId the identifier the operator asked for
     * @param transactionId the identifier that was found
     * @param amount the monetary amount the screen renders
     * @param errorMessage the operator message written to the error line
     * @return a response carrying the supplied values and nothing else
     */
    private static TransactionViewResponse aSparseResponse(final String searchTransactionId,
            final String transactionId, final BigDecimal amount, final String errorMessage) {
        return new TransactionViewResponse(
                null, null, null, null, null,
                null, searchTransactionId, transactionId, null, null,
                null, null, null, amount, null,
                null, null, null, null, null,
                errorMessage, false, null, null, null);
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
            return TransactionViewResponse.class.getDeclaredMethod(componentName)
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
        final Size size = TransactionViewResponse.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the three operator messages")
    class TheThreeOperatorMessages {

        @Test
        @DisplayName("the empty-identifier refusal is the message shown when the operator submits a "
                + "blank search key")
        void theEmptyIdentifierRefusalIsPublished() {
            assertThat(TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE)
                    .isEqualTo("Tran ID can NOT be empty...");
        }

        @Test
        @DisplayName("the not-found and lookup-failed messages are distinct, so an absent transaction "
                + "is never reported as an unreadable one")
        void theNotFoundAndLookupFailedMessagesAreDistinct() {
            assertThat(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE)
                    .isEqualTo("Transaction ID NOT found...");
            assertThat(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE)
                    .isEqualTo("Unable to lookup Transaction...");
            assertThat(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE)
                    .isNotEqualTo(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE);
        }

        @Test
        @DisplayName("all three messages end in the legacy three-dot continuation and fit the "
                + "seventy-eight character error line")
        void allThreeMessagesEndInThreeDotsAndFit() {
            final List<String> messages = List.of(
                    TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE,
                    TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE,
                    TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE);

            assertThat(messages).hasSize(3).doesNotHaveDuplicates();
            assertThat(messages).allSatisfy(message -> {
                assertThat(message).endsWith("...");
                assertThat(message.length()).isLessThanOrEqualTo(78);
            });
        }

        @Test
        @DisplayName("the not-found and lookup-failed texts match the bill-payment screen's own, "
                + "because both screens read the same transaction file and the legacy literals agree")
        void theTransactionMessagesMatchTheBillPaymentScreens() {
            assertThat(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE)
                    .isEqualTo(BillPaymentResponse.MSG_TRANSACTION_ID_NOT_FOUND);
            assertThat(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE)
                    .isEqualTo(BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_TRANSACTION);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and its validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        @Test
        @DisplayName("the response declares twenty-five components in screen order, the furniture, the "
                + "two identifiers, the transaction body, the merchant block and the routing block")
        void theResponseDeclaresTwentyFiveComponentsInScreenOrder() {
            final List<String> declared = Arrays.stream(
                    TransactionViewResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("transactionName", "title01", "currentDate",
                    "programName", "title02", "currentTime", "searchTransactionId",
                    "transactionId", "cardNumber", "typeCode", "categoryCode", "source",
                    "description", "amount", "originationDate", "processingDate", "merchantId",
                    "merchantName", "merchantCity", "merchantZip", "errorMessage", "generalError",
                    "focusScreenFieldId", "nextRoute", "navigationContext");
            assertThat(declared).hasSize(25);
        }

        /**
         * Twenty-one bounded components, and four that are bounded by something other than a width.
         *
         * <p>Every component that is rendered into a fixed-width item on the map carries that item's
         * width, and the focus field identifier is one of them: it names a map item and a map item name
         * is itself seven characters, so the bound is the identifier's own width rather than the width
         * of anything the operator typed. The four that carry no width bound carry a different kind of
         * constraint instead - the amount is bounded by digits and scale, the flag by being a
         * primitive, the route by being a Java routing constant rather than a screen field, and the
         * conversation state by the constraints its own type declares.</p>
         */
        @Test
        @DisplayName("exactly twenty-one components carry a declared upper bound, and the four that do "
                + "not are the amount, the flag, the route and the conversation state")
        void exactlyTwentyOneComponentsCarryAnUpperBound() {
            final List<String> bounded = Arrays.stream(
                    TransactionViewResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(TransactionViewResponseRuleComplianceTest::declaresAnUpperBound).toList();

            assertThat(bounded).hasSize(21);
            assertThat(bounded).contains("focusScreenFieldId");
            assertThat(bounded).doesNotContain("amount", "generalError", "nextRoute",
                    "navigationContext");
        }

        @Test
        @DisplayName("the focus field identifier is bounded by the seven characters a map item name "
                + "occupies, published as its own constant")
        void theFocusFieldIdentifierIsBoundedByTheMapItemNameWidth() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("focusScreenFieldId"))
                    .isEqualTo(TransactionViewResponse.SCREEN_FIELD_ID_LENGTH)
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("the description bound is sixty, the stored width, and not the twenty-six the "
                + "list screen truncates to")
        void theDescriptionBoundIsTheStoredWidth() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("description")).isEqualTo(60);
            assertThat(declaredMaximumLength("description"))
                    .isNotEqualTo(TransactionListResponse.DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("the two identifiers share the sixteen-character transaction key width, and the "
                + "card number shares it too because both are sixteen-character keys")
        void theTwoIdentifiersAndTheCardNumberShareSixteenCharacters() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("searchTransactionId")).isEqualTo(16);
            assertThat(declaredMaximumLength("transactionId")).isEqualTo(16);
            assertThat(declaredMaximumLength("cardNumber")).isEqualTo(16);
        }

        @Test
        @DisplayName("the two date components are ten characters, which is the ISO calendar-date width "
                + "the legacy screen renders rather than the twenty-six of a stored timestamp")
        void theTwoDateComponentsAreTenCharacters() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("originationDate")).isEqualTo(10);
            assertThat(declaredMaximumLength("processingDate")).isEqualTo(10);
        }

        @Test
        @DisplayName("the merchant block carries nine of identifier, thirty of name, twenty-five of "
                + "city and ten of postcode, the stored merchant widths")
        void theMerchantBlockCarriesTheStoredWidths() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("merchantId")).isEqualTo(9);
            assertThat(declaredMaximumLength("merchantName")).isEqualTo(30);
            assertThat(declaredMaximumLength("merchantCity")).isEqualTo(25);
            assertThat(declaredMaximumLength("merchantZip")).isEqualTo(10);
        }

        @Test
        @DisplayName("the type and category codes are two and four characters, the stored code widths")
        void theTypeAndCategoryCodesAreTwoAndFour() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("typeCode")).isEqualTo(2);
            assertThat(declaredMaximumLength("categoryCode")).isEqualTo(4);
        }

        @Test
        @DisplayName("the amount is BigDecimal rather than any floating-point type, because a cent is "
                + "not representable as a binary fraction")
        void theAmountIsBigDecimal() throws NoSuchMethodException {
            assertThat(TransactionViewResponse.class.getDeclaredMethod("amount").getReturnType())
                    .isEqualTo(BigDecimal.class);
            assertThat(Arrays.stream(TransactionViewResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName()).toList())
                    .doesNotContain("double", "float", "java.lang.Double", "java.lang.Float");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final TransactionViewResponse response = aResponse(new BigDecimal("1234.56"),
                    TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE, true);

            assertThat(response.transactionName()).isEqualTo("CT01");
            assertThat(response.title01()).isEqualTo("CardDemo");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.programName()).isEqualTo("COTRN01C");
            assertThat(response.title02()).isEqualTo("View Transaction");
            assertThat(response.currentTime()).isEqualTo("10:30:00");
            assertThat(response.searchTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(response.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.typeCode()).isEqualTo("01");
            assertThat(response.categoryCode()).isEqualTo("0005");
            assertThat(response.source()).isEqualTo("POS TERM");
            assertThat(response.description()).isEqualTo("POS PURCHASE - GROCERY");
            assertThat(response.amount()).isEqualByComparingTo("1234.56");
            assertThat(response.originationDate()).isEqualTo("2022-07-19");
            assertThat(response.processingDate()).isEqualTo("2022-07-19");
            assertThat(response.merchantId()).isEqualTo("123456789");
            assertThat(response.merchantName()).isEqualTo("MERCHANT NAME");
            assertThat(response.merchantCity()).isEqualTo("SEATTLE");
            assertThat(response.merchantZip()).isEqualTo("98101");
            assertThat(response.errorMessage())
                    .isEqualTo(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE);
            assertThat(response.generalError()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo("TRNIDIN");
            assertThat(response.nextRoute()).isEqualTo("/api/transactions");
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a not-found reply can carry the search key with no found identifier, which is "
                + "why the two identifiers are separate components")
        void aNotFoundReplyKeepsTheSearchKey() {
            final TransactionViewResponse response = aSparseResponse(TRANSACTION_ID, null, null,
                    TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE);

            assertThat(response.searchTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(response.transactionId()).isNull();
            assertThat(response.errorMessage())
                    .isEqualTo(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE);
        }

        @Test
        @DisplayName("a wholly absent response reports no violation, because every bound is an upper "
                + "bound and none of the components is mandatory")
        void aWhollyAbsentResponseReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aSparseResponse(null, null, null, null))).isEmpty();
            }
        }

        @Test
        @DisplayName("a fully populated response reports no violation, so the representative fixture "
                + "is itself within every declared bound")
        void aFullyPopulatedResponseReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aResponse(new BigDecimal("1234.56"), null, false))).isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({
            "transactionName,4", "title01,40", "currentDate,8",
            "programName,8", "title02,40", "currentTime,8",
            "searchTransactionId,16", "transactionId,16", "cardNumber,16",
            "typeCode,2", "categoryCode,4", "source,10",
            "description,60", "originationDate,10", "processingDate,10",
            "merchantId,9", "merchantName,30", "merchantCity,25",
            "merchantZip,10", "errorMessage,78", "focusScreenFieldId,7",
        })
        @DisplayName("a bounded component accepts its declared width and rejects one character more")
        void aBoundedComponentAcceptsItsWidthAndRejectsOneMore(final String componentName,
                final int declaredMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(componentName)).isEqualTo(declaredMaximum);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, "X".repeat(declaredMaximum))))
                        .as("%s must accept %d characters", componentName, declaredMaximum)
                        .isEmpty();
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, "X".repeat(declaredMaximum + 1))))
                        .as("%s must reject %d characters", componentName, declaredMaximum + 1)
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString()).isEqualTo(componentName));
            }
        }

        /**
         * Builds a response carrying a single named component and nothing else.
         *
         * <p>The value is placed by name through the record's own canonical constructor after an
         * all-absent template, so the position of every other component is supplied once rather than
         * twenty times.
         *
         * @param componentName the component to populate
         * @param value the value to place in it
         * @return a response carrying only that component
         */
        private TransactionViewResponse responseWith(final String componentName,
                final String value) {
            return new TransactionViewResponse(
                    valueFor("transactionName", componentName, value),
                    valueFor("title01", componentName, value),
                    valueFor("currentDate", componentName, value),
                    valueFor("programName", componentName, value),
                    valueFor("title02", componentName, value),
                    valueFor("currentTime", componentName, value),
                    valueFor("searchTransactionId", componentName, value),
                    valueFor("transactionId", componentName, value),
                    valueFor("cardNumber", componentName, value),
                    valueFor("typeCode", componentName, value),
                    valueFor("categoryCode", componentName, value),
                    valueFor("source", componentName, value),
                    valueFor("description", componentName, value),
                    null,
                    valueFor("originationDate", componentName, value),
                    valueFor("processingDate", componentName, value),
                    valueFor("merchantId", componentName, value),
                    valueFor("merchantName", componentName, value),
                    valueFor("merchantCity", componentName, value),
                    valueFor("merchantZip", componentName, value),
                    valueFor("errorMessage", componentName, value),
                    false, valueFor("focusScreenFieldId", componentName, value), null, null);
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
        @DisplayName("two responses built from identical values are equal and share a hash code")
        void twoResponsesBuiltFromIdenticalValuesAreEqual() {
            final TransactionViewResponse first = aResponse(new BigDecimal("10.00"), null, false);
            final TransactionViewResponse second = aResponse(new BigDecimal("10.00"), null, false);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        /**
         * Scale is part of a monetary value, and the response refuses the wrong one rather than carrying it.
         *
         * <p>Two decimals is not a display preference: the record field this component echoes stores exactly
         * two, so an amount of a different scale did not come from that field. Refusing it at construction is
         * the choice that matters, because {@code BigDecimal} equality is scale-sensitive while its
         * comparison is not - a wrong-scale value would compare equal to the right one everywhere a total was
         * checked and unequal everywhere an instance was, and neither test alone would find it.</p>
         */
        @Test
        @DisplayName("an amount whose scale is not the record field's two decimal places is refused at "
                + "construction rather than normalised")
        void anAmountOfTheWrongScaleIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> aSparseResponse(null, null, new BigDecimal("10.0"), null))
                    .withMessageContaining("must carry scale " + TransactionViewResponse.AMOUNT_SCALE)
                    .withMessageContaining("its scale is 1");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a scale-zero integral amount is refused for the same reason")
                    .isThrownBy(() -> aSparseResponse(null, null, BigDecimal.TEN, null));

            assertThat(new BigDecimal("10.00"))
                    .as("this is why the refusal matters: the two compare equal, so a wrong-scale value "
                            + "would pass every comparison and fail every equality")
                    .isEqualByComparingTo("10.0")
                    .isNotEqualTo(new BigDecimal("10.0"));
        }

        @Test
        @DisplayName("an amount wider than the record field's nine integer digits is refused too, so the "
                + "field's width is enforced and not merely documented")
        void anAmountWiderThanTheFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> aSparseResponse(null, null, new BigDecimal("1000000000.05"), null))
                    .withMessageContaining("must fit " + TransactionViewResponse.AMOUNT_INTEGER_DIGITS
                            + " integer digits")
                    .withMessageContaining("it needs 10");
        }

        /**
         * The rendering withholds the eleven components that describe a cardholder's activity.
         *
         * <p>Both transaction identifiers, the card number, the description, the amount, the two dates
         * and the four merchant components are replaced by a fixed placeholder rather than by any
         * transformation of the value, so nothing about a withheld component - not its length, not a
         * prefix, not a digest - can be recovered from a stringified instance. Read together those
         * eleven values say who spent how much, where and when, which is the whole of what a durable
         * diagnostic channel must not carry.</p>
         *
         * <p>What survives is the screen furniture, the two classification codes, the source, the error
         * message, the flag, the focus field identifier and the route - values that describe which
         * screen was being served rather than whose transaction it was.</p>
         */
        @Test
        @DisplayName("the rendering withholds the eleven components that describe a cardholder's "
                + "activity, and reproduces no fragment of any of them")
        void theRenderingWithholdsTheCardholderActivityComponents() {
            final String rendered = aResponse(new BigDecimal("10.00"),
                    TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE, true).toString();

            assertThat(rendered).startsWith("TransactionViewResponse[");
            assertThat(rendered).contains("searchTransactionId=" + REDACTION_PLACEHOLDER,
                    "transactionId=" + REDACTION_PLACEHOLDER,
                    "cardNumber=" + REDACTION_PLACEHOLDER,
                    "description=" + REDACTION_PLACEHOLDER,
                    "amount=" + REDACTION_PLACEHOLDER,
                    "originationDate=" + REDACTION_PLACEHOLDER,
                    "processingDate=" + REDACTION_PLACEHOLDER,
                    "merchantId=" + REDACTION_PLACEHOLDER,
                    "merchantName=" + REDACTION_PLACEHOLDER,
                    "merchantCity=" + REDACTION_PLACEHOLDER,
                    "merchantZip=" + REDACTION_PLACEHOLDER);
            assertThat(rendered).doesNotContain(TRANSACTION_ID, CARD_NUMBER,
                    "POS PURCHASE - GROCERY", "10.00", "2022-07-19", "123456789",
                    "MERCHANT NAME", "SEATTLE", "98101");
            assertThat(rendered).endsWith("]");
        }

        @Test
        @DisplayName("the rendering still names the screen furniture, the classification codes and the "
                + "routing block, so the withholding is targeted rather than blanket")
        void theRenderingStillNamesTheScreenFurnitureAndRouting() {
            final String rendered = aResponse(new BigDecimal("10.00"),
                    TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE, true).toString();

            assertThat(rendered).contains("transactionName=CT01", "title01=CardDemo",
                    "currentDate=07/19/22", "programName=COTRN01C", "title02=View Transaction",
                    "currentTime=10:30:00", "typeCode=01", "categoryCode=0005", "source=POS TERM",
                    "errorMessage=" + TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE,
                    "generalError=true", "focusScreenFieldId=TRNIDIN",
                    "nextRoute=/api/transactions");
        }

        @Test
        @DisplayName("the withholding is unconditional, so an absent value is rendered as the "
                + "placeholder too and absence is not distinguishable from presence")
        void theWithholdingIsUnconditional() {
            final String rendered = aSparseResponse(null, null, null, null).toString();

            assertThat(rendered).contains("transactionId=" + REDACTION_PLACEHOLDER,
                    "cardNumber=" + REDACTION_PLACEHOLDER, "amount=" + REDACTION_PLACEHOLDER);
            assertThat(rendered).doesNotContain("transactionId=null", "cardNumber=null",
                    "amount=null");
        }

        @Test
        @DisplayName("the payload omits every absent component and keeps the primitive flag")
        void thePayloadOmitsAbsentComponentsAndKeepsTheFlag() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseResponse(TRANSACTION_ID, null, null, null));

            assertThat(payload.has("searchTransactionId")).isTrue();
            assertThat(payload.get("searchTransactionId").asText()).isEqualTo(TRANSACTION_ID);
            assertThat(payload.has("transactionId")).isFalse();
            assertThat(payload.has("amount")).isFalse();
            assertThat(payload.has("errorMessage")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
            assertThat(payload.has("generalError")).isTrue();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("a monetary amount renders in plain notation with its scale intact, read from the "
                + "wire characters rather than from a parsed tree")
        void aMonetaryAmountRendersInPlainNotationWithItsScale() throws JsonProcessingException {
            final String wire = wirePayloadOf(
                    aSparseResponse(null, null, new BigDecimal("999999999.05"), null));

            assertThat(wire).contains("\"amount\":999999999.05");
            assertThat(wire).doesNotContain("E9").doesNotContain("E+");
        }

        @Test
        @DisplayName("a trailing-zero cent survives the wire as two digits, and a negative amount "
                + "keeps its sign, which the legacy signed field requires")
        void aTrailingZeroCentAndANegativeSignSurviveTheWire() throws JsonProcessingException {
            assertThat(wirePayloadOf(aSparseResponse(null, null, new BigDecimal("42.10"), null)))
                    .contains("\"amount\":42.10");
            assertThat(wirePayloadOf(aSparseResponse(null, null, new BigDecimal("-42.10"), null)))
                    .contains("\"amount\":-42.10");
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged, "
                + "monetary scale included")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final TransactionViewResponse original = aResponse(new BigDecimal("1234.56"),
                    TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE, true);
            final TransactionViewResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionViewResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.amount().scale()).isEqualTo(2);
        }
    }
}
