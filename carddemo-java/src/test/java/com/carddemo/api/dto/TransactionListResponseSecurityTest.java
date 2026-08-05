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

import com.carddemo.api.dto.TransactionListResponse.TransactionRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that neither {@link TransactionListResponse} nor its nested {@link TransactionRow} can
 * disclose a regulated value through its stringified form, while both still carry every one of them on
 * the wire.
 *
 * <p>A page of this screen is ten transactions, each with a durable identifier, a description and an
 * amount. One accidental interpolation would disclose a cardholder's recent spending in a single line,
 * which is a materially different disclosure from a single record: a list of amounts and descriptions is
 * a behavioural profile rather than one fact.
 *
 * <p>The filter is withheld on two independent counts, and both matter. It is a key to a stored record,
 * and it is a value the caller chose - so echoing a rejected filter into a log would let a caller place
 * text of their own choosing there, which is the log-forging concern the same review raises against the
 * service and batch layers.
 *
 * <p>As on the sibling list contracts, the central structural property is non-delegation: the outer
 * rendering withholds the row list and the paging block outright, so the outer type's safety is not a
 * consequence of the nested types' renderings being correct. The paging fixture deliberately embeds a
 * transaction identifier in its cursor keys, because a browse cursor over this screen is built from the
 * identifier the page starts at - so a delegation defect or a regression in the paging type's own
 * withholding would both be caught by the fragment scan rather than only by the structural assertion.
 */
@DisplayName("TransactionListResponse - diagnostic rendering safety for legacy transaction CT00")
class TransactionListResponseSecurityTest {

    private static final String PLACEHOLDER = "***REDACTED***";

    private static final String TRANSACTION_ID_FILTER = "8461372935172994";
    private static final String ROW_TRANSACTION_ID = "7295836142058317";
    private static final String ROW_DESCRIPTION = "ARTISAN COFFEE ROASTERS";
    private static final BigDecimal ROW_AMOUNT = new BigDecimal("4821.73");
    private static final String PREVIOUS_CURSOR = "PRV7295836142058317";
    private static final String NEXT_CURSOR = "NXT8461372935172994";

    /** The four components the outer renderer withholds, in declaration order. */
    private static final List<String> WITHHELD_OUTER_COMPONENTS =
            List.of("rows", "pageMetadata", "continuation", "transactionIdFilter");

    /** The three components the row renderer withholds, in declaration order. */
    private static final List<String> WITHHELD_ROW_COMPONENTS =
            List.of("transactionId", "description", "amount");

    private static final List<String> REGULATED_VALUES = List.of(
            TRANSACTION_ID_FILTER, ROW_TRANSACTION_ID, ROW_DESCRIPTION, ROW_AMOUNT.toPlainString(),
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

    private static TransactionRow row() {
        return new TransactionRow("S", ROW_TRANSACTION_ID, "07/19/22", ROW_DESCRIPTION, ROW_AMOUNT);
    }

    private static PageMetadata page() {
        return PageMetadata.forward(
                PageMetadata.TRANSACTION_LIST_PAGE_SIZE, PREVIOUS_CURSOR, NEXT_CURSOR, true, false,
                "00000001");
    }

    private static TransactionListResponse populated() {
        return populatedWith(null);
    }

    private static TransactionListResponse populatedWith(NavigationContext navigation) {
        return new TransactionListResponse(
                List.of(row(), row()), page(), navigation, "route/next",
                TRANSACTION_ID_FILTER, "00000001", "MESSAGE LINE", true, "TRNIDIN",
                "TITLE ONE", "TITLE TWO", "07/19/22", "14:23:07", "CT00", "COTRN00C");
    }

    private static TransactionListResponse empty() {
        return new TransactionListResponse(
                null, null, null, null, null, null, null, false, null, null, null, null, null, null,
                null);
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

    private static JsonNode payloadOf(TransactionListResponse response) throws Exception {
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
        @DisplayName("each of the three withheld components renders as the fixed stand-in, exactly once "
                + "each")
        void eachWithheldComponentRendersAsTheFixedStandIn() {
            String rendered = populated().toString();
            WITHHELD_OUTER_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
            assertThat(occurrencesOf(rendered, PLACEHOLDER))
                    .isEqualTo(WITHHELD_OUTER_COMPONENTS.size());
        }

        @Test
        @DisplayName("the filter is withheld whether it was accepted or rejected, so a caller cannot "
                + "place text of their choosing in a log by submitting an invalid one")
        void theFilterIsWithheldWhetherAcceptedOrRejected() {
            TransactionListResponse rejected = new TransactionListResponse(
                    List.of(), page(), null, "route/next",
                    "'; DROP TABLE transaction; --", "00000001",
                    TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC, true, "TRNIDIN",
                    "TITLE ONE", "TITLE TWO", "07/19/22", "14:23:07", "CT00", "COTRN00C");
            String rendered = rejected.toString();
            assertThat(rendered)
                    .doesNotContain("DROP TABLE")
                    .contains("transactionIdFilter=" + PLACEHOLDER)
                    .contains("message=" + TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC);
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
                    .doesNotContain("TransactionRow[")
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
        @DisplayName("no row content is recoverable: a page of wholly different movements of the same "
                + "cardinality renders identically, while the cardinality itself is retained on purpose "
                + "because a count names nobody and a paging defect is diagnosed by it")
        void noRowContentIsRecoverable() {
            TransactionRow other = new TransactionRow(
                    "U", "1111222233334444", "01/02/23", "SOMEWHERE ELSE", new BigDecimal("1.00"));
            TransactionListResponse differentMovements = new TransactionListResponse(
                    List.of(other, other), page(), null, "route/next",
                    "9999888877776666", "00000001", "MESSAGE LINE", true, "TRNIDIN",
                    "TITLE ONE", "TITLE TWO", "07/19/22", "14:23:07", "CT00", "COTRN00C");

            assertThat(differentMovements.toString()).isEqualTo(populated().toString());
            assertThat(populated().toString()).contains("rowCount=2");
        }
    }

    @Nested
    @DisplayName("The nested row is safe on its own terms")
    class TheNestedRowIsSafeOnItsOwnTerms {

        @Test
        @DisplayName("a row logged directly withholds all three regulated components and shows the "
                + "selector and the displayed date")
        void aRowLoggedDirectlyWithholdsAllThreeRegulatedComponents() {
            String rendered = row().toString();
            assertThat(rendered)
                    .startsWith("TransactionRow[")
                    .endsWith("]")
                    .doesNotContain(ROW_TRANSACTION_ID)
                    .doesNotContain(ROW_DESCRIPTION)
                    .doesNotContain(ROW_AMOUNT.toPlainString())
                    .contains("selection=S")
                    .contains("displayedDate=07/19/22");
            WITHHELD_ROW_COMPONENTS.forEach(name ->
                    assertThat(rendered).contains(name + "=" + PLACEHOLDER));
            assertThat(occurrencesOf(rendered, PLACEHOLDER))
                    .isEqualTo(WITHHELD_ROW_COMPONENTS.size());
        }

        @Test
        @DisplayName("the row amount does not survive in scaled, unscaled or truncated form")
        void theRowAmountDoesNotSurviveInAnyNumericForm() {
            String rendered = row().toString();
            assertThat(rendered)
                    .doesNotContain(ROW_AMOUNT.toPlainString())
                    .doesNotContain(ROW_AMOUNT.unscaledValue().toString())
                    .doesNotContain(ROW_AMOUNT.setScale(0, RoundingMode.DOWN).toPlainString());
        }

        @Test
        @DisplayName("a collection of rows printed through the collection's own rendering is safe, "
                + "because each element renders through the row override")
        void aCollectionOfRowsIsSafe() {
            String rendered = List.of(row(), row()).toString();
            assertThat(rendered)
                    .doesNotContain(ROW_TRANSACTION_ID)
                    .doesNotContain(ROW_DESCRIPTION)
                    .doesNotContain(ROW_AMOUNT.toPlainString());
            assertThat(occurrencesOf(rendered, PLACEHOLDER)).isEqualTo(6);
        }

        @Test
        @DisplayName("a row renders identically whether its regulated components held values or nothing")
        void aRowRendersIdenticallyWhetherPresentOrAbsent() {
            TransactionRow absent = new TransactionRow("S", null, "07/19/22", null, null);
            assertThat(absent.toString()).isEqualTo(row().toString());
        }
    }

    @Nested
    @DisplayName("The retained components stay visible, so the rendering remains diagnostic")
    class TheRetainedComponentsStayVisible {

        @Test
        @DisplayName("the page label, the row count, the message line, the error flag, the focus field, "
                + "the route and the screen furniture are shown as supplied")
        void theDiagnosticallyUsefulComponentsAreShown() {
            assertThat(populated().toString())
                    .startsWith("TransactionListResponse[")
                    .contains("displayedPageNumber=00000001")
                    .contains("rowCount=2")
                    .contains("message=MESSAGE LINE")
                    .contains("error=true")
                    .contains("focusScreenFieldId=TRNIDIN")
                    .contains("nextRoute=route/next")
                    .contains("transactionName=CT00")
                    .contains("programName=COTRN00C")
                    .contains("currentDate=07/19/22")
                    .contains("currentTime=14:23:07");
        }

        @Test
        @DisplayName("the page label is retained while the cursor keys are withheld, because the label "
                + "counts pages whereas the cursors carry record keys")
        void thePageLabelIsRetainedWhileTheCursorKeysAreWithheld() {
            String rendered = populated().toString();
            assertThat(rendered)
                    .contains("displayedPageNumber=00000001")
                    .doesNotContain(PREVIOUS_CURSOR)
                    .doesNotContain(NEXT_CURSOR);
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
                    "CM00", "COMEN01C", "CT00", "COTRN00C", "USER0001", "U",
                    NavigationContext.ProgramContext.REENTER,
                    "123456789", "MARY", "ANN", "OSULLIVAN",
                    "78412590063", "Y", "5555444433332222", "COTRN0A", "COTRN00");

            String rendered = populatedWith(navigation).toString();
            assertThat(rendered)
                    .doesNotContain("123456789")
                    .doesNotContain("78412590063")
                    .doesNotContain("5555444433332222")
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
                    .startsWith("TransactionListResponse[")
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
        @DisplayName("the filter serialises at its full untouched value")
        void theFilterSerialisesInFull() throws Exception {
            assertThat(payloadOf(populated()).get("transactionIdFilter").asText())
                    .isEqualTo(TRANSACTION_ID_FILTER);
        }

        @Test
        @DisplayName("every row serialises its identifier, its truncated description and its amount, so "
                + "withholding from a log does not withhold from the client")
        void everyRowSerialisesItsRegulatedValuesInFull() throws Exception {
            JsonNode rows = payloadOf(populated()).get("rows");
            assertThat(rows).hasSize(2);
            rows.forEach(node -> {
                assertThat(node.get("transactionId").asText()).isEqualTo(ROW_TRANSACTION_ID);
                assertThat(node.get("description").asText()).isEqualTo(ROW_DESCRIPTION);
            });
        }

        @Test
        @DisplayName("the row amount keeps its two-place scale in the emitted bytes, unaffected by the "
                + "rendering override")
        void theRowAmountKeepsItsScaleInTheEmittedBytes() throws Exception {
            // Asserted against the serialized text rather than a re-parsed tree, because reading a JSON
            // float back coerces it to a double and a two-place value ending in zero would return a
            // place short even when the emitted bytes are correct.
            assertThat(moduleEquivalentMapper().writeValueAsString(populated()))
                    .contains("\"amount\":4821.73");
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
