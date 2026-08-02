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

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link UserResponse}, the single response body shared by the four
 * user-administration transactions {@code CU00}, {@code CU01}, {@code CU02} and {@code CU03}.
 *
 * <p>A pure unit test: no application context, no connection, no container. Where the wire shape is
 * what is under test it serialises with a locally built mapper configured to match the settings the
 * module declares in {@code application.yml}.
 *
 * <p>Four properties dominate what is checked here, and each of the four was wrong.
 *
 * <p>The first is disclosure, and it is the most serious of the four. A record's generated rendering
 * prints every component. This response carries a user identifier, a first name, a last name and a
 * user type at its top level, and up to ten rows carrying the same four values each - so a single log
 * statement over one list page disclosed eleven identities together with which of them hold
 * administrative rights. The tests below prove the values are still carried and returned untouched
 * while both the response and each individual row withhold them.
 *
 * <p>The second is cardinality. The list map declares ten row families and the program's own staging
 * table declares ten entries, yet the row collection accepted any number, so a producer could build a
 * page the screen it reproduces could not render.
 *
 * <p>The third is the role vocabulary. The one-character code this response emits is the same code
 * the sign-on response returns, the navigation context carries and the request submits; nothing said
 * so, and nothing published which two characters a client should expect.
 *
 * <p>The fourth is naming. The focus hint and the next route were spelled differently here than in
 * every sibling contract, so a client consuming two endpoints met two spellings for one concept.
 *
 * <p>Provenance for every width, every message literal and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("UserResponse :: shared response contract of legacy transactions CU00 to CU03")
class UserResponseTest {

    /** The nineteen record components in constructor order. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "rows", "pageMetadata", "userId", "firstName", "lastName", "userType",
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "message", "fieldErrors", "generalError", "actionSucceeded", "focusScreenFieldId",
            "nextRoute", "navigationContext");

    /** Every width-bounded string component, in constructor order. */
    private static final List<String> BOUNDED_STRINGS = List.of(
            "userId", "firstName", "lastName", "userType", "transactionName", "title01",
            "currentDate", "programName", "title02", "currentTime", "message",
            "focusScreenFieldId");

    /** The measured widths matching {@link #BOUNDED_STRINGS}. */
    private static final List<Integer> BOUNDED_WIDTHS =
            List.of(8, 20, 20, 1, 4, 40, 8, 8, 40, 8, 78, 7);

    private static final String USER_ID = "TARGETUS";
    private static final String FIRST_NAME = "MARY ANN";
    private static final String LAST_NAME = "o'HARA-smith";
    private static final String USER_TYPE = "A";
    private static final String TRANSACTION_NAME = "CU00";
    private static final String TITLE_01 = "AWS Mainframe Modernization";
    private static final String CURRENT_DATE = "07/19/22";
    private static final String PROGRAM_NAME = "COUSR00C";
    private static final String TITLE_02 = "CardDemo";
    private static final String CURRENT_TIME = "14:30:00";
    private static final String MESSAGE = "Unable to lookup User...";
    private static final String FOCUS_FIELD = "USRIDIN";
    private static final String NEXT_ROUTE = "/api/v1/admin/users";
    private static final String ROW_USER_ID = "ROWUSR01";
    private static final String ROW_FIRST_NAME = "JANE";
    private static final String ROW_LAST_NAME = "MacDONALD";
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CU00", "COUSR00C", "CU02", "COUSR02C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                "00000000011", "Y", "0000000000000011", "COUSR0A", "COUSR00");
    }

    private static UserResponse.UserRow row() {
        return new UserResponse.UserRow("S", ROW_USER_ID, ROW_FIRST_NAME, ROW_LAST_NAME, "U");
    }

    private static List<ErrorResponse.FieldError> fieldErrors() {
        return List.of(new ErrorResponse.FieldError("userId", "USRIDIN",
                ErrorResponse.FieldState.MISSING, "User ID can NOT be empty..."));
    }

    /** A response carrying every component, which is what an inventory assertion needs. */
    private static UserResponse populated() {
        return new UserResponse(List.of(row()), PageMetadata.forward(10, "USER0001", "USER0010",
                true, false, "00000003"), USER_ID, FIRST_NAME, LAST_NAME, USER_TYPE,
                TRANSACTION_NAME, TITLE_01, CURRENT_DATE, PROGRAM_NAME, TITLE_02, CURRENT_TIME,
                MESSAGE, fieldErrors(), true, false, FOCUS_FIELD, NEXT_ROUTE, navigation());
    }

    /** A response carrying only the screen furniture, as a successful add or delete returns. */
    private static UserResponse cleared() {
        return new UserResponse(null, null, null, null, null, null, TRANSACTION_NAME, TITLE_01,
                CURRENT_DATE, PROGRAM_NAME, TITLE_02, CURRENT_TIME, null, null, false, true, null,
                null, null);
    }

    /**
     * Builds a response whose only populated component is the row collection.
     *
     * @param rows the rows to carry
     * @return a response carrying those rows and nothing else
     */
    private static UserResponse carrying(List<UserResponse.UserRow> rows) {
        return new UserResponse(rows, null, null, null, null, null, null, null, null, null, null,
                null, null, null, false, false, null, null, null);
    }

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

    private static JsonNode payloadOf(UserResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    private static List<String> componentNames() {
        return Arrays.stream(UserResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotations a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component, because neither the length
     * constraint nor the published-schema directive declares {@code RECORD_COMPONENT} among its
     * targets: asking the component yields an empty array for every component here, and an assertion
     * phrased that way would pass without testing anything.
     *
     * @param name the component name
     * @return the annotations present on the backing field
     */
    private static Annotation[] annotationsOn(String name) {
        try {
            return UserResponse.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return UserResponse.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A rowAnnotationOn(String name, Class<A> type) {
        try {
            return UserResponse.UserRow.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no row component named " + name, absent);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, with the delegated navigation
     * segment excised.
     *
     * <p>Written as an excision rather than a truncation so it does not depend on the navigation state
     * being the final component. The excision is necessary rather than merely tidy: the nested
     * contract prints a user identifier and a user type of its own in the clear, so a rendering
     * assertion phrased over the whole string would be testing the nested type's redaction policy
     * instead of this one's.
     *
     * @param response the response whose rendering is being examined
     * @return the rendering with any delegated navigation segment replaced
     */
    private static String ownRendering(UserResponse response) {
        String rendered = response.toString();
        return (response.navigationContext() == null)
                ? rendered
                : rendered.replace(response.navigationContext().toString(), "<delegated>");
    }

    private static Set<ConstraintViolation<UserResponse>> violationsOf(UserResponse response) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(response);
        }
    }

    private static long occurrencesOfRedaction(String rendered) {
        return rendered.split(Pattern.quote(REDACTED), -1).length - 1L;
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the nineteen components in constructor order")
        void declaresNineteenComponents() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_ORDER)
                    .hasSize(19);
        }

        @Test
        @DisplayName("bounds each string component at its measured map width")
        void boundsEachStringAtItsMapWidth() {
            for (int index = 0; index < BOUNDED_STRINGS.size(); index++) {
                String component = BOUNDED_STRINGS.get(index);
                Size bound = annotationOn(component, Size.class);
                assertThat(bound)
                        .describedAs("component %s carries a width bound", component)
                        .isNotNull();
                assertThat(bound.max())
                        .describedAs("width of component %s", component)
                        .isEqualTo(BOUNDED_WIDTHS.get(index));
            }
        }

        @Test
        @DisplayName("publishes each width as a named constant that agrees with the annotation")
        void publishesEachWidthAsAConstant() {
            assertThat(UserResponse.SELECTOR_LENGTH).isEqualTo(1);
            assertThat(UserResponse.USER_ID_LENGTH).isEqualTo(8);
            assertThat(UserResponse.FIRST_NAME_LENGTH).isEqualTo(20);
            assertThat(UserResponse.LAST_NAME_LENGTH).isEqualTo(20);
            assertThat(UserResponse.USER_TYPE_LENGTH).isEqualTo(1);
            assertThat(UserResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(UserResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(UserResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(UserResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(UserResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(UserResponse.MESSAGE_LENGTH).isEqualTo(78);
            assertThat(UserResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("declares the nested row with exactly its five map components")
        void declaresTheNestedRowWithFiveComponents() {
            assertThat(Arrays.stream(UserResponse.UserRow.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .containsExactly("selector", "userId", "firstName", "lastName", "userType");

            assertThat(rowAnnotationOn("selector", Size.class).max()).isEqualTo(1);
            assertThat(rowAnnotationOn("userId", Size.class).max()).isEqualTo(8);
            assertThat(rowAnnotationOn("firstName", Size.class).max()).isEqualTo(20);
            assertThat(rowAnnotationOn("lastName", Size.class).max()).isEqualTo(20);
            assertThat(rowAnnotationOn("userType", Size.class).max()).isEqualTo(1);
        }

        @Test
        @DisplayName("nests the row type inside this response rather than adding a package file")
        void nestsTheRowType() {
            assertThat(UserResponse.UserRow.class.getEnclosingClass())
                    .isEqualTo(UserResponse.class);
            assertThat(UserResponse.UserRow.class.isRecord()).isTrue();
        }

        @Test
        @DisplayName("carries every value byte for byte, trimming, padding and re-casing nothing")
        void carriesEveryValueByteForByte() {
            UserResponse response = new UserResponse(null, null, "  usr  ", "  Mary  ", " o'Hara ",
                    " ", " cu ", "  title  ", " 07/19/2", " prog  ", " title2 ", " 14:30:0",
                    "  msg  ", null, false, false, " USRID ", " /route ", null);

            assertThat(response.userId()).isEqualTo("  usr  ");
            assertThat(response.firstName()).isEqualTo("  Mary  ");
            assertThat(response.lastName()).isEqualTo(" o'Hara ");
            assertThat(response.userType()).isEqualTo(" ");
            assertThat(response.message()).isEqualTo("  msg  ");
            assertThat(response.focusScreenFieldId()).isEqualTo(" USRID ");
            assertThat(response.nextRoute()).isEqualTo(" /route ");
        }

        @Test
        @DisplayName("accepts a fully populated response and one carrying only screen furniture")
        void acceptsBothShapes() {
            assertThat(violationsOf(populated())).isEmpty();
            assertThat(violationsOf(cleared())).isEmpty();
        }

        @Test
        @DisplayName("states both outcome flags explicitly and never infers one from the other")
        void statesBothOutcomeFlagsExplicitly() {
            UserResponse messageWithoutError = new UserResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, MESSAGE, null, false, false, null, null,
                    null);

            assertThat(messageWithoutError.message()).isEqualTo(MESSAGE);
            assertThat(messageWithoutError.generalError()).isFalse();
            assertThat(messageWithoutError.actionSucceeded()).isFalse();
        }
    }

    @Nested
    @DisplayName("the row list is capped at the screen's row count")
    class TheRowListIsCapped {

        @Test
        @DisplayName("publishes the row count the list map and the program's table agree on")
        void publishesTheRowCount() {
            assertThat(UserResponse.ROW_COUNT).isEqualTo(10);
        }

        @Test
        @DisplayName("accepts a row collection filled to the row count")
        void acceptsACollectionAtTheRowCount() {
            List<UserResponse.UserRow> full = Collections.nCopies(UserResponse.ROW_COUNT, row());

            assertThat(carrying(full).rows()).hasSize(UserResponse.ROW_COUNT);
        }

        @Test
        @DisplayName("refuses a row collection longer than the row count")
        void refusesACollectionLongerThanTheRowCount() {
            List<UserResponse.UserRow> tooMany =
                    Collections.nCopies(UserResponse.ROW_COUNT + 1, row());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carrying(tooMany))
                    .withMessageContaining("rows")
                    .withMessageContaining("at most 10")
                    .withMessageContaining("11");
        }

        @Test
        @DisplayName("accepts a shorter collection, so a partial final page stays partial")
        void acceptsAShorterCollection() {
            assertThat(carrying(List.of(row(), row())).rows()).hasSize(2);
        }

        @Test
        @DisplayName("accepts an absent collection, because three of the four transactions carry no "
                + "rows at all")
        void acceptsAnAbsentCollection() {
            assertThat(carrying(null).rows()).isEmpty();
            assertThat(cleared().rows()).isEmpty();
        }

        @Test
        @DisplayName("preserves row order and pads nothing")
        void preservesRowOrder() {
            UserResponse.UserRow first = new UserResponse.UserRow("S", "AAAAAAAA", "A", "A", "A");
            UserResponse.UserRow second = new UserResponse.UserRow("U", "BBBBBBBB", "B", "B", "U");

            assertThat(carrying(List.of(second, first)).rows()).containsExactly(second, first);
        }

        @Test
        @DisplayName("freezes the collection and does not alias caller-owned state")
        void freezesTheCollection() {
            List<UserResponse.UserRow> caller = new ArrayList<>(List.of(row()));
            UserResponse response = carrying(caller);

            caller.add(row());

            assertThat(response.rows()).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.rows().add(row()));
        }

        @Test
        @DisplayName("normalises and freezes the field-error collection without capping it")
        void normalisesTheFieldErrorCollection() {
            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, false, false, null, null, null);

            assertThat(response.fieldErrors()).isEmpty();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.fieldErrors().add(fieldErrors().get(0)));
        }

        @Test
        @DisplayName("reports presence through the two convenience tests without conflating either "
                + "with a failure")
        void reportsPresenceThroughTheConvenienceTests() {
            assertThat(populated().hasRows()).isTrue();
            assertThat(populated().hasFieldErrors()).isTrue();
            assertThat(cleared().hasRows()).isFalse();
            assertThat(cleared().hasFieldErrors()).isFalse();
            assertThat(cleared().actionSucceeded()).isTrue();
        }
    }

    @Nested
    @DisplayName("the user-type vocabulary is shared and is not enforced here")
    class TheUserTypeVocabularyIsShared {

        @Test
        @DisplayName("publishes the two authoritative codes on the top-level component")
        void publishesTheTwoCodesOnTheTopLevelComponent() {
            Schema published = annotationOn("userType", Schema.class);

            assertThat(published).isNotNull();
            assertThat(published.description())
                    .contains("A for an")
                    .contains("U for a standard user")
                    .contains("COCOM01Y");
        }

        @Test
        @DisplayName("publishes the same two codes on every row")
        void publishesTheSameTwoCodesOnEveryRow() {
            Schema published = rowAnnotationOn("userType", Schema.class);

            assertThat(published).isNotNull();
            assertThat(published.description())
                    .contains("A for an administrator")
                    .contains("U for a standard user")
                    .contains("COCOM01Y");
        }

        @Test
        @DisplayName("emits both authoritative codes unchanged")
        void emitsBothAuthoritativeCodesUnchanged() {
            for (String code : List.of("A", "U")) {
                UserResponse response = new UserResponse(
                        List.of(new UserResponse.UserRow(null, ROW_USER_ID, null, null, code)),
                        null, null, null, null, code, null, null, null, null, null, null, null,
                        null, false, false, null, null, null);

                assertThat(response.userType()).isEqualTo(code);
                assertThat(response.rows().get(0).userType()).isEqualTo(code);
                assertThat(violationsOf(response)).isEmpty();
            }
        }

        @Test
        @DisplayName("still emits an undeclared one-character code, so a migrated record outside the "
                + "expected pair stays reportable rather than unserialisable")
        void stillEmitsAnUndeclaredCode() {
            for (String code : List.of("X", "a", "9", " ")) {
                UserResponse response = new UserResponse(null, null, null, null, null, code, null,
                        null, null, null, null, null, null, null, false, false, null, null, null);

                assertThat(response.userType())
                        .describedAs("code %s survives", code)
                        .isEqualTo(code);
                assertThat(violationsOf(response))
                        .describedAs("code %s is tolerated", code)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("bounds the code by width, so a two-character value is still refused")
        void boundsTheCodeByWidth() {
            UserResponse response = new UserResponse(null, null, null, null, null, "AU", null, null,
                    null, null, null, null, null, null, false, false, null, null, null);

            assertThat(violationsOf(response)).hasSize(1);
        }

        @Test
        @DisplayName("carries the code as raw text at both levels rather than as the domain "
                + "enumeration")
        void carriesTheCodeAsRawTextAtBothLevels() throws NoSuchFieldException {
            assertThat(UserResponse.class.getDeclaredField("userType").getType())
                    .isEqualTo(String.class);
            assertThat(UserResponse.UserRow.class.getDeclaredField("userType").getType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("agrees with every other contract on the width of the code and the identifier")
        void agreesWithEveryOtherContract() {
            assertThat(UserResponse.USER_TYPE_LENGTH)
                    .isEqualTo(UserRequest.USER_TYPE_LENGTH)
                    .isEqualTo(NavigationContext.USER_TYPE_LENGTH)
                    .isEqualTo(SignOnResponse.USER_TYPE_LENGTH);
            assertThat(UserResponse.USER_ID_LENGTH)
                    .isEqualTo(UserRequest.USER_ID_LENGTH)
                    .isEqualTo(NavigationContext.USER_ID_LENGTH)
                    .isEqualTo(SignOnResponse.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("agrees with the request contract on the row count, so a page cannot be built "
                + "wider than it can be selected")
        void agreesWithTheRequestContractOnTheRowCount() {
            assertThat(UserResponse.ROW_COUNT).isEqualTo(UserRequest.ROW_COUNT);
        }
    }

    @Nested
    @DisplayName("the focus hint and the next route use the canonical spelling")
    class CanonicalSpelling {

        @Test
        @DisplayName("names the focus hint and the route as every sibling contract names them")
        void namesTheFocusHintAndRouteCanonically() {
            assertThat(componentNames())
                    .contains("focusScreenFieldId", "nextRoute", "title01", "title02",
                            "pageMetadata", "navigationContext")
                    .doesNotContain("fieldToFocus", "route", "screenTitle1", "screenTitleLine1",
                            "titleLine1", "navigation", "page");
        }

        @Test
        @DisplayName("bounds the focus hint at the widest field name across the four screens")
        void boundsTheFocusHintAtSeven() {
            assertThat(annotationOn("focusScreenFieldId", Size.class).max()).isEqualTo(7);
            assertThat(UserResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("refuses a focus hint wider than the bound and admits one exactly at it")
        void refusesAnOverWideFocusHint() {
            UserResponse atTheBound = new UserResponse(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false, false, "USRIDIN", null, null);
            UserResponse overTheBound = new UserResponse(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false, false, "USRIDINX", null, null);

            assertThat(violationsOf(atTheBound)).isEmpty();
            assertThat(violationsOf(overTheBound)).hasSize(1);
        }

        @Test
        @DisplayName("leaves the route unbounded, because it is a client-resolved label rather than "
                + "a screen field")
        void leavesTheRouteUnbounded() {
            assertThat(annotationOn("nextRoute", Size.class)).isNull();

            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, false, false, null,
                    "/api/v1/admin/users/TARGETUS/detail", null);

            assertThat(violationsOf(response)).isEmpty();
        }

        @Test
        @DisplayName("agrees with the sibling focus bounds across the package")
        void agreesWithSiblingFocusBounds() {
            assertThat(UserResponse.SCREEN_FIELD_ID_LENGTH)
                    .isEqualTo(SignOnResponse.SCREEN_FIELD_ID_LENGTH)
                    .isEqualTo(MenuResponse.SCREEN_FIELD_ID_WIDTH);
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds the identifier, both name parts and the user type")
        void withholdsTheIdentityValues() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .doesNotContain(USER_ID)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME);
            assertThat(rendered)
                    .contains("userId=" + REDACTED)
                    .contains("firstName=" + REDACTED)
                    .contains("lastName=" + REDACTED)
                    .contains("userType=" + REDACTED);
        }

        @Test
        @DisplayName("withholds the rows entirely and reports their count instead")
        void withholdsTheRowsAndReportsTheirCount() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .contains("rowCount=1")
                    .contains("rows=" + REDACTED)
                    .doesNotContain(ROW_USER_ID)
                    .doesNotContain(ROW_FIRST_NAME)
                    .doesNotContain(ROW_LAST_NAME);
        }

        @Test
        @DisplayName("withholds the paging metadata, whose cursor keys are themselves user "
                + "identifiers")
        void withholdsThePagingMetadata() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .contains("pageMetadata=" + REDACTED)
                    .doesNotContain("USER0001")
                    .doesNotContain("USER0010");
        }

        @Test
        @DisplayName("retains the operator message, because it is externally observable contract "
                + "text and describes nobody")
        void retainsTheOperatorMessage() {
            assertThat(ownRendering(populated())).contains("message=" + MESSAGE);
        }

        @Test
        @DisplayName("retains both outcome flags, which is what makes a failed submission "
                + "diagnosable")
        void retainsBothOutcomeFlags() {
            assertThat(ownRendering(populated()))
                    .contains("generalError=true")
                    .contains("actionSucceeded=false");
        }

        @Test
        @DisplayName("retains the screen furniture, which identifies the screen and describes nobody")
        void retainsTheScreenFurniture() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .contains("transactionName=" + TRANSACTION_NAME)
                    .contains("title01=" + TITLE_01)
                    .contains("title02=" + TITLE_02)
                    .contains("currentDate=" + CURRENT_DATE)
                    .contains("currentTime=" + CURRENT_TIME)
                    .contains("programName=" + PROGRAM_NAME);
        }

        @Test
        @DisplayName("retains the field errors in full, because that contract carries a field name, "
                + "an identity, a state and a message but never a field value")
        void retainsTheFieldErrorsInFull() {
            assertThat(ownRendering(populated()))
                    .contains("fieldErrors=")
                    .contains("MISSING");
        }

        @Test
        @DisplayName("retains the focus hint and the route, which are opaque labels")
        void retainsTheFocusHintAndRoute() {
            assertThat(ownRendering(populated()))
                    .contains("focusScreenFieldId=" + FOCUS_FIELD)
                    .contains("nextRoute=" + NEXT_ROUTE);
        }

        @Test
        @DisplayName("delegates the navigation state to its own rendering rather than suppressing it")
        void delegatesTheNavigationState() {
            assertThat(populated().toString())
                    .contains("navigationContext=NavigationContext[");
        }

        @Test
        @DisplayName("names the type and closes the rendering")
        void namesTheTypeAndClosesTheRendering() {
            assertThat(populated().toString())
                    .startsWith("UserResponse[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("substitutes a fixed placeholder that never varies with the value it hides")
        void substitutesAFixedPlaceholder() {
            UserResponse shortValues = new UserResponse(null, null, "A", "B", "C", "D", null, null,
                    null, null, null, null, null, null, false, false, null, null, null);
            UserResponse longValues = new UserResponse(null, null, "TARGETUS", "MARY ANN",
                    "o'HARA-smith", "A", null, null, null, null, null, null, null, null, false,
                    false, null, null, null);

            assertThat(occurrencesOfRedaction(shortValues.toString()))
                    .isEqualTo(occurrencesOfRedaction(longValues.toString()))
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("stays stable when every value is absent")
        void staysStableWhenEveryValueIsAbsent() {
            UserResponse empty = carrying(null);

            assertThat(empty.toString())
                    .contains("rowCount=0")
                    .contains("userId=" + REDACTED)
                    .contains("message=null")
                    .contains("navigationContext=null");
        }
    }

    @Nested
    @DisplayName("each row withholds its own values")
    class RowDiagnosticRendering {

        @Test
        @DisplayName("withholds the identifier, both name parts and the user type")
        void withholdsTheIdentityValues() {
            String rendered = row().toString();

            assertThat(rendered)
                    .doesNotContain(ROW_USER_ID)
                    .doesNotContain(ROW_FIRST_NAME)
                    .doesNotContain(ROW_LAST_NAME);
            assertThat(rendered)
                    .contains("userId=" + REDACTED)
                    .contains("firstName=" + REDACTED)
                    .contains("lastName=" + REDACTED)
                    .contains("userType=" + REDACTED);
        }

        @Test
        @DisplayName("retains the selector, which is an operator keystroke and not an attribute of "
                + "the person")
        void retainsTheSelector() {
            assertThat(row().toString()).contains("selector=S");
        }

        @Test
        @DisplayName("withholds its values when rendered directly, not only through the response")
        void withholdsWhenRenderedDirectly() {
            UserResponse response = carrying(List.of(row()));

            assertThat(response.rows().get(0).toString()).doesNotContain(ROW_USER_ID);
        }

        @Test
        @DisplayName("names the type and closes the rendering")
        void namesTheTypeAndClosesTheRendering() {
            assertThat(row().toString()).startsWith("UserRow[").endsWith("]");
        }

        @Test
        @DisplayName("substitutes a fixed placeholder that never varies with the value it hides")
        void substitutesAFixedPlaceholder() {
            UserResponse.UserRow shortValues = new UserResponse.UserRow("S", "A", "B", "C", "D");
            UserResponse.UserRow longValues =
                    new UserResponse.UserRow("S", "ROWUSR01", "JANE", "MacDONALD", "U");

            assertThat(occurrencesOfRedaction(shortValues.toString()))
                    .isEqualTo(occurrencesOfRedaction(longValues.toString()))
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("stays stable when every value is absent")
        void staysStableWhenEveryValueIsAbsent() {
            UserResponse.UserRow empty =
                    new UserResponse.UserRow(null, null, null, null, null);

            assertThat(empty.toString())
                    .contains("selector=null")
                    .contains("userId=" + REDACTED);
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("publishes every component under its own name")
        void publishesEveryComponentUnderItsOwnName() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.fieldNames()).toIterable()
                    .containsExactlyInAnyOrderElementsOf(COMPONENTS_IN_ORDER);
        }

        @Test
        @DisplayName("uses the canonical spellings shared across the package")
        void usesTheCanonicalSpellings() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.has("focusScreenFieldId")).isTrue();
            assertThat(payload.has("nextRoute")).isTrue();
            assertThat(payload.has("title01")).isTrue();
            assertThat(payload.has("title02")).isTrue();
            assertThat(payload.has("pageMetadata")).isTrue();
            assertThat(payload.has("navigationContext")).isTrue();
            assertThat(payload.has("fieldToFocus")).isFalse();
            assertThat(payload.has("route")).isFalse();
            assertThat(payload.has("navigation")).isFalse();
        }

        @Test
        @DisplayName("emits each row with its five named components")
        void emitsEachRowWithItsFiveComponents() throws JsonProcessingException {
            JsonNode rows = payloadOf(populated()).get("rows");

            assertThat(rows.isArray()).isTrue();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("selector", "userId", "firstName", "lastName",
                            "userType");
            assertThat(rows.get(0).get("userId").asText()).isEqualTo(ROW_USER_ID);
        }

        @Test
        @DisplayName("emits the identifier and the user type as text, so a leading zero and an "
                + "unexpected character both survive")
        void emitsIdentifiersAsText() throws JsonProcessingException {
            UserResponse response = new UserResponse(null, null, "00000001", null, null, "X", null,
                    null, null, null, null, null, null, null, false, false, null, null, null);

            JsonNode payload = payloadOf(response);

            assertThat(payload.get("userId").isTextual()).isTrue();
            assertThat(payload.get("userId").asText()).isEqualTo("00000001");
            assertThat(payload.get("userType").asText()).isEqualTo("X");
        }

        @Test
        @DisplayName("omits an absent component rather than emitting a null, while still emitting "
                + "both outcome flags")
        void omitsAnAbsentComponent() throws JsonProcessingException {
            JsonNode payload = payloadOf(cleared());

            assertThat(payload.has("userId")).isFalse();
            assertThat(payload.has("message")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("actionSucceeded").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("emits an empty collection rather than omitting it, because the empty list is "
                + "the absence representation")
        void emitsAnEmptyCollectionRatherThanOmittingIt() throws JsonProcessingException {
            JsonNode payload = payloadOf(cleared());

            assertThat(payload.get("rows").isArray()).isTrue();
            assertThat(payload.get("rows")).isEmpty();
            assertThat(payload.get("fieldErrors").isArray()).isTrue();
            assertThat(payload.get("fieldErrors")).isEmpty();
        }

        @Test
        @DisplayName("never emits a credential, because this contract declares none")
        void neverEmitsACredential() throws JsonProcessingException {
            assertThat(componentNames())
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                            .contains("password"));
            assertThat(moduleEquivalentMapper().writeValueAsString(populated()))
                    .doesNotContain("password");
        }
    }

    @Nested
    @DisplayName("operator diagnostics reproduce the legacy message text")
    class OperatorDiagnosticsReproduceLegacyText {

        @Test
        @DisplayName("reproduces the list selection and paging messages of COUSR00C")
        void reproducesTheListMessages() {
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid values are U and D");
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
        @DisplayName("reproduces the add validation and outcome messages of COUSR01C")
        void reproducesTheAddMessages() {
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
            assertThat(UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST)
                    .isEqualTo("User ID already exist...");
            assertThat(UserResponse.MSG_ADD_UNABLE_TO_ADD_USER)
                    .isEqualTo("Unable to Add User...");
        }

        @Test
        @DisplayName("reproduces the update messages of COUSR02C")
        void reproducesTheUpdateMessages() {
            assertThat(UserResponse.MSG_UPDATE_NO_CHANGE)
                    .isEqualTo("Please modify to update ...");
            assertThat(UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND)
                    .isEqualTo("User ID NOT found...");
            assertThat(UserResponse.MSG_UPDATE_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo("Unable to lookup User...");
            assertThat(UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER)
                    .isEqualTo("Unable to Update User...");
        }

        @Test
        @DisplayName("reproduces the delete messages of COUSR03C")
        void reproducesTheDeleteMessages() {
            assertThat(UserResponse.MSG_DELETE_USER_ID_EMPTY)
                    .isEqualTo("User ID can NOT be empty...");
            assertThat(UserResponse.MSG_DELETE_USER_ID_NOT_FOUND)
                    .isEqualTo("User ID NOT found...");
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_LOOKUP_USER)
                    .isEqualTo("Unable to lookup User...");
            assertThat(UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER)
                    .isEqualTo("Unable to Update User...");
        }

        @Test
        @DisplayName("keeps each outcome message split into the prefix and suffix the legacy "
                + "assembled around the identifier")
        void keepsOutcomeMessagesSplit() {
            assertThat(UserResponse.MSG_ADD_SUCCESS_PREFIX).isEqualTo("User ");
            assertThat(UserResponse.MSG_ADD_SUCCESS_SUFFIX).isEqualTo(" has been added ...");
            assertThat(UserResponse.MSG_UPDATE_SUCCESS_SUFFIX).isEqualTo(" has been updated ...");
            assertThat(UserResponse.MSG_DELETE_SUCCESS_SUFFIX).isEqualTo(" has been deleted ...");
        }

        @Test
        @DisplayName("keeps every message inside the width the message field declares")
        void keepsEveryMessageInsideTheDeclaredWidth() {
            List<String> messages = List.of(UserResponse.MSG_LIST_INVALID_SELECTION,
                    UserResponse.MSG_LIST_ALREADY_AT_TOP, UserResponse.MSG_LIST_ALREADY_AT_BOTTOM,
                    UserResponse.MSG_LIST_AT_TOP, UserResponse.MSG_LIST_REACHED_BOTTOM,
                    UserResponse.MSG_LIST_REACHED_TOP, UserResponse.MSG_LIST_UNABLE_TO_LOOKUP_USER,
                    UserResponse.MSG_ADD_FIRST_NAME_EMPTY, UserResponse.MSG_ADD_LAST_NAME_EMPTY,
                    UserResponse.MSG_ADD_USER_ID_EMPTY,
                    UserResponse.MSG_ADD_CREDENTIAL_FIELD_EMPTY,
                    UserResponse.MSG_ADD_USER_TYPE_EMPTY,
                    UserResponse.MSG_ADD_USER_ID_ALREADY_EXIST,
                    UserResponse.MSG_ADD_UNABLE_TO_ADD_USER, UserResponse.MSG_UPDATE_NO_CHANGE,
                    UserResponse.MSG_UPDATE_USER_ID_NOT_FOUND,
                    UserResponse.MSG_UPDATE_UNABLE_TO_UPDATE_USER,
                    UserResponse.MSG_DELETE_USER_ID_NOT_FOUND,
                    UserResponse.MSG_DELETE_UNABLE_TO_UPDATE_USER);

            for (String message : messages) {
                assertThat(message.length())
                        .describedAs("width of message '%s'", message)
                        .isLessThanOrEqualTo(UserResponse.MESSAGE_LENGTH);
            }
        }

        @Test
        @DisplayName("carries a legacy message through the component without altering a byte")
        void carriesALegacyMessageThroughUnaltered() {
            UserResponse response = new UserResponse(null, null, null, null, null, null, null, null,
                    null, null, null, null, UserResponse.MSG_UPDATE_NO_CHANGE, null, false, false,
                    null, null, null);

            assertThat(response.message()).isEqualTo("Please modify to update ...");
            assertThat(violationsOf(response)).isEmpty();
        }
    }
}
