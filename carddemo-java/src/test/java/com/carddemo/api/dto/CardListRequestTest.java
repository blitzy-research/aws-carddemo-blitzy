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

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * Unit tests for {@link CardListRequest}, the request body of legacy transaction {@code CCLI}.
 *
 * <p>A pure unit test: no application context, no connection, no container. Where the wire shape is
 * what is under test it serialises and deserialises with a locally built mapper configured to match
 * the settings the module declares in {@code application.yml}.
 *
 * <p>Two properties dominate what is checked, and both were wrong.
 *
 * <p>The first is who owns the paging state. The request accepted the whole outbound paging contract,
 * which meant a caller could name the page size the screen has - it has seven rows and the number is
 * screen geometry, not a setting - and could assert the availability of pages the browse had not found.
 * The tests below prove the inbound shape now carries only the two boundary keys and the direction, and
 * that the page indicator the legacy program never reads inbound can no longer be supplied at all.
 *
 * <p>The second is transitive validation. Both nested components declare widths of their own that were
 * never evaluated, because Bean Validation does not descend into an object graph unless it is told to.
 *
 * <p>Provenance for every width and every citation: repository checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("CardListRequest :: card-list request contract of legacy transaction CCLI")
class CardListRequestTest {

    /** The thirteen record components in the order the symbolic map declares their fields. */
    private static final List<String> COMPONENTS_IN_MAP_ORDER = List.of(
            "accountIdFilter", "cardNumberFilter", "displayedPageNumber", "selection1", "selection2",
            "selection3", "selection4", "selection5", "selection6", "selection7", "pageMetadata",
            "keyAction", "navigationContext");

    private static final String ACCOUNT_FILTER = "00000000011";
    private static final String CARD_FILTER = "0000000000000011";
    private static final String PREVIOUS_KEY = "0000000000000011" + "00000000011";
    private static final String NEXT_KEY = "0000000000000099" + "00000000011";
    private static final String REDACTED = "***REDACTED***";

    private static NavigationContext navigation() {
        return new NavigationContext("CCLI", "COCRDLIC", "CCLI", "COCRDLIC", "ADMINUSR", "A",
                NavigationContext.ProgramContext.REENTER, "000000011", "MARY", "ANN", "SMITH",
                ACCOUNT_FILTER, "Y", CARD_FILTER, "CCRDLIA", "COCRDLI");
    }

    private static PageMetadata.PageCursorRequest cursor() {
        return new PageMetadata.PageCursorRequest(PREVIOUS_KEY, NEXT_KEY,
                PageMetadata.PagingDirection.FORWARD);
    }

    private static CardListRequest populated() {
        return new CardListRequest(ACCOUNT_FILTER, CARD_FILTER, "002", "S", null, "", " ", null, null,
                null, cursor(), KeyAction.PFK08, navigation());
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

    private static JsonNode payloadOf(CardListRequest request) throws JsonProcessingException {
        ObjectMapper mapper = moduleEquivalentMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    private static List<String> componentNames() {
        return Arrays.stream(CardListRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the annotations a component actually carries at run time.
     *
     * <p>Read from the backing field rather than from the record component: none of the annotations
     * involved declares {@code RECORD_COMPONENT} among its targets, so asking the component yields an
     * empty array for every component here and an assertion phrased that way would pass without
     * testing anything.
     *
     * @param name the component name
     * @return the annotations present on the backing field
     */
    private static Annotation[] annotationsOn(String name) {
        try {
            return CardListRequest.class.getDeclaredField(name).getAnnotations();
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static <A extends Annotation> A annotationOn(String name, Class<A> type) {
        try {
            return CardListRequest.class.getDeclaredField(name).getAnnotation(type);
        } catch (NoSuchFieldException absent) {
            throw new AssertionError("no component named " + name, absent);
        }
    }

    private static Set<ConstraintViolation<CardListRequest>> violationsOf(CardListRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("component inventory")
    class ComponentInventory {

        @Test
        @DisplayName("declares exactly the thirteen components in map order")
        void declaresThirteenComponentsInMapOrder() {
            assertThat(componentNames())
                    .containsExactlyElementsOf(COMPONENTS_IN_MAP_ORDER)
                    .hasSize(13);
        }

        @Test
        @DisplayName("uses the package's canonical names for the paging pair")
        void usesCanonicalPagingNames() {
            assertThat(componentNames()).contains("pageMetadata", "displayedPageNumber");
            assertThat(componentNames()).doesNotContain("page", "pageIndicator", "pageNumber",
                    "pageSize");
        }

        @Test
        @DisplayName("declares one selection component per screen row and no collection")
        void declaresOneSelectionPerRow() {
            long selections = componentNames().stream().filter(name -> name.startsWith("selection"))
                    .count();

            assertThat(selections)
                    .as("seven explicit components make the row count structural")
                    .isEqualTo(PageMetadata.CARD_LIST_PAGE_SIZE);
            assertThat(Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(RecordComponent::getType))
                    .as("no unbounded collection can arrive on this request")
                    .doesNotContain(List.class);
        }

        @Test
        @DisplayName("bounds the two filters and the indicator at their measured map widths")
        void boundsTheTextComponents() {
            assertThat(annotationOn("accountIdFilter", Size.class).max()).isEqualTo(11);
            assertThat(annotationOn("cardNumberFilter", Size.class).max()).isEqualTo(16);
            assertThat(annotationOn("displayedPageNumber", Size.class).max()).isEqualTo(3);
            for (int row = 1; row <= PageMetadata.CARD_LIST_PAGE_SIZE; row++) {
                assertThat(annotationOn("selection" + row, Size.class).max()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("inbound paging state is narrower than outbound")
    class InboundPagingStateIsNarrower {

        @Test
        @DisplayName("the paging component is the inbound cursor shape, not the outbound metadata")
        void pagingComponentIsTheInboundShape() throws NoSuchFieldException {
            assertThat(CardListRequest.class.getDeclaredField("pageMetadata").getType())
                    .isEqualTo(PageMetadata.PageCursorRequest.class)
                    .isNotEqualTo(PageMetadata.class);
        }

        @Test
        @DisplayName("the inbound shape carries only the two keys and the direction")
        void inboundShapeCarriesOnlyKeysAndDirection() {
            assertThat(Arrays.stream(PageMetadata.PageCursorRequest.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactly("previousCursorKey", "nextCursorKey", "direction");
        }

        @Test
        @DisplayName("a body naming a page size or an availability flag cannot set one")
        void bodyCannotNameAPageSizeOrAvailabilityFlag() throws JsonProcessingException {
            String body = """
                    {"pageMetadata":{"previousCursorKey":null,"nextCursorKey":null,\
                    "direction":"FORWARD","pageSize":2147483647,"hasNextPage":true,\
                    "hasPreviousPage":true,"displayedPageNumber":"999"}}""";

            CardListRequest bound = moduleEquivalentMapper().readValue(body, CardListRequest.class);

            assertThat(bound.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(Arrays.stream(bound.pageMetadata().getClass().getRecordComponents())
                    .map(RecordComponent::getName))
                    .as("there is no component for any of the values the body tried to supply")
                    .doesNotContain("pageSize", "hasNextPage", "hasPreviousPage",
                            "displayedPageNumber");
        }

        @Test
        @DisplayName("the display-only page indicator is declared non-bindable")
        void pageIndicatorIsNonBindable() {
            JsonProperty directive = annotationOn("displayedPageNumber", JsonProperty.class);

            assertThat(directive).isNotNull();
            assertThat(directive.access()).isEqualTo(JsonProperty.Access.READ_ONLY);
        }

        @Test
        @DisplayName("a body that supplies the page indicator has it discarded")
        void pageIndicatorSuppliedInABodyIsDiscarded() throws JsonProcessingException {
            CardListRequest bound = moduleEquivalentMapper().readValue(
                    "{\"displayedPageNumber\":\"999\",\"accountIdFilter\":\"00000000011\"}",
                    CardListRequest.class);

            assertThat(bound.displayedPageNumber())
                    .as("the legacy program never reads this item inbound")
                    .isNull();
            assertThat(bound.accountIdFilter())
                    .as("positive control: an ordinary filter still binds")
                    .isEqualTo(ACCOUNT_FILTER);
        }

        @Test
        @DisplayName("the page indicator is still serialised, so the echo stays documented")
        void pageIndicatorIsStillSerialised() throws JsonProcessingException {
            assertThat(payloadOf(populated()).get("displayedPageNumber").asText()).isEqualTo("002");
        }

        @Test
        @DisplayName("the fixed row count is stated once, on the shared paging contract")
        void rowCountIsStatedOnceOnTheSharedContract() {
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE).isEqualTo(7);
            assertThat(Arrays.stream(CardListRequest.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName))
                    .as("this contract adds no second statement of the same fact")
                    .doesNotContain("PAGE_SIZE", "ROW_COUNT", "CARD_LIST_PAGE_SIZE");
        }
    }

    @Nested
    @DisplayName("nested request state is validated transitively")
    class NestedStateIsValidatedTransitively {

        @Test
        @DisplayName("both nested components are marked for cascading validation")
        void bothNestedComponentsCascade() {
            assertThat(annotationOn("pageMetadata", Valid.class)).isNotNull();
            assertThat(annotationOn("navigationContext", Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("an over-long browse key inside the paging state is reported")
        void overLongBrowseKeyIsReported() {
            PageMetadata.PageCursorRequest tooWide = new PageMetadata.PageCursorRequest(
                    "x".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1), null,
                    PageMetadata.PagingDirection.BACKWARD);
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, tooWide, KeyAction.PFK07, null);

            Set<ConstraintViolation<CardListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("pageMetadata.previousCursorKey");
        }

        @Test
        @DisplayName("an over-long value inside the navigation state is reported")
        void overLongNavigationValueIsReported() {
            NavigationContext tooWide = new NavigationContext("CCLIX", "COCRDLIC", "CCLI",
                    "COCRDLIC", "ADMINUSR", "A", NavigationContext.ProgramContext.REENTER,
                    "000000011", "MARY", "ANN", "SMITH", ACCOUNT_FILTER, "Y", CARD_FILTER, "CCRDLIA",
                    "COCRDLI");
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, KeyAction.ENTER, tooWide);

            Set<ConstraintViolation<CardListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("navigationContext.fromTransactionId");
        }

        @Test
        @DisplayName("a fully populated request violates nothing")
        void fullyPopulatedRequestViolatesNothing() {
            assertThat(violationsOf(populated())).isEmpty();
        }
    }

    @Nested
    @DisplayName("delegated rules and tolerated input")
    class DelegatedRulesAndToleratedInput {

        @Test
        @DisplayName("accepts null everywhere, because a first entry supplies nothing")
        void acceptsNullEverywhere() {
            CardListRequest empty = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, null);

            assertThat(violationsOf(empty)).isEmpty();
        }

        @Test
        @DisplayName("accepts an unrecognised row action code, which the service reports back")
        void acceptsUnrecognisedActionCode() {
            CardListRequest odd = new CardListRequest(null, null, null, "Z", null, null, null, null,
                    null, null, null, KeyAction.ENTER, null);

            assertThat(violationsOf(odd)).isEmpty();
        }

        @Test
        @DisplayName("carries no pattern, nullity or range constraint anywhere")
        void carriesNoConstraintBeyondWidthAndCascade() {
            for (String component : COMPONENTS_IN_MAP_ORDER) {
                assertThat(Arrays.stream(annotationsOn(component))
                        .map(annotation -> annotation.annotationType().getSimpleName()))
                        .as(component)
                        .doesNotContain("Pattern", "NotNull", "NotBlank", "Min", "Max", "Digits",
                                "DecimalMin", "DecimalMax", "Positive", "Null");
            }
        }

        @Test
        @DisplayName("projects the seven row codes in row order, keeping every empty position")
        void projectsRowCodesInRowOrderKeepingEmptyPositions() {
            assertThat(populated().selectionsInRowOrder())
                    .containsExactly("S", null, "", " ", null, null, null)
                    .hasSize(PageMetadata.CARD_LIST_PAGE_SIZE);
        }
    }

    @Nested
    @DisplayName("diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("withholds both filters and the whole paging component")
        void withholdsBothFiltersAndThePagingComponent() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).doesNotContain(ACCOUNT_FILTER, CARD_FILTER, PREVIOUS_KEY, NEXT_KEY);
            assertThat(rendered).contains("accountIdFilter=" + REDACTED,
                    "cardNumberFilter=" + REDACTED, "pageMetadata=" + REDACTED);
        }

        @Test
        @DisplayName("names the paging component by its canonical name")
        void namesThePagingComponentCanonically() {
            String rendered = populated().toString();

            assertThat(rendered).contains("pageMetadata=");
            assertThat(ownRendering(rendered)).doesNotContain("page=");
        }

        @Test
        @DisplayName("retains the screen-interaction state that identifies nobody")
        void retainsScreenInteractionState() {
            String rendered = ownRendering(populated().toString());

            assertThat(rendered).contains("displayedPageNumber=002", "selection1=S",
                    "keyAction=PFK08");
            assertThat(populated().toString()).startsWith("CardListRequest[").endsWith("]");
        }

        @Test
        @DisplayName("changes nothing an accessor returns")
        void changesNothingTransported() {
            CardListRequest request = populated();
            request.toString();

            assertThat(request.accountIdFilter()).isEqualTo(ACCOUNT_FILTER);
            assertThat(request.cardNumberFilter()).isEqualTo(CARD_FILTER);
            assertThat(request.pageMetadata()).isEqualTo(cursor());
        }

        @Test
        @DisplayName("changes nothing on the wire")
        void changesNothingOnTheWire() throws JsonProcessingException {
            JsonNode payload = payloadOf(populated());

            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo(CARD_FILTER);
            assertThat(payload.get("accountIdFilter").asText()).isEqualTo(ACCOUNT_FILTER);
            assertThat(payload.toString()).doesNotContain(REDACTED);
        }
    }

    /**
     * Returns the part of a rendering this record produced itself, excluding the delegated navigation
     * state.
     *
     * <p>Necessary because the nested contract names some of the same components and prints values of
     * its own, so an assertion over the whole string would not be about this type.
     *
     * @param rendered a full rendering
     * @return the leading segment this record contributed
     */
    private static String ownRendering(String rendered) {
        int delegated = rendered.indexOf(", navigationContext=");
        return (delegated < 0) ? rendered : rendered.substring(0, delegated);
    }
}
