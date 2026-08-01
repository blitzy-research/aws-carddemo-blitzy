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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verifies {@code PfKeyTranslator}, the translation of the attention-key copybook
 * {@code app/cpy/CSSTRPFY.cpy} into a typed action.
 *
 * <p>The copybook is procedural, not a data structure. Its paragraph
 * {@code YYYY-STORE-PFKEY} is an {@code EVALUATE} over {@code EIBAID} with twenty-eight
 * {@code WHEN} clauses and no {@code WHEN OTHER}, and it is included with quoted syntax by five
 * online members: {@code COACTUPC}, {@code COACTVWC}, {@code COCRDLIC}, {@code COCRDSLC} and
 * {@code COCRDUPC}.
 *
 * <p><strong>The fold is the load-bearing fact.</strong> Clauses seventeen through twenty-eight
 * name {@code DFHPF13} through {@code DFHPF24}, and each one sets the same condition name that its
 * counterpart twelve clauses earlier sets. Keys thirteen to twenty-four are therefore not distinct
 * actions on this system; a translation that invented twelve further actions would manufacture
 * states the legacy cannot produce, and one that dropped the twelve clauses would leave those keys
 * inert when the legacy treats them as live.
 *
 * <p>Two further facts are contractual and are asserted separately:
 *
 * <ul>
 *   <li><strong>The absent clause.</strong> There is no {@code WHEN OTHER}, so an unrecognised key
 *       performs no assignment at all and the caller keeps whatever it was already holding. That is
 *       an empty result, never an exception and never a synthetic "unrecognised" action.</li>
 *   <li><strong>The padding is part of the value.</strong> The condition-name literals beneath
 *       {@code CCARD-AID PIC X(5)} in {@code app/cpy/CVCRD01Y.cpy} are five bytes wide, so the two
 *       program-attention mnemonics carry two trailing spaces. Trimming them would change the
 *       external form that responses echo.</li>
 * </ul>
 */
@DisplayName("PfKeyTranslator: the attention-key copybook, including its twelve-clause fold")
final class PfKeyTranslatorTest {

    // Oracles transcribed from the legacy copybooks, never read back from the class under test.

    /** {@code CCARD-AID PIC X(5)}, {@code [app/cpy/CVCRD01Y.cpy]}. */
    private static final int ORACLE_ACTION_VALUE_WIDTH = 5;

    /** Twenty-eight {@code WHEN} clauses, {@code [app/cpy/CSSTRPFY.cpy:L17-L80]}. */
    private static final int ORACLE_RECOGNISED_IDENTIFIER_COUNT = 28;

    /** Sixteen condition names beneath the action field. */
    private static final int ORACLE_DISTINCT_ACTION_COUNT = 16;

    /** The twelve program-function keys the fold collapses onto. */
    private static final int ORACLE_FUNCTION_KEY_COUNT = 12;

    /** The four non-function attention keys: ENTER, CLEAR and the two program-attention keys. */
    private static final int ORACLE_NON_FUNCTION_KEY_COUNT = 4;

    /** Clause one. */
    private static final String ORACLE_DFHENTER = "DFHENTER";

    /** Clause two. */
    private static final String ORACLE_DFHCLEAR = "DFHCLEAR";

    /** Clause three. */
    private static final String ORACLE_DFHPA1 = "DFHPA1";

    /** Clause four. */
    private static final String ORACLE_DFHPA2 = "DFHPA2";

    /**
     * The twenty-eight identifier tokens in the clause order of the legacy construct.
     *
     * <p>The single-digit tokens carry no leading zero and the double-digit tokens carry no
     * separator, exactly as the copybook spells them. A token spelled any other way is not one the
     * construct matches, which is asserted directly below.
     */
    private static final List<String> ORACLE_IDENTIFIERS_IN_CLAUSE_ORDER = List.of(
            ORACLE_DFHENTER, ORACLE_DFHCLEAR, ORACLE_DFHPA1, ORACLE_DFHPA2,
            "DFHPF1", "DFHPF2", "DFHPF3", "DFHPF4", "DFHPF5", "DFHPF6",
            "DFHPF7", "DFHPF8", "DFHPF9", "DFHPF10", "DFHPF11", "DFHPF12",
            "DFHPF13", "DFHPF14", "DFHPF15", "DFHPF16", "DFHPF17", "DFHPF18",
            "DFHPF19", "DFHPF20", "DFHPF21", "DFHPF22", "DFHPF23", "DFHPF24");

    /** The action the ENTER key sets, five characters with no padding needed. */
    private static final String ORACLE_ACTION_ENTER = "ENTER";

    /** The action the CLEAR key sets, five characters with no padding needed. */
    private static final String ORACLE_ACTION_CLEAR = "CLEAR";

    /** {@code PA1} padded to the five-byte field width with two trailing spaces. */
    private static final String ORACLE_ACTION_PA1 = "PA1  ";

    /** {@code PA2} padded to the five-byte field width with two trailing spaces. */
    private static final String ORACLE_ACTION_PA2 = "PA2  ";

    /** An identifier no clause names, so it must produce no action. */
    private static final String UNRECOGNISED_IDENTIFIER = "DFHPF25";

    /** A plausible misspelling: the copybook writes {@code DFHPF1}, never {@code DFHPF01}. */
    private static final String ZERO_PADDED_MISSPELLING = "DFHPF01";

    /**
     * Returns the action value the legacy declares for a program-function key ordinal.
     *
     * @param ordinal the key ordinal, one through twelve
     * @return the five-character condition-name literal, for example {@code PFK07}
     */
    private static String oracleFunctionKeyActionValue(final int ordinal) {
        return String.format("PFK%02d", ordinal);
    }

    /**
     * Measures a value the way a fixed-width field does, in encoded bytes rather than characters.
     *
     * @param value the value to measure
     * @return the encoded byte count
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("the twenty-eight recognised identifiers")
    final class RecognisedIdentifiers {

        @Test
        @DisplayName("exactly twenty-eight identifiers are recognised, one per clause")
        void exactlyTwentyEightIdentifiersAreRecognised() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .hasSize(ORACLE_RECOGNISED_IDENTIFIER_COUNT);
        }

        @Test
        @DisplayName("the identifiers are reported in the clause order of the legacy construct")
        void theIdentifiersAreReportedInClauseOrder() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .containsExactlyElementsOf(ORACLE_IDENTIFIERS_IN_CLAUSE_ORDER);
        }

        @Test
        @DisplayName("every recognised identifier resolves to an action")
        void everyRecognisedIdentifierResolves() {
            for (final String identifier : ORACLE_IDENTIFIERS_IN_CLAUSE_ORDER) {
                assertThat(PfKeyTranslator.translate(identifier))
                        .as("clause for %s", identifier)
                        .isPresent();
            }
        }

        @Test
        @DisplayName("the reported set is unmodifiable, so no caller can widen the recognised set")
        void theReportedSetIsUnmodifiable() {
            final Set<String> identifiers = PfKeyTranslator.recognisedIdentifiers();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> identifiers.add("DFHPF99"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> identifiers.remove(ORACLE_DFHENTER));
        }

        @Test
        @DisplayName("the same set instance is reported every time, so nothing is rebuilt per call")
        void theSameSetInstanceIsReportedEveryTime() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .isSameAs(PfKeyTranslator.recognisedIdentifiers());
        }
    }

    @Nested
    @DisplayName("translate: the four non-function keys and the twelve function keys")
    final class TranslateContract {

        @Test
        @DisplayName("the ENTER and CLEAR clauses set their own actions")
        void theEnterAndClearClausesSetTheirOwnActions() {
            assertThat(PfKeyTranslator.translate(ORACLE_DFHENTER)).contains(KeyAction.ENTER);
            assertThat(PfKeyTranslator.translate(ORACLE_DFHCLEAR)).contains(KeyAction.CLEAR);
        }

        @Test
        @DisplayName("the two program-attention clauses set the two program-attention actions")
        void theTwoProgramAttentionClausesSetTheirActions() {
            assertThat(PfKeyTranslator.translate(ORACLE_DFHPA1)).contains(KeyAction.PA1);
            assertThat(PfKeyTranslator.translate(ORACLE_DFHPA2)).contains(KeyAction.PA2);
        }

        @Test
        @DisplayName("each of the first twelve function keys sets its own numbered action")
        void eachOfTheFirstTwelveFunctionKeysSetsItsOwnAction() {
            for (int ordinal = 1; ordinal <= ORACLE_FUNCTION_KEY_COUNT; ordinal++) {
                final Optional<KeyAction> resolved = PfKeyTranslator.translate("DFHPF" + ordinal);

                assertThat(resolved).as("DFHPF%d", ordinal).isPresent();
                assertThat(PfKeyTranslator.actionValue(resolved.orElseThrow()))
                        .isEqualTo(oracleFunctionKeyActionValue(ordinal));
            }
        }

        @Test
        @DisplayName("keys thirteen to twenty-four fold onto keys one to twelve, clause for clause")
        void keysThirteenToTwentyFourFoldOntoKeysOneToTwelve() {
            for (int ordinal = 1; ordinal <= ORACLE_FUNCTION_KEY_COUNT; ordinal++) {
                final Optional<KeyAction> lower = PfKeyTranslator.translate("DFHPF" + ordinal);
                final Optional<KeyAction> folded =
                        PfKeyTranslator.translate("DFHPF" + (ordinal + ORACLE_FUNCTION_KEY_COUNT));

                assertThat(folded)
                        .as("DFHPF%d must set the same action as DFHPF%d",
                                ordinal + ORACLE_FUNCTION_KEY_COUNT, ordinal)
                        .isEqualTo(lower);
            }
        }

        @Test
        @DisplayName("the fold is complete: twenty-eight clauses produce only sixteen distinct actions")
        void theFoldProducesOnlySixteenDistinctActions() {
            final Set<KeyAction> produced = new LinkedHashSet<>();
            for (final String identifier : ORACLE_IDENTIFIERS_IN_CLAUSE_ORDER) {
                produced.add(PfKeyTranslator.translate(identifier).orElseThrow());
            }

            assertThat(produced).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
            assertThat(ORACLE_IDENTIFIERS_IN_CLAUSE_ORDER)
                    .hasSize(ORACLE_DISTINCT_ACTION_COUNT + ORACLE_FUNCTION_KEY_COUNT);
        }

        @Test
        @DisplayName("an unrecognised key produces no action, because the construct has no other clause")
        void anUnrecognisedKeyProducesNoAction() {
            assertThat(PfKeyTranslator.translate(UNRECOGNISED_IDENTIFIER)).isEmpty();
            assertThat(PfKeyTranslator.translate("")).isEmpty();
            assertThat(PfKeyTranslator.translate("   ")).isEmpty();
        }

        @Test
        @DisplayName("the token spelling is exact: a zero-padded ordinal matches no clause")
        void theTokenSpellingIsExact() {
            assertThat(PfKeyTranslator.translate(ZERO_PADDED_MISSPELLING))
                    .as("the copybook writes DFHPF1, so DFHPF01 is a different token")
                    .isEmpty();
            assertThat(PfKeyTranslator.translate("dfhenter"))
                    .as("no case folding is performed on either side of the comparison")
                    .isEmpty();
            assertThat(PfKeyTranslator.translate(" DFHENTER"))
                    .as("no white-space normalisation is performed")
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent reference is a caller defect, distinct from an unrecognised key")
        void anAbsentReferenceIsACallerDefect() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.translate(null))
                    .withMessageContaining("attentionKeyIdentifier");
        }
    }

    @Nested
    @DisplayName("actionValue: the five-byte external form, padding intact")
    final class ActionValueContract {

        @Test
        @DisplayName("every action has a value of exactly the field width in encoded bytes")
        void everyActionValueIsExactlyTheFieldWidth() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(encodedWidth(PfKeyTranslator.actionValue(action)))
                        .as("action value width for %s", action)
                        .isEqualTo(ORACLE_ACTION_VALUE_WIDTH);
            }
            assertThat(PfKeyTranslator.ACTION_VALUE_BYTE_WIDTH)
                    .isEqualTo(ORACLE_ACTION_VALUE_WIDTH);
        }

        @Test
        @DisplayName("the ENTER and CLEAR values need no padding and receive none")
        void theEnterAndClearValuesNeedNoPadding() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.ENTER)).isEqualTo(ORACLE_ACTION_ENTER);
            assertThat(PfKeyTranslator.actionValue(KeyAction.CLEAR)).isEqualTo(ORACLE_ACTION_CLEAR);
        }

        @Test
        @DisplayName("the two program-attention values carry their two trailing spaces")
        void theProgramAttentionValuesCarryTheirTrailingSpaces() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA1))
                    .isEqualTo(ORACLE_ACTION_PA1)
                    .endsWith("  ");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA2))
                    .isEqualTo(ORACLE_ACTION_PA2)
                    .endsWith("  ");
        }

        @Test
        @DisplayName("each function key value is its zero-padded ordinal under the PFK prefix")
        void eachFunctionKeyValueIsItsZeroPaddedOrdinal() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.PFK01)).isEqualTo("PFK01");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PFK09)).isEqualTo("PFK09");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PFK10)).isEqualTo("PFK10");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PFK12)).isEqualTo("PFK12");
        }

        @Test
        @DisplayName("the value matches the identifier the enumeration itself carries")
        void theValueMatchesTheEnumerationsOwnIdentifier() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(PfKeyTranslator.actionValue(action))
                        .as("single-sourced value for %s", action)
                        .isEqualTo(action.getAid());
            }
        }

        @Test
        @DisplayName("an absent action is a caller defect: every declared action has a value")
        void anAbsentActionIsACallerDefect() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.actionValue(null))
                    .withMessageContaining("keyAction");
        }
    }

    @Nested
    @DisplayName("fromActionValue: reading the work-area field back to an action")
    final class FromActionValueContract {

        @Test
        @DisplayName("every action round-trips through its own value")
        void everyActionRoundTrips() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(PfKeyTranslator.fromActionValue(PfKeyTranslator.actionValue(action)))
                        .as("round trip for %s", action)
                        .contains(action);
            }
        }

        @Test
        @DisplayName("matching is on the exact padded bytes, so a trimmed mnemonic does not resolve")
        void matchingIsOnTheExactPaddedBytes() {
            assertThat(PfKeyTranslator.fromActionValue("PA1"))
                    .as("three characters cannot fill a five-byte field")
                    .isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PA1 "))
                    .as("four characters cannot fill a five-byte field either")
                    .isEmpty();
            assertThat(PfKeyTranslator.fromActionValue(ORACLE_ACTION_PA1)).contains(KeyAction.PA1);
        }

        @Test
        @DisplayName("a value of the right width but no declared meaning yields no action")
        void anUndeclaredValueOfTheRightWidthYieldsNoAction() {
            assertThat(PfKeyTranslator.fromActionValue("PFK13")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("XXXXX")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("     ")).isEmpty();
        }

        @Test
        @DisplayName("a value of the wrong width is rejected before the lookup is attempted")
        void aValueOfTheWrongWidthIsRejectedBeforeLookup() {
            assertThat(PfKeyTranslator.fromActionValue("")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("ENTER ")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("ENTE")).isEmpty();
        }

        @Test
        @DisplayName("no case folding is performed on either side of the comparison")
        void noCaseFoldingIsPerformed() {
            assertThat(PfKeyTranslator.fromActionValue("enter")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("pfk01")).isEmpty();
        }

        @Test
        @DisplayName("an absent reference is a caller defect, distinct from an unrecognised value")
        void anAbsentReferenceIsACallerDefect() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.fromActionValue(null))
                    .withMessageContaining("actionValue");
        }
    }

    @Nested
    @DisplayName("actionValues: the sixteen declared values")
    final class ActionValuesContract {

        @Test
        @DisplayName("exactly sixteen values are reported, one per condition name")
        void exactlySixteenValuesAreReported() {
            assertThat(PfKeyTranslator.actionValues()).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
        }

        @Test
        @DisplayName("the values are reported in constant declaration order, padding intact")
        void theValuesAreReportedInDeclarationOrder() {
            final List<String> expected = new ArrayList<>();
            expected.add(ORACLE_ACTION_ENTER);
            expected.add(ORACLE_ACTION_CLEAR);
            expected.add(ORACLE_ACTION_PA1);
            expected.add(ORACLE_ACTION_PA2);
            for (int ordinal = 1; ordinal <= ORACLE_FUNCTION_KEY_COUNT; ordinal++) {
                expected.add(oracleFunctionKeyActionValue(ordinal));
            }

            assertThat(PfKeyTranslator.actionValues()).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("every reported value resolves back to an action")
        void everyReportedValueResolvesBack() {
            for (final String value : PfKeyTranslator.actionValues()) {
                assertThat(PfKeyTranslator.fromActionValue(value)).as("value %s", value).isPresent();
            }
        }

        @Test
        @DisplayName("the reported set is unmodifiable")
        void theReportedSetIsUnmodifiable() {
            final Set<String> values = PfKeyTranslator.actionValues();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.add("PFK13"));
        }
    }

    @Nested
    @DisplayName("the enumeration behind the translation")
    final class KeyActionContract {

        @Test
        @DisplayName("sixteen constants exist, twelve of them function keys and four not")
        void sixteenConstantsExistTwelveOfThemFunctionKeys() {
            assertThat(KeyAction.values()).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
            assertThat(List.of(KeyAction.values()).stream()
                    .filter(KeyAction::isProgramFunctionKey).count())
                    .isEqualTo(ORACLE_FUNCTION_KEY_COUNT);
            assertThat(List.of(KeyAction.values()).stream()
                    .filter(action -> !action.isProgramFunctionKey()).count())
                    .isEqualTo(ORACLE_NON_FUNCTION_KEY_COUNT);
        }

        @Test
        @DisplayName("ENTER, CLEAR and the two program-attention keys are not function keys")
        void theFourNonFunctionKeysAreNotFunctionKeys() {
            assertThat(KeyAction.ENTER.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.CLEAR.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA1.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA2.isProgramFunctionKey()).isFalse();
        }

        @Test
        @DisplayName("all twelve numbered keys are function keys")
        void allTwelveNumberedKeysAreFunctionKeys() {
            for (int ordinal = 1; ordinal <= ORACLE_FUNCTION_KEY_COUNT; ordinal++) {
                final KeyAction action =
                        KeyAction.valueOf(oracleFunctionKeyActionValue(ordinal));

                assertThat(action.isProgramFunctionKey()).as("%s", action).isTrue();
            }
        }

        @Test
        @DisplayName("the enumeration resolves its own identifier, tolerating an absent reference")
        void theEnumerationResolvesItsOwnIdentifier() {
            assertThat(KeyAction.fromAid(ORACLE_ACTION_PA1)).contains(KeyAction.PA1);
            assertThat(KeyAction.fromAid("PFK07")).contains(KeyAction.PFK07);
            assertThat(KeyAction.fromAid("PFK13")).isEmpty();
            assertThat(KeyAction.fromAid(null))
                    .as("the enumeration's own resolver tolerates an absent reference")
                    .isEmpty();
        }

        @Test
        @DisplayName("every identifier is exactly the field width, padding included")
        void everyIdentifierIsExactlyTheFieldWidth() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid()).as("%s", action).hasSize(ORACLE_ACTION_VALUE_WIDTH);
            }
        }

        @Test
        @DisplayName("the enumeration's declaration order matches the copybook's clause order")
        void theDeclarationOrderMatchesTheClauseOrder() {
            assertThat(KeyAction.values())
                    .startsWith(KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2)
                    .endsWith(KeyAction.PFK11, KeyAction.PFK12);
        }

        @Test
        @DisplayName("the class is a utility holder and needs no instance to translate a key")
        void theClassIsAUtilityHolder() {
            assertThatNoException().isThrownBy(() -> PfKeyTranslator.translate(ORACLE_DFHENTER));
        }
    }
}
