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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;

/**
 * Exercises the attention-key translation that five online transactions share.
 *
 * <h2>What is under test</h2>
 * {@link PfKeyTranslator} stands in for the procedural copybook {@code app/cpy/CSSTRPFY.cpy}, which
 * five online programs include with quoted syntax and perform as a paragraph range rather than
 * copying as data. The copybook inspects the CICS attention identifier and sets exactly one of
 * sixteen condition flags; this class returns exactly one of sixteen enum constants for the same
 * input, and reports the absence of a match as an empty result rather than by leaving every flag
 * clear.
 *
 * <h2>Why the fold from the upper twelve keys is asserted key by key</h2>
 * The legacy copybook tests twenty-eight distinct attention identifiers but sets only sixteen
 * distinct flags, because the twelve identifiers for program-function keys thirteen through
 * twenty-four each set the same flag as the key twelve positions below it. That is a deliberate
 * legacy behaviour and not an omission: on a 3270 the upper keys were reached by shifting the lower
 * ones, and the application treated them as the same request. Each of the twelve folds is therefore
 * asserted individually, because a translation that mapped the upper keys to twelve further actions
 * would compile, would look more complete, and would give a user twelve actions the legacy system
 * never offered.
 *
 * <h2>Why every action value is measured in encoded bytes</h2>
 * The action value stands in for a {@code PIC X(5)} field, so it is a five-byte reservation and not a
 * five-character string. Two of the sixteen values are shorter words padded to that width with
 * trailing spaces, and the padding is significant: a caller that stores the value into a fixed-width
 * record relies on it filling the field exactly. Width is therefore asserted on the encoded byte
 * count of every value, and the reverse lookup is asserted to refuse a value of any other width.
 *
 * <p>No legacy source text is reproduced.</p>
 */
@DisplayName("PfKeyTranslator - the CSSTRPFY attention-key store")
class PfKeyTranslatorBoundaryTest {

    /** Width of the legacy action field, in bytes. */
    private static final int ACTION_WIDTH = 5;

    /** Count of attention identifiers the legacy copybook tests. */
    private static final int IDENTIFIER_COUNT = 28;

    /** Count of distinct flags the legacy copybook can set. */
    private static final int ACTION_COUNT = 16;

    /** Number of positions separating a folded upper key from the lower key it stands for. */
    private static final int FOLD_DISTANCE = 12;

    /**
     * Returns the encoded byte width of a value, which is how a fixed-width field measures it.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies
     */
    private static int encodedWidth(String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("identifier translation")
    class IdentifierTranslation {

        @ParameterizedTest
        @CsvSource({
            "DFHENTER,ENTER",
            "DFHCLEAR,CLEAR",
            "DFHPA1,PA1",
            "DFHPA2,PA2"
        })
        @DisplayName("the four non-function keys each translate to their own action")
        void theFourNonFunctionKeysTranslate(String identifier, KeyAction expected) {
            assertThat(PfKeyTranslator.translate(identifier)).contains(expected);
        }

        @ParameterizedTest
        @CsvSource({
            "DFHPF1,PFK01", "DFHPF2,PFK02", "DFHPF3,PFK03", "DFHPF4,PFK04",
            "DFHPF5,PFK05", "DFHPF6,PFK06", "DFHPF7,PFK07", "DFHPF8,PFK08",
            "DFHPF9,PFK09", "DFHPF10,PFK10", "DFHPF11,PFK11", "DFHPF12,PFK12"
        })
        @DisplayName("the lower twelve function keys each translate to their own action")
        void theLowerTwelveFunctionKeysTranslate(String identifier, KeyAction expected) {
            assertThat(PfKeyTranslator.translate(identifier)).contains(expected);
        }

        @ParameterizedTest
        @CsvSource({
            "DFHPF13,PFK01", "DFHPF14,PFK02", "DFHPF15,PFK03", "DFHPF16,PFK04",
            "DFHPF17,PFK05", "DFHPF18,PFK06", "DFHPF19,PFK07", "DFHPF20,PFK08",
            "DFHPF21,PFK09", "DFHPF22,PFK10", "DFHPF23,PFK11", "DFHPF24,PFK12"
        })
        @DisplayName("the upper twelve function keys fold onto the lower twelve actions")
        void theUpperTwelveFunctionKeysFold(String identifier, KeyAction expected) {
            assertThat(PfKeyTranslator.translate(identifier)).contains(expected);
        }

        @ParameterizedTest
        @ValueSource(ints = {13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24})
        @DisplayName("a folded key resolves to the same action as the key twelve positions below")
        void aFoldedKeyResolvesToTheKeyTwelveBelow(int upperKeyNumber) {
            Optional<KeyAction> upper = PfKeyTranslator.translate("DFHPF" + upperKeyNumber);
            Optional<KeyAction> lower =
                    PfKeyTranslator.translate("DFHPF" + (upperKeyNumber - FOLD_DISTANCE));

            assertThat(upper).isPresent().isEqualTo(lower);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "DFHPF0", "DFHPF25", "DFHPF13A", "DFHPF", "DFHENTER ", "dfhenter",
            "DFHPA3", "DFHCLRP", "ENTER", "", " ", "DFHPF01"
        })
        @DisplayName("an identifier the legacy copybook does not test yields an empty result")
        void anUntestedIdentifierYieldsEmpty(String identifier) {
            assertThat(PfKeyTranslator.translate(identifier)).isEmpty();
        }

        @Test
        @DisplayName("an absent identifier is a caller defect, distinct from an unrecognised one")
        void anAbsentIdentifierIsACallerDefect() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.translate(null))
                    .withMessage("attentionKeyIdentifier must not be null: an unrecognised key"
                            + " yields an empty result, which is not the same as an absent"
                            + " reference");
        }

        @Test
        @DisplayName("every recognised identifier translates to a present action")
        void everyRecognisedIdentifierTranslates() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .allSatisfy(identifier ->
                            assertThat(PfKeyTranslator.translate(identifier)).isPresent());
        }

        @Test
        @DisplayName("the twenty-eight identifiers between them reach only sixteen actions")
        void theIdentifiersReachOnlySixteenActions() {
            Set<KeyAction> reached = PfKeyTranslator.recognisedIdentifiers().stream()
                    .map(PfKeyTranslator::translate)
                    .map(Optional::orElseThrow)
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(PfKeyTranslator.recognisedIdentifiers()).hasSize(IDENTIFIER_COUNT);
            assertThat(reached).hasSize(ACTION_COUNT);
        }

        @Test
        @DisplayName("every declared action is reachable from at least one identifier")
        void everyDeclaredActionIsReachable() {
            Set<KeyAction> reached = PfKeyTranslator.recognisedIdentifiers().stream()
                    .map(PfKeyTranslator::translate)
                    .map(Optional::orElseThrow)
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(reached).containsExactlyInAnyOrder(KeyAction.values());
        }
    }

    @Nested
    @DisplayName("action values - the PIC X(5) field the copybook stores")
    class ActionValues {

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every action value fills the five-byte legacy field exactly")
        void everyActionValueFillsTheField(KeyAction action) {
            assertThat(encodedWidth(PfKeyTranslator.actionValue(action)))
                    .isEqualTo(ACTION_WIDTH);
        }

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("the translator's action value is the action's own identifier text")
        void theActionValueIsTheActionsOwnText(KeyAction action) {
            assertThat(PfKeyTranslator.actionValue(action)).isEqualTo(action.getAid());
        }

        @Test
        @DisplayName("the two abbreviated values are space-padded, not shortened")
        void theTwoAbbreviatedValuesAreSpacePadded() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA1)).isEqualTo("PA1  ");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA2)).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("the two five-letter words need no padding")
        void theTwoFiveLetterWordsNeedNoPadding() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.ENTER)).isEqualTo("ENTER");
            assertThat(PfKeyTranslator.actionValue(KeyAction.CLEAR)).isEqualTo("CLEAR");
        }

        @ParameterizedTest
        @CsvSource({
            "PFK01,PFK01", "PFK02,PFK02", "PFK03,PFK03", "PFK04,PFK04",
            "PFK05,PFK05", "PFK06,PFK06", "PFK07,PFK07", "PFK08,PFK08",
            "PFK09,PFK09", "PFK10,PFK10", "PFK11,PFK11", "PFK12,PFK12"
        })
        @DisplayName("each function-key action carries its zero-padded ordinal")
        void eachFunctionKeyActionCarriesItsOrdinal(KeyAction action, String expected) {
            assertThat(PfKeyTranslator.actionValue(action)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an absent action is a caller defect, because every action has a value")
        void anAbsentActionIsACallerDefect() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.actionValue(null))
                    .withMessage("keyAction must not be null: every declared action has an action"
                            + " value, so a null reference can only be a caller defect");
        }

        @Test
        @DisplayName("no two actions share an action value")
        void noTwoActionsShareAValue() {
            assertThat(PfKeyTranslator.actionValues()).hasSize(KeyAction.values().length);
        }
    }

    @Nested
    @DisplayName("reverse lookup - reading a stored action value back")
    class ReverseLookup {

        @ParameterizedTest
        @EnumSource(KeyAction.class)
        @DisplayName("every action round-trips through its own action value")
        void everyActionRoundTrips(KeyAction action) {
            String stored = PfKeyTranslator.actionValue(action);

            assertThat(PfKeyTranslator.fromActionValue(stored)).contains(action);
        }

        @Test
        @DisplayName("the padded abbreviations resolve only at their padded width")
        void thePaddedAbbreviationsResolveOnlyWhenPadded() {
            assertThat(PfKeyTranslator.fromActionValue("PA1  ")).contains(KeyAction.PA1);
            assertThat(PfKeyTranslator.fromActionValue("PA1")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "PFK0", "PFK011", "ENTE", "ENTERS", "CLEAR ", " ENTER"})
        @DisplayName("a value of the wrong width cannot have come from the legacy field")
        void aValueOfTheWrongWidthYieldsEmpty(String malformed) {
            assertThat(PfKeyTranslator.fromActionValue(malformed)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"PFK00", "PFK13", "PFK99", "pfk01", "XXXXX", "     "})
        @DisplayName("a correctly sized value that names no action yields an empty result")
        void anUnknownValueOfTheRightWidthYieldsEmpty(String unknown) {
            assertThat(encodedWidth(unknown)).isEqualTo(ACTION_WIDTH);
            assertThat(PfKeyTranslator.fromActionValue(unknown)).isEmpty();
        }

        @Test
        @DisplayName("an absent value is a caller defect, distinct from an unrecognised one")
        void anAbsentValueIsACallerDefect() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.fromActionValue(null))
                    .withMessage("actionValue must not be null: an unrecognised value yields an"
                            + " empty result, which is not the same as an absent reference");
        }

        @Test
        @DisplayName("a value carrying a character outside the legacy character set names no action")
        void aValueOutsideTheLegacyCharacterSetNamesNoAction() {
            assertThat(PfKeyTranslator.fromActionValue("PFK0\u00e9")).isEmpty();
        }
    }

    @Nested
    @DisplayName("published vocabularies")
    class PublishedVocabularies {

        @Test
        @DisplayName("the identifier set carries exactly the twenty-eight tested identifiers")
        void theIdentifierSetCarriesTwentyEight() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .hasSize(IDENTIFIER_COUNT)
                    .containsExactly(
                            "DFHENTER", "DFHCLEAR", "DFHPA1", "DFHPA2",
                            "DFHPF1", "DFHPF2", "DFHPF3", "DFHPF4", "DFHPF5", "DFHPF6",
                            "DFHPF7", "DFHPF8", "DFHPF9", "DFHPF10", "DFHPF11", "DFHPF12",
                            "DFHPF13", "DFHPF14", "DFHPF15", "DFHPF16", "DFHPF17", "DFHPF18",
                            "DFHPF19", "DFHPF20", "DFHPF21", "DFHPF22", "DFHPF23", "DFHPF24");
        }

        @Test
        @DisplayName("the action-value set carries exactly the sixteen declared values, in order")
        void theActionValueSetCarriesSixteenInOrder() {
            assertThat(PfKeyTranslator.actionValues())
                    .hasSize(ACTION_COUNT)
                    .containsExactly(
                            "ENTER", "CLEAR", "PA1  ", "PA2  ",
                            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
                            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");
        }

        @Test
        @DisplayName("the identifier set cannot be modified by a caller")
        void theIdentifierSetCannotBeModified() {
            Set<String> identifiers = PfKeyTranslator.recognisedIdentifiers();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> identifiers.add("DFHPF25"));
        }

        @Test
        @DisplayName("the action-value set cannot be modified by a caller")
        void theActionValueSetCannotBeModified() {
            Set<String> values = PfKeyTranslator.actionValues();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.add("PFK13"));
        }

        @Test
        @DisplayName("the published width constant matches the width every value actually occupies")
        void thePublishedWidthMatchesReality() {
            assertThat(PfKeyTranslator.ACTION_VALUE_BYTE_WIDTH).isEqualTo(ACTION_WIDTH);
            assertThat(PfKeyTranslator.actionValues())
                    .allSatisfy(value ->
                            assertThat(encodedWidth(value)).isEqualTo(ACTION_WIDTH));
        }
    }
}
