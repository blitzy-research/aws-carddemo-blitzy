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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.carddemo.service.MessageCatalogService;
import com.carddemo.domain.enums.UserType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link SignOnResponse}, the outbound contract of legacy transaction {@code CC00}.
 *
 * <p>Two properties belong to this file.
 *
 * <p><strong>A failed credential comparison is not a general error.</strong> The program raises its
 * error flag on five of its eight outcomes and leaves it lowered on three, and the failed comparison
 * is one of the three: that path composes a message and moves the cursor without assigning the flag at
 * all. So "carries a message" and "is an error" are different facts, the flag must be its own explicit
 * primitive, and it must never be derived from the presence of a message. {@link WrongCredentialArm}
 * pins that down and {@link ErrorFlagAsymmetry} proves the asymmetry is modelled rather than
 * accidental.
 *
 * <p><strong>The two shared texts are fifty characters, not forty-nine.</strong> Each is written as a
 * forty-nine-character literal inside a fifty-character field, so the stored value is the literal
 * followed by one filling space. {@link PaddedCommonMessages} asserts all fifty survive construction
 * and both wire directions with the pad intact, and that the fifty-character family is never conflated
 * with the unrelated forty-character title family declared in a different copybook.
 *
 * <p>Every width, field identity and message text is restated here from the legacy artefacts rather
 * than borrowed from the class under test - {@code app/cbl/COSGN00C.cbl} for the behaviour and the five
 * direct texts, {@code app/cpy-bms/COSGN00.CPY} for the symbolic map, {@code app/cpy/CSMSG01Y.cpy} for
 * the two shared texts, {@code app/cpy/COCOM01Y.cpy} for the user-type codes and
 * {@code app/cpy/CSUSR01Y.cpy} for their persisted origin.
 *
 * <p>A pure unit test that starts no context, container or connection and touches no security type.
 * Nothing interrogates the record's shape at run time: the component inventory, the absence of a
 * credential and the absence of terminal furniture are established from the <em>serialised payload</em>
 * of a fully populated instance, which is what a client can actually observe - a component that reaches
 * no client cannot leak to one. Immutability is shown by construction, deriving a modified nested state
 * and finding the original untouched. The mapper is configured by hand here to match the four
 * serialisation settings the module declares in {@code src/main/resources/application.yml}.
 */
@DisplayName("SignOnResponse - the outbound contract of legacy transaction CC00")
class SignOnResponseTest {
    private static final String ORACLE_MSG_PROMPT_USERID = "Please enter User ID ...";

    private static final String ORACLE_MSG_PROMPT_ENTRY = "Please enter Password ...";

    private static final String ORACLE_MSG_COMPARISON_FAILED = "Wrong Password. Try again ...";

    private static final String ORACLE_MSG_USER_NOT_FOUND = "User not found. Try again ...";

    private static final String ORACLE_MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    private static final String ORACLE_MSG_THANK_YOU_AS_WRITTEN =
            "Thank you for using CardDemo application...      ";

    private static final String ORACLE_MSG_INVALID_KEY_AS_WRITTEN =
            "Invalid key pressed. Please see below...         ";

    private static final String ORACLE_FIELD_PAD = " ";

    private static final String ORACLE_MSG_THANK_YOU_STORED =
            ORACLE_MSG_THANK_YOU_AS_WRITTEN + ORACLE_FIELD_PAD;

    private static final String ORACLE_MSG_INVALID_KEY_STORED =
            ORACLE_MSG_INVALID_KEY_AS_WRITTEN + ORACLE_FIELD_PAD;

    private static final int ORACLE_LENGTH_PROMPT_USERID = 24;

    private static final int ORACLE_LENGTH_PROMPT_ENTRY = 25;

    private static final int ORACLE_LENGTH_FAILURE_TEXT = 29;

    private static final int ORACLE_COMMON_MESSAGE_WIDTH = 50;

    private static final int ORACLE_COMMON_MESSAGE_LITERAL_LENGTH = 49;

    private static final int ORACLE_SCREEN_TITLE_WIDTH = 40;

    private static final int ORACLE_MESSAGE_WIDTH = 80;

    private static final int ORACLE_SCREEN_MESSAGE_WIDTH = 78;

    private static final int ORACLE_USER_ID_WIDTH = 8;

    private static final int ORACLE_USER_TYPE_WIDTH = 1;

    private static final int ORACLE_SCREEN_FIELD_ID_WIDTH = 7;

    private static final int ORACLE_TRANSACTION_NAME_WIDTH = 4;

    private static final int ORACLE_EIGHT_CHARACTER_WIDTH = 8;

    private static final int ORACLE_CURRENT_TIME_WIDTH = 9;

    private static final int ORACLE_ACCOUNT_ID_WIDTH = 11;

    private static final int ORACLE_CUSTOMER_ID_WIDTH = 9;

    private static final int ORACLE_CARD_NUMBER_WIDTH = 16;

    private static final List<String> ORACLE_PUBLISHED_PROPERTIES = List.of(
            "message",
            "generalError",
            "focusScreenFieldId",
            "nextRoute",
            "navigationContext",
            "userId",
            "userType",
            "transactionName",
            "programName",
            "title01",
            "title02",
            "currentDate",
            "currentTime",
            "applicationId",
            "systemId");

    private static final List<String> ORACLE_MAP_ITEM_NAMES = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");

    private static final List<String> ORACLE_CONTROL_BYTE_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

    private static final String SAMPLE_USER_ID = "ADMIN001";

    private static final String CODE_ADMINISTRATOR = "A";

    private static final String CODE_STANDARD_USER = "U";

    private static final String CODE_UNDECLARED = "Z";

    private static final String ROUTE_ADMIN_MENU = "/api/v1/menu/admin";

    private static final String ROUTE_USER_MENU = "/api/v1/menu/user";

    private static final String FIELD_ENTRY = "PASSWD";

    private static final String FIELD_USER_ID = "USERID";

    private static final String SAMPLE_TITLE_01 = "      AWS Mainframe Modernization       ";

    private static final String SAMPLE_TITLE_02 = "              CardDemo                  ";

    private static final String SAMPLE_CURRENT_DATE = "01/31/24";

    private static final String SAMPLE_CURRENT_TIME = "12:34:56 ";

    private static final String SAMPLE_APPLICATION_ID = "CICSAWS1";

    private static final String SAMPLE_SYSTEM_ID = "AWS1";

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

    private static JsonNode payloadOf(SignOnResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static String documentOf(SignOnResponse response) throws JsonProcessingException {
        return moduleEquivalentMapper().writeValueAsString(response);
    }

    private static SignOnResponse roundTrip(SignOnResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readValue(mapper.writeValueAsString(response), SignOnResponse.class);
    }

    private static List<String> propertyNamesOf(JsonNode payload) {
        List<String> names = new ArrayList<>();
        payload.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<ConstraintViolation<SignOnResponse>> violationsOf(SignOnResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    private static Set<String> violatedComponentsOf(SignOnResponse response) {
        Set<String> components = new LinkedHashSet<>();
        for (ConstraintViolation<SignOnResponse> violation : violationsOf(response)) {
            components.add(violation.getPropertyPath().toString());
        }
        return components;
    }

    private static String valueOfLength(int length) {
        return "X".repeat(length);
    }

    private static SignOnResponse comparisonFailedArm() {
        return redisplay(ORACLE_MSG_COMPARISON_FAILED, false, FIELD_ENTRY);
    }

    private static SignOnResponse userNotFoundArm() {
        return redisplay(ORACLE_MSG_USER_NOT_FOUND, true, FIELD_USER_ID);
    }

    private static SignOnResponse unableToVerifyArm() {
        return redisplay(ORACLE_MSG_UNABLE_TO_VERIFY, true, FIELD_USER_ID);
    }

    private static SignOnResponse exitKeyArm() {
        return new SignOnResponse(ORACLE_MSG_THANK_YOU_STORED, false, null, null, null,
                null, null, SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                SAMPLE_TITLE_01, SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME,
                SAMPLE_APPLICATION_ID, SAMPLE_SYSTEM_ID);
    }

    private static SignOnResponse unmappedKeyArm() {
        return redisplay(ORACLE_MSG_INVALID_KEY_STORED, true, null);
    }

    private static SignOnResponse redisplay(String message, boolean generalError,
            String focusScreenFieldId) {
        return new SignOnResponse(message, generalError, focusScreenFieldId, null, null,
                SAMPLE_USER_ID, null, SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                SAMPLE_TITLE_01, SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME,
                SAMPLE_APPLICATION_ID, SAMPLE_SYSTEM_ID);
    }

    private static SignOnResponse successfulSignOn(String userTypeCode, String nextRoute) {
        return new SignOnResponse(null, false, null, nextRoute,
                successorState(userTypeCode), SAMPLE_USER_ID, userTypeCode,
                SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                SAMPLE_TITLE_01, SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME,
                SAMPLE_APPLICATION_ID, SAMPLE_SYSTEM_ID);
    }

    private static NavigationContext successorState(String userTypeCode) {
        return new NavigationContext(
                SignOnResponse.TRANSACTION_NAME,
                SignOnResponse.PROGRAM_NAME,
                MenuResponse.ADMIN_MENU_TRANSACTION_NAME,
                MenuResponse.ADMIN_MENU_PROGRAM_NAME,
                SAMPLE_USER_ID,
                userTypeCode,
                NavigationContext.ProgramContext.ENTER,
                "000000001",
                "FIRST                    ",
                "MIDDLE                   ",
                "LAST                     ",
                "00000000001",
                "Y",
                "0000000000000001",
                "COSGN0A",
                "COSGN00");
    }

    private static SignOnResponse whollyAbsent() {
        return new SignOnResponse(null, false, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static SignOnResponse messageOnly(String message, boolean generalError) {
        return new SignOnResponse(message, generalError, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static SignOnResponse fullyPopulatedWith(String message) {
        return new SignOnResponse(message, true, FIELD_ENTRY, ROUTE_ADMIN_MENU,
                successorState(CODE_ADMINISTRATOR), SAMPLE_USER_ID, CODE_ADMINISTRATOR,
                SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME, SAMPLE_TITLE_01,
                SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME, SAMPLE_APPLICATION_ID,
                SAMPLE_SYSTEM_ID);
    }

    @Nested
    @DisplayName("The failed-comparison outcome carries a message with the flag lowered")
    class WrongCredentialArm {
        @Test
        @DisplayName("carries the failure text byte for byte, at its measured length, untrimmed")
        void carriesTheFailureTextByteForByte() {
            SignOnResponse response = comparisonFailedArm();

            assertThat(response.message())
                    .as("the failed-comparison text is an external interface and is reproduced "
                            + "character for character")
                    .isEqualTo(ORACLE_MSG_COMPARISON_FAILED)
                    .hasSize(ORACLE_LENGTH_FAILURE_TEXT);

            String paddedToProgramWidth = ORACLE_MSG_COMPARISON_FAILED
                    + ORACLE_FIELD_PAD.repeat(ORACLE_MESSAGE_WIDTH - ORACLE_LENGTH_FAILURE_TEXT);
            SignOnResponse padded = messageOnly(paddedToProgramWidth, false);

            assertThat(padded.message())
                    .as("every trailing space of the program-side field survives untouched")
                    .isEqualTo(paddedToProgramWidth)
                    .hasSize(ORACLE_MESSAGE_WIDTH);
            assertThat(violationsOf(padded))
                    .as("and the declared bound admits that full width")
                    .isEmpty();
        }

        @Test
        @DisplayName("leaves the general-error flag false, exactly as the legacy arm does")
        void leavesTheGeneralErrorFlagFalse() {
            SignOnResponse response = comparisonFailedArm();

            assertThat(response.generalError())
                    .as("the failed-comparison arm assigns nothing to the error flag, so it stays "
                            + "lowered from entry")
                    .isFalse();
            assertThat(response.message())
                    .as("and yet a message is present, which is what makes the two facts "
                            + "independent")
                    .isNotNull();
        }

        @Test
        @DisplayName("proves the flag is a component of its own and not derived from the message")
        void provesTheFlagIsNotDerivedFromTheMessage() {
            SignOnResponse flagLowered = messageOnly(ORACLE_MSG_COMPARISON_FAILED, false);
            SignOnResponse flagRaised = messageOnly(ORACLE_MSG_COMPARISON_FAILED, true);

            assertThat(flagLowered.message())
                    .as("both instances carry the identical text")
                    .isEqualTo(flagRaised.message())
                    .isEqualTo(ORACLE_MSG_COMPARISON_FAILED);
            assertThat(flagLowered.generalError()).isFalse();
            assertThat(flagRaised.generalError()).isTrue();
            assertThat(flagLowered)
                    .as("identical text with a different flag yields two legal and distinguishable "
                            + "instances, so the flag cannot be a function of the message")
                    .isNotEqualTo(flagRaised);
        }

        @Test
        @DisplayName("survives the wire with the text and the lowered flag both intact")
        void survivesTheWireWithTextAndLoweredFlag() throws JsonProcessingException {
            JsonNode payload = payloadOf(comparisonFailedArm());

            assertThat(payload.get("message").asText())
                    .isEqualTo(ORACLE_MSG_COMPARISON_FAILED)
                    .hasSize(ORACLE_LENGTH_FAILURE_TEXT);
            assertThat(payload.get("generalError").asBoolean())
                    .as("a serialiser must not infer the flag from the message either")
                    .isFalse();
            assertThat(payload.has("message"))
                    .as("the message is published, so its presence alongside a lowered flag is what "
                            + "a client actually observes")
                    .isTrue();
        }

        @Test
        @DisplayName("returns the operator to the entry field without becoming a general error")
        void returnsTheOperatorToTheEntryFieldWithoutBecomingAnError() {
            SignOnResponse response = comparisonFailedArm();

            assertThat(response.focusScreenFieldId())
                    .as("the cursor moves on this arm even though the flag does not")
                    .isEqualTo(FIELD_ENTRY);
            assertThat(response.generalError()).isFalse();
            assertThat(response.userId())
                    .as("the identifier is restated because the identifier is not what failed")
                    .isEqualTo(SAMPLE_USER_ID)
                    .hasSize(ORACLE_USER_ID_WIDTH);
            assertThat(response.nextRoute())
                    .as("a redisplay nominates no destination")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("The error flag is asymmetric across the eight outcomes")
    class ErrorFlagAsymmetry {
        @Test
        @DisplayName("raises the flag on the missing-record arm, which carries the same text length")
        void raisesTheFlagOnTheMissingRecordArm() {
            SignOnResponse response = userNotFoundArm();

            assertThat(response.message())
                    .isEqualTo(ORACLE_MSG_USER_NOT_FOUND)
                    .hasSize(ORACLE_LENGTH_FAILURE_TEXT);
            assertThat(response.generalError())
                    .as("unlike the failed comparison, this arm assigns the flag")
                    .isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_USER_ID);
        }

        @Test
        @DisplayName("raises the flag on the catch-all read-failure arm as well")
        void raisesTheFlagOnTheCatchAllArm() {
            SignOnResponse response = unableToVerifyArm();

            assertThat(response.message())
                    .isEqualTo(ORACLE_MSG_UNABLE_TO_VERIFY)
                    .hasSize(ORACLE_LENGTH_FAILURE_TEXT);
            assertThat(response.generalError()).isTrue();
        }

        @Test
        @DisplayName("leaves the flag lowered on the exit-key arm, which carries a fifty-character text")
        void leavesTheFlagLoweredOnTheExitKeyArm() {
            SignOnResponse response = exitKeyArm();

            assertThat(response.message())
                    .as("the shared thank-you text as the fifty-character field stores it")
                    .isEqualTo(ORACLE_MSG_THANK_YOU_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(response.generalError())
                    .as("leaving the screen courteously is not an error")
                    .isFalse();
        }

        @Test
        @DisplayName("raises the flag on the unmapped-key arm, which also carries a fifty-character text")
        void raisesTheFlagOnTheUnmappedKeyArm() {
            SignOnResponse response = unmappedKeyArm();

            assertThat(response.message())
                    .isEqualTo(ORACLE_MSG_INVALID_KEY_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(response.generalError())
                    .as("an unmapped key is an error, unlike the exit key that sits beside it in the "
                            + "same construct")
                    .isTrue();
        }

        @Test
        @DisplayName("leaves the flag lowered on the first-entry arm, which carries no text at all")
        void leavesTheFlagLoweredOnTheFirstEntryArm() {
            SignOnResponse response = new SignOnResponse(null, false, FIELD_USER_ID, null, null,
                    null, null, SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                    SAMPLE_TITLE_01, SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME,
                    SAMPLE_APPLICATION_ID, SAMPLE_SYSTEM_ID);

            assertThat(response.message())
                    .as("an empty communication area sends the screen with no text")
                    .isNull();
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId())
                    .as("the cursor still lands on the identifier field")
                    .isEqualTo(FIELD_USER_ID);
        }

        @Test
        @DisplayName("admits two arms that carry a message with the flag lowered, so no derivation rule "
                + "can hold")
        void admitsTwoArmsThatCarryAMessageWithTheFlagLowered() {
            List<SignOnResponse> loweredWithMessage =
                    List.of(comparisonFailedArm(), exitKeyArm());
            List<SignOnResponse> raisedWithMessage =
                    List.of(userNotFoundArm(), unableToVerifyArm(), unmappedKeyArm());

            assertThat(loweredWithMessage)
                    .as("both arms carry a message and leave the flag lowered")
                    .allSatisfy(response -> {
                        assertThat(response.message()).isNotNull();
                        assertThat(response.generalError()).isFalse();
                    });
            assertThat(raisedWithMessage)
                    .as("these three carry a message and raise it")
                    .allSatisfy(response -> {
                        assertThat(response.message()).isNotNull();
                        assertThat(response.generalError()).isTrue();
                    });
        }

        @Test
        @DisplayName("carries the flag over the wire as a boolean that is always published")
        void carriesTheFlagOverTheWireAsAnAlwaysPublishedBoolean()
                throws JsonProcessingException {
            JsonNode lowered = payloadOf(comparisonFailedArm());
            JsonNode raised = payloadOf(userNotFoundArm());
            JsonNode absent = payloadOf(whollyAbsent());

            assertThat(lowered.get("generalError").isBoolean())
                    .as("a boolean, never a string flag character")
                    .isTrue();
            assertThat(lowered.get("generalError").asBoolean()).isFalse();
            assertThat(raised.get("generalError").asBoolean()).isTrue();
            assertThat(absent.has("generalError"))
                    .as("a primitive is never omitted, so a client always learns the flag state")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("The two shared texts are fifty characters, and the pad is part of the value")
    class PaddedCommonMessages {
        @Test
        @DisplayName("relates forty-nine as written to fifty as stored, for both texts")
        void relatesFortyNineAsWrittenToFiftyAsStored() {
            assertThat(ORACLE_MSG_THANK_YOU_AS_WRITTEN)
                    .as("the thank-you literal as the value clause writes it")
                    .hasSize(ORACLE_COMMON_MESSAGE_LITERAL_LENGTH);
            assertThat(ORACLE_MSG_INVALID_KEY_AS_WRITTEN)
                    .as("the invalid-key literal as the value clause writes it")
                    .hasSize(ORACLE_COMMON_MESSAGE_LITERAL_LENGTH);
            assertThat(ORACLE_MSG_THANK_YOU_STORED)
                    .as("one pad wider is what the field stores")
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH)
                    .startsWith(ORACLE_MSG_THANK_YOU_AS_WRITTEN)
                    .endsWith(ORACLE_FIELD_PAD);
            assertThat(ORACLE_MSG_INVALID_KEY_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH)
                    .startsWith(ORACLE_MSG_INVALID_KEY_AS_WRITTEN)
                    .endsWith(ORACLE_FIELD_PAD);
            assertThat(ORACLE_COMMON_MESSAGE_WIDTH)
                    .isEqualTo(ORACLE_COMMON_MESSAGE_LITERAL_LENGTH + ORACLE_FIELD_PAD.length());
        }

        @Test
        @DisplayName("carries the thank-you text at exactly fifty characters, pad intact")
        void carriesTheThankYouTextAtFiftyCharacters() {
            SignOnResponse response = messageOnly(ORACLE_MSG_THANK_YOU_STORED, false);

            assertThat(response.message())
                    .as("fifty characters, not the forty-nine of the literal")
                    .isEqualTo(ORACLE_MSG_THANK_YOU_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH)
                    .isNotEqualTo(ORACLE_MSG_THANK_YOU_AS_WRITTEN);
            assertThat(response.message())
                    .as("never trimmed, never stripped, never shortened to forty-nine")
                    .endsWith(ORACLE_FIELD_PAD)
                    .isNotEqualTo(ORACLE_MSG_THANK_YOU_STORED.trim())
                    .isNotEqualTo(ORACLE_MSG_THANK_YOU_STORED.strip());
            assertThat(response.message().length())
                    .isNotEqualTo(ORACLE_COMMON_MESSAGE_LITERAL_LENGTH);
        }

        @Test
        @DisplayName("carries the invalid-key text at exactly fifty characters, pad intact")
        void carriesTheInvalidKeyTextAtFiftyCharacters() {
            SignOnResponse response = messageOnly(ORACLE_MSG_INVALID_KEY_STORED, true);

            assertThat(response.message())
                    .isEqualTo(ORACLE_MSG_INVALID_KEY_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH)
                    .isNotEqualTo(ORACLE_MSG_INVALID_KEY_AS_WRITTEN);
            assertThat(response.message())
                    .as("never trimmed, never stripped, never shortened to forty-nine")
                    .endsWith(ORACLE_FIELD_PAD)
                    .isNotEqualTo(ORACLE_MSG_INVALID_KEY_STORED.trim())
                    .isNotEqualTo(ORACLE_MSG_INVALID_KEY_STORED.strip());
            assertThat(response.message().length())
                    .isNotEqualTo(ORACLE_COMMON_MESSAGE_LITERAL_LENGTH);
        }

        @Test
        @DisplayName("serialises the thank-you text through the module's mapper unchanged")
        void serialisesTheThankYouTextUnchanged() throws JsonProcessingException {
            SignOnResponse response = messageOnly(ORACLE_MSG_THANK_YOU_STORED, false);

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("message").asText())
                    .as("all fifty characters reach the wire")
                    .isEqualTo(ORACLE_MSG_THANK_YOU_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("deserialises the thank-you text back at fifty characters")
        void deserialisesTheThankYouTextAtFiftyCharacters() throws JsonProcessingException {
            SignOnResponse rebuilt = roundTrip(messageOnly(ORACLE_MSG_THANK_YOU_STORED, false));

            assertThat(rebuilt.message())
                    .as("a full round trip neither trims the pad nor adds one")
                    .isEqualTo(ORACLE_MSG_THANK_YOU_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(rebuilt.generalError()).isFalse();
        }

        @Test
        @DisplayName("serialises the invalid-key text through the module's mapper unchanged")
        void serialisesTheInvalidKeyTextUnchanged() throws JsonProcessingException {
            JsonNode payload = payloadOf(messageOnly(ORACLE_MSG_INVALID_KEY_STORED, true));

            assertThat(payload.get("message").asText())
                    .isEqualTo(ORACLE_MSG_INVALID_KEY_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("deserialises the invalid-key text back at fifty characters")
        void deserialisesTheInvalidKeyTextAtFiftyCharacters() throws JsonProcessingException {
            SignOnResponse rebuilt = roundTrip(messageOnly(ORACLE_MSG_INVALID_KEY_STORED, true));

            assertThat(rebuilt.message())
                    .isEqualTo(ORACLE_MSG_INVALID_KEY_STORED)
                    .hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(rebuilt.generalError()).isTrue();
        }

        @Test
        @DisplayName("admits both fifty-character texts within the declared message bound")
        void admitsBothTextsWithinTheDeclaredBound() {
            assertThat(violationsOf(messageOnly(ORACLE_MSG_THANK_YOU_STORED, false)))
                    .as("fifty fits comfortably inside the width the contract carries a message at")
                    .isEmpty();
            assertThat(violationsOf(messageOnly(ORACLE_MSG_INVALID_KEY_STORED, true))).isEmpty();
            assertThat(ORACLE_COMMON_MESSAGE_WIDTH).isLessThan(ORACLE_MESSAGE_WIDTH);
            assertThat(ORACLE_COMMON_MESSAGE_WIDTH).isLessThan(ORACLE_SCREEN_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("keeps the fifty-character family separate from the forty-character title family")
        void keepsTheFiftyCharacterFamilySeparateFromTheTitleFamily() {
            List<String> fiftyCharacterFamily =
                    List.of(ORACLE_MSG_THANK_YOU_STORED, ORACLE_MSG_INVALID_KEY_STORED);
            List<String> fortyCharacterFamily = List.of(MessageCatalogService.CCDA_THANK_YOU,
                    MessageCatalogService.CCDA_TITLE01, MessageCatalogService.CCDA_TITLE02);

            assertThat(fortyCharacterFamily)
                    .as("every title value measures forty")
                    .allSatisfy(title -> assertThat(title).hasSize(ORACLE_SCREEN_TITLE_WIDTH));
            assertThat(fiftyCharacterFamily)
                    .as("every shared message measures fifty and equals no title value")
                    .allSatisfy(message -> {
                        assertThat(message).hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
                        assertThat(fortyCharacterFamily).doesNotContain(message);
                    });
            assertThat(ORACLE_MSG_THANK_YOU_STORED)
                    .as("the two courtesy texts are different values at different widths")
                    .isNotEqualTo(MessageCatalogService.CCDA_THANK_YOU);
            assertThat(ORACLE_COMMON_MESSAGE_WIDTH).isNotEqualTo(ORACLE_SCREEN_TITLE_WIDTH);
        }

        @Test
        @DisplayName("carries a fifty-character text on either flag state, since width is not a verdict")
        void carriesAFiftyCharacterTextOnEitherFlagState() {
            SignOnResponse lowered = messageOnly(ORACLE_MSG_THANK_YOU_STORED, false);
            SignOnResponse raised = messageOnly(ORACLE_MSG_INVALID_KEY_STORED, true);

            assertThat(lowered.message()).hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(raised.message()).hasSize(ORACLE_COMMON_MESSAGE_WIDTH);
            assertThat(lowered.generalError()).isFalse();
            assertThat(raised.generalError()).isTrue();
        }
    }

    @Nested
    @DisplayName("All seven message texts are representable byte for byte")
    class GateFiveMessageContract {
        @Test
        @DisplayName("publishes the five direct texts exactly as the program writes them")
        void publishesTheFiveDirectTextsExactly() {
            assertThat(SignOnResponse.MSG_PROMPT_USERID)
                    .isEqualTo(ORACLE_MSG_PROMPT_USERID)
                    .hasSize(ORACLE_LENGTH_PROMPT_USERID);
            assertThat(SignOnResponse.MSG_PROMPT_PASSWD)
                    .isEqualTo(ORACLE_MSG_PROMPT_ENTRY)
                    .hasSize(ORACLE_LENGTH_PROMPT_ENTRY);
            assertThat(SignOnResponse.MSG_WRONG_PASSWD)
                    .isEqualTo(ORACLE_MSG_COMPARISON_FAILED)
                    .hasSize(ORACLE_LENGTH_FAILURE_TEXT);
            assertThat(SignOnResponse.MSG_USER_NOT_FOUND)
                    .isEqualTo(ORACLE_MSG_USER_NOT_FOUND)
                    .hasSize(ORACLE_LENGTH_FAILURE_TEXT);
            assertThat(SignOnResponse.MSG_UNABLE_TO_VERIFY)
                    .isEqualTo(ORACLE_MSG_UNABLE_TO_VERIFY)
                    .hasSize(ORACLE_LENGTH_FAILURE_TEXT);
        }

        @Test
        @DisplayName("carries every one of the seven at its own measured length, in one table")
        void carriesEverySevenAtItsMeasuredLength() {
            List<String> texts = List.of(
                    ORACLE_MSG_PROMPT_USERID,
                    ORACLE_MSG_PROMPT_ENTRY,
                    ORACLE_MSG_COMPARISON_FAILED,
                    ORACLE_MSG_USER_NOT_FOUND,
                    ORACLE_MSG_UNABLE_TO_VERIFY,
                    ORACLE_MSG_THANK_YOU_STORED,
                    ORACLE_MSG_INVALID_KEY_STORED);
            List<Integer> measuredLengths = List.of(
                    ORACLE_LENGTH_PROMPT_USERID,
                    ORACLE_LENGTH_PROMPT_ENTRY,
                    ORACLE_LENGTH_FAILURE_TEXT,
                    ORACLE_LENGTH_FAILURE_TEXT,
                    ORACLE_LENGTH_FAILURE_TEXT,
                    ORACLE_COMMON_MESSAGE_WIDTH,
                    ORACLE_COMMON_MESSAGE_WIDTH);

            assertThat(texts)
                    .as("seven texts, and the seven measured lengths that go with them")
                    .hasSameSizeAs(measuredLengths);
            for (int index = 0; index < texts.size(); index++) {
                String text = texts.get(index);
                int expectedLength = measuredLengths.get(index);
                SignOnResponse response = messageOnly(text, false);

                assertThat(response.message())
                        .as("text %d of seven survives construction unaltered", index + 1)
                        .isEqualTo(text)
                        .hasSize(expectedLength);
            }
        }

        @Test
        @DisplayName("round-trips every one of the seven through the wire without a single character "
                + "changing")
        void roundTripsEverySevenThroughTheWire() throws JsonProcessingException {
            List<String> texts = List.of(
                    ORACLE_MSG_PROMPT_USERID,
                    ORACLE_MSG_PROMPT_ENTRY,
                    ORACLE_MSG_COMPARISON_FAILED,
                    ORACLE_MSG_USER_NOT_FOUND,
                    ORACLE_MSG_UNABLE_TO_VERIFY,
                    ORACLE_MSG_THANK_YOU_STORED,
                    ORACLE_MSG_INVALID_KEY_STORED);

            for (String text : texts) {
                SignOnResponse rebuilt = roundTrip(messageOnly(text, false));

                assertThat(rebuilt.message())
                        .as("text [%s] must survive serialisation and deserialisation", text)
                        .isEqualTo(text)
                        .hasSize(text.length());
            }
        }

        @Test
        @DisplayName("distinguishes all seven from one another, so none has been conflated")
        void distinguishesAllSevenFromOneAnother() {
            Set<String> distinct = new LinkedHashSet<>(List.of(
                    ORACLE_MSG_PROMPT_USERID,
                    ORACLE_MSG_PROMPT_ENTRY,
                    ORACLE_MSG_COMPARISON_FAILED,
                    ORACLE_MSG_USER_NOT_FOUND,
                    ORACLE_MSG_UNABLE_TO_VERIFY,
                    ORACLE_MSG_THANK_YOU_STORED,
                    ORACLE_MSG_INVALID_KEY_STORED));

            assertThat(distinct)
                    .as("seven texts, seven distinct values - none deduplicated into another")
                    .hasSize(7);
        }

        @Test
        @DisplayName("keeps the two prompts distinct, because the ordered cascade stops at the first "
                + "match")
        void keepsTheTwoPromptsDistinct() {
            assertThat(ORACLE_MSG_PROMPT_USERID).isNotEqualTo(ORACLE_MSG_PROMPT_ENTRY);
            assertThat(ORACLE_LENGTH_PROMPT_USERID).isNotEqualTo(ORACLE_LENGTH_PROMPT_ENTRY);

            SignOnResponse firstClause = redisplay(ORACLE_MSG_PROMPT_USERID, true, FIELD_USER_ID);
            SignOnResponse secondClause = redisplay(ORACLE_MSG_PROMPT_ENTRY, true, FIELD_ENTRY);

            assertThat(firstClause.message()).isEqualTo(ORACLE_MSG_PROMPT_USERID);
            assertThat(firstClause.focusScreenFieldId()).isEqualTo(FIELD_USER_ID);
            assertThat(secondClause.message()).isEqualTo(ORACLE_MSG_PROMPT_ENTRY);
            assertThat(secondClause.focusScreenFieldId()).isEqualTo(FIELD_ENTRY);
            assertThat(firstClause).isNotEqualTo(secondClause);
        }

        @Test
        @DisplayName("fits every text inside the narrower rendered width, so none was ever truncated")
        void fitsEveryTextInsideTheRenderedWidth() {
            List<String> texts = List.of(
                    ORACLE_MSG_PROMPT_USERID,
                    ORACLE_MSG_PROMPT_ENTRY,
                    ORACLE_MSG_COMPARISON_FAILED,
                    ORACLE_MSG_USER_NOT_FOUND,
                    ORACLE_MSG_UNABLE_TO_VERIFY,
                    ORACLE_MSG_THANK_YOU_STORED,
                    ORACLE_MSG_INVALID_KEY_STORED);

            assertThat(ORACLE_MESSAGE_WIDTH).isGreaterThan(ORACLE_SCREEN_MESSAGE_WIDTH);
            assertThat(texts).allSatisfy(text -> assertThat(text.length())
                    .as("[%s] is short enough that the two-character difference cannot bite", text)
                    .isLessThanOrEqualTo(ORACLE_SCREEN_MESSAGE_WIDTH));
        }
    }

    @Nested
    @DisplayName("The destination is a declarative value, not a decision")
    class DeclarativeRouting {
        @Test
        @DisplayName("carries the administrative destination and the main-menu destination as data")
        void carriesBothDestinationsAsData() {
            SignOnResponse administrator = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);
            SignOnResponse standardUser = successfulSignOn(CODE_STANDARD_USER, ROUTE_USER_MENU);

            assertThat(administrator.nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);
            assertThat(standardUser.nextRoute()).isEqualTo(ROUTE_USER_MENU);
            assertThat(administrator.nextRoute())
                    .as("the two outcomes are distinguishable, which is the whole point of carrying "
                            + "the destination at all")
                    .isNotEqualTo(standardUser.nextRoute());
        }

        @Test
        @DisplayName("routes every code other than the administrator code to the main menu")
        void routesEveryOtherCodeToTheMainMenu() {
            List<String> everythingElse = List.of(CODE_STANDARD_USER, CODE_UNDECLARED, "a", " ");

            assertThat(everythingElse).allSatisfy(code -> {
                SignOnResponse response = successfulSignOn(code, ROUTE_USER_MENU);

                assertThat(response.userType())
                        .as("code [%s] survives the round trip rather than being erased", code)
                        .isEqualTo(code);
                assertThat(response.nextRoute())
                        .as("code [%s] still reaches the main menu", code)
                        .isEqualTo(ROUTE_USER_MENU);
                assertThat(response.generalError())
                        .as("code [%s] is not an error", code)
                        .isFalse();
                assertThat(response.message())
                        .as("code [%s] produces no message", code)
                        .isNull();
            });
        }

        @Test
        @DisplayName("carries a destination from no vocabulary at all, so it cannot be an enumeration")
        void carriesADestinationFromNoVocabulary() throws JsonProcessingException {
            String notAnyKnownDestination = "this-is-not-a-destination-any-vocabulary-declares";

            SignOnResponse response = successfulSignOn(CODE_ADMINISTRATOR, notAnyKnownDestination);
            JsonNode payload = payloadOf(response);

            assertThat(response.nextRoute()).isEqualTo(notAnyKnownDestination);
            assertThat(payload.get("nextRoute").isTextual())
                    .as("published as text, never as an enumerated constant name or an ordinal")
                    .isTrue();
            assertThat(payload.get("nextRoute").asText()).isEqualTo(notAnyKnownDestination);
            assertThat(violationsOf(response))
                    .as("an opaque value is not validated against a vocabulary")
                    .isEmpty();
        }

        @Test
        @DisplayName("leaves the destination unbounded, unlike every screen-derived component")
        void leavesTheDestinationUnbounded() {
            String farLongerThanAnyScreenField = valueOfLength(4_000);

            SignOnResponse response = new SignOnResponse(null, false, null,
                    farLongerThanAnyScreenField, null, null, null, null, null, null, null, null,
                    null, null, null);

            assertThat(violationsOf(response))
                    .as("no declared width applies to the destination, because it is not a screen "
                            + "field - the navigation service owns its shape")
                    .isEmpty();
            assertThat(response.nextRoute()).hasSize(4_000);
        }

        @Test
        @DisplayName("carries a mismatched code and destination faithfully, so it performs no dispatch")
        void carriesAMismatchedPairFaithfully() {
            SignOnResponse mismatched = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_USER_MENU);

            assertThat(mismatched.userType()).isEqualTo(CODE_ADMINISTRATOR);
            assertThat(mismatched.nextRoute())
                    .as("the destination is echoed exactly as supplied, mismatch and all")
                    .isEqualTo(ROUTE_USER_MENU)
                    .isNotEqualTo(ROUTE_ADMIN_MENU);
        }

        @Test
        @DisplayName("keeps the destination independent of the code, so neither derives the other")
        void keepsTheDestinationIndependentOfTheCode() {
            SignOnResponse withAdminCode = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);
            SignOnResponse withUserCode = successfulSignOn(CODE_STANDARD_USER, ROUTE_ADMIN_MENU);

            assertThat(withAdminCode.nextRoute())
                    .as("changing only the code leaves the destination exactly where it was put")
                    .isEqualTo(withUserCode.nextRoute())
                    .isEqualTo(ROUTE_ADMIN_MENU);
            assertThat(withAdminCode.userType()).isNotEqualTo(withUserCode.userType());
        }

        @Test
        @DisplayName("nominates no destination on any arm that redisplays the sign-on screen")
        void nominatesNoDestinationOnARedisplay() throws JsonProcessingException {
            List<SignOnResponse> redisplays = List.of(comparisonFailedArm(), userNotFoundArm(),
                    unableToVerifyArm(), unmappedKeyArm());

            assertThat(redisplays).allSatisfy(response -> {
                assertThat(response.nextRoute()).isNull();
                assertThat(response.navigationContext())
                        .as("and no successor state either, since the screen is not being left")
                        .isNull();
            });
            assertThat(payloadOf(comparisonFailedArm()).has("nextRoute"))
                    .as("an absent destination is omitted from the payload, not sent as a null")
                    .isFalse();
        }

        @Test
        @DisplayName("publishes only its own identity as a constant, never a destination")
        void publishesOnlyItsOwnIdentityAsAConstant() {
            assertThat(SignOnResponse.TRANSACTION_NAME)
                    .as("this transaction's own identifier, which the screen echoes back")
                    .isEqualTo("CC00")
                    .hasSize(ORACLE_TRANSACTION_NAME_WIDTH);
            assertThat(SignOnResponse.PROGRAM_NAME)
                    .isEqualTo("COSGN00C")
                    .hasSize(ORACLE_EIGHT_CHARACTER_WIDTH);
            assertThat(SignOnResponse.TRANSACTION_NAME)
                    .as("neither destination the two-way decision reaches is declared here")
                    .isNotEqualTo(MenuResponse.ADMIN_MENU_TRANSACTION_NAME)
                    .isNotEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME);
            assertThat(SignOnResponse.PROGRAM_NAME)
                    .isNotEqualTo(MenuResponse.ADMIN_MENU_PROGRAM_NAME)
                    .isNotEqualTo(MenuResponse.USER_MENU_PROGRAM_NAME);
        }
    }

    @Nested
    @DisplayName("The user type declares exactly two codes and never throws")
    class UserTypeContract {
        @Test
        @DisplayName("declares exactly two constants and no synthetic fallback")
        void declaresExactlyTwoConstants() {
            List<String> constantNames = Arrays.stream(UserType.values())
                    .map(UserType::name)
                    .toList();

            assertThat(UserType.values())
                    .as("the estate declares two codes, so the enumeration has two constants")
                    .hasSize(2);
            assertThat(constantNames).containsExactly("ADMIN", "USER");
            assertThat(constantNames)
                    .as("no synthetic third state, because the legacy has no third branch")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("carries the two declared codes, one character each")
        void carriesTheTwoDeclaredCodes() {
            assertThat(UserType.ADMIN.getCode())
                    .isEqualTo(CODE_ADMINISTRATOR)
                    .hasSize(ORACLE_USER_TYPE_WIDTH);
            assertThat(UserType.USER.getCode())
                    .isEqualTo(CODE_STANDARD_USER)
                    .hasSize(ORACLE_USER_TYPE_WIDTH);
            assertThat(Arrays.stream(UserType.values()).map(UserType::getCode).toList())
                    .containsExactly(CODE_ADMINISTRATOR, CODE_STANDARD_USER);
        }

        @Test
        @DisplayName("answers the administrator predicate for the administrator constant only")
        void answersTheAdministratorPredicateForTheAdministratorOnly() {
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
            assertThat(UserType.USER.isAdmin()).isFalse();
            assertThat(Arrays.stream(UserType.values()).filter(UserType::isAdmin).toList())
                    .as("exactly one constant reaches the administrative menu")
                    .containsExactly(UserType.ADMIN);
        }

        @Test
        @DisplayName("resolves each declared code and applies no case fold")
        void resolvesEachDeclaredCodeAndAppliesNoCaseFold() {
            assertThat(UserType.fromCode(CODE_ADMINISTRATOR)).contains(UserType.ADMIN);
            assertThat(UserType.fromCode(CODE_STANDARD_USER)).contains(UserType.USER);
            assertThat(UserType.fromCode("a"))
                    .as("the program compares the raw character and folds no case, so neither does "
                            + "the lookup - a lower-case administrator character is not an "
                            + "administrator")
                    .isEmpty();
            assertThat(UserType.fromCode("u")).isEmpty();
        }

        @Test
        @DisplayName("returns an absent result rather than throwing, for every unresolvable code")
        void returnsAnAbsentResultRatherThanThrowing() {
            assertThatCode(() -> UserType.fromCode(CODE_UNDECLARED))
                    .as("an unrecognised code is a legitimate outcome, not a failure")
                    .doesNotThrowAnyException();
            assertThatCode(() -> UserType.fromCode(null)).doesNotThrowAnyException();

            assertThat(UserType.fromCode(CODE_UNDECLARED)).isEmpty();
            assertThat(UserType.fromCode(null)).isEmpty();
            assertThat(UserType.fromCode("")).isEmpty();
            assertThat(UserType.fromCode(" ")).isEmpty();
            assertThat(UserType.fromCode("AU"))
                    .as("a wrong-length value resolves to nothing rather than to its first character")
                    .isEmpty();
        }

        @Test
        @DisplayName("yields a non-administrator outcome for an absent or unrecognised code")
        void yieldsANonAdministratorOutcomeForAnUnrecognisedCode() {
            List<String> unresolvable = Arrays.asList(CODE_UNDECLARED, "a", "", " ", "AU", null);

            assertThat(unresolvable).allSatisfy(code -> assertThat(
                    UserType.fromCode(code).map(UserType::isAdmin).orElse(false))
                    .as("code [%s] is not an administrator and raises nothing", code)
                    .isFalse());
            assertThat(UserType.fromCode(CODE_ADMINISTRATOR).map(UserType::isAdmin).orElse(false))
                    .as("only the declared administrator code answers true")
                    .isTrue();
        }

        @Test
        @DisplayName("is never published on the wire as a constant name, only as the raw code")
        void isNeverPublishedAsAConstantName() throws JsonProcessingException {
            JsonNode payload = payloadOf(successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU));

            assertThat(payload.get("userType").asText())
                    .isEqualTo(CODE_ADMINISTRATOR)
                    .hasSize(ORACLE_USER_TYPE_WIDTH)
                    .isNotEqualTo(UserType.ADMIN.name())
                    .isNotEqualTo(UserType.USER.name());
            assertThat(payload.get("userType").isTextual())
                    .as("text, never an ordinal - an ordinal would make a code an index it never was")
                    .isTrue();
        }

        @Test
        @DisplayName("is interpreted through the successor state, not by the response itself")
        void isInterpretedThroughTheSuccessorState() {
            SignOnResponse administrator = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);
            SignOnResponse undeclared = successfulSignOn(CODE_UNDECLARED, ROUTE_USER_MENU);

            assertThat(administrator.navigationContext().resolvedUserType())
                    .contains(UserType.ADMIN);
            assertThat(administrator.navigationContext().echoesAdministratorCode()).isTrue();
            assertThat(undeclared.navigationContext().resolvedUserType())
                    .as("an undeclared code resolves to nothing and still travels intact")
                    .isEmpty();
            assertThat(undeclared.navigationContext().echoesAdministratorCode()).isFalse();
            assertThat(undeclared.userType()).isEqualTo(CODE_UNDECLARED);
        }

        @Test
        @DisplayName("spells the code the same way the successor state spells it")
        void spellsTheCodeTheSameWayTheSuccessorStateSpellsIt() throws JsonProcessingException {
            JsonNode payload = payloadOf(successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU));

            assertThat(payload.get("userType").asText())
                    .as("one wire vocabulary, so a client that echoes what it received sends back "
                            + "the same characters")
                    .isEqualTo(payload.get("navigationContext").get("userType").asText());
            assertThat(payload.get("userId").asText())
                    .isEqualTo(payload.get("navigationContext").get("userId").asText());
        }
    }

    @Nested
    @DisplayName("The successor state is carried whole, with leading zeros intact")
    class EchoedNavigationState {
        @Test
        @DisplayName("carries the successor state a successful sign-on assembled")
        void carriesTheSuccessorState() {
            SignOnResponse response = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);
            NavigationContext state = response.navigationContext();

            assertThat(state).isNotNull();
            assertThat(state.fromTransactionId()).isEqualTo(SignOnResponse.TRANSACTION_NAME);
            assertThat(state.fromProgram()).isEqualTo(SignOnResponse.PROGRAM_NAME);
            assertThat(state.userId()).isEqualTo(SAMPLE_USER_ID);
            assertThat(state.userType()).isEqualTo(CODE_ADMINISTRATOR);
            assertThat(state.firstEntry())
                    .as("a successful sign-on enters the destination screen for the first time, which "
                            + "is what suppresses field-level error decoration there")
                    .isTrue();
            assertThat(state.reEntry()).isFalse();
        }

        @Test
        @DisplayName("preserves a leading-zero account identifier as text, never as a number")
        void preservesALeadingZeroAccountIdentifierAsText() throws JsonProcessingException {
            SignOnResponse response = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);

            JsonNode state = payloadOf(response).get("navigationContext");

            assertThat(state.get("accountId").isTextual())
                    .as("published as text, so the rendered leading zeros are part of the value")
                    .isTrue();
            assertThat(state.get("accountId").asText())
                    .isEqualTo("00000000001")
                    .hasSize(ORACLE_ACCOUNT_ID_WIDTH)
                    .startsWith("0");
            assertThat(state.get("accountId").isNumber())
                    .as("a numeric type would have discarded ten leading zeros with no way back")
                    .isFalse();
        }

        @Test
        @DisplayName("preserves the leading-zero customer identifier and card number as text too")
        void preservesTheOtherLeadingZeroIdentifiersAsText() throws JsonProcessingException {
            JsonNode state = payloadOf(successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU))
                    .get("navigationContext");

            assertThat(state.get("customerId").isTextual()).isTrue();
            assertThat(state.get("customerId").asText())
                    .isEqualTo("000000001")
                    .hasSize(ORACLE_CUSTOMER_ID_WIDTH);
            assertThat(state.get("cardNumber").isTextual()).isTrue();
            assertThat(state.get("cardNumber").asText())
                    .isEqualTo("0000000000000001")
                    .hasSize(ORACLE_CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("round-trips the whole successor state without losing a component")
        void roundTripsTheWholeSuccessorState() throws JsonProcessingException {
            SignOnResponse original = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);

            SignOnResponse rebuilt = roundTrip(original);

            assertThat(rebuilt.navigationContext())
                    .as("the nested state compares by value, so a lost component would show here")
                    .isEqualTo(original.navigationContext());
            assertThat(rebuilt).isEqualTo(original);
            assertThat(rebuilt.navigationContext().accountId()).isEqualTo("00000000001");
            assertThat(rebuilt.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.ENTER);
        }

        @Test
        @DisplayName("keeps the space-padded customer name parts padded, because the padding is the "
                + "value")
        void keepsTheCustomerNamePartsPadded() throws JsonProcessingException {
            JsonNode state = payloadOf(successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU))
                    .get("navigationContext");

            assertThat(state.get("customerFirstName").asText())
                    .as("the legacy area space-pads each name part, and that padding travels")
                    .isEqualTo("FIRST                    ")
                    .hasSize(25);
            assertThat(state.get("customerMiddleName").asText()).hasSize(25);
            assertThat(state.get("customerLastName").asText()).hasSize(25);
        }

        @Test
        @DisplayName("is absent on every arm that redisplays the screen rather than leaving it")
        void isAbsentOnEveryRedisplay() throws JsonProcessingException {
            assertThat(comparisonFailedArm().navigationContext()).isNull();
            assertThat(payloadOf(comparisonFailedArm()).has("navigationContext"))
                    .as("an absent successor state is omitted, not published as a null")
                    .isFalse();
        }

        @Test
        @DisplayName("tolerates the wholly empty successor state, which is a real legacy state")
        void toleratesTheWhollyEmptySuccessorState() throws JsonProcessingException {
            SignOnResponse response = new SignOnResponse(null, false, null, null,
                    NavigationContext.empty(), null, null, null, null, null, null, null, null, null,
                    null);

            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(response.navigationContext().firstEntry())
                    .as("an empty area is a first entry rather than an unknown state")
                    .isTrue();
            assertThat(response.navigationContext().resolvedUserType()).isEmpty();
            assertThat(violationsOf(response)).isEmpty();
            assertThat(payloadOf(response).get("navigationContext").isObject())
                    .as("an empty state is still published, because the reference is present")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("No credential and no terminal furniture reaches a client")
    class NoCredentialOrScreenFurniture {
        @Test
        @DisplayName("publishes exactly the fifteen components, in declaration order and no others")
        void publishesExactlyTheFifteenComponents() throws JsonProcessingException {
            SignOnResponse fullyPopulated = fullyPopulatedWith(ORACLE_MSG_COMPARISON_FAILED);

            List<String> published = propertyNamesOf(payloadOf(fullyPopulated));

            assertThat(published)
                    .as("the component inventory a client observes, pinned so that widening it "
                            + "cannot pass unnoticed")
                    .containsExactlyElementsOf(ORACLE_PUBLISHED_PROPERTIES)
                    .hasSize(15);
        }

        @Test
        @DisplayName("publishes no credential component under any spelling")
        void publishesNoCredentialComponentUnderAnySpelling() throws JsonProcessingException {
            SignOnResponse fullyPopulated = fullyPopulatedWith(ORACLE_MSG_USER_NOT_FOUND);
            List<String> credentialSpellings = List.of(
                    "password", "Password", "passwd", "Passwd", "pwd", "Pwd",
                    "credential", "Credential", "secret", "Secret", "token", "Token",
                    "accessToken", "refreshToken", "bearer", "Bearer", "signingKey",
                    "passwordHash", "hashedPassword", "salt", "workFactor");

            String document = documentOf(fullyPopulated);

            assertThat(document)
                    .as("no key and no value in the whole document is named for a credential")
                    .doesNotContain(credentialSpellings.toArray(new String[0]));
            assertThat(propertyNamesOf(payloadOf(fullyPopulated)))
                    .as("and the key set - the airtight statement, since a component that is not "
                            + "published cannot leak")
                    .doesNotContainAnyElementsOf(credentialSpellings);
        }

        @Test
        @DisplayName("distinguishes wording that names the entry field from a credential value")
        void distinguishesWordingFromAValue() throws JsonProcessingException {
            SignOnResponse response = comparisonFailedArm();

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("message").asText())
                    .as("mandated wording, reproduced verbatim because it is an external interface")
                    .isEqualTo(ORACLE_MSG_COMPARISON_FAILED);
            assertThat(payload.get("focusScreenFieldId").asText())
                    .as("the mapset's own symbolic field name, which is an identity and not a value")
                    .isEqualTo(FIELD_ENTRY)
                    .hasSizeLessThanOrEqualTo(ORACLE_SCREEN_FIELD_ID_WIDTH);
            assertThat(propertyNamesOf(payload))
                    .as("and still no component named for a credential")
                    .isSubsetOf(ORACLE_PUBLISHED_PROPERTIES);
        }

        @Test
        @DisplayName("echoes the identifier while still publishing no credential beside it")
        void echoesTheIdentifierWhilePublishingNoCredential() throws JsonProcessingException {
            JsonNode payload = payloadOf(comparisonFailedArm());

            assertThat(payload.get("userId").asText())
                    .as("the map's safe output item, echoed so a redisplay can be rebuilt")
                    .isEqualTo(SAMPLE_USER_ID)
                    .hasSize(ORACLE_USER_ID_WIDTH);
            assertThat(propertyNamesOf(payload))
                    .as("the item declared beside it, at the same width, has no counterpart here")
                    .contains("userId")
                    .isSubsetOf(ORACLE_PUBLISHED_PROPERTIES);
        }

        @Test
        @DisplayName("publishes no per-item control byte for any of the eleven map value items")
        void publishesNoPerItemControlByte() throws JsonProcessingException {
            List<String> published = propertyNamesOf(payloadOf(
                    successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU)));

            for (String item : ORACLE_MAP_ITEM_NAMES) {
                for (String suffix : ORACLE_CONTROL_BYTE_SUFFIXES) {
                    assertThat(published)
                            .as("the generated control byte %s%s is plumbing, not a value", item,
                                    suffix)
                            .doesNotContain(item + suffix);
                }
            }
        }

        @Test
        @DisplayName("publishes no terminal filler, cursor coordinate, attribute or edit mask")
        void publishesNoTerminalFillerCoordinateAttributeOrMask() throws JsonProcessingException {
            SignOnResponse response = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);
            List<String> furniture = List.of(
                    "filler", "FILLER", "tioa", "cursor", "cursorPosition", "row", "column",
                    "attribute", "attributeByte", "colour", "color", "highlight", "protection",
                    "DFHRED", "DFHGREEN", "DFHBMASB", "editMask", "screenMask", "mapName",
                    "mapsetName", "terminalId", "screenRow", "screenColumn");

            assertThat(documentOf(response))
                    .as("no terminal mechanic of any kind reaches the wire")
                    .doesNotContain(furniture.toArray(new String[0]));
            assertThat(propertyNamesOf(payloadOf(response)))
                    .doesNotContainAnyElementsOf(furniture);
        }

        @Test
        @DisplayName("reduces cursor placement to a field identity of at most seven characters")
        void reducesCursorPlacementToAFieldIdentity() {
            SignOnResponse atTheBound = new SignOnResponse(null, false,
                    valueOfLength(ORACLE_SCREEN_FIELD_ID_WIDTH), null, null, null, null, null, null,
                    null, null, null, null, null, null);
            SignOnResponse overTheBound = new SignOnResponse(null, false,
                    valueOfLength(ORACLE_SCREEN_FIELD_ID_WIDTH + 1), null, null, null, null, null,
                    null, null, null, null, null, null, null);

            assertThat(comparisonFailedArm().focusScreenFieldId())
                    .as("the mapset's own symbolic field name, and nothing else")
                    .isEqualTo(FIELD_ENTRY);
            assertThat(violationsOf(atTheBound)).isEmpty();
            assertThat(violatedComponentsOf(overTheBound))
                    .containsExactly("focusScreenFieldId");
        }

        @Test
        @DisplayName("carries the rendered date and time as plain bounded text, not as temporal values")
        void carriesTheRenderedDateAndTimeAsPlainText() throws JsonProcessingException {
            SignOnResponse response = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("currentDate").isTextual()).isTrue();
            assertThat(payload.get("currentDate").asText())
                    .isEqualTo(SAMPLE_CURRENT_DATE)
                    .hasSize(ORACLE_EIGHT_CHARACTER_WIDTH);

            assertThat(payload.get("currentTime").isTextual()).isTrue();
            assertThat(payload.get("currentTime").asText())
                    .isEqualTo(SAMPLE_CURRENT_TIME)
                    .hasSize(ORACLE_CURRENT_TIME_WIDTH)
                    .endsWith(ORACLE_FIELD_PAD);
            assertThat(roundTrip(response).currentTime())
                    .as("the pad survives a full round trip untrimmed")
                    .isEqualTo(SAMPLE_CURRENT_TIME);
        }

        @Test
        @DisplayName("keeps the nine-character rendered time as this mapset's own anomaly")
        void keepsTheNineCharacterTimeAsAnAnomaly() {
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH)
                    .as("the only nine-character value item across the estate's seventeen mapsets")
                    .isEqualTo(ORACLE_CURRENT_TIME_WIDTH)
                    .isNotEqualTo(SignOnResponse.CURRENT_DATE_LENGTH);
            assertThat(MenuResponse.CURRENT_TIME_WIDTH)
                    .as("every other mapset renders the time one character narrower")
                    .isEqualTo(ORACLE_EIGHT_CHARACTER_WIDTH);
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH)
                    .as("so the anomaly is preserved rather than normalised away")
                    .isNotEqualTo(MenuResponse.CURRENT_TIME_WIDTH);
        }

        @Test
        @DisplayName("publishes a body that is not a problem document")
        void publishesABodyThatIsNotAProblemDocument() throws JsonProcessingException {
            String document = documentOf(comparisonFailedArm());

            assertThat(document)
                    .as("the standard problem-document vocabulary is deliberately not in use here")
                    .doesNotContain("\"type\"")
                    .doesNotContain("\"title\"")
                    .doesNotContain("\"status\"")
                    .doesNotContain("\"detail\"")
                    .doesNotContain("\"instance\"")
                    .doesNotContain("problem+json");
            assertThat(propertyNamesOf(payloadOf(comparisonFailedArm())))
                    .doesNotContain("type", "title", "status", "detail", "instance");
        }
    }

    @Nested
    @DisplayName("Validation measures width and nothing else")
    class ValidationIsMeasurementOnly {
        @Test
        @DisplayName("raises no violation for a response with every component absent")
        void raisesNoViolationForAWhollyAbsentResponse() {
            assertThat(violationsOf(whollyAbsent()))
                    .as("no presence constraint fires anywhere, because every component is optional "
                            + "in the legacy sense")
                    .isEmpty();
        }

        @Test
        @DisplayName("raises no violation for empty or space-filled values either")
        void raisesNoViolationForEmptyOrSpaceFilledValues() {
            SignOnResponse allEmpty = new SignOnResponse("", false, "", "", null, "", "", "", "",
                    "", "", "", "", "", "");
            SignOnResponse allSpaces = new SignOnResponse(" ", false, " ", " ", null, " ", " ", " ",
                    " ", " ", " ", " ", " ", " ", " ");

            assertThat(violationsOf(allEmpty))
                    .as("no blank or emptiness constraint fires, so an empty screen field is legal")
                    .isEmpty();
            assertThat(violationsOf(allSpaces))
                    .as("nor does one fire for a space-filled field, which is what the screen sent")
                    .isEmpty();
            assertThat(allSpaces.userType())
                    .as("and the space is carried, not folded away")
                    .isEqualTo(" ");
        }

        @Test
        @DisplayName("raises no format or pattern violation for values a screen field could never match")
        void raisesNoFormatViolationForUnpatternedValues() {
            SignOnResponse unpatterned = new SignOnResponse("!!!", false, "???", "@@@", null,
                    "########", "*", "&&&&", "%%%%%%%%", "((((", "))))", "++++++++", "=========",
                    "~~~~~~~~", "^^^^");

            assertThat(violationsOf(unpatterned))
                    .as("no pattern, digit, minimum or maximum constraint is declared, so shape is "
                            + "never judged - only length is")
                    .isEmpty();
        }

        @Test
        @DisplayName("measures each screen-derived component at its own declared width")
        void measuresEachComponentAtItsOwnDeclaredWidth() {
            assertThat(violatedComponentsOf(messageOnly(valueOfLength(ORACLE_MESSAGE_WIDTH), false)))
                    .isEmpty();
            assertThat(violatedComponentsOf(
                    messageOnly(valueOfLength(ORACLE_MESSAGE_WIDTH + 1), false)))
                    .containsExactly("message");
            assertThat(violatedComponentsOf(withUserId(valueOfLength(ORACLE_USER_ID_WIDTH))))
                    .isEmpty();
            assertThat(violatedComponentsOf(withUserId(valueOfLength(ORACLE_USER_ID_WIDTH + 1))))
                    .containsExactly("userId");
            assertThat(violatedComponentsOf(withUserType(valueOfLength(ORACLE_USER_TYPE_WIDTH))))
                    .isEmpty();
            assertThat(violatedComponentsOf(withUserType(valueOfLength(ORACLE_USER_TYPE_WIDTH + 1))))
                    .containsExactly("userType");
        }

        @Test
        @DisplayName("measures the header components at their widths, and the two titles at forty")
        void measuresTheHeaderComponentsAtTheirWidths() {
            SignOnResponse atEveryBound = new SignOnResponse(null, false, null, null, null, null,
                    null, valueOfLength(ORACLE_TRANSACTION_NAME_WIDTH),
                    valueOfLength(ORACLE_EIGHT_CHARACTER_WIDTH),
                    valueOfLength(ORACLE_SCREEN_TITLE_WIDTH),
                    valueOfLength(ORACLE_SCREEN_TITLE_WIDTH),
                    valueOfLength(ORACLE_EIGHT_CHARACTER_WIDTH),
                    valueOfLength(ORACLE_CURRENT_TIME_WIDTH),
                    valueOfLength(ORACLE_EIGHT_CHARACTER_WIDTH),
                    valueOfLength(ORACLE_EIGHT_CHARACTER_WIDTH));
            SignOnResponse overEveryBound = new SignOnResponse(null, false, null, null, null, null,
                    null, valueOfLength(ORACLE_TRANSACTION_NAME_WIDTH + 1),
                    valueOfLength(ORACLE_EIGHT_CHARACTER_WIDTH + 1),
                    valueOfLength(ORACLE_SCREEN_TITLE_WIDTH + 1),
                    valueOfLength(ORACLE_SCREEN_TITLE_WIDTH + 1),
                    valueOfLength(ORACLE_EIGHT_CHARACTER_WIDTH + 1),
                    valueOfLength(ORACLE_CURRENT_TIME_WIDTH + 1),
                    valueOfLength(ORACLE_EIGHT_CHARACTER_WIDTH + 1),
                    valueOfLength(ORACLE_EIGHT_CHARACTER_WIDTH + 1));

            assertThat(violationsOf(atEveryBound))
                    .as("every declared width admits exactly its own length")
                    .isEmpty();
            assertThat(violatedComponentsOf(overEveryBound))
                    .as("and one character more is measured, on each of the eight independently")
                    .containsExactlyInAnyOrder("transactionName", "programName", "title01",
                            "title02", "currentDate", "currentTime", "applicationId", "systemId");
        }

        @Test
        @DisplayName("never alters a value while measuring it, even when the measurement fails")
        void neverAltersAValueWhileMeasuringIt() {
            String overTheBound = valueOfLength(ORACLE_MESSAGE_WIDTH + 1);
            SignOnResponse response = messageOnly(overTheBound, false);

            assertThat(violatedComponentsOf(response)).containsExactly("message");
            assertThat(response.message())
                    .as("a failed measurement neither truncates nor rewrites the value it measured")
                    .isEqualTo(overTheBound)
                    .hasSize(ORACLE_MESSAGE_WIDTH + 1);
        }

        @Test
        @DisplayName("does not cascade into the successor state, which measures itself")
        void doesNotCascadeIntoTheSuccessorState() {
            SignOnResponse response = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);

            assertThat(violationsOf(response))
                    .as("a well-formed successor state raises nothing either way")
                    .isEmpty();
            assertThat(response.navigationContext().userId()).isEqualTo(SAMPLE_USER_ID);
        }
    }

    @Nested
    @DisplayName("Value semantics: nullable, immutable, omitting and tolerant")
    class ValueSemantics {
        @Test
        @DisplayName("tolerates a null in every component, one component at a time")
        void toleratesANullInEveryComponent() {
            SignOnResponse absent = whollyAbsent();

            assertThat(absent.message()).isNull();
            assertThat(absent.focusScreenFieldId()).isNull();
            assertThat(absent.nextRoute()).isNull();
            assertThat(absent.navigationContext()).isNull();
            assertThat(absent.userId()).isNull();
            assertThat(absent.userType()).isNull();
            assertThat(absent.transactionName()).isNull();
            assertThat(absent.programName()).isNull();
            assertThat(absent.title01()).isNull();
            assertThat(absent.title02()).isNull();
            assertThat(absent.currentDate()).isNull();
            assertThat(absent.currentTime()).isNull();
            assertThat(absent.applicationId()).isNull();
            assertThat(absent.systemId()).isNull();
            assertThat(absent.generalError())
                    .as("the flag is the one component that cannot be absent, being a primitive")
                    .isFalse();
        }

        @Test
        @DisplayName("returns every component exactly as it was constructed")
        void returnsEveryComponentExactlyAsConstructed() {
            SignOnResponse response = fullyPopulatedWith(ORACLE_MSG_USER_NOT_FOUND);

            assertThat(response.message()).isEqualTo(ORACLE_MSG_USER_NOT_FOUND);
            assertThat(response.generalError()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_ENTRY);
            assertThat(response.nextRoute()).isEqualTo(ROUTE_ADMIN_MENU);
            assertThat(response.navigationContext())
                    .isEqualTo(successorState(CODE_ADMINISTRATOR));
            assertThat(response.userId()).isEqualTo(SAMPLE_USER_ID);
            assertThat(response.userType()).isEqualTo(CODE_ADMINISTRATOR);
            assertThat(response.transactionName()).isEqualTo(SignOnResponse.TRANSACTION_NAME);
            assertThat(response.programName()).isEqualTo(SignOnResponse.PROGRAM_NAME);
            assertThat(response.title01()).isEqualTo(SAMPLE_TITLE_01);
            assertThat(response.title02()).isEqualTo(SAMPLE_TITLE_02);
            assertThat(response.currentDate()).isEqualTo(SAMPLE_CURRENT_DATE);
            assertThat(response.currentTime()).isEqualTo(SAMPLE_CURRENT_TIME);
            assertThat(response.applicationId()).isEqualTo(SAMPLE_APPLICATION_ID);
            assertThat(response.systemId()).isEqualTo(SAMPLE_SYSTEM_ID);
        }

        @Test
        @DisplayName("returns the value it was built from on every accessor, however often it is asked")
        void returnsAStableValueFromEveryAccessor() {
            // Each expectation is the independent oracle this suite already carries - the stored-width
            // courtesy message, the successor conversation state, the sample identifier and the raised
            // flag - rather than a second call of the accessor under test. Comparing an accessor with
            // itself would pass on a stable but wrong value and would protect no part of the sign-on
            // contract.
            SignOnResponse response = fullyPopulatedWith(ORACLE_MSG_THANK_YOU_STORED);

            for (int read = 0; read < 2; read++) {
                assertThat(response.message()).isEqualTo(ORACLE_MSG_THANK_YOU_STORED);
                assertThat(response.navigationContext())
                        .isEqualTo(successorState(CODE_ADMINISTRATOR));
                assertThat(response.userId()).isEqualTo(SAMPLE_USER_ID);
                assertThat(response.generalError())
                        .as("the arm this instance represents raises the flag")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("is immutable by construction: deriving a new nested state leaves it untouched")
        void isImmutableByConstruction() {
            SignOnResponse response = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);
            NavigationContext held = response.navigationContext();

            NavigationContext derived = held.withReEntry();
            NavigationContext reconciled =
                    held.reconciledWith(SAMPLE_USER_ID, UserType.USER);

            assertThat(derived)
                    .as("deriving produces a new state rather than mutating the held one")
                    .isNotSameAs(held)
                    .isNotEqualTo(held);
            assertThat(reconciled).isNotSameAs(held);
            assertThat(response.navigationContext())
                    .as("and the response still says exactly what it said before")
                    .isSameAs(held)
                    .isEqualTo(successorState(CODE_ADMINISTRATOR));
            assertThat(response.navigationContext().firstEntry()).isTrue();
            assertThat(derived.reEntry())
                    .as("the derived state is the one that changed")
                    .isTrue();
            assertThat(reconciled.userType()).isEqualTo(CODE_STANDARD_USER);
        }

        @Test
        @DisplayName("compares and hashes by value, so echoed state survives a turn")
        void comparesAndHashesByValue() {
            SignOnResponse one = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);
            SignOnResponse identical = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU);
            SignOnResponse differentRoute = successfulSignOn(CODE_ADMINISTRATOR, ROUTE_USER_MENU);

            assertThat(one)
                    .isEqualTo(identical)
                    .hasSameHashCodeAs(identical)
                    .isNotSameAs(identical);
            assertThat(one).isNotEqualTo(differentRoute);
            assertThat(one).isNotEqualTo(null);
            assertThat(one).isEqualTo(one);
            assertThat(whollyAbsent()).isEqualTo(whollyAbsent());
        }

        @Test
        @DisplayName("renders itself informatively, which is safe because no component is regulated")
        void rendersItselfInformatively() {
            SignOnResponse response = comparisonFailedArm();

            String rendered = response.toString();

            assertThat(rendered)
                    .as("the screen text and the identifier are what make a rendering useful, and "
                            + "neither is regulated data")
                    .contains(ORACLE_MSG_COMPARISON_FAILED)
                    .contains(SAMPLE_USER_ID)
                    .contains("generalError=false");
            assertThat(successfulSignOn(CODE_ADMINISTRATOR, ROUTE_ADMIN_MENU).toString())
                    .as("the nested state withholds its own identifying values, so this rendering "
                            + "needs no override of its own")
                    .contains("REDACTED")
                    .doesNotContain("00000000001")
                    .doesNotContain("0000000000000001");
        }

        @Test
        @DisplayName("omits every absent component from the published payload")
        void omitsEveryAbsentComponent() throws JsonProcessingException {
            JsonNode payload = payloadOf(whollyAbsent());

            assertThat(propertyNamesOf(payload))
                    .as("absent components are omitted rather than published as nulls, which is the "
                            + "module-wide inclusion policy")
                    .containsExactly("generalError");
            assertThat(payload.has("message")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
            assertThat(payload.has("userId")).isFalse();
        }

        @Test
        @DisplayName("tolerates an unknown incoming property instead of rejecting the body")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            // Written as String.format with an explicit locale rather than the String.formatted
            // shorthand. Only %s appears here, so nothing in this particular body depends on the ambient
            // locale - but the shorthand accepts no locale at all, and LocaleDeterminismAuditTest
            // forbids it module-wide precisely so that nobody has to make that judgement per call site.
            String bodyWithAnUnknownKey = String.format(Locale.ROOT, """
                    {"message":"%s","generalError":false,"userId":"%s",\
                    "aKeyThisContractDoesNotDeclare":"ignored"}""",
                    ORACLE_MSG_COMPARISON_FAILED, SAMPLE_USER_ID);

            SignOnResponse rebuilt = moduleEquivalentMapper()
                    .readValue(bodyWithAnUnknownKey, SignOnResponse.class);

            assertThat(rebuilt.message()).isEqualTo(ORACLE_MSG_COMPARISON_FAILED);
            assertThat(rebuilt.generalError()).isFalse();
            assertThat(rebuilt.userId()).isEqualTo(SAMPLE_USER_ID);
            assertThat(rebuilt.nextRoute())
                    .as("keys the body omitted arrive absent rather than defaulted")
                    .isNull();
        }

        @Test
        @DisplayName("rebuilds a wholly absent response from a body carrying only the flag")
        void rebuildsAWhollyAbsentResponse() throws JsonProcessingException {
            SignOnResponse rebuilt = roundTrip(whollyAbsent());

            assertThat(rebuilt)
                    .as("the absent state round-trips to itself, so absence is representable on the "
                            + "wire and not merely in Java")
                    .isEqualTo(whollyAbsent());
            assertThat(rebuilt.generalError()).isFalse();
        }

        @Test
        @DisplayName("publishes every declared width as a constant that agrees with the artefacts")
        void publishesEveryDeclaredWidth() {
            assertThat(SignOnResponse.MESSAGE_LENGTH).isEqualTo(ORACLE_MESSAGE_WIDTH);
            assertThat(SignOnResponse.SCREEN_MESSAGE_LENGTH).isEqualTo(ORACLE_SCREEN_MESSAGE_WIDTH);
            assertThat(SignOnResponse.SCREEN_FIELD_ID_LENGTH)
                    .isEqualTo(ORACLE_SCREEN_FIELD_ID_WIDTH);
            assertThat(SignOnResponse.USER_ID_LENGTH).isEqualTo(ORACLE_USER_ID_WIDTH);
            assertThat(SignOnResponse.USER_TYPE_LENGTH).isEqualTo(ORACLE_USER_TYPE_WIDTH);
            assertThat(SignOnResponse.TRANSACTION_NAME_LENGTH)
                    .isEqualTo(ORACLE_TRANSACTION_NAME_WIDTH);
            assertThat(SignOnResponse.PROGRAM_NAME_LENGTH).isEqualTo(ORACLE_EIGHT_CHARACTER_WIDTH);
            assertThat(SignOnResponse.SCREEN_TITLE_LENGTH).isEqualTo(ORACLE_SCREEN_TITLE_WIDTH);
            assertThat(SignOnResponse.CURRENT_DATE_LENGTH).isEqualTo(ORACLE_EIGHT_CHARACTER_WIDTH);
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH).isEqualTo(ORACLE_CURRENT_TIME_WIDTH);
            assertThat(SignOnResponse.APPLICATION_ID_LENGTH)
                    .isEqualTo(ORACLE_EIGHT_CHARACTER_WIDTH);
            assertThat(SignOnResponse.SYSTEM_ID_LENGTH).isEqualTo(ORACLE_EIGHT_CHARACTER_WIDTH);
        }

        @Test
        @DisplayName("agrees with every sibling contract on the identifier, the code and the focus hint")
        void agreesWithEverySiblingContract() {
            assertThat(SignOnResponse.USER_ID_LENGTH)
                    .as("one width for the identifier, declared by each contract for itself")
                    .isEqualTo(NavigationContext.USER_ID_LENGTH)
                    .isEqualTo(UserRequest.USER_ID_LENGTH)
                    .isEqualTo(UserResponse.USER_ID_LENGTH);
            assertThat(SignOnResponse.USER_TYPE_LENGTH)
                    .isEqualTo(NavigationContext.USER_TYPE_LENGTH)
                    .isEqualTo(UserRequest.USER_TYPE_LENGTH)
                    .isEqualTo(UserResponse.USER_TYPE_LENGTH);
            assertThat(SignOnResponse.SCREEN_FIELD_ID_LENGTH)
                    .as("and one bound for a screen-field identity across every screen contract")
                    .isEqualTo(MenuResponse.SCREEN_FIELD_ID_WIDTH);
        }
    }

    private static SignOnResponse withUserId(String userId) {
        return new SignOnResponse(null, false, null, null, null, userId, null, null, null, null,
                null, null, null, null, null);
    }

    private static SignOnResponse withUserType(String userType) {
        return new SignOnResponse(null, false, null, null, null, null, userType, null, null, null,
                null, null, null, null, null);
    }
}
