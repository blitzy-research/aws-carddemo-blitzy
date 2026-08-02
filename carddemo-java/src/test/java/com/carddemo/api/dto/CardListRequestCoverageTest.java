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
import jakarta.validation.constraints.Size;
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
 * Unit tests for {@link CardListRequest}, the request body of legacy transaction {@code CCLI}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the card-list submission: the thirteen components, the four declared
 * widths, the seven positional row action codes and the order-preserving projection over them, the
 * wire form under the module's declared serialisation settings, and the diagnostic rendering - which
 * on this type is not the generated one and is the most consequential thing in the file.
 *
 * <h2>Seven is a measured number, not a convention</h2>
 *
 * <p>The card-list screen presents exactly seven card rows. The program declares a 196-character
 * all-rows area redefined as a table of seven 28-character occurrences, each holding an 11-character
 * account identifier, a 16-character card number and a single-character status. Seven separate
 * components rather than a list is what makes the row count part of the type: a list would let a
 * client submit eight marks for a seven-row screen and the boundary would not notice. Tests below pin
 * the count, the ordering of the projection, and the fact that every position is present in it -
 * including the unmarked ones.
 *
 * <h2>The projection tolerates a null element on purpose</h2>
 *
 * <p>An unmarked row is not an omitted row: the returned list always has one element per screen row,
 * so the list index is the row index, and an unmarked row appears as whatever its component holds -
 * absent, empty or blank. That is why the projection is built over a null-tolerant list rather than
 * an immutable-copy factory, which would throw on precisely the unmarked row the contract has to
 * preserve. A test below constructs a request in which every row is unmarked and proves the
 * projection still has seven elements.
 *
 * <h2>Three components are withheld from the rendering, and one of them is withheld whole</h2>
 *
 * <p>The generated record rendering would print every component. Three are regulated: the card filter
 * is a primary account number, the account filter is the key that joins straight to a cardholder, and
 * the paging component's retained browse keys embed the same card number followed by the same account
 * identifier. The paging component is therefore withheld <em>whole</em> rather than partly, because
 * its own rendering does not withhold its browse keys. Tests below prove all three, prove that the
 * placeholder is a fixed constant rather than a length-preserving mask - so neither the length nor a
 * prefix of a withheld value is recoverable - and prove that the remainder is retained, because
 * withholding the page indicator, the row codes and the attention key would remove the only useful
 * diagnostic content without protecting anything.
 *
 * <h2>One of the thirteen components is bound in one direction only</h2>
 *
 * <p>The page indicator is written outbound and ignored inbound. The legacy program writes that screen
 * field and never reads it, and the browse keys the paging component carries are what actually
 * position a browse, so a submitted indicator could not have influenced a page. Binding one would have
 * created an input the legacy never had; discarding it rather than refusing it keeps a client free to
 * echo a reply straight back. The consequence is that a round trip cannot return an equal instance,
 * so the test below asserts that the loss is precisely that component and that everything else -
 * all seven row positions in order, the paging direction and the attention key - survives.
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
@DisplayName("CardListRequest :: card-list request contract of legacy transaction CCLI")
class CardListRequestCoverageTest {

    /** The thirteen components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "accountIdFilter", "cardNumberFilter", "displayedPageNumber",
            "selection1", "selection2", "selection3", "selection4", "selection5", "selection6",
            "selection7", "pageMetadata", "keyAction", "navigationContext");

    /** Number of card rows the screen presents, measured from the legacy row table. */
    private static final int EXPECTED_ROW_COUNT = 7;

    /** Declared width of the account filter, restated from the symbolic map. */
    private static final int EXPECTED_ACCOUNT_FILTER_WIDTH = 11;

    /** Declared width of the card filter, restated from the symbolic map. */
    private static final int EXPECTED_CARD_FILTER_WIDTH = 16;

    /** Declared width of the displayed page indicator, restated from the symbolic map. */
    private static final int EXPECTED_PAGE_INDICATOR_WIDTH = 3;

    /** Declared width of one row action code, restated from the symbolic map. */
    private static final int EXPECTED_SELECTION_WIDTH = 1;

    /** The exact placeholder the rendering emits in place of each withheld component. */
    private static final String EXPECTED_PLACEHOLDER = "***REDACTED***";

    /** An account filter at exactly the declared width. */
    private static final String ACCOUNT_FILTER = "00000000011";

    /** A card filter at exactly the declared width. */
    private static final String CARD_FILTER = "4111111111111111";

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
     * Builds a request carrying the two filters and the seven supplied row codes.
     *
     * @param accountFilter the account filter
     * @param cardFilter the card filter
     * @param rowCodes exactly seven row action codes, any of which may be {@code null}
     * @return a request with no page, key or navigation state
     */
    private static CardListRequest withRowCodes(
            String accountFilter, String cardFilter, String... rowCodes) {
        return new CardListRequest(accountFilter, cardFilter, null,
                rowCodes[0], rowCodes[1], rowCodes[2], rowCodes[3],
                rowCodes[4], rowCodes[5], rowCodes[6], null, null, null);
    }

    /**
     * Builds a request in which no row is marked and no filter is supplied.
     *
     * @return an entirely unmarked request
     */
    private static CardListRequest unmarked() {
        return withRowCodes(null, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds a request carrying only the named component, so a violation can be attributed.
     *
     * @param component the component to populate
     * @param value the value to place in it
     * @return a request carrying that one value
     */
    private static CardListRequest carrying(String component, String value) {
        return new CardListRequest(
                "accountIdFilter".equals(component) ? value : null,
                "cardNumberFilter".equals(component) ? value : null,
                "displayedPageNumber".equals(component) ? value : null,
                "selection1".equals(component) ? value : null,
                "selection2".equals(component) ? value : null,
                "selection3".equals(component) ? value : null,
                "selection4".equals(component) ? value : null,
                "selection5".equals(component) ? value : null,
                "selection6".equals(component) ? value : null,
                "selection7".equals(component) ? value : null,
                null, null, null);
    }

    /**
     * Serialises a request and parses the result back into a tree.
     *
     * @param request the request to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(CardListRequest request) throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the thirteen components are declared in the order the screen submits them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("exactly seven row action codes are declared, one per screen row, so an eighth "
                + "mark cannot be submitted at all")
        void exactlySevenRowActionCodesAreDeclared() {
            long selectionComponents = Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> name.startsWith("selection"))
                    .count();

            assertThat(selectionComponents)
                    .as("the legacy row table has seven occurrences; a list would let a client "
                            + "submit a count the screen cannot display")
                    .isEqualTo(EXPECTED_ROW_COUNT);
        }

        @Test
        @DisplayName("the four published widths equal the widths the symbolic map declares")
        void publishedWidthsEqualTheMapWidths() {
            assertThat(CardListRequest.ACCOUNT_ID_FILTER_LENGTH)
                    .isEqualTo(EXPECTED_ACCOUNT_FILTER_WIDTH);
            assertThat(CardListRequest.CARD_NUMBER_FILTER_LENGTH)
                    .isEqualTo(EXPECTED_CARD_FILTER_WIDTH);
            assertThat(CardListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("this screen's page indicator is three characters; the transaction-list and "
                            + "user-list screens carry a differently named indicator of width eight")
                    .isEqualTo(EXPECTED_PAGE_INDICATOR_WIDTH);
            assertThat(CardListRequest.SELECTION_LENGTH).isEqualTo(EXPECTED_SELECTION_WIDTH);
        }

        @Test
        @DisplayName("all seven row codes share the same declared width, because the program stages "
                + "them into one seven-character area at one character per row")
        void allSevenRowCodesShareTheSameWidth() throws NoSuchFieldException {
            for (int row = 1; row <= EXPECTED_ROW_COUNT; row++) {
                Size bound = CardListRequest.class.getDeclaredField("selection" + row)
                        .getAnnotation(Size.class);

                assertThat(bound).as("row %d declares a width bound", row).isNotNull();
                assertThat(bound.max()).isEqualTo(EXPECTED_SELECTION_WIDTH);
            }
        }

        @Test
        @DisplayName("the paging state is the shared record and the attention key the domain "
                + "enumeration, so neither is a loose string")
        void thePagingStateAndKeyAreTyped() {
            RecordComponent[] components = CardListRequest.class.getRecordComponents();

            assertThat(components[10].getType())
                    .as("a submission nominates a direction and the two cursor keys it was handed; "
                            + "it has no row count to nominate, so the request-shaped carrier is a "
                            + "narrower type than the response metadata")
                    .isEqualTo(PageMetadata.PageCursorRequest.class);
            assertThat(components[11].getType()).isEqualTo(KeyAction.class);
            assertThat(components[12].getType()).isEqualTo(NavigationContext.class);
        }

        @Test
        @DisplayName("the card-list page size the shared paging record publishes is seven, matching "
                + "this screen's row count")
        void thePublishedPageSizeMatchesTheRowCount() {
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE)
                    .as("the row count and the page size are the same measured number and must not "
                            + "drift apart")
                    .isEqualTo(EXPECTED_ROW_COUNT);
        }
    }

    @Nested
    @DisplayName("Row-order projection")
    class RowOrderProjection {

        @Test
        @DisplayName("the projection returns the seven codes in screen-row order, first row first")
        void theProjectionReturnsTheCodesInRowOrder() {
            CardListRequest request = withRowCodes(
                    null, null, "1", "2", "3", "4", "5", "6", "7");

            assertThat(request.selectionsInRowOrder())
                    .as("the list index is the row index, which is what lets a consumer name the "
                            + "offending row without transposing two of them by hand")
                    .containsExactly("1", "2", "3", "4", "5", "6", "7");
        }

        @Test
        @DisplayName("every position is present even when no row is marked, so the list index stays "
                + "the row index")
        void everyPositionIsPresentEvenWhenNothingIsMarked() {
            assertThat(unmarked().selectionsInRowOrder())
                    .as("an unmarked row appears as its component's value rather than being "
                            + "omitted, which an immutable-copy factory could not express")
                    .hasSize(EXPECTED_ROW_COUNT)
                    .containsOnlyNulls();
        }

        @Test
        @DisplayName("a mixture of absent, empty and blank codes is preserved position by position, "
                + "and none of the three is collapsed into another")
        void aMixtureOfAbsentEmptyAndBlankCodesIsPreserved() {
            CardListRequest request = withRowCodes(
                    null, null, "S", null, "", " ", "U", null, "s");

            assertThat(request.selectionsInRowOrder())
                    .containsExactly("S", null, "", " ", "U", null, "s");
        }

        @Test
        @DisplayName("the projection rejects mutation, so a caller cannot alter the row ordering it "
                + "was handed")
        void theProjectionRejectsMutation() {
            List<String> projected = unmarked().selectionsInRowOrder();

            assertThatThrownBy(() -> projected.set(0, "S"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> projected.add("S"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("two calls return equal but independent lists, so this record retains no "
                + "collection of any kind")
        void twoCallsReturnIndependentLists() {
            CardListRequest request = withRowCodes(
                    null, null, "S", null, null, null, null, null, null);

            List<String> first = request.selectionsInRowOrder();
            List<String> second = request.selectionsInRowOrder();

            assertThat(first).isEqualTo(second).isNotSameAs(second);
        }

        @Test
        @DisplayName("the projection interprets nothing: it counts no mark, folds no case, trims no "
                + "space and builds no indicator")
        void theProjectionInterpretsNothing() {
            CardListRequest request = withRowCodes(
                    null, null, "s", "U", "  ", "X", "1", "0", "?");

            assertThat(request.selectionsInRowOrder())
                    .as("recognising a code, tallying the marks and rewriting the positional "
                            + "indicator all belong to the card-list service")
                    .containsExactly("s", "U", "  ", "X", "1", "0", "?");
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a request whose every component sits exactly at its declared width reports no "
                + "violation")
        void aRequestAtEveryDeclaredWidthReportsNoViolation() {
            CardListRequest request = new CardListRequest(
                    ACCOUNT_FILTER, CARD_FILTER, "999",
                    "S", "S", "S", "S", "S", "S", "S",
                    new PageMetadata.PageCursorRequest(null, null,
                            PageMetadata.PagingDirection.FORWARD),
                    KeyAction.PFK08, NavigationContext.empty());

            assertThat(validator.validate(request)).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @CsvSource({
            "accountIdFilter,11",
            "cardNumberFilter,16",
            "displayedPageNumber,3",
            "selection1,1",
            "selection4,1",
            "selection7,1",
        })
        @DisplayName("each bounded component reports a value one character over its width and leaves "
                + "the value exactly as supplied")
        void eachBoundedComponentReportsAnOverLongValue(String component, int width) {
            String tooLong = "9".repeat(width + 1);
            CardListRequest request = carrying(component, tooLong);

            Set<ConstraintViolation<CardListRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @Test
        @DisplayName("an entirely unmarked request reports no violation, which is the state of a "
                + "first entry into the screen")
        void anEntirelyUnmarkedRequestReportsNoViolation() {
            assertThat(validator.validate(unmarked())).isEmpty();
        }

        @ParameterizedTest(name = "row code \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"S", "U", "s", "u", "X", "1", " ", "?"})
        @DisplayName("the row-code bound restricts width only and names no acceptable character, "
                + "because an unrecognised code is reported back to the operator rather than "
                + "rejected at the boundary")
        void theRowCodeBoundNamesNoAcceptableCharacter(String code) {
            assertThat(validator.validate(carrying("selection1", code))).isEmpty();
        }

        @Test
        @DisplayName("a non-numeric filter is accepted by the boundary, because the digit-format "
                + "check is a message-bearing stage of the service cascade")
        void aNonNumericFilterIsAcceptedByTheBoundary() {
            assertThat(validator.validate(carrying("accountIdFilter", "ABCDEFGHIJK"))).isEmpty();
            assertThat(validator.validate(carrying("cardNumberFilter", "ABCDEFGHIJKLMNOP")))
                    .isEmpty();
        }

        @Test
        @DisplayName("marking every one of the seven rows reports no violation, because the "
                + "more-than-one-action rule is a service check that names the offending rows")
        void markingEveryRowReportsNoViolation() {
            assertThat(validator.validate(
                            withRowCodes(null, null, "S", "S", "S", "S", "S", "S", "S")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("a fully populated request renders all thirteen members under their contract "
                + "names, and the seven row codes appear as seven separate members")
        void aFullyPopulatedRequestRendersAllThirteenMembers() throws JsonProcessingException {
            CardListRequest request = new CardListRequest(
                    ACCOUNT_FILTER, CARD_FILTER, "002",
                    "S", "", " ", "U", "", "", "",
                    new PageMetadata.PageCursorRequest("prev", "next",
                            PageMetadata.PagingDirection.BACKWARD),
                    KeyAction.PFK07, JsonContractSupport.populatedNavigation());

            JsonNode payload = payloadOf(request);

            assertThat(payload.size()).isEqualTo(EXPECTED_COMPONENTS.size());
            assertThat(payload.get("accountIdFilter").asText()).isEqualTo(ACCOUNT_FILTER);
            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo(CARD_FILTER);
            assertThat(payload.get("displayedPageNumber").asText()).isEqualTo("002");
            assertThat(payload.get("selection1").asText()).isEqualTo("S");
            assertThat(payload.get("selection3").asText()).isEqualTo(" ");
            assertThat(payload.get("keyAction").asText()).isEqualTo("PFK07");
            assertThat(payload.get("pageMetadata").get("direction").asText())
                    .as("the backward direction is the one that fills a page from the bottom row "
                            + "upward, so it has to survive the round trip")
                    .isEqualTo("BACKWARD");
        }

        @Test
        @DisplayName("the row-order projection is not a wire member, so the seven codes cross once "
                + "rather than twice")
        void theProjectionIsNotAWireMember() throws JsonProcessingException {
            JsonNode payload = payloadOf(withRowCodes(null, null, "S", null, null, null, null, null,
                    null));

            assertThat(payload.has("selectionsInRowOrder"))
                    .as("a derived projection rendered as a member would put the same seven values "
                            + "on the wire twice and let the two disagree")
                    .isFalse();
        }

        @Test
        @DisplayName("an unmarked row is omitted rather than written as null, and an explicitly "
                + "blanked row is written")
        void anUnmarkedRowIsOmittedAndABlankedRowIsWritten() throws JsonProcessingException {
            JsonNode payload = payloadOf(withRowCodes(
                    null, null, "S", null, "", " ", null, null, null));

            assertThat(payload.get("selection1").asText()).isEqualTo("S");
            assertThat(payload.has("selection2")).isFalse();
            assertThat(payload.get("selection3").asText()).isEmpty();
            assertThat(payload.get("selection4").asText()).isEqualTo(" ");
            assertThat(payload.has("selection5")).isFalse();
        }

        /**
         * The round trip preserves everything except the page indicator, which is bound one way only.
         *
         * <p>The indicator is written outbound - a reply has to be able to show the operator which
         * page they are looking at - and ignored inbound, because the legacy program writes that field
         * and never reads it. The browse keys the paging component carries are what actually position a
         * browse, so a submitted indicator could not have influenced a page even if it had been bound,
         * and binding it would have created an input the legacy never had. It is discarded rather than
         * refused so a client stays free to echo a reply straight back, which is the tolerance the test
         * below this one is about.</p>
         *
         * <p>Equality therefore cannot hold across a round trip, and asserting that it does would
         * require reopening the inbound direction. What is asserted instead is that the loss is
         * precisely that one component and that everything else survives - all seven row positions in
         * order, the paging direction and the attention key. That is the stronger statement, because it
         * names what may change and would fail if a second component silently acquired a directional
         * binding.</p>
         */
        @Test
        @DisplayName("a fully populated request round trips with only the non-bindable page indicator "
                + "dropped, the seven row positions and the paging direction preserved")
        void aFullyPopulatedRequestRoundTripsWithoutItsPageIndicator()
                throws JsonProcessingException {
            CardListRequest request = new CardListRequest(
                    ACCOUNT_FILTER, CARD_FILTER, "007",
                    "S", " ", "", "U", "s", "u", "X",
                    new PageMetadata.PageCursorRequest("p", "n",
                            PageMetadata.PagingDirection.FORWARD),
                    KeyAction.PFK08, JsonContractSupport.populatedNavigation());

            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            CardListRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), CardListRequest.class);

            assertThat(returned)
                    .as("the indicator is non-bindable, so equality cannot hold across a round trip")
                    .isNotEqualTo(request);
            assertThat(returned.displayedPageNumber())
                    .as("and it is the component that was dropped")
                    .isNull();
            assertThat(returned)
                    .as("everything else survives, so the loss is exactly that one")
                    .isEqualTo(new CardListRequest(
                            ACCOUNT_FILTER, CARD_FILTER, null,
                            "S", " ", "", "U", "s", "u", "X",
                            request.pageMetadata(), request.keyAction(),
                            request.navigationContext()));
            assertThat(returned.selectionsInRowOrder())
                    .containsExactly("S", " ", "", "U", "s", "u", "X");
        }

        @Test
        @DisplayName("an unknown member a client echoes back is ignored rather than rejected")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"selection1\":\"S\",\"rows\":[],\"infoMessage\":\"whatever\"}";

            CardListRequest returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, CardListRequest.class);

            assertThat(returned.selection1()).isEqualTo("S");
            assertThat(returned.selectionsInRowOrder()).hasSize(EXPECTED_ROW_COUNT);
        }
    }

    @Nested
    @DisplayName("Diagnostic redaction")
    class DiagnosticRedaction {

        @Test
        @DisplayName("the card filter, the account filter and the paging state are all withheld, "
                + "and the paging state is withheld whole")
        void allThreeRegulatedComponentsAreWithheld() {
            CardListRequest request = new CardListRequest(
                    ACCOUNT_FILTER, CARD_FILTER, "003",
                    "S", null, null, null, null, null, null,
                    new PageMetadata.PageCursorRequest(CARD_FILTER + ACCOUNT_FILTER,
                            CARD_FILTER + ACCOUNT_FILTER, PageMetadata.PagingDirection.FORWARD),
                    KeyAction.PFK08, null);

            String rendered = request.toString();

            assertThat(rendered)
                    .as("the browse keys embed the same card number followed by the same account "
                            + "identifier, and the paging record does not withhold them itself")
                    .doesNotContain(CARD_FILTER)
                    .doesNotContain(ACCOUNT_FILTER);
            assertThat(rendered)
                    .contains("accountIdFilter=" + EXPECTED_PLACEHOLDER)
                    .contains("cardNumberFilter=" + EXPECTED_PLACEHOLDER)
                    .contains("pageMetadata=" + EXPECTED_PLACEHOLDER);
        }

        @Test
        @DisplayName("the placeholder is a fixed constant, so neither the length nor a prefix, "
                + "suffix, digest or partial mask of a withheld value is recoverable")
        void thePlaceholderIsAFixedConstant() {
            String shortFilter = "1";
            String longFilter = "4111111111111111";

            String renderedShort = carrying("cardNumberFilter", shortFilter).toString();
            String renderedLong = carrying("cardNumberFilter", longFilter).toString();

            assertThat(renderedShort)
                    .as("a truncated primary account number is still cardholder data, so a partial "
                            + "mask was rejected deliberately")
                    .isEqualTo(renderedLong);
        }

        @Test
        @DisplayName("an absent regulated component renders as the same placeholder, so absence and "
                + "presence are indistinguishable in a diagnostic")
        void absenceAndPresenceAreIndistinguishable() {
            assertThat(unmarked().toString())
                    .contains("accountIdFilter=" + EXPECTED_PLACEHOLDER)
                    .contains("cardNumberFilter=" + EXPECTED_PLACEHOLDER)
                    .contains("pageMetadata=" + EXPECTED_PLACEHOLDER);
        }

        @Test
        @DisplayName("the page indicator, the seven row codes and the attention key are retained, "
                + "because withholding them would remove the only useful content without protecting "
                + "anything")
        void theScreenInteractionStateIsRetained() {
            CardListRequest request = new CardListRequest(
                    ACCOUNT_FILTER, CARD_FILTER, "042",
                    "S", "U", "s", "u", "X", "1", "?", null, KeyAction.PFK07, null);

            assertThat(request.toString())
                    .contains("displayedPageNumber=042")
                    .contains("selection1=S")
                    .contains("selection2=U")
                    .contains("selection3=s")
                    .contains("selection4=u")
                    .contains("selection5=X")
                    .contains("selection6=1")
                    .contains("selection7=?")
                    .contains("keyAction=PFK07");
        }

        @Test
        @DisplayName("the navigation state is printed by delegation, and it withholds its own "
                + "identifying values")
        void theNavigationStateIsPrintedByDelegation() {
            CardListRequest request = new CardListRequest(
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    JsonContractSupport.populatedNavigation());

            assertThat(request.toString())
                    .contains("navigationContext=NavigationContext[")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }

        @Test
        @DisplayName("withholding is confined to the rendering path: every accessor returns its "
                + "component exactly as supplied")
        void withholdingIsConfinedToTheRenderingPath() {
            CardListRequest request = new CardListRequest(
                    ACCOUNT_FILTER, CARD_FILTER, "003",
                    null, null, null, null, null, null, null,
                    new PageMetadata.PageCursorRequest("p", "n",
                            PageMetadata.PagingDirection.FORWARD),
                    null, null);

            assertThat(request.accountIdFilter())
                    .as("the browse compares a filter to a retrieved record character for "
                            + "character, so no value may be masked outside the rendering path")
                    .isEqualTo(ACCOUNT_FILTER);
            assertThat(request.cardNumberFilter()).isEqualTo(CARD_FILTER);
            assertThat(request.pageMetadata().previousCursorKey()).isEqualTo("p");
        }

        @Test
        @DisplayName("the wire form is not redacted, because the response goes to the one operator "
                + "already authorised to see the screen")
        void theWireFormIsNotRedacted() throws JsonProcessingException {
            JsonNode payload = payloadOf(carrying("cardNumberFilter", CARD_FILTER));

            assertThat(payload.get("cardNumberFilter").asText())
                    .as("redaction here would break the browse, because the filter has to reach the "
                            + "service intact; the protection is against diagnostics, not clients")
                    .isEqualTo(CARD_FILTER);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component by value, including each row position")
        void equalityComparesEveryComponentByValue() {
            CardListRequest left = withRowCodes(
                    ACCOUNT_FILTER, CARD_FILTER, "S", null, null, null, null, null, null);
            CardListRequest right = withRowCodes(
                    ACCOUNT_FILTER, CARD_FILTER, "S", null, null, null, null, null, null);
            CardListRequest shifted = withRowCodes(
                    ACCOUNT_FILTER, CARD_FILTER, null, "S", null, null, null, null, null);

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
            assertThat(left)
                    .as("the same mark on a different row is a different submission, which is "
                            + "exactly what positional row codes are for")
                    .isNotEqualTo(shifted);
        }

        @Test
        @DisplayName("a caller that mutates its own array after construction cannot change what the "
                + "request reports, because the seven codes are separate components")
        void aCallerCannotChangeTheRequestAfterConstruction() {
            List<String> callerOwned = new ArrayList<>(
                    Arrays.asList("S", null, null, null, null, null, null));
            CardListRequest request = withRowCodes(null, null,
                    callerOwned.get(0), callerOwned.get(1), callerOwned.get(2), callerOwned.get(3),
                    callerOwned.get(4), callerOwned.get(5), callerOwned.get(6));

            callerOwned.set(0, "U");

            assertThat(request.selection1())
                    .as("no collection is retained, so there is nothing for a caller to reach back "
                            + "into")
                    .isEqualTo("S");
        }
    }
}
