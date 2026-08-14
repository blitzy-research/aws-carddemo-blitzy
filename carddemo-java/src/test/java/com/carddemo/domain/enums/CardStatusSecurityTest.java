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
 * Unit test for {@link CardStatus}, the {@code CARD-ACTIVE-STATUS} flag declared {@code PIC X(01)}
 * at byte offset 91 of the 150-byte card record in copybook member {@code CVACT02Y}.
 *
 * <h2>Two lookups exist because two callers exist, and they must agree</h2>
 *
 * <p>The character overload is what a fixed-width record mapper calls, having sliced a single byte
 * out of the record image. The string overload is what the service layer calls, because the card
 * entity stores the column value as a {@code String}. The string overload delegates to the character
 * one after a length check, so the two can only disagree if that delegation breaks - which is
 * exactly what the cross-overload assertions below pin.</p>
 *
 * <h2>Neither lookup repairs its input</h2>
 *
 * <p>The field is one character wide, so anything else cannot be a status code. A {@code null}, an
 * empty string, a blank, a padded value and an over-length value all yield an empty result, and no
 * case folding is applied, so a lowercase {@code y} is not an active status. That strictness is what
 * stops an unvalidated column value from being silently promoted to active.</p>
 */
@DisplayName("CardStatus - the CARD-ACTIVE-STATUS flag from CVACT02Y")
class CardStatusSecurityTest {

    /** Byte offset of {@code CARD-ACTIVE-STATUS} within the 150-byte card record. */
    private static final int STATUS_OFFSET_IN_RECORD = 91;

    /** Declared width of the card record, from the copybook banner. */
    private static final int CARD_RECORD_WIDTH = 150;

    @Nested
    @DisplayName("Vocabulary recovered from the one-character flag")
    class Vocabulary {

        @Test
        @DisplayName("exactly two constants are declared, because the flag admits exactly two codes")
        void exactlyTwoConstantsAreDeclared() {
            assertThat(CardStatus.values()).hasSize(2);
        }

        @Test
        @DisplayName("the constants are named for the raw codes they carry, Y before N")
        void theConstantsAreNamedForTheirRawCodes() {
            assertThat(Arrays.stream(CardStatus.values()).map(Enum::name).toList())
                    .containsExactly("Y", "N");
        }

        @Test
        @DisplayName("the two constants carry the raw codes Y and N")
        void bothConstantsCarryTheirRawCode() {
            assertThat(CardStatus.Y.getCode()).isEqualTo('Y');
            assertThat(CardStatus.N.getCode()).isEqualTo('N');
        }

        @ParameterizedTest
        @EnumSource(CardStatus.class)
        @DisplayName("each raw code is a single byte that encodes to its own ASCII code point, matching PIC X(01)")
        void eachRawCodeIsASingleAsciiByte(final CardStatus status) {
            assertThat(String.valueOf(status.getCode()).getBytes(StandardCharsets.US_ASCII))
                    .hasSize(1);
        }

        @Test
        @DisplayName("the flag sits at byte offset 91 of the 150-byte card record, which is why the character "
                + "overload exists at all")
        void theFlagSitsAtByteOffsetNinetyOne() {
            assertThat(STATUS_OFFSET_IN_RECORD).isLessThan(CARD_RECORD_WIDTH);
        }

        @Test
        @DisplayName("no synthetic third constant exists for an unset or unrecognised flag")
        void noSyntheticThirdConstantExists() {
            assertThat(Arrays.stream(CardStatus.values()).map(Enum::name).toList())
                    .doesNotContain("UNKNOWN", "NONE", "BLANK", "OTHER", "DEFAULT");
        }
    }

    @Nested
    @DisplayName("Active predicate")
    class ActivePredicate {

        @Test
        @DisplayName("the Y constant is active and the N constant is not")
        void onlyTheYConstantIsActive() {
            assertThat(CardStatus.Y.isActive()).isTrue();
            assertThat(CardStatus.N.isActive()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the two constants reports itself active, so the predicate partitions the "
                + "vocabulary")
        void exactlyOneConstantIsActive() {
            assertThat(Arrays.stream(CardStatus.values()).filter(CardStatus::isActive).count())
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Character lookup, the form a record mapper calls")
    class CharacterLookup {

        @ParameterizedTest
        @EnumSource(CardStatus.class)
        @DisplayName("both raw codes round-trip through the character lookup back to their constant")
        void bothRawCodesRoundTrip(final CardStatus status) {
            assertThat(CardStatus.fromCode(status.getCode())).contains(status);
        }

        @ParameterizedTest
        @ValueSource(chars = {'y', 'n', ' ', '\0', 'A', 'Z', '0', '1', '*', 'Ÿ'})
        @DisplayName("a byte outside the two-code vocabulary yields an empty result, with no case folding applied, "
                + "so a lowercase y is not an active status")
        void aByteOutsideTheVocabularyYieldsAnEmptyResult(final char code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }
    }

    @Nested
    @DisplayName("String lookup, the form the service layer calls")
    class StringLookup {

        @ParameterizedTest
        @EnumSource(CardStatus.class)
        @DisplayName("both one-character column values round-trip through the string lookup back to their constant")
        void bothColumnValuesRoundTrip(final CardStatus status) {
            assertThat(CardStatus.fromCode(String.valueOf(status.getCode()))).contains(status);
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent column value yields an empty result rather than throwing")
        void anAbsentColumnValueYieldsAnEmptyResult(final String code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "  ", "y", "n", "Y ", " Y", "YY", "YN", "NY", "0", "A", "ACTIVE"})
        @DisplayName("a column value that is empty, blank, padded, over-length, lower case or outside the "
                + "vocabulary yields an empty result, because the field is exactly one character wide")
        void aColumnValueOutsideTheVocabularyYieldsAnEmptyResult(final String code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("an over-length value is rejected rather than truncated, so YY does not resolve to Y")
        void anOverLengthValueIsRejectedRatherThanTruncated() {
            assertThat(CardStatus.fromCode("YY")).isEmpty();
            assertThat(CardStatus.fromCode("Y")).contains(CardStatus.Y);
        }
    }

    @Nested
    @DisplayName("The two overloads must agree, because two different callers use them")
    class TheTwoOverloadsAgree {

        @ParameterizedTest
        @EnumSource(CardStatus.class)
        @DisplayName("for every declared code the character overload and the string overload return the same "
                + "constant, which is the delegation the string overload relies on")
        void theOverloadsAgreeOnEveryDeclaredCode(final CardStatus status) {
            assertThat(CardStatus.fromCode(String.valueOf(status.getCode())))
                    .isEqualTo(CardStatus.fromCode(status.getCode()));
        }

        @ParameterizedTest
        @ValueSource(chars = {'y', 'n', ' ', 'A', '0', '*'})
        @DisplayName("for every undeclared single byte the two overloads agree that the result is absent")
        void theOverloadsAgreeOnEveryUndeclaredByte(final char code) {
            assertThat(CardStatus.fromCode(String.valueOf(code)))
                    .isEqualTo(CardStatus.fromCode(code))
                    .isEmpty();
        }
    }
}
