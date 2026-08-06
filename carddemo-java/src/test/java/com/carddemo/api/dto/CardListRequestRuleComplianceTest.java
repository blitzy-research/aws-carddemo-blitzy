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

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.carddemo.domain.enums.KeyAction;
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
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link CardListRequest}, the request body of legacy transaction {@code CCLI}
 * implemented by {@code app/cbl/COCRDLIC.cbl} over screen {@code app/cpy-bms/COCRDLI.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Seven selection components, and the seven is not arbitrary.</strong> The legacy screen
 * holds seven rows, declared as {@code WS-SCREEN-ROWS OCCURS 7 TIMES} at
 * {@code app/cbl/COCRDLIC.cbl:255}. The program builds a selection bitmap positionally across those
 * seven rows and reports an invalid action against the row that carries it, so row order is part of the
 * contract rather than a rendering detail. {@code selectionsInRowOrder()} is the single place that order
 * is expressed, and it is asserted here row by row.
 *
 * <p><strong>Why nulls must survive the row list.</strong> An untouched row is a different input from a
 * row typed with a space: the legacy bitmap step converts {@code 'S'} and {@code 'U'} to one and every
 * other character - space included - to zero, whereas a row the client never sent has no character at
 * all. Compacting nulls out of the list, or substituting a blank for them, would shift every later row
 * left and report an error against the wrong line of the screen.
 *
 * @see CardListRequest
 */
@DisplayName("CardListRequest - the CCLI card list request contract")
class CardListRequestRuleComplianceTest {

    /** The number of rows the legacy screen holds, and therefore the length of the row list. */
    private static final int SCREEN_ROW_COUNT = 7;

    /**
     * A mapper configured exactly as {@code application.yml} configures the application's own.
     *
     * @return the module-equivalent mapper
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
    private static JsonNode payloadOf(final CardListRequest request) throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Builds a request whose seven selection components carry the supplied values in row order.
     *
     * @param selections exactly seven row values, any of which may be {@code null}
     * @return the request
     */
    private static CardListRequest aRequestWithSelections(final String... selections) {
        assertThat(selections)
                .as("the fixture must supply one value per screen row")
                .hasSize(SCREEN_ROW_COUNT);
        return new CardListRequest("00000000011", "4111111111111111", "001", selections[0],
                selections[1], selections[2], selections[3], selections[4], selections[5],
                selections[6], new PageMetadata.PageCursorRequest(null, "next",
                        PageMetadata.PagingDirection.FORWARD, null, false), false, KeyAction.ENTER,
                NavigationContext.empty());
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
        final Size size = CardListRequest.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published widths")
    class ThePublishedWidths {

        @Test
        @DisplayName("the two filter widths are the legacy key widths, eleven digits of account and "
                + "sixteen of card")
        void theTwoFilterWidthsAreTheLegacyKeyWidths() {
            assertThat(CardListRequest.ACCOUNT_ID_FILTER_LENGTH).isEqualTo(11);
            assertThat(CardListRequest.CARD_NUMBER_FILTER_LENGTH).isEqualTo(16);
        }

        @Test
        @DisplayName("the page-number and selection widths are three and one")
        void thePageNumberAndSelectionWidthsAreThreeAndOne() {
            assertThat(CardListRequest.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(3);
            assertThat(CardListRequest.SELECTION_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("the selection width is one because the legacy screen accepts a single action "
                + "character per row, so no row can carry two actions at once")
        void theSelectionWidthIsOne() {
            assertThat(CardListRequest.SELECTION_LENGTH).isOne();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape")
    class TheDeclaredShape {

        @Test
        @DisplayName("the request declares fourteen components, the three filters, seven rows and four "
                + "control components")
        void theRequestDeclaresFourteenComponents() {
            final List<String> declared = Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("accountIdFilter", "cardNumberFilter",
                    "displayedPageNumber", "selection1", "selection2", "selection3", "selection4",
                    "selection5", "selection6", "selection7", "pageMetadata", "lastPageAlreadyShown",
                    "keyAction", "navigationContext");
            assertThat(declared).hasSize(14);
        }

        @Test
        @DisplayName("exactly seven components are selection rows, matching the legacy OCCURS 7")
        void exactlySevenComponentsAreSelectionRows() {
            assertThat(Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> name.startsWith("selection")).toList())
                    .hasSize(SCREEN_ROW_COUNT);
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final CardListRequest request = aRequestWithSelections("S", null, "U", null, null, null,
                    null);

            assertThat(request.accountIdFilter()).isEqualTo("00000000011");
            assertThat(request.cardNumberFilter()).isEqualTo("4111111111111111");
            assertThat(request.displayedPageNumber()).isEqualTo("001");
            assertThat(request.selection1()).isEqualTo("S");
            assertThat(request.selection2()).isNull();
            assertThat(request.selection3()).isEqualTo("U");
            assertThat(request.selection4()).isNull();
            assertThat(request.selection5()).isNull();
            assertThat(request.selection6()).isNull();
            assertThat(request.selection7()).isNull();
            assertThat(request.pageMetadata().nextCursorKey()).isEqualTo("next");
            assertThat(request.pageMetadata().direction())
                    .as("the request carries a cursor and a direction, and no page size: the size is "
                            + "the screen's own and the server supplies it")
                    .isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(request.keyAction()).isEqualTo(KeyAction.ENTER);
            assertThat(request.navigationContext()).isEqualTo(NavigationContext.empty());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the row-ordered selection view")
    class TheRowOrderedSelectionView {

        @Test
        @DisplayName("the view always holds seven entries, one per screen row")
        void theViewAlwaysHoldsSevenEntries() {
            assertThat(aRequestWithSelections(null, null, null, null, null, null, null)
                    .selectionsInRowOrder()).hasSize(SCREEN_ROW_COUNT);
            assertThat(aRequestWithSelections("S", "U", "S", "U", "S", "U", "S")
                    .selectionsInRowOrder()).hasSize(SCREEN_ROW_COUNT);
        }

        @Test
        @DisplayName("the view lists the rows in declaration order, first row first")
        void theViewListsTheRowsInDeclarationOrder() {
            assertThat(aRequestWithSelections("1", "2", "3", "4", "5", "6", "7")
                    .selectionsInRowOrder())
                    .containsExactly("1", "2", "3", "4", "5", "6", "7");
        }

        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
        @DisplayName("a value typed into one row appears at that row's index and nowhere else")
        void aValueAppearsAtItsOwnRowIndexOnly(final int rowIndex) {
            final String[] selections = new String[SCREEN_ROW_COUNT];
            selections[rowIndex] = "S";

            final List<String> rows = aRequestWithSelections(selections).selectionsInRowOrder();

            assertThat(rows.get(rowIndex)).isEqualTo("S");
            for (int index = 0; index < SCREEN_ROW_COUNT; index++) {
                if (index != rowIndex) {
                    assertThat(rows.get(index)).as("row %d", index).isNull();
                }
            }
        }

        @Test
        @DisplayName("an untouched row is preserved as absent rather than compacted away, so a later row "
                + "keeps its own index")
        void anUntouchedRowIsPreservedAsAbsent() {
            final List<String> rows = aRequestWithSelections(null, null, null, null, null, null, "U")
                    .selectionsInRowOrder();

            assertThat(rows).containsExactly(null, null, null, null, null, null, "U");
            assertThat(rows.get(6)).isEqualTo("U");
        }

        @Test
        @DisplayName("a blank row is preserved as a blank rather than folded into absent, because the "
                + "legacy bitmap distinguishes a typed space from an untyped row")
        void aBlankRowIsPreservedAsABlank() {
            final List<String> rows = aRequestWithSelections(" ", null, null, null, null, null, null)
                    .selectionsInRowOrder();

            assertThat(rows.get(0)).isEqualTo(" ").isNotNull();
            assertThat(rows.get(1)).isNull();
        }

        @Test
        @DisplayName("the view cannot be added to, removed from or written through, so a caller cannot "
                + "reach back into the request")
        void theViewCannotBeMutated() {
            final List<String> rows =
                    aRequestWithSelections("S", null, null, null, null, null, null)
                            .selectionsInRowOrder();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.set(0, "U"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add("U"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(rows::clear);
        }

        @Test
        @DisplayName("two calls produce equal views, so the order is stable rather than incidental")
        void twoCallsProduceEqualViews() {
            final CardListRequest request =
                    aRequestWithSelections("S", "U", null, null, null, null, null);

            assertThat(request.selectionsInRowOrder())
                    .isEqualTo(request.selectionsInRowOrder());
        }

        @Test
        @DisplayName("the view agrees with the seven accessors read independently, so it introduces no "
                + "reordering of its own")
        void theViewAgreesWithTheSevenAccessors() {
            final CardListRequest request =
                    aRequestWithSelections("A", "B", "C", "D", "E", "F", "G");

            assertThat(request.selectionsInRowOrder()).containsExactly(request.selection1(),
                    request.selection2(), request.selection3(), request.selection4(),
                    request.selection5(), request.selection6(), request.selection7());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the redacting rendering")
    class TheRedactingRendering {

        @Test
        @DisplayName("neither filter value appears in the rendering, because an account number and a "
                + "card number are both cardholder data")
        void neitherFilterValueAppearsInTheRendering() {
            final String rendered =
                    aRequestWithSelections("S", null, null, null, null, null, null).toString();

            assertThat(rendered).doesNotContain("00000000011");
            assertThat(rendered).doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("the paging cursors do not appear either, because a card-list cursor is built from "
                + "the card number itself")
        void thePagingCursorsDoNotAppear() {
            final CardListRequest request = new CardListRequest(null, null, "001", null, null, null,
                    null, null, null, null, new PageMetadata.PageCursorRequest(
                            "4111111111111111", "4222222222222222",
                            PageMetadata.PagingDirection.BACKWARD, null, false), false, KeyAction.PFK08,
                    NavigationContext.empty());

            assertThat(request.toString()).doesNotContain("4111111111111111");
            assertThat(request.toString()).doesNotContain("4222222222222222");
        }

        @Test
        @DisplayName("the rendering names every redacted component and substitutes the placeholder "
                + "exactly three times, once per component it withholds")
        void theRenderingNamesEveryRedactedComponent() {
            // The navigation context redacts components of its own, so it is left absent here in order
            // that the count measures this record's own redactions rather than the sum of two records'.
            final String rendered = new CardListRequest("00000000011", "4111111111111111", "001",
                    "S", null, null, null, null, null, null, new PageMetadata.PageCursorRequest(
                            null, "next", PageMetadata.PagingDirection.FORWARD, null, false), false,
                    KeyAction.ENTER, null).toString();

            assertThat(rendered).contains("accountIdFilter=***REDACTED***");
            assertThat(rendered).contains("cardNumberFilter=***REDACTED***");
            assertThat(rendered).contains("pageMetadata=***REDACTED***");
            assertThat(rendered.split("\\*\\*\\*REDACTED\\*\\*\\*", -1)).hasSize(4);
        }

        @Test
        @DisplayName("the seven rows, the page number and the key are rendered in the clear, because an "
                + "action character carries no cardholder data and a diagnostic needs it")
        void theRowsPageNumberAndKeyAreRenderedInTheClear() {
            final String rendered =
                    aRequestWithSelections("S", "U", null, null, null, null, "S").toString();

            assertThat(rendered).contains("displayedPageNumber=001");
            assertThat(rendered).contains("selection1=S");
            assertThat(rendered).contains("selection2=U");
            assertThat(rendered).contains("selection7=S");
            assertThat(rendered).contains("keyAction=ENTER");
        }

        @Test
        @DisplayName("the rendering opens with the type name and closes with a bracket, matching the "
                + "record rendering it replaces")
        void theRenderingKeepsTheRecordShape() {
            final String rendered =
                    aRequestWithSelections(null, null, null, null, null, null, null).toString();

            assertThat(rendered).startsWith("CardListRequest[").endsWith("]");
        }

        @Test
        @DisplayName("an absent filter still renders as the placeholder rather than as null, so the "
                + "rendering never reveals whether a filter was supplied")
        void anAbsentFilterStillRendersAsThePlaceholder() {
            final CardListRequest request = new CardListRequest(null, null, null, null, null, null,
                    null, null, null, null, null, false, null, null);

            assertThat(request.toString()).contains("accountIdFilter=***REDACTED***");
            assertThat(request.toString()).contains("cardNumberFilter=***REDACTED***");
            assertThat(request.toString()).contains("pageMetadata=***REDACTED***");
        }

        @Test
        @DisplayName("the redaction placeholder is declared private, because a caller has no reason to "
                + "read it and every reason not to compare against it")
        void theRedactionPlaceholderIsDeclaredPrivate() {
            assertThat(Arrays.stream(CardListRequest.class.getDeclaredFields())
                    .filter(field -> "REDACTION_PLACEHOLDER".equals(field.getName())).toList())
                    .singleElement()
                    .satisfies(field -> assertThat(Modifier.isPrivate(field.getModifiers()))
                            .isTrue());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared validation bounds")
    class TheDeclaredValidationBounds {

        @ParameterizedTest(name = "{0} bounded at {1}")
        @CsvSource({
            "accountIdFilter, 11",
            "cardNumberFilter, 16",
            "displayedPageNumber, 3",
            "selection1, 1",
            "selection2, 1",
            "selection3, 1",
            "selection4, 1",
            "selection5, 1",
            "selection6, 1",
            "selection7, 1"
        })
        @DisplayName("each bounded component carries the legacy field width")
        void eachBoundedComponentCarriesTheLegacyWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {

            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("every one of the seven rows is bounded identically, so no row is accidentally "
                + "wider than its neighbours")
        void everyRowIsBoundedIdentically() throws NoSuchMethodException {
            for (int row = 1; row <= SCREEN_ROW_COUNT; row++) {
                assertThat(declaredMaximumLength("selection" + row))
                        .as("row %d", row)
                        .isEqualTo(CardListRequest.SELECTION_LENGTH);
            }
        }

        @Test
        @DisplayName("a fully populated request passes validation")
        void aFullyPopulatedRequestPassesValidation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(
                        aRequestWithSelections("S", null, null, null, null, null, null))).isEmpty();
            }
        }

        @Test
        @DisplayName("a request with nothing supplied passes validation, because a length bound says "
                + "nothing about presence and an empty list screen is a legitimate first entry")
        void anEmptyRequestPassesValidation() {
            final CardListRequest empty = new CardListRequest(null, null, null, null, null, null,
                    null, null, null, null, null, false, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(empty)).isEmpty();
            }
        }

        @Test
        @DisplayName("a two-character row is reported against that row alone, so a client learns which "
                + "line of the screen was over-filled")
        void aTwoCharacterRowIsReportedAgainstThatRow() {
            final CardListRequest overBound =
                    aRequestWithSelections(null, null, null, "SU", null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("selection4");
            }
        }

        @Test
        @DisplayName("a filter one digit over its key width is reported")
        void aFilterOneDigitOverItsKeyWidthIsReported() {
            final CardListRequest overBound = new CardListRequest(
                    "0".repeat(CardListRequest.ACCOUNT_ID_FILTER_LENGTH + 1),
                    "4".repeat(CardListRequest.CARD_NUMBER_FILTER_LENGTH + 1), null, null, null,
                    null, null, null, null, null, null, false, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("accountIdFilter", "cardNumberFilter");
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two requests built the same way are equal and share a hash code")
        void twoIdenticalRequestsAreEqual() {
            final CardListRequest first =
                    aRequestWithSelections("S", null, null, null, null, null, null);
            final CardListRequest second =
                    aRequestWithSelections("S", null, null, null, null, null, null);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("the same action typed into two different rows produces two different requests, "
                + "because the row is part of the identity")
        void theSameActionInTwoRowsProducesTwoDifferentRequests() {
            assertThat(aRequestWithSelections("S", null, null, null, null, null, null))
                    .isNotEqualTo(aRequestWithSelections(null, "S", null, null, null, null, null));
        }

        @Test
        @DisplayName("an absent row is omitted from the payload, so the wire carries only the rows the "
                + "client actually typed into")
        void anAbsentRowIsOmittedFromThePayload() throws JsonProcessingException {
            final JsonNode payload =
                    payloadOf(aRequestWithSelections("S", null, null, null, null, null, "U"));

            assertThat(payload.get("selection1").asText()).isEqualTo("S");
            assertThat(payload.get("selection7").asText()).isEqualTo("U");
            assertThat(payload.has("selection2")).isFalse();
            assertThat(payload.has("selection6")).isFalse();
        }

        @Test
        @DisplayName("the row-ordered view is not published, because it is a server-side reading of the "
                + "seven components rather than a fourteenth field on the wire")
        void theRowOrderedViewIsNotPublished() throws JsonProcessingException {
            final JsonNode payload =
                    payloadOf(aRequestWithSelections("S", null, null, null, null, null, null));

            assertThat(payload.has("selectionsInRowOrder")).isFalse();
            assertThat(payload.has("selection1")).isTrue();
        }

        /**
         * A round trip loses the page number, and naming which component it loses is the point.
         *
         * <p>The displayed page number is written outbound and ignored inbound: the screen shows it, and a
         * client that echoed it back could otherwise ask to be told it was on a page it was not on. It is
         * therefore the one component a round trip does not carry, and asserting plain equality here would
         * have been asserting that the binding does not exist.</p>
         *
         * <p>Equality against a rebuilt original with that one slot emptied is the stronger statement: it
         * says exactly one component is lost and every other survives, so a second component silently
         * acquiring a directional binding would fail this test, which a simple inequality check could
         * never detect.</p>
         */
        @Test
        @DisplayName("a round trip carries every component except the outbound-only page number, and the "
                + "row positions survive in order")
        void aRequestRoundTripsWithoutItsReadOnlyPageNumber() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final CardListRequest original =
                    aRequestWithSelections(null, "U", null, null, "S", null, null);

            final CardListRequest restored = mapper.readValue(
                    mapper.writeValueAsString(original), CardListRequest.class);

            assertThat(restored).isNotEqualTo(original);
            assertThat(restored.displayedPageNumber())
                    .as("the page number is written for the screen and never bound from the client")
                    .isNull();
            assertThat(restored)
                    .as("exactly one component is lost, and it is that one")
                    .isEqualTo(new CardListRequest(original.accountIdFilter(),
                            original.cardNumberFilter(), null, original.selection1(),
                            original.selection2(), original.selection3(), original.selection4(),
                            original.selection5(), original.selection6(), original.selection7(),
                            original.pageMetadata(), false, original.keyAction(),
                            original.navigationContext()));
            assertThat(restored.selectionsInRowOrder())
                    .containsExactly(null, "U", null, null, "S", null, null);
        }

        @Test
        @DisplayName("a request built from a mutable array is unaffected by a later change to it, "
                + "because the components are copied by value on the way in")
        void aRequestIsUnaffectedByALaterChangeToTheFixtureArray() {
            final List<String> mutable = new ArrayList<>(
                    Arrays.asList("S", null, null, null, null, null, null));
            final CardListRequest request = aRequestWithSelections(mutable.toArray(new String[0]));

            mutable.set(0, "U");

            assertThat(request.selection1()).isEqualTo("S");
        }
    }
}
