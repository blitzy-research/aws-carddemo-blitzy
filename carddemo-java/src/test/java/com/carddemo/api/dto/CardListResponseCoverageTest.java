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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import com.carddemo.api.dto.CardListResponse.CardListRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link CardListResponse} and its nested {@link CardListRow}, the response body of
 * legacy transaction {@code CCLI}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the card-list page: the twenty components in the order the screen
 * presents them, the thirteen declared widths, the four-component row and its four widths, the nine
 * screen texts, the two list normalisations the canonical constructor performs, the wire form under
 * the module's declared serialisation settings, and the value semantics of a page that orders nothing
 * and interprets nothing.
 *
 * <h2>A short page stays short, and no filler row is ever manufactured</h2>
 *
 * <p>Seven is the screen's geometry, and it lives in {@link PageMetadata#CARD_LIST_PAGE_SIZE} rather
 * than being re-declared here. The response neither extends a shorter list to that shape nor caps a
 * longer one: a page with three qualifying cards carries three rows, and the blank screen lines the
 * legacy left behind are an absence rather than three blank row objects. Tests below prove a short
 * list stays its own length, prove no row-count constant is declared in this type, and prove the
 * seven is obtained from the shared paging record.
 *
 * <h2>Row order is emitted verbatim, because a backward page arrives already inverted</h2>
 *
 * <p>A forward page is read ascending and fills the screen top-down; a backward page is read
 * descending and fills the last slot up to the first, so the service hands over a list that is
 * already in presentation order for both directions. Any sorting applied here would invert a backward
 * page that was already correct. That is the single most consequential fact in the file, and a test
 * below submits a deliberately descending list and proves it emerges descending.
 *
 * <h2>The selection indicator is positional, so an unmarked slot is present and false</h2>
 *
 * <p>The legacy converts the selection column into a positional indicator in which every slot that
 * contributed to the more-than-one-action rejection is marked and every other slot is explicitly
 * unmarked. A list preserves that alignment where a set or a keyed structure would not: element
 * <em>i</em> describes row <em>i</em>, and compacting the false entries away would destroy the
 * alignment that lets the screen mark the offending rows. Tests below prove the false entries survive
 * and prove index alignment is preserved element for element.
 *
 * <h2>The punctuation genuinely differs between messages, and is not one rule applied badly</h2>
 *
 * <p>Neither filter text carries a space after its comma and both read "A" where English would use
 * "AN"; the row-action prompt, by contrast, does carry a space after its comma. Exactly one of the
 * nine texts ends in a full stop. Each of those is asserted individually, because a reader who
 * noticed only the filter texts would "fix" the prompt and a reader who noticed only the prompt would
 * "fix" the filter texts.
 *
 * <h2>The informational width is forty-five here and forty on the card-detail screen</h2>
 *
 * <p>The two screens sit beside one another and their message widths differ in both slots: forty-five
 * against forty on the informational line, and seventy-eight against eighty on the error line.
 * Neither is derived from the other, and a test below states the asymmetry as an assertion across the
 * two types so that unifying them fails here rather than in an output comparison.
 *
 * <h2>Every card number travels intact on the wire, and none of them reaches the rendering</h2>
 *
 * <p>The two channels are deliberately different and the tests keep them apart. The serialised
 * response carries every card number in full with its leading zeros intact, because that is the
 * external contract the screen was given and narrowing it would be an unrequested behavioural change;
 * the legacy design applied no field-level protection to a card number anywhere, and that gap is
 * recorded in {@code docs/decision-log.md} rather than closed here.
 *
 * <p>The diagnostic rendering is not an external contract, so both this type and its row override it
 * and withhold what they carry. This type substitutes four components - the echoed account filter, the
 * echoed card filter, the row list and the browse position - and states the row count in place of the
 * list, because the number of rows on a page is the most useful fact in a diagnostic and identifies
 * nobody. The row substitutes the two identifiers it carries and keeps its action code and status
 * visible. Every substitution is unconditional, so an absent value is indistinguishable from a present
 * one and presence itself is not disclosed.
 *
 * <p>The row list and the browse position are each withheld <em>whole</em> rather than through their
 * own renderings. For the browse position that is necessary: its own rendering retains the direction
 * and the page shape, and this type must not become the path by which a future change to it reopens a
 * disclosure. For the row list it is a second line of defence, since each row already withholds its
 * own identifiers. Two further controls live in the nested types and are asserted alongside: a nested
 * {@link NavigationContext} withholds its own identifying components, and a {@link PageMetadata}
 * reached directly still withholds both browse cursor keys, which are themselves a card number
 * followed by an account identifier.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>A pure unit test: no context, no connection, no container. Payloads come from
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in this package where the
 * module's four declared serialisation settings are written by hand. That evidences the shape this
 * type takes under those settings and nothing more; it is not evidence about the mapper a deployed
 * instance holds, and no assertion below is worded as though it were.
 * {@link ApplicationJsonContractTest} is the in-boundary evidence for the deployed object.
 *
 * <p>Provenance: checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.
 */
@DisplayName("CardListResponse :: card-list page contract of legacy transaction CCLI")
class CardListResponseCoverageTest {

    /** The twenty-one components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "displayedPageNumber", "accountFilter", "cardNumberFilter", "rows", "selectionErrorFlags",
            "infoMessage", "errorMessage", "generalError", "pageMetadata", "lastPageAlreadyShown",
            "fieldErrors", "focusScreenFieldId", "nextRoute",
            "navigationContext", "rowSnapshotToken");

    /** The fifteen components that declare a width bound. */
    private static final List<String> BOUNDED_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "displayedPageNumber", "accountFilter", "cardNumberFilter", "infoMessage", "errorMessage",
            "focusScreenFieldId");

    /**
     * The five components that appear on the wire even when nothing was supplied for them: the two
     * primitive indicators, which cannot be absent, and the three collections, which the canonical
     * constructor normalises from {@code null} to an empty list so that a screen carrying no rows,
     * no positional selection marks and no field errors is distinguishable from one whose lists were
     * never established.
     */
    private static final List<String> ALWAYS_WRITTEN_COMPONENTS = List.of(
            "rows", "selectionErrorFlags", "generalError", "lastPageAlreadyShown", "fieldErrors");

    /** The five row components in declaration order. */
    private static final List<String> EXPECTED_ROW_COMPONENTS =
            List.of("screenSlot", "selection", "accountNumber", "cardNumber", "cardStatus");

    /** The four row components that are bounded text; the screen slot is a position, not a field. */
    private static final List<String> EXPECTED_ROW_TEXT_COMPONENTS =
            List.of("selection", "accountNumber", "cardNumber", "cardStatus");

    /** Transaction name shown in the screen header. */
    private static final String TRANSACTION_NAME = "CCLI";

    /** Program name shown in the screen header. */
    private static final String PROGRAM_NAME = "COCRDLIC";

    /** First title line, at the declared forty characters. */
    private static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /** Second title line, at the declared forty characters. */
    private static final String TITLE_02 = "              CardDemo                  ";

    /** Header date, at the declared eight characters. */
    private static final String CURRENT_DATE = "08/02/26";

    /** Header time, at the declared eight characters. */
    private static final String CURRENT_TIME = "14:35:07";

    /** Displayed page indicator, at the declared three characters. */
    private static final String DISPLAYED_PAGE_NUMBER = "001";

    /** Eleven-character account filter whose leading zeros are contract. */
    private static final String ACCOUNT_FILTER = "00000000011";

    /** Sixteen-character card filter whose leading zeros are contract. */
    private static final String CARD_FILTER = "0000000000000001";

    /** Identity of the map field the screen puts attention on, at the declared seven characters. */
    private static final String FOCUS_SCREEN_FIELD_ID = "CRDSEL1";

    /** Opaque next-call token. */
    private static final String NEXT_ROUTE = "/api/cards/detail";

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
     * The nine published screen texts, in declaration order.
     *
     * @return the screen texts
     */
    static Stream<String> screenTexts() {
        return Stream.of(
                CardListResponse.MSG_ACCOUNT_FILTER_INVALID,
                CardListResponse.MSG_CARD_FILTER_INVALID,
                CardListResponse.MSG_INVALID_ACTION_CODE,
                CardListResponse.MSG_MORE_THAN_ONE_ACTION,
                CardListResponse.MSG_NO_PREVIOUS_PAGES,
                CardListResponse.MSG_NO_MORE_PAGES,
                CardListResponse.MSG_NO_MORE_RECORDS,
                CardListResponse.MSG_NO_RECORDS_FOUND,
                CardListResponse.MSG_ROW_ACTION_PROMPT);
    }

    /**
     * Builds one card row.
     *
     * @param selection the echoed action code
     * @param accountNumber the eleven-character account identifier
     * @param cardNumber the sixteen-character card number
     * @param cardStatus the raw status character
     * @return the row
     */
    private static CardListRow row(String selection, String accountNumber, String cardNumber,
            String cardStatus) {
        return new CardListRow(1,selection, accountNumber, cardNumber, cardStatus);
    }

    /** @return three rows in the order a forward page presents them. */
    private static List<CardListRow> threeAscendingRows() {
        return List.of(
                row("S", "00000000011", "0000000000000001", "Y"),
                row("", "00000000012", "0000000000000002", "N"),
                row("", "00000000013", "0000000000000003", "Q"));
    }

    /** @return the browse position a forward first page establishes. */
    private static PageMetadata forwardFirstPage() {
        return PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE, null, "0000000000000003",
                true, false, DISPLAYED_PAGE_NUMBER);
    }

    /**
     * Builds a response carrying only the named text component, so a violation can be attributed to
     * one property rather than inferred from a set.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a response carrying that one value
     */
    private static CardListResponse carrying(String component, String value) {
        return new CardListResponse(
                "transactionName".equals(component) ? value : null,
                "title01".equals(component) ? value : null,
                "currentDate".equals(component) ? value : null,
                "programName".equals(component) ? value : null,
                "title02".equals(component) ? value : null,
                "currentTime".equals(component) ? value : null,
                "displayedPageNumber".equals(component) ? value : null,
                "accountFilter".equals(component) ? value : null,
                "cardNumberFilter".equals(component) ? value : null,
                null, null,
                "infoMessage".equals(component) ? value : null,
                "errorMessage".equals(component) ? value : null,
                false, null, false, List.of(),
                "focusScreenFieldId".equals(component) ? value : null,
                "nextRoute".equals(component) ? value : null,
                null, null);
    }

    /**
     * Builds a response carrying only the two lists, so list behaviour can be observed alone.
     *
     * @param rows the rows to carry, which may be {@code null}
     * @param flags the positional selection indicator, which may be {@code null}
     * @return a response carrying those two lists
     */
    private static CardListResponse withLists(List<CardListRow> rows, List<Boolean> flags) {
        return new CardListResponse(null, null, null, null, null, null, null, null, null, rows,
                flags, null, null, false, null, false, List.of(), null, null, null, null);
    }

    /**
     * Builds the response a filled forward page produces, with every component populated.
     *
     * @param navigation the navigation state to echo, which may be {@code null}
     * @return a fully populated page response
     */
    private static CardListResponse filledPage(NavigationContext navigation) {
        return new CardListResponse(TRANSACTION_NAME, TITLE_01, CURRENT_DATE, PROGRAM_NAME, TITLE_02,
                CURRENT_TIME, DISPLAYED_PAGE_NUMBER, ACCOUNT_FILTER, CARD_FILTER, threeAscendingRows(),
                List.of(false, false, false), CardListResponse.MSG_ROW_ACTION_PROMPT, null, false,
                forwardFirstPage(), false, List.of(), FOCUS_SCREEN_FIELD_ID, NEXT_ROUTE, navigation, null);
    }

    /**
     * Builds a filled page that carries neither nested record, so the diagnostic rendering contains
     * only what this type itself contributes.
     *
     * @return a populated page with no browse position and no echoed navigation state
     */
    private static CardListResponse filledPageWithoutNestedRecords() {
        return new CardListResponse(TRANSACTION_NAME, TITLE_01, CURRENT_DATE, PROGRAM_NAME, TITLE_02,
                CURRENT_TIME, DISPLAYED_PAGE_NUMBER, ACCOUNT_FILTER, CARD_FILTER, threeAscendingRows(),
                List.of(false, false, false), CardListResponse.MSG_ROW_ACTION_PROMPT, null, false,
                null, false, List.of(), FOCUS_SCREEN_FIELD_ID, NEXT_ROUTE, null, null);
    }

    /**
     * Serialises a response and parses the result back into a tree.
     *
     * @param response the response to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(CardListResponse response) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the twenty components are declared in the order the screen presents them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(CardListResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("the thirteen published widths equal the widths the map and the program declare")
        void publishedWidthsEqualTheDeclaredWidths() {
            assertThat(CardListResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(CardListResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(CardListResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(3);
            assertThat(CardListResponse.ACCOUNT_NUMBER_LENGTH).isEqualTo(11);
            assertThat(CardListResponse.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(CardListResponse.SELECTION_LENGTH).isEqualTo(1);
            assertThat(CardListResponse.CARD_STATUS_LENGTH).isEqualTo(1);
            assertThat(CardListResponse.INFO_MESSAGE_LENGTH).isEqualTo(45);
            assertThat(CardListResponse.ERROR_MESSAGE_LENGTH).isEqualTo(78);
            assertThat(CardListResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("twelve components are bounded and the two title lines share one width")
        void theBoundedComponentsAreTheOnesExpected() throws NoSuchFieldException {
            List<String> bounded = new ArrayList<>();
            for (String component : EXPECTED_COMPONENTS) {
                if (CardListResponse.class.getDeclaredField(component)
                        .getAnnotation(Size.class) != null) {
                    bounded.add(component);
                }
            }

            assertThat(bounded).containsExactlyElementsOf(BOUNDED_COMPONENTS);
            assertThat(boundOf("title01"))
                    .isEqualTo(boundOf("title02"))
                    .isEqualTo(CardListResponse.SCREEN_TITLE_LENGTH);
        }

        @Test
        @DisplayName("each bounded component declares the width its own constant publishes")
        void eachBoundedComponentDeclaresItsPublishedWidth() throws NoSuchFieldException {
            assertThat(boundOf("transactionName"))
                    .isEqualTo(CardListResponse.TRANSACTION_NAME_LENGTH);
            assertThat(boundOf("currentDate")).isEqualTo(CardListResponse.CURRENT_DATE_LENGTH);
            assertThat(boundOf("programName")).isEqualTo(CardListResponse.PROGRAM_NAME_LENGTH);
            assertThat(boundOf("currentTime")).isEqualTo(CardListResponse.CURRENT_TIME_LENGTH);
            assertThat(boundOf("displayedPageNumber")).isEqualTo(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH);
            assertThat(boundOf("accountFilter")).isEqualTo(CardListResponse.ACCOUNT_NUMBER_LENGTH);
            assertThat(boundOf("cardNumberFilter")).isEqualTo(CardListResponse.CARD_NUMBER_LENGTH);
            assertThat(boundOf("infoMessage")).isEqualTo(CardListResponse.INFO_MESSAGE_LENGTH);
            assertThat(boundOf("errorMessage")).isEqualTo(CardListResponse.ERROR_MESSAGE_LENGTH);
            assertThat(boundOf("focusScreenFieldId")).isEqualTo(CardListResponse.SCREEN_FIELD_ID_LENGTH);
        }

        @Test
        @DisplayName("no row-count value is declared here, because the screen's row shape is the "
                + "shared paging record's constant")
        void noRowCountValueIsDeclaredHere() {
            List<String> staticNames = Arrays.stream(CardListResponse.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .map(Field::getName)
                    .toList();

            assertThat(staticNames)
                    .as("re-declaring seven here would create a second authority for the screen's "
                            + "row shape, and the two could then disagree")
                    .noneMatch(name -> name.contains("PAGE_SIZE"))
                    .noneMatch(name -> name.contains("ROW_COUNT"))
                    .noneMatch(name -> name.contains("MAX_ROWS"));
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE).isEqualTo(7);
        }

        @Test
        @DisplayName("the two lists, the paging record and the navigation state are the declared "
                + "collaborator types")
        void theCollaboratorComponentsAreTyped() {
            assertThat(componentType("rows")).isEqualTo(List.class);
            assertThat(componentType("selectionErrorFlags")).isEqualTo(List.class);
            assertThat(componentType("generalError")).isEqualTo(boolean.class);
            assertThat(componentType("pageMetadata")).isEqualTo(PageMetadata.class);
            assertThat(componentType("navigationContext")).isEqualTo(NavigationContext.class);
        }

        @Test
        @DisplayName("the next route carries no width bound, because a route is not a legacy screen "
                + "field")
        void theNextRouteCarriesNoWidthBound() throws NoSuchFieldException {
            assertThat(CardListResponse.class.getDeclaredField("nextRoute").getAnnotations())
                    .as("the token vocabulary belongs to the navigation service, not to a map item")
                    .isEmpty();
        }

        @Test
        @DisplayName("one constructor exists and it takes every component, so the compact form "
                + "normalises rather than overloads")
        void oneConstructorExistsAndTakesEveryComponent() {
            assertThat(CardListResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(CardListResponse.class.getDeclaredConstructors()[0].getParameterCount())
                    .isEqualTo(EXPECTED_COMPONENTS.size());
        }

        @Test
        @DisplayName("no member is declared beyond the twenty-one accessors, so this type chooses no "
                + "message and orders no row")
        void noMemberIsDeclaredBeyondTheAccessors() {
            List<String> instanceMethods = Arrays.stream(CardListResponse.class.getDeclaredMethods())
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .map(Method::getName)
                    .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
                    .toList();

            assertThat(instanceMethods)
                    .as("message selection, action tallying and paging all belong to the card-list "
                            + "service, which owns the ordering the legacy produced")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("the row declares its screen slot and four text components in map order, each of "
                + "the four bounded by the enclosing type's width")
        void theRowDeclaresItsSlotAndFourBoundedTextComponents() throws NoSuchFieldException {
            List<String> declared = Arrays.stream(CardListRow.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_ROW_COMPONENTS);
            assertThat(CardListRow.class.getDeclaredField("screenSlot").getType())
                    .as("the slot is the screen position the row occupies, so it is a number rather "
                            + "than one more fixed-width field")
                    .isEqualTo(int.class);
            for (String component : EXPECTED_ROW_TEXT_COMPONENTS) {
                assertThat(CardListRow.class.getDeclaredField(component).getType())
                        .as("%s stays text so leading zeros and an unrecognised status character "
                                + "both survive the round trip", component)
                        .isEqualTo(String.class);
            }
            assertThat(rowBoundOf("selection")).isEqualTo(CardListResponse.SELECTION_LENGTH);
            assertThat(rowBoundOf("accountNumber"))
                    .isEqualTo(CardListResponse.ACCOUNT_NUMBER_LENGTH);
            assertThat(rowBoundOf("cardNumber")).isEqualTo(CardListResponse.CARD_NUMBER_LENGTH);
            assertThat(rowBoundOf("cardStatus")).isEqualTo(CardListResponse.CARD_STATUS_LENGTH);
        }

        @Test
        @DisplayName("the row is implicitly static and declares nothing beyond its four accessors, "
                + "so it holds no reference to a page and interprets no code")
        void theRowIsStaticAndDeclaresNothingBeyondItsAccessors() {
            assertThat(Modifier.isStatic(CardListRow.class.getModifiers()))
                    .as("a record nested in a record is implicitly static, so a row cannot retain "
                            + "the page that carried it")
                    .isTrue();
            assertThat(CardListRow.class.getDeclaredConstructors()).hasSize(1);

            List<String> instanceMethods = Arrays.stream(CardListRow.class.getDeclaredMethods())
                    .filter(method -> !Modifier.isStatic(method.getModifiers()))
                    .map(Method::getName)
                    .filter(name -> !List.of("equals", "hashCode", "toString").contains(name))
                    .toList();

            assertThat(instanceMethods).containsExactlyInAnyOrderElementsOf(EXPECTED_ROW_COMPONENTS);
        }

        /**
         * Reads the declared width bound of a response component.
         *
         * @param component the component name
         * @return the maximum length the component declares
         * @throws NoSuchFieldException if the component does not exist
         */
        private static int boundOf(String component) throws NoSuchFieldException {
            return CardListResponse.class.getDeclaredField(component)
                    .getAnnotation(Size.class)
                    .max();
        }

        /**
         * Reads the declared width bound of a row component.
         *
         * @param component the component name
         * @return the maximum length the component declares
         * @throws NoSuchFieldException if the component does not exist
         */
        private static int rowBoundOf(String component) throws NoSuchFieldException {
            return CardListRow.class.getDeclaredField(component).getAnnotation(Size.class).max();
        }

        /**
         * Reads the declared type of a response component.
         *
         * @param component the component name
         * @return the component's declared type
         */
        private static Class<?> componentType(String component) {
            return Arrays.stream(CardListResponse.class.getRecordComponents())
                    .filter(candidate -> candidate.getName().equals(component))
                    .findFirst()
                    .orElseThrow()
                    .getType();
        }
    }

    @Nested
    @DisplayName("Page shape")
    class PageShape {

        @Test
        @DisplayName("a short page stays short, so no blank filler row is manufactured")
        void aShortPageStaysShort() {
            CardListResponse response = withLists(threeAscendingRows(), List.of());

            assertThat(response.rows())
                    .as("the legacy left the remaining screen lines blank, which is an absence "
                            + "rather than four blank row objects")
                    .hasSize(3)
                    .doesNotContainNull();
            assertThat(response.rows()).hasSizeLessThan(PageMetadata.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("a full page carries exactly the screen's row shape without the record having "
                + "to know that number")
        void aFullPageCarriesTheScreenRowShape() {
            List<CardListRow> seven = new ArrayList<>();
            for (int index = 1; index <= PageMetadata.CARD_LIST_PAGE_SIZE; index++) {
                seven.add(row("", "0000000001" + index, "000000000000000" + index, "Y"));
            }

            assertThat(withLists(seven, List.of()).rows())
                    .hasSize(PageMetadata.CARD_LIST_PAGE_SIZE);
        }

        /**
         * A list deeper than the screen is refused rather than carried or truncated.
         *
         * <p>The screen has a fixed number of row slots, so a page holding more rows than that cannot
         * be presented and represents a service defect. Truncating would hide it; carrying it would
         * defer the failure to whichever layer next assumed the page fitted. Refusing states the
         * invariant where it can still name the offending count, and the message is asserted to carry
         * that count so the diagnostic is actionable.</p>
         */
        @Test
        @DisplayName("a list deeper than the screen is refused, and the refusal names how many rows "
                + "arrived")
        void aLongerListIsRefusedRatherThanTruncated() {
            List<CardListRow> eight = new ArrayList<>();
            for (int index = 1; index <= PageMetadata.CARD_LIST_PAGE_SIZE + 1; index++) {
                eight.add(row("", "0000000001" + index, "00000000000000" + index, "Y"));
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withLists(eight, List.of()))
                    .withMessageContaining("at most " + PageMetadata.CARD_LIST_PAGE_SIZE)
                    .withMessageContaining("row slots the card-list screen has")
                    .withMessageContaining("it holds " + (PageMetadata.CARD_LIST_PAGE_SIZE + 1));
        }

        @Test
        @DisplayName("rows are emitted in the order they arrived, a deliberately descending "
                + "backward page included")
        void rowsAreEmittedInTheOrderTheyArrived() {
            List<CardListRow> descending = List.of(
                    row("", "00000000013", "0000000000000003", "Q"),
                    row("", "00000000012", "0000000000000002", "N"),
                    row("S", "00000000011", "0000000000000001", "Y"));

            assertThat(withLists(descending, List.of()).rows())
                    .as("a backward page is already in presentation order, so sorting here would "
                            + "invert a page that was correct")
                    .containsExactlyElementsOf(descending);
        }

        @Test
        @DisplayName("an absent row list becomes an empty one, so no accessor ever answers null")
        void anAbsentRowListBecomesAnEmptyOne() {
            CardListResponse response = withLists(null, null);

            assertThat(response.rows()).isNotNull().isEmpty();
            assertThat(response.selectionErrorFlags()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("both lists are defensively copied, so a caller that later changes what it "
                + "passed in cannot change what the response reports")
        void bothListsAreDefensivelyCopied() {
            List<CardListRow> mutableRows = new ArrayList<>(threeAscendingRows());
            List<Boolean> mutableFlags = new ArrayList<>(List.of(true, false, false));
            CardListResponse response = withLists(mutableRows, mutableFlags);

            mutableRows.clear();
            mutableFlags.clear();

            assertThat(response.rows()).hasSize(3);
            assertThat(response.selectionErrorFlags()).containsExactly(true, false, false);
        }

        @Test
        @DisplayName("neither accessor hands out a modifiable view")
        void neitherAccessorHandsOutAModifiableView() {
            CardListResponse response = withLists(threeAscendingRows(), List.of(true, false, false));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.rows().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.selectionErrorFlags().clear());
        }

        @Test
        @DisplayName("a null row element is refused, because a missing row is a shorter list rather "
                + "than a null placeholder")
        void aNullRowElementIsRefused() {
            List<CardListRow> withNull = new ArrayList<>();
            withNull.add(row("", "00000000011", "0000000000000001", "Y"));
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .as("the immutable copy admits no null element, and no null element is "
                            + "meaningful on this screen")
                    .isThrownBy(() -> withLists(withNull, null));
        }
    }

    @Nested
    @DisplayName("Selection indicator")
    class SelectionIndicator {

        @Test
        @DisplayName("an unmarked slot is present and false rather than absent, so alignment "
                + "survives")
        void anUnmarkedSlotIsPresentAndFalse() {
            CardListResponse response =
                    withLists(threeAscendingRows(), List.of(false, true, false));

            assertThat(response.selectionErrorFlags())
                    .as("compacting the false entries away would destroy the alignment that lets "
                            + "the screen mark the offending rows")
                    .containsExactly(false, true, false)
                    .hasSameSizeAs(response.rows());
        }

        @Test
        @DisplayName("element i describes row i, so the indicator can be read against the page "
                + "position for position")
        void elementIndexDescribesRowIndex() {
            CardListResponse response =
                    withLists(threeAscendingRows(), List.of(true, false, true));

            for (int index = 0; index < response.rows().size(); index++) {
                boolean marked = response.selectionErrorFlags().get(index);
                String selection = response.rows().get(index).selection();

                assertThat(marked)
                        .as("position %d of the indicator lines up with position %d of the page",
                                index, index)
                        .isEqualTo(index != 1);
                assertThat(selection).isNotNull();
            }
        }

        @Test
        @DisplayName("an all-unmarked indicator is retained rather than collapsed to an empty list")
        void anAllUnmarkedIndicatorIsRetained() {
            assertThat(withLists(threeAscendingRows(), List.of(false, false, false))
                            .selectionErrorFlags())
                    .as("\"nothing offended\" and \"nothing was evaluated\" are different states")
                    .hasSize(3)
                    .containsOnly(false);
        }

        /**
         * The indicator is positional, so its length is cross-checked against the page.
         *
         * <p>Each entry marks the row at the same index. An indicator of a different length therefore
         * has no defined meaning: reading it would attribute an offence to the wrong row, or to no row
         * at all. Two lengths are admissible and both are asserted - empty, meaning nothing was
         * evaluated, and exactly one entry per row, meaning every row was. Anything between is
         * refused, and the refusal names both counts so the mismatch is visible without a debugger.</p>
         */
        @Test
        @DisplayName("an indicator whose length is neither empty nor one entry per row is refused, "
                + "and the refusal names both counts")
        void anIndicatorOfAMismatchedLengthIsRefused() {
            assertThat(withLists(threeAscendingRows(), List.of()).selectionErrorFlags())
                    .as("\"nothing was evaluated\" is admissible and is the empty list")
                    .isEmpty();
            assertThat(withLists(threeAscendingRows(), List.of(true, false, false))
                            .selectionErrorFlags())
                    .as("one entry per row is the other admissible length")
                    .hasSize(3);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withLists(threeAscendingRows(), List.of(true)))
                    .withMessageContaining("the indicator is positional")
                    .withMessageContaining("it holds 1 entries for 3 rows");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withLists(List.of(), List.of(true, false)))
                    .withMessageContaining("it holds 2 entries for 0 rows");
        }

        @Test
        @DisplayName("a null indicator element is refused, because every position is explicitly "
                + "marked or explicitly unmarked")
        void aNullIndicatorElementIsRefused() {
            List<Boolean> withNull = new ArrayList<>();
            withNull.add(Boolean.TRUE);
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> withLists(null, withNull));
        }

        @Test
        @DisplayName("an action code is echoed as received and is neither interpreted nor rejected")
        void anActionCodeIsEchoedAsReceived() {
            for (String code : List.of("S", "U", "s", "X", " ", "")) {
                assertThatNoException()
                        .as("code \"%s\" is echoed rather than validated here", code)
                        .isThrownBy(() -> row(code, "00000000011", "0000000000000001", "Y"));
                assertThat(row(code, "00000000011", "0000000000000001", "Y").selection())
                        .isEqualTo(code);
            }
        }
    }

    @Nested
    @DisplayName("Message contract")
    class MessageContract {

        @Test
        @DisplayName("the nine screen texts are the only text constants this type publishes")
        void theNineScreenTextsAreTheOnlyTextConstants() {
            List<String> published = Arrays.stream(CardListResponse.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> Modifier.isPublic(field.getModifiers()))
                    .filter(field -> field.getType() == String.class)
                    .map(Field::getName)
                    .toList();

            assertThat(published).hasSize(9).allMatch(name -> name.startsWith("MSG_"));
        }

        /**
         * One static text is deliberately not published, and naming it keeps the count above exact.
         *
         * <p>The diagnostic stand-in is private. No screen displays it and no client receives it, so
         * publishing it would invite a caller to depend on its spelling. Asserting that it is the only
         * unpublished text means a tenth text cannot be added without one of these two tests failing
         * and naming it.</p>
         */
        @Test
        @DisplayName("the diagnostic stand-in is the one static text that is not published")
        void theDiagnosticStandInIsTheOneUnpublishedText() {
            List<String> unpublished = Arrays.stream(CardListResponse.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isPublic(field.getModifiers()))
                    .filter(field -> field.getType() == String.class)
                    .map(Field::getName)
                    .toList();

            assertThat(unpublished).containsExactly("REDACTION_PLACEHOLDER");
        }

        @Test
        @DisplayName("the nine texts are reproduced character for character")
        void theNineTextsAreReproducedVerbatim() {
            assertThat(CardListResponse.MSG_ACCOUNT_FILTER_INVALID)
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
            assertThat(CardListResponse.MSG_CARD_FILTER_INVALID)
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
            assertThat(CardListResponse.MSG_INVALID_ACTION_CODE).isEqualTo("INVALID ACTION CODE");
            assertThat(CardListResponse.MSG_MORE_THAN_ONE_ACTION)
                    .isEqualTo("PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE");
            assertThat(CardListResponse.MSG_NO_PREVIOUS_PAGES)
                    .isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
            assertThat(CardListResponse.MSG_NO_MORE_PAGES).isEqualTo("NO MORE PAGES TO DISPLAY");
            assertThat(CardListResponse.MSG_NO_MORE_RECORDS).isEqualTo("NO MORE RECORDS TO SHOW");
            assertThat(CardListResponse.MSG_NO_RECORDS_FOUND)
                    .isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
            assertThat(CardListResponse.MSG_ROW_ACTION_PROMPT)
                    .isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        }

        @ParameterizedTest(name = "\"{0}\" is upper case")
        @MethodSource("com.carddemo.api.dto.CardListResponseCoverageTest#screenTexts")
        @DisplayName("every one of the nine texts is upper case, which this screen's set is and its "
                + "neighbour's is not")
        void everyTextIsUpperCase(String text) {
            assertThat(text).isEqualTo(text.toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("exactly one of the nine texts ends in a full stop, and the stop is part of "
                + "the text")
        void exactlyOneTextEndsInAFullStop() {
            List<String> ending = screenTexts().filter(text -> text.endsWith(".")).toList();

            assertThat(ending).containsExactly(CardListResponse.MSG_NO_RECORDS_FOUND);
            assertThat(CardListResponse.MSG_NO_RECORDS_FOUND)
                    .hasSize(43)
                    .isNotEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION");
        }

        @Test
        @DisplayName("neither filter text carries a space after its comma, and both read \"A\" "
                + "before the digit count")
        void neitherFilterTextCarriesASpaceAfterItsComma() {
            assertThat(CardListResponse.MSG_ACCOUNT_FILTER_INVALID)
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("A 11 DIGIT")
                    .doesNotContain("AN 11");
            assertThat(CardListResponse.MSG_CARD_FILTER_INVALID)
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("A 16 DIGIT")
                    .doesNotContain("AN 16");
        }

        @Test
        @DisplayName("the row-action prompt does carry a space after its comma, so the punctuation "
                + "genuinely differs between messages")
        void theRowActionPromptDoesCarryASpaceAfterItsComma() {
            assertThat(CardListResponse.MSG_ROW_ACTION_PROMPT)
                    .as("a reader who noticed only the filter texts would wrongly strip this space")
                    .contains("DETAIL, U")
                    .doesNotContain("DETAIL,U");
        }

        @Test
        @DisplayName("the two paging guards are distinct texts, because the legacy renders a "
                + "different one at each end of the browse")
        void theTwoPagingGuardsAreDistinct() {
            assertThat(CardListResponse.MSG_NO_PREVIOUS_PAGES)
                    .as("the two end-of-browse indicators in the paging record are independent for "
                            + "the same reason")
                    .isNotEqualTo(CardListResponse.MSG_NO_MORE_PAGES);
            assertThat(CardListResponse.MSG_NO_MORE_RECORDS)
                    .as("the end of the data is not a refused navigation")
                    .isNotEqualTo(CardListResponse.MSG_NO_MORE_PAGES);
        }

        @Test
        @DisplayName("the informational prompt fits the informational slot, which is the narrower "
                + "of the two message widths")
        void theInformationalPromptFitsTheInformationalSlot() {
            assertThat(CardListResponse.MSG_ROW_ACTION_PROMPT.length())
                    .isLessThanOrEqualTo(CardListResponse.INFO_MESSAGE_LENGTH);
            assertThat(validator.validate(
                            carrying("infoMessage", CardListResponse.MSG_ROW_ACTION_PROMPT)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "\"{0}\" fits the error slot")
        @MethodSource("com.carddemo.api.dto.CardListResponseCoverageTest#screenTexts")
        @DisplayName("every one of the nine texts fits the error slot, so no published text can be "
                + "reported over-long by the component that carries it")
        void everyTextFitsTheErrorSlot(String text) {
            assertThat(text.length()).isLessThanOrEqualTo(CardListResponse.ERROR_MESSAGE_LENGTH);
            assertThat(validator.validate(carrying("errorMessage", text))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Width asymmetry with the neighbouring screen")
    class WidthAsymmetry {

        @Test
        @DisplayName("the informational slot is forty-five here and forty on the card-detail "
                + "screen, and neither is derived from the other")
        void theInformationalSlotDiffersFromTheCardDetailScreen() {
            assertThat(CardListResponse.INFO_MESSAGE_LENGTH)
                    .as("two neighbouring screens declare two widths, and unifying them would "
                            + "silently widen the narrower one")
                    .isEqualTo(45)
                    .isNotEqualTo(CardDetailResponse.INFO_MESSAGE_LENGTH);
            assertThat(CardDetailResponse.INFO_MESSAGE_LENGTH).isEqualTo(40);
        }

        @Test
        @DisplayName("the error slot is seventy-eight here and eighty on the card-detail screen")
        void theErrorSlotDiffersFromTheCardDetailScreen() {
            assertThat(CardListResponse.ERROR_MESSAGE_LENGTH)
                    .isEqualTo(78)
                    .isNotEqualTo(CardDetailResponse.ERROR_MESSAGE_LENGTH);
            assertThat(CardDetailResponse.ERROR_MESSAGE_LENGTH).isEqualTo(80);
        }

        @Test
        @DisplayName("the two filter texts are byte-identical across the two response types even "
                + "though the slots that carry them are not")
        void theFilterTextsAreIdenticalAcrossTheTwoTypes() {
            assertThat(CardListResponse.MSG_ACCOUNT_FILTER_INVALID)
                    .as("the same program emits the same wording from two screens, so each type "
                            + "declares it and the two agree by value")
                    .isEqualTo(CardDetailResponse.MSG_ACCOUNT_FILTER_NOT_NUMERIC);
            assertThat(CardListResponse.MSG_CARD_FILTER_INVALID)
                    .isEqualTo(CardDetailResponse.MSG_CARD_FILTER_NOT_NUMERIC);
        }

        @Test
        @DisplayName("the identifier widths do agree across the two screens, so the asymmetry is "
                + "confined to the message slots")
        void theIdentifierWidthsAgreeAcrossTheTwoScreens() {
            assertThat(CardListResponse.ACCOUNT_NUMBER_LENGTH)
                    .isEqualTo(CardDetailResponse.ACCOUNT_ID_LENGTH);
            assertThat(CardListResponse.CARD_NUMBER_LENGTH)
                    .isEqualTo(CardDetailResponse.CARD_NUMBER_LENGTH);
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a fully populated page reports no violation, nested navigation state included")
        void aFullyPopulatedPageReportsNoViolation() {
            assertThat(validator.validate(filledPage(JsonContractSupport.populatedNavigation())))
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} reports a value one character over {1}")
        @CsvSource({
            "transactionName,4",
            "title01,40",
            "currentDate,8",
            "programName,8",
            "title02,40",
            "currentTime,8",
            "displayedPageNumber,3",
            "accountFilter,11",
            "cardNumberFilter,16",
            "infoMessage,45",
            "errorMessage,78",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component reports a value one character over its width")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            Set<ConstraintViolation<CardListResponse>> violations =
                    validator.validate(carrying(component, "9".repeat(width + 1)));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @ParameterizedTest(name = "{0} accepts a value exactly at {1}")
        @CsvSource({
            "transactionName,4",
            "displayedPageNumber,3",
            "accountFilter,11",
            "cardNumberFilter,16",
            "infoMessage,45",
            "errorMessage,78",
            "focusScreenFieldId,7",
        })
        @DisplayName("each bounded component accepts a value exactly at its width, so every bound "
                + "is inclusive")
        void eachBoundedComponentAcceptsAValueAtItsWidth(String component, int width) {
            assertThat(validator.validate(carrying(component, "9".repeat(width)))).isEmpty();
        }

        @Test
        @DisplayName("the next route is unbounded, so a long opaque token is carried rather than "
                + "reported")
        void theNextRouteIsUnbounded() {
            assertThat(validator.validate(carrying("nextRoute", "/".repeat(512)))).isEmpty();
        }

        @Test
        @DisplayName("an entirely empty page reports no violation, because the legacy renders a "
                + "partly filled map on almost every path")
        void anEntirelyEmptyPageReportsNoViolation() {
            assertThat(validator.validate(withLists(null, null))).isEmpty();
        }

        @ParameterizedTest(name = "row {0} reports a value one character over {1}")
        @CsvSource({
            "selection,1",
            "accountNumber,11",
            "cardNumber,16",
            "cardStatus,1",
        })
        @DisplayName("each row component reports a value one character over its width when the row "
                + "itself is validated")
        void eachRowComponentReportsAnOverLongValue(String component, int width) {
            String overLong = "9".repeat(width + 1);
            CardListRow candidate = row(
                    "selection".equals(component) ? overLong : "",
                    "accountNumber".equals(component) ? overLong : null,
                    "cardNumber".equals(component) ? overLong : null,
                    "cardStatus".equals(component) ? overLong : null);

            Set<ConstraintViolation<CardListRow>> violations = validator.validate(candidate);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @Test
        @DisplayName("a row bound is not cascaded from the enclosing page, so a row is validated as "
                + "a value in its own right")
        void aRowBoundIsNotCascadedFromTheEnclosingPage() {
            CardListRow overLong = row("", "00000000011", "9".repeat(17), "Y");

            assertThat(validator.validate(withLists(List.of(overLong), List.of(false))))
                    .as("the page declares no cascade into its rows, so the enclosing validation "
                            + "reports nothing and the row must be checked directly")
                    .isEmpty();
            assertThat(validator.validate(overLong)).hasSize(1);
        }

        @Test
        @DisplayName("a row whose every value sits at its width reports no violation")
        void aRowAtItsWidthsReportsNoViolation() {
            assertThat(validator.validate(row("S", "00000000011", "0000000000000001", "Y")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("a filled page renders every populated member under its contract name")
        void aFilledPageRendersItsPopulatedMembers() throws JsonProcessingException {
            JsonNode payload = payloadOf(filledPage(null));

            assertThat(payload.get("transactionName").asText()).isEqualTo(TRANSACTION_NAME);
            assertThat(payload.get("title01").asText()).isEqualTo(TITLE_01);
            assertThat(payload.get("currentDate").asText()).isEqualTo(CURRENT_DATE);
            assertThat(payload.get("programName").asText()).isEqualTo(PROGRAM_NAME);
            assertThat(payload.get("title02").asText()).isEqualTo(TITLE_02);
            assertThat(payload.get("currentTime").asText()).isEqualTo(CURRENT_TIME);
            assertThat(payload.get("displayedPageNumber").asText()).isEqualTo(DISPLAYED_PAGE_NUMBER);
            assertThat(payload.get("accountFilter").asText()).isEqualTo(ACCOUNT_FILTER);
            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo(CARD_FILTER);
            assertThat(payload.get("infoMessage").asText())
                    .isEqualTo(CardListResponse.MSG_ROW_ACTION_PROMPT);
            assertThat(payload.get("focusScreenFieldId").asText()).isEqualTo(FOCUS_SCREEN_FIELD_ID);
            assertThat(payload.get("nextRoute").asText()).isEqualTo(NEXT_ROUTE);
        }

        @Test
        @DisplayName("the rows render as an ordered array of objects, each carrying the four row "
                + "members under their contract names")
        void theRowsRenderAsAnOrderedArrayOfObjects() throws JsonProcessingException {
            JsonNode rows = payloadOf(filledPage(null)).get("rows");

            assertThat(rows.isArray()).isTrue();
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).get("selection").asText()).isEqualTo("S");
            assertThat(rows.get(0).get("accountNumber").asText()).isEqualTo("00000000011");
            assertThat(rows.get(0).get("cardNumber").asText()).isEqualTo("0000000000000001");
            assertThat(rows.get(0).get("cardStatus").asText()).isEqualTo("Y");
            assertThat(rows.get(2).get("cardNumber").asText())
                    .as("array order is the presentation order the service established")
                    .isEqualTo("0000000000000003");
        }

        @Test
        @DisplayName("an empty row list renders as an empty array rather than being omitted, "
                + "because an empty list is not an absent one")
        void anEmptyRowListRendersAsAnEmptyArray() throws JsonProcessingException {
            JsonNode payload = payloadOf(withLists(null, null));

            assertThat(payload.get("rows").isArray()).isTrue();
            assertThat(payload.get("rows")).isEmpty();
            assertThat(payload.get("selectionErrorFlags").isArray()).isTrue();
            assertThat(payload.get("selectionErrorFlags")).isEmpty();
        }

        @Test
        @DisplayName("the positional indicator renders every slot including the unmarked ones, so "
                + "alignment survives serialisation")
        void thePositionalIndicatorRendersEverySlot() throws JsonProcessingException {
            JsonNode flags = payloadOf(withLists(threeAscendingRows(), List.of(false, true, false)))
                    .get("selectionErrorFlags");

            assertThat(flags).hasSize(3);
            assertThat(flags.get(0).asBoolean()).isFalse();
            assertThat(flags.get(1).asBoolean()).isTrue();
            assertThat(flags.get(2).asBoolean()).isFalse();
        }

        @Test
        @DisplayName("every card number crosses whole with its leading zeros intact, which is the "
                + "delivered contract rather than an accident")
        void everyCardNumberCrossesWhole() throws JsonProcessingException {
            String rendered = JsonContractSupport.declaredSettingsMapper()
                    .writeValueAsString(filledPage(null));

            assertThat(rendered)
                    .as("the legacy applies no field-level protection to a card number anywhere; "
                            + "the gap is recorded in docs/decision-log.md and closing it here "
                            + "would change the response contract as unrequested work")
                    .contains("\"cardNumber\":\"0000000000000001\"")
                    .contains("\"cardNumber\":\"0000000000000002\"")
                    .contains("\"cardNumber\":\"0000000000000003\"")
                    .doesNotContain("****");
        }

        @Test
        @DisplayName("the paging record renders nested with its direction, cursors and two "
                + "independent end-of-browse indicators")
        void thePagingRecordRendersNested() throws JsonProcessingException {
            JsonNode paging = payloadOf(filledPage(null)).get("pageMetadata");

            assertThat(paging.get("pageSize").asInt()).isEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
            assertThat(paging.get("direction").asText()).isEqualTo("FORWARD");
            assertThat(paging.get("nextCursorKey").asText()).isEqualTo("0000000000000003");
            assertThat(paging.get("hasMorePages").asBoolean()).isTrue();
            assertThat(paging.get("hasPreviousPages").asBoolean()).isFalse();
            assertThat(paging.has("previousCursorKey"))
                    .as("a first forward page has no previous cursor, and an absent value is "
                            + "omitted rather than written as null")
                    .isFalse();
        }

        @Test
        @DisplayName("an absent member is omitted while the two indicators and the three lists are "
                + "always written")
        void absentMembersAreOmittedAndTheAlwaysPresentOnesAreWritten()
                throws JsonProcessingException {
            JsonNode payload = payloadOf(withLists(null, null));

            assertThat(payload.size()).isEqualTo(ALWAYS_WRITTEN_COMPONENTS.size());
            assertThat(payload.get("generalError").asBoolean()).isFalse();
            assertThat(payload.get("lastPageAlreadyShown").asBoolean()).isFalse();
            for (String component : EXPECTED_COMPONENTS) {
                if (!ALWAYS_WRITTEN_COMPONENTS.contains(component)) {
                    assertThat(payload.has(component))
                            .as("%s is absent rather than written as null", component)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("an explicitly blanked message is written while an absent one is omitted, so "
                + "the two states stay distinguishable on the wire")
        void aBlankedMessageIsWrittenWhileAnAbsentOneIsOmitted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("errorMessage", ""));

            assertThat(payload.get("errorMessage").asText()).isEmpty();
            assertThat(payload.has("infoMessage")).isFalse();
        }

        @Test
        @DisplayName("a fully populated page round trips unchanged, rows, indicator, paging record "
                + "and navigation state included")
        void aFullyPopulatedPageRoundTripsUnchanged() throws JsonProcessingException {
            CardListResponse response = filledPage(JsonContractSupport.populatedNavigation());
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();

            CardListResponse returned = mapper.readValue(
                    mapper.writeValueAsString(response), CardListResponse.class);

            assertThat(returned).isEqualTo(response);
            assertThat(returned.rows()).containsExactlyElementsOf(threeAscendingRows());
            assertThat(returned.selectionErrorFlags()).containsExactly(false, false, false);
            assertThat(returned.pageMetadata()).isEqualTo(forwardFirstPage());
            assertThat(returned.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
        }

        @Test
        @DisplayName("an unknown member is ignored rather than rejected, so a client may echo the "
                + "response back without being refused")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"displayedPageNumber\":\"002\",\"totalRecords\":41,\"cvvCode\":\"123\"}";

            CardListResponse returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, CardListResponse.class);

            assertThat(returned.displayedPageNumber())
                    .as("the legacy browse never asks the store how many records exist, so a "
                            + "record total is not a member of this contract")
                    .isEqualTo("002");
            assertThat(returned.rows()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        /** The fixed stand-in both overrides write in place of a withheld value. */
        private static final String PLACEHOLDER = "***REDACTED***";

        /** The five components this type withholds, in declaration order. */
        private static final List<String> WITHHELD_BY_THIS_TYPE = List.of(
                "accountFilter", "cardNumberFilter", "rows", "pageMetadata", "rowSnapshotToken");

        /** The two row components the row's own override withholds. */
        private static final List<String> WITHHELD_BY_THE_ROW = List.of(
                "accountNumber", "cardNumber");

        @Test
        @DisplayName("the row rendering names all five components and withholds the account and the "
                + "card, keeping the slot, the action code and the status visible")
        void theRowRenderingIsExactlyTheGeneratedForm() {
            assertThat(row("S", "00000000011", "0000000000000001", "Y").toString())
                    .as("the row declares its own override, so the two identifiers it carries are "
                            + "replaced while the three presentational values are not")
                    .isEqualTo("CardListRow[screenSlot=1, selection=S, accountNumber=" + PLACEHOLDER
                            + ", cardNumber=" + PLACEHOLDER + ", cardStatus=Y]");
        }

        /**
         * The row withholds in its rendering only; its accessors are unchanged.
         *
         * <p>Without this the protection above would be indistinguishable from the row having lost the
         * values, which would be a behavioural regression rather than a hardening.</p>
         */
        @Test
        @DisplayName("the row still returns both identifiers from its accessors")
        void theRowWithholdingIsConfinedToItsRendering() {
            CardListRow subject = row("S", "00000000011", "0000000000000001", "Y");

            assertThat(subject.accountNumber()).isEqualTo("00000000011");
            assertThat(subject.cardNumber()).isEqualTo("0000000000000001");
            for (String component : WITHHELD_BY_THE_ROW) {
                assertThat(subject.toString())
                        .as("%s is withheld from the row rendering", component)
                        .contains(component + "=" + PLACEHOLDER);
            }
        }

        /**
         * The page rendering names every component, plus a row count that is not a component at all.
         *
         * <p>The row list is withheld whole, so the number of rows on the page - the single most
         * useful fact about a card-list response in a diagnostic, and one that identifies nobody - is
         * stated separately as {@code rowCount}. It precedes the withheld list rather than replacing
         * it, so the rendering still names {@code rows} under its declared name.</p>
         */
        @Test
        @DisplayName("the page rendering names all twenty-one components plus the row count, and "
                + "withholds exactly the two filters, the row list, the browse position and the row "
                + "snapshot")
        void thePageRenderingIsTheGeneratedOne() {
            String rendered = filledPage(null).toString();

            assertThat(rendered)
                    .startsWith("CardListResponse[")
                    .endsWith("]")
                    .contains("rowCount=3")
                    .contains("selectionErrorFlags=[false, false, false]")
                    .as("a withheld value must not reach the text by any other spelling")
                    .doesNotContain("0000000000000001")
                    .doesNotContain(ACCOUNT_FILTER);
            for (String component : EXPECTED_COMPONENTS) {
                assertThat(rendered)
                        .as("%s is named in the rendering", component)
                        .contains(component + "=");
            }

            List<String> withheld = EXPECTED_COMPONENTS.stream()
                    .filter(component -> rendered.contains(component + "=" + PLACEHOLDER))
                    .toList();
            assertThat(withheld)
                    .as("the four regulated components are withheld, the already-opaque row snapshot is "
                            + "withheld with them, and the other sixteen are not")
                    .containsExactlyElementsOf(WITHHELD_BY_THIS_TYPE);
        }

        /**
         * The stand-in appears whether or not the component holds a value.
         *
         * <p>A conditional substitution would leak one bit: a reader could tell an absent browse
         * position from a present one by whether the stand-in appeared, and could therefore tell a
         * first page from a continuation. The page that carries neither nested record still shows the
         * browse position withheld, and the row count still reports the rows it does carry.</p>
         */
        @Test
        @DisplayName("a page carrying neither nested record still withholds the browse position, so "
                + "presence is not disclosed either")
        void aPageCarryingNeitherNestedRecordStillWithholds() {
            String rendered = filledPageWithoutNestedRecords().toString();

            assertThat(rendered)
                    .as("the substitution is unconditional, so an absent browse position is "
                            + "indistinguishable from a present one in the rendering")
                    .contains("pageMetadata=" + PLACEHOLDER)
                    .contains("navigationContext=null")
                    .contains("rowCount=3")
                    .contains("rows=" + PLACEHOLDER);
        }

        /**
         * The browse position is withheld whole here, and shows its own shape only when reached
         * directly.
         *
         * <p>Two lines of defence are asserted at once. This type substitutes the whole paging record,
         * so not even its page shape crosses the response rendering - which is the necessary posture,
         * because the paging record's own rendering retains the direction and the page size and a
         * future change to it must not be able to reopen a disclosure here. Reached directly, the same
         * instance still withholds its two cursor keys, which are themselves a card number followed by
         * an account identifier, while showing the direction and the page shape that identify
         * nobody.</p>
         */
        @Test
        @DisplayName("the browse position is withheld whole by the response, and withholds its two "
                + "cursor keys when reached directly")
        void aNestedBrowsePositionWithholdsItsCursorKeys() {
            PageMetadata position = PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE,
                    "PREVCURSORKEY7788", "NEXTCURSORKEY9911", true, true, "002");
            CardListResponse response = new CardListResponse(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, false, position, false, List.of(), null, null, null, null);

            assertThat(response.toString())
                    .as("the paging record is substituted whole, so this type cannot become the "
                            + "path by which any part of it surfaces")
                    .contains("pageMetadata=" + PLACEHOLDER)
                    .doesNotContain("PageMetadata[")
                    .doesNotContain("PREVCURSORKEY7788")
                    .doesNotContain("NEXTCURSORKEY9911")
                    .doesNotContain("direction=FORWARD");

            assertThat(position.toString())
                    .as("reached directly the paging record still withholds its two cursor keys, "
                            + "each of which is a business key")
                    .doesNotContain("PREVCURSORKEY7788")
                    .doesNotContain("NEXTCURSORKEY9911")
                    .contains("direction=FORWARD")
                    .contains("pageSize=" + PageMetadata.CARD_LIST_PAGE_SIZE)
                    .contains("displayedPageNumber=002");
        }

        @Test
        @DisplayName("a nested navigation state withholds its own identifying values, so this type "
                + "cannot become the path by which they surface")
        void aNestedNavigationStateWithholdsItsOwnValues() {
            String rendered = filledPage(JsonContractSupport.populatedNavigation()).toString();

            assertThat(rendered)
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
        @DisplayName("equality compares every component by value and equal instances share a hash")
        void equalityComparesEveryComponentByValue() {
            CardListResponse first = filledPage(JsonContractSupport.populatedNavigation());
            CardListResponse second = filledPage(JsonContractSupport.populatedNavigation());

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(filledPage(null));
        }

        @Test
        @DisplayName("row order participates in equality, so a forward page and its inverted "
                + "counterpart are not the same value")
        void rowOrderParticipatesInEquality() {
            List<CardListRow> ascending = threeAscendingRows();
            List<CardListRow> descending = List.of(
                    ascending.get(2), ascending.get(1), ascending.get(0));

            assertThat(withLists(ascending, List.of()))
                    .as("if order did not participate, inverting a backward page would go "
                            + "undetected by every equality assertion in this suite")
                    .isNotEqualTo(withLists(descending, List.of()));
        }

        @Test
        @DisplayName("the positional indicator participates in equality, so two pages differing "
                + "only in which rows offended are distinguishable")
        void thePositionalIndicatorParticipatesInEquality() {
            assertThat(withLists(threeAscendingRows(), List.of(true, false, false)))
                    .isNotEqualTo(withLists(threeAscendingRows(), List.of(false, true, false)));
        }

        @Test
        @DisplayName("an empty list and an absent one compare equal, because the constructor "
                + "normalises the absent form to the empty one")
        void anEmptyListAndAnAbsentOneCompareEqual() {
            assertThat(withLists(null, null)).isEqualTo(withLists(List.of(), List.of()));
        }

        @Test
        @DisplayName("row equality compares all four values, so two rows differing only in status "
                + "are distinguishable")
        void rowEqualityComparesAllFourValues() {
            CardListRow active = row("", "00000000011", "0000000000000001", "Y");
            CardListRow inactive = row("", "00000000011", "0000000000000001", "N");

            assertThat(active).isEqualTo(row("", "00000000011", "0000000000000001", "Y"))
                    .hasSameHashCodeAs(row("", "00000000011", "0000000000000001", "Y"));
            assertThat(active).isNotEqualTo(inactive);
        }

        @Test
        @DisplayName("a blank text and an absent one do not compare equal, because the screen "
                + "reports on the difference")
        void aBlankTextIsNotAnAbsentOne() {
            assertThat(carrying("errorMessage", "")).isNotEqualTo(carrying("errorMessage", null));
        }
    }
}
