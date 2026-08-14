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
 * Unit test for {@link ReportPeriod}, the three report types the report-request program
 * {@code CORPT00C} offers.
 *
 * <h2>The mixed casing is the contract</h2>
 *
 * <p>Each literal is written by {@code CORPT00C} in title case - a leading capital followed by lower
 * case - and the report-submission path carries the literal onward. Upper-casing or lower-casing it
 * for tidiness would change a transmitted value, so matching is exact: no case folding and no
 * whitespace normalisation. The assertions below prove that an upper-cased and a lower-cased variant
 * both fail to resolve.</p>
 *
 * <h2>An unrecognised period is a legitimate input state</h2>
 *
 * <p>{@code CORPT00C} reaches its catch-all branch when the operator selected no report type at all,
 * and that outcome is an error message produced by the service layer rather than a report type in its
 * own right. The lookup therefore reports absence explicitly and never throws, and no synthetic
 * fourth constant exists to absorb the miss.</p>
 */
@DisplayName("ReportPeriod - the three CORPT00C report types")
class ReportPeriodSecurityTest {

    @Nested
    @DisplayName("Vocabulary recovered from the report-request program")
    class Vocabulary {

        @Test
        @DisplayName("exactly three periods are declared, one per report type the program offers")
        void exactlyThreePeriodsAreDeclared() {
            assertThat(ReportPeriod.values()).hasSize(3);
        }

        @Test
        @DisplayName("the periods are declared in the order the program offers them: monthly, yearly, then custom")
        void thePeriodsAreDeclaredInProgramOrder() {
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .containsExactly("MONTHLY", "YEARLY", "CUSTOM");
        }

        @Test
        @DisplayName("each period carries the bare unpadded literal the program writes, in title case")
        void eachPeriodCarriesItsTitleCaseLiteral() {
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo("Monthly");
            assertThat(ReportPeriod.YEARLY.getValue()).isEqualTo("Yearly");
            assertThat(ReportPeriod.CUSTOM.getValue()).isEqualTo("Custom");
        }

        @ParameterizedTest
        @EnumSource(ReportPeriod.class)
        @DisplayName("every literal is title case rather than upper case, which is the casing the program writes")
        void everyLiteralIsTitleCase(final ReportPeriod period) {
            assertThat(period.getValue()).isNotEqualTo(period.getValue().toUpperCase(java.util.Locale.ROOT));
            assertThat(period.getValue().charAt(0)).isUpperCase();
            assertThat(period.getValue().substring(1))
                    .isEqualTo(period.getValue().substring(1).toLowerCase(java.util.Locale.ROOT));
        }

        @ParameterizedTest
        @EnumSource(ReportPeriod.class)
        @DisplayName("every literal is bare and unpadded, so it carries no leading or trailing whitespace")
        void everyLiteralIsBareAndUnpadded(final ReportPeriod period) {
            assertThat(period.getValue()).isEqualTo(period.getValue().strip());
            assertThat(period.getValue()).isNotBlank();
        }

        @ParameterizedTest
        @EnumSource(ReportPeriod.class)
        @DisplayName("every literal is single-byte US-ASCII, so its character count is its byte count")
        void everyLiteralIsSingleByteAscii(final ReportPeriod period) {
            assertThat(period.getValue().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(period.getValue().length());
        }

        @Test
        @DisplayName("no synthetic fourth constant absorbs the catch-all branch, because selecting no report type is "
                + "an error message rather than a report type")
        void noSyntheticFourthConstantExists() {
            assertThat(Arrays.stream(ReportPeriod.values()).map(Enum::name).toList())
                    .doesNotContain("NONE", "UNKNOWN", "OTHER", "DEFAULT", "DAILY", "WEEKLY", "QUARTERLY");
        }

        @Test
        @DisplayName("all three literals are distinct, which the three-entry immutable map enforces at class "
                + "initialisation")
        void allThreeLiteralsAreDistinct() {
            assertThat(Arrays.stream(ReportPeriod.values())
                    .map(ReportPeriod::getValue).distinct().count()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("Exact lookup from a raw literal")
    class LiteralLookup {

        @ParameterizedTest
        @EnumSource(ReportPeriod.class)
        @DisplayName("every literal round-trips through the lookup back to the period that carries it")
        void everyLiteralRoundTrips(final ReportPeriod period) {
            assertThat(ReportPeriod.fromValue(period.getValue())).contains(period);
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent literal yields an empty result rather than throwing, because the reverse index "
                + "would reject a null probe")
        void anAbsentLiteralYieldsAnEmptyResult(final String value) {
            assertThat(ReportPeriod.fromValue(value)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"MONTHLY", "YEARLY", "CUSTOM", "monthly", "yearly", "custom",
                "Monthly ", " Monthly", "MoNtHlY", "", " ", "Daily", "Weekly", "Quarterly", "0"})
        @DisplayName("a re-cased, padded or unknown literal yields an empty result, because the mixed casing is part "
                + "of the contract and no normalisation is applied")
        void aNonExactLiteralYieldsAnEmptyResult(final String value) {
            assertThat(ReportPeriod.fromValue(value)).isEmpty();
        }

        @Test
        @DisplayName("case folding is not applied in either direction, so neither the upper-cased nor the "
                + "lower-cased form of a valid literal resolves")
        void caseFoldingIsNotAppliedInEitherDirection() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(ReportPeriod.fromValue(period.getValue().toUpperCase(java.util.Locale.ROOT)))
                        .isEmpty();
                assertThat(ReportPeriod.fromValue(period.getValue().toLowerCase(java.util.Locale.ROOT)))
                        .isEmpty();
                assertThat(ReportPeriod.fromValue(period.getValue())).contains(period);
            }
        }
    }
}
