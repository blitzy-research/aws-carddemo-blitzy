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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link DateFormat}, the format selector transmitted in the {@code CSUTLDTC-PARM}
 * parameter area to the shared date-validation subprogram {@code CSUTLDTC}.
 *
 * <h2>The selector is ten characters wide and one of the two literals is padded</h2>
 *
 * <p>The selector slot is a fixed-width field, so both literals occupy ten positions. The hyphenated
 * form fills them naturally; the compact form is eight characters followed by two spaces, and that
 * padding is part of the transmitted value rather than incidental formatting. A lookup keyed on the
 * trimmed compact form would resolve one selector and silently fail on the other, so both the padded
 * hit and the trimmed miss are asserted below.</p>
 *
 * <h2>There is no default selector</h2>
 *
 * <p>The lookup is total and never falls back. An empty result means the caller supplied something
 * outside the two-value contract, and the decision about how to treat that belongs to the caller -
 * which for the date-validation service means a severity and a message number rather than a silently
 * substituted format.</p>
 */
@DisplayName("DateFormat - the CSUTLDTC format selector")
class DateFormatSecurityTest {

    /** Width of the selector slot in the parameter area. */
    private static final int SELECTOR_WIDTH = 10;

    @Nested
    @DisplayName("Vocabulary recovered from the parameter contract")
    class Vocabulary {

        @Test
        @DisplayName("exactly two selectors are declared, because the estate transmits exactly two")
        void exactlyTwoSelectorsAreDeclared() {
            assertThat(DateFormat.values()).hasSize(2);
        }

        @Test
        @DisplayName("the hyphenated selector is declared before the compact one")
        void theHyphenatedSelectorIsDeclaredFirst() {
            assertThat(Arrays.stream(DateFormat.values()).map(Enum::name).toList())
                    .containsExactly("YYYY_MM_DD", "YYYYMMDD");
        }

        @Test
        @DisplayName("the hyphenated selector is the literal YYYY-MM-DD, which fills the slot naturally")
        void theHyphenatedSelectorFillsTheSlotNaturally() {
            assertThat(DateFormat.YYYY_MM_DD.getValue()).isEqualTo("YYYY-MM-DD");
        }

        @Test
        @DisplayName("the compact selector is YYYYMMDD followed by two spaces, and the padding is part of the "
                + "transmitted value")
        void theCompactSelectorCarriesItsPadding() {
            assertThat(DateFormat.YYYYMMDD.getValue()).isEqualTo("YYYYMMDD  ");
        }

        @Test
        @DisplayName("no third selector and no synthetic default exists, so an unrecognised selector cannot be "
                + "silently substituted")
        void noThirdSelectorAndNoDefaultExists() {
            assertThat(Arrays.stream(DateFormat.values()).map(Enum::name).toList())
                    .doesNotContain("DEFAULT", "UNKNOWN", "NONE", "OTHER", "DDMMYYYY", "MMDDYYYY");
        }

        @Test
        @DisplayName("the two selector values are distinct, which the two-entry immutable map enforces at class "
                + "initialisation")
        void theTwoSelectorValuesAreDistinct() {
            assertThat(DateFormat.YYYY_MM_DD.getValue()).isNotEqualTo(DateFormat.YYYYMMDD.getValue());
        }
    }

    @Nested
    @DisplayName("Ten-character selector slot")
    class SelectorWidth {

        @ParameterizedTest
        @EnumSource(DateFormat.class)
        @DisplayName("every selector is exactly ten characters wide, matching the fixed-width slot")
        void everySelectorIsExactlyTenCharactersWide(final DateFormat format) {
            assertThat(format.getValue()).hasSize(SELECTOR_WIDTH);
        }

        @ParameterizedTest
        @EnumSource(DateFormat.class)
        @DisplayName("every selector encodes to exactly ten bytes, so it fills the slot in the transmitted "
                + "parameter area")
        void everySelectorEncodesToExactlyTenBytes(final DateFormat format) {
            assertThat(format.getValue().getBytes(StandardCharsets.US_ASCII)).hasSize(SELECTOR_WIDTH);
        }

        @Test
        @DisplayName("the hyphenated selector describes a ten-character date and the compact one an eight-character "
                + "date, which is why only the latter needs padding")
        void onlyTheCompactSelectorNeedsPadding() {
            assertThat(DateFormat.YYYY_MM_DD.getValue().strip()).hasSize(10);
            assertThat(DateFormat.YYYYMMDD.getValue().strip()).hasSize(8);
        }
    }

    @Nested
    @DisplayName("Total lookup from a raw selector value")
    class SelectorLookup {

        @ParameterizedTest
        @EnumSource(DateFormat.class)
        @DisplayName("every selector round-trips through the lookup back to the constant that carries it")
        void everySelectorRoundTrips(final DateFormat format) {
            assertThat(DateFormat.fromValue(format.getValue())).contains(format);
        }

        @Test
        @DisplayName("the compact selector resolves only in its padded form; the trimmed form resolves to nothing, "
                + "which is the failure a trimming lookup would hide")
        void theTrimmedCompactFormDoesNotResolve() {
            assertThat(DateFormat.fromValue("YYYYMMDD  ")).contains(DateFormat.YYYYMMDD);
            assertThat(DateFormat.fromValue("YYYYMMDD")).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent selector yields an empty result rather than throwing")
        void anAbsentSelectorYieldsAnEmptyResult(final String value) {
            assertThat(DateFormat.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "          ", "yyyy-mm-dd", "yyyymmdd  ", "YYYY-MM-DD ",
                " YYYY-MM-DD", "YYYY/MM/DD", "DD-MM-YYYY", "MM-DD-YYYY", "YYYYMMDD ", "uuuu-MM-dd", "0"})
        @DisplayName("a re-cased, differently padded, re-ordered or unknown selector yields an empty result, "
                + "because no normalisation is applied and the format is never repaired")
        void aNonExactSelectorYieldsAnEmptyResult(final String value) {
            assertThat(DateFormat.fromValue(value)).isEmpty();
        }

        @Test
        @DisplayName("an over-length selector is reported absent rather than truncated to a match")
        void anOverLengthSelectorIsNotTruncated() {
            assertThat(DateFormat.fromValue("YYYY-MM-DDX")).isEmpty();
        }
    }
}
