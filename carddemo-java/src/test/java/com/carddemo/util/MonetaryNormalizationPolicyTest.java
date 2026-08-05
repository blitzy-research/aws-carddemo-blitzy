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

package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.carddemo.domain.StoredValueRules;

/**
 * Pins the module's monetary normalisation policy across the two classes that are allowed to impose it,
 * so that the two enforcement points cannot drift apart.
 *
 * <h2>Why two enforcement points exist at all</h2>
 *
 * <p>One policy governs every monetary value in the module: scale two, and truncation toward zero rather
 * than any form of rounding, because a COBOL arithmetic store without a rounding clause truncates and no
 * such clause appears anywhere in the estate. Two classes impose it:
 *
 * <ul>
 *   <li>{@link ZonedDecimalCodec} — the conversion boundary. Every decode of a legacy field image and
 *       every encode back to one passes through it, and it is the only place the rest of the module may
 *       obtain a scaled monetary value from.</li>
 *   <li>{@link StoredValueRules} — the persistence boundary. It normalises a value on its way into a row
 *       and refuses one whose integer part is wider than the legacy field the column maps.</li>
 * </ul>
 *
 * <p>They are not collapsible. The module's layering forbids the entity layer from depending on the
 * utility layer, so the rules class cannot call the codec; and moving the rules into the utility layer
 * would put a persistence invariant below the entities that own it. The duplication is therefore
 * structural rather than accidental — which is exactly why it needs an assertion rather than a comment.
 *
 * <h2>What this class defends</h2>
 *
 * <p>A single edit to one of the four constants involved would be silent: the module would keep
 * compiling, most suites would keep passing, and only a byte-for-byte comparison of emitted records
 * would eventually disagree — by one unit in the second decimal place, on some inputs and not others.
 * The assertions below make that edit loud. They compare the two classes' scale and rounding constants
 * directly, pin both to the values the estate requires rather than merely to each other, and then drive
 * the same values through both code paths and require identical results.
 *
 * <p>The chosen inputs are the ones on which the candidate rounding modes actually differ. A third
 * decimal digit below the halfway point behaves the same under every mode, so it proves nothing; a digit
 * above the halfway point separates truncation from {@code HALF_UP} and {@code HALF_EVEN}, and a negative
 * value separates truncation toward zero from {@code FLOOR}. Both appear below, in both directions.
 *
 * <p>Provenance: the scale and the absence of a rounding clause were established against the read-only
 * legacy estate under {@code app/} at commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec},
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL statement is
 * transcribed here — only the scale, the widths and the truncation behaviour, which are metadata rather
 * than source.
 */
@DisplayName("Monetary normalisation policy: one policy, two enforcement points, no drift")
final class MonetaryNormalizationPolicyTest {

    /**
     * Total digit count of the widest monetary column, of which two are after the decimal point. Used
     * only so that the persistence-boundary call has a precision to check its argument against; every
     * value below is far inside it.
     */
    private static final int ACCOUNT_PRECISION = 12;

    /** Attribute name the persistence boundary quotes back in a rejection; never asserted on. */
    private static final String ATTRIBUTE = "currentBalance";

    /**
     * Values whose third decimal digit is high enough to separate truncation from rounding, in both
     * signs, plus one value that is already at scale two and must survive untouched.
     *
     * @return the argument stream: the input, and the result the policy requires
     */
    private static Stream<org.junit.jupiter.params.provider.Arguments> separatingValues() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("1.999", "1.99"),
                org.junit.jupiter.params.provider.Arguments.of("-1.999", "-1.99"),
                org.junit.jupiter.params.provider.Arguments.of("0.005", "0.00"),
                org.junit.jupiter.params.provider.Arguments.of("-0.005", "0.00"),
                org.junit.jupiter.params.provider.Arguments.of("2.345", "2.34"),
                org.junit.jupiter.params.provider.Arguments.of("-2.345", "-2.34"),
                org.junit.jupiter.params.provider.Arguments.of("12.34", "12.34"),
                org.junit.jupiter.params.provider.Arguments.of("-12.34", "-12.34"));
    }

    @Nested
    @DisplayName("the two enforcement points declare the same policy")
    class ConstantsAgree {

        @Test
        @DisplayName("both declare scale two")
        void bothDeclareScaleTwo() {
            assertThat(StoredValueRules.MONETARY_SCALE)
                    .as("the persistence boundary and the conversion boundary must impose one scale")
                    .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("both declare truncation toward zero, and neither declares a rounding mode")
        void bothDeclareTruncationTowardZero() {
            assertThat(StoredValueRules.MONETARY_ROUNDING)
                    .as("the persistence boundary and the conversion boundary must impose one mode")
                    .isEqualTo(ZonedDecimalCodec.COBOL_TRUNCATION_MODE)
                    .isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("neither declares a mode the estate's absent rounding clause would contradict")
        void neitherDeclaresARoundingMode() {
            assertThat(StoredValueRules.MONETARY_ROUNDING)
                    .isNotIn(RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.HALF_DOWN,
                            RoundingMode.UP, RoundingMode.CEILING, RoundingMode.FLOOR);
            assertThat(ZonedDecimalCodec.COBOL_TRUNCATION_MODE)
                    .isNotIn(RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.HALF_DOWN,
                            RoundingMode.UP, RoundingMode.CEILING, RoundingMode.FLOOR);
        }
    }

    @Nested
    @DisplayName("the two enforcement points behave identically on the values that separate the modes")
    class BehaviourAgrees {

        @ParameterizedTest(name = "{0} normalises to {1} through both boundaries")
        @MethodSource("com.carddemo.util.MonetaryNormalizationPolicyTest#separatingValues")
        @DisplayName("both paths truncate toward zero to the same result")
        void bothPathsProduceTheSameResult(final String input, final String expected) {
            final BigDecimal supplied = new BigDecimal(input);

            final BigDecimal throughCodec = ZonedDecimalCodec.toScale(supplied,
                    ZonedDecimalCodec.MONETARY_SCALE);
            final BigDecimal throughRules = StoredValueRules.normalizedAmount(supplied,
                    ACCOUNT_PRECISION, ATTRIBUTE);

            assertThat(throughCodec)
                    .as("the conversion boundary must truncate toward zero")
                    .isEqualByComparingTo(expected);
            assertThat(throughRules)
                    .as("the persistence boundary must truncate toward zero")
                    .isEqualByComparingTo(expected);
            assertThat(throughRules)
                    .as("a value must not change depending on which boundary it crossed")
                    .isEqualByComparingTo(throughCodec);
            assertThat(throughRules.scale())
                    .as("both boundaries must land on the declared scale, not merely a compatible value")
                    .isEqualTo(throughCodec.scale())
                    .isEqualTo(ZonedDecimalCodec.MONETARY_SCALE);
        }

        @Test
        @DisplayName("a rounding mode other than truncation would have produced a different answer, "
                + "so these assertions are load bearing")
        void theSeparatingValuesReallySeparate() {
            final BigDecimal supplied = new BigDecimal("1.999");

            assertThat(supplied.setScale(2, RoundingMode.HALF_EVEN))
                    .as("if this equalled the truncated result the test above would prove nothing")
                    .isNotEqualByComparingTo(
                            supplied.setScale(2, ZonedDecimalCodec.COBOL_TRUNCATION_MODE));
            assertThat(new BigDecimal("-1.999").setScale(2, RoundingMode.FLOOR))
                    .as("truncation toward zero is not the same as flooring, for a negative value")
                    .isNotEqualByComparingTo(new BigDecimal("-1.999")
                            .setScale(2, ZonedDecimalCodec.COBOL_TRUNCATION_MODE));
        }
    }

    @Nested
    @DisplayName("no third enforcement point has appeared")
    class NoThirdPoint {

        @Test
        @DisplayName("the encode path imposes the same policy as the explicit rescale")
        void encodePathImposesTheSamePolicy() {
            final BigDecimal supplied = new BigDecimal("1.999");

            final String image = ZonedDecimalCodec.encodeMonetary(supplied,
                    ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH, ATTRIBUTE);
            final BigDecimal decoded = ZonedDecimalCodec.decodeMonetary(image,
                    ZonedDecimalCodec.ACCOUNT_AMOUNT_WIDTH, ATTRIBUTE);

            assertThat(decoded)
                    .as("encoding must truncate exactly as an explicit rescale does, not round")
                    .isEqualByComparingTo(ZonedDecimalCodec.toScale(supplied,
                            ZonedDecimalCodec.MONETARY_SCALE))
                    .isEqualByComparingTo("1.99");
        }
    }
}
