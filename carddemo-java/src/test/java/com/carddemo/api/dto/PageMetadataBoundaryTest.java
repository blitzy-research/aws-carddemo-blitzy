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
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Boundary test for {@link PageMetadata}, covering the declarative bounds a caller-supplied paging state
 * must satisfy at a request boundary.
 *
 * <h2>Why this is a separate file from {@code PageMetadataTest}</h2>
 *
 * <p>{@code PageMetadataTest} establishes the value semantics of this record: what each component means,
 * that construction transforms nothing, that the rendering withholds both cursors, and that the record
 * carries the three proven screen row counts as named constants. It deliberately builds no validator,
 * because none of that is a validation question. The bounds are a different question with a different
 * answer, and they need a validator, so they live here rather than being grafted onto a file whose
 * subject is something else.</p>
 *
 * <h2>What is actually at risk</h2>
 *
 * <p>This record is the only component of the request contracts that carries a <strong>count</strong>, and
 * a count a caller chooses is a count that sizes work on the server. Three request contracts nest it -
 * card list, transaction list and user list - so an unbounded count reaches three separate endpoints
 * through one type. The bound closes that, and these tests assert it fires from the first value above the
 * largest legacy screen page and never below it.</p>
 *
 * <h2>Why the bound is the largest of the three screen figures rather than the exact figure</h2>
 *
 * <p>One record serves three screens whose pages are seven, ten and ten rows. A single declarative bound
 * cannot express three different exact figures, so it expresses the only thing true of all three - that no
 * legacy screen page exceeds ten - and the exact 7-or-10 requirement stays endpoint-owned: each list
 * endpoint must supply its own row count from the named constant rather than from anything a caller sent.
 * That obligation is recorded on the constant itself and is asserted here only in the form the type can
 * carry: that each of the three named figures satisfies the bound, and that the bound is exactly their
 * maximum rather than an arbitrary ceiling.</p>
 *
 * <h2>Why the bound is declarative and not a construction check</h2>
 *
 * <p>The record is a carrier used on both the inbound and outbound sides. A canonical constructor that
 * rejected a count would make the type unable to represent a single-row page, would fire in outbound
 * construction where no caller supplied anything, and would replace a reported violation with a thrown
 * exception at a point where no field name is available to report. The two properties that follow from
 * that choice - construction stays unchecked, and nothing is clamped - are asserted here directly, so a
 * later attempt to move the bound into the constructor fails in this file rather than silently changing
 * what the type can represent.</p>
 *
 * <p>Provenance: checkout SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.</p>
 */
@DisplayName("PageMetadata - the declarative bounds on caller-supplied paging state")
class PageMetadataBoundaryTest {

    /** A cursor key at the declared maximum width. */
    private static final String CURSOR_AT_MAX = "X".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH);

    private static Set<ConstraintViolation<PageMetadata>> violationsOf(PageMetadata page) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(page);
        }
    }

    private static PageMetadata pageOfSize(int pageSize) {
        return new PageMetadata(pageSize, null, null, PageMetadata.PagingDirection.FORWARD, false,
                false, null);
    }

    @Nested
    @DisplayName("The row count is bounded above by the largest legacy screen page")
    class TheRowCountIsBoundedAbove {

        @Test
        @DisplayName("the row count declares both a positive floor and an upper bound, because a count "
                + "of zero and a count of a million are two different kinds of wrong")
        void theRowCountDeclaresBothAFloorAndAnUpperBound() throws NoSuchFieldException {
            Field field = PageMetadata.class.getDeclaredField("pageSize");

            assertThat(field.getAnnotation(Positive.class)).isNotNull();
            assertThat(field.getAnnotation(Max.class)).isNotNull();
            assertThat(field.getAnnotation(Max.class).value())
                    .isEqualTo(PageMetadata.LARGEST_SCREEN_PAGE_SIZE);
        }

        @Test
        @DisplayName("the upper bound is exactly the largest of the three proven screen figures, so it is "
                + "a measured value rather than an arbitrary ceiling")
        void theUpperBoundIsExactlyTheLargestOfTheThreeScreenFigures() {
            List<Integer> screenFigures = List.of(PageMetadata.CARD_LIST_PAGE_SIZE,
                    PageMetadata.TRANSACTION_LIST_PAGE_SIZE, PageMetadata.USER_LIST_PAGE_SIZE);

            assertThat(PageMetadata.LARGEST_SCREEN_PAGE_SIZE)
                    .isEqualTo(screenFigures.stream().max(Integer::compareTo).orElseThrow())
                    .isEqualTo(10);
            assertThat(screenFigures).allSatisfy(figure ->
                    assertThat(figure).isLessThanOrEqualTo(PageMetadata.LARGEST_SCREEN_PAGE_SIZE));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 7, 9, 10})
        @DisplayName("every count from one up to the bound is accepted, so the bound does not exclude any "
                + "page a legacy screen could have produced")
        void everyCountUpToTheBoundIsAccepted(int pageSize) {
            assertThat(violationsOf(pageOfSize(pageSize))).isEmpty();
        }

        @Test
        @DisplayName("each of the three screens' own row counts is accepted, named rather than assumed")
        void eachScreensOwnRowCountIsAccepted() {
            assertThat(violationsOf(pageOfSize(PageMetadata.CARD_LIST_PAGE_SIZE))).isEmpty();
            assertThat(violationsOf(pageOfSize(PageMetadata.TRANSACTION_LIST_PAGE_SIZE))).isEmpty();
            assertThat(violationsOf(pageOfSize(PageMetadata.USER_LIST_PAGE_SIZE))).isEmpty();
        }

        @Test
        @DisplayName("the first count above the bound is reported, so the bound is exclusive of eleven "
                + "rather than approximate")
        void theFirstCountAboveTheBoundIsReported() {
            Set<ConstraintViolation<PageMetadata>> violations =
                    violationsOf(pageOfSize(PageMetadata.LARGEST_SCREEN_PAGE_SIZE + 1));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("pageSize");
        }

        @ParameterizedTest
        @ValueSource(ints = {11, 12, 100, 1_000, 1_000_000, Integer.MAX_VALUE})
        @DisplayName("every count above the bound is reported, including the largest a caller could send")
        void everyCountAboveTheBoundIsReported(int pageSize) {
            Set<ConstraintViolation<PageMetadata>> violations = violationsOf(pageOfSize(pageSize));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("pageSize");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -10, Integer.MIN_VALUE})
        @DisplayName("a count of zero or below is reported by the floor, which the upper bound did not "
                + "displace")
        void aCountOfZeroOrBelowIsReportedByTheFloor(int pageSize) {
            Set<ConstraintViolation<PageMetadata>> violations = violationsOf(pageOfSize(pageSize));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString("pageSize");
        }

        @Test
        @DisplayName("an out-of-range count is retained verbatim and never clamped, so a caller learns "
                + "the value was refused rather than silently receiving a different page")
        void anOutOfRangeCountIsRetainedVerbatimAndNeverClamped() {
            PageMetadata oversized = pageOfSize(500);
            PageMetadata zero = pageOfSize(0);

            assertThat(oversized.pageSize()).isEqualTo(500);
            assertThat(zero.pageSize()).isZero();
        }
    }

    @Nested
    @DisplayName("Construction remains unchecked, which is what makes the bound declarative")
    class ConstructionRemainsUnchecked {

        @Test
        @DisplayName("a count of zero constructs without throwing, because the record is a carrier and a "
                + "constructor rejection would fire where no caller supplied anything")
        void aCountOfZeroConstructsWithoutThrowing() {
            assertThatCode(() -> new PageMetadata(0, null, null,
                    PageMetadata.PagingDirection.BACKWARD, false, false, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a count far above the bound constructs without throwing, so the boundary reports it "
                + "rather than the type refusing to hold it")
        void aCountFarAboveTheBoundConstructsWithoutThrowing() {
            assertThatCode(() -> pageOfSize(Integer.MAX_VALUE)).doesNotThrowAnyException();
            assertThat(pageOfSize(Integer.MAX_VALUE).pageSize()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("a single-row page is representable, which a constructor bound would have made "
                + "impossible to express")
        void aSingleRowPageIsRepresentable() {
            PageMetadata forward = PageMetadata.forward(1, null, null, false, false, null);

            assertThat(forward.pageSize()).isEqualTo(1);
            assertThat(violationsOf(forward)).isEmpty();
        }

        @Test
        @DisplayName("both static factories carry the count through unaltered, so neither defaults nor "
                + "normalises it")
        void bothStaticFactoriesCarryTheCountThroughUnaltered() {
            assertThat(PageMetadata.forward(PageMetadata.CARD_LIST_PAGE_SIZE, null, null, false, false, null)
                    .pageSize()).isEqualTo(7);
            assertThat(PageMetadata.backward(PageMetadata.USER_LIST_PAGE_SIZE, null, null, false, false,
                    null).pageSize()).isEqualTo(10);
            assertThat(PageMetadata.forward(3, null, null, false, false, null).pageSize()).isEqualTo(3);
            assertThat(PageMetadata.backward(3, null, null, false, false, null).pageSize()).isEqualTo(3);
        }

        @Test
        @DisplayName("the only construction-time rejection is the missing browse direction, which is a "
                + "presence requirement rather than a numeric bound")
        void theOnlyConstructionTimeRejectionIsTheMissingDirection() {
            assertThatCode(() -> new PageMetadata(0, "", "", null, false, false, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("direction must be supplied explicitly");
        }
    }

    @Nested
    @DisplayName("The echoed cursor keys and page label are bounded too")
    class TheEchoedCursorKeysAndPageLabelAreBounded {

        @Test
        @DisplayName("both cursor keys carry the stored key's own width")
        void bothCursorKeysCarryTheStoredKeysOwnWidth() throws NoSuchFieldException {
            for (String name : List.of("previousCursorKey", "nextCursorKey")) {
                Field field = PageMetadata.class.getDeclaredField(name);
                Size size = field.getAnnotation(Size.class);

                assertThat(size).as("component %s", name).isNotNull();
                assertThat(size.max()).as("component %s", name)
                        .isEqualTo(PageMetadata.CURSOR_KEY_MAX_LENGTH).isEqualTo(27);
                assertThat(size.min()).as("component %s", name).isZero();
            }
        }

        @Test
        @DisplayName("the page label carries the screen field's own width")
        void thePageLabelCarriesTheScreenFieldsOwnWidth() throws NoSuchFieldException {
            Field field = PageMetadata.class.getDeclaredField("displayedPageNumber");
            Size size = field.getAnnotation(Size.class);

            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH)
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("a cursor key at the declared width is accepted and one character beyond it is "
                + "reported on its own component")
        void aCursorKeyAtTheWidthIsAcceptedAndBeyondItIsReported() {
            PageMetadata atWidth = new PageMetadata(PageMetadata.CARD_LIST_PAGE_SIZE, CURSOR_AT_MAX,
                    CURSOR_AT_MAX, PageMetadata.PagingDirection.FORWARD, false, false, null);
            PageMetadata beyondWidth = new PageMetadata(PageMetadata.CARD_LIST_PAGE_SIZE,
                    CURSOR_AT_MAX + "X", null, PageMetadata.PagingDirection.FORWARD, false, false,
                    null);

            assertThat(violationsOf(atWidth)).isEmpty();
            Set<ConstraintViolation<PageMetadata>> violations = violationsOf(beyondWidth);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("previousCursorKey");
        }

        @Test
        @DisplayName("an over-width page label is reported on its own component, so a bad label is not "
                + "attributed to a cursor")
        void anOverWidthPageLabelIsReportedOnItsOwnComponent() {
            PageMetadata beyondWidth = new PageMetadata(PageMetadata.USER_LIST_PAGE_SIZE, null, null,
                    PageMetadata.PagingDirection.FORWARD, false, false,
                    "9".repeat(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH + 1));

            Set<ConstraintViolation<PageMetadata>> violations = violationsOf(beyondWidth);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString("displayedPageNumber");
        }

        @Test
        @DisplayName("a count violation and a cursor violation are reported together, so neither masks "
                + "the other")
        void aCountViolationAndACursorViolationAreReportedTogether() {
            PageMetadata bothWrong = new PageMetadata(PageMetadata.LARGEST_SCREEN_PAGE_SIZE + 1,
                    CURSOR_AT_MAX + "X", null, PageMetadata.PagingDirection.FORWARD, false, false,
                    null);

            Set<ConstraintViolation<PageMetadata>> violations = violationsOf(bothWrong);

            assertThat(violations).hasSize(2);
            assertThat(violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .sorted()
                    .toList())
                    .containsExactly("pageSize", "previousCursorKey");
        }

        @Test
        @DisplayName("no presence, pattern or digit constraint appears on any component, because the "
                + "browse programs decide what an absent cursor and a blank label mean")
        void noPresenceOrFormatConstraintAppears() {
            for (Field field : PageMetadata.class.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                assertThat(Arrays.stream(field.getAnnotations())
                        .map(annotation -> annotation.annotationType().getSimpleName())
                        .toList())
                        .as("component %s", field.getName())
                        .doesNotContain("NotNull", "NotBlank", "NotEmpty", "Pattern", "Digits",
                                "Min", "AssertTrue");
            }
        }

        @Test
        @DisplayName("an absent cursor pair and an absent label are accepted, because a first page has no "
                + "backward cursor and a screen may echo no label at all")
        void anAbsentCursorPairAndAnAbsentLabelAreAccepted() {
            assertThat(violationsOf(pageOfSize(PageMetadata.CARD_LIST_PAGE_SIZE))).isEmpty();
        }

        @Test
        @DisplayName("a blank cursor and a blank label are accepted, because blank is what a fixed-width "
                + "screen transmits for an empty field")
        void aBlankCursorAndABlankLabelAreAccepted() {
            PageMetadata blanks = new PageMetadata(PageMetadata.TRANSACTION_LIST_PAGE_SIZE,
                    " ".repeat(PageMetadata.CURSOR_KEY_MAX_LENGTH), "",
                    PageMetadata.PagingDirection.BACKWARD, false, false,
                    " ".repeat(PageMetadata.DISPLAYED_PAGE_NUMBER_MAX_LENGTH));

            assertThat(violationsOf(blanks)).isEmpty();
            assertThat(blanks.previousCursorKey()).hasSize(27).isBlank();
        }
    }
}
