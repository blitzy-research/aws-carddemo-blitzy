/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.carddemo.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DateFormat}, the two date-mask selectors the legacy
 * date-validation subprogram accepts across its linkage parameter.
 *
 * <h2>Where the two selectors come from</h2>
 *
 * <p>The shared date-validation subprogram declares its second linkage item as a
 * ten-character alphanumeric field, and the caller-side parameter block declares
 * the matching slot at the same ten characters. Both widths were read from the
 * legacy sources: the subprogram's linkage declaration at
 * {@code [app/cbl/CSUTLDTC.cbl:L85]} and the caller's parameter block at
 * {@code [app/cbl/CORPT00C.cbl:L131]}. Ten characters is therefore the width of
 * the slot, not a Java convenience, which is why every selector this enum
 * publishes measures exactly ten characters.</p>
 *
 * <p>The two selectors reach that width by different routes, and the difference
 * is contractual rather than cosmetic:</p>
 *
 * <ul>
 *   <li>The hyphenated selector is declared in the online callers as a
 *       ten-character working field carrying a ten-character literal
 *       {@code [app/cbl/COTRN02C.cbl:L60]}, {@code [app/cbl/CORPT00C.cbl:L72]}, so
 *       it fills the slot exactly with no padding.</li>
 *   <li>The compact selector is declared in the date-utility work copybook as an
 *       <em>eight</em>-character field {@code [app/cpy/CSUTLDWY.cpy:L58-L59]} and is
 *       moved into it by the validation cascade
 *       {@code [app/cpy/CSUTLDPY.cpy:L291]} before the call at
 *       {@code [app/cpy/CSUTLDPY.cpy:L295]}. An alphanumeric move into a wider
 *       receiving field left-justifies and space-fills, so the value that actually
 *       arrives in the ten-character slot carries exactly two trailing spaces.
 *       Those two spaces are part of the value the subprogram compares, so they
 *       are asserted here rather than trimmed away.</li>
 * </ul>
 *
 * <h2>Scope</h2>
 *
 * <p>These tests construct nothing beyond the enum itself. No Spring context, no
 * database, no file, no network, no container and no introspection is involved.</p>
 *
 * <h2>Expectations are derived, never echoed</h2>
 *
 * <p>Every width and every character position asserted below is derived from the
 * legacy picture clauses cited above and from COBOL's alphanumeric move
 * semantics. No expectation is read back out of the class under test.</p>
 *
 * <p>Provenance: legacy sources read at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text is
 * reproduced here.</p>
 */
@DisplayName("DateFormat - the two ten-character date-mask selectors")
class DateFormatBaselineTest {

    /**
     * The width of the linkage slot, from {@code LS-DATE-FORMAT PIC X(10)} at
     * {@code [app/cbl/CSUTLDTC.cbl:L85]} and the caller's matching
     * {@code CSUTLDTC-DATE-FORMAT PIC X(10)} at {@code [app/cbl/CORPT00C.cbl:L131]}.
     */
    private static final int LINKAGE_SELECTOR_WIDTH = 10;

    /**
     * The declared width of the compact selector's source field,
     * {@code WS-DATE-FORMAT PIC X(08)} at {@code [app/cpy/CSUTLDWY.cpy:L58-L59]}.
     */
    private static final int COMPACT_SOURCE_FIELD_WIDTH = 8;

    /** The number of selectors the legacy estate ever passes across the linkage. */
    private static final int DECLARED_SELECTOR_COUNT = 2;

    @Nested
    @DisplayName("the published vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("declares exactly the two selectors the legacy callers pass")
        void declaresExactlyTwoSelectors() {
            assertThat(DateFormat.values()).hasSize(DECLARED_SELECTOR_COUNT);
        }

        @Test
        @DisplayName("declares the hyphenated selector first, matching the online callers"
                + " that use it most")
        void declarationOrderIsHyphenatedThenCompact() {
            assertThat(DateFormat.values())
                    .containsExactly(DateFormat.YYYY_MM_DD, DateFormat.YYYYMMDD);
        }

        @Test
        @DisplayName("names each constant after the mask it carries")
        void constantNamesMirrorTheirMasks() {
            assertThat(DateFormat.YYYY_MM_DD.name()).isEqualTo("YYYY_MM_DD");
            assertThat(DateFormat.YYYYMMDD.name()).isEqualTo("YYYYMMDD");
        }

        @Test
        @DisplayName("hands out a defensive copy of the constant array")
        void valuesIsDefensivelyCopied() {
            DateFormat[] first = DateFormat.values();
            first[0] = null;

            assertThat(DateFormat.values()[0]).isEqualTo(DateFormat.YYYY_MM_DD);
        }

        @ParameterizedTest
        @EnumSource(DateFormat.class)
        @DisplayName("resolves every constant from its own name")
        void everyConstantResolvesFromItsName(DateFormat format) {
            assertThat(DateFormat.valueOf(format.name())).isSameAs(format);
        }
    }

    @Nested
    @DisplayName("selector width - the ten-character linkage slot")
    class SelectorWidth {

        @ParameterizedTest
        @EnumSource(DateFormat.class)
        @DisplayName("fills the ten-character linkage slot exactly, so the subprogram receives"
                + " no residue from a previous call")
        void everySelectorFillsTheLinkageSlot(DateFormat format) {
            assertThat(format.getValue()).hasSize(LINKAGE_SELECTOR_WIDTH);
        }

        @Test
        @DisplayName("carries the hyphenated mask with no padding, because its source literal is"
                + " already ten characters wide")
        void theHyphenatedSelectorNeedsNoPadding() {
            String selector = DateFormat.YYYY_MM_DD.getValue();

            assertThat(selector).hasSize(LINKAGE_SELECTOR_WIDTH);
            assertThat(selector).doesNotContain(" ");
            assertThat(selector.strip()).hasSameSizeAs(selector);
        }

        @Test
        @DisplayName("carries the compact mask with exactly two trailing spaces, because an"
                + " eight-character source field is space-filled into a ten-character slot")
        void theCompactSelectorCarriesTwoTrailingSpaces() {
            String selector = DateFormat.YYYYMMDD.getValue();

            assertThat(selector).hasSize(LINKAGE_SELECTOR_WIDTH);
            assertThat(selector.strip()).hasSize(COMPACT_SOURCE_FIELD_WIDTH);

            int paddingWidth = LINKAGE_SELECTOR_WIDTH - COMPACT_SOURCE_FIELD_WIDTH;
            assertThat(selector.substring(COMPACT_SOURCE_FIELD_WIDTH))
                    .isEqualTo(" ".repeat(paddingWidth))
                    .isBlank();

            // Left-justified: the significant characters occupy the leading positions,
            // never the trailing ones.
            assertThat(selector.charAt(0)).isNotEqualTo(' ');
        }
    }

    @Nested
    @DisplayName("selector shape - the mask characters themselves")
    class SelectorShape {

        @Test
        @DisplayName("places the hyphens at the fifth and eighth positions, so the mask describes"
                + " a four-two-two date")
        void theHyphenatedMaskSeparatesYearMonthAndDay() {
            String selector = DateFormat.YYYY_MM_DD.getValue();

            assertThat(selector.charAt(4)).isEqualTo('-');
            assertThat(selector.charAt(7)).isEqualTo('-');
            assertThat(selector.substring(0, 4)).isEqualTo("YYYY");
            assertThat(selector.substring(5, 7)).isEqualTo("MM");
            assertThat(selector.substring(8, 10)).isEqualTo("DD");
        }

        @Test
        @DisplayName("runs the compact mask together with no separator at all")
        void theCompactMaskHasNoSeparator() {
            String selector = DateFormat.YYYYMMDD.getValue();

            assertThat(selector).doesNotContain("-");
            assertThat(selector.substring(0, 4)).isEqualTo("YYYY");
            assertThat(selector.substring(4, 6)).isEqualTo("MM");
            assertThat(selector.substring(6, 8)).isEqualTo("DD");
        }

        @ParameterizedTest
        @EnumSource(DateFormat.class)
        @DisplayName("uses only upper-case mask letters, a hyphen or a space, so the selector is"
                + " a single US-ASCII byte per position")
        void everySelectorUsesOnlyMaskCharacters(DateFormat format) {
            String selector = format.getValue();

            for (int index = 0; index < selector.length(); index++) {
                char position = selector.charAt(index);
                assertThat(position)
                        .as("position %d of %s", index, format.name())
                        .isIn('Y', 'M', 'D', '-', ' ');
            }
            assertThat(selector).isEqualTo(selector.toUpperCase(Locale.ROOT));
        }

        @ParameterizedTest
        @EnumSource(DateFormat.class)
        @DisplayName("describes four year, two month and two day positions in every selector")
        void everySelectorDescribesTheSameFieldWidths(DateFormat format) {
            String selector = format.getValue();

            assertThat(selector.chars().filter(codePoint -> codePoint == 'Y').count()).isEqualTo(4);
            assertThat(selector.chars().filter(codePoint -> codePoint == 'M').count()).isEqualTo(2);
            assertThat(selector.chars().filter(codePoint -> codePoint == 'D').count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("fromValue - resolving a selector arriving across the linkage")
    class LookupByValue {

        @ParameterizedTest
        @EnumSource(DateFormat.class)
        @DisplayName("resolves each constant from the exact selector it publishes, and returns the"
                + " same singleton")
        void everySelectorRoundTrips(DateFormat format) {
            assertThat(DateFormat.fromValue(format.getValue())).containsSame(format);
        }

        @Test
        @DisplayName("yields no selector for an absent reference, because an unsupplied mask is"
                + " not the same as an unrecognised one")
        void anAbsentReferenceYieldsNoSelector() {
            Optional<DateFormat> resolved = DateFormat.fromValue(null);

            assertThat(resolved).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "",
            "          ",
            "YYYYMMDD",
            "YYYY-MM-D",
            "YYYY-MM-DDD",
            " YYYY-MM-D",
            "  YYYYMMDD",
            "YYYY/MM/DD",
            "DD-MM-YYYY",
            "CCYY-MM-DD"
        })
        @DisplayName("yields no selector for a mask the legacy callers never pass")
        void anUnrecognisedMaskYieldsNoSelector(String candidate) {
            assertThat(DateFormat.fromValue(candidate)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"yyyy-mm-dd", "Yyyy-Mm-Dd", "yyyymmdd  ", "YyyyMmDd  "})
        @DisplayName("performs no case folding, because the legacy compare is a byte-for-byte"
                + " alphanumeric comparison")
        void lookupIsCaseSensitive(String candidate) {
            assertThat(DateFormat.fromValue(candidate)).isEmpty();
        }

        @Test
        @DisplayName("performs no trimming, so the compact mask resolves only with its two"
                + " trailing spaces present")
        void lookupDoesNotTrim() {
            String compact = DateFormat.YYYYMMDD.getValue();

            assertThat(DateFormat.fromValue(compact.strip())).isEmpty();
            assertThat(DateFormat.fromValue(compact)).containsSame(DateFormat.YYYYMMDD);
        }

        @Test
        @DisplayName("keeps the two selectors distinct, so neither resolves to the other")
        void theTwoSelectorsAreDistinct() {
            assertThat(DateFormat.YYYY_MM_DD.getValue())
                    .isNotEqualTo(DateFormat.YYYYMMDD.getValue());
            assertThat(DateFormat.fromValue(DateFormat.YYYY_MM_DD.getValue()).orElseThrow())
                    .isNotEqualTo(DateFormat.YYYYMMDD);
            assertThat(DateFormat.fromValue(DateFormat.YYYYMMDD.getValue()).orElseThrow())
                    .isNotEqualTo(DateFormat.YYYY_MM_DD);
        }
    }
}
