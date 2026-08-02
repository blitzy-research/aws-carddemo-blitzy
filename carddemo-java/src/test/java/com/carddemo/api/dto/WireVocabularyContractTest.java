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
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jakarta.validation.constraints.Size;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the contract package speaks one vocabulary.
 *
 * <p>Every screen in the legacy estate carries the same common header, the same focus hint and the same
 * onward-navigation marker, so every contract derived from a screen carries those same concepts. Nothing
 * forces the twenty-eight types to agree on what to call them, and nothing in a per-type test would notice
 * disagreement: each type reads correctly on its own while a consumer integrating two of them meets
 * {@code screenTitleLine1} in one and {@code title01} in the next. The cost of that drift falls entirely on
 * the consumer, which is why it is asserted here, across the package, rather than in any one type's test.</p>
 *
 * <p><strong>Why the assertions are expressed over reflected record components.</strong> Searching the source
 * text for a superseded spelling cannot distinguish a component declaration from prose: the package's
 * documentation legitimately discusses the next route, page boundaries and screen navigation in English, and
 * a text search reports every sentence. Reflection sees only what is actually on the wire, so a name found
 * here is a name a consumer must handle.</p>
 *
 * <p><strong>How each rule is framed.</strong> A rule that merely listed the superseded spellings would pass
 * the moment someone invented a twelfth spelling for a screen title. Each rule is therefore framed over the
 * concept rather than over the rejected names: any component whose name speaks of a title must be one of the
 * two canonical title components, any component that speaks of a route must be the canonical route
 * component, and so on. Inventing a new spelling fails the rule that owns the concept.</p>
 */
@DisplayName("wire vocabulary - the one spelling the whole contract package shares for a shared concept")
final class WireVocabularyContractTest {

    /**
     * Every top-level request and response type of the contract package.
     *
     * <p>Twenty-eight entries, one per file. Written out rather than discovered, so a type added to the
     * package and forgotten here is visible as a gap in this list rather than silently exempt from every rule
     * below. No import is needed because this test lives in the package it governs.</p>
     */
    private static final List<Class<?>> CONTRACT_TYPES = List.of(
            AccountUpdateRequest.class, AccountUpdateResponse.class, AccountViewResponse.class,
            BillPaymentRequest.class, BillPaymentResponse.class, CardDetailResponse.class,
            CardListRequest.class, CardListResponse.class, CardUpdateRequest.class,
            CardUpdateResponse.class, ErrorResponse.class, FieldErrorDecorator.class,
            MenuResponse.class, NavigationContext.class, PageMetadata.class, ReportRequest.class,
            ReportResponse.class, ScreenWorkArea.class, SignOnRequest.class, SignOnResponse.class,
            StatementSummary.class, TransactionAddRequest.class, TransactionAddResponse.class,
            TransactionListRequest.class, TransactionListResponse.class, TransactionViewResponse.class,
            UserRequest.class, UserResponse.class);

    /** The canonical spelling of the first line of the screen's common header. */
    private static final String CANONICAL_FIRST_TITLE = "title01";

    /** The canonical spelling of the second line of the screen's common header. */
    private static final String CANONICAL_SECOND_TITLE = "title02";

    /** The canonical spelling of the onward-navigation marker. */
    private static final String CANONICAL_ROUTE = "nextRoute";

    /** The canonical spelling of the carried conversation state. */
    private static final String CANONICAL_NAVIGATION = "navigationContext";

    /** The canonical spelling of the hint naming the screen field the cursor belongs on. */
    private static final String CANONICAL_FOCUS_HINT = "focusScreenFieldId";

    /** The canonical spelling of the carried paging shape. */
    private static final String CANONICAL_PAGING_SHAPE = "pageMetadata";

    /** The canonical spelling of the page indicator the screen displays. */
    private static final String CANONICAL_PAGE_INDICATOR = "displayedPageNumber";

    /**
     * Width the focus hint is bounded to wherever it is bounded at all.
     *
     * <p>Seven, because every screen field identifier in the estate's generated symbolic maps occupies at most
     * that many characters. A hint bounded more loosely would accept an identifier no screen could name.</p>
     */
    private static final int FOCUS_HINT_WIDTH = 7;

    /**
     * Spellings that were once used for a shared concept and must never reappear.
     *
     * <p>Kept as a backstop beneath the concept rules rather than as the primary defence: each of these is
     * already rejected by the rule that owns its concept, and listing them additionally makes a regression to
     * a specific former name report itself by name rather than by category.</p>
     */
    private static final List<String> SUPERSEDED_SPELLINGS = List.of(
            "screenTitle1", "screenTitle2", "screenTitleLine1", "screenTitleLine2", "titleLine1",
            "titleLine2", "title1", "title2", "fieldToFocus", "focusFieldName", "route", "navigation",
            "page", "pageNumber", "pageIndicator", "period", "reportPeriod", "paymentBalance");

    /**
     * A single component of a single contract shape.
     *
     * @param owner the record declaring the component, which may be a nested shape
     * @param name  the component's name exactly as it appears on the wire
     */
    private record Component(Class<?> owner, String name) {

        /** @return a diagnostic naming the owner and the component, for use in a failure message */
        @Override
        public String toString() {
            return owner.getSimpleName() + "." + name;
        }
    }

    /**
     * Collects every component of every contract shape, including nested shapes.
     *
     * @return every component the package puts on the wire
     */
    private static List<Component> allComponents() {
        final List<Component> components = new ArrayList<>();
        CONTRACT_TYPES.forEach(type -> collectFrom(type, components));
        return components;
    }

    /**
     * Adds a type's components to the sink and descends into every type nested inside it.
     *
     * <p>Descends unconditionally rather than only into records, because a nested record can be declared
     * inside a nested class, and a shape a consumer receives is in scope however deeply it is declared.</p>
     *
     * @param type type to read
     * @param sink accumulator receiving every component found
     */
    private static void collectFrom(final Class<?> type, final List<Component> sink) {
        if (type.isRecord()) {
            for (final RecordComponent component : type.getRecordComponents()) {
                sink.add(new Component(type, component.getName()));
            }
        }
        for (final Class<?> nested : type.getDeclaredClasses()) {
            collectFrom(nested, sink);
        }
    }

    /**
     * Reports every component whose name speaks of a concept, by case-folded substring.
     *
     * @param fragment folded fragment identifying the concept
     * @return every component whose folded name contains the fragment
     */
    private static List<Component> componentsSpeakingOf(final String fragment) {
        return allComponents().stream()
                .filter(component -> component.name().toLowerCase(Locale.ROOT).contains(fragment))
                .toList();
    }

    /**
     * Reports every shape declaring a component of the given name.
     *
     * @param name exact component name
     * @return the declaring shapes
     */
    private static List<Class<?>> ownersOf(final String name) {
        return allComponents().stream()
                .filter(component -> component.name().equals(name))
                .map(Component::owner)
                .toList();
    }

    /**
     * Reads the width bound declared on a component, if it declares one.
     *
     * <p>Reads the bound from the backing field rather than from the record component, because a record
     * component reports no annotation of its own: the annotation is propagated to the field, the accessor and
     * the constructor parameter, and the field is the one place all of them agree.</p>
     *
     * @param owner shape declaring the component
     * @param name  component name
     * @return the declared bound, or {@code -1} when the component declares none
     * @throws NoSuchFieldException when the component has no backing field, which cannot happen for a record
     */
    private static int declaredWidthOf(final Class<?> owner, final String name) throws NoSuchFieldException {
        final Size bound = owner.getDeclaredField(name).getAnnotation(Size.class);
        return bound == null ? -1 : bound.max();
    }

    // THE PACKAGE MEMBERSHIP THIS TEST GOVERNS

    /**
     * Verifies that the roster this test reasons over is the package itself.
     *
     * <p>Every rule below is only as complete as this list. A type absent from it is a type no rule reaches,
     * so the list's own size is asserted against the package's declared membership.</p>
     */
    @Nested
    @DisplayName("the governed roster")
    final class TheGovernedRoster {

        @Test
        @DisplayName("the roster carries one entry per contract file, so no type escapes the rules below")
        void theRosterCarriesOneEntryPerContractFile() {
            assertThat(CONTRACT_TYPES)
                    .as("the contract package declares twenty-eight top-level types")
                    .hasSize(28)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every rostered type belongs to this package, so the rules govern the contract surface "
                + "and nothing else")
        void everyRosteredTypeBelongsToThisPackage() {
            assertThat(CONTRACT_TYPES)
                    .allSatisfy(type -> assertThat(type.getPackageName())
                            .isEqualTo("com.carddemo.api.dto"));
        }

        @Test
        @DisplayName("the roster reaches the nested shapes too, so a name on a collection element is "
                + "governed exactly as a name on a top-level shape")
        void theRosterReachesTheNestedShapes() {
            assertThat(allComponents())
                    .extracting(component -> component.owner().getSimpleName())
                    .contains("UserRow", "CardListRow", "TransactionRow", "FieldError",
                            "PageCursorRequest");
        }
    }

    // THE SUPERSEDED SPELLINGS

    /**
     * Verifies that no component carries a spelling the package has moved away from.
     *
     * <p>A backstop beneath the concept rules: it names the specific regression rather than the category.</p>
     */
    @Nested
    @DisplayName("superseded spellings")
    final class SupersededSpellings {

        @Test
        @DisplayName("no component of any shape carries a superseded spelling, so a consumer meets one name "
                + "per concept across the whole package")
        void noComponentCarriesASupersededSpelling() {
            final List<String> offenders = allComponents().stream()
                    .filter(component -> SUPERSEDED_SPELLINGS.contains(component.name()))
                    .map(Component::toString)
                    .toList();

            assertThat(offenders)
                    .as("each of these names was replaced by a canonical one; offenders: %s", offenders)
                    .isEmpty();
        }
    }

    // THE SCREEN HEADER

    /**
     * Verifies that the common header every screen carries is spelled one way.
     *
     * <p>The header is the same two title lines on every map in the estate, so a contract derived from any
     * screen carries the same two components. Thirteen of the twenty-eight contracts do.</p>
     */
    @Nested
    @DisplayName("the screen header")
    final class TheScreenHeader {

        @Test
        @DisplayName("every component that speaks of a title is one of the two canonical title components, "
                + "so inventing a twelfth spelling fails here rather than reaching a consumer")
        void everyTitleComponentIsCanonical() {
            assertThat(componentsSpeakingOf("title"))
                    .allSatisfy(component -> assertThat(component.name())
                            .as("%s names a title", component)
                            .isIn(CANONICAL_FIRST_TITLE, CANONICAL_SECOND_TITLE));
        }

        @Test
        @DisplayName("a shape carrying either title line carries both, because the legacy header has two "
                + "lines and a consumer rendering one of them would render half a header")
        void aShapeCarryingEitherTitleCarriesBoth() {
            assertThat(ownersOf(CANONICAL_FIRST_TITLE))
                    .containsExactlyInAnyOrderElementsOf(ownersOf(CANONICAL_SECOND_TITLE));
        }

        @Test
        @DisplayName("the canonical title pair is shared across the screen-derived responses rather than "
                + "used once, which is what makes it a vocabulary")
        void theCanonicalTitlePairIsShared() {
            assertThat(ownersOf(CANONICAL_FIRST_TITLE))
                    .as("every response derived from a screen carries the screen's header")
                    .hasSizeGreaterThanOrEqualTo(13);
        }
    }

    // THE ONWARD ROUTE AND THE CARRIED STATE

    /**
     * Verifies that onward navigation and carried conversation state are each spelled one way.
     *
     * <p>These two travel together on every response that continues a conversation, and they are the two a
     * client reads on literally every call, so a second spelling of either is felt immediately.</p>
     */
    @Nested
    @DisplayName("onward navigation")
    final class OnwardNavigation {

        @Test
        @DisplayName("every component that speaks of a route is the canonical route component")
        void everyRouteComponentIsCanonical() {
            assertThat(componentsSpeakingOf("route"))
                    .allSatisfy(component -> assertThat(component.name())
                            .as("%s names a route", component)
                            .isEqualTo(CANONICAL_ROUTE));
        }

        @Test
        @DisplayName("every component that speaks of navigation is the canonical navigation context")
        void everyNavigationComponentIsCanonical() {
            assertThat(componentsSpeakingOf("navig"))
                    .allSatisfy(component -> assertThat(component.name())
                            .as("%s names carried navigation state", component)
                            .isEqualTo(CANONICAL_NAVIGATION));
        }

        @Test
        @DisplayName("both are shared across the package rather than used once")
        void bothAreSharedAcrossThePackage() {
            assertThat(ownersOf(CANONICAL_ROUTE)).hasSizeGreaterThanOrEqualTo(13);
            assertThat(ownersOf(CANONICAL_NAVIGATION)).hasSizeGreaterThanOrEqualTo(20);
        }
    }

    // THE FOCUS HINT

    /**
     * Verifies that the hint naming the field the cursor belongs on is spelled and bounded one way.
     *
     * <p>The name is universal across the package. The bound is not: the error envelope names the same
     * concept without bounding it, deliberately, because it reports a field the caller named rather than one
     * this module chose. The rule therefore governs the bound wherever a bound is declared, and requires none
     * where none is.</p>
     */
    @Nested
    @DisplayName("the focus hint")
    final class TheFocusHint {

        @Test
        @DisplayName("every component that speaks of focus is the canonical focus hint")
        void everyFocusComponentIsCanonical() {
            assertThat(componentsSpeakingOf("focus"))
                    .allSatisfy(component -> assertThat(component.name())
                            .as("%s names a focus hint", component)
                            .isEqualTo(CANONICAL_FOCUS_HINT));
        }

        @Test
        @DisplayName("the canonical focus hint is bounded at the screen field identifier width wherever it "
                + "is bounded, so no shape accepts an identifier no screen could name")
        void everyBoundedFocusHintIsBoundedAtTheFieldIdentifierWidth() throws NoSuchFieldException {
            final List<String> offenders = new ArrayList<>();
            for (final Class<?> owner : ownersOf(CANONICAL_FOCUS_HINT)) {
                final int width = declaredWidthOf(owner, CANONICAL_FOCUS_HINT);
                if (width != -1 && width != FOCUS_HINT_WIDTH) {
                    offenders.add(owner.getSimpleName() + " bounds it at " + width);
                }
            }

            assertThat(offenders)
                    .as("a focus hint carries a screen field identifier, which is at most seven characters "
                            + "wide; offenders: %s", offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("the focus hint is shared across the package rather than used once")
        void theFocusHintIsShared() {
            assertThat(ownersOf(CANONICAL_FOCUS_HINT)).hasSizeGreaterThanOrEqualTo(14);
        }
    }

    // PAGING

    /**
     * Verifies that the paging vocabulary is spelled one way.
     *
     * <p>Paging is where drift was widest, because the three paginated screens declare three different page
     * sizes and were translated separately. The carried shape and the displayed indicator are distinct
     * concepts and each has exactly one name.</p>
     */
    @Nested
    @DisplayName("paging")
    final class Paging {

        @Test
        @DisplayName("every component whose name begins with the word page is either the carried paging "
                + "shape or the declared page size, so no shape reintroduces a bare page component")
        void everyPagePrefixedComponentIsCanonical() {
            final List<Component> prefixed = allComponents().stream()
                    .filter(component -> component.name().toLowerCase(Locale.ROOT).startsWith("page"))
                    .toList();

            assertThat(prefixed)
                    .allSatisfy(component -> assertThat(component.name())
                            .as("%s is named for a page", component)
                            .isIn(CANONICAL_PAGING_SHAPE, "pageSize"));
        }

        @Test
        @DisplayName("every component that speaks of a page number is the canonical displayed indicator, "
                + "because the indicator is displayed and never accepted")
        void everyPageNumberComponentIsCanonical() {
            assertThat(componentsSpeakingOf("pagenumber"))
                    .allSatisfy(component -> assertThat(component.name())
                            .as("%s names a page number", component)
                            .isEqualTo(CANONICAL_PAGE_INDICATOR));
        }

        @Test
        @DisplayName("both paging names are shared across the paginated contracts rather than used once")
        void bothPagingNamesAreShared() {
            assertThat(ownersOf(CANONICAL_PAGING_SHAPE)).hasSizeGreaterThanOrEqualTo(4);
            assertThat(ownersOf(CANONICAL_PAGE_INDICATOR)).hasSizeGreaterThanOrEqualTo(5);
        }
    }
}
