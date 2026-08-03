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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionListRequest}, the inbound contract of legacy transaction
 * {@code CT00}.
 *
 * <p><strong>The screen presents ten rows, and the figure is established by loop bounds alone.</strong>
 * This is the subtlest paging fact in the estate. {@code app/cbl/COTRN00C.cbl} declares no row table
 * whatsoever for its displayed rows - its only table declaration, at line 89, is an unrelated
 * redefinition of the communication area and has nothing to do with paging. The ten rows emerge entirely
 * from loop bounds: the row-clearing loop is bounded at ten at line 290, the index is reset to one at
 * line 295, and the filling walk stops once the index reaches eleven at line 297. A reader who searches
 * that member for a row table and finds none must not conclude the figure is unfounded. Because the
 * figure is not declared in the program it is not re-declared here either: the row count is read from
 * the production constants and its agreement with the paging contract is asserted rather than assumed.
 * It is screen shape - the number of lines a 24x80 operator sees - and never a tuning figure.
 *
 * <p><strong>Backward paging fills the rows bottom upward.</strong> Forward paging fills top downward;
 * the backward path is the inverse - the paragraph begins at line 333, the index is seeded to the last
 * row at line 349, and the walk runs from last row to first at lines 351-357, reading in reverse at line
 * 352. A paging abstraction that collapsed that inversion into an always-ascending read would present a
 * backward page in the wrong sequence relative to the legacy screen, which is a visible behavioural
 * regression rather than a refactoring. This request carries the direction as data and imposes no
 * ordering.
 *
 * <p><strong>Two message-bearing rules live in {@code TransactionListService}, not in any constraint on
 * this type.</strong> The accepted-selection rule rejects an unaccepted character with the 35-character
 * text set at line 199, which names exactly one accepted letter in the singular - the administrative user
 * list names two in the plural at 43 characters, and the two are neither shared nor harmonised. The
 * filter rule rejects a non-numeric filter with the 27-character text set at line 214. Both cascades are
 * ordered and stop at their first match, which declarative validation cannot reproduce because it reports
 * violations in no defined order and several at once. The texts belong to the response contract and are
 * asserted there, so they are cited here by location and measured length only.
 *
 * <p>A pure unit test: no context, servlet environment, container or connection. Every property is proved
 * from observable behaviour - constructing, accessing, validating, serialising, deserialising - and never
 * by reading declarations at run time, which keeps the module's introspection count at zero and is the
 * stronger statement: an annotation read back proves only that it is present, whereas binding a body and
 * finding the value discarded proves the contract behaves as the legacy program did. The JSON shape is
 * exercised through a locally built mapper matching
 * {@code carddemo-java/src/main/resources/application.yml} and the constraints through a validator from
 * {@link Validation#buildDefaultValidatorFactory()} rather than any framework bean.
 */
@DisplayName("TransactionListRequest :: inbound contract of legacy transaction CT00")
class TransactionListRequestTest {
    private static final String FILTER_AT_FULL_WIDTH = "0000000000000042";

    private static final String FILTER_WITHOUT_LEADING_ZEROS = "42";

    private static final String FILTER_WITH_TRAILING_SPACES = "42              ";

    private static final String INDICATOR_AT_FULL_WIDTH = "00000003";

    private static final String MARKED = "S";

    private static final String MARKED_OTHER_CASE = "s";

    private static final String UNACCEPTED = "X";

    private static final String UNMARKED = "";

    private static final String FIRST_ROW_KEY = "0000000000000031";

    private static final String LAST_ROW_KEY = "0000000000000041";

    private static final String ACCOUNT_ID = "00000000011";

    private static final String CARD_NUMBER = "4111111111111111";

    private static final String CUSTOMER_ID = "000000011";

    private static final String WITHHELD = "***REDACTED***";

    private static final List<String> PROGRAM_FUNCTION_ACTION_NAMES = List.of(
            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");

    private static ObjectMapper moduleEquivalentMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    private static Set<ConstraintViolation<TransactionListRequest>> violationsOf(
            TransactionListRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    private static String soleViolationPathOf(TransactionListRequest request) {
        Set<ConstraintViolation<TransactionListRequest>> violations = violationsOf(request);
        assertThat(violations)
                .as("exactly one declarative violation was expected, so that a single over-wide value"
                        + " cannot be masked by a second constraint firing beside it")
                .hasSize(1);
        return violations.iterator().next().getPropertyPath().toString();
    }

    private static NavigationContext navigation() {
        return new NavigationContext("CT00", "COTRN00C", "CT01", "COTRN01C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, CUSTOMER_ID, "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN0A", "COTRN00");
    }

    private static PageMetadata.PageCursorRequest cursor(PageMetadata.PagingDirection direction) {
        return new PageMetadata.PageCursorRequest(FIRST_ROW_KEY, LAST_ROW_KEY, direction);
    }

    private static List<String> everyRowUnmarked() {
        List<String> rows = new ArrayList<>();
        for (int position = 0; position < TransactionListRequest.ROW_SELECTOR_COUNT; position++) {
            rows.add(UNMARKED);
        }
        return rows;
    }

    private static List<String> rowsMarkedAt(int index) {
        List<String> rows = everyRowUnmarked();
        rows.set(index, MARKED);
        return rows;
    }

    private static TransactionListRequest withSelectors(List<String> selectors) {
        return new TransactionListRequest(null, null, selectors, KeyAction.ENTER, null, null);
    }

    private static TransactionListRequest populated() {
        return new TransactionListRequest(FILTER_AT_FULL_WIDTH, INDICATOR_AT_FULL_WIDTH,
                rowsMarkedAt(6), KeyAction.PFK08, navigation(),
                cursor(PageMetadata.PagingDirection.FORWARD));
    }

    @Nested
    @DisplayName("the screen row count comes from loop bounds and is read, never re-declared")
    class ScreenRowCountProvenance {
        @Test
        @DisplayName("the selector count agrees with the figure the paging contract names for this "
                + "screen, and is taken from there rather than restated")
        void selectorCountAgreesWithThePagingContract() {
            assertThat(TransactionListRequest.ROW_SELECTOR_COUNT)
                    .as("the map declares one selector item per displayed row, so the two production"
                            + " figures must agree even though each is declared for its own purpose")
                    .isEqualTo(TransactionListRequest.ROW_COUNT)
                    .isEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("the figure is this screen's own and is never borrowed from the card-list screen")
        void theFigureIsNotBorrowedFromTheCardListScreen() {
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .as("the card-list screen presents a different number of rows, and the two"
                            + " screens were established by unrelated mechanisms")
                    .isNotEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("one selector per displayed row is accepted, and an eleventh is refused")
        void oneSelectorPerRowIsAcceptedAndAnEleventhIsRefused() {
            assertThatNoException()
                    .as("a submission that answers every displayed row is the ordinary case")
                    .isThrownBy(() -> withSelectors(everyRowUnmarked()));

            List<String> oneTooMany = everyRowUnmarked();
            oneTooMany.add(UNMARKED);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an eleventh entry answers a row the screen does not have, so it is refused"
                            + " rather than silently discarded")
                    .isThrownBy(() -> withSelectors(oneTooMany))
                    .withMessage("rowSelectors may hold at most "
                            + TransactionListRequest.ROW_COUNT
                            + " entries, because that is how many row families the transaction-list"
                            + " screen declares, but it holds " + oneTooMany.size());
        }

        @Test
        @DisplayName("a shorter sequence is kept exactly as supplied and is never padded out")
        void aShorterSequenceIsKeptExactlyAsSupplied() {
            TransactionListRequest request = withSelectors(List.of(MARKED, UNMARKED));

            assertThat(request.rowSelectors())
                    .as("a final page legitimately displays fewer rows, and a submission that marks"
                            + " an early row need not answer the rows below it")
                    .containsExactly(MARKED, UNMARKED)
                    .hasSize(2);
        }

        @Test
        @DisplayName("each declared width is the width of the map item it echoes")
        void eachDeclaredWidthIsTheMapItemWidth() {
            assertThat(TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH)
                    .as("inbound item TRNIDIN, 16 characters, at app/cpy-bms/COTRN00.CPY line 66")
                    .isEqualTo(16);
            assertThat(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("inbound item PAGENUM, 8 characters, at app/cpy-bms/COTRN00.CPY line 60")
                    .isEqualTo(8);
            assertThat(TransactionListRequest.ROW_SELECTOR_LENGTH)
                    .as("each inbound selector item SEL0001 through SEL0010 is one character wide")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the row selectors are positional, and an unmarked slot is information")
    class PositionalRowSelectors {
        @Test
        @DisplayName("a mark at index seven reads back at index seven, every other slot unmarked")
        void aMarkAtIndexSevenReadsBackAtIndexSeven() {
            TransactionListRequest request = withSelectors(rowsMarkedAt(7));

            List<String> readBack = request.rowSelectors();

            assertThat(readBack).hasSize(TransactionListRequest.ROW_SELECTOR_COUNT);
            assertThat(readBack.get(7))
                    .as("the index is the whole contract: it is the only thing that says which"
                            + " displayed transaction was chosen")
                    .isEqualTo(MARKED);
            for (int position = 0; position < readBack.size(); position++) {
                if (position != 7) {
                    assertThat(readBack.get(position))
                            .as("slot %d was left unmarked and must read back unmarked", position)
                            .isEqualTo(UNMARKED);
                }
            }
        }

        @Test
        @DisplayName("a mark on the seventh displayed row reads back at the seventh slot, which is "
                + "index six because the sequence is zero-based")
        void aMarkOnTheSeventhDisplayedRowReadsBackAtIndexSix() {
            TransactionListRequest request = withSelectors(rowsMarkedAt(6));

            assertThat(request.rowSelectors())
                    .containsExactly(UNMARKED, UNMARKED, UNMARKED, UNMARKED, UNMARKED, UNMARKED,
                            MARKED, UNMARKED, UNMARKED, UNMARKED);
        }

        @Test
        @DisplayName("every one of the displayed rows reads back at its own index")
        void everyRowReadsBackAtItsOwnIndex() {
            for (int marked = 0; marked < TransactionListRequest.ROW_SELECTOR_COUNT; marked++) {
                List<String> readBack = withSelectors(rowsMarkedAt(marked)).rowSelectors();

                assertThat(readBack.get(marked))
                        .as("the mark placed against displayed row %d must read back there and"
                                + " nowhere else", marked + 1)
                        .isEqualTo(MARKED);
                assertThat(readBack)
                        .as("no other slot may acquire a mark when row %d is chosen", marked + 1)
                        .filteredOn(MARKED::equals)
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("unmarked slots survive: nothing is compacted, filtered, re-indexed or sorted")
        void unmarkedSlotsSurviveUntouched() {
            List<String> submitted = everyRowUnmarked();
            submitted.set(2, MARKED);
            submitted.set(8, UNACCEPTED);

            List<String> readBack = withSelectors(submitted).rowSelectors();

            assertThat(readBack)
                    .containsExactly(UNMARKED, UNMARKED, MARKED, UNMARKED, UNMARKED, UNMARKED,
                            UNMARKED, UNMARKED, UNACCEPTED, UNMARKED)
                    .hasSize(TransactionListRequest.ROW_SELECTOR_COUNT);
            assertThat(readBack)
                    .as("a compacted sequence would have collapsed to the two non-blank entries")
                    .isNotEqualTo(List.of(MARKED, UNACCEPTED));
        }

        @Test
        @DisplayName("an entirely unmarked page is legal and reports no violation at any slot")
        void anEntirelyUnmarkedPageIsLegal() {
            assertThat(violationsOf(withSelectors(everyRowUnmarked())))
                    .as("an operator who pages without choosing a row submits exactly this")
                    .isEmpty();
        }

        @Test
        @DisplayName("two marks are both carried, because resolving them belongs to the service")
        void twoMarksAreBothCarried() {
            List<String> submitted = everyRowUnmarked();
            submitted.set(1, MARKED);
            submitted.set(5, MARKED);

            List<String> readBack = withSelectors(submitted).rowSelectors();

            assertThat(readBack.get(1)).isEqualTo(MARKED);
            assertThat(readBack.get(5)).isEqualTo(MARKED);
            assertThat(violationsOf(withSelectors(submitted)))
                    .as("two marks are not a declarative violation; the earlier row simply wins")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the selection character is carried as submitted and its case is never folded")
    class SelectionCharacterIsNeverFolded {
        @Test
        @DisplayName("the accepted character, the same character in the other case, an unaccepted "
                + "character and an unmarked slot all pass and all survive byte for byte")
        void everySubmittedCharacterPassesAndSurvivesUnfolded() {
            for (String submitted : List.of(MARKED, MARKED_OTHER_CASE, UNACCEPTED, UNMARKED)) {
                TransactionListRequest request = withSelectors(List.of(submitted));

                assertThat(violationsOf(request))
                        .as("selection character %s is not a declarative concern", submitted)
                        .isEmpty();
                assertThat(request.rowSelectors())
                        .as("selection character %s must cross unchanged", submitted)
                        .containsExactly(submitted);
            }
        }

        @Test
        @DisplayName("the other case is a distinct byte and is never promoted to the displayed case")
        void theOtherCaseIsNeverPromoted() {
            TransactionListRequest request = withSelectors(List.of(MARKED_OTHER_CASE));

            assertThat(request.rowSelectors().get(0))
                    .as("no case folding of any kind happens on this boundary")
                    .isEqualTo(MARKED_OTHER_CASE)
                    .isNotEqualTo(MARKED);
        }

        @Test
        @DisplayName("an absent sequence is a legitimate submission and violates nothing")
        void anAbsentSequenceViolatesNothing() {
            TransactionListRequest request = withSelectors(null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.rowSelectors())
                    .as("the shape of a submission that marked no row at all")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent entry inside the sequence is refused by construction, because the "
                + "wire form of an unmarked row is the empty string")
        void anAbsentEntryIsRefusedByConstruction() {
            List<String> withAbsentEntry = new ArrayList<>();
            withAbsentEntry.add(MARKED);
            withAbsentEntry.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> withSelectors(withAbsentEntry));
        }
    }

    @Nested
    @DisplayName("the transaction-identifier filter is optional and unvalidated on this boundary")
    class TransactionIdentifierFilter {
        @Test
        @DisplayName("absent, empty, full width, short and non-numeric filters all violate nothing")
        void everyFilterShapeViolatesNothing() {
            assertThat(violationsOf(filteredBy(null))).as("absent").isEmpty();
            assertThat(violationsOf(filteredBy(UNMARKED))).as("empty").isEmpty();
            assertThat(violationsOf(filteredBy(FILTER_AT_FULL_WIDTH))).as("full width").isEmpty();
            assertThat(violationsOf(filteredBy(FILTER_WITHOUT_LEADING_ZEROS))).as("short").isEmpty();
            assertThat(violationsOf(filteredBy("ABCDEFGH"))).as("non-numeric").isEmpty();
            assertThat(violationsOf(filteredBy("   "))).as("all blank").isEmpty();
        }

        @Test
        @DisplayName("a full-width filter crosses untrimmed, with its trailing spaces intact")
        void aFullWidthFilterCrossesUntrimmed() {
            TransactionListRequest request = filteredBy(FILTER_WITH_TRAILING_SPACES);

            assertThat(request.transactionIdFilter())
                    .as("legacy space padding is part of the value, because the browse compares the"
                            + " filter to a retrieved key character for character")
                    .isEqualTo(FILTER_WITH_TRAILING_SPACES)
                    .hasSize(TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("a short filter is never padded up to the map width")
        void aShortFilterIsNeverPaddedUp() {
            assertThat(filteredBy(FILTER_WITHOUT_LEADING_ZEROS).transactionIdFilter())
                    .isEqualTo(FILTER_WITHOUT_LEADING_ZEROS)
                    .hasSize(FILTER_WITHOUT_LEADING_ZEROS.length());
        }

        @Test
        @DisplayName("leading zeros survive, because a zero-prefixed key is not the unprefixed key")
        void leadingZerosSurvive() {
            assertThat(filteredBy(FILTER_AT_FULL_WIDTH).transactionIdFilter())
                    .isEqualTo(FILTER_AT_FULL_WIDTH)
                    .isNotEqualTo(FILTER_WITHOUT_LEADING_ZEROS)
                    .hasSize(TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH);
        }

        @Test
        @DisplayName("the filter crosses the wire as text, never as a number")
        void theFilterCrossesTheWireAsText() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            String json = mapper.writeValueAsString(filteredBy(FILTER_AT_FULL_WIDTH));

            assertThat(json)
                    .as("a numeric component would have written 42 and discarded fourteen zeros")
                    .contains("\"transactionIdFilter\":\"" + FILTER_AT_FULL_WIDTH + "\"");
            assertThat(mapper.readValue(json, TransactionListRequest.class).transactionIdFilter())
                    .isEqualTo(FILTER_AT_FULL_WIDTH);
        }

        @Test
        @DisplayName("one character beyond the map width is reported against the filter itself")
        void oneCharacterBeyondTheMapWidthIsReported() {
            String oneTooWide = "0".repeat(TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH + 1);

            assertThat(soleViolationPathOf(filteredBy(oneTooWide)))
                    .isEqualTo("transactionIdFilter");
        }

        private TransactionListRequest filteredBy(String filter) {
            return new TransactionListRequest(filter, null, null, KeyAction.ENTER, null, null);
        }
    }

    @Nested
    @DisplayName("the displayed page indicator is echoed rather than accepted")
    class DisplayedPageIndicator {
        @Test
        @DisplayName("it is eight characters wide, which is not the card-list screen's width")
        void itIsEightCharactersWideAndNotThree() {
            assertThat(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("inbound item PAGENUM is 8 characters at app/cpy-bms/COTRN00.CPY line 60 and"
                            + " the screen item is defined at that length at app/bms/COTRN00.bms"
                            + " line 85")
                    .isEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH)
                    .as("the card-list screen's indicator is a differently named item three"
                            + " characters wide, and the two widths are deliberately not unified")
                    .isNotEqualTo(3);
        }

        @Test
        @DisplayName("a full-width indicator crosses untrimmed and reports nothing")
        void aFullWidthIndicatorCrossesUntrimmed() {
            String padded = "3       ";
            TransactionListRequest request = indicatedBy(padded);

            assertThat(request.displayedPageNumber())
                    .isEqualTo(padded)
                    .hasSize(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("it crosses the wire as text, never as a number, and keeps its leading zeros")
        void itCrossesTheWireAsText() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json)
                    .contains("\"displayedPageNumber\":\"" + INDICATOR_AT_FULL_WIDTH + "\"");
        }

        @Test
        @DisplayName("a client-supplied indicator is discarded while a bindable sibling still binds")
        void aClientSuppliedIndicatorIsDiscarded() throws JsonProcessingException {
            TransactionListRequest bound = moduleEquivalentMapper().readValue(
                    "{\"displayedPageNumber\":\"99999999\",\"transactionIdFilter\":\""
                            + FILTER_AT_FULL_WIDTH + "\"}",
                    TransactionListRequest.class);

            assertThat(bound.displayedPageNumber())
                    .as("a submission may not substitute a page number the server did not compute")
                    .isNull();
            assertThat(bound.transactionIdFilter())
                    .as("positive control: the body did parse and a bindable sibling did arrive")
                    .isEqualTo(FILTER_AT_FULL_WIDTH);
        }

        @Test
        @DisplayName("one character beyond the map width is reported against the indicator itself")
        void oneCharacterBeyondTheMapWidthIsReported() {
            String oneTooWide = "9".repeat(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH + 1);

            assertThat(soleViolationPathOf(indicatedBy(oneTooWide)))
                    .isEqualTo("displayedPageNumber");
        }

        private TransactionListRequest indicatedBy(String indicator) {
            return new TransactionListRequest(null, indicator, null, KeyAction.ENTER, null, null);
        }
    }

    @Nested
    @DisplayName("the paging state is carried, never re-implemented")
    class PagingStateIsCarried {
        @Test
        @DisplayName("the inbound shape carries two boundary keys and a direction, and nothing else")
        void theInboundShapeCarriesTwoKeysAndADirection() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            JsonNode inbound = mapper.readTree(mapper.writeValueAsString(
                    cursor(PageMetadata.PagingDirection.FORWARD)));

            assertThat(inbound.size())
                    .as("three properties and no fourth: a client is entitled to choose where to"
                            + " resume and which way to walk, and nothing further")
                    .isEqualTo(3);
            assertThat(inbound.has("previousCursorKey")).isTrue();
            assertThat(inbound.has("nextCursorKey")).isTrue();
            assertThat(inbound.has("direction")).isTrue();
        }

        @Test
        @DisplayName("the outbound shape is wider, which is why the request accepts only the narrower "
                + "one and no count of rows or of pages ever arrives inbound")
        void theOutboundShapeIsWiderThanTheInboundOne() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            PageMetadata outbound = PageMetadata.forward(PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                    FIRST_ROW_KEY, LAST_ROW_KEY, true, false, INDICATOR_AT_FULL_WIDTH);

            JsonNode outboundTree = mapper.readTree(mapper.writeValueAsString(outbound));
            JsonNode inboundTree = mapper.readTree(mapper.writeValueAsString(
                    cursor(PageMetadata.PagingDirection.FORWARD)));

            assertThat(inboundTree.size()).isLessThan(outboundTree.size());
            assertThat(outboundTree.has("hasMorePages")).isTrue();
            assertThat(inboundTree.has("hasMorePages"))
                    .as("availability is discovered by the browse, never asserted by the caller")
                    .isFalse();
            assertThat(inboundTree.has("pageSize"))
                    .as("the row count is screen shape, so a submission may not state it")
                    .isFalse();
            assertThat(inboundTree.has("displayedPageNumber"))
                    .as("the indicator is written to the screen and never read back from it")
                    .isFalse();
        }

        @Test
        @DisplayName("an opaque boundary key survives a round trip unchanged, leading zeros included")
        void anOpaqueBoundaryKeySurvivesARoundTrip() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            TransactionListRequest roundTripped = mapper.readValue(
                    mapper.writeValueAsString(populated()), TransactionListRequest.class);

            assertThat(roundTripped.pageMetadata()).isEqualTo(
                    cursor(PageMetadata.PagingDirection.FORWARD));
            assertThat(roundTripped.pageMetadata().previousCursorKey())
                    .as("the browse repositions on the key verbatim, so a single altered byte would"
                            + " change which rows the screen lists")
                    .isEqualTo(FIRST_ROW_KEY);
            assertThat(roundTripped.pageMetadata().nextCursorKey()).isEqualTo(LAST_ROW_KEY);
        }

        @Test
        @DisplayName("forward and backward are distinguishable, and there is no third direction")
        void forwardAndBackwardAreDistinguishable() {
            assertThat(PageMetadata.PagingDirection.values())
                    .as("one constant per legacy browse verb, with no catch-all and no default")
                    .hasSize(2)
                    .containsExactly(PageMetadata.PagingDirection.FORWARD,
                            PageMetadata.PagingDirection.BACKWARD);
            assertThat(withCursor(cursor(PageMetadata.PagingDirection.FORWARD)))
                    .as("two submissions that differ only in direction are different submissions")
                    .isNotEqualTo(withCursor(cursor(PageMetadata.PagingDirection.BACKWARD)));
        }

        @Test
        @DisplayName("a backward submission is representable and nothing here imposes ascending order")
        void aBackwardSubmissionIsRepresentable() {
            List<String> asReadBackward = List.of("j", "i", "h", "g", "f", "e", "d", "c", "b", "a");
            TransactionListRequest request = new TransactionListRequest(null, null, asReadBackward,
                    KeyAction.PFK07, navigation(), cursor(PageMetadata.PagingDirection.BACKWARD));

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
            assertThat(request.rowSelectors())
                    .as("carried in the order supplied: not re-sorted, not reversed, not normalised")
                    .containsExactlyElementsOf(asReadBackward);
        }

        @Test
        @DisplayName("a direction may be absent inbound, because a first entry is not a paging action")
        void aDirectionMayBeAbsentInbound() {
            PageMetadata.PageCursorRequest firstEntry =
                    new PageMetadata.PageCursorRequest(null, null, null);
            TransactionListRequest request = withCursor(firstEntry);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.pageMetadata().direction())
                    .as("the first arrival at a list screen comes on the enter key rather than on a"
                            + " paging key, and the legacy key evaluation applies no default")
                    .isNull();
        }

        @Test
        @DisplayName("a boundary key at the widest browse width passes, and one character more is "
                + "reported through the cascade")
        void theBoundaryKeyWidthIsMeasuredThroughTheCascade() {
            String widest = "x".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH);

            assertThat(violationsOf(withCursor(new PageMetadata.PageCursorRequest(
                    widest, null, PageMetadata.PagingDirection.BACKWARD))))
                    .as("the widest key any of the three browses retains is still a valid key")
                    .isEmpty();
            assertThat(soleViolationPathOf(withCursor(new PageMetadata.PageCursorRequest(
                    widest + "x", null, PageMetadata.PagingDirection.FORWARD))))
                    .as("without the cascade this width would be declared and never evaluated, and an"
                            + " arbitrarily wide echoed key would reach a query unmeasured")
                    .isEqualTo("pageMetadata.previousCursorKey");
        }

        @Test
        @DisplayName("an absent paging state is a legitimate fresh browse")
        void anAbsentPagingStateIsALegitimateFreshBrowse() {
            TransactionListRequest request = withCursor(null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.pageMetadata()).isNull();
        }

        private TransactionListRequest withCursor(PageMetadata.PageCursorRequest paging) {
            return new TransactionListRequest(null, null, null, KeyAction.ENTER, null, paging);
        }
    }

    @Nested
    @DisplayName("the echoed navigation state is carried, never re-implemented")
    class NavigationStateIsCarried {
        @Test
        @DisplayName("it survives a round trip unchanged, leading-zero identifiers included")
        void itSurvivesARoundTripUnchanged() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            TransactionListRequest roundTripped = mapper.readValue(
                    mapper.writeValueAsString(populated()), TransactionListRequest.class);

            assertThat(roundTripped.navigationContext()).isEqualTo(navigation());
            assertThat(roundTripped.navigationContext().accountId())
                    .as("a zero-prefixed identifier is not the same key as its unprefixed form")
                    .isEqualTo(ACCOUNT_ID)
                    .hasSize(NavigationContext.ACCOUNT_ID_LENGTH);
            assertThat(roundTripped.navigationContext().customerId())
                    .isEqualTo(CUSTOMER_ID)
                    .hasSize(NavigationContext.CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("it is carried rather than interpreted: the raw type code and the screen "
                + "transition state cross exactly as supplied")
        void itIsCarriedRatherThanInterpreted() {
            NavigationContext echoed = populated().navigationContext();

            assertThat(echoed.userType())
                    .as("sign-on routes an undeclared code instead of rejecting it, so the raw byte"
                            + " must survive the round trip unchanged")
                    .isEqualTo("A");
            assertThat(echoed.programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(echoed.reEntry())
                    .as("field-level error decoration applies only on re-entry")
                    .isTrue();
            assertThat(echoed.firstEntry()).isFalse();
        }

        @Test
        @DisplayName("an over-wide value inside it is reported through the cascade, with a path that "
                + "names the nested component")
        void anOverWideNestedValueIsReported() {
            NavigationContext oneTooWide = new NavigationContext(
                    "CT00X", "COTRN00C", "CT01", "COTRN01C", "ADMINUSR", "A",
                    NavigationContext.ProgramContext.REENTER, CUSTOMER_ID, "MARY", "ANN", "SMITH",
                    ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN0A", "COTRN00");

            assertThat(soleViolationPathOf(withNavigation(oneTooWide)))
                    .isEqualTo("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("the wholly empty state is a real legacy state and violates nothing")
        void theWhollyEmptyStateViolatesNothing() {
            TransactionListRequest request = withNavigation(NavigationContext.empty());

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext().firstEntry())
                    .as("an online program entered with an empty area has no signed-on user, no"
                            + " selection and no previous screen")
                    .isTrue();
        }

        @Test
        @DisplayName("an absent navigation state is legitimate and is never replaced by an empty one")
        void anAbsentNavigationStateIsNeverSubstituted() {
            TransactionListRequest request = withNavigation(null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext())
                    .as("a synthesised empty context is still a default, and this contract"
                            + " manufactures none")
                    .isNull();
        }

        private TransactionListRequest withNavigation(NavigationContext context) {
            return new TransactionListRequest(null, null, null, KeyAction.ENTER, context, null);
        }
    }

    @Nested
    @DisplayName("the attention key is the estate's declared vocabulary and nothing more")
    class AttentionKeyVocabulary {
        @Test
        @DisplayName("sixteen actions are declared, and not one of them is a catch-all")
        void sixteenActionsAreDeclaredAndNoneIsACatchAll() {
            List<String> names = actionNames();

            assertThat(KeyAction.values())
                    .as("the sixteen condition names the estate declares for the attention key")
                    .hasSize(16);
            assertThat(names)
                    .as("the legacy key evaluation has no catch-all branch: on no match it assigns"
                            + " nothing and the caller keeps whatever action it was already holding,"
                            + " so absence is modelled as absence rather than as a synthetic constant")
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "DEFAULT");
        }

        @Test
        @DisplayName("no action is declared for the higher function keys")
        void noActionIsDeclaredForTheHigherFunctionKeys() {
            List<String> functionKeyNames = new ArrayList<>();
            for (KeyAction action : KeyAction.values()) {
                if (action.isProgramFunctionKey()) {
                    functionKeyNames.add(action.name());
                }
            }

            assertThat(functionKeyNames)
                    .containsExactlyElementsOf(PROGRAM_FUNCTION_ACTION_NAMES);
            assertThat(actionNames()).doesNotContain("PFK13", "PFK24");
        }

        @Test
        @DisplayName("every identifier is five characters and is never trimmed")
        void everyIdentifierIsFiveCharactersAndIsNeverTrimmed() {
            for (KeyAction action : KeyAction.values()) {
                assertThat(action.getAid())
                        .as("the identifier of %s is read from a fixed-width work area", action.name())
                        .hasSize(5);
            }
            assertThat(KeyAction.PA1.getAid())
                    .as("two of the identifiers carry trailing padding, and the padding is the value")
                    .isEqualTo("PA1  ")
                    .endsWith("  ");
            assertThat(KeyAction.PA2.getAid()).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("resolution never throws, never folds case and never accepts a trimmed form")
        void resolutionNeverThrowsAndNeverFoldsCase() {
            Optional<KeyAction> resolved = KeyAction.fromAid("PFK08");

            assertThat(resolved).contains(KeyAction.PFK08);
            assertThatNoException()
                    .as("an unrecognised identifier is answered with an empty result, not an error")
                    .isThrownBy(() -> KeyAction.fromAid(null));
            assertThat(KeyAction.fromAid(null)).isEmpty();
            assertThat(KeyAction.fromAid(UNMARKED)).isEmpty();
            assertThat(KeyAction.fromAid("pfk08"))
                    .as("matching is exact, so the other case resolves to nothing")
                    .isEmpty();
            assertThat(KeyAction.fromAid("PFK13"))
                    .as("the higher function keys have no constant to resolve to")
                    .isEmpty();
            assertThat(KeyAction.fromAid("PA1"))
                    .as("the trimmed form is a different value: the identifier is five characters")
                    .isEmpty();
            assertThat(KeyAction.fromAid("PA1  ")).contains(KeyAction.PA1);
        }

        @Test
        @DisplayName("the action crosses the wire as its constant name and round-trips")
        void theActionCrossesTheWireAsItsConstantName() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            String json = mapper.writeValueAsString(populated());

            assertThat(json).contains("\"keyAction\":\"PFK08\"");
            assertThat(mapper.readValue(json, TransactionListRequest.class).keyAction())
                    .isEqualTo(KeyAction.PFK08);
        }

        @Test
        @DisplayName("an absent action is carried as absent and omitted from the wire form")
        void anAbsentActionIsCarriedAsAbsent() throws JsonProcessingException {
            TransactionListRequest request = new TransactionListRequest(
                    FILTER_AT_FULL_WIDTH, null, null, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.keyAction()).isNull();
            assertThat(moduleEquivalentMapper().writeValueAsString(request))
                    .doesNotContain("keyAction");
        }

        private List<String> actionNames() {
            List<String> names = new ArrayList<>();
            for (KeyAction action : KeyAction.values()) {
                names.add(action.name());
            }
            return names;
        }
    }

    @Nested
    @DisplayName("declarative validation measures widths and decides nothing else")
    class DeclarativeValidationMeasuresWidthsOnly {
        @Test
        @DisplayName("a wholly absent submission reports nothing, so no presence rule is declared")
        void aWhollyAbsentSubmissionReportsNothing() {
            assertThat(violationsOf(new TransactionListRequest(null, null, null, null, null, null)))
                    .as("a presence rule would reject the legitimate first arrival at the screen")
                    .isEmpty();
        }

        @Test
        @DisplayName("a wholly blank submission reports nothing, so no emptiness or pattern rule is "
                + "declared either")
        void aWhollyBlankSubmissionReportsNothing() {
            TransactionListRequest allBlank = new TransactionListRequest(UNMARKED, UNMARKED,
                    everyRowUnmarked(), null,
                    new NavigationContext(UNMARKED, UNMARKED, UNMARKED, UNMARKED, UNMARKED, UNMARKED,
                            null, UNMARKED, UNMARKED, UNMARKED, UNMARKED, UNMARKED, UNMARKED,
                            UNMARKED, UNMARKED, UNMARKED),
                    new PageMetadata.PageCursorRequest(UNMARKED, UNMARKED, null));

            assertThat(violationsOf(allBlank))
                    .as("a fixed-width screen submits blanks for every field the operator left alone")
                    .isEmpty();
        }

        @Test
        @DisplayName("a non-numeric filter and an unaccepted selection character both report nothing, "
                + "because both rules are ordered service checks carrying their own texts")
        void theTwoMessageBearingRulesAreNotDeclaredHere() {
            TransactionListRequest request = new TransactionListRequest("NOT-NUMERIC", null,
                    List.of(UNACCEPTED), KeyAction.ENTER, null, null);

            assertThat(violationsOf(request))
                    .as("declarative validation reports in no defined order and reports several at"
                            + " once, so it cannot reproduce a first-match-wins cascade that must"
                            + " yield one specific text with the cursor placed on one specific item")
                    .isEmpty();
        }

        @Test
        @DisplayName("each width passes at the bound and reports exactly one violation one character "
                + "beyond it, which is the whole of what the constraints do")
        void eachWidthPassesAtTheBoundAndReportsOneCharacterBeyond() {
            int filterWidth = TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH;
            int indicatorWidth = TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH;
            int selectorWidth = TransactionListRequest.ROW_SELECTOR_LENGTH;

            assertThat(violationsOf(new TransactionListRequest("0".repeat(filterWidth),
                    "9".repeat(indicatorWidth), List.of(MARKED.repeat(selectorWidth)),
                    KeyAction.ENTER, null, null)))
                    .as("every value exactly at its map width is a value the screen can transmit")
                    .isEmpty();

            assertThat(soleViolationPathOf(new TransactionListRequest("0".repeat(filterWidth + 1),
                    null, null, KeyAction.ENTER, null, null)))
                    .isEqualTo("transactionIdFilter");
            assertThat(soleViolationPathOf(new TransactionListRequest(null,
                    "9".repeat(indicatorWidth + 1), null, KeyAction.ENTER, null, null)))
                    .isEqualTo("displayedPageNumber");
            assertThat(soleViolationPathOf(withSelectors(
                    List.of(MARKED.repeat(selectorWidth + 1)))))
                    .as("the bound applies to each entry of the sequence, not to the sequence")
                    .isEqualTo("rowSelectors[0].<list element>");
        }

        @Test
        @DisplayName("a nested width and an unaccepted character together report the width alone, so "
                + "no constraint pre-empts a service check")
        void aNestedWidthReportsAloneAndPreEmptsNothing() {
            NavigationContext oneTooWide = new NavigationContext("CT00", "COTRN00CX", "CT01",
                    "COTRN01C", "ADMINUSR", "A", NavigationContext.ProgramContext.ENTER, CUSTOMER_ID,
                    "MARY", "ANN", "SMITH", ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN0A", "COTRN00");
            TransactionListRequest request = new TransactionListRequest("NOT-NUMERIC", null,
                    List.of(UNACCEPTED), KeyAction.ENTER, oneTooWide, null);

            assertThat(soleViolationPathOf(request)).isEqualTo("navigationContext.fromProgram");
        }

        @Test
        @DisplayName("a fully populated submission reports nothing at all")
        void aFullyPopulatedSubmissionReportsNothing() {
            assertThat(violationsOf(populated())).isEmpty();
        }
    }

    @Nested
    @DisplayName("absence is tolerated, the value is immutable, and the wire form omits what is absent")
    class AbsenceToleranceImmutabilityAndWireShape {
        @Test
        @DisplayName("the selector sequence is exposed immutably, so a caller cannot alter a submission "
                + "after it has been accepted")
        void theSelectorSequenceIsExposedImmutably() {
            List<String> exposed = populated().rowSelectors();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exposed.add(MARKED));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exposed.set(0, MARKED));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exposed.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(exposed::clear);
        }

        @Test
        @DisplayName("an absent sequence becomes the empty immutable sequence")
        void anAbsentSequenceBecomesTheEmptyImmutableSequence() {
            List<String> exposed = withSelectors(null).rowSelectors();

            assertThat(exposed).isEmpty();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> exposed.add(MARKED));
        }

        @Test
        @DisplayName("the supplied sequence is copied, so later changes to the caller's list are not "
                + "visible through the request")
        void theSuppliedSequenceIsCopied() {
            List<String> callerOwned = new ArrayList<>();
            callerOwned.add(MARKED);

            TransactionListRequest request = withSelectors(callerOwned);
            callerOwned.clear();
            callerOwned.add(UNACCEPTED);

            assertThat(request.rowSelectors())
                    .as("the copy detaches the submission from caller-owned state")
                    .containsExactly(MARKED)
                    .isNotSameAs(callerOwned);
        }

        @Test
        @DisplayName("every component tolerates absence, and the sequence is the only one normalised")
        void everyComponentToleratesAbsence() {
            TransactionListRequest absent =
                    new TransactionListRequest(null, null, null, null, null, null);

            assertThat(absent.transactionIdFilter()).isNull();
            assertThat(absent.displayedPageNumber()).isNull();
            assertThat(absent.keyAction()).isNull();
            assertThat(absent.navigationContext()).isNull();
            assertThat(absent.pageMetadata()).isNull();
            assertThat(absent.rowSelectors())
                    .as("the sole normalisation in the whole contract: an absent sequence becomes the"
                            + " empty one, which is the shape of a submission that marked no row")
                    .isEmpty();
        }

        @Test
        @DisplayName("absent components are omitted from the wire form altogether")
        void absentComponentsAreOmittedFromTheWireForm() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(
                    new TransactionListRequest(null, null, null, null, null, null));

            assertThat(json)
                    .as("absent components are omitted rather than written as explicit nulls, and the"
                            + " normalised sequence is written because it is present and empty")
                    .isEqualTo("{\"rowSelectors\":[]}");
        }

        @Test
        @DisplayName("an unknown incoming property is tolerated rather than rejected")
        void anUnknownIncomingPropertyIsTolerated() throws JsonProcessingException {
            TransactionListRequest bound = moduleEquivalentMapper().readValue(
                    "{\"aPropertyThisContractDoesNotDeclare\":\"anything\",\"keyAction\":\"PFK07\"}",
                    TransactionListRequest.class);

            assertThat(bound.keyAction()).isEqualTo(KeyAction.PFK07);
            assertThat(bound.rowSelectors()).isEmpty();
        }

        @Test
        @DisplayName("the wire form carries exactly the six declared components and no seventh")
        void theWireFormCarriesExactlySixComponents() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            JsonNode tree = mapper.readTree(mapper.writeValueAsString(populated()));

            assertThat(tree.size()).isEqualTo(6);
            assertThat(tree.has("transactionIdFilter")).isTrue();
            assertThat(tree.has("displayedPageNumber")).isTrue();
            assertThat(tree.has("rowSelectors")).isTrue();
            assertThat(tree.has("keyAction")).isTrue();
            assertThat(tree.has("navigationContext")).isTrue();
            assertThat(tree.has("pageMetadata")).isTrue();
        }

        @Test
        @DisplayName("no screen furniture and no monetary value rides on the request")
        void noScreenFurnitureOrMonetaryValueRidesOnTheRequest() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json).doesNotContain("errorMessage", "transactionName", "screenTitle",
                    "currentDate", "currentTime", "programName", "transactionAmount");
        }
    }

    @Nested
    @DisplayName("value semantics and the diagnostic rendering")
    class ValueSemanticsAndRendering {
        @Test
        @DisplayName("two identical submissions are equal and agree on their hash code")
        void twoIdenticalSubmissionsAreEqual() {
            assertThat(populated())
                    .isEqualTo(populated())
                    .hasSameHashCodeAs(populated());
        }

        @Test
        @DisplayName("a difference in any one of the six components is visible to equality")
        void aDifferenceInAnyComponentIsVisibleToEquality() {
            TransactionListRequest base = populated();

            assertThat(base).isNotEqualTo(new TransactionListRequest(FILTER_WITHOUT_LEADING_ZEROS,
                    INDICATOR_AT_FULL_WIDTH, rowsMarkedAt(6), KeyAction.PFK08, navigation(),
                    cursor(PageMetadata.PagingDirection.FORWARD)));
            assertThat(base).isNotEqualTo(new TransactionListRequest(FILTER_AT_FULL_WIDTH,
                    "00000004", rowsMarkedAt(6), KeyAction.PFK08, navigation(),
                    cursor(PageMetadata.PagingDirection.FORWARD)));
            assertThat(base).isNotEqualTo(new TransactionListRequest(FILTER_AT_FULL_WIDTH,
                    INDICATOR_AT_FULL_WIDTH, rowsMarkedAt(7), KeyAction.PFK08, navigation(),
                    cursor(PageMetadata.PagingDirection.FORWARD)));
            assertThat(base).isNotEqualTo(new TransactionListRequest(FILTER_AT_FULL_WIDTH,
                    INDICATOR_AT_FULL_WIDTH, rowsMarkedAt(6), KeyAction.PFK07, navigation(),
                    cursor(PageMetadata.PagingDirection.FORWARD)));
            assertThat(base).isNotEqualTo(new TransactionListRequest(FILTER_AT_FULL_WIDTH,
                    INDICATOR_AT_FULL_WIDTH, rowsMarkedAt(6), KeyAction.PFK08,
                    NavigationContext.empty(), cursor(PageMetadata.PagingDirection.FORWARD)));
            assertThat(base).isNotEqualTo(new TransactionListRequest(FILTER_AT_FULL_WIDTH,
                    INDICATOR_AT_FULL_WIDTH, rowsMarkedAt(6), KeyAction.PFK08, navigation(),
                    cursor(PageMetadata.PagingDirection.BACKWARD)));
            assertThat(base)
                    .as("a mark at a different row is a different submission, which is exactly why the"
                            + " sequence may never be compacted")
                    .isNotEqualTo("not a submission at all");
        }

        @Test
        @DisplayName("the rendering withholds the filter and withholds the paging block whole rather "
                + "than delegating to it")
        void theRenderingWithholdsTheFilterAndThePagingBlockWhole() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .as("the filter names one specific movement of money on one specific card, and"
                            + " each boundary key is a record key of the same kind")
                    .doesNotContain(FILTER_AT_FULL_WIDTH, FIRST_ROW_KEY, LAST_ROW_KEY);
            assertThat(rendered).contains("transactionIdFilter=" + WITHHELD);
            assertThat(rendered)
                    .as("withheld whole, so this type's safety is not a property of another type's"
                            + " rendering staying safe")
                    .contains("pageMetadata=" + WITHHELD)
                    .doesNotContain("PageCursorRequest[");
        }

        @Test
        @DisplayName("the rendering keeps the interaction state and prints the navigation state by "
                + "delegation, so that state's own redactions still apply")
        void theRenderingKeepsTheInteractionStateAndDelegates() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .as("which page, which row was marked and which direction was asked for is"
                            + " exactly what a diagnostic on this browse needs")
                    .contains("displayedPageNumber=" + INDICATOR_AT_FULL_WIDTH)
                    .contains("keyAction=PFK08")
                    .contains(MARKED);
            assertThat(rendered)
                    .as("printed by delegation, because that state redacts its own identifiers")
                    .contains("NavigationContext[")
                    .doesNotContain(CARD_NUMBER, ACCOUNT_ID, CUSTOMER_ID);
        }

        @Test
        @DisplayName("withholding is confined to the rendering: every accessor returns its component "
                + "exactly as supplied")
        void withholdingIsConfinedToTheRendering() {
            TransactionListRequest request = populated();

            assertThat(request.transactionIdFilter()).isEqualTo(FILTER_AT_FULL_WIDTH);
            assertThat(request.displayedPageNumber()).isEqualTo(INDICATOR_AT_FULL_WIDTH);
            assertThat(request.rowSelectors()).containsExactlyElementsOf(rowsMarkedAt(6));
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(request.navigationContext()).isEqualTo(navigation());
            assertThat(request.pageMetadata())
                    .isEqualTo(cursor(PageMetadata.PagingDirection.FORWARD));
            assertThat(request.pageMetadata().previousCursorKey()).isEqualTo(FIRST_ROW_KEY);
        }
    }
}
