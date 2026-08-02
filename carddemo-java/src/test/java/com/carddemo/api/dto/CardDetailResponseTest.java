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
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardDetailResponse}, the response body of legacy transaction {@code CCDL}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>Two properties dominate what is checked.
 *
 * <p>The first is that this type now speaks the package's vocabulary. Its two screen titles and its
 * routing hint were spelled three different ways across the DTO families, and a client could not learn
 * one name for one concept. The tests below hold the canonical spellings and hold the superseded ones
 * absent, on the wire as well as in the declaration.
 *
 * <p>The second is disclosure. This is the one response in the card family that carries a card number,
 * an embossed cardholder name and an expiry date together, which makes its generated rendering the most
 * sensitive grouping in the package - a complete payment instrument on one line. The rendering
 * withholds all five regulated values while keeping the screen furniture that identifies nobody, and
 * the withholding is confined to the rendering: every accessor and the whole wire form are untouched,
 * because the detail screen echoes what it retrieved character for character.
 *
 * <p>Operator diagnostics are asserted byte for byte, including the two filter messages whose legacy
 * text is upper case, omits the space after the comma and reads "A 11" and "A 16" rather than "AN 11".
 * Those oddities are the contract, and a tidied message would be a behavioural change.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("CardDetailResponse :: card-detail response contract of legacy transaction CCDL")
class CardDetailResponseTest {

    /** The eighteen record components in the order the symbolic map declares their fields. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "accountId", "cardNumber", "embossedName", "cardActiveStatus", "expiryMonth",
            "expiryYear", "infoMessage", "errorMessage", "generalError", "focusScreenFieldId",
            "nextRoute", "navigationContext");

    private static final String ACCOUNT_ID = "00000000011";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String EMBOSSED_NAME = "MARY ANN SMITH";
    private static final String EXPIRY_MONTH = "07";
    private static final String EXPIRY_YEAR = "2027";
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CCDL", "COCRDSLC", "CCDL", "COCRDSLC", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", CARD_NUMBER, "CCRDSLA", "COCRDSL");
    }

    private static CardDetailResponse populated() {
        return new CardDetailResponse("CCDL", "AWS Mainframe Modernization", "08/02/26", "COCRDSLC",
                "CardDemo", "14:30:00", ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, "Y", EXPIRY_MONTH,
                EXPIRY_YEAR, CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT, null, false,
                CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID, "/api/cards/detail", navigation());
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

    private static JsonNode payloadOf(CardDetailResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static List<String> componentNames() {
        return Arrays.stream(CardDetailResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotation of the requested type present on a component's backing field.
     *
     * <p>Read from the field rather than from the record component: {@code @Size} does not declare
     * {@code RECORD_COMPONENT} among its targets, so asking the component yields {@code null} for every
     * component here and an assertion phrased that way would pass without testing anything.
     *
     * @param name the component name
     * @param <A> the annotation type
     * @param type the annotation type to read
     * @return the annotation, or {@code null} when absent
     */
    private static <A extends java.lang.annotation.Annotation> A annotationOn(String name,
            Class<A> type) {
        try {
            return CardDetailResponse.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, excluding the delegated navigation
     * state.
     *
     * <p>Necessary because the nested navigation contract names some of the same components and prints
     * values of its own, so an assertion over the whole string would not be about this type.
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
        @DisplayName("declares exactly the eighteen components in map order")
        void declaresEighteenComponentsInMapOrder() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER)
                    .hasSize(18);
        }

        @Test
        @DisplayName("uses the package's canonical names for the titles, route and focus hint")
        void usesCanonicalNames() {
            assertThat(componentNames()).contains("title01", "title02", "nextRoute",
                    "focusScreenFieldId");
            assertThat(componentNames()).doesNotContain("screenTitleLine1", "screenTitleLine2",
                    "route", "focusField", "fieldToFocus");
        }

        @Test
        @DisplayName("bounds every text component at its measured map width")
        void boundsEveryTextComponentAtItsMapWidth() {
            assertThat(annotationOn("transactionName", Size.class).max()).isEqualTo(4);
            assertThat(annotationOn("title01", Size.class).max()).isEqualTo(40);
            assertThat(annotationOn("title02", Size.class).max()).isEqualTo(40);
            assertThat(annotationOn("accountId", Size.class).max()).isEqualTo(11);
            assertThat(annotationOn("cardNumber", Size.class).max()).isEqualTo(16);
            assertThat(annotationOn("embossedName", Size.class).max()).isEqualTo(50);
            assertThat(annotationOn("cardActiveStatus", Size.class).max()).isEqualTo(1);
            assertThat(annotationOn("expiryMonth", Size.class).max()).isEqualTo(2);
            assertThat(annotationOn("expiryYear", Size.class).max()).isEqualTo(4);
        }

        @Test
        @DisplayName("bounds the focus hint at the width of a BMS field identifier")
        void boundsTheFocusHint() {
            assertThat(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(annotationOn("focusScreenFieldId", Size.class).max())
                    .isEqualTo(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("leaves the routing hint unbounded, because it is not a screen field")
        void leavesTheRoutingHintUnbounded() {
            assertThat(annotationOn("nextRoute", Size.class))
                    .as("a route is an endpoint path invented here, not a map item")
                    .isNull();
        }

        @Test
        @DisplayName("names the two focusable screen fields exactly as the mapset declares them")
        void namesTheTwoFocusableFields() {
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID).isEqualTo("ACCTSID");
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER).isEqualTo("CARDSID");
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID.length())
                    .isLessThanOrEqualTo(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER.length())
                    .isLessThanOrEqualTo(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
        }
    }

    @Nested
    @DisplayName("canonical names reach the wire")
    class CanonicalNamesReachTheWire {

        @Test
        @DisplayName("publishes the canonical spellings")
        void publishesCanonicalSpellings() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.has("title01")).isTrue();
            assertThat(payload.has("title02")).isTrue();
            assertThat(payload.has("nextRoute")).isTrue();
            assertThat(payload.has("focusScreenFieldId")).isTrue();
        }

        @Test
        @DisplayName("publishes none of the superseded spellings")
        void publishesNoSupersededSpelling() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.has("screenTitleLine1")).isFalse();
            assertThat(payload.has("screenTitleLine2")).isFalse();
            assertThat(payload.has("route")).isFalse();
            assertThat(payload.has("focusField")).isFalse();
        }

        @Test
        @DisplayName("carries the two title values under the renamed keys")
        void carriesTitleValuesUnderRenamedKeys() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("title01").asText()).isEqualTo("AWS Mainframe Modernization");
            assertThat(payload.get("title02").asText()).isEqualTo("CardDemo");
            assertThat(payload.get("nextRoute").asText()).isEqualTo("/api/cards/detail");
            assertThat(payload.get("focusScreenFieldId").asText()).isEqualTo("ACCTSID");
        }

        @Test
        @DisplayName("binds a body written in the canonical vocabulary")
        void bindsABodyInTheCanonicalVocabulary() throws JsonProcessingException {
            CardDetailResponse bound = moduleEquivalentMapper().readValue(
                    "{\"title01\":\"A\",\"title02\":\"B\",\"nextRoute\":\"/r\","
                            + "\"focusScreenFieldId\":\"CARDSID\"}",
                    CardDetailResponse.class);

            assertThat(bound.title01()).isEqualTo("A");
            assertThat(bound.title02()).isEqualTo("B");
            assertThat(bound.nextRoute()).isEqualTo("/r");
            assertThat(bound.focusScreenFieldId()).isEqualTo("CARDSID");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds the whole payment instrument")
        void withholdsTheWholePaymentInstrument() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).doesNotContain(ACCOUNT_ID, CARD_NUMBER, EMBOSSED_NAME, EXPIRY_YEAR);
            assertThat(rendered).contains("accountId=" + REDACTED, "cardNumber=" + REDACTED,
                    "embossedName=" + REDACTED, "expiryMonth=" + REDACTED,
                    "expiryYear=" + REDACTED);
        }

        @Test
        @DisplayName("retains the screen furniture that identifies nobody")
        void retainsScreenFurniture() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).contains("transactionName=CCDL", "title01=AWS Mainframe"
                    + " Modernization", "programName=COCRDSLC", "currentTime=14:30:00",
                    "cardActiveStatus=Y", "generalError=false", "focusScreenFieldId=ACCTSID",
                    "nextRoute=/api/cards/detail");
        }

        @Test
        @DisplayName("delegates the navigation state, which withholds its own identifiers")
        void delegatesTheNavigationState() {
            String rendered = populated().toString();

            assertThat(rendered).contains(", navigationContext=NavigationContext[");
            assertThat(rendered).doesNotContain(ACCOUNT_ID, CARD_NUMBER);
        }

        @Test
        @DisplayName("is bracketed by the type name")
        void isBracketedByTheTypeName() {
            assertThat(populated().toString()).startsWith("CardDetailResponse[").endsWith("]");
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            CardDetailResponse response = populated();
            response.toString();

            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(response.expiryMonth()).isEqualTo(EXPIRY_MONTH);
            assertThat(response.expiryYear()).isEqualTo(EXPIRY_YEAR);
        }

        @Test
        @DisplayName("changes nothing on the wire")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountId").asText()).isEqualTo(ACCOUNT_ID);
            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
            assertThat(payload.get("embossedName").asText()).isEqualTo(EMBOSSED_NAME);
            assertThat(payload.get("expiryMonth").asText()).isEqualTo(EXPIRY_MONTH);
            assertThat(payload.get("expiryYear").asText()).isEqualTo(EXPIRY_YEAR);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("tolerates an instance with nothing populated")
        void toleratesAnEmptyInstance() {
            CardDetailResponse empty = new CardDetailResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, false, null, null, null);

            assertThat(empty.toString()).contains("cardNumber=" + REDACTED,
                    "navigationContext=null");
        }
    }

    @Nested
    @DisplayName("operator diagnostics reproduce the legacy text byte for byte")
    class OperatorDiagnosticsReproduceLegacyText {

        @Test
        @DisplayName("keeps the upper-case filter messages with their legacy oddities")
        void keepsTheFilterMessagesWithTheirOddities() {
            assertThat(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC)
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC)
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        }

        @Test
        @DisplayName("keeps the missing space after the comma in both filter messages")
        void keepsTheMissingSpaceAfterTheComma() {
            assertThat(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC)
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF");
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC)
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF");
        }

        @Test
        @DisplayName("keeps the ungrammatical article in both filter messages")
        void keepsTheUngrammaticalArticle() {
            assertThat(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC)
                    .contains("A 11")
                    .doesNotContain("AN 11");
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC).contains("A 16");
        }

        @Test
        @DisplayName("keeps the mixed-case searched-value messages distinct from the filter pair")
        void keepsTheSearchedValueMessagesDistinct() {
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC)
                    .isEqualTo("Account number must be a non zero 11 digit number")
                    .isNotEqualTo(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC);
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC)
                    .isEqualTo("Card number if supplied must be a 16 digit number")
                    .isNotEqualTo(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC);
        }

        @Test
        @DisplayName("keeps the two messages that share one text as two separate constants")
        void keepsTheTwoZeroAndNotNumericMessagesSeparate() {
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES)
                    .as("the legacy program raises the same text on two distinct branches")
                    .isEqualTo(CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC);
        }

        @Test
        @DisplayName("keeps the trailing spaces that are part of the exit message")
        void keepsTrailingSpacesInTheExitMessage() {
            assertThat(CardDetailResponse.MSG_EXIT)
                    .isEqualTo("PF03 pressed.Exiting              ")
                    .endsWith(" ");
        }

        @Test
        @DisplayName("keeps the leading spaces that are part of the found-details message")
        void keepsLeadingSpacesInTheFoundDetailsMessage() {
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT)
                    .isEqualTo("   Displaying requested details")
                    .startsWith("   ");
        }

        @Test
        @DisplayName("keeps the prompts and the not-found messages exactly")
        void keepsThePromptsAndNotFoundMessages() {
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_INPUT)
                    .isEqualTo("Please enter Account and Card Number");
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT)
                    .isEqualTo("Account number not provided");
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_CARD)
                    .isEqualTo("Card number not provided");
            assertThat(CardDetailResponse.MSG_NO_SEARCH_CRITERIA_RECEIVED)
                    .isEqualTo("No input received");
            assertThat(CardDetailResponse.MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE)
                    .isEqualTo("Did not find this account in cards database");
            assertThat(CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION)
                    .isEqualTo("Did not find cards for this search condition");
            assertThat(CardDetailResponse.MSG_CARD_DATA_READ_ERROR)
                    .isEqualTo("Error reading Card Data File");
            assertThat(CardDetailResponse.MSG_CODING_TO_BE_DONE).isEqualTo("Looks Good.... so far");
            assertThat(CardDetailResponse.MSG_UNEXPECTED_DATA_SCENARIO)
                    .isEqualTo("UNEXPECTED DATA SCENARIO");
        }

        @Test
        @DisplayName("keeps every diagnostic within the error-message field width")
        void keepsEveryDiagnosticWithinFieldWidth() {
            List<String> diagnostics = List.of(
                    CardDetailResponse.MSG_PROMPT_FOR_INPUT,
                    CardDetailResponse.MSG_EXIT,
                    CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT,
                    CardDetailResponse.MSG_PROMPT_FOR_CARD,
                    CardDetailResponse.MSG_NO_SEARCH_CRITERIA_RECEIVED,
                    CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES,
                    CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC,
                    CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC,
                    CardDetailResponse.MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE,
                    CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION,
                    CardDetailResponse.MSG_CARD_DATA_READ_ERROR,
                    CardDetailResponse.MSG_CODING_TO_BE_DONE,
                    CardDetailResponse.MSG_UNEXPECTED_DATA_SCENARIO,
                    CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC,
                    CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC);

            assertThat(diagnostics)
                    .allSatisfy(text -> assertThat(text.length())
                            .isLessThanOrEqualTo(CardDetailResponse.ERROR_MESSAGE_LENGTH));
        }
    }
}
