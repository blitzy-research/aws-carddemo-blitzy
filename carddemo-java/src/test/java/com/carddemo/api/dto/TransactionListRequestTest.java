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
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link TransactionListRequest}, the request body of legacy transaction {@code CT00}.
 *
 * <p>A pure unit test: no application context, no connection, no container.
 *
 * <p>Three properties are under test, and each corrects a specific way the earlier contract let a
 * client submit state the legacy program would never have honoured.
 *
 * <p>The inbound paging shape is narrower than the outbound one. A client resuming a browse needs only
 * the two boundary keys and a direction; the page size, the availability flags and the page indicator
 * are all computed server-side. Accepting the full outbound shape would have let a client claim there
 * were further pages when there were none.
 *
 * <p>The page indicator is echoed, not accepted. {@code PAGENUMI} is touched at exactly two sites in
 * {@code app/cbl/COTRN00C.cbl} - line 324 and line 373 - and both are
 * {@code MOVE CDEMO-CT00-PAGE-NUM TO PAGENUMI}. Both are writes. The program never reads the item
 * back, because the authoritative figure lives in the communication area. Marking the component
 * non-bindable is therefore not a restriction added to the legacy behaviour; it is that behaviour.
 *
 * <p>The row count is bounded at ten. The screen declares ten row families, and the program's fill
 * loops run to that bound at lines 290 and 297.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("TransactionListRequest :: transaction-list request contract of legacy transaction CT00")
class TransactionListRequestTest {

    /** The six record components in declaration order. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "transactionIdFilter", "displayedPageNumber", "rowSelectors", "keyAction",
            "navigationContext", "pageMetadata");

    private static final String TRANSACTION_ID_FILTER = "0000000000000042";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String ACCOUNT_ID = "00000000011";

    private static NavigationContext navigation() {
        return new NavigationContext("CT00", "COTRN00C", "CT00", "COTRN00C", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN0A", "COTRN00");
    }

    private static PageMetadata.PageCursorRequest cursor() {
        return new PageMetadata.PageCursorRequest("0000000000000031", "0000000000000041",
                PageMetadata.PagingDirection.FORWARD);
    }

    private static TransactionListRequest populated() {
        return new TransactionListRequest(TRANSACTION_ID_FILTER, "00000003",
                List.of("", "", "S", "", "", "", "", "", "", ""), KeyAction.PFK08, navigation(),
                cursor());
    }

    private static TransactionListRequest withSelectors(List<String> selectors) {
        return new TransactionListRequest(null, null, selectors, KeyAction.ENTER, null, null);
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
        return Arrays.stream(TransactionListRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotation a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component: none of the annotations
     * involved declares {@code RECORD_COMPONENT} among its targets, so asking the component yields
     * nothing and an assertion phrased that way would pass without testing anything.
     *
     * @param <A> the annotation type
     * @param name the component name
     * @param type the annotation type to read
     * @return the annotation, or {@code null} when absent
     */
    private static <A extends java.lang.annotation.Annotation> A annotationOn(String name,
            Class<A> type) {
        try {
            return TransactionListRequest.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static Set<ConstraintViolation<TransactionListRequest>> violationsOf(
            TransactionListRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the six components in declaration order")
        void declaresSixComponents() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_ORDER)
                    .hasSize(6);
        }

        @Test
        @DisplayName("carries the canonical paging vocabulary and none of the superseded spellings")
        void carriesTheCanonicalPagingVocabulary() {
            assertThat(componentNames())
                    .contains("displayedPageNumber", "pageMetadata", "navigationContext")
                    .doesNotContain("pageIndicator", "pageNumber", "page", "navigation");
        }

        @Test
        @DisplayName("declares its widths from the map items it echoes")
        void declaresItsWidthsFromTheMap() {
            assertThat(TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH)
                    .as("TRNIDINI PIC X(16)")
                    .isEqualTo(16);
            assertThat(TransactionListRequest.ROW_SELECTOR_LENGTH)
                    .as("one selection character per row family")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("declares the page-indicator width as eight, which is not the card-list width")
        void declaresThePageIndicatorWidthAsEight() {
            assertThat(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("PAGENUM of COTRN00 is PIC 9(08); the card-list PAGENO is X(3) and the two"
                            + " widths must never be unified")
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("declares the row count as the ten row families the screen carries")
        void declaresTheRowCountAsTen() {
            assertThat(TransactionListRequest.ROW_COUNT)
                    .as("the fill loops at COTRN00C lines 290 and 297 run to this bound")
                    .isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("the inbound paging shape is narrower than the outbound one")
    class InboundPagingShapeIsNarrower {

        @Test
        @DisplayName("accepts only the cursor request, never the full outbound metadata")
        void acceptsOnlyTheCursorRequest() throws NoSuchFieldException {
            assertThat(TransactionListRequest.class.getDeclaredField("pageMetadata").getType())
                    .isEqualTo(PageMetadata.PageCursorRequest.class);
            assertThat(Arrays.stream(TransactionListRequest.class.getRecordComponents())
                    .map(RecordComponent::getType))
                    .as("the outbound shape carries page size and availability flags a client"
                            + " must not be able to assert")
                    .doesNotContain(PageMetadata.class);
        }

        @Test
        @DisplayName("carries the two boundary keys and a direction, and nothing else")
        void carriesOnlyKeysAndDirection() {
            assertThat(Arrays.stream(PageMetadata.PageCursorRequest.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactly("previousCursorKey", "nextCursorKey", "direction");
        }

        @Test
        @DisplayName("holds a cursor key of the full twenty-seven characters as valid")
        void holdsAFullWidthCursorKeyAsValid() {
            String widest = "4111111111111111" + "00000000011";
            assertThat(widest).hasSize(PageMetadata.CURSOR_KEY_MAX_LENGTH);

            TransactionListRequest request = new TransactionListRequest(null, null, null,
                    KeyAction.ENTER, null, new PageMetadata.PageCursorRequest(widest, null,
                            PageMetadata.PagingDirection.BACKWARD));

            assertThat(violationsOf(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the page indicator is echoed, not accepted")
    class PageIndicatorIsEchoedNotAccepted {

        @Test
        @DisplayName("is marked non-bindable")
        void isMarkedNonBindable() {
            JsonProperty binding = annotationOn("displayedPageNumber", JsonProperty.class);

            assertThat(binding).isNotNull();
            assertThat(binding.access()).isEqualTo(JsonProperty.Access.READ_ONLY);
        }

        @Test
        @DisplayName("discards a client-supplied indicator while a sibling on the same body binds")
        void discardsAClientSuppliedIndicator() throws JsonProcessingException {
            TransactionListRequest bound = moduleEquivalentMapper().readValue(
                    "{\"displayedPageNumber\":\"99999999\",\"transactionIdFilter\":\"0000000000000042\"}",
                    TransactionListRequest.class);

            assertThat(bound.displayedPageNumber())
                    .as("both PAGENUMI sites in COTRN00C are writes; the program never reads it back")
                    .isNull();
            assertThat(bound.transactionIdFilter())
                    .as("positive control: the body was parsed and a bindable sibling did arrive")
                    .isEqualTo(TRANSACTION_ID_FILTER);
        }

        @Test
        @DisplayName("is still written outbound, because the screen displays it")
        void isStillWrittenOutbound() throws JsonProcessingException {
            String json = moduleEquivalentMapper().writeValueAsString(populated());

            assertThat(json).contains("\"displayedPageNumber\":\"00000003\"");
        }

        @Test
        @DisplayName("is bounded at its map width when the server sets it")
        void isBoundedAtItsMapWidth() {
            assertThat(annotationOn("displayedPageNumber", Size.class).max()).isEqualTo(8);

            TransactionListRequest tooWide = new TransactionListRequest(null, "999999999", null,
                    KeyAction.ENTER, null, null);

            assertThat(violationsOf(tooWide)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("the row-selector list matches the screen's row families")
    class RowSelectorListMatchesTheScreen {

        @Test
        @DisplayName("accepts exactly ten selectors, one per row family")
        void acceptsExactlyTenSelectors() {
            assertThatNoException().isThrownBy(() -> withSelectors(
                    List.of("", "", "S", "", "", "", "", "", "", "")));
        }

        @Test
        @DisplayName("rejects an eleventh selector, naming both figures")
        void rejectsAnEleventhSelector() {
            List<String> eleven = Collections.nCopies(11, "");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withSelectors(eleven))
                    .withMessage("rowSelectors may hold at most 10 entries, because that is how many"
                            + " row families the transaction-list screen declares, but it holds 11");
        }

        @Test
        @DisplayName("accepts a short list, because a short page selects fewer rows")
        void acceptsAShortList() {
            assertThat(withSelectors(List.of("S", "")).rowSelectors()).hasSize(2);
        }

        @Test
        @DisplayName("replaces an absent list with an empty one")
        void replacesAnAbsentListWithAnEmptyOne() {
            assertThat(withSelectors(null).rowSelectors()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("copies the supplied list defensively")
        void copiesTheSuppliedListDefensively() {
            List<String> mutable = new ArrayList<>();
            mutable.add("S");

            TransactionListRequest request = withSelectors(mutable);
            mutable.clear();

            assertThat(request.rowSelectors()).containsExactly("S");
        }

        @Test
        @DisplayName("bounds each selector at one character")
        void boundsEachSelectorAtOneCharacter() {
            Set<ConstraintViolation<TransactionListRequest>> violations =
                    violationsOf(withSelectors(List.of("SS")));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("rowSelectors[0].<list element>");
        }
    }

    @Nested
    @DisplayName("nested request state is validated transitively")
    class NestedStateIsValidatedTransitively {

        @Test
        @DisplayName("both nested components are marked for cascading validation")
        void bothNestedComponentsCascade() {
            assertThat(annotationOn("navigationContext", Valid.class)).isNotNull();
            assertThat(annotationOn("pageMetadata", Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("an over-long value inside the navigation state is reported")
        void overLongNavigationValueIsReported() {
            NavigationContext tooWide = new NavigationContext("CT00X", "COTRN00C", "CT00",
                    "COTRN00C", "ADMINUSR", "A", NavigationContext.ProgramContext.REENTER,
                    "000000011", "MARY", "ANN", "SMITH", ACCOUNT_ID, "Y", CARD_NUMBER, "COTRN0A",
                    "COTRN00");
            TransactionListRequest request = new TransactionListRequest(null, null, null,
                    KeyAction.ENTER, tooWide, null);

            Set<ConstraintViolation<TransactionListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("an over-long cursor key inside the paging state is reported")
        void overLongCursorKeyIsReported() {
            TransactionListRequest request = new TransactionListRequest(null, null, null,
                    KeyAction.ENTER, null, new PageMetadata.PageCursorRequest("x".repeat(28), null,
                            PageMetadata.PagingDirection.FORWARD));

            Set<ConstraintViolation<TransactionListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("pageMetadata.previousCursorKey");
        }

        @Test
        @DisplayName("a fully populated request violates nothing")
        void fullyPopulatedRequestViolatesNothing() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("accepts an entirely empty submission, which is a legitimate first entry")
        void acceptsAnEmptySubmission() {
            assertThat(violationsOf(new TransactionListRequest(null, null, null, null, null, null)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        @DisplayName("withholds the nested identifiers, and withholds the paging block whole rather "
                + "than delegating to it")
        void withholdsTheNestedIdentifiers() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain(CARD_NUMBER, ACCOUNT_ID, "0000000000000031",
                    "0000000000000041");
            assertThat(rendered)
                    .as("the echoed navigation state is printed by delegation and redacts its own")
                    .contains("NavigationContext[");
            assertThat(rendered)
                    .as("the paging block is replaced entirely, so this type's safety does not depend "
                            + "on another type's rendering staying safe: a cursor key is composed from "
                            + "the keys it positions on, so it names the very row it points at")
                    .contains("pageMetadata=***REDACTED***")
                    .doesNotContain("PageCursorRequest[");
        }

        @Test
        @DisplayName("retains the interaction state, while the paging direction stays readable through "
                + "the accessor rather than through a diagnostic")
        void retainsInteractionStateAndDirection() {
            TransactionListRequest request = populated();

            String rendered = request.toString();

            assertThat(rendered).contains("keyAction=PFK08");
            assertThat(rendered).doesNotContain("direction=FORWARD");
            assertThat(request.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.FORWARD);
        }
    }
}
