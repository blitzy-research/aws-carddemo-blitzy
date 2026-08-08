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
import java.util.regex.Pattern;

import com.carddemo.domain.enums.KeyAction;
import com.carddemo.support.SensitiveValues;
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
 * Unit tests for {@link UserRequest}, the request body shared by the four legacy administrative
 * transactions {@code CU00} through {@code CU03}, implemented by {@code app/cbl/COUSR00C.cbl},
 * {@code COUSR01C.cbl}, {@code COUSR02C.cbl} and {@code COUSR03C.cbl} over screens
 * {@code app/cpy-bms/COUSR00.CPY} through {@code COUSR03.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Every width is a field of the eighty-byte user-security record.</strong>
 * {@code app/cpy/CSUSR01Y.cpy} declares {@code SEC-USR-ID PIC X(08)},
 * {@code SEC-USR-FNAME PIC X(20)}, {@code SEC-USR-LNAME PIC X(20)},
 * {@code SEC-USR-PWD PIC X(08)} and {@code SEC-USR-TYPE PIC X(01)}. The request bounds follow that
 * record rather than a screen rendering, so an over-long value is refused before it can reach a
 * repository write.
 *
 * <p><strong>One request serves four screens.</strong> The list screen supplies a page number and a
 * selector per row; the add, update and delete screens supply a user identity. A component is
 * therefore optional at this layer wherever some screen does not use it, and the mandatory-field rules
 * belong to the service that publishes the corresponding message on {@link UserResponse}.
 *
 * <p><strong>The credential is withheld from the rendering but not from the payload.</strong> This is
 * an inbound request, so the wire has to carry the password the operator typed; only the rendering is
 * redacted, so a log record cannot carry it. Both halves are asserted, and the exact placeholder count
 * is taken with an absent conversation state because {@code NavigationContext} performs redactions of
 * its own.
 */
@DisplayName("UserRequest - the CU00 through CU03 administrative screen contract")
class UserRequestRuleComplianceTest {

    /** A representative eight-character user identifier drawn from the seeded fixture range. */
    private static final String USER_ID = "ADMIN001";

    /** The redaction placeholder the record's own rendering substitutes. */
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
     * Serializes a request and reads the result back as a tree.
     *
     * @param request the request to render
     * @return the rendered payload
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static JsonNode payloadOf(final UserRequest request) throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Builds a fully populated request, laid out in rows of five so a component cannot silently drift
     * one position out of line past a compiler that sees a wall of interchangeable strings.
     *
     * @param userId the identity being added, updated or deleted
     * @param password the credential the operator typed
     * @param rowSelections the per-row selection characters from the list screen
     * @param keyAction the attention key the operator pressed
     * @param navigationContext the carried conversation state
     * @return a request carrying the supplied values and representative identity detail
     */
    private static UserRequest aRequest(final String userId, final String password,
            final List<String> rowSelections, final KeyAction keyAction,
            final NavigationContext navigationContext) {
        return new UserRequest(
                userId, USER_ID, "FIRSTNAME", "LASTNAME", password,
                "A", rowSelections, "00000001", "ADMIN001", "USER0005",
                keyAction, navigationContext);
    }

    /**
     * Builds a request carrying only a selector list.
     *
     * @param rowSelections the per-row selection characters
     * @return a request carrying only that list
     */
    private static UserRequest aRequestWithSelections(final List<String> rowSelections) {
        return new UserRequest(
                null, null, null, null, null,
                null, rowSelections, null, null, null,
                null, null);
    }

    /**
     * Reports whether a named component's accessor carries a declared upper bound.
     *
     * <p>The annotation is read from the accessor rather than from the record component, because
     * {@code jakarta.validation.constraints.Size} does not target {@code RECORD_COMPONENT}. A
     * constraint written on a record component is propagated to the backing field, the accessor and
     * the canonical constructor parameter instead of being retained on the component itself.
     *
     * @param componentName the record component to test
     * @return {@code true} when the accessor declares a {@link Size} bound
     */
    private static boolean declaresAnUpperBound(final String componentName) {
        try {
            return UserRequest.class.getDeclaredMethod(componentName)
                    .getAnnotation(Size.class) != null;
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("no accessor declared for " + componentName, cause);
        }
    }

    /**
     * Reads the declared upper bound of a named component's accessor.
     *
     * @param componentName the record component whose accessor carries the annotation
     * @return the declared maximum length
     * @throws NoSuchMethodException when no such accessor is declared
     */
    private static int declaredMaximumLength(final String componentName)
            throws NoSuchMethodException {
        final Size size = UserRequest.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    /**
     * Counts the occurrences of the redaction placeholder in a rendering.
     *
     * @param rendered the rendering to scan
     * @return the number of placeholders present
     */
    private static int placeholderCount(final String rendered) {
        return rendered.split(Pattern.quote(REDACTION_PLACEHOLDER), -1).length - 1;
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published field widths")
    class ThePublishedFieldWidths {

        @Test
        @DisplayName("the user identifier is eight characters, matching SEC-USR-ID PIC X(08) in the "
                + "eighty-byte user-security record")
        void theUserIdentifierIsEightCharacters() {
            assertThat(UserRequest.USER_ID_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("a name part is twenty characters, matching SEC-USR-FNAME and SEC-USR-LNAME which "
                + "share one width in the record")
        void aNamePartIsTwentyCharacters() {
            assertThat(UserRequest.NAME_PART_LENGTH).isEqualTo(20);
        }

        @Test
        @DisplayName("the credential is eight characters, matching SEC-USR-PWD PIC X(08), which is the "
                + "legacy width and not a policy minimum")
        void theCredentialIsEightCharacters() {
            assertThat(UserRequest.PASSWORD_LENGTH).isEqualTo(8);
            assertThat(UserRequest.PASSWORD_LENGTH).isEqualTo(UserRequest.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("the user type is a single character, matching SEC-USR-TYPE PIC X(01) which holds "
                + "A for an administrator and U for a standard user")
        void theUserTypeIsASingleCharacter() {
            assertThat(UserRequest.USER_TYPE_LENGTH).isOne();
        }

        @Test
        @DisplayName("a row selection is a single character and the displayed page number is eight, "
                + "the shared paging width across this estate")
        void theSelectionAndPageWidthsAreOneAndEight() {
            assertThat(UserRequest.ROW_SELECTION_LENGTH).isOne();
            assertThat(UserRequest.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(8)
                    .isEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);
        }

        @Test
        @DisplayName("the selection count is declared exactly once and agrees with the row count the "
                + "paging contract publishes for this screen")
        void theSelectionCountEqualsTheRowCount() {
            assertThat(UserRequest.ROW_SELECTION_COUNT)
                    .as("one selection position belongs to each rendered row")
                    .isEqualTo(PageMetadata.USER_LIST_PAGE_SIZE)
                    .isEqualTo(10);

            // The figure is published once and once only. An earlier revision declared it twice, under
            // two names with two separate justifications, and that duplication was the stated reason a
            // later revision dropped the cap altogether. Asserting singularity here is what stops the
            // same argument being available again.
            assertThat(Arrays.stream(UserRequest.class.getDeclaredFields())
                            .filter(field -> Modifier.isStatic(field.getModifiers()))
                            .filter(field -> !field.isSynthetic())
                            .map(Field::getName)
                            .filter(name -> name.contains("ROW_COUNT")
                                    || name.contains("ROW_SELECTION_COUNT"))
                            .toList())
                    .as("exactly one constant states how many selection positions the screen offers")
                    .containsExactly("ROW_SELECTION_COUNT");
        }

        @ParameterizedTest
        @CsvSource({
            "userId,8", "searchUserId,8", "firstName,20",
            "lastName,20", "password,8", "userType,1",
            "displayedPageNumber,8", "firstUserIdOnPage,8", "lastUserIdOnPage,8",
        })
        @DisplayName("every accessor-bounded component declares the width its named constant publishes")
        void everyAccessorBoundedComponentDeclaresItsPublishedWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the four identifier components share one width, because every one of them holds "
                + "the same eight-character key")
        void theFourIdentifierComponentsShareOneWidth() throws NoSuchMethodException {
            assertThat(declaredMaximumLength("userId"))
                    .isEqualTo(declaredMaximumLength("searchUserId"))
                    .isEqualTo(declaredMaximumLength("firstUserIdOnPage"))
                    .isEqualTo(declaredMaximumLength("lastUserIdOnPage"))
                    .isEqualTo(UserRequest.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("the page size the list screen fills is ten, published by the shared paging "
                + "metadata from the legacy OCCURS 10 table")
        void thePageSizeIsTen() {
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE).isEqualTo(10);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the compact constructor's handling of the selection list")
    class TheCompactConstructorsSelectionHandling {

        @Test
        @DisplayName("an absent selection list becomes an empty list, so a caller never has to guard "
                + "against a null before iterating the rows")
        void anAbsentSelectionListBecomesAnEmptyList() {
            assertThat(aRequestWithSelections(null).rowSelections()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("a supplied selection list is copied, so mutating the caller's list afterwards "
                + "cannot change the request")
        void aSuppliedSelectionListIsCopied() {
            final List<String> mutable = new ArrayList<>(List.of("U", "D"));
            final UserRequest request = aRequestWithSelections(mutable);

            mutable.clear();
            mutable.add("X");

            assertThat(request.rowSelections()).containsExactly("U", "D");
        }

        @Test
        @DisplayName("the copy is unmodifiable, so a holder of the request cannot rewrite a selection "
                + "after validation has run over it")
        void theCopyIsUnmodifiable() {
            final List<String> selections = aRequestWithSelections(List.of("U")).rowSelections();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> selections.set(0, "D"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> selections.add("D"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(selections::clear);
        }

        @Test
        @DisplayName("a null element is refused outright, because an unselected row is carried as a "
                + "blank rather than as an absent entry")
        void aNullElementIsRefusedOutright() {
            final List<String> withNull = Arrays.asList("U", null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aRequestWithSelections(withNull));
        }

        @Test
        @DisplayName("an empty selection list and an absent one produce equal requests, because both "
                + "mean no row was selected")
        void anEmptySelectionListAndAnAbsentOneProduceEqualRequests() {
            assertThat(aRequestWithSelections(List.of()))
                    .isEqualTo(aRequestWithSelections(null));
        }

        @Test
        @DisplayName("a full page of ten selections keeps row order, because the legacy program reports "
                + "an invalid selection against its own row")
        void aFullPageOfTenSelectionsKeepsRowOrder() {
            final List<String> tenRows = List.of(" ", " ", " ", "U", " ", " ", " ", "D", " ", " ");

            assertThat(aRequestWithSelections(tenRows).rowSelections())
                    .hasSize(PageMetadata.USER_LIST_PAGE_SIZE)
                    .containsExactlyElementsOf(tenRows);
            assertThat(aRequestWithSelections(tenRows).rowSelections().indexOf("U")).isEqualTo(3);
            assertThat(aRequestWithSelections(tenRows).rowSelections().indexOf("D")).isEqualTo(7);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the container element constraint on the selection list")
    class TheContainerElementConstraint {

        @Test
        @DisplayName("the bound is written on the list's type argument rather than on the accessor, "
                + "which is why a reflective accessor lookup finds nothing for this component")
        void theBoundIsNotOnTheAccessor() {
            assertThat(declaresAnUpperBound("rowSelections")).isFalse();
            assertThat(declaresAnUpperBound("userId")).isTrue();
        }

        @Test
        @DisplayName("the two legal selection characters are accepted, so a list of legitimate "
                + "selections passes validation untouched")
        void theTwoLegalSelectionCharactersAreAccepted() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequestWithSelections(List.of("U", "D", " ")))).isEmpty();
            }
        }

        @Test
        @DisplayName("a two-character selection is reported against the offending index, so the screen "
                + "can mark the row that carries it")
        void aTwoCharacterSelectionIsReportedAgainstItsIndex() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequestWithSelections(List.of(" ", "UD"))))
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString())
                                .startsWith("rowSelections[1]"));
            }
        }

        @Test
        @DisplayName("the bound admits any single character, because the legacy program tests the value "
                + "against U and D and reports its own invalid-selection message")
        void theBoundAdmitsAnySingleCharacter() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequestWithSelections(List.of("U", "D", "S", "X", "1"))))
                        .isEmpty();
            }
            assertThat(UserResponse.MSG_LIST_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid values are U and D");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the withholding rendering")
    class TheWithholdingRendering {

        @Test
        @DisplayName("the credential is withheld, so a log record can never carry the password an "
                + "operator typed on the add or update screen")
        void theCredentialIsWithheld() {
            final String rendered = aRequest(USER_ID, "PASSWORD", List.of("U"), KeyAction.PFK05,
                    null).toString();

            assertThat(rendered).doesNotContain("PASSWORD");
            assertThat(rendered).contains("password=" + REDACTION_PLACEHOLDER);
        }

        /**
         * The credential is not the only withheld component; nine of the twelve are withheld.
         *
         * <p>Every component that names or describes a person is replaced by the placeholder: the two
         * identifier roles, the two name parts, the credential, the user-type code, the two page
         * boundary identifiers and the carried conversation state, which itself holds an identifier and
         * a resolved role. What survives in the clear is the count of selections rather than the
         * selections themselves, the displayed page number and the attention key - three values that
         * describe where the operator was on the screen and say nothing about whom the screen was
         * about.</p>
         */
        @Test
        @DisplayName("ten of the thirteen components are withheld, including the page snapshot token")
        void nineComponentsAreWithheld() {
            final String rendered = aRequest(USER_ID, "PASSWORD", List.of("U"), KeyAction.PFK05,
                    NavigationContext.empty()).toString();

            assertThat(rendered).startsWith("UserRequest[");
            assertThat(rendered).contains("userId=" + REDACTION_PLACEHOLDER,
                    "searchUserId=" + REDACTION_PLACEHOLDER,
                    "firstName=" + REDACTION_PLACEHOLDER,
                    "lastName=" + REDACTION_PLACEHOLDER,
                    "password=" + REDACTION_PLACEHOLDER,
                    "userType=" + REDACTION_PLACEHOLDER,
                    "firstUserIdOnPage=" + REDACTION_PLACEHOLDER,
                    "lastUserIdOnPage=" + REDACTION_PLACEHOLDER,
                    "rowSnapshotToken=" + REDACTION_PLACEHOLDER,
                    "navigationContext=" + REDACTION_PLACEHOLDER);
            assertThat(placeholderCount(rendered)).isEqualTo(10);
            assertThat(rendered).doesNotContain(USER_ID, "FIRSTNAME", "LASTNAME", "USER0005");
            assertThat(rendered).endsWith("]");
        }

        @Test
        @DisplayName("the three surviving components describe where the operator was rather than whom "
                + "the screen was about, and the selections are reduced to their count")
        void theThreeSurvivingComponentsDescribeThePagePosition() {
            final String rendered = aRequest(USER_ID, "PASSWORD", List.of("U", " ", "D"),
                    KeyAction.PFK05, null).toString();

            assertThat(rendered).contains("rowSelectionCount=3", "displayedPageNumber=00000001",
                    "keyAction=PFK05");
            assertThat(rendered)
                    .as("the selections themselves are not rendered, only how many arrived")
                    .doesNotContain("rowSelections=");
        }

        @Test
        @DisplayName("the withholding is unconditional, so an absent value is rendered as the "
                + "placeholder too and absence is not distinguishable from presence")
        void theWithholdingIsUnconditional() {
            final String rendered = aRequestWithSelections(null).toString();

            assertThat(placeholderCount(rendered)).isEqualTo(10);
            assertThat(rendered).contains("password=" + REDACTION_PLACEHOLDER);
            assertThat(rendered).doesNotContain("userId=null", "password=null",
                    "navigationContext=null");
        }

        @Test
        @DisplayName("the conversation state is withheld here as well, so the placeholder count does "
                + "not move when a context is carried and no nested rendering leaks through")
        void theConversationStateIsWithheldToo() {
            final String withContext = aRequest(USER_ID, "PASSWORD", List.of(), KeyAction.ENTER,
                    NavigationContext.empty()).toString();
            final String withoutContext = aRequest(USER_ID, "PASSWORD", List.of(), KeyAction.ENTER,
                    null).toString();

            assertThat(withContext).doesNotContain("NavigationContext[");
            assertThat(placeholderCount(withContext))
                    .isEqualTo(placeholderCount(withoutContext))
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the placeholder is a private constant, so no caller can build a rendering that "
                + "looks redacted without being redacted")
        void thePlaceholderIsAPrivateConstant() throws NoSuchFieldException {
            final int modifiers = UserRequest.class
                    .getDeclaredField("REDACTION_PLACEHOLDER").getModifiers();

            assertThat(Modifier.isPrivate(modifiers)).isTrue();
            assertThat(Modifier.isStatic(modifiers)).isTrue();
            assertThat(Modifier.isFinal(modifiers)).isTrue();
        }

        /**
         * The rendering is not the accessor, and the outbound payload is not the inbound one.
         *
         * <p>Three channels carry this record and each one carries a different amount. The accessor
         * returns the credential exactly as supplied, because the service has to hash it. The rendering
         * withholds it, because a log record must not carry it. The outbound payload omits it entirely,
         * because the component is bound write-only: it is accepted from a submission and never emitted
         * back. Asserting all three together is what proves the withholding is confined to the
         * rendering path rather than having damaged the value itself.</p>
         */
        @Test
        @DisplayName("the accessor carries the credential, the rendering withholds it and the outbound "
                + "payload omits it, because the component is bound write-only")
        void theRenderingIsNotTheAccessorAndTheOutboundPayloadIsNeither()
                throws JsonProcessingException {
            final UserRequest request = aRequest(USER_ID, "PASSWORD", List.of(), KeyAction.ENTER,
                    null);

            assertThat(request.password())
                    .as("the accessor returns the component exactly as supplied")
                    .isEqualTo("PASSWORD");
            assertThat(request.toString()).doesNotContain("PASSWORD");
            assertThat(payloadOf(request).has("password"))
                    .as("write-only means the credential is never emitted outbound")
                    .isFalse();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and its validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        @Test
        @DisplayName("the request declares thirteen components including the protected page snapshot")
        void theRequestDeclaresTwelveComponents() {
            final List<String> declared = Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("userId", "searchUserId", "firstName", "lastName",
                    "password", "userType", "rowSelections", "displayedPageNumber",
                    "firstUserIdOnPage", "lastUserIdOnPage", "rowSnapshotToken", "keyAction",
                    "navigationContext");
            assertThat(declared).hasSize(13);
        }

        /**
         * Nine components carry a bound a single-annotation lookup can read.
         *
         * <p>The selection list is not among them, and the reason is worth stating precisely because it
         * is not that the list is unbounded. The list carries two width annotations - a container bound
         * limiting how many positions may arrive and a group-scoped bound forbidding any position on
         * the three non-list operations - and two repeatable annotations on one element are wrapped by
         * the compiler into a container annotation, so a lookup for the single annotation reports
         * nothing. The element bound is written on the list's type argument in addition. What this
         * count therefore measures is the components whose bound is directly readable, which is exactly
         * the set the parameterised width tests drive.</p>
         */
        @Test
        @DisplayName("exactly nine components carry a directly readable accessor-level bound, and the "
                + "selection list is not one of them because its two bounds are wrapped in a container")
        void exactlyNineComponentsCarryAnAccessorLevelBound() {
            final List<String> bounded = Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(UserRequestRuleComplianceTest::declaresAnUpperBound).toList();

            assertThat(bounded).hasSize(9);
            assertThat(bounded).containsExactly("userId", "searchUserId", "firstName", "lastName",
                    "password", "userType", "displayedPageNumber", "firstUserIdOnPage",
                    "lastUserIdOnPage");
            assertThat(bounded).doesNotContain("rowSelections", "rowSnapshotToken", "keyAction",
                    "navigationContext");
        }

        @Test
        @DisplayName("the two identifier roles are separate components, so a search that finds nothing "
                + "can still redisplay the key the operator asked for")
        void theTwoIdentifierRolesAreSeparateComponents() {
            final UserRequest request = new UserRequest("NEWUSER1", "OLDUSER1", null, null, null,
                    null, null, null, null, null, null, null);

            assertThat(request.userId()).isEqualTo("NEWUSER1");
            assertThat(request.searchUserId()).isEqualTo("OLDUSER1");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final UserRequest request = aRequest(USER_ID, "PASSWORD", List.of("U", "D"),
                    KeyAction.PFK05, NavigationContext.empty());

            assertThat(request.userId()).isEqualTo(USER_ID);
            assertThat(request.searchUserId()).isEqualTo(USER_ID);
            assertThat(request.firstName()).isEqualTo("FIRSTNAME");
            assertThat(request.lastName()).isEqualTo("LASTNAME");
            assertThat(SensitiveValues.fingerprint(request.password())).isEqualTo(SensitiveValues.fingerprint("PASSWORD"));
            assertThat(request.userType()).isEqualTo("A");
            assertThat(request.rowSelections()).containsExactly("U", "D");
            assertThat(request.displayedPageNumber()).isEqualTo("00000001");
            assertThat(request.firstUserIdOnPage()).isEqualTo("ADMIN001");
            assertThat(request.lastUserIdOnPage()).isEqualTo("USER0005");
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK05);
            assertThat(request.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a wholly absent request reports no violation, because every bound is an upper "
                + "bound and one request serves four screens with different mandatory fields")
        void aWhollyAbsentRequestReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequestWithSelections(null))).isEmpty();
            }
        }

        @Test
        @DisplayName("a fully populated request reports no violation, so the representative fixture is "
                + "itself within every declared bound")
        void aFullyPopulatedRequestReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequest(USER_ID, "PASSWORD",
                        List.of("U"), KeyAction.ENTER, NavigationContext.empty()))).isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({
            "userId,8", "searchUserId,8", "firstName,20",
            "lastName,20", "password,8", "userType,1",
            "displayedPageNumber,8", "firstUserIdOnPage,8", "lastUserIdOnPage,8",
        })
        @DisplayName("a bounded component accepts its declared width and rejects one character more")
        void aBoundedComponentAcceptsItsWidthAndRejectsOneMore(final String componentName,
                final int declaredMaximum) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(requestWith(componentName, "X".repeat(declaredMaximum))))
                        .as("%s must accept %d characters", componentName, declaredMaximum)
                        .isEmpty();
                assertThat(factory.getValidator()
                        .validate(requestWith(componentName, "X".repeat(declaredMaximum + 1))))
                        .as("%s must reject %d characters", componentName, declaredMaximum + 1)
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString()).isEqualTo(componentName));
            }
        }

        @Test
        @DisplayName("an over-long identifier and an over-long credential are reported as two separate "
                + "violations, so a screen can mark both fields at once")
        void twoOverLongValuesAreReportedSeparately() {
            final UserRequest request = new UserRequest("X".repeat(9), null, null, null,
                    "X".repeat(9), null, null, null, null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(request))
                        .hasSize(2)
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("userId", "password");
            }
        }

        /**
         * Builds a request carrying a single named component and nothing else.
         *
         * @param componentName the component to populate
         * @param value the value to place in it
         * @return a request carrying only that component
         */
        private UserRequest requestWith(final String componentName, final String value) {
            return new UserRequest(
                    valueFor("userId", componentName, value),
                    valueFor("searchUserId", componentName, value),
                    valueFor("firstName", componentName, value),
                    valueFor("lastName", componentName, value),
                    valueFor("password", componentName, value),
                    valueFor("userType", componentName, value),
                    null,
                    valueFor("displayedPageNumber", componentName, value),
                    valueFor("firstUserIdOnPage", componentName, value),
                    valueFor("lastUserIdOnPage", componentName, value),
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
        @DisplayName("two requests built from identical values are equal and share a hash code")
        void twoRequestsBuiltFromIdenticalValuesAreEqual() {
            final UserRequest first = aRequest(USER_ID, "PASSWORD", List.of("U"), KeyAction.ENTER,
                    NavigationContext.empty());
            final UserRequest second = aRequest(USER_ID, "PASSWORD", List.of("U"), KeyAction.ENTER,
                    NavigationContext.empty());

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a difference in the credential alone makes two requests unequal, so an update "
                + "that changes only the password is a distinct request")
        void aDifferenceInTheCredentialMakesTwoRequestsUnequal() {
            assertThat(aRequest(USER_ID, "PASSWORD", List.of(), null, null))
                    .isNotEqualTo(aRequest(USER_ID, "NEWPASSW", List.of(), null, null));
        }

        @Test
        @DisplayName("a difference in selection order makes two requests unequal, because the selection "
                + "position identifies the row it belongs to")
        void aDifferenceInSelectionOrderMakesTwoRequestsUnequal() {
            assertThat(aRequestWithSelections(List.of("U", " ")))
                    .isNotEqualTo(aRequestWithSelections(List.of(" ", "U")));
        }

        @Test
        @DisplayName("the payload always carries the selection list and omits every absent component")
        void thePayloadAlwaysCarriesTheSelectionList() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aRequestWithSelections(null));

            assertThat(payload.has("rowSelections")).isTrue();
            assertThat(payload.get("rowSelections").isArray()).isTrue();
            assertThat(payload.get("rowSelections")).isEmpty();
            assertThat(payload.has("userId")).isFalse();
            assertThat(payload.has("password")).isFalse();
            assertThat(payload.has("keyAction")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
        }

        @Test
        @DisplayName("a selection list travels on the wire in row order")
        void aSelectionListTravelsInRowOrder() throws JsonProcessingException {
            final JsonNode selections = payloadOf(aRequestWithSelections(List.of("U", " ", "D")))
                    .get("rowSelections");

            assertThat(selections).hasSize(3);
            assertThat(selections.get(0).asText()).isEqualTo("U");
            assertThat(selections.get(2).asText()).isEqualTo("D");
        }

        @Test
        @DisplayName("the attention key travels as its enum name rather than as its five-character "
                + "3270 attention identifier")
        void theAttentionKeyTravelsAsItsEnumName() throws JsonProcessingException {
            assertThat(payloadOf(aRequest(null, null, List.of(), KeyAction.PFK05, null))
                    .get("keyAction").asText()).isEqualTo("PFK05");
            assertThat(KeyAction.PFK05.getAid()).isEqualTo("PFK05");
            assertThat(KeyAction.PA1.getAid()).isEqualTo("PA1  ");
        }

        /**
         * Two components are bound in one direction each, and the round trip loses exactly those two.
         *
         * <p>The credential is write-only: it is accepted from a submission and never emitted, because
         * the stored value is a one-way digest and no reply can echo it. The displayed page number is
         * read-only in the opposite sense: it is emitted so a client can be told which page it is
         * looking at, and discarded on the way in because the server computes it and a submitted value
         * could only be a client asserting a page it was not given. Equality across a round trip
         * therefore cannot hold, and asserting that it does would require reopening one of the two
         * directions.</p>
         *
         * <p>What is asserted instead is that the loss is precisely those two components and that
         * everything else survives, selection order included. That is stronger than equality would have
         * been, because it names what may change and would fail if a third component silently acquired
         * a directional binding.</p>
         */
        @Test
        @DisplayName("a request round trips with exactly the write-only credential and the read-only "
                + "page number dropped, the selection order preserved")
        void aRequestRoundTripsWithoutItsDirectionallyBoundComponents()
                throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final UserRequest original = aRequest(USER_ID, "PASSWORD", List.of("U", "D"),
                    KeyAction.PFK05, NavigationContext.empty().withReEntry());
            final UserRequest restored = mapper.readValue(mapper.writeValueAsString(original),
                    UserRequest.class);

            assertThat(restored)
                    .as("two directional bindings mean equality cannot hold across a round trip")
                    .isNotEqualTo(original);
            assertThat(restored.password())
                    .as("the credential is never emitted, so nothing came back to bind")
                    .isNull();
            assertThat(restored.displayedPageNumber())
                    .as("the page number is emitted but never bound inbound")
                    .isNull();
            assertThat(restored)
                    .as("everything else survives, so the loss is exactly those two")
                    .isEqualTo(new UserRequest(original.userId(), original.searchUserId(),
                            original.firstName(), original.lastName(), null, original.userType(),
                            original.rowSelections(), null, original.firstUserIdOnPage(),
                            original.lastUserIdOnPage(), original.keyAction(),
                            original.navigationContext()));
            assertThat(restored.rowSelections()).containsExactly("U", "D");
        }
    }
}
