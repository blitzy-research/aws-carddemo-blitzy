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

import java.nio.charset.StandardCharsets;
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
 * Unit tests for {@link KeyAction}, the five-byte attention-identifier vocabulary of
 * the screen work area.
 *
 * <h2>What is under test</h2>
 *
 * <p>The screen work-area copybook {@code app/cpy/CVCRD01Y.cpy} declares a
 * five-character attention-identifier field with a condition name for each admitted
 * value, and the procedural copybook {@code app/cpy/CSSTRPFY.cpy} sets the matching
 * flag from the terminal attention identifier. Sixteen values are admitted: the
 * enter key, the clear key, the two program-attention keys and the twelve program
 * function keys.</p>
 *
 * <h2>Why the field is five bytes and the short values are padded</h2>
 *
 * <p>The field is a byte reservation, so the two program-attention values are stored
 * with trailing spaces rather than short. That padding is contractual and this class
 * asserts it explicitly, because an unpadded value would leave the remaining bytes of
 * the work area carrying whatever the previous turn left there.</p>
 *
 * <h2>Keys thirteen through twenty-four are deliberately absent</h2>
 *
 * <p>The legacy procedural copybook folds the upper twelve function keys back onto the
 * same twelve flags as the lower twelve, so keys thirteen through twenty-four are not
 * distinct actions. Only twelve function-key constants therefore exist, and this class
 * asserts that no thirteenth is defined — the folding itself belongs to the key
 * translator rather than to this vocabulary.</p>
 */
@DisplayName("KeyAction: the five-byte attention-identifier vocabulary of the screen work area")
class KeyActionBoundaryTest {

    /** Number of attention identifiers the work area admits. */
    private static final int ACTION_COUNT = 16;

    /** Width of the attention-identifier field, in bytes. */
    private static final int AID_FIELD_WIDTH = 5;

    /** Number of program function keys the legacy vocabulary distinguishes. */
    private static final int FUNCTION_KEY_COUNT = 12;

    /**
     * Measures a value in legacy single-byte characters.
     *
     * @param value the value to measure
     * @return the encoded byte count
     */
    private static int encodedWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("the admitted vocabulary")
    class AdmittedVocabulary {

        @Test
        @DisplayName("sixteen actions are defined: enter, clear, two attention keys and twelve function keys")
        void sixteenActionsAreDefined() {
            assertThat(KeyAction.values()).hasSize(ACTION_COUNT);
        }

        @Test
        @DisplayName("the enter action carries its unpadded five-character identifier")
        void theEnterActionCarriesItsIdentifier() {
            assertThat(KeyAction.ENTER.getAid()).isEqualTo("ENTER");
        }

        @Test
        @DisplayName("the clear action carries its unpadded five-character identifier")
        void theClearActionCarriesItsIdentifier() {
            assertThat(KeyAction.CLEAR.getAid()).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("the first attention key carries a padded identifier, not a short one")
        void theFirstAttentionKeyCarriesAPaddedIdentifier() {
            assertThat(KeyAction.PA1.getAid()).isEqualTo("PA1  ");
        }

        @Test
        @DisplayName("the second attention key carries a padded identifier, not a short one")
        void theSecondAttentionKeyCarriesAPaddedIdentifier() {
            assertThat(KeyAction.PA2.getAid()).isEqualTo("PA2  ");
        }

        @ParameterizedTest(name = "function key identifier {0} is defined and zero-padded to two digits")
        @ValueSource(strings = {
            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12"})
        @DisplayName("every function key carries its own zero-padded identifier")
        void everyFunctionKeyCarriesItsIdentifier(String aid) {
            assertThat(KeyAction.valueOf(aid).getAid()).isEqualTo(aid);
        }

        @Test
        @DisplayName("exactly twelve function keys exist, so the upper twelve are not distinct actions")
        void exactlyTwelveFunctionKeysExist() {
            assertThat(Arrays.stream(KeyAction.values())
                    .filter(action -> action.name().startsWith("PFK")))
                    .hasSize(FUNCTION_KEY_COUNT);
        }

        @Test
        @DisplayName("no identifier beyond the twelfth function key is defined")
        void noIdentifierBeyondTheTwelfthFunctionKeyIsDefined() {
            assertThat(Arrays.stream(KeyAction.values()).map(Enum::name))
                    .doesNotContain("PFK13", "PFK14", "PFK15", "PFK16", "PFK17", "PFK18",
                            "PFK19", "PFK20", "PFK21", "PFK22", "PFK23", "PFK24");
        }

        @Test
        @DisplayName("every identifier is distinct, so a stored value identifies one action")
        void everyIdentifierIsDistinct() {
            assertThat(Arrays.stream(KeyAction.values()).map(KeyAction::getAid))
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the declaration order is enter, clear, the attention keys then the function keys")
        void theDeclarationOrderMatchesTheCopybookOrder() {
            assertThat(Arrays.copyOfRange(KeyAction.values(), 0, 4))
                    .containsExactly(KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2);
            assertThat(KeyAction.values()[4]).isSameAs(KeyAction.PFK01);
            assertThat(KeyAction.values()[ACTION_COUNT - 1]).isSameAs(KeyAction.PFK12);
        }
    }

    @Nested
    @DisplayName("the five-byte field invariant")
    class FieldWidthInvariant {

        @Test
        @DisplayName("every identifier measures exactly the field width in encoded bytes")
        void everyIdentifierMeasuresTheFieldWidth() {
            for (KeyAction action : KeyAction.values()) {
                assertThat(encodedWidth(action.getAid())).isEqualTo(AID_FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("no identifier carries a byte outside printable single-byte range")
        void noIdentifierCarriesAnUnprintableByte() {
            for (KeyAction action : KeyAction.values()) {
                for (byte encoded : action.getAid().getBytes(StandardCharsets.US_ASCII)) {
                    assertThat(encoded).isBetween((byte) 0x20, (byte) 0x7E);
                }
            }
        }

        @Test
        @DisplayName("every identifier is right-padded rather than left-padded")
        void everyIdentifierIsRightPadded() {
            for (KeyAction action : KeyAction.values()) {
                assertThat(action.getAid()).doesNotStartWith(" ");
            }
        }
    }

    @Nested
    @DisplayName("the function-key predicate")
    class FunctionKeyPredicate {

        @ParameterizedTest(name = "{0} reports itself as a program function key")
        @ValueSource(strings = {
            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12"})
        @DisplayName("every function key satisfies the predicate")
        void everyFunctionKeySatisfiesThePredicate(String constantName) {
            assertThat(KeyAction.valueOf(constantName).isProgramFunctionKey()).isTrue();
        }

        @ParameterizedTest(name = "{0} does not report itself as a program function key")
        @ValueSource(strings = {"ENTER", "CLEAR", "PA1", "PA2"})
        @DisplayName("neither the enter and clear keys nor the attention keys satisfy the predicate")
        void theNonFunctionKeysDoNotSatisfyThePredicate(String constantName) {
            assertThat(KeyAction.valueOf(constantName).isProgramFunctionKey()).isFalse();
        }

        @Test
        @DisplayName("exactly twelve actions satisfy the predicate, so the partition is complete")
        void exactlyTwelveActionsSatisfyThePredicate() {
            assertThat(Arrays.stream(KeyAction.values()).filter(KeyAction::isProgramFunctionKey))
                    .hasSize(FUNCTION_KEY_COUNT);
        }
    }

    @Nested
    @DisplayName("resolution from a stored identifier")
    class ResolutionFromAid {

        @Test
        @DisplayName("every admitted identifier resolves to its own action")
        void everyAdmittedIdentifierResolves() {
            for (KeyAction action : KeyAction.values()) {
                assertThat(KeyAction.fromAid(action.getAid())).containsSame(action);
            }
        }

        @ParameterizedTest(name = "the identifier [{0}] resolves to nothing")
        @ValueSource(strings = {
            "PA1", "PA2", "PA3  ", "enter", "clear", "pfk01", "PFK13", "PFK00", "PFK1 ",
            "PF001", "     ", "ENTER ", " ENTER", "CLEAR1", "PFK"})
        @DisplayName("an unpadded, lower-case or unadmitted identifier resolves to nothing")
        void anUnadmittedIdentifierResolvesToNothing(String aid) {
            assertThat(KeyAction.fromAid(aid)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("a null identifier resolves to an empty result rather than throwing")
        void aNullIdentifierResolvesToNothing(String aid) {
            assertThat(KeyAction.fromAid(aid)).isEmpty();
        }

        @ParameterizedTest
        @EmptySource
        @DisplayName("an empty identifier resolves to an empty result rather than throwing")
        void anEmptyIdentifierResolvesToNothing(String aid) {
            assertThat(KeyAction.fromAid(aid)).isEmpty();
        }

        @Test
        @DisplayName("the padded attention identifiers resolve while their unpadded spellings do not")
        void thePaddedAttentionIdentifiersResolveAndTheUnpaddedDoNot() {
            Optional<KeyAction> padded = KeyAction.fromAid("PA1  ");

            assertThat(padded).containsSame(KeyAction.PA1);
            assertThat(KeyAction.fromAid("PA1")).isEmpty();
        }

        @Test
        @DisplayName("resolution is stable across calls, so the index is not rebuilt per call")
        void resolutionIsStableAcrossCalls() {
            assertThat(KeyAction.fromAid("PFK03"))
                    .containsSame(KeyAction.PFK03)
                    .isEqualTo(KeyAction.fromAid("PFK03"));
        }
    }
}
