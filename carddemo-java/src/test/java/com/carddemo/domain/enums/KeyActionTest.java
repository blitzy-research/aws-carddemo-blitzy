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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KeyAction}, the typed replacement for the terminal attention-identifier
 * vocabulary the legacy screen work area records a key press into.
 *
 * <p><strong>Where the sixteen constants come from.</strong> The screen work-area copybook
 * {@code app/cpy/CVCRD01Y.cpy} declares the attention-identifier field {@code CCARD-AID} as a
 * five-character item and declares sixteen condition names over that same field immediately beneath
 * it: one for the enter key, one for the clear key, two for the program-attention keys and twelve for
 * the program-function keys. Two plus two plus twelve is sixteen, and there is no seventeenth.
 * Citations here name the copybook member and the field or condition name rather than a line number,
 * because the copybook carries COBOL sequence numbers in its leftmost columns that are neither line
 * numbers nor even unique &mdash; one sequence value occurs on two separate records of this member.
 * Where a line is genuinely useful it is labelled a physical line: the field is declared on physical
 * line 3 and its sixteen condition names occupy physical lines 4 through 19.
 *
 * <p><strong>Why two of the sixteen identifiers carry trailing spaces.</strong> The field is five
 * characters wide, so a three-character program-attention name occupies three characters followed by
 * two spaces, and the copybook writes those two spaces into the condition value itself. The padding is
 * therefore data, not formatting. This suite asserts it, because an identifier recorded as three
 * characters would never equal the five-character image the screen layer actually stores and every
 * comparison against it would silently fail to match. See {@code docs/decision-log.md} for the
 * faithful-over-idiomatic decision that keeps the padding rather than normalising it away.
 *
 * <p><strong>Why there is deliberately no catch-all constant.</strong> The procedural copybook
 * {@code app/cpy/CSSTRPFY.cpy} holds exactly two paragraphs, and between them one dispatch construct
 * carrying twenty-eight clauses and <em>no</em> catch-all clause whatsoever. The behavioural
 * consequence is precise: when an incoming terminal identifier matches none of the twenty-eight
 * clauses, nothing is assigned, nothing is raised and the field simply keeps the value it already
 * held. A synthetic sentinel constant &mdash; of any name, whether unknown, none, other, invalid,
 * unmapped or default &mdash; would manufacture a state the legacy system cannot represent and would
 * invite callers to branch on something the legacy dispatch never branched on. Absence is therefore
 * modelled by the empty {@link Optional} that {@link KeyAction#fromAid(String)} returns, and by
 * nothing else. That is the second faithful-over-idiomatic decision recorded in
 * {@code docs/decision-log.md}.
 *
 * <p><strong>Why the upper twelve function keys have no constants.</strong> Within that same dispatch
 * construct, the clauses for function keys thirteen through twenty-four assign the very same twelve
 * flags as the clauses for keys one through twelve, so the upper twelve are aliases rather than
 * distinct actions. A search of the whole legacy tree finds no condition name in that range at all.
 * Performing the fold belongs to the utility-layer key translator, which this suite neither names,
 * imports nor invokes: the domain layer does not depend on the utility layer, and calling the
 * translator to produce an expected value would be using an implementation as its own oracle. This
 * suite asserts only the consequence &mdash; that the upper twelve identifiers resolve to nothing.
 * That is the third faithful-over-idiomatic decision recorded in {@code docs/decision-log.md}.
 *
 * <p><strong>Every expectation is hand-derived.</strong> No production method is asked to compute an
 * expected value and no output is snapshotted. Each of the sixteen identifiers below was read from the
 * copybook's condition values and typed out as a literal with its trailing spaces visible in the
 * source, and each census figure was counted in the copybook and typed out as a literal too. The
 * transcription of the sixteen data values is a deliberate, narrow exception to this module's
 * no-transcription standard; no declaration, clause or paragraph body is reproduced.
 *
 * <p><strong>Deliberately not asserted.</strong> This type models transient screen state and has no
 * persistence mapping at all &mdash; no table, no column, no converter &mdash; so nothing here asserts
 * mapping metadata, column naming, length or nullability. Nothing here maps a terminal identifier to
 * an action either, because that is the translator's contract and is covered by its own suite. The
 * remaining items of the same work-area group &mdash; the next-program, next-mapset and next-map
 * fields, the two message fields with their low-values condition name, and the three identifier fields
 * with their numeric redefinitions &mdash; belong to the screen work-area transfer object rather than
 * here, and the eight commented-out declarations in the same copybook are inactive text that describes
 * no behaviour. None of them is modelled, named or asserted.
 */
@DisplayName("KeyAction :: the sixteen attention identifiers of the five-character work-area field")
class KeyActionTest {

    /**
     * Width of {@code CCARD-AID}, declared as a five-character item.
     *
     * <p>Every width assertion below measures encoded bytes rather than characters, because the field
     * is a fixed-width byte field in the legacy record image: the two counts coincide for these
     * particular values, which is precisely why counting characters would conceal a value that had
     * acquired a character costing more than one byte.
     */
    private static final int ACTION_FIELD_BYTE_WIDTH = 5;

    private static final int CONDITION_NAME_COUNT = 16;

    private static final int DISPATCH_CLAUSE_COUNT = 28;

    /** Catch-all clauses in that construct. There are none, which is why no sentinel constant exists. */
    private static final int DISPATCH_CATCH_ALL_CLAUSE_COUNT = 0;

    /** Paragraphs in {@code app/cpy/CSSTRPFY.cpy}: the storing paragraph and its exit paragraph. */
    private static final int DISPATCH_PARAGRAPH_COUNT = 2;

    private static final int NON_FUNCTION_KEY_CLAUSES = 4;

    private static final int LOWER_FUNCTION_KEY_CLAUSES = 12;

    /** Dispatch clauses for function keys thirteen through twenty-four, which fold onto the lower twelve. */
    private static final int FOLDED_FUNCTION_KEY_CLAUSES = 12;

    private static final int PROGRAM_ATTENTION_TRAILING_SPACES = 2;

    private static final String PROGRAM_ATTENTION_1_IDENTIFIER = "PA1  ";

    private static final String PROGRAM_ATTENTION_2_IDENTIFIER = "PA2  ";

    /** The three-character form of program-attention key one, which the five-character field never holds. */
    private static final String PROGRAM_ATTENTION_1_UNPADDED = "PA1";

    /** The three-character form of program-attention key two, which the five-character field never holds. */
    private static final String PROGRAM_ATTENTION_2_UNPADDED = "PA2";

    /**
     * The sixteen identifiers transcribed from the condition values declared over {@code CCARD-AID},
     * keyed by condition-name suffix and held in copybook declaration order.
     */
    private static final Map<String, String> TRANSCRIBED_IDENTIFIERS = transcribedIdentifiers();

    private static final List<String> TRANSCRIBED_NAMES = List.of(
            "ENTER", "CLEAR", "PA1", "PA2",
            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");

    private static final List<String> FUNCTION_KEY_IDENTIFIERS = List.of(
            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");

    /**
     * The single-digit renderings of function keys one through nine.
     *
     * <p>The copybook zero-fills the suffix, so none of these nine is a declared identifier. They are
     * probed rather than assumed absent, because a renderer that dropped the zero fill would produce
     * exactly these and would then match nothing the screen layer stores.
     */
    private static final List<String> SINGLE_DIGIT_RENDERINGS = List.of(
            "PFK1", "PFK2", "PFK3", "PFK4", "PFK5", "PFK6", "PFK7", "PFK8", "PFK9");

    /**
     * The twelve identifiers a caller would expect for function keys thirteen through twenty-four.
     *
     * <p>The dispatch construct folds those keys onto the lower twelve flags, so the legacy estate
     * declares no condition name in this range and none of these twelve is a valid identifier.
     */
    private static final List<String> FOLDED_UPPER_FUNCTION_KEY_RENDERINGS = List.of(
            "PFK13", "PFK14", "PFK15", "PFK16", "PFK17", "PFK18",
            "PFK19", "PFK20", "PFK21", "PFK22", "PFK23", "PFK24");

    /**
     * Transcribes the sixteen condition values declared over {@code CCARD-AID}, in declaration order.
     *
     * <p>An insertion-ordered map is wrapped rather than copied into a hash-ordered immutable map,
     * because the copybook's declaration order is itself asserted below and a hash-ordered copy would
     * discard it.
     *
     * @return an ordered, unmodifiable view of the sixteen transcribed identifiers
     */
    private static Map<String, String> transcribedIdentifiers() {
        final Map<String, String> identifiers = new LinkedHashMap<>();
        identifiers.put("ENTER", "ENTER");
        identifiers.put("CLEAR", "CLEAR");
        identifiers.put("PA1", PROGRAM_ATTENTION_1_IDENTIFIER);
        identifiers.put("PA2", PROGRAM_ATTENTION_2_IDENTIFIER);
        identifiers.put("PFK01", "PFK01");
        identifiers.put("PFK02", "PFK02");
        identifiers.put("PFK03", "PFK03");
        identifiers.put("PFK04", "PFK04");
        identifiers.put("PFK05", "PFK05");
        identifiers.put("PFK06", "PFK06");
        identifiers.put("PFK07", "PFK07");
        identifiers.put("PFK08", "PFK08");
        identifiers.put("PFK09", "PFK09");
        identifiers.put("PFK10", "PFK10");
        identifiers.put("PFK11", "PFK11");
        identifiers.put("PFK12", "PFK12");
        return Collections.unmodifiableMap(identifiers);
    }

    /**
     * Counts the trailing spaces of an identifier in the encoded byte domain the legacy field occupies.
     *
     * <p>The scan walks the encoded bytes backwards from the last one. It deliberately performs no
     * white-space normalisation of any kind: normalising would make the padded and unpadded forms of a
     * program-attention identifier interchangeable, which a fixed-width field does not permit and which
     * is the very confusion these tests exist to detect.
     *
     * @param identifier the identifier exactly as declared
     * @return the number of consecutive space bytes at the end of the encoded identifier
     */
    private static int trailingSpaceCount(String identifier) {
        final byte[] encoded = identifier.getBytes(StandardCharsets.US_ASCII);
        int count = 0;
        for (int index = encoded.length - 1; index >= 0 && encoded[index] == (byte) ' '; index--) {
            count++;
        }
        return count;
    }

    /**
     * Verifies the sixteen constants against the condition names declared over {@code CCARD-AID}.
     */
    @Nested
    @DisplayName("the constant vocabulary")
    class ConstantVocabulary {

        @Test
        @DisplayName("all sixteen condition names declared over CCARD-AID have a constant carrying that "
                + "exact identifier")
        void allSixteenIdentifiersMatchTheirTranscribedConditionValue() {
            assertThat(KeyAction.ENTER.getAid()).isEqualTo("ENTER");
            assertThat(KeyAction.CLEAR.getAid()).isEqualTo("CLEAR");
            assertThat(KeyAction.PA1.getAid()).isEqualTo(PROGRAM_ATTENTION_1_IDENTIFIER);
            assertThat(KeyAction.PA2.getAid()).isEqualTo(PROGRAM_ATTENTION_2_IDENTIFIER);
            assertThat(KeyAction.PFK01.getAid()).isEqualTo("PFK01");
            assertThat(KeyAction.PFK02.getAid()).isEqualTo("PFK02");
            assertThat(KeyAction.PFK03.getAid()).isEqualTo("PFK03");
            assertThat(KeyAction.PFK04.getAid()).isEqualTo("PFK04");
            assertThat(KeyAction.PFK05.getAid()).isEqualTo("PFK05");
            assertThat(KeyAction.PFK06.getAid()).isEqualTo("PFK06");
            assertThat(KeyAction.PFK07.getAid()).isEqualTo("PFK07");
            assertThat(KeyAction.PFK08.getAid()).isEqualTo("PFK08");
            assertThat(KeyAction.PFK09.getAid()).isEqualTo("PFK09");
            assertThat(KeyAction.PFK10.getAid()).isEqualTo("PFK10");
            assertThat(KeyAction.PFK11.getAid()).isEqualTo("PFK11");
            assertThat(KeyAction.PFK12.getAid()).isEqualTo("PFK12");
        }

        @Test
        @DisplayName("the vocabulary is closed at exactly sixteen, the number of condition names the "
                + "copybook declares over the action field")
        void theVocabularyIsClosedAtExactlySixteen() {
            assertThat(KeyAction.values()).hasSize(CONDITION_NAME_COUNT);
            assertThat(TRANSCRIBED_IDENTIFIERS).hasSize(CONDITION_NAME_COUNT);
            assertThat(TRANSCRIBED_NAMES).hasSize(CONDITION_NAME_COUNT);
        }

        @Test
        @DisplayName("the constant names are exactly the sixteen condition-name suffixes, so no "
                + "seventeenth constant of any kind has been introduced")
        void theConstantNamesAreExactlyTheSixteenConditionNameSuffixes() {
            final List<String> declaredNames = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                declaredNames.add(action.name());
            }

            assertThat(declaredNames).containsExactlyElementsOf(TRANSCRIBED_NAMES);
        }

        @Test
        @DisplayName("the constants are declared in copybook order: enter, clear, the two "
                + "program-attention keys, then the twelve function keys ascending")
        void theConstantsAreDeclaredInCopybookOrder() {
            assertThat(KeyAction.values()).containsExactly(
                    KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2,
                    KeyAction.PFK01, KeyAction.PFK02, KeyAction.PFK03, KeyAction.PFK04,
                    KeyAction.PFK05, KeyAction.PFK06, KeyAction.PFK07, KeyAction.PFK08,
                    KeyAction.PFK09, KeyAction.PFK10, KeyAction.PFK11, KeyAction.PFK12);
        }

        @Test
        @DisplayName("the enter and clear identifiers fill the five-character field on their own and "
                + "need no padding, unlike the two program-attention identifiers")
        void theEnterAndClearIdentifiersNeedNoPadding() {
            assertThat(trailingSpaceCount("ENTER")).isZero();
            assertThat(trailingSpaceCount("CLEAR")).isZero();
            assertThat(trailingSpaceCount(KeyAction.ENTER.getAid())).isZero();
            assertThat(trailingSpaceCount(KeyAction.CLEAR.getAid())).isZero();
            assertThat(KeyAction.ENTER.getAid()).doesNotContain(" ");
            assertThat(KeyAction.CLEAR.getAid()).doesNotContain(" ");
        }

        @Test
        @DisplayName("the sixteen identifiers are distinct, so a recorded key press resolves to one "
                + "action and never to two")
        void theSixteenIdentifiersAreDistinct() {
            final List<String> identifiers = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                identifiers.add(action.getAid());
            }

            assertThat(identifiers).doesNotHaveDuplicates().hasSize(CONDITION_NAME_COUNT);
        }
    }

    /**
     * Verifies that every identifier fills the fixed-width field the copybook declares.
     */
    @Nested
    @DisplayName("the five-character action field")
    class ActionFieldWidth {

        @Test
        @DisplayName("every one of the sixteen identifiers encodes to exactly five bytes, matching the "
                + "five-character picture of CCARD-AID")
        void everyIdentifierEncodesToExactlyFiveBytes() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of %s", action.name())
                        .hasSize(ACTION_FIELD_BYTE_WIDTH);
            }
        }

        @Test
        @DisplayName("every transcribed condition value also encodes to exactly five bytes, confirming "
                + "the copybook's own picture rather than the type's")
        void everyTranscribedIdentifierEncodesToExactlyFiveBytes() {
            for (final Map.Entry<String, String> entry : TRANSCRIBED_IDENTIFIERS.entrySet()) {
                assertThat(entry.getValue().getBytes(StandardCharsets.US_ASCII))
                        .as("encoded width of the value transcribed for %s", entry.getKey())
                        .hasSize(ACTION_FIELD_BYTE_WIDTH);
            }
        }

        @Test
        @DisplayName("no identifier is padded on the left, so a recorded key press compares from the "
                + "first byte of the field")
        void noIdentifierIsPaddedOnTheLeft() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(action.getAid())
                        .as("identifier of %s", action.name())
                        .doesNotStartWith(" ");
            }
        }
    }

    /**
     * Verifies the padding the copybook writes into the two program-attention condition values.
     */
    @Nested
    @DisplayName("the two program-attention identifiers")
    class ProgramAttentionPadding {

        @Test
        @DisplayName("program-attention key one is three characters plus two trailing spaces in a "
                + "five-byte field, never the bare three characters")
        void programAttentionKeyOneIsThreeCharactersPlusTwoSpaces() {
            final String identifier = KeyAction.PA1.getAid();

            assertThat(identifier).isEqualTo(PROGRAM_ATTENTION_1_IDENTIFIER);
            assertThat(identifier).isNotEqualTo(PROGRAM_ATTENTION_1_UNPADDED);
            assertThat(trailingSpaceCount(identifier)).isEqualTo(PROGRAM_ATTENTION_TRAILING_SPACES);
            assertThat(identifier.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ACTION_FIELD_BYTE_WIDTH)
                    .isEqualTo(PROGRAM_ATTENTION_1_IDENTIFIER.getBytes(StandardCharsets.US_ASCII))
                    .isNotEqualTo(PROGRAM_ATTENTION_1_UNPADDED.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("program-attention key two is three characters plus two trailing spaces in a "
                + "five-byte field, never the bare three characters")
        void programAttentionKeyTwoIsThreeCharactersPlusTwoSpaces() {
            final String identifier = KeyAction.PA2.getAid();

            assertThat(identifier).isEqualTo(PROGRAM_ATTENTION_2_IDENTIFIER);
            assertThat(identifier).isNotEqualTo(PROGRAM_ATTENTION_2_UNPADDED);
            assertThat(trailingSpaceCount(identifier)).isEqualTo(PROGRAM_ATTENTION_TRAILING_SPACES);
            assertThat(identifier.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ACTION_FIELD_BYTE_WIDTH)
                    .isEqualTo(PROGRAM_ATTENTION_2_IDENTIFIER.getBytes(StandardCharsets.US_ASCII))
                    .isNotEqualTo(PROGRAM_ATTENTION_2_UNPADDED.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("each padded program-attention identifier round-trips through the lookup byte for "
                + "byte, so the padding survives resolution intact")
        void eachPaddedProgramAttentionIdentifierRoundTripsByteForByte() {
            final Optional<KeyAction> resolvedOne = KeyAction.fromAid(PROGRAM_ATTENTION_1_IDENTIFIER);
            final Optional<KeyAction> resolvedTwo = KeyAction.fromAid(PROGRAM_ATTENTION_2_IDENTIFIER);

            assertThat(resolvedOne).contains(KeyAction.PA1);
            assertThat(resolvedTwo).contains(KeyAction.PA2);
            assertThat(resolvedOne.orElseThrow().getAid().getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(PROGRAM_ATTENTION_1_IDENTIFIER.getBytes(StandardCharsets.US_ASCII));
            assertThat(resolvedTwo.orElseThrow().getAid().getBytes(StandardCharsets.US_ASCII))
                    .isEqualTo(PROGRAM_ATTENTION_2_IDENTIFIER.getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the bare three-character program-attention forms are not valid identifiers, "
                + "because the field is five characters wide and blank-filled")
        void theBareThreeCharacterFormsAreNotValidIdentifiers() {
            assertThat(KeyAction.fromAid(PROGRAM_ATTENTION_1_UNPADDED)).isEmpty();
            assertThat(KeyAction.fromAid(PROGRAM_ATTENTION_2_UNPADDED)).isEmpty();
        }

        @Test
        @DisplayName("only the two program-attention identifiers carry trailing spaces; the other "
                + "fourteen carry none")
        void onlyTheTwoProgramAttentionIdentifiersCarryTrailingSpaces() {
            int padded = 0;
            for (final KeyAction action : KeyAction.values()) {
                if (trailingSpaceCount(action.getAid()) > 0) {
                    padded++;
                    assertThat(trailingSpaceCount(action.getAid()))
                            .as("trailing spaces of %s", action.name())
                            .isEqualTo(PROGRAM_ATTENTION_TRAILING_SPACES);
                }
            }

            assertThat(padded).isEqualTo(2);
        }
    }

    /**
     * Verifies the zero-filled two-digit suffix the copybook uses for the twelve function keys.
     */
    @Nested
    @DisplayName("the twelve function-key identifiers")
    class FunctionKeyIdentifiers {

        @Test
        @DisplayName("each function-key suffix is zero-filled to two digits, so function key nine is "
                + "PFK09 and never PFK9")
        void eachFunctionKeySuffixIsZeroFilledToTwoDigits() {
            assertThat(KeyAction.PFK01.getAid()).isEqualTo("PFK01");
            assertThat(KeyAction.PFK02.getAid()).isEqualTo("PFK02");
            assertThat(KeyAction.PFK03.getAid()).isEqualTo("PFK03");
            assertThat(KeyAction.PFK04.getAid()).isEqualTo("PFK04");
            assertThat(KeyAction.PFK05.getAid()).isEqualTo("PFK05");
            assertThat(KeyAction.PFK06.getAid()).isEqualTo("PFK06");
            assertThat(KeyAction.PFK07.getAid()).isEqualTo("PFK07");
            assertThat(KeyAction.PFK08.getAid()).isEqualTo("PFK08");
            assertThat(KeyAction.PFK09.getAid()).isEqualTo("PFK09");
            assertThat(KeyAction.PFK10.getAid()).isEqualTo("PFK10");
            assertThat(KeyAction.PFK11.getAid()).isEqualTo("PFK11");
            assertThat(KeyAction.PFK12.getAid()).isEqualTo("PFK12");
            assertThat(FUNCTION_KEY_IDENTIFIERS).hasSize(LOWER_FUNCTION_KEY_CLAUSES);
        }

        @Test
        @DisplayName("the single-digit renderings PFK1 through PFK9 are not the declared identifiers, "
                + "because the copybook zero-fills the suffix")
        void theSingleDigitRenderingsAreNotTheDeclaredIdentifiers() {
            final List<String> declared = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                declared.add(action.getAid());
            }

            assertThat(declared).doesNotContainAnyElementsOf(SINGLE_DIGIT_RENDERINGS);
            assertThat(SINGLE_DIGIT_RENDERINGS).hasSize(9);
        }

        @Test
        @DisplayName("the single-digit renderings PFK1 through PFK9 are not valid identifiers either, "
                + "so a dropped zero fill resolves to nothing rather than to the wrong key")
        void theSingleDigitRenderingsAreNotValidIdentifiers() {
            for (final String rendering : SINGLE_DIGIT_RENDERINGS) {
                assertThat(KeyAction.fromAid(rendering))
                        .as("lookup of the single-digit rendering %s", rendering)
                        .isEmpty();
            }
        }
    }

    /**
     * Verifies the partition between the four non-function identifiers and the twelve function keys.
     */
    @Nested
    @DisplayName("the function-key predicate")
    class FunctionKeyPredicate {

        @Test
        @DisplayName("the twelve function-key constants report themselves function keys, matching the "
                + "twelve function-key clauses of the dispatch construct")
        void theTwelveFunctionKeysReportThemselvesFunctionKeys() {
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
        @DisplayName("the enter, clear and two program-attention constants do not report themselves "
                + "function keys, matching the four non-function clauses")
        void theFourNonFunctionConstantsDoNotReportThemselvesFunctionKeys() {
            assertThat(KeyAction.ENTER.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.CLEAR.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA1.isProgramFunctionKey()).isFalse();
            assertThat(KeyAction.PA2.isProgramFunctionKey()).isFalse();
        }

        @Test
        @DisplayName("the predicate partitions the vocabulary twelve to four with nothing left over, "
                + "which is the four-plus-twelve split the copybook declares")
        void thePredicatePartitionsTheVocabularyTwelveToFour() {
            int functionKeys = 0;
            int others = 0;
            for (final KeyAction action : KeyAction.values()) {
                if (action.isProgramFunctionKey()) {
                    functionKeys++;
                } else {
                    others++;
                }
            }

            assertThat(functionKeys).isEqualTo(LOWER_FUNCTION_KEY_CLAUSES);
            assertThat(others).isEqualTo(NON_FUNCTION_KEY_CLAUSES);
            assertThat(functionKeys + others).isEqualTo(CONDITION_NAME_COUNT);
        }
    }

    @Nested
    @DisplayName("lookup from a recorded identifier")
    class Lookup {

        @Test
        @DisplayName("all sixteen transcribed condition values resolve to the constant bearing that "
                + "condition name")
        void allSixteenTranscribedValuesResolve() {
            for (final Map.Entry<String, String> entry : TRANSCRIBED_IDENTIFIERS.entrySet()) {
                assertThat(KeyAction.fromAid(entry.getValue()))
                        .as("lookup of the value transcribed for %s", entry.getKey())
                        .isPresent()
                        .get()
                        .extracting(KeyAction::name)
                        .isEqualTo(entry.getKey());
            }
        }

        @Test
        @DisplayName("every constant round-trips through its own identifier, so recording and resolving "
                + "a key press is lossless")
        void everyConstantRoundTripsThroughItsOwnIdentifier() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(KeyAction.fromAid(action.getAid()))
                        .as("round trip of %s", action.name())
                        .contains(action);
            }
        }

        @Test
        @DisplayName("an unmapped identifier resolves to nothing without throwing, because the dispatch "
                + "construct has no catch-all clause and so stores no sentinel")
        void anUnmappedIdentifierResolvesToNothingWithoutThrowing() {
            // The dispatch construct in app/cpy/CSSTRPFY.cpy carries twenty-eight clauses and no
            // catch-all clause at all, so an unrecognised terminal identifier causes no assignment and
            // the work-area field keeps whatever it already held. There is consequently no sentinel
            // constant to fall back on, and absence is modelled by an empty result. Each probe below is
            // a genuinely undeclared value.
            assertThatCode(() -> {
                assertThat(KeyAction.fromAid("PFK13")).isEmpty();
                assertThat(KeyAction.fromAid("PFK24")).isEmpty();
                assertThat(KeyAction.fromAid("XXXXX")).isEmpty();
                assertThat(KeyAction.fromAid("     ")).isEmpty();
                assertThat(KeyAction.fromAid("")).isEmpty();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an absent identifier resolves to nothing rather than throwing, so an unpopulated "
                + "work-area field is not mistaken for a key press")
        void anAbsentIdentifierResolvesToNothingRatherThanThrowing() {
            assertThatCode(() -> assertThat(KeyAction.fromAid(null)).isNotNull().isEmpty())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("no white-space normalisation is applied, so an identifier of the wrong width does "
                + "not resolve whether it is short or long")
        void noWhiteSpaceNormalisationIsApplied() {
            assertThat(KeyAction.fromAid("ENTE")).isEmpty();
            assertThat(KeyAction.fromAid("ENTER ")).isEmpty();
            assertThat(KeyAction.fromAid("PA1 ")).isEmpty();
            assertThat(KeyAction.fromAid("PA1   ")).isEmpty();
            assertThat(KeyAction.fromAid(" PA1 ")).isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied, so a lower-cased identifier does not resolve")
        void noCaseFoldingIsApplied() {
            assertThat(KeyAction.fromAid("enter")).isEmpty();
            assertThat(KeyAction.fromAid("clear")).isEmpty();
            assertThat(KeyAction.fromAid("pa1  ")).isEmpty();
            assertThat(KeyAction.fromAid("pfk03")).isEmpty();
            assertThat(KeyAction.fromAid("Pfk03")).isEmpty();
        }

        @Test
        @DisplayName("the lookup admits the whole vocabulary and nothing beyond it, so exactly sixteen "
                + "identifiers resolve")
        void theLookupAdmitsTheWholeVocabularyAndNothingBeyondIt() {
            int resolved = 0;
            for (final KeyAction action : KeyAction.values()) {
                if (KeyAction.fromAid(action.getAid()).isPresent()) {
                    resolved++;
                }
            }

            assertThat(resolved).isEqualTo(CONDITION_NAME_COUNT);
            assertThat(KeyAction.fromAid("PFK00")).isEmpty();
            assertThat(KeyAction.fromAid("PA3  ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the fold of function keys thirteen through twenty-four")
    class FoldedUpperFunctionKeys {

        @Test
        @DisplayName("function keys thirteen through twenty-four have no identifier of their own, "
                + "because the dispatch construct folds them onto keys one through twelve")
        void theUpperTwelveFunctionKeysHaveNoIdentifierOfTheirOwn() {
            // In app/cpy/CSSTRPFY.cpy the clauses for terminal function keys thirteen through
            // twenty-four assign the same twelve flags as the clauses for keys one through twelve, so
            // the upper twelve are aliases rather than distinct actions. A search of the whole legacy
            // tree for a condition name in that range returns no hits. Performing the fold is the
            // utility-layer key translator's responsibility and is verified in its own suite; this
            // enumeration only declares constants, and it declares none for the upper twelve. The
            // translator is deliberately neither named, imported nor invoked here.
            for (final String rendering : FOLDED_UPPER_FUNCTION_KEY_RENDERINGS) {
                assertThat(KeyAction.fromAid(rendering))
                        .as("an identifier for the folded upper key %s must not exist", rendering)
                        .isEmpty();
            }

            assertThat(FOLDED_UPPER_FUNCTION_KEY_RENDERINGS).hasSize(FOLDED_FUNCTION_KEY_CLAUSES);
        }

        @Test
        @DisplayName("no constant carries an identifier for the upper twelve function keys, so the "
                + "vocabulary is lossy relative to the terminal's key set by design")
        void noConstantCarriesAnUpperFunctionKeyIdentifier() {
            final List<String> declared = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                declared.add(action.getAid());
            }

            assertThat(declared).doesNotContainAnyElementsOf(FOLDED_UPPER_FUNCTION_KEY_RENDERINGS);
            assertThat(KeyAction.values().length).isLessThan(DISPATCH_CLAUSE_COUNT);
        }
    }

    @Nested
    @DisplayName("the legacy census and the absences it justifies")
    class LegacyCensusAndDocumentedAbsences {

        @Test
        @DisplayName("the counted legacy facts are sixteen condition names, twenty-eight dispatch "
                + "clauses, two paragraphs and zero catch-all clauses")
        void theCountedLegacyFactsHold() {
            // Counted in the two governing copybooks and typed out as literals rather than derived from
            // the type under test. These are layout and census figures, not service levels.
            assertThat(CONDITION_NAME_COUNT).isEqualTo(16);
            assertThat(DISPATCH_CLAUSE_COUNT).isEqualTo(28);
            assertThat(DISPATCH_PARAGRAPH_COUNT).isEqualTo(2);
            assertThat(DISPATCH_CATCH_ALL_CLAUSE_COUNT).isZero();
            assertThat(KeyAction.values()).hasSize(CONDITION_NAME_COUNT);
        }

        @Test
        @DisplayName("the twenty-eight dispatch clauses decompose as four non-function keys plus twelve "
                + "lower function keys plus twelve folded upper function keys")
        void theDispatchClausesDecomposeAsFourPlusTwelvePlusTwelve() {
            assertThat(NON_FUNCTION_KEY_CLAUSES).isEqualTo(4);
            assertThat(LOWER_FUNCTION_KEY_CLAUSES).isEqualTo(12);
            assertThat(FOLDED_FUNCTION_KEY_CLAUSES).isEqualTo(12);
            assertThat(NON_FUNCTION_KEY_CLAUSES
                    + LOWER_FUNCTION_KEY_CLAUSES
                    + FOLDED_FUNCTION_KEY_CLAUSES)
                    .isEqualTo(DISPATCH_CLAUSE_COUNT);
            assertThat(NON_FUNCTION_KEY_CLAUSES + LOWER_FUNCTION_KEY_CLAUSES)
                    .isEqualTo(CONDITION_NAME_COUNT);
        }

        @Test
        @DisplayName("the enumeration declares no catch-all constant of any name, because the dispatch "
                + "construct has twenty-eight clauses and no catch-all clause")
        void theEnumerationDeclaresNoCatchAllConstant() {
            // Documented by absence. The dispatch construct in app/cpy/CSSTRPFY.cpy has no catch-all
            // clause, so an unrecognised terminal identifier leaves the work-area field untouched and no
            // sentinel value is ever stored. This enumeration therefore declares no synthetic constant
            // for an unrecognised key under any name - not unknown, none, other, invalid, unmapped or
            // default - because such a constant would represent a state the legacy system cannot
            // produce, and would let a caller branch on something the legacy dispatch never branched on.
            // The absence is proved by the closed count of sixteen and by the constant names matching
            // the copybook's sixteen condition-name suffixes exactly, and no such constant is referenced
            // anywhere in this file. No reflection is used to prove it. Recorded in
            // docs/decision-log.md. A default clause inside a Java switch is an unrelated language
            // construct and is not what is excluded here.
            assertThat(KeyAction.values()).hasSize(CONDITION_NAME_COUNT);

            final List<String> declaredNames = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                declaredNames.add(action.name());
            }

            assertThat(declaredNames).containsExactlyElementsOf(TRANSCRIBED_NAMES);
            assertThat(DISPATCH_CATCH_ALL_CLAUSE_COUNT).isZero();
        }

        @Test
        @DisplayName("the remaining fields of the same work-area group are not modelled here, nor are "
                + "the copybook's eight commented-out declarations")
        void theRemainingWorkAreaFieldsAreNotModelledHere() {
            // Documented by absence. Besides the attention-identifier field, the work-area group in
            // app/cpy/CVCRD01Y.cpy declares a next-program field, a next-mapset field, a next-map
            // field, two seventy-five-character message fields with a low-values condition name, and
            // three identifier fields each paired with a numeric redefinition. All of those belong to
            // the screen work-area transfer object, not to this enumeration, and none is referenced
            // anywhere in this file. The same copybook additionally carries eight commented-out
            // declarations - a last-program field, a return-to-program field, a return flag with two
            // condition names and a function field with two condition names - which are inactive text
            // describing no behaviour; they are neither modelled nor asserted. The proof is that the
            // constant names are exactly the sixteen attention-identifier condition-name suffixes, so
            // nothing from the rest of the group has leaked in.
            final List<String> declaredNames = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                declaredNames.add(action.name());
            }

            assertThat(declaredNames)
                    .containsExactlyElementsOf(TRANSCRIBED_NAMES)
                    .hasSize(CONDITION_NAME_COUNT);
        }

        @Test
        @DisplayName("the transcribed copybook evidence is immutable, so no test can widen the sixteen "
                + "condition values the rest of this suite compares against")
        void theTranscribedEvidenceIsImmutable() {
            assertThatThrownBy(() -> TRANSCRIBED_IDENTIFIERS.put("PFK13", "PFK13"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(TRANSCRIBED_IDENTIFIERS).hasSize(CONDITION_NAME_COUNT);
        }
    }
}
