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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract tests for {@link UserResponse}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The declared shape of the response shared by all four administrative user transactions: its
 * nineteen components and their order, the twelve widths it measures and the seven components it
 * leaves unmeasured, the nested row's five map-derived components, the thirty-five published message
 * texts, the two collections its compact constructor normalises, and the one field it deliberately
 * refuses to carry.
 *
 * <h2>The credential is the documented parity exception, and its absence is asserted</h2>
 *
 * <p>Two of the four legacy screens round-trip an eight-character credential on their output side.
 * This contract knowingly declines to reproduce that, and the refusal is total: no component, no
 * accessor, no masked or truncated form, no length and no derived presence indicator. The tests
 * below prove the absence structurally rather than trusting the documentation, and separately prove
 * that the only place the word appears is inside two external-contract message texts that report an
 * input was left blank.
 *
 * <h2>Two failure arms carry a message with the error flag clear, so nothing may be derived</h2>
 *
 * <p>The invalid-selection arm lists the page anyway, and the no-change arm re-sends the screen; both
 * set a message without raising the error flag. A response therefore legitimately carries a message
 * alongside a fully populated ten-row list with the flag clear. Both arms are exercised as explicit
 * shapes, because deriving the flag from message presence would misreport each as a failure.
 *
 * <h2>Thirty-five constants hold twenty-three distinct texts, and the duplication is deliberate</h2>
 *
 * <p>Several texts recur across the add, update and delete families. Each family declares its own
 * constant so that one screen's contract can change without disturbing another's, so the tests pin
 * both counts: thirty-five declared constants and twenty-three distinct values. The punctuation is
 * pinned too — twenty-five texts close with three tight dots, six with a space before them, and four
 * carry no ellipsis at all.
 *
 * <h2>The nested row follows the map, not the program's staging table</h2>
 *
 * <p>A ten-occurrence internal staging table describes the same rows with a single combined
 * twenty-five-character name and an eight-character type. That is a terminal display line and is not
 * the contract. The row keeps two separate twenty-character names and a one-character type, and the
 * tests assert the absence of the combined name, the wide type and the spacing pads.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>Payloads come from {@link JsonContractSupport#declaredSettingsMapper()}, a mapper carrying the
 * four serialisation settings this module declares, written out by hand in one place rather than
 * copied into every suite. That evidences the shape this type takes <em>under those settings</em>,
 * and nothing more. It is not evidence about the mapper a deployed instance holds, and no assertion
 * below is worded as though it were; {@code ApplicationJsonContractTest} is the in-boundary evidence
 * for the deployed object.
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy antecedents cited by the type under test: programs {@code app/cbl/COUSR00C.cbl},
 * {@code app/cbl/COUSR01C.cbl}, {@code app/cbl/COUSR02C.cbl} and {@code app/cbl/COUSR03C.cbl},
 * symbolic maps {@code app/cpy-bms/COUSR00.CPY} through {@code COUSR03.CPY} and record layout
 * {@code app/cpy/CSUSR01Y.cpy}. Checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec};
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source
 * text is reproduced here.
 */
@DisplayName("UserResponse :: response contract of legacy transactions CU00 through CU03")
class UserResponseCoverageTest {

    /** The nineteen components, in the order the record declares them. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "rows",
            "pageMetadata",
            "userId",
            "firstName",
            "lastName",
            "userType",
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "message",
            "fieldErrors",
            "generalError",
            "actionSucceeded",
            "focusScreenFieldId",
            "nextRoute",
            "navigationContext");

    /** The twelve components that carry a declared maximum length. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "userId",
            "firstName",
            "lastName",
            "userType",
            "transactionName",
            "title01",
            "currentDate",
            "programName",
            "title02",
            "currentTime",
            "message",
            "focusScreenFieldId");

    /** The seven components that carry no declared maximum length. */
    private static final List<String> UNBOUNDED_COMPONENTS = List.of(
            "rows",
            "pageMetadata",
            "fieldErrors",
            "generalError",
            "actionSucceeded",
            "nextRoute",
            "navigationContext");

    /** The nested row's five components, in the order the record declares them. */
    private static final List<String> EXPECTED_ROW_COMPONENTS =
            List.of("selector", "userId", "firstName", "lastName", "userType");

    /** The twelve declared width constants, by name. */
    private static final List<String> EXPECTED_WIDTH_CONSTANTS = List.of(
            "SELECTOR_LENGTH",
            // No row count appears in this list. Every constant here states the width of one screen
            // item; the number of row slots the list screen declares is a screen dimension rather than
            // a field width, and PageMetadata states it once for the whole module. Publishing it here
            // as well would create a competing source of truth for the same measurement.
            "USER_ID_LENGTH",
            "FIRST_NAME_LENGTH",
            "LAST_NAME_LENGTH",
            "USER_TYPE_LENGTH",
            "TRANSACTION_NAME_LENGTH",
            "SCREEN_TITLE_LENGTH",
            "CURRENT_DATE_LENGTH",
            "PROGRAM_NAME_LENGTH",
            "CURRENT_TIME_LENGTH",
            "MESSAGE_LENGTH",
            "SCREEN_FIELD_ID_LENGTH");

    /** Eight-character user identifier. */
    private static final String USER_ID = "ADMIN001";

    /** Twenty-character-bounded first name. */
    private static final String FIRST_NAME = "Alice";

    /** Twenty-character-bounded last name. */
    private static final String LAST_NAME = "Anderson";

    /** One raw character of user type; carried uninterpreted. */
    private static final String USER_TYPE = "A";

    /** Transaction identifier this screen displays in its header. */
    private static final String TRANSACTION_NAME = "CU00";

    /** First screen title line. */
    private static final String TITLE_01 = "AWS Mainframe Modernization";

    /** Clock date as the screen renders it. */
    private static final String CURRENT_DATE = "08/02/26";

    /** Program name this screen displays in its header. */
    private static final String PROGRAM_NAME = "COUSR00C";

    /** Second screen title line. */
    private static final String TITLE_02 = "CardDemo";

    /** Clock time as the screen renders it. */
    private static final String CURRENT_TIME = "14:35:07";

    /** Map field name the client should place the cursor in; seven characters at most. */
    private static final String FIELD_TO_FOCUS = "USRID01";

    /** Declarative route; deliberately unbounded because it is service-owned. */
    private static final String ROUTE = "/api/admin/users";

    /** A distinctive backward cursor key, used to prove the paging record withholds it. */
    private static final String PREVIOUS_CURSOR_KEY = "ZQ71835X";

    /** A distinctive forward cursor key, used to prove the paging record withholds it. */
    private static final String NEXT_CURSOR_KEY = "MV62094T";

    /** Eight-character page indicator; text, so its leading zeros survive. */
    private static final String DISPLAYED_PAGE_NUMBER = "00000001";

    /** The exact assembled add-success sentence, written out by hand. */
    private static final String ASSEMBLED_ADD_SUCCESS = "User ADMIN001 has been added ...";

    /** The exact assembled update-success sentence, written out by hand. */
    private static final String ASSEMBLED_UPDATE_SUCCESS = "User ADMIN001 has been updated ...";

    /** The exact assembled delete-success sentence, written out by hand. */
    private static final String ASSEMBLED_DELETE_SUCCESS = "User ADMIN001 has been deleted ...";

    /** A field error whose state reports a blank field. */
    private static final ErrorResponse.FieldError MISSING_FIRST_NAME =
            new ErrorResponse.FieldError(
                    "firstName",
                    "FNAME",
                    ErrorResponse.FieldState.MISSING,
                    "First Name can NOT be empty...");

    /** A field error whose state reports a badly filled field. */
    private static final ErrorResponse.FieldError INVALID_USER_TYPE =
            new ErrorResponse.FieldError(
                    "userType",
                    "USRTYPE",
                    ErrorResponse.FieldState.INVALID,
                    "User Type can NOT be empty...");

    /** Bean Validation factory, opened once for the class and closed after it. */
    private static ValidatorFactory validatorFactory;

    /** Validator obtained from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the Bean Validation factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the Bean Validation factory opened for this class. */
    @AfterAll
    static void closeValidatorFactory() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * Supplies each published message text alongside an independently written expectation of its
     * exact characters and length.
     *
     * @return the published constant, the hand-written expectation and the expected length
     */
    static Stream<Arguments> publishedMessages() {
        return Stream.of(
                Arguments.of(
                        UserResponse.MSG_LIST_INVALID_SELECTION,
                        "Invalid selection. Valid values are U and D",
                        43),
                Arguments.of(
                        UserResponse.MSG_LIST_ALREADY_AT_TOP,
                        "You are already at the top of the page...",
                        41),
                Arguments.of(
                        UserResponse.MSG_LIST_ALREADY_AT_BOTTOM,
                        "You are already at the bottom of the page...",
                        44),
                Arguments.of(
                        UserResponse.MSG_LIST_AT_TOP, "You are at the top of the page...", 33),
                Arguments.of(
                        UserResponse.MSG_LIST_REACHED_BOTTOM,
                        "You have reached the bottom of the page...",
                        42),
                Arguments.of(
                        UserResponse.MSG_LIST_REACHED_TOP,
                        "You have reached the top of the page...",
                        39),
                Arguments.of(
                        UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER, "Unable to lookup User...", 24),
                Arguments.of(
                        UserResponse.MSG_ADD_FIRST_NAME_EMPTY,
                        "First Name can NOT be empty...",
                        30),
                Arguments.of(
                        UserResponse.MSG_ADD_LAST_NAME_EMPTY, "Last Name can NOT be empty...", 29),
                Arguments.of(
                        UserResponse.MSG_ADD_USER_ID_EMPTY, "User ID can NOT be empty...", 27),
                Arguments.of(
                        UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY,
                        "Password can NOT be empty...",
                        28),
                Arguments.of(
                        UserResponse.MSG_ADD_USER_TYPE_EMPTY, "User Type can NOT be empty...", 29),
                Arguments.of(UserResponse.MSG_ADD_SUCCESS_PREFIX, "User ", 5),
                Arguments.of(UserResponse.MSG_ADD_SUCCESS_SUFFIX, " has been added ...", 19),
                Arguments.of(
                        UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST, "User ID already exist...", 24),
                Arguments.of(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER, "Unable to Add User...", 21),
                Arguments.of(
                        UserResponse.MSG_UPDATE_USER_ID_EMPTY, "User ID can NOT be empty...", 27),
                Arguments.of(
                        UserResponse.MSG_UPDATE_FIRST_NAME_EMPTY,
                        "First Name can NOT be empty...",
                        30),
                Arguments.of(
                        UserResponse.MSG_UPDATE_LAST_NAME_EMPTY,
                        "Last Name can NOT be empty...",
                        29),
                Arguments.of(
                        UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY,
                        "Password can NOT be empty...",
                        28),
                Arguments.of(
                        UserResponse.MSG_UPDATE_USER_TYPE_EMPTY,
                        "User Type can NOT be empty...",
                        29),
                Arguments.of(UserResponse.MSG_UPDATE_NO_CHANGE, "Please modify to update ...", 27),
                Arguments.of(
                        UserResponse.MSG_UPDATE_PRESS_PF5,
                        "Press PF5 key to save your updates ...",
                        38),
                Arguments.of(
                        UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND, "User ID NOT found...", 20),
                Arguments.of(
                        UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER,
                        "Unable to lookup User...",
                        24),
                Arguments.of(UserResponse.MSG_UPDATE_SUCCESS_PREFIX, "User ", 5),
                Arguments.of(UserResponse.MSG_UPDATE_SUCCESS_SUFFIX, " has been updated ...", 21),
                Arguments.of(
                        UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER,
                        "Unable to Update User...",
                        24),
                Arguments.of(
                        UserResponse.MSG_DELETE_USER_ID_EMPTY, "User ID can NOT be empty...", 27),
                Arguments.of(
                        UserResponse.MSG_DELETE_PRESS_PF5,
                        "Press PF5 key to delete this user ...",
                        37),
                Arguments.of(
                        UserResponse.MSG_DELETE_USER_ID_NOT_FOUND, "User ID NOT found...", 20),
                Arguments.of(
                        UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER,
                        "Unable to lookup User...",
                        24),
                Arguments.of(UserResponse.MSG_DELETE_SUCCESS_PREFIX, "User ", 5),
                Arguments.of(UserResponse.MSG_DELETE_SUCCESS_SUFFIX, " has been deleted ...", 21),
                Arguments.of(
                        UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER,
                        "Unable to Update User...",
                        24));
    }

    /**
     * Supplies the twenty-five texts that close with three dots and no preceding space.
     *
     * @return the tightly punctuated texts
     */
    static Stream<Arguments> tightEllipsisMessages() {
        return Stream.of(
                Arguments.of(UserResponse.MSG_LIST_ALREADY_AT_TOP),
                Arguments.of(UserResponse.MSG_LIST_ALREADY_AT_BOTTOM),
                Arguments.of(UserResponse.MSG_LIST_AT_TOP),
                Arguments.of(UserResponse.MSG_LIST_REACHED_BOTTOM),
                Arguments.of(UserResponse.MSG_LIST_REACHED_TOP),
                Arguments.of(UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER),
                Arguments.of(UserResponse.MSG_ADD_FIRST_NAME_EMPTY),
                Arguments.of(UserResponse.MSG_ADD_LAST_NAME_EMPTY),
                Arguments.of(UserResponse.MSG_ADD_USER_ID_EMPTY),
                Arguments.of(UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY),
                Arguments.of(UserResponse.MSG_ADD_USER_TYPE_EMPTY),
                Arguments.of(UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST),
                Arguments.of(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER),
                Arguments.of(UserResponse.MSG_UPDATE_USER_ID_EMPTY),
                Arguments.of(UserResponse.MSG_UPDATE_FIRST_NAME_EMPTY),
                Arguments.of(UserResponse.MSG_UPDATE_LAST_NAME_EMPTY),
                Arguments.of(UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY),
                Arguments.of(UserResponse.MSG_UPDATE_USER_TYPE_EMPTY),
                Arguments.of(UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND),
                Arguments.of(UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER),
                Arguments.of(UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER),
                Arguments.of(UserResponse.MSG_DELETE_USER_ID_EMPTY),
                Arguments.of(UserResponse.MSG_DELETE_USER_ID_NOT_FOUND),
                Arguments.of(UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER),
                Arguments.of(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER));
    }

    /**
     * Supplies the six texts that place a space before their three dots.
     *
     * @return the spaced-ellipsis texts
     */
    static Stream<Arguments> spacedEllipsisMessages() {
        return Stream.of(
                Arguments.of(UserResponse.MSG_ADD_SUCCESS_SUFFIX),
                Arguments.of(UserResponse.MSG_UPDATE_SUCCESS_SUFFIX),
                Arguments.of(UserResponse.MSG_DELETE_SUCCESS_SUFFIX),
                Arguments.of(UserResponse.MSG_UPDATE_NO_CHANGE),
                Arguments.of(UserResponse.MSG_UPDATE_PRESS_PF5),
                Arguments.of(UserResponse.MSG_DELETE_PRESS_PF5));
    }

    /**
     * Supplies the four texts that carry no terminal ellipsis at all.
     *
     * @return the texts with no ellipsis
     */
    static Stream<Arguments> nonEllipsisMessages() {
        return Stream.of(
                Arguments.of(UserResponse.MSG_LIST_INVALID_SELECTION),
                Arguments.of(UserResponse.MSG_ADD_SUCCESS_PREFIX),
                Arguments.of(UserResponse.MSG_UPDATE_SUCCESS_PREFIX),
                Arguments.of(UserResponse.MSG_DELETE_SUCCESS_PREFIX));
    }

    /**
     * Supplies the five paging-boundary texts this screen shares with the transaction-list screen.
     *
     * @return each shared text as declared by both contracts
     */
    static Stream<Arguments> sharedBoundaryTexts() {
        return Stream.of(
                Arguments.of(
                        UserResponse.MSG_LIST_ALREADY_AT_TOP,
                        TransactionListResponse.MESSAGE_ALREADY_AT_TOP),
                Arguments.of(
                        UserResponse.MSG_LIST_ALREADY_AT_BOTTOM,
                        TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM),
                Arguments.of(
                        UserResponse.MSG_LIST_AT_TOP, TransactionListResponse.MESSAGE_AT_TOP),
                Arguments.of(
                        UserResponse.MSG_LIST_REACHED_BOTTOM,
                        TransactionListResponse.MESSAGE_REACHED_BOTTOM),
                Arguments.of(
                        UserResponse.MSG_LIST_REACHED_TOP,
                        TransactionListResponse.MESSAGE_REACHED_TOP));
    }

    /**
     * Builds a response from the supplied components, leaving every unnamed component absent.
     *
     * @param text the text components to populate, keyed by declared component name
     * @param rows the rows to carry, which may be {@code null}
     * @param pageMetadata the paging record to carry, which may be {@code null}
     * @param fieldErrors the per-field errors to carry, which may be {@code null}
     * @param generalError whether the response reports a general failure
     * @param actionSucceeded whether the response reports a completed action
     * @param navigation the echoed navigation state, which may be {@code null}
     * @return a response carrying exactly the supplied components
     */
    private static UserResponse build(
            Map<String, String> text,
            List<UserResponse.UserRow> rows,
            PageMetadata pageMetadata,
            List<ErrorResponse.FieldError> fieldErrors,
            boolean generalError,
            boolean actionSucceeded,
            NavigationContext navigation) {
        return new UserResponse(
                rows,
                pageMetadata,
                text.get("userId"),
                text.get("firstName"),
                text.get("lastName"),
                text.get("userType"),
                text.get("transactionName"),
                text.get("title01"),
                text.get("currentDate"),
                text.get("programName"),
                text.get("title02"),
                text.get("currentTime"),
                text.get("message"),
                fieldErrors,
                generalError,
                actionSucceeded,
                text.get("focusScreenFieldId"),
                text.get("nextRoute"),
                navigation);
    }

    /**
     * Builds a response carrying exactly one text component.
     *
     * @param component the component to populate
     * @param value the value to place in that component
     * @return a response carrying only the named component
     */
    private static UserResponse carrying(String component, String value) {
        Map<String, String> text = new HashMap<>();
        text.put(component, value);
        return build(text, null, null, null, false, false, null);
    }

    /**
     * Builds the empty response: no component populated and both indicators clear.
     *
     * @return a response with every optional component absent
     */
    private static UserResponse empty() {
        return build(Map.of(), null, null, null, false, false, null);
    }

    /**
     * Builds one row from its five components.
     *
     * @param selector the raw one-character action marker
     * @param userId the eight-character identifier
     * @param firstName the first name
     * @param lastName the last name
     * @param userType the raw one-character type
     * @return the assembled row
     */
    private static UserResponse.UserRow row(
            String selector, String userId, String firstName, String lastName, String userType) {
        return new UserResponse.UserRow(selector, userId, firstName, lastName, userType);
    }

    /**
     * Builds the ten rows a full list page carries, in ascending identifier order.
     *
     * @return ten rows in presentation order
     */
    private static List<UserResponse.UserRow> tenAscendingRows() {
        return IntStream.rangeClosed(1, 10)
                .mapToObj(
                        index ->
                                row(
                                        " ",
                                        String.format(Locale.ROOT, "USER%04d", index),
                                        "First" + index,
                                        "Last" + index,
                                        (index % 2 == 0) ? "A" : "U"))
                .toList();
    }

    /**
     * Builds a forward paging record carrying the two distinctive cursor keys.
     *
     * @return a forward paging record for a ten-row page
     */
    private static PageMetadata forwardPage() {
        return PageMetadata.forward(
                PageMetadata.USER_LIST_PAGE_SIZE,
                PREVIOUS_CURSOR_KEY,
                NEXT_CURSOR_KEY,
                true,
                false,
                DISPLAYED_PAGE_NUMBER);
    }

    /**
     * Builds the every-component fixture, optionally carrying the echoed navigation state.
     *
     * @param navigation the echoed navigation state, which may be {@code null}
     * @return a response carrying every component
     */
    private static UserResponse populated(NavigationContext navigation) {
        Map<String, String> text = new HashMap<>();
        text.put("userId", USER_ID);
        text.put("firstName", FIRST_NAME);
        text.put("lastName", LAST_NAME);
        text.put("userType", USER_TYPE);
        text.put("transactionName", TRANSACTION_NAME);
        text.put("title01", TITLE_01);
        text.put("currentDate", CURRENT_DATE);
        text.put("programName", PROGRAM_NAME);
        text.put("title02", TITLE_02);
        text.put("currentTime", CURRENT_TIME);
        text.put("message", UserResponse.MSG_LIST_REACHED_BOTTOM);
        text.put("focusScreenFieldId", FIELD_TO_FOCUS);
        text.put("nextRoute", ROUTE);

        return build(
                text,
                tenAscendingRows(),
                forwardPage(),
                List.of(MISSING_FIRST_NAME, INVALID_USER_TYPE),
                true,
                false,
                navigation);
    }

    /**
     * Serialises the supplied response with the module's declared settings.
     *
     * @param response the response to serialise
     * @return the emitted JSON text
     * @throws JsonProcessingException if serialisation fails, which fails the calling test
     */
    private static String payloadOf(UserResponse response) throws JsonProcessingException {
        return JsonContractSupport.declaredSettingsMapper().writeValueAsString(response);
    }

    /** The declared shape of the record: components, order, widths and declared surface. */
    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        /**
         * Reads the declared maximum length of a component of the supplied record type.
         *
         * @param type the record type to inspect
         * @param component the component name
         * @return the declared maximum length
         * @throws NoSuchFieldException if the component does not exist, failing the calling test
         */
        private int boundOf(Class<?> type, String component) throws NoSuchFieldException {
            Size size = type.getDeclaredField(component).getAnnotation(Size.class);
            assertThat(size)
                    .as("component %s must declare a maximum length", component)
                    .isNotNull();
            return size.max();
        }

        /** The nineteen components appear in the documented order. */
        @Test
        @DisplayName("declares nineteen components in the documented order")
        void theComponentsAreDeclaredInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(UserResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        /** The two collaborators lead, so the list-shaped components are declared first. */
        @Test
        @DisplayName("declares the row list and paging record first")
        void theCollaboratorsAreDeclaredFirst() {
            List<String> declared =
                    Arrays.stream(UserResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared.subList(0, 2)).containsExactly("rows", "pageMetadata");
        }

        /** Each bounded component declares the width the symbolic map declares. */
        @ParameterizedTest(name = "{0} is bounded at {1}")
        @CsvSource({
            "userId,8",
            "firstName,20",
            "lastName,20",
            "userType,1",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "message,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("declares each screen width")
        void eachBoundedComponentDeclaresItsDocumentedWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(boundOf(UserResponse.class, component)).isEqualTo(width);
        }

        /** The collections, paging record, indicators, route and state carry no width. */
        @ParameterizedTest(name = "{0} declares no width")
        @ValueSource(
                strings = {
                    "rows",
                    "pageMetadata",
                    "fieldErrors",
                    "generalError",
                    "actionSucceeded",
                    "nextRoute",
                    "navigationContext"
                })
        @DisplayName("leaves the collections, paging record, indicators and route unbounded")
        void theUnboundedComponentsDeclareNoWidth(String component) throws NoSuchFieldException {
            assertThat(UserResponse.class.getDeclaredField(component).getAnnotation(Size.class))
                    .isNull();
        }

        /** Every component is accounted for as bounded or unbounded. */
        @Test
        @DisplayName("accounts for every component as bounded or unbounded")
        void everyComponentIsAccountedFor() {
            List<String> partition = new ArrayList<>(BOUNDED_COMPONENTS);
            partition.addAll(UNBOUNDED_COMPONENTS);

            assertThat(partition).containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
            assertThat(BOUNDED_COMPONENTS).hasSize(12);
            assertThat(UNBOUNDED_COMPONENTS).hasSize(7);
        }

        /** Each declared width constant carries its documented value. */
        @ParameterizedTest(name = "{0} equals {1}")
        @CsvSource({
            "SELECTOR_LENGTH,1",
            "USER_ID_LENGTH,8",
            "FIRST_NAME_LENGTH,20",
            "LAST_NAME_LENGTH,20",
            "USER_TYPE_LENGTH,1",
            "TRANSACTION_NAME_LENGTH,4",
            "SCREEN_TITLE_LENGTH,40",
            "CURRENT_DATE_LENGTH,8",
            "PROGRAM_NAME_LENGTH,8",
            "CURRENT_TIME_LENGTH,8",
            "MESSAGE_LENGTH,78",
            "SCREEN_FIELD_ID_LENGTH,7"
        })
        @DisplayName("publishes each width constant at its documented value")
        void eachWidthConstantCarriesItsDocumentedValue(String constant, int value)
                throws ReflectiveOperationException {
            Field field = UserResponse.class.getDeclaredField(constant);

            assertThat(field.getInt(null)).isEqualTo(value);
            assertThat(Modifier.isPublic(field.getModifiers())).isTrue();
        }

        /**
         * The selector width and the user-type width are declared separately even though both are
         * one, because they are unrelated fields whose widths coincide by accident.
         */
        @Test
        @DisplayName("declares the selector and user-type widths separately")
        void theSelectorAndUserTypeWidthsAreDeclaredSeparately() throws NoSuchFieldException {
            assertThat(UserResponse.SELECTOR_LENGTH).isEqualTo(1);
            assertThat(UserResponse.USER_TYPE_LENGTH).isEqualTo(1);
            assertThat(UserResponse.class.getDeclaredField("SELECTOR_LENGTH")).isNotNull();
            assertThat(UserResponse.class.getDeclaredField("USER_TYPE_LENGTH")).isNotNull();
        }

        /** One title constant governs both title lines, because they are the same field kind. */
        @Test
        @DisplayName("governs both title lines with one width constant")
        void oneTitleConstantGovernsBothTitleLines() throws NoSuchFieldException {
            assertThat(boundOf(UserResponse.class, "title01"))
                    .isEqualTo(UserResponse.SCREEN_TITLE_LENGTH);
            assertThat(boundOf(UserResponse.class, "title02"))
                    .isEqualTo(UserResponse.SCREEN_TITLE_LENGTH);
        }

        /**
         * No page-size constant is declared here: the row count is screen shape carried as data by
         * the paging record, and the module's three page sizes are never shared.
         */
        @Test
        @DisplayName("declares no page-size constant")
        void noPageSizeConstantIsDeclared() {
            List<String> names =
                    Arrays.stream(UserResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .map(Field::getName)
                            .toList();

            assertThat(names).noneMatch(name -> name.contains("PAGE_SIZE"));
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE)
                    .as("the row count lives on the paging record")
                    .isEqualTo(10);
        }

        /** Every value component is text; no numeric or temporal type appears. */
        @Test
        @DisplayName("carries every value component as text")
        void everyValueComponentIsText() {
            for (String component : BOUNDED_COMPONENTS) {
                assertThat(UserResponse.class.getRecordComponents())
                        .filteredOn(candidate -> candidate.getName().equals(component))
                        .allSatisfy(
                                candidate ->
                                        assertThat(candidate.getType()).isEqualTo(String.class));
            }
            assertThat(UserResponse.class.getRecordComponents())
                    .allSatisfy(
                            component ->
                                    assertThat(component.getType().getName())
                                            .doesNotStartWith("java.time")
                                            .isNotEqualTo("java.math.BigDecimal"));
        }

        /** Both indicators are primitive booleans, stated explicitly. */
        @ParameterizedTest(name = "{0} is a primitive boolean")
        @ValueSource(strings = {"generalError", "actionSucceeded"})
        @DisplayName("carries both indicators as primitive booleans")
        void bothIndicatorsArePrimitiveBooleans(String component) throws NoSuchFieldException {
            assertThat(UserResponse.class.getDeclaredField(component).getType())
                    .isEqualTo(boolean.class);
        }

        /**
         * The user type is raw text rather than the domain enumeration, so a stored value outside
         * the expected pair serialises instead of failing.
         */
        @Test
        @DisplayName("carries the user type as raw text and not as an enumeration")
        void theUserTypeIsRawText() throws NoSuchFieldException {
            assertThat(UserResponse.class.getDeclaredField("userType").getType())
                    .isEqualTo(String.class);
            assertThat(UserResponse.UserRow.class.getDeclaredField("userType").getType())
                    .isEqualTo(String.class);
            assertThat(UserResponse.class.getRecordComponents())
                    .allSatisfy(
                            component ->
                                    assertThat(component.getType().isEnum())
                                            .as("no component is an enumeration")
                                            .isFalse());
        }

        /** The declared surface beyond the accessors is the two presence tests. */
        @Test
        @DisplayName("declares two presence tests and nothing else")
        void theDeclaredSurfaceBeyondTheAccessorsIsTwoPresenceTests() {
            List<String> declared =
                    Arrays.stream(UserResponse.class.getDeclaredMethods())
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(
                                    name ->
                                            !"equals".equals(name)
                                                    && !"hashCode".equals(name)
                                                    && !"toString".equals(name))
                            .filter(name -> !EXPECTED_COMPONENTS.contains(name))
                            .toList();

            assertThat(declared).containsExactlyInAnyOrder("hasRows", "hasFieldErrors");
        }

        /** Only the compact canonical constructor exists, taking all nineteen components. */
        @Test
        @DisplayName("keeps only the compact canonical constructor")
        void onlyTheCompactCanonicalConstructorExists() {
            assertThat(UserResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(UserResponse.class.getDeclaredConstructors()[0].getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
        }

        /** No serialisation annotation appears on any component. */
        @Test
        @DisplayName("declares no serialisation annotation on any component")
        void noSerialisationAnnotationAppearsOnAnyComponent() {
            List<Annotation> annotations = new ArrayList<>();
            for (Field field : UserResponse.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }
            for (Field field : UserResponse.UserRow.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }

            assertThat(annotations)
                    .noneMatch(
                            annotation ->
                                    annotation
                                            .annotationType()
                                            .getName()
                                            .startsWith("com.fasterxml.jackson"));
        }

        /** No constraint other than a maximum length appears anywhere on either record. */
        @Test
        @DisplayName("declares no constraint other than a maximum length")
        void noConstraintOtherThanAMaximumLengthIsDeclared() {
            List<Annotation> constraints = new ArrayList<>();
            for (Field field : UserResponse.class.getDeclaredFields()) {
                Arrays.stream(field.getAnnotations())
                        .filter(
                                annotation ->
                                        annotation
                                                .annotationType()
                                                .getName()
                                                .startsWith("jakarta.validation"))
                        .forEach(constraints::add);
            }

            assertThat(constraints)
                    .allSatisfy(
                            annotation ->
                                    assertThat(annotation.annotationType()).isEqualTo(Size.class));
            assertThat(constraints).hasSize(BOUNDED_COMPONENTS.size());
        }

        /**
         * The static surface is thirteen widths, thirty-five texts and one unpublished stand-in.
         *
         * <p>The one non-public static is the diagnostic stand-in the rendering writes in place of a
         * withheld value. It is deliberately not published: no screen displays it and no client receives
         * it, so publishing it would invite a caller to depend on its spelling. Naming it here rather
         * than relaxing the "nothing internal" claim keeps the claim exact - every other static is
         * public, and a second internal static could not be added without this test failing and saying
         * which.</p>
         *
         * <p>Every one of the twelve widths states the size of a single screen item. The number of row
         * slots the list screen declares is deliberately not among them: it is a screen dimension rather
         * than a field width, and the paging contract states it once for the whole module, so publishing
         * it here as well would make this response body a second source of truth for the same
         * measurement.</p>
         */
        @Test
        @DisplayName("publishes twelve widths and thirty-five texts, and declares exactly one "
                + "static for internal use")
        void theStaticSurfaceIsTwelveWidthsAndThirtyFiveTexts() {
            List<Field> statics =
                    Arrays.stream(UserResponse.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .toList();

            List<String> widths =
                    statics.stream()
                            .filter(field -> field.getType() == int.class)
                            .map(Field::getName)
                            .toList();
            List<String> texts =
                    statics.stream()
                            .filter(field -> field.getType() == String.class)
                            .filter(field -> Modifier.isPublic(field.getModifiers()))
                            .map(Field::getName)
                            .toList();

            assertThat(widths).containsExactlyInAnyOrderElementsOf(EXPECTED_WIDTH_CONSTANTS);
            assertThat(widths)
                    .as("every published width names one screen item, so none of them is a count")
                    .hasSize(12)
                    .allMatch(name -> name.endsWith("_LENGTH"));
            assertThat(texts).hasSize(35).allMatch(name -> name.startsWith("MSG_"));
            assertThat(statics).hasSize(48);

            List<String> internal =
                    statics.stream()
                            .filter(field -> !Modifier.isPublic(field.getModifiers()))
                            .map(Field::getName)
                            .toList();
            assertThat(internal)
                    .as("the diagnostic stand-in is the one static a caller may not depend on")
                    .containsExactly("REDACTION_PLACEHOLDER");
        }
    }

    /** The nested row's shape, which follows the map rather than the program's staging table. */
    @Nested
    @DisplayName("Row contract")
    class RowContract {

        /** The five row components appear in the documented order. */
        @Test
        @DisplayName("declares five row components in the documented order")
        void theRowComponentsAreDeclaredInTheDocumentedOrder() {
            List<String> declared =
                    Arrays.stream(UserResponse.UserRow.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_ROW_COMPONENTS);
        }

        /** Each row component declares the width the map declares. */
        @ParameterizedTest(name = "{0} is bounded at {1}")
        @CsvSource({"selector,1", "userId,8", "firstName,20", "lastName,20", "userType,1"})
        @DisplayName("declares each row width from the map")
        void eachRowComponentDeclaresItsMapWidth(String component, int width)
                throws NoSuchFieldException {
            assertThat(
                            UserResponse.UserRow.class
                                    .getDeclaredField(component)
                                    .getAnnotation(Size.class)
                                    .max())
                    .isEqualTo(width);
        }

        /**
         * The row models no combined name, no eight-wide type and no spacing pad, because the
         * program's staging table is a terminal display line and not the contract.
         */
        @Test
        @DisplayName("models nothing from the program's staging table")
        void theRowModelsNothingFromTheStagingTable() throws NoSuchFieldException {
            List<String> declared =
                    Arrays.stream(UserResponse.UserRow.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared)
                    .doesNotContain("name", "combinedName", "fullName", "filler", "gap", "padding");
            assertThat(
                            UserResponse.UserRow.class
                                    .getDeclaredField("userType")
                                    .getAnnotation(Size.class)
                                    .max())
                    .as("the map declares one character, not the staging table's eight")
                    .isEqualTo(1);
            assertThat(
                            UserResponse.UserRow.class
                                            .getDeclaredField("firstName")
                                            .getAnnotation(Size.class)
                                            .max()
                                    + UserResponse.UserRow.class
                                            .getDeclaredField("lastName")
                                            .getAnnotation(Size.class)
                                            .max())
                    .as("two separate twenty-wide names, not one combined twenty-five")
                    .isEqualTo(40);
        }

        /** Every row component is text, so a leading zero or unexpected character survives. */
        @ParameterizedTest(name = "{0} is text")
        @ValueSource(strings = {"selector", "userId", "firstName", "lastName", "userType"})
        @DisplayName("carries every row component as text")
        void everyRowComponentIsText(String component) throws NoSuchFieldException {
            assertThat(UserResponse.UserRow.class.getDeclaredField(component).getType())
                    .isEqualTo(String.class);
        }

        /** The row keeps only the generated canonical constructor. */
        @Test
        @DisplayName("keeps only the generated canonical constructor on the row")
        void theRowKeepsOnlyTheGeneratedCanonicalConstructor() {
            assertThat(UserResponse.UserRow.class.getDeclaredConstructors()).hasSize(1);
            assertThat(UserResponse.UserRow.class.getDeclaredConstructors()[0].getParameterCount())
                    .isEqualTo(5);
        }

        /** The row declares no member beyond its accessors. */
        @Test
        @DisplayName("declares no member beyond the row accessors")
        void theRowDeclaresNoMemberBeyondItsAccessors() {
            List<String> declared =
                    Arrays.stream(UserResponse.UserRow.class.getDeclaredMethods())
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(
                                    name ->
                                            !"equals".equals(name)
                                                    && !"hashCode".equals(name)
                                                    && !"toString".equals(name))
                            .filter(name -> !EXPECTED_ROW_COMPONENTS.contains(name))
                            .toList();

            assertThat(declared).isEmpty();
        }

        /** A row carries every value exactly as supplied, trailing space included. */
        @Test
        @DisplayName("carries every row value exactly as supplied")
        void aRowCarriesEveryValueExactlyAsSupplied() {
            UserResponse.UserRow carried = row("U", "USER0001", "Ada  ", "Lovelace  ", "?");

            assertThat(carried.selector()).isEqualTo("U");
            assertThat(carried.userId()).isEqualTo("USER0001");
            assertThat(carried.firstName()).isEqualTo("Ada  ");
            assertThat(carried.lastName()).isEqualTo("Lovelace  ");
            assertThat(carried.userType()).isEqualTo("?");
        }

        /** A blank selector is ordinary on a row the operator has not marked. */
        @Test
        @DisplayName("accepts a blank selector")
        void aBlankSelectorIsAccepted() {
            assertThat(validator.validate(row(" ", USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE)))
                    .isEmpty();
            assertThat(validator.validate(row(null, USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE)))
                    .isEmpty();
        }

        /** An over-long row value is reported against the row, not the enclosing response. */
        @ParameterizedTest(name = "{0} over {1} is reported on the row")
        @CsvSource({"selector,1", "userId,8", "firstName,20", "lastName,20", "userType,1"})
        @DisplayName("reports an over-long row value against the row")
        void anOverLongRowValueIsReportedAgainstTheRow(String component, int width) {
            Map<String, String> values = new HashMap<>();
            for (String name : EXPECTED_ROW_COMPONENTS) {
                values.put(name, null);
            }
            values.put(component, "A".repeat(width + 1));

            UserResponse.UserRow candidate =
                    row(
                            values.get("selector"),
                            values.get("userId"),
                            values.get("firstName"),
                            values.get("lastName"),
                            values.get("userType"));

            Set<ConstraintViolation<UserResponse.UserRow>> violations =
                    validator.validate(candidate);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /**
         * A row bound is not cascaded from the enclosing response, so an over-long row value
         * produces no violation at the response level and must be validated directly.
         */
        @Test
        @DisplayName("does not cascade a row bound from the enclosing response")
        void aRowBoundIsNotCascadedFromTheEnclosingResponse() throws NoSuchFieldException {
            UserResponse response =
                    build(
                            Map.of(),
                            List.of(row("XX", USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE)),
                            null,
                            null,
                            false,
                            false,
                            null);

            assertThat(validator.validate(response))
                    .as("no cascade is declared over the row list")
                    .isEmpty();
            assertThat(
                            UserResponse.class
                                    .getDeclaredField("rows")
                                    .getAnnotations())
                    .noneMatch(
                            annotation ->
                                    "jakarta.validation.Valid"
                                            .equals(annotation.annotationType().getName()));
            assertThat(validator.validate(row("XX", USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE)))
                    .hasSize(1);
        }
    }

    /** The thirty-five published message texts, their punctuation and their shared values. */
    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        /** Each published text matches its independently written expectation exactly. */
        @ParameterizedTest(name = "[{index}] {1}")
        @MethodSource("com.carddemo.api.dto.UserResponseCoverageTest#publishedMessages")
        @DisplayName("publishes each message text exactly")
        void eachPublishedTextMatchesItsExpectation(String actual, String expected, int length) {
            assertThat(actual).isEqualTo(expected).hasSize(length);
        }

        /** Every published text fits the seventy-eight-character message bound. */
        @ParameterizedTest(name = "[{index}] fits the message bound")
        @MethodSource("com.carddemo.api.dto.UserResponseCoverageTest#publishedMessages")
        @DisplayName("keeps every published text within the message bound")
        void everyPublishedTextFitsTheMessageBound(String actual, String expected, int length) {
            assertThat(actual).hasSizeLessThanOrEqualTo(UserResponse.MESSAGE_LENGTH);
            assertThat(length).isLessThanOrEqualTo(78);
        }

        /** Twenty-five texts close with three dots and no preceding space. */
        @ParameterizedTest(name = "[{index}] closes tightly")
        @MethodSource("com.carddemo.api.dto.UserResponseCoverageTest#tightEllipsisMessages")
        @DisplayName("closes twenty-five texts with a tight ellipsis")
        void aTightlyPunctuatedTextClosesWithoutASpace(String text) {
            assertThat(text).endsWith("...").doesNotEndWith(" ...").doesNotEndWith("....");
        }

        /** Six texts place a space before their three dots. */
        @ParameterizedTest(name = "[{index}] closes with a spaced ellipsis")
        @MethodSource("com.carddemo.api.dto.UserResponseCoverageTest#spacedEllipsisMessages")
        @DisplayName("closes six texts with a spaced ellipsis")
        void aSpacedTextPlacesASpaceBeforeItsEllipsis(String text) {
            assertThat(text).endsWith(" ...").doesNotEndWith("  ...");
        }

        /** Four texts carry no terminal ellipsis at all. */
        @ParameterizedTest(name = "[{index}] carries no ellipsis")
        @MethodSource("com.carddemo.api.dto.UserResponseCoverageTest#nonEllipsisMessages")
        @DisplayName("gives four texts no terminal ellipsis")
        void aNonEllipsisTextCarriesNone(String text) {
            assertThat(text).doesNotEndWith("...");
        }

        /** The three punctuation groups partition the thirty-five published texts. */
        @Test
        @DisplayName("partitions the thirty-five texts into twenty-five, six and four")
        void thePunctuationGroupsPartitionThePublishedTexts() {
            assertThat(tightEllipsisMessages().count()).isEqualTo(25);
            assertThat(spacedEllipsisMessages().count()).isEqualTo(6);
            assertThat(nonEllipsisMessages().count()).isEqualTo(4);
            assertThat(publishedMessages().count()).isEqualTo(35);
        }

        /**
         * Thirty-five constants hold twenty-three distinct texts. The duplication is deliberate: each
         * transaction family declares its own constant so one screen's contract can change without
         * disturbing another's.
         */
        @Test
        @DisplayName("holds twenty-three distinct texts in thirty-five constants")
        void thirtyFiveConstantsHoldTwentyThreeDistinctTexts() {
            List<String> texts =
                    publishedMessages().map(arguments -> (String) arguments.get()[0]).toList();

            assertThat(texts).hasSize(35);
            assertThat(Set.copyOf(texts)).hasSize(23);
        }

        /** The identifier-empty text is declared once per family and the three agree. */
        @Test
        @DisplayName("declares the identifier-empty text once per family")
        void theIdentifierEmptyTextIsDeclaredOncePerFamily() {
            assertThat(UserResponse.MSG_ADD_USER_ID_EMPTY)
                    .isEqualTo(UserResponse.MSG_UPDATE_USER_ID_EMPTY)
                    .isEqualTo(UserResponse.MSG_DELETE_USER_ID_EMPTY)
                    .isEqualTo("User ID can NOT be empty...");
        }

        /** The lookup-failure text is declared once per family and the three agree. */
        @Test
        @DisplayName("declares the lookup-failure text once per family")
        void theLookupFailureTextIsDeclaredOncePerFamily() {
            assertThat(UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo(UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo(UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo("Unable to lookup User...");
        }

        /**
         * The invalid-selection text names its two valid characters in the plural and carries no
         * ellipsis, which distinguishes it from the transaction-list screen's singular equivalent.
         */
        @Test
        @DisplayName("names two valid characters in the plural, unlike the transaction-list text")
        void theInvalidSelectionTextIsPluralAndDistinct() {
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid values are U and D")
                    .contains("values are")
                    .doesNotEndWith("...");
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid value is S")
                    .contains("value is");
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .as("the two texts are separate external contracts and must never be unified")
                    .isNotEqualTo(TransactionListResponse.MESSAGE_INVALID_SELECTION);
        }

        /**
         * The five paging-boundary texts are byte-identical to the transaction-list screen's five,
         * which is what makes the invalid-selection divergence above significant rather than
         * incidental.
         */
        @ParameterizedTest(name = "[{index}] agrees with the transaction-list text")
        @MethodSource("com.carddemo.api.dto.UserResponseCoverageTest#sharedBoundaryTexts")
        @DisplayName("shares all five paging-boundary texts with the transaction-list screen")
        void theFiveBoundaryTextsAgreeWithTheTransactionListScreen(String mine, String theirs) {
            assertThat(mine).isEqualTo(theirs);
        }

        /** The list screen has five distinct boundary texts, not two. */
        @Test
        @DisplayName("declares five distinct paging-boundary texts")
        void fiveDistinctBoundaryTextsAreDeclared() {
            Set<String> boundary =
                    Set.of(
                            UserResponse.MSG_LIST_ALREADY_AT_TOP,
                            UserResponse.MSG_LIST_ALREADY_AT_BOTTOM,
                            UserResponse.MSG_LIST_AT_TOP,
                            UserResponse.MSG_LIST_REACHED_BOTTOM,
                            UserResponse.MSG_LIST_REACHED_TOP);

            assertThat(boundary).hasSize(5);
        }

        /** The two top-of-page texts differ only by the word that reports no movement. */
        @Test
        @DisplayName("differentiates the two top-of-page texts by one word")
        void theTwoTopOfPageTextsDifferByOneWord() {
            assertThat(UserResponse.MSG_LIST_ALREADY_AT_TOP).contains("already");
            assertThat(UserResponse.MSG_LIST_AT_TOP).doesNotContain("already");
            assertThat(UserResponse.MSG_LIST_ALREADY_AT_TOP)
                    .isNotEqualTo(UserResponse.MSG_LIST_AT_TOP);
        }

        /** All three success prefixes are the same five characters, trailing space included. */
        @Test
        @DisplayName("gives all three success prefixes the same trailing space")
        void allThreeSuccessPrefixesCarryATrailingSpace() {
            assertThat(UserResponse.MSG_ADD_SUCCESS_PREFIX)
                    .isEqualTo("User ")
                    .endsWith(" ")
                    .isEqualTo(UserResponse.MSG_UPDATE_SUCCESS_PREFIX)
                    .isEqualTo(UserResponse.MSG_DELETE_SUCCESS_PREFIX);
        }

        /** The three success suffixes are distinct and each carries a leading space. */
        @Test
        @DisplayName("keeps the three success suffixes distinct")
        void theThreeSuccessSuffixesAreDistinct() {
            assertThat(UserResponse.MSG_ADD_SUCCESS_SUFFIX)
                    .isEqualTo(" has been added ...")
                    .startsWith(" ");
            assertThat(UserResponse.MSG_UPDATE_SUCCESS_SUFFIX)
                    .isEqualTo(" has been updated ...")
                    .startsWith(" ");
            assertThat(UserResponse.MSG_DELETE_SUCCESS_SUFFIX)
                    .isEqualTo(" has been deleted ...")
                    .startsWith(" ");
            assertThat(
                            Set.of(
                                    UserResponse.MSG_ADD_SUCCESS_SUFFIX,
                                    UserResponse.MSG_UPDATE_SUCCESS_SUFFIX,
                                    UserResponse.MSG_DELETE_SUCCESS_SUFFIX))
                    .hasSize(3);
        }

        /**
         * Each success sentence assembles to exactly one space on either side of the identifier: the
         * prefix supplies the leading one and the suffix the trailing one, so no doubling occurs.
         */
        @Test
        @DisplayName("assembles each success sentence with exactly one space around the identifier")
        void eachSuccessSentenceAssemblesWithSingleSpaces() {
            assertThat(
                            UserResponse.MSG_ADD_SUCCESS_PREFIX
                                    + USER_ID
                                    + UserResponse.MSG_ADD_SUCCESS_SUFFIX)
                    .isEqualTo(ASSEMBLED_ADD_SUCCESS)
                    .doesNotContain("  ");
            assertThat(
                            UserResponse.MSG_UPDATE_SUCCESS_PREFIX
                                    + USER_ID
                                    + UserResponse.MSG_UPDATE_SUCCESS_SUFFIX)
                    .isEqualTo(ASSEMBLED_UPDATE_SUCCESS)
                    .doesNotContain("  ");
            assertThat(
                            UserResponse.MSG_DELETE_SUCCESS_PREFIX
                                    + USER_ID
                                    + UserResponse.MSG_DELETE_SUCCESS_SUFFIX)
                    .isEqualTo(ASSEMBLED_DELETE_SUCCESS)
                    .doesNotContain("  ");
        }

        /** No fragment is joined by this type, and the assembled text is not published. */
        @Test
        @DisplayName("joins no success fragment")
        void noSuccessFragmentIsJoinedByThisType() {
            List<String> texts =
                    publishedMessages().map(arguments -> (String) arguments.get()[0]).toList();

            assertThat(texts)
                    .doesNotContain(
                            ASSEMBLED_ADD_SUCCESS,
                            ASSEMBLED_UPDATE_SUCCESS,
                            ASSEMBLED_DELETE_SUCCESS);
        }

        /** The already-exists text keeps its singular verb rather than being corrected. */
        @Test
        @DisplayName("keeps the singular verb of the already-exists text")
        void theAlreadyExistsTextKeepsItsSingularVerb() {
            assertThat(UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST)
                    .isEqualTo("User ID already exist...")
                    .doesNotContain("exists");
        }

        /** The two attention-key prompts name different keys' purposes and stay distinct. */
        @Test
        @DisplayName("keeps the two attention-key prompts distinct")
        void theTwoAttentionKeyPromptsStayDistinct() {
            assertThat(UserResponse.MSG_UPDATE_PRESS_PF5)
                    .isEqualTo("Press PF5 key to save your updates ...");
            assertThat(UserResponse.MSG_DELETE_PRESS_PF5)
                    .isEqualTo("Press PF5 key to delete this user ...");
            assertThat(UserResponse.MSG_UPDATE_PRESS_PF5)
                    .isNotEqualTo(UserResponse.MSG_DELETE_PRESS_PF5);
        }
    }

    /** The credential this contract deliberately refuses to carry. */
    @Nested
    @DisplayName("Credential exclusion")
    class CredentialExclusion {

        /** No component names or implies a credential. */
        @Test
        @DisplayName("declares no credential component")
        void noCredentialComponentIsDeclared() {
            List<String> declared =
                    Arrays.stream(UserResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();

            assertThat(declared)
                    .noneMatch(
                            name ->
                                    name.contains("password")
                                            || name.contains("passwd")
                                            || name.contains("pwd")
                                            || name.contains("credential")
                                            || name.contains("secret"));
        }

        /** The nested row declares no credential component either. */
        @Test
        @DisplayName("declares no credential component on the row")
        void noCredentialComponentIsDeclaredOnTheRow() {
            List<String> declared =
                    Arrays.stream(UserResponse.UserRow.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();

            assertThat(declared)
                    .noneMatch(
                            name ->
                                    name.contains("password")
                                            || name.contains("pwd")
                                            || name.contains("credential"));
        }

        /**
         * No accessor, masked form, length or derived presence indicator exists either, because each
         * of those leaks information about a secret.
         *
         * <p>The three generated canonical methods are excluded before the check is applied. They
         * cannot carry a credential, and one of them — the generated hash — would otherwise match
         * the token that guards against a credential digest accessor.
         */
        @Test
        @DisplayName("exposes no accessor, masked form, length or presence indicator")
        void noCredentialAccessorOrDerivedFormExists() {
            List<String> methods =
                    Stream.concat(
                                    Arrays.stream(UserResponse.class.getDeclaredMethods()),
                                    Arrays.stream(
                                            UserResponse.UserRow.class.getDeclaredMethods()))
                            .filter(method -> !method.isSynthetic())
                            .map(Method::getName)
                            .filter(
                                    name ->
                                            !"equals".equals(name)
                                                    && !"hashCode".equals(name)
                                                    && !"toString".equals(name))
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();

            assertThat(methods)
                    .noneMatch(
                            name ->
                                    name.contains("password")
                                            || name.contains("pwd")
                                            || name.contains("credential")
                                            || name.contains("secret")
                                            || name.contains("mask")
                                            || name.contains("hash")
                                            || name.contains("digest"));
        }

        /** No width constant describes a credential field. */
        @Test
        @DisplayName("declares no credential width")
        void noCredentialWidthIsDeclared() {
            assertThat(EXPECTED_WIDTH_CONSTANTS)
                    .noneMatch(
                            name ->
                                    name.contains("PASSWORD")
                                            || name.contains("PWD")
                                            || name.contains("CREDENTIAL"));
        }

        /**
         * The word appears in exactly two message constants, whose literals report that an input was
         * left blank and carry no credential value of any kind.
         */
        @Test
        @DisplayName("names the field only in the two emptiness texts")
        void theWordAppearsOnlyInTheTwoEmptinessTexts() {
            List<String> naming =
                    publishedMessages()
                            .map(arguments -> (String) arguments.get()[0])
                            .filter(text -> text.contains("Password"))
                            .toList();

            assertThat(naming)
                    .hasSize(2)
                    .allSatisfy(text -> assertThat(text).isEqualTo("Password can NOT be empty..."));
            assertThat(UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY)
                    .isEqualTo(UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY);
        }

        /** No credential value appears anywhere in the serialized payload. */
        @Test
        @DisplayName("emits no credential in the payload")
        void noCredentialAppearsInThePayload() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(
                                    payloadOf(
                                            populated(
                                                    JsonContractSupport.populatedNavigation())));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .allSatisfy(
                            name ->
                                    assertThat(name.toLowerCase(Locale.ROOT))
                                            .doesNotContain("password")
                                            .doesNotContain("pwd")
                                            .doesNotContain("credential"));
        }

        /** A payload naming a credential property is tolerated on read and simply ignored. */
        @Test
        @DisplayName("ignores a credential property offered on read")
        void aCredentialPropertyOfferedOnReadIsIgnored() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload = "{\"userId\":\"ADMIN001\",\"password\":\"PASSWORD\"}";

            UserResponse restored = mapper.readValue(payload, UserResponse.class);

            assertThat(restored.userId()).isEqualTo(USER_ID);
            assertThat(mapper.writeValueAsString(restored)).doesNotContain("PASSWORD");
        }
    }

    /** The two collections the compact constructor normalises, and everything it leaves alone. */
    @Nested
    @DisplayName("Collection discipline")
    class CollectionDiscipline {

        /** An absent row list becomes the empty immutable list. */
        @Test
        @DisplayName("substitutes the empty list for an absent row list")
        void anAbsentRowListBecomesTheEmptyList() {
            UserResponse response = build(Map.of(), null, null, null, false, false, null);

            assertThat(response.rows()).isNotNull().isEmpty();
            assertThat(response.hasRows()).isFalse();
        }

        /** An absent field-error list becomes the empty immutable list. */
        @Test
        @DisplayName("substitutes the empty list for an absent field-error list")
        void anAbsentFieldErrorListBecomesTheEmptyList() {
            UserResponse response = build(Map.of(), null, null, null, false, false, null);

            assertThat(response.fieldErrors()).isNotNull().isEmpty();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        /** Both collections are detached from the caller. */
        @Test
        @DisplayName("detaches both collections from the caller")
        void bothCollectionsAreDetachedFromTheCaller() {
            List<UserResponse.UserRow> mutableRows = new ArrayList<>();
            mutableRows.add(row(" ", USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE));
            List<ErrorResponse.FieldError> mutableErrors = new ArrayList<>();
            mutableErrors.add(MISSING_FIRST_NAME);

            UserResponse response =
                    build(Map.of(), mutableRows, null, mutableErrors, false, false, null);
            mutableRows.add(row("U", "USER0002", "Bob", "Brown", "U"));
            mutableErrors.add(INVALID_USER_TYPE);

            assertThat(response.rows()).hasSize(1);
            assertThat(response.fieldErrors()).containsExactly(MISSING_FIRST_NAME);
        }

        /** Both stored collections reject modification. */
        @Test
        @DisplayName("rejects modification of both stored collections")
        void bothStoredCollectionsRejectModification() {
            UserResponse response =
                    build(
                            Map.of(),
                            List.of(row(" ", USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE)),
                            null,
                            List.of(MISSING_FIRST_NAME),
                            false,
                            false,
                            null);

            List<UserResponse.UserRow> rows = response.rows();
            List<ErrorResponse.FieldError> errors = response.fieldErrors();

            assertThatThrownBy(() -> rows.add(row("U", "USER0002", "Bob", "Brown", "U")))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> errors.add(INVALID_USER_TYPE))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        /** A null element is rejected in either collection. */
        @Test
        @DisplayName("rejects a null element in either collection")
        void aNullElementIsRejectedInEitherCollection() {
            List<UserResponse.UserRow> rowsWithNull = new ArrayList<>();
            rowsWithNull.add(null);
            List<ErrorResponse.FieldError> errorsWithNull = new ArrayList<>();
            errorsWithNull.add(null);

            assertThatThrownBy(
                            () -> build(Map.of(), rowsWithNull, null, null, false, false, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(
                            () -> build(Map.of(), null, null, errorsWithNull, false, false, null))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * Row order and length are preserved exactly: nothing is sorted, reversed, renumbered,
         * compacted, de-duplicated or padded.
         */
        @Test
        @DisplayName("preserves row order and length exactly")
        void rowOrderAndLengthArePreservedExactly() {
            List<UserResponse.UserRow> supplied = tenAscendingRows();
            List<UserResponse.UserRow> reversed = new ArrayList<>(supplied);
            Collections.reverse(reversed);

            assertThat(build(Map.of(), supplied, null, null, false, false, null).rows())
                    .containsExactlyElementsOf(supplied);
            assertThat(build(Map.of(), reversed, null, null, false, false, null).rows())
                    .as("a page filled from the bottom upward keeps the sequence it was given")
                    .containsExactlyElementsOf(reversed);
        }

        /** A partial final page keeps only the rows it has and is never padded. */
        @Test
        @DisplayName("keeps a partial final page short")
        void aPartialFinalPageKeepsOnlyTheRowsItHas() {
            List<UserResponse.UserRow> three = tenAscendingRows().subList(0, 3);

            assertThat(build(Map.of(), three, null, null, false, false, null).rows()).hasSize(3);
        }

        /**
         * An over-deep page is refused rather than truncated or carried.
         *
         * <p>Three responses were available and only one of them states the invariant where it can
         * still be acted on. Truncating would discard returned data silently, so the collection crosses
         * intact. Reporting an over-deep page needs the screen's row count, and that figure is stated
         * once by the paging contract rather than twice here, so the report belongs to the layer that
         * already holds the dimension - which is also the layer that assembled the page.</p>
         */
        @Test
        @DisplayName("carries an over-deep page untouched, leaving the screen's depth to the contract "
                + "that measures it")
        void anOverDeepPageIsCarriedUntouched() {
            List<UserResponse.UserRow> twelve = new ArrayList<>(tenAscendingRows());
            twelve.add(row(" ", "USER0011", "First11", "Last11", "U"));
            twelve.add(row(" ", "USER0012", "First12", "Last12", "A"));

            assertThat(build(Map.of(), twelve, null, null, false, false, null).rows())
                    .as("truncating would discard returned data silently, so the collection crosses "
                            + "intact and whoever knows the screen depth reports it")
                    .hasSize(12);

            assertThat(build(Map.of(), tenAscendingRows(), null, null, false, false, null).rows())
                    .as("a page at exactly the screen's depth crosses unchanged too")
                    .hasSize(PageMetadata.USER_LIST_PAGE_SIZE);
        }

        /** Nothing else is normalised; every other component crosses byte for byte. */
        @Test
        @DisplayName("normalises nothing but the two collections")
        void nothingElseIsNormalised() {
            String padded = "  spaced  ";
            UserResponse response =
                    build(
                            Map.of("firstName", padded, "message", padded, "userType", "?"),
                            null,
                            null,
                            null,
                            false,
                            false,
                            null);

            assertThat(response.firstName()).isEqualTo(padded);
            assertThat(response.message()).isEqualTo(padded);
            assertThat(response.userType()).isEqualTo("?");
        }

        /** The row presence test is not an error indicator. */
        @Test
        @DisplayName("keeps the row presence test independent of failure")
        void theRowPresenceTestIsNotAnErrorIndicator() {
            UserResponse tenRowsWithMessage =
                    build(
                            Map.of("message", UserResponse.MSG_LIST_REACHED_BOTTOM),
                            tenAscendingRows(),
                            forwardPage(),
                            null,
                            false,
                            false,
                            null);

            assertThat(tenRowsWithMessage.hasRows()).isTrue();
            assertThat(tenRowsWithMessage.generalError()).isFalse();
            assertThat(build(Map.of(), List.of(), null, null, true, false, null).hasRows())
                    .as("an empty list is not a failure and a failure need not empty the list")
                    .isFalse();
        }
    }

    /** The four legitimate response shapes the four transactions produce. */
    @Nested
    @DisplayName("Response shapes")
    class ResponseShapes {

        /**
         * The invalid-selection arm carries a message together with a fully populated ten-row page
         * and the error flag clear. That combination is real behaviour, not a defect.
         */
        @Test
        @DisplayName("carries the invalid-selection message with a full page and no error flag")
        void theInvalidSelectionArmCarriesAFullPageWithNoErrorFlag() {
            UserResponse response =
                    build(
                            Map.of(
                                    "message",
                                    UserResponse.MSG_LIST_INVALID_SELECTION,
                                    "focusScreenFieldId",
                                    FIELD_TO_FOCUS),
                            tenAscendingRows(),
                            forwardPage(),
                            null,
                            false,
                            false,
                            null);

            assertThat(response.message()).isEqualTo(UserResponse.MSG_LIST_INVALID_SELECTION);
            assertThat(response.rows()).hasSize(10);
            assertThat(response.hasRows()).isTrue();
            assertThat(response.generalError()).isFalse();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        /** The no-change arm re-sends the screen with a message and the error flag clear. */
        @Test
        @DisplayName("carries the no-change message with no error flag")
        void theNoChangeArmCarriesNoErrorFlag() {
            UserResponse response =
                    build(
                            Map.of(
                                    "message", UserResponse.MSG_UPDATE_NO_CHANGE,
                                    "userId", USER_ID,
                                    "firstName", FIRST_NAME,
                                    "lastName", LAST_NAME),
                            null,
                            null,
                            null,
                            false,
                            false,
                            null);

            assertThat(response.message()).isEqualTo(UserResponse.MSG_UPDATE_NO_CHANGE);
            assertThat(response.generalError()).isFalse();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.userId()).isEqualTo(USER_ID);
        }

        /**
         * A successful add or delete clears the echoed values before reporting, so a response
         * legitimately reports success alongside blank or absent echoed values.
         */
        @Test
        @DisplayName("reports a successful add or delete with the echoed values cleared")
        void aSuccessfulAddOrDeleteClearsTheEchoedValues() {
            UserResponse added =
                    build(
                            Map.of("message", ASSEMBLED_ADD_SUCCESS),
                            null,
                            null,
                            null,
                            false,
                            true,
                            null);

            assertThat(added.actionSucceeded()).isTrue();
            assertThat(added.generalError()).isFalse();
            assertThat(added.userId()).isNull();
            assertThat(added.firstName()).isNull();
            assertThat(added.lastName()).isNull();
            assertThat(validator.validate(added)).isEmpty();
        }

        /**
         * A successful update keeps the echoed values populated, and the two behaviours are
         * deliberately not unified.
         */
        @Test
        @DisplayName("reports a successful update with the echoed values populated")
        void aSuccessfulUpdateKeepsTheEchoedValuesPopulated() {
            UserResponse updated =
                    build(
                            Map.of(
                                    "message", ASSEMBLED_UPDATE_SUCCESS,
                                    "userId", USER_ID,
                                    "firstName", FIRST_NAME,
                                    "lastName", LAST_NAME,
                                    "userType", USER_TYPE),
                            null,
                            null,
                            null,
                            false,
                            true,
                            null);

            assertThat(updated.actionSucceeded()).isTrue();
            assertThat(updated.userId()).isEqualTo(USER_ID);
            assertThat(updated.firstName()).isEqualTo(FIRST_NAME);
            assertThat(validator.validate(updated)).isEmpty();
        }

        /** The three single-user transactions carry no row list and no paging record. */
        @Test
        @DisplayName("carries no row list or paging record for a single-user transaction")
        void aSingleUserTransactionCarriesNoRowListOrPagingRecord() {
            UserResponse response = carrying("userId", USER_ID);

            assertThat(response.rows()).isEmpty();
            assertThat(response.pageMetadata()).isNull();
            assertThat(response.hasRows()).isFalse();
        }

        /**
         * The two indicators and the message are mutually independent, so every combination is a
         * legitimate shape.
         */
        @Test
        @DisplayName("keeps both indicators and the message mutually independent")
        void bothIndicatorsAndTheMessageAreMutuallyIndependent() {
            assertThat(build(Map.of(), null, null, null, true, false, null).generalError()).isTrue();
            assertThat(build(Map.of(), null, null, null, true, false, null).actionSucceeded())
                    .isFalse();
            assertThat(build(Map.of(), null, null, null, false, true, null).actionSucceeded())
                    .isTrue();
            assertThat(build(Map.of(), null, null, null, false, true, null).generalError())
                    .isFalse();
            assertThat(build(Map.of(), null, null, null, true, true, null).generalError()).isTrue();
            assertThat(build(Map.of(), null, null, null, true, true, null).actionSucceeded())
                    .isTrue();
            assertThat(carrying("message", UserResponse.MSG_LIST_AT_TOP).generalError()).isFalse();
            assertThat(carrying("message", UserResponse.MSG_LIST_AT_TOP).actionSucceeded())
                    .isFalse();
        }

        /**
         * A failure reported only as a summary line carries no field error, so the field-error
         * presence test is not a substitute for the general-error flag.
         */
        @Test
        @DisplayName("reports a summary-only failure with no field error")
        void aSummaryOnlyFailureCarriesNoFieldError() {
            UserResponse response =
                    build(
                            Map.of("message", UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND),
                            null,
                            null,
                            null,
                            true,
                            false,
                            null);

            assertThat(response.generalError()).isTrue();
            assertThat(response.hasFieldErrors()).isFalse();
        }

        /** The paging record carries no total row count and no total page count. */
        @Test
        @DisplayName("carries no total row count or total page count")
        void noTotalCountIsCarried() {
            List<String> declared =
                    Arrays.stream(UserResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared)
                    .doesNotContain(
                            "totalRows", "totalRowCount", "totalPages", "totalPageCount", "count");
            assertThat(
                            Arrays.stream(PageMetadata.class.getRecordComponents())
                                    .map(RecordComponent::getName)
                                    .toList())
                    .as("the legacy browse never counts the file")
                    .doesNotContain("totalRows", "totalPages");
        }
    }

    /** The JSON form each component takes under the module's declared settings. */
    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        /** Every populated component appears under the name the record declares. */
        @Test
        @DisplayName("emits every populated component under its declared name")
        void everyPopulatedComponentAppearsUnderItsDeclaredName() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(
                                    payloadOf(
                                            populated(
                                                    JsonContractSupport.populatedNavigation())));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        /** Both collections are always present and are emitted as empty arrays when empty. */
        @Test
        @DisplayName("always emits both collections, as empty arrays when empty")
        void bothCollectionsAreAlwaysEmitted() throws JsonProcessingException {
            String payload = payloadOf(empty());

            assertThat(payload).contains("\"rows\":[]").contains("\"fieldErrors\":[]");
        }

        /** An empty response carries exactly the four components that cannot be absent. */
        @Test
        @DisplayName("emits only the collections and indicators when nothing is populated")
        void anEmptyResponseCarriesOnlyTheCollectionsAndIndicators()
                throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper().readTree(payloadOf(empty()));

            assertThat(tree.fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder(
                            "rows", "fieldErrors", "generalError", "actionSucceeded");
        }

        /** An absent component is omitted rather than emitted as a null. */
        @Test
        @DisplayName("omits an absent component")
        void anAbsentComponentIsOmitted() throws JsonProcessingException {
            String payload = payloadOf(carrying("userId", USER_ID));

            assertThat(payload).contains("\"userId\":\"" + USER_ID + "\"");
            for (String component : EXPECTED_COMPONENTS) {
                if (!"userId".equals(component)
                        && !"rows".equals(component)
                        && !"fieldErrors".equals(component)
                        && !"generalError".equals(component)
                        && !"actionSucceeded".equals(component)) {
                    assertThat(payload)
                            .as("absent component %s must be omitted", component)
                            .doesNotContain("\"" + component + "\"");
                }
            }
        }

        /** Each row is emitted with its five declared members. */
        @Test
        @DisplayName("emits each row with its five declared members")
        void eachRowIsEmittedWithItsFiveMembers() throws JsonProcessingException {
            JsonNode tree =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payloadOf(populated(null)));
            JsonNode rows = tree.get("rows");

            assertThat(rows.isArray()).isTrue();
            assertThat(rows).hasSize(10);
            assertThat(rows.get(0).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_ROW_COMPONENTS);
        }

        /** Row order survives serialisation unchanged. */
        @Test
        @DisplayName("preserves row order on the wire")
        void rowOrderSurvivesSerialisation() throws JsonProcessingException {
            JsonNode rows =
                    JsonContractSupport.declaredSettingsMapper()
                            .readTree(payloadOf(populated(null)))
                            .get("rows");

            assertThat(rows.get(0).get("userId").asText()).isEqualTo("USER0001");
            assertThat(rows.get(9).get("userId").asText()).isEqualTo("USER0010");
        }

        /** The identifier is emitted as text and keeps every character. */
        @Test
        @DisplayName("emits the identifier as text")
        void theIdentifierIsEmittedAsText() throws JsonProcessingException {
            assertThat(payloadOf(carrying("userId", "00000001")))
                    .contains("\"userId\":\"00000001\"")
                    .doesNotContain("\"userId\":1");
        }

        /** The raw user type is emitted as text, whatever character it holds. */
        @ParameterizedTest(name = "type {0} is emitted as text")
        @ValueSource(strings = {"A", "U", "a", "u", "?", "0", " "})
        @DisplayName("emits an unexpected user type rather than failing")
        void anUnexpectedUserTypeIsEmittedAsText(String type) throws JsonProcessingException {
            assertThat(payloadOf(carrying("userType", type)))
                    .contains("\"userType\":\"" + type + "\"");
        }

        /** The page indicator keeps its leading zeros on the wire. */
        @Test
        @DisplayName("emits the page indicator with its leading zeros intact")
        void thePageIndicatorKeepsItsLeadingZeros() throws JsonProcessingException {
            assertThat(payloadOf(populated(null)))
                    .contains("\"displayedPageNumber\":\"" + DISPLAYED_PAGE_NUMBER + "\"");
        }

        /** Neither presence test is emitted, because both are methods and not components. */
        @Test
        @DisplayName("emits neither presence test")
        void neitherPresenceTestIsEmitted() throws JsonProcessingException {
            assertThat(payloadOf(populated(null)))
                    .doesNotContain("\"hasRows\"")
                    .doesNotContain("\"hasFieldErrors\"");
        }

        /** A space-significant value survives serialisation untouched. */
        @Test
        @DisplayName("preserves a space-significant value")
        void aSpaceSignificantValueSurvives() throws JsonProcessingException {
            assertThat(payloadOf(carrying("firstName", "Ada  ")))
                    .contains("\"firstName\":\"Ada  \"");
        }

        /** An unknown property is tolerated on read under the module's declared settings. */
        @Test
        @DisplayName("tolerates an unknown property on read")
        void anUnknownPropertyIsToleratedOnRead() {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload = "{\"userId\":\"ADMIN001\",\"generalError\":false,\"x\":1}";

            assertThatNoException().isThrownBy(() -> mapper.readValue(payload, UserResponse.class));
        }

        /** A round trip preserves every component. */
        @Test
        @DisplayName("preserves every component across a round trip")
        void aRoundTripPreservesEveryComponent() throws JsonProcessingException {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            UserResponse original = populated(JsonContractSupport.populatedNavigation());

            UserResponse restored =
                    mapper.readValue(mapper.writeValueAsString(original), UserResponse.class);

            assertThat(restored).isEqualTo(original);
        }
    }

    /** Bean Validation behaviour at, inside and outside each declared bound. */
    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        /** A fully populated response reports no violation. */
        @Test
        @DisplayName("reports no violation on a populated response")
        void aPopulatedResponseReportsNoViolation() {
            assertThat(validator.validate(populated(JsonContractSupport.populatedNavigation())))
                    .isEmpty();
        }

        /** An empty response reports no violation, because nothing is required. */
        @Test
        @DisplayName("reports no violation on an empty response")
        void anEmptyResponseReportsNoViolation() {
            assertThat(validator.validate(empty())).isEmpty();
        }

        /** A value one character past a bound is reported against that component. */
        @ParameterizedTest(name = "{0} over {1} is reported")
        @CsvSource({
            "userId,8",
            "firstName,20",
            "lastName,20",
            "userType,1",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "message,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("reports an over-long value")
        void anOverLongValueIsReported(String component, int width) {
            Set<ConstraintViolation<UserResponse>> violations =
                    validator.validate(carrying(component, "A".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        /** A value exactly at a bound is accepted. */
        @ParameterizedTest(name = "{0} at {1} is accepted")
        @CsvSource({
            "userId,8",
            "firstName,20",
            "lastName,20",
            "userType,1",
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "message,78",
            "focusScreenFieldId,7"
        })
        @DisplayName("accepts a value at a bound")
        void anAtWidthValueIsAccepted(String component, int width) {
            assertThat(validator.validate(carrying(component, "A".repeat(width)))).isEmpty();
        }

        /**
         * No component is mandatory, because every one is legitimately absent on some path through
         * the four transactions.
         */
        @Test
        @DisplayName("marks no component mandatory")
        void noComponentIsMandatory() {
            for (String component : BOUNDED_COMPONENTS) {
                assertThat(validator.validate(carrying(component, null)))
                        .as("component %s must tolerate absence", component)
                        .isEmpty();
                assertThat(validator.validate(carrying(component, "")))
                        .as("component %s must tolerate a blank", component)
                        .isEmpty();
            }
        }

        /** The route is not measured, because it is a service-owned identifier. */
        @Test
        @DisplayName("does not measure the route")
        void theRouteIsNotMeasured() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(4096)))).isEmpty();
        }

        /** Every published message text passes the message bound. */
        @ParameterizedTest(name = "[{index}] passes the message bound")
        @MethodSource("com.carddemo.api.dto.UserResponseCoverageTest#publishedMessages")
        @DisplayName("accepts every published message text")
        void everyPublishedTextPassesTheMessageBound(String actual, String expected, int length) {
            assertThat(validator.validate(carrying("message", actual))).isEmpty();
        }

        /** Each assembled success sentence passes the message bound. */
        @Test
        @DisplayName("accepts each assembled success sentence")
        void eachAssembledSuccessSentencePassesTheMessageBound() {
            assertThat(validator.validate(carrying("message", ASSEMBLED_ADD_SUCCESS))).isEmpty();
            assertThat(validator.validate(carrying("message", ASSEMBLED_UPDATE_SUCCESS))).isEmpty();
            assertThat(validator.validate(carrying("message", ASSEMBLED_DELETE_SUCCESS))).isEmpty();
        }

        /** A space-padded or fully blank value survives validation exactly as supplied. */
        @Test
        @DisplayName("accepts space-padded and fully blank values")
        void aSpacePaddedOrBlankValueIsAccepted() {
            assertThat(validator.validate(carrying("firstName", "        "))).isEmpty();
            assertThat(validator.validate(carrying("userType", " "))).isEmpty();
            assertThat(carrying("firstName", "        ").firstName()).hasSize(8);
        }

        /** The field-error collection is not cascaded from the enclosing response. */
        @Test
        @DisplayName("does not cascade validation into the field-error collection")
        void theFieldErrorCollectionIsNotCascaded() throws NoSuchFieldException {
            assertThat(UserResponse.class.getDeclaredField("fieldErrors").getAnnotations())
                    .noneMatch(
                            annotation ->
                                    "jakarta.validation.Valid"
                                            .equals(annotation.annotationType().getName()));
        }
    }

    /** What the generated rendering reveals, and what the nested records withhold. */
    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        /**
         * The six components this type withholds itself, each named and replaced by the fixed marker.
         *
         * <p>The four top-level person values are withheld because a single list response carries the
         * same four again in each of up to ten rows, so one log statement over one response would
         * otherwise disclose eleven identities. The row collection is withheld whole rather than per
         * row, and the paging record with it, because both of its cursor keys are user identifiers by
         * another name and would reintroduce exactly the identities the row withholding removes.</p>
         */
        private static final List<String> WITHHELD_BY_THIS_TYPE = List.of(
                "rows", "pageMetadata", "userId", "firstName", "lastName", "userType");

        /**
         * Each of the six withheld components is named and replaced by the marker, so a reader can
         * see that a value existed without being able to read it. The row count is retained in the
         * collection's place, which is the property a reader diagnosing a paging or cardinality
         * problem actually needs.
         */
        @Test
        @DisplayName("names each of the six withheld components and replaces its value")
        void eachWithheldComponentIsNamedAndReplaced() {
            String rendered =
                    build(
                                    Map.of(
                                            "userId", USER_ID,
                                            "firstName", FIRST_NAME,
                                            "lastName", LAST_NAME,
                                            "userType", USER_TYPE,
                                            "message", UserResponse.MSG_LIST_AT_TOP,
                                            "nextRoute", ROUTE),
                                    tenAscendingRows(),
                                    forwardPage(),
                                    List.of(MISSING_FIRST_NAME),
                                    false,
                                    false,
                                    null)
                            .toString();

            for (String component : WITHHELD_BY_THIS_TYPE) {
                assertThat(rendered)
                        .as("component %s must be named and withheld", component)
                        .contains(component + "=***REDACTED***");
            }
            assertThat(rendered)
                    .as("the count stands in the collection's place")
                    .contains("rowCount=10");
            assertThat(rendered)
                    .doesNotContain(USER_ID)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME)
                    .doesNotContain(PREVIOUS_CURSOR_KEY)
                    .doesNotContain(NEXT_CURSOR_KEY);
        }

        /** No component outside the six is withheld, so the rendering stays diagnosable. */
        @Test
        @DisplayName("withholds nothing outside the six")
        void nothingOutsideTheSixIsWithheld() {
            String rendered = populated(null).toString();

            for (String component : EXPECTED_COMPONENTS) {
                if (WITHHELD_BY_THIS_TYPE.contains(component)) {
                    continue;
                }
                assertThat(rendered)
                        .as("component %s is not withheld by this type", component)
                        .doesNotContain(component + "=***REDACTED***");
            }
        }

        /** The rendering names the record and shows every value that identifies nobody. */
        @Test
        @DisplayName("names the record and shows the values that identify nobody")
        void theRenderingNamesTheRecordAndShowsTheNonIdentifyingValues() {
            String rendered = populated(null).toString();

            assertThat(rendered)
                    .startsWith("UserResponse[")
                    .endsWith("]")
                    .contains("transactionName=" + TRANSACTION_NAME)
                    .contains("title01=" + TITLE_01)
                    .contains("title02=" + TITLE_02)
                    .contains("currentDate=" + CURRENT_DATE)
                    .contains("currentTime=" + CURRENT_TIME)
                    .contains("programName=" + PROGRAM_NAME)
                    .contains("message=" + UserResponse.MSG_LIST_REACHED_BOTTOM)
                    .contains("generalError=true")
                    .contains("actionSucceeded=false")
                    .contains("focusScreenFieldId=" + FIELD_TO_FOCUS)
                    .contains("nextRoute=" + ROUTE);
        }

        /**
         * The nested paging record withholds both cursor keys of its own accord, so neither
         * distinctive key reaches this rendering.
         */
        @Test
        @DisplayName("withholds the paging record whole rather than relying on it to withhold its "
                + "own cursor keys")
        void theNestedPagingRecordIsWithheldWhole() {
            String rendered = populated(null).toString();

            assertThat(rendered)
                    .doesNotContain(PREVIOUS_CURSOR_KEY)
                    .doesNotContain(NEXT_CURSOR_KEY);
            assertThat(rendered)
                    .as("this type does not delegate to the paging record here: on a user list both "
                            + "of that record's cursor keys are themselves user identifiers, so "
                            + "delegating would leave two identities readable in exchange for a page "
                            + "number that is recoverable from the retained row count and the "
                            + "request that produced the page")
                    .contains("pageMetadata=***REDACTED***")
                    .doesNotContain("previousCursorKey=")
                    .doesNotContain("nextCursorKey=")
                    .doesNotContain("displayedPageNumber=");
            assertThat(populated(null).pageMetadata().toString())
                    .as("reached directly the paging record still withholds its own keys, so a "
                            + "rendering that goes through it rather than through this response "
                            + "discloses nothing either")
                    .contains("previousCursorKey=***REDACTED***")
                    .contains("nextCursorKey=***REDACTED***")
                    .contains("displayedPageNumber=" + DISPLAYED_PAGE_NUMBER);
        }

        /**
         * A nested navigation state withholds its own identifying components, so none of its
         * distinctive values reaches this rendering either.
         */
        @Test
        @DisplayName("relies on the navigation state to withhold its identifying components")
        void aNestedNavigationStateWithholdsItsIdentifyingComponents() {
            String rendered = populated(JsonContractSupport.populatedNavigation()).toString();

            assertThat(rendered).contains("navigationContext=NavigationContext[");
            assertThat(rendered)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER);
        }

        /** No credential value can appear in the rendering, because none is carried. */
        @Test
        @DisplayName("renders no credential, because none is carried")
        void noCredentialAppearsInTheRendering() {
            String rendered = populated(JsonContractSupport.populatedNavigation()).toString();

            assertThat(rendered.toLowerCase(Locale.ROOT))
                    .doesNotContain("password=")
                    .doesNotContain("credential=")
                    .doesNotContain("pwd=");
        }

        /**
         * No raw response code, reason code, storage identifier or failure class name reaches this
         * contract; only the operator-facing message text does.
         */
        @Test
        @DisplayName("carries no raw diagnostic detail")
        void noRawDiagnosticDetailIsCarried() {
            List<String> declared =
                    Arrays.stream(UserResponse.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .toList();

            assertThat(declared)
                    .noneMatch(
                            name ->
                                    name.contains("responsecode")
                                            || name.contains("reasoncode")
                                            || name.contains("resp")
                                            || name.contains("stack")
                                            || name.contains("exception")
                                            || name.contains("cause"));
        }
    }

    /** Equality, hashing, absence tolerance and accessor fidelity. */
    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        /** Two responses carrying equal components are equal. */
        @Test
        @DisplayName("treats equal components as equal values")
        void twoResponsesWithEqualComponentsAreEqual() {
            assertThat(populated(null)).isEqualTo(populated(null));
            assertThat(populated(JsonContractSupport.populatedNavigation()))
                    .isEqualTo(populated(JsonContractSupport.populatedNavigation()));
        }

        /** Hashing is stable across equal instances. */
        @Test
        @DisplayName("hashes equal values alike")
        void hashCodeIsStableAcrossEqualInstances() {
            assertThat(populated(null)).hasSameHashCodeAs(populated(null));
        }

        /** A difference in any component is observed. */
        @Test
        @DisplayName("observes a difference in any component")
        void aDifferenceInAnyComponentIsObserved() {
            assertThat(carrying("userId", USER_ID)).isNotEqualTo(carrying("userId", "ADMIN002"));
            assertThat(populated(null))
                    .isNotEqualTo(populated(JsonContractSupport.populatedNavigation()));
            assertThat(build(Map.of(), null, null, null, true, false, null))
                    .isNotEqualTo(build(Map.of(), null, null, null, false, true, null));
        }

        /** An absent collection and an explicitly empty one produce equal responses. */
        @Test
        @DisplayName("equates an absent collection with an explicitly empty one")
        void anAbsentCollectionEqualsAnExplicitlyEmptyOne() {
            assertThat(build(Map.of(), null, null, null, false, false, null))
                    .isEqualTo(
                            build(
                                    Map.of(),
                                    Collections.emptyList(),
                                    null,
                                    Collections.emptyList(),
                                    false,
                                    false,
                                    null));
        }

        /** Rows are value objects, so two equal rows compare equal. */
        @Test
        @DisplayName("treats two equal rows as equal values")
        void twoEqualRowsAreEqual() {
            assertThat(row("U", USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE))
                    .isEqualTo(row("U", USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE))
                    .hasSameHashCodeAs(row("U", USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE));
            assertThat(row("U", USER_ID, FIRST_NAME, LAST_NAME, "A"))
                    .isNotEqualTo(row("U", USER_ID, FIRST_NAME, LAST_NAME, "U"));
        }

        /** Every component tolerates an absent value. */
        @Test
        @DisplayName("tolerates an absent value in every component")
        void everyComponentToleratesAnAbsentValue() {
            UserResponse response = empty();

            assertThat(response.userId()).isNull();
            assertThat(response.firstName()).isNull();
            assertThat(response.lastName()).isNull();
            assertThat(response.userType()).isNull();
            assertThat(response.transactionName()).isNull();
            assertThat(response.message()).isNull();
            assertThat(response.focusScreenFieldId()).isNull();
            assertThat(response.nextRoute()).isNull();
            assertThat(response.navigationContext()).isNull();
            assertThat(response.pageMetadata()).isNull();
            assertThat(response.rows()).isEmpty();
            assertThat(response.fieldErrors()).isEmpty();
            assertThat(response.generalError()).isFalse();
            assertThat(response.actionSucceeded()).isFalse();
        }

        /** Every accessor returns exactly what was supplied. */
        @Test
        @DisplayName("returns from every accessor exactly what was supplied")
        void everyAccessorReturnsWhatWasSupplied() {
            UserResponse response = populated(JsonContractSupport.populatedNavigation());

            assertThat(response.rows()).hasSize(10);
            assertThat(response.pageMetadata()).isEqualTo(forwardPage());
            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.firstName()).isEqualTo(FIRST_NAME);
            assertThat(response.lastName()).isEqualTo(LAST_NAME);
            assertThat(response.userType()).isEqualTo(USER_TYPE);
            assertThat(response.transactionName()).isEqualTo(TRANSACTION_NAME);
            assertThat(response.title01()).isEqualTo(TITLE_01);
            assertThat(response.currentDate()).isEqualTo(CURRENT_DATE);
            assertThat(response.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(response.title02()).isEqualTo(TITLE_02);
            assertThat(response.currentTime()).isEqualTo(CURRENT_TIME);
            assertThat(response.message()).isEqualTo(UserResponse.MSG_LIST_REACHED_BOTTOM);
            assertThat(response.fieldErrors())
                    .containsExactly(MISSING_FIRST_NAME, INVALID_USER_TYPE);
            assertThat(response.generalError()).isTrue();
            assertThat(response.actionSucceeded()).isFalse();
            assertThat(response.focusScreenFieldId()).isEqualTo(FIELD_TO_FOCUS);
            assertThat(response.nextRoute()).isEqualTo(ROUTE);
            assertThat(response.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        /** A space-significant value survives construction untouched. */
        @Test
        @DisplayName("preserves a space-significant value")
        void aSpaceSignificantValueSurvives() {
            String padded = "  padded  ";

            assertThat(carrying("lastName", padded).lastName()).isEqualTo(padded);
            assertThat(carrying("userType", " ").userType()).isEqualTo(" ");
        }
    }
}
