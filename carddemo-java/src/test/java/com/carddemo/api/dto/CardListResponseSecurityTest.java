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

import com.carddemo.api.dto.CardListResponse.CardListRow;
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
 * Verifies that neither {@link CardListResponse} nor its nested {@link CardListRow} can disclose a
 * regulated value through its stringified form, while both still carry every one of them on the wire.
 *
 * <p>A paginated response is the worst case for accidental disclosure, because one interpolation
 * discloses a whole page rather than a single record: seven rows, each with an account number and a
 * full sixteen-character card number, plus the operator's two search filters and the browse cursors
 * that carry the card number a page starts at.
 *
 * <p>The central property asserted here is <strong>non-delegation</strong>. The outer rendering
 * withholds the row list and the paging block outright rather than rendering them, so the outer type's
 * safety is not a consequence of the nested types' renderings being correct. That distinction is not
 * pedantic: if the outer delegated, then a future edit to {@code CardListRow.toString()} - a different
 * file, reviewable without ever opening this one - would silently widen what the outer response
 * discloses. The tests below assert both that the nested renderings are safe in their own right and
 * that the outer does not rely on them, by checking the outer rendering contains no nested type's
 * rendering at all.
 */
@DisplayName("CardListResponse - diagnostic rendering safety for legacy transaction CCLI")
class CardListResponseSecurityTest {

    private static final String PLACEHOLDER = "***REDACTED***";

    private static final String ACCOUNT_FILTER = "78412590063";
    private static final String CARD_NUMBER_FILTER = "4532015112830366";
    private static final String ROW_ACCOUNT_NUMBER = "63925871407";
    private static final String ROW_CARD_NUMBER = "5500005555555559";

    /**
     * Cursor keys that deliberately embed regulated values.
     *
     * <p>A browse cursor over this screen is built from the card number the page starts at, so an
     * adversarially constructed cursor is the right fixture: if the outer rendering ever delegated to
     * {@link PageMetadata}, or if that type stopped withholding its cursors, the fragment scan below
     * would find the embedded card number and fail. A neutral cursor value would let both defects pass.
     */
    private static final String PREVIOUS_CURSOR = "PRV5500005555555559";

    private static final String NEXT_CURSOR = "NXT4532015112830366";

    /** The four components the outer renderer withholds, in declaration order. */
    private static final List<String> WITHHELD_OUTER_COMPONENTS =
            List.of("accountFilter", "cardNumberFilter", "rows", "pageMetadata");

    /** The two components the row renderer withholds, in declaration order. */
    private static final List<String> WITHHELD_ROW_COMPONENTS =
            List.of("accountNumber", "cardNumber");

    private static final List<String> REGULATED_VALUES = List.of(
            ACCOUNT_FILTER, CARD_NUMBER_FILTER, ROW_ACCOUNT_NUMBER, ROW_CARD_NUMBER,
            PREVIOUS_CURSOR, NEXT_CURSOR);

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

    private static CardListRow row() {
        return new CardListRow(1,"S", ROW_ACCOUNT_NUMBER, ROW_CARD_NUMBER, "Y");
    }

    private static PageMetadata page() {
        return PageMetadata.forward(
                PageMetadata.CARD_LIST_PAGE_SIZE, PREVIOUS_CURSOR, NEXT_CURSOR, true, false, "001");
    }

    private static CardListResponse populated() {
        return populatedWith(null);
    }

    private static CardListResponse populatedWith(NavigationContext navigation) {
        return new CardListResponse(
                "CCLI", "TITLE ONE", "07/19/22", "COCRDLIC", "TITLE TWO", "14:23:07",
                "001", ACCOUNT_FILTER, CARD_NUMBER_FILTER,
                // One flag per row and never a flag more: the indicator is positional, so a third
                // flag beside two rows would name a row that is not on the page.
                List.of(row(), row()), List.of(false, true),
                "INFORMATION LINE", "ERROR LINE", true, page(), false, List.of(), "CARDSID", "route/next", navigation);
    }

    private static CardListResponse empty() {
        return new CardListResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null, false,
                null, false, List.of(), null, null, null);
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

    private static JsonNode payloadOf(CardListResponse response) throws Exception {
        return moduleEquivalentMapper().readTree(moduleEquivalentMapper().writeValueAsString(response));
    }

    @Nested
    @DisplayName("The outer rendering discloses nothing, from any of its components")
    class TheOuterRenderingDisclosesNothing {

        @Test
        @DisplayName("every regulated value is absent from the outer rendering in whole, including the "
                + "row values and the cursor keys reached only through nested components")
        void everyRegulatedValueIsAbsentInWhole() {
            assertThat(populated().toString()).doesNotContain(REGULATED_VALUES);
        }

        @Test
        @DisplayName("no six-character run of any regulated value survives the outer rendering")
        void noSixCharacterRunSurvivesTheOuterRendering() {
            String rendered = populated().toString();
            for (String value : REGULATED_VALUES) {
                for (int start = 0; start + 6 <= value.length(); start++) {
                    String fragment = value.substring(start, start + 6);
                    assertThat(rendered)
                            .withFailMessage("fragment %s of %s must not appear in %s",
                                    fragment, value, rendered)
                            .doesNotContain(fragment);
                }
            }
        }

        @Test
        @DisplayName("each of the four withheld components renders as the fixed stand-in, exactly once "
                + "each")
        void eachWithheldComponentRendersAsTheFixedStandIn() {
            String rendered = populated().toString();
            WITHHELD_OUTER_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
            assertThat(occurrencesOf(rendered, PLACEHOLDER))
                    .isEqualTo(WITHHELD_OUTER_COMPONENTS.size());
        }

        @Test
        @DisplayName("neither the issuer prefix nor the trailing digits of either card number appear")
        void neitherIssuerPrefixNorTrailingDigitsAppear() {
            String rendered = populated().toString();
            for (String pan : List.of(CARD_NUMBER_FILTER, ROW_CARD_NUMBER)) {
                assertThat(rendered)
                        .doesNotContain(pan.substring(0, 4))
                        .doesNotContain(pan.substring(pan.length() - 4));
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
                    .doesNotContain("CardListRow[")
                    .contains("rows=" + PLACEHOLDER);
        }

        @Test
        @DisplayName("the outer rendering contains no paging rendering at all, so it does not depend on "
                + "the paging type continuing to withhold its cursors")
        void theOuterRenderingContainsNoPagingRendering() {
            assertThat(populated().toString())
                    .doesNotContain("PageMetadata[")
                    .contains("pageMetadata=" + PLACEHOLDER);
        }

        @Test
        @DisplayName("no row content is recoverable: a page of wholly different cards of the same "
                + "cardinality renders identically, while the cardinality itself is retained on purpose "
                + "because a count names no cardholder and a paging defect is diagnosed by it")
        void noRowContentIsRecoverable() {
            CardListRow other = new CardListRow(1,"U", "00000000099", "4111111111111111", "N");
            CardListResponse differentCards = new CardListResponse(
                    "CCLI", "TITLE ONE", "07/19/22", "COCRDLIC", "TITLE TWO", "14:23:07",
                    "001", "00000000099", "4111111111111111",
                    List.of(other, other), List.of(false, true),
                    "INFORMATION LINE", "ERROR LINE", true, page(), false, List.of(), "CARDSID", "route/next", null);

            assertThat(differentCards.toString()).isEqualTo(populated().toString());
            assertThat(populated().toString()).contains("rowCount=2");
        }
    }

    @Nested
    @DisplayName("The nested row is safe on its own terms")
    class TheNestedRowIsSafeOnItsOwnTerms {

        @Test
        @DisplayName("a row logged directly withholds both regulated components and shows the selector "
                + "and status code")
        void aRowLoggedDirectlyWithholdsBothRegulatedComponents() {
            String rendered = row().toString();
            assertThat(rendered)
                    .startsWith("CardListRow[")
                    .endsWith("]")
                    .doesNotContain(ROW_ACCOUNT_NUMBER)
                    .doesNotContain(ROW_CARD_NUMBER)
                    .contains("selection=S")
                    .contains("cardStatus=Y");
            WITHHELD_ROW_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
            assertThat(occurrencesOf(rendered, PLACEHOLDER))
                    .isEqualTo(WITHHELD_ROW_COMPONENTS.size());
        }

        @Test
        @DisplayName("no six-character run of either row value survives the row's own rendering")
        void noSixCharacterRunSurvivesTheRowRendering() {
            String rendered = row().toString();
            for (String value : List.of(ROW_ACCOUNT_NUMBER, ROW_CARD_NUMBER)) {
                for (int start = 0; start + 6 <= value.length(); start++) {
                    assertThat(rendered).doesNotContain(value.substring(start, start + 6));
                }
            }
        }

        @Test
        @DisplayName("a collection of rows printed through the collection's own rendering is safe, "
                + "because each element renders through the row override")
        void aCollectionOfRowsIsSafe() {
            String rendered = List.of(row(), row()).toString();
            assertThat(rendered)
                    .doesNotContain(ROW_ACCOUNT_NUMBER)
                    .doesNotContain(ROW_CARD_NUMBER);
            assertThat(occurrencesOf(rendered, PLACEHOLDER)).isEqualTo(4);
        }

        @Test
        @DisplayName("a row renders identically whether its regulated components held values or nothing")
        void aRowRendersIdenticallyWhetherPresentOrAbsent() {
            CardListRow absent = new CardListRow(1,"S", null, null, "Y");
            assertThat(absent.toString()).isEqualTo(row().toString());
        }
    }

    @Nested
    @DisplayName("The retained components stay visible, so the rendering remains diagnostic")
    class TheRetainedComponentsStayVisible {

        @Test
        @DisplayName("the displayed page number, the row count, the positional selection-error flags, "
                + "both message lines, the error flag, the focus field and the route are shown as "
                + "supplied")
        void theDiagnosticallyUsefulComponentsAreShown() {
            assertThat(populated().toString())
                    .startsWith("CardListResponse[")
                    .contains("transactionName=CCLI")
                    .contains("programName=COCRDLIC")
                    .contains("displayedPageNumber=001")
                    .contains("rowCount=2")
                    .contains("selectionErrorFlags=[false, true]")
                    .contains("infoMessage=INFORMATION LINE")
                    .contains("errorMessage=ERROR LINE")
                    .contains("generalError=true")
                    .contains("focusScreenFieldId=CARDSID")
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
                    "CM00", "COMEN01C", "CCLI", "COCRDLIC", "USER0001", "U",
                    NavigationContext.ProgramContext.REENTER,
                    "123456789", "MARY", "ANN", "OSULLIVAN",
                    ACCOUNT_FILTER, "Y", ROW_CARD_NUMBER, "COCRDLA", "COCRDLI");

            String rendered = populatedWith(navigation).toString();
            assertThat(rendered)
                    .doesNotContain("123456789")
                    .doesNotContain(ACCOUNT_FILTER)
                    .doesNotContain(ROW_CARD_NUMBER)
                    .doesNotContain("OSULLIVAN");
            assertThat(rendered).contains("NavigationContext[");
        }
    }

    @Nested
    @DisplayName("An entirely absent response renders safely")
    class AnEntirelyAbsentResponseRendersSafely {

        @Test
        @DisplayName("a response with every component absent renders without throwing and carries no "
                + "regulated value, with the empty row list still withheld")
        void anAbsentResponseRendersWithoutThrowing() {
            String rendered = empty().toString();
            assertThat(rendered)
                    .startsWith("CardListResponse[")
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
        @DisplayName("both filters serialise at their full untouched values")
        void bothFiltersSerialiseInFull() throws Exception {
            JsonNode payload = payloadOf(populated());
            assertThat(payload.get("accountFilter").asText()).isEqualTo(ACCOUNT_FILTER);
            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo(CARD_NUMBER_FILTER);
        }

        @Test
        @DisplayName("every row serialises its account number and its full sixteen-character card "
                + "number, so withholding from a log does not withhold from the client")
        void everyRowSerialisesItsRegulatedValuesInFull() throws Exception {
            JsonNode rows = payloadOf(populated()).get("rows");
            assertThat(rows).hasSize(2);
            rows.forEach(node -> {
                assertThat(node.get("accountNumber").asText()).isEqualTo(ROW_ACCOUNT_NUMBER);
                assertThat(node.get("cardNumber").asText()).isEqualTo(ROW_CARD_NUMBER);
                assertThat(node.get("cardNumber").asText()).hasSize(16);
            });
        }

        @Test
        @DisplayName("the paging block serialises both cursor keys, which the rendering withholds")
        void thePagingBlockSerialisesBothCursorKeys() throws Exception {
            JsonNode paging = payloadOf(populated()).get("pageMetadata");
            assertThat(paging.get("previousCursorKey").asText()).isEqualTo(PREVIOUS_CURSOR);
            assertThat(paging.get("nextCursorKey").asText()).isEqualTo(NEXT_CURSOR);
        }

        @Test
        @DisplayName("the stand-in never leaks into the serialized form, so no client ever receives a "
                + "redacted value in place of a real one")
        void theStandInNeverReachesTheWire() throws Exception {
            assertThat(payloadOf(populated()).toString()).doesNotContain("REDACTED");
        }
    }
}
