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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link KeyAction}, the sixteen attention-key actions the screen work area can record.
 *
 * <p><strong>Where the sixteen come from.</strong> The screen work area declares a five-byte action
 * field, {@code CCARD-AID PIC X(5)}, at {@code app/cpy/CVCRD01Y.cpy} line 3, and immediately below it
 * declares sixteen condition names over that same field at lines 4 through 19 &mdash; one for the enter
 * key, one for the clear key, two for the program-attention keys and twelve for the program-function
 * keys. Sixteen condition names is the whole vocabulary; there is no seventeenth.
 *
 * <p><strong>Why the two program-attention values carry trailing blanks.</strong> The field is five
 * bytes wide and blank-filled, so a three-character name is stored as three characters plus two blanks.
 * The copybook writes those blanks out explicitly in its condition values. This class asserts them,
 * because an action recorded as three characters would not equal the five-byte image the screen layer
 * actually stores, and every comparison against it would silently fail.
 *
 * <p><strong>Why the vocabulary is smaller than the key set.</strong> The function-key copybook
 * recognises twenty-eight distinct terminal identifiers but folds the upper twelve onto the lower twelve,
 * so twenty-eight identifiers collapse onto exactly twelve function-key actions. That fold belongs to
 * the translator and is verified there. What matters here is the consequence: this vocabulary is
 * deliberately lossy relative to the terminal's key set, so nothing may be added to it to make the fold
 * lossless.
 *
 * <p><strong>An independent oracle.</strong> The sixteen expected action images below are transcribed
 * from the copybook's condition values rather than read back from the type under test, so a change to
 * either side is detected rather than accommodated.
 *
 * <p><strong>Deliberately not asserted.</strong> Nothing here maps a terminal identifier to an action;
 * that is the translator's contract and is covered by its own suite. Nothing claims an action ordering
 * with screen meaning, because the copybook assigns none &mdash; each program decides for itself what a
 * given function key does.
 */
@DisplayName("KeyAction — the sixteen attention-key actions of the five-byte action field")
class KeyActionTest {

    /** Width of {@code CCARD-AID}, declared {@code PIC X(5)}. */
    private static final int ACTION_FIELD_WIDTH = 5;

    /** Condition names declared over {@code CCARD-AID} at copybook lines 4 through 19. */
    private static final int LEGACY_CONDITION_NAME_COUNT = 16;

    /** Terminal identifiers the function-key copybook recognises before folding. */
    private static final int RECOGNISED_TERMINAL_IDENTIFIERS = 28;

    /** Program-function actions the twenty-eight identifiers fold onto. */
    private static final int FUNCTION_KEY_ACTIONS = 12;

    /**
     * The sixteen action images transcribed from the condition values at
     * {@code app/cpy/CVCRD01Y.cpy} lines 4 through 19, in copybook order.
     */
    private static final Map<String, String> LEGACY_CONDITION_VALUES = legacyConditionValues();

    /**
     * Transcribes the copybook's sixteen condition values, keyed by the condition-name suffix.
     *
     * <p>The map is wrapped rather than copied into an immutable map, because the copybook's
     * declaration order is itself part of what this suite asserts and a hash-ordered immutable copy
     * would discard it.
     *
     * @return an ordered, unmodifiable view of the copybook's declared action images
     */
    private static Map<String, String> legacyConditionValues() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("ENTER", "ENTER");
        values.put("CLEAR", "CLEAR");
        values.put("PA1", "PA1  ");
        values.put("PA2", "PA2  ");
        values.put("PFK01", "PFK01");
        values.put("PFK02", "PFK02");
        values.put("PFK03", "PFK03");
        values.put("PFK04", "PFK04");
        values.put("PFK05", "PFK05");
        values.put("PFK06", "PFK06");
        values.put("PFK07", "PFK07");
        values.put("PFK08", "PFK08");
        values.put("PFK09", "PFK09");
        values.put("PFK10", "PFK10");
        values.put("PFK11", "PFK11");
        values.put("PFK12", "PFK12");
        return Collections.unmodifiableMap(values);
    }

    // VOCABULARY

    /**
     * Verifies the sixteen actions against the copybook's condition names.
     */
    @Nested
    @DisplayName("vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("exactly sixteen actions exist, one per condition name over the action field")
        void exactlySixteenActionsExist() {
            assertThat(KeyAction.values()).hasSize(LEGACY_CONDITION_NAME_COUNT);
            assertThat(LEGACY_CONDITION_VALUES).hasSize(LEGACY_CONDITION_NAME_COUNT);
        }

        @Test
        @DisplayName("every declared action has a transcribed condition value, and vice versa")
        void everyActionHasATranscribedConditionValue() {
            final List<String> declaredNames = List.of(KeyAction.values()).stream()
                    .map(KeyAction::name)
                    .toList();

            assertThat(declaredNames).containsExactlyElementsOf(LEGACY_CONDITION_VALUES.keySet());
        }

        @Test
        @DisplayName("every action's image equals its transcribed condition value")
        void everyActionImageMatchesTheTranscription() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid())
                        .as("image of %s", action.name())
                        .isEqualTo(LEGACY_CONDITION_VALUES.get(action.name()));
            }
        }

        @Test
        @DisplayName("the two program-attention images carry their two trailing blanks, as the copybook "
                + "writes them")
        void theProgramAttentionImagesCarryTheirTrailingBlanks() {
            assertThat(KeyAction.PA1.getAid()).isEqualTo("PA1  ").endsWith("  ");
            assertThat(KeyAction.PA2.getAid()).isEqualTo("PA2  ").endsWith("  ");
            assertThat(KeyAction.PA1.getAid().strip()).isEqualTo("PA1").hasSize(3);
            assertThat(KeyAction.PA2.getAid().strip()).isEqualTo("PA2").hasSize(3);
        }

        @Test
        @DisplayName("the fourteen non-attention images fill the field exactly and carry no blank")
        void theFourteenOtherImagesCarryNoBlank() {
            for (final KeyAction action : KeyAction.values()) {
                if (action == KeyAction.PA1 || action == KeyAction.PA2) {
                    continue;
                }
                assertThat(action.getAid())
                        .as("image of %s", action.name())
                        .doesNotContain(" ")
                        .hasSize(ACTION_FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("the sixteen images are distinct, so a recorded action is unambiguous")
        void theSixteenImagesAreDistinct() {
            final List<String> images = List.of(KeyAction.values()).stream()
                    .map(KeyAction::getAid)
                    .toList();

            assertThat(images).doesNotHaveDuplicates().hasSize(LEGACY_CONDITION_NAME_COUNT);
        }

        @Test
        @DisplayName("the actions are declared in copybook order: enter, clear, the two attention keys, "
                + "then the twelve function keys ascending")
        void theActionsAreDeclaredInCopybookOrder() {
            assertThat(KeyAction.values()).containsExactly(
                    KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2,
                    KeyAction.PFK01, KeyAction.PFK02, KeyAction.PFK03, KeyAction.PFK04,
                    KeyAction.PFK05, KeyAction.PFK06, KeyAction.PFK07, KeyAction.PFK08,
                    KeyAction.PFK09, KeyAction.PFK10, KeyAction.PFK11, KeyAction.PFK12);
        }

        @Test
        @DisplayName("each function-key image is its two-digit ordinal, zero-filled, so key nine reads "
                + "PFK09 rather than PFK9")
        void eachFunctionKeyImageIsItsZeroFilledOrdinal() {
            final List<KeyAction> functionKeys = List.of(KeyAction.values()).stream()
                    .filter(KeyAction::isProgramFunctionKey)
                    .toList();

            assertThat(functionKeys).hasSize(FUNCTION_KEY_ACTIONS);
            for (int ordinal = 1; ordinal <= FUNCTION_KEY_ACTIONS; ordinal++) {
                final String expected = "PFK" + (ordinal < 10 ? "0" + ordinal : Integer.toString(ordinal));

                assertThat(functionKeys.get(ordinal - 1).getAid())
                        .as("image of function key %d", ordinal)
                        .isEqualTo(expected);
            }
        }
    }

    // FIELD WIDTH

    /**
     * Verifies the fixed five-byte field every action image must fill.
     */
    @Nested
    @DisplayName("the five-byte action field")
    class FieldWidth {

        @Test
        @DisplayName("every image measures exactly five characters, so it fills the field")
        void everyImageMeasuresFiveCharacters() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid())
                        .as("character width of %s", action.name())
                        .hasSize(ACTION_FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("every image measures exactly five encoded bytes, so no character costs two")
        void everyImageMeasuresFiveEncodedBytes() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of %s", action.name())
                        .hasSize(ACTION_FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("every transcribed condition value is also five characters, confirming the "
                + "copybook's own picture rather than the type's")
        void everyTranscribedValueMeasuresFiveCharacters() {
            for (final Map.Entry<String, String> entry : LEGACY_CONDITION_VALUES.entrySet()) {
                assertThat(entry.getValue())
                        .as("transcribed value for %s", entry.getKey())
                        .hasSize(ACTION_FIELD_WIDTH);
            }
        }

        @Test
        @DisplayName("no image is padded on the left, so a recorded action compares from byte one")
        void noImageIsPaddedOnTheLeft() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid())
                        .as("image of %s", action.name())
                        .doesNotStartWith(" ");
            }
        }
    }

    // FUNCTION-KEY PREDICATE

    /**
     * Verifies the partition between the four non-function actions and the twelve function keys.
     */
    @Nested
    @DisplayName("the function-key predicate")
    class FunctionKeyPredicate {

        @Test
        @DisplayName("the twelve function keys report themselves function keys")
        void theTwelveFunctionKeysReportThemselvesSo() {
            assertThat(KeyAction.PFK01.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK02.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK03.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK04.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK05.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK06.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK07.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK08.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK09.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK10.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK11.isProgramFunctionKey()).isTrue();
            assertThat(KeyAction.PFK12.isProgramFunctionKey()).isTrue();
        }

        @Test
        @DisplayName("the enter, clear and two attention actions do not report themselves function keys")
        void theFourOthersDoNotReportThemselvesSo() {
            assertThat(KeyAction.ENTER.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.CLEAR.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA1.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA2.isProgramFunctionKey()).isFalse();
        }

        @Test
        @DisplayName("the predicate partitions the vocabulary twelve to four with nothing left over")
        void thePredicatePartitionsTheVocabulary() {
            final List<KeyAction> functionKeys = List.of(KeyAction.values()).stream()
                    .filter(KeyAction::isProgramFunctionKey)
                    .toList();
            final List<KeyAction> others = List.of(KeyAction.values()).stream()
                    .filter(action -> !action.isProgramFunctionKey())
                    .toList();

            assertThat(functionKeys).hasSize(FUNCTION_KEY_ACTIONS);
            assertThat(others).containsExactly(
                    KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2);
            assertThat(functionKeys.size() + others.size()).isEqualTo(KeyAction.values().length);
        }

        @Test
        @DisplayName("the predicate agrees with the image prefix, so the naming and the classification "
                + "cannot drift apart")
        void thePredicateAgreesWithTheImagePrefix() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.isProgramFunctionKey())
                        .as("classification of %s", action.name())
                        .isEqualTo(action.getAid().startsWith("PFK"));
            }
        }
    }

    // LOOKUP

    /**
     * Verifies the lookup from a recorded five-byte image back to an action.
     */
    @Nested
    @DisplayName("lookup from a recorded image")
    class Lookup {

        @Test
        @DisplayName("every transcribed condition value resolves to the action bearing that name")
        void everyTranscribedValueResolves() {
            for (final Map.Entry<String, String> entry : LEGACY_CONDITION_VALUES.entrySet()) {
                assertThat(KeyAction.fromAid(entry.getValue()))
                        .as("lookup of the value transcribed for %s", entry.getKey())
                        .isPresent()
                        .get()
                        .extracting(KeyAction::name)
                        .isEqualTo(entry.getKey());
            }
        }

        @Test
        @DisplayName("every action round-trips through its own image")
        void everyActionRoundTrips() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(KeyAction.fromAid(action.getAid()))
                        .as("round trip of %s", action.name())
                        .contains(action);
            }
        }

        @Test
        @DisplayName("a stripped attention image does not resolve, because the field is blank-filled")
        void aStrippedAttentionImageDoesNotResolve() {
            assertThat(KeyAction.fromAid("PA1")).isEmpty();
            assertThat(KeyAction.fromAid("PA2")).isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied, so a lower-cased image does not resolve")
        void noCaseFoldingIsApplied() {
            assertThat(KeyAction.fromAid("enter")).isEmpty();
            assertThat(KeyAction.fromAid("pfk03")).isEmpty();
            assertThat(KeyAction.fromAid("Pfk03")).isEmpty();
        }

        @Test
        @DisplayName("an unfolded function-key name does not resolve, because the fold happens before "
                + "the action is recorded")
        void anUnfoldedFunctionKeyNameDoesNotResolve() {
            assertThat(KeyAction.fromAid("PFK13")).isEmpty();
            assertThat(KeyAction.fromAid("PFK24")).isEmpty();
            assertThat(KeyAction.fromAid("PFK00")).isEmpty();
            assertThat(KeyAction.fromAid("PFK9")).isEmpty();
        }

        @Test
        @DisplayName("an image of the wrong width does not resolve, whether short or long")
        void anImageOfTheWrongWidthDoesNotResolve() {
            assertThat(KeyAction.fromAid("ENTE")).isEmpty();
            assertThat(KeyAction.fromAid("ENTER ")).isEmpty();
            assertThat(KeyAction.fromAid("PA1 ")).isEmpty();
            assertThat(KeyAction.fromAid("PA1   ")).isEmpty();
        }

        @Test
        @DisplayName("a blank image resolves to nothing, so an unset action field is not mistaken for a "
                + "key press")
        void aBlankImageResolvesToNothing() {
            assertThat(KeyAction.fromAid("     ")).isEmpty();
            assertThat(KeyAction.fromAid("")).isEmpty();
        }

        @Test
        @DisplayName("an absent image resolves to nothing rather than throwing")
        void anAbsentImageResolvesToNothing() {
            assertThat(KeyAction.fromAid(null)).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("the lookup covers the whole vocabulary and nothing beyond it")
        void theLookupCoversTheWholeVocabularyAndNoMore() {
            long resolved = 0;
            for (final KeyAction action : KeyAction.values()) {
                if (KeyAction.fromAid(action.getAid()).isPresent()) {
                    resolved++;
                }
            }

            assertThat(resolved).isEqualTo(LEGACY_CONDITION_NAME_COUNT);
            assertThat(KeyAction.fromAid("PFK13")).isEmpty();
        }
    }

    // THE DELIBERATE FOLD

    /**
     * Records why the vocabulary is smaller than the terminal's key set.
     */
    @Nested
    @DisplayName("the deliberate fold")
    class DeliberateFold {

        @Test
        @DisplayName("twenty-eight recognised terminal identifiers fold onto sixteen actions, so the "
                + "vocabulary is lossy by design")
        void theVocabularyIsLossyByDesign() {
            assertThat(KeyAction.values().length)
                    .isEqualTo(LEGACY_CONDITION_NAME_COUNT)
                    .isLessThan(RECOGNISED_TERMINAL_IDENTIFIERS);
        }

        @Test
        @DisplayName("the twelve upper function keys have no action of their own, which is what makes "
                + "the fold lossy")
        void theUpperFunctionKeysHaveNoActionOfTheirOwn() {
            for (int upperKey = 13; upperKey <= 24; upperKey++) {
                assertThat(KeyAction.fromAid("PFK" + upperKey))
                        .as("an action for upper function key %d must not exist", upperKey)
                        .isEmpty();
            }
            assertThat(RECOGNISED_TERMINAL_IDENTIFIERS - LEGACY_CONDITION_NAME_COUNT)
                    .isEqualTo(FUNCTION_KEY_ACTIONS);
        }
    }
}
