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

package com.carddemo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the shape rules every natural key must satisfy before it becomes a row: exact width, the digit
 * class where the legacy picture clause is numeric, and refusal rather than repair.
 *
 * <h2>What each assertion is defending</h2>
 *
 * <p>The rules exist because a bounded-length column and a maximum-length constraint both admit a value
 * shorter than the fixed-width field it came from, and a short value is not a harmless near-miss: it
 * splits one record's identity across two rows, and it breaks the character orderings that are only
 * equivalent to their numeric counterparts over equal-width zero-padded digits - the transaction
 * identifier maximum that mints the next identifier, and the card-number ordering the legacy report sort
 * depends on. The assertions below are therefore about the two failure modes that survive compilation:
 * a value of the wrong width, and a value of the right width carrying something that is not a digit.
 *
 * <p>They are equally about what the rules must <em>not</em> do. Every accepted value is asserted to come
 * back byte for byte, including one that is entirely spaces and one that keeps its leading zeros, because
 * a rule that padded or trimmed would invent or destroy an identity rather than report a defect.
 *
 * <p>Provenance: the widths asserted here are those of the read-only legacy copybooks under
 * {@code app/cpy} at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is transcribed.
 */
@DisplayName("Stored value rules: exact width, the digit class where it is contractual, and no repair")
final class StoredValueRulesTest {

    /** The width of the transaction identifier and the card number. */
    private static final int SIXTEEN = 16;

    /** The width of the account identifier. */
    private static final int ELEVEN = 11;

    /** Creates the test class. */
    StoredValueRulesTest() {
    }

    @Nested
    @DisplayName("exact width")
    final class ExactWidth {

        /** Creates the nest. */
        ExactWidth() {
        }

        @Test
        @DisplayName("a value of exactly the declared width is returned unchanged, so the check can be "
                + "applied inline at the point of assignment")
        void aValueOfTheDeclaredWidthIsReturnedUnchanged() {
            final String value = "0000000000000042";

            assertThat(StoredValueRules.requireFixedWidth(value, SIXTEEN, "tranId"))
                    .isSameAs(value)
                    .hasSize(SIXTEEN);
        }

        @Test
        @DisplayName("a value that is entirely spaces at the declared width is accepted, because a legacy "
                + "fixed-width field transmits spaces for an unset value and that is data, not absence")
        void anAllSpaceValueAtTheDeclaredWidthIsAccepted() {
            assertThat(StoredValueRules.requireFixedWidth(" ".repeat(ELEVEN), ELEVEN, "acctGroupId"))
                    .isEqualTo(" ".repeat(ELEVEN));
        }

        @ParameterizedTest(name = "width {0}")
        @ValueSource(ints = {0, 1, 15, 17, 32})
        @DisplayName("a value of any other width is refused, and the message names both the attribute and "
                + "the width that was expected")
        void aValueOfAnyOtherWidthIsRefused(final int suppliedWidth) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StoredValueRules.requireFixedWidth(
                            "0".repeat(suppliedWidth), SIXTEEN, "tranId"))
                    .withMessageContaining("tranId")
                    .withMessageContaining("exactly 16 characters")
                    .withMessageContaining(String.valueOf(suppliedWidth));
        }

        @Test
        @DisplayName("an absent value is refused with a message that says why a key cannot be absent, "
                + "rather than with a bare null failure from somewhere deeper")
        void anAbsentValueIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StoredValueRules.requireFixedWidth(null, SIXTEEN, "tranId"))
                    .withMessageContaining("tranId")
                    .withMessageContaining("must be present");
        }

        @Test
        @DisplayName("nothing is padded and nothing is trimmed, so a caller's defect is reported rather "
                + "than turned into a silently different stored value")
        void nothingIsPaddedAndNothingIsTrimmed() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("padding \"1\" to eleven characters would invent an identity")
                    .isThrownBy(() -> StoredValueRules.requireFixedWidth("1", ELEVEN, "acctId"));
            assertThat(StoredValueRules.requireFixedWidth("1          ", ELEVEN, "acctId"))
                    .as("and a legitimately space-bearing value keeps its spaces")
                    .isEqualTo("1          ");
        }
    }

    @Nested
    @DisplayName("the digit class")
    final class DigitClass {

        /** Creates the nest. */
        DigitClass() {
        }

        @Test
        @DisplayName("a zero-padded digit string at the declared width is returned unchanged, leading "
                + "zeros included, because they are contractual rather than cosmetic")
        void aZeroPaddedDigitStringIsReturnedUnchanged() {
            assertThat(StoredValueRules.requireFixedWidthDigits("0000000000000001", SIXTEEN, "tranId"))
                    .isEqualTo("0000000000000001")
                    .startsWith("0");
        }

        @ParameterizedTest(name = "value {0}")
        @ValueSource(strings = {
            "000000000000000A", "A000000000000000", "00000000 00000000", "000000000000004+",
            "\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660\u0660"})
        @DisplayName("a value of the right width carrying anything outside the ASCII digits is refused, "
                + "including an Arabic-Indic digit a Unicode-aware predicate would have accepted")
        void aNonAsciiDigitValueIsRefused(final String value) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StoredValueRules.requireFixedWidthDigits(value, SIXTEEN, "tranId"))
                    .withMessageContaining("tranId");
        }

        @Test
        @DisplayName("the message names the position of the first character that is not a digit, so the "
                + "defect is locatable without a debugger")
        void theMessageNamesThePositionOfTheOffendingCharacter() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StoredValueRules.requireFixedWidthDigits(
                            "00000000000000X0", SIXTEEN, "tranId"))
                    .withMessageContaining("position 14");
        }

        @Test
        @DisplayName("the width is checked before the digit class, so a short digit string is reported as "
                + "a width defect rather than as a character defect")
        void theWidthIsCheckedBeforeTheDigitClass() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StoredValueRules.requireFixedWidthDigits("42", SIXTEEN, "tranId"))
                    .withMessageContaining("exactly 16 characters");
        }
    }

    @Nested
    @DisplayName("monetary normalisation")
    final class MonetaryNormalisation {

        /** Creates the nest. */
        MonetaryNormalisation() {
        }

        @Test
        @DisplayName("the policy is scale two truncated toward zero, and the constants say so - half-even "
                + "would differ by a cent on roughly half of all interest computations")
        void thePolicyIsScaleTwoTruncatedTowardZero() {
            assertThat(StoredValueRules.MONETARY_SCALE).isEqualTo(2);
            assertThat(StoredValueRules.MONETARY_ROUNDING)
                    .as("no arithmetic statement in the estate carries a rounding clause, and a COBOL store "
                            + "without one truncates")
                    .isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("a longer scale is truncated rather than rounded, in both directions, because a COBOL "
                + "store of a longer intermediate discards the surplus digit")
        void aLongerScaleIsTruncatedRatherThanRounded() {
            assertThat(StoredValueRules.normalizedAmount(new BigDecimal("1.999"), 11, "tranAmt"))
                    .isEqualTo(new BigDecimal("1.99"));
            assertThat(StoredValueRules.normalizedAmount(new BigDecimal("1.005"), 11, "tranAmt"))
                    .as("half-up or half-even would have produced 1.01 here")
                    .isEqualTo(new BigDecimal("1.00"));
            assertThat(StoredValueRules.normalizedAmount(new BigDecimal("-1.999"), 11, "tranAmt"))
                    .as("toward zero, not away from it")
                    .isEqualTo(new BigDecimal("-1.99"));
        }

        @Test
        @DisplayName("a shorter scale is widened to two, so a stored amount always carries both digits the "
                + "legacy field declares")
        void aShorterScaleIsWidenedToTwo() {
            assertThat(StoredValueRules.normalizedAmount(new BigDecimal("7"), 11, "tranAmt"))
                    .isEqualTo(new BigDecimal("7.00"))
                    .extracting(BigDecimal::scale)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a value already at scale two comes back as the very same instance, so the path the "
                + "fixed-width codec produces costs nothing")
        void aValueAlreadyAtScaleTwoIsReturnedUnchanged() {
            final BigDecimal exact = new BigDecimal("123.45");

            assertThat(StoredValueRules.normalizedAmount(exact, 11, "tranAmt")).isSameAs(exact);
        }

        @Test
        @DisplayName("a magnitude the column cannot hold is refused, because it has no faithful stored form "
                + "and no legacy path can produce one")
        void aMagnitudeTheColumnCannotHoldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StoredValueRules.normalizedAmount(
                            new BigDecimal("1000000000.00"), 11, "tranAmt"))
                    .withMessageContaining("tranAmt")
                    .withMessageContaining("9 digits before the decimal point");
            assertThat(StoredValueRules.normalizedAmount(new BigDecimal("999999999.99"), 11, "tranAmt"))
                    .as("the widest value a nine-digit field holds is accepted")
                    .isEqualTo(new BigDecimal("999999999.99"));
            assertThat(StoredValueRules.normalizedAmount(new BigDecimal("9999999999.99"), 12, "acctCurrBal"))
                    .as("and the ten-digit account fields hold one digit more")
                    .isEqualTo(new BigDecimal("9999999999.99"));
        }

        @Test
        @DisplayName("a fractional value below one is accepted, so the precision test measures the integer "
                + "part and not the whole number of significant digits")
        void aFractionalValueBelowOneIsAccepted() {
            assertThat(StoredValueRules.normalizedAmount(new BigDecimal("0.05"), 6, "disIntRate"))
                    .isEqualTo(new BigDecimal("0.05"));
            assertThat(StoredValueRules.normalizedAmount(BigDecimal.ZERO, 6, "disIntRate"))
                    .isEqualTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("an absent amount is refused, because the column is declared not-null and the legacy "
                + "field carries zeros rather than nothing")
        void anAbsentAmountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StoredValueRules.normalizedAmount(null, 11, "tranAmt"))
                    .withMessageContaining("tranAmt")
                    .withMessageContaining("must be present");
        }
    }

    @Nested
    @DisplayName("the type itself")
    final class TheTypeItself {

        /** Creates the nest. */
        TheTypeItself() {
        }

        @Test
        @DisplayName("holds no state and cannot be instantiated, because it is a set of rules rather than "
                + "a collaborator")
        void holdsNoStateAndCannotBeInstantiated() throws ReflectiveOperationException {
            final Constructor<StoredValueRules> constructor =
                    StoredValueRules.class.getDeclaredConstructor();

            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(StoredValueRules.class.getModifiers())).isTrue();
            assertThat(StoredValueRules.class.getDeclaredFields())
                    .as("a rule set that carried instance state could give two callers different answers, "
                            + "so every field it declares is a static final contractual figure")
                    .allSatisfy(field -> {
                        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
                        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
                    })
                    .extracting(java.lang.reflect.Field::getName)
                    .containsExactlyInAnyOrder("MONETARY_SCALE", "MONETARY_ROUNDING");

            // Reached only through this test, and only to prove the constructor is inert: reflection is
            // permitted in test sources and the zero-reflection budget is scoped to src/main/java.
            constructor.setAccessible(true);
            assertThat(constructor.newInstance()).isNotNull();
        }
    }
}
