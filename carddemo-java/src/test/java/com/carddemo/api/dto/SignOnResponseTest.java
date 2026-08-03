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
 * <p>A pure unit test. It starts no application context, opens no connection, launches no container
 * and touches no security type: it constructs the record directly and, where the wire shape is the
 * thing under test, serialises through a mapper configured by hand in this file to match the four
 * serialisation settings the module declares in {@code src/main/resources/application.yml} &mdash;
 * absent properties omitted, dates not written as timestamps, unknown incoming properties tolerated,
 * and plain rendering of arbitrary-precision numbers. Nothing here is shared with another test.
 *
 * <h2>Every expectation is an independent oracle</h2>
 *
 * <p>A test that reads its expectations out of the type it is testing asserts nothing. Every width,
 * every field identity and every message text below is therefore restated here from the legacy
 * artefacts rather than borrowed from the class under test: {@code app/cbl/COSGN00C.cbl} for the
 * behaviour and the five direct texts, {@code app/cpy-bms/COSGN00.CPY} for the symbolic map,
 * {@code app/cpy/CSMSG01Y.cpy} for the two shared texts, {@code app/cpy/COCOM01Y.cpy} for the
 * user-type codes and {@code app/cpy/CSUSR01Y.cpy} for their persisted origin. No generated
 * value-producer of any kind is used as the source of an expectation.
 *
 * <h2>The two properties this file owns</h2>
 *
 * <ol>
 *   <li><em>A failed credential comparison is not a general error.</em> The program raises its error
 *       flag on five of its eight outcomes and leaves it lowered on three, and the failed comparison
 *       is one of the three: that path composes a message and moves the cursor without assigning the
 *       flag at all. So "carries a message" and "is an error" are different facts, the flag must be
 *       its own explicit primitive, and it must never be derived from the presence of a message.
 *       {@link WrongCredentialArm} pins that down and {@link ErrorFlagAsymmetry} proves the
 *       asymmetry is modelled rather than accidental.</li>
 *   <li><em>The two shared texts are fifty characters, not forty-nine.</em> Each is written as a
 *       forty-nine-character literal inside a fifty-character field, so the stored value is the
 *       literal followed by one filling space. {@link PaddedCommonMessages} asserts that all fifty
 *       survive construction, serialisation and deserialisation with the trailing pad intact, and
 *       that the fifty-character family is never conflated with the unrelated forty-character title
 *       family declared in a different copybook.</li>
 * </ol>
 *
 * <h2>Why nothing here introspects the type</h2>
 *
 * <p>No component of this file interrogates the record's shape at run time. The component inventory, the
 * absence of a credential and the absence of terminal furniture are established from the
 * <em>serialised payload</em> of a fully populated instance, which is both the stronger statement and
 * the one a client can actually observe: a component that reaches no client cannot leak to one, and a
 * component that reaches a client appears as a property name. Immutability is likewise demonstrated
 * by construction &mdash; deriving a modified nested state leaves the original response untouched
 * &mdash; rather than by interrogating the class for setters.
 *
 * <p>Source checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Those identifiers are provenance prose for a
 * reader and are deliberately not declared as constants of this file.
 */
@DisplayName("SignOnResponse - the outbound contract of legacy transaction CC00")
class SignOnResponseTest {

    // -----------------------------------------------------------------------------------------
    // Independent oracles: the seven texts Gate 5 verifies, restated from the legacy artefacts.
    // -----------------------------------------------------------------------------------------

    /** Prompt for an empty user id, from line 120 of the program. Twenty-four characters. */
    private static final String ORACLE_MSG_PROMPT_USERID = "Please enter User ID ...";

    /** Prompt for an empty entry field, from line 125 of the program. Twenty-five characters. */
    private static final String ORACLE_MSG_PROMPT_ENTRY = "Please enter Password ...";

    /** Text for a failed comparison, from line 242 of the program. Twenty-nine characters. */
    private static final String ORACLE_MSG_COMPARISON_FAILED = "Wrong Password. Try again ...";

    /** Text for a missing record, from line 249 of the program. Twenty-nine characters. */
    private static final String ORACLE_MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /** Text for any other read failure, from line 254 of the program. Twenty-nine characters. */
    private static final String ORACLE_MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * The thank-you text exactly as the value clause of the shared-message copybook writes it:
     * forty-nine characters, being the sentence followed by six spaces. The field that holds it is
     * one character wider, so this is not the stored value - {@link #ORACLE_MSG_THANK_YOU_STORED} is.
     */
    private static final String ORACLE_MSG_THANK_YOU_AS_WRITTEN =
            "Thank you for using CardDemo application...      ";

    /**
     * The invalid-key text exactly as written: forty-nine characters, being the sentence followed by
     * nine spaces. Stored one character wider, as {@link #ORACLE_MSG_INVALID_KEY_STORED}.
     */
    private static final String ORACLE_MSG_INVALID_KEY_AS_WRITTEN =
            "Invalid key pressed. Please see below...         ";

    /**
     * The single space a fifty-character field adds to a forty-nine-character literal. Declared
     * rather than written into the literals above so the forty-nine versus fifty relationship is
     * visible to a reader instead of hidden in a run of trailing spaces nobody can count.
     */
    private static final String ORACLE_FIELD_PAD = " ";

    /** The thank-you text as the fifty-character field stores it: forty-nine as written, plus one pad. */
    private static final String ORACLE_MSG_THANK_YOU_STORED =
            ORACLE_MSG_THANK_YOU_AS_WRITTEN + ORACLE_FIELD_PAD;

    /** The invalid-key text as the fifty-character field stores it. */
    private static final String ORACLE_MSG_INVALID_KEY_STORED =
            ORACLE_MSG_INVALID_KEY_AS_WRITTEN + ORACLE_FIELD_PAD;

    // -----------------------------------------------------------------------------------------
    // Independent oracles: measured lengths and widths.
    // -----------------------------------------------------------------------------------------

    /** Measured length of the user-id prompt. */
    private static final int ORACLE_LENGTH_PROMPT_USERID = 24;

    /** Measured length of the entry-field prompt. */
    private static final int ORACLE_LENGTH_PROMPT_ENTRY = 25;

    /** Measured length of each of the three failure texts. */
    private static final int ORACLE_LENGTH_FAILURE_TEXT = 29;

    /** Declared width of each shared message, and therefore the length of its stored value. */
    private static final int ORACLE_COMMON_MESSAGE_WIDTH = 50;

    /** Length of each shared message as its value clause writes it, one short of the field width. */
    private static final int ORACLE_COMMON_MESSAGE_LITERAL_LENGTH = 49;

    /** Width of the unrelated title family, in a different copybook. Never the width above. */
    private static final int ORACLE_SCREEN_TITLE_WIDTH = 40;

    /** Width of the message the program composes. */
    private static final int ORACLE_MESSAGE_WIDTH = 80;

    /** Width of the map item the message is rendered through, two characters narrower. */
    private static final int ORACLE_SCREEN_MESSAGE_WIDTH = 78;

    /** Width of the echoed user identifier, agreed by the map, the mapset and the work field. */
    private static final int ORACLE_USER_ID_WIDTH = 8;

    /** Width of the user-type code, in the security record and in the communication area alike. */
    private static final int ORACLE_USER_TYPE_WIDTH = 1;

    /** Widest symbolic field name in the sign-on mapset. */
    private static final int ORACLE_SCREEN_FIELD_ID_WIDTH = 7;

    /** Width of the echoed transaction identifier. */
    private static final int ORACLE_TRANSACTION_NAME_WIDTH = 4;

    /** Width shared by the program name, the rendered date and the two region identifiers. */
    private static final int ORACLE_EIGHT_CHARACTER_WIDTH = 8;

    /** Width of the rendered time on this mapset, and on no other in the estate. */
    private static final int ORACLE_CURRENT_TIME_WIDTH = 9;

    /** Width of the echoed account identifier on the successor state. */
    private static final int ORACLE_ACCOUNT_ID_WIDTH = 11;

    /** Width of the echoed customer identifier on the successor state. */
    private static final int ORACLE_CUSTOMER_ID_WIDTH = 9;

    /** Width of the echoed card number on the successor state. */
    private static final int ORACLE_CARD_NUMBER_WIDTH = 16;

    // -----------------------------------------------------------------------------------------
    // Independent oracles: the wire vocabulary and the sample values.
    // -----------------------------------------------------------------------------------------

    /**
     * The fifteen property names a fully populated response publishes, in declaration order. This
     * list is the component inventory: it is what a client sees, and asserting the payload's key set
     * against it establishes both that every component is published and that nothing else is.
     */
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

    /** The eleven value items the symbolic map declares, used to rule out generated furniture. */
    private static final List<String> ORACLE_MAP_ITEM_NAMES = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");

    /**
     * The per-item control-byte suffixes the generated map declares: length, flag and attribute on
     * the input side, and colour, highlight, outline and validation on the output side. None of the
     * seven is a value, and none may reach a client.
     */
    private static final List<String> ORACLE_CONTROL_BYTE_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

    /** A representative identifier at the full declared width. */
    private static final String SAMPLE_USER_ID = "ADMIN001";

    /** The one code the program's single two-way decision tests for. */
    private static final String CODE_ADMINISTRATOR = "A";

    /** The code every standard-user record carries. */
    private static final String CODE_STANDARD_USER = "U";

    /** A code the security record never defines, which the program still routes rather than rejects. */
    private static final String CODE_UNDECLARED = "Z";

    /** The destination the administrator outcome nominates, opaque to this contract. */
    private static final String ROUTE_ADMIN_MENU = "/api/v1/menu/admin";

    /** The destination every other outcome nominates, opaque to this contract. */
    private static final String ROUTE_USER_MENU = "/api/v1/menu/user";

    /** Symbolic name of the field the cursor returned to on a failed comparison. */
    private static final String FIELD_ENTRY = "PASSWD";

    /** Symbolic name of the field the cursor returned to on a missing or empty identifier. */
    private static final String FIELD_USER_ID = "USERID";

    /** The first title line, forty characters including its own padding. */
    private static final String SAMPLE_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** The active second title line, forty characters. The copybook's alternative stays inactive. */
    private static final String SAMPLE_TITLE_02 = "              CardDemo                  ";

    /** The rendered date, eight characters of text and never a temporal value. */
    private static final String SAMPLE_CURRENT_DATE = "01/31/24";

    /**
     * The rendered time as the nine-character field holds it: eight characters of clock text plus one
     * pad. No temporal type could carry this value, which is precisely why it is the one asserted.
     */
    private static final String SAMPLE_CURRENT_TIME = "12:34:56 ";

    /** A region application identifier. */
    private static final String SAMPLE_APPLICATION_ID = "CICSAWS1";

    /** A region system identifier. */
    private static final String SAMPLE_SYSTEM_ID = "AWS1";

    // -----------------------------------------------------------------------------------------
    // Local mapper and validator. Built per call: no shared, mutable, order-dependent state.
    // -----------------------------------------------------------------------------------------

    /**
     * A mapper configured by hand to match the four serialisation settings the module declares, so
     * that what this test observes is what a client would receive. Nothing is inherited from a
     * framework slice and nothing is auto-configured.
     *
     * @return a mapper equivalent to the module's own
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

    /**
     * Serialises a response and reparses it, so assertions read the wire form rather than the object.
     *
     * @param response the response to publish
     * @return the parsed payload
     * @throws JsonProcessingException if the response cannot be written or reparsed
     */
    private static JsonNode payloadOf(SignOnResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Serialises a response to its raw text, for scans that must see the whole document.
     *
     * @param response the response to publish
     * @return the serialised document
     * @throws JsonProcessingException if the response cannot be written
     */
    private static String documentOf(SignOnResponse response) throws JsonProcessingException {
        return moduleEquivalentMapper().writeValueAsString(response);
    }

    /**
     * Writes a response and reads it back into a new instance through the module's shape.
     *
     * @param response the response to round-trip
     * @return the instance rebuilt from the serialised form
     * @throws JsonProcessingException if the response cannot be written or rebuilt
     */
    private static SignOnResponse roundTrip(SignOnResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readValue(mapper.writeValueAsString(response), SignOnResponse.class);
    }

    /**
     * Collects the property names a payload publishes, in the order it publishes them.
     *
     * @param payload the parsed payload
     * @return the published property names
     */
    private static List<String> propertyNamesOf(JsonNode payload) {
        List<String> names = new ArrayList<>();
        payload.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Validates a response with a standalone validator, built and closed per call. Not a framework
     * validator: nothing about this measurement depends on an application context.
     *
     * @param response the response to measure
     * @return the violations raised, which for this contract are width violations only
     */
    private static Set<ConstraintViolation<SignOnResponse>> violationsOf(SignOnResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    /**
     * Collects the component names the violations of a response name, so a bound can be attributed to
     * the component it belongs to rather than merely counted.
     *
     * @param response the response to measure
     * @return the violated component names
     */
    private static Set<String> violatedComponentsOf(SignOnResponse response) {
        Set<String> components = new LinkedHashSet<>();
        for (ConstraintViolation<SignOnResponse> violation : violationsOf(response)) {
            components.add(violation.getPropertyPath().toString());
        }
        return components;
    }

    /**
     * Builds a string of a given length, for probing a declared width without writing an unreadable
     * run of characters into a literal.
     *
     * @param length how many characters the value should have
     * @return a value of exactly that length
     */
    private static String valueOfLength(int length) {
        return "X".repeat(length);
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures, one per legacy outcome. Each names the arm it reproduces and the flag state the
    // program leaves behind on that arm, so a reader can check the fixture against the source.
    // -----------------------------------------------------------------------------------------

    /**
     * The failed-comparison outcome: the text is composed and the cursor moves to the entry field,
     * and the error flag is <strong>not</strong> raised. No route, because the screen is redisplayed.
     *
     * @return the response the failed-comparison arm produces
     */
    private static SignOnResponse comparisonFailedArm() {
        return redisplay(ORACLE_MSG_COMPARISON_FAILED, false, FIELD_ENTRY);
    }

    /**
     * The missing-record outcome, which unlike the failed comparison does raise the error flag.
     *
     * @return the response the missing-record arm produces
     */
    private static SignOnResponse userNotFoundArm() {
        return redisplay(ORACLE_MSG_USER_NOT_FOUND, true, FIELD_USER_ID);
    }

    /**
     * The catch-all read-failure outcome, which also raises the error flag.
     *
     * @return the response the catch-all arm produces
     */
    private static SignOnResponse unableToVerifyArm() {
        return redisplay(ORACLE_MSG_UNABLE_TO_VERIFY, true, FIELD_USER_ID);
    }

    /**
     * The exit-key outcome: the shared thank-you text is sent and the flag stays lowered. The
     * identifier is not echoed, because this path leaves the screen rather than redisplaying it.
     *
     * @return the response the exit-key arm produces
     */
    private static SignOnResponse exitKeyArm() {
        return new SignOnResponse(ORACLE_MSG_THANK_YOU_STORED, false, null, null, null,
                null, null, SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                SAMPLE_TITLE_01, SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME,
                SAMPLE_APPLICATION_ID, SAMPLE_SYSTEM_ID);
    }

    /**
     * The unmapped-key outcome: the shared invalid-key text is sent, the screen is redisplayed and
     * the flag <strong>is</strong> raised.
     *
     * @return the response the unmapped-key arm produces
     */
    private static SignOnResponse unmappedKeyArm() {
        return redisplay(ORACLE_MSG_INVALID_KEY_STORED, true, null);
    }

    /**
     * A redisplay of the sign-on screen: header values echoed, identifier restated so the operator
     * need not retype what did not fail, no route and no successor state.
     *
     * @param message            the text the screen displayed
     * @param generalError       the flag state the legacy arm left behind
     * @param focusScreenFieldId the field the cursor returned to, or {@code null} for none
     * @return the redisplay response
     */
    private static SignOnResponse redisplay(String message, boolean generalError,
            String focusScreenFieldId) {
        return new SignOnResponse(message, generalError, focusScreenFieldId, null, null,
                SAMPLE_USER_ID, null, SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                SAMPLE_TITLE_01, SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME,
                SAMPLE_APPLICATION_ID, SAMPLE_SYSTEM_ID);
    }

    /**
     * A successful sign-on for the given code: the successor state is assembled and a destination is
     * nominated. The caller supplies both the code and the destination, because the split between
     * them belongs to the service and this contract only carries whatever pair it is handed.
     *
     * @param userTypeCode the raw one-character code the security record stored
     * @param nextRoute    the destination the navigation service nominated
     * @return the successful sign-on response
     */
    private static SignOnResponse successfulSignOn(String userTypeCode, String nextRoute) {
        return new SignOnResponse(null, false, null, nextRoute,
                successorState(userTypeCode), SAMPLE_USER_ID, userTypeCode,
                SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                SAMPLE_TITLE_01, SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME,
                SAMPLE_APPLICATION_ID, SAMPLE_SYSTEM_ID);
    }

    /**
     * The successor state a successful sign-on assembles: originating transaction and program, the
     * identifier, the raw code, and first entry into the destination screen. The selection
     * identifiers are populated with leading-zero values because preserving a rendered leading zero
     * is the whole reason they cross the boundary as text.
     *
     * @param userTypeCode the raw one-character code
     * @return the successor state
     */
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

    /**
     * A response with every component absent, which is a legal state on this contract because every
     * component is optional in the legacy sense. The flag is a primitive and cannot be absent.
     *
     * @return the wholly absent response
     */
    private static SignOnResponse whollyAbsent() {
        return new SignOnResponse(null, false, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * A response carrying only a message and a flag state, for the many assertions that are about the
     * pairing of those two and nothing else.
     *
     * @param message      the text
     * @param generalError the flag state
     * @return a response carrying exactly those two facts
     */
    private static SignOnResponse messageOnly(String message, boolean generalError) {
        return new SignOnResponse(message, generalError, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * A response with every one of the fifteen components populated, so that the published key set is
     * the full component inventory rather than whatever a single legacy arm happens to fill in. Not a
     * legacy state - no arm both redisplays and nominates a destination - and deliberately so: the
     * inventory has to be observed with nothing omitted.
     *
     * @param message the text to carry, which some scans need to choose for themselves
     * @return a response with no component absent
     */
    private static SignOnResponse fullyPopulatedWith(String message) {
        return new SignOnResponse(message, true, FIELD_ENTRY, ROUTE_ADMIN_MENU,
                successorState(CODE_ADMINISTRATOR), SAMPLE_USER_ID, CODE_ADMINISTRATOR,
                SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME, SAMPLE_TITLE_01,
                SAMPLE_TITLE_02, SAMPLE_CURRENT_DATE, SAMPLE_CURRENT_TIME, SAMPLE_APPLICATION_ID,
                SAMPLE_SYSTEM_ID);
    }

    /**
     * The failed-comparison outcome, which is the subtlest fact in the whole sign-on contract and the
     * one this file exists to pin down.
     *
     * <p>The program composes its text and moves the cursor to the entry field, and it assigns nothing
     * to the error flag on that path. The flag was lowered on entry and stays lowered. So a response
     * that carries a message with the flag {@code false} is not a contradiction to be tidied away - it
     * is the exact shape of a failed credential comparison, and any implementation that derives the
     * flag from the presence of a message reverses it.
     */
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

            // The program composes the text into an eighty-character field, so the value it actually
            // holds is the text followed by fifty-one spaces. Carrying that padded form proves the
            // absence of a trim far better than carrying a value that has nothing to trim.
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

    /**
     * The asymmetry across the eight outcomes, asserted arm by arm so that it is demonstrably modelled
     * rather than accidentally true of one fixture.
     *
     * <p>Five arms raise the flag and three leave it lowered. Two of the three carry a message while
     * doing so, which is why no rule of the form "a message implies an error" can hold.
     */
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

    /**
     * The two shared texts, carried at fifty characters and never at forty-nine.
     *
     * <p>Both facts about them are true at once and neither may be dropped: the literal each value
     * clause writes is forty-nine characters long, and the field that holds it is fifty characters
     * wide, so the stored content is the literal followed by one filling space. Fifty is what a client
     * receives. The trailing pad is part of the value, not noise around it, and nothing on this
     * contract trims, strips, re-fills or blank-collapses it.
     *
     * <p>These fifty-character values belong to one family. A separate forty-character family lives in
     * a different copybook, names the application by an older abbreviation, and includes its own
     * courtesy text that reads almost the same. <strong>The two families must never be merged,
     * cross-referenced or de-duplicated</strong>, which is why the last test here asserts the
     * inequality rather than leaving it to a reader's care.
     */
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
            // Two different copybooks, two different widths, two different texts. The forty-character
            // courtesy title reads almost the same as the fifty-character thank-you and names the
            // application by an older abbreviation. Merging or de-duplicating the two families would
            // silently substitute one contract for the other, so the inequality is asserted here
            // rather than left to a reader's care.
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

    /**
     * All seven texts the sign-on transaction can emit, driven through the contract and asserted at
     * their measured lengths. Five are written directly in the program and two come from the shared
     * copybook, and the seven measure 24, 25, 29, 29, 29, 50 and 50 characters respectively.
     *
     * <p>Character-for-character fidelity is what this part of the acceptance criteria verifies, so
     * every assertion here compares against a literal restated in this file rather than against the
     * constant the contract publishes. Where the contract does publish a constant, its value is
     * checked against the restated literal too, so a drift in either direction fails.
     */
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
            // The program evaluates its clauses top down and stops at the first that matches, so a
            // submission with both entry fields empty yields the identifier prompt and never the
            // second one. The two texts therefore have to remain separately representable.
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

            // The composed field is two characters wider than the item it is rendered through. That
            // difference is recorded rather than enforced, and it is harmless precisely because no
            // text the transaction can emit is long enough to be affected by it.
            assertThat(ORACLE_MESSAGE_WIDTH).isGreaterThan(ORACLE_SCREEN_MESSAGE_WIDTH);
            assertThat(texts).allSatisfy(text -> assertThat(text.length())
                    .as("[%s] is short enough that the two-character difference cannot bite", text)
                    .isLessThanOrEqualTo(ORACLE_SCREEN_MESSAGE_WIDTH));
        }
    }

    /**
     * The destination is data this contract echoes, never a decision it makes.
     *
     * <p>On a successful comparison the program makes a single two-way decision: the administrator code
     * transfers control to the administrative menu, and the alternative transfers control to the user
     * main menu. That alternative is <strong>unconditional</strong> - it is not a second test of the
     * standard-user condition - so there is no third branch and no failure path, and every code that is
     * not the administrator code reaches the main menu rather than an error.
     *
     * <p>The decision itself belongs to the service layer, and the route vocabulary belongs to the
     * navigation service. This type holds an opaque value. The tests below establish that behaviourally
     * rather than by inspecting the class: a value from no vocabulary survives, an arbitrarily long
     * value raises no violation, and a deliberately mismatched code-and-destination pair is carried
     * faithfully instead of being corrected. A route enumeration could not accept the first, a declared
     * bound would reject the second, and any dispatch at all would rewrite the third.
     */
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
            // The alternative arm is unconditional, so an undeclared code is routed and not rejected.
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
            // A contract that re-derived the destination from the code would correct this pairing.
            // Carrying it unchanged is the proof that no dispatch, route table or lookup runs here.
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

    /**
     * The user-type contract: two declared codes, no synthetic third state, and a lookup that never
     * throws.
     *
     * <p>The persisted field is a single character and the communication area declares exactly two
     * condition names over it. The response carries the raw character rather than a resolved role, so a
     * code the record never defines survives the round trip instead of being erased, and interpretation
     * happens through the successor state's own lookup where it belongs.
     *
     * <p>The lookup returning an absent result for an unrecognised code is not a convenience: it is
     * what the unconditional alternative encodes. A throwing lookup would be the more conventional Java
     * shape and it would abort a sign-on the legacy program completes.
     */
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
            // This composition is the unconditional alternative, expressed in Java: an absent result
            // can never answer true to the administrator predicate, because there is no instance on
            // which to ask.
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

    /**
     * The successor state is carried, never re-implemented.
     *
     * <p>Every identifier the legacy area holds is a fixed-width numeric field whose rendered form
     * includes its leading zeros, so each crosses this boundary as bounded text. A numeric Java type
     * would drop those zeros irrecoverably, and no amount of formatting on the way out would tell a
     * client whether the original had them. The tests below carry a leading-zero value all the way
     * through the wire and assert both that it is unchanged and that it was published as text.
     */
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

    /**
     * What never reaches a client: no credential in any form, and no generated terminal furniture.
     *
     * <p>The symbolic map declares an eight-character output item for the operator's credential
     * immediately after the one for the identifier and at the same width. One of those two is carried
     * and the other must never be, and being declared by a generated map is no argument either way.
     *
     * <p>The map also declares, for each of its eleven value items, a per-item length, flag and
     * attribute group on the input side and a colour, highlight, outline and validation group on the
     * output side, plus a twelve-byte terminal area filler at the head of each map. None of that is a
     * value. The assertions here work from the published payload, which is the strongest available
     * statement: the key set is pinned to exactly fifteen names, so anything else - a credential, a
     * control byte, a filler, a cursor coordinate, an attribute or an edit mask - cannot be present
     * without failing.
     */
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
            // The missing-record arm is used rather than the failed-comparison arm because the latter's
            // mandated interface wording legitimately contains the word this scan looks for. Mentioning
            // it in a sentence the screen displayed is not publishing a credential; the following test
            // asserts that distinction explicitly.
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

            // Two permitted mentions, both metadata rather than data: the mandated interface wording
            // the screen displayed, and the symbolic name of the field the cursor returned to. Neither
            // is a credential, and no component of this contract holds one.
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

            // The time value carries the pad the nine-character field adds. No temporal type could
            // hold it, and none would return it unchanged, so this is the assertion that proves the
            // component is text and that no formatter sits in the path.
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

    /**
     * Bean Validation is used for measurement only, and a width constraint is essentially the only kind
     * that appears.
     *
     * <p>Every component of this contract is optional in the legacy sense: each can legitimately be
     * absent, empty or space-filled, because the screen it came from allowed all three. A presence, a
     * pattern or a format constraint would therefore reject input the legacy accepted, and the legacy
     * cascade is ordered and first-error-wins in any case, so a declarative constraint set could not
     * reproduce it. The tests here establish that the only thing being measured is width, and that the
     * measurement never alters a value.
     */
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

    /**
     * Value semantics: nullable throughout, immutable by construction, absent keys omitted, unknown
     * incoming keys tolerated.
     *
     * <p>Immutability is demonstrated here by <em>building</em> rather than by interrogating. Deriving a
     * modified successor state from the one a response holds produces a new state and leaves the
     * response's own untouched, which is the property that actually matters: no holder of a reference
     * to this response can change what it says.
     */
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
        @DisplayName("returns a stable value from every accessor, however often it is asked")
        void returnsAStableValueFromEveryAccessor() {
            SignOnResponse response = fullyPopulatedWith(ORACLE_MSG_THANK_YOU_STORED);

            assertThat(response.message()).isSameAs(response.message());
            assertThat(response.navigationContext()).isSameAs(response.navigationContext());
            assertThat(response.userId()).isSameAs(response.userId());
            assertThat(response.generalError()).isEqualTo(response.generalError());
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
            String bodyWithAnUnknownKey = """
                    {"message":"%s","generalError":false,"userId":"%s",\
                    "aKeyThisContractDoesNotDeclare":"ignored"}"""
                    .formatted(ORACLE_MSG_COMPARISON_FAILED, SAMPLE_USER_ID);

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

    /**
     * Builds a response carrying only an identifier, for probing that component's declared width.
     *
     * @param userId the identifier to carry
     * @return a response carrying only that identifier
     */
    private static SignOnResponse withUserId(String userId) {
        return new SignOnResponse(null, false, null, null, null, userId, null, null, null, null,
                null, null, null, null, null);
    }

    /**
     * Builds a response carrying only a user-type code, for probing that component's declared width.
     *
     * @param userType the raw code to carry
     * @return a response carrying only that code
     */
    private static SignOnResponse withUserType(String userType) {
        return new SignOnResponse(null, false, null, null, null, null, userType, null, null, null,
                null, null, null, null, null);
    }
}
