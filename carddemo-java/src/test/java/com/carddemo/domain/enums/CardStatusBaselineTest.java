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
 * Unit test for {@link CardStatus}, the typed replacement for the one-byte active indicator carried
 * by the card record.
 *
 * <p><strong>What this test proves.</strong> The card record devotes a single byte to the active
 * indicator, and the estate writes only the two bytes {@code Y} and {@code N} into it. Because the
 * field is one byte wide, the type models the code as a {@code char} rather than as a string, and it
 * offers two resolution entry points: one taking the raw character, for a caller slicing a
 * fixed-width record image, and one taking a string, for a caller holding a column value or a request
 * field. The string entry point deliberately refuses anything that is not exactly one character
 * long, because a longer value cannot have come from a one-byte field. This test pins the closed
 * two-member vocabulary, the two code characters, the active predicate, and both resolution
 * overloads including the length guard on the string form.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * connection, reads no file and performs no introspection. Every expected value is a literal typed
 * out in this source; no expectation is produced by calling the type under test.
 *
 * <p>Cited as provenance only and never asserted against a member.
 */
@DisplayName("CardStatus - the one-byte active indicator of the 150-byte card record")
class CardStatusBaselineTest {

    /** The byte written for an active card. */
    private static final char ACTIVE_CODE = 'Y';

    /** The byte written for an inactive card. */
    private static final char INACTIVE_CODE = 'N';

    @Nested
    @DisplayName("Vocabulary and declaration order")
    class Vocabulary {

        @Test
        @DisplayName("exactly two indicators exist, the active one first, matching the order the "
                + "record layout documents for the field")
        void exactlyTwoIndicatorsExist() {
            assertThat(CardStatus.values()).containsExactly(CardStatus.Y, CardStatus.N);
        }

        @Test
        @DisplayName("the enumeration is closed at two members, because the field is one byte wide "
                + "and the estate writes only two values into it")
        void theEnumerationIsClosedAtTwoMembers() {
            assertThat(CardStatus.values()).hasSize(2);
        }

        @Test
        @DisplayName("each member carries its own code character, so the constant name and the stored "
                + "byte agree by construction")
        void eachMemberCarriesItsCodeCharacter() {
            assertThat(CardStatus.Y.getCode()).isEqualTo(ACTIVE_CODE);
            assertThat(CardStatus.N.getCode()).isEqualTo(INACTIVE_CODE);
        }

        @Test
        @DisplayName("the constant name is the single code character in every case, which is why no "
                + "separate name-to-code mapping table is needed")
        void theConstantNameIsTheCodeCharacter() {
            for (final CardStatus status : CardStatus.values()) {
                assertThat(status.name()).hasSize(1);
                assertThat(status.name().charAt(0)).isEqualTo(status.getCode());
            }
        }

        @Test
        @DisplayName("the two code characters are distinct, so the single byte discriminates the two "
                + "indicators without ambiguity")
        void theTwoCodeCharactersAreDistinct() {
            assertThat(CardStatus.Y.getCode()).isNotEqualTo(CardStatus.N.getCode());
        }

        @Test
        @DisplayName("valueOf resolves each constant name back to its member")
        void valueOfResolvesEachConstantName() {
            assertThat(CardStatus.valueOf("Y")).isSameAs(CardStatus.Y);
            assertThat(CardStatus.valueOf("N")).isSameAs(CardStatus.N);
        }
    }

    @Nested
    @DisplayName("Active predicate")
    class ActivePredicate {

        @Test
        @DisplayName("the Y indicator reports the card active")
        void theActiveIndicatorReportsActive() {
            assertThat(CardStatus.Y.isActive()).isTrue();
        }

        @Test
        @DisplayName("the N indicator reports the card inactive")
        void theInactiveIndicatorReportsInactive() {
            assertThat(CardStatus.N.isActive()).isFalse();
        }

        @Test
        @DisplayName("exactly one of the two indicators is active, so the predicate is total and "
                + "unambiguous for every stored byte")
        void exactlyOneIndicatorIsActive() {
            final long active = Arrays.stream(CardStatus.values())
                    .filter(CardStatus::isActive)
                    .count();

            assertThat(active).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Resolution from a raw record byte")
    class CharacterResolution {

        @Test
        @DisplayName("the two known bytes resolve to their indicators")
        void theTwoKnownBytesResolve() {
            assertThat(CardStatus.fromCode(ACTIVE_CODE)).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode(INACTIVE_CODE)).contains(CardStatus.N);
        }

        @ParameterizedTest(name = "byte [{0}] does not resolve")
        @DisplayName("nothing is folded: a lower-case byte, a space, a digit or any other character "
                + "yields no indicator rather than throwing")
        @ValueSource(chars = {'y', 'n', ' ', '0', '1', 'A', 'Z', '*', '-'})
        void nothingIsFolded(final char code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("resolution returns the singleton member rather than a copy, so identity "
                + "comparison remains valid for callers that switch on the result")
        void resolutionReturnsTheSingletonMember() {
            final Optional<CardStatus> resolved = CardStatus.fromCode(ACTIVE_CODE);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow()).isSameAs(CardStatus.Y);
        }

        @Test
        @DisplayName("every member's own byte round-trips through resolution, so the index covers the "
                + "whole vocabulary and not a subset of it")
        void everyMembersOwnByteRoundTrips() {
            for (final CardStatus status : CardStatus.values()) {
                assertThat(CardStatus.fromCode(status.getCode())).contains(status);
            }
        }
    }

    @Nested
    @DisplayName("Resolution from a column or request value")
    class StringResolution {

        @Test
        @DisplayName("a single-character string resolves through the same index as the raw byte")
        void aSingleCharacterStringResolves() {
            assertThat(CardStatus.fromCode("Y")).contains(CardStatus.Y);
            assertThat(CardStatus.fromCode("N")).contains(CardStatus.N);
        }

        @Test
        @DisplayName("an absent value yields no indicator rather than throwing, so a null column is "
                + "reported by the caller instead of aborting the lookup")
        void anAbsentValueYieldsNoIndicator() {
            assertThat(CardStatus.fromCode(null)).isEmpty();
        }

        @ParameterizedTest(name = "value [{0}] does not resolve")
        @DisplayName("a value that is not exactly one character is refused before the index is "
                + "consulted, because it cannot have come from the one-byte record field - so a "
                + "padded or spelled-out value is rejected rather than trimmed")
        @ValueSource(strings = {"", "YY", " Y", "Y ", "YES", "NO", "NN", "  ", "Yn"})
        void aValueThatIsNotExactlyOneCharacterIsRefused(final String code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @ParameterizedTest(name = "value [{0}] does not resolve")
        @DisplayName("a single character outside the vocabulary is refused by the index, which is a "
                + "different rejection path from the length guard and is exercised separately")
        @ValueSource(strings = {"y", "n", " ", "0", "X"})
        void aSingleCharacterOutsideTheVocabularyIsRefused(final String code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("both overloads agree for every member, so a caller slicing a record image and a "
                + "caller reading a column reach the same indicator")
        void bothOverloadsAgreeForEveryMember() {
            for (final CardStatus status : CardStatus.values()) {
                assertThat(CardStatus.fromCode(String.valueOf(status.getCode())))
                        .isEqualTo(CardStatus.fromCode(status.getCode()));
            }
        }
    }
}
