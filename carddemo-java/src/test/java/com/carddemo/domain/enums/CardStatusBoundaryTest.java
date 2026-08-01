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
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CardStatus}, the one-character active-status vocabulary of
 * the card record.
 *
 * <h2>What is under test</h2>
 *
 * <p>The card copybook {@code app/cpy/CVACT02Y.cpy} declares a one-character
 * active-status field inside the one-hundred-and-fifty byte card record. The
 * vocabulary is the affirmative and negative single characters, and the type
 * exposes an activity predicate so that a service tests a named condition rather
 * than comparing a character literal, which is the translation the migration
 * mandates for a legacy condition-name vocabulary.</p>
 *
 * <h2>Two resolution overloads, and why both exist</h2>
 *
 * <p>The record layout stores the status as a single byte, so a fixed-width record
 * mapper naturally holds a character, whereas a screen field and a JSON document
 * naturally hold a one-character string. Both entry points therefore exist, and the
 * string form is deliberately strict about width: a value of any length other than
 * one resolves to nothing rather than being trimmed or truncated, because trimming
 * would silently accept a padded value that the fixed-width record could never have
 * contained.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("CardStatus: the one-character active-status vocabulary of the card record")
class CardStatusBoundaryTest {

    /** Number of statuses the card record admits. */
    private static final int STATUS_COUNT = 2;

    @Nested
    @DisplayName("the admitted statuses")
    class AdmittedStatuses {

        @Test
        @DisplayName("exactly two statuses are defined, matching the affirmative and negative bytes")
        void exactlyTwoStatusesAreDefined() {
            assertThat(CardStatus.values()).hasSize(STATUS_COUNT);
        }

        @Test
        @DisplayName("the active status carries the affirmative character")
        void theActiveStatusCarriesTheAffirmativeCharacter() {
            assertThat(CardStatus.Y.getCode()).isEqualTo('Y');
        }

        @Test
        @DisplayName("the inactive status carries the negative character")
        void theInactiveStatusCarriesTheNegativeCharacter() {
            assertThat(CardStatus.N.getCode()).isEqualTo('N');
        }

        @Test
        @DisplayName("every code is distinct, so a stored byte identifies one status")
        void everyCodeIsDistinct() {
            assertThat(Arrays.stream(CardStatus.values()).map(CardStatus::getCode))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every code is a printable single-byte character, as the record layout requires")
        void everyCodeIsPrintable() {
            for (CardStatus status : CardStatus.values()) {
                assertThat((int) status.getCode()).isBetween(0x20, 0x7E);
            }
        }
    }

    @Nested
    @DisplayName("the activity predicate")
    class ActivityPredicate {

        @Test
        @DisplayName("the affirmative status reports itself as active")
        void theAffirmativeStatusReportsActive() {
            assertThat(CardStatus.Y.isActive()).isTrue();
        }

        @Test
        @DisplayName("the negative status reports itself as inactive")
        void theNegativeStatusReportsInactive() {
            assertThat(CardStatus.N.isActive()).isFalse();
        }

        @Test
        @DisplayName("exactly one status is active, so the predicate genuinely partitions the vocabulary")
        void exactlyOneStatusIsActive() {
            assertThat(Arrays.stream(CardStatus.values()).filter(CardStatus::isActive))
                    .containsExactly(CardStatus.Y);
        }
    }

    @Nested
    @DisplayName("resolution from a stored character")
    class ResolutionFromCharacter {

        @Test
        @DisplayName("the affirmative character resolves to the active status")
        void theAffirmativeCharacterResolves() {
            assertThat(CardStatus.fromCode('Y')).containsSame(CardStatus.Y);
        }

        @Test
        @DisplayName("the negative character resolves to the inactive status")
        void theNegativeCharacterResolves() {
            assertThat(CardStatus.fromCode('N')).containsSame(CardStatus.N);
        }

        @ParameterizedTest(name = "the character [{0}] resolves to nothing")
        @ValueSource(chars = {'y', 'n', ' ', '0', '1', 'A', 'Z', '*', '-', '\u0000'})
        @DisplayName("an unadmitted character resolves to an empty result, lower case included")
        void anUnadmittedCharacterResolvesToNothing(char code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("resolution covers every declared status, so no status is unreachable")
        void resolutionCoversEveryDeclaredStatus() {
            for (CardStatus status : CardStatus.values()) {
                assertThat(CardStatus.fromCode(status.getCode())).containsSame(status);
            }
        }
    }

    @Nested
    @DisplayName("resolution from a one-character string")
    class ResolutionFromString {

        @ParameterizedTest(name = "the string [{0}] resolves to the status carrying it")
        @ValueSource(strings = {"Y", "N"})
        @DisplayName("every admitted one-character string resolves to its own status")
        void everyAdmittedStringResolves(String code) {
            Optional<CardStatus> resolved = CardStatus.fromCode(code);

            assertThat(resolved).isPresent();
            assertThat(String.valueOf(resolved.orElseThrow().getCode())).isEqualTo(code);
        }

        @ParameterizedTest(name = "the string [{0}] resolves to nothing")
        @ValueSource(strings = {"y", "n", " ", "0", "A", "*", "YY", "NN", "YN", "Y ", " Y", "   "})
        @DisplayName("a string of the wrong width or the wrong character resolves to nothing")
        void aStringOfWrongWidthOrCharacterResolvesToNothing(String code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("a null string resolves to an empty result rather than throwing")
        void aNullStringResolvesToNothing(String code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @ParameterizedTest
        @EmptySource
        @DisplayName("an empty string resolves to an empty result rather than throwing")
        void anEmptyStringResolvesToNothing(String code) {
            assertThat(CardStatus.fromCode(code)).isEmpty();
        }

        @Test
        @DisplayName("a padded value is refused rather than trimmed, as the fixed-width layout requires")
        void aPaddedValueIsRefusedRatherThanTrimmed() {
            assertThat(CardStatus.fromCode("Y ")).isEmpty();
            assertThat(CardStatus.fromCode(" N")).isEmpty();
        }

        @Test
        @DisplayName("both overloads agree for every admitted status")
        void bothOverloadsAgreeForEveryAdmittedStatus() {
            for (CardStatus status : CardStatus.values()) {
                assertThat(CardStatus.fromCode(String.valueOf(status.getCode())))
                        .isEqualTo(CardStatus.fromCode(status.getCode()));
            }
        }
    }
}
