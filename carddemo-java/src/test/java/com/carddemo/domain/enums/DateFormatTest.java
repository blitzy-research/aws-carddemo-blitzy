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
package com.carddemo.domain.enums;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link DateFormat}, the mask selector passed to the date-validation subprogram.
 *
 * <p><strong>What the legacy authority is.</strong> The selector travels in
 * {@code LS-DATE-FORMAT PIC X(10)}, the second field of the linkage area declared in
 * {@code app/cbl/CSUTLDTC.cbl}. Because the slot is a fixed ten-character field, a mask shorter than
 * ten characters arrives space-padded on the right, which is why the compact eight-character mask is
 * carried here as eight characters followed by two blanks rather than as a bare eight. The subprogram
 * also echoes the selector back into its eighty-character result block at a ten-character position, so
 * the padding is part of the value on both the way in and the way out.
 *
 * <p><strong>Why the padding is the whole point.</strong> A selector of the wrong width would either
 * overrun the slot or leave stale bytes from a previous call visible in the tail, and the subprogram
 * compares the slot by value. Trimming the compact mask to eight characters would therefore be a
 * silent contract change: the call would still compile, the field would still look right in a
 * debugger, and the comparison would fail. This class pins the ten-character width of both selectors
 * and pins it in encoded bytes as well as in characters.
 *
 * <p><strong>Deliberately not asserted.</strong> The declaring constructor carries a width guard that
 * refuses a selector of any other length. That guard is unreachable through the published API, because
 * both declared literals are exactly ten characters and no other constant can be added at run time, so
 * it is reported as uncovered rather than reached by reflection &mdash; the module's reflection budget
 * is zero. What this class asserts instead is the property the guard exists to protect: every declared
 * selector measures exactly ten characters. There is no third mask, because the estate passes only
 * these two.
 */
@DisplayName("DateFormat — the ten-character mask selector")
class DateFormatTest {

    /** Width of the linkage slot the selector travels in. */
    private static final int SELECTOR_WIDTH = 10;

    /** The hyphenated mask exactly as the linkage slot carries it. */
    private static final String HYPHENATED = "YYYY-MM-DD";

    /** The compact mask exactly as the linkage slot carries it, right-padded to the slot width. */
    private static final String COMPACT = "YYYYMMDD  ";

    // =================================================================================================
    // VOCABULARY
    // =================================================================================================

    /**
     * Verifies the two-selector vocabulary the estate actually passes.
     */
    @Nested
    @DisplayName("vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("exactly two selectors exist, because the estate passes exactly two masks")
        void exactlyTwoSelectorsExist() {
            assertThat(DateFormat.values()).hasSize(2);
        }

        @Test
        @DisplayName("the hyphenated selector carries the hyphenated mask")
        void theHyphenatedSelectorCarriesItsMask() {
            assertThat(DateFormat.YYYY_MM_DD.getValue()).isEqualTo(HYPHENATED);
        }

        @Test
        @DisplayName("the compact selector carries the compact mask right-padded to the slot width")
        void theCompactSelectorCarriesItsPaddedMask() {
            assertThat(DateFormat.YYYYMMDD.getValue())
                    .isEqualTo(COMPACT)
                    .startsWith("YYYYMMDD")
                    .endsWith("  ");
        }

        @Test
        @DisplayName("the two selectors are distinct, so a slot value resolves unambiguously")
        void theTwoSelectorsAreDistinct() {
            assertThat(DateFormat.YYYY_MM_DD.getValue())
                    .isNotEqualTo(DateFormat.YYYYMMDD.getValue());
        }

        @Test
        @DisplayName("the hyphenated selector is declared first")
        void theHyphenatedSelectorIsDeclaredFirst() {
            assertThat(DateFormat.values())
                    .containsExactly(DateFormat.YYYY_MM_DD, DateFormat.YYYYMMDD);
        }
    }

    // =================================================================================================
    // SLOT WIDTH
    // =================================================================================================

    /**
     * Verifies that every selector fills the fixed linkage slot exactly.
     */
    @Nested
    @DisplayName("linkage slot width")
    class SlotWidth {

        @Test
        @DisplayName("every selector measures exactly ten characters, which is the property the "
                + "declaring guard exists to protect")
        void everySelectorMeasuresTenCharacters() {
            for (final DateFormat mask : DateFormat.values()) {
                assertThat(mask.getValue())
                        .as("declared width of %s", mask.name())
                        .hasSize(SELECTOR_WIDTH);
            }
        }

        @Test
        @DisplayName("every selector encodes to exactly ten single-byte characters, so it neither "
                + "overruns the slot nor leaves stale bytes in the tail")
        void everySelectorEncodesToTenBytes() {
            for (final DateFormat mask : DateFormat.values()) {
                assertThat(mask.getValue().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of %s", mask.name())
                        .hasSize(SELECTOR_WIDTH);
            }
        }

        @Test
        @DisplayName("the compact mask's own content is eight characters, and the remaining two are the "
                + "padding the slot forces")
        void theCompactMaskIsEightCharactersPlusPadding() {
            final String value = DateFormat.YYYYMMDD.getValue();

            assertThat(value.substring(0, 8)).isEqualTo("YYYYMMDD");
            assertThat(value.substring(8)).isEqualTo("  ").hasSize(SELECTOR_WIDTH - 8);
            assertThat(value.trim()).hasSize(8);
        }

        @Test
        @DisplayName("the hyphenated mask needs no padding, because its own content already fills the "
                + "slot")
        void theHyphenatedMaskNeedsNoPadding() {
            final String value = DateFormat.YYYY_MM_DD.getValue();

            assertThat(value.trim()).isEqualTo(value).hasSize(SELECTOR_WIDTH);
        }
    }

    // =================================================================================================
    // LOOKUP
    // =================================================================================================

    /**
     * Verifies the lookup from a raw slot value back to a selector.
     */
    @Nested
    @DisplayName("lookup from a raw slot value")
    class Lookup {

        @Test
        @DisplayName("both slot values resolve to their selector")
        void bothSlotValuesResolve() {
            assertThat(DateFormat.fromValue(HYPHENATED)).contains(DateFormat.YYYY_MM_DD);
            assertThat(DateFormat.fromValue(COMPACT)).contains(DateFormat.YYYYMMDD);
        }

        @Test
        @DisplayName("every selector round-trips through its own slot value")
        void everySelectorRoundTrips() {
            for (final DateFormat mask : DateFormat.values()) {
                assertThat(DateFormat.fromValue(mask.getValue()))
                        .as("round trip of %s", mask.name())
                        .contains(mask);
            }
        }

        @Test
        @DisplayName("the trimmed compact mask does not resolve, because the slot value includes its "
                + "padding")
        void theTrimmedCompactMaskDoesNotResolve() {
            assertThat(DateFormat.fromValue("YYYYMMDD")).isEmpty();
            assertThat(DateFormat.fromValue("YYYYMMDD ")).isEmpty();
            assertThat(DateFormat.fromValue("YYYYMMDD   ")).isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied, so a lowercase mask does not resolve")
        void noCaseFoldingIsApplied() {
            assertThat(DateFormat.fromValue("yyyy-mm-dd")).isEmpty();
            assertThat(DateFormat.fromValue("yyyymmdd  ")).isEmpty();
        }

        @Test
        @DisplayName("a mask the estate does not pass resolves to nothing, rather than being guessed at")
        void anUnknownMaskResolvesToNothing() {
            assertThat(DateFormat.fromValue("DD-MM-YYYY")).isEmpty();
            assertThat(DateFormat.fromValue("MM/DD/YYYY")).isEmpty();
            assertThat(DateFormat.fromValue("YYYY/MM/DD")).isEmpty();
            assertThat(DateFormat.fromValue("          ")).isEmpty();
        }

        @Test
        @DisplayName("an empty or absent slot value resolves to nothing rather than throwing")
        void anEmptyOrAbsentValueResolvesToNothing() {
            assertThat(DateFormat.fromValue("")).isEmpty();
            assertThat(DateFormat.fromValue(null)).isNotNull().isEmpty();
        }
    }
}
