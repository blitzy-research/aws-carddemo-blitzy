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

import com.carddemo.domain.enums.TransactionSourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for the transaction-view contract of legacy transaction {@code CT01}, held to its map
 * widths.
 *
 * <p>The two sixteen-character transaction identifiers - the key that was searched for and the one
 * that was retrieved - are separate components on purpose: the record-absent reply carries the search
 * key with no retrieved identifier, so deriving either from the other would lose that state. Both
 * stay text so sixteen leading-zero digits survive, and the two ten-character dates stay opaque text
 * so an all-space value round-trips unchanged rather than becoming a parsed date.
 */
@DisplayName("TransactionViewResponse :: the CT01 transaction-view contract, held to its map widths")
class TransactionViewResponseTest {
    private static final List<String> PUBLISHED_MEMBERS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "searchTransactionId", "transactionId", "cardNumber", "typeCode", "categoryCode",
            "source", "description", "amount", "originationDate", "processingDate", "merchantId",
            "merchantName", "merchantCity", "merchantZip", "errorMessage", "generalError", "fieldErrors",
            "focusScreenFieldId", "nextRoute", "navigationContext");

    private static final String TRANSACTION_NAME = "CT01";

    private static final String TITLE01 = "AWS Mainframe Modernization";

    private static final String TITLE02 = "View Transaction";

    private static final String CURRENT_DATE = "06/10/22";

    private static final String CURRENT_TIME = "19:27:53";

    private static final String PROGRAM_NAME = "COTRN01C";

    private static final String SEARCH_TRANSACTION_ID = "0000000000000042";

    private static final String FOUND_TRANSACTION_ID = "0000000000000199";

    private static final String CARD_NUMBER = "5111111111111118";

    private static final String TYPE_CODE = "01";

    private static final String CATEGORY_CODE = "0002";

    private static final String SOURCE = "POS TERM  ";

    private static final String DESCRIPTION = "PURCHASE AT HARDWARE STORE";

    private static final BigDecimal AMOUNT = new BigDecimal("-123.45");

    private static final String ORIGINATION_DATE = "2022-06-10";

    private static final String PROCESSING_DATE = "2022-06-13";

    private static final String MERCHANT_ID = "000000042";

    private static final String MERCHANT_NAME = "SMITH HARDWARE";

    private static final String MERCHANT_CITY = "SEATTLE";

    private static final String MERCHANT_ZIP = "98101     ";

    private static final String FOCUS_SCREEN_FIELD_ID = "TRNIDIN";

    private static final String NEXT_ROUTE = "/api/transactions/view";

    private static final String NAVIGATION_ACCOUNT_ID = "00000000001";

    private static final String NAVIGATION_CUSTOMER_ID = "000000199";

    private static final String NAVIGATION_CARD_NUMBER = "4111111111111111";

    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    private static ObjectMapper declaredSettingsMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static final ObjectMapper MAPPER = declaredSettingsMapper();

    private static TransactionViewResponse foundReply() {
        return new TransactionViewResponse(TRANSACTION_NAME, TITLE01, CURRENT_DATE, PROGRAM_NAME,
                TITLE02, CURRENT_TIME, SEARCH_TRANSACTION_ID, FOUND_TRANSACTION_ID, CARD_NUMBER,
                TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT, ORIGINATION_DATE,
                PROCESSING_DATE, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE, true, List.of(), FOCUS_SCREEN_FIELD_ID,
                NEXT_ROUTE, populatedNavigation());
    }

    private static TransactionViewResponse absentReply() {
        return new TransactionViewResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, false, List.of(), null,
                null, null);
    }

    private static TransactionViewResponse withDescription(final String description) {
        return new TransactionViewResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, description, null, null, null, null, null, null, null, null,
                false, List.of(), null, null, null);
    }

    private static TransactionViewResponse withMerchant(final String merchantName,
            final String merchantCity) {
        return new TransactionViewResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, merchantName, merchantCity, null,
                null, false, List.of(), null, null, null);
    }

    private static TransactionViewResponse withErrorMessage(final String errorMessage) {
        return new TransactionViewResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, errorMessage,
                true, List.of(), null, null, null);
    }

    private static TransactionViewResponse withAmount(final BigDecimal amount) {
        return new TransactionViewResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, amount, null, null, null, null, null, null, null, false, List.of(),
                null, null, null);
    }

    private static TransactionViewResponse withDates(final String originationDate,
            final String processingDate) {
        return new TransactionViewResponse(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, originationDate, processingDate, null, null, null,
                null, null, false, List.of(), null, null, null);
    }

    private static TransactionViewResponse withIdentifiers(final String searchTransactionId,
            final String transactionId) {
        return new TransactionViewResponse(null, null, null, null, null, null, searchTransactionId,
                transactionId, null, null, null, null, null, null, null, null, null, null, null,
                null, null, false, List.of(), null, null, null);
    }

    private static TransactionViewResponse withSource(final String source) {
        return new TransactionViewResponse(null, null, null, null, null, null, null, null, null,
                null, null, source, null, null, null, null, null, null, null, null, null, false, List.of(),
                null, null, null);
    }

    private static NavigationContext populatedNavigation() {
        return new NavigationContext("CT00", "COTRN00C", "CT01", "COTRN01C", "USER0001", "U",
                NavigationContext.ProgramContext.REENTER, NAVIGATION_CUSTOMER_ID, "MARY", "ANN",
                "SMITH", NAVIGATION_ACCOUNT_ID, "Y", NAVIGATION_CARD_NUMBER, "COTRN1A", "COTRN01");
    }

    private static String atWidth(final String text, final int width) {
        if (text.length() > width) {
            throw new AssertionError("fixture text of length " + text.length()
                    + " cannot be padded to the narrower width " + width);
        }
        return text + " ".repeat(width - text.length());
    }

    private static String oneCharacterOver(final int width) {
        return "X".repeat(width + 1);
    }

    private static Set<ConstraintViolation<TransactionViewResponse>> violationsOf(
            final TransactionViewResponse response) {
        return validator.validate(response);
    }

    private static String serialise(final TransactionViewResponse response)
            throws JsonProcessingException {
        return MAPPER.writeValueAsString(response);
    }

    private static TransactionViewResponse deserialise(final String payload)
            throws JsonProcessingException {
        return MAPPER.readValue(payload, TransactionViewResponse.class);
    }

    private static Set<String> memberNamesOf(final String payload) throws JsonProcessingException {
        Map<String, Object> members =
                MAPPER.readValue(payload, new TypeReference<Map<String, Object>>() { });
        return members.keySet();
    }

    @Nested
    @DisplayName("the two sixteen-character transaction identifiers are separate and stay separate")
    class TwoTransactionIdentifiers {
        @Test
        @DisplayName("both are carried as their own component and hold different values at once")
        void bothAreCarriedSeparatelyAndHoldDifferentValues() {
            TransactionViewResponse reply = foundReply();

            String searched = reply.searchTransactionId();
            String retrieved = reply.transactionId();

            assertThat(searched)
                    .as("the identifier the operator asked for, echoed back from map line 60")
                    .isEqualTo(SEARCH_TRANSACTION_ID)
                    .hasSize(16);
            assertThat(retrieved)
                    .as("the identifier of the record actually retrieved, from map line 66")
                    .isEqualTo(FOUND_TRANSACTION_ID)
                    .hasSize(16);
            assertThat(searched)
                    .as("one component could not express both of these at the same time")
                    .isNotEqualTo(retrieved);
        }

        @Test
        @DisplayName("neither is derived from the other, so exchanging them exchanges nothing else")
        void neitherIsDerivedFromTheOther() {
            TransactionViewResponse asGiven = withIdentifiers(SEARCH_TRANSACTION_ID,
                    FOUND_TRANSACTION_ID);
            TransactionViewResponse exchanged = withIdentifiers(FOUND_TRANSACTION_ID,
                    SEARCH_TRANSACTION_ID);

            assertThat(asGiven.searchTransactionId()).isEqualTo(SEARCH_TRANSACTION_ID);
            assertThat(asGiven.transactionId()).isEqualTo(FOUND_TRANSACTION_ID);
            assertThat(exchanged.searchTransactionId())
                    .as("each component follows its own argument and computes nothing")
                    .isEqualTo(FOUND_TRANSACTION_ID);
            assertThat(exchanged.transactionId()).isEqualTo(SEARCH_TRANSACTION_ID);
        }

        @Test
        @DisplayName("the record-absent reply keeps the search key with no retrieved identifier")
        void theRecordAbsentReplyKeepsTheSearchKey() {
            TransactionViewResponse notFound = new TransactionViewResponse(TRANSACTION_NAME, TITLE01,
                    CURRENT_DATE, PROGRAM_NAME, TITLE02, CURRENT_TIME, SEARCH_TRANSACTION_ID, null,
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE, true, List.of(),
                    FOCUS_SCREEN_FIELD_ID, NEXT_ROUTE, populatedNavigation());

            assertThat(notFound.searchTransactionId())
                    .as("the program blanks the retrieved fields and deliberately not the search key")
                    .isEqualTo(SEARCH_TRANSACTION_ID);
            assertThat(notFound.transactionId()).isNull();
            assertThat(notFound.errorMessage())
                    .isEqualTo(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE);
            assertThat(notFound.generalError()).isTrue();
        }

        @Test
        @DisplayName("both keep sixteen leading-zero digits as text, so neither becomes a number")
        void bothKeepLeadingZeroDigitsAsText() {
            String lowest = "0000000000000001";

            TransactionViewResponse reply = withIdentifiers(lowest, lowest);

            assertThat(reply.searchTransactionId())
                    .isEqualTo(lowest)
                    .hasSize(16)
                    .isNotEqualTo("1");
            assertThat(reply.transactionId()).isEqualTo(lowest).hasSize(16).isNotEqualTo("1");
        }

        @Test
        @DisplayName("the payload carries both under their own member names and their own values")
        void thePayloadCarriesBothUnderTheirOwnNames() throws JsonProcessingException {
            String payload = serialise(withIdentifiers(SEARCH_TRANSACTION_ID, FOUND_TRANSACTION_ID));

            assertThat(payload)
                    .contains("\"searchTransactionId\":\"" + SEARCH_TRANSACTION_ID + "\"")
                    .contains("\"transactionId\":\"" + FOUND_TRANSACTION_ID + "\"");
        }

        @Test
        @DisplayName("each accepts sixteen characters and refuses a seventeenth")
        void eachAcceptsSixteenAndRefusesSeventeen() {
            assertThat(violationsOf(withIdentifiers(atWidth("", 16), atWidth("", 16))))
                    .as("sixteen is the width both fields declare on the map")
                    .isEmpty();
            assertThat(violationsOf(withIdentifiers(oneCharacterOver(16), null)))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("searchTransactionId"));
            assertThat(violationsOf(withIdentifiers(null, oneCharacterOver(16))))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("transactionId"));
        }
    }

    @Nested
    @DisplayName("the two ten-character dates stay opaque text, all-space values included")
    class OpaqueTenCharacterDates {
        @Test
        @DisplayName("both are text rather than any date or time type")
        void bothAreTextRatherThanATemporalType() {
            TransactionViewResponse reply = foundReply();

            String origination = reply.originationDate();
            String processing = reply.processingDate();

            assertThat(origination).isEqualTo(ORIGINATION_DATE).hasSize(10);
            assertThat(processing).isEqualTo(PROCESSING_DATE).hasSize(10);
        }

        @Test
        @DisplayName("a ten-character value survives each field byte for byte, independently")
        void aTenCharacterValueSurvivesEachFieldIndependently() {
            TransactionViewResponse originationOnly = withDates(ORIGINATION_DATE, null);
            TransactionViewResponse processingOnly = withDates(null, PROCESSING_DATE);

            assertThat(originationOnly.originationDate()).isEqualTo(ORIGINATION_DATE).hasSize(10);
            assertThat(originationOnly.processingDate()).isNull();
            assertThat(processingOnly.processingDate()).isEqualTo(PROCESSING_DATE).hasSize(10);
            assertThat(processingOnly.originationDate()).isNull();
        }

        @Test
        @DisplayName("an all-space value survives unchanged, neither nulled, emptied nor trimmed")
        void anAllSpaceValueSurvivesUnchanged() {
            String blank = atWidth("", 10);

            TransactionViewResponse reply = withDates(blank, blank);

            assertThat(reply.originationDate())
                    .as("blank date text is production data, not an absent value")
                    .isEqualTo(blank)
                    .hasSize(10)
                    .isNotNull()
                    .isNotEmpty();
            assertThat(reply.processingDate()).isEqualTo(blank).hasSize(10).isNotEmpty();
        }

        @Test
        @DisplayName("an all-space value survives the wire round trip as ten spaces")
        void anAllSpaceValueSurvivesTheWireRoundTrip() throws JsonProcessingException {
            String blank = atWidth("", 10);

            TransactionViewResponse returned = deserialise(serialise(withDates(blank, blank)));

            assertThat(returned.originationDate()).isEqualTo(blank).hasSize(10);
            assertThat(returned.processingDate()).isEqualTo(blank).hasSize(10);
        }

        @Test
        @DisplayName("text that is no calendar date at all survives, because nothing parses it")
        void textThatIsNoCalendarDateSurvives() {
            String impossible = "2022-13-45";

            TransactionViewResponse reply = withDates(impossible, "06/10/2022");

            assertThat(reply.originationDate())
                    .as("no month thirteen and no forty-fifth day exist, and nothing here checks")
                    .isEqualTo(impossible);
            assertThat(reply.processingDate())
                    .as("a second shape passes through untouched, so neither is canonical")
                    .isEqualTo("06/10/2022");
        }

        @Test
        @DisplayName("both hold different values at once and neither is derived from the other")
        void bothHoldDifferentValuesAtOnce() {
            TransactionViewResponse reply = withDates(ORIGINATION_DATE, PROCESSING_DATE);

            assertThat(reply.originationDate()).isEqualTo(ORIGINATION_DATE);
            assertThat(reply.processingDate())
                    .isEqualTo(PROCESSING_DATE)
                    .isNotEqualTo(reply.originationDate());
        }

        @Test
        @DisplayName("the payload carries no derived, formatted or reformatted date member")
        void thePayloadCarriesNoDerivedDateMember() throws JsonProcessingException {
            Set<String> members = memberNamesOf(serialise(foundReply()));

            assertThat(members)
                    .as("the two raw members are the whole of the date surface")
                    .contains("originationDate", "processingDate")
                    .doesNotContain("originationTimestamp", "processingTimestamp",
                            "originationDateTime", "processingDateTime", "originationDateFormatted",
                            "processingDateFormatted", "originationInstant", "processingInstant");
        }

        @Test
        @DisplayName("each accepts ten characters and refuses an eleventh")
        void eachAcceptsTenAndRefusesEleven() {
            assertThat(violationsOf(withDates(atWidth("", 10), atWidth("", 10)))).isEmpty();
            assertThat(violationsOf(withDates(oneCharacterOver(10), null)))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("originationDate"));
            assertThat(violationsOf(withDates(null, oneCharacterOver(10))))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("processingDate"));
        }
    }

    @Nested
    @DisplayName("the sixty, thirty and twenty-five widths are this screen's own and stay distinct")
    class WidthFamiliesStayDistinct {
        @Test
        @DisplayName("the description holds sixty characters, refuses sixty-one and refuses the "
                + "record's hundred")
        void theDescriptionHoldsSixtyAndRefusesTheRecordWidth() {
            String atSixty = atWidth("PURCHASE AT HARDWARE STORE", 60);

            assertThat(violationsOf(withDescription(atSixty)))
                    .as("sixty passes, so this bound is not the twenty-six of the list map")
                    .isEmpty();
            assertThat(withDescription(atSixty).description()).isEqualTo(atSixty).hasSize(60);
            assertThat(violationsOf(withDescription(oneCharacterOver(60))))
                    .as("sixty-one fails, so this bound is not the hundred of the record")
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("description"));
            assertThat(violationsOf(withDescription(atWidth("", 100))))
                    .as("the record and statement-work-area width is refused outright here")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the merchant name holds thirty and the city twenty-five, and both refuse the "
                + "record's fifty")
        void theMerchantWidthsRefuseTheRecordWidth() {
            String nameAtThirty = atWidth("SMITH HARDWARE", 30);
            String cityAtTwentyFive = atWidth("SEATTLE", 25);

            assertThat(violationsOf(withMerchant(nameAtThirty, cityAtTwentyFive))).isEmpty();
            assertThat(withMerchant(nameAtThirty, cityAtTwentyFive).merchantName())
                    .isEqualTo(nameAtThirty)
                    .hasSize(30);
            assertThat(withMerchant(nameAtThirty, cityAtTwentyFive).merchantCity())
                    .isEqualTo(cityAtTwentyFive)
                    .hasSize(25);
            assertThat(violationsOf(withMerchant(oneCharacterOver(30), null)))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("merchantName"));
            assertThat(violationsOf(withMerchant(null, oneCharacterOver(25))))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("merchantCity"));
            assertThat(violationsOf(withMerchant(atWidth("", 50), atWidth("", 50))))
                    .as("fifty is the record width for both and is refused by both here")
                    .hasSize(2);
        }

        @Test
        @DisplayName("one value of twenty-six characters passes as a description and a merchant name "
                + "and fails as a merchant city, so the three bounds are three bounds")
        void oneValueSeparatesTheThreeBounds() {
            String twentySix = atWidth("", 26);
            String thirtyOne = oneCharacterOver(30);
            String sixtyOne = oneCharacterOver(60);

            assertThat(violationsOf(withDescription(twentySix))).isEmpty();
            assertThat(violationsOf(withMerchant(twentySix, null))).isEmpty();
            assertThat(violationsOf(withMerchant(null, twentySix)))
                    .as("twenty-six exceeds the city's twenty-five while clearing the other two")
                    .hasSize(1);

            assertThat(violationsOf(withDescription(thirtyOne))).isEmpty();
            assertThat(violationsOf(withMerchant(thirtyOne, thirtyOne)))
                    .as("thirty-one exceeds both merchant widths while clearing the description")
                    .hasSize(2);

            assertThat(violationsOf(withDescription(sixtyOne))).hasSize(1);
            assertThat(violationsOf(withMerchant(sixtyOne, sixtyOne))).hasSize(2);
        }

        @Test
        @DisplayName("the message line holds seventy-eight and refuses the card maps' eighty")
        void theMessageLineHoldsSeventyEight() {
            String atSeventyEight = atWidth("", 78);

            assertThat(violationsOf(withErrorMessage(atSeventyEight))).isEmpty();
            assertThat(withErrorMessage(atSeventyEight).errorMessage())
                    .isEqualTo(atSeventyEight)
                    .hasSize(78);
            assertThat(violationsOf(withErrorMessage(oneCharacterOver(78))))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("errorMessage"));
            assertThat(violationsOf(withErrorMessage(atWidth("", 80))))
                    .as("eighty belongs to the two card maps alone and is refused here")
                    .hasSize(1);
        }

        @Test
        @DisplayName("no map width is published as a constant, so no sibling screen can share one")
        void noMapWidthIsPublishedAsAConstant() {
            assertThat(List.of(TransactionViewResponse.SCREEN_FIELD_ID_LENGTH,
                            TransactionViewResponse.AMOUNT_SCALE,
                            TransactionViewResponse.AMOUNT_INTEGER_DIGITS))
                    .containsExactly(7, 2, 9)
                    .doesNotContain(26, 25, 30, 50, 60, 78, 80, 100);
        }

        @Test
        @DisplayName("the focus hint holds the seven characters a map item name can occupy")
        void theFocusHintHoldsSevenCharacters() {
            assertThat(TransactionViewResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(foundReply().focusScreenFieldId())
                    .isEqualTo(FOCUS_SCREEN_FIELD_ID)
                    .hasSize(TransactionViewResponse.SCREEN_FIELD_ID_LENGTH);
            assertThat(violationsOf(new TransactionViewResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, false, List.of(), oneCharacterOver(7), null, null)))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("focusScreenFieldId"));
        }
    }

    @Nested
    @DisplayName("every text component round-trips at its own width, untrimmed and un-case-folded")
    class EveryTextComponentRoundTripsAtItsOwnWidth {
        private TransactionViewResponse atEveryDeclaredWidth() {
            return new TransactionViewResponse(atWidth("CT01", 4), atWidth("TITLE ONE", 40),
                    atWidth("06/10/22", 8), atWidth("COTRN01C", 8), atWidth("TITLE TWO", 40),
                    atWidth("19:27:53", 8), atWidth("SEARCHKEY", 16), atWidth("FOUNDKEY", 16),
                    atWidth("CARDNUMBER", 16), atWidth("", 2), atWidth("", 4), atWidth("CHANNEL", 10),
                    atWidth("DESCRIPTION", 60), AMOUNT, atWidth("ORIGDATE", 10),
                    atWidth("PROCDATE", 10), atWidth("MERCHID", 9), atWidth("MERCHANT NAME", 30),
                    atWidth("MERCHANT CITY", 25), atWidth("ZIP", 10), atWidth("MESSAGE", 78), true, List.of(),
                    atWidth("FOCUS", 7), NEXT_ROUTE, populatedNavigation());
        }

        @Test
        @DisplayName("all twenty-one bounded components accept a value sitting exactly on the bound")
        void allBoundedComponentsAcceptTheirExactWidth() {
            assertThat(violationsOf(atEveryDeclaredWidth()))
                    .as("a value at the bound is inside it, because the bound is a maximum")
                    .isEmpty();
        }

        @Test
        @DisplayName("each of the fourteen transaction fields reads back at its own exact width")
        void eachTransactionFieldReadsBackAtItsWidth() {
            TransactionViewResponse reply = atEveryDeclaredWidth();

            assertThat(reply.searchTransactionId()).isEqualTo(atWidth("SEARCHKEY", 16)).hasSize(16);
            assertThat(reply.transactionId()).isEqualTo(atWidth("FOUNDKEY", 16)).hasSize(16);
            assertThat(reply.cardNumber()).isEqualTo(atWidth("CARDNUMBER", 16)).hasSize(16);
            assertThat(reply.typeCode()).isEqualTo(atWidth("", 2)).hasSize(2);
            assertThat(reply.categoryCode()).isEqualTo(atWidth("", 4)).hasSize(4);
            assertThat(reply.source()).isEqualTo(atWidth("CHANNEL", 10)).hasSize(10);
            assertThat(reply.description()).isEqualTo(atWidth("DESCRIPTION", 60)).hasSize(60);
            assertThat(reply.originationDate()).isEqualTo(atWidth("ORIGDATE", 10)).hasSize(10);
            assertThat(reply.processingDate()).isEqualTo(atWidth("PROCDATE", 10)).hasSize(10);
            assertThat(reply.merchantId()).isEqualTo(atWidth("MERCHID", 9)).hasSize(9);
            assertThat(reply.merchantName()).isEqualTo(atWidth("MERCHANT NAME", 30)).hasSize(30);
            assertThat(reply.merchantCity()).isEqualTo(atWidth("MERCHANT CITY", 25)).hasSize(25);
            assertThat(reply.merchantZip()).isEqualTo(atWidth("ZIP", 10)).hasSize(10);
            assertThat(reply.errorMessage()).isEqualTo(atWidth("MESSAGE", 78)).hasSize(78);
        }

        @Test
        @DisplayName("the seven server-supplied header fields read back at their own widths too")
        void theHeaderFieldsReadBackAtTheirWidths() {
            TransactionViewResponse reply = atEveryDeclaredWidth();

            assertThat(reply.transactionName()).isEqualTo(atWidth("CT01", 4)).hasSize(4);
            assertThat(reply.title01()).isEqualTo(atWidth("TITLE ONE", 40)).hasSize(40);
            assertThat(reply.title02()).isEqualTo(atWidth("TITLE TWO", 40)).hasSize(40);
            assertThat(reply.currentDate()).isEqualTo(atWidth("06/10/22", 8)).hasSize(8);
            assertThat(reply.currentTime()).isEqualTo(atWidth("19:27:53", 8)).hasSize(8);
            assertThat(reply.programName()).isEqualTo(atWidth("COTRN01C", 8)).hasSize(8);
            assertThat(reply.focusScreenFieldId()).isEqualTo(atWidth("FOCUS", 7)).hasSize(7);
        }

        @Test
        @DisplayName("a short value is never padded up to its declared width")
        void aShortValueIsNeverPaddedUp() {
            TransactionViewResponse reply = withDescription("TEA");

            assertThat(reply.description())
                    .as("the bound measures a value and never alters one")
                    .isEqualTo("TEA")
                    .hasSize(3);
        }

        @Test
        @DisplayName("leading and trailing spaces are never trimmed away")
        void spacesAreNeverTrimmed() {
            String spaced = "  SPACED BOTH ENDS  ";

            assertThat(withDescription(spaced).description())
                    .as("in a fixed-width estate the padding is part of the value")
                    .isEqualTo(spaced)
                    .hasSize(20);
            assertThat(foundReply().merchantZip())
                    .as("the postal code arrives padded to the full ten characters and stays so")
                    .isEqualTo(MERCHANT_ZIP)
                    .hasSize(10);
            assertThat(foundReply().source())
                    .as("the channel keeps the two spaces its ten-character field pads it with")
                    .isEqualTo(SOURCE)
                    .hasSize(10);
        }

        @Test
        @DisplayName("case is never folded in either direction")
        void caseIsNeverFolded() {
            String mixedCase = "Purchase at Hardware Store";

            TransactionViewResponse reply = withDescription(mixedCase);

            assertThat(reply.description())
                    .isEqualTo(mixedCase)
                    .isNotEqualTo("PURCHASE AT HARDWARE STORE")
                    .isNotEqualTo("purchase at hardware store");
        }

        @Test
        @DisplayName("every width survives the wire round trip, padding included")
        void everyWidthSurvivesTheWireRoundTrip() throws JsonProcessingException {
            TransactionViewResponse sent = atEveryDeclaredWidth();

            TransactionViewResponse returned = deserialise(serialise(sent));

            assertThat(returned)
                    .as("a client echoing the payload back gets the same values, spaces and all")
                    .isEqualTo(sent);
            assertThat(returned.description()).hasSize(60);
            assertThat(returned.merchantName()).hasSize(30);
            assertThat(returned.merchantCity()).hasSize(25);
            assertThat(returned.errorMessage()).hasSize(78);
        }
    }

    @Nested
    @DisplayName("every identifier and code is text, so its leading zeros and width survive")
    class IdentifiersAndCodesStayText {
        private TransactionViewResponse withZeroLeadingValues() {
            return new TransactionViewResponse(null, null, null, null, null, null, null,
                    "0000000000000001", null, null, "0002", null, null, null, null, null,
                    "000000042", null, null, null, null, false, List.of(), null, null, null);
        }

        @Test
        @DisplayName("the sixteen-character identifier stays sixteen characters, never one")
        void theIdentifierStaysSixteenCharacters() {
            String transactionId = withZeroLeadingValues().transactionId();

            assertThat(transactionId)
                    .isEqualTo("0000000000000001")
                    .hasSize(16)
                    .isNotEqualTo("1")
                    .startsWith("0");
        }

        @Test
        @DisplayName("the four-character category code stays four characters, never two")
        void theCategoryCodeStaysFourCharacters() {
            String categoryCode = withZeroLeadingValues().categoryCode();

            assertThat(categoryCode).isEqualTo("0002").hasSize(4).isNotEqualTo("2");
        }

        @Test
        @DisplayName("the nine-character merchant identifier stays nine characters, never forty-two")
        void theMerchantIdentifierStaysNineCharacters() {
            String merchantId = withZeroLeadingValues().merchantId();

            assertThat(merchantId).isEqualTo("000000042").hasSize(9).isNotEqualTo("42");
        }

        @Test
        @DisplayName("the payload quotes all three, so none of them arrives as a JSON number")
        void thePayloadQuotesAllThree() throws JsonProcessingException {
            String payload = serialise(withZeroLeadingValues());

            assertThat(payload)
                    .as("a quoted member keeps its leading zeros; an unquoted one loses them")
                    .contains("\"transactionId\":\"0000000000000001\"")
                    .contains("\"categoryCode\":\"0002\"")
                    .contains("\"merchantId\":\"000000042\"");
            assertThat(payload)
                    .doesNotContain("\"transactionId\":1", "\"categoryCode\":2",
                            "\"merchantId\":42");
        }

        @Test
        @DisplayName("all three survive the wire round trip with their leading zeros intact")
        void allThreeSurviveTheWireRoundTrip() throws JsonProcessingException {
            TransactionViewResponse returned = deserialise(serialise(withZeroLeadingValues()));

            assertThat(returned.transactionId()).isEqualTo("0000000000000001");
            assertThat(returned.categoryCode()).isEqualTo("0002");
            assertThat(returned.merchantId()).isEqualTo("000000042");
        }
    }

    @Nested
    @DisplayName("the origination channel stays a raw, space-padded ten-character value")
    class RawPaddedChannel {
        @Test
        @DisplayName("the channel is text rather than the domain enumeration")
        void theChannelIsTextRatherThanTheEnumeration() {
            String channel = withSource(SOURCE).source();

            assertThat(channel).isEqualTo(SOURCE).hasSize(TransactionSourceType.VALUE_LENGTH);
        }

        @Test
        @DisplayName("each of the three legacy images round-trips untrimmed at ten characters")
        void eachLegacyImageRoundTripsUntrimmed() {
            assertThat(withSource("POS TERM  ").source())
                    .as("the point-of-sale image, two trailing spaces included")
                    .isEqualTo("POS TERM  ")
                    .hasSize(10)
                    .isNotEqualTo("POS TERM");
            assertThat(withSource("OPERATOR  ").source())
                    .isEqualTo("OPERATOR  ")
                    .hasSize(10)
                    .isNotEqualTo("OPERATOR");
            assertThat(withSource("System    ").source())
                    .as("mixed case and four trailing spaces, both preserved exactly")
                    .isEqualTo("System    ")
                    .hasSize(10)
                    .isNotEqualTo("SYSTEM    ");
        }

        @Test
        @DisplayName("the three images are the stored images the domain enumeration publishes")
        void theThreeImagesAreTheStoredImages() {
            assertThat(TransactionSourceType.POS_TERM.getValue()).isEqualTo("POS TERM  ");
            assertThat(TransactionSourceType.OPERATOR.getValue()).isEqualTo("OPERATOR  ");
            assertThat(TransactionSourceType.SYSTEM.getValue()).isEqualTo("System    ");
            assertThat(TransactionSourceType.VALUE_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("a channel outside the declared set round-trips instead of failing conversion")
        void anUndeclaredChannelRoundTrips() {
            String undeclared = "MAILORDER ";

            assertThat(TransactionSourceType.fromValue(undeclared))
                    .as("the enumeration cannot resolve it, which is why this contract does not try")
                    .isEmpty();
            assertThatNoException().isThrownBy(() -> withSource(undeclared));
            assertThat(withSource(undeclared).source()).isEqualTo(undeclared).hasSize(10);
            assertThat(violationsOf(withSource(undeclared)))
                    .as("only the width is measured; membership is not checked")
                    .isEmpty();
        }

        @Test
        @DisplayName("the payload carries the padded image and never an enumeration constant name")
        void thePayloadCarriesThePaddedImage() throws JsonProcessingException {
            String payload = serialise(withSource(SOURCE));

            assertThat(payload).contains("\"source\":\"POS TERM  \"");
            assertThat(payload)
                    .as("a constant name on the wire would mean the padding had been resolved away")
                    .doesNotContain(TransactionSourceType.POS_TERM.name());
        }

        @Test
        @DisplayName("the channel accepts ten characters and refuses an eleventh")
        void theChannelAcceptsTenAndRefusesEleven() {
            assertThat(violationsOf(withSource(atWidth("", 10)))).isEmpty();
            assertThat(violationsOf(withSource(oneCharacterOver(10))))
                    .singleElement()
                    .satisfies(refusal -> assertThat(refusal.getPropertyPath())
                            .hasToString("source"));
        }
    }

    @Nested
    @DisplayName("the amount is a fixed-scale decimal that nothing in this contract rescales")
    class AmountIsAFixedScaleDecimal {
        @Test
        @DisplayName("the amount is a decimal and the accepted value is the very object supplied")
        void theAcceptedValueIsTheObjectSupplied() {
            BigDecimal supplied = new BigDecimal("-123.45");

            BigDecimal carried = withAmount(supplied).amount();

            assertThat(carried)
                    .as("refusing is not normalising: the same object comes back")
                    .isSameAs(supplied);
            assertThat(carried.scale())
                    .as("the two decimal places of the record field")
                    .isEqualTo(TransactionViewResponse.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("the scale is exactly two after a wire round trip, not merely numerically equal")
        void theScaleIsExactlyTwoAfterAWireRoundTrip() throws JsonProcessingException {
            BigDecimal returned = deserialise(serialise(withAmount(new BigDecimal("10.00"))))
                    .amount();

            assertThat(returned.scale())
                    .as("a scale of one would still compare equal to ten, which is why scale is "
                            + "asserted in its own right")
                    .isEqualTo(2);
            assertThat(returned).isEqualTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("the widest record value renders plainly and never in scientific notation")
        void theWidestValueRendersPlainly() throws JsonProcessingException {
            String payload = serialise(withAmount(new BigDecimal("999999999.99")));

            assertThat(payload)
                    .as("plain text, with no exponent, no grouping separator, no leading sign and no "
                            + "currency symbol")
                    .contains("\"amount\":999999999.99");
            assertThat(payload).doesNotContain("E+", "E-", "e+", "e-");
            assertThat(payload)
                    .as("a number, never a quoted string")
                    .doesNotContain("\"amount\":\"");
        }

        @Test
        @DisplayName("a negative amount round-trips, because fifty of the three hundred seeded "
                + "transactions are returns")
        void aNegativeAmountRoundTrips() throws JsonProcessingException {
            BigDecimal refund = new BigDecimal("-999999999.99");

            assertThat(withAmount(refund).amount()).isSameAs(refund);
            assertThat(serialise(withAmount(refund))).contains("\"amount\":-999999999.99");
            assertThat(deserialise(serialise(withAmount(refund))).amount())
                    .isEqualTo(refund)
                    .satisfies(returned -> assertThat(returned.scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("a zero amount round-trips with both of its trailing zeros")
        void aZeroAmountKeepsItsTrailingZeros() throws JsonProcessingException {
            BigDecimal zero = new BigDecimal("0.00");

            assertThat(withAmount(zero).amount()).isSameAs(zero);
            assertThat(serialise(withAmount(zero)))
                    .as("the record field stores two decimal places whatever the value is")
                    .contains("\"amount\":0.00");
            assertThat(deserialise(serialise(withAmount(zero))).amount().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("a scale other than two is refused rather than rounded into shape")
        void aWrongScaleIsRefusedRatherThanRounded() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withAmount(new BigDecimal("123.4")))
                    .withMessage("amount must carry scale 2, because its record field stores two"
                            + " decimal places, but its scale is 1");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a third decimal place is refused, never truncated away")
                    .isThrownBy(() -> withAmount(new BigDecimal("123.456")))
                    .withMessage("amount must carry scale 2, because its record field stores two"
                            + " decimal places, but its scale is 3");
        }

        @Test
        @DisplayName("a tenth integer digit is refused, because the record field holds nine")
        void aTenthIntegerDigitIsRefused() {
            assertThat(TransactionViewResponse.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
            assertThatNoException()
                    .isThrownBy(() -> withAmount(new BigDecimal("999999999.99")));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withAmount(new BigDecimal("1000000000.00")))
                    .withMessage("amount must fit 9 integer digits, because that is the width of its"
                            + " record field, but it needs 10");
        }

        @Test
        @DisplayName("an absent amount is accepted, because a record-absent reply carries none")
        void anAbsentAmountIsAccepted() {
            assertThatNoException().isThrownBy(() -> withAmount(null));
            assertThat(withAmount(null).amount()).isNull();
            assertThat(violationsOf(withAmount(null))).isEmpty();
        }

        @Test
        @DisplayName("the amount carries no length bound of its own, only the two record figures")
        void theAmountCarriesNoLengthBound() {
            assertThat(violationsOf(withAmount(new BigDecimal("-999999999.99"))))
                    .as("a length bound measures character sequences, so declaring one on a decimal "
                            + "would be an invalid declaration")
                    .isEmpty();
            assertThat(TransactionViewResponse.AMOUNT_SCALE).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("an upper bound is the only declarative constraint, so nothing else ever fires")
    class OnlyAnUpperBoundEverFires {
        @Test
        @DisplayName("a reply with every component absent reports no violation at all")
        void aWhollyAbsentReplyReportsNoViolation() {
            assertThat(violationsOf(absentReply()))
                    .as("no presence, pattern, digit or numeric-range constraint exists to fire")
                    .isEmpty();
        }

        @Test
        @DisplayName("a fully populated reply reports no violation either")
        void aFullyPopulatedReplyReportsNoViolation() {
            assertThat(violationsOf(foundReply())).isEmpty();
        }

        @Test
        @DisplayName("an empty string reports no violation on any component, so no blankness or "
                + "pattern constraint is present")
        void anEmptyStringReportsNoViolation() {
            TransactionViewResponse allEmpty = new TransactionViewResponse("", "", "", "", "", "",
                    "", "", "", "", "", "", "", null, "", "", "", "", "", "", "", false, List.of(), "", "",
                    NavigationContext.empty());

            assertThat(violationsOf(allEmpty))
                    .as("the legacy cascade is ordered and first-error-wins, which unordered "
                            + "violation reporting cannot reproduce, so it stays in the service")
                    .isEmpty();
            assertThat(allEmpty.description()).isEmpty();
            assertThat(allEmpty.transactionId()).isEmpty();
        }

        @Test
        @DisplayName("a blank string reports no violation, because blankness is not constrained")
        void aBlankStringReportsNoViolation() {
            assertThat(violationsOf(withDescription("   "))).isEmpty();
            assertThat(withDescription("   ").description()).isEqualTo("   ").hasSize(3);
        }

        @Test
        @DisplayName("non-numeric text in a numerically-pictured component reports no violation")
        void nonNumericTextInACodeComponentReportsNoViolation() {
            TransactionViewResponse reply = new TransactionViewResponse(null, null, null, null, null,
                    null, null, "NOT-A-NUMBER----", null, null, "ABCD", null, null, null, null,
                    null, "NOTNUMBER", null, null, null, null, false, List.of(), null, null, null);

            assertThat(violationsOf(reply))
                    .as("a digit constraint would reject values the legacy screen accepts")
                    .isEmpty();
            assertThat(reply.transactionId()).isEqualTo("NOT-A-NUMBER----");
            assertThat(reply.categoryCode()).isEqualTo("ABCD");
            assertThat(reply.merchantId()).isEqualTo("NOTNUMBER");
        }

        @Test
        @DisplayName("only the over-long component is reported when several are over-long at once")
        void onlyTheOverLongComponentsAreReported() {
            TransactionViewResponse reply = new TransactionViewResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null, oneCharacterOver(60), null, null,
                    null, null, oneCharacterOver(30), atWidth("SEATTLE", 25), null, null, false, List.of(),
                    null, null, null);

            assertThat(violationsOf(reply)).hasSize(2);
            assertThat(violationsOf(reply))
                    .extracting(refusal -> refusal.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("description", "merchantName");
        }

        @Test
        @DisplayName("the unbounded route accepts a label longer than any map field")
        void theUnboundedRouteAcceptsALongLabel() {
            TransactionViewResponse reply = new TransactionViewResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, false, List.of(), null, atWidth("/api/transactions/view", 200), null);

            assertThat(violationsOf(reply))
                    .as("a route label is an opaque server-chosen name, not a screen field")
                    .isEmpty();
            assertThat(reply.nextRoute()).hasSize(200);
        }
    }

    @Nested
    @DisplayName("the conversation state is carried whole rather than re-implemented")
    class ConversationStateIsCarriedWhole {
        @Test
        @DisplayName("the nested state round-trips, leading-zero identifiers included")
        void theNestedStateRoundTrips() {
            NavigationContext supplied = populatedNavigation();

            NavigationContext carried = foundReply().navigationContext();

            assertThat(carried).isEqualTo(supplied);
            assertThat(carried.accountId())
                    .as("eleven characters with ten leading zeros, carried as text")
                    .isEqualTo(NAVIGATION_ACCOUNT_ID)
                    .hasSize(11)
                    .isNotEqualTo("1");
            assertThat(carried.customerId()).isEqualTo(NAVIGATION_CUSTOMER_ID).hasSize(9);
            assertThat(carried.cardNumber()).isEqualTo(NAVIGATION_CARD_NUMBER).hasSize(16);
            assertThat(carried.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("the same object is carried, so nothing is copied, rebuilt or normalised")
        void theSameObjectIsCarried() {
            NavigationContext supplied = populatedNavigation();

            TransactionViewResponse reply = new TransactionViewResponse(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, false, List.of(), null, null, supplied);

            assertThat(reply.navigationContext()).isSameAs(supplied);
        }

        @Test
        @DisplayName("the reply declares no conversation member of its own")
        void theReplyDeclaresNoConversationMemberOfItsOwn() throws JsonProcessingException {
            Set<String> members = memberNamesOf(serialise(foundReply()));

            assertThat(members)
                    .as("the state is one nested member, not a set of copied fields")
                    .contains("navigationContext")
                    .doesNotContain("fromTransactionId", "toTransactionId", "userId", "userType",
                            "customerId", "accountId", "lastMap", "lastMapset", "commarea",
                            "screenWorkArea");
        }

        @Test
        @DisplayName("the nested state survives the wire round trip and an absent one is omitted")
        void theNestedStateSurvivesTheWireRoundTrip() throws JsonProcessingException {
            TransactionViewResponse returned = deserialise(serialise(foundReply()));

            assertThat(returned.navigationContext()).isEqualTo(populatedNavigation());
            assertThat(returned.navigationContext().accountId()).isEqualTo(NAVIGATION_ACCOUNT_ID);
            assertThat(serialise(absentReply())).doesNotContain("navigationContext");
        }

        @Test
        @DisplayName("an absent conversation state is accepted, because an entered screen may have "
                + "none")
        void anAbsentConversationStateIsAccepted() {
            assertThat(absentReply().navigationContext()).isNull();
            assertThat(violationsOf(absentReply())).isEmpty();
        }
    }

    @Nested
    @DisplayName("the onward route is declarative data and never a decision taken here")
    class TheOnwardRouteIsDeclarativeData {
        @Test
        @DisplayName("the route is carried as an opaque label")
        void theRouteIsCarriedAsAnOpaqueLabel() {
            String route = foundReply().nextRoute();

            assertThat(route).isEqualTo(NEXT_ROUTE);
        }

        @Test
        @DisplayName("any label is accepted, because no route vocabulary is enforced here")
        void anyLabelIsAccepted() {
            assertThatNoException().isThrownBy(() -> new TransactionViewResponse(null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, false, List.of(), null, "not-a-route-at-all", null));
            assertThat(violationsOf(new TransactionViewResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, false, List.of(), null, "not-a-route-at-all", null))).isEmpty();
        }

        @Test
        @DisplayName("an absent route is accepted, because the client may stay where it is")
        void anAbsentRouteIsAccepted() throws JsonProcessingException {
            assertThat(absentReply().nextRoute()).isNull();
            assertThat(serialise(absentReply())).doesNotContain("nextRoute");
        }

        @Test
        @DisplayName("the payload carries the route as one text member and no route table")
        void thePayloadCarriesNoRouteTable() throws JsonProcessingException {
            String payload = serialise(foundReply());

            assertThat(payload).contains("\"nextRoute\":\"" + NEXT_ROUTE + "\"");
            assertThat(memberNamesOf(payload))
                    .contains("nextRoute")
                    .doesNotContain("routes", "route", "routeTable", "targetProgram", "xctlTarget",
                            "dispatch");
        }
    }

    @Nested
    @DisplayName("the published payload carries exactly the contract and tolerates an echo")
    class ThePublishedPayload {
        @Test
        @DisplayName("a fully populated reply publishes exactly the twenty-five declared members")
        void aPopulatedReplyPublishesExactlyTheDeclaredMembers() throws JsonProcessingException {
            Set<String> members = memberNamesOf(serialise(foundReply()));

            assertThat(members)
                    .as("an exact comparison, so any extra member - a reformatted date, a masked or "
                            + "truncated card number, a security code, a page cursor or a screen "
                            + "attribute byte - fails here")
                    .containsExactlyElementsOf(PUBLISHED_MEMBERS)
                    .hasSize(26);
        }

        @Test
        @DisplayName("no screen-plumbing member reaches the wire")
        void noScreenPlumbingMemberReachesTheWire() throws JsonProcessingException {
            Set<String> members = memberNamesOf(serialise(foundReply()));

            assertThat(members)
                    .as("the map's length halfwords, attribute bytes, colour, highlight, programmed "
                            + "symbol and validation bytes and its terminal-buffer filler describe "
                            + "how a terminal renders a field rather than what the field means")
                    .doesNotContain("trnidinl", "trnidinf", "trnidina", "trnidinc", "trnidinp",
                            "trnidinh", "trnidinv", "filler", "tioa", "cursorRow", "cursorColumn",
                            "attributeByte", "cvv", "maskedCardNumber", "infoMessage");
        }

        @Test
        @DisplayName("an absent member is omitted entirely rather than written as null")
        void anAbsentMemberIsOmittedEntirely() throws JsonProcessingException {
            String payload = serialise(absentReply());

            assertThat(payload)
                    .as("a field the map did not carry has to be absent rather than present-and-empty, "
                            + "leaving only the two members that cannot be absent - the primitive flag "
                            + "and the normalised finding list")
                    .isEqualTo("{\"generalError\":false,\"fieldErrors\":[]}");
            assertThat(memberNamesOf(payload)).containsExactly("generalError", "fieldErrors");
        }

        @Test
        @DisplayName("the primitive error indicator is always published, in both of its states")
        void theErrorIndicatorIsAlwaysPublished() throws JsonProcessingException {
            assertThat(serialise(absentReply())).contains("\"generalError\":false");
            assertThat(serialise(foundReply())).contains("\"generalError\":true");
            assertThat(foundReply().generalError())
                    .as("carried in its own right and never inferred from the message")
                    .isTrue();
            assertThat(withErrorMessage(null).generalError())
                    .as("a raised indicator beside a blank message is a state the screen reaches")
                    .isTrue();
        }

        @Test
        @DisplayName("an unknown incoming member is tolerated, so a client can echo a payload back")
        void anUnknownIncomingMemberIsTolerated() throws JsonProcessingException {
            String echoed = "{\"transactionId\":\"0000000000000001\","
                    + "\"screenAttributeByte\":\"A\",\"cursorRow\":7}";

            TransactionViewResponse returned = deserialise(echoed);

            assertThat(returned.transactionId()).isEqualTo("0000000000000001");
            assertThat(returned.generalError()).isFalse();
            assertThat(returned.amount()).isNull();
        }

        @Test
        @DisplayName("the whole reply survives a round trip through the wire unchanged")
        void theWholeReplySurvivesARoundTrip() throws JsonProcessingException {
            TransactionViewResponse sent = foundReply();

            assertThat(deserialise(serialise(sent))).isEqualTo(sent);
        }

        @Test
        @DisplayName("the payload is a plain contract body and not a problem document")
        void thePayloadIsNotAProblemDocument() throws JsonProcessingException {
            Set<String> members = memberNamesOf(serialise(foundReply()));

            assertThat(members)
                    .as("the module publishes its error surface as this contract's own message line, "
                            + "not as a problem document")
                    .doesNotContain("type", "title", "status", "detail", "instance", "problem");
            assertThat(members).contains("errorMessage", "generalError");
        }
    }

    @Nested
    @DisplayName("the three published message texts are reproduced character for character")
    class ThePublishedMessageTexts {
        @Test
        @DisplayName("the empty-identifier refusal is twenty-seven characters and round-trips whole")
        void theEmptyIdentifierRefusalIsTwentySevenCharacters() throws JsonProcessingException {
            String published = TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE;

            assertThat(published)
                    .as("program line 149, the first branch of the enter-key cascade")
                    .isEqualTo("Tran ID can NOT be empty...")
                    .hasSize(27);
            assertThat(withErrorMessage(published).errorMessage())
                    .as("driven through the message component and back, untouched")
                    .isEqualTo("Tran ID can NOT be empty...")
                    .hasSize(27);
            assertThat(deserialise(serialise(withErrorMessage(published))).errorMessage())
                    .isEqualTo("Tran ID can NOT be empty...")
                    .hasSize(27);
        }

        @Test
        @DisplayName("its capitalised NOT, its three dots and the absence of a space before them are "
                + "all contract")
        void itsCapitalisationAndItsThreeDotsAreContract() {
            String published = TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE;

            assertThat(published)
                    .as("the house style for the estate's emptiness messages, not a defect to tidy")
                    .contains("NOT")
                    .doesNotContain("not")
                    .endsWith("empty...")
                    .doesNotContain("empty ...")
                    .doesNotContain("....");
            assertThat(published)
                    .as("neither upper-folded nor lower-folded anywhere on the way through")
                    .isNotEqualTo("TRAN ID CAN NOT BE EMPTY...")
                    .isNotEqualTo("tran id can not be empty...");
        }

        @Test
        @DisplayName("the not-found and lookup-failed texts are distinct and reproduced whole")
        void theOtherTwoTextsAreDistinctAndReproducedWhole() {
            assertThat(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE)
                    .as("program line 285, the record-absent outcome of the read")
                    .isEqualTo("Transaction ID NOT found...")
                    .hasSize(27);
            assertThat(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE)
                    .as("program line 292, every other failure of the read")
                    .isEqualTo("Unable to lookup Transaction...")
                    .hasSize(31);
            assertThat(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE)
                    .as("an absent transaction and a failed read are different outcomes")
                    .isNotEqualTo(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE);
        }

        @Test
        @DisplayName("the lookup-failure text names no response code, reason code or internal detail")
        void theLookupFailureTextNamesNoInternalDetail() {
            assertThat(TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE)
                    .as("the program writes the raw codes to the operator console instead, and only "
                            + "this sentence belongs in the contract")
                    .doesNotContain("RESP", "resp", "SQL", "Exception", "TRANSACT", "carddemo");
        }

        @Test
        @DisplayName("all three fit the seventy-eight character message line, and so does a shared "
                + "fifty-character common message")
        void allThreeFitTheMessageLine() {
            List<String> published = List.of(
                    TransactionViewResponse.EMPTY_TRANSACTION_ID_MESSAGE,
                    TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE,
                    TransactionViewResponse.TRANSACTION_LOOKUP_FAILED_MESSAGE);

            assertThat(published).allSatisfy(text -> {
                assertThat(text).hasSizeLessThanOrEqualTo(78);
                assertThat(violationsOf(withErrorMessage(text))).isEmpty();
            });
            assertThat(violationsOf(withErrorMessage(atWidth("", 50))))
                    .as("a shared common message is fifty characters wide and must arrive untouched")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("value semantics, immutability by construction, and the diagnostic rendering")
    class ValueSemanticsAndRendering {
        @Test
        @DisplayName("two replies built from identical components are equal and share a hash code")
        void twoRepliesFromIdenticalComponentsAreEqual() {
            TransactionViewResponse first = foundReply();
            TransactionViewResponse second = foundReply();

            assertThat(first).isEqualTo(second).isNotSameAs(second);
            assertThat(first).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("replies differing in a single component are not equal")
        void repliesDifferingInASingleComponentAreNotEqual() {
            assertThat(withIdentifiers(SEARCH_TRANSACTION_ID, FOUND_TRANSACTION_ID))
                    .isNotEqualTo(withIdentifiers(SEARCH_TRANSACTION_ID, SEARCH_TRANSACTION_ID));
            assertThat(withDates(ORIGINATION_DATE, PROCESSING_DATE))
                    .as("a blank date is a different value from an absent one")
                    .isNotEqualTo(withDates(ORIGINATION_DATE, atWidth("", 10)));
            assertThat(withSource("POS TERM  "))
                    .as("padding is part of the value, so a trimmed channel is a different reply")
                    .isNotEqualTo(withSource("POS TERM"));
        }

        @Test
        @DisplayName("every one of the twenty-five accessors returns exactly what it was handed")
        void everyAccessorReturnsWhatItWasHanded() {
            TransactionViewResponse reply = foundReply();

            assertThat(reply.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(reply.title01()).isEqualTo(TITLE01);
            assertThat(reply.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(reply.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(reply.title02()).isEqualTo(TITLE02);
            assertThat(reply.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(reply.searchTransactionId()).isEqualTo(SEARCH_TRANSACTION_ID);
            assertThat(reply.transactionId()).isEqualTo(FOUND_TRANSACTION_ID);
            assertThat(reply.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(reply.typeCode()).isEqualTo(TYPE_CODE);
            assertThat(reply.categoryCode()).isEqualTo(CATEGORY_CODE);
            assertThat(reply.source()).isEqualTo(SOURCE);
            assertThat(reply.description()).isEqualTo(DESCRIPTION);
            assertThat(reply.amount()).isEqualTo(AMOUNT);
            assertThat(reply.originationDate()).isEqualTo(ORIGINATION_DATE);
            assertThat(reply.processingDate()).isEqualTo(PROCESSING_DATE);
            assertThat(reply.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(reply.merchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(reply.merchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(reply.merchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(reply.errorMessage())
                    .isEqualTo(TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE);
            assertThat(reply.generalError()).isTrue();
            assertThat(reply.focusScreenFieldId()).isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(reply.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(reply.navigationContext()).isEqualTo(populatedNavigation());
        }

        @Test
        @DisplayName("every component tolerates being absent")
        void everyComponentToleratesBeingAbsent() {
            TransactionViewResponse reply = absentReply();

            assertThat(reply.transactionName()).isNull();
            assertThat(reply.title01()).isNull();
            assertThat(reply.currentDate()).isNull();
            assertThat(reply.programName()).isNull();
            assertThat(reply.title02()).isNull();
            assertThat(reply.currentTime()).isNull();
            assertThat(reply.searchTransactionId()).isNull();
            assertThat(reply.transactionId()).isNull();
            assertThat(reply.cardNumber()).isNull();
            assertThat(reply.typeCode()).isNull();
            assertThat(reply.categoryCode()).isNull();
            assertThat(reply.source()).isNull();
            assertThat(reply.description()).isNull();
            assertThat(reply.amount()).isNull();
            assertThat(reply.originationDate()).isNull();
            assertThat(reply.processingDate()).isNull();
            assertThat(reply.merchantId()).isNull();
            assertThat(reply.merchantName()).isNull();
            assertThat(reply.merchantCity()).isNull();
            assertThat(reply.merchantZip()).isNull();
            assertThat(reply.errorMessage()).isNull();
            assertThat(reply.generalError())
                    .as("the one component that cannot be absent, because it is a primitive")
                    .isFalse();
            assertThat(reply.focusScreenFieldId()).isNull();
            assertThat(reply.nextRoute()).isNull();
            assertThat(reply.navigationContext()).isNull();
        }

        @Test
        @DisplayName("nothing observable changes after rendering, so the type is immutable in use")
        void nothingChangesAfterRendering() {
            TransactionViewResponse reply = foundReply();
            TransactionViewResponse before = foundReply();

            String ignoredRendering = reply.toString();

            assertThat(ignoredRendering).isNotEmpty();
            assertThat(reply)
                    .as("immutability shown by construction: a record has no setter to call, and "
                            + "every accessor still answers what it was handed")
                    .isEqualTo(before);
            assertThat(reply.amount()).isSameAs(AMOUNT);
            assertThat(reply.navigationContext()).isEqualTo(populatedNavigation());
        }

        @Test
        @DisplayName("the rendering is bracketed by the type name and withholds the regulated values")
        void theRenderingIsBracketedAndWithholdsRegulatedValues() {
            String rendering = foundReply().toString();

            assertThat(rendering).startsWith("TransactionViewResponse[").endsWith("]");
            assertThat(rendering)
                    .as("a stringified instance must not carry a cardholder's activity into a log")
                    .doesNotContain(CARD_NUMBER, SEARCH_TRANSACTION_ID, FOUND_TRANSACTION_ID,
                            DESCRIPTION, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                            ORIGINATION_DATE, PROCESSING_DATE, "123.45");
            assertThat(rendering).contains("cardNumber=" + REDACTION_PLACEHOLDER,
                    "amount=" + REDACTION_PLACEHOLDER,
                    "originationDate=" + REDACTION_PLACEHOLDER,
                    "processingDate=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("the rendering keeps the screen furniture, the codes and the error surface")
        void theRenderingKeepsTheDiagnosticallyUsefulValues() {
            String rendering = foundReply().toString();

            assertThat(rendering).contains("transactionName=" + TRANSACTION_NAME,
                    "programName=" + PROGRAM_NAME, "typeCode=" + TYPE_CODE,
                    "categoryCode=" + CATEGORY_CODE, "source=" + SOURCE, "generalError=true",
                    "focusScreenFieldId=" + FOCUS_SCREEN_FIELD_ID, "nextRoute=" + NEXT_ROUTE,
                    "errorMessage=" + TransactionViewResponse.TRANSACTION_NOT_FOUND_MESSAGE);
            assertThat(rendering)
                    .as("the nested state renders by delegation and withholds its own identifiers")
                    .contains("navigationContext=NavigationContext[")
                    .doesNotContain(NAVIGATION_ACCOUNT_ID, NAVIGATION_CUSTOMER_ID,
                            NAVIGATION_CARD_NUMBER);
        }

        @Test
        @DisplayName("an entirely absent reply renders without failing")
        void anEntirelyAbsentReplyRendersWithoutFailing() {
            assertThatNoException().isThrownBy(() -> absentReply().toString());
            assertThat(absentReply().toString())
                    .startsWith("TransactionViewResponse[")
                    .contains("generalError=false")
                    .contains("cardNumber=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("the rendering changes nothing a client receives")
        void theRenderingChangesNothingAClientReceives() throws JsonProcessingException {
            TransactionViewResponse reply = foundReply();
            reply.toString();

            String payload = serialise(reply);

            assertThat(payload)
                    .contains("\"cardNumber\":\"" + CARD_NUMBER + "\"")
                    .contains("\"merchantName\":\"" + MERCHANT_NAME + "\"")
                    .contains("\"amount\":-123.45");
            assertThat(payload)
                    .as("the stand-in is a rendering detail and never reaches a client")
                    .doesNotContain(REDACTION_PLACEHOLDER);
        }
    }
}
