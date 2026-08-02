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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link TransactionListResponse}, the response body of legacy transaction {@code CT00}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>Four properties are under test. The presentation vocabulary is the one every other response in
 * this package uses, so a client reads one set of names rather than six. The row count is checked at
 * the ten row families the screen declares - and checking is not truncating: a page holding more rows
 * than the screen can show is a defect worth surfacing, not one worth hiding by discarding rows. The
 * row amount's record shape is both published and refused when wrong, having previously been stated in
 * prose and enforced nowhere. And the rendering withholds the row list, the paging cursor and the
 * search key while still reporting how many rows came back.
 *
 * <p>The five paging messages are asserted character for character. They are operator-visible text
 * reproduced from the legacy program, so they are an external contract under Gate 5 rather than
 * incidental strings, and a well-intentioned edit to a component name must never be able to reach
 * inside them.
 *
 * <p>Every assertion about the decimal on the wire goes through {@code writeValueAsString}, because
 * reading a decimal back out of a parsed tree as text renders it in exponential notation and would
 * describe the tree's own formatting rather than this record's serialized form.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("TransactionListResponse :: transaction-list response contract of legacy transaction CT00")
class TransactionListResponseTest {

    /** The fifteen record components in declaration order. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "rows", "pageMetadata", "navigationContext", "nextRoute", "transactionIdFilter",
            "displayedPageNumber", "message", "error", "focusScreenFieldId", "title01", "title02",
            "currentDate", "currentTime", "transactionName", "programName");

    private static final String TRANSACTION_ID = "0000000000000042";
    private static final String TRANSACTION_ID_FILTER = "0000000000000031";
    private static final String DESCRIPTION = "GROCERIES AT STORE 42";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String ACCOUNT_ID = "00000000011";
    private static final BigDecimal AMOUNT = new BigDecimal("-123.45");
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CT00", "COTRN00C", "CT00", "COTRN00C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN0A", "COTRN00");
    }

    private static PageMetadata pageMetadata() {
        return PageMetadata.forward(TransactionListResponse.ROW_COUNT, TRANSACTION_ID_FILTER,
                TRANSACTION_ID, true, false, "00000003");
    }

    private static TransactionListResponse.TransactionRow row() {
        return new TransactionListResponse.TransactionRow("S", TRANSACTION_ID, "07/19/22",
                DESCRIPTION.substring(0, 26 - 5), AMOUNT);
    }

    private static TransactionListResponse response(List<TransactionListResponse.TransactionRow> rows) {
        return new TransactionListResponse(rows, pageMetadata(), navigation(), "/api/transactions",
                TRANSACTION_ID_FILTER, "00000003", TransactionListResponse.MESSAGE_AT_TOP, false,
                "TRNIDIN", "List Transactions", "Tran List", "07/19/22", "14:23:07", "CT00",
                "COTRN00C");
    }

    private static TransactionListResponse populated() {
        return response(List.of(row()));
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

    private static List<String> componentNames() {
        return Arrays.stream(TransactionListResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotation an outer component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component: neither {@code Size} nor
     * {@code Schema} declares {@code RECORD_COMPONENT} among its targets, so asking the component
     * yields nothing and an assertion phrased that way would pass without testing anything.
     *
     * @param <A> the annotation type
     * @param name the component name
     * @param type the annotation type to read
     * @return the annotation, or {@code null} when absent
     */
    private static <A extends java.lang.annotation.Annotation> A annotationOn(String name,
            Class<A> type) {
        try {
            return TransactionListResponse.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends java.lang.annotation.Annotation> A rowAnnotationOn(String name,
            Class<A> type) {
        try {
            return TransactionListResponse.TransactionRow.class.getDeclaredField(name)
                    .getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no row component named " + name, absent);
        }
    }

    /**
     * Returns a rendering with the delegated navigation segment excised.
     *
     * <p>Excised rather than truncated at, because this record prints its navigation state fourth
     * rather than last: truncating there would discard the presentation and paging state the
     * assertions are about. The delegated segment is removed by matching the nested record's own
     * rendering exactly, so no bracket counting is needed and nothing this record produced is lost.
     *
     * @param response the response whose rendering is wanted
     * @return the rendering with the delegated segment replaced by a marker
     */
    private static String ownRendering(TransactionListResponse response) {
        String rendered = response.toString();
        return (response.navigationContext() == null) ? rendered
                : rendered.replace(response.navigationContext().toString(), "<delegated>");
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the fifteen components in declaration order")
        void declaresFifteenComponents() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_ORDER)
                    .hasSize(15);
        }

        @Test
        @DisplayName("declares the five row components in map order")
        void declaresTheFiveRowComponents() {
            assertThat(Arrays.stream(
                    TransactionListResponse.TransactionRow.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactly("selection", "transactionId", "displayedDate", "description",
                            "amount");
        }

        @Test
        @DisplayName("carries the canonical vocabulary and none of the six superseded spellings")
        void carriesTheCanonicalVocabulary() {
            assertThat(componentNames())
                    .contains("pageMetadata", "navigationContext", "displayedPageNumber",
                            "focusScreenFieldId", "title01", "title02")
                    .doesNotContain("page", "navigation", "pageNumber", "focusFieldName",
                            "screenTitleLine1", "screenTitleLine2");
        }

        @Test
        @DisplayName("declares every width from the map item it echoes")
        void declaresEveryWidthFromTheMap() {
            assertThat(TransactionListResponse.SELECTION_LENGTH).isEqualTo(1);
            assertThat(TransactionListResponse.TRANSACTION_ID_LENGTH).isEqualTo(16);
            assertThat(TransactionListResponse.DISPLAYED_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.DESCRIPTION_LENGTH).isEqualTo(26);
            assertThat(TransactionListResponse.MESSAGE_LENGTH).isEqualTo(78);
            assertThat(TransactionListResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(TransactionListResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(TransactionListResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("bounds the focus field identifier at the seven-character generator ceiling")
        void boundsTheFocusFieldIdentifier() {
            assertThat(TransactionListResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
            assertThat(annotationOn("focusScreenFieldId", Size.class).max()).isEqualTo(7);
        }

        @Test
        @DisplayName("declares the page-indicator width as eight, which is not the card-list width")
        void declaresThePageIndicatorWidthAsEight() {
            assertThat(TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("PAGENUM of COTRN00 is PIC 9(08); the card-list PAGENO is X(3)")
                    .isEqualTo(8);
            assertThat(annotationOn("displayedPageNumber", Size.class).max()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("the row count is checked, and checking is not truncating")
    class RowCountIsChecked {

        @Test
        @DisplayName("declares the row count as the ten row families the screen carries")
        void declaresTheRowCountAsTen() {
            assertThat(TransactionListResponse.ROW_COUNT)
                    .as("the fill loops at COTRN00C lines 290 and 297 run to this bound")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("accepts a full page of ten rows")
        void acceptsAFullPage() {
            List<TransactionListResponse.TransactionRow> ten = Collections.nCopies(10, row());

            assertThatNoException().isThrownBy(() -> response(ten));
            assertThat(response(ten).rows()).hasSize(10);
        }

        @Test
        @DisplayName("rejects an eleventh row, naming both figures")
        void rejectsAnEleventhRow() {
            List<TransactionListResponse.TransactionRow> eleven = Collections.nCopies(11, row());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response(eleven))
                    .withMessage("rows may hold at most 10 entries, because that is how many row"
                            + " families the transaction-list screen declares, but it holds 11");
        }

        @Test
        @DisplayName("leaves a short page short rather than padding it")
        void leavesAShortPageShort() {
            assertThat(response(List.of(row(), row())).rows())
                    .as("the legacy screen shows blank rows; the contract reports the rows it has")
                    .hasSize(2);
        }

        @Test
        @DisplayName("replaces an absent list with an empty one")
        void replacesAnAbsentListWithAnEmptyOne() {
            assertThat(response(null).rows()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("copies the supplied list defensively")
        void copiesTheSuppliedListDefensively() {
            List<TransactionListResponse.TransactionRow> mutable = new ArrayList<>();
            mutable.add(row());

            TransactionListResponse response = response(mutable);
            mutable.clear();

            assertThat(response.rows()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("the row amount's record shape is published and refused when wrong")
    class RowAmountRecordShape {

        @Test
        @DisplayName("declares the scale and the integer-digit width as named constants")
        void declaresScaleAndWidthAsConstants() {
            assertThat(TransactionListResponse.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionListResponse.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
        }

        @Test
        @DisplayName("publishes a schema description naming the record field and its figures")
        void publishesASchemaDescription() {
            Schema schema = rowAnnotationOn("amount", Schema.class);

            assertThat(schema).isNotNull();
            assertThat(schema.description())
                    .contains("TRAN-AMT")
                    .contains("CVTRA05Y.cpy")
                    .contains("nine integer digits")
                    .contains("scale exactly 2");
        }

        @Test
        @DisplayName("rejects a scale below the record scale, with the figure named")
        void rejectsAScaleBelowTheRecordScale() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionListResponse.TransactionRow("S",
                            TRANSACTION_ID, "07/19/22", "GROCERIES", new BigDecimal("123.4")))
                    .withMessage("amount must carry scale 2, because its record field stores two"
                            + " decimal places, but its scale is 1");
        }

        @Test
        @DisplayName("rejects a scale above the record scale rather than rounding it away")
        void rejectsAScaleAboveTheRecordScale() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionListResponse.TransactionRow("S",
                            TRANSACTION_ID, "07/19/22", "GROCERIES", new BigDecimal("123.456")))
                    .withMessage("amount must carry scale 2, because its record field stores two"
                            + " decimal places, but its scale is 3");
        }

        @Test
        @DisplayName("rejects a value needing a tenth integer digit, with the figure named")
        void rejectsATenthIntegerDigit() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionListResponse.TransactionRow("S",
                            TRANSACTION_ID, "07/19/22", "GROCERIES",
                            new BigDecimal("1000000000.00")))
                    .withMessage("amount must fit 9 integer digits, because that is the width of its"
                            + " record field, but it needs 10");
        }

        @Test
        @DisplayName("accepts the widest value the record field can hold, and an absent one")
        void acceptsTheWidestValueAndAnAbsentOne() {
            assertThatNoException().isThrownBy(() -> new TransactionListResponse.TransactionRow("S",
                    TRANSACTION_ID, "07/19/22", "GROCERIES", new BigDecimal("999999999.99")));
            assertThatNoException().isThrownBy(() -> new TransactionListResponse.TransactionRow("S",
                    TRANSACTION_ID, "07/19/22", "GROCERIES", null));
        }

        @Test
        @DisplayName("leaves an accepted amount untouched, applying no rescaling of any kind")
        void leavesAnAcceptedAmountUntouched() {
            BigDecimal supplied = new BigDecimal("-123.45");

            assertThat(new TransactionListResponse.TransactionRow("S", TRANSACTION_ID, "07/19/22",
                    "GROCERIES", supplied).amount())
                    .as("refusing is not normalising: the very same value comes back")
                    .isSameAs(supplied);
        }
    }

    @Nested
    @DisplayName("serialized form")
    class SerializedForm {

        @Test
        @DisplayName("carries the canonical names onto the wire")
        void carriesTheCanonicalNamesOntoTheWire() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json).contains("\"pageMetadata\":", "\"navigationContext\":",
                    "\"displayedPageNumber\":", "\"focusScreenFieldId\":", "\"title01\":",
                    "\"title02\":");
        }

        @Test
        @DisplayName("carries none of the six superseded names onto the wire")
        void carriesNoneOfTheSupersededNames() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json).doesNotContain("\"page\":", "\"navigation\":", "\"pageNumber\":",
                    "\"focusFieldName\":", "\"screenTitleLine1\":", "\"screenTitleLine2\":");
        }

        @Test
        @DisplayName("writes the row amount in plain notation, never in exponential form")
        void writesTheRowAmountInPlainNotation() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(
                    response(List.of(new TransactionListResponse.TransactionRow("S",
                            TRANSACTION_ID, "07/19/22", "GROCERIES",
                            new BigDecimal("999999999.99")))));

            assertThat(json).contains("\"amount\":999999999.99");
            assertThat(json).doesNotContain("E+", "E9", "e+");
        }

        @Test
        @DisplayName("preserves the trailing zeros the record scale requires")
        void preservesTrailingZeros() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(
                    response(List.of(new TransactionListResponse.TransactionRow("S",
                            TRANSACTION_ID, "07/19/22", "GROCERIES", new BigDecimal("10.00")))));

            assertThat(json).contains("\"amount\":10.00");
        }
    }

    @Nested
    @DisplayName("diagnostic rendering of the response")
    class DiagnosticRenderingOfTheResponse {

        @Test
        @DisplayName("withholds the row list, the paging cursor and the search key")
        void withholdsTheRowListCursorAndSearchKey() {
            String rendered = ownRendering(populated());

            assertThat(rendered).contains("rows=" + REDACTED, "pageMetadata=" + REDACTED,
                    "transactionIdFilter=" + REDACTED);
            assertThat(rendered).doesNotContain(TRANSACTION_ID, TRANSACTION_ID_FILTER, DESCRIPTION);
        }

        @Test
        @DisplayName("still reports how many rows came back, which is the useful diagnostic")
        void stillReportsTheRowCount() {
            assertThat(populated().toString()).contains("rowCount=1");
            assertThat(response(Collections.nCopies(7, row())).toString()).contains("rowCount=7");
            assertThat(response(null).toString()).contains("rowCount=0");
        }

        @Test
        @DisplayName("retains the presentation and paging state an operator needs")
        void retainsThePresentationState() {
            String rendered = ownRendering(populated());

            assertThat(rendered).contains("displayedPageNumber=00000003", "error=false",
                    "focusScreenFieldId=TRNIDIN", "title01=List Transactions", "title02=Tran List",
                    "transactionName=CT00", "programName=COTRN00C");
        }

        @Test
        @DisplayName("delegates the navigation state, which withholds its own identifiers")
        void delegatesTheNavigationState() {
            String rendered = populated().toString();

            assertThat(rendered).contains(", navigationContext=NavigationContext[");
            assertThat(rendered).doesNotContain(CARD_NUMBER, ACCOUNT_ID);
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            TransactionListResponse response = populated();
            response.toString();

            assertThat(response.transactionIdFilter()).isEqualTo(TRANSACTION_ID_FILTER);
            assertThat(response.rows()).hasSize(1);
            assertThat(response.pageMetadata()).isNotNull();
        }
    }

    @Nested
    @DisplayName("diagnostic rendering of one row")
    class DiagnosticRenderingOfOneRow {

        @Test
        @DisplayName("withholds the transaction identifier, the description and the amount")
        void withholdsTheRegulatedValues() {
            String rendered = row().toString();

            assertThat(rendered).contains("transactionId=" + REDACTED, "description=" + REDACTED,
                    "amount=" + REDACTED);
            assertThat(rendered).doesNotContain(TRANSACTION_ID, "GROCERIES", "123.45");
        }

        @Test
        @DisplayName("retains the selection marker and the displayed date")
        void retainsSelectionAndDisplayedDate() {
            assertThat(row().toString())
                    .startsWith("TransactionRow[")
                    .contains("selection=S", "displayedDate=07/19/22")
                    .endsWith("]");
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            TransactionListResponse.TransactionRow row = row();
            row.toString();

            assertThat(row.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(row.amount()).isEqualByComparingTo(AMOUNT);
        }
    }

    @Nested
    @DisplayName("operator diagnostics reproduce the legacy text")
    class OperatorDiagnosticsReproduceLegacyText {

        @Test
        @DisplayName("reproduces the already-at-top message character for character")
        void reproducesAlreadyAtTop() {
            assertThat(TransactionListResponse.MESSAGE_ALREADY_AT_TOP)
                    .isEqualTo("You are already at the top of the page...");
        }

        @Test
        @DisplayName("reproduces the already-at-bottom message character for character")
        void reproducesAlreadyAtBottom() {
            assertThat(TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM)
                    .isEqualTo("You are already at the bottom of the page...");
        }

        @Test
        @DisplayName("reproduces the at-top message character for character")
        void reproducesAtTop() {
            assertThat(TransactionListResponse.MESSAGE_AT_TOP)
                    .isEqualTo("You are at the top of the page...");
        }

        @Test
        @DisplayName("reproduces the reached-bottom message character for character")
        void reproducesReachedBottom() {
            assertThat(TransactionListResponse.MESSAGE_REACHED_BOTTOM)
                    .isEqualTo("You have reached the bottom of the page...");
        }

        @Test
        @DisplayName("reproduces the reached-top message character for character")
        void reproducesReachedTop() {
            assertThat(TransactionListResponse.MESSAGE_REACHED_TOP)
                    .isEqualTo("You have reached the top of the page...");
        }

        @Test
        @DisplayName("keeps every paging message clear of any component name")
        void keepsEveryPagingMessageClearOfComponentNames() {
            List<String> pagingMessages = List.of(TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                    TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                    TransactionListResponse.MESSAGE_AT_TOP,
                    TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                    TransactionListResponse.MESSAGE_REACHED_TOP);

            assertThat(pagingMessages)
                    .as("these are operator-visible legacy text under Gate 5, not incidental"
                            + " strings: a component rename must never reach inside them")
                    .allSatisfy(message -> assertThat(message)
                            .contains("the page...")
                            .doesNotContain("pageMetadata", "navigationContext",
                                    "displayedPageNumber"));
        }

        @Test
        @DisplayName("reproduces the selection and numeric-key messages")
        void reproducesSelectionAndNumericKeyMessages() {
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid value is S");
            assertThat(TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC)
                    .as("the legacy text carries a space before the ellipsis")
                    .isEqualTo("Tran ID must be Numeric ...");
        }
    }
}
