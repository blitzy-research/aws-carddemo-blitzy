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
 * Unit tests for {@link TransactionViewResponse}, the response body of legacy transaction {@code CT01}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>Three properties are under test. The two screen titles carry the names every other response in
 * this package uses, and the focus field identifier is bounded at the seven characters the map
 * generator can emit rather than left unbounded. The amount's record shape - nine integer digits and
 * two decimal places, from {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10 - is
 * published in the schema and refused when wrong; the record previously declared no constructor at all
 * and so enforced nothing. And the rendering withholds the identifiers, the amount, the dates and the
 * merchant, which is the whole of what this response carries beyond its presentation frame.
 *
 * <p>The constructor added here only ever <em>refuses</em>. It performs no rescaling, no rounding, no
 * truncation, no trimming and no formatting, so every value that survives it crosses the boundary byte
 * for byte - which is what the file's original comment was protecting and what these tests confirm is
 * still true.
 *
 * <p>Every assertion about the decimal on the wire goes through {@code writeValueAsString}, because
 * reading a decimal back out of a parsed tree as text renders it in exponential notation and would
 * describe the tree's own formatting rather than this record's serialized form.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("TransactionViewResponse :: transaction-view response contract of legacy transaction CT01")
class TransactionViewResponseTest {

    /** The twenty-five record components in declaration order. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "searchTransactionId", "transactionId", "cardNumber", "typeCode", "categoryCode",
            "source", "description", "amount", "originationDate", "processingDate", "merchantId",
            "merchantName", "merchantCity", "merchantZip", "errorMessage", "generalError",
            "focusScreenFieldId", "nextRoute", "navigationContext");

    private static final String TRANSACTION_ID = "0000000000000042";
    private static final String SEARCH_TRANSACTION_ID = "0000000000000042";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String ACCOUNT_ID = "00000000011";
    private static final String DESCRIPTION = "GROCERIES AT STORE 42";
    private static final String MERCHANT_ID = "999999999";
    private static final String MERCHANT_NAME = "SMITH HARDWARE";
    private static final String MERCHANT_CITY = "SEATTLE";
    private static final String MERCHANT_ZIP = "98101";
    private static final BigDecimal AMOUNT = new BigDecimal("-123.45");
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CT01", "COTRN01C", "CT01", "COTRN01C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN1A", "COTRN01");
    }

    private static TransactionViewResponse withAmount(BigDecimal amount) {
        return new TransactionViewResponse("CT01", "View a Transaction", "07/19/22", "COTRN01C",
                "Tran View", "14:23:07", SEARCH_TRANSACTION_ID, TRANSACTION_ID, CARD_NUMBER, "01",
                "0002", "POS TERM  ", DESCRIPTION, amount, "2022-07-19", "2022-07-20", MERCHANT_ID,
                MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, null, false, "TRNIDIN",
                "/api/transactions", navigation());
    }

    private static TransactionViewResponse populated() {
        return withAmount(AMOUNT);
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
        return Arrays.stream(TransactionViewResponse.class.getRecordComponents())
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
            return TransactionViewResponse.class.getDeclaredField(name).getAnnotation(type);
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
        @DisplayName("declares exactly the twenty-five components in declaration order")
        void declaresTwentyFiveComponents() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_ORDER)
                    .hasSize(25);
        }

        @Test
        @DisplayName("carries the canonical title vocabulary and neither superseded spelling")
        void carriesTheCanonicalTitleVocabulary() {
            assertThat(componentNames())
                    .contains("title01", "title02", "focusScreenFieldId", "nextRoute",
                            "navigationContext")
                    .doesNotContain("titleLine1", "titleLine2", "screenTitleLine1",
                            "screenTitleLine2", "fieldToFocus", "focusFieldName", "route",
                            "navigation");
        }

        @Test
        @DisplayName("bounds every text component at its measured map width")
        void boundsEveryTextComponentAtItsMapWidth() {
            assertThat(annotationOn("transactionName", Size.class).max()).isEqualTo(4);
            assertThat(annotationOn("title01", Size.class).max()).isEqualTo(40);
            assertThat(annotationOn("title02", Size.class).max()).isEqualTo(40);
            assertThat(annotationOn("currentDate", Size.class).max()).isEqualTo(8);
            assertThat(annotationOn("currentTime", Size.class).max()).isEqualTo(8);
            assertThat(annotationOn("programName", Size.class).max()).isEqualTo(8);
            assertThat(annotationOn("searchTransactionId", Size.class).max()).isEqualTo(16);
            assertThat(annotationOn("transactionId", Size.class).max()).isEqualTo(16);
            assertThat(annotationOn("cardNumber", Size.class).max()).isEqualTo(16);
            assertThat(annotationOn("description", Size.class).max()).isEqualTo(60);
            assertThat(annotationOn("merchantId", Size.class).max()).isEqualTo(9);
            assertThat(annotationOn("merchantName", Size.class).max()).isEqualTo(30);
            assertThat(annotationOn("merchantCity", Size.class).max()).isEqualTo(25);
            assertThat(annotationOn("merchantZip", Size.class).max()).isEqualTo(10);
            assertThat(annotationOn("errorMessage", Size.class).max()).isEqualTo(78);
        }

        @Test
        @DisplayName("bounds the focus field identifier at the seven-character generator ceiling")
        void boundsTheFocusFieldIdentifier() {
            assertThat(TransactionViewResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(annotationOn("focusScreenFieldId", Size.class))
                    .as("it was previously unbounded, which let an arbitrary identifier through")
                    .isNotNull();
            assertThat(annotationOn("focusScreenFieldId", Size.class).max()).isEqualTo(7);
        }

        @Test
        @DisplayName("carries the amount as a decimal and no floating-point type anywhere")
        void carriesTheAmountAsADecimal() throws NoSuchFieldException {
            assertThat(TransactionViewResponse.class.getDeclaredField("amount").getType())
                    .isEqualTo(BigDecimal.class);
            assertThat(Arrays.stream(TransactionViewResponse.class.getRecordComponents())
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
            assertThat(TransactionViewResponse.AMOUNT_SCALE)
                    .as("V99 of TRAN-AMT at CVTRA05Y.cpy line 10")
                    .isEqualTo(2);
            assertThat(TransactionViewResponse.AMOUNT_INTEGER_DIGITS)
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
            assertThatNoException().isThrownBy(() -> withAmount(new BigDecimal("999999999.99")));
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
        @DisplayName("accepts an absent amount, because a not-found lookup carries none")
        void acceptsAnAbsentAmount() {
            assertThatNoException().isThrownBy(() -> withAmount(null));
            assertThat(withAmount(null).amount()).isNull();
        }

        @Test
        @DisplayName("leaves an accepted amount untouched, applying no rescaling of any kind")
        void leavesAnAcceptedAmountUntouched() {
            BigDecimal supplied = new BigDecimal("-123.45");

            assertThat(withAmount(supplied).amount())
                    .as("refusing is not normalising: the very same value comes back")
                    .isSameAs(supplied);
        }

        @Test
        @DisplayName("leaves every text component untrimmed and unaltered")
        void leavesEveryTextComponentUnaltered() {
            TransactionViewResponse response = new TransactionViewResponse("CT01", "  padded  ",
                    "07/19/22", "COTRN01C", "Tran View", "14:23:07", SEARCH_TRANSACTION_ID,
                    TRANSACTION_ID, CARD_NUMBER, "01", "0002", "POS TERM  ", "  spaced  ", AMOUNT,
                    "2022-07-19", "2022-07-20", MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY,
                    MERCHANT_ZIP, null, false, "TRNIDIN", "/api/transactions", navigation());

            assertThat(response.title01())
                    .as("the constructor refuses; it never repairs, trims or pads")
                    .isEqualTo("  padded  ");
            assertThat(response.description()).isEqualTo("  spaced  ");
            assertThat(response.source()).isEqualTo("POS TERM  ");
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("carries the canonical title names onto the wire")
        void carriesTheCanonicalTitleNamesOntoTheWire() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json).contains("\"title01\":", "\"title02\":", "\"focusScreenFieldId\":",
                    "\"nextRoute\":", "\"navigationContext\":");
            assertThat(json).doesNotContain("\"titleLine1\":", "\"titleLine2\":",
                    "\"screenTitleLine1\":", "\"fieldToFocus\":", "\"route\":", "\"navigation\":");
        }

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
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds the identifiers, the amount, the dates and the merchant")
        void withholdsEveryRegulatedValue() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).doesNotContain(TRANSACTION_ID, CARD_NUMBER, DESCRIPTION,
                    MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, "123.45",
                    "2022-07-19", "2022-07-20");
            assertThat(rendered).contains("searchTransactionId=" + REDACTED,
                    "transactionId=" + REDACTED, "cardNumber=" + REDACTED,
                    "description=" + REDACTED, "amount=" + REDACTED,
                    "originationDate=" + REDACTED, "processingDate=" + REDACTED,
                    "merchantId=" + REDACTED, "merchantName=" + REDACTED,
                    "merchantCity=" + REDACTED, "merchantZip=" + REDACTED);
        }

        @Test
        @DisplayName("retains the presentation frame, the codes and the error state")
        void retainsThePresentationFrame() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).contains("transactionName=CT01", "title01=View a Transaction",
                    "title02=Tran View", "currentDate=07/19/22", "currentTime=14:23:07",
                    "programName=COTRN01C", "typeCode=01", "categoryCode=0002",
                    "source=POS TERM  ", "generalError=false", "focusScreenFieldId=TRNIDIN");
        }

        @Test
        @DisplayName("is bracketed by the type name")
        void isBracketedByTheTypeName() {
            assertThat(populated().toString())
                    .startsWith("TransactionViewResponse[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("delegates the navigation state, which withholds its own identifiers")
        void delegatesTheNavigationState() {
            String rendered = populated().toString();

            assertThat(rendered).contains(", navigationContext=NavigationContext[");
            assertThat(rendered).doesNotContain(CARD_NUMBER, ACCOUNT_ID);
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            TransactionViewResponse response = populated();
            response.toString();

            assertThat(response.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.amount()).isEqualByComparingTo(AMOUNT);
            assertThat(response.merchantZip()).isEqualTo(MERCHANT_ZIP);
        }

        @Test
        @DisplayName("changes nothing on the wire")
        void changesNothingOnTheWire() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json).contains("\"cardNumber\":\"" + CARD_NUMBER + "\"",
                    "\"merchantName\":\"" + MERCHANT_NAME + "\"");
            assertThat(json).doesNotContain(REDACTED);
        }
    }

    @Nested
    @DisplayName("operator diagnostics reproduce the legacy text")
    class OperatorDiagnosticsReproduceLegacyText {

        @Test
        @DisplayName("reproduces the empty-identifier message character for character")
        void reproducesTheEmptyIdentifierMessage() {
            assertThat(TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE)
                    .isEqualTo("Tran ID can NOT be empty...");
        }

        @Test
        @DisplayName("reproduces the not-found message character for character")
        void reproducesTheNotFoundMessage() {
            assertThat(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE)
                    .isEqualTo("Transaction ID NOT found...");
        }

        @Test
        @DisplayName("reproduces the lookup-failed message character for character")
        void reproducesTheLookupFailedMessage() {
            assertThat(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE)
                    .isEqualTo("Unable to lookup Transaction...");
        }

        @Test
        @DisplayName("keeps every message within the error line the map declares")
        void keepsEveryMessageWithinTheErrorLine() {
            List<String> messages = List.of(TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE,
                    TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE,
                    TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE);

            assertThat(messages).allSatisfy(message -> assertThat(message).hasSizeLessThanOrEqualTo(78));
        }
    }
}
