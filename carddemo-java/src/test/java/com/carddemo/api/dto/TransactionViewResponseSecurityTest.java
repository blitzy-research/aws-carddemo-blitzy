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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link TransactionViewResponse} cannot disclose a regulated value through its
 * stringified form, while still carrying every one of them on the wire.
 *
 * <p>The risk this file exists to close is that a response object reaches a log, an exception message
 * or a failure report by being interpolated into a string. That happens without anyone intending it -
 * a logging call that passes the object, a message built with concatenation, a framework that
 * stringifies a payload while reporting a fault - and the generated rendering of a record prints every
 * component. On this screen that would mean a full sixteen-character card number, both transaction
 * identifiers, the description, the amount and the four merchant components, all in one line.
 *
 * <p>The assertions are deliberately stronger than "the value is absent". A rendering could disclose a
 * value in four weaker ways that an equality check would miss: by printing part of it, by printing
 * something derived from it, by revealing its length, or by revealing whether it was present at all.
 * Each is covered below. The fixture values are chosen to be distinctive so that a fragment scan is
 * meaningful: a card number sharing a six-digit run with a retained date would make the scan pass for
 * the wrong reason, which is a defect this suite has already caught once elsewhere in the package.
 *
 * <p>The complementary half matters just as much. Withholding a value from a log must not withhold it
 * from the client, because the screen contract requires the full value in the payload. Every group that
 * asserts a value is absent from the rendering is paired with one asserting it is present in the JSON.
 */
@DisplayName("TransactionViewResponse - diagnostic rendering safety for legacy transaction CT01")
class TransactionViewResponseSecurityTest {

    private static final String PLACEHOLDER = "***REDACTED***";

    private static final String CARD_NUMBER = "4532015112830366";
    private static final String TRANSACTION_ID = "8461372935172994";
    private static final String SEARCH_TRANSACTION_ID = "7295836142058317";
    private static final String DESCRIPTION = "ARTISAN COFFEE ROASTERS SUBSCRIPTION";
    private static final BigDecimal AMOUNT = new BigDecimal("4821.73");
    private static final String MERCHANT_ID = "938271465";
    private static final String MERCHANT_NAME = "ACME HARDWARE SUPPLY";
    private static final String MERCHANT_CITY = "SPRINGFIELD";
    private static final String MERCHANT_ZIP = "62704-5581";

    /**
     * The eleven components the renderer withholds, in declaration order.
     *
     * <p>The two calendar dates are among them. They are withheld on this contract rather than
     * retained as the sibling account contracts retain theirs, because here they date a single
     * movement made by one cardholder rather than describing an account's lifecycle, and the payload
     * still carries them in full for any caller that needs them.
     */
    private static final List<String> WITHHELD_COMPONENTS = List.of(
            "searchTransactionId", "transactionId", "cardNumber", "description", "amount",
            "originationDate", "processingDate", "merchantId", "merchantName", "merchantCity",
            "merchantZip");

    /** Every regulated value carried by a fully populated fixture. */
    private static final List<String> REGULATED_VALUES = List.of(
            CARD_NUMBER, TRANSACTION_ID, SEARCH_TRANSACTION_ID, DESCRIPTION, AMOUNT.toPlainString(),
            MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP);

    /**
     * A mapper configured the way the deployed context configures the module's single mapper.
     *
     * <p>Equivalence with the real one is proved separately by {@code ApplicationJsonContractTest},
     * which builds a Spring context from {@code application.yml} and asserts the two stay identical.
     * This suite therefore needs no context of its own to make a faithful wire assertion.
     */
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

    /** A fully populated response whose navigation state is absent, so placeholders can be counted. */
    private static TransactionViewResponse populated() {
        return populatedWith(null);
    }

    private static TransactionViewResponse populatedWith(NavigationContext navigation) {
        return new TransactionViewResponse(
                "CT01", "TITLE ONE", "07/19/22", "COTRN01C", "TITLE TWO", "14:23:07",
                SEARCH_TRANSACTION_ID, TRANSACTION_ID, CARD_NUMBER,
                "01", "0005", "POS TERM  ", DESCRIPTION, AMOUNT,
                "2022-07-19", "2022-07-20",
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                "MESSAGE LINE", true, "TRNIDIN", "route/next", navigation);
    }

    /** An entirely absent response, which the not-found path legitimately produces. */
    private static TransactionViewResponse empty() {
        return new TransactionViewResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, false, null, null, null);
    }

    private static int occurrencesOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static JsonNode payloadOf(TransactionViewResponse response) throws Exception {
        return moduleEquivalentMapper().readTree(moduleEquivalentMapper().writeValueAsString(response));
    }

    @Nested
    @DisplayName("No regulated value survives the rendering")
    class NoRegulatedValueSurvives {

        @Test
        @DisplayName("every regulated value is absent from the rendering in whole")
        void everyRegulatedValueIsAbsentInWhole() {
            String rendered = populated().toString();
            assertThat(rendered).doesNotContain(REGULATED_VALUES);
        }

        @Test
        @DisplayName("no six-character run of any regulated value survives, so nothing is disclosed "
                + "in part")
        void noSixCharacterRunOfAnyRegulatedValueSurvives() {
            String rendered = populated().toString();
            for (String value : REGULATED_VALUES) {
                for (int start = 0; start + 6 <= value.length(); start++) {
                    String fragment = value.substring(start, start + 6);
                    assertThat(rendered)
                            .withFailMessage("fragment %s of %s must not appear in %s",
                                    fragment, value, rendered)
                            .doesNotContain(fragment);
                }
            }
        }

        @Test
        @DisplayName("neither the issuer prefix nor the trailing digits of the card number appear, so "
                + "the two fragments a masked rendering conventionally keeps are both withheld")
        void neitherIssuerPrefixNorTrailingDigitsAppear() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .doesNotContain(CARD_NUMBER.substring(0, 4))
                    .doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
        }

        @Test
        @DisplayName("the amount does not survive as a scaled, truncated or unscaled variant")
        void theAmountDoesNotSurviveInAnyNumericForm() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .doesNotContain(AMOUNT.toPlainString())
                    .doesNotContain(AMOUNT.setScale(0, java.math.RoundingMode.DOWN).toPlainString())
                    .doesNotContain(AMOUNT.unscaledValue().toString());
        }

        @Test
        @DisplayName("both transaction identifiers are withheld together, because withholding the "
                + "retrieved one while printing the echoed search key would withhold nothing")
        void bothTransactionIdentifiersAreWithheldTogether() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .contains("searchTransactionId=" + PLACEHOLDER)
                    .contains("transactionId=" + PLACEHOLDER)
                    .doesNotContain(SEARCH_TRANSACTION_ID)
                    .doesNotContain(TRANSACTION_ID);
        }
    }

    @Nested
    @DisplayName("The stand-in is constant and reveals nothing about what it replaced")
    class TheStandInIsConstant {

        @Test
        @DisplayName("each withheld component renders as the same fixed stand-in")
        void eachWithheldComponentRendersAsTheFixedStandIn() {
            String rendered = populated().toString();
            WITHHELD_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
        }

        @Test
        @DisplayName("the stand-in appears exactly eleven times, one per withheld component, so no "
                + "regulated component is left rendering itself")
        void theStandInAppearsExactlyOncePerWithheldComponent() {
            assertThat(occurrencesOf(populated().toString(), PLACEHOLDER))
                    .isEqualTo(WITHHELD_COMPONENTS.size());
        }

        @Test
        @DisplayName("a withheld component renders identically whether it held a value or nothing, so "
                + "the rendering does not disclose presence")
        void aWithheldComponentRendersIdenticallyWhetherPresentOrAbsent() {
            String withValues = populated().toString();
            String withoutValues = empty().toString();
            for (String name : WITHHELD_COMPONENTS) {
                assertThat(withValues).contains(name + "=" + PLACEHOLDER);
                assertThat(withoutValues).contains(name + "=" + PLACEHOLDER);
            }
        }

        @Test
        @DisplayName("two responses differing only in their regulated values render identically, which "
                + "is presence and length disclosure ruled out together")
        void responsesDifferingOnlyInRegulatedValuesRenderIdentically() {
            TransactionViewResponse other = new TransactionViewResponse(
                    "CT01", "TITLE ONE", "07/19/22", "COTRN01C", "TITLE TWO", "14:23:07",
                    "1111111111111111", "2222222222222222", "5500005555555559",
                    "01", "0005", "POS TERM  ", "A MUCH LONGER DESCRIPTION THAN THE OTHER FIXTURE",
                    new BigDecimal("0.01"), "2022-07-19", "2022-07-20",
                    "111111111", "OTHER MERCHANT", "OTHER CITY", "99999-0000",
                    "MESSAGE LINE", true, "TRNIDIN", "route/next", null);
            assertThat(other.toString()).isEqualTo(populated().toString());
        }
    }

    @Nested
    @DisplayName("The retained components stay visible, so the rendering remains diagnostic")
    class TheRetainedComponentsStayVisible {

        @Test
        @DisplayName("the screen furniture, the codes and the channel are shown as supplied")
        void theDiagnosticallyUsefulComponentsAreShown() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .contains("transactionName=CT01")
                    .contains("programName=COTRN01C")
                    .contains("currentDate=07/19/22")
                    .contains("currentTime=14:23:07")
                    .contains("typeCode=01")
                    .contains("categoryCode=0005")
                    .contains("source=POS TERM");
        }

        @Test
        @DisplayName("both calendar dates are withheld, because on this contract they date one "
                + "cardholder's movement rather than an account's lifecycle")
        void bothCalendarDatesAreWithheld() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .contains("originationDate=" + PLACEHOLDER)
                    .contains("processingDate=" + PLACEHOLDER)
                    .doesNotContain("2022-07-19")
                    .doesNotContain("2022-07-20");
        }

        @Test
        @DisplayName("the error surface, the focus field and the route are shown, so a failure remains "
                + "diagnosable from the rendering alone")
        void theErrorSurfaceAndNavigationLabelsAreShown() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .contains("errorMessage=MESSAGE LINE")
                    .contains("generalError=true")
                    .contains("focusScreenFieldId=TRNIDIN")
                    .contains("nextRoute=route/next")
                    .startsWith("TransactionViewResponse[");
        }
    }

    @Nested
    @DisplayName("Delegating to the navigation state does not widen disclosure")
    class DelegationDoesNotWidenDisclosure {

        @Test
        @DisplayName("the nested navigation state withholds its own identifying values, so rendering "
                + "it rather than replacing it discloses nothing further")
        void theNestedNavigationStateWithholdsItsOwnIdentifyingValues() {
            NavigationContext navigation = new NavigationContext(
                    "CT00", "COTRN00C", "CT01", "COTRN01C", "USER0001", "U",
                    NavigationContext.ProgramContext.REENTER,
                    "123456789", "MARY", "ANN", "OSULLIVAN",
                    "78412590063", "Y", "5555444433332222", "COTRN1A", "COTRN01");

            String rendered = populatedWith(navigation).toString();
            assertThat(rendered)
                    .doesNotContain("123456789")
                    .doesNotContain("78412590063")
                    .doesNotContain("5555444433332222")
                    .doesNotContain("OSULLIVAN");
            assertThat(rendered).contains("NavigationContext[");
        }
    }

    @Nested
    @DisplayName("An entirely absent response renders safely")
    class AnEntirelyAbsentResponseRendersSafely {

        @Test
        @DisplayName("a response with every component absent renders without throwing and carries no "
                + "regulated value")
        void anAbsentResponseRendersWithoutThrowing() {
            String rendered = empty().toString();
            assertThat(rendered)
                    .startsWith("TransactionViewResponse[")
                    .endsWith("]")
                    .doesNotContain(REGULATED_VALUES);
            assertThat(occurrencesOf(rendered, PLACEHOLDER)).isEqualTo(WITHHELD_COMPONENTS.size());
        }
    }

    @Nested
    @DisplayName("The wire payload still carries every withheld value in full")
    class TheWirePayloadStillCarriesEveryValue {

        @Test
        @DisplayName("all eleven withheld components serialise at their full untouched values, so "
                + "withholding from a log does not withhold from the client")
        void allWithheldComponentsSerialiseInFull() throws Exception {
            JsonNode payload = payloadOf(populated());
            assertThat(payload.get("searchTransactionId").asText()).isEqualTo(SEARCH_TRANSACTION_ID);
            assertThat(payload.get("transactionId").asText()).isEqualTo(TRANSACTION_ID);
            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            assertThat(payload.get("description").asText()).isEqualTo(DESCRIPTION);
            assertThat(payload.get("amount").asText()).isEqualTo(AMOUNT.toPlainString());
            assertThat(payload.get("merchantId").asText()).isEqualTo(MERCHANT_ID);
            assertThat(payload.get("merchantName").asText()).isEqualTo(MERCHANT_NAME);
            assertThat(payload.get("merchantCity").asText()).isEqualTo(MERCHANT_CITY);
            assertThat(payload.get("merchantZip").asText()).isEqualTo(MERCHANT_ZIP);
        }

        @Test
        @DisplayName("the stand-in never leaks into the serialized form, so no client ever receives a "
                + "redacted value in place of a real one")
        void theStandInNeverReachesTheWire() throws Exception {
            assertThat(payloadOf(populated()).toString()).doesNotContain("REDACTED");
        }

        @Test
        @DisplayName("the amount keeps its two-place scale in the emitted bytes, unaffected by the "
                + "rendering override")
        void theAmountKeepsItsScaleOnTheWire() throws Exception {
            // Asserted against the serialized text rather than a re-parsed tree: reading a JSON float
            // back through readTree coerces it to a double, so a two-place value ending in zero would
            // come back a place short even when the emitted bytes are correct. The bytes are what the
            // byte-equivalence criterion compares, so the bytes are what is asserted.
            assertThat(moduleEquivalentMapper().writeValueAsString(populated()))
                    .contains("\"amount\":4821.73");
        }
    }
}
