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

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

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
 * Unit tests for {@link TransactionAddResponse}, the response body of legacy transaction {@code CT02}
 * implemented by {@code app/cbl/COTRN02C.cbl} over screen {@code app/cpy-bms/COTRN02.CPY}.
 *
 * <p><strong>Thirty-five message literals, and the grouping is the contract.</strong> The add screen
 * distinguishes an empty field from a non-numeric one from a malformed date from an invalid calendar
 * date, and reports each with its own sentence. Collapsing any pair would leave an operator unable to
 * tell why the field was refused. Every literal is asserted character for character here because
 * {@code COTRN02C} writes them into a {@code PIC X(78)} line an operator matches on.
 *
 * <p><strong>The rendering withholds eight values.</strong> {@code toString} substitutes a
 * placeholder for the account identifier, the card number, the description, the amount and the whole
 * four-component merchant block, so a log record can name the response without carrying a card number
 * or a merchant address into a log file. The exact placeholder count is taken with an absent
 * conversation state, because {@code NavigationContext} is rendered in full here and performs
 * redactions of its own - counting over a populated context would measure two records' redactions
 * summed rather than this record's.
 *
 * <p><strong>The field-error list is normalised and copied.</strong> An absent list becomes empty so
 * {@code hasFieldErrors} never has to guard against a null, and a supplied list is copied so a caller
 * cannot rewrite the errors after handing the response over. The list is not cascaded into by the
 * validator, because the component carries no {@code Valid} marker - asserted both ways so the
 * absence is a recorded decision rather than an assumption.
 */
@DisplayName("TransactionAddResponse - the CT02 transaction-add screen contract")
class TransactionAddResponseRuleComplianceTest {

    /** A representative eleven-digit account identifier drawn from the seeded fixture range. */
    private static final String ACCOUNT_ID = "00000000011";

    /** A representative sixteen-digit card number. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A representative sixteen-digit transaction identifier. */
    private static final String TRANSACTION_ID = "0000000000000042";

    /** The redaction placeholder the record's own rendering substitutes. */
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
    private static JsonNode payloadOf(final TransactionAddResponse response)
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
    private static String wirePayloadOf(final TransactionAddResponse response)
            throws JsonProcessingException {
        return moduleEquivalentMapper().writeValueAsString(response);
    }

    /**
     * Builds a fully populated response, laid out in rows of five so a component cannot silently
     * drift one position out of line past a compiler that sees a wall of interchangeable strings.
     *
     * @param newTransactionId the identifier assigned to the posted transaction
     * @param amount the monetary amount posted
     * @param message the operator message written to the message line
     * @param generalError whether the screen is reporting a whole-screen failure
     * @param fieldErrors the per-field error list
     * @param navigationContext the carried conversation state
     * @return a response carrying representative values for every component
     */
    private static TransactionAddResponse aResponse(final String newTransactionId,
            final BigDecimal amount, final String message, final boolean generalError,
            final List<ErrorResponse.FieldError> fieldErrors,
            final NavigationContext navigationContext) {
        return new TransactionAddResponse(
                newTransactionId, ACCOUNT_ID, CARD_NUMBER, "01", "0005",
                "POS TERM", "POS PURCHASE - GROCERY", "00001234.56", amount,
                "2022-07-19", "2022-07-19",
                "123456789", "MERCHANT NAME", "SEATTLE", "98101", "Y",
                "CT02", "CardDemo", "07/19/22", "COTRN02C", "Add Transaction",
                "10:30:00", message, generalError, fieldErrors, "TRNAMT",
                "/api/transactions", navigationContext);
    }

    /**
     * Builds a response carrying only the components a single assertion needs.
     *
     * @param newTransactionId the identifier assigned to the posted transaction
     * @param amount the monetary amount posted
     * @param message the operator message written to the message line
     * @param fieldErrors the per-field error list
     * @return a response carrying the supplied values and nothing else
     */
    private static TransactionAddResponse aSparseResponse(final String newTransactionId,
            final BigDecimal amount, final String message,
            final List<ErrorResponse.FieldError> fieldErrors) {
        return new TransactionAddResponse(
                newTransactionId, null, null, null, null,
                null, null, null, amount, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, message, false, fieldErrors, null,
                null, null);
    }

    /**
     * Builds a field error naming the amount field.
     *
     * @param state the error state to report
     * @return a field error over the amount field
     */
    private static ErrorResponse.FieldError anAmountError(
            final ErrorResponse.FieldState state) {
        return new ErrorResponse.FieldError("amount", "TRNAMT", state,
                TransactionAddResponse.MESSAGE_AMOUNT_EMPTY);
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
            return TransactionAddResponse.class.getDeclaredMethod(componentName)
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
        final Size size = TransactionAddResponse.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    /**
     * Counts the occurrences of the redaction placeholder in a rendering.
     *
     * @param rendered the rendering to scan
     * @return the number of placeholders present
     */
    private static int placeholderCount(final String rendered) {
        return rendered.split(Pattern.quote(REDACTION_PLACEHOLDER), -1).length - 1;
    }

    // =============================================================================================

    @Nested
    @DisplayName("the thirty-five operator messages")
    class TheThirtyFiveOperatorMessages {

        @Test
        @DisplayName("the two confirmation messages are the prompt and the invalid-value refusal")
        void theTwoConfirmationMessagesArePublished() {
            assertThat(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT)
                    .isEqualTo("Confirm to add this transaction...");
            assertThat(TransactionAddResponse.MESSAGE_CONFIRM_INVALID)
                    .isEqualTo("Invalid value. Valid values are (Y/N)...");
        }

        @Test
        @DisplayName("the invalid-confirmation text matches the bill-payment screen's own, because the "
                + "legacy literal is shared across the two confirmation gates")
        void theInvalidConfirmationTextMatchesTheBillPaymentScreens() {
            assertThat(TransactionAddResponse.MESSAGE_CONFIRM_INVALID)
                    .isEqualTo(BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE);
        }

        @Test
        @DisplayName("the either-or key rule is reported as one whole-screen sentence rather than as "
                + "two per-field refusals, because either key alone is sufficient")
        void theEitherOrKeyRuleIsOneWholeScreenSentence() {
            assertThat(TransactionAddResponse.MESSAGE_KEY_NOT_ENTERED)
                    .isEqualTo("Account or Card Number must be entered...");
        }

        @Test
        @DisplayName("the eleven empty-field messages each name their own field")
        void theElevenEmptyFieldMessagesNameTheirField() {
            assertThat(TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY)
                    .isEqualTo("Type CD can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_CATEGORY_CODE_EMPTY)
                    .isEqualTo("Category CD can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_SOURCE_EMPTY)
                    .isEqualTo("Source can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_DESCRIPTION_EMPTY)
                    .isEqualTo("Description can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_AMOUNT_EMPTY)
                    .isEqualTo("Amount can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_EMPTY)
                    .isEqualTo("Orig Date can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_PROCESSING_DATE_EMPTY)
                    .isEqualTo("Proc Date can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_MERCHANT_ID_EMPTY)
                    .isEqualTo("Merchant ID can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_MERCHANT_NAME_EMPTY)
                    .isEqualTo("Merchant Name can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_MERCHANT_CITY_EMPTY)
                    .isEqualTo("Merchant City can NOT be empty...");
            assertThat(TransactionAddResponse.MESSAGE_MERCHANT_ZIP_EMPTY)
                    .isEqualTo("Merchant Zip can NOT be empty...");
        }

        @Test
        @DisplayName("the five non-numeric messages are distinct from the empty ones, so a blank field "
                + "is never reported as a badly typed one")
        void theFiveNonNumericMessagesAreDistinctFromTheEmptyOnes() {
            assertThat(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_NUMERIC)
                    .isEqualTo("Account ID must be Numeric...");
            assertThat(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_NUMERIC)
                    .isEqualTo("Card Number must be Numeric...");
            assertThat(TransactionAddResponse.MESSAGE_TYPE_CODE_NOT_NUMERIC)
                    .isEqualTo("Type CD must be Numeric...");
            assertThat(TransactionAddResponse.MESSAGE_CATEGORY_CODE_NOT_NUMERIC)
                    .isEqualTo("Category CD must be Numeric...");
            assertThat(TransactionAddResponse.MESSAGE_MERCHANT_ID_NOT_NUMERIC)
                    .isEqualTo("Merchant ID must be Numeric...");

            assertThat(TransactionAddResponse.MESSAGE_TYPE_CODE_NOT_NUMERIC)
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY);
        }

        @Test
        @DisplayName("the three format messages publish the exact pattern the operator must type, "
                + "amount with a leading sign and both dates in ISO order")
        void theThreeFormatMessagesPublishTheExactPattern() {
            assertThat(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT)
                    .isEqualTo("Amount should be in format -99999999.99");
            assertThat(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT)
                    .isEqualTo("Orig Date should be in format YYYY-MM-DD");
            assertThat(TransactionAddResponse.MESSAGE_PROCESSING_DATE_FORMAT)
                    .isEqualTo("Proc Date should be in format YYYY-MM-DD");
        }

        @Test
        @DisplayName("the amount format names two decimal places, which is the scale of the signed "
                + "PIC S9(09)V99 field the amount is stored in")
        void theAmountFormatNamesTwoDecimalPlaces() {
            assertThat(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT).endsWith(".99");
            assertThat(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT).contains("-");
        }

        @Test
        @DisplayName("the two invalid-date messages are distinct from the two format messages, because "
                + "a well-formed date can still name a day that does not exist")
        void theTwoInvalidDateMessagesAreDistinctFromTheFormatMessages() {
            assertThat(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID)
                    .isEqualTo("Orig Date - Not a valid date...");
            assertThat(TransactionAddResponse.MESSAGE_PROCESSING_DATE_INVALID)
                    .isEqualTo("Proc Date - Not a valid date...");

            assertThat(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID)
                    .isNotEqualTo(TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT);
        }

        @Test
        @DisplayName("the six lookup failures name the resource that failed, each distinguishing an "
                + "absent record from an unreadable file")
        void theSixLookupFailuresNameTheirResource() {
            assertThat(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND)
                    .isEqualTo("Account ID NOT found...");
            assertThat(TransactionAddResponse.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED)
                    .isEqualTo("Unable to lookup Acct in XREF AIX file...");
            assertThat(TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND)
                    .isEqualTo("Card Number NOT found...");
            assertThat(TransactionAddResponse.MESSAGE_CARD_XREF_LOOKUP_FAILED)
                    .isEqualTo("Unable to lookup Card # in XREF file...");
            assertThat(TransactionAddResponse.MESSAGE_TRANSACTION_ID_NOT_FOUND)
                    .isEqualTo("Transaction ID NOT found...");
            assertThat(TransactionAddResponse.MESSAGE_TRANSACTION_LOOKUP_FAILED)
                    .isEqualTo("Unable to lookup Transaction...");
        }

        @Test
        @DisplayName("the two cross-reference failures name the alternate index and the base file "
                + "separately, preserving the legacy distinction between the two access paths")
        void theTwoCrossReferenceFailuresNameDifferentAccessPaths() {
            assertThat(TransactionAddResponse.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED)
                    .contains("XREF AIX file");
            assertThat(TransactionAddResponse.MESSAGE_CARD_XREF_LOOKUP_FAILED)
                    .contains("XREF file").doesNotContain("AIX");
        }

        @Test
        @DisplayName("the two posting failures are the duplicate-key refusal and the general add "
                + "failure, and the duplicate text matches the bill-payment screen's own")
        void theTwoPostingFailuresArePublished() {
            assertThat(TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID)
                    .isEqualTo("Tran ID already exist...")
                    .isEqualTo(BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST);
            assertThat(TransactionAddResponse.MESSAGE_ADD_FAILED)
                    .isEqualTo("Unable to Add Transaction...");
        }

        @Test
        @DisplayName("the success sentence is a prefix, a label and a terminator, so the assigned "
                + "identifier can be spliced in without a format string")
        void theSuccessSentenceIsThreeFragments() {
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_PREFIX)
                    .isEqualTo("Transaction added successfully. ");
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL)
                    .isEqualTo(" Your Tran ID is ");
            assertThat(TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR).isEqualTo(".");
        }

        @Test
        @DisplayName("the assembled success sentence still fits the seventy-eight character message "
                + "line once a sixteen-digit identifier has been spliced into it")
        void theAssembledSuccessSentenceStillFitsTheMessageLine() {
            final String assembled = TransactionAddResponse.MESSAGE_SUCCESS_PREFIX
                    + TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL + TRANSACTION_ID
                    + TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR;

            assertThat(assembled).isEqualTo(
                    "Transaction added successfully.  Your Tran ID is " + TRANSACTION_ID + ".");
            assertThat(assembled.length()).isLessThanOrEqualTo(78);
        }

        @Test
        @DisplayName("all thirty-five literals are distinct and every one of them fits the message line")
        void allThirtyFiveLiteralsAreDistinctAndFit() {
            final List<String> literals = List.of(
                    TransactionAddResponse.MESSAGE_CONFIRM_PROMPT,
                    TransactionAddResponse.MESSAGE_CONFIRM_INVALID,
                    TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_NUMERIC,
                    TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_NUMERIC,
                    TransactionAddResponse.MESSAGE_KEY_NOT_ENTERED,
                    TransactionAddResponse.MESSAGE_TYPE_CODE_EMPTY,
                    TransactionAddResponse.MESSAGE_CATEGORY_CODE_EMPTY,
                    TransactionAddResponse.MESSAGE_SOURCE_EMPTY,
                    TransactionAddResponse.MESSAGE_DESCRIPTION_EMPTY,
                    TransactionAddResponse.MESSAGE_AMOUNT_EMPTY,
                    TransactionAddResponse.MESSAGE_ORIGINATION_DATE_EMPTY,
                    TransactionAddResponse.MESSAGE_PROCESSING_DATE_EMPTY,
                    TransactionAddResponse.MESSAGE_MERCHANT_ID_EMPTY,
                    TransactionAddResponse.MESSAGE_MERCHANT_NAME_EMPTY,
                    TransactionAddResponse.MESSAGE_MERCHANT_CITY_EMPTY,
                    TransactionAddResponse.MESSAGE_MERCHANT_ZIP_EMPTY,
                    TransactionAddResponse.MESSAGE_TYPE_CODE_NOT_NUMERIC,
                    TransactionAddResponse.MESSAGE_CATEGORY_CODE_NOT_NUMERIC,
                    TransactionAddResponse.MESSAGE_MERCHANT_ID_NOT_NUMERIC,
                    TransactionAddResponse.MESSAGE_AMOUNT_FORMAT,
                    TransactionAddResponse.MESSAGE_ORIGINATION_DATE_FORMAT,
                    TransactionAddResponse.MESSAGE_PROCESSING_DATE_FORMAT,
                    TransactionAddResponse.MESSAGE_ORIGINATION_DATE_INVALID,
                    TransactionAddResponse.MESSAGE_PROCESSING_DATE_INVALID,
                    TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND,
                    TransactionAddResponse.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED,
                    TransactionAddResponse.MESSAGE_CARD_NUMBER_NOT_FOUND,
                    TransactionAddResponse.MESSAGE_CARD_XREF_LOOKUP_FAILED,
                    TransactionAddResponse.MESSAGE_TRANSACTION_ID_NOT_FOUND,
                    TransactionAddResponse.MESSAGE_TRANSACTION_LOOKUP_FAILED,
                    TransactionAddResponse.MESSAGE_SUCCESS_PREFIX,
                    TransactionAddResponse.MESSAGE_SUCCESS_ID_LABEL,
                    TransactionAddResponse.MESSAGE_SUCCESS_TERMINATOR,
                    TransactionAddResponse.MESSAGE_DUPLICATE_TRANSACTION_ID,
                    TransactionAddResponse.MESSAGE_ADD_FAILED);

            assertThat(literals).hasSize(35).doesNotHaveDuplicates();
            assertThat(literals).allSatisfy(literal -> assertThat(literal.length())
                    .isLessThanOrEqualTo(78));
        }

        @Test
        @DisplayName("the account and card not-found texts match the bill-payment and view screens' "
                + "own where the legacy literals agree")
        void theSharedNotFoundTextsAgreeAcrossScreens() {
            assertThat(TransactionAddResponse.MESSAGE_ACCOUNT_ID_NOT_FOUND)
                    .isEqualTo(BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND);
            assertThat(TransactionAddResponse.MESSAGE_TRANSACTION_ID_NOT_FOUND)
                    .isEqualTo(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE);
            assertThat(TransactionAddResponse.MESSAGE_TRANSACTION_LOOKUP_FAILED)
                    .isEqualTo(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the field-error list and its reading")
    class TheFieldErrorListAndItsReading {

        @Test
        @DisplayName("an absent error list becomes an empty list, so the predicate never has to guard "
                + "against a null")
        void anAbsentErrorListBecomesAnEmptyList() {
            final TransactionAddResponse response = aSparseResponse(null, null, null, null);

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("a populated error list is reported by the predicate, which is how a screen knows "
                + "to decorate fields rather than to show a whole-screen message")
        void aPopulatedErrorListIsReportedByThePredicate() {
            final TransactionAddResponse response = aSparseResponse(null, null, null,
                    List.of(anAmountError(ErrorResponse.FieldState.MISSING)));

            assertThat(response.hasFieldErrors()).isTrue();
            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.fieldErrors().getFirst().fieldName()).isEqualTo("amount");
            assertThat(response.fieldErrors().getFirst().state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
        }

        @Test
        @DisplayName("an empty error list is reported as no errors, so an empty list and an absent one "
                + "mean the same thing to the predicate")
        void anEmptyErrorListIsReportedAsNoErrors() {
            assertThat(aSparseResponse(null, null, null, List.of()).hasFieldErrors()).isFalse();
            assertThat(aSparseResponse(null, null, null, List.of()))
                    .isEqualTo(aSparseResponse(null, null, null, null));
        }

        @Test
        @DisplayName("a supplied error list is copied, so mutating the caller's list afterwards cannot "
                + "change the response")
        void aSuppliedErrorListIsCopied() {
            final List<ErrorResponse.FieldError> mutable = new ArrayList<>(
                    List.of(anAmountError(ErrorResponse.FieldState.INVALID)));
            final TransactionAddResponse response = aSparseResponse(null, null, null, mutable);

            mutable.clear();

            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.hasFieldErrors()).isTrue();
        }

        @Test
        @DisplayName("the copy is unmodifiable, so a holder of the response cannot add an error after "
                + "the response has been built")
        void theCopyIsUnmodifiable() {
            final List<ErrorResponse.FieldError> errors = aSparseResponse(null, null, null,
                    List.of(anAmountError(ErrorResponse.FieldState.MISSING))).fieldErrors();
            final ErrorResponse.FieldError extra =
                    anAmountError(ErrorResponse.FieldState.INVALID);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> errors.add(extra));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> errors.set(0, extra));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(errors::clear);
        }

        @Test
        @DisplayName("a null error is refused outright, because an absent entry would leave the screen "
                + "with a hole in its decoration list")
        void aNullErrorIsRefusedOutright() {
            final List<ErrorResponse.FieldError> withNull = Arrays.asList(
                    anAmountError(ErrorResponse.FieldState.MISSING), null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aSparseResponse(null, null, null, withNull));
        }

        @Test
        @DisplayName("both error states are carried, so the screen can distinguish a field left blank "
                + "from a field filled in wrongly")
        void bothErrorStatesAreCarried() {
            final TransactionAddResponse response = aSparseResponse(null, null, null, List.of(
                    anAmountError(ErrorResponse.FieldState.MISSING),
                    anAmountError(ErrorResponse.FieldState.INVALID)));

            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("the error list is not cascaded into, because the response declares no Valid "
                + "marker on it")
        void theErrorListIsNotCascadedInto() {
            final TransactionAddResponse response = aSparseResponse(null, null, null,
                    List.of(anAmountError(ErrorResponse.FieldState.MISSING)));

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response)).isEmpty();
            }
        }

        @Test
        @DisplayName("a field error refuses an absent field name, screen field identifier or state, "
                + "so a decoration instruction is never ambiguous")
        void aFieldErrorRefusesAnAbsentIdentity() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new ErrorResponse.FieldError(null, "TRNAMT",
                            ErrorResponse.FieldState.MISSING, null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new ErrorResponse.FieldError("amount", null,
                            ErrorResponse.FieldState.MISSING, null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new ErrorResponse.FieldError("amount", "TRNAMT", null, null));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the withholding rendering")
    class TheWithholdingRendering {

        @Test
        @DisplayName("the account, the card, the description, the amount and the whole merchant block "
                + "are withheld, so a log record can never carry a card number or a merchant address")
        void theSensitiveComponentsAreWithheld() {
            final String rendered = aResponse(TRANSACTION_ID, new BigDecimal("1234.56"),
                    TransactionAddResponse.MESSAGE_CONFIRM_PROMPT, false, List.of(), null)
                    .toString();

            assertThat(rendered).doesNotContain(ACCOUNT_ID);
            assertThat(rendered).doesNotContain(CARD_NUMBER);
            assertThat(rendered).doesNotContain("1234.56");
            assertThat(rendered).doesNotContain("POS PURCHASE - GROCERY");
            assertThat(rendered).doesNotContain("123456789");
            assertThat(rendered).doesNotContain("MERCHANT NAME");
            assertThat(rendered).doesNotContain("SEATTLE");
            assertThat(rendered).doesNotContain("98101");
        }

        @Test
        @DisplayName("the assigned identifier, the codes, the dates, the furniture and the routing are "
                + "rendered in full, because none of them is operator data")
        void theNonSensitiveComponentsAreRenderedInFull() {
            final String rendered = aResponse(TRANSACTION_ID, new BigDecimal("1.00"),
                    TransactionAddResponse.MESSAGE_ADD_FAILED, true, List.of(), null).toString();

            assertThat(rendered).startsWith("TransactionAddResponse[");
            assertThat(rendered).contains("newTransactionId=" + TRANSACTION_ID, "typeCode=01",
                    "categoryCode=0005", "source=POS TERM", "originationDate=2022-07-19",
                    "processingDate=2022-07-19", "confirmationFlag=Y", "transactionName=CT02",
                    "title01=CardDemo", "currentDate=07/19/22", "programName=COTRN02C",
                    "title02=Add Transaction", "currentTime=10:30:00",
                    "message=" + TransactionAddResponse.MESSAGE_ADD_FAILED, "generalError=true",
                    "fieldErrors=[]", "focusScreenFieldId=TRNAMT",
                    "nextRoute=/api/transactions");
            assertThat(rendered).endsWith("]");
        }

        @Test
        @DisplayName("exactly nine placeholders are present, counted with an absent conversation "
                + "state so the figure measures this record's redactions rather than two records' "
                + "summed")
        void exactlyNinePlaceholdersArePresent() {
            final String rendered = aResponse(TRANSACTION_ID, new BigDecimal("10.00"), null, false,
                    List.of(), null).toString();

            assertThat(placeholderCount(rendered)).isEqualTo(9);
        }

        @Test
        @DisplayName("an absent sensitive value is still rendered as a placeholder rather than as "
                + "null, so the rendering never discloses which values were present")
        void anAbsentSensitiveValueIsStillAPlaceholder() {
            final String rendered = aSparseResponse(null, null, null, null).toString();

            assertThat(placeholderCount(rendered)).isEqualTo(9);
            assertThat(rendered).contains("accountId=" + REDACTION_PLACEHOLDER,
                    "cardNumber=" + REDACTION_PLACEHOLDER,
                    "description=" + REDACTION_PLACEHOLDER,
                    "amountEntered=" + REDACTION_PLACEHOLDER,
                    "amount=" + REDACTION_PLACEHOLDER,
                    "merchantId=" + REDACTION_PLACEHOLDER,
                    "merchantName=" + REDACTION_PLACEHOLDER,
                    "merchantCity=" + REDACTION_PLACEHOLDER,
                    "merchantZip=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("the conversation state is rendered rather than withheld here, which is why the "
                + "exact placeholder count is only meaningful with an absent context")
        void theConversationStateIsRenderedRatherThanWithheld() {
            final String withContext = aResponse(TRANSACTION_ID, new BigDecimal("1.00"), null, false,
                    List.of(), NavigationContext.empty()).toString();

            assertThat(withContext).contains("NavigationContext[");
            assertThat(placeholderCount(withContext)).isGreaterThan(9);
        }

        @Test
        @DisplayName("the placeholder is a private constant, so no caller can build a rendering that "
                + "looks redacted without being redacted")
        void thePlaceholderIsAPrivateConstant() throws NoSuchFieldException {
            final int modifiers = TransactionAddResponse.class
                    .getDeclaredField("REDACTION_PLACEHOLDER").getModifiers();

            assertThat(Modifier.isPrivate(modifiers)).isTrue();
            assertThat(Modifier.isStatic(modifiers)).isTrue();
            assertThat(Modifier.isFinal(modifiers)).isTrue();
        }

        @Test
        @DisplayName("the rendering is not the payload, because the payload must carry the values the "
                + "screen has to display")
        void theRenderingIsNotThePayload() throws JsonProcessingException {
            final TransactionAddResponse response = aResponse(TRANSACTION_ID,
                    new BigDecimal("1234.56"), null, false, List.of(), null);

            assertThat(response.toString()).doesNotContain(CARD_NUMBER);
            assertThat(wirePayloadOf(response)).contains("\"cardNumber\":\"" + CARD_NUMBER + "\"");
            assertThat(wirePayloadOf(response)).contains("\"amount\":1234.56");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and its validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        @Test
        @DisplayName("the response declares twenty-eight components in screen order, the assigned "
                + "identifier, the transaction body, the merchant block, the furniture, the error "
                + "block and the routing block")
        void theResponseDeclaresTwentyEightComponentsInScreenOrder() {
            final List<String> declared = Arrays.stream(
                    TransactionAddResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("newTransactionId", "accountId", "cardNumber",
                    "typeCode", "categoryCode", "source", "description", "amountEntered", "amount",
                    "originationDate", "processingDate", "merchantId", "merchantName",
                    "merchantCity", "merchantZip", "confirmationFlag", "transactionName", "title01",
                    "currentDate", "programName", "title02", "currentTime", "message",
                    "generalError", "fieldErrors", "focusScreenFieldId", "nextRoute",
                    "navigationContext");
            assertThat(declared).hasSize(28);
        }

        @Test
        @DisplayName("exactly twenty-three components carry a declared upper bound, and the five that do "
                + "not are the amount, the flag, the error list, the route and the conversation state")
        void exactlyTwentyThreeComponentsCarryAnUpperBound() {
            final List<String> bounded = Arrays.stream(
                    TransactionAddResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(TransactionAddResponseRuleComplianceTest::declaresAnUpperBound).toList();

            assertThat(bounded).hasSize(23);
            assertThat(bounded).doesNotContain("amount", "generalError", "fieldErrors", "nextRoute",
                    "navigationContext");
        }

        @Test
        @DisplayName("the amount is BigDecimal rather than any floating-point type, because a cent is "
                + "not representable as a binary fraction")
        void theAmountIsBigDecimal() throws NoSuchMethodException {
            assertThat(TransactionAddResponse.class.getDeclaredMethod("amount").getReturnType())
                    .isEqualTo(BigDecimal.class);
            assertThat(Arrays.stream(TransactionAddResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName()).toList())
                    .doesNotContain("double", "float", "java.lang.Double", "java.lang.Float");
        }

        @Test
        @DisplayName("the focus field name is seven characters here, the BMS field-name width, while "
                + "the description carries the sixty-character stored width")
        void theFocusFieldAndDescriptionWidthsAreTheLegacyOnes() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("focusScreenFieldId")).isEqualTo(7);
            assertThat(declaredMaximumLength("description")).isEqualTo(60);
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final TransactionAddResponse response = aResponse(TRANSACTION_ID,
                    new BigDecimal("1234.56"), TransactionAddResponse.MESSAGE_CONFIRM_PROMPT, true,
                    List.of(anAmountError(ErrorResponse.FieldState.INVALID)),
                    NavigationContext.empty());

            assertThat(response.newTransactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
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
            assertThat(response.confirmationFlag()).isEqualTo("Y");
            assertThat(response.transactionName()).isEqualTo("CT02");
            assertThat(response.title01()).isEqualTo("CardDemo");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.programName()).isEqualTo("COTRN02C");
            assertThat(response.title02()).isEqualTo("Add Transaction");
            assertThat(response.currentTime()).isEqualTo("10:30:00");
            assertThat(response.message())
                    .isEqualTo(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
            assertThat(response.generalError()).isTrue();
            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.focusScreenFieldId()).isEqualTo("TRNAMT");
            assertThat(response.nextRoute()).isEqualTo("/api/transactions");
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a wholly absent response reports no violation, because every bound is an upper "
                + "bound and the error list defaults to empty")
        void aWhollyAbsentResponseReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aSparseResponse(null, null, null, null)))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a fully populated response reports no violation, so the representative fixture "
                + "is itself within every declared bound")
        void aFullyPopulatedResponseReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aResponse(TRANSACTION_ID,
                        new BigDecimal("1234.56"), TransactionAddResponse.MESSAGE_CONFIRM_PROMPT,
                        false, List.of(), NavigationContext.empty()))).isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({
            "newTransactionId,16", "accountId,11", "cardNumber,16",
            "typeCode,2", "categoryCode,4", "source,10",
            "description,60", "amountEntered,12", "originationDate,10", "processingDate,10",
            "merchantId,9", "merchantName,30", "merchantCity,25",
            "merchantZip,10", "confirmationFlag,1", "transactionName,4",
            "title01,40", "currentDate,8", "programName,8",
            "title02,40", "currentTime,8", "message,78",
            "focusScreenFieldId,7",
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
         * @param componentName the component to populate
         * @param value the value to place in it
         * @return a response carrying only that component
         */
        private TransactionAddResponse responseWith(final String componentName,
                final String value) {
            return new TransactionAddResponse(
                    valueFor("newTransactionId", componentName, value),
                    valueFor("accountId", componentName, value),
                    valueFor("cardNumber", componentName, value),
                    valueFor("typeCode", componentName, value),
                    valueFor("categoryCode", componentName, value),
                    valueFor("source", componentName, value),
                    valueFor("description", componentName, value),
                    valueFor("amountEntered", componentName, value),
                    null,
                    valueFor("originationDate", componentName, value),
                    valueFor("processingDate", componentName, value),
                    valueFor("merchantId", componentName, value),
                    valueFor("merchantName", componentName, value),
                    valueFor("merchantCity", componentName, value),
                    valueFor("merchantZip", componentName, value),
                    valueFor("confirmationFlag", componentName, value),
                    valueFor("transactionName", componentName, value),
                    valueFor("title01", componentName, value),
                    valueFor("currentDate", componentName, value),
                    valueFor("programName", componentName, value),
                    valueFor("title02", componentName, value),
                    valueFor("currentTime", componentName, value),
                    valueFor("message", componentName, value),
                    false, null,
                    valueFor("focusScreenFieldId", componentName, value),
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
        @DisplayName("two responses built from identical values are equal and share a hash code")
        void twoResponsesBuiltFromIdenticalValuesAreEqual() {
            final TransactionAddResponse first = aResponse(TRANSACTION_ID, new BigDecimal("10.00"),
                    null, false, List.of(), null);
            final TransactionAddResponse second = aResponse(TRANSACTION_ID, new BigDecimal("10.00"),
                    null, false, List.of(), null);

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
                    .isThrownBy(() -> aSparseResponse(TRANSACTION_ID, new BigDecimal("10.0"), null,
                            null))
                    .withMessageContaining("must carry scale " + TransactionAddResponse.AMOUNT_SCALE)
                    .withMessageContaining("its scale is 1");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a scale-zero integral amount is refused for the same reason")
                    .isThrownBy(() -> aSparseResponse(TRANSACTION_ID, BigDecimal.TEN, null, null));

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
                    .isThrownBy(() -> aSparseResponse(TRANSACTION_ID,
                            new BigDecimal("1000000000.05"), null, null))
                    .withMessageContaining("must fit " + TransactionAddResponse.AMOUNT_INTEGER_DIGITS
                            + " integer digits")
                    .withMessageContaining("it needs 10");
        }

        @Test
        @DisplayName("the payload always carries the error list and the flag, and omits every absent "
                + "component")
        void thePayloadAlwaysCarriesTheErrorListAndTheFlag() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseResponse(TRANSACTION_ID, null, null, null));

            assertThat(payload.has("fieldErrors")).isTrue();
            assertThat(payload.get("fieldErrors").isArray()).isTrue();
            assertThat(payload.get("fieldErrors")).isEmpty();
            assertThat(payload.has("generalError")).isTrue();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.has("amount")).isFalse();
            assertThat(payload.has("message")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
            assertThat(payload.get("newTransactionId").asText()).isEqualTo(TRANSACTION_ID);
        }

        @Test
        @DisplayName("a field error travels with its field name, its screen field identifier and its "
                + "state, so a client can decorate the field the operator has to correct")
        void aFieldErrorTravelsWithItsIdentity() throws JsonProcessingException {
            final JsonNode errors = payloadOf(aSparseResponse(null, null, null,
                    List.of(anAmountError(ErrorResponse.FieldState.MISSING)))).get("fieldErrors");

            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).get("fieldName").asText()).isEqualTo("amount");
            assertThat(errors.get(0).get("screenFieldId").asText()).isEqualTo("TRNAMT");
            assertThat(errors.get(0).get("state").asText()).isEqualTo("MISSING");
            assertThat(errors.get(0).get("message").asText())
                    .isEqualTo(TransactionAddResponse.MESSAGE_AMOUNT_EMPTY);
        }

        @Test
        @DisplayName("a monetary amount renders in plain notation with its scale intact, read from the "
                + "wire characters rather than from a parsed tree")
        void aMonetaryAmountRendersInPlainNotationWithItsScale() throws JsonProcessingException {
            final String wire = wirePayloadOf(aSparseResponse(TRANSACTION_ID,
                    new BigDecimal("999999999.05"), null, null));

            assertThat(wire).contains("\"amount\":999999999.05");
            assertThat(wire).doesNotContain("E9").doesNotContain("E+");
        }

        @Test
        @DisplayName("a negative amount keeps its sign on the wire and a trailing-zero cent survives "
                + "as two digits")
        void aNegativeAmountAndATrailingZeroCentSurviveTheWire() throws JsonProcessingException {
            assertThat(wirePayloadOf(aSparseResponse(null, new BigDecimal("-42.10"), null, null)))
                    .contains("\"amount\":-42.10");
            assertThat(wirePayloadOf(aSparseResponse(null, new BigDecimal("42.10"), null, null)))
                    .contains("\"amount\":42.10");
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged, "
                + "monetary scale and error list included")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final TransactionAddResponse original = aResponse(TRANSACTION_ID,
                    new BigDecimal("1234.56"), TransactionAddResponse.MESSAGE_ADD_FAILED, true,
                    List.of(anAmountError(ErrorResponse.FieldState.INVALID)),
                    NavigationContext.empty());
            final TransactionAddResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionAddResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.amount().scale()).isEqualTo(2);
            assertThat(restored.hasFieldErrors()).isTrue();
        }
    }
}
