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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link PfKeyTranslator}, the single Java equivalent of the two paragraphs of
 * procedural copybook member {@code CSSTRPFY} - {@code YYYY-STORE-PFKEY} and its exit paragraph -
 * into which five legacy {@code COPY 'CSSTRPFY'} call sites collapse.
 *
 * <h2>The fold is the behaviour under test, and it is easy to get wrong in the plausible direction</h2>
 *
 * <p>The legacy {@code EVALUATE TRUE} has 28 ordered {@code WHEN} clauses, and the last twelve of them
 * are a <em>fold</em>: the upper program-function keys 13 through 24 set the <em>same twelve flags</em>
 * as keys 1 through 12. A translation that treated them as twelve further distinct actions would
 * compile, would look more complete, and would be wrong - keys 13 to 24 are not distinct actions in this
 * estate, and a screen that reacted differently to key 15 than to key 3 would be new behaviour. The
 * fold assertions below pin each of the twelve pairs individually rather than spot-checking one.</p>
 *
 * <h2>There is no fallback clause, and absence is not an error</h2>
 *
 * <p>The legacy construct has zero {@code WHEN OTHER} clauses, so an unrecognised identifier produces
 * <em>no assignment at all</em> and the work-area field retains the value it already held from the prior
 * interaction. That is modelled by an empty result, which the caller is free to ignore in order to keep
 * whatever action it was holding. An empty result is therefore distinct from a null argument, which can
 * only be a caller defect - and the two are asserted separately below, because collapsing them would
 * turn a programming error into a silently retained stale action.</p>
 */
@DisplayName("PfKeyTranslator - the CSSTRPFY attention-key translation")
class PfKeyTranslatorSecurityTest {

    /** The twelve base program-function identifiers, in clause order. */
    private static final List<String> BASE_FUNCTION_IDENTIFIERS = List.of(
            "DFHPF1", "DFHPF2", "DFHPF3", "DFHPF4", "DFHPF5", "DFHPF6",
            "DFHPF7", "DFHPF8", "DFHPF9", "DFHPF10", "DFHPF11", "DFHPF12");

    /** The twelve upper program-function identifiers that fold onto the base twelve, in clause order. */
    private static final List<String> FOLDED_FUNCTION_IDENTIFIERS = List.of(
            "DFHPF13", "DFHPF14", "DFHPF15", "DFHPF16", "DFHPF17", "DFHPF18",
            "DFHPF19", "DFHPF20", "DFHPF21", "DFHPF22", "DFHPF23", "DFHPF24");

    /** The twelve program-function actions, in declaration order. */
    private static final List<KeyAction> FUNCTION_ACTIONS = List.of(
            KeyAction.PFK01, KeyAction.PFK02, KeyAction.PFK03, KeyAction.PFK04,
            KeyAction.PFK05, KeyAction.PFK06, KeyAction.PFK07, KeyAction.PFK08,
            KeyAction.PFK09, KeyAction.PFK10, KeyAction.PFK11, KeyAction.PFK12);

    @Nested
    @DisplayName("The 28 clauses, translated in clause order")
    class ClauseCoverage {

        @ParameterizedTest
        @CsvSource({"DFHENTER,ENTER", "DFHCLEAR,CLEAR", "DFHPA1,PA1", "DFHPA2,PA2"})
        @DisplayName("the four non-function clauses translate to their actions")
        void theFourNonFunctionClausesTranslate(final String identifier, final KeyAction expected) {
            assertThat(PfKeyTranslator.translate(identifier)).contains(expected);
        }

        @Test
        @DisplayName("the twelve base program-function clauses translate to the twelve function actions, in order")
        void theTwelveBaseFunctionClausesTranslate() {
            for (int index = 0; index < BASE_FUNCTION_IDENTIFIERS.size(); index++) {
                assertThat(PfKeyTranslator.translate(BASE_FUNCTION_IDENTIFIERS.get(index)))
                        .contains(FUNCTION_ACTIONS.get(index));
            }
        }

        @Test
        @DisplayName("all 28 recognised identifiers translate to some action, so no clause is unreachable")
        void allTwentyEightClausesAreReachable() {
            assertThat(PfKeyTranslator.recognisedIdentifiers()).hasSize(28);
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .allSatisfy(identifier ->
                            assertThat(PfKeyTranslator.translate(identifier)).isPresent());
        }

        @Test
        @DisplayName("the recognised set contains the four non-function identifiers and all 24 function identifiers")
        void theRecognisedSetContainsEveryIdentifier() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .contains("DFHENTER", "DFHCLEAR", "DFHPA1", "DFHPA2")
                    .containsAll(BASE_FUNCTION_IDENTIFIERS)
                    .containsAll(FOLDED_FUNCTION_IDENTIFIERS);
        }

        @Test
        @DisplayName("the recognised set is unmodifiable, so a caller cannot extend the vocabulary at runtime")
        void theRecognisedSetIsUnmodifiable() {
            final Set<String> identifiers = PfKeyTranslator.recognisedIdentifiers();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> identifiers.add("DFHPF25"));
        }
    }

    @Nested
    @DisplayName("The fold: keys 13 through 24 are not distinct actions")
    class TheUpperKeyFold {

        @Test
        @DisplayName("each of the twelve upper identifiers translates to the SAME action as its base counterpart, "
                + "pair by pair, because the legacy clauses set the same twelve flags")
        void eachUpperIdentifierFoldsOntoItsBaseCounterpart() {
            for (int index = 0; index < FOLDED_FUNCTION_IDENTIFIERS.size(); index++) {
                final String base = BASE_FUNCTION_IDENTIFIERS.get(index);
                final String folded = FOLDED_FUNCTION_IDENTIFIERS.get(index);
                assertThat(PfKeyTranslator.translate(folded))
                        .as("%s must fold onto %s", folded, base)
                        .isEqualTo(PfKeyTranslator.translate(base))
                        .contains(FUNCTION_ACTIONS.get(index));
            }
        }

        @Test
        @DisplayName("the 24 function identifiers produce only twelve distinct actions, so the fold genuinely halves "
                + "the action space rather than merely aliasing one key")
        void theTwentyFourIdentifiersProduceOnlyTwelveActions() {
            assertThat(java.util.stream.Stream
                    .concat(BASE_FUNCTION_IDENTIFIERS.stream(), FOLDED_FUNCTION_IDENTIFIERS.stream())
                    .map(PfKeyTranslator::translate)
                    .distinct()
                    .count()).isEqualTo(12L);
        }

        @Test
        @DisplayName("no action exists that only an upper identifier can reach, so keys 13 to 24 add no behaviour")
        void noActionIsReachableOnlyFromAnUpperIdentifier() {
            final Set<KeyAction> fromBase = BASE_FUNCTION_IDENTIFIERS.stream()
                    .map(identifier -> PfKeyTranslator.translate(identifier).orElseThrow())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            final Set<KeyAction> fromUpper = FOLDED_FUNCTION_IDENTIFIERS.stream()
                    .map(identifier -> PfKeyTranslator.translate(identifier).orElseThrow())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            assertThat(fromUpper).isEqualTo(fromBase);
        }

        @Test
        @DisplayName("no identifier beyond 24 is recognised, so the fold does not silently continue past the legacy "
                + "clause table")
        void noIdentifierBeyondTwentyFourIsRecognised() {
            for (int ordinal = 25; ordinal <= 36; ordinal++) {
                assertThat(PfKeyTranslator.translate("DFHPF" + ordinal)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Absence of a fallback clause, distinguished from a caller defect")
    class NoFallbackClause {

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "DFHPF0", "DFHPF25", "DFHPF99", "dfhenter", "DFHENTER ",
                " DFHENTER", "DFHPA3", "ENTER", "PF3", "DFH", "DFHPF", "0"})
        @DisplayName("an unrecognised identifier yields an empty result rather than throwing, because the legacy "
                + "construct makes no assignment and the caller keeps the action it was holding")
        void anUnrecognisedIdentifierYieldsAnEmptyResult(final String identifier) {
            assertThat(PfKeyTranslator.translate(identifier)).isEmpty();
        }

        @Test
        @DisplayName("case folding is not applied, so a lower-case identifier is unrecognised rather than resolved")
        void caseFoldingIsNotApplied() {
            assertThat(PfKeyTranslator.translate("dfhpf3")).isEmpty();
            assertThat(PfKeyTranslator.translate("DFHPF3")).contains(KeyAction.PFK03);
        }

        @Test
        @DisplayName("whitespace normalisation is not applied, so a padded identifier is unrecognised")
        void whitespaceNormalisationIsNotApplied() {
            assertThat(PfKeyTranslator.translate("DFHPF3 ")).isEmpty();
            assertThat(PfKeyTranslator.translate(" DFHPF3")).isEmpty();
        }

        @Test
        @DisplayName("a null identifier is rejected outright rather than reported absent, because an unrecognised key "
                + "and an absent reference are different situations and collapsing them would let a caller defect "
                + "masquerade as a retained stale action")
        void aNullIdentifierIsRejectedRatherThanReportedAbsent() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.translate(null))
                    .withMessageContaining("attentionKeyIdentifier");
        }
    }

    @Nested
    @DisplayName("Forward accessor from an action to its five-byte work-area value")
    class ActionValueAccessor {

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every action has an action value, so the accessor is total over the declared vocabulary")
        void everyActionHasAnActionValue(final KeyAction action) {
            assertThat(PfKeyTranslator.actionValue(action)).isNotNull();
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every action value is exactly five bytes wide, matching the published width and the "
                + "PIC X(5) work-area field")
        void everyActionValueIsExactlyFiveBytesWide(final KeyAction action) {
            final String value = PfKeyTranslator.actionValue(action);
            assertThat(value).hasSize(PfKeyTranslator.ACTION_VALUE_BYTE_WIDTH);
            assertThat(value.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(PfKeyTranslator.ACTION_VALUE_BYTE_WIDTH);
        }

        @Test
        @DisplayName("the published width is five, matching the PIC X(5) clause on the work-area field")
        void thePublishedWidthIsFive() {
            assertThat(PfKeyTranslator.ACTION_VALUE_BYTE_WIDTH).isEqualTo(5);
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("the accessor agrees with the action's own identifier, so the translation point and the enum "
                + "cannot drift apart")
        void theAccessorAgreesWithTheActionsOwnIdentifier(final KeyAction action) {
            assertThat(PfKeyTranslator.actionValue(action)).isEqualTo(action.getAid());
        }

        @Test
        @DisplayName("the two program-attention values carry their trailing space padding, which is real data in a "
                + "fixed-width work area rather than incidental formatting")
        void theProgramAttentionValuesCarryTheirPadding() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA1)).isEqualTo("PA1  ");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA2)).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("a null action is rejected, because every declared action has a value so a null reference can "
                + "only be a caller defect")
        void aNullActionIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.actionValue(null))
                    .withMessageContaining("keyAction");
        }

        @Test
        @DisplayName("all sixteen action values are distinct, so the reverse index cannot collide")
        void allSixteenActionValuesAreDistinct() {
            assertThat(Arrays.stream(KeyAction.values())
                    .map(PfKeyTranslator::actionValue).distinct().count()).isEqualTo(16L);
        }
    }

    @Nested
    @DisplayName("Reverse accessor from a work-area value back to its action")
    class ActionValueLookup {

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every action value round-trips back to the action that produced it, so the two directions are "
                + "genuinely inverse")
        void everyActionValueRoundTrips(final KeyAction action) {
            assertThat(PfKeyTranslator.fromActionValue(PfKeyTranslator.actionValue(action)))
                    .contains(action);
        }

        @Test
        @DisplayName("the published action-value set holds all sixteen values and is the same set the reverse lookup "
                + "resolves")
        void thePublishedActionValueSetIsTheLookupDomain() {
            assertThat(PfKeyTranslator.actionValues()).hasSize(16);
            assertThat(PfKeyTranslator.actionValues())
                    .allSatisfy(value ->
                            assertThat(PfKeyTranslator.fromActionValue(value)).isPresent());
        }

        @Test
        @DisplayName("the published action-value set is unmodifiable")
        void thePublishedActionValueSetIsUnmodifiable() {
            final Set<String> values = PfKeyTranslator.actionValues();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.add("PFK13"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PA1", "PA2", "PFK1", "ENTER ", "CLEAR ", "PFK13", "PFK00",
                "enter", "pfk01", "     ", "", "ZZZZZ", "0"})
        @DisplayName("a value that is trimmed, re-cased, wrongly padded or outside the vocabulary yields an empty "
                + "result - notably the trimmed program-attention form, which a trimming lookup would resolve")
        void aValueOutsideTheVocabularyYieldsAnEmptyResult(final String actionValue) {
            assertThat(PfKeyTranslator.fromActionValue(actionValue)).isEmpty();
        }

        @Test
        @DisplayName("a value of the wrong byte width is reported absent before the index is consulted, so a longer "
                + "value cannot be truncated into a match")
        void aValueOfTheWrongByteWidthIsReportedAbsent() {
            assertThat(PfKeyTranslator.fromActionValue("PFK011")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PFK0")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PFK01")).contains(KeyAction.PFK01);
        }

        @Test
        @DisplayName("a null value is rejected outright rather than reported absent, matching the forward "
                + "translation's distinction between an unrecognised value and an absent reference")
        void aNullValueIsRejectedRatherThanReportedAbsent() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.fromActionValue(null))
                    .withMessageContaining("actionValue");
        }
    }

    @Nested
    @DisplayName("The whole translation chain, which is what the five legacy call sites actually do")
    class TheWholeChain {

        @Test
        @DisplayName("for every recognised identifier, translating then taking the action value then reversing "
                + "returns the same action, so the chain is stable end to end")
        void theChainIsStableEndToEnd() {
            for (final String identifier : PfKeyTranslator.recognisedIdentifiers()) {
                final KeyAction action = PfKeyTranslator.translate(identifier).orElseThrow();
                final String value = PfKeyTranslator.actionValue(action);
                assertThat(PfKeyTranslator.fromActionValue(value)).contains(action);
            }
        }

        @Test
        @DisplayName("28 recognised identifiers collapse to exactly 16 action values, which is the fold expressed as "
                + "a single fact: 28 clauses less the 12 folded duplicates")
        void twentyEightIdentifiersCollapseToSixteenValues() {
            assertThat(PfKeyTranslator.recognisedIdentifiers()).hasSize(28);
            assertThat(PfKeyTranslator.recognisedIdentifiers().stream()
                    .map(identifier -> PfKeyTranslator.translate(identifier).orElseThrow())
                    .map(PfKeyTranslator::actionValue)
                    .distinct()
                    .count()).isEqualTo(16L);
        }

        @Test
        @DisplayName("an upper identifier and its base counterpart yield the same action value, so a downstream "
                + "fixed-width work area cannot tell them apart - which is the legacy behaviour")
        void anUpperIdentifierYieldsTheSameWorkAreaValue() {
            for (int index = 0; index < FOLDED_FUNCTION_IDENTIFIERS.size(); index++) {
                final String baseValue = PfKeyTranslator.actionValue(
                        PfKeyTranslator.translate(BASE_FUNCTION_IDENTIFIERS.get(index)).orElseThrow());
                final String foldedValue = PfKeyTranslator.actionValue(
                        PfKeyTranslator.translate(FOLDED_FUNCTION_IDENTIFIERS.get(index)).orElseThrow());
                assertThat(foldedValue).isEqualTo(baseValue);
            }
        }
    }
}
