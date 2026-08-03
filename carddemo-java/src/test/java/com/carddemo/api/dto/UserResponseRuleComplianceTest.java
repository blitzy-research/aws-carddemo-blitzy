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
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link UserResponse}, the response body shared by the four legacy administrative
 * transactions {@code CU00} through {@code CU03}, implemented by {@code app/cbl/COUSR00C.cbl},
 * {@code COUSR01C.cbl}, {@code COUSR02C.cbl} and {@code COUSR03C.cbl}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Ten rows, fixed by an OCCURS clause.</strong> {@code app/cbl/COUSR00C.cbl:57} declares
 * {@code 02 USER-REC OCCURS 10 TIMES}, so the list screen's page size is ten and is declared in the
 * table rather than inferred from a loop bound as the transaction list's is.
 *
 * <p><strong>Thirty-five message constants carry only twenty-three distinct texts.</strong> The four
 * legacy programs each declare their own working-storage message literals, and many of those literals
 * are textually identical across the add, update and delete flows. The aliasing is a property of the
 * legacy source and is therefore asserted deliberately, constant by constant, rather than being
 * treated as duplication to be collapsed: collapsing it would silently couple three screens that the
 * estate keeps separate, and a blanket no-duplicates assertion would forbid a faithful translation.
 *
 * <p><strong>The response carries no credential.</strong> {@link UserRequest} has to carry the password
 * an operator typed, but nothing is ever sent back, so the absence of a credential component here is a
 * contract worth asserting reflectively rather than merely observing.
 */
@DisplayName("UserResponse - the CU00 through CU03 administrative screen contract")
class UserResponseRuleComplianceTest {

    /** A representative eight-character user identifier drawn from the seeded fixture range. */
    private static final String USER_ID = "ADMIN001";

    /** The placeholder the response and its nested row substitute for a withheld component. */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /**
     * Builds a mapper configured exactly as {@code application.yml} configures the module's mapper.
     *
     * @return a mapper carrying the module's four Jackson settings
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
    private static JsonNode payloadOf(final UserResponse response) throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Builds a fully populated response, laid out in rows of five so a component cannot silently drift
     * one position out of line past a compiler that sees a wall of interchangeable strings.
     *
     * @param rows the rows the list screen displays
     * @param pageMetadata the paging cursor state
     * @param message the operator message
     * @param fieldErrors the per-field error detail
     * @param navigationContext the carried conversation state
     * @return a response carrying the supplied values and representative screen furniture
     */
    private static UserResponse aResponse(final List<UserResponse.UserRow> rows,
            final PageMetadata pageMetadata, final String message,
            final List<ErrorResponse.FieldError> fieldErrors,
            final NavigationContext navigationContext) {
        return new UserResponse(
                rows, pageMetadata, USER_ID, "FIRSTNAME", "LASTNAME",
                "A", "CU01", "List Users", "08/02/26", "COUSR01C",
                "CardDemo", "14:30:00", message, fieldErrors, false,
                true, "USRIDIN", "/api/admin/users", navigationContext);
    }

    /**
     * Builds a response carrying only the two list components.
     *
     * @param rows the rows the list screen displays
     * @param fieldErrors the per-field error detail
     * @return a response carrying only those two components
     */
    private static UserResponse aSparseResponse(final List<UserResponse.UserRow> rows,
            final List<ErrorResponse.FieldError> fieldErrors) {
        return new UserResponse(
                rows, null, null, null, null,
                null, null, null, null, null,
                null, null, null, fieldErrors, false,
                false, null, null, null);
    }

    /**
     * Builds a representative displayed row.
     *
     * @param userId the identity the row displays
     * @return a row carrying that identity and representative name detail
     */
    private static UserResponse.UserRow aRow(final String userId) {
        return new UserResponse.UserRow(" ", userId, "FIRSTNAME", "LASTNAME", "U");
    }

    /**
     * Reports whether a named component's accessor on a given record carries a declared upper bound.
     *
     * <p>The annotation is read from the accessor rather than from the record component, because
     * {@code jakarta.validation.constraints.Size} does not target {@code RECORD_COMPONENT}. A
     * constraint written on a record component is propagated to the backing field, the accessor and
     * the canonical constructor parameter instead of being retained on the component itself.
     *
     * @param owner the record declaring the component
     * @param componentName the record component to test
     * @return {@code true} when the accessor declares a {@link Size} bound
     */
    private static boolean declaresAnUpperBound(final Class<?> owner, final String componentName) {
        try {
            return owner.getDeclaredMethod(componentName).getAnnotation(Size.class) != null;
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("no accessor declared for " + componentName, cause);
        }
    }

    /**
     * Reads the declared upper bound of a named component's accessor on a given record.
     *
     * @param owner the record declaring the component
     * @param componentName the record component whose accessor carries the annotation
     * @return the declared maximum length
     * @throws NoSuchMethodException when no such accessor is declared
     */
    private static int declaredMaximumLength(final Class<?> owner, final String componentName)
            throws NoSuchMethodException {
        final Size size = owner.getDeclaredMethod(componentName).getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    /**
     * Collects the values of every public string constant the record declares.
     *
     * @return the declared message texts, in declaration order
     * @throws IllegalAccessException when a declared constant cannot be read
     */
    private static List<String> declaredMessageTexts() throws IllegalAccessException {
        final List<String> texts = new ArrayList<>();
        for (final Field field : UserResponse.class.getDeclaredFields()) {
            if (field.getType() == String.class && Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())) {
                texts.add((String) field.get(null));
            }
        }
        return texts;
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published field widths")
    class ThePublishedFieldWidths {

        @Test
        @DisplayName("the user identifier is eight characters, matching SEC-USR-ID PIC X(08) in the "
                + "eighty-byte user-security record")
        void theUserIdentifierIsEightCharacters() {
            assertThat(UserResponse.USER_ID_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("the two name parts are declared separately at twenty characters each, matching "
                + "SEC-USR-FNAME and SEC-USR-LNAME which happen to share one width")
        void theTwoNamePartsAreDeclaredSeparatelyAtTwenty() {
            assertThat(UserResponse.FIRST_NAME_LENGTH).isEqualTo(20);
            assertThat(UserResponse.LAST_NAME_LENGTH).isEqualTo(20);
        }

        @Test
        @DisplayName("the user type and the row selector are each a single character")
        void theUserTypeAndSelectorAreSingleCharacters() {
            assertThat(UserResponse.USER_TYPE_LENGTH).isOne();
            assertThat(UserResponse.SELECTOR_LENGTH).isOne();
        }

        @Test
        @DisplayName("the screen furniture widths are the estate's shared ones, four for the "
                + "transaction name, forty for a title, eight for a date, a time and a program name")
        void theScreenFurnitureWidthsAreTheSharedOnes() {
            assertThat(UserResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(UserResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(UserResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(UserResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(UserResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("the operator message is seventy-eight characters and the focus field identifier "
                + "seven, the widths every screen in this estate shares")
        void theMessageAndFocusWidthsAreSeventyEightAndSeven() {
            assertThat(UserResponse.MESSAGE_LENGTH).isEqualTo(78);
            assertThat(UserResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the response widths agree with the request widths, because both sides of the "
                + "conversation describe the same eighty-byte record")
        void theResponseWidthsAgreeWithTheRequestWidths() {
            assertThat(UserResponse.USER_ID_LENGTH).isEqualTo(UserRequest.USER_ID_LENGTH);
            assertThat(UserResponse.FIRST_NAME_LENGTH).isEqualTo(UserRequest.NAME_PART_LENGTH);
            assertThat(UserResponse.LAST_NAME_LENGTH).isEqualTo(UserRequest.NAME_PART_LENGTH);
            assertThat(UserResponse.USER_TYPE_LENGTH).isEqualTo(UserRequest.USER_TYPE_LENGTH);
            assertThat(UserResponse.SELECTOR_LENGTH).isEqualTo(UserRequest.ROW_SELECTION_LENGTH);
        }

        @Test
        @DisplayName("the page size the list screen fills is ten, declared by OCCURS 10 TIMES at "
                + "app/cbl/COUSR00C.cbl:57 rather than inferred from a loop bound")
        void thePageSizeIsTen() {
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE).isEqualTo(10);
        }

        /**
         * Thirteen published integer constants, twelve widths and one row count.
         *
         * <p>The count is asserted over every public static {@code int} the record declares, not over
         * the ones a reader remembers, so a new constant of either kind moves it. Twelve of the
         * thirteen are field widths in characters; the thirteenth, the row count, is a cardinality and
         * is published for the same reason the widths are - the page the list screen fills is part of
         * the contract, and the legacy program establishes it by loop bound rather than by table, so
         * nothing else in the record records it.</p>
         */
        @Test
        @DisplayName("the record declares exactly thirteen published integer constants, twelve widths "
                + "and the row count, so a new one cannot be introduced without this count moving")
        void theRecordDeclaresExactlyThirteenPublishedIntegerConstants() {
            final List<String> publishedIntegers = Arrays.stream(
                    UserResponse.class.getDeclaredFields())
                    .filter(field -> field.getType() == int.class)
                    .filter(field -> Modifier.isPublic(field.getModifiers()))
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .map(Field::getName).toList();

            assertThat(publishedIntegers).hasSize(13);
            assertThat(publishedIntegers).containsExactly("SELECTOR_LENGTH", "ROW_COUNT",
                    "USER_ID_LENGTH", "FIRST_NAME_LENGTH", "LAST_NAME_LENGTH", "USER_TYPE_LENGTH",
                    "TRANSACTION_NAME_LENGTH", "SCREEN_TITLE_LENGTH", "CURRENT_DATE_LENGTH",
                    "PROGRAM_NAME_LENGTH", "CURRENT_TIME_LENGTH", "MESSAGE_LENGTH",
                    "SCREEN_FIELD_ID_LENGTH");
            assertThat(UserResponse.ROW_COUNT)
                    .as("the row count is the page the list screen fills")
                    .isEqualTo(PageMetadata.USER_LIST_PAGE_SIZE)
                    .isEqualTo(10);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the thirty-five operator messages")
    class TheThirtyFiveOperatorMessages {

        @Test
        @DisplayName("the list screen's invalid-selection message names both legal characters and ends "
                + "without the trailing ellipsis the rest of the estate uses")
        void theListInvalidSelectionMessageNamesBothLegalCharacters() {
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid values are U and D");
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION).doesNotEndWith(".");
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid value is S");
        }

        @Test
        @DisplayName("the five paging boundary messages are reproduced character for character, "
                + "distinguishing an attempted move from a completed one")
        void theFivePagingBoundaryMessagesAreReproduced() {
            assertThat(UserResponse.MSG_LIST_ALREADY_AT_TOP)
                    .isEqualTo("You are already at the top of the page...");
            assertThat(UserResponse.MSG_LIST_ALREADY_AT_BOTTOM)
                    .isEqualTo("You are already at the bottom of the page...");
            assertThat(UserResponse.MSG_LIST_AT_TOP)
                    .isEqualTo("You are at the top of the page...");
            assertThat(UserResponse.MSG_LIST_REACHED_BOTTOM)
                    .isEqualTo("You have reached the bottom of the page...");
            assertThat(UserResponse.MSG_LIST_REACHED_TOP)
                    .isEqualTo("You have reached the top of the page...");
        }

        @Test
        @DisplayName("the paging messages match the transaction list's word for word, because both "
                + "screens were written from the same working-storage literals")
        void thePagingMessagesMatchTheTransactionListsWordForWord() {
            assertThat(UserResponse.MSG_LIST_ALREADY_AT_TOP)
                    .isEqualTo(TransactionListResponse.MESSAGE_ALREADY_AT_TOP);
            assertThat(UserResponse.MSG_LIST_ALREADY_AT_BOTTOM)
                    .isEqualTo(TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM);
            assertThat(UserResponse.MSG_LIST_AT_TOP)
                    .isEqualTo(TransactionListResponse.MESSAGE_AT_TOP);
            assertThat(UserResponse.MSG_LIST_REACHED_BOTTOM)
                    .isEqualTo(TransactionListResponse.MESSAGE_REACHED_BOTTOM);
            assertThat(UserResponse.MSG_LIST_REACHED_TOP)
                    .isEqualTo(TransactionListResponse.MESSAGE_REACHED_TOP);
        }

        @Test
        @DisplayName("the five mandatory-field messages the add screen publishes are reproduced exactly")
        void theAddScreenMandatoryFieldMessagesAreReproduced() {
            assertThat(UserResponse.MSG_ADD_FIRST_NAME_EMPTY)
                    .isEqualTo("First Name can NOT be empty...");
            assertThat(UserResponse.MSG_ADD_LAST_NAME_EMPTY)
                    .isEqualTo("Last Name can NOT be empty...");
            assertThat(UserResponse.MSG_ADD_USER_ID_EMPTY)
                    .isEqualTo("User ID can NOT be empty...");
            assertThat(UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY)
                    .isEqualTo("Password can NOT be empty...");
            assertThat(UserResponse.MSG_ADD_USER_TYPE_EMPTY)
                    .isEqualTo("User Type can NOT be empty...");
        }

        @Test
        @DisplayName("the add screen's outcome messages are reproduced, the success pair assembling "
                + "around the identity and the two failures naming their own cause")
        void theAddScreenOutcomeMessagesAreReproduced() {
            assertThat(UserResponse.MSG_ADD_SUCCESS_PREFIX).isEqualTo("User ");
            assertThat(UserResponse.MSG_ADD_SUCCESS_SUFFIX).isEqualTo(" has been added ...");
            assertThat(UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST)
                    .isEqualTo("User ID already exist...");
            assertThat(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER).isEqualTo("Unable to Add User...");
            assertThat(UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID
                    + UserResponse.MSG_ADD_SUCCESS_SUFFIX)
                    .isEqualTo("User ADMIN001 has been added ...");
        }

        @Test
        @DisplayName("the update screen's twelve messages are reproduced, including the two whose "
                + "ellipsis is preceded by a space")
        void theUpdateScreenMessagesAreReproduced() {
            assertThat(UserResponse.MSG_UPDATE_USER_ID_EMPTY)
                    .isEqualTo("User ID can NOT be empty...");
            assertThat(UserResponse.MSG_UPDATE_FIRST_NAME_EMPTY)
                    .isEqualTo("First Name can NOT be empty...");
            assertThat(UserResponse.MSG_UPDATE_LAST_NAME_EMPTY)
                    .isEqualTo("Last Name can NOT be empty...");
            assertThat(UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY)
                    .isEqualTo("Password can NOT be empty...");
            assertThat(UserResponse.MSG_UPDATE_USER_TYPE_EMPTY)
                    .isEqualTo("User Type can NOT be empty...");
            assertThat(UserResponse.MSG_UPDATE_NO_CHANGE).isEqualTo("Please modify to update ...");
            assertThat(UserResponse.MSG_UPDATE_PRESS_PF5)
                    .isEqualTo("Press PF5 key to save your updates ...");
            assertThat(UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND).isEqualTo("User ID NOT found...");
            assertThat(UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo("Unable to lookup User...");
            assertThat(UserResponse.MSG_UPDATE_SUCCESS_PREFIX).isEqualTo("User ");
            assertThat(UserResponse.MSG_UPDATE_SUCCESS_SUFFIX).isEqualTo(" has been updated ...");
            assertThat(UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER)
                    .isEqualTo("Unable to Update User...");
        }

        @Test
        @DisplayName("the update screen's two prompts keep the space before their ellipsis, which the "
                + "mandatory-field messages do not, so the spacing is not incidental")
        void theUpdatePromptsKeepTheSpaceBeforeTheirEllipsis() {
            assertThat(UserResponse.MSG_UPDATE_NO_CHANGE).endsWith(" ...");
            assertThat(UserResponse.MSG_UPDATE_PRESS_PF5).endsWith(" ...");
            assertThat(UserResponse.MSG_DELETE_PRESS_PF5).endsWith(" ...");
            assertThat(UserResponse.MSG_UPDATE_USER_ID_EMPTY).endsWith("y...");
        }

        @Test
        @DisplayName("the delete screen's seven messages are reproduced, its confirmation prompt "
                + "naming the same function key as the update screen's")
        void theDeleteScreenMessagesAreReproduced() {
            assertThat(UserResponse.MSG_DELETE_USER_ID_EMPTY)
                    .isEqualTo("User ID can NOT be empty...");
            assertThat(UserResponse.MSG_DELETE_PRESS_PF5)
                    .isEqualTo("Press PF5 key to delete this user ...");
            assertThat(UserResponse.MSG_DELETE_USER_ID_NOT_FOUND).isEqualTo("User ID NOT found...");
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo("Unable to lookup User...");
            assertThat(UserResponse.MSG_DELETE_SUCCESS_PREFIX).isEqualTo("User ");
            assertThat(UserResponse.MSG_DELETE_SUCCESS_SUFFIX).isEqualTo(" has been deleted ...");
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER)
                    .isEqualTo("Unable to Update User...");
            assertThat(UserResponse.MSG_DELETE_PRESS_PF5).contains("PF5");
            assertThat(UserResponse.MSG_UPDATE_PRESS_PF5).contains("PF5");
        }

        @Test
        @DisplayName("the three identity-empty messages are deliberate aliases, because the add, update "
                + "and delete programs each declare the same literal in their own working storage")
        void theThreeIdentityEmptyMessagesAreDeliberateAliases() {
            assertThat(UserResponse.MSG_ADD_USER_ID_EMPTY)
                    .isEqualTo(UserResponse.MSG_UPDATE_USER_ID_EMPTY)
                    .isEqualTo(UserResponse.MSG_DELETE_USER_ID_EMPTY);
        }

        @Test
        @DisplayName("the add and update screens share four further mandatory-field texts, and the "
                + "delete screen shares none of them because it edits no detail field")
        void theAddAndUpdateScreensShareFourFurtherTexts() {
            assertThat(UserResponse.MSG_ADD_FIRST_NAME_EMPTY)
                    .isEqualTo(UserResponse.MSG_UPDATE_FIRST_NAME_EMPTY);
            assertThat(UserResponse.MSG_ADD_LAST_NAME_EMPTY)
                    .isEqualTo(UserResponse.MSG_UPDATE_LAST_NAME_EMPTY);
            assertThat(UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY)
                    .isEqualTo(UserResponse.MSG_UPDATE_CREDENTIAL_FIELD_EMPTY);
            assertThat(UserResponse.MSG_ADD_USER_TYPE_EMPTY)
                    .isEqualTo(UserResponse.MSG_UPDATE_USER_TYPE_EMPTY);
        }

        @Test
        @DisplayName("the three success prefixes are the same five characters, so the differing suffix "
                + "alone distinguishes an add from an update from a delete")
        void theThreeSuccessPrefixesAreTheSameFiveCharacters() {
            assertThat(UserResponse.MSG_ADD_SUCCESS_PREFIX)
                    .isEqualTo(UserResponse.MSG_UPDATE_SUCCESS_PREFIX)
                    .isEqualTo(UserResponse.MSG_DELETE_SUCCESS_PREFIX)
                    .isEqualTo("User ");
            assertThat(List.of(UserResponse.MSG_ADD_SUCCESS_SUFFIX,
                    UserResponse.MSG_UPDATE_SUCCESS_SUFFIX,
                    UserResponse.MSG_DELETE_SUCCESS_SUFFIX)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the three lookup-failure texts and the two not-found texts and the two "
                + "update-failure texts are aliases, one literal shared across the flows that use it")
        void theFailureTextsAreAliasesAcrossTheFlowsThatUseThem() {
            assertThat(UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo(UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo(UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER);
            assertThat(UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND)
                    .isEqualTo(UserResponse.MSG_DELETE_USER_ID_NOT_FOUND);
            assertThat(UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER)
                    .isEqualTo(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER);
        }

        @Test
        @DisplayName("thirty-five constants carry twenty-three distinct texts, so the aliasing is "
                + "measured rather than assumed and a collapsed constant would move the count")
        void thirtyFiveConstantsCarryTwentyThreeDistinctTexts() throws IllegalAccessException {
            final List<String> texts = declaredMessageTexts();

            assertThat(texts).hasSize(35);
            assertThat(texts).doesNotContainNull();
            assertThat(texts.stream().distinct().toList()).hasSize(23);
        }

        @Test
        @DisplayName("every message fits the seventy-eight character screen field, so no published text "
                + "can be truncated on the way to the operator")
        void everyMessageFitsTheScreenField() throws IllegalAccessException {
            assertThat(declaredMessageTexts())
                    .allSatisfy(text -> assertThat(text.length())
                            .isLessThanOrEqualTo(UserResponse.MESSAGE_LENGTH));
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the two defaulted lists")
    class TheTwoDefaultedLists {

        @Test
        @DisplayName("both absent lists become empty lists, so a caller never has to guard against a "
                + "null before iterating either one")
        void bothAbsentListsBecomeEmptyLists() {
            final UserResponse response = aSparseResponse(null, null);

            assertThat(response.rows()).isNotNull().isEmpty();
            assertThat(response.fieldErrors()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("both supplied lists are copied, so mutating a caller's list afterwards cannot "
                + "change the response")
        void bothSuppliedListsAreCopied() {
            final List<UserResponse.UserRow> rows = new ArrayList<>(List.of(aRow(USER_ID)));
            final List<ErrorResponse.FieldError> errors = new ArrayList<>(List.of(
                    new ErrorResponse.FieldError("userId", "USRIDIN",
                            ErrorResponse.FieldState.MISSING)));
            final UserResponse response = aSparseResponse(rows, errors);

            rows.clear();
            errors.clear();

            assertThat(response.rows()).hasSize(1);
            assertThat(response.fieldErrors()).hasSize(1);
        }

        @Test
        @DisplayName("both copies are unmodifiable, so a holder of the response cannot rewrite a row "
                + "or an error after the service has published it")
        void bothCopiesAreUnmodifiable() {
            final UserResponse response = aSparseResponse(List.of(aRow(USER_ID)), List.of());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.rows().add(aRow("USER0002")));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.fieldErrors().add(
                            new ErrorResponse.FieldError("userId", "USRIDIN",
                                    ErrorResponse.FieldState.INVALID)));
        }

        @Test
        @DisplayName("a null element in either list is refused outright, because an absent row is "
                + "carried as a shorter list rather than as a hole in a full one")
        void aNullElementInEitherListIsRefusedOutright() {
            final List<UserResponse.UserRow> rowsWithNull = Arrays.asList(aRow(USER_ID), null);
            final List<ErrorResponse.FieldError> errorsWithNull = Arrays.asList(
                    new ErrorResponse.FieldError("userId", "USRIDIN",
                            ErrorResponse.FieldState.MISSING), null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aSparseResponse(rowsWithNull, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aSparseResponse(null, errorsWithNull));
        }

        @Test
        @DisplayName("the two row predicates report emptiness independently, so an error on the add "
                + "screen does not imply a populated list screen")
        void theTwoPredicatesReportEmptinessIndependently() {
            final ErrorResponse.FieldError error = new ErrorResponse.FieldError("userId", "USRIDIN",
                    ErrorResponse.FieldState.MISSING);

            assertThat(aSparseResponse(null, null).hasRows()).isFalse();
            assertThat(aSparseResponse(null, null).hasFieldErrors()).isFalse();
            assertThat(aSparseResponse(List.of(aRow(USER_ID)), null).hasRows()).isTrue();
            assertThat(aSparseResponse(List.of(aRow(USER_ID)), null).hasFieldErrors()).isFalse();
            assertThat(aSparseResponse(null, List.of(error)).hasRows()).isFalse();
            assertThat(aSparseResponse(null, List.of(error)).hasFieldErrors()).isTrue();
        }

        @Test
        @DisplayName("an empty list and an absent one produce equal responses, because both mean the "
                + "screen has nothing of that kind to show")
        void anEmptyListAndAnAbsentOneProduceEqualResponses() {
            assertThat(aSparseResponse(List.of(), List.of())).isEqualTo(aSparseResponse(null, null));
        }

        @Test
        @DisplayName("a full page of ten rows keeps its order, because a selector marks the row it "
                + "arrives beside and a reordered page would mark the wrong identity")
        void aFullPageOfTenRowsKeepsItsOrder() {
            final List<UserResponse.UserRow> tenRows = new ArrayList<>();
            for (int index = 1; index <= PageMetadata.USER_LIST_PAGE_SIZE; index++) {
                tenRows.add(aRow(String.format(Locale.ROOT, "USER%04d", index)));
            }

            final UserResponse response = aSparseResponse(tenRows, null);

            assertThat(response.rows()).hasSize(PageMetadata.USER_LIST_PAGE_SIZE);
            assertThat(response.rows()).containsExactlyElementsOf(tenRows);
            assertThat(response.rows().get(0).userId()).isEqualTo("USER0001");
            assertThat(response.rows().get(9).userId()).isEqualTo("USER0010");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the nested displayed row")
    class TheNestedDisplayedRow {

        @Test
        @DisplayName("the row declares five components, the selector and the four fields the list "
                + "screen shows from the eighty-byte record")
        void theRowDeclaresFiveComponents() {
            assertThat(Arrays.stream(UserResponse.UserRow.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .containsExactly("selector", "userId", "firstName", "lastName", "userType");
        }

        @Test
        @DisplayName("every one of the row's five components carries an upper bound, because every one "
                + "of them is a field of the fixed-width record")
        void everyRowComponentCarriesAnUpperBound() {
            assertThat(Arrays.stream(UserResponse.UserRow.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> declaresAnUpperBound(UserResponse.UserRow.class, name))
                    .toList()).hasSize(5);
        }

        @ParameterizedTest
        @CsvSource({"selector,1", "userId,8", "firstName,20", "lastName,20", "userType,1"})
        @DisplayName("each row component declares the width its named constant publishes")
        void eachRowComponentDeclaresItsPublishedWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(UserResponse.UserRow.class, componentName))
                    .isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the row's widths are the enclosing record's widths, so the list rendering and the "
                + "detail rendering cannot drift apart")
        void theRowsWidthsAreTheEnclosingRecordsWidths() throws NoSuchMethodException {
            assertThat(declaredMaximumLength(UserResponse.UserRow.class, "userId"))
                    .isEqualTo(declaredMaximumLength(UserResponse.class, "userId"));
            assertThat(declaredMaximumLength(UserResponse.UserRow.class, "firstName"))
                    .isEqualTo(declaredMaximumLength(UserResponse.class, "firstName"));
            assertThat(declaredMaximumLength(UserResponse.UserRow.class, "userType"))
                    .isEqualTo(declaredMaximumLength(UserResponse.class, "userType"));
        }

        @Test
        @DisplayName("every row component round-trips through its own accessor unchanged")
        void everyRowComponentRoundTripsThroughItsAccessor() {
            final UserResponse.UserRow row = new UserResponse.UserRow("U", USER_ID, "FIRSTNAME",
                    "LASTNAME", "A");

            assertThat(row.selector()).isEqualTo("U");
            assertThat(row.userId()).isEqualTo(USER_ID);
            assertThat(row.firstName()).isEqualTo("FIRSTNAME");
            assertThat(row.lastName()).isEqualTo("LASTNAME");
            assertThat(row.userType()).isEqualTo("A");
        }

        @Test
        @DisplayName("two rows built from identical values are equal and share a hash code")
        void twoRowsBuiltFromIdenticalValuesAreEqual() {
            assertThat(aRow(USER_ID)).isEqualTo(aRow(USER_ID)).hasSameHashCodeAs(aRow(USER_ID));
            assertThat(aRow(USER_ID)).isNotEqualTo(aRow("USER0002"));
        }

        @Test
        @DisplayName("an over-long row value is reported when the row is validated in its own right")
        void anOverLongRowValueIsReportedWhenTheRowIsValidated() {
            final UserResponse.UserRow row = new UserResponse.UserRow(" ", "X".repeat(9), null,
                    null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(row))
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString()).isEqualTo("userId"));
            }
        }

        @Test
        @DisplayName("the enclosing response does not cascade into its rows, because the row list "
                + "carries no cascade marker and the rows are produced by the service, not the client")
        void theEnclosingResponseDoesNotCascadeIntoItsRows() {
            final UserResponse response = aSparseResponse(
                    List.of(new UserResponse.UserRow(" ", "X".repeat(9), null, null, null)), null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response)).isEmpty();
            }
        }

        /**
         * A list screen shows no credential, but it does show an identity, and the row withholds that.
         *
         * <p>Carrying no credential is not the same as carrying nothing worth withholding. Every row names
         * a person - an identifier, a first and last name - and the row's type code names the authorization
         * that person holds, which is the single most useful field to an attacker choosing an account to
         * attack. A page carries ten of them, so a generated rendering here would disclose ten identities
         * per line.</p>
         *
         * <p>The selector survives, because it is the operator's own action code on the row in front of
         * them and identifies nobody. The row declares this rendering itself rather than relying on the
         * enclosing response's, because a row is an element of a list a caller can iterate and render on
         * its own.</p>
         */
        @Test
        @DisplayName("a row withholds the identity and the authorization it names, keeping only the "
                + "operator's own selector, because a page carries ten rows")
        void aRowWithholdsTheIdentityAndTheAuthorization() {
            final String rendered = new UserResponse.UserRow("U", USER_ID, "FIRSTNAME", "LASTNAME",
                    "A").toString();

            assertThat(rendered).isEqualTo(
                    "UserRow[selector=U, userId=" + REDACTION_PLACEHOLDER
                            + ", firstName=" + REDACTION_PLACEHOLDER
                            + ", lastName=" + REDACTION_PLACEHOLDER
                            + ", userType=" + REDACTION_PLACEHOLDER + "]");
            assertThat(rendered)
                    .doesNotContain(USER_ID)
                    .doesNotContain("FIRSTNAME")
                    .doesNotContain("LASTNAME");
        }

        @Test
        @DisplayName("the row withholding is unconditional, so an empty row renders four placeholders "
                + "rather than four nulls")
        void theRowWithholdingIsUnconditional() {
            assertThat(new UserResponse.UserRow(null, null, null, null, null).toString())
                    .isEqualTo("UserRow[selector=null, userId=" + REDACTION_PLACEHOLDER
                            + ", firstName=" + REDACTION_PLACEHOLDER
                            + ", lastName=" + REDACTION_PLACEHOLDER
                            + ", userType=" + REDACTION_PLACEHOLDER + "]");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and its validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        @Test
        @DisplayName("the response declares nineteen components, the list block, the detail block, the "
                + "screen furniture, the outcome block and the two navigation components")
        void theResponseDeclaresNineteenComponents() {
            final List<String> declared = Arrays.stream(UserResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("rows", "pageMetadata", "userId", "firstName",
                    "lastName", "userType", "transactionName", "title01", "currentDate",
                    "programName", "title02", "currentTime", "message", "fieldErrors",
                    "generalError", "actionSucceeded", "focusScreenFieldId", "nextRoute",
                    "navigationContext");
            assertThat(declared).hasSize(19);
        }

        @Test
        @DisplayName("exactly twelve components carry an upper bound, and neither list, neither flag "
                + "nor either navigation component is one of them")
        void exactlyTwelveComponentsCarryAnUpperBound() {
            final List<String> bounded = Arrays.stream(UserResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> declaresAnUpperBound(UserResponse.class, name)).toList();

            assertThat(bounded).hasSize(12);
            assertThat(bounded).containsExactly("userId", "firstName", "lastName", "userType",
                    "transactionName", "title01", "currentDate", "programName", "title02",
                    "currentTime", "message", "focusScreenFieldId");
            assertThat(bounded).doesNotContain("rows", "pageMetadata", "fieldErrors", "nextRoute",
                    "navigationContext");
        }

        @Test
        @DisplayName("no component carries a credential, because nothing about a password is ever sent "
                + "back to the operator even though the request had to carry one")
        void noComponentCarriesACredential() {
            final List<String> declared = Arrays.stream(UserResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).doesNotContain("password", "credential", "passwordHash",
                    "secret", "hashedPassword");
            assertThat(Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList()).contains("password");
        }

        @Test
        @DisplayName("the two outcome flags are primitives, so a response can never be ambiguous about "
                + "whether the requested action actually took effect")
        void theTwoOutcomeFlagsArePrimitives() throws NoSuchMethodException {
            assertThat(UserResponse.class.getDeclaredMethod("generalError").getReturnType())
                    .isEqualTo(boolean.class);
            assertThat(UserResponse.class.getDeclaredMethod("actionSucceeded").getReturnType())
                    .isEqualTo(boolean.class);
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final PageMetadata page = PageMetadata.forward(PageMetadata.USER_LIST_PAGE_SIZE,
                    "ADMIN001", "USER0005", true, false, "00000001");
            final ErrorResponse.FieldError error = new ErrorResponse.FieldError("userId", "USRIDIN",
                    ErrorResponse.FieldState.MISSING);
            final UserResponse response = aResponse(List.of(aRow(USER_ID)), page,
                    UserResponse.MSG_LIST_AT_TOP, List.of(error), NavigationContext.empty());

            assertThat(response.rows()).containsExactly(aRow(USER_ID));
            assertThat(response.pageMetadata()).isEqualTo(page);
            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.firstName()).isEqualTo("FIRSTNAME");
            assertThat(response.lastName()).isEqualTo("LASTNAME");
            assertThat(response.userType()).isEqualTo("A");
            assertThat(response.transactionName()).isEqualTo("CU01");
            assertThat(response.title01()).isEqualTo("List Users");
            assertThat(response.currentDate()).isEqualTo("08/02/26");
            assertThat(response.programName()).isEqualTo("COUSR01C");
            assertThat(response.title02()).isEqualTo("CardDemo");
            assertThat(response.currentTime()).isEqualTo("14:30:00");
            assertThat(response.message()).isEqualTo(UserResponse.MSG_LIST_AT_TOP);
            assertThat(response.fieldErrors()).containsExactly(error);
            assertThat(response.generalError()).isFalse();
            assertThat(response.actionSucceeded()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo("USRIDIN");
            assertThat(response.nextRoute()).isEqualTo("/api/admin/users");
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a wholly absent response reports no violation, because every bound is an upper "
                + "bound and one response serves four screens")
        void aWhollyAbsentResponseReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aSparseResponse(null, null))).isEmpty();
            }
        }

        @Test
        @DisplayName("a fully populated response reports no violation, so the representative fixture "
                + "is itself within every declared bound")
        void aFullyPopulatedResponseReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aResponse(List.of(aRow(USER_ID)),
                        PageMetadata.forward(PageMetadata.USER_LIST_PAGE_SIZE, null, null, false,
                                false, "00000001"),
                        UserResponse.MSG_LIST_AT_TOP, List.of(), NavigationContext.empty())))
                        .isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({
            "userId,8", "firstName,20", "lastName,20",
            "userType,1", "transactionName,4", "title01,40",
            "currentDate,8", "programName,8", "title02,40",
            "currentTime,8", "message,78", "focusScreenFieldId,7",
        })
        @DisplayName("a bounded component accepts its declared width and rejects one character more")
        void aBoundedComponentAcceptsItsWidthAndRejectsOneMore(final String componentName,
                final int declaredMaximum) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, "X".repeat(declaredMaximum))))
                        .as("%s must accept %d characters", componentName, declaredMaximum)
                        .isEmpty();
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, "X".repeat(declaredMaximum + 1))))
                        .as("%s must reject %d characters", componentName, declaredMaximum + 1)
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString()).isEqualTo(componentName));
            }
        }

        @Test
        @DisplayName("the two titles share one bound, because both screen lines are rendered into the "
                + "same forty-character field on the map")
        void theTwoTitlesShareOneBound() throws NoSuchMethodException {
            assertThat(declaredMaximumLength(UserResponse.class, "title01"))
                    .isEqualTo(declaredMaximumLength(UserResponse.class, "title02"))
                    .isEqualTo(UserResponse.SCREEN_TITLE_LENGTH);
        }

        @Test
        @DisplayName("the next route is unbounded, because it is a Java routing constant rather than a "
                + "field of the fixed-width record the screen renders")
        void theNextRouteIsUnbounded() {
            assertThat(declaresAnUpperBound(UserResponse.class, "nextRoute")).isFalse();

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(new UserResponse(null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, false, false,
                        null, "/".repeat(200), null))).isEmpty();
            }
        }

        /**
         * Builds a response carrying a single named component and nothing else.
         *
         * @param componentName the component to populate
         * @param value the value to place in it
         * @return a response carrying only that component
         */
        private UserResponse responseWith(final String componentName, final String value) {
            return new UserResponse(
                    null, null,
                    valueFor("userId", componentName, value),
                    valueFor("firstName", componentName, value),
                    valueFor("lastName", componentName, value),
                    valueFor("userType", componentName, value),
                    valueFor("transactionName", componentName, value),
                    valueFor("title01", componentName, value),
                    valueFor("currentDate", componentName, value),
                    valueFor("programName", componentName, value),
                    valueFor("title02", componentName, value),
                    valueFor("currentTime", componentName, value),
                    valueFor("message", componentName, value),
                    null, false, false,
                    valueFor("focusScreenFieldId", componentName, value),
                    null, null);
        }

        /**
         * Returns the value when the position being filled is the requested component.
         *
         * @param position the component this constructor argument fills
         * @param requested the component the caller wants populated
         * @param value the value to place
         * @return the value when the position matches, otherwise {@code null}
         */
        private String valueFor(final String position, final String requested, final String value) {
            return position.equals(requested) ? value : null;
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two responses built from identical values are equal and share a hash code")
        void twoResponsesBuiltFromIdenticalValuesAreEqual() {
            final UserResponse first = aResponse(List.of(aRow(USER_ID)), null,
                    UserResponse.MSG_LIST_AT_TOP, List.of(), NavigationContext.empty());
            final UserResponse second = aResponse(List.of(aRow(USER_ID)), null,
                    UserResponse.MSG_LIST_AT_TOP, List.of(), NavigationContext.empty());

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a difference in the outcome flag alone makes two responses unequal, so a "
                + "succeeded update is never mistaken for a redisplayed one")
        void aDifferenceInTheOutcomeFlagMakesTwoResponsesUnequal() {
            final UserResponse succeeded = new UserResponse(null, null, USER_ID, null, null, null,
                    null, null, null, null, null, null, null, null, false, true, null, null, null);
            final UserResponse redisplayed = new UserResponse(null, null, USER_ID, null, null, null,
                    null, null, null, null, null, null, null, null, false, false, null, null, null);

            assertThat(succeeded).isNotEqualTo(redisplayed);
        }

        @Test
        @DisplayName("a difference in row order makes two responses unequal, because a selector marks "
                + "the row position it arrives beside")
        void aDifferenceInRowOrderMakesTwoResponsesUnequal() {
            assertThat(aSparseResponse(List.of(aRow("USER0001"), aRow("USER0002")), null))
                    .isNotEqualTo(aSparseResponse(List.of(aRow("USER0002"), aRow("USER0001")),
                            null));
        }

        @Test
        @DisplayName("the payload always carries both lists and omits every absent component")
        void thePayloadAlwaysCarriesBothLists() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseResponse(null, null));

            assertThat(payload.get("rows").isArray()).isTrue();
            assertThat(payload.get("rows")).isEmpty();
            assertThat(payload.get("fieldErrors").isArray()).isTrue();
            assertThat(payload.get("fieldErrors")).isEmpty();
            assertThat(payload.has("userId")).isFalse();
            assertThat(payload.has("pageMetadata")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
        }

        @Test
        @DisplayName("both flags always travel, because a primitive has no absent state for the module "
                + "mapper's non-null inclusion to omit")
        void bothFlagsAlwaysTravel() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aSparseResponse(null, null));

            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("actionSucceeded").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("a row travels with its five named fields in declaration order")
        void aRowTravelsWithItsFiveNamedFields() throws JsonProcessingException {
            final JsonNode row = payloadOf(aSparseResponse(
                    List.of(new UserResponse.UserRow("U", USER_ID, "FIRSTNAME", "LASTNAME", "A")),
                    null)).get("rows").get(0);

            assertThat(row.properties().stream().map(Map.Entry::getKey).toList())
                    .containsExactly("selector", "userId", "firstName", "lastName", "userType");
            assertThat(row.get("userId").asText()).isEqualTo(USER_ID);
            assertThat(row.get("userType").asText()).isEqualTo("A");
        }

        @Test
        @DisplayName("a field error travels with its state as an enum name, so the screen can tell a "
                + "missing field from an invalid one")
        void aFieldErrorTravelsWithItsStateAsAnEnumName() throws JsonProcessingException {
            final JsonNode error = payloadOf(aSparseResponse(null, List.of(
                    new ErrorResponse.FieldError("userType", "USRTYPE",
                            ErrorResponse.FieldState.INVALID, UserResponse.MSG_ADD_USER_TYPE_EMPTY))))
                    .get("fieldErrors").get(0);

            assertThat(error.get("fieldName").asText()).isEqualTo("userType");
            assertThat(error.get("screenFieldId").asText()).isEqualTo("USRTYPE");
            assertThat(error.get("state").asText()).isEqualTo("INVALID");
            assertThat(error.get("message").asText())
                    .isEqualTo(UserResponse.MSG_ADD_USER_TYPE_EMPTY);
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged, "
                + "rows, paging state and error detail included")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final UserResponse original = aResponse(List.of(aRow(USER_ID), aRow("USER0002")),
                    PageMetadata.backward(PageMetadata.USER_LIST_PAGE_SIZE, "ADMIN001", "USER0005",
                            false, true, "00000002"),
                    UserResponse.MSG_LIST_REACHED_TOP,
                    List.of(new ErrorResponse.FieldError("userId", "USRIDIN",
                            ErrorResponse.FieldState.MISSING)),
                    NavigationContext.empty().withReEntry());
            final UserResponse restored = mapper.readValue(mapper.writeValueAsString(original),
                    UserResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.hasRows()).isTrue();
            assertThat(restored.hasFieldErrors()).isTrue();
            assertThat(restored.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
        }
    }
}
