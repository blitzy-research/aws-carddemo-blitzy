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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;

/**
 * Unit test for {@link CardListRequest}, the inbound contract of the {@code CCLI} card-browse screen.
 *
 * <h2>What is actually at risk in a request of this shape</h2>
 *
 * <p>The first risk is <strong>a regulated filter reaching a diagnostic sink</strong>. The screen offers
 * two filters, one an account identifier and one a primary account number at full sixteen characters, and
 * the paging component carries the two cursor keys that position the browse. The rendering withholds all
 * three, and the tests assert that negatively - absent in whole, and absent in every fragment long enough
 * to identify the value, including the issuer prefix and the four-character tail a partial mask leaks.</p>
 *
 * <p>The paging component is withheld <strong>whole rather than by delegation</strong>. It does redact its
 * own cursors, but depending on that would make this type's safety a property of another type's rendering,
 * and a later change there would silently reopen this one.</p>
 *
 * <p>The second risk is <strong>the two nested components going unmeasured</strong>. Both the echoed
 * paging state and the echoed navigation state declare widths of their own that nothing evaluates unless
 * this contract cascades into them, and the paging state additionally carries the row count. This screen
 * is the only one of the three browse screens whose page is seven rows rather than ten, which makes the
 * cascade the mechanism by which a caller-supplied row count is measured at all. Both cascades are
 * asserted by observing a nested property path rather than by reading the annotations alone.</p>
 *
 * <p>The third is <strong>positional fidelity</strong>. The seven selection components are not a list but
 * seven separate one-character screen fields, and selection n is the keystroke typed against row n. They
 * are asserted individually, and the ordered view over them is asserted to preserve position including the
 * absent ones, because collapsing or compacting them would move a selection onto a different card.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("CardListRequest - the CCLI inbound contract")
class CardListRequestSecurityTest {

    /** The fifteen components in declaration order. A change here is a change to the REST contract. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "accountIdFilter", "cardNumberFilter", "displayedPageNumber", "selection1", "selection2",
            "selection3", "selection4", "selection5", "selection6", "selection7", "pageMetadata",
            "lastPageAlreadyShown", "keyAction", "navigationContext", "rowSnapshotToken");

    /** The seven positional selection components, in row order. */
    private static final List<String> SELECTION_COMPONENTS = List.of(
            "selection1", "selection2", "selection3", "selection4", "selection5", "selection6",
            "selection7");

    /** The fixed stand-in the rendering must emit in place of a withheld value. */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    /**
     * An eleven-character account identifier, deliberately every-digit-distinct rather than zero padded
     * so the fragment scans measure disclosure of this value rather than an accidental run of zeros
     * shared with a retained value.
     */
    private static final String ACCOUNT_ID_FILTER = "78412590063";

    /** A sixteen-character primary account number. A synthetic test value, not an issued card. */
    private static final String CARD_NUMBER_FILTER = "4532015112830366";

    /** The three-character page label the screen echoes. */
    private static final String DISPLAYED_PAGE_NUMBER = "003";

    /**
     * A backward cursor key at the declared paging width.
     *
     * <p>It deliberately embeds the account identifier, because a real browse cursor is composed from the
     * keys it positions on. That makes the withholding assertions adversarial rather than incidental: if
     * the paging component were ever rendered - even by delegating to its own rendering - the account
     * identifier would reach the diagnostic through it, and the fragment scans below would catch it.</p>
     */
    private static final String PREVIOUS_CURSOR = "AAA784125900639";

    /** The forward cursor key, distinct from the backward one so a transposition is visible. */
    private static final String NEXT_CURSOR = "BBB784125900637";

    /**
     * The echoed paging state a caller may submit.
     *
     * <p>This is the request-shaped cursor carrier rather than the full response metadata: it declares
     * the two cursor keys and the direction and nothing else, so a caller has no row count to nominate.
     * The rendering of this request withholds the whole component regardless, which is what the tests
     * below measure.</p>
     *
     * @return a forward-paging cursor request at the declared widths
     */
    private static PageMetadata.PageCursorRequest populatedPage() {
        return new PageMetadata.PageCursorRequest(PREVIOUS_CURSOR, NEXT_CURSOR,
                PageMetadata.PagingDirection.FORWARD, null, false);
    }

    /** A realistic forward-page submission with a selection on the third row. */
    private static CardListRequest populated() {
        return new CardListRequest(ACCOUNT_ID_FILTER, CARD_NUMBER_FILTER, DISPLAYED_PAGE_NUMBER, " ",
                " ", "S", " ", " ", " ", " ", populatedPage(), false, KeyAction.PFK08,
                NavigationContext.empty().withReEntry(), null);
    }

    /**
     * Mirrors the four serialisation settings the module declares in {@code application.yml}, so a
     * payload asserted here is the payload the service actually emits and receives.
     */
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

    private static Set<ConstraintViolation<CardListRequest>> violationsOf(CardListRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("The component set is the browse map plus paging plus conversation state")
    class TheComponentSetIsTheBrowseMap {

        @Test
        @DisplayName("fifteen components are declared in order")
        void fifteenComponentsAreDeclaredInOrder() {
            List<String> declared = Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_ORDER).hasSize(15);
        }

        @Test
        @DisplayName("the seven selections are seven separate components rather than a collection, "
                + "because the screen has seven separate one-character fields")
        void theSevenSelectionsAreSevenSeparateComponents() {
            List<String> declared = Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> name.startsWith("selection"))
                    .toList();

            assertThat(declared).containsExactlyElementsOf(SELECTION_COMPONENTS).hasSize(7);
            assertThat(SELECTION_COMPONENTS).hasSize(PageMetadata.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("no card row payload is declared, because the browse reads its rows from the store "
                + "rather than trusting a client to echo them back")
        void noCardRowPayloadIsDeclared() {
            List<String> lowerCased = Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("status"))
                    .noneMatch(name -> name.contains("expir"))
                    .noneMatch(name -> name.contains("embossed") || name.contains("name"))
                    .noneMatch(name -> name.contains("cvv"));
        }

        @Test
        @DisplayName("no screen-attribute, length or cursor-position component is declared")
        void noScreenAttributeComponentIsDeclared() {
            List<String> lowerCased = Arrays.stream(CardListRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("attrib"))
                    .noneMatch(name -> name.contains("colour") || name.contains("color"))
                    .noneMatch(name -> name.contains("cursor"))
                    .noneMatch(name -> name.endsWith("len") || name.endsWith("length"))
                    // "flag" would also have caught the retained end-of-data value, which is paging
                    // state rather than a screen attribute, so the exclusion names the attribute
                    // families instead of a spelling that overlaps one of them.
                    .noneMatch(name -> name.contains("highlight"))
                    .noneMatch(name -> name.contains("protected") || name.contains("darken"));
        }

        @Test
        @DisplayName("each component carries the type its role requires, so no two are transposed")
        void eachComponentCarriesTheTypeItsRoleRequires() {
            for (RecordComponent component : CardListRequest.class.getRecordComponents()) {
                switch (component.getName()) {
                    case "pageMetadata" -> assertThat(component.getType())
                            .isEqualTo(PageMetadata.PageCursorRequest.class);
                    case "keyAction" -> assertThat(component.getType()).isEqualTo(KeyAction.class);
                    case "navigationContext" ->
                            assertThat(component.getType()).isEqualTo(NavigationContext.class);
                    case "lastPageAlreadyShown" ->
                            assertThat(component.getType()).isEqualTo(boolean.class);
                    default -> assertThat(component.getType())
                            .as("component %s", component.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("every accessor returns exactly what it was constructed with, so no two of the "
                + "seven same-typed selections are transposed")
        void everyAccessorReturnsExactlyWhatItWasConstructedWith() {
            CardListRequest request = populated();

            assertThat(request.accountIdFilter()).isEqualTo(ACCOUNT_ID_FILTER);
            assertThat(request.cardNumberFilter()).isEqualTo(CARD_NUMBER_FILTER);
            assertThat(request.displayedPageNumber()).isEqualTo(DISPLAYED_PAGE_NUMBER);
            assertThat(request.selection1()).isEqualTo(" ");
            assertThat(request.selection2()).isEqualTo(" ");
            assertThat(request.selection3()).isEqualTo("S");
            assertThat(request.selection4()).isEqualTo(" ");
            assertThat(request.selection5()).isEqualTo(" ");
            assertThat(request.selection6()).isEqualTo(" ");
            assertThat(request.selection7()).isEqualTo(" ");
            assertThat(request.pageMetadata().nextCursorKey()).isEqualTo(NEXT_CURSOR);
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(request.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
        }

        @Test
        @DisplayName("the ordered view preserves every position including the absent ones, because "
                + "compacting it would move a selection onto a different card")
        void theOrderedViewPreservesEveryPosition() {
            CardListRequest request = new CardListRequest(null, null, null, null, "U", null, null,
                    "S", null, null, null, false, null, null, null);

            List<String> ordered = request.selectionsInRowOrder();

            assertThat(ordered).hasSize(7);
            assertThat(ordered.get(0)).isNull();
            assertThat(ordered.get(1)).isEqualTo("U");
            assertThat(ordered.get(4)).isEqualTo("S");
            assertThat(ordered).containsExactly(null, "U", null, null, "S", null, null);
        }

        @Test
        @DisplayName("the ordered view is unmodifiable, so no caller can rewrite a selection through it")
        void theOrderedViewIsUnmodifiable() {
            assertThat(populated().selectionsInRowOrder()).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("The rendering discloses nothing while the wire carries everything")
    class TheRenderingDisclosesNothingWhileTheWireCarriesEverything {

        @Test
        @DisplayName("the rendering is exactly the eleven retained values plus four placeholders")
        void theRenderingIsExactlyTheElevenRetainedValuesPlusFourPlaceholders() {
            assertThat(populated()).hasToString("CardListRequest["
                    + "accountIdFilter=" + REDACTION_PLACEHOLDER_TEXT
                    + ", cardNumberFilter=" + REDACTION_PLACEHOLDER_TEXT
                    + ", displayedPageNumber=" + DISPLAYED_PAGE_NUMBER
                    + ", selection1= "
                    + ", selection2= "
                    + ", selection3=S"
                    + ", selection4= "
                    + ", selection5= "
                    + ", selection6= "
                    + ", selection7= "
                    + ", pageMetadata=" + REDACTION_PLACEHOLDER_TEXT
                    + ", lastPageAlreadyShown=false"
                    + ", keyAction=" + KeyAction.PFK08
                    + ", navigationContext=" + NavigationContext.empty().withReEntry()
                    + ", rowSnapshotToken=" + REDACTION_PLACEHOLDER_TEXT
                    + "]");
        }

        @Test
        @DisplayName("neither filter appears anywhere in the rendering, in whole")
        void neitherFilterAppearsInWhole() {
            String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain(ACCOUNT_ID_FILTER)
                    .doesNotContain(CARD_NUMBER_FILTER);
        }

        @Test
        @DisplayName("neither the issuer prefix nor the four-character tail of the card filter survives, "
                + "which is the partial disclosure a masked rendering would have left")
        void neitherThePrefixNorTheTailOfTheCardFilterSurvives() {
            String rendered = populated().toString();

            assertThat(rendered).doesNotContain(CARD_NUMBER_FILTER.substring(0, 6));
            assertThat(rendered)
                    .doesNotContain(CARD_NUMBER_FILTER.substring(CARD_NUMBER_FILTER.length() - 4));
        }

        @Test
        @DisplayName("no six-character run of either filter survives")
        void noSixCharacterRunOfEitherFilterSurvives() {
            String rendered = populated().toString();

            List.of(ACCOUNT_ID_FILTER, CARD_NUMBER_FILTER).forEach(value ->
                    IntStream.rangeClosed(0, value.length() - 6)
                            .mapToObj(start -> value.substring(start, start + 6))
                            .forEach(fragment -> assertThat(rendered)
                                    .as("fragment %s must not appear", fragment)
                                    .doesNotContain(fragment)));
        }

        @Test
        @DisplayName("the paging component is withheld whole rather than by delegation, so this type's "
                + "safety does not depend on another type's rendering")
        void thePagingComponentIsWithheldWholeRatherThanByDelegation() {
            String rendered = populated().toString();

            assertThat(rendered).contains("pageMetadata=" + REDACTION_PLACEHOLDER_TEXT);
            assertThat(rendered).doesNotContain("PageMetadata[").doesNotContain("PageCursorRequest[");
            assertThat(rendered).doesNotContain(PREVIOUS_CURSOR);
            assertThat(rendered).doesNotContain(NEXT_CURSOR);
        }

        @Test
        @DisplayName("two instances differing only in the withheld values render identically, so the "
                + "placeholders are constants rather than transformations")
        void twoInstancesDifferingOnlyInTheWithheldValuesRenderIdentically() {
            CardListRequest first = populated();
            CardListRequest second = new CardListRequest("00000000011", "9999888877776666",
                    DISPLAYED_PAGE_NUMBER, " ", " ", "S", " ", " ", " ", " ",
                    new PageMetadata.PageCursorRequest("OTHER", "DIFFERENT",
                            PageMetadata.PagingDirection.BACKWARD, null, false), false,
                    KeyAction.PFK08, NavigationContext.empty().withReEntry(), null);

            assertThat(first).hasToString(second.toString());
        }

        @Test
        @DisplayName("an absent withheld value still renders as the placeholder, so absence and presence "
                + "are indistinguishable in a diagnostic")
        void anAbsentWithheldValueStillRendersAsThePlaceholder() {
            CardListRequest sparse = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, false, null, null, null);

            assertThat(sparse.toString())
                    .contains("accountIdFilter=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("cardNumberFilter=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("pageMetadata=" + REDACTION_PLACEHOLDER_TEXT)
                    .doesNotContain("cardNumberFilter=null")
                    .doesNotContain("pageMetadata=null");
        }

        @Test
        @DisplayName("the positional selections are retained, because a keystroke against a row names no "
                + "card and is what a reader needs to see")
        void thePositionalSelectionsAreRetained() {
            String rendered = populated().toString();

            assertThat(rendered).contains("selection3=S");
            assertThat(rendered).contains("displayedPageNumber=" + DISPLAYED_PAGE_NUMBER);
            assertThat(rendered).contains("keyAction=" + KeyAction.PFK08);
        }

        @Test
        @DisplayName("the nested navigation state redacts its own identifying values, so printing it by "
                + "delegation discloses nothing further")
        void theNestedNavigationStateRedactsItsOwn() {
            NavigationContext identifying = new NavigationContext(null, null, null, null, "USER0001",
                    "U", NavigationContext.ProgramContext.REENTER, "000000007", "GRACE", null,
                    "HOPPER", ACCOUNT_ID_FILTER, "Y", CARD_NUMBER_FILTER, null, null);
            CardListRequest request = new CardListRequest(ACCOUNT_ID_FILTER, CARD_NUMBER_FILTER, null,
                    null, null, null, null, null, null, null, null, false, null, identifying, null);

            String rendered = request.toString();

            assertThat(rendered)
                    .doesNotContain(CARD_NUMBER_FILTER)
                    .doesNotContain(ACCOUNT_ID_FILTER)
                    .doesNotContain("GRACE")
                    .doesNotContain("HOPPER");
        }

        @Test
        @DisplayName("the rendering is safe when every component is absent")
        void theRenderingIsSafeWhenEveryComponentIsAbsent() {
            CardListRequest empty = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, false, null, null, null);

            assertThatCode(empty::toString).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the wire still carries every withheld value at full width, because the rendering "
                + "is a diagnostic channel and not the transport")
        void theWireStillCarriesEveryWithheldValue() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            JsonNode payload = mapper.readTree(mapper.writeValueAsString(populated()));

            assertThat(payload.get("cardNumberFilter").asText()).isEqualTo(CARD_NUMBER_FILTER)
                    .hasSize(16);
            assertThat(payload.get("accountIdFilter").asText()).isEqualTo(ACCOUNT_ID_FILTER);
            assertThat(payload.get("pageMetadata").get("nextCursorKey").asText())
                    .isEqualTo(NEXT_CURSOR);
        }
    }

    @Nested
    @DisplayName("Bounds measure and never alter, and the cascades are not field edits")
    class BoundsMeasureAndNeverAlter {

        @Test
        @DisplayName("the account filter carries the account key's own width and nothing else")
        void theAccountFilterCarriesTheAccountKeysOwnWidth() throws NoSuchFieldException {
            Field field = CardListRequest.class.getDeclaredField("accountIdFilter");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(CardListRequest.ACCOUNT_ID_FILTER_LENGTH).isEqualTo(11);
            assertThat(size.min()).isZero();
            assertThat(Arrays.stream(field.getAnnotations())
                    .filter(annotation -> annotation.annotationType().getPackageName()
                            .startsWith("jakarta.validation"))
                    .toList())
                    .as("the width is the only rule; a documentation annotation states what the filter "
                            + "means and imposes nothing")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the card filter carries the card key's own width and nothing else")
        void theCardFilterCarriesTheCardKeysOwnWidth() throws NoSuchFieldException {
            Field field = CardListRequest.class.getDeclaredField("cardNumberFilter");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(CardListRequest.CARD_NUMBER_FILTER_LENGTH).isEqualTo(16);
            assertThat(Arrays.stream(field.getAnnotations())
                    .filter(annotation -> annotation.annotationType().getPackageName()
                            .startsWith("jakarta.validation"))
                    .toList())
                    .as("the width is the only rule; a documentation annotation states what the filter "
                            + "means and imposes nothing")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the page label carries a three-character width, which is this screen's own figure "
                + "and not the eight the other browse screens use")
        void thePageLabelCarriesAThreeCharacterWidth() throws NoSuchFieldException {
            Field field = CardListRequest.class.getDeclaredField("displayedPageNumber");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(CardListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("every selection carries a one-character width, because each is one screen field "
                + "and a second character is data the screen could not have produced")
        void everySelectionCarriesAOneCharacterWidth() throws NoSuchFieldException {
            for (String name : SELECTION_COMPONENTS) {
                Field field = CardListRequest.class.getDeclaredField(name);
                Size size = field.getAnnotation(Size.class);

                assertThat(size).as("component %s", name).isNotNull();
                assertThat(size.max()).as("component %s", name)
                        .isEqualTo(CardListRequest.SELECTION_LENGTH).isEqualTo(1);
                assertThat(field.getAnnotations()).as("component %s", name).hasSize(1);
            }
        }

        @Test
        @DisplayName("no presence, pattern, digit, range or assertion constraint appears on any "
                + "component, because the browse program owns its own ordered cascade")
        void noPresenceOrFormatConstraintAppears() throws NoSuchFieldException {
            for (String name : COMPONENTS_IN_ORDER) {
                Field field = CardListRequest.class.getDeclaredField(name);

                assertThat(Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .toList())
                        .as("component %s", name)
                        .doesNotContain("NotNull", "NotBlank", "NotEmpty", "Pattern", "Digits",
                                "Min", "Max", "Positive", "AssertTrue");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("a blank filter and a blank selection transport rather than being rejected, because "
                + "a blank filter means browse from the beginning")
        void aBlankValueTransportsRatherThanBeingRejected(String blank) {
            CardListRequest request = new CardListRequest(blank, blank, blank, blank, blank, blank,
                    blank, blank, blank, blank, null, false, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.accountIdFilter()).isEqualTo(blank);
            assertThat(request.selection1()).isEqualTo(blank);
        }

        @Test
        @DisplayName("a submission space-filled to each declared width draws no violation, because that "
                + "is the shape a blank browse screen transmits")
        void aSpaceFilledSubmissionDrawsNoViolation() {
            CardListRequest spaceFilled = new CardListRequest(" ".repeat(11), " ".repeat(16), "   ",
                    " ", " ", " ", " ", " ", " ", " ", null, false, null, null, null);

            assertThat(violationsOf(spaceFilled)).isEmpty();
            assertThat(spaceFilled.cardNumberFilter()).hasSize(16).isBlank();
            assertThat(spaceFilled.displayedPageNumber()).hasSize(3).isBlank();
        }

        @Test
        @DisplayName("each filter and the page label one character over its own width is reported "
                + "exactly once and never trimmed")
        void eachOverWidthValueIsReportedExactlyOnce() {
            assertThatExactlyOneViolationOn("accountIdFilter",
                    new CardListRequest("1".repeat(12), null, null, null, null, null, null, null,
                            null, null, null, false, null, null, null));
            assertThatExactlyOneViolationOn("cardNumberFilter",
                    new CardListRequest(null, "1".repeat(17), null, null, null, null, null, null,
                            null, null, null, false, null, null, null));
            assertThatExactlyOneViolationOn("displayedPageNumber",
                    new CardListRequest(null, null, "1234", null, null, null, null, null, null, null,
                            null, false, null, null, null));
        }

        @Test
        @DisplayName("each selection of two characters is reported on its own component, so a selection "
                + "cannot be attributed to the wrong row")
        void eachOverWidthSelectionIsReportedOnItsOwnComponent() {
            for (int index = 0; index < SELECTION_COMPONENTS.size(); index++) {
                String[] selections = new String[7];
                selections[index] = "SS";
                CardListRequest request = new CardListRequest(null, null, null, selections[0],
                        selections[1], selections[2], selections[3], selections[4], selections[5],
                        selections[6], null, false, null, null, null);

                assertThatExactlyOneViolationOn(SELECTION_COMPONENTS.get(index), request);
            }
        }

        @Test
        @DisplayName("a fully populated valid submission draws no violation at all")
        void aFullyPopulatedValidSubmissionDrawsNoViolation() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        private static void assertThatExactlyOneViolationOn(String component,
                CardListRequest request) {
            Set<ConstraintViolation<CardListRequest>> violations = violationsOf(request);

            assertThat(violations).as("component %s", component).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .as("component %s", component)
                    .hasToString(component);
        }
    }

    @Nested
    @DisplayName("Both nested components are cascaded into")
    class BothNestedComponentsAreCascadedInto {

        @Test
        @DisplayName("the paging component declares the cascade")
        void thePagingComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = CardListRequest.class.getDeclaredField("pageMetadata");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("the navigation component declares the cascade")
        void theNavigationComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = CardListRequest.class.getDeclaredField("navigationContext");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("a violation inside the echoed paging state is reported under a nested property "
                + "path, which is the observable proof the cascade reaches it")
        void aViolationInsideTheEchoedPagingStateIsReported() {
            PageMetadata.PageCursorRequest overWidth = new PageMetadata.PageCursorRequest(null,
                    "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1),
                    PageMetadata.PagingDirection.FORWARD, null, false);
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, overWidth, false, null, null, null);

            Set<ConstraintViolation<CardListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("pageMetadata.nextCursorKey");
        }

        @Test
        @DisplayName("the echoed paging state carries no caller-supplied row count at all, so this "
                + "screen's page size cannot be raised from the wire rather than merely being bounded "
                + "there")
        void theEchoedPagingStateCarriesNoCallerSuppliedRowCount() {
            List<String> declared =
                    Arrays.stream(PageMetadata.PageCursorRequest.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared)
                    .containsExactly("previousCursorKey", "nextCursorKey", "direction",
                            "displayedPageNumber", "nextPageIndicated")
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("size"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("count"))
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("limit"));
        }

        @Test
        @DisplayName("the backward cursor is cascaded into as well, so the cascade covers every nested "
                + "component rather than only the first one declared")
        void aViolationInTheBackwardCursorIsReportedToo() {
            PageMetadata.PageCursorRequest overWidth = new PageMetadata.PageCursorRequest(
                    "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1), null,
                    PageMetadata.PagingDirection.BACKWARD, null, false);
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, overWidth, false, null, null, null);

            Set<ConstraintViolation<CardListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("pageMetadata.previousCursorKey");
        }

        @Test
        @DisplayName("this screen's own seven-row page passes the cascade, because the bound is the "
                + "largest of the three screen figures and seven is one of them")
        void thisScreensOwnSevenRowPagePassesTheCascade() {
            assertThat(PageMetadata.CARD_LIST_PAGE_SIZE).isEqualTo(7)
                    .isLessThan(PageMetadata.LARGEST_SCREEN_PAGE_SIZE);
            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("a violation inside the echoed navigation state is reported under a nested property "
                + "path too, so the second cascade is independent of the first")
        void aViolationInsideTheEchoedNavigationStateIsReported() {
            NavigationContext overWidth = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, false, null, overWidth, null);

            Set<ConstraintViolation<CardListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("navigationContext.fromProgram");
        }

        @Test
        @DisplayName("violations from both nested components are reported together, so neither cascade "
                + "masks the other")
        void violationsFromBothNestedComponentsAreReportedTogether() {
            PageMetadata.PageCursorRequest badPage = new PageMetadata.PageCursorRequest(null,
                    "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1),
                    PageMetadata.PagingDirection.FORWARD, null, false);
            NavigationContext badContext = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            CardListRequest request = new CardListRequest(null, null, null, null, null, null, null,
                    null, null, null, badPage, false, null, badContext, null);

            Set<ConstraintViolation<CardListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(2);
            assertThat(violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .toList())
                    .containsExactly("navigationContext.fromProgram", "pageMetadata.nextCursorKey");
        }

        @Test
        @DisplayName("both absent nested components are not violations, because a first entry carries "
                + "neither")
        void bothAbsentNestedComponentsAreNotViolations() {
            CardListRequest request = new CardListRequest(ACCOUNT_ID_FILTER, null, null, null, null,
                    null, null, null, null, null, null, false, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.pageMetadata()).isNull();
            assertThat(request.navigationContext()).isNull();
        }
    }

    @Nested
    @DisplayName("Nothing is transformed on the way through")
    class NothingIsTransformedOnTheWayThrough {

        @Test
        @DisplayName("leading zeros survive in both filters and the page label")
        void leadingZerosSurvive() {
            CardListRequest request = new CardListRequest("00000000011", "0000000000000001", "001",
                    null, null, null, null, null, null, null, null, false, null, null, null);

            assertThat(request.accountIdFilter()).isEqualTo("00000000011");
            assertThat(request.cardNumberFilter()).isEqualTo("0000000000000001");
            assertThat(request.displayedPageNumber()).isEqualTo("001");
        }

        @Test
        @DisplayName("case is never folded on a selection, because the browse distinguishes the two "
                + "selection characters by value")
        void caseIsNeverFoldedOnASelection() {
            CardListRequest request = new CardListRequest(null, null, null, "s", "u", null, null,
                    null, null, null, null, false, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.selection1()).isEqualTo("s");
            assertThat(request.selection2()).isEqualTo("u");
        }

        @Test
        @DisplayName("an out-of-vocabulary selection round-trips, because the browse reports its own "
                + "rejection rather than the boundary refusing the submission")
        void anOutOfVocabularySelectionRoundTrips() {
            CardListRequest request = new CardListRequest(null, null, null, "X", null, null, null,
                    null, null, null, null, false, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.selection1()).isEqualTo("X");
        }

        @Test
        @DisplayName("a non-numeric filter round-trips, because the shape check belongs to the browse "
                + "program rather than to this boundary")
        void aNonNumericFilterRoundTrips() {
            CardListRequest request = new CardListRequest("ABCDEFGHIJK", "ZZZZ", null, null, null,
                    null, null, null, null, null, null, false, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.accountIdFilter()).isEqualTo("ABCDEFGHIJK");
            assertThat(request.cardNumberFilter()).isEqualTo("ZZZZ");
        }

        @Test
        @DisplayName("equality compares every component including the withheld ones, because an "
                + "in-memory comparison emits nothing")
        void equalityComparesEveryComponentIncludingTheWithheldOnes() {
            CardListRequest first = populated();
            CardListRequest same = populated();
            CardListRequest differentCardFilter = new CardListRequest(ACCOUNT_ID_FILTER,
                    "4532015112830367", DISPLAYED_PAGE_NUMBER, " ", " ", "S", " ", " ", " ", " ",
                    populatedPage(), false, KeyAction.PFK08, NavigationContext.empty().withReEntry(), null);

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(differentCardFilter);
        }

        @Test
        @DisplayName("a document naming every component deserializes with each value intact, so the "
                + "withholding rendering did not disturb the wire contract")
        void aDocumentNamingEveryComponentDeserializesIntact() throws JsonProcessingException {
            String document = "{\"accountIdFilter\":\"" + ACCOUNT_ID_FILTER + "\","
                    + "\"cardNumberFilter\":\"" + CARD_NUMBER_FILTER + "\","
                    + "\"displayedPageNumber\":\"003\",\"selection3\":\"S\","
                    + "\"keyAction\":\"PFK07\"}";

            CardListRequest request =
                    moduleEquivalentMapper().readValue(document, CardListRequest.class);

            assertThat(request.accountIdFilter()).isEqualTo(ACCOUNT_ID_FILTER);
            assertThat(request.cardNumberFilter()).isEqualTo(CARD_NUMBER_FILTER);
            assertThat(request.selection3()).isEqualTo("S");
            assertThat(request.selection1()).isNull();
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK07);
        }
    }
}
