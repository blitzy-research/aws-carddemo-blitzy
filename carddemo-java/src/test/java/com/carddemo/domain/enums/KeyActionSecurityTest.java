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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link KeyAction}, the Java realisation of the {@code CCARD-AID} attention-key
 * identifier declared {@code PIC X(5)} in the {@code CC-WORK-AREAS} structure of copybook member
 * {@code CVCRD01Y}.
 *
 * <h2>What is actually at risk here</h2>
 *
 * <p>Two properties of this enum are load-bearing and neither is self-evident from reading it.</p>
 *
 * <p>The first is that the identifier is <em>five characters wide including its padding</em>. Two of
 * the sixteen literals are shorter than the field and are space padded in the source literal itself,
 * so a lookup keyed on a trimmed form would resolve fourteen identifiers and silently fail on the
 * remaining two. A test that only ever probes the twelve program-function identifiers - whose
 * two-digit zero-padded suffix happens to fill the field naturally - would never notice. The
 * assertions below therefore probe the padded forms explicitly and assert the trimmed forms do
 * <em>not</em> resolve.</p>
 *
 * <p>The second is that there is deliberately no default constant. The legacy mapping lives in the
 * {@code YYYY-STORE-PFKEY} paragraph of procedural copybook {@code CSSTRPFY}, whose single
 * {@code EVALUATE TRUE} has 28 ordered {@code WHEN} clauses and zero {@code WHEN OTHER}. When the
 * incoming identifier matches none of them, no assignment happens and the work-area field retains
 * its prior value. Adding a synthetic {@code UNKNOWN} constant would manufacture a state the legacy
 * system cannot produce, so the absence of one is asserted rather than assumed.</p>
 */
@DisplayName("KeyAction - the CCARD-AID attention-key identifier from CVCRD01Y")
class KeyActionSecurityTest {

    /** Declared width of {@code CCARD-AID}, from its {@code PIC X(5)} clause. */
    private static final int AID_WIDTH = 5;

    /** The twelve program-function constants, in declaration order. */
    private static final List<KeyAction> PROGRAM_FUNCTION_KEYS = List.of(
            KeyAction.PFK01, KeyAction.PFK02, KeyAction.PFK03, KeyAction.PFK04,
            KeyAction.PFK05, KeyAction.PFK06, KeyAction.PFK07, KeyAction.PFK08,
            KeyAction.PFK09, KeyAction.PFK10, KeyAction.PFK11, KeyAction.PFK12);

    /** The four constants that are not program-function keys, in declaration order. */
    private static final List<KeyAction> NON_FUNCTION_KEYS =
            List.of(KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2);

    @Nested
    @DisplayName("Vocabulary recovered from the sixteen level-88 condition names")
    class Vocabulary {

        @Test
        @DisplayName("exactly sixteen constants are declared, one per level-88 condition name in CVCRD01Y")
        void exactlySixteenConstantsAreDeclared() {
            assertThat(KeyAction.values()).hasSize(16);
        }

        @Test
        @DisplayName("the constants are declared in copybook order: ENTER, CLEAR, PA1, PA2, then PFK01 through PFK12")
        void constantsAreDeclaredInCopybookOrder() {
            assertThat(Arrays.stream(KeyAction.values()).map(Enum::name).toList())
                    .containsExactly("ENTER", "CLEAR", "PA1", "PA2",
                            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
                            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");
        }

        @Test
        @DisplayName("no synthetic catch-all constant exists, because CSSTRPFY has zero WHEN OTHER clauses and "
                + "retains the prior value on a miss")
        void noSyntheticCatchAllConstantExists() {
            assertThat(Arrays.stream(KeyAction.values()).map(Enum::name).toList())
                    .doesNotContain("UNKNOWN", "NONE", "OTHER", "INVALID", "UNMAPPED", "DEFAULT");
        }

        @Test
        @DisplayName("the four non-function constants and the twelve function constants together account for every "
                + "declared constant, with no overlap")
        void theTwoFamiliesPartitionTheVocabulary() {
            assertThat(NON_FUNCTION_KEYS).doesNotContainAnyElementsOf(PROGRAM_FUNCTION_KEYS);
            assertThat(NON_FUNCTION_KEYS.size() + PROGRAM_FUNCTION_KEYS.size())
                    .isEqualTo(KeyAction.values().length);
        }
    }

    @Nested
    @DisplayName("Five-character identifiers, padding included")
    class IdentifierWidth {

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every identifier is exactly five characters wide, matching PIC X(5)")
        void everyIdentifierIsExactlyFiveCharactersWide(final KeyAction action) {
            assertThat(action.getAid()).hasSize(AID_WIDTH);
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every identifier encodes to exactly five bytes, so it occupies the whole fixed-width field")
        void everyIdentifierEncodesToExactlyFiveBytes(final KeyAction action) {
            assertThat(action.getAid().getBytes(StandardCharsets.US_ASCII)).hasSize(AID_WIDTH);
        }

        @Test
        @DisplayName("the two program-attention identifiers carry their trailing space padding in the value itself")
        void programAttentionIdentifiersCarryTheirPadding() {
            assertThat(KeyAction.PA1.getAid()).isEqualTo("PA1  ");
            assertThat(KeyAction.PA2.getAid()).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("the ENTER and CLEAR identifiers fill the field naturally and carry no padding")
        void enterAndClearFillTheFieldNaturally() {
            assertThat(KeyAction.ENTER.getAid()).isEqualTo("ENTER");
            assertThat(KeyAction.CLEAR.getAid()).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("every program-function identifier is the literal PFK followed by a two-digit zero-padded "
                + "ordinal, so the single-digit ordinals are 01 through 09 and not 1 through 9")
        void programFunctionIdentifiersUseATwoDigitZeroPaddedOrdinal() {
            for (int ordinal = 1; ordinal <= PROGRAM_FUNCTION_KEYS.size(); ordinal++) {
                assertThat(PROGRAM_FUNCTION_KEYS.get(ordinal - 1).getAid())
                        .isEqualTo("PFK%02d".formatted(ordinal));
            }
        }

        @Test
        @DisplayName("all sixteen identifiers are distinct, so the reverse index cannot collide")
        void allSixteenIdentifiersAreDistinct() {
            assertThat(Arrays.stream(KeyAction.values()).map(KeyAction::getAid).distinct().count())
                    .isEqualTo(KeyAction.values().length);
        }
    }

    @Nested
    @DisplayName("Tolerant lookup from a raw work-area value")
    class IdentifierLookup {

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every identifier round-trips through the lookup back to the constant that carries it")
        void everyIdentifierRoundTrips(final KeyAction action) {
            assertThat(KeyAction.fromAid(action.getAid())).contains(action);
        }

        @Test
        @DisplayName("the two padded program-attention identifiers resolve only in their padded form; the trimmed "
                + "form resolves to nothing, which is the failure a trimming lookup would hide")
        void theTrimmedProgramAttentionFormDoesNotResolve() {
            assertThat(KeyAction.fromAid("PA1  ")).contains(KeyAction.PA1);
            assertThat(KeyAction.fromAid("PA2  ")).contains(KeyAction.PA2);
            assertThat(KeyAction.fromAid("PA1")).isEmpty();
            assertThat(KeyAction.fromAid("PA2")).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("an absent value yields an empty result rather than throwing, so an unset work area cannot "
                + "fail the very dispatch that reads it")
        void anAbsentValueYieldsAnEmptyResult(final String aid) {
            assertThat(KeyAction.fromAid(aid)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "     ", "PFK13", "PFK00", "pfk01", "enter", "ENTER ", " ENTER",
                "PFK1 ", "PF01 ", "CLEAR ", "PA3  ", "ZZZZZ", "0"})
        @DisplayName("a value outside the sixteen-identifier vocabulary yields an empty result, with no case "
                + "folding and no whitespace normalisation applied first")
        void valuesOutsideTheVocabularyYieldAnEmptyResult(final String aid) {
            assertThat(KeyAction.fromAid(aid)).isEmpty();
        }

        @Test
        @DisplayName("PFK13 through PFK24 do not resolve, because CSSTRPFY folds the upper twelve keys onto the "
                + "same twelve flags rather than defining twelve further identifiers")
        void theUpperTwelveKeysAreNotIdentifiersOfTheirOwn() {
            for (int ordinal = 13; ordinal <= 24; ordinal++) {
                assertThat(KeyAction.fromAid("PFK%02d".formatted(ordinal))).isEmpty();
            }
        }

        @Test
        @DisplayName("the lookup returns a present Optional rather than a nullable constant, so a caller cannot "
                + "dereference a miss")
        void theLookupReturnsAnOptional() {
            final Optional<KeyAction> hit = KeyAction.fromAid("PFK03");
            final Optional<KeyAction> miss = KeyAction.fromAid("PFK99");
            assertThat(hit).isPresent();
            assertThat(miss).isNotPresent();
        }
    }

    @Nested
    @DisplayName("Program-function predicate")
    class ProgramFunctionPredicate {

        @Test
        @DisplayName("all twelve program-function constants report themselves as program-function keys")
        void allTwelveFunctionConstantsReportTrue() {
            assertThat(PROGRAM_FUNCTION_KEYS).allMatch(KeyAction::isProgramFunctionKey);
        }

        @Test
        @DisplayName("the ENTER key, the CLEAR key and the two program-attention keys are not program-function keys")
        void theFourNonFunctionConstantsReportFalse() {
            assertThat(NON_FUNCTION_KEYS).noneMatch(KeyAction::isProgramFunctionKey);
        }

        @Test
        @DisplayName("the predicate partitions the vocabulary exactly twelve to four, so no constant is unclassified")
        void thePredicatePartitionsTheVocabulary() {
            final long functionKeys = Arrays.stream(KeyAction.values())
                    .filter(KeyAction::isProgramFunctionKey)
                    .count();
            assertThat(functionKeys).isEqualTo(12L);
            assertThat(KeyAction.values().length - functionKeys).isEqualTo(4L);
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("the predicate answers for every declared constant without throwing, so its exhaustive switch "
                + "genuinely covers the vocabulary")
        void thePredicateAnswersForEveryConstant(final KeyAction action) {
            assertThat(action.isProgramFunctionKey())
                    .isEqualTo(PROGRAM_FUNCTION_KEYS.contains(action));
        }
    }
}
