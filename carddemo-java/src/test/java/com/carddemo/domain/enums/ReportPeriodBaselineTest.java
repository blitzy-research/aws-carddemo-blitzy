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

import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link ReportPeriod}, the typed replacement for the three report windows the
 * transaction-report request screen offers.
 *
 * <p><strong>What this test proves.</strong> The report-request program offers exactly three
 * windows - a monthly window, a yearly window and a caller-supplied custom window - and each of the
 * three drives the same job submission with a different pair of boundary dates. The external values
 * are title-cased words rather than upper-case codes, which matters because the resolution performed
 * here is an exact map lookup with no case folding: an upper-cased or lower-cased spelling does not
 * resolve. This test pins the closed three-member vocabulary, the exact title-cased spellings, the
 * declaration order in which the program tests the three options, and the strictness of the lookup.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * connection, reads no file, publishes no message and performs no introspection. Every expected
 * value is a literal typed out in this source; no expectation is produced by calling the type under
 * test.
 *
 * <p>Cited as provenance only and never asserted against a member.
 */
@DisplayName("ReportPeriod - the three report windows offered by the report-request transaction")
class ReportPeriodBaselineTest {

    /** External value of the monthly window, exactly as the request screen carries it. */
    private static final String MONTHLY_VALUE = "Monthly";

    /** External value of the yearly window, exactly as the request screen carries it. */
    private static final String YEARLY_VALUE = "Yearly";

    /** External value of the caller-supplied window, exactly as the request screen carries it. */
    private static final String CUSTOM_VALUE = "Custom";

    @Nested
    @DisplayName("Vocabulary and declaration order")
    class Vocabulary {

        @Test
        @DisplayName("exactly three windows exist, monthly then yearly then custom, matching the "
                + "order in which the report-request program tests the three options")
        void exactlyThreeWindowsExistInRequestTestOrder() {
            assertThat(ReportPeriod.values())
                    .containsExactly(ReportPeriod.MONTHLY, ReportPeriod.YEARLY, ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("the enumeration is closed at three members, because the request screen offers "
                + "no fourth window and adding one would be feature expansion")
        void theEnumerationIsClosedAtThreeMembers() {
            assertThat(ReportPeriod.values()).hasSize(3);
        }

        @Test
        @DisplayName("each window carries its title-cased external value: one leading capital "
                + "followed by lower-case letters, not an upper-case code")
        void eachWindowCarriesItsTitleCasedExternalValue() {
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo(MONTHLY_VALUE);
            assertThat(ReportPeriod.YEARLY.getValue()).isEqualTo(YEARLY_VALUE);
            assertThat(ReportPeriod.CUSTOM.getValue()).isEqualTo(CUSTOM_VALUE);
        }

        @Test
        @DisplayName("every external value is title cased, so a case-insensitive comparison would "
                + "mask a spelling defect that this exact comparison catches")
        void everyExternalValueIsTitleCased() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                final String value = period.getValue();

                assertThat(value).isNotEmpty();
                assertThat(value.substring(0, 1)).isUpperCase();
                assertThat(value.substring(1)).isLowerCase();
            }
        }

        @Test
        @DisplayName("the three external values are distinct, so the stored value discriminates the "
                + "three windows without ambiguity")
        void theThreeExternalValuesAreDistinct() {
            assertThat(Arrays.stream(ReportPeriod.values()).map(ReportPeriod::getValue).distinct().count())
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("no external value carries padding, unlike the fixed-width source-type "
                + "vocabulary, because this value never occupies a fixed-width record field")
        void noExternalValueCarriesPadding() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(period.getValue()).isEqualTo(period.getValue().trim());
            }
        }

        @Test
        @DisplayName("the constant names are stable identifiers that request payloads may reference")
        void theConstantNamesAreStable() {
            assertThat(ReportPeriod.MONTHLY.name()).isEqualTo("MONTHLY");
            assertThat(ReportPeriod.YEARLY.name()).isEqualTo("YEARLY");
            assertThat(ReportPeriod.CUSTOM.name()).isEqualTo("CUSTOM");
        }

        @Test
        @DisplayName("valueOf resolves each constant name back to its member")
        void valueOfResolvesEachConstantName() {
            assertThat(ReportPeriod.valueOf("MONTHLY")).isSameAs(ReportPeriod.MONTHLY);
            assertThat(ReportPeriod.valueOf("YEARLY")).isSameAs(ReportPeriod.YEARLY);
            assertThat(ReportPeriod.valueOf("CUSTOM")).isSameAs(ReportPeriod.CUSTOM);
        }
    }

    @Nested
    @DisplayName("Resolution of a submitted window value")
    class ValueResolution {

        @Test
        @DisplayName("each title-cased value resolves to its own window")
        void eachTitleCasedValueResolves() {
            assertThat(ReportPeriod.fromValue(MONTHLY_VALUE)).contains(ReportPeriod.MONTHLY);
            assertThat(ReportPeriod.fromValue(YEARLY_VALUE)).contains(ReportPeriod.YEARLY);
            assertThat(ReportPeriod.fromValue(CUSTOM_VALUE)).contains(ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("an absent value yields no window rather than throwing, so a caller that omits "
                + "the field receives a validation failure instead of an exception")
        void anAbsentValueYieldsNoWindow() {
            assertThat(ReportPeriod.fromValue(null)).isEmpty();
        }

        @ParameterizedTest(name = "value [{0}] does not resolve")
        @DisplayName("nothing is folded, trimmed or abbreviated: an upper-cased, lower-cased, padded "
                + "or shortened spelling is rejected")
        @ValueSource(strings = {
            "MONTHLY", "monthly", "YEARLY", "yearly", "CUSTOM", "custom",
            " Monthly", "Monthly ", "Month", "Year", "Weekly", "Daily", "Quarterly", "", " "})
        void nothingIsFoldedTrimmedOrAbbreviated(final String value) {
            assertThat(ReportPeriod.fromValue(value)).isEmpty();
        }

        @Test
        @DisplayName("resolution returns the singleton member rather than a copy, so identity "
                + "comparison remains valid for callers that switch on the result")
        void resolutionReturnsTheSingletonMember() {
            final Optional<ReportPeriod> resolved = ReportPeriod.fromValue(CUSTOM_VALUE);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow()).isSameAs(ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("every member's own value round-trips through resolution, so the index covers "
                + "the whole vocabulary and not a subset of it")
        void everyMembersOwnValueRoundTrips() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(ReportPeriod.fromValue(period.getValue())).contains(period);
            }
        }
    }
}
