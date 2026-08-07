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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract tests for {@link TransactionAddResponse}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The declared shape of the transaction-add response: its twenty-eight components and their
 * order, the twenty-three widths it measures and the five components it deliberately leaves
 * unmeasured, the thirty-five published message texts, the single normalisation its compact
 * constructor performs, and the eight components its diagnostic rendering withholds.
 *
 * <h2>The success text joins three fragments and produces two consecutive spaces</h2>
 *
 * <p>Three fragments compose the success message and none is joined here. The first ends with a
 * space and the second begins with one, so the assembled text carries <strong>two consecutive
 * spaces</strong> after its first full stop. That doubling is an estate idiom rather than a defect
 * and byte equivalence depends on it, so the fragments are asserted individually — including the
 * boundary spaces — and then asserted again as the exact assembled sentence a first-ever successful
 * add produces.
 *
 * <h2>The success path returns blank echoed values, so nothing may be mandatory</h2>
 *
 * <p>The legacy program clears the screen before redisplaying it on success, so a successful
 * response legitimately carries a new identifier and a message alongside fourteen blank or absent
 * echoed values. Not one component may therefore be marked mandatory, and the absence of any
 * presence constraint is asserted rather than left implicit.
 *
 * <h2>One summary message, any number of independent field errors</h2>
 *
 * <p>The legacy cascade is first-error-wins, so the response carries exactly one summary text. The
 * per-field collection is independent of it in both directions, and so is the general-error
 * indicator: each of the three can be present without either of the others, and every combination
 * is exercised.
 *
 * <h2>Eight components are withheld from the diagnostic rendering, not one</h2>
 *
 * <p>The rendering withholds the account key and card number because they identify a cardholder,
 * and the amount, description and four merchant components because together with the retained
 * identifier they reconstruct what a cardholder spent and where. Both halves are asserted: every
 * withheld component is proven absent from the text, and every retained one proven present, so a
 * later narrowing or widening of that set fails here.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>Payloads come from {@link JsonContractSupport#declaredSettingsMapper()}, a mapper carrying the
 * four serialisation settings this module declares, written out by hand in one place rather than
 * copied into every suite. That evidences the shape this type takes <em>under those settings</em>,
 * and nothing more. It is not evidence about the mapper a deployed instance holds, and no assertion
 * below is worded as though it were; {@code ApplicationJsonContractTest} is the in-boundary evidence
 * for the deployed object. The amount is asserted against the emitted characters through {@link
 * JsonContractSupport#renderedValueToken(String, String)} and never against a re-parsed tree,
 * because re-parsing a JSON number loses the scale the contract is about.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy antecedents cited by the type under test: program {@code app/cbl/COTRN02C.cbl}, symbolic
 * map {@code app/cpy-bms/COTRN02.CPY}, mapset {@code app/bms/COTRN02.bms} and record layout {@code
 * app/cpy/CVTRA05Y.cpy}. Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}; upstream
 * release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * reproduced here.
 */
@DisplayName("TransactionAddResponse :: response contract of legacy transaction CT02")
class TransactionAddResponseCoverageTest {

    /** The twenty-eight components, in the order the record declares them. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "newTransactionId",
            "accountId",
            "cardNumber",
            "typeCode",
            "categoryCode",
            "source",
            "description",
            "amountEntered",
            "amount",
            "originationDate",
            "processingDate",
            "merchantId",
            "merchantName",
            "merchantCity",
            "merchantZip",
            "confirmationFlag",
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "message",
            "generalError",
            "fieldErrors",
            "focusScreenFieldId",
            "nextRoute",
            "navigationContext");

    /** The twenty-three components that carry a declared maximum length. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "newTransactionId",
            "accountId",
            "cardNumber",
            "typeCode",
            "categoryCode",
            "source",
            "description",
            "amountEntered",
            "originationDate",
            "processingDate",
            "merchantId",
            "merchantName",
            "merchantCity",
            "merchantZip",
            "confirmationFlag",
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "message",
            "focusScreenFieldId");

    /** The five components that carry no declared maximum length. */
    private static final List<String> UNBOUNDED_COMPONENTS = List.of(
            "amount", "generalError", "fieldErrors", "nextRoute", "navigationContext");

    /** The fourteen echoed input items, in map declaration order. */
    private static final List<String> ECHOED_COMPONENTS = List.of(
            "accountId",
            "cardNumber",
            "typeCode",
            "categoryCode",
            "source",
            "description",
            "amountEntered",
            "originationDate",
            "processingDate",
            "merchantId",
            "merchantName",
            "merchantCity",
            "merchantZip",
            "confirmationFlag");

    /** The nine components the diagnostic rendering withholds. */
    private static final List<String> WITHHELD_COMPONENTS = List.of(
            "accountId",
            "cardNumber",
            "description",
            "amountEntered",
            "amount",
            "merchantId",
            "merchantName",
            "merchantCity",
            "merchantZip");

    /** The fixed stand-in the diagnostic rendering writes in place of a withheld component. */
    private static final String PLACEHOLDER = "***REDACTED***";

    /** Sixteen characters, fifteen of them leading zeros: the first key the system ever issues. */
    private static final String NEW_TRANSACTION_ID = "0000000000000001";

    /** Eleven-character account key with contractual leading zeros. */
    private static final String ACCOUNT_ID = "00000000011";

    /** Sixteen-character card number. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Two-character transaction type code. */
    private static final String TYPE_CODE = "01";

    /** Four-character category code; text, so the leading zeros survive. */
    private static final String CATEGORY_CODE = "0002";

    /** Ten-character source, space-padded exactly as the map carries it. */
    private static final String SOURCE = "POS TERM  ";

    /** Sixty-character-bounded description; sixty here, twenty-six on the list map. */
    private static final String DESCRIPTION = "GROCERY PURCHASE - DOWNTOWN STORE";

    /** Exact amount at the contractual scale of two. */
    private static final BigDecimal AMOUNT = new BigDecimal("1234.56");

    /** Ten-character origination date as the screen carries it. */
    private static final String ORIGINATION_DATE = "2022-01-01";

    /** Ten-character processing date as the screen carries it. */
    private static final String PROCESSING_DATE = "2022-01-02";

    /** Nine-character merchant identifier; text, so all nine characters survive. */
    private static final String MERCHANT_ID = "999999999";

    /** Thirty-character-bounded merchant name; thirty here, fifty in the record. */
    private static final String MERCHANT_NAME = "DOWNTOWN GROCERY";

    /** Twenty-five-character-bounded merchant city; twenty-five here, fifty in the record. */
    private static final String MERCHANT_CITY = "SEATTLE";

    /** Ten-character merchant postal code. */
    private static final String MERCHANT_ZIP = "98101";

    /** One-character confirmation answer. */
    private static final String CONFIRMATION_FLAG = "Y";

    /** Transaction identifier this screen displays in its header. */
    private static final String TRANSACTION_NAME = "CT02";

    /** First screen title line. */
    private static final String TITLE_01 = "AWS Mainframe Modernization";

    /** Clock date as the screen renders it. */
    private static final String CURRENT_DATE = "08/02/26";

    /** Program name this screen displays in its header. */
    private static final String PROGRAM_NAME = "COTRN02C";

    /** Second screen title line. */
    private static final String TITLE_02 = "CardDemo";

    /** Clock time as the screen renders it. */
    private static final String CURRENT_TIME = "14:35:07";

    /** Map field name the client should place the cursor in; seven characters at most. */
    private static final String FOCUS_SCREEN_FIELD_ID = "TRNAMT";

    /** Declarative next route; deliberately unbounded because it is service-owned. */
    private static final String NEXT_ROUTE = "/api/transactions/add";

    /**
     * The exact assembled success sentence for the first key the system ever issues, written out by
     * hand rather than composed from the constants, so that the doubled space is proven and not
     * merely propagated.
     */
    private static final String ASSEMBLED_SUCCESS_TEXT =
            "Transaction added successfully.  Your Tran ID is 0000000000000001.";

    /** A field error whose state reports a blank field. */
    private static final ErrorResponse.FieldError MISSING_AMOUNT =
            new ErrorResponse.FieldError(
                    "amount",
                    "TRNAMT",
                    ErrorResponse.FieldState.MISSING,
                    "Amount can NOT be empty...");

    /** A field error whose state reports a badly filled field. */
    private static final ErrorResponse.FieldError INVALID_TYPE_CODE =
            new ErrorResponse.FieldError(
                    "typeCode",
                    "TTYPCD",
                    ErrorResponse.FieldState.INVALID,
                    "Type CD must be Numeric...");

    /** Bean Validation factory, opened once for the class and closed after it. */
    private static ValidatorFactory validatorFactory;

    /** Validator obtained from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the Bean Validation factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the Bean Validation factory opened for this class. */
    @AfterAll
    static void closeValidatorFactory() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * Supplies each published message text alongside an independently written expectation of its
     * exact characters and length.
     *
     * @return the published constant, the hand-written expectation and the expected length
     */
    static Stream<Arguments> publishedMessages() {
        return Stream.of(
                Arguments.of(
                        TransactionAddResponse.MESSAGE_CONFIRM_PROMPT,
                        "Confirm to add this transaction...",
                        34),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_CONFIRM_INVALID,
                        "Invalid value. Valid values are (Y/N)...",
                        40),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_NUMERIC,
                        "Account ID must be Numeric...",
                        29),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_NUMERIC,
                        "Card Number must be Numeric...",
                        30),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_KEY_NOT_ENTERED,
                        "Account or Card Number must be entered...",
                        41),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY,
                        "Type CD can NOT be empty...",
                        27),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_CATEGORY_CODE_EMPTY,
                        "Category CD can NOT be empty...",
                        31),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_SOURCE_EMPTY,
                        "Source can NOT be empty...",
                        26),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_DESCRIPTION_EMPTY,
                        "Description can NOT be empty...",
                        31),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_AMOUNT_EMPTY,
                        "Amount can NOT be empty...",
                        26),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_ORIGINATION_DATE_EMPTY,
                        "Orig Date can NOT be empty...",
                        29),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_PROCESSING_DATE_EMPTY,
                        "Proc Date can NOT be empty...",
                        29),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_MERCHANT_ID_EMPTY,
                        "Merchant ID can NOT be empty...",
                        31),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_MERCHANT_NAME_EMPTY,
                        "Merchant Name can NOT be empty...",
                        33),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_MERCHANT_CITY_EMPTY,
                        "Merchant City can NOT be empty...",
                        33),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_MERCHANT_ZIP_EMPTY,
                        "Merchant Zip can NOT be empty...",
                        32),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_TYPE_CODE_NOT_NUMERIC,
                        "Type CD must be Numeric...",
                        26),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_CATEGORY_CODE_NOT_NUMERIC,
                        "Category CD must be Numeric...",
                        30),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_MERCHANT_ID_NOT_NUMERIC,
                        "Merchant ID must be Numeric...",
                        30),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_AMOUNT_FORMAT,
                        "Amount should be in format -99999999.99",
                        39),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT,
                        "Orig Date should be in format YYYY-MM-DD",
                        40),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_PROCESSING_DATE_FORMAT,
                        "Proc Date should be in format YYYY-MM-DD",
                        40),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID,
                        "Orig Date - Not a valid date...",
                        31),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_PROCESSING_DATE_INVALID,
                        "Proc Date - Not a valid date...",
                        31),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND,
                        "Account ID NOT found...",
                        23),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED,
                        "Unable to lookup Acct in XREF AIX file...",
                        41),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND,
                        "Card Number NOT found...",
                        24),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_CARD_XREF_LOOKUP_FAILED,
                        "Unable to lookup Card # in XREF file...",
                        39),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_TRANSACTION_ID_NOT_FOUND,
                        "Transaction ID NOT found...",
                        27),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_TRANSACTION_LOOKUP_FAILED,
                        "Unable to lookup Transaction...",
                        31),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_SUCCESS_PREFIX,
                        "Transaction added successfully. ",
                        32),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL, " Your Tran ID is ", 17),
                Arguments.of(TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR, ".", 1),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID,
                        "Tran ID already exist...",
                        24),
                Arguments.of(
                        TransactionAddResponse.MESSAGE_ADD_FAILED,
                        "Unable to Add Transaction...",
                        28));
    }

    /**
     * Supplies the twenty-nine published texts that terminate in an ellipsis.
     *
     * @return the ellipsis-terminated texts
     */
    static Stream<Arguments> ellipsisTerminatedMessages() {
        return Stream.of(
                Arguments.of(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT),
                Arguments.of(TransactionAddResponse.MESSAGE_CONFIRM_INVALID),
                Arguments.of(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_NUMERIC),
                Arguments.of(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_NUMERIC),
                Arguments.of(TransactionAddResponse.MESSAGE_KEY_NOT_ENTERED),
                Arguments.of(TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_CATEGORY_CODE_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_SOURCE_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_DESCRIPTION_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_AMOUNT_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_PROCESSING_DATE_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_MERCHANT_ID_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_MERCHANT_NAME_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_MERCHANT_CITY_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_MERCHANT_ZIP_EMPTY),
                Arguments.of(TransactionAddResponse.MESSAGE_TYPE_CODE_NOT_NUMERIC),
                Arguments.of(TransactionAddResponse.MESSAGE_CATEGORY_CODE_NOT_NUMERIC),
                Arguments.of(TransactionAddResponse.MESSAGE_MERCHANT_ID_NOT_NUMERIC),
                Arguments.of(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID),
                Arguments.of(TransactionAddResponse.MESSAGE_PROCESSING_DATE_INVALID),
                Arguments.of(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND),
                Arguments.of(TransactionAddResponse.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED),
                Arguments.of(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND),
                Arguments.of(TransactionAddResponse.MESSAGE_CARD_XREF_LOOKUP_FAILED),
                Arguments.of(TransactionAddResponse.MESSAGE_TRANSACTION_ID_NOT_FOUND),
                Arguments.of(TransactionAddResponse.MESSAGE_TRANSACTION_LOOKUP_FAILED),
                Arguments.of(TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID),
                Arguments.of(TransactionAddResponse.MESSAGE_ADD_FAILED));
    }

    /**
     * Supplies the six published texts that do not terminate in an ellipsis.
     *
     * @return the texts carrying no terminal ellipsis
     */
    static Stream<Arguments> nonEllipsisMessages() {
        return Stream.of(
                Arguments.of(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT),
                Arguments.of(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT),
                Arguments.of(TransactionAddResponse.MESSAGE_PROCESSING_DATE_FORMAT),
                Arguments.of(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX),
                Arguments.of(TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL),
                Arguments.of(TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR));
    }

    /**
     * Builds a response from the supplied components, leaving every unnamed component absent.
     *
     * @param text the text components to populate, keyed by declared component name
     * @param amount the exact amount to carry, which may be {@code null}
     * @param generalError whether the response reports a general failure
     * @param fieldErrors the per-field errors to carry, which may be {@code null}
     * @param navigation the echoed navigation state, which may be {@code null}
     * @return a response carrying exactly the supplied components
     */
    private static TransactionAddResponse build(
            Map<String, String> text,
            BigDecimal amount,
            boolean generalError,
            List<ErrorResponse.FieldError> fieldErrors,
            NavigationContext navigation) {
        return new TransactionAddResponse(
                text.get("newTransactionId"),
                text.get("accountId"),
                text.get("cardNumber"),
                text.get("typeCode"),
                text.get("categoryCode"),
                text.get("source"),
                text.get("description"),
                text.get("amountEntered"),
                amount,
                text.get("originationDate"),
                text.get("processingDate"),
                text.get("merchantId"),
                text.get("merchantName"),
                text.get("merchantCity"),
                text.get("merchantZip"),
                text.get("confirmationFlag"),
                text.get("transactionName"),
                text.get("title01"),
                text.get("currentDate"),
                text.get("programName"),
                text.get("title02"),
                text.get("currentTime"),
                text.get("message"),
                generalError,
                fieldErrors,
                text.get("focusScreenFieldId"),
                text.get("nextRoute"),
                navigation);
    }

    /**
     * Builds a response carrying exactly one text component.
     *
     * @param component the component to populate
     * @param value the value to place in that component
     * @return a response carrying only the named component
     */
    private static TransactionAddResponse carrying(String component, String value) {
        Map<String, String> text = new HashMap<>();
        text.put(component, value);
        return build(text, null, false, null, null);
    }

    /**
     * Builds a response carrying only the exact amount.
     *
     * @param amount the amount to carry
     * @return a response carrying only the amount
     */
    private static TransactionAddResponse carryingAmount(BigDecimal amount) {
        return build(Map.of(), amount, false, null, null);
    }

    /**
     * Builds the empty response: no component populated, no field error and the indicator clear.
     *
     * @return a response with every optional component absent
     */
    private static TransactionAddResponse empty() {
        return build(Map.of(), null, false, null, null);
    }

    /**
     * Builds the every-component fixture, optionally carrying the echoed navigation state.
     *
     * @param navigation the echoed navigation state, which may be {@code null}
     * @return a response carrying every component
     */
    private static TransactionAddResponse populated(NavigationContext navigation) {
        Map<String, String> text = new HashMap<>();
        text.put("newTransactionId", NEW_TRANSACTION_ID);
        text.put("accountId", ACCOUNT_ID);
        text.put("cardNumber", CARD_NUMBER);
        text.put("typeCode", TYPE_CODE);
        text.put("categoryCode", CATEGORY_CODE);
        text.put("source", SOURCE);
        text.put("description", DESCRIPTION);
        text.put("amountEntered", AMOUNT.toPlainString());
        text.put("originationDate", ORIGINATION_DATE);
        text.put("processingDate", PROCESSING_DATE);
        text.put("merchantId", MERCHANT_ID);
        text.put("merchantName", MERCHANT_NAME);
        text.put("merchantCity", MERCHANT_CITY);
        text.put("merchantZip", MERCHANT_ZIP);
        text.put("confirmationFlag", CONFIRMATION_FLAG);
        text.put("transactionName", TRANSACTION_NAME);
        text.put("title01", TITLE_01);
        text.put("currentDate", CURRENT_DATE);
        text.put("programName", PROGRAM_NAME);
        text.put("title02", TITLE_02);
        text.put("currentTime", CURRENT_TIME);
        text.put("message", ASSEMBLED_SUCCESS_TEXT);
        text.put("focusScreenFieldId", FOCUS_SCREEN_FIELD_ID);
        text.put("nextRoute", NEXT_ROUTE);

        return build(
                text, AMOUNT, true, List.of(MISSING_AMOUNT, INVALID_TYPE_CODE), navigation);
    }

    /**
     * Serialises the supplied response with the module's declared settings.
     *
     * @param response the response to serialise
     * @return the emitted JSON text
     * @throws JsonProcessingException if serialisation fails, which fails the calling test
     */
    private static String payloadOf(TransactionAddResponse response)
            throws JsonProcessingException {
        return JsonContractSupport.declaredSettingsMapper().writeValueAsString(response);
    }

    /** The declared shape of the record: components, order, widths and declared surface. */
    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        /**
         * Reads the declared maximum length of a component.
         *
         * @param component the component name
         * @return the declared maximum length
         * @throws NoSuchFieldException if the component does not exist, failing the calling test
         */
        private int boundOf(String component) throws NoSuchFieldException {
            Size size =
                    TransactionAddResponse.class
                            .getDeclaredField(component)
                            .getAnnotation(Size.class);
            assertThat(size)
                    .as("component %s must declare a maximum length", component)
                    .isNotNull();
            return size.max();
        }

        /** The twenty-eight components appear in the documented order. */
        @Test
        @DisplayName("declares twenty-eight components in the documented order")
        void theComponentsAreDeclaredInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(TransactionAddResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        /**
         * The generated key leads, the screen amount stays beside the persisted amount, and the
         * protocol components with no map item of their own come last.
         */
        @Test
        @DisplayName("orders the generated key, the echoed items and the protocol components")
        void theComponentGroupsAppearInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(TransactionAddResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared.get(0)).isEqualTo("newTransactionId");
            assertThat(declared.subList(1, 7))
                    .containsExactlyElementsOf(ECHOED_COMPONENTS.subList(0, 6));
            assertThat(declared.get(7)).isEqualTo("amountEntered");
            assertThat(declared.get(8)).isEqualTo("amount");
            assertThat(declared.subList(9, 16))
                    .containsExactlyElementsOf(ECHOED_COMPONENTS.subList(7, 14));
            assertThat(declared.subList(16, 23))
                    .containsExactly(
                            "transactionName",
                            "title01",
                            "currentDate",
                            "programName",
                            "title02",
                            "currentTime",
                            "message");
            assertThat(declared.subList(23, 28))
                    .containsExactly(
                            "generalError",
                            "fieldErrors",
                            "focusScreenFieldId",
                            "nextRoute",
                            "navigationContext");
        }

        /** Each bounded component declares the width the symbolic map declares. */
        @ParameterizedTest(name = "{0} is bounded at {1}")
        @CsvSource({
            "newTransactionId,16",
            "accountId,11",
            "cardNumber,16",
            "typeCode,2",
            "categoryCode,4",
            "source,10",
            "description,60",
            "amountEntered,12",
            "originationDate,10",
            "processingDate,10",
            "merchantId,9",
            "merchantName,30",
            "merchantCity,25",
            "merchantZip,10",
            "confirmationFlag,1",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "message,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("declares each screen width")
        void eachBoundedComponentDeclaresItsDocumentedWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(boundOf(component)).isEqualTo(width);
        }

        /** The amount, indicator, collection, route and navigation state carry no width. */
        @ParameterizedTest(name = "{0} declares no width")
        @ValueSource(
                strings = {
                    "amount",
                    "generalError",
                    "fieldErrors",
                    "nextRoute",
                    "navigationContext"
                })
        @DisplayName("leaves the amount, indicator, collection, route and state unbounded")
        void theUnboundedComponentsDeclareNoWidth(String component) throws NoSuchFieldException {
            assertThat(
                            TransactionAddResponse.class
                                    .getDeclaredField(component)
                                    .getAnnotation(Size.class))
                    .isNull();
        }

        /** Every component is accounted for as bounded or unbounded. */
        @Test
        @DisplayName("accounts for every component as bounded or unbounded")
        void everyComponentIsAccountedFor() {
            List<String> partition = new ArrayList<>(BOUNDED_COMPONENTS);
            partition.addAll(UNBOUNDED_COMPONENTS);

            assertThat(partition).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
            assertThat(BOUNDED_COMPONENTS).hasSize(23);
            assertThat(UNBOUNDED_COMPONENTS).hasSize(5);
        }

        /** The amount is an exact decimal; no floating-point type may stand in for it. */
        @Test
        @DisplayName("carries the amount as an exact decimal")
        void theAmountIsAnExactDecimal() throws NoSuchFieldException {
            assertThat(TransactionAddResponse.class.getDeclaredField("amount").getType())
                    .isEqualTo(BigDecimal.class);
        }

        /** The amount is the only decimal; every other value component is text. */
        @Test
        @DisplayName("declares exactly one decimal component")
        void exactlyOneComponentIsADecimal() {
            List<String> decimals =
                    Arrays.stream(TransactionAddResponse.class.getRecordComponents())
                            .filter(component -> component.getType() == BigDecimal.class)
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(decimals).containsExactly("amount");
        }

        /** Every identifier and code is text, never a numeric type. */
        @ParameterizedTest(name = "{0} is text")
        @ValueSource(
                strings = {
                    "newTransactionId",
                    "accountId",
                    "cardNumber",
                    "typeCode",
                    "categoryCode",
                    "merchantId"
                })
        @DisplayName("carries every identifier and code as text")
        void everyIdentifierIsText(String component) throws NoSuchFieldException {
            assertThat(TransactionAddResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(String.class);
        }

        /** Both dates are text; no date or time type is imported anywhere in the type. */
        @ParameterizedTest(name = "{0} is text")
        @ValueSource(strings = {"originationDate", "processingDate"})
        @DisplayName("carries both dates as text")
        void bothDatesAreText(String component) throws NoSuchFieldException {
            assertThat(TransactionAddResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(String.class);
        }

        /** No component is a date, time or temporal type. */
        @Test
        @DisplayName("declares no temporal component")
        void noComponentIsATemporalType() {
            assertThat(TransactionAddResponse.class.getRecordComponents())
                    .allSatisfy(
                            component ->
                                    assertThat(component.getType().getName())
                                            .doesNotStartWith("java.time"));
        }

        /** The general-error indicator is a primitive boolean, stated explicitly. */
        @Test
        @DisplayName("carries the general-error indicator as a primitive boolean")
        void theGeneralErrorIndicatorIsAPrimitiveBoolean() throws NoSuchFieldException {
            assertThat(TransactionAddResponse.class.getDeclaredField("generalError").getType())
                    .isEqualTo(boolean.class);
        }

        /**
         * The declared surface beyond the accessors is the presence test, the rendering and one private
         * helper.
         *
         * <p>The presence test and the rendering are published because a caller and a logger read them.
         * The helper is the one thing the compact constructor does beyond copying the collection: it
         * confirms the amount has the decimal shape of the record field it represents. It is private,
         * static and void because it decides nothing and returns nothing - it either accepts an amount or
         * refuses it - and its exact modifiers are asserted, because a helper that became public or began
         * returning a value would be new published surface rather than an implementation detail.</p>
         *
         * @throws NoSuchMethodException if the helper is not declared, failing this test
         */
        @Test
        @DisplayName("declares the presence test, the rendering and one private helper")
        void theDeclaredSurfaceBeyondTheAccessorsIsThePresenceTest() throws NoSuchMethodException {
            List<String> declared =
                    Arrays.stream(TransactionAddResponse.class.getDeclaredMethods())
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(name -> !"equals".equals(name) && !"hashCode".equals(name))
                            .filter(name -> !EXPECTED_COMPONENTS.contains(name))
                            .toList();

            assertThat(declared)
                    .containsExactlyInAnyOrder("hasFieldErrors", "toString", "requireRecordShape");

            Method helper =
                    TransactionAddResponse.class.getDeclaredMethod(
                            "requireRecordShape", BigDecimal.class);

            assertThat(Modifier.isPrivate(helper.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(helper.getModifiers())).isTrue();
            assertThat(helper.getReturnType()).isEqualTo(void.class);
        }

        /** Only the compact canonical constructor exists, taking all twenty-seven components. */
        @Test
        @DisplayName("keeps only the compact canonical constructor")
        void onlyTheCompactCanonicalConstructorExists() {
            assertThat(TransactionAddResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(
                            TransactionAddResponse.class
                                    .getDeclaredConstructors()[0]
                                    .getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
        }

        /** No serialisation annotation appears on any component. */
        @Test
        @DisplayName("declares no serialisation annotation on any component")
        void noSerialisationAnnotationAppearsOnAnyComponent() {
            List<Annotation> annotations = new ArrayList<>();
            for (Field field : TransactionAddResponse.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }

            assertThat(annotations)
                    .as("no custom serializer is registered and no setting is overridden here")
                    .noneMatch(
                            annotation ->
                                    annotation
                                            .annotationType()
                                            .getName()
                                            .startsWith("com.fasterxml.jackson"));
        }

        /**
         * No constraint other than a maximum length appears anywhere: the ordered, message-bearing
         * legacy cascade cannot be expressed by unordered declarative constraints, and reproducing
         * it here would change which single message an operator sees.
         */
        @Test
        @DisplayName("declares no constraint other than a maximum length")
        void noConstraintOtherThanAMaximumLengthIsDeclared() {
            List<Annotation> constraints = new ArrayList<>();
            for (Field field : TransactionAddResponse.class.getDeclaredFields()) {
                Arrays.stream(field.getAnnotations())
                        .filter(
                                annotation ->
                                        annotation
                                                .annotationType()
                                                .getName()
                                                .startsWith("jakarta.validation"))
                        .forEach(constraints::add);
            }

            assertThat(constraints)
                    .allSatisfy(
                            annotation ->
                                    assertThat(annotation.annotationType()).isEqualTo(Size.class));
            assertThat(constraints).hasSize(BOUNDED_COMPONENTS.size());
        }

        /**
         * The static surface is thirty-five published texts, two published record-shape figures and one
         * private stand-in.
         *
         * <p>The two figures are not message texts, so the every-static-starts-with-MESSAGE claim is now
         * scoped to the texts alone and the figures are named explicitly. They are published rather than
         * private because the compact constructor refuses an amount that contradicts them, and a producer
         * needs to be able to read the rule it will be held to. The stand-in remains the only unpublished
         * static, because it is an implementation detail of the rendering.</p>
         */
        @Test
        @DisplayName("publishes thirty-five message texts and three shape figures, and withholds "
                + "one stand-in")
        void theStaticSurfaceIsThirtyFivePublishedTextsAndOnePrivateStandIn() {
            List<Field> statics =
                    Arrays.stream(TransactionAddResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .toList();

            List<Field> published =
                    statics.stream()
                            .filter(field -> Modifier.isPublic(field.getModifiers()))
                            .toList();
            List<Field> internal =
                    statics.stream()
                            .filter(field -> !Modifier.isPublic(field.getModifiers()))
                            .toList();

            assertThat(published).hasSize(38);
            assertThat(
                            published.stream()
                                    .filter(field -> field.getType() == String.class)
                                    .toList())
                    .hasSize(35)
                    .allSatisfy(field -> assertThat(field.getName()).startsWith("MESSAGE_"));
            assertThat(
                            published.stream()
                                    .filter(field -> field.getType() == int.class)
                                    .map(Field::getName)
                                    .toList())
                    .containsExactlyInAnyOrder(
                            "AMOUNT_SCALE", "AMOUNT_ENTERED_LENGTH", "AMOUNT_INTEGER_DIGITS");
            assertThat(internal).hasSize(1);
            assertThat(internal.get(0).getName()).isEqualTo("REDACTION_PLACEHOLDER");
        }

        /**
         * The decimal shape the constructor holds the amount to is published, and it is the shape of the
         * record field the amount represents.
         *
         * <p>Nine integer digits and two decimal places is {@code TRAN-AMT PIC S9(09)V99}, so the two
         * figures together restate that field's precision of eleven.</p>
         */
        @Test
        @DisplayName("publishes the record shape of the amount")
        void theRecordShapeOfTheAmountIsPublished() {
            assertThat(TransactionAddResponse.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionAddResponse.AMOUNT_ENTERED_LENGTH).isEqualTo(12);
            assertThat(TransactionAddResponse.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(
                            TransactionAddResponse.AMOUNT_INTEGER_DIGITS
                                    + TransactionAddResponse.AMOUNT_SCALE)
                    .as("nine integer digits and two decimal places is a precision of eleven")
                    .isEqualTo(11);
        }
    }

    /** The thirty-five published message texts and the three success fragments. */
    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        /** Each published text matches its independently written expectation exactly. */
        @ParameterizedTest(name = "[{index}] {1}")
        @MethodSource("com.carddemo.api.dto.TransactionAddResponseCoverageTest#publishedMessages")
        @DisplayName("publishes each message text exactly")
        void eachPublishedTextMatchesItsExpectation(String actual, String expected, int length) {
            assertThat(actual).isEqualTo(expected).hasSize(length);
        }

        /** Every published text fits the seventy-eight-character message bound. */
        @ParameterizedTest(name = "[{index}] fits the message bound")
        @MethodSource("com.carddemo.api.dto.TransactionAddResponseCoverageTest#publishedMessages")
        @DisplayName("keeps every published text within the message bound")
        void everyPublishedTextFitsTheMessageBound(String actual, String expected, int length) {
            assertThat(actual).hasSizeLessThanOrEqualTo(78);
            assertThat(length).isLessThanOrEqualTo(78);
        }

        /** Twenty-nine texts terminate in exactly three dots with no space before them. */
        @ParameterizedTest(name = "[{index}] ends in three dots")
        @MethodSource(
                "com.carddemo.api.dto.TransactionAddResponseCoverageTest#ellipsisTerminatedMessages")
        @DisplayName("terminates twenty-nine texts in three dots")
        void anEllipsisTerminatedTextEndsInThreeDots(String text) {
            assertThat(text).endsWith("...").doesNotEndWith("....").doesNotEndWith(" ...");
        }

        /** Six texts carry no terminal ellipsis at all. */
        @ParameterizedTest(name = "[{index}] carries no ellipsis")
        @MethodSource("com.carddemo.api.dto.TransactionAddResponseCoverageTest#nonEllipsisMessages")
        @DisplayName("gives six texts no terminal ellipsis")
        void aNonEllipsisTextCarriesNoTerminalEllipsis(String text) {
            assertThat(text).doesNotEndWith("...");
        }

        /** The twenty-nine and the six partition the thirty-five published texts. */
        @Test
        @DisplayName("partitions the thirty-five texts into twenty-nine and six")
        void theTwoGroupsPartitionThePublishedTexts() {
            assertThat(ellipsisTerminatedMessages().count()).isEqualTo(29);
            assertThat(nonEllipsisMessages().count()).isEqualTo(6);
            assertThat(publishedMessages().count()).isEqualTo(35);
        }

        /** Every published text is distinct, so no two arms of the cascade collide. */
        @Test
        @DisplayName("publishes thirty-five distinct texts")
        void everyPublishedTextIsDistinct() {
            List<String> texts =
                    publishedMessages()
                            .map(arguments -> (String) arguments.get()[0])
                            .toList();

            assertThat(texts).doesNotHaveDuplicates().hasSize(35);
        }

        /** The first success fragment carries a significant trailing space. */
        @Test
        @DisplayName("gives the first success fragment a trailing space")
        void theFirstSuccessFragmentCarriesATrailingSpace() {
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX)
                    .endsWith(" ")
                    .isEqualTo("Transaction added successfully. ");
        }

        /** The second success fragment carries significant leading and trailing spaces. */
        @Test
        @DisplayName("gives the second success fragment leading and trailing spaces")
        void theSecondSuccessFragmentCarriesBoundarySpaces() {
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL)
                    .startsWith(" ")
                    .endsWith(" ")
                    .isEqualTo(" Your Tran ID is ");
        }

        /** The terminator is a single full stop. */
        @Test
        @DisplayName("terminates the success text with a single full stop")
        void theTerminatorIsASingleFullStop() {
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR).isEqualTo(".");
        }

        /**
         * The three fragments assemble to the exact sentence a first-ever successful add produces,
         * including the two consecutive spaces the join creates.
         */
        @Test
        @DisplayName("assembles to the exact success sentence, doubled space included")
        void theFragmentsAssembleToTheExactSuccessSentence() {
            String assembled =
                    TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                            + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL
                            + NEW_TRANSACTION_ID
                            + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

            assertThat(assembled).isEqualTo(ASSEMBLED_SUCCESS_TEXT).hasSize(66);
            assertThat(assembled)
                    .as("the join of the two fragments produces two consecutive spaces")
                    .contains("successfully.  Your");
        }

        /** No fragment is joined by this type, and no whitespace is collapsed. */
        @Test
        @DisplayName("joins no fragment and collapses no whitespace")
        void noFragmentIsJoinedByThisType() {
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX)
                    .doesNotContain("Your Tran ID");
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL)
                    .doesNotContain("Transaction added");
            assertThat(ASSEMBLED_SUCCESS_TEXT)
                    .as("the assembled text is not published as a constant")
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX);
        }

        /**
         * The duplicate-key text keeps its singular verb, which deviates from ordinary English and
         * is reproduced rather than corrected.
         */
        @Test
        @DisplayName("keeps the singular verb of the duplicate-key text")
        void theDuplicateKeyTextKeepsItsSingularVerb() {
            assertThat(TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID)
                    .isEqualTo("Tran ID already exist...")
                    .doesNotContain("exists");
        }

        /** The amount-shape text names the edited display form the screen shows. */
        @Test
        @DisplayName("names the edited display form in the amount-shape text")
        void theAmountShapeTextNamesTheEditedForm() {
            assertThat(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT)
                    .isEqualTo("Amount should be in format -99999999.99");
        }

        /** The two date-shape texts differ only in their field label. */
        @Test
        @DisplayName("differentiates the two date-shape texts only by field label")
        void theTwoDateShapeTextsDifferOnlyByLabel() {
            assertThat(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT)
                    .isEqualTo("Orig Date should be in format YYYY-MM-DD");
            assertThat(TransactionAddResponse.MESSAGE_PROCESSING_DATE_FORMAT)
                    .isEqualTo("Proc Date should be in format YYYY-MM-DD");
            assertThat(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT)
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_PROCESSING_DATE_FORMAT);
        }
    }

    /** The single normalisation the compact constructor performs, and everything it leaves alone. */
    @Nested
    @DisplayName("Field-error normalisation")
    class FieldErrorNormalisation {

        /** An absent collection becomes the empty immutable list. */
        @Test
        @DisplayName("substitutes the empty list for an absent collection")
        void anAbsentCollectionBecomesTheEmptyList() {
            TransactionAddResponse response = build(Map.of(), null, false, null, null);

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        /** A supplied collection is detached from the caller. */
        @Test
        @DisplayName("detaches a supplied collection from the caller")
        void aSuppliedCollectionIsDetachedFromTheCaller() {
            List<ErrorResponse.FieldError> mutable = new ArrayList<>();
            mutable.add(MISSING_AMOUNT);

            TransactionAddResponse response = build(Map.of(), null, false, mutable, null);
            mutable.add(INVALID_TYPE_CODE);

            assertThat(response.fieldErrors()).containsExactly(MISSING_AMOUNT);
        }

        /** The stored collection rejects modification. */
        @Test
        @DisplayName("rejects modification of the stored collection")
        void theStoredCollectionRejectsModification() {
            List<ErrorResponse.FieldError> stored =
                    build(Map.of(), null, false, List.of(MISSING_AMOUNT), null).fieldErrors();

            assertThatThrownBy(() -> stored.add(INVALID_TYPE_CODE))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /**
         * A null element is rejected rather than dropped, because an entry with no state would be
         * meaningless and silently discarding it would hide an error the client has to show.
         */
        @Test
        @DisplayName("rejects a null element rather than dropping it")
        void aNullElementIsRejected() {
            List<ErrorResponse.FieldError> withNull = new ArrayList<>();
            withNull.add(MISSING_AMOUNT);
            withNull.add(null);

            assertThatThrownBy(() -> build(Map.of(), null, false, withNull, null))
                    .isInstanceOf(NullPointerException.class);
        }

        /** Element order is preserved exactly as supplied. */
        @Test
        @DisplayName("preserves the supplied element order")
        void theSuppliedElementOrderIsPreserved() {
            List<ErrorResponse.FieldError> supplied = List.of(INVALID_TYPE_CODE, MISSING_AMOUNT);

            assertThat(build(Map.of(), null, false, supplied, null).fieldErrors())
                    .containsExactly(INVALID_TYPE_CODE, MISSING_AMOUNT);
        }

        /** An explicitly empty collection stays empty and is not confused with absence. */
        @Test
        @DisplayName("keeps an explicitly empty collection empty")
        void anExplicitlyEmptyCollectionStaysEmpty() {
            TransactionAddResponse response =
                    build(Map.of(), null, false, Collections.emptyList(), null);

            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        /** The presence test answers true for any non-empty collection. */
        @Test
        @DisplayName("answers the presence test from the collection alone")
        void thePresenceTestAnswersFromTheCollectionAlone() {
            assertThat(build(Map.of(), null, false, List.of(MISSING_AMOUNT), null).hasFieldErrors())
                    .isTrue();
            assertThat(
                            build(
                                            Map.of(),
                                            null,
                                            true,
                                            Collections.emptyList(),
                                            null)
                                    .hasFieldErrors())
                    .as("a general failure with no per-field error answers false")
                    .isFalse();
        }

        /**
         * Nothing else is normalised: every other component is stored precisely as supplied,
         * including every leading and trailing space, because the legacy fields are space
         * significant and the success path supplies blanks for all fourteen echoed values.
         */
        @Test
        @DisplayName("normalises nothing but the collection")
        void nothingElseIsNormalised() {
            String padded = "  spaced  ";
            TransactionAddResponse response =
                    build(
                            Map.of("description", padded, "source", SOURCE, "message", padded),
                            new BigDecimal("1.50"),
                            false,
                            null,
                            null);

            assertThat(response.description()).isEqualTo(padded);
            assertThat(response.source()).isEqualTo(SOURCE).hasSize(10);
            assertThat(response.message()).isEqualTo(padded);
            assertThat(response.amount()).isEqualTo(new BigDecimal("1.50"));
            assertThat(response.amount().toPlainString())
                    .as("the trailing zero a normalising layer would strip survives")
                    .isEqualTo("1.50");
            assertThat(response.amount().scale()).isEqualTo(TransactionAddResponse.AMOUNT_SCALE);
        }

        /**
         * The three failure signals are independent. A general failure can arrive with no per-field
         * error, a per-field error with no general failure, and a summary message with neither.
         */
        @Test
        @DisplayName("keeps the general flag, the collection and the message independent")
        void theThreeFailureSignalsAreIndependent() {
            TransactionAddResponse generalOnly = build(Map.of(), null, true, null, null);
            TransactionAddResponse fieldOnly =
                    build(Map.of(), null, false, List.of(MISSING_AMOUNT), null);
            TransactionAddResponse messageOnly =
                    build(
                            Map.of("message", TransactionAddResponse.MESSAGE_CONFIRM_PROMPT),
                            null,
                            false,
                            null,
                            null);

            assertThat(generalOnly.generalError()).isTrue();
            assertThat(generalOnly.hasFieldErrors()).isFalse();
            assertThat(generalOnly.message()).isNull();

            assertThat(fieldOnly.generalError()).isFalse();
            assertThat(fieldOnly.hasFieldErrors()).isTrue();
            assertThat(fieldOnly.message()).isNull();

            assertThat(messageOnly.generalError()).isFalse();
            assertThat(messageOnly.hasFieldErrors()).isFalse();
            assertThat(messageOnly.message())
                    .isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
        }
    }

    /** The wire form the exact amount takes under the module's declared settings. */
    @Nested
    @DisplayName("Decimal wire form")
    class DecimalWireForm {

        /**
         * Extracts the characters emitted for the amount.
         *
         * @param amount the amount to carry
         * @return the emitted characters of the amount
         * @throws JsonProcessingException if serialisation fails, failing the calling test
         */
        private String emitted(BigDecimal amount) throws JsonProcessingException {
            return JsonContractSupport.renderedValueToken(
                    payloadOf(carryingAmount(amount)), "amount");
        }

        /** The amount is emitted in plain decimal form at its constructed scale. */
        @ParameterizedTest(name = "{0} is emitted plainly")
        @ValueSource(strings = {"0.00", "1.20", "1234.56", "-99999999.99", "999999999.99"})
        @DisplayName("emits the amount in plain decimal form")
        void theAmountIsEmittedPlainly(String literal) throws JsonProcessingException {
            assertThat(emitted(new BigDecimal(literal))).isEqualTo(literal);
        }

        /**
         * The widest amount the record field can hold is never emitted in exponential form.
         *
         * <p>Nine integer digits and two decimal places is the whole of the field, so this is the largest
         * magnitude that can reach the wire at all - which makes it the case most likely to acquire an
         * exponent from a mapper left to its own devices. A wider magnitude is not a stronger case of the
         * same test; it is a value the constructor refuses, which is asserted separately.</p>
         */
        @Test
        @DisplayName("never emits the amount in exponential form")
        void theAmountIsNeverEmittedInExponentialForm() throws JsonProcessingException {
            String token = emitted(new BigDecimal("999999999.99"));

            assertThat(token).doesNotContain("E").doesNotContain("e");
            assertThat(token).isEqualTo("999999999.99");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a wider amount never reaches the wire in any form")
                    .isThrownBy(() -> carryingAmount(new BigDecimal("1234567890.12")))
                    .withMessageContaining("must fit 9 integer digits")
                    .withMessageContaining("it needs 10");
        }

        /**
         * A trailing zero in the scale survives to the wire, and the one-place form that would have
         * demonstrated the converse cannot be constructed.
         *
         * <p>The point of the test is that the emitted characters are the constructed characters: a
         * normalising mapper would strip the zero and emit {@code 1.2}. That is now proved by the
         * surviving zero alone, because the one-place amount is refused at construction - a stronger
         * guarantee than emitting it faithfully would have been.</p>
         */
        @Test
        @DisplayName("keeps a trailing zero in the emitted scale")
        void aTrailingZeroSurvivesToTheWire() throws JsonProcessingException {
            assertThat(emitted(new BigDecimal("1.20"))).isEqualTo("1.20");
            assertThat(emitted(new BigDecimal("0.00"))).isEqualTo("0.00");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingAmount(new BigDecimal("1.2")))
                    .withMessageContaining("must carry scale 2");
        }

        /**
         * The edited display form the screen shows is never carried in place of the value: no sign
         * glyph, group separator or currency mark appears.
         */
        @Test
        @DisplayName("carries no part of the edited display form")
        void theEditedDisplayFormIsNeverCarried() throws JsonProcessingException {
            String token = emitted(new BigDecimal("1234567.89"));

            assertThat(token)
                    .isEqualTo("1234567.89")
                    .doesNotContain(",")
                    .doesNotContain("$")
                    .doesNotContain("+");
        }

        /** A negative amount is carried, because the legacy amount may legitimately be negative. */
        @Test
        @DisplayName("carries a negative amount")
        void aNegativeAmountIsCarried() throws JsonProcessingException {
            assertThat(emitted(new BigDecimal("-0.01"))).isEqualTo("-0.01");
        }

        /**
         * No amount is rescaled or rounded; the single rounding site stays single.
         *
         * <p>Every literal is at the record scale, and every one is a value a normalising layer would be
         * tempted to alter: a trailing zero it would strip, a negative zero it would collapse, a signed
         * value smaller than one cent it would round away, and the widest magnitude the field holds.
         * Surviving unchanged is the whole claim, so the plain string is asserted alongside the value and
         * the sign.</p>
         */
        @ParameterizedTest(name = "scale of {0} survives")
        @ValueSource(strings = {"1.20", "0.00", "-0.01", "999999999.99", "-99999999.99"})
        @DisplayName("neither rescales nor rounds the amount")
        void theAmountIsNeitherRescaledNorRounded(String literal) {
            BigDecimal supplied = new BigDecimal(literal);
            TransactionAddResponse response = carryingAmount(supplied);

            assertThat(response.amount()).isEqualTo(supplied);
            assertThat(response.amount().scale()).isEqualTo(TransactionAddResponse.AMOUNT_SCALE);
            assertThat(response.amount().signum()).isEqualTo(supplied.signum());
            assertThat(response.amount().toPlainString()).isEqualTo(literal);
        }

        /**
         * An amount off the record scale is refused rather than rescaled, in both scale directions.
         *
         * <p>Refusing and rescaling are different acts and only the second would change the bytes the
         * screen presents, so the refusal is what keeps the no-rescaling guarantee above honest rather
         * than contradicting it. A value carrying too few decimal places and one carrying too many are
         * separate mistakes, and the refusal reports each with its own count.</p>
         */
        @ParameterizedTest(name = "an amount of scale {1} is refused")
        @CsvSource({"1,0", "1.5,1", "1.234,3", "-1.23456,5", "0.001,3"})
        @DisplayName("refuses an amount off the record scale")
        void anAmountOffTheRecordScaleIsRefused(String literal, int scale) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingAmount(new BigDecimal(literal)))
                    .withMessageContaining("must carry scale 2")
                    .withMessageContaining("its scale is " + scale);
        }

        /**
         * Two responses differing only in amount value are not equal, so a substituted amount is
         * detectable even when both amounts are record-shaped.
         *
         * <p>An earlier revision made this point with two amounts differing only in scale. That case no
         * longer exists to be distinguished, because the scale-shifted amount cannot be constructed - a
         * refusal is a stronger guarantee than an inequality a caller has to think to check.</p>
         */
        @Test
        @DisplayName("distinguishes two amounts that differ only in value")
        void twoResponsesDifferingOnlyInAmountScaleAreNotEqual() {
            assertThat(carryingAmount(new BigDecimal("1.20")))
                    .isNotEqualTo(carryingAmount(new BigDecimal("1.21")));
        }

        /** An absent amount is omitted rather than emitted as a zero. */
        @Test
        @DisplayName("omits an absent amount")
        void anAbsentAmountIsOmitted() throws JsonProcessingException {
            assertThat(payloadOf(empty())).doesNotContain("\"amount\"");
        }

        /** The amount is never emitted as a quoted string. */
        @Test
        @DisplayName("emits the amount as a number and never as a string")
        void theAmountIsNeverEmittedAsAString() throws JsonProcessingException {
            assertThat(payloadOf(populated(null))).doesNotContain("\"amount\":\"");
        }
    }

    /** The JSON form each component takes under the module's declared settings. */
    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        /** Every populated component appears under the name the record declares. */
        @Test
        @DisplayName("emits every populated component under its declared name")
        void everyPopulatedComponentAppearsUnderItsDeclaredName() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(
                                    payloadOf(
                                            populated(
                                                    JsonContractSupport.populatedNavigation())));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /**
         * The field-error collection is always present and is emitted as an empty array when there
         * is nothing in it, so a client never has to test it for absence.
         */
        @Test
        @DisplayName("always emits the field-error collection, as an empty array when empty")
        void theFieldErrorCollectionIsAlwaysEmitted() throws JsonProcessingException {
            assertThat(payloadOf(empty())).contains("\"fieldErrors\":[]");
        }

        /** An empty response carries exactly the two components that cannot be absent. */
        @Test
        @DisplayName("emits only the indicator and the empty collection when nothing is populated")
        void anEmptyResponseCarriesOnlyTheIndicatorAndTheCollection()
                throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper().readTree(payloadOf(empty()));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder("generalError", "fieldErrors");
        }

        /** An absent component is omitted rather than emitted as a null. */
        @Test
        @DisplayName("omits an absent component")
        void anAbsentComponentIsOmitted() throws JsonProcessingException {
            String payload = payloadOf(carrying("newTransactionId", NEW_TRANSACTION_ID));

            assertThat(payload).contains("\"newTransactionId\":\"" + NEW_TRANSACTION_ID + "\"");
            for (String component : EXPECTED_COMPONENTS) {
                if (!"newTransactionId".equals(component)
                        && !"generalError".equals(component)
                        && !"fieldErrors".equals(component)) {
                    assertThat(payload)
                            .as("absent component %s must be omitted", component)
                            .doesNotContain("\"" + component + "\"");
                }
            }
        }

        /**
         * The generated key is emitted as text with all fifteen leading zeros intact, and never as
         * the number one.
         */
        @Test
        @DisplayName("emits the generated key with its leading zeros intact")
        void theGeneratedKeyKeepsItsLeadingZeros() throws JsonProcessingException {
            String payload = payloadOf(carrying("newTransactionId", NEW_TRANSACTION_ID));

            assertThat(payload).contains("\"newTransactionId\":\"0000000000000001\"");
            assertThat(payload).doesNotContain("\"newTransactionId\":1");
        }

        /** The four-character category code keeps its leading zeros. */
        @Test
        @DisplayName("emits the category code with its leading zeros intact")
        void theCategoryCodeKeepsItsLeadingZeros() throws JsonProcessingException {
            assertThat(payloadOf(carrying("categoryCode", CATEGORY_CODE)))
                    .contains("\"categoryCode\":\"0002\"");
        }

        /** The nine-character merchant identifier keeps all nine characters. */
        @Test
        @DisplayName("emits the merchant identifier at its full nine characters")
        void theMerchantIdentifierKeepsAllNineCharacters() throws JsonProcessingException {
            assertThat(payloadOf(carrying("merchantId", MERCHANT_ID)))
                    .contains("\"merchantId\":\"999999999\"");
        }

        /** Both dates are emitted as text rather than as temporal values. */
        @Test
        @DisplayName("emits both dates as text")
        void bothDatesAreEmittedAsText() throws JsonProcessingException {
            String payload = payloadOf(populated(null));

            assertThat(payload).contains("\"originationDate\":\"" + ORIGINATION_DATE + "\"");
            assertThat(payload).contains("\"processingDate\":\"" + PROCESSING_DATE + "\"");
        }

        /** The presence test is not emitted, because it is a method and not a component. */
        @Test
        @DisplayName("emits no presence test")
        void thePresenceTestIsNotEmitted() throws JsonProcessingException {
            assertThat(payloadOf(populated(null))).doesNotContain("\"hasFieldErrors\"");
        }

        /** A space-significant value survives serialisation untouched. */
        @Test
        @DisplayName("preserves a space-significant value")
        void aSpaceSignificantValueSurvives() throws JsonProcessingException {
            assertThat(payloadOf(carrying("source", SOURCE)))
                    .contains("\"source\":\"" + SOURCE + "\"");
        }

        /** The assembled success text survives serialisation with its doubled space intact. */
        @Test
        @DisplayName("preserves the doubled space of the assembled success text")
        void theDoubledSpaceOfTheSuccessTextSurvives() throws JsonProcessingException {
            assertThat(payloadOf(carrying("message", ASSEMBLED_SUCCESS_TEXT)))
                    .contains("\"message\":\"" + ASSEMBLED_SUCCESS_TEXT + "\"")
                    .contains("successfully.  Your");
        }

        /** An unknown property is tolerated on read under the module's declared settings. */
        @Test
        @DisplayName("tolerates an unknown property on read")
        void anUnknownPropertyIsToleratedOnRead() {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload =
                    "{\"newTransactionId\":\"0000000000000001\",\"generalError\":false,\"x\":1}";

            assertThatNoException()
                    .isThrownBy(() -> mapper.readValue(payload, TransactionAddResponse.class));
        }

        /** A round trip preserves every component. */
        @Test
        @DisplayName("preserves every component across a round trip")
        void aRoundTripPreservesEveryComponent() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            TransactionAddResponse original =
                    populated(JsonContractSupport.populatedNavigation());

            TransactionAddResponse restored =
                    mapper.readValue(
                            mapper.writeValueAsString(original), TransactionAddResponse.class);

            assertThat(restored).isEqualTo(original);
        }
    }

    /** Bean Validation behaviour at, inside and outside each declared bound. */
    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        /** A fully populated response reports no violation. */
        @Test
        @DisplayName("reports no violation on a populated response")
        void aPopulatedResponseReportsNoViolation() {
            assertThat(validator.validate(populated(JsonContractSupport.populatedNavigation())))
                    .isEmpty();
        }

        /** An empty response reports no violation, because nothing is required. */
        @Test
        @DisplayName("reports no violation on an empty response")
        void anEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(empty())).isEmpty();
        }

        /** A value one character past a bound is reported against that component. */
        @ParameterizedTest(name = "{0} over {1} is reported")
        @CsvSource({
            "newTransactionId,16",
            "accountId,11",
            "cardNumber,16",
            "typeCode,2",
            "categoryCode,4",
            "source,10",
            "description,60",
            "originationDate,10",
            "processingDate,10",
            "merchantId,9",
            "merchantName,30",
            "merchantCity,25",
            "merchantZip,10",
            "confirmationFlag,1",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "message,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("reports an over-long value")
        void anOverLongValueIsReported(String component, int width) {
            Set<ConstraintViolation<TransactionAddResponse>> violations =
                    validator.validate(carrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** A value exactly at a bound is accepted. */
        @ParameterizedTest(name = "{0} at {1} is accepted")
        @CsvSource({
            "newTransactionId,16",
            "accountId,11",
            "cardNumber,16",
            "typeCode,2",
            "categoryCode,4",
            "source,10",
            "description,60",
            "originationDate,10",
            "processingDate,10",
            "merchantId,9",
            "merchantName,30",
            "merchantCity,25",
            "merchantZip,10",
            "confirmationFlag,1",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "message,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("accepts a value at a bound")
        void anAtWidthValueIsAccepted(String component, int width) {
            assertThat(validator.validate(carrying(component, "A".repeat(width)))).isEmpty();
        }

        /**
         * Not one component is mandatory, because the success path deliberately blanks all fourteen
         * echoed values before redisplaying the screen.
         */
        @Test
        @DisplayName("marks no component mandatory")
        void noComponentIsMandatory() {
            for (String component : ECHOED_COMPONENTS) {
                if (!"amount".equals(component)) {
                    assertThat(validator.validate(carrying(component, null)))
                            .as("component %s must tolerate absence", component)
                            .isEmpty();
                    assertThat(validator.validate(carrying(component, "")))
                            .as("component %s must tolerate a blank", component)
                            .isEmpty();
                }
            }
            assertThat(validator.validate(carryingAmount(null))).isEmpty();
        }

        /** A fully blank echoed set, as the success path produces, reports no violation. */
        @Test
        @DisplayName("accepts the blank echoed set the success path produces")
        void theBlankEchoedSetIsAccepted() {
            Map<String, String> blanks = new HashMap<>();
            blanks.put("newTransactionId", NEW_TRANSACTION_ID);
            blanks.put("message", ASSEMBLED_SUCCESS_TEXT);
            for (String component : ECHOED_COMPONENTS) {
                if (!"amount".equals(component)) {
                    blanks.put(component, "");
                }
            }

            assertThat(validator.validate(build(blanks, null, false, null, null))).isEmpty();
        }

        /** The route is not measured, because it is a service-owned identifier. */
        @Test
        @DisplayName("does not measure the route")
        void theRouteIsNotMeasured() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(4096)))).isEmpty();
        }

        /**
         * The amount is not measured by Bean Validation, at either end of the range the record field can
         * hold.
         *
         * <p>The extremes chosen are the widest positive and widest negative values the field holds, not
         * arbitrarily large ones: a wider value never reaches the validator because the compact
         * constructor refuses it, so asserting that the validator tolerated one would assert nothing about
         * the validator. The two mechanisms are not alternatives - the constructor decides which decimal
         * shapes exist, and Bean Validation measures character widths on the components that carry
         * text.</p>
         */
        @Test
        @DisplayName("does not measure the amount")
        void theAmountIsNotMeasured() {
            assertThat(validator.validate(carryingAmount(new BigDecimal("999999999.99")))).isEmpty();
            assertThat(validator.validate(carryingAmount(new BigDecimal("-999999999.99"))))
                    .isEmpty();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an out-of-shape amount is refused before a validator could see it")
                    .isThrownBy(
                            () ->
                                    carryingAmount(
                                            new BigDecimal(
                                                    "123456789012345678901234567890.123456789")));
        }

        /** A space-padded or fully blank value survives validation exactly as supplied. */
        @Test
        @DisplayName("accepts space-padded and fully blank values")
        void aSpacePaddedOrBlankValueIsAccepted() {
            assertThat(validator.validate(carrying("source", "          "))).isEmpty();
            assertThat(validator.validate(carrying("description", ""))).isEmpty();
            assertThat(carrying("source", "          ").source()).hasSize(10);
        }

        /** Every published message text passes the message bound. */
        @ParameterizedTest(name = "[{index}] passes the message bound")
        @MethodSource("com.carddemo.api.dto.TransactionAddResponseCoverageTest#publishedMessages")
        @DisplayName("accepts every published message text")
        void everyPublishedTextPassesTheMessageBound(String actual, String expected, int length) {
            assertThat(validator.validate(carrying("message", actual))).isEmpty();
        }

        /** The assembled success text passes the message bound. */
        @Test
        @DisplayName("accepts the assembled success text")
        void theAssembledSuccessTextPassesTheMessageBound() {
            assertThat(validator.validate(carrying("message", ASSEMBLED_SUCCESS_TEXT))).isEmpty();
        }

        /**
         * The field-error collection is not cascaded, so a nested value is validated directly rather
         * than through the enclosing response.
         */
        @Test
        @DisplayName("does not cascade validation into the field-error collection")
        void theFieldErrorCollectionIsNotCascaded() throws NoSuchFieldException {
            Annotation[] annotations =
                    TransactionAddResponse.class.getDeclaredField("fieldErrors").getAnnotations();

            assertThat(annotations)
                    .as("no cascade is declared over the collection")
                    .noneMatch(
                            annotation ->
                                    "jakarta.validation.Valid"
                                            .equals(annotation.annotationType().getName()));
        }
    }

    /** What the diagnostic rendering retains and what it withholds. */
    @Nested
    @DisplayName("Diagnostic redaction")
    class DiagnosticRedaction {

        /**
         * The rendering is exactly the declared form. The expected text is assembled here from the
         * declared component order and an independently written list of rendered values, so that a
         * change to the order, to a value or to the withheld set fails this test.
         */
        @Test
        @DisplayName("renders exactly the declared form")
        void theRenderingIsExactlyTheDeclaredForm() {
            List<ErrorResponse.FieldError> errors =
                    List.of(MISSING_AMOUNT, INVALID_TYPE_CODE);

            List<String> renderedValues =
                    List.of(
                            NEW_TRANSACTION_ID,
                            PLACEHOLDER,
                            PLACEHOLDER,
                            TYPE_CODE,
                            CATEGORY_CODE,
                            SOURCE,
                            PLACEHOLDER,
                            PLACEHOLDER,
                            PLACEHOLDER,
                            ORIGINATION_DATE,
                            PROCESSING_DATE,
                            PLACEHOLDER,
                            PLACEHOLDER,
                            PLACEHOLDER,
                            PLACEHOLDER,
                            CONFIRMATION_FLAG,
                            TRANSACTION_NAME,
                            TITLE_01,
                            CURRENT_DATE,
                            PROGRAM_NAME,
                            TITLE_02,
                            CURRENT_TIME,
                            ASSEMBLED_SUCCESS_TEXT,
                            "true",
                            errors.toString(),
                            FOCUS_SCREEN_FIELD_ID,
                            NEXT_ROUTE,
                            "null");

            StringBuilder expected = new StringBuilder("TransactionAddResponse[");
            for (int index = 0; index < EXPECTED_COMPONENTS.size(); index++) {
                if (index > 0) {
                    expected.append(", ");
                }
                expected.append(EXPECTED_COMPONENTS.get(index))
                        .append('=')
                        .append(renderedValues.get(index));
            }
            expected.append(']');

            assertThat(populated(null)).hasToString(expected.toString());
        }

        /** Each withheld component is rendered as the fixed stand-in. */
        @ParameterizedTest(name = "{0} is withheld")
        @ValueSource(
                strings = {
                    "accountId",
                    "cardNumber",
                    "description",
                    "amountEntered",
                    "amount",
                    "merchantId",
                    "merchantName",
                    "merchantCity",
                    "merchantZip"
                })
        @DisplayName("withholds each cardholder-bearing component")
        void eachWithheldComponentIsRenderedAsTheStandIn(String component) {
            assertThat(populated(null).toString()).contains(component + "=" + PLACEHOLDER);
        }

        /** Exactly nine components are withheld, not one and not all. */
        @Test
        @DisplayName("withholds exactly nine components")
        void exactlyNineComponentsAreWithheld() {
            String rendered = populated(null).toString();
            long withheld =
                    EXPECTED_COMPONENTS.stream()
                            .filter(component -> rendered.contains(component + "=" + PLACEHOLDER))
                            .count();

            assertThat(withheld).isEqualTo(9);
            assertThat(WITHHELD_COMPONENTS).hasSize(9);
        }

        /** Every retained component is rendered with its real value. */
        @Test
        @DisplayName("retains every component outside the withheld set")
        void everyRetainedComponentIsRenderedInFull() {
            String rendered = populated(null).toString();

            for (String component : EXPECTED_COMPONENTS) {
                if (!WITHHELD_COMPONENTS.contains(component)) {
                    assertThat(rendered)
                            .as("component %s belongs to the retained diagnostic set", component)
                            .doesNotContain(component + "=" + PLACEHOLDER);
                }
            }
            assertThat(rendered)
                    .contains("newTransactionId=" + NEW_TRANSACTION_ID)
                    .contains("typeCode=" + TYPE_CODE)
                    .contains("categoryCode=" + CATEGORY_CODE)
                    .contains("originationDate=" + ORIGINATION_DATE)
                    .contains("processingDate=" + PROCESSING_DATE)
                    .contains("confirmationFlag=" + CONFIRMATION_FLAG)
                    .contains("focusScreenFieldId=" + FOCUS_SCREEN_FIELD_ID)
                    .contains("nextRoute=" + NEXT_ROUTE);
        }

        /**
         * No fragment of a withheld value survives: not its characters, not a leading or trailing
         * piece of it, and not its length.
         */
        @Test
        @DisplayName("leaks no fragment of a withheld value")
        void noFragmentOfAWithheldValueSurvives() {
            String rendered = populated(null).toString();

            assertThat(rendered)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(CARD_NUMBER.substring(0, 6))
                    .doesNotContain(CARD_NUMBER.substring(12))
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(DESCRIPTION)
                    .doesNotContain(MERCHANT_ID)
                    .doesNotContain(MERCHANT_NAME)
                    .doesNotContain(MERCHANT_CITY)
                    .doesNotContain(MERCHANT_ZIP)
                    .doesNotContain(AMOUNT.toPlainString());
        }

        /** The stand-in is a constant and never a transformation of the value it replaces. */
        @Test
        @DisplayName("uses one fixed stand-in for every withheld component")
        void theStandInIsAFixedConstant() {
            String shortValues = build(
                            Map.of(
                                    "accountId", "1",
                                    "cardNumber", "2",
                                    "description", "3",
                                    "merchantId", "4",
                                    "merchantName", "5",
                                    "merchantCity", "6",
                                    "merchantZip", "7"),
                            new BigDecimal("8.00"),
                            false,
                            null,
                            null)
                    .toString();

            for (String component : WITHHELD_COMPONENTS) {
                assertThat(shortValues)
                        .as("the stand-in for %s does not vary with the value", component)
                        .contains(component + "=" + PLACEHOLDER);
            }
        }

        /** An absent withheld component is still rendered as the stand-in, not as a null. */
        @Test
        @DisplayName("renders an absent withheld component as the stand-in")
        void anAbsentWithheldComponentIsStillRenderedAsTheStandIn() {
            String rendered = empty().toString();

            for (String component : WITHHELD_COMPONENTS) {
                assertThat(rendered).contains(component + "=" + PLACEHOLDER);
                assertThat(rendered).doesNotContain(component + "=null");
            }
        }

        /**
         * The field errors are retained because they carry only field labels and states, never a
         * carried value.
         */
        @Test
        @DisplayName("retains the field errors, which carry only labels and states")
        void theFieldErrorsAreRetained() {
            String rendered =
                    build(Map.of(), null, false, List.of(MISSING_AMOUNT), null).toString();

            assertThat(rendered).contains("MISSING").contains("TRNAMT");
            assertThat(rendered).doesNotContain("fieldErrors=" + PLACEHOLDER);
        }

        /**
         * Withholding affects the rendering only. Every accessor returns the value the producer
         * supplied, and the serialized payload carries every withheld value in full.
         */
        @Test
        @DisplayName("withholds from the rendering only, never from an accessor or the payload")
        void withholdingAffectsTheRenderingOnly() throws JsonProcessingException {
            TransactionAddResponse response = populated(null);

            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER).hasSize(16);
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.amount()).isEqualTo(AMOUNT);
            assertThat(response.description()).isEqualTo(DESCRIPTION);
            assertThat(response.merchantName()).isEqualTo(MERCHANT_NAME);

            String payload = payloadOf(response);

            assertThat(payload)
                    .contains("\"cardNumber\":\"" + CARD_NUMBER + "\"")
                    .contains("\"accountId\":\"" + ACCOUNT_ID + "\"")
                    .contains("\"description\":\"" + DESCRIPTION + "\"")
                    .doesNotContain(PLACEHOLDER);
        }

        /**
         * A nested navigation state withholds its own identifying components, so none of its
         * distinctive values reaches this rendering either.
         */
        @Test
        @DisplayName("relies on the navigation state to withhold its identifying components")
        void aNestedNavigationStateWithholdsItsIdentifyingComponents() {
            String rendered = populated(JsonContractSupport.populatedNavigation()).toString();

            assertThat(rendered).contains("navigationContext=NavigationContext[");
            assertThat(rendered)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER);
        }
    }

    /** Equality, hashing, absence tolerance and accessor fidelity. */
    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        /** Two responses carrying equal components are equal. */
        @Test
        @DisplayName("treats equal components as equal values")
        void twoResponsesWithEqualComponentsAreEqual() {
            assertThat(populated(null)).isEqualTo(populated(null));
            assertThat(populated(JsonContractSupport.populatedNavigation()))
                    .isEqualTo(populated(JsonContractSupport.populatedNavigation()));
        }

        /**
         * Equality and hashing compare every component by value and emit nothing, so byte-for-byte
         * fixture comparison is unaffected by anything the rendering withholds.
         */
        @Test
        @DisplayName("compares withheld components by value")
        void equalityComparesWithheldComponentsByValue() {
            assertThat(carrying("cardNumber", CARD_NUMBER))
                    .isNotEqualTo(carrying("cardNumber", "4111111111111112"));
            assertThat(carryingAmount(AMOUNT)).isNotEqualTo(carryingAmount(new BigDecimal("1.00")));
        }

        /** Hashing is stable across equal instances. */
        @Test
        @DisplayName("hashes equal values alike")
        void hashCodeIsStableAcrossEqualInstances() {
            assertThat(populated(null)).hasSameHashCodeAs(populated(null));
        }

        /** An absent collection and an explicitly empty one produce equal responses. */
        @Test
        @DisplayName("equates an absent collection with an explicitly empty one")
        void anAbsentCollectionEqualsAnExplicitlyEmptyOne() {
            assertThat(build(Map.of(), null, false, null, null))
                    .isEqualTo(build(Map.of(), null, false, Collections.emptyList(), null));
        }

        /** Every component tolerates an absent value. */
        @Test
        @DisplayName("tolerates an absent value in every component")
        void everyComponentToleratesAnAbsentValue() {
            TransactionAddResponse response = empty();

            assertThat(response.newTransactionId()).isNull();
            assertThat(response.accountId()).isNull();
            assertThat(response.cardNumber()).isNull();
            assertThat(response.amount()).isNull();
            assertThat(response.description()).isNull();
            assertThat(response.message()).isNull();
            assertThat(response.focusScreenFieldId()).isNull();
            assertThat(response.nextRoute()).isNull();
            assertThat(response.navigationContext()).isNull();
            assertThat(response.generalError()).isFalse();
            assertThat(response.fieldErrors()).isEmpty();
        }

        /** Every accessor returns exactly what was supplied. */
        @Test
        @DisplayName("returns from every accessor exactly what was supplied")
        void everyAccessorReturnsWhatWasSupplied() {
            TransactionAddResponse response =
                    populated(JsonContractSupport.populatedNavigation());

            assertThat(response.newTransactionId()).isEqualTo(NEW_TRANSACTION_ID);
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.typeCode()).isEqualTo(TYPE_CODE);
            assertThat(response.categoryCode()).isEqualTo(CATEGORY_CODE);
            assertThat(response.source()).isEqualTo(SOURCE);
            assertThat(response.description()).isEqualTo(DESCRIPTION);
            assertThat(response.amount()).isEqualTo(AMOUNT);
            assertThat(response.originationDate()).isEqualTo(ORIGINATION_DATE);
            assertThat(response.processingDate()).isEqualTo(PROCESSING_DATE);
            assertThat(response.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(response.merchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(response.merchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(response.merchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(response.confirmationFlag()).isEqualTo(CONFIRMATION_FLAG);
            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(response.title01()).isEqualTo(TITLE_01);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(response.title02()).isEqualTo(TITLE_02);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(response.message()).isEqualTo(ASSEMBLED_SUCCESS_TEXT);
            assertThat(response.generalError()).isTrue();
            assertThat(response.fieldErrors())
                    .containsExactly(MISSING_AMOUNT, INVALID_TYPE_CODE);
            assertThat(response.focusScreenFieldId()).isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(response.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(response.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        /** A space-significant value survives construction untouched. */
        @Test
        @DisplayName("preserves a space-significant value")
        void aSpaceSignificantValueSurvives() {
            String padded = "  padded  ";

            assertThat(carrying("merchantName", padded).merchantName()).isEqualTo(padded);
            assertThat(carrying("source", SOURCE).source()).isEqualTo(SOURCE).hasSize(10);
        }
    }
}
