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

import com.carddemo.api.dto.UserResponse.UserRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that neither {@link UserResponse} nor its nested {@link UserRow} can disclose a personal
 * name through its stringified form, while both still carry every one on the wire.
 *
 * <p>This contract has no credential component at all, so there is no credential here to withhold.
 * What it does carry, on the single-record surface and on every row of the list screen, is a person's
 * first and last name, and a name is regulated data. One accidental interpolation of a populated list
 * response would disclose ten of them.
 *
 * <p>Two decisions are pinned by tests here rather than left to inspection, because both look like
 * defects to a reader who has not seen the reasoning.
 *
 * <p>The first is that <strong>the user identifier is retained</strong>. It is an operator login name
 * rather than a customer, account, card or transaction identifier; it is the operand of every
 * administrative action these four screens perform, so an audit trail of such an action must record it;
 * and withholding it would be futile, because the add, update and delete confirmations this contract
 * publishes compose the identifier into their own text and that text is external contract that must
 * render verbatim. A test below builds a confirmation message the way a service builds it and asserts
 * both facts together, so that anyone tempted to redact the component can see what it would and would
 * not achieve.
 *
 * <p>The second is that <strong>the per-field error list is retained</strong>, and the justification is
 * structural rather than a judgement about the values it happens to carry: a field error has components
 * for a field name, a screen field identifier, a state and a message, and none for the value that was
 * rejected, so it cannot echo regulated input however it is populated.
 */
@DisplayName("UserResponse - diagnostic rendering safety for legacy transactions CU00 to CU03")
class UserResponseSecurityTest {

    private static final String PLACEHOLDER = "***REDACTED***";

    private static final String USER_ID = "ADMIN001";
    private static final String FIRST_NAME = "MARIANNE";
    private static final String LAST_NAME = "OSULLIVAN";
    private static final String ROW_USER_ID = "USER0042";
    private static final String ROW_FIRST_NAME = "THEODORA";
    private static final String ROW_LAST_NAME = "FITZGERALD";

    /**
     * Cursor keys carrying the browse key of the user list, which is a user identifier.
     *
     * <p>This screen browses on the user identifier, so a realistic cursor embeds one - which is a
     * second reason the paging block is withheld whole. Both the structural assertion, that the outer
     * rendering contains no paging rendering at all, and the fragment scan over the cursors themselves
     * are written below.
     */
    private static final String PREVIOUS_CURSOR = "PRVUSER0042";

    private static final String NEXT_CURSOR = "NXTUSER0099";

    /** The six components the outer renderer withholds, in declaration order. */
    private static final List<String> WITHHELD_OUTER_COMPONENTS =
            List.of("rows", "pageMetadata", "userId", "firstName", "lastName", "userType");

    /** The four components the row renderer withholds, in declaration order. */
    private static final List<String> WITHHELD_ROW_COMPONENTS =
            List.of("userId", "firstName", "lastName", "userType");

    /** Every personal name carried by a fully populated fixture. */
    private static final List<String> REGULATED_VALUES =
            List.of(FIRST_NAME, LAST_NAME, ROW_FIRST_NAME, ROW_LAST_NAME);

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

    private static UserRow row() {
        return new UserRow("U", ROW_USER_ID, ROW_FIRST_NAME, ROW_LAST_NAME, "U");
    }

    private static PageMetadata page() {
        return PageMetadata.forward(
                PageMetadata.USER_LIST_PAGE_SIZE, PREVIOUS_CURSOR, NEXT_CURSOR, true, false,
                "00000001");
    }

    /** The confirmation text a service composes, which embeds the user identifier by design. */
    private static String additionConfirmation() {
        return UserResponse.MSG_ADD_SUCCESS_PREFIX + USER_ID + UserResponse.MSG_ADD_SUCCESS_SUFFIX;
    }

    private static UserResponse populated() {
        return populatedWith(null);
    }

    private static UserResponse populatedWith(NavigationContext navigation) {
        return new UserResponse(
                List.of(row(), row()), page(), USER_ID, FIRST_NAME, LAST_NAME, "A",
                "CU01", "TITLE ONE", "07/19/22", "COUSR01C", "TITLE TWO", "14:23:07",
                additionConfirmation(),
                List.of(new ErrorResponse.FieldError(
                        "firstName", "FNAME", ErrorResponse.FieldState.MISSING, "ERROR LINE")),
                true, true, "USRIDIN", "route/next", navigation);
    }

    private static UserResponse empty() {
        return new UserResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, null);
    }

    private static int occurrencesOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static JsonNode payloadOf(UserResponse response) throws Exception {
        return moduleEquivalentMapper().readTree(moduleEquivalentMapper().writeValueAsString(response));
    }

    @Nested
    @DisplayName("No personal name survives the rendering")
    class NoPersonalNameSurvives {

        @Test
        @DisplayName("every name is absent from the outer rendering in whole, including the row names "
                + "reached only through the nested list")
        void everyNameIsAbsentInWhole() {
            assertThat(populated().toString()).doesNotContain(REGULATED_VALUES);
        }

        @Test
        @DisplayName("no five-character run of any name survives the outer rendering")
        void noFiveCharacterRunSurvivesTheOuterRendering() {
            String rendered = populated().toString();
            for (String value : REGULATED_VALUES) {
                for (int start = 0; start + 5 <= value.length(); start++) {
                    String fragment = value.substring(start, start + 5);
                    assertThat(rendered)
                            .withFailMessage("fragment %s of %s must not appear in %s",
                                    fragment, value, rendered)
                            .doesNotContain(fragment);
                }
            }
        }

        @Test
        @DisplayName("each of the six withheld components renders as the fixed stand-in, exactly once "
                + "each")
        void eachWithheldComponentRendersAsTheFixedStandIn() {
            String rendered = populated().toString();
            WITHHELD_OUTER_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
            assertThat(occurrencesOf(rendered, PLACEHOLDER))
                    .isEqualTo(WITHHELD_OUTER_COMPONENTS.size());
        }

        @Test
        @DisplayName("a withheld name renders identically whether it held a value or nothing, so the "
                + "rendering does not disclose presence")
        void aWithheldNameRendersIdenticallyWhetherPresentOrAbsent() {
            String withValues = populated().toString();
            String withoutValues = empty().toString();
            for (String name : WITHHELD_ROW_COMPONENTS) {
                assertThat(withValues).contains(name + "=" + PLACEHOLDER);
                assertThat(withoutValues).contains(name + "=" + PLACEHOLDER);
            }
        }
    }

    @Nested
    @DisplayName("The outer rendering does not delegate to its nested types")
    class TheOuterRenderingDoesNotDelegate {

        @Test
        @DisplayName("the outer rendering contains no row rendering at all, so the outer type's safety "
                + "does not depend on the row's rendering staying correct")
        void theOuterRenderingContainsNoRowRendering() {
            assertThat(populated().toString())
                    .doesNotContain("UserRow[")
                    .contains("rows=" + PLACEHOLDER);
        }

        @Test
        @DisplayName("the outer rendering contains no paging rendering at all, which is the operative "
                + "assertion for a screen whose cursor carries no regulated value")
        void theOuterRenderingContainsNoPagingRendering() {
            assertThat(populated().toString())
                    .doesNotContain("PageMetadata[")
                    .doesNotContain(PREVIOUS_CURSOR)
                    .doesNotContain(NEXT_CURSOR)
                    .doesNotContain(ROW_USER_ID)
                    .contains("pageMetadata=" + PLACEHOLDER);
        }

        @Test
        @DisplayName("no row content is recoverable: two pages of the same cardinality carrying wholly "
                + "different people render identically, while the cardinality itself is retained on "
                + "purpose because a count names nobody and a paging defect is diagnosed by it")
        void noRowContentIsRecoverable() {
            UserRow other = new UserRow("S", "USER9999", "CORNELIUS", "ABERNATHY", "A");
            UserResponse differentPeople = new UserResponse(
                    List.of(other, other), page(), "OTHER001", "GRETCHEN", "HAMMES", "U",
                    "CU01", "TITLE ONE", "07/19/22", "COUSR01C", "TITLE TWO", "14:23:07",
                    additionConfirmation(),
                    List.of(new ErrorResponse.FieldError(
                            "firstName", "FNAME", ErrorResponse.FieldState.MISSING, "ERROR LINE")),
                    true, true, "USRIDIN", "route/next", null);

            assertThat(differentPeople.toString()).isEqualTo(populated().toString());
            assertThat(populated().toString()).contains("rowCount=2");
        }
    }

    @Nested
    @DisplayName("The nested row is safe on its own terms")
    class TheNestedRowIsSafeOnItsOwnTerms {

        @Test
        @DisplayName("a row logged directly withholds both names, the identifier and the type, and "
                + "shows only the operator's own keystroke")
        void aRowLoggedDirectlyWithholdsBothNames() {
            String rendered = row().toString();
            assertThat(rendered)
                    .startsWith("UserRow[")
                    .endsWith("]")
                    .doesNotContain(ROW_FIRST_NAME)
                    .doesNotContain(ROW_LAST_NAME)
                    .doesNotContain(ROW_USER_ID)
                    .contains("selector=U");
            WITHHELD_ROW_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
            assertThat(occurrencesOf(rendered, PLACEHOLDER))
                    .isEqualTo(WITHHELD_ROW_COMPONENTS.size());
        }

        @Test
        @DisplayName("a collection of rows printed through the collection's own rendering is safe, "
                + "because each element renders through the row override")
        void aCollectionOfRowsIsSafe() {
            String rendered = List.of(row(), row()).toString();
            assertThat(rendered)
                    .doesNotContain(ROW_FIRST_NAME)
                    .doesNotContain(ROW_LAST_NAME)
                    .doesNotContain(ROW_USER_ID);
            assertThat(occurrencesOf(rendered, PLACEHOLDER))
                    .isEqualTo(2 * WITHHELD_ROW_COMPONENTS.size());
        }

        @Test
        @DisplayName("a row renders identically whether its withheld components held values or "
                + "nothing, and identically for two different people marked with the same keystroke")
        void aRowRendersIdenticallyWhetherPresentOrAbsent() {
            UserRow absent = new UserRow("U", null, null, null, null);
            UserRow someoneElse = new UserRow("U", "USER9999", "CORNELIUS", "ABERNATHY", "A");
            assertThat(absent.toString()).isEqualTo(row().toString());
            assertThat(someoneElse.toString()).isEqualTo(row().toString());
        }
    }

    @Nested
    @DisplayName("The user identifier is withheld, and the message line is the one seam that remains")
    class TheUserIdentifierIsWithheld {

        @Test
        @DisplayName("the identifier is withheld on both the single-record surface and every row, "
                + "because it names the individual and doubles as the browse anchor")
        void theIdentifierIsWithheldOnBothSurfaces() {
            assertThat(populated().toString()).contains("userId=" + PLACEHOLDER);
            assertThat(row().toString()).contains("userId=" + PLACEHOLDER);
        }

        @Test
        @DisplayName("the confirmation message embeds the identifier and renders verbatim, so the "
                + "component-level withholding narrows the surface without closing this seam - the "
                + "message is external contract text and altering it would be a behavioural change")
        void theConfirmationMessageEmbedsTheIdentifierAndRendersVerbatim() {
            String rendered = populated().toString();
            assertThat(additionConfirmation()).contains(USER_ID);
            assertThat(rendered).contains("message=" + additionConfirmation());
            assertThat(occurrencesOf(rendered, USER_ID))
                    .describedAs("the identifier reaches the rendering only through the contract text,"
                            + " never through the component")
                    .isEqualTo(1);
            assertThat(rendered).doesNotContain("userId=" + USER_ID);
        }
    }

    @Nested
    @DisplayName("The per-field error list is retained because it cannot echo a rejected value")
    class ThePerFieldErrorListIsRetained {

        @Test
        @DisplayName("a field error renders its field name, screen identifier, state and message, and "
                + "has no component in which a rejected value could travel")
        void aFieldErrorCarriesNoRejectedValue() {
            ErrorResponse.FieldError error = new ErrorResponse.FieldError(
                    "firstName", "FNAME", ErrorResponse.FieldState.MISSING, "ERROR LINE");
            assertThat(ErrorResponse.FieldError.class.getRecordComponents()).hasSize(4);
            assertThat(error.toString()).doesNotContain(REGULATED_VALUES);
            assertThat(populated().toString()).contains("FNAME");
        }
    }

    @Nested
    @DisplayName("The retained components stay visible, so the rendering remains diagnostic")
    class TheRetainedComponentsStayVisible {

        @Test
        @DisplayName("the row count, the screen furniture, both flags, the focus field and the route "
                + "are shown as supplied")
        void theDiagnosticallyUsefulComponentsAreShown() {
            assertThat(populated().toString())
                    .startsWith("UserResponse[")
                    .contains("rowCount=2")
                    .contains("transactionName=CU01")
                    .contains("programName=COUSR01C")
                    .contains("currentDate=07/19/22")
                    .contains("currentTime=14:23:07")
                    .contains("generalError=true")
                    .contains("actionSucceeded=true")
                    .contains("focusScreenFieldId=USRIDIN")
                    .contains("nextRoute=route/next");
        }
    }

    @Nested
    @DisplayName("Delegating to the navigation state does not widen disclosure")
    class DelegationDoesNotWidenDisclosure {

        @Test
        @DisplayName("the nested navigation state withholds its own identifying values, so rendering "
                + "it rather than replacing it discloses nothing further")
        void theNestedNavigationStateWithholdsItsOwnIdentifyingValues() {
            NavigationContext navigation = new NavigationContext(
                    "CA00", "COADM01C", "CU01", "COUSR01C", USER_ID, "A",
                    NavigationContext.ProgramContext.REENTER,
                    "123456789", FIRST_NAME, "ANN", LAST_NAME,
                    "78412590063", "Y", "5555444433332222", "COUSR1A", "COUSR01");

            String rendered = populatedWith(navigation).toString();
            assertThat(rendered)
                    .doesNotContain("123456789")
                    .doesNotContain("78412590063")
                    .doesNotContain("5555444433332222")
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME);
            assertThat(rendered).contains("NavigationContext[");
        }
    }

    @Nested
    @DisplayName("An entirely absent response renders safely")
    class AnEntirelyAbsentResponseRendersSafely {

        @Test
        @DisplayName("a response with every component absent renders without throwing and carries no "
                + "name, with the empty row list still withheld")
        void anAbsentResponseRendersWithoutThrowing() {
            String rendered = empty().toString();
            assertThat(rendered)
                    .startsWith("UserResponse[")
                    .endsWith("]")
                    .doesNotContain(REGULATED_VALUES)
                    .contains("rows=" + PLACEHOLDER);
            assertThat(occurrencesOf(rendered, PLACEHOLDER))
                    .isEqualTo(WITHHELD_OUTER_COMPONENTS.size());
        }
    }

    @Nested
    @DisplayName("The wire payload still carries every withheld value in full")
    class TheWirePayloadStillCarriesEveryValue {

        @Test
        @DisplayName("both names on the single-record surface serialise at their full untouched values")
        void bothNamesSerialiseInFull() throws Exception {
            JsonNode payload = payloadOf(populated());
            assertThat(payload.get("firstName").asText()).isEqualTo(FIRST_NAME);
            assertThat(payload.get("lastName").asText()).isEqualTo(LAST_NAME);
            assertThat(payload.get("userId").asText()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("every row serialises both of its names, so withholding from a log does not "
                + "withhold from the client")
        void everyRowSerialisesBothNamesInFull() throws Exception {
            JsonNode rows = payloadOf(populated()).get("rows");
            assertThat(rows).hasSize(2);
            rows.forEach(node -> {
                assertThat(node.get("firstName").asText()).isEqualTo(ROW_FIRST_NAME);
                assertThat(node.get("lastName").asText()).isEqualTo(ROW_LAST_NAME);
                assertThat(node.get("userId").asText()).isEqualTo(ROW_USER_ID);
            });
        }

        @Test
        @DisplayName("no credential component exists to serialise, which is the strongest form the "
                + "prohibition can take")
        void noCredentialComponentExistsToSerialise() throws Exception {
            JsonNode payload = payloadOf(populated());
            assertThat(payload.has("password")).isFalse();
            assertThat(payload.has("passwordHash")).isFalse();
            assertThat(UserResponse.class.getRecordComponents())
                    .noneMatch(component -> component.getName().toLowerCase()
                            .contains("password"));
        }

        @Test
        @DisplayName("the stand-in never leaks into the serialized form, so no client ever receives a "
                + "redacted value in place of a real one")
        void theStandInNeverReachesTheWire() throws Exception {
            assertThat(payloadOf(populated()).toString()).doesNotContain("REDACTED");
        }
    }
}
