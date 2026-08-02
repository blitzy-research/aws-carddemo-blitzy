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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link CardListResponse}, the response body of legacy transaction {@code CCLI}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>Three properties dominate what is checked.
 *
 * <p>The first is screen geometry. The card-list screen has seven row slots, and the number is a
 * property of the mapset rather than a setting. A response was previously able to carry any number of
 * rows, which would have described a screen that does not exist and left the page size unbounded. The
 * bound is an <em>upper</em> one: a short page stays short, because the legacy program leaves unfilled
 * slots unfilled rather than padding the list, and the tests below hold that distinction.
 *
 * <p>The second is positional agreement. The selection indicator is built only on the rejection path,
 * so its ordinary state is absent; but when it exists it is read position for position against the
 * rows, and a length disagreement would attribute an error to the wrong row or to a row the page does
 * not contain.
 *
 * <p>The third is disclosure. A page holds up to seven rows and each row carries a card number beside
 * the account it belongs to, so the generated rendering of one response was the densest disclosure any
 * type in this package could produce.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("CardListResponse :: card-list response contract of legacy transaction CCLI")
class CardListResponseTest {

    /** The eighteen record components in the order the symbolic map declares their fields. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "displayedPageNumber", "accountFilter", "cardNumberFilter", "rows",
            "selectionErrorFlags", "infoMessage", "errorMessage", "generalError", "pageMetadata",
            "focusScreenFieldId", "nextRoute", "navigationContext");

    private static final String ACCOUNT_FILTER = "00000000011";
    private static final String CARD_FILTER = "4111111111111111";
    private static final String ROW_ACCOUNT = "00000000022";
    private static final String ROW_CARD = "4222222222222222";
    private static final String PREVIOUS_KEY = "422222222222222200000000022";
    private static final String NEXT_KEY = "433333333333333300000000033";
    private static final String REDACTED = "***REDACTED***";

    private static CardListResponse.CardListRow row(int ordinal) {
        return new CardListResponse.CardListRow("S", "0000000002" + ordinal,
                "422222222222222" + ordinal, "Y");
    }

    private static List<CardListResponse.CardListRow> rows(int count) {
        List<CardListResponse.CardListRow> built = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            built.add(row(ordinal));
        }
        return built;
    }

    private static List<Boolean> flags(int count) {
        List<Boolean> built = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            built.add(position == 0);
        }
        return built;
    }

    private static NavigationContext navigation() {
        return new NavigationContext("CCLI", "COCRDLIC", "CCLI", "COCRDLIC", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_FILTER, "Y", CARD_FILTER, "CCRDLIA", "COCRDLI");
    }

    private static PageMetadata paging() {
        return PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE, PREVIOUS_KEY, NEXT_KEY, true,
                false, "001");
    }

    private static CardListResponse response(List<CardListResponse.CardListRow> rows,
            List<Boolean> selectionErrorFlags) {
        return new CardListResponse("CCLI", "AWS Mainframe Modernization", "08/02/26", "COCRDLIC",
                "CardDemo", "14:30:00", "001", ACCOUNT_FILTER, CARD_FILTER, rows,
                selectionErrorFlags, CardListResponse.MSG_ROW_ACTION_PROMPT, null, false, paging(),
                "CRDSID", "/api/cards", navigation());
    }

    private static CardListResponse populated() {
        return response(rows(PageMetadata.CARD_LIST_PAGE_SIZE), List.of());
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

    private static JsonNode payloadOf(CardListResponse response) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Returns the part of a rendering this record produced itself, excluding the delegated navigation
     * state.
     *
     * <p>Necessary because the nested navigation contract names some of the same components and prints
     * values of its own, so an assertion over the whole string would not be about this type.
     *
     * @param rendered a full rendering
     * @return the leading segment this record contributed
     */
    private static String ownRendering(String rendered) {
        int delegated = rendered.indexOf(", navigationContext=");
        return (delegated < 0) ? rendered : rendered.substring(0, delegated);
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the eighteen components in map order")
        void declaresEighteenComponentsInMapOrder() {
            assertThat(Arrays.stream(CardListResponse.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER)
                    .hasSize(18);
        }

        @Test
        @DisplayName("uses the package's canonical names throughout")
        void usesCanonicalNames() {
            List<String> names = Arrays.stream(CardListResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(names).contains("title01", "title02", "nextRoute", "focusScreenFieldId",
                    "displayedPageNumber", "pageMetadata");
            assertThat(names).doesNotContain("screenTitleLine1", "screenTitleLine2", "route",
                    "focusField", "pageIndicator", "page", "pageNumber");
        }

        @Test
        @DisplayName("declares the row and indicator lists at their canonical element types")
        void declaresRowAndIndicatorLists() throws NoSuchFieldException {
            assertThat(CardListResponse.class.getDeclaredField("rows").getType())
                    .isEqualTo(List.class);
            assertThat(CardListResponse.class.getDeclaredField("selectionErrorFlags").getType())
                    .isEqualTo(List.class);
            assertThat(Arrays.stream(CardListResponse.CardListRow.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactly("selection", "accountNumber", "cardNumber", "cardStatus");
        }

        @Test
        @DisplayName("states the renamed widths at their measured map values")
        void statesRenamedWidths() {
            assertThat(CardListResponse.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("PAGENO is X(3) on the card-list mapset")
                    .isEqualTo(3);
            assertThat(CardListResponse.SCREEN_FIELD_ID_LENGTH)
                    .as("a BMS field identifier is at most seven characters")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("retains no constant naming the superseded spellings")
        void retainsNoSupersededConstant() {
            assertThat(Arrays.stream(CardListResponse.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName))
                    .doesNotContain("PAGE_INDICATOR_LENGTH", "FOCUS_FIELD_LENGTH");
        }
    }

    @Nested
    @DisplayName("screen geometry is enforced as an upper bound")
    class ScreenGeometryIsEnforced {

        @Test
        @DisplayName("accepts a full page of exactly seven rows")
        void acceptsAFullPage() {
            assertThatNoException()
                    .isThrownBy(() -> response(rows(PageMetadata.CARD_LIST_PAGE_SIZE), List.of()));
        }

        @Test
        @DisplayName("rejects an eighth row, naming the count and the reason")
        void rejectsAnEighthRow() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response(rows(PageMetadata.CARD_LIST_PAGE_SIZE + 1),
                            List.of()))
                    .withMessage("rows may hold at most 7 entries, because that is how many row"
                            + " slots the card-list screen has, but it holds 8");
        }

        @Test
        @DisplayName("accepts a short page unchanged rather than padding it")
        void acceptsAShortPageUnchanged() {
            CardListResponse shortPage = response(rows(3), List.of());

            assertThat(shortPage.rows())
                    .as("a short page stays short: unfilled slots stay unfilled")
                    .hasSize(3);
        }

        @Test
        @DisplayName("accepts an absent row list as an empty page")
        void acceptsAnAbsentRowList() {
            CardListResponse empty = response(null, null);

            assertThat(empty.rows()).isEmpty();
            assertThat(empty.selectionErrorFlags()).isEmpty();
        }

        @Test
        @DisplayName("states the row count once, on the shared paging contract")
        void statesTheRowCountOnce() {
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE).isEqualTo(7);
            assertThat(Arrays.stream(CardListResponse.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName))
                    .as("this contract adds no second statement of the same fact")
                    .doesNotContain("PAGE_SIZE", "ROW_COUNT", "CARD_LIST_PAGE_SIZE");
        }
    }

    @Nested
    @DisplayName("the positional indicator must agree with the rows")
    class PositionalIndicatorMustAgree {

        @Test
        @DisplayName("accepts an absent indicator, which is the ordinary case")
        void acceptsAnAbsentIndicator() {
            assertThatNoException().isThrownBy(() -> response(rows(4), List.of()));
        }

        @Test
        @DisplayName("accepts an indicator holding exactly one entry per row")
        void acceptsAnAlignedIndicator() {
            CardListResponse aligned = response(rows(4), flags(4));

            assertThat(aligned.selectionErrorFlags()).containsExactly(true, false, false, false);
        }

        @Test
        @DisplayName("rejects an indicator shorter than the rows, naming both counts")
        void rejectsAShortIndicator() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response(rows(4), flags(3)))
                    .withMessage("selectionErrorFlags must be empty or hold exactly one entry per"
                            + " row, because the indicator is positional, but it holds 3 entries"
                            + " for 4 rows");
        }

        @Test
        @DisplayName("rejects an indicator longer than the rows")
        void rejectsALongIndicator() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response(rows(2), flags(5)))
                    .withMessageContaining("5 entries for 2 rows");
        }

        @Test
        @DisplayName("rejects an indicator on an empty page")
        void rejectsAnIndicatorOnAnEmptyPage() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response(List.of(), flags(1)))
                    .withMessageContaining("1 entries for 0 rows");
        }
    }

    @Nested
    @DisplayName("both lists are defensively copied")
    class BothListsAreDefensivelyCopied {

        @Test
        @DisplayName("a later mutation of the caller's row list does not reach the response")
        void rowListIsCopied() {
            List<CardListResponse.CardListRow> mutable = new ArrayList<>(rows(2));
            CardListResponse response = response(mutable, List.of());

            mutable.add(row(3));

            assertThat(response.rows()).hasSize(2);
        }

        @Test
        @DisplayName("a later mutation of the caller's indicator does not reach the response")
        void indicatorIsCopied() {
            List<Boolean> mutable = new ArrayList<>(flags(2));
            CardListResponse response = response(rows(2), mutable);

            mutable.set(0, false);

            assertThat(response.selectionErrorFlags()).containsExactly(true, false);
        }

        @Test
        @DisplayName("neither retained list can be modified through its accessor")
        void neitherListIsModifiable() {
            CardListResponse response = response(rows(2), flags(2));

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.rows().add(row(3)));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.selectionErrorFlags().add(true));
        }
    }

    @Nested
    @DisplayName("diagnostic rendering of the response")
    class DiagnosticRenderingOfTheResponse {

        @Test
        @DisplayName("withholds both filters, the whole row list and the whole paging component")
        void withholdsEveryRegulatedGrouping() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).doesNotContain(ACCOUNT_FILTER, CARD_FILTER, ROW_CARD, PREVIOUS_KEY,
                    NEXT_KEY);
            assertThat(rendered).contains("accountFilter=" + REDACTED,
                    "cardNumberFilter=" + REDACTED, "rows=" + REDACTED,
                    "pageMetadata=" + REDACTED);
        }

        @Test
        @DisplayName("reports the row count, which is the useful non-identifying fact")
        void reportsTheRowCount() {
            assertThat(ownRendering(populated().toString())).contains("rowCount=7");
            assertThat(ownRendering(response(rows(2), List.of()).toString())).contains("rowCount=2");
        }

        @Test
        @DisplayName("retains the screen furniture and the positional indicator")
        void retainsScreenFurniture() {
            String rendered = ownRendering(response(rows(2), flags(2)).toString());

            assertThat(rendered).contains("transactionName=CCLI", "programName=COCRDLIC",
                    "displayedPageNumber=001", "generalError=false",
                    "focusScreenFieldId=CRDSID", "nextRoute=/api/cards",
                    "selectionErrorFlags=[true, false]");
            assertThat(populated().toString()).startsWith("CardListResponse[").endsWith("]");
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            CardListResponse response = populated();
            response.toString();

            assertThat(response.accountFilter()).isEqualTo(ACCOUNT_FILTER);
            assertThat(response.cardNumberFilter()).isEqualTo(CARD_FILTER);
            assertThat(response.pageMetadata()).isEqualTo(paging());
            assertThat(response.rows()).hasSize(PageMetadata.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("changes nothing on the wire")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("accountFilter").asText()).isEqualTo(ACCOUNT_FILTER);
            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo(CARD_FILTER);
            assertThat(payload.get("rows")).hasSize(PageMetadata.CARD_LIST_PAGE_SIZE);
            assertThat(payload.get("pageMetadata").get("nextCursorKey").asText())
                    .isEqualTo(NEXT_KEY);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("publishes the canonical names on the wire")
        void publishesCanonicalNamesOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.has("title01")).isTrue();
            assertThat(payload.has("title02")).isTrue();
            assertThat(payload.has("nextRoute")).isTrue();
            assertThat(payload.has("focusScreenFieldId")).isTrue();
            assertThat(payload.has("displayedPageNumber")).isTrue();
            assertThat(payload.has("route")).isFalse();
            assertThat(payload.has("focusField")).isFalse();
            assertThat(payload.has("pageIndicator")).isFalse();
            assertThat(payload.has("screenTitleLine1")).isFalse();
        }
    }

    @Nested
    @DisplayName("diagnostic rendering of one row")
    class DiagnosticRenderingOfOneRow {

        @Test
        @DisplayName("withholds both identifiers and retains the action and status codes")
        void withholdsBothIdentifiers() {
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("U", ROW_ACCOUNT, ROW_CARD, "N");

            String rendered = subject.toString();

            assertThat(rendered).doesNotContain(ROW_ACCOUNT, ROW_CARD);
            assertThat(rendered).isEqualTo("CardListRow[selection=U, accountNumber=" + REDACTED
                    + ", cardNumber=" + REDACTED + ", cardStatus=N]");
        }

        @Test
        @DisplayName("changes nothing a row accessor returns")
        void changesNothingTransported() {
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("U", ROW_ACCOUNT, ROW_CARD, "N");
            subject.toString();

            assertThat(subject.accountNumber()).isEqualTo(ROW_ACCOUNT);
            assertThat(subject.cardNumber()).isEqualTo(ROW_CARD);
        }

        @Test
        @DisplayName("changes nothing a row puts on the wire")
        void changesNothingOnTheWire() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();
            CardListResponse.CardListRow subject =
                    new CardListResponse.CardListRow("U", ROW_ACCOUNT, ROW_CARD, "N");

            JsonNode payload = mapper.readTree(mapper.writeValueAsString(subject));

            assertThat(payload.get("accountNumber").asText()).isEqualTo(ROW_ACCOUNT);
            assertThat(payload.get("cardNumber").asText()).isEqualTo(ROW_CARD);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }

        @Test
        @DisplayName("a whole page of rows discloses no card number through the response rendering")
        void aWholePageDisclosesNothing() {
            String rendered = ownRendering(populated().toString());

            for (CardListResponse.CardListRow subject : populated().rows()) {
                assertThat(rendered).doesNotContain(subject.cardNumber(), subject.accountNumber());
            }
        }
    }

    @Nested
    @DisplayName("operator diagnostics reproduce the legacy text")
    class OperatorDiagnosticsReproduceLegacyText {

        @Test
        @DisplayName("carries the row-action prompt exactly as the program builds it")
        void carriesTheRowActionPrompt() {
            assertThat(CardListResponse.MSG_ROW_ACTION_PROMPT)
                    .isEqualTo("TYPE S FOR DETAIL, U TO UPDATE ANY RECORD");
        }

        @Test
        @DisplayName("carries the paging boundary messages exactly")
        void carriesThePagingBoundaryMessages() {
            assertThat(CardListResponse.MSG_NO_PREVIOUS_PAGES)
                    .isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
            assertThat(CardListResponse.MSG_NO_MORE_PAGES).isEqualTo("NO MORE PAGES TO DISPLAY");
            assertThat(CardListResponse.MSG_NO_MORE_RECORDS).isEqualTo("NO MORE RECORDS TO SHOW");
        }

        @Test
        @DisplayName("carries the invalid-action message exactly")
        void carriesTheInvalidActionMessage() {
            assertThat(CardListResponse.MSG_INVALID_ACTION_CODE).isEqualTo("INVALID ACTION CODE");
        }

        @Test
        @DisplayName("keeps every diagnostic within the error-message field width")
        void keepsEveryDiagnosticWithinFieldWidth() {
            List<String> diagnostics = List.of(CardListResponse.MSG_ACCOUNT_FILTER_INVALID,
                    CardListResponse.MSG_CARD_FILTER_INVALID,
                    CardListResponse.MSG_INVALID_ACTION_CODE,
                    CardListResponse.MSG_MORE_THAN_ONE_ACTION,
                    CardListResponse.MSG_NO_PREVIOUS_PAGES, CardListResponse.MSG_NO_MORE_PAGES,
                    CardListResponse.MSG_NO_MORE_RECORDS, CardListResponse.MSG_NO_RECORDS_FOUND);

            assertThat(diagnostics)
                    .allSatisfy(text -> assertThat(text.length())
                            .isLessThanOrEqualTo(CardListResponse.ERROR_MESSAGE_LENGTH));
        }
    }
}
