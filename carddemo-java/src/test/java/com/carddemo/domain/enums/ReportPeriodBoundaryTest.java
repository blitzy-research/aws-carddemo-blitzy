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

import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReportPeriod}, the three reporting windows the legacy
 * report-request transaction offers.
 *
 * <h2>What is under test</h2>
 *
 * <p>Transaction {@code CR00}, program {@code app/cbl/CORPT00C.cbl}, offers a
 * monthly window, a yearly window and a custom window, and submits a report job
 * from three distinct sites — line 238 for the monthly selection, line 255 for the
 * yearly selection and line 435 for the custom selection, the last after the
 * operator-supplied dates have been validated. The three selections are the whole
 * vocabulary: the program offers no quarterly, weekly, daily or year-to-date
 * window, and adding one would be feature expansion.</p>
 *
 * <h2>Why the resolution is null-tolerant rather than null-rejecting</h2>
 *
 * <p>The legacy screen fields are fixed-width alphanumeric items that arrive as
 * spaces when the operator selects nothing, so an unrecognised or absent value is
 * an ordinary re-prompt path rather than an error condition. The resolution
 * therefore returns an empty result for a null, for an empty string and for any
 * value outside the three, and never throws, so the calling service can drive the
 * legacy re-prompt instead of unwinding.</p>
 */
@DisplayName("ReportPeriod: the three reporting windows of transaction CR00")
class ReportPeriodBoundaryTest {

    /** Number of windows the legacy transaction offers. */
    private static final int WINDOW_COUNT = 3;

    @Nested
    @DisplayName("the offered vocabulary")
    class OfferedVocabulary {

        @Test
        @DisplayName("exactly three windows are defined, one per selection the legacy screen offers")
        void exactlyThreeWindowsAreDefined() {
            assertThat(ReportPeriod.values()).hasSize(WINDOW_COUNT);
        }

        @ParameterizedTest(name = "{0} carries the value {1}")
        @CsvSource({"MONTHLY, Monthly", "YEARLY, Yearly", "CUSTOM, Custom"})
        @DisplayName("each window carries its legacy value in the legacy letter case")
        void eachWindowCarriesItsLegacyValue(ReportPeriod period, String expectedValue) {
            assertThat(period.getValue()).isEqualTo(expectedValue);
        }

        @Test
        @DisplayName("every value is distinct, so a selection identifies one window")
        void everyValueIsDistinct() {
            assertThat(Arrays.stream(ReportPeriod.values()).map(ReportPeriod::getValue))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the declaration order is monthly, yearly then custom, matching the screen order")
        void theDeclarationOrderMatchesTheScreenOrder() {
            assertThat(ReportPeriod.values())
                    .containsExactly(ReportPeriod.MONTHLY, ReportPeriod.YEARLY, ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("no window beyond the three is defined, so no reporting period is invented")
        void noWindowBeyondTheThreeIsDefined() {
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name))
                    .containsExactlyInAnyOrder("MONTHLY", "YEARLY", "CUSTOM")
                    .doesNotContain("QUARTERLY", "WEEKLY", "DAILY", "YEAR_TO_DATE");
        }
    }

    @Nested
    @DisplayName("resolution from a submitted value")
    class ResolutionFromValue {

        @ParameterizedTest(name = "the value {0} resolves to the window carrying it")
        @ValueSource(strings = {"Monthly", "Yearly", "Custom"})
        @DisplayName("every offered value resolves to its own window")
        void everyOfferedValueResolves(String value) {
            Optional<ReportPeriod> resolved = ReportPeriod.fromValue(value);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().getValue()).isEqualTo(value);
        }

        @ParameterizedTest(name = "the unoffered value [{0}] resolves to nothing")
        @ValueSource(strings = {
            "MONTHLY", "monthly", "MoNtHlY", "Monthly ", " Monthly", "Month", "Monthlyy",
            "Quarterly", "Weekly", "Daily", "0", "1", "  "})
        @DisplayName("an unoffered value resolves to an empty result, letter case included")
        void anUnofferedValueResolvesToNothing(String value) {
            assertThat(ReportPeriod.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("a null value resolves to an empty result rather than throwing")
        void aNullValueResolvesToNothing(String value) {
            assertThat(ReportPeriod.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @EmptySource
        @DisplayName("an empty value resolves to an empty result rather than throwing")
        void anEmptyValueResolvesToNothing(String value) {
            assertThat(ReportPeriod.fromValue(value)).isEmpty();
        }

        @Test
        @DisplayName("resolution covers every declared window, so no window is unreachable")
        void resolutionCoversEveryDeclaredWindow() {
            for (ReportPeriod period : ReportPeriod.values()) {
                assertThat(ReportPeriod.fromValue(period.getValue())).containsSame(period);
            }
        }
    }
}
