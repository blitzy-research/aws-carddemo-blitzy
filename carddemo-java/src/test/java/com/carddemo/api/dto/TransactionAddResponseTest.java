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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link TransactionAddResponse}, the response body of legacy transaction {@code CT02}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>The amount is the focus. The record field behind it is
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10 - nine integer digits and two
 * decimal places, so total precision eleven and scale exactly two. Before this milestone the scale was
 * stated in prose and nowhere checked, which is a scale a producer can silently break. It is now both
 * published in the schema and refused when wrong, and the tests below hold the distinction that makes
 * that safe: the constructor <em>refuses</em> a misshapen value and never repairs one. It performs no
 * rescaling, no rounding, no truncation and no formatting, so no value that survives it has been
 * altered on the way through.
 *
 * <p>Every assertion about the decimal on the wire goes through {@code writeValueAsString}, because
 * reading a decimal back out of a parsed tree as text renders it in exponential notation and would
 * describe the tree's own formatting rather than this record's serialized form.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("TransactionAddResponse :: transaction-add response contract of legacy transaction CT02")
class TransactionAddResponseTest {

    /** The twenty-seven record components in declaration order. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "newTransactionId", "accountId", "cardNumber", "typeCode", "categoryCode", "source",
            "description", "amount", "originationDate", "processingDate", "merchantId",
            "merchantName", "merchantCity", "merchantZip", "confirmationFlag", "transactionName",
            "title01", "currentDate", "programName", "title02", "currentTime", "message",
            "generalError", "fieldErrors", "focusScreenFieldId", "nextRoute", "navigationContext");

    private static final String TRANSACTION_ID = "0000000000000042";
    private static final String ACCOUNT_ID = "00000000011";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String DESCRIPTION = "GROCERIES AT STORE 42";
    private static final String MERCHANT_ID = "999999999";
    private static final String MERCHANT_NAME = "SMITH HARDWARE";
    private static final String MERCHANT_CITY = "SEATTLE";
    private static final String MERCHANT_ZIP = "98101";
    private static final BigDecimal AMOUNT = new BigDecimal("-123.45");
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CT02", "COTRN02C", "CT02", "COTRN02C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN2A", "COTRN02");
    }

    private static TransactionAddResponse populated() {
        return response(AMOUNT, List.of(new ErrorResponse.FieldError("amount", "TRNAMT",
                ErrorResponse.FieldState.INVALID, TransactionAddResponse.MESSAGE_AMOUNT_FORMAT)));
    }

    private static TransactionAddResponse response(BigDecimal amount,
            List<ErrorResponse.FieldError> fieldErrors) {
        return new TransactionAddResponse(TRANSACTION_ID, ACCOUNT_ID, CARD_NUMBER, "01", "0002",
                "POS TERM  ", DESCRIPTION, amount, "2022-07-19", "2022-07-20", MERCHANT_ID,
                MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, "Y", "CT02",
                "Add a New Transaction", "07/19/22", "COTRN02C", "Tran Add", "14:23:07",
                TransactionAddResponse.MESSAGE_CONFIRM_PROMPT, false, fieldErrors, "TRNAMT",
                "/api/transactions", navigation());
    }

    private static TransactionAddResponse withAmount(BigDecimal amount) {
        return response(amount, List.of());
    }

    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                                JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static List<String> componentNames() {
        return Arrays.stream(TransactionAddResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotation a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component: neither {@code Size} nor
     * {@code Schema} declares {@code RECORD_COMPONENT} among its targets, so asking the component
     * yields nothing and an assertion phrased that way would pass without testing anything.
     *
     * @param <A> the annotation type
     * @param name the component name
     * @param type the annotation type to read
     * @return the annotation, or {@code null} when absent
     */
    private static <A extends java.lang.annotation.Annotation> A annotationOn(String name,
            Class<A> type) {
        try {
            return TransactionAddResponse.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, excluding delegated state.
     *
     * @param rendered a full rendering
     * @return the leading segment this record contributed
     */
    private static String ownRendering(String rendered) {
        int delegated = rendered.indexOf(", navigationContext=");
        return (delegated < 0) ? rendered : rendered.substring(0, delegated);
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the twenty-seven components in declaration order")
        void declaresTwentySevenComponents() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_ORDER)
                    .hasSize(27);
        }

        @Test
        @DisplayName("already carries the canonical presentation vocabulary")
        void alreadyCarriesTheCanonicalVocabulary() {
            assertThat(componentNames())
                    .contains("title01", "title02", "focusScreenFieldId", "nextRoute",
                            "navigationContext")
                    .doesNotContain("screenTitleLine1", "screenTitleLine2", "titleLine1",
                            "titleLine2", "fieldToFocus", "focusFieldName", "route", "navigation");
        }

        @Test
        @DisplayName("bounds the focus field identifier at seven characters")
        void boundsTheFocusFieldIdentifier() {
            assertThat(annotationOn("focusScreenFieldId", Size.class).max()).isEqualTo(7);
        }

        @Test
        @DisplayName("carries the amount as a decimal and not as a floating-point type")
        void carriesTheAmountAsADecimal() throws NoSuchFieldException {
            assertThat(TransactionAddResponse.class.getDeclaredField("amount").getType())
                    .isEqualTo(BigDecimal.class);
            assertThat(Arrays.stream(TransactionAddResponse.class.getRecordComponents())
                    .map(RecordComponent::getType))
                    .doesNotContain(double.class, Double.class, float.class, Float.class);
        }
    }

    @Nested
    @DisplayName("the amount's record shape is published")
    class RecordShapeIsPublished {

        @Test
        @DisplayName("declares the scale and the integer-digit width as named constants")
        void declaresScaleAndWidthAsConstants() {
            assertThat(TransactionAddResponse.AMOUNT_SCALE)
                    .as("V99 of TRAN-AMT at CVTRA05Y.cpy line 10")
                    .isEqualTo(2);
            assertThat(TransactionAddResponse.AMOUNT_INTEGER_DIGITS)
                    .as("S9(09) of TRAN-AMT at CVTRA05Y.cpy line 10")
                    .isEqualTo(9);
        }

        @Test
        @DisplayName("publishes a schema description naming the record field and its figures")
        void publishesASchemaDescription() {
            Schema schema = annotationOn("amount", Schema.class);

            assertThat(schema).isNotNull();
            assertThat(schema.description())
                    .contains("TRAN-AMT")
                    .contains("CVTRA05Y.cpy line 10")
                    .contains("nine integer digits")
                    .contains("scale exactly 2");
        }
    }

    @Nested
    @DisplayName("a misshapen amount is refused, never repaired")
    class MisshapenAmountIsRefused {

        @Test
        @DisplayName("accepts an amount already carrying the record scale")
        void acceptsTheRecordScale() {
            assertThatNoException().isThrownBy(() -> withAmount(new BigDecimal("-123.45")));
            assertThat(withAmount(new BigDecimal("-123.45")).amount())
                    .isEqualByComparingTo("-123.45")
                    .extracting(BigDecimal::scale)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("rejects a scale below the record scale, with the figure named")
        void rejectsAScaleBelowTheRecordScale() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withAmount(new BigDecimal("123.4")))
                    .withMessage("amount must carry scale 2, because its record field stores two"
                            + " decimal places, but its scale is 1");
        }

        @Test
        @DisplayName("rejects a scale above the record scale rather than rounding it away")
        void rejectsAScaleAboveTheRecordScale() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withAmount(new BigDecimal("123.456")))
                    .withMessage("amount must carry scale 2, because its record field stores two"
                            + " decimal places, but its scale is 3");
        }

        @Test
        @DisplayName("accepts the widest value the record field can hold")
        void acceptsTheWidestRecordValue() {
            BigDecimal widest = new BigDecimal("999999999.99");

            assertThatNoException().isThrownBy(() -> withAmount(widest));
            assertThat(withAmount(widest).amount()).isEqualByComparingTo(widest);
        }

        @Test
        @DisplayName("rejects a value needing a tenth integer digit, with the figure named")
        void rejectsATenthIntegerDigit() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withAmount(new BigDecimal("1000000000.00")))
                    .withMessage("amount must fit 9 integer digits, because that is the width of its"
                            + " record field, but it needs 10");
        }

        @Test
        @DisplayName("accepts a signed value, because a refund is legitimately negative")
        void acceptsASignedValue() {
            assertThatNoException().isThrownBy(() -> withAmount(new BigDecimal("-999999999.99")));
        }

        @Test
        @DisplayName("accepts an absent amount, because a first entry carries none")
        void acceptsAnAbsentAmount() {
            assertThatNoException().isThrownBy(() -> withAmount(null));
            assertThat(withAmount(null).amount()).isNull();
        }

        @Test
        @DisplayName("accepts a scaled zero without normalising it")
        void acceptsAScaledZero() {
            assertThat(withAmount(new BigDecimal("0.00")).amount())
                    .isEqualTo(new BigDecimal("0.00"))
                    .extracting(BigDecimal::scale)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("leaves an accepted amount untouched, applying no rescaling of any kind")
        void leavesAnAcceptedAmountUntouched() {
            BigDecimal supplied = new BigDecimal("-123.45");

            assertThat(withAmount(supplied).amount())
                    .as("refusing is not normalising: the very same value comes back")
                    .isSameAs(supplied);
        }
    }

    @Nested
    @DisplayName("the field-error list stays normalised")
    class FieldErrorListStaysNormalised {

        @Test
        @DisplayName("replaces an absent list with an empty one")
        void replacesAnAbsentListWithAnEmptyOne() {
            assertThat(response(AMOUNT, null).fieldErrors()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("copies the supplied list defensively")
        void copiesTheSuppliedListDefensively() {
            List<ErrorResponse.FieldError> mutable = new ArrayList<>();
            mutable.add(new ErrorResponse.FieldError("amount", "TRNAMT",
                    ErrorResponse.FieldState.MISSING,
                    TransactionAddResponse.MESSAGE_AMOUNT_EMPTY));

            TransactionAddResponse response = response(AMOUNT, mutable);
            mutable.clear();

            assertThat(response.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("exposes an unmodifiable list")
        void exposesAnUnmodifiableList() {
            assertThat(populated().fieldErrors().getClass().getName())
                    .startsWith("java.util.ImmutableCollections");
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("writes the amount in plain notation, never in exponential form")
        void writesTheAmountInPlainNotation() throws JsonProcessingException {
            String json = moduleEquivalentMapper()
                    .writeValueAsString(withAmount(new BigDecimal("999999999.99")));

            assertThat(json).contains("\"amount\":999999999.99");
            assertThat(json).doesNotContain("E+", "E9", "e+");
        }

        @Test
        @DisplayName("preserves the trailing zeros the record scale requires")
        void preservesTrailingZeros() throws JsonProcessingException {
            String json =
                    moduleEquivalentMapper().writeValueAsString(withAmount(new BigDecimal("10.00")));

            assertThat(json).contains("\"amount\":10.00");
        }

        @Test
        @DisplayName("carries the canonical presentation names onto the wire")
        void carriesTheCanonicalNamesOntoTheWire() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json).contains("\"title01\":", "\"title02\":", "\"focusScreenFieldId\":",
                    "\"nextRoute\":", "\"navigationContext\":");
            assertThat(json).doesNotContain("\"screenTitleLine1\":", "\"screenTitleLine2\":",
                    "\"fieldToFocus\":", "\"route\":", "\"navigation\":");
        }

        @Test
        @DisplayName("omits an absent amount rather than writing a null")
        void omitsAnAbsentAmount() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(withAmount(null));

            assertThat(json).doesNotContain("\"amount\"");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds the regulated values and retains the operator diagnostics")
        void withholdsRegulatedValues() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).doesNotContain(ACCOUNT_ID, CARD_NUMBER, DESCRIPTION, MERCHANT_ID,
                    MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, "123.45");
            assertThat(rendered).contains("accountId=" + REDACTED, "cardNumber=" + REDACTED,
                    "amount=" + REDACTED, "merchantName=" + REDACTED);
            assertThat(rendered).contains("newTransactionId=" + TRANSACTION_ID, "typeCode=01",
                    "generalError=false",
                    "message=" + TransactionAddResponse.MESSAGE_CONFIRM_PROMPT);
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            TransactionAddResponse response = populated();
            response.toString();

            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.amount()).isEqualByComparingTo(AMOUNT);
        }
    }

    @Nested
    @DisplayName("operator diagnostics reproduce the legacy text")
    class OperatorDiagnosticsReproduceLegacyText {

        @Test
        @DisplayName("reproduces the empty-amount message from COTRN02C line 278")
        void reproducesTheEmptyAmountMessage() {
            assertThat(TransactionAddResponse.MESSAGE_AMOUNT_EMPTY)
                    .isEqualTo("Amount can NOT be empty...");
        }

        @Test
        @DisplayName("reproduces the amount-format message from COTRN02C line 349")
        void reproducesTheAmountFormatMessage() {
            assertThat(TransactionAddResponse.MESSAGE_AMOUNT_FORMAT)
                    .as("the two states a decimal component could not have reported")
                    .isEqualTo("Amount should be in format -99999999.99");
        }

        @Test
        @DisplayName("reproduces the confirmation prompt")
        void reproducesTheConfirmationPrompt() {
            assertThat(TransactionAddResponse.MESSAGE_CONFIRM_PROMPT)
                    .isEqualTo("Confirm to add this transaction...");
        }
    }
}
