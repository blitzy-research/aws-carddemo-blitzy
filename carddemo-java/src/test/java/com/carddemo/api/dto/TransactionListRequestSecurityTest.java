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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
 * Unit test for {@link TransactionListRequest}, the inbound contract of the {@code CT00} browse screen.
 *
 * <h2>What is actually at risk in a request of this shape</h2>
 *
 * <p>The first risk is <strong>a regulated identifier reaching a diagnostic sink</strong>. The filter
 * component carries a sixteen-character transaction identifier the operator typed, and the paging
 * component carries the two cursor keys that position the browse. A generated record rendering would
 * place all three into every log line, exception message and debugger frame that touches the request, so
 * the rendering is replaced and the replacement is asserted negatively: neither the filter nor either
 * cursor may appear, in whole or in any fragment long enough to be useful.</p>
 *
 * <p>The paging component is withheld <strong>whole rather than by delegation</strong>. It does redact
 * its own cursors, but depending on that would make this type's safety a property of another type's
 * rendering, and a later change there would silently reopen this one. The test therefore asserts the
 * placeholder stands in the paging position, not merely that the cursor text is absent.</p>
 *
 * <p>The second risk is <strong>unbounded work</strong>. The selector sequence is index-aligned to a
 * screen of ten rows and is defensively copied, so without a cardinality bound an arbitrarily long
 * sequence is retained in full; and both nested components declare widths of their own that nothing
 * evaluates unless this contract cascades into them. All three bounds are asserted, together with the
 * deliberate absence of every presence, format and vocabulary rule the browse program reserves to
 * itself.</p>
 *
 * <p>The third risk is <strong>transport fidelity</strong>. The browse fills rows one to ten ascending
 * on a forward page and ten down to one on a backward page, so element order is not decoration: element
 * n is the keystroke typed against screen row n, and a reordering or a silent truncation would move a
 * selection onto a different transaction.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("TransactionListRequest - the CT00 inbound contract")
class TransactionListRequestSecurityTest {

    /**
     * A complete sixteen-character transaction identifier, used as the value a caller would have to
     * place somewhere in this contract in order to name a row the previous page displayed.
     */
    private static final String A_FULL_WIDTH_IDENTIFIER = "0000000000000099";

    /** The six components in declaration order. A change here is a change to the REST contract. */
    private static final List<String> COMPONENTS_IN_ORDER = List.of(
            "transactionIdFilter", "displayedPageNumber", "rowSelectors", "keyAction",
            "navigationContext", "pageMetadata");

    /** The fixed stand-in the rendering must emit in place of a withheld value. */
    private static final String REDACTION_PLACEHOLDER_TEXT = "***REDACTED***";

    /**
     * A sixteen-character transaction identifier at the declared filter width.
     *
     * <p>Deliberately every-digit-distinct rather than the zero-padded shape a real key usually takes.
     * The fragment scan below asserts that no six-character run of this value survives into the
     * rendering, and a zero-padded value shares long runs with the page label the rendering legitimately
     * retains, so the scan would report a disclosure that had not happened. A distinctive value makes the
     * scan measure the identifier rather than the coincidence.</p>
     */
    private static final String TRANSACTION_ID = "8461372935172994";

    /** The eight-character page label the screen publishes. */
    private static final String DISPLAYED_PAGE_NUMBER = "00000003";

    /** A cursor key at the declared paging width, distinct from every other literal here. */
    private static final String PREVIOUS_CURSOR = "000000000000031X";

    /** The forward cursor key, distinct from the backward one so a transposition is visible. */
    private static final String NEXT_CURSOR = "000000000000052Y";

    /** The ten selector keystrokes of a full browse page, one per screen row. */
    private static final List<String> TEN_SELECTORS =
            List.of(" ", " ", "S", " ", " ", " ", " ", " ", " ", " ");

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

    /**
     * The echoed paging state a caller may submit.
     *
     * <p>This is the request-shaped cursor carrier rather than the full response metadata: it declares the
     * two cursor keys and the direction and nothing else, so a caller has no page size to nominate and no
     * exhaustion flag to assert. The rendering of this request withholds the whole component regardless,
     * which is what the tests below measure.</p>
     *
     * @return a forward-paging cursor request at the declared widths
     */
    private static PageMetadata.PageCursorRequest populatedCursor() {
        return new PageMetadata.PageCursorRequest(
                PREVIOUS_CURSOR, NEXT_CURSOR, PageMetadata.PagingDirection.FORWARD,
                DISPLAYED_PAGE_NUMBER, true);
    }

    /** A realistic forward-page submission with every component populated. */
    private static TransactionListRequest populated() {
        return new TransactionListRequest(TRANSACTION_ID, DISPLAYED_PAGE_NUMBER, TEN_SELECTORS,
                KeyAction.PFK08, NavigationContext.empty().withReEntry(),
                populatedCursor());
    }

    /**
     * The upper bound a component declares on its own width, or zero when it declares none.
     *
     * @param component the record component name
     * @return the declared maximum, or zero
     */
    private static int boundOf(final String component) {
        try {
            Size size = TransactionListRequest.class.getDeclaredField(component)
                    .getAnnotation(Size.class);
            return size == null ? 0 : size.max();
        } catch (final NoSuchFieldException absent) {
            throw new AssertionError("component " + component + " is not declared", absent);
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
    @DisplayName("The component set is the browse map plus conversation state plus paging")
    class TheComponentSetIsTheBrowseMap {

        @Test
        @DisplayName("six components are declared in order")
        void sixComponentsAreDeclaredInOrder() {
            List<String> declared = Arrays.stream(TransactionListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(COMPONENTS_IN_ORDER).hasSize(6);
        }

        @Test
        @DisplayName("no screen-attribute, length or cursor-position component is declared, because a "
                + "terminal field's presentation bytes are not part of a REST contract")
        void noScreenAttributeComponentIsDeclared() {
            List<String> lowerCased = Arrays.stream(TransactionListRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("attrib"))
                    .noneMatch(name -> name.contains("colour") || name.contains("color"))
                    .noneMatch(name -> name.contains("cursor"))
                    .noneMatch(name -> name.endsWith("len") || name.endsWith("length"))
                    .noneMatch(name -> name.contains("flag"));
        }

        @Test
        @DisplayName("no row payload is declared, because the browse reads its rows from the store "
                + "rather than trusting a client to echo them back")
        void noRowPayloadIsDeclared() {
            List<String> lowerCased = Arrays.stream(TransactionListRequest.class.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .toList();

            assertThat(lowerCased)
                    .noneMatch(name -> name.contains("amount"))
                    .noneMatch(name -> name.contains("description"))
                    .noneMatch(name -> name.contains("account"))
                    .noneMatch(name -> name.contains("card"))
                    .noneMatch(name -> name.contains("row") && !name.equals("rowselectors"));
        }

        @Test
        @DisplayName("each component carries the type its role requires, so no two are transposed")
        void eachComponentCarriesTheTypeItsRoleRequires() {
            for (RecordComponent component : TransactionListRequest.class.getRecordComponents()) {
                switch (component.getName()) {
                    case "rowSelectors" -> assertThat(component.getType()).isEqualTo(List.class);
                    case "keyAction" -> assertThat(component.getType()).isEqualTo(KeyAction.class);
                    case "navigationContext" ->
                            assertThat(component.getType()).isEqualTo(NavigationContext.class);
                    case "pageMetadata" -> assertThat(component.getType())
                            .isEqualTo(PageMetadata.PageCursorRequest.class);
                    default -> assertThat(component.getType())
                            .as("component %s", component.getName())
                            .isEqualTo(String.class);
                }
            }
        }

        @Test
        @DisplayName("every accessor returns exactly what it was constructed with")
        void everyAccessorReturnsExactlyWhatItWasConstructedWith() {
            TransactionListRequest request = populated();

            assertThat(request.transactionIdFilter()).isEqualTo(TRANSACTION_ID);
            assertThat(request.rowSelectors()).containsExactlyElementsOf(TEN_SELECTORS);
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(request.navigationContext().programContext())
                    .isEqualTo(NavigationContext.ProgramContext.REENTER);
            assertThat(request.displayedPageNumber()).isEqualTo(DISPLAYED_PAGE_NUMBER);
            assertThat(request.pageMetadata().displayedPageNumber())
                    .isEqualTo(DISPLAYED_PAGE_NUMBER);
            assertThat(request.pageMetadata().nextCursorKey()).isEqualTo(NEXT_CURSOR);
        }
    }

    @Nested
    @DisplayName("The rendering discloses nothing while the wire carries everything")
    class TheRenderingDisclosesNothingWhileTheWireCarriesEverything {

        @Test
        @DisplayName("the rendering is exactly the retained values plus two placeholders")
        void theRenderingIsExactlyTheFourRetainedValuesPlusTwoPlaceholders() {
            assertThat(populated()).hasToString("TransactionListRequest["
                    + "transactionIdFilter=" + REDACTION_PLACEHOLDER_TEXT
                    + ", displayedPageNumber=" + DISPLAYED_PAGE_NUMBER
                    + ", rowSelectors=" + TEN_SELECTORS
                    + ", keyAction=" + KeyAction.PFK08
                    + ", navigationContext=" + NavigationContext.empty().withReEntry()
                    + ", pageMetadata=" + REDACTION_PLACEHOLDER_TEXT
                    + "]");
        }

        @Test
        @DisplayName("the typed transaction identifier appears nowhere in the rendering")
        void theTypedTransactionIdentifierAppearsNowhere() {
            assertThat(populated().toString()).doesNotContain(TRANSACTION_ID);
        }

        @Test
        @DisplayName("no six-character run of the identifier survives, so no partial disclosure is left")
        void noSixCharacterRunOfTheIdentifierSurvives() {
            String rendered = populated().toString();

            IntStream.rangeClosed(0, TRANSACTION_ID.length() - 6)
                    .mapToObj(start -> TRANSACTION_ID.substring(start, start + 6))
                    .forEach(fragment -> assertThat(rendered)
                            .as("fragment %s must not appear", fragment)
                            .doesNotContain(fragment));
        }

        @Test
        @DisplayName("the paging component is withheld whole rather than by delegation, so this type's "
                + "safety does not depend on another type's rendering")
        void thePagingComponentIsWithheldWholeRatherThanByDelegation() {
            String rendered = populated().toString();

            assertThat(rendered).contains("pageMetadata=" + REDACTION_PLACEHOLDER_TEXT);
            assertThat(rendered).doesNotContain("PageCursorRequest[");
            assertThat(rendered).doesNotContain(PREVIOUS_CURSOR);
            assertThat(rendered).doesNotContain(NEXT_CURSOR);
        }

        @Test
        @DisplayName("two instances differing only in the withheld values render identically, so the "
                + "placeholders are constants rather than transformations")
        void twoInstancesDifferingOnlyInTheWithheldValuesRenderIdentically() {
            TransactionListRequest first = new TransactionListRequest("1111111111111111",
                    DISPLAYED_PAGE_NUMBER, TEN_SELECTORS, KeyAction.PFK08, null, populatedCursor());
            TransactionListRequest second = new TransactionListRequest("9999999999999999",
                    DISPLAYED_PAGE_NUMBER, TEN_SELECTORS, KeyAction.PFK08, null,
                    new PageMetadata.PageCursorRequest(
                            "OTHER", "DIFFERENT", PageMetadata.PagingDirection.BACKWARD,
                            DISPLAYED_PAGE_NUMBER, true));

            assertThat(first).hasToString(second.toString());
        }

        @Test
        @DisplayName("an absent withheld value still renders as the placeholder, so absence and presence "
                + "are indistinguishable in a diagnostic")
        void anAbsentWithheldValueStillRendersAsThePlaceholder() {
            TransactionListRequest sparse =
                    new TransactionListRequest(null, null, null, null, null, null);

            assertThat(sparse.toString())
                    .contains("transactionIdFilter=" + REDACTION_PLACEHOLDER_TEXT)
                    .contains("pageMetadata=" + REDACTION_PLACEHOLDER_TEXT)
                    .doesNotContain("transactionIdFilter=null")
                    .doesNotContain("pageMetadata=null");
        }

        @Test
        @DisplayName("the retained values are retained, because withholding them would remove the "
                + "rendering's diagnostic value without protecting anything")
        void theRetainedValuesAreRetained() {
            String rendered = populated().toString();

            assertThat(rendered).contains("keyAction=" + KeyAction.PFK08);
            assertThat(rendered).contains("rowSelectors=");
        }

        @Test
        @DisplayName("the nested navigation state redacts its own identifying values, so printing it by "
                + "delegation discloses nothing further")
        void theNestedNavigationStateRedactsItsOwn() {
            NavigationContext identifying = new NavigationContext(null, null, null, null, "USER0001",
                    "U", NavigationContext.ProgramContext.REENTER, "000000007", "GRACE", null,
                    "HOPPER", "00000000099", "Y", "4111111111111111", null, null);
            TransactionListRequest request =
                    new TransactionListRequest(TRANSACTION_ID, null, null, null, identifying,
                            null);

            String rendered = request.toString();

            assertThat(rendered)
                    .doesNotContain("4111111111111111")
                    .doesNotContain("00000000099")
                    .doesNotContain("GRACE")
                    .doesNotContain("HOPPER");
        }

        @Test
        @DisplayName("the rendering is safe when every component is absent")
        void theRenderingIsSafeWhenEveryComponentIsAbsent() {
            TransactionListRequest empty =
                    new TransactionListRequest(null, null, null, null, null, null);

            assertThatCode(empty::toString).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the wire still carries every withheld value, because the rendering is a diagnostic "
                + "channel and not the transport")
        void theWireStillCarriesEveryWithheldValue() throws JsonProcessingException {
            ObjectMapper mapper = moduleEquivalentMapper();

            JsonNode payload = mapper.readTree(mapper.writeValueAsString(populated()));

            assertThat(payload.get("transactionIdFilter").asText()).isEqualTo(TRANSACTION_ID);
            assertThat(payload.get("pageMetadata").get("nextCursorKey").asText())
                    .isEqualTo(NEXT_CURSOR);
        }
    }

    @Nested
    @DisplayName("Bounds measure and never alter, and the structural bounds are not field edits")
    class BoundsMeasureAndNeverAlter {

        @Test
        @DisplayName("the filter carries the transaction key's own width and nothing else")
        void theFilterCarriesTheTransactionKeysOwnWidth() throws NoSuchFieldException {
            Field field = TransactionListRequest.class.getDeclaredField("transactionIdFilter");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH)
                    .isEqualTo(16);
            assertThat(size.min()).isZero();
            assertThat(field.getAnnotations()).hasSize(1);
        }

        @Test
        @DisplayName("the page label carries the screen field's own width and, beyond the binding that "
                + "makes it publish-only, nothing else")
        void thePageLabelCarriesTheScreenFieldsOwnWidth() throws NoSuchFieldException {
            Field field = TransactionListRequest.class.getDeclaredField("displayedPageNumber");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max())
                    .isEqualTo(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .isEqualTo(8);
            assertThat(Arrays.stream(field.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .sorted()
                    .toList())
                    .as("the width bound plus the binding that makes the label publish-only, and"
                            + " nothing else")
                    .containsExactly("JsonProperty", "Size");
        }

        @Test
        @DisplayName("the retained page label inside the echoed paging state carries the same width and "
                + "the lexical shape that stops an internally spaced value travelling further in")
        void theRetainedPageLabelCarriesTheSameWidthAndItsLexicalShape() throws NoSuchFieldException {
            Field field = PageMetadata.PageCursorRequest.class.getDeclaredField("displayedPageNumber");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max())
                    .isEqualTo(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .isEqualTo(8);
            assertThat(Arrays.stream(field.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .sorted()
                    .toList())
                    .containsExactly("Pattern", "Size");
        }

        @Test
        @DisplayName("no presence, pattern, digit, range or assertion constraint appears on any "
                + "component, because the browse program owns its own ordered cascade")
        void noPresenceOrFormatConstraintAppears() throws NoSuchFieldException {
            for (String name : COMPONENTS_IN_ORDER) {
                Field field = TransactionListRequest.class.getDeclaredField(name);

                assertThat(Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .toList())
                        .as("component %s", name)
                        .doesNotContain("NotNull", "NotBlank", "NotEmpty", "Pattern", "Digits",
                                "Min", "Max", "Positive", "AssertTrue");
            }
        }

        @Test
        @DisplayName("no component of the contract is wide enough to name a displayed row, so a "
                + "submission cannot assert which transactions the previous page showed")
        void noComponentCanCarryADisplayedRowIdentifier() {
            // A caller that wants the server to act on a row it names has exactly two places to put a
            // sixteen-character value. The first is a selector position, which is one byte wide and
            // reports a violation the moment a wider value arrives.
            assertThat(violationsOf(new TransactionListRequest(null, null,
                    List.of(A_FULL_WIDTH_IDENTIFIER), null, null, null)))
                    .as("a selector position is a mark, so an identifier typed into one is refused")
                    .hasSize(1);
            assertThat(TransactionListRequest.ROW_SELECTOR_LENGTH).isEqualTo(1);

            // The second is a sequence of them. The contract declares exactly one collection component
            // and it is that same one-byte selector sequence, so there is no list of identifiers to
            // submit and nothing for the browse to take on trust.
            assertThat(Arrays.stream(TransactionListRequest.class.getRecordComponents())
                    .filter(component -> Collection.class.isAssignableFrom(component.getType()))
                    .map(RecordComponent::getName).toList())
                    .containsExactly("rowSelectors");

            // The one sixteen-wide component that remains is the browse position, which is an operator
            // input in the legacy screen too: it says where to start reading, never which row to act on.
            assertThat(Arrays.stream(TransactionListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> boundOf(name) == TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH)
                    .toList())
                    .containsExactly("transactionIdFilter");
        }

        @Test
        @DisplayName("the selector sequence is bounded at the screen's ten rows, because an eleventh "
                + "selector corresponds to no row")
        void theSelectorSequenceIsBoundedAtTenRows() throws NoSuchFieldException {
            Field field = TransactionListRequest.class.getDeclaredField("rowSelectors");

            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(TransactionListRequest.ROW_SELECTOR_COUNT).isEqualTo(10);
        }

        @Test
        @DisplayName("the selector bound equals the page size the browse actually fills, so the two "
                + "figures cannot drift apart")
        void theSelectorBoundEqualsThePageSizeTheBrowseFills() {
            assertThat(TransactionListRequest.ROW_SELECTOR_COUNT)
                    .isEqualTo(PageMetadata.TRANSACTION_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("a sequence of exactly ten selectors draws no violation, so the bound admits a full "
                + "page")
        void aSequenceOfExactlyTenSelectorsDrawsNoViolation() {
            TransactionListRequest fullPage =
                    new TransactionListRequest(null, null, TEN_SELECTORS, null, null, null);

            assertThat(violationsOf(fullPage)).isEmpty();
            assertThat(fullPage.rowSelectors()).hasSize(10);
        }

        @Test
        @DisplayName("a sequence of eleven selectors is refused outright and never truncated")
        void aSequenceOfElevenSelectorsIsRefusedAndNeverTruncated() {
            List<String> eleven = Collections.nCopies(11, "S");

            // The arity of this sequence is structural rather than editorial: an eleventh keystroke
            // belongs to no row family the browse screen declares, so the value is refused at
            // construction rather than held and reported. Nothing over-length is therefore ever
            // retained, rendered or logged, which is a stronger outcome than a violation on a value the
            // request still carries. The declared width bound stays on the component so that the
            // published contract states the same limit the constructor enforces.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new TransactionListRequest(null, null, eleven, null, null, null))
                    .withMessageContaining("at most " + TransactionListRequest.ROW_COUNT)
                    .withMessageContaining("11");
        }

        @Test
        @DisplayName("an arbitrarily long sequence is refused on the same terms, so an unbounded "
                + "submission cannot be retained at all")
        void anArbitrarilyLongSequenceIsRefusedOnTheSameTerms() {
            List<String> farTooMany = Collections.nCopies(4096, "S");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new TransactionListRequest(null, null, farTooMany, null, null, null))
                    .withMessageContaining("4096");
        }

        @Test
        @DisplayName("the declared bound and the enforced bound are the same ten, so the published "
                + "contract cannot drift from what the constructor accepts")
        void theDeclaredAndEnforcedBoundsAgree() throws NoSuchFieldException {
            Field field = TransactionListRequest.class.getDeclaredField("rowSelectors");

            assertThat(field.getAnnotation(Size.class).max())
                    .isEqualTo(TransactionListRequest.ROW_SELECTOR_COUNT)
                    .isEqualTo(TransactionListRequest.ROW_COUNT)
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("an over-long selector element is still reported, so the container and element "
                + "bounds are independent")
        void anOverLongSelectorElementIsStillReported() {
            TransactionListRequest badElement =
                    new TransactionListRequest(null, null, List.of("SS"), null, null, null);

            Set<ConstraintViolation<TransactionListRequest>> violations = violationsOf(badElement);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .startsWith("rowSelectors");
        }

        @Test
        @DisplayName("an empty sequence and an absent one are both accepted")
        void anEmptyAndAnAbsentSequenceAreBothAccepted() {
            TransactionListRequest absent =
                    new TransactionListRequest(null, null, null, null, null, null);
            TransactionListRequest empty =
                    new TransactionListRequest(null, null, List.of(), null, null, null);

            assertThat(violationsOf(absent)).isEmpty();
            assertThat(violationsOf(empty)).isEmpty();
            assertThat(absent.rowSelectors()).isEmpty();
            assertThat(empty.rowSelectors()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("a blank filter is transported rather than rejected, because a blank filter means "
                + "browse from the beginning rather than an invalid submission")
        void aBlankFilterIsTransportedRatherThanRejected(String blank) {
            TransactionListRequest request =
                    new TransactionListRequest(blank, null, List.of(blank), null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.transactionIdFilter()).isEqualTo(blank);
        }

        @Test
        @DisplayName("a submission space-filled to each declared width draws no violation, because that "
                + "is the shape a blank browse screen transmits")
        void aSpaceFilledSubmissionDrawsNoViolation() {
            TransactionListRequest spaceFilled = new TransactionListRequest(" ".repeat(16),
                    " ".repeat(8), TEN_SELECTORS, null, null,
                    new PageMetadata.PageCursorRequest(
                            null, null, null, " ".repeat(8), false));

            assertThat(violationsOf(spaceFilled)).isEmpty();
            assertThat(spaceFilled.transactionIdFilter()).hasSize(16).isBlank();
        }

        @Test
        @DisplayName("a filter one character over its width is reported and never trimmed")
        void aFilterOneCharacterOverItsWidthIsReported() {
            TransactionListRequest tooWide =
                    new TransactionListRequest("1".repeat(17), null, null, null, null, null);

            Set<ConstraintViolation<TransactionListRequest>> violations = violationsOf(tooWide);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("transactionIdFilter");
            assertThat(tooWide.transactionIdFilter()).hasSize(17);
        }
    }

    @Nested
    @DisplayName("Both nested components are cascaded into")
    class BothNestedComponentsAreCascadedInto {

        @Test
        @DisplayName("the navigation component declares the cascade")
        void theNavigationComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = TransactionListRequest.class.getDeclaredField("navigationContext");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("the paging component declares the cascade")
        void thePagingComponentDeclaresTheCascade() throws NoSuchFieldException {
            Field field = TransactionListRequest.class.getDeclaredField("pageMetadata");

            assertThat(field.getAnnotation(Valid.class)).isNotNull();
        }

        @Test
        @DisplayName("a violation inside the echoed navigation state is reported under a nested property "
                + "path")
        void aViolationInsideTheEchoedNavigationStateIsReported() {
            NavigationContext overWidth = new NavigationContext(null, "NINECHARS", null, null, null,
                    null, NavigationContext.ProgramContext.REENTER, null, null, null, null, null,
                    null, null, null, null);
            TransactionListRequest request = new TransactionListRequest(null, null, null, null,
                    overWidth, null);

            Set<ConstraintViolation<TransactionListRequest>> violations = violationsOf(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .startsWith("navigationContext."));
        }

        @Test
        @DisplayName("a violation inside the echoed paging state is reported under a nested property "
                + "path, which is the observable proof the second cascade reaches it")
        void aViolationInsideTheEchoedPagingStateIsReported() {
            PageMetadata.PageCursorRequest overWidth =
                    new PageMetadata.PageCursorRequest(
                            "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1), null,
                            PageMetadata.PagingDirection.FORWARD, null, false);
            TransactionListRequest request =
                    new TransactionListRequest(null, null, null, null, null, overWidth);

            Set<ConstraintViolation<TransactionListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("pageMetadata.previousCursorKey");
        }

        @Test
        @DisplayName("the forward cursor is cascaded into as well, so the second cascade covers every "
                + "nested component rather than only the first one declared")
        void aViolationInTheForwardCursorIsReportedToo() {
            PageMetadata.PageCursorRequest overWidth =
                    new PageMetadata.PageCursorRequest(
                            null, "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH + 1),
                            PageMetadata.PagingDirection.BACKWARD, null, false);
            TransactionListRequest request =
                    new TransactionListRequest(null, null, null, null, null, overWidth);

            Set<ConstraintViolation<TransactionListRequest>> violations = violationsOf(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("pageMetadata.nextCursorKey");
        }

        @Test
        @DisplayName("the echoed paging state carries no caller-supplied page size at all, so the screen "
                + "bound cannot be raised from the wire rather than merely being bounded there")
        void theEchoedPagingStateCarriesNoCallerSuppliedPageSize() {
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
        @DisplayName("both absent nested components are not violations, because a first entry carries "
                + "neither")
        void bothAbsentNestedComponentsAreNotViolations() {
            TransactionListRequest request =
                    new TransactionListRequest(TRANSACTION_ID, null, null, null, null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.navigationContext()).isNull();
            assertThat(request.pageMetadata()).isNull();
        }

        @Test
        @DisplayName("a fully valid submission passes both cascades untouched")
        void aFullyValidSubmissionPassesBothCascades() {
            assertThat(violationsOf(populated())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Nothing is transformed on the way through")
    class NothingIsTransformedOnTheWayThrough {

        @Test
        @DisplayName("the selector sequence preserves order and length exactly, because element n is "
                + "the keystroke typed against screen row n")
        void theSelectorSequencePreservesOrderAndLengthExactly() {
            List<String> withGaps = Arrays.asList(" ", "U", " ", " ", "S", " ", " ", " ", " ", " ");

            TransactionListRequest request = new TransactionListRequest(null, null, withGaps, null,
                    null, null);

            assertThat(request.rowSelectors()).containsExactlyElementsOf(withGaps).hasSize(10);
            assertThat(request.rowSelectors().get(1)).isEqualTo("U");
            assertThat(request.rowSelectors().get(4)).isEqualTo("S");
        }

        @Test
        @DisplayName("the stored sequence is detached from the caller and immutable")
        void theStoredSequenceIsDetachedAndImmutable() {
            List<String> mutable = new ArrayList<>(TEN_SELECTORS);
            TransactionListRequest request = new TransactionListRequest(null, null, mutable, null,
                    null, null);

            mutable.set(0, "X");

            assertThat(request.rowSelectors().get(0)).isEqualTo(" ");
            assertThat(request.rowSelectors()).isUnmodifiable();
        }

        @Test
        @DisplayName("leading zeros survive in the filter and the page label, because both mirror "
                + "fixed-width character fields")
        void leadingZerosSurvive() {
            TransactionListRequest request = new TransactionListRequest("0000000000000001",
                    "00000001", null, null, null, null);

            assertThat(request.transactionIdFilter()).isEqualTo("0000000000000001");
            assertThat(request.displayedPageNumber()).isEqualTo("00000001");
        }

        @Test
        @DisplayName("a non-numeric filter round-trips, because the browse reports its own rejection "
                + "rather than having the boundary refuse the submission")
        void aNonNumericFilterRoundTrips() {
            TransactionListRequest request = new TransactionListRequest("ABCDEF", null, null, null,
                    null, null);

            assertThat(violationsOf(request)).isEmpty();
            assertThat(request.transactionIdFilter()).isEqualTo("ABCDEF");
        }

        @Test
        @DisplayName("equality compares every component including the withheld ones, because an "
                + "in-memory comparison emits nothing")
        void equalityComparesEveryComponentIncludingTheWithheldOnes() {
            TransactionListRequest first = populated();
            TransactionListRequest same = populated();
            TransactionListRequest differentFilter = new TransactionListRequest("0000000000000099",
                    DISPLAYED_PAGE_NUMBER, TEN_SELECTORS, KeyAction.PFK08,
                    NavigationContext.empty().withReEntry(), populatedCursor());

            assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
            assertThat(first).isNotEqualTo(differentFilter);
        }

        @Test
        @DisplayName("a document naming every component deserializes with each value intact, so the "
                + "replaced rendering did not disturb the wire contract")
        void aDocumentNamingEveryComponentDeserializesIntact() throws JsonProcessingException {
            String document = "{\"transactionIdFilter\":\"" + TRANSACTION_ID + "\","
                    + "\"displayedPageNumber\":\"" + DISPLAYED_PAGE_NUMBER + "\","
                    + "\"rowSelectors\":[\" \",\"S\"],"
                    + "\"keyAction\":\"PFK07\","
                    + "\"pageMetadata\":{\"displayedPageNumber\":\""
                    + DISPLAYED_PAGE_NUMBER + "\"}}";

            TransactionListRequest request =
                    moduleEquivalentMapper().readValue(document, TransactionListRequest.class);

            assertThat(request.transactionIdFilter()).isEqualTo(TRANSACTION_ID);
            assertThat(request.displayedPageNumber())
                    .as("the label is publish-only, so a submitted one is discarded rather than"
                            + " believed")
                    .isNull();
            assertThat(request.pageMetadata().displayedPageNumber())
                    .isEqualTo(DISPLAYED_PAGE_NUMBER);
            assertThat(request.rowSelectors()).containsExactly(" ", "S");
            assertThat(request.keyAction()).isEqualTo(KeyAction.PFK07);
        }
    }
}
