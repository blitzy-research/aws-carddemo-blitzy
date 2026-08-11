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
package com.carddemo.service;

import com.carddemo.exception.ValidationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The service-owned account-update outcome: fifty-seven components, the screen's own message texts, and a
 * decimal shape it confirms rather than corrects.
 *
 * <p>Three properties carry weight here. The monetary check is a refusal and never a correction, because
 * re-scaling an amount is a rounding decision and the module makes that decision in exactly one place. The
 * finding list is normalised so a caller never distinguishes "no findings" from "no list". And the rendering
 * discloses control state only, because forty-nine of the components are account and customer values.
 */
@DisplayName("AccountUpdateOutcome :: the settled account-update turn the transaction produces")
class AccountUpdateOutcomeTest {

    /** Builds an outcome carrying the five monetary components and the findings supplied. */
    private static AccountUpdateOutcome outcomeWith(final BigDecimal money,
            final List<ValidationException.FieldError> fieldErrors) {
        return new AccountUpdateOutcome("CAUP", "TITLE ONE", "07/19/22", "COACTUPC", "TITLE TWO",
                "23:12:33", "00000000011", "Y", "2020", "01", "15", money, "2029", "12", "31", money,
                "2021", "06", "30", money, money, "GRP000001A", money, "000000456", "123", "45",
                "6789", "1984", "07", "22", "750", "ANN", "B", "SMITH", "1 MAIN ST", "MI", "SUITE 2",
                "48226", "DETROIT", "USA", "248", "555", "0188", "GOVT-ID-000000000001", "313", "555",
                "0199", "4471902856", "Y", "Looks Good.... so far", "ERROR TEXT", true, "ACSTTUS",
                "account-update", ScreenNavigationState.empty(), fieldErrors,
                "sealed-proof-as-presented");
    }

    /** One field finding of each state, in cascade order. */
    private static List<ValidationException.FieldError> twoFindings() {
        return List.of(
                new ValidationException.FieldError("accountStatus", "ACSTTUS",
                        ValidationException.FieldState.MISSING, "must be supplied"),
                new ValidationException.FieldError("ficoScore", "ACSTFCO",
                        ValidationException.FieldState.INVALID,
                        "FICO score: should be between 300 and 850"));
    }

    @Nested
    @DisplayName("the declared shape")
    final class TheDeclaredShape {

        @Test
        @DisplayName("declares exactly fifty-seven components, because the adapter copies out of it "
                + "positionally and a fifty-eighth would reach no client")
        void declaresExactlyFiftySevenComponents() {
            assertThat(AccountUpdateOutcome.class.getRecordComponents()).hasSize(57);
        }

        @Test
        @DisplayName("publishes the two money bounds the five record fields fix")
        void publishesTheTwoMoneyBounds() {
            assertThat(AccountUpdateOutcome.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountUpdateOutcome.MONEY_INTEGER_DIGITS).isEqualTo(10);
        }

        @Test
        @DisplayName("publishes the twelve telephone and range suffixes and the combined postal message, "
                + "each exactly as the legacy emits it")
        void publishesTheScreenTexts() {
            assertThat(AccountUpdateOutcome.SUFFIX_FICO_OUT_OF_RANGE)
                    .isEqualTo(": should be between 300 and 850");
            assertThat(AccountUpdateOutcome.SUFFIX_STATE_NOT_VALID)
                    .isEqualTo(": is not a valid state code");
            assertThat(AccountUpdateOutcome.SUFFIX_AREA_CODE_REQUIRED)
                    .isEqualTo(": Area code must be supplied.");
            assertThat(AccountUpdateOutcome.SUFFIX_AREA_CODE_NOT_3_DIGITS)
                    .isEqualTo(": Area code must be A 3 digit number.");
            assertThat(AccountUpdateOutcome.SUFFIX_AREA_CODE_ZERO)
                    .isEqualTo(": Area code cannot be zero");
            assertThat(AccountUpdateOutcome.SUFFIX_AREA_CODE_NOT_GENERAL_PURPOSE)
                    .isEqualTo(": Not valid North America general purpose area code");
            assertThat(AccountUpdateOutcome.SUFFIX_PREFIX_REQUIRED)
                    .isEqualTo(": Prefix code must be supplied.");
            assertThat(AccountUpdateOutcome.SUFFIX_PREFIX_NOT_3_DIGITS)
                    .isEqualTo(": Prefix code must be A 3 digit number.");
            assertThat(AccountUpdateOutcome.SUFFIX_PREFIX_ZERO)
                    .isEqualTo(": Prefix code cannot be zero");
            assertThat(AccountUpdateOutcome.SUFFIX_LINE_NUMBER_REQUIRED)
                    .isEqualTo(": Line number code must be supplied.");
            assertThat(AccountUpdateOutcome.SUFFIX_LINE_NUMBER_NOT_4_DIGITS)
                    .isEqualTo(": Line number code must be A 4 digit number.");
            assertThat(AccountUpdateOutcome.SUFFIX_LINE_NUMBER_ZERO)
                    .isEqualTo(": Line number code cannot be zero");
            assertThat(AccountUpdateOutcome.MSG_INVALID_ZIP_FOR_STATE)
                    .isEqualTo("Invalid zip code for state");
        }
    }

    @Nested
    @DisplayName("the decimal shape it confirms")
    final class TheDecimalShape {

        @Test
        @DisplayName("accepts an amount already at the record field's own scale, unchanged")
        void acceptsAnAmountAtTheRecordScale() {
            final BigDecimal money = new BigDecimal("1234567890.99");

            assertThat(outcomeWith(money, List.of()).creditLimit())
                    .isEqualTo(money)
                    .satisfies(value -> assertThat(value.scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("accepts an absent amount, because the legacy screen leaves a monetary field blank on "
                + "a submission that never reached the record")
        void acceptsAnAbsentAmount() {
            assertThat(outcomeWith(null, List.of()).creditLimit()).isNull();
        }

        @ParameterizedTest(name = "scale {0} is refused")
        @ValueSource(ints = {0, 1, 3, 4})
        @DisplayName("refuses any scale but two rather than re-scaling, because re-scaling is a rounding "
                + "decision and the module makes that decision in one place only")
        void refusesAnyScaleButTwo(final int scale) {
            final BigDecimal money = new BigDecimal("1.5").setScale(scale, java.math.RoundingMode.DOWN);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> outcomeWith(money, List.of()))
                    .withMessageContaining("creditLimit")
                    .withMessageContaining("scale");
        }

        @Test
        @DisplayName("refuses an eleventh integer digit, which no record field can hold")
        void refusesAnEleventhIntegerDigit() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> outcomeWith(new BigDecimal("12345678901.00"), List.of()))
                    .withMessageContaining("creditLimit")
                    .withMessageContaining("10");
        }

        @Test
        @DisplayName("names the component in the failure and never the amount, so a rejected value cannot "
                + "reach a log through its own diagnostic")
        void namesTheComponentAndNeverTheAmount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> outcomeWith(new BigDecimal("98765.4321"), List.of()))
                    .withMessageNotContaining("98765");
        }
    }

    @Nested
    @DisplayName("the findings it normalises")
    final class TheFindingsItNormalises {

        @Test
        @DisplayName("an absent list becomes an empty one, so a caller never distinguishes no findings "
                + "from no list")
        void anAbsentListBecomesEmpty() {
            final AccountUpdateOutcome outcome = outcomeWith(null, null);

            assertThat(outcome.fieldErrors()).isEmpty();
            assertThat(outcome.hasFieldErrors()).isFalse();
        }

        @Test
        @DisplayName("keeps the findings in cascade order, which is the order an operator saw the fields "
                + "marked")
        void keepsTheFindingsInCascadeOrder() {
            final AccountUpdateOutcome outcome = outcomeWith(null, twoFindings());

            assertThat(outcome.fieldErrors()).extracting(ValidationException.FieldError::bmsFieldId)
                    .containsExactly("ACSTTUS", "ACSTFCO");
            assertThat(outcome.hasFieldErrors()).isTrue();
        }

        @Test
        @DisplayName("copies the list rather than aliasing it, so a later change to the caller's collection "
                + "cannot alter a settled turn")
        void copiesTheListRatherThanAliasingIt() {
            final List<ValidationException.FieldError> mutable = new ArrayList<>(twoFindings());
            final AccountUpdateOutcome outcome = outcomeWith(null, mutable);

            mutable.clear();

            assertThat(outcome.fieldErrors()).hasSize(2);
        }

        @Test
        @DisplayName("publishes an unmodifiable list, so a consumer cannot rewrite what the cascade found")
        void publishesAnUnmodifiableList() {
            final AccountUpdateOutcome outcome = outcomeWith(null, twoFindings());

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> outcome.fieldErrors().clear());
        }

        @Test
        @DisplayName("the error switch is independent of the findings, because the legacy raises it on "
                + "paths that flag no individual field at all")
        void theErrorSwitchIsIndependentOfTheFindings() {
            assertThat(outcomeWith(null, List.of()).error()).isTrue();
            assertThat(outcomeWith(null, List.of()).hasFieldErrors()).isFalse();
        }
    }

    @Nested
    @DisplayName("rendering")
    final class Rendering {

        @Test
        @DisplayName("renders the control state and the finding count and no value at all")
        void rendersTheControlStateOnly() {
            final String rendered = outcomeWith(new BigDecimal("250.00"), twoFindings()).toString();

            assertThat(rendered)
                    .startsWith("AccountUpdateOutcome[error=true")
                    .contains("errorMessage=ERROR TEXT")
                    .contains("focusScreenFieldId=ACSTTUS")
                    .contains("fieldErrorCount=2")
                    .contains("nextRoute=account-update")
                    .contains("values=***REDACTED***");
            assertThat(rendered).doesNotContain("00000000011", "SMITH", "6789", "48226",
                    "GOVT-ID-000000000001", "4471902856", "250.00", "sealed-proof-as-presented");
        }
    }
}
