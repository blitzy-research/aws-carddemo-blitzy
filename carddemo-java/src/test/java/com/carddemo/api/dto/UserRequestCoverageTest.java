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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
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
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link UserRequest}, the request body shared by legacy transactions {@code CU00},
 * {@code CU01}, {@code CU02} and {@code CU03}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the administrative user-maintenance submission: the twelve
 * components, the six declared widths, the one piece of normalising the canonical constructor
 * performs, the wire form under the module's declared serialisation settings, and the diagnostic
 * rendering - which withholds exactly one component and is the most consequential thing in the file.
 *
 * <h2>One request type serves four screens, so almost nothing can be mandatory</h2>
 *
 * <p>The list, add, update and delete screens submit overlapping but unequal item sets: only the add
 * and update maps declare a credential item at all, only the list map declares a browse start key and
 * the ten per-row marks, and only the list map declares the paging echoes. A presence constraint on
 * any of them would therefore reject a submission one of the four screens legitimately makes. Tests
 * below prove that an entirely empty request reports no violation and that each operation's own
 * emptiness cascade - which is ordered and message-bearing, and therefore lives in the service - is
 * not pre-empted here.
 *
 * <h2>The two user identifiers are separate components on purpose</h2>
 *
 * <p>The add, update and delete screens carry the identifier being acted upon; the list screen
 * carries a browse start key, for which blank is meaningful and means "start at the beginning".
 * Folding the two into one component would make a blank start key indistinguishable from a missing
 * subject identifier, which are different submissions with different outcomes. A test below proves
 * both are declared and that they are independently bounded.
 *
 * <h2>The paging echoes are three text components, not the shared paging record</h2>
 *
 * <p>Unlike the card-list and transaction-list screens, this screen's retained boundary keys are
 * single eight-character user identifiers rather than composite browse keys, and its page number is
 * an eight-character display echo. They are therefore carried as three bounded text components and
 * the shared {@link PageMetadata} does not appear at all. A test below proves the absence, because an
 * unused-but-declared paging record would invite a consumer to read cursor state that this screen
 * never sends.
 *
 * <h2>The credential travels inbound, and is withheld from both the outbound wire and diagnostics</h2>
 *
 * <p>The password must reach the service intact - that is where it is hashed - so it is carried
 * verbatim and the accessor returns exactly what the client sent. Two channels are closed around it.
 * It is bound {@code WRITE_ONLY}, so it is accepted from a client and never written back, which means
 * a surface echoing a submitted request cannot repeat it. And the rendering substitutes a fixed
 * constant, so neither the characters nor the length of the credential is recoverable from a
 * stringified instance. Tests below prove the inbound binding still works, prove the outbound
 * direction is closed, prove that the placeholder does not vary with the value so the length stays
 * hidden, prove that an absent credential is indistinguishable from a present one, and prove that
 * neither control reaches the accessor.
 *
 * <h2>Two components are bound in one direction only, which makes the round trip asymmetric</h2>
 *
 * <p>The credential is write-only and the displayed page number is read-only, for opposite reasons.
 * The page number is computed entirely by the legacy program from its own retained counter, so a
 * submitted value could never have influenced a page and accepting one would create an input the
 * legacy never had. A round trip therefore cannot return an equal instance, and the test that
 * exercises it asserts that the loss is precisely those two components rather than relaxing to an
 * inequality - which would have passed had a third component silently acquired a binding.
 *
 * <h2>Nine of the twelve components are withheld from the rendering, not one</h2>
 *
 * <p>Every withheld component identifies or authenticates a person: the administrator's own
 * identifier, the identifier being searched for, both name halves, the credential, the role, the two
 * retained page keys - which are themselves user identifiers - and the echoed navigation state, which
 * is substituted whole so that not even its presentational parts cross. What remains describes the
 * request's shape rather than its subject: how many row slots were submitted, which page was showing,
 * and which key was pressed. The row marks are replaced by their count, because a mark's position is a
 * row index and a row on a user-list page is a person, while a count of slots identifies nobody and
 * still distinguishes a full page from a short one. Every substitution is unconditional, so absence is
 * indistinguishable from presence, and none of it reaches an accessor or the inbound wire form.
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
@DisplayName("UserRequest :: user-maintenance request contract of legacy transactions CU00 to CU03")
class UserRequestCoverageTest {

    /** The thirteen components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "userId", "searchUserId", "firstName", "lastName", "password", "userType",
            "rowSelections", "displayedPageNumber", "firstUserIdOnPage", "lastUserIdOnPage",
            "rowSnapshotToken", "keyAction", "navigationContext");

    /** Declared width of a user identifier, restated from the symbolic maps. */
    private static final int EXPECTED_USER_ID_WIDTH = 8;

    /** Declared width of a name part, restated from the symbolic maps. */
    private static final int EXPECTED_NAME_PART_WIDTH = 20;

    /** Declared width of the credential, restated from the add and update maps. */
    private static final int EXPECTED_PASSWORD_WIDTH = 8;

    /** Declared width of the user-type character, restated from the symbolic maps. */
    private static final int EXPECTED_USER_TYPE_WIDTH = 1;

    /** Declared width of one per-row mark, restated from the list map. */
    private static final int EXPECTED_ROW_SELECTION_WIDTH = 1;

    /** Declared width of the displayed page number, restated from the list map. */
    private static final int EXPECTED_PAGE_NUMBER_WIDTH = 8;

    /** Number of rows the administrative user-list screen presents. */
    private static final int EXPECTED_ROW_COUNT = 10;

    /** The exact placeholder the rendering emits in place of the credential. */
    private static final String EXPECTED_PLACEHOLDER = "***REDACTED***";

    /**
     * A credential at exactly the declared width, chosen so that it cannot collide with any other
     * value this test renders and so that a non-disclosure assertion therefore means what it says.
     */
    private static final String CREDENTIAL = "Zq7#kR2x";

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
     * Builds a request carrying only the named component, so a violation can be attributed.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a request carrying that one value
     */
    private static UserRequest carrying(String component, String value) {
        return new UserRequest(
                "userId".equals(component) ? value : null,
                "searchUserId".equals(component) ? value : null,
                "firstName".equals(component) ? value : null,
                "lastName".equals(component) ? value : null,
                "password".equals(component) ? value : null,
                "userType".equals(component) ? value : null,
                null,
                "displayedPageNumber".equals(component) ? value : null,
                "firstUserIdOnPage".equals(component) ? value : null,
                "lastUserIdOnPage".equals(component) ? value : null,
                null, null);
    }

    /**
     * Builds a request carrying only the supplied per-row marks.
     *
     * @param selections the mark sequence, which may be {@code null}
     * @return a request carrying no other populated component
     */
    private static UserRequest carryingSelections(List<String> selections) {
        return new UserRequest(null, null, null, null, null, null, selections, null, null, null,
                null, null);
    }

    /**
     * Builds a ten-entry mark sequence in which exactly one row is marked.
     *
     * @param markedRowIndex the zero-based index of the marked row
     * @param mark the value to place on that row
     * @return a ten-entry sequence whose other nine entries are blank
     */
    private static List<String> tenRowsWithOneMark(int markedRowIndex, String mark) {
        List<String> rows = new ArrayList<>(Collections.nCopies(EXPECTED_ROW_COUNT, " "));
        rows.set(markedRowIndex, mark);
        return rows;
    }

    /**
     * Builds a fully populated add-style request carrying the credential.
     *
     * @return a request with every component populated
     */
    private static UserRequest fullyPopulated() {
        return new UserRequest("ADMIN001", "SRCH0001", "FIRSTNAMEEXACTLY20AB",
                "LASTNAMEEXACTLY20ABC", CREDENTIAL, "A", tenRowsWithOneMark(4, "U"),
                "00000002", "PAGEFRST", "PAGELAST", "sealed-page-token", KeyAction.PFK08,
                JsonContractSupport.populatedNavigation());
    }

    /**
     * Serialises a request and parses the result back into a tree.
     *
     * @param request the request to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(UserRequest request) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the thirteen components are declared in screen and protected-snapshot order")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("the six published widths equal the widths the symbolic maps declare")
        void publishedWidthsEqualTheMapWidths() {
            assertThat(UserRequest.USER_ID_LENGTH).isEqualTo(EXPECTED_USER_ID_WIDTH);
            assertThat(UserRequest.NAME_PART_LENGTH).isEqualTo(EXPECTED_NAME_PART_WIDTH);
            assertThat(UserRequest.PASSWORD_LENGTH).isEqualTo(EXPECTED_PASSWORD_WIDTH);
            assertThat(UserRequest.USER_TYPE_LENGTH).isEqualTo(EXPECTED_USER_TYPE_WIDTH);
            assertThat(UserRequest.ROW_SELECTION_LENGTH).isEqualTo(EXPECTED_ROW_SELECTION_WIDTH);
            assertThat(UserRequest.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(EXPECTED_PAGE_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("the subject identifier and the browse start key are separate components, so a "
                + "blank start key is not indistinguishable from a missing subject")
        void theSubjectIdentifierAndTheBrowseStartKeyAreSeparate() {
            List<String> declared = Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).contains("userId", "searchUserId");
            assertThat(carrying("searchUserId", "").searchUserId())
                    .as("a blank start key means \"list from the beginning\" and must survive as a "
                            + "distinct state")
                    .isEmpty();
            assertThat(carrying("searchUserId", "").userId()).isNull();
        }

        @Test
        @DisplayName("the shared paging record is not a component, because this screen's boundary "
                + "keys are single identifiers rather than composite browse keys")
        void theSharedPagingRecordIsNotAComponent() {
            List<Class<?>> componentTypes = Arrays.stream(UserRequest.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .toList();

            assertThat(componentTypes)
                    .as("declaring an unused paging record would invite a consumer to read cursor "
                            + "state this screen never sends")
                    .doesNotContain(PageMetadata.class);
        }

        @Test
        @DisplayName("the three paging echoes are declared as bounded text, so the leading zeros of "
                + "the page number survive")
        void theThreePagingEchoesAreBoundedText() throws NoSuchFieldException {
            for (String component : List.of("displayedPageNumber", "firstUserIdOnPage", "lastUserIdOnPage")) {
                assertThat(UserRequest.class.getDeclaredField(component).getType())
                        .as("%s crosses as text rather than a number", component)
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the ten per-row marks are one sequence rather than ten components, and the "
                + "attention key and navigation state are typed")
        void theRemainingComponentsAreTyped() {
            RecordComponent[] components = UserRequest.class.getRecordComponents();

            assertThat(components[6].getType()).isEqualTo(List.class);
            assertThat(components[10].getType()).isEqualTo(String.class);
            assertThat(components[11].getType()).isEqualTo(KeyAction.class);
            assertThat(components[12].getType()).isEqualTo(NavigationContext.class);
        }

        @Test
        @DisplayName("the administrative user list presents ten rows, the count the shared paging "
                + "record publishes")
        void thePublishedPageSizeIsTen() {
            assertThat(PageMetadata.USER_LIST_PAGE_SIZE).isEqualTo(EXPECTED_ROW_COUNT);
        }
    }

    @Nested
    @DisplayName("Selection normalisation")
    class SelectionNormalisation {

        @Test
        @DisplayName("an absent sequence becomes the empty sequence, so no accessor has to be "
                + "null-checked before iterating")
        void anAbsentSequenceBecomesTheEmptySequence() {
            assertThat(carryingSelections(null).rowSelections()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("a supplied sequence is detached from the caller, so a later mutation cannot "
                + "change what the request reports")
        void aSuppliedSequenceIsDetachedFromTheCaller() {
            List<String> callerOwned = new ArrayList<>(tenRowsWithOneMark(0, "U"));

            UserRequest request = carryingSelections(callerOwned);
            callerOwned.set(0, "D");
            callerOwned.add("EXTRA");

            assertThat(request.rowSelections())
                    .hasSize(EXPECTED_ROW_COUNT)
                    .element(0).isEqualTo("U");
        }

        @Test
        @DisplayName("the retained sequence rejects mutation by its holder as well as by its "
                + "supplier")
        void theRetainedSequenceRejectsMutation() {
            List<String> retained = carryingSelections(tenRowsWithOneMark(2, "U")).rowSelections();

            assertThatThrownBy(() -> retained.set(0, "U"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> retained.add("U"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null entry is refused, because a mark with no character would be neither "
                + "blank nor a choice")
        void aNullEntryIsRefused() {
            List<String> withNullEntry = new ArrayList<>(
                    Arrays.asList(" ", null, " ", " ", " ", " ", " ", " ", " ", " "));

            assertThatThrownBy(() -> carryingSelections(withNullEntry))
                    .as("silently dropping one would shift every later element onto the wrong screen "
                            + "row")
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("order and length are preserved exactly: nothing is filtered, de-duplicated, "
                + "compacted, re-ordered or padded")
        void orderAndLengthArePreservedExactly() {
            List<String> supplied = List.of("U", " ", "", "D", "u", "", " ", "d", "", " ");

            assertThat(carryingSelections(supplied).rowSelections())
                    .as("element n is the mark typed against screen row n, and that correspondence "
                            + "is the whole meaning of the sequence")
                    .containsExactlyElementsOf(supplied);
        }

        @Test
        @DisplayName("blank and empty marks survive untouched, because the items they mirror are "
                + "fixed-width and blank-significant")
        void blankAndEmptyMarksSurviveUntouched() {
            assertThat(carryingSelections(List.of(" ", "", "  ")).rowSelections())
                    .containsExactly(" ", "", "  ");
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a request whose every component sits exactly at its declared width reports no "
                + "violation")
        void aRequestAtEveryDeclaredWidthReportsNoViolation() {
            assertThat(validator.validate(fullyPopulated())).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @CsvSource({
            "userId,8",
            "searchUserId,8",
            "firstName,20",
            "lastName,20",
            "password,8",
            "userType,1",
            "displayedPageNumber,8",
            "firstUserIdOnPage,8",
            "lastUserIdOnPage,8",
        })
        @DisplayName("each bounded text component reports a value one character over its width")
        void eachBoundedTextComponentReportsAnOverLongValue(String component, int width) {
            Set<ConstraintViolation<UserRequest>> violations =
                    validator.validate(carrying(component, "X".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @ParameterizedTest(name = "{0} accepts a value exactly at {1}")
        @CsvSource({
            "userId,8",
            "firstName,20",
            "password,8",
            "userType,1",
            "displayedPageNumber,8",
        })
        @DisplayName("each bounded text component accepts a value exactly at its width, so the bound "
                + "is inclusive")
        void eachBoundedTextComponentAcceptsAValueAtItsWidth(String component, int width) {
            assertThat(validator.validate(carrying(component, "X".repeat(width)))).isEmpty();
        }

        @Test
        @DisplayName("an over-long per-row mark is reported against its own index, so the offending "
                + "row can be named")
        void anOverLongMarkIsReportedAgainstItsIndex() {
            Set<ConstraintViolation<UserRequest>> violations =
                    validator.validate(carryingSelections(tenRowsWithOneMark(3, "UU")));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .as("the bound is a container-element constraint, so the reported path carries "
                            + "the index rather than naming the sequence as a whole")
                    .startsWith("rowSelections[3]");
        }

        @Test
        @DisplayName("an entirely empty request reports no violation, because one request type "
                + "serves four screens with unequal item sets")
        void anEntirelyEmptyRequestReportsNoViolation() {
            assertThat(validator.validate(
                            new UserRequest(null, null, null, null, null, null, null, null, null,
                                    null, null, null)))
                    .as("a presence constraint on any component would reject a submission one of the "
                            + "four screens legitimately makes")
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent credential reports no violation, because the delete map declares no "
                + "credential item at all")
        void anAbsentCredentialReportsNoViolation() {
            assertThat(validator.validate(carrying("userId", "ADMIN001"))).isEmpty();
        }

        @ParameterizedTest(name = "user type \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"A", "U", "a", "u", "X", "1", " "})
        @DisplayName("the user-type bound restricts width only and names no acceptable character, "
                + "because no legacy program tests the value")
        void theUserTypeBoundNamesNoAcceptableCharacter(String type) {
            assertThat(validator.validate(carrying("userType", type))).isEmpty();
        }

        @Test
        @DisplayName("a blank name part is accepted by the boundary, because each operation's "
                + "emptiness cascade is ordered and message-bearing and lives in the service")
        void aBlankNamePartIsAcceptedByTheBoundary() {
            assertThat(validator.validate(carrying("firstName", "   "))).isEmpty();
            assertThat(validator.validate(carrying("lastName", ""))).isEmpty();
        }

        @Test
        @DisplayName("no constraint imposes a credential composition rule, because hashing rather "
                + "than shaping is what this migration changed about the credential")
        void noCredentialCompositionRuleIsImposed() {
            assertThat(validator.validate(carrying("password", "        "))).isEmpty();
            assertThat(validator.validate(carrying("password", "PASSWORD"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        /**
         * Eleven of the twelve components are written outbound; the credential is not one of them.
         *
         * <p>The credential is bound write-only, so it is accepted from a client and never written
         * back. The authenticated page token is likewise inbound-only on the request type: the list
         * response publishes it, while a request echo must never be serialized as response content.
         */
        @Test
        @DisplayName("the credential and page token are omitted from an outbound request rendering")
        void aFullyPopulatedRequestRendersAllTwelveMembers() throws JsonProcessingException {
            JsonNode payload = payloadOf(fullyPopulated());

            assertThat(payload.size()).isEqualTo(EXPECTED_COMPONENTS.size() - 2);
            assertThat(payload.has("password"))
                    .as("the credential is write-only")
                    .isFalse();
            assertThat(payload.has("rowSnapshotToken"))
                    .as("the echoed page token is write-only on the request")
                    .isFalse();
            assertThat(payload.get("userId").asText()).isEqualTo("ADMIN001");
            assertThat(payload.get("searchUserId").asText()).isEqualTo("SRCH0001");
            assertThat(payload.get("firstName").asText()).isEqualTo("FIRSTNAMEEXACTLY20AB");
            assertThat(payload.get("lastName").asText()).isEqualTo("LASTNAMEEXACTLY20ABC");
            assertThat(payload.get("userType").asText()).isEqualTo("A");
            assertThat(payload.get("displayedPageNumber").asText()).isEqualTo("00000002");
            assertThat(payload.get("firstUserIdOnPage").asText()).isEqualTo("PAGEFRST");
            assertThat(payload.get("lastUserIdOnPage").asText()).isEqualTo("PAGELAST");
            assertThat(payload.get("keyAction").asText()).isEqualTo("PFK08");
        }

        /**
         * The credential travels inbound and never outbound.
         *
         * <p>Both halves are asserted because either alone would be the wrong contract. Closing the
         * inbound direction as well would leave the add and update screens unable to set a credential
         * at all, which is why the binding is write-only rather than ignored; leaving the outbound
         * direction open would let any surface that echoes a request repeat the credential, which is
         * the exposure the binding closes. What reaches the service is unchanged - the accessor returns
         * exactly what the client sent - so this is a narrowing of the serialised form and not of the
         * type's behaviour.</p>
         */
        @Test
        @DisplayName("the credential is accepted inbound and never written outbound, because it must "
                + "reach the service that hashes it and nothing beyond")
        void theCredentialTravelsInboundAndNeverOutbound() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("password", CREDENTIAL));

            assertThat(payload.has("password"))
                    .as("writing it outbound would let any surface echoing a request repeat it")
                    .isFalse();

            UserRequest inbound = JsonContractSupport.declaredSettingsMapper()
                    .readValue("{\"password\":\"" + CREDENTIAL + "\"}", UserRequest.class);

            assertThat(inbound.password())
                    .as("closing the inbound direction too would leave the add and update screens "
                            + "unable to set a credential at all")
                    .isEqualTo(CREDENTIAL);
        }

        @Test
        @DisplayName("the marks render as an array in row order, with every blank slot present, so "
                + "the array index stays the row index")
        void theMarksRenderAsAnArrayInRowOrder() throws JsonProcessingException {
            JsonNode marks = payloadOf(carryingSelections(tenRowsWithOneMark(8, "D")))
                    .get("rowSelections");

            assertThat(marks.isArray()).isTrue();
            assertThat(marks).hasSize(EXPECTED_ROW_COUNT);
            assertThat(marks.get(8).asText()).isEqualTo("D");
            assertThat(marks.get(0).asText()).isEqualTo(" ");
        }

        @Test
        @DisplayName("an empty mark sequence is written as an empty array rather than omitted, and "
                + "every absent component is omitted")
        void anEmptyMarkSequenceIsWrittenAsAnEmptyArray() throws JsonProcessingException {
            JsonNode payload = payloadOf(carryingSelections(null));

            assertThat(payload.has("rowSelections")).isTrue();
            assertThat(payload.get("rowSelections")).isEmpty();
            assertThat(payload.size())
                    .as("the other eleven components are absent and are therefore omitted")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an explicitly blanked component is written while an absent one is omitted, so "
                + "the two states stay distinguishable on the wire")
        void ablankedComponentIsWrittenWhileAnAbsentOneIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("firstName", ""));

            assertThat(payload.get("firstName").asText()).isEmpty();
            assertThat(payload.has("lastName")).isFalse();
        }

        /**
         * The round trip is deliberately asymmetric, and the asymmetry is exactly two components.
         *
         * <p>Two components are bound in one direction only, for opposite reasons, and a round trip is
         * where both become visible at once. The credential is write-only, so serialising drops it. The
         * displayed page number is read-only, so deserialising ignores it - the legacy program computes
         * that number entirely from its own retained counter, so a submitted value could never have
         * influenced a page, and accepting one would create an input the legacy never had.</p>
         *
         * <p>A round trip therefore cannot return an equal instance. What is asserted instead is that
         * the loss is precisely the three directionally bound components and everything else survives,
         * which is a stronger
         * statement than equality, because it names what may change and would fail if a third component
         * silently acquired a directional binding.</p>
         */
        @Test
        @DisplayName("a round trip loses the write-only credential and page token plus the read-only "
                + "page number, and returns every other component unchanged")
        void aFullyPopulatedRequestRoundTripsWithoutItsDirectionalComponents()
                throws JsonProcessingException {
            UserRequest request = fullyPopulated();
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();

            UserRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), UserRequest.class);

            assertThat(returned)
                    .as("three components are bound in one direction only, so equality cannot hold")
                    .isNotEqualTo(request);
            assertThat(returned.password())
                    .as("the credential is write-only, so serialising dropped it")
                    .isNull();
            assertThat(returned.displayedPageNumber())
                    .as("the page number is read-only, so deserialising ignored it")
                    .isNull();
            assertThat(returned.rowSnapshotToken())
                    .as("the page token is write-only on the request, so serialising dropped it")
                    .isNull();

            assertThat(returned)
                    .as("everything else survives, so the loss is exactly those three")
                    .isEqualTo(new UserRequest(request.userId(), request.searchUserId(),
                            request.firstName(), request.lastName(), null, request.userType(),
                            request.rowSelections(), null, request.firstUserIdOnPage(),
                            request.lastUserIdOnPage(), null, request.keyAction(),
                            request.navigationContext()));
            assertThat(returned.rowSelections()).containsExactlyElementsOf(request.rowSelections());
        }

        @Test
        @DisplayName("a null entry inside an inbound mark array is refused, so the constructor's "
                + "refusal is not bypassed by deserialisation")
        void aNullEntryInsideAnInboundArrayIsRefused() {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload = "{\"rowSelections\":[\" \",null]}";

            assertThatThrownBy(() -> mapper.readValue(payload, UserRequest.class))
                    .isInstanceOf(JsonProcessingException.class)
                    .hasRootCauseInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an unknown member a client echoes back is ignored rather than rejected")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"userId\":\"ADMIN001\",\"rows\":[],\"infoMessage\":\"x\"}";

            UserRequest returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, UserRequest.class);

            assertThat(returned.userId()).isEqualTo("ADMIN001");
            assertThat(returned.rowSelections()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Diagnostic redaction")
    class DiagnosticRedaction {

        /** The nine components the rendering withholds, in declaration order. */
        private static final List<String> WITHHELD_BY_THIS_TYPE = List.of(
                "userId", "searchUserId", "firstName", "lastName", "password", "userType",
                "firstUserIdOnPage", "lastUserIdOnPage", "navigationContext");

        /**
         * Nine of the twelve components are withheld and three are shown.
         *
         * <p>Every withheld component identifies or authenticates a person: an administrator's own
         * identifier, the identifier being searched for, the two name halves, the credential, the role,
         * the two retained page keys - which are themselves user identifiers - and the echoed navigation
         * state. What remains is the shape of the request rather than its subject: how many row slots
         * were submitted, which page the screen was showing, and which key was pressed. That is enough
         * to tell one request from another in a diagnostic while naming nobody, which is why the
         * narrower posture was taken rather than withholding the credential alone.</p>
         *
         * <p>The row marks are replaced by their count rather than withheld under their own name,
         * because a count of submitted slots identifies nobody and is the single most useful fact about
         * a user-list submission. The count is asserted separately below in row order.</p>
         */
        @Test
        @DisplayName("nine components are withheld and the three that describe the request's shape "
                + "rather than its subject are shown")
        void theIdentifyingComponentsAreWithheldAndTheShapeIsShown() {
            String rendered = fullyPopulated().toString();

            assertThat(rendered)
                    .startsWith("UserRequest[")
                    .doesNotContain(CREDENTIAL)
                    .doesNotContain("ADMIN001")
                    .doesNotContain("SRCH0001")
                    .doesNotContain("FIRSTNAMEEXACTLY20AB")
                    .doesNotContain("LASTNAMEEXACTLY20ABC")
                    .doesNotContain("PAGEFRST")
                    .doesNotContain("PAGELAST")
                    .contains("rowSelectionCount=10")
                    .contains("displayedPageNumber=00000002")
                    .contains("keyAction=PFK08");

            for (String component : WITHHELD_BY_THIS_TYPE) {
                assertThat(rendered)
                        .as("%s is withheld", component)
                        .contains(component + "=" + EXPECTED_PLACEHOLDER);
            }
        }

        /**
         * Withholding is confined to the rendering; every accessor is unchanged.
         *
         * <p>This is what keeps the wider posture above from being a behavioural regression. The
         * service still receives every value the client sent, and only the diagnostic channel declines
         * to repeat them.</p>
         */
        @Test
        @DisplayName("every withheld component is still returned unaltered by its accessor")
        void theWiderWithholdingIsConfinedToTheRendering() {
            UserRequest request = fullyPopulated();

            assertThat(request.userId()).isEqualTo("ADMIN001");
            assertThat(request.searchUserId()).isEqualTo("SRCH0001");
            assertThat(request.firstName()).isEqualTo("FIRSTNAMEEXACTLY20AB");
            assertThat(request.lastName()).isEqualTo("LASTNAMEEXACTLY20ABC");
            assertThat(request.userType()).isEqualTo("A");
            assertThat(request.firstUserIdOnPage()).isEqualTo("PAGEFRST");
            assertThat(request.lastUserIdOnPage()).isEqualTo("PAGELAST");
            assertThat(request.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        @Test
        @DisplayName("the placeholder does not vary with the credential, so neither its characters "
                + "nor its length is recoverable")
        void thePlaceholderDoesNotVaryWithTheCredential() {
            String renderedShort = carrying("password", "a").toString();
            String renderedFull = carrying("password", CREDENTIAL).toString();

            assertThat(renderedShort)
                    .as("for an item this narrow, disclosing the length would materially narrow a "
                            + "guess, which is why a length-preserving mask was rejected")
                    .isEqualTo(renderedFull);
        }

        @Test
        @DisplayName("an absent credential renders as the same placeholder, so absence and presence "
                + "are indistinguishable in a diagnostic")
        void absenceAndPresenceAreIndistinguishable() {
            assertThat(carrying("userId", "ADMIN001").toString())
                    .contains("password=" + EXPECTED_PLACEHOLDER);
        }

        @Test
        @DisplayName("no branch of the rendering can emit the credential, including when it is blank "
                + "or entirely spaces")
        void noBranchOfTheRenderingCanEmitTheCredential() {
            for (String candidate : List.of("", " ", "        ", "PASSWORD", CREDENTIAL)) {
                assertThat(carrying("password", candidate).toString())
                        .as("credential candidate %s", candidate.isBlank() ? "<blank>" : candidate)
                        .contains("password=" + EXPECTED_PLACEHOLDER);
            }
        }

        /**
         * The rendering states how many marks were submitted rather than which they were.
         *
         * <p>A mark is a one-character action code and discloses nothing by itself, but its position is
         * a row index and a row on a user-list page is a person. Emitting the sequence would therefore
         * have said which row an administrator acted on, and paired with the page number that names a
         * user. The count keeps what a diagnostic actually needs - that a submission carried ten slots
         * rather than three, which is the difference between a full page and a truncated one - and drops
         * what it does not. The sequence itself is still reachable through the accessor and still
         * travels on the wire in row order, both asserted elsewhere.</p>
         */
        @Test
        @DisplayName("the rendering states the number of marks submitted rather than the marks "
                + "themselves, because a mark's position is a row and a row is a person")
        void theMarkCountIsRenderedRatherThanTheMarks() {
            assertThat(carryingSelections(List.of("a", "b", "c")).toString())
                    .contains("rowSelectionCount=3")
                    .doesNotContain("rowSelections=[a, b, c]");
            assertThat(carryingSelections(tenRowsWithOneMark(8, "D")).toString())
                    .as("a full page and a short one are still distinguishable")
                    .contains("rowSelectionCount=" + EXPECTED_ROW_COUNT);
            assertThat(carryingSelections(null).toString())
                    .as("an absent sequence is an empty one, so the count is zero rather than a "
                            + "placeholder")
                    .contains("rowSelectionCount=0");
            assertThat(carryingSelections(List.of("a", "b", "c")).rowSelections())
                    .as("the sequence itself is unchanged behind the rendering")
                    .containsExactly("a", "b", "c");
        }

        /**
         * The navigation state is withheld whole here, and withholds its own values when reached
         * directly.
         *
         * <p>Two lines of defence, asserted together. This type substitutes the whole nested record, so
         * not even the presentational parts of it - the transaction and program names it carries - reach
         * the rendering; that is the necessary posture, because the nested record's own rendering
         * retains those and a future change to it must not be able to widen what surfaces here. Reached
         * directly, the same instance still withholds its six identifying components, so neither path
         * discloses a customer or an account.</p>
         */
        @Test
        @DisplayName("the navigation state is withheld whole by this type, and withholds its own "
                + "identifying values when reached directly")
        void aNestedNavigationStateWithholdsItsOwnValues() {
            assertThat(fullyPopulated().toString())
                    .as("the nested record is substituted whole, so this type cannot become the "
                            + "path by which any part of it surfaces")
                    .contains("navigationContext=" + EXPECTED_PLACEHOLDER)
                    .doesNotContain("NavigationContext[")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);

            assertThat(JsonContractSupport.populatedNavigation().toString())
                    .as("reached directly the nested record still withholds its own six")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }

        @Test
        @DisplayName("withholding is confined to the rendering path: the accessor returns the "
                + "credential exactly as supplied")
        void withholdingIsConfinedToTheRenderingPath() {
            assertThat(carrying("password", CREDENTIAL).password())
                    .as("the service hashes what the client sent, so no value may be masked outside "
                            + "the rendering path")
                    .isEqualTo(CREDENTIAL);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component by value, credential included, because "
                + "equality is an in-memory operation that emits nothing")
        void equalityComparesEveryComponentByValue() {
            UserRequest left = carrying("password", CREDENTIAL);
            UserRequest right = carrying("password", CREDENTIAL);
            UserRequest different = carrying("password", "OTHERPWD");

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
            assertThat(left).isNotEqualTo(different);
        }

        @Test
        @DisplayName("the same mark on a different row is a different submission")
        void theSameMarkOnADifferentRowIsADifferentSubmission() {
            assertThat(carryingSelections(tenRowsWithOneMark(0, "U")))
                    .isNotEqualTo(carryingSelections(tenRowsWithOneMark(1, "U")));
        }

        @Test
        @DisplayName("an absent mark sequence and an empty one compare equal, because the "
                + "constructor normalises the first into the second")
        void anAbsentMarkSequenceEqualsAnEmptyOne() {
            assertThat(carryingSelections(null))
                    .isEqualTo(carryingSelections(List.of()))
                    .hasSameHashCodeAs(carryingSelections(List.of()));
        }

        @Test
        @DisplayName("a blank subject identifier and an absent one do not compare equal, because the "
                + "items they mirror are fixed-width and blank-significant")
        void aBlankSubjectIdentifierIsNotAnAbsentOne() {
            assertThat(carrying("userId", "")).isNotEqualTo(
                    new UserRequest(null, null, null, null, null, null, null, null, null, null,
                            null, null));
        }
    }
}
