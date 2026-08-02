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

import java.lang.annotation.Annotation;
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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link BillPaymentResponse}, the response body of legacy transaction {@code CB00}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>Three properties dominate what is checked here.
 *
 * <p>The first is that there is exactly <strong>one</strong> balance. An earlier shape of this contract
 * offered a second, resulting balance. The symbolic map declares a single balance item family - inbound
 * {@code CURBALI} at {@code app/cpy-bms/COBIL00.CPY} line 66 and outbound {@code CURBALO} at line 128 -
 * and {@code app/cbl/COBIL00C.cbl} touches it at exactly two sites, lines 194 and 564, never writing a
 * post-payment figure to the screen. The resulting figure was not independent information either: lines
 * 224 and 234 pay the whole of the balance, so it was necessarily zero on success and a duplicate of its
 * neighbour otherwise. Its removal is asserted structurally, on the wire and in the rendering.
 *
 * <p>The second is the balance's decimal shape. It derives from {@code ACCT-CURR-BAL PIC S9(10)V99} at
 * {@code app/cpy/CVACT01Y.cpy} line 7 - ten integer digits and two decimal places, so total precision
 * twelve - and the canonical constructor <em>refuses</em> a value that does not have it. Refusing is not
 * normalising, and that distinction is asserted explicitly: an accepted value comes back as the very
 * same object, so nothing is rescaled, rounded, truncated or reformatted at this boundary.
 *
 * <p>The third is that the rendering withholds the account identifier, the balance and the operator's
 * confirmation answer, while retaining what a diagnostic actually needs.
 *
 * <p>The published message texts are additionally pinned byte for byte, because they are external
 * contract under validation gate 5 and a stray edit to a literal would be invisible to a compiler.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("BillPaymentResponse :: bill-payment response contract of legacy transaction CB00")
class BillPaymentResponseTest {

    /** The sixteen record components in the order the symbolic map declares their fields. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "accountId", "currentBalance", "confirm", "newTransactionId", "transactionName",
            "title01", "currentDate", "programName", "title02", "currentTime", "errorMessage",
            "paymentAccepted", "generalError", "focusScreenFieldId", "nextRoute",
            "navigationContext");

    private static final String ACCOUNT_ID = "00000000011";
    private static final String TRANSACTION_ID = "0000000000000042";
    private static final BigDecimal BALANCE = new BigDecimal("1234.56");
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CB00", "COBIL00C", "CB00", "COBIL00C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", "4111111111111111", "COBIL0A", "COBIL00");
    }

    private static BillPaymentResponse populated() {
        return new BillPaymentResponse(ACCOUNT_ID, BALANCE, "Y", TRANSACTION_ID, "CB00",
                "CardDemo - Bill Payment", "07/19/22", "COBIL00C", "Pay Full Balance", "14:30:00",
                BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT, false, false,
                BillPaymentResponse.CONFIRM_FIELD_ID, "/api/menu", navigation());
    }

    private static BillPaymentResponse withBalance(BigDecimal balance) {
        return new BillPaymentResponse(null, balance, null, null, null, null, null, null, null, null,
                null, false, false, null, null, null);
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

    private static JsonNode payloadOf(BillPaymentResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static List<String> componentNames() {
        return Arrays.stream(BillPaymentResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotations a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component: none of the annotations
     * involved declares {@code RECORD_COMPONENT} among its targets, so asking the component yields an
     * empty array and an assertion phrased that way would pass without testing anything.
     *
     * @param name the component name
     * @return the annotations present on the backing field
     */
    private static Annotation[] annotationsOn(String name) {
        try {
            return BillPaymentResponse.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return BillPaymentResponse.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, with the delegated navigation state
     * excised rather than truncated away.
     *
     * <p>Excision and not truncation, so that the helper stays correct wherever the nested component
     * sits in the component order.
     *
     * @param response the response to render
     * @return the rendering with the nested contribution replaced by a marker
     */
    private static String ownRendering(BillPaymentResponse response) {
        String rendered = response.toString();
        return (response.navigationContext() == null)
                ? rendered
                : rendered.replace(response.navigationContext().toString(), "<delegated>");
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the sixteen components the screen shows, in map order")
        void declaresTheSixteenComponentsInMapOrder() {
            assertThat(componentNames()).containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("carries the canonical wire names for title, route, focus and navigation")
        void carriesTheCanonicalWireNames() {
            assertThat(componentNames())
                    .contains("title01", "title02", "nextRoute", "focusScreenFieldId",
                            "navigationContext")
                    .doesNotContain("screenTitle1", "screenTitle2", "screenTitleLine1",
                            "screenTitleLine2", "titleLine1", "titleLine2", "route", "navigation",
                            "fieldToFocus");
        }

        @Test
        @DisplayName("bounds the focus hint at the seven characters a generated map name allows")
        void boundsTheFocusHintAtSeven() {
            assertThat(annotationOn("focusScreenFieldId", Size.class).max()).isEqualTo(7);
        }

        @Test
        @DisplayName("publishes the two field identities the program actually focuses")
        void publishesTheTwoFieldIdentities() {
            assertThat(BillPaymentResponse.ACCOUNT_ID_FIELD_ID).isEqualTo("ACTIDIN");
            assertThat(BillPaymentResponse.CONFIRM_FIELD_ID).isEqualTo("CONFIRM");
            assertThat(BillPaymentResponse.ACCOUNT_ID_FIELD_ID.length())
                    .isLessThanOrEqualTo(annotationOn("focusScreenFieldId", Size.class).max());
            assertThat(BillPaymentResponse.CONFIRM_FIELD_ID.length())
                    .isLessThanOrEqualTo(annotationOn("focusScreenFieldId", Size.class).max());
        }

        @Test
        @DisplayName("carries the next route as opaque text rather than an enumeration")
        void carriesTheNextRouteAsOpaqueText() {
            assertThat(BillPaymentResponse.class.getRecordComponents()[14].getType())
                    .isEqualTo(String.class);
            assertThat(annotationsOn("nextRoute")).isEmpty();
        }
    }

    @Nested
    @DisplayName("there is exactly one balance")
    class ThereIsExactlyOneBalance {

        @Test
        @DisplayName("declares one decimal component and no resulting balance beside it")
        void declaresOneDecimalComponent() {
            List<String> decimals = Arrays.stream(BillPaymentResponse.class.getRecordComponents())
                    .filter(component -> component.getType().equals(BigDecimal.class))
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(decimals).containsExactly("currentBalance");
        }

        @Test
        @DisplayName("declares no component named for a resulting or new balance")
        void declaresNoResultingBalance() {
            assertThat(componentNames())
                    .doesNotContain("paymentBalance", "resultingBalance", "newBalance",
                            "remainingBalance");
        }

        @Test
        @DisplayName("publishes no resulting balance on the wire")
        void publishesNoResultingBalanceOnTheWire() throws JsonProcessingException {
            assertThat(payloadOf(populated()).fieldNames()).toIterable()
                    .doesNotContain("paymentBalance", "resultingBalance", "newBalance");
        }

        @Test
        @DisplayName("names no resulting balance in the rendering")
        void namesNoResultingBalanceInTheRendering() {
            assertThat(populated().toString()).doesNotContain("paymentBalance");
        }
    }

    @Nested
    @DisplayName("the balance's record shape is published")
    class RecordShapeIsPublished {

        @Test
        @DisplayName("publishes the scale and integer digits of the account balance record field")
        void publishesTheRecordFigures() {
            assertThat(BillPaymentResponse.BALANCE_SCALE).isEqualTo(2);
            assertThat(BillPaymentResponse.BALANCE_INTEGER_DIGITS).isEqualTo(10);
        }

        @Test
        @DisplayName("uses the wider account-balance figure, not the narrower transaction-amount one")
        void usesTheAccountBalanceFigure() {
            assertThat(BillPaymentResponse.BALANCE_INTEGER_DIGITS)
                    .isEqualTo(TransactionAddResponse.AMOUNT_INTEGER_DIGITS + 1)
                    .isNotEqualTo(TransactionAddResponse.AMOUNT_INTEGER_DIGITS);
        }

        @Test
        @DisplayName("describes the balance in the published schema, naming its record field")
        void describesTheBalanceInTheSchema() {
            Schema schema = annotationOn("currentBalance", Schema.class);

            assertThat(schema).isNotNull();
            assertThat(schema.description())
                    .contains("ACCT-CURR-BAL")
                    .contains("S9(10)V99")
                    .contains("app/cpy/CVACT01Y.cpy");
        }

        @Test
        @DisplayName("places no character bound on the balance, since the map width is geometry")
        void placesNoCharacterBoundOnTheBalance() {
            assertThat(annotationsOn("currentBalance")).hasOnlyElementsOfType(Schema.class);
            assertThat(annotationOn("currentBalance", Size.class)).isNull();
        }
    }

    @Nested
    @DisplayName("a misshapen balance is refused")
    class MisshapenBalanceIsRefused {

        @Test
        @DisplayName("refuses a balance carrying one decimal place")
        void refusesOneDecimalPlace() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(new BigDecimal("1234.5")))
                    .withMessage("currentBalance must carry scale 2, because its record field stores"
                            + " two decimal places, but its scale is 1");
        }

        @Test
        @DisplayName("refuses a balance carrying three decimal places")
        void refusesThreeDecimalPlaces() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(new BigDecimal("1234.567")))
                    .withMessage("currentBalance must carry scale 2, because its record field stores"
                            + " two decimal places, but its scale is 3");
        }

        @Test
        @DisplayName("refuses a balance needing eleven integer digits")
        void refusesElevenIntegerDigits() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withBalance(new BigDecimal("12345678901.00")))
                    .withMessage("currentBalance must fit 10 integer digits, because that is the"
                            + " width of its record field, but it needs 11");
        }

        @Test
        @DisplayName("accepts a balance filling all ten integer digits the record field holds")
        void acceptsTenIntegerDigits() {
            assertThatNoException()
                    .isThrownBy(() -> withBalance(new BigDecimal("1234567890.00")));
        }

        @Test
        @DisplayName("accepts an absent balance, since several arms read no account")
        void acceptsAnAbsentBalance() {
            assertThatNoException().isThrownBy(() -> withBalance(null));
            assertThat(withBalance(null).currentBalance()).isNull();
        }

        @Test
        @DisplayName("accepts a negative balance, because the record field is signed")
        void acceptsANegativeBalance() {
            assertThatNoException().isThrownBy(() -> withBalance(new BigDecimal("-1234.56")));
        }

        @Test
        @DisplayName("accepts a zero balance at scale two without asserting anything about it")
        void acceptsAZeroBalance() {
            assertThat(withBalance(new BigDecimal("0.00")).currentBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("returns an accepted balance as the very same object, so nothing is rescaled")
        void leavesAnAcceptedBalanceUntouched() {
            BigDecimal supplied = new BigDecimal("1234.56");

            assertThat(withBalance(supplied).currentBalance()).isSameAs(supplied);
        }

        @Test
        @DisplayName("leaves every text component unaltered, including padding")
        void leavesEveryTextComponentUnaltered() {
            BillPaymentResponse response = new BillPaymentResponse("  padded  ", BALANCE, " ",
                    TRANSACTION_ID, "CB00", "title one padded   ", "07/19/22", "COBIL00C",
                    "title two padded   ", "14:30:00", "  message  ", false, false, "ACTIDIN",
                    "/api/menu", null);

            assertThat(response.accountId()).isEqualTo("  padded  ");
            assertThat(response.confirm()).isEqualTo(" ");
            assertThat(response.title01()).isEqualTo("title one padded   ");
            assertThat(response.errorMessage()).isEqualTo("  message  ");
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("publishes every component under its own name")
        void publishesEveryComponentUnderItsOwnName() throws JsonProcessingException {
            assertThat(payloadOf(populated()).fieldNames()).toIterable()
                    .containsExactlyInAnyOrderElementsOf(COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("writes the balance in plain notation at its contractual scale")
        void writesTheBalanceInPlainNotation() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json).contains("\"currentBalance\":1234.56").doesNotContain("E+");
        }

        @Test
        @DisplayName("writes a small balance without collapsing its trailing zero")
        void writesASmallBalanceWithoutCollapsing() throws JsonProcessingException {
            String json = moduleEquivalentMapper()
                    .writeValueAsString(withBalance(new BigDecimal("0.10")));

            assertThat(json).contains("\"currentBalance\":0.10");
        }

        @Test
        @DisplayName("publishes the two explicit flags even when false")
        void publishesTheTwoExplicitFlags() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("paymentAccepted").asBoolean()).isFalse();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("omits an absent component rather than publishing a null")
        void omitsAnAbsentComponent() throws JsonProcessingException {
            assertThat(payloadOf(withBalance(null)).fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("paymentAccepted", "generalError");
        }

        @Test
        @DisplayName("publishes none of the superseded component spellings")
        void publishesNoneOfTheSupersededSpellings() throws JsonProcessingException {
            assertThat(payloadOf(populated()).toString())
                    .doesNotContain("\"route\"", "\"navigation\"", "\"fieldToFocus\"",
                            "\"paymentBalance\"", "\"screenTitle1\"", "\"screenTitle2\"");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("names the type so a diagnostic identifies what it is looking at")
        void namesTheType() {
            assertThat(populated().toString()).startsWith("BillPaymentResponse[").endsWith("]");
        }

        @Test
        @DisplayName("withholds the account identifier, the balance and the confirmation answer")
        void withholdsTheRegulatedValues() {
            assertThat(ownRendering(populated()))
                    .contains("accountId=" + REDACTED)
                    .contains("currentBalance=" + REDACTED)
                    .contains("confirm=" + REDACTED)
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain("1234.56");
        }

        @Test
        @DisplayName("retains what a diagnostic needs to locate the same attempt again")
        void retainsWhatADiagnosticNeeds() {
            assertThat(ownRendering(populated()))
                    .contains("newTransactionId=" + TRANSACTION_ID)
                    .contains("paymentAccepted=false")
                    .contains("generalError=false")
                    .contains("focusScreenFieldId=" + BillPaymentResponse.CONFIRM_FIELD_ID)
                    .contains("nextRoute=/api/menu")
                    .contains("errorMessage=" + BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT);
        }

        @Test
        @DisplayName("labels the retained values with their canonical names")
        void labelsWithCanonicalNames() {
            assertThat(ownRendering(populated()))
                    .contains("title01=", "title02=", "nextRoute=", "focusScreenFieldId=",
                            "navigationContext=")
                    .doesNotContain("fieldToFocus=", ", route=", ", navigation=");
        }

        @Test
        @DisplayName("withholds the navigation state rather than relying on its own rendering")
        void withholdsTheNavigationState() {
            assertThat(populated().toString())
                    .contains("navigationContext=" + REDACTED)
                    .doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("renders safely when every optional value is absent")
        void rendersSafelyWhenEverythingIsAbsent() {
            assertThat(withBalance(null).toString())
                    .startsWith("BillPaymentResponse[accountId=" + REDACTED)
                    .contains("currentBalance=" + REDACTED)
                    .contains("navigationContext=" + REDACTED);
        }

        @Test
        @DisplayName("changes nothing it transports")
        void changesNothingTransported() {
            BillPaymentResponse response = populated();
            response.toString();

            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.currentBalance()).isEqualByComparingTo(BALANCE);
            assertThat(response.confirm()).isEqualTo("Y");
            assertThat(response.navigationContext()).isEqualTo(navigation());
        }

        @Test
        @DisplayName("changes nothing on the wire, where every withheld value still travels")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("confirm").asText()).isEqualTo("Y");
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("leaves equality and hashing exactly as the record contract generates them")
        void leavesEqualityAndHashingAlone() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(populated()).isNotEqualTo(withBalance(BALANCE));
        }
    }

    @Nested
    @DisplayName("operator diagnostics reproduce the legacy text")
    class OperatorDiagnosticsReproduceLegacyText {

        @Test
        @DisplayName("reproduces the empty-account-identifier text of line 161 byte for byte")
        void reproducesTheEmptyAccountIdentifierText() {
            assertThat(BillPaymentResponse.MSG_ACCT_ID_EMPTY)
                    .isEqualTo("Acct ID can NOT be empty...");
        }

        @Test
        @DisplayName("reproduces the invalid-confirmation text of line 187 byte for byte")
        void reproducesTheInvalidConfirmationText() {
            assertThat(BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE)
                    .isEqualTo("Invalid value. Valid values are (Y/N)...");
        }

        @Test
        @DisplayName("reproduces the nothing-to-pay text of line 201 byte for byte")
        void reproducesTheNothingToPayText() {
            assertThat(BillPaymentResponse.MSG_NOTHING_TO_PAY)
                    .isEqualTo("You have nothing to pay...");
        }

        @Test
        @DisplayName("reproduces the confirmation prompt of line 237 byte for byte")
        void reproducesTheConfirmationPrompt() {
            assertThat(BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT)
                    .isEqualTo("Confirm to make a bill payment...");
        }

        @Test
        @DisplayName("reproduces the account-not-found text of lines 361, 392 and 425")
        void reproducesTheAccountNotFoundText() {
            assertThat(BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND)
                    .isEqualTo("Account ID NOT found...");
        }

        @Test
        @DisplayName("reproduces the two success fragments of lines 527 and 528 byte for byte")
        void reproducesTheSuccessFragments() {
            assertThat(BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX)
                    .isEqualTo("Payment successful. ");
            assertThat(BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT)
                    .isEqualTo(" Your Transaction ID is ");
        }

        @Test
        @DisplayName("keeps the assembled success message inside the map's message width")
        void keepsTheAssembledSuccessMessageInsideTheMapWidth() {
            String assembled = BillPaymentResponse.MSG_PAYMENT_SUCCESSFUL_PREFIX
                    + BillPaymentResponse.MSG_TRANSACTION_ID_FRAGMENT + TRANSACTION_ID + ".";

            assertThat(assembled.length())
                    .isLessThanOrEqualTo(annotationOn("errorMessage", Size.class).max());
        }

        @Test
        @DisplayName("keeps every published message inside the map's message width of seventy-eight")
        void keepsEveryPublishedMessageInsideTheMapWidth() {
            int width = annotationOn("errorMessage", Size.class).max();

            assertThat(width).isEqualTo(78);
            assertThat(List.of(BillPaymentResponse.MSG_ACCT_ID_EMPTY,
                    BillPaymentResponse.MSG_INVALID_CONFIRM_VALUE,
                    BillPaymentResponse.MSG_NOTHING_TO_PAY,
                    BillPaymentResponse.MSG_CONFIRM_BILL_PAYMENT,
                    BillPaymentResponse.MSG_ACCOUNT_ID_NOT_FOUND,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_ACCOUNT,
                    BillPaymentResponse.MSG_UNABLE_TO_UPDATE_ACCOUNT,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_XREF_AIX,
                    BillPaymentResponse.MSG_TRANSACTION_ID_NOT_FOUND,
                    BillPaymentResponse.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
                    BillPaymentResponse.MSG_TRAN_ID_ALREADY_EXIST,
                    BillPaymentResponse.MSG_UNABLE_TO_ADD_BILL_PAY_TRANSACTION))
                    .allSatisfy(message -> assertThat(message.length()).isLessThanOrEqualTo(width));
        }
    }
}
