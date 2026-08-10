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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TransactionListRequest}, the request body of legacy transaction {@code CT00}.
 *
 * <h2>What is under test</h2>
 *
 * <p>The transport contract of the transaction-list submission: the six components, the three
 * declared widths, the one piece of normalising the canonical constructor performs, the wire form
 * under the module's declared serialisation settings, and the value semantics of a record that
 * carries a sequence.
 *
 * <h2>The selectors are a sequence here, and seven separate components on the card-list screen</h2>
 *
 * <p>That difference is not an inconsistency and the tests below treat it as deliberate. The
 * card-list program declares an explicit table of seven occurrences, so seven positional components
 * restate a declaration. This program declares no table at all: the ten rows emerge from loop bounds
 * - a clearing bound, an index reset and a filling loop that stops once the index passes the last row
 * - so there is no declaration to translate into a component count. What the sequence does need is a
 * bound, because a selector's position <em>is</em> a row: the count is therefore restated on this
 * record as a named value and the canonical constructor refuses a deeper sequence. Tests below prove
 * that the value is published, that it is ten, and that the refusal names the offending count.
 *
 * <p>That the shared paging record also publishes a transaction-list page size of ten is not a
 * duplicate of the same statement. The page size describes how many rows a browse returns; the value
 * here describes how many selector positions the map declares, and a hand-built submission can be
 * malformed with respect to the second with no paging data present at all. A test below proves the
 * two agree, which is the coincidence worth pinning rather than the reason for either.
 *
 * <h2>The one thing the canonical constructor normalises, and the one thing it refuses</h2>
 *
 * <p>An absent sequence becomes the empty immutable sequence, because a submission that marked no
 * row still has a usable shape. A supplied sequence is copied, which both detaches it from
 * caller-owned state and <em>rejects a null entry</em>. The rejection is the interesting half: the
 * wire form of an unmarked row is the empty string, exactly as a fixed-width screen item transmits
 * blanks, so a null entry is a caller defect rather than an unmarked row, and accepting one would
 * leave an index-aligned sequence carrying an entry that means nothing. Tests below prove the
 * refusal at construction and again across deserialisation, and prove that blank entries - which
 * <em>are</em> unmarked rows - survive untouched.
 *
 * <h2>Index alignment is the contract, so nothing may be dropped or re-ordered</h2>
 *
 * <p>The index says which displayed transaction was chosen, because the program pairs each selector
 * with the row value beside it. Compacting the sequence, de-duplicating it or keying it would shift
 * every later entry and silently re-point the selection at the wrong row. Tests below prove that a
 * sequence of ten entries of which one is marked keeps its nine blanks and its position, that
 * duplicate blanks are not collapsed, and that the projection is neither re-ordered nor interpreted.
 *
 * <h2>Two of the six components are withheld from the diagnostic rendering</h2>
 *
 * <p>This type carries no primary account number and no cardholder name, and the page number, the
 * ten single-character marks and the attention key identify nobody, so all three are rendered. Two
 * components are not. The transaction key names one card holder's single transaction, and the cursor
 * carrier holds the browse keys either side of it, which are transaction keys by another name; a
 * diagnostic that printed either would put a specific person's spending in a log file. Both are
 * therefore replaced by a fixed marker, and tests below prove the marker is present and the value
 * absent. The compensating assertion is unchanged: a nested {@link NavigationContext} - which does
 * carry identifying values - withholds its own, so this type cannot become the path by which they
 * surface.
 *
 * <h2>One component is bound in one direction only</h2>
 *
 * <p>The page number is written outbound and ignored inbound. The legacy program computes the
 * displayed page from its own retained counter, so a submitted value could never have influenced a
 * page, and binding one would create an input the legacy never had and a client could steer.
 * Discarding it rather than refusing it keeps a client free to echo a response straight back. The
 * consequence is that a round trip cannot return an equal instance, so the test below asserts the
 * loss is precisely that one component and that everything else - selector order and paging direction
 * included - survives.
 *
 * <h2>How the wire form is observed, and what that does and does not prove</h2>
 *
 * <p>A pure unit test: no context, no connection, no container. Payloads come from
 * {@link JsonContractSupport#declaredSettingsMapper()}, the single place in this package where the
 * module's four declared serialisation settings are written by hand. That evidences the shape this
 * type takes under those settings and makes no claim about the mapper a deployed instance holds;
 * {@link ApplicationJsonContractTest} is the in-boundary evidence for the deployed object.
 *
 * <p>Provenance: checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is reproduced.
 */
@DisplayName("TransactionListRequest :: transaction-list request contract of legacy transaction CT00")
class TransactionListRequestCoverageTest {

    /** The five components in declaration order. */
    private static final List<String> EXPECTED_COMPONENTS = List.of(
            "transactionIdFilter", "displayedPageNumber", "rowSelectors", "keyAction",
            "navigationContext", "pageMetadata");

    /** Declared width of the transaction-identifier filter, restated from the symbolic map. */
    private static final int EXPECTED_FILTER_WIDTH = 16;

    /** Declared width of the displayed page number, restated from the symbolic map. */
    private static final int EXPECTED_DISPLAYED_PAGE_NUMBER_WIDTH = 8;

    /** Declared width of one row selector, restated from the symbolic map. */
    private static final int EXPECTED_SELECTOR_WIDTH = 1;

    /** Number of rows the transaction-list screen presents, measured from the legacy loop bounds. */
    private static final int EXPECTED_ROW_COUNT = 10;

    /** A transaction-identifier filter at exactly the declared width. */
    private static final String FILTER = "0000000000000001";

    /** Shared validator factory, opened once and closed once. */
    private static ValidatorFactory validatorFactory;

    /** Validator drawn from {@link #validatorFactory}. */
    private static Validator validator;

    /** Opens the validator factory used by the bound assertions. */
    @BeforeAll
    static void openValidatorFactory() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Closes the validator factory opened by {@link #openValidatorFactory()}. */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    /**
     * Builds a request carrying only the supplied selector sequence.
     *
     * @param selectors the selector sequence, which may be {@code null}
     * @return a request with no filter, indicator, key, navigation or paging state
     */
    private static TransactionListRequest carryingSelectors(List<String> selectors) {
        return new TransactionListRequest(null, null, selectors, null, null, null);
    }

    /**
     * Builds a ten-entry selector sequence in which exactly one row is marked.
     *
     * @param markedRowIndex the zero-based index of the marked row
     * @param mark the value to place on that row
     * @return a ten-entry sequence whose other nine entries are blank
     */
    private static List<String> tenRowsWithOneMark(int markedRowIndex, String mark) {
        List<String> rows = new ArrayList<>(Collections.nCopies(EXPECTED_ROW_COUNT, " "));
        rows.set(markedRowIndex, mark);
        return rows;
    }

    /**
     * Serialises a request and parses the result back into a tree.
     *
     * @param request the request to render
     * @return the parsed payload
     * @throws JsonProcessingException if rendering or parsing fails
     */
    private static JsonNode payloadOf(TransactionListRequest request)
            throws JsonProcessingException {
        ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
        return mapper.readTree(mapper.writeValueAsString(request));
    }

    @Nested
    @DisplayName("Declared contract")
    class DeclaredContract {

        @Test
        @DisplayName("the six components are declared in the order the screen submits them")
        void componentsAreDeclaredInScreenOrder() {
            List<String> declared = Arrays.stream(
                            TransactionListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).containsExactlyElementsOf(EXPECTED_COMPONENTS);
        }

        @Test
        @DisplayName("the three published widths equal the widths the symbolic map declares")
        void publishedWidthsEqualTheMapWidths() {
            assertThat(TransactionListRequest.TRANSACTION_ID_FILTER_LENGTH)
                    .isEqualTo(EXPECTED_FILTER_WIDTH);
            assertThat(TransactionListRequest.DISPLAYED_PAGE_NUMBER_LENGTH)
                    .as("this screen's indicator is eight characters; the card-list screen carries a "
                            + "differently named indicator of width three, and the two are "
                            + "deliberately not unified")
                    .isEqualTo(EXPECTED_DISPLAYED_PAGE_NUMBER_WIDTH);
            assertThat(TransactionListRequest.ROW_SELECTOR_LENGTH)
                    .isEqualTo(EXPECTED_SELECTOR_WIDTH);
        }

        @Test
        @DisplayName("the row count the legacy loop bounds establish is restated here, alongside the "
                + "three widths and the selector count")
        void theStaticInventoryIsTheExpectedOne() {
            List<String> published = Arrays.stream(TransactionListRequest.class.getDeclaredFields())
                    .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                    .map(java.lang.reflect.Field::getName)
                    .toList();

            assertThat(published)
                    .as("the ten rows emerge from loop bounds rather than from a row table, and the "
                            + "count is restated here so a reader need not re-derive it; the "
                            + "withholding marker is an implementation detail of the rendering")
                    .containsExactlyInAnyOrder("TRANSACTION_ID_FILTER_LENGTH",
                            "DISPLAYED_PAGE_NUMBER_LENGTH", "ROW_SELECTOR_LENGTH", "ROW_COUNT",
                            "ROW_SELECTOR_COUNT", "REDACTION_PLACEHOLDER");
            assertThat(TransactionListRequest.ROW_COUNT)
                    .as("the loop runs from one until the index passes ten")
                    .isEqualTo(EXPECTED_ROW_COUNT);
            assertThat(TransactionListRequest.ROW_SELECTOR_COUNT)
                    .as("one selector position per presented row")
                    .isEqualTo(TransactionListRequest.ROW_COUNT);
        }

        @Test
        @DisplayName("the transaction-list page size the shared paging record publishes is ten")
        void thePublishedPageSizeIsTen() {
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE).isEqualTo(EXPECTED_ROW_COUNT);
            assertThat(PageMetadata.TRANSACTION_LIST_PAGE_SIZE)
                    .as("the administrative user list happens to agree, but the two screens were "
                            + "established by different mechanisms and the constants are declared "
                            + "separately on purpose")
                    .isEqualTo(PageMetadata.USER_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("the selector sequence, the attention key, the navigation state and the paging "
                + "state are all typed rather than loose strings")
        void theRemainingComponentsAreTyped() {
            RecordComponent[] components = TransactionListRequest.class.getRecordComponents();

            assertThat(components[1].getType()).isEqualTo(String.class);
            assertThat(components[2].getType()).isEqualTo(List.class);
            assertThat(components[3].getType()).isEqualTo(KeyAction.class);
            assertThat(components[4].getType()).isEqualTo(NavigationContext.class);
            assertThat(components[5].getType())
                    .isEqualTo(PageMetadata.PageCursorRequest.class);
        }

        @Test
        @DisplayName("no framework paging abstraction is declared, so the request stays expressed in "
                + "the legacy screen's own terms")
        void noFrameworkPagingAbstractionIsDeclared() {
            List<String> componentTypes = Arrays.stream(
                            TransactionListRequest.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .toList();

            assertThat(componentTypes)
                    .as("a data-access paging type here would leak a layer above this package into "
                            + "the published contract")
                    .noneMatch(name -> name.startsWith("org.springframework"));
        }
    }

    @Nested
    @DisplayName("Selector normalisation")
    class SelectorNormalisation {

        @Test
        @DisplayName("an absent sequence becomes the empty sequence, which is the shape of a "
                + "submission that marked no row")
        void anAbsentSequenceBecomesTheEmptySequence() {
            assertThat(carryingSelectors(null).rowSelectors())
                    .as("accessors and payloads always see a usable sequence rather than a null")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("a supplied sequence is detached from the caller, so a later mutation cannot "
                + "change what the request reports")
        void aSuppliedSequenceIsDetachedFromTheCaller() {
            List<String> callerOwned = new ArrayList<>(tenRowsWithOneMark(0, "S"));

            TransactionListRequest request = carryingSelectors(callerOwned);
            callerOwned.set(0, "U");
            callerOwned.add("EXTRA");

            assertThat(request.rowSelectors())
                    .hasSize(EXPECTED_ROW_COUNT)
                    .element(0).isEqualTo("S");
        }

        @Test
        @DisplayName("the retained sequence rejects mutation by its holder as well as by its "
                + "supplier")
        void theRetainedSequenceRejectsMutation() {
            List<String> retained = carryingSelectors(tenRowsWithOneMark(3, "S")).rowSelectors();

            assertThatThrownBy(() -> retained.set(0, "S"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> retained.add("S"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(retained::clear)
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null entry is refused at construction, because the wire form of an unmarked "
                + "row is the empty string rather than an absent value")
        void aNullEntryIsRefusedAtConstruction() {
            List<String> withNullEntry = new ArrayList<>(
                    Arrays.asList(" ", null, " ", " ", " ", " ", " ", " ", " ", " "));

            assertThatThrownBy(() -> carryingSelectors(withNullEntry))
                    .as("accepting one would leave an index-aligned sequence carrying an entry that "
                            + "means nothing")
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank and empty entries survive untouched, because a blank slot is what says "
                + "that row was not chosen")
        void blankAndEmptyEntriesSurviveUntouched() {
            List<String> mixed = List.of("S", " ", "", "  ", "u", "", " ", "U", "", " ");

            assertThat(carryingSelectors(mixed).rowSelectors())
                    .containsExactly("S", " ", "", "  ", "u", "", " ", "U", "", " ");
        }

        @Test
        @DisplayName("nine blanks are preserved beside one mark, and duplicate blanks are not "
                + "collapsed, because compacting would re-point the selection at the wrong row")
        void blanksAreNeitherDroppedNorCollapsed() {
            TransactionListRequest request = carryingSelectors(tenRowsWithOneMark(6, "S"));

            assertThat(request.rowSelectors())
                    .as("a de-duplicating or compacting collection would shift every later entry")
                    .hasSize(EXPECTED_ROW_COUNT);
            assertThat(request.rowSelectors().get(6)).isEqualTo("S");
            assertThat(request.rowSelectors().stream().filter(" "::equals))
                    .hasSize(EXPECTED_ROW_COUNT - 1);
        }

        @Test
        @DisplayName("the sequence is neither re-ordered nor interpreted: no scan, no tally, no case "
                + "fold and no first-match resolution happens here")
        void theSequenceIsNeitherReOrderedNorInterpreted() {
            List<String> supplied = List.of("u", "S", " ", "X", "1", "?", "", "S", " ", "s");

            assertThat(carryingSelectors(supplied).rowSelectors())
                    .as("stop-at-first-non-blank resolution and the accepted-letter check both "
                            + "belong to the transaction-list service")
                    .containsExactlyElementsOf(supplied);
        }

        /**
         * A sequence deeper than the screen is refused, because a selector's position is a row.
         *
         * <p>Each selector marks the row family at the same index, so a sequence longer than the screen
         * has no defined meaning: the surplus entries mark rows the screen does not present. Carrying it
         * would defer the failure to whichever layer next read a selector by index, and truncating would
         * discard a submitted mark silently. Refusing names the offending count where the defect is
         * introduced, and the count is asserted so the diagnostic is actionable.</p>
         *
         * <p>The screen's depth is declared on this record rather than left to the paging data. That is
         * not a second statement of the same fact: the paging record's page size describes how many rows
         * a browse returns, while this bound describes how many selector positions the map declares, and
         * a request can be malformed with respect to the second without any paging data being present at
         * all - which is exactly the case a client submitting a hand-built payload produces.</p>
         */
        @Test
        @DisplayName("a sequence deeper than the screen's ten rows is refused, and the refusal names "
                + "how many selectors arrived")
        void anOverLongSequenceIsRefused() {
            List<String> twelve = Collections.nCopies(EXPECTED_ROW_COUNT + 2, " ");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> carryingSelectors(twelve))
                    .withMessageContaining("at most " + EXPECTED_ROW_COUNT)
                    .withMessageContaining("row families the transaction-list screen declares")
                    .withMessageContaining("it holds " + (EXPECTED_ROW_COUNT + 2));

            assertThat(carryingSelectors(Collections.nCopies(EXPECTED_ROW_COUNT, " "))
                            .rowSelectors())
                    .as("a sequence at exactly the screen's depth is accepted, so the bound is "
                            + "inclusive")
                    .hasSize(EXPECTED_ROW_COUNT);
        }
    }

    /**
     * Reading the echoed page indicator is a total operation over every value the pattern admits.
     *
     * <p>The indicator is a value the client echoes back, so this type cannot assume it is well formed.
     * It is read on the way in to decide which page to fetch, which means a value the reader cannot
     * make sense of has to resolve to a page number rather than propagate a parse failure: an operator
     * who edits or corrupts the field they were handed must get a screen back, not a server fault. The
     * declared pattern narrows what can arrive at all, and this reader answers whatever still does.</p>
     */
    @Nested
    @DisplayName("Reading the echoed page indicator")
    class ReadingTheEchoedPageIndicator {

        /**
         * Builds a continuation carrying only the supplied page indicator.
         *
         * @param displayedPageNumber the indicator as echoed back, possibly {@code null}
         * @return the continuation
         */
        private static PageMetadata.PageCursorRequest echoing(
                final String displayedPageNumber) {
            return new PageMetadata.PageCursorRequest(
                    null, null, null, displayedPageNumber, false);
        }

        @Test
        @DisplayName("a well-formed indicator reads as the number it spells, with the map's padding "
                + "ignored on either side")
        void aWellFormedIndicatorReadsAsItsNumber() {
            assertThat(echoing("00000003").retainedPageNumber()).isEqualTo(3);
            assertThat(echoing("3       ").retainedPageNumber()).isEqualTo(3);
            assertThat(echoing("      42").retainedPageNumber()).isEqualTo(42);
            assertThat(echoing("  7     ").retainedPageNumber()).isEqualTo(7);
        }

        @Test
        @DisplayName("an absent, empty or all-space indicator reads as no page rather than throwing")
        void anAbsentIndicatorReadsAsNoPage() {
            assertThat(echoing(null).retainedPageNumber()).isZero();
            assertThat(echoing("").retainedPageNumber()).isZero();
            assertThat(echoing("        ").retainedPageNumber()).isZero();
        }

        /**
         * The value that previously produced a server fault.
         *
         * <p>An embedded space between two digit runs satisfied the earlier pattern and then failed to
         * parse, so a corrupted echo became a generic fault rather than a screen. The reader now answers
         * it, and the tightened pattern refuses it before the reader is even reached.</p>
         */
        @ParameterizedTest(name = "an indicator of \"{0}\" reads as no page instead of faulting")
        @ValueSource(strings = {"1 2", " 1 2 ", "1  2", "12 34"})
        @DisplayName("an indicator with an embedded space reads as no page rather than faulting")
        void anIndicatorWithAnEmbeddedSpaceReadsAsNoPage(String echoed) {
            assertThatCode(() -> echoing(echoed).retainedPageNumber()).doesNotThrowAnyException();

            assertThat(echoing(echoed).retainedPageNumber()).isZero();
        }

        @Test
        @DisplayName("an indicator carrying a non-digit reads as no page rather than faulting")
        void anIndicatorCarryingANonDigitReadsAsNoPage() {
            assertThat(echoing("PAGE0001").retainedPageNumber()).isZero();
            assertThat(echoing("-1").retainedPageNumber()).isZero();
            assertThat(echoing("+3").retainedPageNumber()).isZero();
            assertThat(echoing("1.5").retainedPageNumber()).isZero();
            assertThat(echoing("\u0000").retainedPageNumber()).isZero();
        }

        /**
         * A value that would overflow the accumulator is refused by width before it can be read.
         */
        @Test
        @DisplayName("an indicator longer than the map's field reads as no page")
        void anOverWideIndicatorReadsAsNoPage() {
            assertThat(echoing("9".repeat(EXPECTED_DISPLAYED_PAGE_NUMBER_WIDTH + 1)).retainedPageNumber())
                    .isZero();
            assertThat(echoing("9".repeat(40)).retainedPageNumber()).isZero();
        }

        /**
         * The declared pattern is the first line of defence, and it now refuses embedded spaces.
         *
         * <p>Rejecting the value at validation is the better outcome, because the operator is told the
         * field is wrong rather than silently returned to page one. The reader's totality is the second
         * line of defence, for any path that reaches it without validation having run.</p>
         */
        @Test
        @DisplayName("the declared pattern refuses an embedded space, so the malformed echo is "
                + "reported as a violation rather than silently reset")
        void theDeclaredPatternRefusesAnEmbeddedSpace() {
            TransactionListRequest request = new TransactionListRequest(
                    null, null, null, null, NavigationContext.empty(), echoing("1 2"));

            assertThat(validator.validate(request)).singleElement()
                    .satisfies(violation -> assertThat(violation.getPropertyPath())
                            .hasToString("pageMetadata.displayedPageNumber"));
        }

        @Test
        @DisplayName("the declared pattern still admits the padded forms the map actually produces")
        void theDeclaredPatternAdmitsThePaddedForms() {
            for (String admitted : List.of("00000001", "1       ", "       1", "  7     ", "",
                    "        ")) {
                TransactionListRequest request = new TransactionListRequest(
                        null, null, null, null, NavigationContext.empty(), echoing(admitted));

                assertThat(validator.validate(request))
                        .as("the map produces \"%s\", so validation has to admit it", admitted)
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Validation bounds")
    class ValidationBounds {

        @Test
        @DisplayName("a request whose every component sits exactly at its declared width reports no "
                + "violation")
        void aRequestAtEveryDeclaredWidthReportsNoViolation() {
            TransactionListRequest request = new TransactionListRequest(
                    FILTER, "00000001", tenRowsWithOneMark(0, "S"), KeyAction.PFK08,
                    NavigationContext.empty(),
                    new PageMetadata.PageCursorRequest(
                            "1".repeat(EXPECTED_FILTER_WIDTH),
                            "2".repeat(EXPECTED_FILTER_WIDTH),
                            PageMetadata.PagingDirection.FORWARD,
                            "00000001", true));

            assertThat(validator.validate(request)).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value one character over {1}")
        @CsvSource({
            "transactionIdFilter,16",
            "displayedPageNumber,8",
            "pageMetadata.displayedPageNumber,8",
            "pageMetadata.previousCursorKey,16",
            "pageMetadata.nextCursorKey,16",
        })
        @DisplayName("each bounded text component reports a value one character over its width")
        void eachBoundedTextComponentReportsAnOverLongValue(String component, int width) {
            String tooLong = "9".repeat(width + 1);
            PageMetadata.PageCursorRequest carriedPaging =
                    new PageMetadata.PageCursorRequest(
                            "pageMetadata.previousCursorKey".equals(component) ? tooLong : null,
                            "pageMetadata.nextCursorKey".equals(component) ? tooLong : null,
                            null,
                            "pageMetadata.displayedPageNumber".equals(component) ? tooLong : null,
                            false);
            TransactionListRequest request = new TransactionListRequest(
                    "transactionIdFilter".equals(component) ? tooLong : null,
                    "displayedPageNumber".equals(component) ? tooLong : null,
                    null, null, null, carriedPaging);

            Set<ConstraintViolation<TransactionListRequest>> violations = validator.validate(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
        }

        @Test
        @DisplayName("an over-long selector entry is reported against its own index, so the "
                + "offending row can be named")
        void anOverLongSelectorEntryIsReportedAgainstItsIndex() {
            List<String> rows = tenRowsWithOneMark(4, "SS");

            Set<ConstraintViolation<TransactionListRequest>> violations =
                    validator.validate(carryingSelectors(rows));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .as("the bound is declared on the sequence's element type, so the reported path "
                            + "carries the index rather than naming the sequence as a whole")
                    .startsWith("rowSelectors[4]");
        }

        @Test
        @DisplayName("two over-long selector entries are reported once each, against two different "
                + "indexes")
        void twoOverLongSelectorEntriesAreReportedSeparately() {
            List<String> rows = new ArrayList<>(tenRowsWithOneMark(0, "SS"));
            rows.set(9, "UU");

            Set<ConstraintViolation<TransactionListRequest>> violations =
                    validator.validate(carryingSelectors(rows));

            assertThat(violations).hasSize(2);
            assertThat(violations.stream()
                            .map(violation -> violation.getPropertyPath().toString())
                            .toList())
                    .anyMatch(path -> path.startsWith("rowSelectors[0]"))
                    .anyMatch(path -> path.startsWith("rowSelectors[9]"));
        }

        @Test
        @DisplayName("an entirely empty request reports no violation, which is the state of a first "
                + "entry into the screen")
        void anEntirelyEmptyRequestReportsNoViolation() {
            assertThat(validator.validate(
                            new TransactionListRequest(null, null, null, null, null, null)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "filter value \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"", " ", "                ", "ABCDEFGHIJKLMNOP", "0000000000000001"})
        @DisplayName("a blank or non-numeric filter is accepted by the boundary, because the blank "
                + "case is a legitimate submission and the numeric check is a message-bearing "
                + "service stage")
        void aBlankOrNonNumericFilterIsAcceptedByTheBoundary(String filter) {
            assertThat(validator.validate(
                            new TransactionListRequest(filter, null, null, null, null, null)))
                    .isEmpty();
        }

        @ParameterizedTest(name = "selector \"{0}\" is accepted by the boundary")
        @ValueSource(strings = {"S", "s", "U", "u", "X", "1", " ", "?"})
        @DisplayName("the selector bound restricts width only and names no acceptable character, "
                + "because the accepted-letter check carries one specific message")
        void theSelectorBoundNamesNoAcceptableCharacter(String selector) {
            assertThat(validator.validate(carryingSelectors(List.of(selector)))).isEmpty();
        }

        @Test
        @DisplayName("all ten rows marked reports no violation, because first-non-blank resolution "
                + "ignores the later marks rather than rejecting the submission")
        void allTenRowsMarkedReportsNoViolation() {
            assertThat(validator.validate(
                            carryingSelectors(Collections.nCopies(EXPECTED_ROW_COUNT, "S"))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Wire shape")
    class WireShape {

        @Test
        @DisplayName("a fully populated request renders all six components in the body")
        void aFullyPopulatedRequestRendersAllSixMembers() throws JsonProcessingException {
            TransactionListRequest request = new TransactionListRequest(
                    FILTER, "00000002", tenRowsWithOneMark(2, "S"), KeyAction.PFK07,
                    JsonContractSupport.populatedNavigation(),
                    new PageMetadata.PageCursorRequest(
                            "prev", "next", PageMetadata.PagingDirection.BACKWARD,
                            "00000002", true));

            JsonNode payload = payloadOf(request);

            assertThat(payload.size()).isEqualTo(EXPECTED_COMPONENTS.size());
            assertThat(payload.get("transactionIdFilter").asText()).isEqualTo(FILTER);
            assertThat(payload.get("keyAction").asText()).isEqualTo("PFK07");
            assertThat(payload.get("displayedPageNumber").asText()).isEqualTo("00000002");
            assertThat(payload.get("pageMetadata").get("displayedPageNumber").asText())
                    .isEqualTo("00000002");
            assertThat(payload.get("pageMetadata").get("direction").asText())
                    .as("the backward direction is the one whose rows are read bottom-up and "
                            + "re-ordered by the service, so it has to survive the round trip")
                    .isEqualTo("BACKWARD");
        }

        @Test
        @DisplayName("the selectors render as an array in row order, with every blank slot present, "
                + "so the array index stays the row index")
        void theSelectorsRenderAsAnArrayInRowOrder() throws JsonProcessingException {
            JsonNode selectors = payloadOf(carryingSelectors(tenRowsWithOneMark(7, "S")))
                    .get("rowSelectors");

            assertThat(selectors.isArray()).isTrue();
            assertThat(selectors).hasSize(EXPECTED_ROW_COUNT);
            assertThat(selectors.get(7).asText()).isEqualTo("S");
            assertThat(selectors.get(0).asText())
                    .as("dropping the blank slots on the wire would shift every later entry")
                    .isEqualTo(" ");
        }

        @Test
        @DisplayName("an empty selector sequence is written as an empty array rather than omitted, "
                + "because omission is reserved for an absent value")
        void anEmptySelectorSequenceIsWrittenAsAnEmptyArray() throws JsonProcessingException {
            JsonNode payload = payloadOf(carryingSelectors(null));

            assertThat(payload.has("rowSelectors")).isTrue();
            assertThat(payload.get("rowSelectors")).isEmpty();
            assertThat(payload.size())
                    .as("the other five components are absent and are therefore omitted")
                    .isEqualTo(1);
            assertThat(payload.has("pageMetadata")).isFalse();
        }

        /**
         * The round trip preserves everything except the displayed page label.
         *
         * <p>One component is bound in one direction only. The label is written outbound - a response
         * has to be able to tell a client which page it is looking at - and ignored inbound, because it
         * mirrors the protected map item the program writes and never reads back. A round trip therefore
         * cannot return an equal instance, and asserting that it does would require reopening the
         * inbound direction.</p>
         *
         * <p>What is asserted instead is that the loss is precisely that one component and that
         * everything else survives, selector order and paging direction included. That is stronger than
         * equality would have been, because it names what may change and would fail if a second
         * component silently acquired a directional binding. The <em>retained</em> figure inside the
         * paging state does survive, and it is the one a turn takes its page decisions from.</p>
         */
        @Test
        @DisplayName("a fully populated request round trips without its publish-only page label")
        void aFullyPopulatedRequestRoundTripsWithoutItsPublishOnlyPageLabel()
                throws JsonProcessingException {
            TransactionListRequest request = new TransactionListRequest(
                    FILTER, "00000007", List.of("S", " ", "", "  ", "u", "", " ", "U", "", " "),
                    KeyAction.PFK08, JsonContractSupport.populatedNavigation(),
                    new PageMetadata.PageCursorRequest(
                            "p", "n", PageMetadata.PagingDirection.FORWARD,
                            "00000007", true));

            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            TransactionListRequest returned = mapper.readValue(
                    mapper.writeValueAsString(request), TransactionListRequest.class);

            assertThat(returned.displayedPageNumber())
                    .as("bound outbound only, so a submitted label cannot substitute a page the server "
                            + "did not compute")
                    .isNull();
            assertThat(returned.transactionIdFilter()).isEqualTo(FILTER);
            assertThat(returned.keyAction()).isEqualTo(KeyAction.PFK08);
            assertThat(returned.navigationContext())
                    .isEqualTo(JsonContractSupport.populatedNavigation());
            assertThat(returned.pageMetadata().displayedPageNumber()).isEqualTo("00000007");
            assertThat(returned.pageMetadata().direction())
                    .isEqualTo(PageMetadata.PagingDirection.FORWARD);
            assertThat(returned.rowSelectors())
                    .containsExactly("S", " ", "", "  ", "u", "", " ", "U", "", " ");
        }

        @Test
        @DisplayName("a null entry inside an inbound selector array is refused, so the constructor's "
                + "refusal is not bypassed by deserialisation")
        void aNullEntryInsideAnInboundArrayIsRefused() {
            ObjectMapper mapper = JsonContractSupport.declaredSettingsMapper();
            String payload = "{\"rowSelectors\":[\" \",null,\" \"]}";

            assertThatThrownBy(() -> mapper.readValue(payload, TransactionListRequest.class))
                    .as("inbound binding goes through the canonical constructor, so the refusal "
                            + "holds on the path a client actually takes")
                    .isInstanceOf(JsonProcessingException.class)
                    .hasRootCauseInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an absent selector member deserialises to the empty sequence rather than a "
                + "null one")
        void anAbsentSelectorMemberDeserialisesToTheEmptySequence()
                throws JsonProcessingException {
            TransactionListRequest returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue("{\"transactionIdFilter\":\"" + FILTER + "\"}",
                            TransactionListRequest.class);

            assertThat(returned.rowSelectors()).isNotNull().isEmpty();
            assertThat(returned.transactionIdFilter()).isEqualTo(FILTER);
        }

        /**
         * An undeclared member is ignored, and so is the read-only page number a client echoes back.
         *
         * <p>The page number is bound read-only, so a submitted value is discarded exactly as an
         * undeclared member is. That is deliberate rather than incidental: the legacy program computes
         * the displayed page entirely from its own retained counter, so a submitted value could never
         * have influenced a page, and accepting one would create an input the legacy never had and a
         * client could steer. Discarding it rather than refusing it is what keeps a client free to echo
         * a response back without being rejected, which is the tolerance this test is about.</p>
         */
        @Test
        @DisplayName("an unknown member a client echoes back is ignored rather than rejected, and so "
                + "is the read-only page number")
        void anUnknownMemberIsIgnored() throws JsonProcessingException {
            String payload = "{\"displayedPageNumber\":\"00000003\",\"rows\":[],"
                    + "\"errorMessage\":\"x\",\"transactionIdFilter\":\"" + FILTER + "\"}";

            TransactionListRequest returned = JsonContractSupport.declaredSettingsMapper()
                    .readValue(payload, TransactionListRequest.class);

            assertThat(returned.transactionIdFilter())
                    .as("the declared, bindable member is still bound")
                    .isEqualTo(FILTER);
            assertThat(returned.displayedPageNumber())
                    .as("a submitted page number is discarded, because the server computes the "
                            + "displayed page and a client may not assert it")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Diagnostic rendering")
    class DiagnosticRendering {

        @Test
        @DisplayName("the rendering names all six members and replaces those that identify a "
                + "card holder's transaction with a fixed marker")
        void theRenderingWithholdsTheBrowseKeyAndTheCursor() {
            TransactionListRequest request = new TransactionListRequest(
                    FILTER, "00000004", tenRowsWithOneMark(1, "S"), KeyAction.PFK07, null, null);

            assertThat(request.toString())
                    .as("the page number, the ten marks and the attention key are screen-interaction "
                            + "state and are rendered, but the browse key and the cursor carrier "
                            + "both name a single card holder's transaction and are withheld")
                    .startsWith("TransactionListRequest[")
                    .contains("transactionIdFilter=***REDACTED***")
                    .contains("keyAction=PFK07")
                    .contains("displayedPageNumber=00000004")
                    .contains("pageMetadata=***REDACTED***")
                    .doesNotContain(FILTER);
        }

        @Test
        @DisplayName("the selector sequence appears in row order in the rendering, which is what "
                + "makes a diagnostic about the wrong row detectable")
        void theSelectorSequenceAppearsInRowOrder() {
            assertThat(carryingSelectors(List.of("a", "b", "c")).toString())
                    .contains("rowSelectors=[a, b, c]");
        }

        @Test
        @DisplayName("a nested navigation state withholds its own identifying values, so this type "
                + "cannot become the path by which they surface")
        void aNestedNavigationStateWithholdsItsOwnValues() {
            TransactionListRequest request = new TransactionListRequest(
                    FILTER, null, null, null, JsonContractSupport.populatedNavigation(), null);

            assertThat(request.toString())
                    .contains("navigationContext=NavigationContext[")
                    .doesNotContain(JsonContractSupport.NAV_CARD_NUMBER)
                    .doesNotContain(JsonContractSupport.NAV_ACCOUNT_ID)
                    .doesNotContain(JsonContractSupport.NAV_CUSTOMER_ID)
                    .doesNotContain(JsonContractSupport.NAV_FIRST_NAME)
                    .doesNotContain(JsonContractSupport.NAV_MIDDLE_NAME)
                    .doesNotContain(JsonContractSupport.NAV_LAST_NAME);
        }
    }

    @Nested
    @DisplayName("Value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equality compares every component by value, including the selector ordering")
        void equalityComparesEveryComponentByValue() {
            TransactionListRequest left = carryingSelectors(tenRowsWithOneMark(0, "S"));
            TransactionListRequest right = carryingSelectors(tenRowsWithOneMark(0, "S"));
            TransactionListRequest shifted = carryingSelectors(tenRowsWithOneMark(1, "S"));

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
            assertThat(left)
                    .as("the same mark on a different row is a different submission, which is what "
                            + "index alignment exists to express")
                    .isNotEqualTo(shifted);
        }

        @Test
        @DisplayName("an absent sequence and an empty sequence compare equal, because the "
                + "constructor normalises the first into the second")
        void anAbsentSequenceEqualsAnEmptySequence() {
            assertThat(carryingSelectors(null))
                    .isEqualTo(carryingSelectors(List.of()))
                    .hasSameHashCodeAs(carryingSelectors(List.of()));
        }

        @Test
        @DisplayName("a blank entry and an empty entry do not compare equal, because a fixed-width "
                + "screen item transmits its blanks")
        void aBlankEntryIsNotAnEmptyEntry() {
            assertThat(carryingSelectors(List.of(" ")))
                    .isNotEqualTo(carryingSelectors(List.of("")));
        }
    }
}
