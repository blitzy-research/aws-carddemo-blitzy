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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SignOnResponse}, the response body of legacy transaction {@code CC00}, the
 * sign-on screen implemented by {@code app/cbl/COSGN00C.cbl}.
 *
 * <p><strong>Provenance.</strong> Every width and every message literal asserted here was read from the
 * mainframe estate at checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The five operator messages are the ones the
 * legacy program moves into its 80-character work field at {@code app/cbl/COSGN00C.cbl} lines 120, 125,
 * 242 to 243, 249 and 254.
 *
 * <p><strong>Why the message literals are asserted character for character.</strong> They are an
 * external interface contract, not decoration: operators and downstream tooling match on the exact
 * text, so a changed space, a changed ellipsis or a changed capital is a contract break. Each literal
 * is restated here independently of the class under test rather than compared against itself, which is
 * the only form of the assertion that can actually fail when the constant drifts.
 *
 * @see SignOnResponse
 */
@DisplayName("SignOnResponse - the CC00 sign-on response contract")
class SignOnResponseRuleComplianceTest {

    /** A route label. Role-named and carrying no legacy program name, as the vocabulary requires. */
    private static final String ROUTE = "user-menu";

    /** The operator's own entry, echoed back at the map's eight-character output width. */
    private static final String USER_ID = "USER0001";

    /**
     * Builds a fully populated response, so a test that cares about one component still exercises the
     * canonical constructor with every other component present.
     *
     * @return a populated response
     */
    private static SignOnResponse aResponse() {
        return new SignOnResponse(SignOnResponse.MSG_PROMPT_USERID, false, "USERIDI", ROUTE,
                NavigationContext.empty(), USER_ID, UserType.USER.getCode(),
                SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                "      AWS Mainframe Modernization       ",
                "              CardDemo                  ", "07/19/22", "14:30:00 ", "CARDDEMO",
                "AWSMFRAM");
    }

    /**
     * A mapper configured exactly as {@code application.yml} configures the application's own, so a
     * payload assertion measures the published contract rather than Jackson's defaults.
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
    private static JsonNode payloadOf(final SignOnResponse response) throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published widths, read from the screen definition")
    class ThePublishedWidths {

        @Test
        @DisplayName("the work-field width is 80 and the screen message field is 78, the two being "
                + "different values for a reason and not a duplication")
        void theMessageWidthsAreEightyAndSeventyEight() {
            assertThat(SignOnResponse.MESSAGE_LENGTH).isEqualTo(80);
            assertThat(SignOnResponse.SCREEN_MESSAGE_LENGTH).isEqualTo(78);
            assertThat(SignOnResponse.SCREEN_MESSAGE_LENGTH)
                    .as("the screen field is narrower than the work field it is written from")
                    .isLessThan(SignOnResponse.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the remaining widths are the legacy field widths")
        void theRemainingWidthsAreTheLegacyOnes() {
            assertThat(SignOnResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(SignOnResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(SignOnResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(SignOnResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(SignOnResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH).isEqualTo(9);
            assertThat(SignOnResponse.APPLICATION_ID_LENGTH).isEqualTo(8);
            assertThat(SignOnResponse.SYSTEM_ID_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("the transaction name and program name are their own declared widths, so the "
                + "constants and the widths cannot drift apart")
        void theIdentityConstantsMatchTheirDeclaredWidths() {
            assertThat(SignOnResponse.TRANSACTION_NAME)
                    .hasSize(SignOnResponse.TRANSACTION_NAME_LENGTH);
            assertThat(SignOnResponse.PROGRAM_NAME).hasSize(SignOnResponse.PROGRAM_NAME_LENGTH);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the operator message contract - Gate 5, character for character")
    class TheOperatorMessageContract {

        @Test
        @DisplayName("the two prompts are the legacy literals exactly")
        void theTwoPromptsAreTheLegacyLiterals() {
            assertThat(SignOnResponse.MSG_PROMPT_USERID).isEqualTo("Please enter User ID ...");
            assertThat(SignOnResponse.MSG_PROMPT_PASSWD).isEqualTo("Please enter Password ...");
        }

        @Test
        @DisplayName("the three authentication failures are the legacy literals exactly")
        void theThreeAuthenticationFailuresAreTheLegacyLiterals() {
            assertThat(SignOnResponse.MSG_WRONG_PASSWD).isEqualTo("Wrong Password. Try again ...");
            assertThat(SignOnResponse.MSG_USER_NOT_FOUND).isEqualTo("User not found. Try again ...");
            assertThat(SignOnResponse.MSG_UNABLE_TO_VERIFY).isEqualTo("Unable to verify the User ...");
        }

        @Test
        @DisplayName("the identity constants are the registered transaction and the source member name")
        void theIdentityConstantsAreTheRegisteredOnes() {
            assertThat(SignOnResponse.TRANSACTION_NAME).isEqualTo("CC00");
            assertThat(SignOnResponse.PROGRAM_NAME).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("every message fits the work field it is written into, so none is silently truncated")
        void everyMessageFitsTheWorkField() {
            for (final String message : List.of(SignOnResponse.MSG_PROMPT_USERID,
                    SignOnResponse.MSG_PROMPT_PASSWD, SignOnResponse.MSG_WRONG_PASSWD,
                    SignOnResponse.MSG_USER_NOT_FOUND, SignOnResponse.MSG_UNABLE_TO_VERIFY)) {
                assertThat(message.length())
                        .as("[%s] must fit the %d-character work field", message,
                                SignOnResponse.MESSAGE_LENGTH)
                        .isLessThanOrEqualTo(SignOnResponse.MESSAGE_LENGTH);
                assertThat(message.length())
                        .as("[%s] must also fit the %d-character screen field", message,
                                SignOnResponse.SCREEN_MESSAGE_LENGTH)
                        .isLessThanOrEqualTo(SignOnResponse.SCREEN_MESSAGE_LENGTH);
            }
        }

        @Test
        @DisplayName("no message carries a trailing space, a leading space or a line break, because the "
                + "legacy literals carry none and a diagnostic channel must not gain one")
        void noMessageCarriesStrayWhitespace() {
            for (final String message : List.of(SignOnResponse.MSG_PROMPT_USERID,
                    SignOnResponse.MSG_PROMPT_PASSWD, SignOnResponse.MSG_WRONG_PASSWD,
                    SignOnResponse.MSG_USER_NOT_FOUND, SignOnResponse.MSG_UNABLE_TO_VERIFY)) {
                assertThat(message).isEqualTo(message.strip());
                assertThat(message).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
            }
        }

        @Test
        @DisplayName("the five messages are distinct, so an operator can tell the failures apart")
        void theFiveMessagesAreDistinct() {
            assertThat(List.of(SignOnResponse.MSG_PROMPT_USERID, SignOnResponse.MSG_PROMPT_PASSWD,
                    SignOnResponse.MSG_WRONG_PASSWD, SignOnResponse.MSG_USER_NOT_FOUND,
                    SignOnResponse.MSG_UNABLE_TO_VERIFY))
                    .doesNotHaveDuplicates();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("component behaviour")
    class ComponentBehaviour {

        @Test
        @DisplayName("every supplied component is returned exactly as supplied, with no trimming, "
                + "padding or re-casing")
        void everyComponentIsReturnedUnaltered() {
            final SignOnResponse response = aResponse();

            assertThat(response.message()).isEqualTo(SignOnResponse.MSG_PROMPT_USERID);
            assertThat(response.generalError()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo("USERIDI");
            assertThat(response.nextRoute()).isEqualTo(ROUTE);
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.userType()).isEqualTo(UserType.USER.getCode());
            assertThat(response.transactionName()).isEqualTo("CC00");
            assertThat(response.programName()).isEqualTo("COSGN00C");
            assertThat(response.title01()).isEqualTo("      AWS Mainframe Modernization       ");
            assertThat(response.title02()).isEqualTo("              CardDemo                  ");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.currentTime()).isEqualTo("14:30:00 ");
            assertThat(response.applicationId()).isEqualTo("CARDDEMO");
            assertThat(response.systemId()).isEqualTo("AWSMFRAM");
        }

        @Test
        @DisplayName("a wholly absent response is constructible, because a sign-on failure carries no "
                + "user type and no next route")
        void aWhollyAbsentResponseIsConstructible() {
            final SignOnResponse response = new SignOnResponse(null, true, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(response.message()).isNull();
            assertThat(response.generalError()).isTrue();
            assertThat(response.userId()).isNull();
            assertThat(response.userType()).isNull();
            assertThat(response.nextRoute()).isNull();
        }

        @Test
        @DisplayName("the error flag is a distinct component from the message, so a message may be "
                + "carried without an error and an error without a message")
        void theErrorFlagIsIndependentOfTheMessage() {
            final SignOnResponse informational = new SignOnResponse(
                    SignOnResponse.MSG_PROMPT_USERID, false, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
            final SignOnResponse silentError = new SignOnResponse(null, true, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(informational.generalError()).isFalse();
            assertThat(informational.message()).isNotNull();
            assertThat(silentError.generalError()).isTrue();
            assertThat(silentError.message()).isNull();
        }

        @Test
        @DisplayName("value semantics hold, because a record with only immutable components is a value")
        void valueSemanticsHold() {
            assertThat(aResponse()).isEqualTo(aResponse());
            assertThat(aResponse()).hasSameHashCodeAs(aResponse());
            assertThat(aResponse()).isNotEqualTo(new SignOnResponse(null, true, null, null, null,
                    null, null, null, null, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("both declared user-type codes are carried, so the role split is representable in "
                + "the response as well as decided by the service")
        void bothDeclaredUserTypeCodesAreCarried() {
            for (final UserType userType : UserType.values()) {
                final SignOnResponse response = new SignOnResponse(null, false, null, null, null,
                        null, userType.getCode(), null, null, null, null, null, null, null, null);

                assertThat(response.userType()).isEqualTo(userType.getCode());
                assertThat(UserType.fromCode(response.userType())).contains(userType);
            }
        }

        @Test
        @DisplayName("an undeclared user-type code survives the round trip rather than being rejected, "
                + "because the legacy comparison routes any non-administrator code to the main menu")
        void anUndeclaredUserTypeCodeSurvives() {
            final SignOnResponse response = new SignOnResponse(null, false, null, null, null, null,
                    "Z", null, null, null, null, null, null, null, null);

            assertThat(response.userType()).isEqualTo("Z");
            assertThat(UserType.fromCode("Z"))
                    .as("the code is carried raw, so resolution can report that it is undeclared")
                    .isEmpty();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared validation bounds")
    class TheDeclaredValidationBounds {

        @ParameterizedTest(name = "{0} bounded at {1}")
        @CsvSource({
            "message,80", "focusScreenFieldId,7", "userId,8", "userType,1",
            "transactionName,4", "programName,8",
            "title01,40", "title02,40", "currentDate,8", "currentTime,9",
            "applicationId,8", "systemId,8"})
        @DisplayName("each bounded component declares the legacy width, read from the annotation itself")
        void eachBoundedComponentDeclaresTheLegacyWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {

            final Size size = SignOnResponse.class.getDeclaredMethod(componentName)
                    .getAnnotation(Size.class);

            assertThat(size)
                    .as("component %s must declare a width bound", componentName)
                    .isNotNull();
            assertThat(size.max()).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("a value exactly at its bound is accepted and one character over is reported, "
                + "which is what makes the bound a bound")
        void aValueAtTheBoundIsAcceptedAndOneOverIsReported() {
            final String atBound = "X".repeat(SignOnResponse.MESSAGE_LENGTH);
            final String overBound = "X".repeat(SignOnResponse.MESSAGE_LENGTH + 1);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(new SignOnResponse(atBound, false, null,
                        null, null, null, null, null, null, null, null, null, null, null, null)))
                        .isEmpty();

                final Set<ConstraintViolation<SignOnResponse>> violations =
                        factory.getValidator().validate(new SignOnResponse(overBound, false, null,
                                null, null, null, null, null, null, null, null, null, null, null,
                                null));

                assertThat(violations).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath()).hasToString("message");
            }
        }

        @Test
        @DisplayName("the bound reports and never alters: the over-long value is still returned in full")
        void theBoundReportsAndNeverAlters() {
            final String overBound = "X".repeat(SignOnResponse.MESSAGE_LENGTH + 1);
            final SignOnResponse response = new SignOnResponse(overBound, false, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);

            assertThat(response.message())
                    .hasSize(SignOnResponse.MESSAGE_LENGTH + 1)
                    .isEqualTo(overBound);
        }

        @Test
        @DisplayName("the route, the navigation context and the error flag carry no width bound, "
                + "because none of them is a fixed-width screen field")
        void theNonScreenComponentsCarryNoWidthBound() throws NoSuchMethodException {
            for (final String componentName : List.of("nextRoute", "navigationContext",
                    "generalError")) {
                assertThat(SignOnResponse.class.getDeclaredMethod(componentName)
                        .getAnnotation(Size.class))
                        .as("component %s must not declare a width bound", componentName)
                        .isNull();
            }
        }

        @Test
        @DisplayName("the user-type code does carry a width bound, because it is a fixed-width screen "
                + "field: one character, the width the legacy comparison reads")
        void theUserTypeCodeCarriesAOneCharacterBound() throws NoSuchMethodException {
            final Size size = SignOnResponse.class.getDeclaredMethod("userType")
                    .getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isOne().isEqualTo(SignOnResponse.USER_TYPE_LENGTH);
            assertThat(UserType.values()).allSatisfy(userType ->
                    assertThat(userType.getCode()).hasSize(SignOnResponse.USER_TYPE_LENGTH));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published payload shape")
    class ThePublishedPayloadShape {

        @Test
        @DisplayName("the payload names every declared component and nothing else")
        void thePayloadNamesEveryDeclaredComponent() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aResponse());

            for (final RecordComponent component : SignOnResponse.class.getRecordComponents()) {
                assertThat(payload.has(component.getName()))
                        .as("component %s must appear in the payload", component.getName())
                        .isTrue();
            }
            assertThat(payload.size()).isEqualTo(SignOnResponse.class.getRecordComponents().length);
        }

        @Test
        @DisplayName("an absent component is omitted rather than rendered as a null literal, which is "
                + "what the module's inclusion setting means")
        void anAbsentComponentIsOmitted() throws JsonProcessingException {
            final JsonNode payload = payloadOf(new SignOnResponse(null, false, null, ROUTE, null,
                    null, null, null, null, null, null, null, null, null, null));

            assertThat(payload.has("message")).isFalse();
            assertThat(payload.has("userType")).isFalse();
            assertThat(payload.get("nextRoute").asText()).isEqualTo(ROUTE);
            assertThat(payload.get("generalError").asBoolean())
                    .as("a primitive is never absent, so the flag is always rendered")
                    .isFalse();
        }

        @Test
        @DisplayName("the user type is rendered as the stored one-character code and not as a resolved "
                + "constant name, so an undeclared code survives the round trip")
        void theUserTypeIsRenderedAsTheStoredCharacter() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aResponse());

            assertThat(payload.get("userType").asText()).isEqualTo(UserType.USER.getCode())
                    .hasSize(SignOnResponse.USER_TYPE_LENGTH)
                    .isNotEqualTo(UserType.USER.name());
        }

        @Test
        @DisplayName("the payload carries the operator's own identifier and no credential of any kind, "
                + "because the response echoes what was typed into the identifier field alone")
        void thePayloadCarriesNoCredential() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aResponse());
            final List<String> keys = new ArrayList<>();
            payload.fieldNames().forEachRemaining(keys::add);

            assertThat(payload.get("userId").asText()).isEqualTo(USER_ID);
            assertThat(keys).doesNotContain("password", "passwd", "PASSWDO", "credential",
                    "passwordHash");
            assertThat(payload.toString()).doesNotContain("assword");
        }

        @Test
        @DisplayName("the payload survives a round trip through the module-equivalent mapper unchanged")
        void thePayloadSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final SignOnResponse original = aResponse();

            final SignOnResponse restored =
                    mapper.readValue(mapper.writeValueAsString(original), SignOnResponse.class);

            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("no payload key names a mainframe screen map or a copybook, because the published "
                + "contract is free of mainframe vocabulary")
        void noPayloadKeyNamesAMainframeArtefact() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aResponse());

            payload.fieldNames().forEachRemaining(name -> assertThat(name)
                    .as("payload key %s", name)
                    .doesNotStartWith("CSGN")
                    .doesNotStartWith("COSGN")
                    .doesNotStartWith("DFH"));
        }
    }
}
