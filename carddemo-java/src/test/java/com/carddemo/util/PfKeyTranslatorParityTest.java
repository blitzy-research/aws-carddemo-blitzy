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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.domain.enums.KeyAction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link PfKeyTranslator}, the substitution for the attention-key copybook.
 *
 * <p><strong>What the legacy authority is.</strong> The whole of this utility stands in for the
 * procedural copybook {@code app/cpy/CSSTRPFY.cpy}, which holds two paragraphs:
 * {@code YYYY-STORE-PFKEY} at line 17 and {@code YYYY-STORE-PFKEY-EXIT} at line 80, the latter being
 * a bare {@code EXIT} and therefore a no-op. The first paragraph is a single
 * {@code EVALUATE TRUE} construct with exactly <b>28</b> {@code WHEN} clauses, running from line 22
 * to line 77, that compares the CICS attention-key field against 28 named identifiers and sets one
 * of 16 condition names on the work-area action field. Five programs copy it with the quoted form
 * and each performs the range once: {@code app/cbl/COACTUPC.cbl} line 898,
 * {@code app/cbl/COACTVWC.cbl} line 299, {@code app/cbl/COCRDLIC.cbl} line 349,
 * {@code app/cbl/COCRDSLC.cbl} line 284 and {@code app/cbl/COCRDUPC.cbl} line 406.
 *
 * <p>The action field itself is declared in {@code app/cpy/CVCRD01Y.cpy} at line 3 as a
 * five-character field, with its 16 condition names at lines 4 to 19. That declared width is what
 * makes two of the 16 values carry trailing blanks: the first and second programmed-attention keys
 * are three characters of mnemonic in a five-character field, so their stored images are
 * {@code "PA1  "} and {@code "PA2  "}. Trimming them would be a natural-looking Java tidy-up that
 * silently changes a stored field image, so this class pins the padding explicitly.
 *
 * <p><strong>The three properties that matter most.</strong> First, <em>the twelve-way fold</em>.
 * Clauses 17 to 28 map the thirteenth through twenty-fourth function keys onto the very same
 * condition names as the first through twelfth. Keys 13 to 24 are therefore not distinct actions,
 * and a caller cannot tell which of a folded pair was pressed. That is a deliberate legacy
 * behaviour, not an oversight, and preserving it is what keeps 28 recognised identifiers collapsing
 * to 16 distinct actions.
 *
 * <p>Second, <em>the absent fallback clause</em>. The legacy construct has no {@code WHEN OTHER}
 * arm. An unrecognised attention key therefore performs no assignment at all, which leaves the
 * work-area field holding whatever it already held from the previous turn of the pseudo-conversation.
 * The faithful translation of "no assignment happened" is an empty result rather than a default
 * action or an exception, because either of those would invent a decision the legacy never made.
 *
 * <p>Third, <em>an unrecognised key is data but a null reference is a defect</em>. A terminal can
 * legitimately deliver an identifier this construct does not name; it cannot legitimately deliver no
 * identifier at all. The two are kept apart: the former yields an empty result, the latter a
 * {@link NullPointerException}.
 *
 * <p><strong>How the expectations are anchored.</strong> Every expected pair in this class is
 * transcribed from the two copybooks named above &mdash; identifier tokens from the 28 clauses and
 * action images from the 16 condition names &mdash; and held in this file as literals. Nothing here
 * derives an expectation from the class under test, so a change to either table in production is a
 * failure here rather than a silently agreeing pair of tables.
 *
 * <p><strong>Deliberately not asserted.</strong> The utility's private constructor is not reached.
 * Doing so would require reflection and the module's unsafe-code audit budget for reflection is
 * zero, so the constructor is left exactly as unreachable as it is meant to be. The two static
 * builders each guard themselves with a cardinality check that raises
 * {@link IllegalStateException}; those arms are unreachable through the published API by
 * construction, because the tables they check are compile-time literals, and they are therefore
 * reported as uncovered rather than chased. What this class asserts instead is the property those
 * guards exist to protect: the published cardinalities are 28 and 16, and every published action
 * image measures exactly five encoded bytes.
 *
 * <p>One further honest limitation is recorded rather than glossed over. The width check inside the
 * reverse reading is behaviourally redundant given the lookup that follows it: a value of the wrong
 * width can never equal a five-character key, so removing the check would change no outcome. The
 * tests in that group therefore establish the <em>outcome</em> for narrow, wide and unrepresentable
 * values, and make no claim to prove the check itself is load-bearing.
 *
 * <p><strong>What mutating the production class proves.</strong> Rewriting the twenty-fourth clause
 * so it no longer folds onto the twelfth breaks three assertions here. Trimming the trailing blanks
 * from the first programmed-attention image breaks thirty-six, because the width guard in the static
 * builder then refuses to initialise the class at all &mdash; which also demonstrates that the guard
 * is live rather than decorative. The five assertions over the transcribed tables survive both
 * mutations, exactly as they should: they check the transcription, not the class under test.
 */
@DisplayName("PfKeyTranslator — 28 identifiers, 16 actions, one twelve-way fold")
class PfKeyTranslatorParityTest {

    /** The declared width of the work-area action field, in bytes. */
    private static final int ACTION_WIDTH = 5;

    /** The number of {@code WHEN} clauses in the legacy construct. */
    private static final int CLAUSE_COUNT = 28;

    /** The number of condition names on the work-area action field. */
    private static final int ACTION_COUNT = 16;

    /**
     * The 28 clauses of the legacy construct, in source order, transcribed from
     * {@code app/cpy/CSSTRPFY.cpy} lines 22 to 77.
     *
     * <p>Iteration order is the clause order, so a test that walks this map walks the legacy
     * construct top to bottom. The final twelve entries are the fold.</p>
     */
    private static final Map<String, KeyAction> LEGACY_CLAUSES = legacyClauses();

    /**
     * The 16 condition names on the work-area action field, in declaration order, transcribed from
     * {@code app/cpy/CVCRD01Y.cpy} lines 4 to 19.
     */
    private static final Map<KeyAction, String> LEGACY_ACTION_IMAGES = legacyActionImages();

    /**
     * Transcribes the 28 clauses of the legacy attention-key construct.
     *
     * @return the identifier-to-action pairs in clause order
     */
    private static Map<String, KeyAction> legacyClauses() {
        final Map<String, KeyAction> clauses = new LinkedHashMap<>();
        clauses.put("DFHENTER", KeyAction.ENTER);
        clauses.put("DFHCLEAR", KeyAction.CLEAR);
        clauses.put("DFHPA1", KeyAction.PA1);
        clauses.put("DFHPA2", KeyAction.PA2);
        clauses.put("DFHPF1", KeyAction.PFK01);
        clauses.put("DFHPF2", KeyAction.PFK02);
        clauses.put("DFHPF3", KeyAction.PFK03);
        clauses.put("DFHPF4", KeyAction.PFK04);
        clauses.put("DFHPF5", KeyAction.PFK05);
        clauses.put("DFHPF6", KeyAction.PFK06);
        clauses.put("DFHPF7", KeyAction.PFK07);
        clauses.put("DFHPF8", KeyAction.PFK08);
        clauses.put("DFHPF9", KeyAction.PFK09);
        clauses.put("DFHPF10", KeyAction.PFK10);
        clauses.put("DFHPF11", KeyAction.PFK11);
        clauses.put("DFHPF12", KeyAction.PFK12);
        clauses.put("DFHPF13", KeyAction.PFK01);
        clauses.put("DFHPF14", KeyAction.PFK02);
        clauses.put("DFHPF15", KeyAction.PFK03);
        clauses.put("DFHPF16", KeyAction.PFK04);
        clauses.put("DFHPF17", KeyAction.PFK05);
        clauses.put("DFHPF18", KeyAction.PFK06);
        clauses.put("DFHPF19", KeyAction.PFK07);
        clauses.put("DFHPF20", KeyAction.PFK08);
        clauses.put("DFHPF21", KeyAction.PFK09);
        clauses.put("DFHPF22", KeyAction.PFK10);
        clauses.put("DFHPF23", KeyAction.PFK11);
        clauses.put("DFHPF24", KeyAction.PFK12);
        return clauses;
    }

    /**
     * Transcribes the 16 condition-name values on the work-area action field.
     *
     * <p>The first and second programmed-attention values carry the trailing blanks the
     * five-character declaration forces.</p>
     *
     * @return the action-to-image pairs in declaration order
     */
    private static Map<KeyAction, String> legacyActionImages() {
        final Map<KeyAction, String> images = new LinkedHashMap<>();
        images.put(KeyAction.ENTER, "ENTER");
        images.put(KeyAction.CLEAR, "CLEAR");
        images.put(KeyAction.PA1, "PA1  ");
        images.put(KeyAction.PA2, "PA2  ");
        images.put(KeyAction.PFK01, "PFK01");
        images.put(KeyAction.PFK02, "PFK02");
        images.put(KeyAction.PFK03, "PFK03");
        images.put(KeyAction.PFK04, "PFK04");
        images.put(KeyAction.PFK05, "PFK05");
        images.put(KeyAction.PFK06, "PFK06");
        images.put(KeyAction.PFK07, "PFK07");
        images.put(KeyAction.PFK08, "PFK08");
        images.put(KeyAction.PFK09, "PFK09");
        images.put(KeyAction.PFK10, "PFK10");
        images.put(KeyAction.PFK11, "PFK11");
        images.put(KeyAction.PFK12, "PFK12");
        return images;
    }

    /**
     * Measures a value the way the utility does, in encoded bytes rather than characters.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies once encoded for a fixed-width field
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    // THE TRANSCRIBED TABLES

    /**
     * Guards the transcription itself.
     *
     * <p>Every other group in this class trusts the two tables above. If a transcription slipped, the
     * whole class would agree with the wrong thing, so the transcription is checked against the
     * counts the legacy declarations independently establish: 28 clauses across 16 distinct
     * assignments, with the last twelve clauses repeating the first twelve function-key
     * assignments.</p>
     */
    @Nested
    @DisplayName("the transcribed legacy tables")
    class TranscribedTables {

        @Test
        @DisplayName("hold one entry per clause of the legacy construct")
        void holdOneEntryPerClause() {
            assertThat(LEGACY_CLAUSES).hasSize(CLAUSE_COUNT);
        }

        @Test
        @DisplayName("hold one image per condition name on the action field")
        void holdOneImagePerConditionName() {
            assertThat(LEGACY_ACTION_IMAGES).hasSize(ACTION_COUNT);
        }

        @Test
        @DisplayName("assign only the 16 declared actions across the 28 clauses")
        void assignOnlyDeclaredActions() {
            assertThat(LEGACY_CLAUSES.values()).containsOnly(KeyAction.values());
            assertThat(Set.copyOf(LEGACY_CLAUSES.values())).hasSize(ACTION_COUNT);
        }

        @Test
        @DisplayName("cover every declared action, so no clause is missing")
        void coverEveryDeclaredAction() {
            assertThat(LEGACY_ACTION_IMAGES.keySet())
                    .containsExactly(KeyAction.values());
        }

        @Test
        @DisplayName("repeat the first twelve function-key assignments in the last twelve clauses")
        void repeatTheFunctionKeyAssignments() {
            final List<KeyAction> assignments = new ArrayList<>(LEGACY_CLAUSES.values());
            final List<KeyAction> firstTwelve = assignments.subList(4, 16);
            final List<KeyAction> lastTwelve = assignments.subList(16, CLAUSE_COUNT);

            assertThat(lastTwelve).isEqualTo(firstTwelve);
        }
    }

    // TRANSLATION OF AN ATTENTION KEY

    /**
     * Verifies the translation of an attention-key identifier, which is the whole of
     * {@code YYYY-STORE-PFKEY}.
     */
    @Nested
    @DisplayName("translate")
    class Translation {

        @Test
        @DisplayName("resolves every one of the 28 legacy clauses to its declared action")
        void resolvesEveryClause() {
            for (final Map.Entry<String, KeyAction> clause : LEGACY_CLAUSES.entrySet()) {
                assertThat(PfKeyTranslator.translate(clause.getKey()))
                        .as("clause for %s", clause.getKey())
                        .contains(clause.getValue());
            }
        }

        @Test
        @DisplayName("resolves the four keys that are not function keys")
        void resolvesTheNonFunctionKeys() {
            assertThat(PfKeyTranslator.translate("DFHENTER")).contains(KeyAction.ENTER);
            assertThat(PfKeyTranslator.translate("DFHCLEAR")).contains(KeyAction.CLEAR);
            assertThat(PfKeyTranslator.translate("DFHPA1")).contains(KeyAction.PA1);
            assertThat(PfKeyTranslator.translate("DFHPA2")).contains(KeyAction.PA2);
        }

        @Test
        @DisplayName("resolves function keys one through twelve in ordinal order")
        void resolvesTheFirstTwelveFunctionKeys() {
            for (int key = 1; key <= 12; key++) {
                final KeyAction expected = KeyAction.valueOf(String.format("PFK%02d", key));

                assertThat(PfKeyTranslator.translate("DFHPF" + key))
                        .as("function key %d", key)
                        .contains(expected);
            }
        }

        @Test
        @DisplayName("folds function keys thirteen through twenty-four onto one through twelve")
        void foldsTheUpperFunctionKeys() {
            for (int key = 1; key <= 12; key++) {
                final Optional<KeyAction> lower = PfKeyTranslator.translate("DFHPF" + key);
                final Optional<KeyAction> upper = PfKeyTranslator.translate("DFHPF" + (key + 12));

                assertThat(upper)
                        .as("key %d folds onto key %d", key + 12, key)
                        .isEqualTo(lower);
            }
        }

        @Test
        @DisplayName("cannot distinguish a folded pair, which is the legacy behaviour")
        void cannotDistinguishAFoldedPair() {
            assertThat(PfKeyTranslator.translate("DFHPF24"))
                    .contains(KeyAction.PFK12)
                    .isEqualTo(PfKeyTranslator.translate("DFHPF12"));
        }

        @Test
        @DisplayName("collapses the 28 recognised identifiers to 16 distinct actions")
        void collapsesToSixteenDistinctActions() {
            final Set<KeyAction> produced = PfKeyTranslator.recognisedIdentifiers().stream()
                    .map(PfKeyTranslator::translate)
                    .map(Optional::orElseThrow)
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(PfKeyTranslator.recognisedIdentifiers()).hasSize(CLAUSE_COUNT);
            assertThat(produced).hasSize(ACTION_COUNT);
        }

        @Test
        @DisplayName("yields nothing for a key beyond the twenty-fourth, because no clause names it")
        void yieldsNothingBeyondTwentyFour() {
            assertThat(PfKeyTranslator.translate("DFHPF25")).isEmpty();
            assertThat(PfKeyTranslator.translate("DFHPF36")).isEmpty();
        }

        @Test
        @DisplayName("yields nothing for a third programmed-attention key, which CICS has but the "
                + "construct does not name")
        void yieldsNothingForTheThirdProgrammedAttentionKey() {
            assertThat(PfKeyTranslator.translate("DFHPA3")).isEmpty();
        }

        @Test
        @DisplayName("yields nothing for a zero-numbered or leading-zero function key")
        void yieldsNothingForAMalformedFunctionKeyNumber() {
            assertThat(PfKeyTranslator.translate("DFHPF0")).isEmpty();
            assertThat(PfKeyTranslator.translate("DFHPF01")).isEmpty();
            assertThat(PfKeyTranslator.translate("DFHPF012")).isEmpty();
        }

        @Test
        @DisplayName("compares the identifier exactly, so case and surrounding blanks matter")
        void comparesTheIdentifierExactly() {
            assertThat(PfKeyTranslator.translate("dfhenter")).isEmpty();
            assertThat(PfKeyTranslator.translate("DfhEnter")).isEmpty();
            assertThat(PfKeyTranslator.translate("DFHENTER ")).isEmpty();
            assertThat(PfKeyTranslator.translate(" DFHENTER")).isEmpty();
        }

        @Test
        @DisplayName("yields nothing for an empty or blank identifier")
        void yieldsNothingForAnEmptyIdentifier() {
            assertThat(PfKeyTranslator.translate("")).isEmpty();
            assertThat(PfKeyTranslator.translate("   ")).isEmpty();
        }

        @Test
        @DisplayName("refuses a null identifier, which is a caller defect rather than a key the "
                + "construct does not name")
        void refusesANullIdentifier() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PfKeyTranslator.translate(null))
                    .withMessageContaining("attentionKeyIdentifier must not be null")
                    .withMessageContaining("an unrecognised key yields an empty result");
        }

        @Test
        @DisplayName("never yields a null result")
        void neverYieldsANullResult() {
            assertThat(PfKeyTranslator.translate("DFHENTER")).isNotNull();
            assertThat(PfKeyTranslator.translate("NOT-A-KEY")).isNotNull().isEmpty();
        }
    }

    // THE STORED ACTION IMAGE

    /**
     * Verifies the action image the translation stores into the five-character work-area field.
     */
    @Nested
    @DisplayName("actionValue")
    class ActionImage {

        @Test
        @DisplayName("returns the declared condition-name value for every action")
        void returnsTheDeclaredValue() {
            for (final Map.Entry<KeyAction, String> declared : LEGACY_ACTION_IMAGES.entrySet()) {
                assertThat(PfKeyTranslator.actionValue(declared.getKey()))
                        .as("condition name for %s", declared.getKey())
                        .isEqualTo(declared.getValue());
            }
        }

        @Test
        @DisplayName("pads the two programmed-attention values to the declared field width")
        void padsTheProgrammedAttentionValues() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA1)).isEqualTo("PA1  ");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA2)).isEqualTo("PA2  ");
        }

        @Test
        @DisplayName("measures exactly the declared field width for every action")
        void measuresTheDeclaredWidth() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(encodedWidth(PfKeyTranslator.actionValue(action)))
                        .as("encoded width of %s", action)
                        .isEqualTo(ACTION_WIDTH);
            }
        }

        @Test
        @DisplayName("publishes the declared field width as a constant")
        void publishesTheDeclaredWidth() {
            assertThat(PfKeyTranslator.ACTION_VALUE_BYTE_WIDTH).isEqualTo(ACTION_WIDTH);
        }

        @Test
        @DisplayName("returns a distinct value for every action, so no two actions collide")
        void returnsADistinctValuePerAction() {
            final List<String> values = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                values.add(PfKeyTranslator.actionValue(action));
            }

            assertThat(values).hasSize(ACTION_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("refuses a null action, because every declared action has a value")
        void refusesANullAction() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PfKeyTranslator.actionValue(null))
                    .withMessageContaining("keyAction must not be null")
                    .withMessageContaining("every declared action has an action value");
        }
    }

    // READING AN ACTION IMAGE BACK

    /**
     * Verifies the reverse reading, which recovers an action from a stored field image.
     *
     * <p>This direction exists because the work-area field survives a pseudo-conversation turn: a
     * later turn reads back what an earlier turn stored. Reading is therefore width-sensitive in a
     * way translating is not, because a value that does not fill the field cannot have come out of
     * it.</p>
     */
    @Nested
    @DisplayName("fromActionValue")
    class ReverseReading {

        @Test
        @DisplayName("round-trips every action through its stored image")
        void roundTripsEveryAction() {
            for (final KeyAction action : KeyAction.values()) {
                final String stored = PfKeyTranslator.actionValue(action);

                assertThat(PfKeyTranslator.fromActionValue(stored))
                        .as("round trip of %s", action)
                        .contains(action);
            }
        }

        @Test
        @DisplayName("recovers the padded programmed-attention actions only when the padding is "
                + "present")
        void recoversThePaddedActionsOnlyWhenPadded() {
            assertThat(PfKeyTranslator.fromActionValue("PA1  ")).contains(KeyAction.PA1);
            assertThat(PfKeyTranslator.fromActionValue("PA2  ")).contains(KeyAction.PA2);
            assertThat(PfKeyTranslator.fromActionValue("PA1")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PA2")).isEmpty();
        }

        @Test
        @DisplayName("yields nothing for a value narrower than the field")
        void yieldsNothingForANarrowValue() {
            assertThat(PfKeyTranslator.fromActionValue("")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PFK")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("ENTE")).isEmpty();
        }

        @Test
        @DisplayName("yields nothing for a value wider than the field")
        void yieldsNothingForAWideValue() {
            assertThat(PfKeyTranslator.fromActionValue("ENTER ")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PFK012")).isEmpty();
        }

        @Test
        @DisplayName("yields nothing for a correctly sized value that names no condition")
        void yieldsNothingForAnUnnamedValue() {
            assertThat(PfKeyTranslator.fromActionValue("PFK13")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PFK00")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PA3  ")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("XXXXX")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("     ")).isEmpty();
        }

        @Test
        @DisplayName("compares the image exactly, so case matters")
        void comparesTheImageExactly() {
            assertThat(PfKeyTranslator.fromActionValue("enter")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("pfk01")).isEmpty();
        }

        @Test
        @DisplayName("yields nothing for a value that is not representable in the field's encoding")
        void yieldsNothingForANonRepresentableValue() {
            assertThat(PfKeyTranslator.fromActionValue("ENTE\u00c9")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PFK0\u20ac")).isEmpty();
        }

        @Test
        @DisplayName("refuses a null image, which is a caller defect rather than an unnamed value")
        void refusesANullImage() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PfKeyTranslator.fromActionValue(null))
                    .withMessageContaining("actionValue must not be null")
                    .withMessageContaining("an unrecognised value yields an empty result");
        }

        @Test
        @DisplayName("agrees with the forward translation for every recognised identifier")
        void agreesWithTheForwardTranslation() {
            for (final String identifier : PfKeyTranslator.recognisedIdentifiers()) {
                final KeyAction forward = PfKeyTranslator.translate(identifier).orElseThrow();
                final String stored = PfKeyTranslator.actionValue(forward);

                assertThat(PfKeyTranslator.fromActionValue(stored))
                        .as("agreement for %s", identifier)
                        .contains(forward);
            }
        }
    }

    // THE PUBLISHED CATALOGUES

    /**
     * Verifies the two catalogues the utility publishes, which exist so that a caller can enumerate
     * what the construct names without duplicating either table.
     */
    @Nested
    @DisplayName("the published catalogues")
    class Catalogues {

        @Test
        @DisplayName("list the recognised identifiers in legacy clause order")
        void listIdentifiersInClauseOrder() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .containsExactlyElementsOf(LEGACY_CLAUSES.keySet());
        }

        @Test
        @DisplayName("list the action images in declaration order")
        void listActionImagesInDeclarationOrder() {
            assertThat(PfKeyTranslator.actionValues())
                    .containsExactlyElementsOf(LEGACY_ACTION_IMAGES.values());
        }

        @Test
        @DisplayName("hold the legacy cardinalities of 28 and 16")
        void holdTheLegacyCardinalities() {
            assertThat(PfKeyTranslator.recognisedIdentifiers()).hasSize(CLAUSE_COUNT);
            assertThat(PfKeyTranslator.actionValues()).hasSize(ACTION_COUNT);
        }

        @Test
        @DisplayName("refuse modification, so a caller cannot corrupt the shared tables")
        void refuseModification() {
            final Set<String> identifiers = PfKeyTranslator.recognisedIdentifiers();
            final Set<String> images = PfKeyTranslator.actionValues();

            assertThatThrownBy(() -> identifiers.add("DFHPF25"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> identifiers.clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> images.remove("ENTER"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("hand back the same instance on every call, because both are immutable")
        void handBackTheSameInstance() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .isSameAs(PfKeyTranslator.recognisedIdentifiers());
            assertThat(PfKeyTranslator.actionValues())
                    .isSameAs(PfKeyTranslator.actionValues());
        }

        @Test
        @DisplayName("name every identifier the construct recognises and nothing else")
        void nameOnlyRecognisedIdentifiers() {
            for (final String identifier : PfKeyTranslator.recognisedIdentifiers()) {
                assertThat(PfKeyTranslator.translate(identifier))
                        .as("catalogued identifier %s", identifier)
                        .isPresent();
            }
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .doesNotContain("DFHPF25", "DFHPA3", "DFHENTER ", "dfhenter");
        }

        @Test
        @DisplayName("publish only images that fill the declared field width")
        void publishOnlyFullWidthImages() {
            for (final String image : PfKeyTranslator.actionValues()) {
                assertThat(encodedWidth(image))
                        .as("encoded width of %s", image)
                        .isEqualTo(ACTION_WIDTH);
            }
        }

        @Test
        @DisplayName("resolve every published image back to an action")
        void resolveEveryPublishedImage() {
            for (final String image : PfKeyTranslator.actionValues()) {
                assertThat(PfKeyTranslator.fromActionValue(image))
                        .as("published image %s", image)
                        .isPresent();
            }
        }
    }
}
