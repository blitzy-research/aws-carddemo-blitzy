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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link UserResponse}, the single response body shared by the four administrative
 * user transactions - {@code CU00} list, {@code CU01} add, {@code CU02} update and {@code CU03}
 * delete - translated from {@code app/cbl/COUSR00C.cbl}, {@code COUSR01C.cbl}, {@code COUSR02C.cbl}
 * and {@code COUSR03C.cbl} against the field contract of {@code app/cpy-bms/COUSR00.CPY} and its
 * three siblings, all four being views of the eighty-byte record {@code app/cpy/CSUSR01Y.cpy}.
 *
 * <p>What this file pins, and why each is easy to break:
 *
 * <ul>
 *   <li><strong>The ten-row list contract at the map's widths</strong> - one, eight, twenty, twenty
 *       and one. The list program's own staging table describes the same ten rows with a single
 *       combined twenty-five-character name and an eight-wide type, but that is a terminal display
 *       line and not the contract, so a twenty-five-character name and an eight-character type are
 *       both refused.</li>
 *   <li><strong>The plural selection message.</strong> This screen names two valid characters in the
 *       plural over forty-three characters; the structurally similar text on the transaction-list
 *       screen names one in the singular over thirty-five. Harmonising them is a one-character edit
 *       and a contract break, so the two are asserted unequal.</li>
 *   <li><strong>Twenty-five message literals at their exact lengths.</strong> The emptiness family
 *       ends in three dots with no preceding space; the prompts and the no-change advisory have one.
 *       The two function-key prompts are thirty-eight and thirty-seven characters and are not
 *       unified.</li>
 *   <li><strong>The three composed confirmations</strong> - a five-character prefix carrying a
 *       trailing space, the identifier stripped of its padding, and a fragment carrying a leading
 *       space, so exactly one space sits on each side of the identifier. Every expectation is built
 *       by concatenating those literal pieces inside the test; no production formatter is asked to
 *       produce an expected value.</li>
 *   <li><strong>A message does not imply a failure.</strong> Two legacy arms set a message and
 *       continue, so the failure indicator is an explicit component, never derived from whether a
 *       message is present.</li>
 *   <li><strong>The delete failure reports the update verb</strong> - a preserved source defect,
 *       asserted identical to the update program's text so that a future correction breaks a test
 *       rather than the contract.</li>
 *   <li><strong>No credential crosses this contract in any form</strong> - not as a value, masked,
 *       hashed, as a length or as a presence flag. Where a credential-shaped value is needed at all
 *       this file uses a synthetic one.</li>
 * </ul>
 *
 * <p>A pure unit test with no context, container, database, security type or shared helper. Nothing
 * inspects a component, accessor, annotation or constant through the run-time class model: the
 * inventory comes from the serialized payload of a fully populated instance and the widths from the
 * behaviour of a bean validator, so this file names no member it did not first read out of the
 * production source. Payloads come from a mapper built locally to match
 * {@code src/main/resources/application.yml}.
 */
@DisplayName("UserResponse :: the shared contract of legacy transactions CU00, CU01, CU02 and CU03")
class UserResponseTest {
    private static final String TRANSACTION_LIST_SINGULAR_SELECTION_TEXT =
            "Invalid selection. Valid value is S";

    private static final String REDACTED = "***REDACTED***";

    private static final String USER_ID = "ABCD1234";

    private static final String SHORT_USER_ID = "AB12";

    private static final String FIRST_NAME = "MARY ANN";

    private static final String LAST_NAME = "o'HARA-smith";

    private static final String ADMIN_CODE = "A";

    private static final String USER_CODE = "U";

    private static final String TRANSACTION_NAME = "CU00";

    private static final String TITLE_01 = "AWS Mainframe Modernization";

    private static final String TITLE_02 = "CardDemo";

    private static final String CURRENT_DATE = "07/19/22";

    private static final String CURRENT_TIME = "19:27:53";

    private static final String PROGRAM_NAME = "COUSR00C";

    private static final String FOCUS_FIELD = "USRIDIN";

    private static final String NEXT_ROUTE = "/api/v1/admin/users";

    private static final String FIRST_CURSOR_KEY = "00000001";

    private static final String LAST_CURSOR_KEY = "00000010";

    private static final String DISPLAYED_PAGE_NUMBER = "00000003";

    private static final List<String> RESPONSE_COMPONENTS = List.of(
            "rows", "pageMetadata", "userId", "firstName", "lastName", "userType",
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "message", "fieldErrors", "generalError", "actionSucceeded", "focusScreenFieldId",
            "nextRoute", "navigationContext");

    private static final List<String> ROW_COMPONENTS =
            List.of("selector", "userId", "firstName", "lastName", "userType");

    private static final List<String> CREDENTIAL_SPELLINGS = List.of(
            "password", "Password", "passwordHash", "encodedPassword", "pwd", "Pwd", "secUsrPwd",
            "credential", "Credential", "secret", "Secret", "salt", "Salt", "digest", "Digest",
            "hash", "Hash", "passwordSet", "hasPassword", "passwordLength");

    private static Set<ConstraintViolation<UserResponse>> violationsOf(UserResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    private static Set<ConstraintViolation<UserResponse.UserRow>> violationsOf(
            UserResponse.UserRow row) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(row);
        }
    }

    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(
                                JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static JsonNode payloadOf(UserResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static List<String> keysOf(JsonNode payload) {
        List<String> keys = new ArrayList<>();
        payload.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static UserResponse.UserRow rowOf(String selector, String userId, String userType) {
        return new UserResponse.UserRow(selector, userId, FIRST_NAME, LAST_NAME, userType);
    }

    private static List<UserResponse.UserRow> rows(int count) {
        List<UserResponse.UserRow> built = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            built.add(rowOf(null, "ROWUSR" + (index / 10) + (index % 10), USER_CODE));
        }
        return built;
    }

    private static PageMetadata forwardPage() {
        return PageMetadata.forward(PageMetadata.USER_LIST_PAGE_SIZE, FIRST_CURSOR_KEY,
                LAST_CURSOR_KEY, true, false, DISPLAYED_PAGE_NUMBER);
    }

    private static NavigationContext navigation() {
        return new NavigationContext(TRANSACTION_NAME, PROGRAM_NAME, "CU02", "COUSR02C", USER_ID,
                ADMIN_CODE, NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN",
                "SMITH", "00000000011", "Y", "0000000000000011", "COUSR0A", "COUSR00");
    }

    private static List<ErrorResponse.FieldError> fieldErrors() {
        return List.of(new ErrorResponse.FieldError("userId", FOCUS_FIELD,
                ErrorResponse.FieldState.MISSING, UserResponse.MSG_ADD_USER_ID_EMPTY));
    }

    private static UserResponse populated() {
        return new UserResponse(rows(1), forwardPage(), USER_ID, FIRST_NAME, LAST_NAME, ADMIN_CODE,
                TRANSACTION_NAME, TITLE_01, CURRENT_DATE, PROGRAM_NAME, TITLE_02, CURRENT_TIME,
                UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER, fieldErrors(), true, false,
                FOCUS_FIELD, NEXT_ROUTE, navigation());
    }

    private static UserResponse reporting(String message, boolean generalError) {
        return new UserResponse(null, null, null, null, null, null, null, null, null, null, null,
                null, message, null, generalError, false, null, null, null);
    }

    private static UserResponse carrying(List<UserResponse.UserRow> carried) {
        return new UserResponse(carried, null, null, null, null, null, null, null, null, null, null,
                null, null, null, false, false, null, null, null);
    }

    private static UserResponse empty() {
        return new UserResponse(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, false, false, null, null, null);
    }

    private static String widthOf(int length) {
        return "X".repeat(length);
    }

    private static String ownRendering(UserResponse response) {
        String rendered = response.toString();
        return (response.navigationContext() == null)
                ? rendered
                : rendered.replace(response.navigationContext().toString(), "<delegated>");
    }

    private static void assertMessageContract(
            String constant, String expectedText, int expectedLength) {
        assertThat(constant)
                .as("the published constant must reproduce the source text byte for byte")
                .isEqualTo(expectedText)
                .hasSize(expectedLength);
        assertThat(reporting(constant, true).message())
                .as("the message component must carry the text untouched")
                .isEqualTo(expectedText)
                .hasSize(expectedLength);
    }

    @Nested
    @DisplayName("the list surface carries rows shaped by the map, never by the staging table")
    class ScreenRowContract {
        @Test
        @DisplayName("publishes no row count of its own, leaving the paging contract the single place "
                + "that states how many rows this screen presents")
        void publishesNoRowCountOfItsOwn() {
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE)
                    .describedAs("the paging contract states this screen's row count once; a count "
                            + "published on the response body would be a competing source of truth "
                            + "for the same measurement, and a reference to one on UserResponse would "
                            + "fail to compile rather than fail here, which is the strongest "
                            + "available proof that none exists")
                    .isEqualTo(10);
            assertThat(PageMetadata.LARGEST_SCREEN_PAGE_SIZE)
                    .describedAs("the widest screen in the estate is stated in the same one place")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("accepts a collection filled to the row count the map declares")
        void acceptsACollectionFilledToTheRowCount() {
            UserResponse response = carrying(rows(PageMetadata.USER_LIST_PAGE_SIZE));

            assertThat(response.rows()).hasSize(PageMetadata.USER_LIST_PAGE_SIZE);
            assertThat(response.hasRows()).isTrue();
        }

        @Test
        @DisplayName("carries a collection longer than the screen's row count untouched, because how "
                + "many rows fit is the paging contract's measurement and not this type's")
        void carriesACollectionLongerThanTheRowCountUntouched() {
            List<UserResponse.UserRow> overLong = rows(PageMetadata.LARGEST_SCREEN_PAGE_SIZE + 1);

            UserResponse response = carrying(overLong);

            assertThat(response.rows())
                    .describedAs("nothing is refused and nothing is truncated, so an over-long page "
                            + "is reported by whoever knows the screen rather than lost here")
                    .hasSize(overLong.size());
        }

        @Test
        @DisplayName("leaves a partial page partial and pads nothing")
        void leavesAPartialPagePartial() {
            UserResponse response = carrying(rows(2));

            assertThat(response.rows()).hasSize(2);
            assertThat(response.rows())
                    .as("a short page must not be topped up with blank rows")
                    .doesNotContainNull();
        }

        @Test
        @DisplayName("preserves the supplied order exactly, sorting, reversing and de-duplicating "
                + "nothing")
        void preservesTheSuppliedOrderExactly() {
            UserResponse.UserRow third = rowOf("U", "ROWUSR03", USER_CODE);
            UserResponse.UserRow first = rowOf(null, "ROWUSR01", ADMIN_CODE);
            UserResponse.UserRow duplicate = rowOf(null, "ROWUSR01", ADMIN_CODE);

            UserResponse response = carrying(List.of(third, first, duplicate));

            assertThat(response.rows()).containsExactly(third, first, duplicate);
        }

        @Test
        @DisplayName("emits each row with exactly the five components the map declares")
        void emitsEachRowWithExactlyFiveComponents() throws JsonProcessingException {
            JsonNode rows =
                    payloadOf(carrying(List.of(rowOf("U", USER_ID, USER_CODE)))).get("rows");

            assertThat(rows.isArray()).isTrue();
            assertThat(rows).hasSize(1);
            assertThat(keysOf(rows.get(0)))
                    .as("the five map fields and no sixth component of any kind")
                    .containsExactlyInAnyOrderElementsOf(ROW_COMPONENTS)
                    .hasSize(ROW_COMPONENTS.size());
        }

        @Test
        @DisplayName("declares no filler component, because a payload is a named structure")
        void declaresNoFillerComponent() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(keysOf(payload.get("rows").get(0)))
                    .doesNotContain("filler", "FILLER", "filler01", "pad", "gap");
            assertThat(keysOf(payload))
                    .doesNotContain("filler", "FILLER", "pad", "gap", "tioa");
        }

        @Test
        @DisplayName("bounds each row value at the map's width: one, eight, twenty, twenty and one")
        void boundsEachRowValueAtTheMapWidth() {
            UserResponse.UserRow atTheBound = new UserResponse.UserRow(
                    widthOf(UserResponse.SELECTOR_LENGTH),
                    widthOf(UserResponse.USER_ID_LENGTH),
                    widthOf(UserResponse.FIRST_NAME_LENGTH),
                    widthOf(UserResponse.LAST_NAME_LENGTH),
                    widthOf(UserResponse.USER_TYPE_LENGTH));

            assertThat(violationsOf(atTheBound)).isEmpty();
            assertThat(UserResponse.SELECTOR_LENGTH).isEqualTo(1);
            assertThat(UserResponse.USER_ID_LENGTH).isEqualTo(8);
            assertThat(UserResponse.FIRST_NAME_LENGTH).isEqualTo(20);
            assertThat(UserResponse.LAST_NAME_LENGTH).isEqualTo(20);
            assertThat(UserResponse.USER_TYPE_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses the staging table's twenty-five-character name, which is a display "
                + "line and not the contract")
        void refusesTheStagingTablesCombinedName() {
            UserResponse.UserRow overWide = new UserResponse.UserRow(null, null, widthOf(25),
                    widthOf(25), null);

            assertThat(violationsOf(overWide))
                    .as("the map keeps the two names separate at twenty characters each")
                    .hasSize(2)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isIn("firstName", "lastName"));
        }

        @Test
        @DisplayName("refuses the staging table's eight-character type, which the map declares at "
                + "one")
        void refusesTheStagingTablesWideType() {
            UserResponse.UserRow overWide = rowOf(null, null, widthOf(8));

            assertThat(violationsOf(overWide))
                    .hasSize(1)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("userType"));
        }

        @Test
        @DisplayName("carries the echoed identifier filter and the page indicator at eight "
                + "characters each")
        void carriesTheFilterAndPageIndicatorAtEightCharacters() {
            assertThat(UserResponse.USER_ID_LENGTH)
                    .as("the identifier-input field of the list map")
                    .isEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);

            UserResponse response = new UserResponse(null, forwardPage(), USER_ID, null, null, null,
                    null, null, null, null, null, null, null, null, false, false, null, null, null);

            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.pageMetadata().displayedPageNumber())
                    .isEqualTo(DISPLAYED_PAGE_NUMBER);
        }

        @Test
        @DisplayName("bounds the message line at seventy-eight characters, the width all four "
                + "administrative maps declare")
        void boundsTheMessageLineAtSeventyEight() {
            assertThat(UserResponse.MESSAGE_LENGTH)
                    .as("the administrative maps declare 78; the two card maps declare 80 and are "
                            + "deliberately not normalised toward this width")
                    .isEqualTo(78);
            assertThat(violationsOf(reporting(widthOf(UserResponse.MESSAGE_LENGTH), true)))
                    .isEmpty();
            assertThat(violationsOf(reporting(widthOf(UserResponse.MESSAGE_LENGTH + 1), true)))
                    .hasSize(1);
        }

        @Test
        @DisplayName("declares no terminal artefact: no attribute, no coordinate and no key legend")
        void declaresNoTerminalArtefact() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys).doesNotContain("attribute", "attributeByte", "colour", "color",
                    "highlight", "marker", "cursor", "cursorPosition", "row", "column",
                    "keyLegend", "functionKeys", "pfKeys", "map", "mapset");
        }
    }

    @Nested
    @DisplayName("the selection rejection is plural and is never harmonised with the singular one")
    class SelectionMessageIsPlural {
        @Test
        @DisplayName("reproduces the forty-three-character plural text byte for byte")
        void reproducesThePluralTextByteForByte() {
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid values are U and D")
                    .hasSize(43);
        }

        @Test
        @DisplayName("differs from the transaction list's singular text, which is never shared")
        void differsFromTheTransactionListSingularText() {
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .as("harmonising the two texts is a one-word edit and a contract break")
                    .isNotEqualTo(TRANSACTION_LIST_SINGULAR_SELECTION_TEXT);
            assertThat(TRANSACTION_LIST_SINGULAR_SELECTION_TEXT).hasSize(35);
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION.length())
                    .isNotEqualTo(TRANSACTION_LIST_SINGULAR_SELECTION_TEXT.length());
        }

        @Test
        @DisplayName("names two valid characters in the plural, not one in the singular")
        void namesTwoValidCharactersInThePlural() {
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .contains("values are")
                    .doesNotContain("value is")
                    .endsWith("U and D");
        }

        @Test
        @DisplayName("carries the rejection through the single message component")
        void carriesTheRejectionThroughTheMessageComponent() {
            UserResponse response = reporting(UserResponse.MSG_LIST_INVALID_SELECTION, false);

            assertThat(response.message())
                    .isEqualTo(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .hasSize(43);
        }

        @Test
        @DisplayName("carries a selector un-case-folded, so a lower-case mark stays lower case")
        void carriesASelectorUnCaseFolded() {
            UserResponse.UserRow lowerCase = rowOf("u", USER_ID, USER_CODE);
            UserResponse.UserRow upperCase = rowOf("U", USER_ID, USER_CODE);

            assertThat(lowerCase.selector()).isEqualTo("u");
            assertThat(upperCase.selector()).isEqualTo("U");
            assertThat(lowerCase.selector()).isNotEqualTo(upperCase.selector());
        }

        @Test
        @DisplayName("carries both supported action marks and an arbitrary one without complaint, "
                + "because deciding validity is the service's work")
        void carriesAnArbitrarySelectorWithoutComplaint() {
            assertThat(violationsOf(rowOf("U", USER_ID, USER_CODE))).isEmpty();
            assertThat(violationsOf(rowOf("D", USER_ID, USER_CODE))).isEmpty();
            assertThat(violationsOf(rowOf("Z", USER_ID, USER_CODE))).isEmpty();
            assertThat(violationsOf(rowOf("7", USER_ID, USER_CODE))).isEmpty();
            assertThat(violationsOf(rowOf(" ", USER_ID, USER_CODE))).isEmpty();
            assertThat(violationsOf(rowOf(null, USER_ID, USER_CODE))).isEmpty();
            assertThat(rowOf("Z", USER_ID, USER_CODE).selector()).isEqualTo("Z");
        }
    }

    @Nested
    @DisplayName("every measured message literal is reproduced verbatim at its exact length")
    class MeasuredMessageLiterals {
        @Test
        @DisplayName("reproduces the five add-screen emptiness rejections of COUSR01C")
        void reproducesTheAddEmptinessRejections() {
            assertMessageContract(UserResponse.MSG_ADD_FIRST_NAME_EMPTY,
                    "First Name can NOT be empty...", 30);
            assertMessageContract(UserResponse.MSG_ADD_LAST_NAME_EMPTY,
                    "Last Name can NOT be empty...", 29);
            assertMessageContract(UserResponse.MSG_ADD_USER_ID_EMPTY,
                    "User ID can NOT be empty...", 27);
            assertMessageContract(UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY,
                    "Password can NOT be empty...", 28);
            assertMessageContract(UserResponse.MSG_ADD_USER_TYPE_EMPTY,
                    "User Type can NOT be empty...", 29);
        }

        @Test
        @DisplayName("reproduces the add-screen duplicate and failure texts of COUSR01C, including "
                + "the non-plural verb")
        void reproducesTheAddDuplicateAndFailureTexts() {
            assertMessageContract(UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST,
                    "User ID already exist...", 24);
            assertMessageContract(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER,
                    "Unable to Add User...", 21);
            assertThat(UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST)
                    .as("the source verb is not pluralised and is never corrected")
                    .endsWith("exist...")
                    .doesNotContain("exists");
        }

        @Test
        @DisplayName("reproduces the five update-screen emptiness rejections of COUSR02C")
        void reproducesTheUpdateEmptinessRejections() {
            assertMessageContract(UserResponse.MSG_UPDATE_USER_ID_EMPTY,
                    "User ID can NOT be empty...", 27);
            assertMessageContract(UserResponse.MSG_UPDATE_FIRST_NAME_EMPTY,
                    "First Name can NOT be empty...", 30);
            assertMessageContract(UserResponse.MSG_UPDATE_LAST_NAME_EMPTY,
                    "Last Name can NOT be empty...", 29);
            assertMessageContract(UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY,
                    "Password can NOT be empty...", 28);
            assertMessageContract(UserResponse.MSG_UPDATE_USER_TYPE_EMPTY,
                    "User Type can NOT be empty...", 29);
        }

        @Test
        @DisplayName("reproduces the update-screen advisory, prompt, not-found and failure texts of "
                + "COUSR02C")
        void reproducesTheUpdateAdvisoryPromptAndFailureTexts() {
            assertMessageContract(UserResponse.MSG_UPDATE_NO_CHANGE,
                    "Please modify to update ...", 27);
            assertMessageContract(UserResponse.MSG_UPDATE_PRESS_PF5,
                    "Press PF5 key to save your updates ...", 38);
            assertMessageContract(UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND,
                    "User ID NOT found...", 20);
            assertMessageContract(UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER,
                    "Unable to lookup User...", 24);
            assertMessageContract(UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER,
                    "Unable to Update User...", 24);
        }

        @Test
        @DisplayName("reproduces the delete-screen emptiness, prompt, not-found and lookup texts of "
                + "COUSR03C")
        void reproducesTheDeleteTexts() {
            assertMessageContract(UserResponse.MSG_DELETE_USER_ID_EMPTY,
                    "User ID can NOT be empty...", 27);
            assertMessageContract(UserResponse.MSG_DELETE_PRESS_PF5,
                    "Press PF5 key to delete this user ...", 37);
            assertMessageContract(UserResponse.MSG_DELETE_USER_ID_NOT_FOUND,
                    "User ID NOT found...", 20);
            assertMessageContract(UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER,
                    "Unable to lookup User...", 24);
        }

        @Test
        @DisplayName("reproduces the list-screen lookup failure and the five paging-boundary texts "
                + "of COUSR00C")
        void reproducesTheListTexts() {
            assertMessageContract(UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER,
                    "Unable to lookup User...", 24);
            assertMessageContract(UserResponse.MSG_LIST_ALREADY_AT_TOP,
                    "You are already at the top of the page...", 41);
            assertMessageContract(UserResponse.MSG_LIST_ALREADY_AT_BOTTOM,
                    "You are already at the bottom of the page...", 44);
            assertMessageContract(UserResponse.MSG_LIST_AT_TOP,
                    "You are at the top of the page...", 33);
            assertMessageContract(UserResponse.MSG_LIST_REACHED_BOTTOM,
                    "You have reached the bottom of the page...", 42);
            assertMessageContract(UserResponse.MSG_LIST_REACHED_TOP,
                    "You have reached the top of the page...", 39);
        }

        @Test
        @DisplayName("★ preserves the source defect where the delete failure reports the update verb")
        void preservesTheDeleteFailureReportingTheUpdateVerb() {
            assertMessageContract(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER,
                    "Unable to Update User...", 24);
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER)
                    .as("the delete failure path reports the update verb; correcting it to say "
                            + "\"Delete\" would break byte parity with the legacy screen, so this "
                            + "assertion exists to fail on the correction rather than on the defect")
                    .isEqualTo(UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER);
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER)
                    .doesNotContain("Delete")
                    .doesNotContain("delete");
        }

        @Test
        @DisplayName("keeps the two function-key prompts at thirty-eight and thirty-seven "
                + "characters and never unifies them")
        void keepsTheTwoFunctionKeyPromptsDistinct() {
            assertThat(UserResponse.MSG_UPDATE_PRESS_PF5).hasSize(38);
            assertThat(UserResponse.MSG_DELETE_PRESS_PF5).hasSize(37);
            assertThat(UserResponse.MSG_UPDATE_PRESS_PF5)
                    .as("one prompt saves and the other deletes; a shared template would collapse "
                            + "them onto one length")
                    .isNotEqualTo(UserResponse.MSG_DELETE_PRESS_PF5);
            assertThat(UserResponse.MSG_UPDATE_PRESS_PF5.length())
                    .isNotEqualTo(UserResponse.MSG_DELETE_PRESS_PF5.length());
        }

        @Test
        @DisplayName("ends the emptiness family in three dots with no preceding space, unlike the "
                + "prompts and the advisory")
        void distinguishesTheTwoEllipsisSpacings() {
            List<String> withoutPrecedingSpace = List.of(
                    UserResponse.MSG_ADD_FIRST_NAME_EMPTY,
                    UserResponse.MSG_ADD_LAST_NAME_EMPTY,
                    UserResponse.MSG_ADD_USER_ID_EMPTY,
                    UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY,
                    UserResponse.MSG_ADD_USER_TYPE_EMPTY,
                    UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND,
                    UserResponse.MSG_DELETE_USER_ID_NOT_FOUND,
                    UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST,
                    UserResponse.MSG_ADD_UNABLE_TO_ADD_USER,
                    UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER,
                    UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(withoutPrecedingSpace).allSatisfy(text -> assertThat(text)
                    .endsWith("...")
                    .doesNotEndWith(" ..."));

            List<String> withPrecedingSpace = List.of(
                    UserResponse.MSG_UPDATE_NO_CHANGE,
                    UserResponse.MSG_UPDATE_PRESS_PF5,
                    UserResponse.MSG_DELETE_PRESS_PF5,
                    UserResponse.MSG_ADD_SUCCESS_SUFFIX,
                    UserResponse.MSG_UPDATE_SUCCESS_SUFFIX,
                    UserResponse.MSG_DELETE_SUCCESS_SUFFIX);
            assertThat(withPrecedingSpace)
                    .allSatisfy(text -> assertThat(text).endsWith(" ..."));
        }

        @Test
        @DisplayName("capitalises the negation the same way on all four screens")
        void capitalisesTheNegationConsistently() {
            List<String> emptinessFamily = List.of(
                    UserResponse.MSG_ADD_FIRST_NAME_EMPTY,
                    UserResponse.MSG_ADD_LAST_NAME_EMPTY,
                    UserResponse.MSG_ADD_USER_ID_EMPTY,
                    UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY,
                    UserResponse.MSG_ADD_USER_TYPE_EMPTY,
                    UserResponse.MSG_UPDATE_USER_ID_EMPTY,
                    UserResponse.MSG_UPDATE_FIRST_NAME_EMPTY,
                    UserResponse.MSG_UPDATE_LAST_NAME_EMPTY,
                    UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY,
                    UserResponse.MSG_UPDATE_USER_TYPE_EMPTY,
                    UserResponse.MSG_DELETE_USER_ID_EMPTY);
            assertThat(emptinessFamily).allSatisfy(text -> assertThat(text)
                    .contains("can NOT be empty")
                    .doesNotContain("can not be empty")
                    .doesNotContain("cannot be empty"));

            assertThat(UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND).contains("NOT found");
            assertThat(UserResponse.MSG_DELETE_USER_ID_NOT_FOUND).contains("NOT found");
        }

        @Test
        @DisplayName("carries every published literal through the message component untrimmed")
        void carriesEveryPublishedLiteralUntrimmed() {
            List<String> everyText = List.of(
                    UserResponse.MSG_LIST_INVALID_SELECTION,
                    UserResponse.MSG_LIST_ALREADY_AT_TOP,
                    UserResponse.MSG_LIST_ALREADY_AT_BOTTOM,
                    UserResponse.MSG_LIST_AT_TOP,
                    UserResponse.MSG_LIST_REACHED_BOTTOM,
                    UserResponse.MSG_LIST_REACHED_TOP,
                    UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER,
                    UserResponse.MSG_ADD_FIRST_NAME_EMPTY,
                    UserResponse.MSG_ADD_LAST_NAME_EMPTY,
                    UserResponse.MSG_ADD_USER_ID_EMPTY,
                    UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY,
                    UserResponse.MSG_ADD_USER_TYPE_EMPTY,
                    UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST,
                    UserResponse.MSG_ADD_UNABLE_TO_ADD_USER,
                    UserResponse.MSG_UPDATE_USER_ID_EMPTY,
                    UserResponse.MSG_UPDATE_FIRST_NAME_EMPTY,
                    UserResponse.MSG_UPDATE_LAST_NAME_EMPTY,
                    UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY,
                    UserResponse.MSG_UPDATE_USER_TYPE_EMPTY,
                    UserResponse.MSG_UPDATE_NO_CHANGE,
                    UserResponse.MSG_UPDATE_PRESS_PF5,
                    UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND,
                    UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER,
                    UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER,
                    UserResponse.MSG_DELETE_USER_ID_EMPTY,
                    UserResponse.MSG_DELETE_PRESS_PF5,
                    UserResponse.MSG_DELETE_USER_ID_NOT_FOUND,
                    UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER,
                    UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER);

            assertThat(everyText).allSatisfy(text -> {
                assertThat(reporting(text, true).message()).isEqualTo(text);
                assertThat(text.length())
                        .as("no legacy message exceeds the width the message field declares")
                        .isLessThanOrEqualTo(UserResponse.MESSAGE_LENGTH);
                assertThat(violationsOf(reporting(text, true))).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("the three confirmations compose a five-character prefix, the unpadded identifier "
            + "and a leading-space fragment")
    class ComposedSuccessMessages {
        @Test
        @DisplayName("publishes the prefix as five characters ending in a space, on all three "
                + "screens")
        void publishesThePrefixAsFiveCharactersEndingInASpace() {
            assertThat(UserResponse.MSG_ADD_SUCCESS_PREFIX).isEqualTo("User ").hasSize(5);
            assertThat(UserResponse.MSG_UPDATE_SUCCESS_PREFIX).isEqualTo("User ").hasSize(5);
            assertThat(UserResponse.MSG_DELETE_SUCCESS_PREFIX).isEqualTo("User ").hasSize(5);
            assertThat(UserResponse.MSG_ADD_SUCCESS_PREFIX)
                    .as("the trailing space is contributed by size, so it survives the assembly")
                    .endsWith(" ");
        }

        @Test
        @DisplayName("publishes each outcome fragment with its leading space, at nineteen, "
                + "twenty-one and twenty-one characters")
        void publishesEachOutcomeFragmentWithItsLeadingSpace() {
            assertThat(UserResponse.MSG_ADD_SUCCESS_SUFFIX)
                    .isEqualTo(" has been added ...").hasSize(19).startsWith(" ");
            assertThat(UserResponse.MSG_UPDATE_SUCCESS_SUFFIX)
                    .isEqualTo(" has been updated ...").hasSize(21).startsWith(" ");
            assertThat(UserResponse.MSG_DELETE_SUCCESS_SUFFIX)
                    .isEqualTo(" has been deleted ...").hasSize(21).startsWith(" ");
        }

        @Test
        @DisplayName("assembles the add confirmation with exactly one space on each side of the "
                + "identifier")
        void assemblesTheAddConfirmation() {
            String expected = "User " + USER_ID + " has been added ...";

            UserResponse response = reporting(
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX, false);

            assertThat(response.message()).isEqualTo(expected);
            assertThat(response.message()).startsWith(UserResponse.MSG_ADD_SUCCESS_PREFIX);
            assertThat(response.message())
                    .as("one space from the prefix and one from the fragment, and no more")
                    .contains(" " + USER_ID + " ")
                    .doesNotContain("  ");
            assertThat(response.message()).hasSize(5 + USER_ID.length() + 19);
        }

        @Test
        @DisplayName("assembles the update confirmation with exactly one space on each side of the "
                + "identifier")
        void assemblesTheUpdateConfirmation() {
            String expected = "User " + USER_ID + " has been updated ...";

            UserResponse response = reporting(
                    UserResponse.MSG_UPDATE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_UPDATE_SUCCESS_SUFFIX, false);

            assertThat(response.message()).isEqualTo(expected);
            assertThat(response.message()).contains(" " + USER_ID + " ").doesNotContain("  ");
            assertThat(response.message()).hasSize(5 + USER_ID.length() + 21);
        }

        @Test
        @DisplayName("assembles the delete confirmation with exactly one space on each side of the "
                + "identifier")
        void assemblesTheDeleteConfirmation() {
            String expected = "User " + USER_ID + " has been deleted ...";

            UserResponse response = reporting(
                    UserResponse.MSG_DELETE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_DELETE_SUCCESS_SUFFIX, false);

            assertThat(response.message()).isEqualTo(expected);
            assertThat(response.message()).contains(" " + USER_ID + " ").doesNotContain("  ");
            assertThat(response.message()).hasSize(5 + USER_ID.length() + 21);
        }

        @Test
        @DisplayName("drops the identifier's fixed-width padding, because it is contributed "
                + "delimited by space")
        void dropsTheIdentifiersFixedWidthPadding() {
            String padded = SHORT_USER_ID + "    ";
            assertThat(padded).hasSize(UserResponse.USER_ID_LENGTH);

            String expected = "User " + SHORT_USER_ID + " has been added ...";

            UserResponse response = reporting(
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + SHORT_USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX, false);

            assertThat(response.message()).isEqualTo(expected);
            assertThat(response.message())
                    .as("the padding is dropped inside the message only, never in the component")
                    .doesNotContain(padded)
                    .doesNotContain("  ");
        }

        @Test
        @DisplayName("keeps the identifier component at its full field width even when the message "
                + "carries it unpadded")
        void keepsTheIdentifierComponentAtItsFullWidth() {
            String padded = SHORT_USER_ID + "    ";

            UserResponse response = new UserResponse(null, null, padded, null, null, null, null,
                    null, null, null, null, null,
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + SHORT_USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX,
                    null, false, true, null, null, null);

            assertThat(response.userId())
                    .isEqualTo(padded)
                    .hasSize(UserResponse.USER_ID_LENGTH);
            assertThat(response.message()).doesNotContain(padded);
        }

        @Test
        @DisplayName("carries a full eight-character identifier into the message unchanged")
        void carriesAFullWidthIdentifierUnchanged() {
            assertThat(USER_ID).hasSize(UserResponse.USER_ID_LENGTH);

            UserResponse response = reporting(
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX, false);

            assertThat(response.message()).isEqualTo("User " + USER_ID + " has been added ...");
        }

        @Test
        @DisplayName("never trims, collapses or re-spaces an assembled confirmation")
        void neverTrimsCollapsesOrReSpacesAConfirmation() {
            String assembled = UserResponse.MSG_DELETE_SUCCESS_PREFIX + USER_ID
                    + UserResponse.MSG_DELETE_SUCCESS_SUFFIX;

            UserResponse response = reporting(assembled, false);

            assertThat(response.message())
                    .isEqualTo(assembled)
                    .endsWith(" ...")
                    .hasSize(assembled.length());
        }

        @Test
        @DisplayName("produces three pairwise distinct confirmations, so no shared template can "
                + "have built them")
        void producesThreePairwiseDistinctConfirmations() {
            String added = "User " + USER_ID + " has been added ...";
            String updated = "User " + USER_ID + " has been updated ...";
            String deleted = "User " + USER_ID + " has been deleted ...";

            assertThat(List.of(added, updated, deleted)).doesNotHaveDuplicates();
            assertThat(added).isNotEqualTo(updated).isNotEqualTo(deleted);
            assertThat(updated).isNotEqualTo(deleted);
            assertThat(List.of(UserResponse.MSG_ADD_SUCCESS_SUFFIX,
                    UserResponse.MSG_UPDATE_SUCCESS_SUFFIX,
                    UserResponse.MSG_DELETE_SUCCESS_SUFFIX)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("keeps every assembled confirmation inside the declared message width")
        void keepsEveryConfirmationInsideTheDeclaredWidth() {
            List<String> assembled = List.of(
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX,
                    UserResponse.MSG_UPDATE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_UPDATE_SUCCESS_SUFFIX,
                    UserResponse.MSG_DELETE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_DELETE_SUCCESS_SUFFIX);

            assertThat(assembled).allSatisfy(text -> {
                assertThat(text.length()).isLessThanOrEqualTo(UserResponse.MESSAGE_LENGTH);
                assertThat(violationsOf(reporting(text, false))).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("a populated message never implies a failure, because the flag is explicit")
    class SuccessIsNotAnError {
        @Test
        @DisplayName("carries each of the three confirmations together with a false failure flag")
        void carriesEachConfirmationWithAFalseFlag() {
            List<String> confirmations = List.of(
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX,
                    UserResponse.MSG_UPDATE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_UPDATE_SUCCESS_SUFFIX,
                    UserResponse.MSG_DELETE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_DELETE_SUCCESS_SUFFIX);

            assertThat(confirmations).allSatisfy(text -> {
                UserResponse response = new UserResponse(null, null, null, null, null, null, null,
                        null, null, null, null, null, text, null, false, true, null, null, null);

                assertThat(response.message()).isEqualTo(text);
                assertThat(response.generalError()).isFalse();
                assertThat(response.actionSucceeded()).isTrue();
            });
        }

        @Test
        @DisplayName("carries the emptiness, duplicate, not-found, lookup and write failures with a "
                + "true failure flag")
        void carriesEveryFailureWithATrueFlag() {
            List<String> failures = List.of(
                    UserResponse.MSG_ADD_FIRST_NAME_EMPTY,
                    UserResponse.MSG_ADD_LAST_NAME_EMPTY,
                    UserResponse.MSG_ADD_USER_ID_EMPTY,
                    UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY,
                    UserResponse.MSG_ADD_USER_TYPE_EMPTY,
                    UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST,
                    UserResponse.MSG_ADD_UNABLE_TO_ADD_USER,
                    UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND,
                    UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER,
                    UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER,
                    UserResponse.MSG_DELETE_USER_ID_NOT_FOUND,
                    UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER,
                    UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER,
                    UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER);

            assertThat(failures).allSatisfy(text -> {
                UserResponse response = reporting(text, true);

                assertThat(response.message()).isEqualTo(text);
                assertThat(response.generalError()).isTrue();
                assertThat(response.actionSucceeded()).isFalse();
            });
        }

        @Test
        @DisplayName("treats the two function-key prompts and the no-change advisory as "
                + "informational, neither a failure nor a completed action")
        void treatsThePromptsAndAdvisoryAsInformational() {
            List<String> informational = List.of(
                    UserResponse.MSG_UPDATE_PRESS_PF5,
                    UserResponse.MSG_DELETE_PRESS_PF5,
                    UserResponse.MSG_UPDATE_NO_CHANGE);

            assertThat(informational).allSatisfy(text -> {
                UserResponse response = reporting(text, false);

                assertThat(response.message()).isEqualTo(text);
                assertThat(response.generalError()).isFalse();
                assertThat(response.actionSucceeded()).isFalse();
            });
        }

        @Test
        @DisplayName("carries the invalid-selection rejection together with a full page and a false "
                + "failure flag, which is what the legacy arm actually does")
        void carriesTheSelectionRejectionWithAFullPage() {
            UserResponse response = new UserResponse(rows(PageMetadata.USER_LIST_PAGE_SIZE), forwardPage(),
                    null, null, null, null, null, null, null, null, null, null,
                    UserResponse.MSG_LIST_INVALID_SELECTION, null, false, false, FOCUS_FIELD, null,
                    null);

            assertThat(response.message()).isEqualTo(UserResponse.MSG_LIST_INVALID_SELECTION);
            assertThat(response.rows()).hasSize(PageMetadata.USER_LIST_PAGE_SIZE);
            assertThat(response.hasRows()).isTrue();
            assertThat(response.generalError())
                    .as("the arm that sets this message never raises the error flag")
                    .isFalse();
        }

        @Test
        @DisplayName("proves the flag is not derived from the message: one text carries both "
                + "polarities")
        void provesTheFlagIsNotDerivedFromTheMessage() {
            String sameText = UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND;

            UserResponse reportedAsFailure = reporting(sameText, true);
            UserResponse reportedAsAdvisory = reporting(sameText, false);

            assertThat(reportedAsFailure.message()).isEqualTo(reportedAsAdvisory.message());
            assertThat(reportedAsFailure.generalError()).isTrue();
            assertThat(reportedAsAdvisory.generalError()).isFalse();
        }

        @Test
        @DisplayName("proves the flag is not derived from message absence either")
        void provesTheFlagIsNotDerivedFromMessageAbsence() {
            assertThat(reporting(null, true).generalError())
                    .as("a failure reported without a summary line is still a failure")
                    .isTrue();
            assertThat(reporting("", true).generalError()).isTrue();
            assertThat(reporting(null, false).generalError()).isFalse();
            assertThat(reporting(null, true).message()).isNull();
        }

        @Test
        @DisplayName("proves the flag is not derived from the collections or from the other flag")
        void provesTheFlagIsNotDerivedFromTheCollectionsOrTheOtherFlag() {
            UserResponse withFieldErrorsButNoFailure = new UserResponse(null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    UserResponse.MSG_UPDATE_NO_CHANGE, fieldErrors(), false, false, null, null,
                    null);

            assertThat(withFieldErrorsButNoFailure.hasFieldErrors()).isTrue();
            assertThat(withFieldErrorsButNoFailure.generalError()).isFalse();

            UserResponse failureWithoutFieldErrors =
                    reporting(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER, true);

            assertThat(failureWithoutFieldErrors.hasFieldErrors()).isFalse();
            assertThat(failureWithoutFieldErrors.generalError()).isTrue();

            UserResponse bothFalse = reporting(UserResponse.MSG_UPDATE_NO_CHANGE, false);

            assertThat(bothFalse.generalError()).isFalse();
            assertThat(bothFalse.actionSucceeded()).isFalse();
        }

        @Test
        @DisplayName("exposes the flag as its own component on the wire, alongside a non-empty "
                + "message")
        void exposesTheFlagAsItsOwnComponentOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(
                    reporting(UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX, false));

            assertThat(payload.get("message").isTextual()).isTrue();
            assertThat(payload.get("message").asText()).isNotEmpty();
            assertThat(payload.get("generalError").isBoolean()).isTrue();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("actionSucceeded").isBoolean()).isTrue();
        }
    }

    @Nested
    @DisplayName("one summary message, because the legacy cascades stop at the first failure")
    class SingleSummaryMessage {
        @Test
        @DisplayName("carries exactly one message key on the wire, never a collection of them")
        void carriesExactlyOneMessageKey() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(keysOf(payload))
                    .containsOnlyOnce("message")
                    .doesNotContain("messages", "messageList", "errors", "errorMessages",
                            "validationMessages");
            assertThat(payload.get("message").isArray()).isFalse();
            assertThat(payload.get("message").isTextual()).isTrue();
        }

        @Test
        @DisplayName("routes the first-failure text of each cascade through that one component")
        void routesEachCascadesFirstFailureThroughOneComponent() {
            assertThat(reporting(UserResponse.MSG_ADD_FIRST_NAME_EMPTY, true).message())
                    .as("the add cascade checks the first name first")
                    .isEqualTo(UserResponse.MSG_ADD_FIRST_NAME_EMPTY);
            assertThat(reporting(UserResponse.MSG_UPDATE_USER_ID_EMPTY, true).message())
                    .as("the update cascade checks the identifier first, the reverse of add")
                    .isEqualTo(UserResponse.MSG_UPDATE_USER_ID_EMPTY);
            assertThat(reporting(UserResponse.MSG_DELETE_USER_ID_EMPTY, true).message())
                    .as("the delete cascade also checks the identifier first")
                    .isEqualTo(UserResponse.MSG_DELETE_USER_ID_EMPTY);
        }

        @Test
        @DisplayName("keeps the summary line independent of the per-field errors")
        void keepsTheSummaryLineIndependentOfTheFieldErrors() {
            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, UserResponse.MSG_ADD_USER_ID_EMPTY, fieldErrors(), true,
                    false, FOCUS_FIELD, null, null);

            assertThat(response.message()).isEqualTo(UserResponse.MSG_ADD_USER_ID_EMPTY);
            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.fieldErrors().get(0).state())
                    .isEqualTo(ErrorResponse.FieldState.MISSING);
            assertThat(response.fieldErrors().get(0).message())
                    .isEqualTo(UserResponse.MSG_ADD_USER_ID_EMPTY);
        }

        @Test
        @DisplayName("distinguishes the two operator mistakes the decorating macro distinguished")
        void distinguishesTheTwoOperatorMistakes() {
            List<ErrorResponse.FieldError> both = List.of(
                    new ErrorResponse.FieldError("userId", FOCUS_FIELD,
                            ErrorResponse.FieldState.MISSING, UserResponse.MSG_ADD_USER_ID_EMPTY),
                    new ErrorResponse.FieldError("userType", "UTYPE01",
                            ErrorResponse.FieldState.INVALID,
                            UserResponse.MSG_ADD_USER_TYPE_EMPTY));

            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, both, true, false, null, null, null);

            assertThat(response.fieldErrors()).hasSize(2);
            assertThat(response.fieldErrors())
                    .extracting(ErrorResponse.FieldError::state)
                    .containsExactly(ErrorResponse.FieldState.MISSING,
                            ErrorResponse.FieldState.INVALID);
        }

        @Test
        @DisplayName("carries no field error on a first submission, because the macro fired only on "
                + "re-entry")
        void carriesNoFieldErrorOnAFirstSubmission() {
            UserResponse firstSubmission = reporting(UserResponse.MSG_ADD_USER_ID_EMPTY, true);

            assertThat(firstSubmission.fieldErrors()).isEmpty();
            assertThat(firstSubmission.hasFieldErrors()).isFalse();
        }
    }

    @Nested
    @DisplayName("every value crosses at its own width, untrimmed, unpadded and un-case-folded")
    class ExactWidthRoundTrip {
        @Test
        @DisplayName("returns each value exactly as supplied at its declared width")
        void returnsEachValueExactlyAsSupplied() {
            UserResponse response = new UserResponse(null, null,
                    widthOf(UserResponse.USER_ID_LENGTH),
                    widthOf(UserResponse.FIRST_NAME_LENGTH),
                    widthOf(UserResponse.LAST_NAME_LENGTH),
                    widthOf(UserResponse.USER_TYPE_LENGTH),
                    widthOf(UserResponse.TRANSACTION_NAME_LENGTH),
                    widthOf(UserResponse.SCREEN_TITLE_LENGTH),
                    widthOf(UserResponse.CURRENT_DATE_LENGTH),
                    widthOf(UserResponse.PROGRAM_NAME_LENGTH),
                    widthOf(UserResponse.SCREEN_TITLE_LENGTH),
                    widthOf(UserResponse.CURRENT_TIME_LENGTH),
                    widthOf(UserResponse.MESSAGE_LENGTH), null, false, false,
                    widthOf(UserResponse.SCREEN_FIELD_ID_LENGTH), null, null);

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.userId()).hasSize(UserResponse.USER_ID_LENGTH);
            assertThat(response.firstName()).hasSize(UserResponse.FIRST_NAME_LENGTH);
            assertThat(response.lastName()).hasSize(UserResponse.LAST_NAME_LENGTH);
            assertThat(response.userType()).hasSize(UserResponse.USER_TYPE_LENGTH);
            assertThat(response.transactionName()).hasSize(UserResponse.TRANSACTION_NAME_LENGTH);
            assertThat(response.title01()).hasSize(UserResponse.SCREEN_TITLE_LENGTH);
            assertThat(response.currentDate()).hasSize(UserResponse.CURRENT_DATE_LENGTH);
            assertThat(response.programName()).hasSize(UserResponse.PROGRAM_NAME_LENGTH);
            assertThat(response.title02()).hasSize(UserResponse.SCREEN_TITLE_LENGTH);
            assertThat(response.currentTime()).hasSize(UserResponse.CURRENT_TIME_LENGTH);
            assertThat(response.message()).hasSize(UserResponse.MESSAGE_LENGTH);
            assertThat(response.focusScreenFieldId())
                    .hasSize(UserResponse.SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("keeps a trailing space, because a fixed-width field's padding is its value")
        void keepsATrailingSpace() {
            String paddedFirstName = "MARY" + " ".repeat(UserResponse.FIRST_NAME_LENGTH - 4);
            UserResponse.UserRow row = new UserResponse.UserRow("U", USER_ID, paddedFirstName,
                    LAST_NAME, USER_CODE);

            assertThat(row.firstName())
                    .isEqualTo(paddedFirstName)
                    .hasSize(UserResponse.FIRST_NAME_LENGTH)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("never pads a short value up to its field width")
        void neverPadsAShortValueUp() {
            UserResponse.UserRow row = rowOf("U", SHORT_USER_ID, USER_CODE);

            assertThat(row.userId())
                    .isEqualTo(SHORT_USER_ID)
                    .hasSize(SHORT_USER_ID.length());
            assertThat(row.userId().length()).isLessThan(UserResponse.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("never re-cases a value, at either level")
        void neverReCasesAValue() {
            UserResponse response = new UserResponse(List.of(rowOf("d", "abcd1234", "a")), null,
                    "abcd1234", "mary ann", LAST_NAME, "a", null, null, null, null, null, null,
                    null, null, false, false, null, null, null);

            assertThat(response.userId()).isEqualTo("abcd1234");
            assertThat(response.firstName()).isEqualTo("mary ann");
            assertThat(response.lastName()).isEqualTo("o'HARA-smith");
            assertThat(response.userType()).isEqualTo("a");
            assertThat(response.rows().get(0).selector()).isEqualTo("d");
            assertThat(response.rows().get(0).userType()).isEqualTo("a");
        }

        @Test
        @DisplayName("accepts a name carrying a digit, an apostrophe, a hyphen or an embedded space, "
                + "because the legacy alphabetic idiom accepts them")
        void acceptsAPunctuatedOrDigitBearingName() {
            UserResponse response = new UserResponse(null, null, null, "MARY ANN 2ND",
                    "o'HARA-smith", null, null, null, null, null, null, null, null, null, false,
                    false, null, null, null);

            assertThat(violationsOf(response))
                    .as("no name-format constraint exists on this contract")
                    .isEmpty();
            assertThat(response.firstName()).isEqualTo("MARY ANN 2ND");
            assertThat(response.lastName()).isEqualTo("o'HARA-smith");
        }
    }

    @Nested
    @DisplayName("no credential crosses this contract in any form")
    class NoCredentialAnywhere {
        @Test
        @DisplayName("declares exactly nineteen components, so there is no credential one")
        void declaresExactlyNineteenComponents() throws JsonProcessingException {
            List<String> keys = keysOf(payloadOf(populated()));

            assertThat(keys)
                    .containsExactlyInAnyOrderElementsOf(RESPONSE_COMPONENTS)
                    .hasSize(RESPONSE_COMPONENTS.size());
        }

        @Test
        @DisplayName("carries no credential key under any spelling, masked, hashed or derived")
        void carriesNoCredentialKeyUnderAnySpelling() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(keysOf(payload)).doesNotContainAnyElementsOf(CREDENTIAL_SPELLINGS);
            assertThat(keysOf(payload.get("rows").get(0)))
                    .doesNotContainAnyElementsOf(CREDENTIAL_SPELLINGS);
            assertThat(CREDENTIAL_SPELLINGS)
                    .allSatisfy(spelling -> assertThat(payload.has(spelling)).isFalse());
        }

        @Test
        @DisplayName("cannot render a credential, because none exists to withhold")
        void cannotRenderACredential() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain("password", "Password", "pwd", "Pwd", "credential",
                    "Credential", "secret", "Secret", "digest", "Digest", "salt", "Salt");
            assertThat(rowOf("U", USER_ID, USER_CODE).toString())
                    .doesNotContain("password", "Password", "pwd", "Pwd");
        }

        @Test
        @DisplayName("the two blank-input texts that name the field state only that an input was "
                + "blank")
        void theTwoBlankInputTextsCarryNoValue() {
            assertThat(UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY)
                    .isEqualTo("Password can NOT be empty...")
                    .hasSize(28);
            assertThat(UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY)
                    .isEqualTo("Password can NOT be empty...")
                    .hasSize(28);
            assertThat(UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY)
                    .as("external contract text, stating a field was blank and nothing more")
                    .endsWith("empty...");
        }

        @Test
        @DisplayName("uses only synthetic identity values, never a seeded legacy one")
        void usesOnlySyntheticIdentityValues() {
            assertThat(USER_ID).isEqualTo("ABCD1234").hasSize(UserResponse.USER_ID_LENGTH);
            assertThat(SHORT_USER_ID).isEqualTo("AB12");
            assertThat(populated().userId()).isEqualTo(USER_ID);
        }
    }

    @Nested
    @DisplayName("the user-type vocabulary is two codes, resolved without ever throwing")
    class UserTypeVocabulary {
        @Test
        @DisplayName("declares exactly two constants and no synthetic third state")
        void declaresExactlyTwoConstants() {
            assertThat(UserType.values()).hasSize(2);
            assertThat(UserType.values())
                    .extracting(Enum::name)
                    .containsExactly("ADMIN", "USER")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("publishes the two authoritative codes")
        void publishesTheTwoAuthoritativeCodes() {
            assertThat(UserType.ADMIN.getCode()).isEqualTo(ADMIN_CODE);
            assertThat(UserType.USER.getCode()).isEqualTo(USER_CODE);
            assertThat(UserType.values())
                    .extracting(UserType::getCode)
                    .containsExactly("A", "U");
        }

        @Test
        @DisplayName("answers the administrator question true for the administrator only")
        void answersTheAdministratorQuestionForTheAdministratorOnly() {
            assertThat(UserType.ADMIN.isAdmin()).isTrue();
            assertThat(UserType.USER.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("resolves each declared code without throwing")
        void resolvesEachDeclaredCode() {
            assertThat(UserType.fromCode("A")).contains(UserType.ADMIN);
            assertThat(UserType.fromCode("U")).contains(UserType.USER);
        }

        @Test
        @DisplayName("returns an empty result rather than throwing for an unrecognised or absent "
                + "code, and folds no case")
        void returnsAnEmptyResultForAnUnrecognisedCode() {
            assertThat(UserType.fromCode("X")).isEmpty();
            assertThat(UserType.fromCode(null)).isEmpty();
            assertThat(UserType.fromCode("")).isEmpty();
            assertThat(UserType.fromCode(" ")).isEmpty();
            assertThat(UserType.fromCode("AA")).isEmpty();
            assertThat(UserType.fromCode("a"))
                    .as("sign-on compares the raw character and applies no case fold")
                    .isEmpty();
            assertThat(UserType.fromCode("u")).isEmpty();
        }

        @Test
        @DisplayName("composes to a false administrator answer for an unrecognised code, without "
                + "throwing")
        void composesToAFalseAdministratorAnswer() {
            Optional<UserType> unrecognised = UserType.fromCode("X");

            assertThat(unrecognised.map(UserType::isAdmin).orElse(false)).isFalse();
            assertThat(UserType.fromCode("A").map(UserType::isAdmin).orElse(false)).isTrue();
            assertThat(UserType.fromCode("U").map(UserType::isAdmin).orElse(false)).isFalse();
            assertThat(UserType.fromCode(null).map(UserType::isAdmin).orElse(false)).isFalse();
        }

        @Test
        @DisplayName("carries the code on the response as raw text, not as the enumeration")
        void carriesTheCodeAsRawText() throws JsonProcessingException {
            UserResponse response = new UserResponse(List.of(rowOf("U", USER_ID, ADMIN_CODE)), null,
                    USER_ID, null, null, USER_CODE, null, null, null, null, null, null, null, null,
                    false, false, null, null, null);

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("userType").isTextual()).isTrue();
            assertThat(payload.get("userType").asText()).isEqualTo(USER_CODE);
            assertThat(payload.get("rows").get(0).get("userType").asText()).isEqualTo(ADMIN_CODE);
            assertThat(payload.get("userType").asText())
                    .as("the wire vocabulary is the raw code, never a Java constant name")
                    .isNotEqualTo(UserType.USER.name());
        }

        @Test
        @DisplayName("still carries an undeclared one-character code, at both levels and without a "
                + "violation")
        void stillCarriesAnUndeclaredCode() {
            UserResponse response = new UserResponse(List.of(rowOf("U", USER_ID, "X")), null, null,
                    null, null, "X", null, null, null, null, null, null, null, null, false, false,
                    null, null, null);

            assertThat(violationsOf(response))
                    .as("the persisted column constrains the character no further, so an "
                            + "unexpected code must remain reportable")
                    .isEmpty();
            assertThat(response.userType()).isEqualTo("X");
            assertThat(response.rows().get(0).userType()).isEqualTo("X");
            assertThat(UserType.fromCode(response.userType())).isEmpty();
        }

        @Test
        @DisplayName("bounds the code by width, so a two-character value is still refused")
        void boundsTheCodeByWidth() {
            UserResponse overWide = new UserResponse(null, null, null, null, null, "AU", null, null,
                    null, null, null, null, null, null, false, false, null, null, null);

            assertThat(violationsOf(overWide))
                    .hasSize(1)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("userType"));
        }
    }

    @Nested
    @DisplayName("identifiers are bounded text, so a leading zero survives")
    class IdentifiersAreText {
        @Test
        @DisplayName("returns an eight-character identifier byte for byte at both levels")
        void returnsAnEightCharacterIdentifierByteForByte() {
            UserResponse response = new UserResponse(List.of(rowOf("U", USER_ID, USER_CODE)), null,
                    USER_ID, null, null, null, null, null, null, null, null, null, null, null,
                    false, false, null, null, null);

            assertThat(response.userId()).isEqualTo(USER_ID).hasSize(UserResponse.USER_ID_LENGTH);
            assertThat(response.rows().get(0).userId())
                    .isEqualTo(USER_ID)
                    .hasSize(UserResponse.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("preserves the leading zeros of a numeric-looking identifier")
        void preservesLeadingZeros() throws JsonProcessingException {
            String numericLooking = "00000001";
            assertThat(numericLooking).hasSize(UserResponse.USER_ID_LENGTH);

            UserResponse response = new UserResponse(
                    List.of(rowOf(null, numericLooking, USER_CODE)), null, numericLooking, null,
                    null, null, null, null, null, null, null, null, null, null, false, false, null,
                    null, null);

            assertThat(response.userId()).isEqualTo(numericLooking);
            assertThat(response.rows().get(0).userId()).isEqualTo(numericLooking);

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("userId").isTextual())
                    .as("a numeric type would discard the leading zeros silently")
                    .isTrue();
            assertThat(payload.get("userId").isNumber()).isFalse();
            assertThat(payload.get("userId").asText()).isEqualTo(numericLooking);
        }

        @Test
        @DisplayName("keeps the displayed page indicator textual, so its leading zeros survive too")
        void keepsThePageIndicatorTextual() throws JsonProcessingException {
            JsonNode paging = payloadOf(populated()).get("pageMetadata");

            assertThat(paging.get("displayedPageNumber").isTextual()).isTrue();
            assertThat(paging.get("displayedPageNumber").asText())
                    .isEqualTo(DISPLAYED_PAGE_NUMBER)
                    .hasSize(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);
        }

        @Test
        @DisplayName("refuses an identifier wider than the field and admits one exactly at it")
        void refusesAnOverWideIdentifier() {
            UserResponse atTheBound = new UserResponse(null, null,
                    widthOf(UserResponse.USER_ID_LENGTH), null, null, null, null, null, null, null,
                    null, null, null, null, false, false, null, null, null);
            UserResponse overWide = new UserResponse(null, null,
                    widthOf(UserResponse.USER_ID_LENGTH + 1), null, null, null, null, null, null,
                    null, null, null, null, null, false, false, null, null, null);

            assertThat(violationsOf(atTheBound)).isEmpty();
            assertThat(violationsOf(overWide))
                    .hasSize(1)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("userId"));
        }
    }

    @Nested
    @DisplayName("both collections are frozen, detached and never null")
    class CollectionsAreImmutable {
        @Test
        @DisplayName("returns an unmodifiable row collection")
        void returnsAnUnmodifiableRowCollection() {
            List<UserResponse.UserRow> carried = carrying(rows(2)).rows();
            UserResponse.UserRow extra = rowOf("U", USER_ID, USER_CODE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> carried.add(extra));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> carried.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(carried::clear);
        }

        @Test
        @DisplayName("detaches the row collection from caller-owned state")
        void detachesTheRowCollectionFromCallerOwnedState() {
            List<UserResponse.UserRow> mutable = new ArrayList<>(rows(1));

            UserResponse response = carrying(mutable);
            mutable.add(rowOf("D", USER_ID, ADMIN_CODE));

            assertThat(mutable).hasSize(2);
            assertThat(response.rows())
                    .as("a copy taken at construction cannot grow behind the response")
                    .hasSize(1);
        }

        @Test
        @DisplayName("normalises an absent row collection to the empty immutable list")
        void normalisesAnAbsentRowCollection() {
            UserResponse response = carrying(null);
            UserResponse.UserRow extra = rowOf("U", USER_ID, USER_CODE);

            assertThat(response.rows()).isNotNull().isEmpty();
            assertThat(response.hasRows()).isFalse();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.rows().add(extra));
        }

        @Test
        @DisplayName("normalises, detaches and freezes the field-error collection the same way, "
                + "without capping it")
        void normalisesTheFieldErrorCollection() {
            List<ErrorResponse.FieldError> mutable = new ArrayList<>(fieldErrors());

            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, mutable, true, false, null, null, null);
            mutable.add(new ErrorResponse.FieldError("userType", "UTYPE01",
                    ErrorResponse.FieldState.INVALID, UserResponse.MSG_ADD_USER_TYPE_EMPTY));

            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.hasFieldErrors()).isTrue();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.fieldErrors().clear());

            UserResponse absent = reporting(null, false);

            assertThat(absent.fieldErrors()).isNotNull().isEmpty();
            assertThat(absent.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("keeps each row deeply immutable, since every row component is text")
        void keepsEachRowDeeplyImmutable() {
            UserResponse.UserRow row = rowOf("U", USER_ID, USER_CODE);
            UserResponse response = carrying(List.of(row));

            assertThat(response.rows().get(0)).isEqualTo(row);
            assertThat(response.rows().get(0).selector()).isEqualTo("U");
            assertThat(response.rows().get(0).firstName()).isEqualTo(FIRST_NAME);
            assertThat(response.rows().get(0).lastName()).isEqualTo(LAST_NAME);
        }
    }

    @Nested
    @DisplayName("the paging contract is carried, not re-implemented, and counts nothing")
    class PagingMetadataIsCarried {
        @Test
        @DisplayName("carries both cursors unchanged, including their leading zeros")
        void carriesBothCursorsUnchanged() {
            UserResponse response = new UserResponse(rows(1), forwardPage(), null, null, null, null,
                    null, null, null, null, null, null, null, null, false, false, null, null, null);

            assertThat(response.pageMetadata().previousCursorKey()).isEqualTo(FIRST_CURSOR_KEY);
            assertThat(response.pageMetadata().nextCursorKey()).isEqualTo(LAST_CURSOR_KEY);
            assertThat(response.pageMetadata().previousCursorKey()).startsWith("0");
        }

        @Test
        @DisplayName("carries the eight-character page indicator untrimmed")
        void carriesThePageIndicatorUntrimmed() {
            String padded = "3" + " ".repeat(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH - 1);

            PageMetadata paging = PageMetadata.forward(PageMetadata.USER_LIST_PAGE_SIZE,
                    FIRST_CURSOR_KEY, LAST_CURSOR_KEY, false, false, padded);

            assertThat(paging.displayedPageNumber())
                    .isEqualTo(padded)
                    .hasSize(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("distinguishes a page walked forward from one walked backward")
        void distinguishesForwardFromBackward() {
            PageMetadata forwards = forwardPage();
            PageMetadata backwards = PageMetadata.backward(PageMetadata.USER_LIST_PAGE_SIZE,
                    FIRST_CURSOR_KEY, LAST_CURSOR_KEY, false, true, DISPLAYED_PAGE_NUMBER);

            assertThat(forwards.direction()).isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(backwards.direction()).isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(forwards.direction()).isNotEqualTo(backwards.direction());
            assertThat(PageMetadata.PagingDirection.values()).hasSize(2);
        }

        @Test
        @DisplayName("carries a backward page with its rows still in the order the service produced")
        void carriesABackwardPageInServiceOrder() {
            List<UserResponse.UserRow> ordered = rows(3);
            PageMetadata backwards = PageMetadata.backward(PageMetadata.USER_LIST_PAGE_SIZE,
                    FIRST_CURSOR_KEY, LAST_CURSOR_KEY, false, true, DISPLAYED_PAGE_NUMBER);

            UserResponse response = new UserResponse(ordered, backwards, null, null, null, null,
                    null, null, null, null, null, null, null, null, false, false, null, null, null);

            assertThat(response.rows()).containsExactlyElementsOf(ordered);
            assertThat(response.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
        }

        @Test
        @DisplayName("counts neither rows nor pages, because the legacy browse never counted the "
                + "file")
        void countsNeitherRowsNorPages() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(keysOf(payload))
                    .doesNotContain("totalElements", "totalPages", "totalCount", "totalRows",
                            "count", "size", "numberOfElements");
            assertThat(keysOf(payload.get("pageMetadata")))
                    .containsExactlyInAnyOrder("pageSize", "previousCursorKey", "nextCursorKey",
                            "direction", "hasMorePages", "hasPreviousPages", "displayedPageNumber")
                    .doesNotContain("totalElements", "totalPages", "totalCount");
        }

        @Test
        @DisplayName("is legitimately absent on the three single-user transactions")
        void isLegitimatelyAbsentOnTheSingleUserTransactions() throws JsonProcessingException {
            UserResponse addResult = reporting(
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX, false);

            assertThat(addResult.pageMetadata()).isNull();
            assertThat(addResult.rows()).isEmpty();
            assertThat(violationsOf(addResult)).isEmpty();
            assertThat(payloadOf(addResult).has("pageMetadata")).isFalse();
        }
    }

    @Nested
    @DisplayName("the echoed navigation state is carried, not re-implemented")
    class NavigationStateIsCarried {
        @Test
        @DisplayName("returns every echoed identifier unchanged, leading zeros included")
        void returnsEveryEchoedIdentifierUnchanged() {
            UserResponse response = populated();
            NavigationContext echoed = response.navigationContext();

            assertThat(echoed.userId()).isEqualTo(USER_ID);
            assertThat(echoed.accountId()).isEqualTo("00000000011");
            assertThat(echoed.customerId()).isEqualTo("000000011");
            assertThat(echoed.cardNumber()).isEqualTo("0000000000000011");
            assertThat(echoed.userType()).isEqualTo(ADMIN_CODE);
        }

        @Test
        @DisplayName("carries the screen-flow state without interpreting it here")
        void carriesTheScreenFlowStateWithoutInterpretingIt() {
            NavigationContext echoed = populated().navigationContext();

            assertThat(echoed.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(echoed.reEntry()).isTrue();
            assertThat(echoed.firstEntry()).isFalse();
            assertThat(echoed.fromTransactionId()).isEqualTo(TRANSACTION_NAME);
            assertThat(echoed.fromProgram()).isEqualTo(PROGRAM_NAME);
        }

        @Test
        @DisplayName("is legitimately absent, and is then omitted from the payload")
        void isLegitimatelyAbsent() throws JsonProcessingException {
            UserResponse response = empty();

            assertThat(response.navigationContext()).isNull();
            assertThat(payloadOf(response).has("navigationContext")).isFalse();
        }

        @Test
        @DisplayName("resolves its echoed code through the same non-throwing lookup")
        void resolvesItsEchoedCodeThroughTheSameLookup() {
            NavigationContext echoed = populated().navigationContext();

            assertThat(echoed.resolvedUserType()).contains(UserType.ADMIN);
            assertThat(echoed.echoesAdministratorCode()).isTrue();
        }
    }

    @Nested
    @DisplayName("width is the only declarative constraint, because the cascade is ordered and "
            + "first-error-wins")
    class DeclarativeValidation {
        @Test
        @DisplayName("produces no violation at all when every component is absent")
        void producesNoViolationWhenEveryComponentIsAbsent() {
            UserResponse response = empty();

            assertThat(violationsOf(response))
                    .as("a presence constraint here would reject responses the four legacy screens "
                            + "genuinely produce")
                    .isEmpty();
            assertThat(response.userId()).isNull();
            assertThat(response.message()).isNull();
            assertThat(response.pageMetadata()).isNull();
            assertThat(response.navigationContext()).isNull();
        }

        @Test
        @DisplayName("produces no violation for a row whose every component is absent")
        void producesNoViolationForAnAbsentRow() {
            UserResponse.UserRow blank = new UserResponse.UserRow(null, null, null, null, null);

            assertThat(violationsOf(blank)).isEmpty();
            assertThat(blank.selector()).isNull();
            assertThat(blank.userId()).isNull();
            assertThat(blank.firstName()).isNull();
            assertThat(blank.lastName()).isNull();
            assertThat(blank.userType()).isNull();
        }

        @Test
        @DisplayName("produces no violation for a blank value, so no emptiness constraint exists")
        void producesNoViolationForABlankValue() {
            UserResponse response = new UserResponse(List.of(rowOf(" ", " ", " ")), null, "",
                    "   ", "", " ", "", "", "", "", "", "", "", null, false, false, "", "", null);

            assertThat(violationsOf(response))
                    .as("the ordered cascade in the service decides emptiness, not an annotation")
                    .isEmpty();
        }

        @Test
        @DisplayName("produces no violation for an unrecognised selection or user-type character")
        void producesNoViolationForAnUnrecognisedCharacter() {
            assertThat(violationsOf(rowOf("Q", USER_ID, "Z"))).isEmpty();
            assertThat(violationsOf(rowOf("1", USER_ID, "9"))).isEmpty();
            assertThat(violationsOf(rowOf("*", USER_ID, "-"))).isEmpty();
        }

        @Test
        @DisplayName("produces no violation for a digit-bearing or punctuated name")
        void producesNoViolationForADigitBearingName() {
            assertThat(violationsOf(new UserResponse.UserRow("U", USER_ID, "MARY 2ND",
                    "o'HARA-smith", USER_CODE))).isEmpty();
            assertThat(violationsOf(new UserResponse.UserRow("U", USER_ID, "12345678",
                    "!@#$%^&*()", USER_CODE))).isEmpty();
        }

        @Test
        @DisplayName("produces exactly one violation for an over-long value, naming that component "
                + "and nothing else")
        void producesExactlyOneViolationForAnOverLongValue() {
            UserResponse response = new UserResponse(null, null, null,
                    widthOf(UserResponse.FIRST_NAME_LENGTH + 1), null, null, null, null, null, null,
                    null, null, null, null, false, false, null, null, null);

            assertThat(violationsOf(response))
                    .hasSize(1)
                    .allSatisfy(violation -> {
                        assertThat(violation.getPropertyPath().toString()).isEqualTo("firstName");
                        assertThat(violation.getMessage()).isNotBlank();
                    });
        }

        @Test
        @DisplayName("leaves the route unbounded, because it is a client-resolved label")
        void leavesTheRouteUnbounded() {
            String longRoute = "/api/v1/admin/users/" + widthOf(200);

            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, false, false, null, longRoute, null);

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.nextRoute()).isEqualTo(longRoute);
        }

        @Test
        @DisplayName("bounds the focus hint at the widest field name across the four screens")
        void boundsTheFocusHintAtTheWidestFieldName() {
            assertThat(UserResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(FOCUS_FIELD).hasSize(UserResponse.SCREEN_FIELD_ID_LENGTH);

            UserResponse overWide = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, false, false,
                    widthOf(UserResponse.SCREEN_FIELD_ID_LENGTH + 1), null, null);

            assertThat(violationsOf(overWide))
                    .hasSize(1)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("focusScreenFieldId"));
        }
    }

    @Nested
    @DisplayName("the wire form omits what is absent, tolerates what it does not know and compares "
            + "by value")
    class WireShape {
        @Test
        @DisplayName("omits an absent component instead of emitting a null")
        void omitsAnAbsentComponent() throws JsonProcessingException {
            JsonNode payload = payloadOf(empty());

            assertThat(payload.has("userId")).isFalse();
            assertThat(payload.has("firstName")).isFalse();
            assertThat(payload.has("message")).isFalse();
            assertThat(payload.has("pageMetadata")).isFalse();
            assertThat(payload.has("focusScreenFieldId")).isFalse();
            assertThat(payload.has("nextRoute")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
        }

        @Test
        @DisplayName("still emits both indicators and both normalised collections when everything "
                + "else is absent")
        void stillEmitsBothIndicatorsAndBothCollections() throws JsonProcessingException {
            JsonNode payload = payloadOf(empty());

            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("actionSucceeded").asBoolean()).isFalse();
            assertThat(payload.get("rows").isArray()).isTrue();
            assertThat(payload.get("rows")).isEmpty();
            assertThat(payload.get("fieldErrors").isArray()).isTrue();
            assertThat(payload.get("fieldErrors")).isEmpty();
        }

        @Test
        @DisplayName("tolerates an unknown incoming property instead of rejecting the body")
        void toleratesAnUnknownIncomingProperty() throws JsonProcessingException {
            String body = """
                    {
                      "userId": "ABCD1234",
                      "userType": "A",
                      "generalError": false,
                      "actionSucceeded": true,
                      "somePropertyThisContractNeverDeclared": "ignored"
                    }""";

            UserResponse read = moduleEquivalentMapper().readValue(body, UserResponse.class);

            assertThat(read.userId()).isEqualTo(USER_ID);
            assertThat(read.userType()).isEqualTo(ADMIN_CODE);
            assertThat(read.generalError()).isFalse();
            assertThat(read.actionSucceeded()).isTrue();
            assertThat(read.rows()).isEmpty();
            assertThat(read.fieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("round-trips a fully populated response through the payload unchanged")
        void roundTripsAFullyPopulatedResponse() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            UserResponse original = populated();

            UserResponse read =
                    mapper.readValue(mapper.writeValueAsString(original), UserResponse.class);

            assertThat(read).isEqualTo(original);
            assertThat(read.hashCode()).isEqualTo(original.hashCode());
            assertThat(read.rows()).containsExactlyElementsOf(original.rows());
        }

        @Test
        @DisplayName("compares by value, so two responses built from the same values are equal")
        void comparesByValue() {
            UserResponse first = populated();
            UserResponse second = populated();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(reporting(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER, true));
            assertThat(rowOf("U", USER_ID, USER_CODE))
                    .isEqualTo(rowOf("U", USER_ID, USER_CODE))
                    .hasSameHashCodeAs(rowOf("U", USER_ID, USER_CODE));
            assertThat(rowOf("U", USER_ID, USER_CODE))
                    .isNotEqualTo(rowOf("D", USER_ID, USER_CODE));
        }

        @Test
        @DisplayName("is immutable by construction: no accessor hands back mutable state and no "
                + "value can be replaced")
        void isImmutableByConstruction() {
            UserResponse response = populated();
            List<UserResponse.UserRow> handedBack = response.rows();
            UserResponse.UserRow extra = rowOf("U", USER_ID, USER_CODE);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> handedBack.add(extra));
            assertThat(response.rows()).isSameAs(handedBack);
            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response).isEqualTo(populated());
        }

        @Test
        @DisplayName("is not a problem document, because that representation is deliberately off")
        void isNotAProblemDocument() throws JsonProcessingException {
            JsonNode payload = payloadOf(reporting(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER, true));

            assertThat(keysOf(payload))
                    .doesNotContain("type", "title", "status", "detail", "instance");
            assertThat(payload.get("message").asText())
                    .isEqualTo(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER);
        }

        @Test
        @DisplayName("exposes no diagnostic detail: no response code, path, class name or stack "
                + "fragment")
        void exposesNoDiagnosticDetail() throws JsonProcessingException {
            JsonNode payload = payloadOf(reporting(UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER,
                    true));

            assertThat(keysOf(payload))
                    .doesNotContain("responseCode", "reasonCode", "resp", "reas", "exception",
                            "stackTrace", "cause", "path", "file", "dataset");
            assertThat(payload.get("message").asText())
                    .isEqualTo(UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER);
        }
    }

    @Nested
    @DisplayName("the next route is declarative data and never a decision taken here")
    class RouteIsData {
        @Test
        @DisplayName("carries an opaque route label unchanged")
        void carriesAnOpaqueRouteLabelUnchanged() throws JsonProcessingException {
            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, false, true, null, NEXT_ROUTE, null);

            assertThat(response.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(payloadOf(response).get("nextRoute").isTextual()).isTrue();
            assertThat(payloadOf(response).get("nextRoute").asText()).isEqualTo(NEXT_ROUTE);
        }

        @Test
        @DisplayName("carries an unrecognised label just as readily, since it interprets none")
        void carriesAnUnrecognisedLabelJustAsReadily() {
            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, false, false, null, "NOT-A-KNOWN-ROUTE",
                    null);

            assertThat(violationsOf(response)).isEmpty();
            assertThat(response.nextRoute()).isEqualTo("NOT-A-KNOWN-ROUTE");
        }

        @Test
        @DisplayName("is absent when the response implies no next call")
        void isAbsentWhenTheResponseImpliesNoNextCall() throws JsonProcessingException {
            UserResponse response = reporting(UserResponse.MSG_ADD_USER_ID_EMPTY, true);

            assertThat(response.nextRoute()).isNull();
            assertThat(payloadOf(response).has("nextRoute")).isFalse();
        }

        @Test
        @DisplayName("publishes no route table, so the payload names no route enumeration")
        void publishesNoRouteTable() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(keysOf(payload))
                    .doesNotContain("routes", "routeTable", "availableRoutes", "route", "target",
                            "targetProgram", "transferTo");
            assertThat(payload.get("nextRoute").isObject()).isFalse();
            assertThat(payload.get("nextRoute").isArray()).isFalse();
        }
    }

    @Nested
    @DisplayName("the diagnostic rendering withholds identity while staying diagnosable")
    class DiagnosticRendering {
        @Test
        @DisplayName("withholds the identifier, both name parts and the type on the response")
        void withholdsTheIdentityValuesOnTheResponse() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .doesNotContain(USER_ID)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME)
                    .contains("userId=" + REDACTED)
                    .contains("firstName=" + REDACTED)
                    .contains("lastName=" + REDACTED)
                    .contains("userType=" + REDACTED);
        }

        @Test
        @DisplayName("withholds the rows and the paging cursors, reporting only how many rows there "
                + "were")
        void withholdsTheRowsAndTheCursors() {
            String rendered = carrying(rows(3)).toString();

            assertThat(rendered)
                    .contains("rowCount=3")
                    .contains("rows=" + REDACTED)
                    .contains("pageMetadata=" + REDACTED)
                    .doesNotContain("ROWUSR01");
        }

        @Test
        @DisplayName("retains the message, both indicators and the screen furniture, which is what "
                + "makes a failure diagnosable")
        void retainsTheMessageIndicatorsAndFurniture() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .startsWith("UserResponse[")
                    .endsWith("]")
                    .contains("message=" + UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER)
                    .contains("generalError=true")
                    .contains("actionSucceeded=false")
                    .contains("transactionName=" + TRANSACTION_NAME)
                    .contains("programName=" + PROGRAM_NAME)
                    .contains("title01=" + TITLE_01)
                    .contains("title02=" + TITLE_02)
                    .contains("currentDate=" + CURRENT_DATE)
                    .contains("currentTime=" + CURRENT_TIME)
                    .contains("focusScreenFieldId=" + FOCUS_FIELD)
                    .contains("nextRoute=" + NEXT_ROUTE);
        }

        @Test
        @DisplayName("stays stable and still withholds when every value is absent")
        void staysStableWhenEveryValueIsAbsent() {
            String rendered = empty().toString();

            assertThat(rendered)
                    .startsWith("UserResponse[")
                    .endsWith("]")
                    .contains("rowCount=0")
                    .contains("userId=" + REDACTED)
                    .contains("generalError=false");
        }

        @Test
        @DisplayName("withholds a row's own values whether it is rendered directly or through the "
                + "response")
        void withholdsARowsOwnValues() {
            UserResponse.UserRow row = rowOf("U", USER_ID, ADMIN_CODE);

            String rendered = row.toString();

            assertThat(rendered)
                    .startsWith("UserRow[")
                    .endsWith("]")
                    .contains("selector=U")
                    .contains("userId=" + REDACTED)
                    .contains("firstName=" + REDACTED)
                    .contains("lastName=" + REDACTED)
                    .contains("userType=" + REDACTED)
                    .doesNotContain(USER_ID)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME);
        }

        @Test
        @DisplayName("substitutes a placeholder that never varies with the value it hides")
        void substitutesAPlaceholderThatNeverVaries() {
            String shortValue = rowOf("U", "A", "A").toString();
            String longValue =
                    rowOf("U", widthOf(UserResponse.USER_ID_LENGTH), USER_CODE).toString();

            assertThat(shortValue).isEqualTo(longValue);
            assertThat(shortValue).contains(REDACTED);
        }

        @Test
        @DisplayName("delegates the echoed navigation state to its own rendering")
        void delegatesTheEchoedNavigationState() {
            UserResponse response = populated();

            assertThat(response.toString())
                    .contains("navigationContext=" + response.navigationContext().toString());
        }

        @Test
        @DisplayName("renders a row's absent values without failing")
        void rendersARowsAbsentValuesWithoutFailing() {
            String rendered = new UserResponse.UserRow(null, null, null, null, null).toString();

            assertThat(rendered).startsWith("UserRow[").contains("selector=null").endsWith("]");
        }
    }

    @Nested
    @DisplayName("every accessor on the response and on the row is exercised")
    class AccessorCoverage {
        @Test
        @DisplayName("returns all nineteen components of a fully populated response")
        void returnsAllNineteenComponents() {
            UserResponse response = populated();

            assertThat(response.rows()).hasSize(1);
            assertThat(response.pageMetadata()).isEqualTo(forwardPage());
            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.firstName()).isEqualTo(FIRST_NAME);
            assertThat(response.lastName()).isEqualTo(LAST_NAME);
            assertThat(response.userType()).isEqualTo(ADMIN_CODE);
            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(response.title01()).isEqualTo(TITLE_01);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(response.title02()).isEqualTo(TITLE_02);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(response.message()).isEqualTo(UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER);
            assertThat(response.fieldErrors()).hasSize(1);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(FOCUS_FIELD);
            assertThat(response.nextRoute()).isEqualTo(NEXT_ROUTE);
            assertThat(response.navigationContext()).isEqualTo(navigation());
        }

        @Test
        @DisplayName("returns all five components of a populated row")
        void returnsAllFiveRowComponents() {
            UserResponse.UserRow row = new UserResponse.UserRow("D", USER_ID, FIRST_NAME, LAST_NAME,
                    ADMIN_CODE);

            assertThat(row.selector()).isEqualTo("D");
            assertThat(row.userId()).isEqualTo(USER_ID);
            assertThat(row.firstName()).isEqualTo(FIRST_NAME);
            assertThat(row.lastName()).isEqualTo(LAST_NAME);
            assertThat(row.userType()).isEqualTo(ADMIN_CODE);
        }

        @Test
        @DisplayName("answers both presence questions in both polarities")
        void answersBothPresenceQuestionsInBothPolarities() {
            UserResponse withBoth = populated();
            UserResponse withNeither = empty();

            assertThat(withBoth.hasRows()).isTrue();
            assertThat(withBoth.hasFieldErrors()).isTrue();
            assertThat(withNeither.hasRows()).isFalse();
            assertThat(withNeither.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("serves the list transaction with rows and the three write transactions "
                + "without them")
        void servesAllFourTransactions() {
            UserResponse listResult = new UserResponse(rows(PageMetadata.USER_LIST_PAGE_SIZE), forwardPage(),
                    USER_ID, null, null, null, TRANSACTION_NAME, TITLE_01, CURRENT_DATE,
                    PROGRAM_NAME, TITLE_02, CURRENT_TIME, null, null, false, false, FOCUS_FIELD,
                    null, navigation());
            UserResponse addResult = reporting(
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX, false);
            UserResponse updateResult = reporting(
                    UserResponse.MSG_UPDATE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_UPDATE_SUCCESS_SUFFIX, false);
            UserResponse deleteResult = reporting(
                    UserResponse.MSG_DELETE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_DELETE_SUCCESS_SUFFIX, false);

            assertThat(listResult.hasRows()).isTrue();
            assertThat(List.of(addResult, updateResult, deleteResult))
                    .allSatisfy(result -> {
                        assertThat(result.hasRows()).isFalse();
                        assertThat(result.pageMetadata()).isNull();
                        assertThat(result.generalError()).isFalse();
                        assertThat(violationsOf(result)).isEmpty();
                    });
            assertThat(List.of(addResult.message(), updateResult.message(), deleteResult.message()))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("reports a successful update with its fields still populated, unlike add and "
                + "delete which clear theirs")
        void reportsASuccessfulUpdateWithFieldsStillPopulated() {
            UserResponse updateResult = new UserResponse(null, null, USER_ID, FIRST_NAME, LAST_NAME,
                    USER_CODE, null, null, null, null, null, null,
                    UserResponse.MSG_UPDATE_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_UPDATE_SUCCESS_SUFFIX,
                    null, false, true, null, null, null);
            UserResponse addResult = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null,
                    UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                            + UserResponse.MSG_ADD_SUCCESS_SUFFIX,
                    null, false, true, null, null, null);

            assertThat(updateResult.userId()).isEqualTo(USER_ID);
            assertThat(updateResult.firstName()).isEqualTo(FIRST_NAME);
            assertThat(addResult.userId())
                    .as("add clears every field before reporting success; the two behaviours are "
                            + "deliberately not unified")
                    .isNull();
            assertThat(addResult.actionSucceeded()).isTrue();
            assertThat(updateResult.actionSucceeded()).isTrue();
        }
    }
}
