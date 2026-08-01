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

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ReportPeriod}, the three period selections the report request offers.
 *
 * <p><strong>What the legacy authority is.</strong> The report program moves one of three literals
 * into its report-name work field before submitting the job: the monthly literal at
 * {@code app/cbl/CORPT00C.cbl} line 214, the yearly literal at line 240 and the custom literal at line
 * 433. Those three literals are the entire vocabulary. Each is written in mixed case, initial capital
 * followed by lower case, and the value travels into the submitted job stream, so the casing is part of
 * the external contract rather than a display choice.
 *
 * <p><strong>Why the mixed case is contractual.</strong> The literal is carried in the job-submission
 * card image the report program builds and writes card by card to the submission queue. Anything that
 * reads that stream sees the literal exactly as written. Upper-casing it for tidiness, or lower-casing
 * it for consistency with an enumeration constant name, would change a value that leaves the system.
 * This class therefore asserts the exact casing of all three and asserts that a case-folded probe does
 * not resolve.
 *
 * <p><strong>Why the custom period is the odd one out.</strong> The monthly and yearly selections
 * derive their own date window, while the custom selection takes a start date and an end date from the
 * operator. That difference lives in the request service rather than in this type, so nothing here
 * asserts anything about dates &mdash; but the third constant must exist, because a two-value
 * enumeration would force the custom path to be represented some other way.
 *
 * <p><strong>Deliberately not asserted.</strong> There is no weekly, quarterly or daily period. The
 * report program offers three choices and no more, so adding a fourth would be feature expansion. No
 * ordering claim is made about which period produces the widest window, because the program makes no
 * such claim either.
 */
@DisplayName("ReportPeriod — the three period selections, in the report program's own casing")
class ReportPeriodTest {

    /** The three literals exactly as the report program writes them. */
    private static final List<String> LEGACY_LITERALS = List.of("Monthly", "Yearly", "Custom");

    // VOCABULARY

    /**
     * Verifies the three-value vocabulary the report program offers.
     */
    @Nested
    @DisplayName("vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("exactly three periods exist, because the report program offers exactly three")
        void exactlyThreePeriodsExist() {
            assertThat(ReportPeriod.values()).hasSize(LEGACY_LITERALS.size());
        }

        @Test
        @DisplayName("each period carries its legacy literal verbatim, including the initial capital")
        void eachPeriodCarriesItsLegacyLiteral() {
            assertThat(ReportPeriod.MONTHLY.getValue()).isEqualTo("Monthly");
            assertThat(ReportPeriod.YEARLY.getValue()).isEqualTo("Yearly");
            assertThat(ReportPeriod.CUSTOM.getValue()).isEqualTo("Custom");
        }

        @Test
        @DisplayName("the periods are declared in the order the report program's branches appear")
        void thePeriodsAreDeclaredInBranchOrder() {
            assertThat(ReportPeriod.values()).containsExactly(
                    ReportPeriod.MONTHLY, ReportPeriod.YEARLY, ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("the declared values match the legacy literal list element for element")
        void theDeclaredValuesMatchTheLegacyLiterals() {
            final List<String> declared = List.of(
                    ReportPeriod.MONTHLY.getValue(),
                    ReportPeriod.YEARLY.getValue(),
                    ReportPeriod.CUSTOM.getValue());

            assertThat(declared).containsExactlyElementsOf(LEGACY_LITERALS);
        }

        @Test
        @DisplayName("every value is mixed case rather than upper or lower, because the literal leaves "
                + "the system inside a submitted job stream")
        void everyValueIsMixedCase() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                final String value = period.getValue();

                assertThat(value)
                        .as("value of %s", period.name())
                        .isNotEqualTo(value.toUpperCase(Locale.ROOT))
                        .isNotEqualTo(value.toLowerCase(Locale.ROOT));
                assertThat(value.substring(0, 1))
                        .as("initial character of %s", period.name())
                        .isEqualTo(value.substring(0, 1).toUpperCase(Locale.ROOT));
                assertThat(value.substring(1))
                        .as("remainder of %s", period.name())
                        .isEqualTo(value.substring(1).toLowerCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("the three values are distinct, so a submitted stream identifies its period "
                + "unambiguously")
        void theThreeValuesAreDistinct() {
            assertThat(LEGACY_LITERALS).doesNotHaveDuplicates();
            assertThat(List.of(
                    ReportPeriod.MONTHLY.getValue(),
                    ReportPeriod.YEARLY.getValue(),
                    ReportPeriod.CUSTOM.getValue()))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the custom period exists as its own constant, because the operator-supplied window "
                + "cannot be represented by the other two")
        void theCustomPeriodExistsAsItsOwnConstant() {
            assertThat(ReportPeriod.CUSTOM)
                    .isNotEqualTo(ReportPeriod.MONTHLY)
                    .isNotEqualTo(ReportPeriod.YEARLY);
        }
    }

    // LOOKUP

    /**
     * Verifies the lookup from a raw literal back to a period.
     */
    @Nested
    @DisplayName("lookup from a raw literal")
    class Lookup {

        @Test
        @DisplayName("all three legacy literals resolve to their period")
        void allThreeLiteralsResolve() {
            assertThat(ReportPeriod.fromValue("Monthly")).contains(ReportPeriod.MONTHLY);
            assertThat(ReportPeriod.fromValue("Yearly")).contains(ReportPeriod.YEARLY);
            assertThat(ReportPeriod.fromValue("Custom")).contains(ReportPeriod.CUSTOM);
        }

        @Test
        @DisplayName("every period round-trips through its own literal")
        void everyPeriodRoundTrips() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(ReportPeriod.fromValue(period.getValue()))
                        .as("round trip of %s", period.name())
                        .contains(period);
            }
        }

        @Test
        @DisplayName("no case folding is applied, so neither the upper-cased nor the lower-cased form "
                + "resolves")
        void noCaseFoldingIsApplied() {
            for (final String literal : LEGACY_LITERALS) {
                assertThat(ReportPeriod.fromValue(literal.toUpperCase(Locale.ROOT)))
                        .as("upper-cased probe for [%s]", literal)
                        .isEmpty();
                assertThat(ReportPeriod.fromValue(literal.toLowerCase(Locale.ROOT)))
                        .as("lower-cased probe for [%s]", literal)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the constant name is not the literal, so resolving by constant name fails")
        void theConstantNameIsNotTheLiteral() {
            for (final ReportPeriod period : ReportPeriod.values()) {
                assertThat(period.name()).isNotEqualTo(period.getValue());
                assertThat(ReportPeriod.fromValue(period.name()))
                        .as("probe by constant name %s", period.name())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("no trimming is applied, so a padded literal does not resolve")
        void noTrimmingIsApplied() {
            assertThat(ReportPeriod.fromValue("Monthly ")).isEmpty();
            assertThat(ReportPeriod.fromValue(" Monthly")).isEmpty();
        }

        @Test
        @DisplayName("a period the report program does not offer resolves to nothing")
        void anUnofferedPeriodResolvesToNothing() {
            assertThat(ReportPeriod.fromValue("Weekly")).isEmpty();
            assertThat(ReportPeriod.fromValue("Quarterly")).isEmpty();
            assertThat(ReportPeriod.fromValue("Daily")).isEmpty();
            assertThat(ReportPeriod.fromValue("Annual")).isEmpty();
        }

        @Test
        @DisplayName("an empty or absent literal resolves to nothing rather than throwing")
        void anEmptyOrAbsentLiteralResolvesToNothing() {
            assertThat(ReportPeriod.fromValue("")).isEmpty();
            assertThat(ReportPeriod.fromValue(null)).isNotNull().isEmpty();
        }
    }
}
