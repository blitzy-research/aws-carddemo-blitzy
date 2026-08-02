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
import java.util.Locale;

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
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardDetailResponse}, the response body of legacy transaction {@code CCDL}
 * implemented by {@code app/cbl/COCRDSLC.cbl} over screen {@code app/cpy-bms/COCRDSL.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Three literals carry padding that looks like a mistake and is not.</strong> The
 * found-details message opens with three spaces, the exit message closes with fourteen, and both come
 * straight from the legacy {@code MOVE} of a space-filled literal into a fixed screen field. A reader
 * tidying either one would change what an operator sees, and Gate 5 compares the operator-visible text
 * character for character - so the padding is asserted by exact equality and by length, not by
 * {@code contains}.
 *
 * <p><strong>Two constants deliberately hold the same text.</strong> A searched account of all zeroes and
 * a searched account that is not numeric both report that the account must be a non-zero eleven digit
 * number, because the legacy program emits one message for the two conditions. Collapsing them into a
 * single constant would lose the two distinct call sites the traceability matrix records; giving them
 * different text would invent a message the legacy never emits. Their equality is therefore asserted
 * explicitly, so that neither correction can be made silently.
 *
 * @see CardDetailResponse
 */
@DisplayName("CardDetailResponse - the CCDL card detail response contract")
class CardDetailResponseRuleComplianceTest {

    /** A card number at the legacy sixteen-digit width. */
    private static final String CARD_NUMBER = "4111111111111111";

    /**
     * The placeholder the record's own rendering substitutes for a withheld component.
     *
     * <p>Declared here rather than read from the response, whose copy is private on purpose: a
     * placeholder a caller could reach would be a placeholder a caller could compare against, and
     * the point of it is that nothing downstream branches on whether a value was withheld.</p>
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /** An account identifier at the legacy eleven-digit width. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * A mapper configured exactly as {@code application.yml} configures the application's own.
     *
     * @return the module-equivalent mapper
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
    private static JsonNode payloadOf(final CardDetailResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Builds a fully populated response describing one found card.
     *
     * @return the populated response
     */
    private static CardDetailResponse aFoundCardResponse() {
        return new CardDetailResponse("CCDL", "AWS Mainframe Modernization", "01/15/22", "COCRDSLC",
                "CardDemo", "10:30:00", ACCOUNT_ID, CARD_NUMBER, "JOHN Q PUBLIC", "Y", "12", "2025",
                CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT, null, false,
                CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID, "card-detail",
                NavigationContext.empty());
    }

    /**
     * Builds a response reporting a failure, with no card values populated.
     *
     * @param errorMessage the operator message to carry
     * @return the failure response
     */
    private static CardDetailResponse aFailureResponse(final String errorMessage) {
        return new CardDetailResponse("CCDL", null, null, "COCRDSLC", null, null, null, null, null,
                null, null, null, null, errorMessage, true,
                CardDetailResponse.SCREEN_FIELD_CARD_NUMBER, "card-detail",
                NavigationContext.empty());
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
        final Size size = CardDetailResponse.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    /**
     * Every operator message the response publishes.
     *
     * @return the sixteen messages in declaration order
     */
    private static List<String> everyOperatorMessage() {
        return List.of(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT,
                CardDetailResponse.MSG_PROMPT_FOR_INPUT, CardDetailResponse.MSG_EXIT,
                CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT, CardDetailResponse.MSG_PROMPT_FOR_CARD,
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
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published widths and screen field identifiers")
    class ThePublishedWidthsAndScreenFieldIdentifiers {

        @Test
        @DisplayName("the screen-furniture widths are the legacy ones")
        void theScreenFurnitureWidthsAreTheLegacyOnes() {
            assertThat(CardDetailResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(CardDetailResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(CardDetailResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(CardDetailResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(CardDetailResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the card widths are the legacy record widths")
        void theCardWidthsAreTheLegacyRecordWidths() {
            assertThat(CardDetailResponse.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(CardDetailResponse.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(CardDetailResponse.EMBOSSED_NAME_LENGTH).isEqualTo(50);
            assertThat(CardDetailResponse.CARD_ACTIVE_STATUS_LENGTH).isEqualTo(1);
            assertThat(CardDetailResponse.EXPIRY_MONTH_LENGTH).isEqualTo(2);
            assertThat(CardDetailResponse.EXPIRY_YEAR_LENGTH).isEqualTo(4);
        }

        @Test
        @DisplayName("the error field is wider than the information field on this screen, which is the "
                + "reverse of the card-list screen and is why the two are not shared")
        void theErrorFieldIsWiderThanTheInformationField() {
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH).isEqualTo(40);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH)
                    .isLessThan(CardDetailResponse.ERROR_MESSAGE_LENGTH);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH)
                    .isNotEqualTo(CardListResponse.ERROR_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the two screen field identifiers are the legacy map field names, each within the "
                + "focus field's own width")
        void theTwoScreenFieldIdentifiersAreTheLegacyMapFieldNames() {
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID).isEqualTo("ACCTSID");
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER).isEqualTo("CARDSID");
            assertThat(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID)
                    .hasSize(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
            assertThat(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER)
                    .hasSize(CardDetailResponse.FOCUS_SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("the card widths agree with the update request's own, so a value shown on this "
                + "screen can be resubmitted to that one without truncation")
        void theCardWidthsAgreeWithTheUpdateRequest() throws NoSuchMethodException {
            assertThat(CardDetailResponse.ACCOUNT_ID_LENGTH).isEqualTo(
                    CardUpdateRequest.class.getDeclaredMethod("accountId")
                            .getAnnotation(Size.class).max());
            assertThat(CardDetailResponse.CARD_NUMBER_LENGTH).isEqualTo(
                    CardUpdateRequest.class.getDeclaredMethod("cardNumber")
                            .getAnnotation(Size.class).max());
            assertThat(CardDetailResponse.EMBOSSED_NAME_LENGTH).isEqualTo(
                    CardUpdateRequest.class.getDeclaredMethod("embossedName")
                            .getAnnotation(Size.class).max());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the sixteen operator messages, character for character")
    class TheSixteenOperatorMessages {

        @Test
        @DisplayName("the found-details message opens with exactly three spaces, as the legacy literal "
                + "does")
        void theFoundDetailsMessageOpensWithThreeSpaces() {
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT)
                    .isEqualTo("   Displaying requested details");
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT).startsWith("   ");
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT.charAt(3)).isEqualTo('D');
            assertThat(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT).hasSize(31);
        }

        @Test
        @DisplayName("the exit message keeps its trailing space filling, so the field it is written into "
                + "is fully overwritten rather than partly")
        void theExitMessageKeepsItsTrailingSpaceFilling() {
            assertThat(CardDetailResponse.MSG_EXIT).isEqualTo("PF03 pressed.Exiting              ");
            assertThat(CardDetailResponse.MSG_EXIT).endsWith(" ");
            assertThat(CardDetailResponse.MSG_EXIT).hasSize(34);
            assertThat(CardDetailResponse.MSG_EXIT.strip()).isEqualTo("PF03 pressed.Exiting");
        }

        @Test
        @DisplayName("the exit message carries no space after its full stop, as the legacy literal does "
                + "not")
        void theExitMessageCarriesNoSpaceAfterItsFullStop() {
            assertThat(CardDetailResponse.MSG_EXIT).contains("pressed.Exiting");
            assertThat(CardDetailResponse.MSG_EXIT).doesNotContain("pressed. Exiting");
        }

        @Test
        @DisplayName("the two prompts carry the legacy text")
        void theTwoPromptsCarryTheLegacyText() {
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_INPUT)
                    .isEqualTo("Please enter Account and Card Number");
            assertThat(CardDetailResponse.MSG_NO_SEARCH_CRITERIA_RECEIVED)
                    .isEqualTo("No input received");
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_ACCOUNT)
                    .isEqualTo("Account number not provided");
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_CARD)
                    .isEqualTo("Card number not provided");
        }

        @Test
        @DisplayName("the zeroes message and the not-numeric message hold the same text, because the "
                + "legacy program emits one message for the two conditions")
        void theZeroesAndNotNumericMessagesHoldTheSameText() {
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES)
                    .isEqualTo("Account number must be a non zero 11 digit number");
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_NOT_NUMERIC)
                    .isEqualTo(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES);
        }

        @Test
        @DisplayName("the card not-numeric message says the card number is optional, which the account "
                + "message does not")
        void theCardNotNumericMessageSaysTheCardNumberIsOptional() {
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC)
                    .isEqualTo("Card number if supplied must be a 16 digit number");
            assertThat(CardDetailResponse.MSG_SEARCHED_CARD_NOT_NUMERIC).contains("if supplied");
            assertThat(CardDetailResponse.MSG_SEARCHED_ACCOUNT_ZEROES)
                    .doesNotContain("if supplied");
        }

        @Test
        @DisplayName("the two lookup-failure messages and the read-error message carry the legacy text")
        void theLookupFailureMessagesCarryTheLegacyText() {
            assertThat(CardDetailResponse.MSG_ACCOUNT_NOT_IN_CARD_CROSS_REFERENCE)
                    .isEqualTo("Did not find this account in cards database");
            assertThat(CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION)
                    .isEqualTo("Did not find cards for this search condition");
            assertThat(CardDetailResponse.MSG_CARD_DATA_READ_ERROR)
                    .isEqualTo("Error reading Card Data File");
        }

        @Test
        @DisplayName("the placeholder-progress message carries its four full stops verbatim, because it "
                + "is what the legacy screen shows and not a marker of unfinished work here")
        void thePlaceholderProgressMessageCarriesItsFourFullStops() {
            assertThat(CardDetailResponse.MSG_CODING_TO_BE_DONE).isEqualTo("Looks Good.... so far");
            assertThat(CardDetailResponse.MSG_CODING_TO_BE_DONE).contains("....");
        }

        @Test
        @DisplayName("the unexpected-scenario message is upper case while the rest of this screen's "
                + "messages are mixed, and the difference is the legacy's own")
        void theUnexpectedScenarioMessageIsUpperCase() {
            assertThat(CardDetailResponse.MSG_UNEXPECTED_DATA_SCENARIO)
                    .isEqualTo("UNEXPECTED DATA SCENARIO");
            assertThat(CardDetailResponse.MSG_PROMPT_FOR_INPUT)
                    .isNotEqualTo(CardDetailResponse.MSG_PROMPT_FOR_INPUT.toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("the two filter messages match the card-list screen's own word for word, because the "
                + "two screens share the operator-facing wording")
        void theTwoFilterMessagesMatchTheCardListScreen() {
            assertThat(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC)
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER")
                    .isEqualTo(CardListResponse.MSG_ACCOUNT_FILTER_INVALID);
            assertThat(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC)
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER")
                    .isEqualTo(CardListResponse.MSG_CARD_FILTER_INVALID);
        }

        @Test
        @DisplayName("every message fits the error field, so no message can be published wider than the "
                + "widest field that renders it")
        void everyMessageFitsTheErrorField() {
            assertThat(everyOperatorMessage()).allSatisfy(message ->
                    assertThat(message.length())
                            .isLessThanOrEqualTo(CardDetailResponse.ERROR_MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("exactly one pair of messages shares its text, so no third message has drifted into "
                + "duplicating another")
        void exactlyOnePairOfMessagesSharesItsText() {
            final List<String> messages = everyOperatorMessage();

            assertThat(messages).hasSize(16);
            assertThat(messages.stream().distinct().toList()).hasSize(15);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape")
    class TheDeclaredShape {

        @Test
        @DisplayName("the response declares eighteen components in screen order")
        void theResponseDeclaresEighteenComponents() {
            final List<String> declared =
                    Arrays.stream(CardDetailResponse.class.getRecordComponents())
                            .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("transactionName", "title01",
                    "currentDate", "programName", "title02", "currentTime", "accountId",
                    "cardNumber", "embossedName", "cardActiveStatus", "expiryMonth", "expiryYear",
                    "infoMessage", "errorMessage", "generalError", "focusScreenFieldId", "nextRoute",
                    "navigationContext");
            assertThat(declared).hasSize(18);
        }

        @Test
        @DisplayName("the only primitive component is the general-error flag")
        void theOnlyPrimitiveComponentIsTheGeneralErrorFlag() {
            assertThat(Arrays.stream(CardDetailResponse.class.getRecordComponents())
                    .filter(component -> component.getType().isPrimitive())
                    .map(RecordComponent::getName).toList())
                    .containsExactly("generalError");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final CardDetailResponse response = aFoundCardResponse();

            assertThat(response.transactionName()).isEqualTo("CCDL");
            assertThat(response.title01()).isEqualTo("AWS Mainframe Modernization");
            assertThat(response.currentDate()).isEqualTo("01/15/22");
            assertThat(response.programName()).isEqualTo("COCRDSLC");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.currentTime()).isEqualTo("10:30:00");
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(response.embossedName()).isEqualTo("JOHN Q PUBLIC");
            assertThat(response.cardActiveStatus()).isEqualTo("Y");
            assertThat(response.expiryMonth()).isEqualTo("12");
            assertThat(response.expiryYear()).isEqualTo("2025");
            assertThat(response.infoMessage())
                    .isEqualTo(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT);
            assertThat(response.errorMessage()).isNull();
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId())
                    .isEqualTo(CardDetailResponse.SCREEN_FIELD_ACCOUNT_ID);
            assertThat(response.nextRoute()).isEqualTo("card-detail");
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a failure response carries the message and the flag and no card values, because "
                + "there is no card to describe")
        void aFailureResponseCarriesNoCardValues() {
            final CardDetailResponse response =
                    aFailureResponse(CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION);

            assertThat(response.generalError()).isTrue();
            assertThat(response.errorMessage())
                    .isEqualTo(CardDetailResponse.MSG_NO_CARDS_FOR_SEARCH_CONDITION);
            assertThat(response.infoMessage()).isNull();
            assertThat(response.cardNumber()).isNull();
            assertThat(response.embossedName()).isNull();
            assertThat(response.focusScreenFieldId())
                    .isEqualTo(CardDetailResponse.SCREEN_FIELD_CARD_NUMBER);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared validation bounds")
    class TheDeclaredValidationBounds {

        @ParameterizedTest(name = "{0} bounded at {1}")
        @CsvSource({
            "transactionName, 4",
            "title01, 40",
            "currentDate, 8",
            "programName, 8",
            "title02, 40",
            "currentTime, 8",
            "accountId, 11",
            "cardNumber, 16",
            "embossedName, 50",
            "cardActiveStatus, 1",
            "expiryMonth, 2",
            "expiryYear, 4",
            "infoMessage, 40",
            "errorMessage, 80",
            "focusScreenFieldId, 7"
        })
        @DisplayName("each bounded component carries the legacy field width")
        void eachBoundedComponentCarriesTheLegacyWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {

            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the route carries no upper bound, because it is a server-chosen token rather than a "
                + "screen field")
        void theRouteCarriesNoUpperBound() throws NoSuchMethodException {
            assertThat(CardDetailResponse.class.getDeclaredMethod("nextRoute").getAnnotation(Size.class))
                    .isNull();
        }

        @Test
        @DisplayName("a fully populated response passes validation, message padding included")
        void aFullyPopulatedResponsePassesValidation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aFoundCardResponse())).isEmpty();
            }
        }

        @ParameterizedTest(name = "carrying [{0}]")
        @ValueSource(strings = {"   Displaying requested details", "PF03 pressed.Exiting              "})
        @DisplayName("each padded literal fits the field that carries it, so publishing one does not "
                + "trip validation on the very message the legacy always sends")
        void eachPaddedLiteralFitsTheFieldThatCarriesIt(final String message) {
            final CardDetailResponse response = new CardDetailResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null, message, null, false, null, null,
                    null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response)).isEmpty();
            }
            assertThat(message.length())
                    .isLessThanOrEqualTo(CardDetailResponse.INFO_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("a response with nothing supplied passes validation, which is the state a first "
                + "entry to the screen is rendered from")
        void anEmptyResponsePassesValidation() {
            final CardDetailResponse empty = new CardDetailResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, false, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(empty)).isEmpty();
            }
        }

        @Test
        @DisplayName("a card number one digit over its width is reported against that component alone")
        void aCardNumberOneDigitOverItsWidthIsReported() {
            final CardDetailResponse overBound = new CardDetailResponse(null, null, null, null, null,
                    null, null, "4".repeat(CardDetailResponse.CARD_NUMBER_LENGTH + 1), null, null,
                    null, null, null, null, false, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("cardNumber");
            }
        }

        @Test
        @DisplayName("an information message one character over its narrower field is reported even "
                + "though it would fit the error field, so the two fields stay separate")
        void anInformationMessageOverItsNarrowerFieldIsReported() {
            final CardDetailResponse overBound = new CardDetailResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    "X".repeat(CardDetailResponse.INFO_MESSAGE_LENGTH + 1), null, false, null, null,
                    null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("infoMessage");
            }
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH + 1)
                    .isLessThanOrEqualTo(CardDetailResponse.ERROR_MESSAGE_LENGTH);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two responses built the same way are equal and share a hash code")
        void twoIdenticalResponsesAreEqual() {
            assertThat(aFoundCardResponse()).isEqualTo(aFoundCardResponse())
                    .hasSameHashCodeAs(aFoundCardResponse());
        }

        @Test
        @DisplayName("a found response and a failure response are never equal")
        void aFoundAndAFailureResponseAreNeverEqual() {
            assertThat(aFoundCardResponse()).isNotEqualTo(
                    aFailureResponse(CardDetailResponse.MSG_CARD_DATA_READ_ERROR));
        }

        @Test
        @DisplayName("two responses whose messages differ only in padding are not equal, which is why the "
                + "padding must be reproduced rather than trimmed")
        void twoResponsesDifferingOnlyInPaddingAreNotEqual() {
            final CardDetailResponse padded = new CardDetailResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT, null, false, null, null, null);
            final CardDetailResponse trimmed = new CardDetailResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null,
                    CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT.strip(), null, false, null, null,
                    null);

            assertThat(padded).isNotEqualTo(trimmed);
        }

        @Test
        @DisplayName("an absent component is omitted from the payload and the boolean is always present")
        void anAbsentComponentIsOmittedAndTheBooleanIsAlwaysPresent()
                throws JsonProcessingException {

            final JsonNode payload = payloadOf(aFoundCardResponse());

            assertThat(payload.has("errorMessage")).isFalse();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("cardNumber").asText()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("a padded message survives the payload with its padding intact, so a client renders "
                + "what the legacy screen showed")
        void aPaddedMessageSurvivesThePayloadWithItsPaddingIntact() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aFoundCardResponse());

            assertThat(payload.get("infoMessage").asText())
                    .isEqualTo(CardDetailResponse.MSG_FOUND_CARDS_FOR_ACCOUNT)
                    .startsWith("   ");
        }

        @Test
        @DisplayName("the exit message survives the payload with its trailing filling intact")
        void theExitMessageSurvivesThePayloadWithItsTrailingFilling() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aFailureResponse(CardDetailResponse.MSG_EXIT));

            assertThat(payload.get("errorMessage").asText())
                    .isEqualTo(CardDetailResponse.MSG_EXIT)
                    .hasSize(CardDetailResponse.MSG_EXIT.length())
                    .endsWith(" ");
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final CardDetailResponse original = aFoundCardResponse();

            assertThat(mapper.readValue(mapper.writeValueAsString(original),
                    CardDetailResponse.class)).isEqualTo(original);
        }

        /**
         * The rendering withholds the five components that identify a cardholder's instrument.
         *
         * <p>A record's generated rendering names every component and reproduces every value, which for
         * this response would put a card number, an embossed name, an account identifier and an expiry
         * date into whatever accepted the rendering - a log line, an exception message, a debugger
         * transcript. The rendering is therefore hand-written and substitutes a fixed placeholder for
         * those five, keeping the screen furniture and the routing state in the clear because a reader
         * needs them to place the record and none of them identifies anybody.</p>
         *
         * <p>The expiry components are withheld even though neither is secret on its own: together with
         * the card number they complete the set a card-not-present transaction needs, and withholding
         * them costs a reader nothing they cannot obtain from the response itself.</p>
         */
        @Test
        @DisplayName("the rendering withholds the five instrument components and keeps the screen "
                + "furniture, so the response is safe to render whole")
        void theRenderingWithholdsTheInstrumentComponents() {
            final String rendered = aFoundCardResponse().toString();

            assertThat(rendered)
                    .startsWith("CardDetailResponse[")
                    .contains("accountId=" + REDACTION_PLACEHOLDER)
                    .contains("cardNumber=" + REDACTION_PLACEHOLDER)
                    .contains("embossedName=" + REDACTION_PLACEHOLDER)
                    .contains("expiryMonth=" + REDACTION_PLACEHOLDER)
                    .contains("expiryYear=" + REDACTION_PLACEHOLDER)
                    .doesNotContain(CARD_NUMBER);
            final CardDetailResponse withoutNestedState = new CardDetailResponse("CCDL",
                    "AWS Mainframe Modernization", "01/15/22", "COCRDSLC", "CardDemo", "10:30:00",
                    ACCOUNT_ID, CARD_NUMBER, "JOHN SMITH", "Y", "12", "2025", null, null, false,
                    null, "card-detail", null);
            assertThat(withoutNestedState.toString()
                    .split(java.util.regex.Pattern.quote(REDACTION_PLACEHOLDER), -1))
                    .as("exactly five components are withheld by this record's own rendering, counted "
                            + "with an absent navigation block so the figure measures this record's "
                            + "redactions rather than two records' summed")
                    .hasSize(6);
        }

        @Test
        @DisplayName("the rendering still names the screen furniture and the routing state, because a "
                + "reader cannot place the record without them and neither identifies anybody")
        void theRenderingStillNamesTheScreenFurniture() {
            assertThat(aFoundCardResponse().toString())
                    .contains("transactionName=CCDL")
                    .contains("programName=COCRDSLC")
                    .contains("cardActiveStatus=Y")
                    .contains("nextRoute=card-detail");
        }

        @Test
        @DisplayName("the withholding is unconditional, so an absent instrument component renders as the "
                + "placeholder rather than betraying its absence")
        void theWithholdingIsUnconditional() {
            final CardDetailResponse empty = new CardDetailResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, false, null, null, null);

            assertThat(empty.toString())
                    .contains("cardNumber=" + REDACTION_PLACEHOLDER)
                    .contains("embossedName=" + REDACTION_PLACEHOLDER)
                    .doesNotContain("cardNumber=null");
        }
    }
}
