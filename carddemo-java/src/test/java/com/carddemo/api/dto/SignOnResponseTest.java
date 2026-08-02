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

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SignOnResponse}, the outbound contract of legacy transaction {@code CC00}.
 *
 * <p>A pure unit test. It starts no application context, opens no connection and launches no container:
 * it constructs the type directly and, where the wire shape is what is under test, serialises with a
 * local mapper configured by hand to match the serialisation settings the module declares in
 * {@code application.yml}. Nothing here shares state with any other test.</p>
 *
 * <p>Every expectation is restated independently of the class under test. A table that read its
 * expectations out of the type it is testing would assert nothing, so the widths, the field names and
 * the message literals below are written out again from the legacy artefacts:
 * {@code app/cpy-bms/COSGN00.CPY} for the symbolic map, {@code app/bms/COSGN00.bms} for the layout, and
 * {@code app/cbl/COSGN00C.cbl} for the behaviour and the texts.</p>
 *
 * <p>Three properties get the most attention here because they are the ones a careless edit would
 * silently reverse.</p>
 *
 * <ol>
 *   <li><em>The user identifier is echoed and the credential is not.</em> The map declares an
 *       eight-character safe output item for the identifier on line 140 and an item of the same width
 *       for the credential on line 146. One of those is carried and the other must never be, and being
 *       declared by the map is no argument either way.</li>
 *   <li><em>The user-type code crosses as one raw character, not as a resolved role.</em> The whole
 *       module spells that code one way, and a test below compares this contract's spelling against
 *       {@link NavigationContext} rather than trusting that they agree.</li>
 *   <li><em>The rendered time is nine characters here and eight everywhere else.</em> That is the one
 *       genuine width anomaly in the estate's seventeen mapsets, so it is asserted as an anomaly rather
 *       than normalised away.</li>
 * </ol>
 *
 * <p>Source checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("SignOnResponse - the outbound sign-on contract")
class SignOnResponseTest {

    /** The fifteen components, in the order the record declares them. */
    private static final List<String> COMPONENTS_IN_DECLARED_ORDER = List.of(
            "message", "generalError", "focusScreenFieldId", "nextRoute", "navigationContext",
            "userId", "userType", "transactionName", "programName", "title01", "title02",
            "currentDate", "currentTime", "applicationId", "systemId");

    /** Width of {@code USERIDO}, {@code USERIDI}, the mapset field and {@code WS-USER-ID}: eight. */
    private static final int ORACLE_USER_ID_LENGTH = 8;

    /** Width of {@code SEC-USR-TYPE} and of {@code CDEMO-USER-TYPE}: one. */
    private static final int ORACLE_USER_TYPE_LENGTH = 1;

    /** Widest symbolic field name in {@code app/bms/COSGN00.bms}: seven. */
    private static final int ORACLE_SCREEN_FIELD_ID_LENGTH = 7;

    /** Width of {@code WS-MESSAGE} on line 38 of the program: eighty. */
    private static final int ORACLE_MESSAGE_LENGTH = 80;

    /** Width of the map's {@code ERRMSG} item: seventy-eight. */
    private static final int ORACLE_SCREEN_MESSAGE_LENGTH = 78;

    /** Width of {@code TRNNAME}: four. */
    private static final int ORACLE_TRANSACTION_NAME_LENGTH = 4;

    /** Width of {@code PGMNAME}, {@code APPLID}, {@code SYSID} and {@code CURDATE}: eight. */
    private static final int ORACLE_EIGHT = 8;

    /** Width of the two title items: forty. */
    private static final int ORACLE_SCREEN_TITLE_LENGTH = 40;

    /** Width of {@code CURTIME} in <em>this</em> mapset, and only this one: nine. */
    private static final int ORACLE_CURRENT_TIME_LENGTH = 9;

    /** A representative identifier at the full declared width. */
    private static final String USER_ID = "ADMIN001";

    /** The administrator code the program's single two-way decision tests for. */
    private static final String ADMIN_CODE = "A";

    /** The code every non-administrator record carries. */
    private static final String USER_CODE = "U";

    /** A declarative route label, opaque to this contract. */
    private static final String ROUTE_ADMIN_MENU = "admin-menu";

    /** The symbolic name of the field the cursor was placed on, at its declared width. */
    private static final String SCREEN_USER_ID_FIELD = "USERID";

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

    /** A response on the wrong-credential redisplay path: identifier echoed, no route, flag lowered. */
    private static SignOnResponse wrongCredentialRedisplay() {
        return new SignOnResponse(SignOnResponse.MSG_WRONG_PASSWD, false, "PASSWD", null, null,
                USER_ID, null, SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                "      AWS Mainframe Modernization       ",
                "              CardDemo                  ", "01/31/24", "10:15:30", "CICSAWS1",
                "AWS1");
    }

    /** A response on the successful administrator path: role code carried, route nominated. */
    private static SignOnResponse successfulAdministratorSignOn() {
        return new SignOnResponse(null, false, null, ROUTE_ADMIN_MENU,
                new NavigationContext("CC00", "COSGN00C", "CA00", "COADM01C", USER_ID, ADMIN_CODE,
                        NavigationContext.ProgramContext.ENTER, null, null, null, null, null, null,
                        null, null, null),
                USER_ID, ADMIN_CODE, SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                "      AWS Mainframe Modernization       ",
                "              CardDemo                  ", "01/31/24", "10:15:30", "CICSAWS1",
                "AWS1");
    }

    private static Set<ConstraintViolation<SignOnResponse>> violationsOf(SignOnResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    @Nested
    @DisplayName("The component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the fifteen components, in declaration order")
        void declaresExactlyFifteenComponentsInOrder() {
            List<String> declared = Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_DECLARED_ORDER).hasSize(15);
        }

        @Test
        @DisplayName("carries the map's safe output item for the operator's own entry")
        void carriesTheSafeOutputItem() {
            List<String> declared = Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).contains("userId");
        }

        @Test
        @DisplayName("carries no credential under any spelling, whatever the map declares beside the "
                + "identifier")
        void carriesNoCredentialUnderAnySpelling() {
            List<String> lowered = Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowered).noneMatch(name -> name.contains("passw"))
                    .noneMatch(name -> name.contains("credential"))
                    .noneMatch(name -> name.contains("secret"))
                    .noneMatch(name -> name.contains("hash"))
                    .noneMatch(name -> name.contains("token"))
                    .noneMatch(name -> name.contains("jwt"))
                    .noneMatch(name -> name.contains("bearer"));
        }

        @Test
        @DisplayName("names focus for the concept, not the action, so every screen contract agrees")
        void namesFocusForTheConcept() {
            List<String> declared = Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).contains("focusScreenFieldId")
                    .doesNotContain("fieldToFocus", "focusField", "focusFieldName");
        }

        @Test
        @DisplayName("names the destination and the successor state the way every other contract does")
        void namesTheDestinationAndSuccessorStateCanonically() {
            List<String> declared = Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).contains("nextRoute", "navigationContext", "title01", "title02")
                    .doesNotContain("route", "navigation", "screenTitle1", "screenTitleLine1",
                            "titleLine1");
        }

        @Test
        @DisplayName("carries characters throughout, with the failure flag the only primitive and no "
                + "domain enumeration anywhere")
        void carriesCharactersThroughoutAndNoDomainEnumeration() {
            Map<String, Class<?>> types = new LinkedHashMap<>();
            for (RecordComponent component : SignOnResponse.class.getRecordComponents()) {
                types.put(component.getName(), component.getType());
            }

            assertThat(types.get("generalError")).isEqualTo(boolean.class);
            assertThat(types.get("navigationContext")).isEqualTo(NavigationContext.class);
            types.entrySet().stream()
                    .filter(entry -> !List.of("generalError", "navigationContext")
                            .contains(entry.getKey()))
                    .forEach(entry -> assertThat(entry.getValue())
                            .as("component %s", entry.getKey())
                            .isEqualTo(String.class));

            assertThat(types.values()).noneMatch(Class::isEnum);
            assertThat(types.values()).noneMatch(type -> type.getName()
                    .startsWith("com.carddemo.domain."));
        }
    }

    @Nested
    @DisplayName("The declared widths")
    class DeclaredWidths {

        @Test
        @DisplayName("match the symbolic map, the mapset and the program's own work fields")
        void matchTheLegacyArtefacts() {
            assertThat(SignOnResponse.USER_ID_LENGTH).isEqualTo(ORACLE_USER_ID_LENGTH);
            assertThat(SignOnResponse.USER_TYPE_LENGTH).isEqualTo(ORACLE_USER_TYPE_LENGTH);
            assertThat(SignOnResponse.SCREEN_FIELD_ID_LENGTH)
                    .isEqualTo(ORACLE_SCREEN_FIELD_ID_LENGTH);
            assertThat(SignOnResponse.MESSAGE_LENGTH).isEqualTo(ORACLE_MESSAGE_LENGTH);
            assertThat(SignOnResponse.SCREEN_MESSAGE_LENGTH)
                    .isEqualTo(ORACLE_SCREEN_MESSAGE_LENGTH);
            assertThat(SignOnResponse.TRANSACTION_NAME_LENGTH)
                    .isEqualTo(ORACLE_TRANSACTION_NAME_LENGTH);
            assertThat(SignOnResponse.PROGRAM_NAME_LENGTH).isEqualTo(ORACLE_EIGHT);
            assertThat(SignOnResponse.CURRENT_DATE_LENGTH).isEqualTo(ORACLE_EIGHT);
            assertThat(SignOnResponse.APPLICATION_ID_LENGTH).isEqualTo(ORACLE_EIGHT);
            assertThat(SignOnResponse.SYSTEM_ID_LENGTH).isEqualTo(ORACLE_EIGHT);
            assertThat(SignOnResponse.SCREEN_TITLE_LENGTH).isEqualTo(ORACLE_SCREEN_TITLE_LENGTH);
        }

        @Test
        @DisplayName("keep the nine-character rendered time as this mapset's own anomaly")
        void keepTheNineCharacterTimeAsAnAnomaly() {
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH).isEqualTo(ORACLE_CURRENT_TIME_LENGTH);
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH)
                    .isNotEqualTo(SignOnResponse.CURRENT_DATE_LENGTH);
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH)
                    .isNotEqualTo(MenuResponse.CURRENT_TIME_WIDTH);
            assertThat(MenuResponse.CURRENT_TIME_WIDTH).isEqualTo(ORACLE_EIGHT);
        }

        @Test
        @DisplayName("agree with every other contract on the identifier, the role code and the focus "
                + "hint")
        void agreeWithEveryOtherContract() {
            assertThat(SignOnResponse.USER_ID_LENGTH).isEqualTo(NavigationContext.USER_ID_LENGTH)
                    .isEqualTo(UserRequest.USER_ID_LENGTH)
                    .isEqualTo(UserResponse.USER_ID_LENGTH);
            assertThat(SignOnResponse.USER_TYPE_LENGTH)
                    .isEqualTo(NavigationContext.USER_TYPE_LENGTH)
                    .isEqualTo(UserRequest.USER_TYPE_LENGTH)
                    .isEqualTo(UserResponse.USER_TYPE_LENGTH);
            assertThat(SignOnResponse.SCREEN_FIELD_ID_LENGTH)
                    .isEqualTo(MenuResponse.SCREEN_FIELD_ID_WIDTH);
        }

        @Test
        @DisplayName("bound the identifier at eight and admit exactly eight")
        void boundTheIdentifierAtEight() {
            SignOnResponse atTheBound = new SignOnResponse(null, false, null, null, null,
                    "12345678", null, null, null, null, null, null, null, null, null);
            SignOnResponse overTheBound = new SignOnResponse(null, false, null, null, null,
                    "123456789", null, null, null, null, null, null, null, null, null);

            assertThat(violationsOf(atTheBound)).isEmpty();
            assertThat(violationsOf(overTheBound)).hasSize(1);
            assertThat(violationsOf(overTheBound).iterator().next().getPropertyPath())
                    .hasToString("userId");
        }

        @Test
        @DisplayName("bound the role code at one character and admit a code the record never defines")
        void boundTheRoleCodeAtOneCharacter() {
            SignOnResponse undeclaredCode = new SignOnResponse(null, false, null, null, null, null,
                    "Z", null, null, null, null, null, null, null, null);
            SignOnResponse twoCharacters = new SignOnResponse(null, false, null, null, null, null,
                    "AU", null, null, null, null, null, null, null, null);

            assertThat(violationsOf(undeclaredCode)).isEmpty();
            assertThat(undeclaredCode.userType()).isEqualTo("Z");
            assertThat(violationsOf(twoCharacters)).hasSize(1);
            assertThat(violationsOf(twoCharacters).iterator().next().getPropertyPath())
                    .hasToString("userType");
        }

        @Test
        @DisplayName("bound the focus hint at seven and leave the destination unbounded")
        void boundTheFocusHintAtSevenAndLeaveTheDestinationUnbounded() throws Exception {
            SignOnResponse overTheBound = new SignOnResponse(null, false, "EIGHTLET", null, null,
                    null, null, null, null, null, null, null, null, null, null);
            assertThat(violationsOf(overTheBound)).hasSize(1);
            assertThat(violationsOf(overTheBound).iterator().next().getPropertyPath())
                    .hasToString("focusScreenFieldId");

            Field route = SignOnResponse.class.getDeclaredField("nextRoute");
            assertThat(route.getAnnotations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The user-type code on the wire")
    class UserTypeOnTheWire {

        @Test
        @DisplayName("is the raw character the program compares, never the resolved role's name")
        void isTheRawCharacterAndNotTheRoleName() throws Exception {
            JsonNode administrator = payloadOf(successfulAdministratorSignOn());

            assertThat(administrator.get("userType").asText()).isEqualTo(ADMIN_CODE);
            assertThat(administrator.get("userType").asText())
                    .isNotEqualTo(UserType.ADMIN.name())
                    .isNotEqualTo(UserType.USER.name());
            assertThat(administrator.get("userType").asText()).hasSize(ORACLE_USER_TYPE_LENGTH);
        }

        @Test
        @DisplayName("is spelled exactly as the echoed navigation state spells it")
        void isSpelledAsTheEchoedNavigationStateSpellsIt() throws Exception {
            JsonNode payload = payloadOf(successfulAdministratorSignOn());

            assertThat(payload.get("userType").asText())
                    .isEqualTo(payload.get("navigationContext").get("userType").asText());
            assertThat(payload.get("userId").asText())
                    .isEqualTo(payload.get("navigationContext").get("userId").asText());
        }

        @Test
        @DisplayName("resolves through the navigation state's own lookup without ever throwing")
        void resolvesThroughTheNavigationStateLookup() {
            SignOnResponse response = successfulAdministratorSignOn();

            assertThat(response.navigationContext().resolvedUserType())
                    .contains(UserType.ADMIN);
            assertThat(new NavigationContext(null, null, null, null, null, "Z", null, null, null,
                    null, null, null, null, null, null, null).resolvedUserType()).isEmpty();
            assertThat(new NavigationContext(null, null, null, null, null,
                    ADMIN_CODE.toLowerCase(Locale.ROOT), null, null, null, null, null, null, null,
                    null, null, null).resolvedUserType()).isEmpty();
        }

        @Test
        @DisplayName("still routes when the stored code is not the administrator code")
        void stillRoutesWhenTheCodeIsNotTheAdministratorCode() {
            SignOnResponse standardUser = new SignOnResponse(null, false, null, "user-menu", null,
                    USER_ID, USER_CODE, null, null, null, null, null, null, null, null);

            assertThat(standardUser.userType()).isEqualTo(USER_CODE);
            assertThat(standardUser.nextRoute()).isEqualTo("user-menu");
        }
    }

    @Nested
    @DisplayName("The redisplay paths")
    class RedisplayPaths {

        @Test
        @DisplayName("echo the identifier so the operator need not retype what did not fail")
        void echoTheIdentifier() throws Exception {
            JsonNode payload = payloadOf(wrongCredentialRedisplay());

            assertThat(payload.get("userId").asText()).isEqualTo(USER_ID);
            assertThat(payload.get("message").asText())
                    .isEqualTo(SignOnResponse.MSG_WRONG_PASSWD);
            assertThat(payload.get("focusScreenFieldId").asText()).isEqualTo("PASSWD");
        }

        @Test
        @DisplayName("leave the failure flag lowered on the wrong-credential path, exactly as the "
                + "program does")
        void leaveTheFailureFlagLoweredOnTheWrongCredentialPath() throws Exception {
            JsonNode payload = payloadOf(wrongCredentialRedisplay());

            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.has("message")).isTrue();
        }

        @Test
        @DisplayName("omit the identifier on the first-entry path, where the program clears the whole "
                + "output area")
        void omitTheIdentifierOnFirstEntry() throws Exception {
            SignOnResponse firstEntry = new SignOnResponse(null, false, SCREEN_USER_ID_FIELD, null,
                    null, null, null, SignOnResponse.TRANSACTION_NAME, SignOnResponse.PROGRAM_NAME,
                    null, null, null, null, null, null);

            JsonNode payload = payloadOf(firstEntry);

            assertThat(payload.has("userId")).isFalse();
            assertThat(payload.has("userType")).isFalse();
            assertThat(payload.has("message")).isFalse();
            assertThat(payload.get("focusScreenFieldId").asText()).isEqualTo(SCREEN_USER_ID_FIELD);
        }

        @Test
        @DisplayName("carry the identifier through the wire unaltered, including trailing spaces")
        void carryTheIdentifierUnaltered() throws Exception {
            SignOnResponse padded = new SignOnResponse(null, false, null, null, null, "usr1    ",
                    " ", null, null, null, null, null, null, null, null);

            JsonNode payload = payloadOf(padded);

            assertThat(payload.get("userId").asText()).isEqualTo("usr1    ").hasSize(8);
            assertThat(payload.get("userType").asText()).isEqualTo(" ");
            assertThat(violationsOf(padded)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The wire shape")
    class WireShape {

        @Test
        @DisplayName("publishes no property named for a superseded spelling")
        void publishesNoSupersededSpelling() throws Exception {
            JsonNode payload = payloadOf(successfulAdministratorSignOn());

            assertThat(payload.has("fieldToFocus")).isFalse();
            assertThat(payload.has("route")).isFalse();
            assertThat(payload.has("navigation")).isFalse();
            assertThat(payload.has("screenTitle1")).isFalse();
            assertThat(payload.has("screenTitleLine1")).isFalse();
            assertThat(payload.has("titleLine1")).isFalse();
        }

        @Test
        @DisplayName("publishes no credential and no bearer artefact")
        void publishesNoCredentialAndNoBearerArtefact() throws Exception {
            String json = moduleEquivalentMapper()
                    .writeValueAsString(successfulAdministratorSignOn())
                    .toLowerCase(Locale.ROOT);

            assertThat(json).doesNotContain("passw")
                    .doesNotContain("credential")
                    .doesNotContain("secret")
                    .doesNotContain("token")
                    .doesNotContain("bearer");
        }

        @Test
        @DisplayName("round-trips through the module's shape without losing a component")
        void roundTripsWithoutLosingAComponent() throws Exception {
            ObjectMapper mapper = moduleEquivalentMapper();
            SignOnResponse original = successfulAdministratorSignOn();

            SignOnResponse back = mapper.readValue(mapper.writeValueAsString(original),
                    SignOnResponse.class);

            assertThat(back).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(back.userId()).isEqualTo(USER_ID);
            assertThat(back.userType()).isEqualTo(ADMIN_CODE);
        }

        @Test
        @DisplayName("names its own transaction and program and never a destination")
        void namesItsOwnTransactionAndProgram() {
            assertThat(SignOnResponse.TRANSACTION_NAME).isEqualTo("CC00")
                    .hasSize(ORACLE_TRANSACTION_NAME_LENGTH);
            assertThat(SignOnResponse.PROGRAM_NAME).isEqualTo("COSGN00C").hasSize(ORACLE_EIGHT);
            assertThat(SignOnResponse.TRANSACTION_NAME)
                    .isNotEqualTo(MenuResponse.USER_MENU_TRANSACTION_NAME)
                    .isNotEqualTo(MenuResponse.ADMIN_MENU_TRANSACTION_NAME);
        }
    }

    @Nested
    @DisplayName("The five message literals")
    class MessageLiterals {

        @Test
        @DisplayName("are reproduced character for character")
        void areReproducedCharacterForCharacter() {
            assertThat(SignOnResponse.MSG_PROMPT_USERID).isEqualTo("Please enter User ID ...");
            assertThat(SignOnResponse.MSG_PROMPT_PASSWD).isEqualTo("Please enter Password ...");
            assertThat(SignOnResponse.MSG_WRONG_PASSWD)
                    .isEqualTo("Wrong Password. Try again ...");
            assertThat(SignOnResponse.MSG_USER_NOT_FOUND)
                    .isEqualTo("User not found. Try again ...");
            assertThat(SignOnResponse.MSG_UNABLE_TO_VERIFY)
                    .isEqualTo("Unable to verify the User ...");
        }

        @Test
        @DisplayName("all fit inside the width this contract carries them at")
        void allFitInsideTheCarriedWidth() {
            List.of(SignOnResponse.MSG_PROMPT_USERID, SignOnResponse.MSG_PROMPT_PASSWD,
                            SignOnResponse.MSG_WRONG_PASSWD, SignOnResponse.MSG_USER_NOT_FOUND,
                            SignOnResponse.MSG_UNABLE_TO_VERIFY)
                    .forEach(text -> assertThat(text.length())
                            .isLessThanOrEqualTo(ORACLE_SCREEN_MESSAGE_LENGTH));
        }
    }
}
