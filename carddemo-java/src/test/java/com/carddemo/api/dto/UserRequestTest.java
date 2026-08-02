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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link UserRequest}, the single request body shared by the four user-administration
 * transactions {@code CU00}, {@code CU01}, {@code CU02} and {@code CU03}.
 *
 * <p>A pure unit test: no application context, no connection, no container. Where the wire shape is
 * what is under test it serialises and deserialises with a locally built mapper configured to match
 * the settings the module declares in {@code application.yml}.
 *
 * <p>Five properties dominate what is checked here, and each of the five was wrong.
 *
 * <p>The first is credential disclosure. An administrator's chosen password crossed this boundary in
 * a component that Jackson would happily write back out again, so any body that was deserialized and
 * re-serialized - a validation echo, a debug endpoint, a retry envelope - carried the plaintext
 * onward. The tests below prove the credential still binds inbound and can no longer leave.
 *
 * <p>The second is over-posting. One record body serves four operations, so every component the
 * union declares was bindable on every operation, and a caller submitting a list page could carry a
 * password and a user type alongside it. The tests prove each operation now refuses the components
 * that belong to the other three, and - just as importantly - that the plain unqualified validation
 * still accepts a fully populated body, so nothing was made unusable in the process.
 *
 * <p>The third is trust in server-owned state. The displayed page number is computed entirely by the
 * program from a counter it retains itself and is never read back from the screen, so accepting one
 * from a caller could only ever mislead. The two boundary anchors are the opposite case: they
 * genuinely are the browse start key, so they must stay bindable, and a test pins that asymmetry so a
 * later tidy-up cannot collapse the two into one rule.
 *
 * <p>The fourth is cardinality. The list screen declares ten rows; the selection collection accepted
 * any number.
 *
 * <p>The fifth is diagnostic disclosure. The generated rendering printed two user identifiers, a
 * first and last name, a user type and the credential itself - a complete identity with its
 * authorization level attached.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("UserRequest :: shared request contract of legacy transactions CU00 to CU03")
class UserRequestTest {

    /** The twelve record components in constructor order. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "userId", "searchUserId", "firstName", "lastName", "password", "userType",
            "rowSelections", "displayedPageNumber", "firstUserIdOnPage", "lastUserIdOnPage",
            "keyAction", "navigationContext");

    /** The measured map width of each singly-bounded string component, in the same order. */
    private static final List<String> BOUNDED_STRINGS = List.of(
            "userId", "searchUserId", "firstName", "lastName", "password", "userType",
            "displayedPageNumber", "firstUserIdOnPage", "lastUserIdOnPage");

    /** The measured widths matching {@link #BOUNDED_STRINGS}. */
    private static final List<Integer> BOUNDED_WIDTHS = List.of(8, 8, 20, 20, 8, 1, 8, 8, 8);

    private static final String USER_ID = "NEWUSR01";
    private static final String SEARCH_USER_ID = "ADMINUSR";
    private static final String FIRST_NAME = "MARY ANN";
    private static final String LAST_NAME = "o'HARA-smith";
    private static final String PASSWORD = "PASSWORD";
    private static final String USER_TYPE = "A";
    private static final String PAGE_NUMBER = "00000003";
    private static final String FIRST_ANCHOR = "USER0001";
    private static final String LAST_ANCHOR = "USER0010";
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CU00", "COUSR00C", "CU02", "COUSR02C", SEARCH_USER_ID, "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                "00000000011", "Y", "0000000000000011", "COUSR0A", "COUSR00");
    }

    /** A body carrying every component, which is what an inventory assertion needs. */
    private static UserRequest populated() {
        return new UserRequest(USER_ID, SEARCH_USER_ID, FIRST_NAME, LAST_NAME, PASSWORD, USER_TYPE,
                List.of("S"), PAGE_NUMBER, FIRST_ANCHOR, LAST_ANCHOR, KeyAction.PFK05,
                navigation());
    }

    /** A body shaped as the list transaction submits one. */
    private static UserRequest listSubmission() {
        return new UserRequest(null, SEARCH_USER_ID, null, null, null, null, List.of("S"),
                PAGE_NUMBER, FIRST_ANCHOR, LAST_ANCHOR, KeyAction.PFK08, navigation());
    }

    /** A body shaped as the add and update transactions submit one. */
    private static UserRequest singleUserSubmission() {
        return new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, PASSWORD, USER_TYPE, null,
                null, null, null, KeyAction.ENTER, navigation());
    }

    /** A body shaped as the delete transaction submits one: an identifier and nothing else. */
    private static UserRequest deleteSubmission() {
        return new UserRequest(USER_ID, null, null, null, null, null, null, null, null, null,
                KeyAction.PFK05, navigation());
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

    private static JsonNode payloadOf(UserRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    private static List<String> componentNames() {
        return Arrays.stream(UserRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotations a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component. Neither the length
     * constraint, the nullity constraint, the cascade marker, the serialization directive nor the
     * published-schema directive declares {@code RECORD_COMPONENT} among its targets, so the compiler
     * propagates each to the field, the accessor and the constructor parameter but records none
     * against the component itself: asking the component yields an empty array for every component
     * here, and an assertion phrased that way would pass without testing anything.
     *
     * @param name the component name
     * @return the annotations present on the backing field
     */
    private static Annotation[] annotationsOn(String name) {
        try {
            return UserRequest.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return UserRequest.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    /**
     * Reads every constraint of one repeatable type from a component.
     *
     * <p>Needed because a component may legitimately carry the same constraint twice under different
     * validation groups, and a repeated constraint is held in a container annotation: the single-value
     * lookup above then finds nothing at all rather than finding one of the two. This accessor reads
     * both spellings, so an assertion made through it stays true whether the constraint is repeated or
     * not.
     *
     * @param name the component name
     * @param type the repeatable constraint type to read
     * @param <A>  the constraint type
     * @return every constraint of that type declared on the component, in declaration order
     */
    private static <A extends Annotation> A[] annotationsOn(String name, Class<A> type) {
        try {
            return UserRequest.class.getDeclaredField(name).getAnnotationsByType(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, excluding any delegated segment.
     *
     * <p>Written as an excision rather than a truncation so it does not depend on the nested
     * navigation state being the final component. Nothing is delegated in the current rendering - the
     * navigation state is withheld outright - but a rendering that later begins delegating would
     * silently invalidate a truncating helper, and this form would not notice.
     *
     * @param request the request whose rendering is being examined
     * @return the rendering with any delegated navigation segment replaced
     */
    private static String ownRendering(UserRequest request) {
        String rendered = request.toString();
        return (request.navigationContext() == null)
                ? rendered
                : rendered.replace(request.navigationContext().toString(), "<delegated>");
    }

    private static Set<ConstraintViolation<UserRequest>> violationsOf(UserRequest request,
                                                                     Class<?>... groups) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request, groups);
        }
    }

    private static List<String> violationPathsOf(UserRequest request, Class<?>... groups) {
        return violationsOf(request, groups).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .toList();
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the twelve components in constructor order")
        void declaresTwelveComponents() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_ORDER)
                    .hasSize(12);
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
            assertThat(UserRequest.USER_ID_LENGTH).isEqualTo(8);
            assertThat(UserRequest.NAME_PART_LENGTH).isEqualTo(20);
            assertThat(UserRequest.PASSWORD_LENGTH).isEqualTo(8);
            assertThat(UserRequest.USER_TYPE_LENGTH).isEqualTo(1);
            assertThat(UserRequest.ROW_SELECTION_LENGTH).isEqualTo(1);
            assertThat(UserRequest.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("carries the two identifiers, the attention key and the navigation state as "
                + "distinct components")
        void carriesTheTwoIdentifiersDistinctly() {
            UserRequest request = populated();

            assertThat(request.userId()).isEqualTo(USER_ID);
            assertThat(request.searchUserId()).isEqualTo(SEARCH_USER_ID);
            assertThat(request.userId()).isNotEqualTo(request.searchUserId());
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(request.navigationContext()).isNotNull();
        }

        @Test
        @DisplayName("carries every value byte for byte, trimming, padding and re-casing nothing")
        void carriesEveryValueByteForByte() {
            UserRequest request = new UserRequest("  usr  ", " srch ", "  Mary  ", " o'Hara ",
                    " pw ", " ", List.of(" "), " 0000003", "  anchor", "anchor  ", null, null);

            assertThat(request.userId()).isEqualTo("  usr  ");
            assertThat(request.searchUserId()).isEqualTo(" srch ");
            assertThat(request.firstName()).isEqualTo("  Mary  ");
            assertThat(request.lastName()).isEqualTo(" o'Hara ");
            assertThat(request.password()).isEqualTo(" pw ");
            assertThat(request.userType()).isEqualTo(" ");
            assertThat(request.displayedPageNumber()).isEqualTo(" 0000003");
            assertThat(request.firstUserIdOnPage()).isEqualTo("  anchor");
            assertThat(request.lastUserIdOnPage()).isEqualTo("anchor  ");
        }

        @Test
        @DisplayName("accepts a fully populated body under the plain unqualified validation")
        void acceptsAFullyPopulatedBodyUnderDefaultValidation() {
            assertThat(violationsOf(populated())).isEmpty();
        }
    }

    @Nested
    @DisplayName("the credential is accepted inbound and never emitted")
    class TheCredentialIsWriteOnly {

        @Test
        @DisplayName("withholds the credential from every serialized body")
        void withholdsTheCredentialFromEverySerializedBody() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.has("password")).isFalse();
            assertThat(moduleEquivalentMapper().writeValueAsString(populated()))
                    .doesNotContain(PASSWORD);
        }

        @Test
        @DisplayName("still binds a submitted credential, so the write-only rule did not break the "
                + "add and update operations")
        void stillBindsASubmittedCredential() throws JsonProcessingException {
            String body = "{\"userId\":\"" + USER_ID + "\",\"password\":\"" + PASSWORD + "\"}";

            UserRequest bound = moduleEquivalentMapper().readValue(body, UserRequest.class);

            assertThat(bound.password()).isEqualTo(PASSWORD);
            assertThat(bound.userId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("cannot round-trip a credential back onto the wire")
        void cannotRoundTripACredentialBackOntoTheWire() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            String body = "{\"userId\":\"" + USER_ID + "\",\"password\":\"" + PASSWORD + "\"}";

            UserRequest bound = mapper.readValue(body, UserRequest.class);
            String echoed = mapper.writeValueAsString(bound);

            assertThat(echoed).doesNotContain(PASSWORD);
            assertThat(echoed).contains(USER_ID);
        }

        @Test
        @DisplayName("declares the write-only access directive on the credential alone")
        void declaresTheWriteOnlyDirectiveOnTheCredentialAlone() {
            JsonProperty directive = annotationOn("password", JsonProperty.class);

            assertThat(directive).isNotNull();
            assertThat(directive.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);

            for (String component : BOUNDED_STRINGS) {
                if (component.equals("password") || component.equals("displayedPageNumber")) {
                    continue;
                }
                assertThat(annotationOn(component, JsonProperty.class))
                        .describedAs("component %s carries no access directive", component)
                        .isNull();
            }
        }

        @Test
        @DisplayName("publishes the credential as write-only in the API contract, with a password "
                + "format")
        void publishesTheCredentialAsWriteOnly() {
            Schema published = annotationOn("password", Schema.class);

            assertThat(published).isNotNull();
            assertThat(published.accessMode()).isEqualTo(Schema.AccessMode.WRITE_ONLY);
            assertThat(published.format()).isEqualTo("password");
            assertThat(published.description()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("each operation refuses the components belonging to the other three")
    class OperationScopedAbsence {

        @Test
        @DisplayName("declares the four operation markers as nested interfaces of this type")
        void declaresTheFourOperationMarkers() {
            assertThat(UserRequest.ListOperation.class.isInterface()).isTrue();
            assertThat(UserRequest.AddOperation.class.isInterface()).isTrue();
            assertThat(UserRequest.UpdateOperation.class.isInterface()).isTrue();
            assertThat(UserRequest.DeleteOperation.class.isInterface()).isTrue();

            assertThat(UserRequest.ListOperation.class.getEnclosingClass())
                    .isEqualTo(UserRequest.class);
            assertThat(UserRequest.DeleteOperation.class.getEnclosingClass())
                    .isEqualTo(UserRequest.class);
        }

        @Test
        @DisplayName("accepts a list submission under the list operation")
        void acceptsAListSubmission() {
            assertThat(violationsOf(listSubmission(), UserRequest.ListOperation.class)).isEmpty();
        }

        @Test
        @DisplayName("refuses single-user components on a list submission")
        void refusesSingleUserComponentsOnAListSubmission() {
            assertThat(violationPathsOf(populated(), UserRequest.ListOperation.class))
                    .containsExactly("firstName", "lastName", "password", "userId", "userType");
        }

        @Test
        @DisplayName("accepts an add submission under the add operation")
        void acceptsAnAddSubmission() {
            assertThat(violationsOf(singleUserSubmission(), UserRequest.AddOperation.class))
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses list components on an add submission")
        void refusesListComponentsOnAnAddSubmission() {
            assertThat(violationPathsOf(populated(), UserRequest.AddOperation.class))
                    .containsExactly("firstUserIdOnPage", "lastUserIdOnPage", "rowSelections",
                            "searchUserId");
        }

        @Test
        @DisplayName("accepts an update submission under the update operation")
        void acceptsAnUpdateSubmission() {
            assertThat(violationsOf(singleUserSubmission(), UserRequest.UpdateOperation.class))
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses list components on an update submission")
        void refusesListComponentsOnAnUpdateSubmission() {
            assertThat(violationPathsOf(populated(), UserRequest.UpdateOperation.class))
                    .containsExactly("firstUserIdOnPage", "lastUserIdOnPage", "rowSelections",
                            "searchUserId");
        }

        @Test
        @DisplayName("accepts a delete submission carrying only an identifier")
        void acceptsADeleteSubmission() {
            assertThat(violationsOf(deleteSubmission(), UserRequest.DeleteOperation.class))
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses the echoed display values and the credential on a delete submission, "
                + "because the delete program only ever writes them")
        void refusesEchoedValuesOnADeleteSubmission() {
            assertThat(violationPathsOf(populated(), UserRequest.DeleteOperation.class))
                    .containsExactly("firstName", "firstUserIdOnPage", "lastName",
                            "lastUserIdOnPage", "password", "rowSelections", "searchUserId",
                            "userType");
        }

        @Test
        @DisplayName("keeps the update operation strictly wider than the delete operation, because "
                + "only the update program reads the three name and type items")
        void keepsUpdateWiderThanDelete() {
            UserRequest namesAndType = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME,
                    PASSWORD, USER_TYPE, null, null, null, null, KeyAction.ENTER, null);

            assertThat(violationsOf(namesAndType, UserRequest.UpdateOperation.class)).isEmpty();
            assertThat(violationPathsOf(namesAndType, UserRequest.DeleteOperation.class))
                    .containsExactly("firstName", "lastName", "password", "userType");
        }

        @Test
        @DisplayName("leaves every group-scoped rule inert under the plain unqualified validation")
        void leavesEveryGroupScopedRuleInertByDefault() {
            assertThat(violationsOf(populated())).isEmpty();
            assertThat(violationsOf(listSubmission())).isEmpty();
            assertThat(violationsOf(singleUserSubmission())).isEmpty();
            assertThat(violationsOf(deleteSubmission())).isEmpty();
        }

        @Test
        @DisplayName("scopes the absence rule on the search identifier to the three single-user "
                + "operations")
        void scopesTheSearchIdentifierRule() {
            Null rule = annotationOn("searchUserId", Null.class);

            assertThat(rule).isNotNull();
            assertThat(rule.groups()).containsExactlyInAnyOrder(UserRequest.AddOperation.class,
                    UserRequest.UpdateOperation.class, UserRequest.DeleteOperation.class);
        }

        @Test
        @DisplayName("asserts emptiness rather than absence on the selection collection, because "
                + "the canonical constructor normalises a null collection to an empty one")
        void assertsEmptinessRatherThanAbsenceOnTheSelections() {
            assertThat(annotationOn("rowSelections", Null.class)).isNull();

            Size[] rules = annotationsOn("rowSelections", Size.class);
            assertThat(rules)
                    .describedAs("the operation-scoped emptiness rule and the arity bound both apply")
                    .hasSize(2);

            Size emptiness = Arrays.stream(rules)
                    .filter(rule -> rule.groups().length > 0)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no operation-scoped rule on rowSelections"));
            assertThat(emptiness.max()).isZero();
            assertThat(emptiness.groups()).containsExactlyInAnyOrder(UserRequest.AddOperation.class,
                    UserRequest.UpdateOperation.class, UserRequest.DeleteOperation.class);

            Size arity = Arrays.stream(rules)
                    .filter(rule -> rule.groups().length == 0)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no arity bound on rowSelections"));
            assertThat(arity.max())
                    .describedAs("the collection is bounded on every operation, not only on three")
                    .isEqualTo(UserRequest.ROW_SELECTION_COUNT);

            assertThat(new UserRequest(null, null, null, null, null, null, null, null, null, null,
                    null, null).rowSelections()).isEmpty();
        }
    }

    @Nested
    @DisplayName("server-owned paging state is not bindable, but the browse anchors are")
    class PagingStateBindability {

        @Test
        @DisplayName("ignores a submitted page number, because the program computes it from a "
                + "counter it retains itself")
        void ignoresASubmittedPageNumber() throws JsonProcessingException {
            String body = "{\"searchUserId\":\"" + SEARCH_USER_ID
                    + "\",\"displayedPageNumber\":\"00000009\"}";

            UserRequest bound = moduleEquivalentMapper().readValue(body, UserRequest.class);

            assertThat(bound.displayedPageNumber()).isNull();
            assertThat(bound.searchUserId()).isEqualTo(SEARCH_USER_ID);
        }

        @Test
        @DisplayName("still emits the page number outbound, so a client can echo what it was shown")
        void stillEmitsThePageNumberOutbound() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.has("displayedPageNumber")).isTrue();
            assertThat(payload.get("displayedPageNumber").asText()).isEqualTo(PAGE_NUMBER);
        }

        @Test
        @DisplayName("declares the read-only access directive on the page number alone")
        void declaresTheReadOnlyDirectiveOnThePageNumberAlone() {
            JsonProperty directive = annotationOn("displayedPageNumber", JsonProperty.class);

            assertThat(directive).isNotNull();
            assertThat(directive.access()).isEqualTo(JsonProperty.Access.READ_ONLY);
        }

        @Test
        @DisplayName("keeps both browse anchors bindable, because the backward and forward paging "
                + "paragraphs read them as the browse start key")
        void keepsBothBrowseAnchorsBindable() throws JsonProcessingException {
            String body = "{\"firstUserIdOnPage\":\"" + FIRST_ANCHOR
                    + "\",\"lastUserIdOnPage\":\"" + LAST_ANCHOR + "\"}";

            UserRequest bound = moduleEquivalentMapper().readValue(body, UserRequest.class);

            assertThat(bound.firstUserIdOnPage()).isEqualTo(FIRST_ANCHOR);
            assertThat(bound.lastUserIdOnPage()).isEqualTo(LAST_ANCHOR);
        }

        @Test
        @DisplayName("declares no access directive on either anchor, so neither is accidentally "
                + "swept up with the page number")
        void declaresNoAccessDirectiveOnEitherAnchor() {
            assertThat(annotationOn("firstUserIdOnPage", JsonProperty.class)).isNull();
            assertThat(annotationOn("lastUserIdOnPage", JsonProperty.class)).isNull();
        }

        @Test
        @DisplayName("carries no page size, window length or availability flag")
        void carriesNoPagingPolicy() {
            assertThat(componentNames())
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("pagesize"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("hasmore"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("total"));
        }
    }

    @Nested
    @DisplayName("the selection collection is capped at the screen's row count")
    class TheSelectionCollectionIsCapped {

        @Test
        @DisplayName("publishes the row count the list map and the program's table agree on")
        void publishesTheRowCount() {
            assertThat(UserRequest.ROW_COUNT).isEqualTo(10);
        }

        @Test
        @DisplayName("accepts a selection collection filled to the row count")
        void acceptsACollectionAtTheRowCount() {
            List<String> full = Collections.nCopies(UserRequest.ROW_COUNT, "S");

            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    full, null, null, null, null, null);

            assertThat(request.rowSelections()).hasSize(UserRequest.ROW_COUNT);
        }

        @Test
        @DisplayName("refuses a selection collection longer than the row count")
        void refusesACollectionLongerThanTheRowCount() {
            List<String> tooMany = Collections.nCopies(UserRequest.ROW_COUNT + 1, "S");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                            tooMany, null, null, null, null, null))
                    .withMessageContaining("rowSelections")
                    .withMessageContaining("at most 10")
                    .withMessageContaining("11");
        }

        @Test
        @DisplayName("accepts a shorter collection, so a partial page stays partial")
        void acceptsAShorterCollection() {
            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    List.of("S", "U"), null, null, null, null, null);

            assertThat(request.rowSelections()).containsExactly("S", "U");
        }

        @Test
        @DisplayName("preserves selection order and interprets no element")
        void preservesSelectionOrder() {
            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    List.of(" ", "u", "S", "x"), null, null, null, null, null);

            assertThat(request.rowSelections()).containsExactly(" ", "u", "S", "x");
        }

        @Test
        @DisplayName("freezes the collection and does not alias caller-owned state")
        void freezesTheCollection() {
            List<String> caller = new ArrayList<>(List.of("S"));
            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    caller, null, null, null, null, null);

            caller.add("U");

            assertThat(request.rowSelections()).containsExactly("S");
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> request.rowSelections().add("U"));
        }

        @Test
        @DisplayName("bounds every element at one character")
        void boundsEveryElementAtOneCharacter() {
            UserRequest overLong = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    List.of("SS"), null, null, null, null, null);

            assertThat(violationPathsOf(overLong)).containsExactly("rowSelections[0].<list element>");
        }
    }

    @Nested
    @DisplayName("nested navigation state is validated transitively")
    class NestedStateIsValidatedTransitively {

        @Test
        @DisplayName("declares the cascade marker on the navigation state")
        void declaresTheCascadeMarker() {
            assertThat(annotationsOn("navigationContext")).hasAtLeastOneElementOfType(Valid.class);
        }

        @Test
        @DisplayName("reports a bound the nested contract declares, which was previously unevaluated")
        void reportsANestedBound() {
            NavigationContext overLong = new NavigationContext("CU000", "COUSR00C", "CU02",
                    "COUSR02C", SEARCH_USER_ID, "A", NavigationContext.ProgramContext.REENTER,
                    "000000011", "MARY", "ANN", "SMITH", "00000000011", "Y",
                    "0000000000000011", "COUSR0A", "COUSR00");

            UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, PASSWORD,
                    USER_TYPE, null, null, null, null, KeyAction.ENTER, overLong);

            assertThat(violationPathsOf(request))
                    .containsExactly("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("accepts an absent navigation state, because the cascade is not a presence rule")
        void acceptsAnAbsentNavigationState() {
            UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, PASSWORD,
                    USER_TYPE, null, null, null, null, KeyAction.ENTER, null);

            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("accepts a well-formed navigation state")
        void acceptsAWellFormedNavigationState() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        /**
         * A body whose nested navigation state carries a five-character transaction identifier, one
         * over the four-character bound the nested contract declares.
         *
         * @return a request whose only defect is inside its navigation state
         */
        private UserRequest withOverLongNestedTransactionId() {
            NavigationContext overLong = new NavigationContext("CU000", "COUSR00C", "CU02",
                    "COUSR02C", SEARCH_USER_ID, "A", NavigationContext.ProgramContext.REENTER,
                    "000000011", "MARY", "ANN", "SMITH", "00000000011", "Y",
                    "0000000000000011", "COUSR0A", "COUSR00");

            return new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, PASSWORD, USER_TYPE, null,
                    null, null, null, KeyAction.ENTER, overLong);
        }

        @Test
        @DisplayName("does not evaluate the nested width bounds under a bare operation group, "
                + "because a cascade propagates the group it was invoked with")
        void doesNotEvaluateNestedBoundsUnderABareOperationGroup() {
            assertThat(violationsOf(withOverLongNestedTransactionId(),
                    UserRequest.UpdateOperation.class)).isEmpty();
        }

        @Test
        @DisplayName("evaluates both the operation-scoped absence rules and the nested width bounds "
                + "when the operation group is combined with the default group, which is how a "
                + "caller must request validation")
        void evaluatesBothWhenTheOperationGroupIsCombinedWithTheDefaultGroup() {
            List<String> paths = violationPathsOf(withOverLongNestedTransactionId(),
                    UserRequest.UpdateOperation.class, Default.class);

            assertThat(paths).containsExactly("navigationContext.fromTransactionId");

            assertThat(violationPathsOf(populated(), UserRequest.UpdateOperation.class,
                    Default.class))
                    .containsExactly("firstUserIdOnPage", "lastUserIdOnPage", "rowSelections",
                            "searchUserId");
        }
    }

    @Nested
    @DisplayName("the user-type vocabulary is shared and is not enforced here")
    class TheUserTypeVocabularyIsShared {

        @Test
        @DisplayName("publishes the two authoritative codes in the API contract")
        void publishesTheTwoAuthoritativeCodes() {
            Schema published = annotationOn("userType", Schema.class);

            assertThat(published).isNotNull();
            assertThat(published.description())
                    .contains("A for an")
                    .contains("U for a standard user")
                    .contains("COCOM01Y");
        }

        @Test
        @DisplayName("accepts both authoritative codes")
        void acceptsBothAuthoritativeCodes() {
            for (String code : List.of("A", "U")) {
                UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME,
                        PASSWORD, code, null, null, null, null, KeyAction.ENTER, null);

                assertThat(violationsOf(request))
                        .describedAs("code %s is accepted", code)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("still accepts an undeclared one-character code, because the legacy programs "
                + "test only that the item is non-blank and store whatever arrived")
        void stillAcceptsAnUndeclaredCode() {
            for (String code : List.of("X", "a", "9", " ")) {
                UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME,
                        PASSWORD, code, null, null, null, null, KeyAction.ENTER, null);

                assertThat(violationsOf(request))
                        .describedAs("code %s is tolerated", code)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("bounds the code by width, so a two-character value is still refused")
        void boundsTheCodeByWidth() {
            UserRequest request = new UserRequest(USER_ID, null, FIRST_NAME, LAST_NAME, PASSWORD,
                    "AU", null, null, null, null, KeyAction.ENTER, null);

            assertThat(violationPathsOf(request)).containsExactly("userType");
        }

        @Test
        @DisplayName("agrees with every other contract on the width of the code and the identifier")
        void agreesWithEveryOtherContract() {
            assertThat(UserRequest.USER_TYPE_LENGTH)
                    .isEqualTo(UserResponse.USER_TYPE_LENGTH)
                    .isEqualTo(NavigationContext.USER_TYPE_LENGTH)
                    .isEqualTo(SignOnResponse.USER_TYPE_LENGTH);
            assertThat(UserRequest.USER_ID_LENGTH)
                    .isEqualTo(UserResponse.USER_ID_LENGTH)
                    .isEqualTo(NavigationContext.USER_ID_LENGTH)
                    .isEqualTo(SignOnResponse.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("carries the code as raw text rather than as the domain enumeration")
        void carriesTheCodeAsRawText() throws NoSuchFieldException {
            assertThat(UserRequest.class.getDeclaredField("userType").getType())
                    .isEqualTo(String.class);
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds the credential")
        void withholdsTheCredential() {
            assertThat(ownRendering(populated())).doesNotContain(PASSWORD);
        }

        @Test
        @DisplayName("withholds both user identifiers and both browse anchors")
        void withholdsIdentifiersAndAnchors() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .doesNotContain(USER_ID)
                    .doesNotContain(SEARCH_USER_ID)
                    .doesNotContain(FIRST_ANCHOR)
                    .doesNotContain(LAST_ANCHOR);
        }

        @Test
        @DisplayName("withholds the two name parts and the user type")
        void withholdsNamesAndUserType() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME);
            assertThat(rendered).contains("userType=" + REDACTED);
        }

        @Test
        @DisplayName("labels every withheld component and substitutes one fixed placeholder")
        void labelsEveryWithheldComponent() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .contains("userId=" + REDACTED)
                    .contains("searchUserId=" + REDACTED)
                    .contains("firstName=" + REDACTED)
                    .contains("lastName=" + REDACTED)
                    .contains("password=" + REDACTED)
                    .contains("userType=" + REDACTED)
                    .contains("firstUserIdOnPage=" + REDACTED)
                    .contains("lastUserIdOnPage=" + REDACTED)
                    .contains("navigationContext=" + REDACTED);
        }

        @Test
        @DisplayName("retains the selection count rather than the selections")
        void retainsTheSelectionCountRatherThanTheSelections() {
            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    List.of("S", "U", "x"), PAGE_NUMBER, null, null, KeyAction.PFK08, null);

            assertThat(request.toString())
                    .contains("rowSelectionCount=3")
                    .doesNotContain("rowSelections=");
        }

        @Test
        @DisplayName("retains the page number and the attention key, which describe nobody")
        void retainsThePageNumberAndAttentionKey() {
            String rendered = ownRendering(populated());

            assertThat(rendered)
                    .contains("displayedPageNumber=" + PAGE_NUMBER)
                    .contains("keyAction=" + KeyAction.PFK05);
        }

        @Test
        @DisplayName("names the type and closes the rendering")
        void namesTheTypeAndClosesTheRendering() {
            assertThat(populated().toString())
                    .startsWith("UserRequest[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("withholds nothing that was absent, and stays stable when every value is null")
        void staysStableWhenEveryValueIsNull() {
            UserRequest empty = new UserRequest(null, null, null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(empty.toString())
                    .contains("rowSelectionCount=0")
                    .contains("displayedPageNumber=null")
                    .contains("keyAction=null")
                    .contains("userId=" + REDACTED);
        }

        @Test
        @DisplayName("substitutes a fixed placeholder that never varies with the value it hides")
        void substitutesAFixedPlaceholder() {
            UserRequest shortValues = new UserRequest("A", "B", "C", "D", "E", "F", null, null,
                    "G", "H", null, null);
            UserRequest longValues = new UserRequest("12345678", "87654321", "MARY ANN",
                    "o'HARA-smith", "PASSWORD", "A", null, null, "USER0001", "USER0010", null,
                    null);

            long shortCount = shortValues.toString().split(Pattern.quote(REDACTED),
                    -1).length - 1;
            long longCount = longValues.toString().split(Pattern.quote(REDACTED),
                    -1).length - 1;

            assertThat(shortCount).isEqualTo(longCount).isEqualTo(9);
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("publishes every emittable component under its own name")
        void publishesEveryEmittableComponentUnderItsOwnName() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "userId", "searchUserId", "firstName", "lastName", "userType", "rowSelections",
                    "displayedPageNumber", "firstUserIdOnPage", "lastUserIdOnPage", "keyAction",
                    "navigationContext");
        }

        @Test
        @DisplayName("uses the canonical spellings shared across the package")
        void usesTheCanonicalSpellings() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.has("displayedPageNumber")).isTrue();
            assertThat(payload.has("navigationContext")).isTrue();
            assertThat(payload.has("pageNumber")).isFalse();
            assertThat(payload.has("pageIndicator")).isFalse();
            assertThat(payload.has("navigation")).isFalse();
        }

        @Test
        @DisplayName("omits an absent component rather than emitting a null")
        void omitsAnAbsentComponent() throws JsonProcessingException {
            JsonNode payload = payloadOf(deleteSubmission());

            assertThat(payload.has("firstName")).isFalse();
            assertThat(payload.has("lastName")).isFalse();
            assertThat(payload.has("displayedPageNumber")).isFalse();
            assertThat(payload.get("userId").asText()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("emits the selection collection as an ordered array")
        void emitsTheSelectionCollectionAsAnOrderedArray() throws JsonProcessingException {
            UserRequest request = new UserRequest(null, SEARCH_USER_ID, null, null, null, null,
                    List.of("S", " ", "U"), null, null, null, null, null);

            JsonNode selections = payloadOf(request).get("rowSelections");

            assertThat(selections.isArray()).isTrue();
            assertThat(selections).hasSize(3);
            assertThat(selections.get(0).asText()).isEqualTo("S");
            assertThat(selections.get(2).asText()).isEqualTo("U");
        }

        @Test
        @DisplayName("emits every identifier as text, so a leading zero survives")
        void emitsEveryIdentifierAsText() throws JsonProcessingException {
            UserRequest request = new UserRequest("00000001", null, null, null, null, null, null,
                    "00000003", null, null, null, null);

            JsonNode payload = payloadOf(request);

            assertThat(payload.get("userId").isTextual()).isTrue();
            assertThat(payload.get("userId").asText()).isEqualTo("00000001");
            assertThat(payload.get("displayedPageNumber").asText()).isEqualTo("00000003");
        }

        @Test
        @DisplayName("round-trips every bindable component")
        void roundTripsEveryBindableComponent() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            UserRequest original = listSubmission();

            UserRequest restored = mapper.readValue(mapper.writeValueAsString(original),
                    UserRequest.class);

            assertThat(restored.searchUserId()).isEqualTo(original.searchUserId());
            assertThat(restored.rowSelections()).isEqualTo(original.rowSelections());
            assertThat(restored.firstUserIdOnPage()).isEqualTo(original.firstUserIdOnPage());
            assertThat(restored.lastUserIdOnPage()).isEqualTo(original.lastUserIdOnPage());
            assertThat(restored.keyAction()).isEqualTo(original.keyAction());
        }
    }
}
