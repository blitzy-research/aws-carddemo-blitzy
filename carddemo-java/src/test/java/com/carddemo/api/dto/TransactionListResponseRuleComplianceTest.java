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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link TransactionListResponse} and its nested
 * {@link TransactionListResponse.TransactionRow}, the response body of legacy transaction
 * {@code CT00} implemented by {@code app/cbl/COTRN00C.cbl} over screen
 * {@code app/cpy-bms/COTRN00.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Ten rows, and both fill directions matter.</strong> Forward paging fills rows one
 * through ten ascending; the backward key fills ten down to one through {@code READPREV} at
 * {@code app/cbl/COTRN00C.cbl:349}. The row list therefore carries presentation order rather than
 * retrieval order, and the two are asserted separately here because a backward page whose rows are
 * left in retrieval order would present the page upside down relative to the legacy screen.
 *
 * <p><strong>The displayed description is twenty-six characters, not sixty.</strong>
 * {@code TRAN-DESC} is {@code PIC X(60)} in {@code app/cpy/CVTRA05Y.cpy}, but the list screen has
 * room for twenty-six. {@link TransactionListResponse#DESCRIPTION_LENGTH} publishes the screen width,
 * so the truncation is an explicit contract rather than an accident of rendering.
 *
 * <p><strong>The amount is decimal and truncating.</strong> {@code TRAN-AMT} is
 * {@code PIC S9(09)V99} in {@code app/cpy/CVTRA05Y.cpy:L14} and the estate declares no
 * {@code ROUNDED} clause anywhere, so the row carries {@link BigDecimal} at scale two. The wire
 * assertions read the serialized characters rather than a parsed tree, because a tree binds a JSON
 * float to a double by default and would report scientific notation for a plain decimal payload.
 */
@DisplayName("TransactionListResponse - the CT00 transaction-list screen contract")
class TransactionListResponseRuleComplianceTest {

    /**
     * The placeholder the response's own rendering substitutes for a withheld component.
     *
     * <p>Restated here rather than read reflectively because the production constant is private on
     * purpose: a diagnostic placeholder is not part of the published contract, and exposing it so a
     * test could read it would widen the contract to suit the test.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

    /** A representative sixteen-digit transaction identifier. */
    private static final String TRANSACTION_ID = "0000000000000042";

    /**
     * Builds a mapper configured exactly as {@code application.yml} configures the module's mapper.
     *
     * @return a mapper carrying the module's four Jackson settings
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
    private static JsonNode payloadOf(final TransactionListResponse response)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(response));
    }

    /**
     * Serializes a response to the exact characters that go on the wire.
     *
     * @param response the response to render
     * @return the rendered payload as written
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static String wirePayloadOf(final TransactionListResponse response)
            throws JsonProcessingException {
        return moduleEquivalentMapper().writeValueAsString(response);
    }

    /**
     * Builds a response positionally, laid out in rows of five so a component cannot silently drift
     * one position out of line past a compiler that sees a wall of interchangeable strings.
     *
     * @param rows the rendered transaction rows in presentation order
     * @param pageMetadata the paging cursor and direction
     * @param navigationContext the carried conversation state
     * @param transactionIdFilter the identifier the browse started from
     * @param displayedPageNumber the displayed page number
     * @param message the operator message written to the message line
     * @param error whether the message is a rejection rather than an advisory
     * @param focusScreenFieldId the identity of the map item the cursor is placed on
     * @return a response carrying the supplied values and the fixed screen furniture
     */
    private static TransactionListResponse aResponse(
            final List<TransactionListResponse.TransactionRow> rows,
            final PageMetadata pageMetadata,
            final NavigationContext navigationContext, final String transactionIdFilter,
            final String displayedPageNumber,
            final String message, final boolean error, final String focusScreenFieldId) {
        return new TransactionListResponse(
                rows, pageMetadata, navigationContext, "/api/menu/user", transactionIdFilter,
                displayedPageNumber, message, error, focusScreenFieldId, "CardDemo",
                "List Transactions", "07/19/22", "10:30:00", "CT00", "COTRN00C");
    }

    /**
     * Builds a response carrying only a row list.
     *
     * @param rows the rendered transaction rows
     * @return a response carrying only those rows
     */
    private static TransactionListResponse aResponseWithRows(
            final List<TransactionListResponse.TransactionRow> rows) {
        return new TransactionListResponse(
                rows, null, null, null, null,
                null, null, false, null, null,
                null, null, null, null, null);
    }

    /**
     * Builds a row carrying a selection marker, an identifier and an amount.
     *
     * @param selection the one-character selection marker
     * @param transactionId the transaction the row renders
     * @param amount the monetary amount the row renders
     * @return a row carrying those values and fixed date and description text
     */
    private static TransactionListResponse.TransactionRow aRow(final String selection,
            final String transactionId, final String amount) {
        return new TransactionListResponse.TransactionRow(selection, transactionId, "07/19/22",
                "POS PURCHASE", new BigDecimal(amount));
    }

    /**
     * Reports whether a named component's accessor carries a declared upper bound.
     *
     * <p>The annotation is read from the accessor rather than from the record component, because
     * {@code jakarta.validation.constraints.Size} does not target {@code RECORD_COMPONENT}. A
     * constraint written on a record component is propagated to the backing field, the accessor and
     * the canonical constructor parameter instead of being retained on the component itself.
     *
     * @param owner the record whose accessor is inspected
     * @param componentName the record component to test
     * @return {@code true} when the accessor declares a {@link Size} bound
     */
    private static boolean declaresAnUpperBound(final Class<?> owner, final String componentName) {
        try {
            return owner.getDeclaredMethod(componentName).getAnnotation(Size.class) != null;
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("no accessor declared for " + componentName, cause);
        }
    }

    /**
     * Reads the declared upper bound of a named component's accessor.
     *
     * @param owner the record whose accessor is inspected
     * @param componentName the record component whose accessor carries the annotation
     * @return the declared maximum length
     * @throws NoSuchMethodException when no such accessor is declared
     */
    private static int declaredMaximumLength(final Class<?> owner, final String componentName)
            throws NoSuchMethodException {
        final Size size = owner.getDeclaredMethod(componentName).getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published widths and the amount scale")
    class ThePublishedWidthsAndTheAmountScale {

        @Test
        @DisplayName("the selection width is one, so no row can carry two actions at once")
        void theSelectionWidthIsOne() {
            assertThat(TransactionListResponse.SELECTION_LENGTH).isOne();
        }

        @Test
        @DisplayName("the identifier width is sixteen, matching TRAN-ID PIC X(16) in the transaction "
                + "record")
        void theIdentifierWidthIsSixteen() {
            assertThat(TransactionListResponse.TRANSACTION_ID_LENGTH).isEqualTo(16);
        }

        @Test
        @DisplayName("the displayed description is twenty-six characters, well short of the sixty the "
                + "transaction record stores, because the list screen truncates it")
        void theDisplayedDescriptionIsTwentySixCharacters() {
            assertThat(TransactionListResponse.DESCRIPTION_LENGTH).isEqualTo(26);
            assertThat(TransactionListResponse.DESCRIPTION_LENGTH).isLessThan(60);
        }

        @Test
        @DisplayName("the amount scale is two, the scale of the PIC S9(09)V99 field the amount comes "
                + "from")
        void theAmountScaleIsTwo() {
            assertThat(TransactionListResponse.AMOUNT_SCALE).isEqualTo(2);
        }

        @Test
        @DisplayName("the displayed date is eight characters, and so are the current date and time, "
                + "because all three are the same legacy screen width")
        void theDisplayedDateAndTheClockFieldsAreEightCharacters() {
            assertThat(TransactionListResponse.DISPLAYED_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.CURRENT_TIME_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("the message line is seventy-eight characters and the focus field name is seven, "
                + "which are the shared screen widths across this estate")
        void theMessageLineAndFocusFieldNameCarryTheSharedWidths() {
            assertThat(TransactionListResponse.MESSAGE_LENGTH).isEqualTo(78);
            assertThat(TransactionListResponse.SCREEN_FIELD_ID_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the furniture widths are forty of title, four of transaction name, eight of "
                + "program name and eight of page number")
        void theFurnitureWidthsAreTheLegacyWidths() {
            assertThat(TransactionListResponse.SCREEN_TITLE_LENGTH).isEqualTo(40);
            assertThat(TransactionListResponse.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(TransactionListResponse.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(TransactionListResponse.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(8);
        }

        @ParameterizedTest
        @CsvSource({
            "transactionIdFilter,16", "displayedPageNumber,8", "message,78",
            "focusScreenFieldId,7", "title01,40", "title02,40",
            "currentDate,8", "currentTime,8", "transactionName,4",
            "programName,8",
        })
        @DisplayName("every bounded component declares the width its named constant publishes")
        void everyBoundedComponentDeclaresItsPublishedWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(TransactionListResponse.class, componentName))
                    .isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the page size this screen fills is ten, published by the shared paging metadata "
                + "because the legacy screen establishes it by loop bound and not by table")
        void thePageSizeIsTen() {
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE).isEqualTo(10);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the seven paging and selection messages")
    class TheSevenPagingAndSelectionMessages {

        @Test
        @DisplayName("the invalid-selection message names the single legal value, which is S and not "
                + "the U and D pair the user-list screen accepts")
        void theInvalidSelectionMessageNamesTheSingleLegalValue() {
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION)
                    .isEqualTo("Invalid selection. Valid value is S");
        }

        @Test
        @DisplayName("the non-numeric filter message keeps its space before the ellipsis, which the "
                + "legacy literal carries and an operator match would otherwise miss")
        void theNonNumericFilterMessageKeepsItsSpaceBeforeTheEllipsis() {
            assertThat(TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC)
                    .isEqualTo("Tran ID must be Numeric ...");
            assertThat(TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC).contains(" ...");
        }

        @Test
        @DisplayName("the two already-at messages are the refusals raised when the operator pages "
                + "beyond an end")
        void theTwoAlreadyAtMessagesArePublished() {
            assertThat(TransactionListResponse.MESSAGE_ALREADY_AT_TOP)
                    .isEqualTo("You are already at the top of the page...");
            assertThat(TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM)
                    .isEqualTo("You are already at the bottom of the page...");
        }

        @Test
        @DisplayName("the three positional advisories are distinct from the refusals, so a screen can "
                + "say where the operator is without saying the request failed")
        void theThreePositionalAdvisoriesAreDistinctFromTheRefusals() {
            assertThat(TransactionListResponse.MESSAGE_AT_TOP)
                    .isEqualTo("You are at the top of the page...");
            assertThat(TransactionListResponse.MESSAGE_REACHED_BOTTOM)
                    .isEqualTo("You have reached the bottom of the page...");
            assertThat(TransactionListResponse.MESSAGE_REACHED_TOP)
                    .isEqualTo("You have reached the top of the page...");

            assertThat(TransactionListResponse.MESSAGE_AT_TOP)
                    .isNotEqualTo(TransactionListResponse.MESSAGE_ALREADY_AT_TOP);
            assertThat(TransactionListResponse.MESSAGE_REACHED_TOP)
                    .isNotEqualTo(TransactionListResponse.MESSAGE_ALREADY_AT_TOP);
        }

        @Test
        @DisplayName("all seven messages are distinct and every one of them fits the message line")
        void allSevenMessagesAreDistinctAndFit() {
            final List<String> messages = List.of(
                    TransactionListResponse.MESSAGE_INVALID_SELECTION,
                    TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC,
                    TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                    TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                    TransactionListResponse.MESSAGE_AT_TOP,
                    TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                    TransactionListResponse.MESSAGE_REACHED_TOP);

            assertThat(messages).hasSize(7).doesNotHaveDuplicates();
            assertThat(messages).allSatisfy(message -> assertThat(message.length())
                    .isLessThanOrEqualTo(TransactionListResponse.MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("the six paging messages all end in the legacy three-dot continuation while the "
                + "invalid-selection message deliberately does not")
        void theSixPagingMessagesEndInThreeDotsAndTheSelectionMessageDoesNot() {
            assertThat(List.of(
                    TransactionListResponse.MESSAGE_TRAN_ID_NOT_NUMERIC,
                    TransactionListResponse.MESSAGE_ALREADY_AT_TOP,
                    TransactionListResponse.MESSAGE_ALREADY_AT_BOTTOM,
                    TransactionListResponse.MESSAGE_AT_TOP,
                    TransactionListResponse.MESSAGE_REACHED_BOTTOM,
                    TransactionListResponse.MESSAGE_REACHED_TOP))
                    .allSatisfy(message -> assertThat(message).endsWith("..."));
            assertThat(TransactionListResponse.MESSAGE_INVALID_SELECTION).doesNotEndWith("...");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the defaulted row list")
    class TheDefaultedRowList {

        @Test
        @DisplayName("an absent row list becomes an empty list, so a screen never has to guard against "
                + "a null before rendering rows")
        void anAbsentRowListBecomesAnEmptyList() {
            assertThat(aResponseWithRows(null).rows()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("a supplied row list is copied, so mutating the caller's list afterwards cannot "
                + "change the response")
        void aSuppliedRowListIsCopied() {
            final List<TransactionListResponse.TransactionRow> mutable =
                    new ArrayList<>(List.of(aRow("S", TRANSACTION_ID, "10.00")));
            final TransactionListResponse response = aResponseWithRows(mutable);

            mutable.clear();

            assertThat(response.rows()).hasSize(1);
        }

        @Test
        @DisplayName("the copy is unmodifiable, so a holder of the response cannot rewrite a rendered "
                + "page")
        void theCopyIsUnmodifiable() {
            final List<TransactionListResponse.TransactionRow> rows =
                    aResponseWithRows(List.of(aRow("S", TRANSACTION_ID, "10.00"))).rows();
            final TransactionListResponse.TransactionRow replacement =
                    aRow("U", TRANSACTION_ID, "20.00");

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.set(0, replacement));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(replacement));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(rows::clear);
        }

        @Test
        @DisplayName("a null row is refused outright, because a blank screen row is carried as a row "
                + "of blanks rather than as an absent entry")
        void aNullRowIsRefusedOutright() {
            final List<TransactionListResponse.TransactionRow> withNull =
                    Arrays.asList(aRow("S", TRANSACTION_ID, "10.00"), null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aResponseWithRows(withNull));
        }

        @Test
        @DisplayName("an empty row list and an absent one produce equal responses, because both mean "
                + "the browse returned nothing")
        void anEmptyRowListAndAnAbsentOneProduceEqualResponses() {
            assertThat(aResponseWithRows(List.of())).isEqualTo(aResponseWithRows(null));
        }

        @Test
        @DisplayName("a forward page carries its ten rows in ascending presentation order")
        void aForwardPageCarriesTenRowsAscending() {
            final List<TransactionListResponse.TransactionRow> ascending = new ArrayList<>();
            for (int index = 1; index <= PageMetadata.TRANSACTION_LIST_PAGE_SIZE; index++) {
                ascending.add(aRow(" ", String.format("%016d", index), index + ".00"));
            }

            assertThat(aResponseWithRows(ascending).rows())
                    .hasSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .extracting(TransactionListResponse.TransactionRow::transactionId)
                    .containsExactly("0000000000000001", "0000000000000002", "0000000000000003",
                            "0000000000000004", "0000000000000005", "0000000000000006",
                            "0000000000000007", "0000000000000008", "0000000000000009",
                            "0000000000000010");
        }

        @Test
        @DisplayName("a backward page carries the rows the legacy READPREV fill produced, so the page "
                + "presents in the order the screen would have drawn it and not in retrieval order")
        void aBackwardPageCarriesTheRowsInPresentationOrder() {
            final List<TransactionListResponse.TransactionRow> retrievalOrder = List.of(
                    aRow(" ", "0000000000000010", "10.00"),
                    aRow(" ", "0000000000000009", "9.00"),
                    aRow(" ", "0000000000000008", "8.00"));
            final List<TransactionListResponse.TransactionRow> presentationOrder =
                    new ArrayList<>(retrievalOrder);
            Collections.reverse(presentationOrder);

            final TransactionListResponse response = aResponse(presentationOrder,
                    PageMetadata.backward(PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                            "0000000000000008", "0000000000000010", false, true, "00000001"),
                    null, null, "00000001", null, false, null);

            assertThat(response.rows())
                    .extracting(TransactionListResponse.TransactionRow::transactionId)
                    .containsExactly("0000000000000008", "0000000000000009", "0000000000000010");
            assertThat(response.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.BACKWARD);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the nested transaction row")
    class TheNestedTransactionRow {

        @Test
        @DisplayName("the row declares five components, the selection marker, the identifier, the "
                + "displayed date, the truncated description and the amount")
        void theRowDeclaresFiveComponents() {
            final List<String> declared = Arrays.stream(
                    TransactionListResponse.TransactionRow.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("selection", "transactionId", "displayedDate",
                    "description", "amount");
            assertThat(declared).hasSize(5);
        }

        @Test
        @DisplayName("every row component round-trips through its own accessor unchanged")
        void everyRowComponentRoundTripsThroughItsAccessor() {
            final TransactionListResponse.TransactionRow row = aRow("S", TRANSACTION_ID, "1234.56");

            assertThat(row.selection()).isEqualTo("S");
            assertThat(row.transactionId()).isEqualTo(TRANSACTION_ID);
            assertThat(row.displayedDate()).isEqualTo("07/19/22");
            assertThat(row.description()).isEqualTo("POS PURCHASE");
            assertThat(row.amount()).isEqualByComparingTo("1234.56");
        }

        @Test
        @DisplayName("the amount is BigDecimal rather than any floating-point type, because a cent is "
                + "not representable as a binary fraction")
        void theAmountIsBigDecimal() throws NoSuchMethodException {
            assertThat(TransactionListResponse.TransactionRow.class.getDeclaredMethod("amount")
                    .getReturnType()).isEqualTo(BigDecimal.class);
            assertThat(Arrays.stream(
                    TransactionListResponse.TransactionRow.class.getRecordComponents())
                    .map(component -> component.getType().getName()).toList())
                    .doesNotContain("double", "float", "java.lang.Double", "java.lang.Float");
        }

        @ParameterizedTest
        @CsvSource({"selection,1", "transactionId,16", "displayedDate,8", "description,26"})
        @DisplayName("every bounded row component declares the width the outer record publishes for it")
        void everyBoundedRowComponentDeclaresItsPublishedWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(TransactionListResponse.TransactionRow.class,
                    componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the amount carries no length bound, because a decimal is bounded by its scale "
                + "and precision rather than by a character count")
        void theAmountCarriesNoLengthBound() {
            assertThat(declaresAnUpperBound(TransactionListResponse.TransactionRow.class, "amount"))
                    .isFalse();
        }

        @Test
        @DisplayName("a row accepts its declared widths and rejects one character more on each")
        void aRowAcceptsItsWidthsAndRejectsOneMore() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(
                        new TransactionListResponse.TransactionRow("X", "X".repeat(16),
                                "X".repeat(8), "X".repeat(26), new BigDecimal("0.00")))).isEmpty();
                assertThat(factory.getValidator().validate(
                        new TransactionListResponse.TransactionRow("XX", "X".repeat(17),
                                "X".repeat(9), "X".repeat(27), new BigDecimal("0.00")))).hasSize(4);
            }
        }

        @Test
        @DisplayName("a wholly absent row is constructible and reports no violation, which is how a "
                + "blank screen row is carried")
        void aWhollyAbsentRowIsConstructibleAndValid() {
            final TransactionListResponse.TransactionRow blank =
                    new TransactionListResponse.TransactionRow(null, null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(blank)).isEmpty();
            }
            assertThat(blank.amount()).isNull();
        }

        @Test
        @DisplayName("two rows built from identical values are equal and share a hash code")
        void rowsCarryValueSemantics() {
            assertThat(aRow("S", TRANSACTION_ID, "10.00"))
                    .isEqualTo(aRow("S", TRANSACTION_ID, "10.00"))
                    .hasSameHashCodeAs(aRow("S", TRANSACTION_ID, "10.00"));
        }

        /**
         * A row amount of the wrong scale is refused rather than carried into a page.
         *
         * <p>An unequal pair would have been the weaker statement, and it is no longer available to make:
         * the row refuses a scale its record field does not store, so the two instances a scale comparison
         * needs cannot both exist. The refusal is the better control, because {@code BigDecimal} equality is
         * scale-sensitive while its comparison is not, so a wrong-scale row would have compared equal
         * wherever a total was checked and unequal wherever a row was.</p>
         */
        @Test
        @DisplayName("a row amount whose scale is not the record field's two decimal places is refused at "
                + "construction rather than carried into a page")
        void aRowAmountOfTheWrongScaleIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> aRow("S", TRANSACTION_ID, "10.0"))
                    .withMessageContaining("must carry scale " + TransactionListResponse.AMOUNT_SCALE)
                    .withMessageContaining("its scale is 1");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an amount wider than the field's nine integer digits is refused too")
                    .isThrownBy(() -> aRow("S", TRANSACTION_ID, "1000000000.05"))
                    .withMessageContaining("must fit " + TransactionListResponse.AMOUNT_INTEGER_DIGITS
                            + " integer digits");

            assertThat(new BigDecimal("10.00"))
                    .isEqualByComparingTo("10.0")
                    .isNotEqualTo(new BigDecimal("10.0"));
        }

        /**
         * The nested row withholds too, and it has to: a page carries ten of them.
         *
         * <p>The row is where the cardholder's activity actually sits - the transaction identifier, what was
         * bought and for how much - so a generated rendering here would disclose ten transactions per line
         * rather than one. The row therefore declares its own withholding rendering rather than relying on
         * the enclosing response's, because a row can be rendered on its own: it is an element of a list a
         * caller can iterate, and a control that only applied to the whole page would not cover that.</p>
         *
         * <p>The selection code and the displayed date survive. The code is one character drawn from a
         * two-value vocabulary and the date is the one already shown on a screen the operator is looking
         * at, so neither identifies anybody.</p>
         */
        @Test
        @DisplayName("the row rendering withholds the transaction identifier, the description and the "
                + "amount, because a page carries ten rows and each can be rendered on its own")
        void theRowRenderingWithholdsTheActivityComponents() {
            final String rendered = aRow("S", TRANSACTION_ID, "10.00").toString();

            assertThat(rendered)
                    .startsWith("TransactionRow[")
                    .contains("selection=S")
                    .contains("displayedDate=07/19/22")
                    .contains("transactionId=" + REDACTION_PLACEHOLDER)
                    .contains("description=" + REDACTION_PLACEHOLDER)
                    .contains("amount=" + REDACTION_PLACEHOLDER)
                    .doesNotContain(TRANSACTION_ID)
                    .doesNotContain("POS PURCHASE")
                    .doesNotContain("10.00");
        }

        @Test
        @DisplayName("the row withholding is unconditional, so an absent activity component renders as the "
                + "placeholder rather than betraying its absence")
        void theRowWithholdingIsUnconditional() {
            final TransactionListResponse.TransactionRow blank =
                    new TransactionListResponse.TransactionRow(null, null, null, null, null);

            assertThat(blank.toString())
                    .contains("transactionId=" + REDACTION_PLACEHOLDER)
                    .contains("amount=" + REDACTION_PLACEHOLDER)
                    .doesNotContain("transactionId=null");
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and its validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        @Test
        @DisplayName("the response declares fifteen components, the row block, the routing block, the "
                + "filter and page number, the message pair and the screen furniture")
        void theResponseDeclaresFifteenComponents() {
            final List<String> declared = Arrays.stream(
                    TransactionListResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("rows", "pageMetadata", "navigationContext",
                    "nextRoute", "transactionIdFilter", "displayedPageNumber", "message", "error",
                    "focusScreenFieldId", "title01", "title02", "currentDate", "currentTime",
                    "transactionName", "programName");
            assertThat(declared).hasSize(15);
        }

        @Test
        @DisplayName("exactly ten components carry a declared upper bound, and the five that do not "
                + "are the row list, the cursor, the conversation state, the route and the flag")
        void exactlyTenComponentsCarryAnUpperBound() {
            final List<String> bounded = Arrays.stream(
                    TransactionListResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> declaresAnUpperBound(TransactionListResponse.class, name))
                    .toList();

            assertThat(bounded).hasSize(10);
            assertThat(bounded).doesNotContain("rows", "pageMetadata", "navigationContext",
                    "nextRoute", "error");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final PageMetadata page = PageMetadata.forward(
                    PageMetadata.TRANSACTION_LIST_PAGE_SIZE, null, TRANSACTION_ID, true, false,
                    "00000001");
            final TransactionListResponse response = aResponse(
                    List.of(aRow("S", TRANSACTION_ID, "10.00")), page, NavigationContext.empty(),
                    TRANSACTION_ID, "00000001",
                    TransactionListResponse.MESSAGE_INVALID_SELECTION, true, "TRNIDIN");

            assertThat(response.rows()).hasSize(1);
            assertThat(response.pageMetadata()).isEqualTo(page);
            assertThat(response.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(response.nextRoute()).isEqualTo("/api/menu/user");
            assertThat(response.transactionIdFilter()).isEqualTo(TRANSACTION_ID);
            assertThat(response.displayedPageNumber()).isEqualTo("00000001");
            assertThat(response.message())
                    .isEqualTo(TransactionListResponse.MESSAGE_INVALID_SELECTION);
            assertThat(response.error()).isTrue();
            assertThat(response.focusScreenFieldId()).isEqualTo("TRNIDIN");
            assertThat(response.title01()).isEqualTo("CardDemo");
            assertThat(response.title02()).isEqualTo("List Transactions");
            assertThat(response.currentDate()).isEqualTo("07/19/22");
            assertThat(response.currentTime()).isEqualTo("10:30:00");
            assertThat(response.transactionName()).isEqualTo("CT00");
            assertThat(response.programName()).isEqualTo("COTRN00C");
        }

        @Test
        @DisplayName("a wholly absent response reports no violation, because every bound is an upper "
                + "bound and the row list defaults to empty")
        void aWhollyAbsentResponseReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aResponseWithRows(null))).isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({
            "transactionIdFilter,16", "displayedPageNumber,8", "message,78",
            "focusScreenFieldId,7", "title01,40", "title02,40",
            "currentDate,8", "currentTime,8", "transactionName,4",
            "programName,8",
        })
        @DisplayName("a bounded component accepts its declared width and rejects one character more")
        void aBoundedComponentAcceptsItsWidthAndRejectsOneMore(final String componentName,
                final int declaredMaximum) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, "X".repeat(declaredMaximum))))
                        .as("%s must accept %d characters", componentName, declaredMaximum)
                        .isEmpty();
                assertThat(factory.getValidator()
                        .validate(responseWith(componentName, "X".repeat(declaredMaximum + 1))))
                        .as("%s must reject %d characters", componentName, declaredMaximum + 1)
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString()).isEqualTo(componentName));
            }
        }

        @Test
        @DisplayName("the row list is not cascaded into, because the response declares no Valid marker "
                + "on it, so an over-long row surfaces only when the row is validated in its own right")
        void theRowListIsNotCascadedInto() {
            final TransactionListResponse.TransactionRow overLong =
                    new TransactionListResponse.TransactionRow("XX", null, null, null, null);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aResponseWithRows(List.of(overLong))))
                        .isEmpty();
                assertThat(factory.getValidator().validate(overLong)).hasSize(1);
            }
        }

        /**
         * Builds a response carrying a single named component and nothing else.
         *
         * @param componentName the component to populate
         * @param value the value to place in it
         * @return a response carrying only that component
         */
        private TransactionListResponse responseWith(final String componentName,
                final String value) {
            return switch (componentName) {
                case "transactionIdFilter" -> new TransactionListResponse(null, null, null, null,
                        value, null, null, false, null, null, null, null, null, null, null);
                case "displayedPageNumber" -> new TransactionListResponse(null, null, null, null,
                        null,
                        value, null, false, null, null, null, null, null, null, null);
                case "message" -> new TransactionListResponse(null, null, null, null, null, null,
                        value, false, null, null, null, null, null, null, null);
                case "focusScreenFieldId" -> new TransactionListResponse(null, null, null, null,
                        null,
                        null, null, false, value, null, null, null, null, null, null);
                case "title01" -> new TransactionListResponse(null, null, null, null, null,
                        null, null, false, null, value, null, null, null, null, null);
                case "title02" -> new TransactionListResponse(null, null, null, null, null,
                        null, null, false, null, null, value, null, null, null, null);
                case "currentDate" -> new TransactionListResponse(null, null, null, null, null,
                        null, null, false, null, null, null, value, null, null, null);
                case "currentTime" -> new TransactionListResponse(null, null, null, null, null,
                        null, null, false, null, null, null, null, value, null, null);
                case "transactionName" -> new TransactionListResponse(null, null, null, null, null,
                        null, null, false, null, null, null, null, null, value, null);
                case "programName" -> new TransactionListResponse(null, null, null, null, null,
                        null, null, false, null, null, null, null, null, null, value);
                default -> throw new IllegalArgumentException(
                        "no bounded component named " + componentName);
            };
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two responses built from identical values are equal and share a hash code")
        void twoResponsesBuiltFromIdenticalValuesAreEqual() {
            final TransactionListResponse first = aResponse(
                    List.of(aRow("S", TRANSACTION_ID, "10.00")), null, null, TRANSACTION_ID,
                    "00000001", null, false, null);
            final TransactionListResponse second = aResponse(
                    List.of(aRow("S", TRANSACTION_ID, "10.00")), null, null, TRANSACTION_ID,
                    "00000001", null, false, null);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("the rendering withholds the row block, the cursor and the identifier filter, and "
                + "publishes a row count in place of the rows it refuses to reproduce")
        void theRenderingWithholdsTheAccountBearingComponents() {
            final String rendered = aResponse(
                    List.of(aRow("S", TRANSACTION_ID, "10.00")),
                    PageMetadata.forward(PageMetadata.TRANSACTION_LIST_PAGE_SIZE, null,
                            TRANSACTION_ID, true, false, "00000001"),
                    NavigationContext.empty(), TRANSACTION_ID, "00000001",
                    TransactionListResponse.MESSAGE_AT_TOP, false, "TRNIDIN").toString();

            assertThat(rendered).startsWith("TransactionListResponse[");
            assertThat(rendered).contains("rowCount=1", "rows=" + REDACTION_PLACEHOLDER,
                    "pageMetadata=" + REDACTION_PLACEHOLDER,
                    "transactionIdFilter=" + REDACTION_PLACEHOLDER);
            assertThat(rendered)
                    .as("the identifier the browse started from is a cardholder-bearing value")
                    .doesNotContain(TRANSACTION_ID);
        }

        @Test
        @DisplayName("the rendering still names the screen furniture and the message pair, so a "
                + "diagnostic remains readable without reproducing a transaction identifier")
        void theRenderingStillNamesTheScreenFurniture() {
            final String rendered = aResponse(List.of(), null, null, null, "00000001",
                    TransactionListResponse.MESSAGE_AT_TOP, false, "TRNIDIN").toString();

            assertThat(rendered).contains("rowCount=0", "displayedPageNumber=00000001",
                    "message=" + TransactionListResponse.MESSAGE_AT_TOP, "error=false",
                    "focusScreenFieldId=TRNIDIN", "transactionName=CT00", "programName=COTRN00C");
        }

        @Test
        @DisplayName("the withholding is unconditional, so an absent row block and an absent cursor "
                + "render as the same placeholder a populated one does")
        void theWithholdingIsUnconditional() {
            final String rendered = aResponseWithRows(null).toString();

            assertThat(rendered).contains("rowCount=0", "rows=" + REDACTION_PLACEHOLDER,
                    "pageMetadata=" + REDACTION_PLACEHOLDER,
                    "transactionIdFilter=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("the payload always carries the row list and the error flag, and omits every "
                + "absent component")
        void thePayloadAlwaysCarriesTheRowListAndTheFlag() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aResponseWithRows(null));

            assertThat(payload.has("rows")).isTrue();
            assertThat(payload.get("rows").isArray()).isTrue();
            assertThat(payload.get("rows")).isEmpty();
            assertThat(payload.has("error")).isTrue();
            assertThat(payload.get("error").asBoolean()).isFalse();
            assertThat(payload.has("message")).isFalse();
            assertThat(payload.has("pageMetadata")).isFalse();
            assertThat(payload.has("navigationContext")).isFalse();
        }

        @Test
        @DisplayName("a row amount renders in plain notation with its scale intact, read from the wire "
                + "characters rather than from a parsed tree")
        void aRowAmountRendersInPlainNotationWithItsScale() throws JsonProcessingException {
            final String wire = wirePayloadOf(aResponseWithRows(
                    List.of(aRow("S", TRANSACTION_ID, "999999999.05"))));

            assertThat(wire).contains("\"amount\":999999999.05");
            assertThat(wire).doesNotContain("E9").doesNotContain("E+");
        }

        @Test
        @DisplayName("a trailing-zero cent survives the wire as two digits")
        void aTrailingZeroCentSurvivesTheWire() throws JsonProcessingException {
            assertThat(wirePayloadOf(aResponseWithRows(List.of(aRow("S", TRANSACTION_ID, "42.10")))))
                    .contains("\"amount\":42.10");
        }

        @Test
        @DisplayName("rows travel on the wire in presentation order, so a client renders the page the "
                + "legacy screen would have drawn")
        void rowsTravelInPresentationOrder() throws JsonProcessingException {
            final JsonNode rows = payloadOf(aResponseWithRows(List.of(
                    aRow(" ", "0000000000000001", "1.00"),
                    aRow(" ", "0000000000000002", "2.00"),
                    aRow(" ", "0000000000000003", "3.00")))).get("rows");

            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).get("transactionId").asText()).isEqualTo("0000000000000001");
            assertThat(rows.get(2).get("transactionId").asText()).isEqualTo("0000000000000003");
        }

        @Test
        @DisplayName("a response survives a round trip through the module-equivalent mapper unchanged, "
                + "row amounts and paging cursor included")
        void aResponseSurvivesARoundTrip() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final TransactionListResponse original = aResponse(
                    List.of(aRow("S", TRANSACTION_ID, "1234.56")),
                    PageMetadata.forward(PageMetadata.TRANSACTION_LIST_PAGE_SIZE, null,
                            TRANSACTION_ID, true, false, "00000001"),
                    NavigationContext.empty(), TRANSACTION_ID, "00000001",
                    TransactionListResponse.MESSAGE_REACHED_BOTTOM, false, "TRNIDIN");
            final TransactionListResponse restored = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionListResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.rows().getFirst().amount().scale())
                    .isEqualTo(TransactionListResponse.AMOUNT_SCALE);
        }
    }
}
