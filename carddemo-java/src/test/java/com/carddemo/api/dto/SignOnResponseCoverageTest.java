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
import java.util.Set;

import com.carddemo.domain.enums.UserType;
import com.carddemo.service.MessageCatalogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SignOnResponse}, the response body of legacy transaction {@code CC00}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the sign-on response: the fifteen components, the twelve declared
 * widths, the three echoed identities, the five message texts the program emits itself, the wire form
 * under the module's declared serialisation settings, and the value semantics of a record that
 * carries no secret at all.
 *
 * <h2>The message texts are an external contract, so they are asserted character for character</h2>
 *
 * <p>Operators and downstream tooling match on these strings, which makes them interface rather than
 * presentation. Five of the seven texts the sign-on path can produce are declared on this type -
 * the two prompts and the three rejections - and the remaining two, the courtesy text on the exit key
 * and the unmapped-key text, come from the shared common-message catalog because the legacy program
 * takes them from a shared copybook rather than declaring its own. Tests below assert all five
 * verbatim, assert that the catalog supplies the other two, and prove the whole set of seven fits
 * inside the width the screen actually rendered - which is two characters narrower than the width the
 * program composed at, and is the one place a text could have been silently clipped.
 *
 * <h2>Nothing here is a secret, and one omission is the reason why</h2>
 *
 * <p>The response carries no credential, no credential digest and no credential-shaped component at
 * all: the submitted password is consumed by the authentication service and never echoed. A test
 * below proves the absence over the declared components rather than assuming it, because an echoed
 * credential is exactly the defect this contract exists to avoid. The generated record rendering
 * therefore stands, and the compensating assertion is that a nested {@link NavigationContext} - which
 * does carry identifying values - withholds its own.
 *
 * <h2>The route and the user-type character are the outcome, and both are explicit</h2>
 *
 * <p>The legacy program decided where to go next by transferring control to one of two programs
 * according to the stored user-type character. Here the outcome is data: a route the client calls
 * next, and the user-type character itself.
 *
 * <p>That character travels raw rather than as the domain enumeration, and that is the deliberate
 * choice rather than an omission. The user-security record stores one character, and the estate's
 * translation of it has no catch-all: a character outside the two the estate declares resolves to
 * nothing. Publishing a resolved role would therefore make such a character impossible to represent
 * on the boundary at all - it would fail to bind, and a reply describing a real stored row would be
 * unrepresentable. Carrying the character keeps the reply faithful to the row, keeps one spelling
 * across this contract, the navigation state and the user-administration contracts so a client can
 * echo a reply back without translating, and leaves the resolution to a role where it belongs, on the
 * navigation state. Tests below prove the component is a character rather than the enumeration, prove
 * each declared type crosses as its stored code, prove an undeclared character survives a round trip,
 * and prove the route is carried opaquely - this contract neither enumerates the routes nor validates
 * one, because doing so would put a fragment of the navigation table on the transport boundary.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>A pure unit test: no context, no connection, no container. Payloads come from
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in this package where the
 * module's four declared serialisation settings are written by hand. That evidences the shape this
 * type takes under those settings and makes no claim about the mapper a deployed instance holds;
 * {@link ApplicationJsonContractTest} is the in-boundary evidence for the deployed object.
 *
 * <p>Provenance: checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.
 */
@DisplayName("SignOnResponse :: sign-on response contract of legacy transaction CC00")
class SignOnResponseCoverageTest {

    /** The fifteen components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "message", "generalError", "focusScreenFieldId", "nextRoute", "navigationContext",
            "userId", "userType",
            "transactionName", "programName", "title01", "title02", "currentDate", "currentTime",
            "applicationId", "systemId");

    /** Width the program composed the message at, restated from its own work field. */
    private static final int EXPECTED_MESSAGE_WIDTH = 80;

    /** Width the screen actually rendered the message at, restated from the symbolic map. */
    private static final int EXPECTED_SCREEN_MESSAGE_WIDTH = 78;

    /** Widest symbolic field name in the sign-on mapset, restated from the mapset. */
    private static final int EXPECTED_FIELD_ID_WIDTH = 7;

    /** Declared width of the echoed transaction identifier. */
    private static final int EXPECTED_TRANSACTION_NAME_WIDTH = 4;

    /** Declared width of the echoed program name. */
    private static final int EXPECTED_PROGRAM_NAME_WIDTH = 8;

    /** Declared width of a screen title line. */
    private static final int EXPECTED_TITLE_WIDTH = 40;

    /** Declared width of the rendered current date. */
    private static final int EXPECTED_DATE_WIDTH = 8;

    /** Declared width of the rendered current time - the estate's only nine-character map item. */
    private static final int EXPECTED_TIME_WIDTH = 9;

    /** Declared width of the region's application identifier. */
    private static final int EXPECTED_APPLICATION_ID_WIDTH = 8;

    /** Declared width of the region's system identifier. */
    private static final int EXPECTED_SYSTEM_ID_WIDTH = 8;

    /** Declared width of the echoed operator entry, restated from the symbolic map. */
    private static final int EXPECTED_USER_ID_WIDTH = 8;

    /** Declared width of the stored user-type character, restated from the record layout. */
    private static final int EXPECTED_USER_TYPE_WIDTH = 1;

    /** An operator entry at exactly the declared width. */
    private static final String USER_ID = "ADMIN001";

    /** The transaction identifier this response echoes, restated independently. */
    private static final String EXPECTED_TRANSACTION_NAME = "CC00";

    /** The program name this response echoes, restated independently. */
    private static final String EXPECTED_PROGRAM_NAME = "COSGN00C";

    /** Shared validator factory, opened once and closed once. */
    private static ValidatorFactory validatorFactory;

    /** Validator drawn from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the validator factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the validator factory opened by {@link #openValidatorFactory()}. */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    /**
     * Supplies the five message texts the sign-on program declares itself, each restated here as an
     * independently authored literal rather than read from the type under test.
     *
     * <p>Package-private so that a nested test class can reference it by fully qualified name.
     *
     * @return the published constant paired with the text it must equal
     */
    static List<org.junit.jupiter.params.provider.Arguments> programMessageTexts() {
        return List.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        SignOnResponse.MSG_PROMPT_USERID, "Please enter User ID ..."),
                org.junit.jupiter.params.provider.Arguments.of(
                        SignOnResponse.MSG_PROMPT_PASSWD, "Please enter Password ..."),
                org.junit.jupiter.params.provider.Arguments.of(
                        SignOnResponse.MSG_WRONG_PASSWD, "Wrong Password. Try again ..."),
                org.junit.jupiter.params.provider.Arguments.of(
                        SignOnResponse.MSG_USER_NOT_FOUND, "User not found. Try again ..."),
                org.junit.jupiter.params.provider.Arguments.of(
                        SignOnResponse.MSG_UNABLE_TO_VERIFY, "Unable to verify the User ..."));
    }

    /**
     * Builds a response carrying only the named component, so a violation can be attributed.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a response carrying that one value
     */
    private static SignOnResponse carrying(String component, String value) {
        return new SignOnResponse(
                "message".equals(component) ? value : null,
                false,
                "focusScreenFieldId".equals(component) ? value : null,
                "nextRoute".equals(component) ? value : null,
                null,
                "userId".equals(component) ? value : null,
                "userType".equals(component) ? value : null,
                "transactionName".equals(component) ? value : null,
                "programName".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                "applicationId".equals(component) ? value : null,
                "systemId".equals(component) ? value : null);
    }

    /**
     * Builds the response an accepted sign-on produces for the supplied user type.
     *
     * <p>The reply carries the <em>stored one-character code</em> rather than the resolved role, so
     * this helper translates the domain constant into the character the record holds. That keeps the
     * parameterisation below readable while testing what actually crosses the wire.</p>
     *
     * <p>The echoed operator entry is absent on success, matching the program: the output area is
     * cleared, because a client that has just been admitted has no redisplay screen to rebuild.</p>
     *
     * @param userType the resolved user type, which may be {@code null}
     * @param nextRoute the route the client calls next
     * @return a fully populated success response
     */
    private static SignOnResponse accepted(UserType userType, String nextRoute) {
        return new SignOnResponse(null, false, null, nextRoute,
                JsonContractSupport.populatedNavigation(), null,
                userType == null ? null : userType.getCode(),
                EXPECTED_TRANSACTION_NAME, EXPECTED_PROGRAM_NAME,
                MessageCatalogService.CCDA_TITLE01, MessageCatalogService.CCDA_TITLE02,
                "08/02/26", "14:35:07", "CICSAWS1", "AWSSYS01");
    }

    /**
     * Builds the response a rejected sign-on produces.
     *
     * @param message the rejection text
     * @param focusScreenFieldId the symbolic field the cursor returns to
     * @return a fully populated failure response
     */
    private static SignOnResponse rejected(String message, String focusScreenFieldId) {
        return new SignOnResponse(message, true, focusScreenFieldId, null, null, USER_ID, null,
                EXPECTED_TRANSACTION_NAME, EXPECTED_PROGRAM_NAME,
                MessageCatalogService.CCDA_TITLE01, MessageCatalogService.CCDA_TITLE02,
                "08/02/26", "14:35:07", "CICSAWS1", "AWSSYS01");
    }

    /**
     * Serialises a response and parses the result back into a tree.
     *
     * @param response the response to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(SignOnResponse response) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the fifteen components are declared in the order the screen presents them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("the twelve published widths equal the widths the program and the map declare")
        void publishedWidthsEqualTheDeclaredWidths() {
            assertThat(SignOnResponse.MESSAGE_LENGTH).isEqualTo(EXPECTED_MESSAGE_WIDTH);
            assertThat(SignOnResponse.SCREEN_MESSAGE_LENGTH)
                    .isEqualTo(EXPECTED_SCREEN_MESSAGE_WIDTH);
            assertThat(SignOnResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(EXPECTED_FIELD_ID_WIDTH);
            assertThat(SignOnResponse.TRANSACTION_NAME_LENGTH)
                    .isEqualTo(EXPECTED_TRANSACTION_NAME_WIDTH);
            assertThat(SignOnResponse.PROGRAM_NAME_LENGTH).isEqualTo(EXPECTED_PROGRAM_NAME_WIDTH);
            assertThat(SignOnResponse.SCREEN_TITLE_LENGTH).isEqualTo(EXPECTED_TITLE_WIDTH);
            assertThat(SignOnResponse.CURRENT_DATE_LENGTH).isEqualTo(EXPECTED_DATE_WIDTH);
            assertThat(SignOnResponse.CURRENT_TIME_LENGTH).isEqualTo(EXPECTED_TIME_WIDTH);
            assertThat(SignOnResponse.APPLICATION_ID_LENGTH)
                    .isEqualTo(EXPECTED_APPLICATION_ID_WIDTH);
            assertThat(SignOnResponse.SYSTEM_ID_LENGTH).isEqualTo(EXPECTED_SYSTEM_ID_WIDTH);
            assertThat(SignOnResponse.USER_ID_LENGTH)
                    .as("the echoed operator entry is the map's own output item, so it carries the "
                            + "same width as the input item the operator typed into")
                    .isEqualTo(EXPECTED_USER_ID_WIDTH);
            assertThat(SignOnResponse.USER_TYPE_LENGTH)
                    .as("the stored user-type character is one byte in the record, and the reply "
                            + "carries the character rather than a resolved role")
                    .isEqualTo(EXPECTED_USER_TYPE_WIDTH);
        }

        @Test
        @DisplayName("the composed message width exceeds the rendered width by exactly two "
                + "characters, which is the only place a text could have been clipped")
        void theComposedWidthExceedsTheRenderedWidthByTwo() {
            assertThat(SignOnResponse.MESSAGE_LENGTH - SignOnResponse.SCREEN_MESSAGE_LENGTH)
                    .as("the program composed at the wider width and moved the value into a narrower "
                            + "map item, so the difference is recorded rather than enforced")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the echoed transaction identifier and program name are the sign-on pair, "
                + "published as constants rather than left to a caller")
        void theEchoedIdentitiesAreTheSignOnPair() {
            assertThat(SignOnResponse.TRANSACTION_NAME).isEqualTo(EXPECTED_TRANSACTION_NAME);
            assertThat(SignOnResponse.PROGRAM_NAME).isEqualTo(EXPECTED_PROGRAM_NAME);
            assertThat(SignOnResponse.TRANSACTION_NAME)
                    .hasSize(SignOnResponse.TRANSACTION_NAME_LENGTH);
            assertThat(SignOnResponse.PROGRAM_NAME).hasSize(SignOnResponse.PROGRAM_NAME_LENGTH);
        }

        @Test
        @DisplayName("no credential, credential digest or credential-shaped component is declared, "
                + "because the submitted password is consumed and never echoed")
        void noCredentialComponentIsDeclared() {
            List<String> declared = Arrays.stream(SignOnResponse.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(java.util.Locale.ROOT))
                    .toList();

            assertThat(declared)
                    .as("an echoed credential is exactly the defect this contract exists to avoid")
                    .noneMatch(name -> name.contains("password")
                            || name.contains("passwd")
                            || name.contains("secret")
                            || name.contains("credential")
                            || name.contains("hash")
                            || name.contains("digest"));
        }

        @Test
        @DisplayName("the user-type character is carried raw rather than as the domain enumeration, "
                + "and the error indicator is a primitive rather than a derived value")
        void theOutcomeComponentsAreTyped() {
            RecordComponent[] components = SignOnResponse.class.getRecordComponents();

            assertThat(components[1].getType()).isEqualTo(boolean.class);
            assertThat(components[4].getType()).isEqualTo(NavigationContext.class);
            assertThat(components[5].getType())
                    .as("the echoed operator entry is the characters the operator typed, never a "
                            + "resolved identity")
                    .isEqualTo(String.class);
            assertThat(components[6].getType())
                    .as("the stored character travels raw, so a code outside the two the estate "
                            + "declares survives the round trip instead of failing to bind; the "
                            + "resolution to a role belongs to the navigation state, which exposes "
                            + "it through resolvedUserType()")
                    .isEqualTo(String.class);
            assertThat(components[3].getType())
                    .as("the route is carried opaquely, because enumerating the routes here would "
                            + "put a fragment of the navigation table on the transport boundary")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("no member is declared beyond the fifteen accessors, so this type computes "
                + "nothing")
        void noMemberIsDeclaredBeyondTheAccessors() {
            List<String> instanceMethods = Arrays.stream(SignOnResponse.class.getDeclaredMethods())
                    .filter(method -> !java.lang.reflect.Modifier.isStatic(method.getModifiers()))
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
                    .toList();

            assertThat(instanceMethods).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }
    }

    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        @ParameterizedTest(name = "the published text equals \"{1}\"")
        @MethodSource("com.carddemo.api.dto.SignOnResponseCoverageTest#programMessageTexts")
        @DisplayName("each of the five texts the program emits itself is reproduced character for "
                + "character, because operators and tooling match on them")
        void eachProgramTextIsReproducedVerbatim(String published, String expected) {
            assertThat(published).isEqualTo(expected);
        }

        @ParameterizedTest(name = "\"{1}\" survives the two-character narrowing")
        @MethodSource("com.carddemo.api.dto.SignOnResponseCoverageTest#programMessageTexts")
        @DisplayName("no text the program emits is long enough to be clipped by the narrower map "
                + "item, so the recorded width difference affects nothing")
        void noProgramTextIsClippedByTheNarrowerItem(String published, String expected) {
            assertThat(published.length())
                    .as("clipping would change an external contract silently")
                    .isLessThanOrEqualTo(SignOnResponse.SCREEN_MESSAGE_LENGTH);
            assertThat(expected).isNotBlank();
        }

        @Test
        @DisplayName("the five texts are distinct, so a client can tell the two prompts and the "
                + "three rejections apart")
        void theFiveTextsAreDistinct() {
            assertThat(List.of(SignOnResponse.MSG_PROMPT_USERID, SignOnResponse.MSG_PROMPT_PASSWD,
                            SignOnResponse.MSG_WRONG_PASSWD, SignOnResponse.MSG_USER_NOT_FOUND,
                            SignOnResponse.MSG_UNABLE_TO_VERIFY))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the wrong-password text and the user-not-found text are separate, which "
                + "reproduces the legacy behaviour rather than a hardened one")
        void theTwoRejectionTextsAreSeparate() {
            assertThat(SignOnResponse.MSG_WRONG_PASSWD)
                    .as("collapsing them into one text would be a security improvement the estate "
                            + "does not make, and this migration reproduces observable behaviour")
                    .isNotEqualTo(SignOnResponse.MSG_USER_NOT_FOUND);
        }

        @Test
        @DisplayName("the courtesy text and the unmapped-key text are supplied by the shared "
                + "catalog rather than declared here, completing the set of seven")
        void theTwoCommonTextsComeFromTheSharedCatalog() {
            assertThat(MessageCatalogService.CCDA_MSG_THANK_YOU)
                    .as("the legacy program takes both from a shared copybook rather than declaring "
                            + "its own, so declaring them here would duplicate the catalog")
                    .startsWith("Thank you for using CardDemo application...");
            assertThat(MessageCatalogService.CCDA_MSG_INVALID_KEY)
                    .startsWith("Invalid key pressed. Please see below...");
        }

        @Test
        @DisplayName("both catalog texts also fit inside the width the screen rendered, so all seven "
                + "sign-on texts survive the narrowing")
        void bothCatalogTextsAlsoFitTheRenderedWidth() {
            assertThat(MessageCatalogService.CCDA_MSG_THANK_YOU.length())
                    .isLessThanOrEqualTo(SignOnResponse.SCREEN_MESSAGE_LENGTH);
            assertThat(MessageCatalogService.CCDA_MSG_INVALID_KEY.length())
                    .isLessThanOrEqualTo(SignOnResponse.SCREEN_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the catalog pads its common texts to the shared width, so their trailing "
                + "spaces are part of the value rather than an accident")
        void theCatalogPadsItsCommonTextsToTheSharedWidth() {
            assertThat(MessageCatalogService.CCDA_MSG_THANK_YOU)
                    .hasSize(MessageCatalogService.COMMON_MESSAGE_WIDTH);
            assertThat(MessageCatalogService.CCDA_MSG_INVALID_KEY)
                    .hasSize(MessageCatalogService.COMMON_MESSAGE_WIDTH);
            assertThat(MessageCatalogService.COMMON_MESSAGE_WIDTH)
                    .as("the shared width is comfortably inside the width this response carries")
                    .isLessThan(SignOnResponse.MESSAGE_LENGTH);
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("an accepted response whose every component sits at or inside its declared "
                + "width reports no violation")
        void anAcceptedResponseReportsNoViolation() {
            assertThat(validator.validate(accepted(UserType.ADMIN, "/api/admin/menu"))).isEmpty();
        }

        @Test
        @DisplayName("a rejected response carrying a message and a focus field reports no violation")
        void aRejectedResponseReportsNoViolation() {
            assertThat(validator.validate(
                            rejected(SignOnResponse.MSG_WRONG_PASSWD, "PASSWD")))
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @CsvSource({
            "message,80",
            "focusScreenFieldId,7",
            "userId,8",
            "userType,1",
            "transactionName,4",
            "programName,8",
            "title01,40",
            "title02,40",
            "currentDate,8",
            "currentTime,9",
            "applicationId,8",
            "systemId,8",
        })
        @DisplayName("each bounded component reports a value one character over its width")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            Set<ConstraintViolation<SignOnResponse>> violations =
                    validator.validate(carrying(component, "X".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @ParameterizedTest(name = "{0} accepts a value exactly at {1}")
        @CsvSource({
            "message,80",
            "focusScreenFieldId,7",
            "userId,8",
            "userType,1",
            "currentTime,9",
            "title01,40",
        })
        @DisplayName("each bounded component accepts a value exactly at its width, so the bound is "
                + "inclusive")
        void eachBoundedComponentAcceptsAValueAtItsWidth(String component, int width) {
            assertThat(validator.validate(carrying(component, "X".repeat(width)))).isEmpty();
        }

        @Test
        @DisplayName("the route is unbounded, because a route is a module-internal identifier rather "
                + "than a screen item of a measured width")
        void theRouteIsUnbounded() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(512)))).isEmpty();
        }

        @Test
        @DisplayName("an entirely empty response reports no violation, because a response with "
                + "nothing to say is the state before anything has happened")
        void anEntirelyEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(
                            new SignOnResponse(null, false, null, null, null, null, null, null,
                                    null, null, null, null, null, null, null)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "message \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"", " ", "                    "})
        @DisplayName("a blank message is accepted, because a screen with no message to display is "
                + "the ordinary first-entry state")
        void aBlankMessageIsAccepted(String message) {
            assertThat(validator.validate(carrying("message", message))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        /**
         * An accepted reply publishes every populated member, the user type as its stored character.
         *
         * <p>The type crosses as the one character the user-security record stores rather than under
         * the constant's name. That is the same spelling the navigation state and the
         * user-administration contracts carry, so a client can echo a reply straight back without
         * translating, and it is the only spelling in which a character the estate does not declare can
         * be represented at all. The test immediately below this one is the authority for the whole
         * enumeration and for the undeclared-character case; this assertion pins the one value the
         * populated fixture carries so that a reader of the accepted-reply shape sees the spelling in
         * place rather than having to look it up.</p>
         */
        @Test
        @DisplayName("an accepted response renders every populated member, and the resolved user "
                + "type crosses as the stored one-character code")
        void anAcceptedResponseRendersItsPopulatedMembers() throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted(UserType.ADMIN, "/api/admin/menu"));

            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("nextRoute").asText()).isEqualTo("/api/admin/menu");
            assertThat(payload.get("userType").asText())
                    .as("the stored character, not the constant name")
                    .isEqualTo(UserType.ADMIN.getCode());
            assertThat(payload.get("transactionName").asText())
                    .isEqualTo(EXPECTED_TRANSACTION_NAME);
            assertThat(payload.get("programName").asText()).isEqualTo(EXPECTED_PROGRAM_NAME);
            assertThat(payload.get("currentTime").asText()).isEqualTo("14:35:07");
            assertThat(payload.has("message"))
                    .as("an accepted sign-on has nothing to say, and an absent value is omitted")
                    .isFalse();
        }

        @ParameterizedTest(name = "{0} crosses as its stored one-character code")
        @EnumSource(UserType.class)
        @DisplayName("each declared user type crosses the wire as the stored one-character code "
                + "rather than under its constant name")
        void eachUserTypeCrossesAsItsStoredCode(UserType userType) throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted(userType, "/api/menu"));

            assertThat(payload.get("userType").asText())
                    .as("the reply carries the character the record stores, because the same "
                            + "spelling is what the navigation state and the user-administration "
                            + "contracts carry, and a client that echoes the reply back must be "
                            + "able to do so without translating")
                    .isEqualTo(userType.getCode())
                    .hasSize(SignOnResponse.USER_TYPE_LENGTH);
            assertThat(payload.get("userType").asText())
                    .as("naming the constant here would put a resolved role on the boundary, which "
                            + "would make an undeclared code impossible to represent at all")
                    .isNotEqualTo(userType.name());
        }

        @ParameterizedTest(name = "the undeclared code \"{0}\" survives the round trip")
        @ValueSource(strings = {"Z", "a", "0", " "})
        @DisplayName("a user-type character outside the two the estate declares survives the round "
                + "trip, because the reply carries the stored character rather than a resolved role")
        void anUndeclaredUserTypeCodeSurvivesTheRoundTrip(String code)
                throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            SignOnResponse response = new SignOnResponse(null, false, null, "/api/menu", null,
                    USER_ID, code, EXPECTED_TRANSACTION_NAME, EXPECTED_PROGRAM_NAME,
                    null, null, null, null, null, null);

            SignOnResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), SignOnResponse.class);

            assertThat(returned.userType())
                    .as("refusing the value here would decide a routing question the estate leaves "
                            + "to the administrator-code comparison, which treats every other code "
                            + "as a standard user")
                    .isEqualTo(code);
        }

        @Test
        @DisplayName("the operator's own entry is echoed on a rejection and absent on success, "
                + "because only a redisplay screen needs to be rebuilt")
        void theOperatorEntryIsEchoedOnlyWhenAScreenMustBeRebuilt() throws JsonProcessingException {
            assertThat(payloadOf(rejected(SignOnResponse.MSG_WRONG_PASSWD, "PASSWD"))
                            .get("userId").asText())
                    .isEqualTo(USER_ID);
            assertThat(payloadOf(accepted(UserType.ADMIN, "/api/admin/menu")).has("userId"))
                    .as("an admitted operator has no screen to redisplay, so the output area is "
                            + "cleared rather than echoed")
                    .isFalse();
        }

        @Test
        @DisplayName("an unresolved user type is omitted rather than defaulted, so a character "
                + "outside the two the estate declares resolves to nothing")
        void anUnresolvedUserTypeIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted(null, "/api/menu"));

            assertThat(payload.has("userType"))
                    .as("substituting a default would grant or withhold administrative reach on a "
                            + "value the estate never resolved")
                    .isFalse();
        }

        @Test
        @DisplayName("the boolean error indicator is always written, because a primitive has no "
                + "absent state to omit")
        void theBooleanErrorIndicatorIsAlwaysWritten() throws JsonProcessingException {
            assertThat(payloadOf(rejected(SignOnResponse.MSG_USER_NOT_FOUND, "USERID"))
                            .get("generalError").asBoolean())
                    .isTrue();
            assertThat(payloadOf(accepted(UserType.USER, "/api/menu"))
                            .get("generalError").asBoolean())
                    .isFalse();
        }

        @Test
        @DisplayName("a rejection renders its text and its focus field, so a client can reproduce "
                + "the screen the operator saw")
        void aRejectionRendersItsTextAndFocusField() throws JsonProcessingException {
            JsonNode payload = payloadOf(rejected(SignOnResponse.MSG_WRONG_PASSWD, "PASSWD"));

            assertThat(payload.get("message").asText()).isEqualTo(SignOnResponse.MSG_WRONG_PASSWD);
            assertThat(payload.get("focusScreenFieldId").asText()).isEqualTo("PASSWD");
            assertThat(payload.has("nextRoute"))
                    .as("a rejection goes nowhere, so the route is absent rather than empty")
                    .isFalse();
        }

        @Test
        @DisplayName("the title lines round trip with their trailing spaces intact, because they are "
                + "fixed-width screen values")
        void theTitleLinesRoundTripWithTheirTrailingSpaces() throws JsonProcessingException {
            JsonNode payload = payloadOf(accepted(UserType.USER, "/api/menu"));

            assertThat(payload.get("title01").asText())
                    .isEqualTo(MessageCatalogService.CCDA_TITLE01)
                    .hasSize(SignOnResponse.SCREEN_TITLE_LENGTH);
            assertThat(payload.get("title02").asText())
                    .isEqualTo(MessageCatalogService.CCDA_TITLE02)
                    .hasSize(SignOnResponse.SCREEN_TITLE_LENGTH);
        }

        @Test
        @DisplayName("a fully populated response round trips unchanged, nested navigation state "
                + "included")
        void aFullyPopulatedResponseRoundTripsUnchanged() throws JsonProcessingException {
            SignOnResponse response = accepted(UserType.ADMIN, "/api/admin/menu");
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();

            SignOnResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), SignOnResponse.class);

            assertThat(returned).isEqualTo(response);
            assertThat(returned.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        @Test
        @DisplayName("no credential member appears on the wire under any name, which is the property "
                + "a client can actually observe")
        void noCredentialMemberAppearsOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(rejected(SignOnResponse.MSG_WRONG_PASSWD, "PASSWD"));

            payload.fieldNames().forEachRemaining(name ->
                    assertThat(name.toLowerCase(java.util.Locale.ROOT))
                            .doesNotContain("password")
                            .doesNotContain("passwd")
                            .doesNotContain("secret")
                            .doesNotContain("credential"));
        }

        @Test
        @DisplayName("an unknown member is ignored rather than rejected, so a client may echo the "
                + "response back without being refused")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"userType\":\"U\",\"password\":\"whatever\",\"rows\":[]}";

            SignOnResponse returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, SignOnResponse.class);

            assertThat(returned.userType()).isEqualTo(UserType.USER.getCode());
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering is the one the record contract generates, because this response "
                + "carries no secret of its own")
        void theRenderingIsTheGeneratedOne() {
            assertThat(rejected(SignOnResponse.MSG_WRONG_PASSWD, "PASSWD").toString())
                    .startsWith("SignOnResponse[")
                    .contains("message=" + SignOnResponse.MSG_WRONG_PASSWD)
                    .contains("generalError=true")
                    .contains("focusScreenFieldId=PASSWD")
                    .doesNotContain("REDACTED");
        }

        @Test
        @DisplayName("a nested navigation state withholds its own identifying values, so this type "
                + "cannot become the path by which they surface")
        void aNestedNavigationStateWithholdsItsOwnValues() {
            assertThat(accepted(UserType.ADMIN, "/api/admin/menu").toString())
                    .contains("navigationContext=NavigationContext[")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component by value, the resolved user type included")
        void equalityComparesEveryComponentByValue() {
            SignOnResponse asAdmin = accepted(UserType.ADMIN, "/api/admin/menu");
            SignOnResponse alsoAdmin = accepted(UserType.ADMIN, "/api/admin/menu");
            SignOnResponse asUser = accepted(UserType.USER, "/api/admin/menu");

            assertThat(asAdmin).isEqualTo(alsoAdmin).hasSameHashCodeAs(alsoAdmin);
            assertThat(asAdmin)
                    .as("the resolved type is what decides administrative reach, so it must "
                            + "participate in equality")
                    .isNotEqualTo(asUser);
        }

        @Test
        @DisplayName("a blank message and an absent message do not compare equal, because the screen "
                + "item is fixed-width and blank-significant")
        void aBlankMessageIsNotAnAbsentMessage() {
            assertThat(carrying("message", "")).isNotEqualTo(carrying("message", null));
        }

        @Test
        @DisplayName("the error indicator participates in equality, so a rejection and an acceptance "
                + "carrying the same text are distinguishable")
        void theErrorIndicatorParticipatesInEquality() {
            SignOnResponse flagged = new SignOnResponse("same text", true, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
            SignOnResponse unflagged = new SignOnResponse("same text", false, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            assertThat(flagged).isNotEqualTo(unflagged);
        }
    }
}
