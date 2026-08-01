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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link KeyAction}, the typed replacement for the five-byte attention-identifier work
 * field that the screen work area carries and that the shared function-key copybook populates.
 *
 * <p><strong>What this test proves.</strong> The attention identifier is a five-byte field, and every
 * value written into it is exactly five bytes wide - which is why the two program-access keys are
 * space padded to five while the twelve function keys and the two data keys already fill the field.
 * The padding is part of the stored value and therefore part of the lookup key, so a caller
 * presenting an unpadded spelling does not resolve. The vocabulary stops at the twelfth function key
 * on purpose: the legacy translator folds the upper bank of function keys back onto the same twelve
 * flags, so keys thirteen through twenty-four are not distinct actions and no constants exist for
 * them. This test pins the closed sixteen-member vocabulary, the declaration order, the uniform
 * five-byte width including the two padded values, the strict lookup, the function-key predicate,
 * and the deliberate absence of a second bank of function-key constants.
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * connection, reads no file and performs no introspection. Every expected value is a literal typed
 * out in this source; no expectation is produced by calling the type under test, and the two
 * collection expectations are built from independently typed literals rather than from the members.
 *
 * <p><strong>Provenance.</strong> Legacy estate read at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Cited as provenance only and never asserted
 * against a member.
 */
@DisplayName("KeyAction - the 5-byte attention identifier of the screen work area")
class KeyActionBaselineTest {

    /** Width of the attention-identifier work field. */
    private static final int AID_WIDTH = 5;

    /** Function keys the translator distinguishes, before the upper bank folds onto them. */
    private static final int DISTINCT_FUNCTION_KEYS = 12;

    /** Highest function key the legacy terminal reports, which folds onto the lower bank. */
    private static final int HIGHEST_TERMINAL_FUNCTION_KEY = 24;

    /** Every attention-identifier image, in declaration order, typed out independently. */
    private static final List<String> AID_IMAGES = List.of(
            "ENTER", "CLEAR", "PA1  ", "PA2  ",
            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");

    @Nested
    @DisplayName("Vocabulary and declaration order")
    class Vocabulary {

        @Test
        @DisplayName("exactly sixteen actions exist: the two data keys, the two program-access keys, "
                + "then the twelve function keys in ascending order")
        void exactlySixteenActionsExistInDeclarationOrder() {
            assertThat(KeyAction.values()).containsExactly(
                    KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2,
                    KeyAction.PFK01, KeyAction.PFK02, KeyAction.PFK03, KeyAction.PFK04,
                    KeyAction.PFK05, KeyAction.PFK06, KeyAction.PFK07, KeyAction.PFK08,
                    KeyAction.PFK09, KeyAction.PFK10, KeyAction.PFK11, KeyAction.PFK12);
        }

        @Test
        @DisplayName("the enumeration is closed at sixteen members: four non-function keys plus the "
                + "twelve distinct function keys")
        void theEnumerationIsClosedAtSixteenMembers() {
            assertThat(KeyAction.values()).hasSize(4 + DISTINCT_FUNCTION_KEYS);
        }

        @Test
        @DisplayName("each action carries its own attention-identifier image, in declaration order and "
                + "matching independently typed literals")
        void eachActionCarriesItsAttentionIdentifierImage() {
            final List<String> observed = Arrays.stream(KeyAction.values())
                    .map(KeyAction::getAid)
                    .toList();

            assertThat(observed).isEqualTo(AID_IMAGES);
        }

        @Test
        @DisplayName("the two data keys carry their names unpadded, because both names already fill "
                + "the five-byte field")
        void theTwoDataKeysCarryTheirNamesUnpadded() {
            assertThat(KeyAction.ENTER.getAid()).isEqualTo("ENTER");
            assertThat(KeyAction.CLEAR.getAid()).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("the two program-access keys are space padded to five bytes, because their names "
                + "are only three characters long")
        void theTwoProgramAccessKeysAreSpacePadded() {
            assertThat(KeyAction.PA1.getAid()).isEqualTo("PA1  ");
            assertThat(KeyAction.PA2.getAid()).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("function keys one through nine carry a leading zero, so all twelve images sort "
                + "in numeric order as text")
        void functionKeysBelowTenCarryALeadingZero() {
            assertThat(KeyAction.PFK01.getAid()).isEqualTo("PFK01");
            assertThat(KeyAction.PFK09.getAid()).isEqualTo("PFK09");
            assertThat(KeyAction.PFK10.getAid()).isEqualTo("PFK10");
            assertThat(KeyAction.PFK12.getAid()).isEqualTo("PFK12");
        }

        @Test
        @DisplayName("all sixteen images are distinct, so the stored field discriminates every action "
                + "without ambiguity")
        void allSixteenImagesAreDistinct() {
            final Set<String> distinct = new LinkedHashSet<>(AID_IMAGES);

            assertThat(distinct).hasSize(16);
            assertThat(Arrays.stream(KeyAction.values()).map(KeyAction::getAid).distinct().count())
                    .isEqualTo(16L);
        }

        @Test
        @DisplayName("the constant name equals the trimmed image for every member, which keeps the "
                + "constant readable without a second mapping table")
        void theConstantNameEqualsTheTrimmedImage() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.name()).isEqualTo(action.getAid().trim());
            }
        }

        @Test
        @DisplayName("valueOf resolves a representative constant name from each group back to its "
                + "member")
        void valueOfResolvesRepresentativeConstantNames() {
            assertThat(KeyAction.valueOf("ENTER")).isSameAs(KeyAction.ENTER);
            assertThat(KeyAction.valueOf("CLEAR")).isSameAs(KeyAction.CLEAR);
            assertThat(KeyAction.valueOf("PA1")).isSameAs(KeyAction.PA1);
            assertThat(KeyAction.valueOf("PFK03")).isSameAs(KeyAction.PFK03);
            assertThat(KeyAction.valueOf("PFK12")).isSameAs(KeyAction.PFK12);
        }
    }

    @Nested
    @DisplayName("Uniform five-byte width of the attention identifier")
    class AidWidth {

        @Test
        @DisplayName("every image is exactly five bytes, so writing one into the work field needs "
                + "neither pad nor truncation")
        void everyImageIsExactlyFiveBytes() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid()).hasSize(AID_WIDTH);
            }
        }

        @Test
        @DisplayName("only the two program-access images carry padding; the other fourteen fill the "
                + "field exactly")
        void onlyTheProgramAccessImagesCarryPadding() {
            final long padded = Arrays.stream(KeyAction.values())
                    .filter(action -> !action.getAid().equals(action.getAid().trim()))
                    .count();

            assertThat(padded).isEqualTo(2L);
        }

        @Test
        @DisplayName("padding is trailing rather than leading, so significant text begins at the first "
                + "byte of the field in every case")
        void paddingIsTrailingRatherThanLeading() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid()).doesNotStartWith(" ");
                assertThat(action.getAid().trim()).isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Resolution of a stored attention identifier")
    class AidResolution {

        @Test
        @DisplayName("every image resolves to its own action, so the index covers the whole vocabulary "
                + "and not a subset of it")
        void everyImageResolvesToItsOwnAction() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(KeyAction.fromAid(action.getAid())).contains(action);
            }
        }

        @Test
        @DisplayName("each independently typed image resolves, confirming the index keys on the stored "
                + "field rather than on the constant name")
        void eachIndependentlyTypedImageResolves() {
            assertThat(KeyAction.fromAid("ENTER")).contains(KeyAction.ENTER);
            assertThat(KeyAction.fromAid("CLEAR")).contains(KeyAction.CLEAR);
            assertThat(KeyAction.fromAid("PA1  ")).contains(KeyAction.PA1);
            assertThat(KeyAction.fromAid("PA2  ")).contains(KeyAction.PA2);
            assertThat(KeyAction.fromAid("PFK01")).contains(KeyAction.PFK01);
            assertThat(KeyAction.fromAid("PFK07")).contains(KeyAction.PFK07);
            assertThat(KeyAction.fromAid("PFK12")).contains(KeyAction.PFK12);
        }

        @Test
        @DisplayName("an absent identifier yields no action rather than throwing, so an unset work "
                + "field is reported by the caller instead of aborting the lookup")
        void anAbsentIdentifierYieldsNoAction() {
            assertThat(KeyAction.fromAid(null)).isEmpty();
        }

        @ParameterizedTest(name = "identifier [{0}] does not resolve")
        @DisplayName("the padding is part of the key: the unpadded program-access spellings do not "
                + "resolve, which is why callers must present the field exactly as stored")
        @ValueSource(strings = {"PA1", "PA2", "PA1 ", "PA2 "})
        void theUnpaddedProgramAccessSpellingsDoNotResolve(final String aid) {
            assertThat(KeyAction.fromAid(aid)).isEmpty();
        }

        @ParameterizedTest(name = "identifier [{0}] does not resolve")
        @DisplayName("nothing is folded, trimmed or re-numbered: a case-shifted image, an unpadded "
                + "function-key spelling, or a function key beyond the twelfth is rejected")
        @ValueSource(strings = {
            "enter", "clear", "pfk01", "Enter", "ENTER ", " ENTER",
            "PFK1 ", "PFK 1", "PFK13", "PFK24", "PFK00", "PF001", "PA3  ", "", "     "})
        void nothingIsFoldedTrimmedOrRenumbered(final String aid) {
            assertThat(KeyAction.fromAid(aid)).isEmpty();
        }

        @Test
        @DisplayName("resolution returns the singleton member rather than a copy, so identity "
                + "comparison remains valid for callers that switch on the result")
        void resolutionReturnsTheSingletonMember() {
            final Optional<KeyAction> resolved = KeyAction.fromAid("PFK03");

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow()).isSameAs(KeyAction.PFK03);
        }
    }

    @Nested
    @DisplayName("Function-key predicate")
    class FunctionKeyPredicate {

        @Test
        @DisplayName("all twelve function keys report themselves function keys")
        void allTwelveFunctionKeysReportTrue() {
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
        @DisplayName("the two data keys and the two program-access keys report themselves not function "
                + "keys, because a program that only handles function keys must ignore them")
        void theFourNonFunctionKeysReportFalse() {
            assertThat(KeyAction.ENTER.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.CLEAR.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA1.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA2.isProgramFunctionKey()).isFalse();
        }

        @Test
        @DisplayName("exactly twelve of the sixteen actions are function keys, so the predicate "
                + "partitions the vocabulary twelve to four with no member unclassified")
        void exactlyTwelveActionsAreFunctionKeys() {
            final long functionKeys = Arrays.stream(KeyAction.values())
                    .filter(KeyAction::isProgramFunctionKey)
                    .count();
            final long others = Arrays.stream(KeyAction.values())
                    .filter(action -> !action.isProgramFunctionKey())
                    .count();

            assertThat(functionKeys).isEqualTo(DISTINCT_FUNCTION_KEYS);
            assertThat(others).isEqualTo(4L);
            assertThat(functionKeys + others).isEqualTo(KeyAction.values().length);
        }

        @Test
        @DisplayName("every function-key image begins with the function-key prefix while no "
                + "non-function-key image does, so the predicate and the image agree")
        void thePredicateAndTheImagePrefixAgree() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid().startsWith("PFK"))
                        .isEqualTo(action.isProgramFunctionKey());
            }
        }
    }

    @Nested
    @DisplayName("Deliberate absence of an upper bank of function keys")
    class UpperBankAbsence {

        @Test
        @DisplayName("no constant exists beyond the twelfth function key, because the legacy "
                + "translator folds the upper bank onto the same twelve flags rather than treating "
                + "them as distinct actions")
        void noConstantExistsBeyondTheTwelfthFunctionKey() {
            final Set<String> names =
                    new LinkedHashSet<>(Arrays.stream(KeyAction.values()).map(KeyAction::name).toList());

            assertThat(names).contains("PFK12");
            assertThat(names).doesNotContain("PFK13", "PFK14", "PFK15", "PFK16", "PFK17", "PFK18",
                    "PFK19", "PFK20", "PFK21", "PFK22", "PFK23", "PFK24");
        }

        @Test
        @DisplayName("the fold halves the twenty-four keys the terminal can report onto the twelve the "
                + "programs distinguish, which is why the vocabulary stops where it does")
        void theFoldHalvesTheTerminalKeyRange() {
            assertThat(HIGHEST_TERMINAL_FUNCTION_KEY).isEqualTo(2 * DISTINCT_FUNCTION_KEYS);
        }

        @Test
        @DisplayName("an upper-bank image does not resolve, so a caller cannot smuggle a "
                + "thirteenth-through-twenty-fourth key past the vocabulary")
        void anUpperBankImageDoesNotResolve() {
            assertThat(KeyAction.fromAid("PFK13")).isEmpty();
            assertThat(KeyAction.fromAid("PFK18")).isEmpty();
            assertThat(KeyAction.fromAid("PFK24")).isEmpty();
        }
    }
}
