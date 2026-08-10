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

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
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
 * Unit tests for {@link TransactionListRequest}, the request body of legacy transaction {@code CT00}
 * implemented by {@code app/cbl/COTRN00C.cbl} over screen {@code app/cpy-bms/COTRN00.CPY}.
 *
 * <p><strong>Provenance.</strong> Read from the mainframe estate at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 *
 * <p><strong>Ten rows, established by loop bounds rather than by an OCCURS clause.</strong> Unlike
 * the card list, the transaction list screen declares no table. Its page size is fixed by the two
 * loop bounds {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} at
 * {@code app/cbl/COTRN00C.cbl:290} and {@code PERFORM UNTIL WS-IDX >= 11} at {@code :297}. The Java
 * page size therefore lives on {@link PageMetadata#TRANSACTION_LIST_PAGE_SIZE} rather than on this
 * request, and the row selectors arrive as a list rather than as ten numbered components.
 *
 * <p><strong>The selector list is normalised, copied and element-bounded.</strong> The compact
 * constructor substitutes an empty list for an absent one and takes a defensive copy of a supplied
 * one, so a caller cannot mutate a request after handing it over.
 *
 * <p><strong>The selector list carries two independent bounds, at two different levels.</strong> The
 * accessor carries {@code Size(max = ROW_SELECTOR_COUNT)}, which measures how many rows may arrive at
 * all - ten, the page the screen fills - and the list's type argument carries
 * {@code Size(max = ROW_SELECTOR_LENGTH)}, which measures each entry and is a Bean Validation
 * container element constraint, validated automatically for a supported container and reported against
 * a path that names the offending index. Both are asserted here, at both levels, because a reader who
 * finds only one of them would conclude the other is not enforced.
 *
 * <p><strong>The page number is written outbound and ignored inbound.</strong> The server computes
 * which page the operator is looking at, so the component is bound {@code READ_ONLY}: it is rendered
 * into a reply and discarded from a submission. A round trip therefore cannot return an equal
 * instance, and what is asserted instead is that the loss is precisely that one component.
 *
 * <p><strong>The rendering withholds the two components that name a transaction.</strong> The record
 * declares a {@code toString} of its own that replaces the identifier filter and the paging cursor
 * with a fixed placeholder, because a boundary cursor key on this browse <em>is</em> a transaction
 * identifier. The remaining four components are screen-flow state a diagnostic genuinely reads and are
 * emitted in the clear.
 */
@DisplayName("TransactionListRequest - the CT00 transaction-list screen contract")
class TransactionListRequestRuleComplianceTest {

    /** A representative sixteen-digit transaction identifier used as a starting filter. */
    private static final String TRANSACTION_ID = "0000000000000042";

    /**
     * The fixed stand-in the record's own rendering emits in place of each withheld component.
     *
     * <p>Restated here rather than read from the record, because the production constant is private on
     * purpose: it is a rendering detail and not part of the request contract. Restating it means this
     * test would fail if the production placeholder were changed to something recoverable, which is
     * exactly the failure a reader wants to see.
     */
    private static final String REDACTION_PLACEHOLDER = "***REDACTED***";

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
     * Serializes a request and reads the result back as a tree.
     *
     * @param request the request to render
     * @return the rendered payload
     * @throws JsonProcessingException when the payload cannot be produced
     */
    private static JsonNode payloadOf(final TransactionListRequest request)
            throws JsonProcessingException {
        final ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    /**
     * Builds a request positionally over the record's six components.
     *
     * @param transactionIdFilter the identifier the browse starts from
     * @param displayedPageNumber the page number the server computed, echoed back read-only
     * @param rowSelectors the per-row selection characters
     * @param keyAction the attention key the operator pressed
     * @param navigationContext the carried conversation state
     * @param pageMetadata the paging cursor and direction
     * @return a request carrying exactly those values
     */
    private static TransactionListRequest aRequest(
            final String transactionIdFilter, final String displayedPageNumber,
            final List<String> rowSelectors, final KeyAction keyAction,
            final NavigationContext navigationContext,
            final PageMetadata.PageCursorRequest pageMetadata) {
        return new TransactionListRequest(transactionIdFilter, displayedPageNumber, rowSelectors,
                keyAction, navigationContext, pageMetadata, null);
    }

    /**
     * Builds a request carrying only a selector list.
     *
     * @param rowSelectors the per-row selection characters
     * @return a request carrying only that list
     */
    private static TransactionListRequest aRequestWithSelectors(final List<String> rowSelectors) {
        return aRequest(null, null, rowSelectors, null, null, null);
    }

    /**
     * Reports whether a named component's accessor carries a declared upper bound.
     *
     * <p>The annotation is read from the accessor rather than from the record component, because
     * {@code jakarta.validation.constraints.Size} does not target {@code RECORD_COMPONENT}. A
     * constraint written on a record component is propagated to the backing field, the accessor and
     * the canonical constructor parameter instead of being retained on the component itself, so
     * {@code RecordComponent.getAnnotation} would report nothing for every component.
     *
     * @param componentName the record component to test
     * @return {@code true} when the accessor declares a {@link Size} bound
     */
    private static boolean declaresAnUpperBound(final String componentName) {
        try {
            return TransactionListRequest.class.getDeclaredMethod(componentName)
                    .getAnnotation(Size.class) != null;
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("no accessor declared for " + componentName, cause);
        }
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
        final Size size = TransactionListRequest.class.getDeclaredMethod(componentName)
                .getAnnotation(Size.class);
        assertThat(size).as("component %s declares no upper bound", componentName).isNotNull();
        return size.max();
    }

    // =============================================================================================

    @Nested
    @DisplayName("the published field widths")
    class ThePublishedFieldWidths {

        @Test
        @DisplayName("the identifier filter is sixteen characters, matching TRAN-ID PIC X(16) in the "
                + "transaction record rather than any screen rendering of it")
        void theIdentifierFilterIsSixteenCharacters() {
            assertThat(TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH).isEqualTo(16);
        }

        @Test
        @DisplayName("the displayed page number is eight characters, the same width the shared paging "
                + "metadata publishes for a displayed page number")
        void theDisplayedPageNumberIsEightCharacters() {
            assertThat(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH).isEqualTo(8)
                    .isEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH);
        }

        @Test
        @DisplayName("a row selector is a single character, so no row can carry two actions at once")
        void aRowSelectorIsASingleCharacter() {
            assertThat(TransactionListRequest.ROW_SELECTOR_LENGTH).isOne();
        }

        @Test
        @DisplayName("the selector count equals the row count, because one selector belongs to each "
                + "rendered row and the two constants are separately declared")
        void theSelectorCountEqualsTheRowCount() {
            assertThat(TransactionListRequest.ROW_SELECTOR_COUNT)
                    .isEqualTo(TransactionListRequest.ROW_COUNT)
                    .isEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .isEqualTo(10);
            assertThat(TransactionListRequest.ROW_SELECTOR_COUNT)
                    .as("the count of rows and the width of one selector are different measurements")
                    .isNotEqualTo(TransactionListRequest.ROW_SELECTOR_LENGTH);
        }

        @ParameterizedTest
        @CsvSource({"transactionIdFilter,16", "rowSelectors,10"})
        @DisplayName("every accessor-bounded component declares the width its named constant "
                + "publishes")
        void everyAccessorBoundedComponentDeclaresItsPublishedWidth(final String componentName,
                final int expectedMaximum) throws NoSuchMethodException {
            assertThat(declaredMaximumLength(componentName)).isEqualTo(expectedMaximum);
        }

        @Test
        @DisplayName("the page size this screen fills is ten, and it lives on the shared paging "
                + "metadata because the legacy screen establishes it by loop bound and not by table")
        void thePageSizeIsTenAndLivesOnThePagingMetadata() {
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE).isEqualTo(10);
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .isNotEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the compact constructor's handling of the selector list")
    class TheCompactConstructorsSelectorHandling {

        @Test
        @DisplayName("an absent selector list becomes an empty list, so a caller never has to guard "
                + "against a null before iterating the rows")
        void anAbsentSelectorListBecomesAnEmptyList() {
            assertThat(aRequestWithSelectors(null).rowSelectors()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("a supplied selector list is copied, so mutating the caller's list afterwards "
                + "cannot change the request")
        void aSuppliedSelectorListIsCopied() {
            final List<String> mutable = new ArrayList<>(List.of("S", "S"));
            final TransactionListRequest request = aRequestWithSelectors(mutable);

            mutable.clear();
            mutable.add("X");

            assertThat(request.rowSelectors()).containsExactly("S", "S");
        }

        @Test
        @DisplayName("the copy is unmodifiable, so a holder of the request cannot rewrite a selection "
                + "after validation has run over it")
        void theCopyIsUnmodifiable() {
            final List<String> selectors = aRequestWithSelectors(List.of("S")).rowSelectors();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> selectors.set(0, "U"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> selectors.add("U"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(selectors::clear);
        }

        @Test
        @DisplayName("a null element is refused outright, because an unselected row is carried as a "
                + "blank rather than as an absent entry")
        void aNullElementIsRefusedOutright() {
            final List<String> withNull = Arrays.asList("S", null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> aRequestWithSelectors(withNull));
        }

        @Test
        @DisplayName("an empty selector list is preserved as empty rather than being confused with an "
                + "absent one, because both mean no row was selected")
        void anEmptySelectorListIsPreserved() {
            assertThat(aRequestWithSelectors(List.of()).rowSelectors()).isEmpty();
            assertThat(aRequestWithSelectors(List.of())).isEqualTo(aRequestWithSelectors(null));
        }

        @Test
        @DisplayName("a full page of ten selectors is carried in the order the rows were rendered, "
                + "because the legacy program reports an invalid selection against its own row")
        void aFullPageOfTenSelectorsKeepsRowOrder() {
            final List<String> tenRows = List.of(" ", " ", "S", " ", " ", " ", " ", " ", " ", " ");

            assertThat(aRequestWithSelectors(tenRows).rowSelectors())
                    .hasSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .containsExactlyElementsOf(tenRows);
            assertThat(aRequestWithSelectors(tenRows).rowSelectors().indexOf("S")).isEqualTo(2);
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the container element constraint on the selector list")
    class TheContainerElementConstraint {

        /**
         * Two bounds at two levels, not one bound in one place.
         *
         * <p>The accessor bound and the element bound measure different things and are declared in
         * different places. The accessor carries {@code Size(max = ROW_SELECTOR_COUNT)} and limits how
         * many rows may arrive; the list's type argument carries
         * {@code Size(max = ROW_SELECTOR_LENGTH)} and limits each entry to one character. A reader who
         * finds the accessor annotation and stops would conclude the entries are unbounded, and a
         * reader who finds the element annotation and stops would conclude the list length is
         * unbounded. Both are asserted, and their maxima are asserted to differ, so neither can be
         * silently retargeted onto the other.</p>
         */
        @Test
        @DisplayName("the list carries a container bound on its accessor and a separate element bound "
                + "on its type argument, and the two measure different things")
        void theListCarriesBothAContainerBoundAndAnElementBound() throws NoSuchMethodException {
            assertThat(declaresAnUpperBound("rowSelectors")).isTrue();
            assertThat(declaredMaximumLength("rowSelectors"))
                    .as("the accessor bound counts rows")
                    .isEqualTo(TransactionListRequest.ROW_SELECTOR_COUNT);
            assertThat(declaredMaximumLength("rowSelectors"))
                    .as("and it is not the one-character element bound")
                    .isNotEqualTo(TransactionListRequest.ROW_SELECTOR_LENGTH);
            assertThat(declaresAnUpperBound("transactionIdFilter")).isTrue();
        }

        /**
         * The container bound is enforced at construction, so the annotation never gets to report it.
         *
         * <p>The bound is declared twice on purpose, and the two declarations do different work. The
         * annotation is the published contract: it is what the generated interface description shows a
         * client, and what the reflective census reads. The compact constructor is the enforcement: it
         * refuses an over-long list before an instance exists, which is why a validator run can never
         * produce a violation for it - there is nothing to hand the validator.</p>
         *
         * <p>Enforcing it at construction rather than leaving it to the validator is the stronger of the
         * two, because a request assembled in code rather than bound from a payload never reaches a
         * validator at all, and an eleventh row is a caller defect wherever it comes from. The refusal is
         * asserted here, and the declaration is asserted separately by the census, so removing either one
         * fails a test.</p>
         */
        @Test
        @DisplayName("a list longer than the page it fills is refused at construction rather than reported "
                + "as a violation, because no instance carrying an eleventh row is ever created")
        void aListLongerThanThePageIsRefusedAtConstruction() {
            final List<String> elevenRows = List.of(" ", " ", " ", " ", " ", " ", " ", " ", " ", " ",
                    "S");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> aRequestWithSelectors(elevenRows))
                    .withMessageContaining("rowSelectors may hold at most "
                            + TransactionListRequest.ROW_SELECTOR_COUNT)
                    .withMessageContaining("but it holds 11");
        }

        @Test
        @DisplayName("a full page of ten selectors is accepted, so the refusal above costs the screen "
                + "nothing it legitimately renders")
        void aFullPageOfSelectorsIsAccepted() {
            final List<String> tenRows = List.of(" ", " ", " ", " ", " ", " ", " ", " ", " ", "S");

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequestWithSelectors(tenRows))).isEmpty();
            }
            assertThat(aRequestWithSelectors(tenRows).rowSelectors())
                    .hasSize(TransactionListRequest.ROW_SELECTOR_COUNT);
        }

        @Test
        @DisplayName("a one-character selector is accepted, so a list of legitimate selections passes "
                + "validation untouched")
        void aOneCharacterSelectorIsAccepted() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequestWithSelectors(List.of("S", " ", "S")))).isEmpty();
            }
        }

        @Test
        @DisplayName("a two-character selector is reported against the offending index, so the screen "
                + "can mark the row that carries it")
        void aTwoCharacterSelectorIsReportedAgainstItsIndex() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequestWithSelectors(List.of(" ", "SS"))))
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString())
                                .startsWith("rowSelectors[1]"));
            }
        }

        @Test
        @DisplayName("two offending rows are reported as two violations, each naming its own index")
        void twoOffendingRowsAreReportedSeparately() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequestWithSelectors(List.of("SU", " ", "US"))))
                        .hasSize(2)
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .allSatisfy(path -> assertThat(path).startsWith("rowSelectors["));
            }
        }

        @Test
        @DisplayName("an empty selector is accepted, because the bound is an upper bound and a blank "
                + "row is the normal unselected state")
        void anEmptySelectorIsAccepted() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(aRequestWithSelectors(List.of("", ""))))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the bound admits any single character, because the legacy program tests the "
                + "value against S and reports its own invalid-selection message")
        void theBoundAdmitsAnySingleCharacter() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequestWithSelectors(List.of("S", "U", "X", "1", "*"))))
                        .isEmpty();
            }
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("the declared shape and its validation bounds")
    class TheDeclaredShapeAndValidationBounds {

        @Test
        @DisplayName("the request declares seven components, the identifier filter, page label, "
                + "selector list, attention key, conversation state, paging state and the sealed "
                + "snapshot of the page being continued")
        void theRequestDeclaresSevenComponents() {
            final List<String> declared = Arrays.stream(
                    TransactionListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            assertThat(declared).containsExactly("transactionIdFilter", "displayedPageNumber",
                    "rowSelectors", "keyAction", "navigationContext", "pageMetadata",
                    "rowSnapshotToken");
            assertThat(declared).hasSize(7);
        }

        @Test
        @DisplayName("exactly three top-level components carry an accessor-level bound")
        void exactlyThreeComponentsCarryAnAccessorLevelBound() {
            final List<String> bounded = Arrays.stream(
                    TransactionListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(TransactionListRequestRuleComplianceTest::declaresAnUpperBound).toList();

            assertThat(bounded).containsExactly("transactionIdFilter", "displayedPageNumber",
                    "rowSelectors");
            assertThat(bounded)
                    .as("the attention key, the conversation state and the paging state carry no width")
                    .doesNotContain("keyAction", "navigationContext", "pageMetadata");
        }

        @Test
        @DisplayName("every component round-trips through its own accessor unchanged")
        void everyComponentRoundTripsThroughItsAccessor() {
            final PageMetadata.PageCursorRequest cursor = new PageMetadata.PageCursorRequest(
                    TRANSACTION_ID, null, PageMetadata.PagingDirection.FORWARD, null, false);
            final TransactionListRequest request = aRequest(TRANSACTION_ID, "00000001",
                    List.of("S"), KeyAction.PFK08, NavigationContext.empty(), cursor);

            assertThat(request.transactionIdFilter()).isEqualTo(TRANSACTION_ID);
            assertThat(request.displayedPageNumber()).isEqualTo("00000001");
            assertThat(request.rowSelectors()).containsExactly("S");
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(request.navigationContext()).isEqualTo(NavigationContext.empty());
            // The paging carrier is answered exactly as supplied. It carries its own retained page
            // number, which is the figure a turn takes its page decisions from; the displayed label
            // above is a separate value the screen renders and no decision reads.
            assertThat(request.pageMetadata()).isEqualTo(cursor);
            assertThat(request.pageMetadata().retainedPageNumber())
                    .as("this cursor carried no retained figure, which is the first-entry shape")
                    .isZero();
        }

        @Test
        @DisplayName("a wholly absent request reports no violation, because every bound is an upper "
                + "bound and the selector list defaults to empty")
        void aWhollyAbsentRequestReportsNoViolation() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequest(null, null, null, null, null, null))).isEmpty();
            }
        }

        @ParameterizedTest
        @CsvSource({"transactionIdFilter,16", "displayedPageNumber,8"})
        @DisplayName("a bounded component accepts its declared width and rejects one character more")
        void aBoundedComponentAcceptsItsWidthAndRejectsOneMore(final String componentName,
                final int declaredMaximum) {
            final String atBound = "9".repeat(declaredMaximum);
            final String pastBound = "9".repeat(declaredMaximum + 1);
            final boolean isFilter = "transactionIdFilter".equals(componentName);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator().validate(isFilter
                        ? aRequest(atBound, null, null, null, null, null)
                        : aRequest(null, atBound, null, null, null, null)))
                        .as("%s must accept %d characters", componentName, declaredMaximum)
                        .isEmpty();
                assertThat(factory.getValidator().validate(isFilter
                        ? aRequest(pastBound, null, null, null, null, null)
                        : aRequest(null, pastBound, null, null, null, null)))
                        .as("%s must reject %d characters", componentName, declaredMaximum + 1)
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString())
                                .isEqualTo(componentName));
            }
        }

        /**
         * Both nested components are cascaded into, and the proof is the reported path.
         *
         * <p>The request declares {@code Valid} on the paging cursor and on the conversation state, so
         * a violation inside either surfaces when the request is validated rather than only when the
         * nested object is validated on its own. The observable proof is the property path: it names
         * the outer component and then the inner one, which is what lets a screen mark the field that
         * carries the offending value.</p>
         */
        @Test
        @DisplayName("the paging cursor is cascaded into, so an over-width boundary key is reported "
                + "against a path that names the cursor component and then the key")
        void thePagingCursorIsCascadedInto() {
            final PageMetadata.PageCursorRequest overWidthCursor =
                    new PageMetadata.PageCursorRequest(
                            "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1), null,
                            PageMetadata.PagingDirection.FORWARD, null, false);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequest(null, null, null, null, null, overWidthCursor)))
                        .hasSize(1)
                        .allSatisfy(violation -> assertThat(
                                violation.getPropertyPath().toString())
                                .isEqualTo("pageMetadata.previousCursorKey"));
            }
        }

        @Test
        @DisplayName("a cursor at the published key bound passes the cascade, because the bound is an "
                + "upper bound and the widest legitimate key is a record key")
        void aCursorAtThePublishedKeyBoundPassesTheCascade() {
            final PageMetadata.PageCursorRequest atBound = new PageMetadata.PageCursorRequest(
                    "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH),
                    "Y".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH),
                    PageMetadata.PagingDirection.BACKWARD, null, false);

            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                assertThat(factory.getValidator()
                        .validate(aRequest(null, null, null, null, null, atBound))).isEmpty();
            }
        }

        @Test
        @DisplayName("both nested components declare the cascade, so neither is validated only in its "
                + "own right")
        void bothNestedComponentsDeclareTheCascade() throws NoSuchMethodException {
            assertThat(TransactionListRequest.class.getDeclaredMethod("pageMetadata")
                    .getAnnotation(Valid.class)).isNotNull();
            assertThat(TransactionListRequest.class.getDeclaredMethod("navigationContext")
                    .getAnnotation(Valid.class)).isNotNull();
        }
    }

    // =============================================================================================

    @Nested
    @DisplayName("value semantics and the published payload")
    class ValueSemanticsAndThePublishedPayload {

        @Test
        @DisplayName("two requests built from identical values are equal and share a hash code")
        void twoRequestsBuiltFromIdenticalValuesAreEqual() {
            final TransactionListRequest first = aRequest(TRANSACTION_ID, "00000001", List.of("S"),
                    KeyAction.ENTER, NavigationContext.empty(), null);
            final TransactionListRequest second = aRequest(TRANSACTION_ID, "00000001", List.of("S"),
                    KeyAction.ENTER, NavigationContext.empty(), null);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a difference in selector order makes two requests unequal, because the selector "
                + "position identifies the row it belongs to")
        void aDifferenceInSelectorOrderMakesTwoRequestsUnequal() {
            assertThat(aRequestWithSelectors(List.of("S", " ")))
                    .isNotEqualTo(aRequestWithSelectors(List.of(" ", "S")));
        }

        /**
         * The rendering withholds the two components that name a transaction.
         *
         * <p>The identifier filter is a transaction identifier, and a boundary cursor key on this
         * browse is a transaction identifier too, so both are replaced by a fixed placeholder rather
         * than by any transformation of the value: nothing about a withheld component - not its
         * length, not a prefix, not a digest - can be recovered from a stringified instance. The other
         * four components are screen-flow state a diagnostic genuinely reads and stay in the
         * clear.</p>
         */
        @Test
        @DisplayName("the rendering withholds the identifier filter and the paging cursor, because a "
                + "boundary cursor key on this browse is itself a transaction identifier")
        void theRenderingWithholdsTheTransactionBearingComponents() {
            final String rendered = aRequest(TRANSACTION_ID, "00000001", List.of("S"),
                    KeyAction.PFK07, null,
                    new PageMetadata.PageCursorRequest(TRANSACTION_ID, null,
                            PageMetadata.PagingDirection.FORWARD, null, false)).toString();

            assertThat(rendered).startsWith("TransactionListRequest[");
            assertThat(rendered).contains("transactionIdFilter=" + REDACTION_PLACEHOLDER,
                    "pageMetadata=" + REDACTION_PLACEHOLDER);
            assertThat(rendered)
                    .as("no fragment of the withheld identifier survives anywhere in the rendering")
                    .doesNotContain(TRANSACTION_ID);
        }

        @Test
        @DisplayName("the rendering still names the four components a diagnostic reads, so the "
                + "withholding is targeted rather than blanket")
        void theRenderingStillNamesTheScreenFlowState() {
            final String rendered = aRequest(TRANSACTION_ID, "00000001", List.of("S"),
                    KeyAction.PFK07, null, null).toString();

            assertThat(rendered).contains("displayedPageNumber=00000001", "rowSelectors=[S]",
                    "keyAction=PFK07", "navigationContext=null",
                    "pageMetadata=" + REDACTION_PLACEHOLDER);
        }

        @Test
        @DisplayName("the withholding is unconditional, so an absent filter and an absent cursor are "
                + "rendered as the placeholder too and absence is not distinguishable from presence")
        void theWithholdingIsUnconditional() {
            final String rendered = aRequest(null, null, null, null, null, null).toString();

            assertThat(rendered).contains("transactionIdFilter=" + REDACTION_PLACEHOLDER,
                    "pageMetadata=" + REDACTION_PLACEHOLDER);
            assertThat(rendered).doesNotContain("transactionIdFilter=null", "pageMetadata=null");
        }

        @Test
        @DisplayName("the payload always carries the selector list, because the compact constructor "
                + "has already replaced an absent list with an empty one")
        void thePayloadAlwaysCarriesTheSelectorList() throws JsonProcessingException {
            final JsonNode payload = payloadOf(aRequest(null, null, null, null, null, null));

            assertThat(payload.has("rowSelectors")).isTrue();
            assertThat(payload.get("rowSelectors").isArray()).isTrue();
            assertThat(payload.get("rowSelectors")).isEmpty();
            assertThat(payload.has("transactionIdFilter")).isFalse();
            assertThat(payload.has("keyAction")).isFalse();
        }

        @Test
        @DisplayName("a selector list travels on the wire in row order")
        void aSelectorListTravelsInRowOrder() throws JsonProcessingException {
            final JsonNode selectors = payloadOf(aRequestWithSelectors(List.of("A", "B", "C")))
                    .get("rowSelectors");

            assertThat(selectors).hasSize(3);
            assertThat(selectors.get(0).asText()).isEqualTo("A");
            assertThat(selectors.get(1).asText()).isEqualTo("B");
            assertThat(selectors.get(2).asText()).isEqualTo("C");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ENTER", "PFK03", "PFK07", "PFK08"})
        @DisplayName("the attention keys this screen uses travel as their enum names, so a "
                + "trailing-space 3270 attention identifier never reaches a client")
        void theAttentionKeysTravelAsEnumNames(final String keyName)
                throws JsonProcessingException {
            final KeyAction keyAction = KeyAction.valueOf(keyName);

            assertThat(payloadOf(aRequest(null, null, null, keyAction, null, null))
                    .get("keyAction").asText()).isEqualTo(keyName);
        }

        /**
         * The round trip preserves everything except the read-only page number.
         *
         * <p>One component is bound in one direction only. The page number is written outbound,
         * because a reply has to be able to tell a client which page it is looking at, and ignored
         * inbound, because the server computes it and a submitted value could only be a client
         * asserting a page it was not given. Equality across a round trip therefore cannot hold, and
         * asserting that it does would require reopening the inbound direction.</p>
         *
         * <p>What is asserted instead is that the loss is precisely that one component and that
         * everything else survives, selector order and paging direction included. That is stronger
         * than equality would have been, because it names what may change and would fail if a second
         * component silently acquired a directional binding.</p>
         */
        @Test
        @DisplayName("a request round trips with everything but its publish-only page label preserved")
        void aRequestRoundTripsWithoutItsReadOnlyPageNumber() throws JsonProcessingException {
            final ObjectMapper mapper = moduleEquivalentMapper();
            final PageMetadata.PageCursorRequest carriedPaging =
                    new PageMetadata.PageCursorRequest(TRANSACTION_ID, null,
                            PageMetadata.PagingDirection.BACKWARD, "00000002", true);
            final TransactionListRequest original = aRequest(TRANSACTION_ID, "00000002",
                    List.of(" ", "S"), KeyAction.PFK08, NavigationContext.empty().withReEntry(),
                    carriedPaging);

            final TransactionListRequest returned = mapper.readValue(
                    mapper.writeValueAsString(original), TransactionListRequest.class);

            assertThat(returned.displayedPageNumber()).isNull();
            assertThat(returned.transactionIdFilter()).isEqualTo(TRANSACTION_ID);
            assertThat(returned.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(returned.navigationContext())
                    .isEqualTo(NavigationContext.empty().withReEntry());
            assertThat(returned.pageMetadata())
                    .as("the retained figure and the direction are what a turn acts on, and both survive")
                    .isEqualTo(carriedPaging);
            assertThat(returned.rowSelectors()).containsExactly(" ", "S");
        }
    }
}
