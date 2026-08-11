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
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DateFormat}, the format selector of the shared
 * date-validation linkage contract.
 *
 * <h2>What is under test</h2>
 *
 * <p>The shared subprogram {@code app/cbl/CSUTLDTC.cbl} is called with a
 * three-part parameter area: the candidate date, a format selector and a result
 * block carrying a severity and a message number. The selector occupies a fixed
 * ten-character slot, which is why the selector for the compact form is declared
 * with two trailing spaces rather than as an eight-character string: the slot is a
 * byte reservation, so a shorter value would leave the remaining bytes carrying
 * whatever the caller's storage held.</p>
 *
 * <h2>The width invariant is enforced at construction, and that matters</h2>
 *
 * <p>The production type refuses to construct a selector of any other width. That
 * refusal cannot be triggered from a test, because the only constructor calls are
 * the two constant declarations, so this class asserts the invariant the refusal
 * protects instead: that both declared selectors measure exactly ten bytes in the
 * legacy single-byte character set, measured as encoded bytes rather than as Java
 * characters, because the linkage slot is a byte reservation.</p>
 */
@DisplayName("DateFormat: the ten-byte format selector of the date-validation linkage")
class DateFormatBoundaryTest {

    /** Width of the format-selector slot in the linkage parameter area, in bytes. */
    private static final int SELECTOR_SLOT_WIDTH = 10;

    /** Number of selectors the legacy linkage contract admits. */
    private static final int SELECTOR_COUNT = 2;

    /**
     * Measures a value in legacy single-byte characters.
     *
     * @param value the value to measure
     * @return the encoded byte count
     */
    private static int encodedWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("the admitted selectors")
    class AdmittedSelectors {

        @Test
        @DisplayName("exactly two selectors are defined, matching the two forms the subprogram accepts")
        void exactlyTwoSelectorsAreDefined() {
            assertThat(DateFormat.values()).hasSize(SELECTOR_COUNT);
        }

        @Test
        @DisplayName("the hyphenated selector carries the exact legacy value")
        void theHyphenatedSelectorCarriesItsLegacyValue() {
            assertThat(DateFormat.YYYY_MM_DD.getValue()).isEqualTo("YYYY-MM-DD");
        }

        @Test
        @DisplayName("the compact selector carries the exact legacy value, trailing spaces included")
        void theCompactSelectorCarriesItsLegacyValue() {
            assertThat(DateFormat.YYYYMMDD.getValue()).isEqualTo("YYYYMMDD  ");
        }

        @Test
        @DisplayName("the compact selector is padded with two trailing spaces, not left short")
        void theCompactSelectorIsPaddedWithTwoTrailingSpaces() {
            assertThat(DateFormat.YYYYMMDD.getValue())
                    .startsWith("YYYYMMDD")
                    .endsWith("  ")
                    .hasSize(SELECTOR_SLOT_WIDTH);
        }

        @Test
        @DisplayName("the hyphenated selector needs no padding, because it already fills the slot")
        void theHyphenatedSelectorNeedsNoPadding() {
            assertThat(DateFormat.YYYY_MM_DD.getValue())
                    .isEqualTo("YYYY-MM-DD")
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("every selector value is distinct, so a selector identifies one form")
        void everySelectorValueIsDistinct() {
            assertThat(Arrays.stream(DateFormat.values()).map(DateFormat::getValue))
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("the ten-byte slot invariant")
    class SlotWidthInvariant {

        @ParameterizedTest(name = "{0} fills the slot to exactly ten bytes")
        @ValueSource(strings = {"YYYY_MM_DD", "YYYYMMDD"})
        @DisplayName("every declared selector measures exactly the slot width in encoded bytes")
        void everySelectorMeasuresTheSlotWidth(String constantName) {
            DateFormat format = DateFormat.valueOf(constantName);

            assertThat(encodedWidth(format.getValue())).isEqualTo(SELECTOR_SLOT_WIDTH);
        }

        @ParameterizedTest(name = "{0} is representable in the legacy character set")
        @ValueSource(strings = {"YYYY_MM_DD", "YYYYMMDD"})
        @DisplayName("no selector carries a byte outside printable single-byte range")
        void noSelectorCarriesAnUnprintableByte(String constantName) {
            DateFormat format = DateFormat.valueOf(constantName);

            for (byte encoded : format.getValue().getBytes(StandardCharsets.US_ASCII)) {
                assertThat(encoded).isBetween((byte) 0x20, (byte) 0x7E);
            }
        }

        @Test
        @DisplayName("the byte width and the character width agree, so the values are single-byte throughout")
        void theByteWidthAndCharacterWidthAgree() {
            for (DateFormat format : DateFormat.values()) {
                assertThat(encodedWidth(format.getValue())).isEqualTo(format.getValue().length());
            }
        }
    }

    @Nested
    @DisplayName("resolution from a submitted selector")
    class ResolutionFromValue {

        @ParameterizedTest(name = "the selector [{0}] resolves to the format carrying it")
        @ValueSource(strings = {"YYYY-MM-DD", "YYYYMMDD  "})
        @DisplayName("every admitted selector resolves to its own format")
        void everyAdmittedSelectorResolves(String value) {
            Optional<DateFormat> resolved = DateFormat.fromValue(value);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().getValue()).isEqualTo(value);
        }

        @ParameterizedTest(name = "the unadmitted selector [{0}] resolves to nothing")
        @ValueSource(strings = {
            "YYYYMMDD", "YYYYMMDD   ", " YYYYMMDD ", "yyyy-mm-dd", "YYYY-MM-DD ", "DD-MM-YYYY",
            "MM/DD/YYYY", "YYYY/MM/DD", "CCYYMMDD  ", "          "})
        @DisplayName("an unadmitted selector resolves to an empty result, an unpadded one included")
        void anUnadmittedSelectorResolvesToNothing(String value) {
            assertThat(DateFormat.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("a null selector resolves to an empty result rather than throwing")
        void aNullSelectorResolvesToNothing(String value) {
            assertThat(DateFormat.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @EmptySource
        @DisplayName("an empty selector resolves to an empty result rather than throwing")
        void anEmptySelectorResolvesToNothing(String value) {
            assertThat(DateFormat.fromValue(value)).isEmpty();
        }

        @Test
        @DisplayName("resolution covers every declared format, so no format is unreachable")
        void resolutionCoversEveryDeclaredFormat() {
            for (DateFormat format : DateFormat.values()) {
                assertThat(DateFormat.fromValue(format.getValue())).containsSame(format);
            }
        }
    }
}
