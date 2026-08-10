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
import java.util.Locale;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link CardListResponse}, the response body of legacy transaction {@code CCLI}
 * implemented by {@code app/cbl/COCRDLIC.cbl} over screen {@code app/cpy-bms/COCRDLI.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Nine operator messages, reproduced character for character.</strong> The legacy screen's
 * messages are upper case, and two of them carry punctuation that a tidying hand would remove - a comma
 * with no following space in the two filter messages, and a full stop that only the not-found message
 * has. Gate 5 compares these against the operator-visible text, so each is asserted literally rather
 * than through a formatter.
 *
 * <p><strong>Two lists, both defaulted rather than nullable.</strong> The compact constructor turns an
 * absent row list into an empty one, because a card-list screen with no rows is a real state - the
 * not-found message exists precisely to describe it - and a client should not have to distinguish absent
 * from empty to render it. The selection-error flags are defaulted the same way for the same reason.
 * Both are asserted, along with the defensive copy that keeps a caller's later change out of a published
 * response.
 *
 * @see CardListResponse
 */
@DisplayName("CardListResponse - the CCLI card list response contract")
class CardListResponseRuleComplianceTest {

    /** The number of rows the legacy screen holds. */
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
     * Serializes a response and reads the result back as a tree.
     *
     * @param response the response to render
     * @return the rendered payload
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static JsonNode payloadOf(final CardListResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Builds a full page of seven rows, each carrying a distinct card number.
     *
     * @return the seven rows in screen order
     */
    private static List<CardListResponse.CardListRow> aFullPageOfRows() {
        final List<CardListResponse.CardListRow> rows = new ArrayList<>();
        for (int row = 1; row <= SCREEN_ROW_COUNT; row++) {
            rows.add(new CardListResponse.CardListRow(1,null, "0000000001" + row,
                    "411111111111111" + row, row % 2 == 0 ? "N" : "Y"));
        }
        return List.copyOf(rows);
    }

    /**
     * Builds a full page of selection-error flags, marking the named row positions.
     *
     * <p>The indicator is positional, so the constructor requires either no flags at all or exactly one
     * per row - element <em>i</em> describes row <em>i</em>, and an unmarked slot is present and
     * {@code false} rather than absent. A shorter list would have no reading: there would be no way to
     * tell which rows it described. Every fixture that publishes flags therefore builds a full page of
     * them through this helper rather than listing however many the assertion happened to need.</p>
     *
     * @param markedRowIndexes the zero-based row positions to mark
     * @return exactly {@link #SCREEN_ROW_COUNT} flags, true at the named positions and false elsewhere
     */
    private static List<Boolean> aFullPageOfFlags(final int... markedRowIndexes) {
        final List<Boolean> flags = new ArrayList<>();
        for (int row = 0; row < SCREEN_ROW_COUNT; row++) {
            boolean marked = false;
            for (final int markedRowIndex : markedRowIndexes) {
                marked = marked || markedRowIndex == row;
            }
            flags.add(marked);
        }
        return List.copyOf(flags);
    }

    /**
     * Builds a populated response carrying the supplied rows and flags.
     *
     * @param rows                the rows to publish, possibly {@code null}
     * @param selectionErrorFlags the per-row error flags to publish, possibly {@code null}
     * @return the response
     */
    private static CardListResponse aResponse(final List<CardListResponse.CardListRow> rows,
            final List<Boolean> selectionErrorFlags) {

        return new CardListResponse("CCLI", "AWS Mainframe Modernization", "01/15/22", "COCRDLIC",
                "CardDemo", "10:30:00", "001", "00000000011", "4111111111111111", rows,
                selectionErrorFlags, CardListResponse.MSG_ROW_ACTION_PROMPT, null, false,
                PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE, null, "next", true, false,
                        "001"), false, List.of(), "CRDSEL1", "card-list", NavigationContext.empty(), null);
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
        final Size size = CardListResponse.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published widths")
    class ThePublishedWidths {

        @Test
        @DisplayName("the screen-furniture widths are the legacy ones")
        void theScreenFurnitureWidthsAreTheLegacyOnes() {
            assertThat(CardListResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(CardListResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(CardListResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(3);
            assertThat(CardListResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the key and row widths are the legacy record widths")
        void theKeyAndRowWidthsAreTheLegacyRecordWidths() {
            assertThat(CardListResponse.ACCOUNT_NUMBER_LENGTH).isEqualTo(11);
            assertThat(CardListResponse.CARD_NUMBER_LENGTH).isEqualTo(16);
            assertThat(CardListResponse.SELECTION_LENGTH).isEqualTo(1);
            assertThat(CardListResponse.CARD_STATUS_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("the information message is narrower than the error message, because the legacy "
                + "screen gives the two different fields")
        void theInformationMessageIsNarrowerThanTheErrorMessage() {
            assertThat(CardListResponse.INFO_MESSAGE_LENGTH).isEqualTo(45);
            assertThat(CardListResponse.ERROR_MESSAGE_LENGTH).isEqualTo(78);
            assertThat(CardListResponse.INFO_MESSAGE_LENGTH)
                    .isLessThan(CardListResponse.ERROR_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the account and card widths agree with the request's own filter widths, so a filter "
                + "echoed back cannot overflow the field it came from")
        void theKeyWidthsAgreeWithTheRequest() {
            assertThat(CardListResponse.ACCOUNT_NUMBER_LENGTH)
                    .isEqualTo(CardListRequest.ACCOUNT_ID_FILTER_LENGTH);
            assertThat(CardListResponse.CARD_NUMBER_LENGTH)
                    .isEqualTo(CardListRequest.CARD_NUMBER_FILTER_LENGTH);
            assertThat(CardListResponse.SELECTION_LENGTH)
                    .isEqualTo(CardListRequest.SELECTION_LENGTH);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the nine operator messages")
    class TheNineOperatorMessages {

        @Test
        @DisplayName("the two filter messages carry the legacy comma with no following space")
        void theTwoFilterMessagesCarryTheLegacyComma() {
            assertThat(CardListResponse.MSG_ACCOUNT_FILTER_INVALID)
                    .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
            assertThat(CardListResponse.MSG_CARD_FILTER_INVALID)
                    .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
            assertThat(CardListResponse.MSG_ACCOUNT_FILTER_INVALID).contains("FILTER,IF");
            assertThat(CardListResponse.MSG_CARD_FILTER_INVALID).contains("FILTER,IF");
        }

        @Test
        @DisplayName("the action messages carry the legacy text")
        void theActionMessagesCarryTheLegacyText() {
            assertThat(CardListResponse.MSG_INVALID_ACTION_CODE).isEqualTo("INVALID ACTION CODE");
            assertThat(CardListResponse.MSG_MORE_THAN_ONE_ACTION)
                    .isEqualTo("PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE");
            assertThat(CardListResponse.MSG_ROW_ACTION_PROMPT)
                    .isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        }

        @Test
        @DisplayName("the paging messages carry the legacy text and are three distinct messages, because "
                + "no previous page, no next page and no records are three different situations")
        void thePagingMessagesAreThreeDistinctMessages() {
            assertThat(CardListResponse.MSG_NO_PREVIOUS_PAGES)
                    .isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
            assertThat(CardListResponse.MSG_NO_MORE_PAGES).isEqualTo("NO MORE PAGES TO DISPLAY");
            assertThat(CardListResponse.MSG_NO_MORE_RECORDS).isEqualTo("NO MORE RECORDS TO SHOW");
            assertThat(List.of(CardListResponse.MSG_NO_PREVIOUS_PAGES,
                    CardListResponse.MSG_NO_MORE_PAGES, CardListResponse.MSG_NO_MORE_RECORDS))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the not-found message ends in a full stop, which is the only one of the nine that "
                + "does")
        void theNotFoundMessageEndsInAFullStop() {
            assertThat(CardListResponse.MSG_NO_RECORDS_FOUND)
                    .isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.")
                    .endsWith(".");
            assertThat(List.of(CardListResponse.MSG_ACCOUNT_FILTER_INVALID,
                    CardListResponse.MSG_CARD_FILTER_INVALID,
                    CardListResponse.MSG_INVALID_ACTION_CODE,
                    CardListResponse.MSG_MORE_THAN_ONE_ACTION,
                    CardListResponse.MSG_NO_PREVIOUS_PAGES, CardListResponse.MSG_NO_MORE_PAGES,
                    CardListResponse.MSG_NO_MORE_RECORDS, CardListResponse.MSG_ROW_ACTION_PROMPT))
                    .allSatisfy(message -> assertThat(message).doesNotEndWith("."));
        }

        @Test
        @DisplayName("every message is upper case, matching a 3270 screen that has no lower case")
        void everyMessageIsUpperCase() {
            for (final String message : List.of(CardListResponse.MSG_ACCOUNT_FILTER_INVALID,
                    CardListResponse.MSG_CARD_FILTER_INVALID,
                    CardListResponse.MSG_INVALID_ACTION_CODE,
                    CardListResponse.MSG_MORE_THAN_ONE_ACTION,
                    CardListResponse.MSG_NO_PREVIOUS_PAGES, CardListResponse.MSG_NO_MORE_PAGES,
                    CardListResponse.MSG_NO_MORE_RECORDS, CardListResponse.MSG_NO_RECORDS_FOUND,
                    CardListResponse.MSG_ROW_ACTION_PROMPT)) {

                assertThat(message).isEqualTo(message.toUpperCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("the two filter messages fit the error field and the row prompt fits the "
                + "information field, so no message is published wider than the field that renders it")
        void everyMessageFitsTheFieldThatRendersIt() {
            assertThat(CardListResponse.MSG_ACCOUNT_FILTER_INVALID.length())
                    .isLessThanOrEqualTo(CardListResponse.ERROR_MESSAGE_LENGTH);
            assertThat(CardListResponse.MSG_CARD_FILTER_INVALID.length())
                    .isLessThanOrEqualTo(CardListResponse.ERROR_MESSAGE_LENGTH);
            assertThat(CardListResponse.MSG_ROW_ACTION_PROMPT.length())
                    .isLessThanOrEqualTo(CardListResponse.INFO_MESSAGE_LENGTH);
            assertThat(CardListResponse.MSG_NO_RECORDS_FOUND.length())
                    .isLessThanOrEqualTo(CardListResponse.INFO_MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the nine messages are all distinct, so no two situations are reported with the "
                + "same words")
        void theNineMessagesAreAllDistinct() {
            assertThat(List.of(CardListResponse.MSG_ACCOUNT_FILTER_INVALID,
                    CardListResponse.MSG_CARD_FILTER_INVALID,
                    CardListResponse.MSG_INVALID_ACTION_CODE,
                    CardListResponse.MSG_MORE_THAN_ONE_ACTION,
                    CardListResponse.MSG_NO_PREVIOUS_PAGES, CardListResponse.MSG_NO_MORE_PAGES,
                    CardListResponse.MSG_NO_MORE_RECORDS, CardListResponse.MSG_NO_RECORDS_FOUND,
                    CardListResponse.MSG_ROW_ACTION_PROMPT))
                    .hasSize(9)
                    .doesNotHaveDuplicates();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the compact constructor's two defaulted lists")
    class TheCompactConstructorsTwoDefaultedLists {

        @Test
        @DisplayName("an absent row list becomes an empty one, so a client never has to distinguish "
                + "absent from empty to render a page with nothing on it")
        void anAbsentRowListBecomesAnEmptyOne() {
            final CardListResponse response = aResponse(null, null);

            assertThat(response.rows()).isNotNull().isEmpty();
            assertThat(response.selectionErrorFlags()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("a supplied row list is copied, so a later change to the caller's list cannot reach "
                + "inside the response")
        void aSuppliedRowListIsCopied() {
            final List<CardListResponse.CardListRow> mutable = new ArrayList<>(aFullPageOfRows());

            final CardListResponse response = aResponse(mutable, aFullPageOfFlags(1));
            mutable.clear();

            assertThat(response.rows()).hasSize(SCREEN_ROW_COUNT);
        }

        @Test
        @DisplayName("a supplied flag list is copied too")
        void aSuppliedFlagListIsCopied() {
            final List<Boolean> mutable = new ArrayList<>(aFullPageOfFlags(0, 2));

            final CardListResponse response = aResponse(aFullPageOfRows(), mutable);
            mutable.clear();

            assertThat(response.selectionErrorFlags())
                    .hasSize(SCREEN_ROW_COUNT)
                    .containsExactly(true, false, true, false, false, false, false);
        }

        @Test
        @DisplayName("both copies are unmodifiable, so nothing downstream can alter a published page")
        void bothCopiesAreUnmodifiable() {
            final CardListResponse response = aResponse(aFullPageOfRows(), aFullPageOfFlags());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.rows().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.selectionErrorFlags().clear());
        }

        /**
         * The alignment between flags and rows is an invariant, not a convention.
         *
         * <p>The indicator marks the rows that contributed to a rejection and leaves the others
         * explicitly unmarked, so a list shorter than the page cannot be read: nothing says whether its
         * entries describe the first rows, the marked rows, or some compacted subset. Compacting it would
         * destroy exactly the alignment that lets the screen mark the offending rows. The constructor
         * therefore admits two shapes and no others - no flags at all, or one per row - and refuses
         * anything between them where the mistake is made rather than letting a misaligned page reach a
         * client.</p>
         */
        @Test
        @DisplayName("a flag list neither empty nor one-per-row is refused, because a positional "
                + "indicator shorter than the page has no reading")
        void aPartialFlagListIsRefused() {
            final List<CardListResponse.CardListRow> rows = aFullPageOfRows();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> aResponse(rows, List.of(true, false)))
                    .withMessageContaining("positional")
                    .withMessageContaining("2 entries for " + SCREEN_ROW_COUNT + " rows");
            assertThatCode(() -> aResponse(rows, List.of()))
                    .as("no flags at all is the other admissible shape")
                    .doesNotThrowAnyException();
            assertThatCode(() -> aResponse(rows, aFullPageOfFlags(3)))
                    .as("one flag per row is the first admissible shape")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a row list containing an absent row is rejected, because a null row is one a client "
                + "could not render")
        void aRowListContainingAnAbsentRowIsRejected() {
            final List<CardListResponse.CardListRow> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aResponse(withNull, null));
        }

        @Test
        @DisplayName("a flag list containing an absent flag is rejected, because an unknown per-row state "
                + "is not a state the screen has")
        void aFlagListContainingAnAbsentFlagIsRejected() {
            final List<Boolean> withNull = new ArrayList<>();
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aResponse(null, withNull));
        }

        @Test
        @DisplayName("an already-empty list is preserved as empty rather than replaced, so the two "
                + "arrivals at empty are indistinguishable downstream")
        void anAlreadyEmptyListIsPreservedAsEmpty() {
            assertThat(aResponse(List.of(), List.of()).rows())
                    .isEqualTo(aResponse(null, null).rows());
            assertThat(aResponse(List.of(), List.of()).selectionErrorFlags())
                    .isEqualTo(aResponse(null, null).selectionErrorFlags());
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the nested row type")
    class TheNestedRowType {

        @Test
        @DisplayName("a row declares five components: the screen slot it occupies, the action character "
                + "and the three values the screen shows")
        void aRowDeclaresFiveComponents() {
            assertThat(Arrays.stream(CardListResponse.CardListRow.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .containsExactly("screenSlot", "selection", "accountNumber", "cardNumber",
                            "cardStatus");
        }

        @Test
        @DisplayName("a row round-trips through its own accessors unchanged")
        void aRowRoundTripsThroughItsAccessors() {
            final CardListResponse.CardListRow row = new CardListResponse.CardListRow(1,"S",
                    "00000000011", "4111111111111111", "Y");

            assertThat(row.selection()).isEqualTo("S");
            assertThat(row.accountNumber()).isEqualTo("00000000011");
            assertThat(row.cardNumber()).isEqualTo("4111111111111111");
            assertThat(row.cardStatus()).isEqualTo("Y");
        }

        @Test
        @DisplayName("two rows carrying the same values are equal and share a hash code")
        void twoIdenticalRowsAreEqual() {
            final CardListResponse.CardListRow first =
                    new CardListResponse.CardListRow(1,null, "00000000011", "4111111111111111", "Y");
            final CardListResponse.CardListRow second =
                    new CardListResponse.CardListRow(1,null, "00000000011", "4111111111111111", "Y");

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a row with nothing supplied is constructible, which is the state a short final page "
                + "pads its unused lines with")
        void aRowWithNothingSuppliedIsConstructible() {
            final CardListResponse.CardListRow blank =
                    new CardListResponse.CardListRow(1,null, null, null, null);

            assertThat(blank.selection()).isNull();
            assertThat(blank.cardNumber()).isNull();
        }

        @Test
        @DisplayName("each row component carries the response's own declared width, so a row cannot hold "
                + "a value the enclosing screen field could not show")
        void eachRowComponentCarriesTheResponsesDeclaredWidth() throws NoSuchMethodException {
            assertThat(CardListResponse.CardListRow.class.getDeclaredMethod("selection")
                    .getAnnotation(Size.class).max()).isEqualTo(CardListResponse.SELECTION_LENGTH);
            assertThat(CardListResponse.CardListRow.class.getDeclaredMethod("accountNumber")
                    .getAnnotation(Size.class).max())
                    .isEqualTo(CardListResponse.ACCOUNT_NUMBER_LENGTH);
            assertThat(CardListResponse.CardListRow.class.getDeclaredMethod("cardNumber")
                    .getAnnotation(Size.class).max()).isEqualTo(CardListResponse.CARD_NUMBER_LENGTH);
            assertThat(CardListResponse.CardListRow.class.getDeclaredMethod("cardStatus")
                    .getAnnotation(Size.class).max())
                    .isEqualTo(CardListResponse.CARD_STATUS_LENGTH);
        }

        @Test
        @DisplayName("a full page holds exactly seven rows in the order they were supplied, because the "
                + "legacy screen shows seven and the row position carries the error")
        void aFullPageHoldsSevenRowsInSuppliedOrder() {
            final List<CardListResponse.CardListRow> rows = aFullPageOfRows();
            final CardListResponse response = aResponse(rows, null);

            assertThat(response.rows()).hasSize(SCREEN_ROW_COUNT).containsExactlyElementsOf(rows);
            assertThat(response.rows().get(0).cardNumber()).isEqualTo("4111111111111111");
            assertThat(response.rows().get(6).cardNumber()).isEqualTo("4111111111111117");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        @Test
        @DisplayName("the response declares twenty-one components in screen order")
        void theResponseDeclaresTwentyOneComponents() {
            final List<String> declared = Arrays.stream(CardListResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("transactionName", "title01", "currentDate",
                    "programName", "title02", "currentTime", "displayedPageNumber", "accountFilter",
                    "cardNumberFilter", "rows", "selectionErrorFlags", "infoMessage", "errorMessage",
                    "generalError", "pageMetadata", "lastPageAlreadyShown", "fieldErrors",
                    "focusScreenFieldId", "nextRoute", "navigationContext", "rowSnapshotToken");
            assertThat(declared).hasSize(21);
        }

        @ParameterizedTest(name = "{0} bounded at {1}")
        @CsvSource({
            "transactionName, 4",
            "title01, 40",
            "currentDate, 8",
            "programName, 8",
            "title02, 40",
            "currentTime, 8",
            "displayedPageNumber, 3",
            "accountFilter, 11",
            "cardNumberFilter, 16",
            "infoMessage, 45",
            "errorMessage, 78",
            "focusScreenFieldId, 7"
        })
        @DisplayName("each bounded component carries the legacy field width")
        void eachBoundedComponentCarriesTheLegacyWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {

            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the next route carries no upper bound, because it is a server-chosen token rather "
                + "than a screen field")
        void theNextRouteCarriesNoUpperBound() throws NoSuchMethodException {
            assertThat(CardListResponse.class.getDeclaredMethod("nextRoute")
                    .getAnnotation(Size.class)).isNull();
        }

        @Test
        @DisplayName("a fully populated response passes validation")
        void aFullyPopulatedResponsePassesValidation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aResponse(aFullPageOfRows(), aFullPageOfFlags())))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a response with nothing supplied passes validation and still publishes two empty "
                + "lists")
        void anEmptyResponsePassesValidationAndPublishesTwoEmptyLists() {
            final CardListResponse empty = new CardListResponse(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, false, null, false, List.of(), null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(empty)).isEmpty();
            }
            assertThat(empty.rows()).isEmpty();
            assertThat(empty.selectionErrorFlags()).isEmpty();
        }

        @Test
        @DisplayName("a filter one digit over its key width is reported against that component alone")
        void aFilterOneDigitOverItsKeyWidthIsReported() {
            final CardListResponse overBound = new CardListResponse(null, null, null, null, null,
                    null, null, "0".repeat(CardListResponse.ACCOUNT_NUMBER_LENGTH + 1), null, null,
                    null, null, null, false, null, false, List.of(), null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(overBound))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("accountFilter");
            }
        }

        @Test
        @DisplayName("an over-wide row is not reported by validating the response, because the row list "
                + "carries no cascade marker - the rows are the server's own output and not client input")
        void anOverWideRowIsNotReportedByValidatingTheResponse() {
            final CardListResponse response = aResponse(List.of(new CardListResponse.CardListRow(1,
                    "SU", "0".repeat(20), "4".repeat(20), "YN")), null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(response)).isEmpty();
                assertThat(factory.getValidator().validate(response.rows().get(0)))
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("selection", "accountNumber", "cardNumber",
                                "cardStatus");
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two responses built the same way are equal and share a hash code")
        void twoIdenticalResponsesAreEqual() {
            final CardListResponse first = aResponse(aFullPageOfRows(), aFullPageOfFlags());
            final CardListResponse second = aResponse(aFullPageOfRows(), aFullPageOfFlags());

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a response built with an absent list equals one built with an empty list, because "
                + "the constructor has already made them the same")
        void anAbsentListEqualsAnEmptyList() {
            assertThat(aResponse(null, null)).isEqualTo(aResponse(List.of(), List.of()));
        }

        @Test
        @DisplayName("two pages holding the same rows in a different order are not equal, because row "
                + "position is part of the contract")
        void twoPagesInADifferentOrderAreNotEqual() {
            final List<CardListResponse.CardListRow> forward = aFullPageOfRows();
            final List<CardListResponse.CardListRow> reversed = new ArrayList<>(forward);
            Collections.reverse(reversed);

            assertThat(aResponse(forward, null)).isNotEqualTo(aResponse(reversed, null));
        }

        @Test
        @DisplayName("an absent component is omitted from the payload, and the two lists are always "
                + "present because they are never absent")
        void anAbsentComponentIsOmittedAndTheListsAreAlwaysPresent()
                throws JsonProcessingException {

            final JsonNode payload = payloadOf(aResponse(null, null));

            assertThat(payload.has("errorMessage")).isFalse();
            assertThat(payload.get("rows").isArray()).isTrue();
            assertThat(payload.get("rows")).isEmpty();
            assertThat(payload.get("selectionErrorFlags").isArray()).isTrue();
            assertThat(payload.get("generalError").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("each published row renders its own four components, and an absent action character "
                + "is omitted from the row rather than rendered as null")
        void eachPublishedRowRendersItsOwnComponents() throws JsonProcessingException {
            final JsonNode firstRow = payloadOf(aResponse(aFullPageOfRows(), null))
                    .get("rows").get(0);

            assertThat(firstRow.get("accountNumber").asText()).isEqualTo("00000000011");
            assertThat(firstRow.get("cardNumber").asText()).isEqualTo("4111111111111111");
            assertThat(firstRow.get("cardStatus").asText()).isEqualTo("Y");
            assertThat(firstRow.has("selection")).isFalse();
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged, "
                + "row order included")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final CardListResponse original = aResponse(aFullPageOfRows(), aFullPageOfFlags(1));

            final CardListResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), CardListResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.rows()).hasSize(SCREEN_ROW_COUNT);
            assertThat(restored.selectionErrorFlags().get(1)).isTrue();
        }

        @Test
        @DisplayName("a restored response's lists are unmodifiable too, so the defensive copy survives "
                + "deserialisation rather than being bypassed by it")
        void aRestoredResponsesListsAreUnmodifiable() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final CardListResponse restored = mapper.readValue(
                    mapper.writeValueAsString(aResponse(aFullPageOfRows(), aFullPageOfFlags(0))),
                    CardListResponse.class);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> restored.rows().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> restored.selectionErrorFlags().clear());
        }
    }
}
