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
package com.carddemo.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The projections the suite's sensitive assertions are made through.
 *
 * <p>One property carries the whole design and is asserted first: a fingerprint comparison is exactly as
 * decisive as an equality comparison. If that did not hold, every converted assertion in the suite would
 * have been weakened rather than made safe, and the conversion would have traded a real check for a
 * comfortable-looking one. The rest of this class asserts that nothing here prints what it was given.
 */
@DisplayName("the projections sensitive assertions are made through")
class SensitiveValuesTest {

    /** A stand-in for a stored credential digest. */
    private static final String SECRET = "$2a$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZabcd";

    /** A different stand-in, one character away from the first. */
    private static final String NEARLY_THE_SAME =
            "$2a$10$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZabce";

    /** Creates the specification. */
    SensitiveValuesTest() {
        // Intentionally empty.
    }

    @Nested
    @DisplayName("the fingerprint")
    class TheFingerprint {

        /** Creates the nested specification. */
        TheFingerprint() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("is as decisive as comparing the values: equal values agree, and values one "
                + "character apart do not")
        void isAsDecisiveAsEquality() {
            assertThat(SensitiveValues.fingerprint(SECRET))
                    .as("the same value must fingerprint the same way, or a correct implementation "
                            + "would fail its own assertion")
                    .isEqualTo(SensitiveValues.fingerprint(SECRET));
            assertThat(SensitiveValues.fingerprint(SECRET))
                    .as("and a value that differs anywhere must fingerprint differently, or a wrong "
                            + "value would pass an assertion that compares fingerprints")
                    .isNotEqualTo(SensitiveValues.fingerprint(NEARLY_THE_SAME));
        }

        @Test
        @DisplayName("contains no part of the value it stands for")
        void revealsNothingOfTheValue() {
            final String fingerprint = SensitiveValues.fingerprint(SECRET);

            assertThat(fingerprint).startsWith("sha256:").hasSize("sha256:".length() + 12);
            for (int length = 4; length <= SECRET.length(); length++) {
                assertThat(fingerprint)
                        .as("no substring of the value may appear in its own fingerprint")
                        .doesNotContain(SECRET.substring(0, length));
            }
        }

        @Test
        @DisplayName("distinguishes an absent value from an empty one, so a null does not read as a "
                + "blank")
        void distinguishesAbsentFromEmpty() {
            assertThat(SensitiveValues.fingerprint(null)).isEqualTo("<absent>");
            assertThat(SensitiveValues.fingerprint("")).isEqualTo("<empty>");
            assertThat(SensitiveValues.fingerprint(null))
                    .isNotEqualTo(SensitiveValues.fingerprint(""));
        }

        @Test
        @DisplayName("is stable across calls, so an assertion cannot pass once and fail once on one "
                + "value")
        void isStable() {
            assertThat(SensitiveValues.fingerprint("0500024453765740"))
                    .isEqualTo(SensitiveValues.fingerprint("0500024453765740"));
        }
    }

    @Nested
    @DisplayName("the description")
    class TheDescription {

        /** Creates the nested specification. */
        TheDescription() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("carries the length and the fingerprint and no character of the value")
        void carriesLengthAndFingerprintOnly() {
            final String described = SensitiveValues.describe("0500024453765740");

            assertThat(described).contains("length=16").contains("sha256:");
            assertThat(described).doesNotContain("0500024453765740");
            assertThat(SensitiveValues.describe(null)).isEqualTo("<absent>");
        }
    }

    @Nested
    @DisplayName("the collection projections")
    class TheCollectionProjections {

        /** Creates the nested specification. */
        TheCollectionProjections() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("fingerprint every member, in order, printing none of them")
        void fingerprintEveryMemberInOrder() {
            final List<String> values = List.of("0500024453765740", "0683586198171516");
            final List<String> fingerprints = SensitiveValues.fingerprints(values);

            assertThat(fingerprints).hasSize(2);
            assertThat(fingerprints.get(0)).isEqualTo(SensitiveValues.fingerprint(values.get(0)));
            assertThat(fingerprints.get(1)).isEqualTo(SensitiveValues.fingerprint(values.get(1)));
            assertThat(fingerprints.toString())
                    .as("the rendering of the whole list must carry no member")
                    .doesNotContain(values.get(0))
                    .doesNotContain(values.get(1));
            assertThatNullPointerException().isThrownBy(() -> SensitiveValues.fingerprints(null));
        }

        @Test
        @DisplayName("count the distinct members, which is what replaces a duplicate-freeness assertion")
        void countTheDistinctMembers() {
            assertThat(SensitiveValues.distinctCount(List.of("a", "b", "c"))).isEqualTo(3);
            assertThat(SensitiveValues.distinctCount(List.of("a", "b", "a"))).isEqualTo(2);
            assertThat(SensitiveValues.distinctCount(List.of())).isZero();
            assertThatNullPointerException().isThrownBy(() -> SensitiveValues.distinctCount(null));
        }

        @Test
        @DisplayName("report ascending order on the values themselves, because a digest does not "
                + "preserve order")
        void reportAscendingOrderOnTheValues() {
            assertThat(SensitiveValues.ascending(List.of("1", "2", "3"))).isTrue();
            assertThat(SensitiveValues.ascending(List.of("1", "3", "2"))).isFalse();
            assertThat(SensitiveValues.ascending(List.of("2", "2"))).isTrue();
            assertThat(SensitiveValues.ascending(List.of("only"))).isTrue();
            assertThat(SensitiveValues.ascending(List.of())).isTrue();
            assertThatNullPointerException().isThrownBy(() -> SensitiveValues.ascending(null));
        }
    }

    @Nested
    @DisplayName("the absence predicate")
    class TheAbsencePredicate {

        /** Creates the nested specification. */
        TheAbsencePredicate() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("answers what doesNotContain answers, without printing either operand")
        void answersWhatDoesNotContainAnswers() {
            assertThat(SensitiveValues.absentFrom("a response body", SECRET)).isTrue();
            assertThat(SensitiveValues.absentFrom("a body carrying " + SECRET, SECRET)).isFalse();
            assertThat(SensitiveValues.absentFrom(null, SECRET))
                    .as("nothing is carried by a body that does not exist")
                    .isTrue();
        }

        @Test
        @DisplayName("refuses an empty needle, which every text trivially contains and which would "
                + "therefore assert nothing")
        void refusesAnEmptyNeedle() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SensitiveValues.absentFrom("text", null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SensitiveValues.absentFrom("text", ""));
        }
    }

    @Nested
    @DisplayName("the signed-token shape")
    class TheSignedTokenShape {

        /** Creates the nested specification. */
        TheSignedTokenShape() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("accepts three non-empty dot-separated parts and refuses everything else")
        void acceptsThreeNonEmptyParts() {
            assertThat(SensitiveValues.hasSignedTokenShape("header.payload.signature")).isTrue();
            assertThat(SensitiveValues.hasSignedTokenShape("header.payload")).isFalse();
            assertThat(SensitiveValues.hasSignedTokenShape("header.payload.signature.extra")).isFalse();
            assertThat(SensitiveValues.hasSignedTokenShape("header..signature"))
                    .as("an empty middle part is not a payload")
                    .isFalse();
            assertThat(SensitiveValues.hasSignedTokenShape("header.payload."))
                    .as("and an empty trailing part is not a signature, which a permissive split would "
                            + "have discarded rather than reported")
                    .isFalse();
            assertThat(SensitiveValues.hasSignedTokenShape("   ")).isFalse();
            assertThat(SensitiveValues.hasSignedTokenShape("")).isFalse();
            assertThat(SensitiveValues.hasSignedTokenShape(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("the type itself")
    class TheType {

        /** Creates the nested specification. */
        TheType() {
            // Intentionally empty.
        }

        @Test
        @DisplayName("holds no state and refuses to be instantiated")
        void refusesInstantiation() throws ReflectiveOperationException {
            final Constructor<SensitiveValues> constructor =
                    SensitiveValues.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }
}
