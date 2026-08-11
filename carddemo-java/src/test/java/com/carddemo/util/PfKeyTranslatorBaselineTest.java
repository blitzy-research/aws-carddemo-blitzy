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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.domain.enums.KeyAction;

/**
 * Unit test for {@link PfKeyTranslator}, the single Java equivalent of the two-paragraph attention-key
 * translation copybook that five online programs include with quoted syntax.
 *
 * <p><strong>What this test proves.</strong> The legacy construct has 28 clauses, no fallback clause, and
 * one property that a modernising translation would very plausibly "fix" and thereby break:
 * <ul>
 *   <li><strong>The upper bank folds.</strong> Clauses 17 through 28 map the twelfth through
 *       twenty-fourth program-function keys onto the <em>same twelve</em> flags that clauses 5 through 16
 *       set for keys one through twelve. Keys 13 to 24 are therefore not distinct actions, and a
 *       translation that invented twelve more constants would let a screen distinguish two keystrokes the
 *       legacy system cannot distinguish. This test asserts the fold pairwise for all twelve pairs.</li>
 *   <li><strong>There is no otherwise branch.</strong> An unrecognised identifier performs no assignment
 *       at all, so the caller keeps whatever action it was already holding. The Java equivalent of "no
 *       assignment happened" is an empty result, never a synthesised UNKNOWN constant, because no such
 *       state exists in the legacy work area. This test asserts that an unrecognised token yields an empty
 *       result and that an absent reference is instead rejected as a caller defect - the two are
 *       different failures and must not collapse into one.</li>
 *   <li><strong>Action values are fixed-width, padding included.</strong> The work-area action field is
 *       five bytes. Two of the sixteen values - the two program-attention mnemonics - are three
 *       characters of text followed by two significant spaces. This test measures every value as
 *       <em>encoded bytes</em> rather than characters, which is the meaningful measure for a field that
 *       reserves a byte count, and asserts that a trimmed mnemonic does <em>not</em> resolve.</li>
 *   <li><strong>28 identifiers, 16 values.</strong> The gap between the two counts is the fold made
 *       visible: twelve of the recognised identifiers contribute no value of their own.</li>
 * </ul>
 *
 * <p><strong>Scope.</strong> A pure in-process unit test. It starts no application context, opens no
 * database connection, reads no file, touches no network, runs no container and performs no
 * introspection - in particular it does not reach the private constructor reflectively, because the
 * constructor's inaccessibility is established by the code that never calls it rather than by a
 * reflective probe. The module's zero-reflection budget is scoped to production sources under
 * {@code src/main/java}, so a test that did reflect would not undermine it.
 *
 * <p><strong>Expectations are derived, never echoed.</strong> Every identifier token, action value, width
 * and count below is a literal typed out in this source, taken from the copybook clause table and from the
 * level-88 condition names declared beneath the work-area action field. Nothing is read back out of the
 * class under test and then asserted against itself, and no line of legacy source is transcribed.
 */
@DisplayName("PfKeyTranslator - the single translation point for the 28-clause attention-key construct")
class PfKeyTranslatorBaselineTest {

    /** Clauses in the legacy construct, and therefore recognised identifier tokens. */
    private static final int RECOGNISED_IDENTIFIER_COUNT = 28;

    /** Level-88 condition names on the work-area action field, and therefore distinct actions. */
    private static final int DISTINCT_ACTION_COUNT = 16;

    /** Program-function keys in each of the two banks. */
    private static final int FUNCTION_KEYS_PER_BANK = 12;

    /** Byte width the work-area action field reserves. */
    private static final int ACTION_VALUE_BYTE_WIDTH = 5;

    /**
     * The 28 recognised identifier tokens in the clause order of the legacy construct: the two
     * non-function keys, the two program-attention keys, the lower function-key bank and the upper
     * function-key bank.
     */
    private static final List<String> CLAUSE_ORDERED_IDENTIFIERS = List.of(
            "DFHENTER", "DFHCLEAR", "DFHPA1", "DFHPA2",
            "DFHPF1", "DFHPF2", "DFHPF3", "DFHPF4", "DFHPF5", "DFHPF6",
            "DFHPF7", "DFHPF8", "DFHPF9", "DFHPF10", "DFHPF11", "DFHPF12",
            "DFHPF13", "DFHPF14", "DFHPF15", "DFHPF16", "DFHPF17", "DFHPF18",
            "DFHPF19", "DFHPF20", "DFHPF21", "DFHPF22", "DFHPF23", "DFHPF24");

    /**
     * The 16 action values in the declaration order of the level-88 condition names, padding intact.
     * The two program-attention values carry two significant trailing spaces each.
     */
    private static final List<String> DECLARATION_ORDERED_ACTION_VALUES = List.of(
            "ENTER", "CLEAR", "PA1  ", "PA2  ",
            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");

    /** The 16 actions in declaration order, aligned index for index with the values above. */
    private static final List<KeyAction> DECLARATION_ORDERED_ACTIONS = List.of(
            KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2,
            KeyAction.PFK01, KeyAction.PFK02, KeyAction.PFK03, KeyAction.PFK04,
            KeyAction.PFK05, KeyAction.PFK06, KeyAction.PFK07, KeyAction.PFK08,
            KeyAction.PFK09, KeyAction.PFK10, KeyAction.PFK11, KeyAction.PFK12);

    /**
     * Measures a value as encoded bytes in the single-byte character set the fixed-width fields are read
     * and written in, which is the meaningful measure for a field that reserves a byte count.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies when encoded
     */
    private static int encodedByteWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("Identifier translation across all 28 clauses")
    class IdentifierTranslation {

        @ParameterizedTest(name = "clause for {0} resolves to {1}")
        @DisplayName("each of the four non-function-key clauses resolves to its own action")
        @CsvSource({
            "DFHENTER, ENTER",
            "DFHCLEAR, CLEAR",
            "DFHPA1,   PA1",
            "DFHPA2,   PA2"})
        void eachNonFunctionKeyClauseResolvesToItsOwnAction(final String identifier,
                final String actionName) {
            assertThat(PfKeyTranslator.translate(identifier))
                    .contains(KeyAction.valueOf(actionName));
        }

        @ParameterizedTest(name = "lower-bank clause for {0} resolves to {1}")
        @DisplayName("each of the twelve lower-bank clauses resolves to its own function-key action")
        @CsvSource({
            "DFHPF1,  PFK01", "DFHPF2,  PFK02", "DFHPF3,  PFK03", "DFHPF4,  PFK04",
            "DFHPF5,  PFK05", "DFHPF6,  PFK06", "DFHPF7,  PFK07", "DFHPF8,  PFK08",
            "DFHPF9,  PFK09", "DFHPF10, PFK10", "DFHPF11, PFK11", "DFHPF12, PFK12"})
        void eachLowerBankClauseResolvesToItsOwnAction(final String identifier,
                final String actionName) {
            assertThat(PfKeyTranslator.translate(identifier))
                    .contains(KeyAction.valueOf(actionName));
        }

        @ParameterizedTest(name = "upper-bank clause for {0} folds onto {1}")
        @DisplayName("each of the twelve upper-bank clauses folds onto the lower-bank action twelve "
                + "positions below it, so keys 13 to 24 are not distinct actions")
        @CsvSource({
            "DFHPF13, PFK01", "DFHPF14, PFK02", "DFHPF15, PFK03", "DFHPF16, PFK04",
            "DFHPF17, PFK05", "DFHPF18, PFK06", "DFHPF19, PFK07", "DFHPF20, PFK08",
            "DFHPF21, PFK09", "DFHPF22, PFK10", "DFHPF23, PFK11", "DFHPF24, PFK12"})
        void eachUpperBankClauseFoldsOntoItsLowerBankCounterpart(final String identifier,
                final String actionName) {
            assertThat(PfKeyTranslator.translate(identifier))
                    .contains(KeyAction.valueOf(actionName));
        }

        @Test
        @DisplayName("the fold is exact for all twelve pairs: the upper-bank identifier and its "
                + "lower-bank counterpart resolve to the identical action, twelve times over")
        void theFoldIsExactForAllTwelvePairs() {
            for (int key = 1; key <= FUNCTION_KEYS_PER_BANK; key++) {
                final Optional<KeyAction> lower = PfKeyTranslator.translate("DFHPF" + key);
                final Optional<KeyAction> upper =
                        PfKeyTranslator.translate("DFHPF" + (key + FUNCTION_KEYS_PER_BANK));

                assertThat(lower).isPresent();
                assertThat(upper).isEqualTo(lower);
            }
        }

        @Test
        @DisplayName("all 28 recognised identifiers resolve, and between them they yield exactly the "
                + "16 distinct actions - the 12-token gap is the fold made visible")
        void allTwentyEightIdentifiersResolveToSixteenDistinctActions() {
            final Set<KeyAction> resolved = new HashSet<>();

            for (final String identifier : CLAUSE_ORDERED_IDENTIFIERS) {
                final Optional<KeyAction> action = PfKeyTranslator.translate(identifier);

                assertThat(action).as("identifier %s must resolve", identifier).isPresent();
                resolved.add(action.orElseThrow());
            }

            assertThat(CLAUSE_ORDERED_IDENTIFIERS).hasSize(RECOGNISED_IDENTIFIER_COUNT);
            assertThat(resolved).hasSize(DISTINCT_ACTION_COUNT);
            assertThat(RECOGNISED_IDENTIFIER_COUNT - DISTINCT_ACTION_COUNT)
                    .isEqualTo(FUNCTION_KEYS_PER_BANK);
        }

        @ParameterizedTest(name = "unrecognised identifier {0} yields no action")
        @DisplayName("an unrecognised identifier yields an empty result rather than a synthesised "
                + "constant, because the legacy construct has no otherwise branch and performs no "
                + "assignment at all")
        @ValueSource(strings = {
            "DFHPF0", "DFHPF25", "DFHPF13 ", " DFHPF13", "dfhpf13", "DFHPA3", "DFHENTER ",
            "ENTER", "PFK01", "", "   ", "DFHPF", "DFHPF1.", "DFHCLEA"})
        void anUnrecognisedIdentifierYieldsAnEmptyResult(final String identifier) {
            assertThat(PfKeyTranslator.translate(identifier)).isEmpty();
        }

        @Test
        @DisplayName("matching is exact: no trim, no case fold and no prefix match, so a token that "
                + "merely contains a recognised one does not resolve")
        void matchingIsExact() {
            assertThat(PfKeyTranslator.translate("DFHPF1")).contains(KeyAction.PFK01);
            assertThat(PfKeyTranslator.translate("DFHPF1X")).isEmpty();
            assertThat(PfKeyTranslator.translate("XDFHPF1")).isEmpty();
            assertThat(PfKeyTranslator.translate("DFHPF01")).isEmpty();
        }

        @Test
        @DisplayName("an absent reference is rejected as a caller defect rather than treated as an "
                + "unrecognised key, because the two are different failures")
        void anAbsentReferenceIsRejectedAsACallerDefect() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.translate(null))
                    .withMessageContaining("attentionKeyIdentifier");
        }
    }

    @Nested
    @DisplayName("Action values are fixed-width with significant padding")
    class ActionValues {

        @ParameterizedTest(name = "{0} carries the action value {1}")
        @DisplayName("every action carries its declared five-byte value, padding included")
        @CsvSource({
            "ENTER, ENTER", "CLEAR, CLEAR", "PA1, 'PA1  '", "PA2, 'PA2  '",
            "PFK01, PFK01", "PFK02, PFK02", "PFK03, PFK03", "PFK04, PFK04",
            "PFK05, PFK05", "PFK06, PFK06", "PFK07, PFK07", "PFK08, PFK08",
            "PFK09, PFK09", "PFK10, PFK10", "PFK11, PFK11", "PFK12, PFK12"})
        void everyActionCarriesItsDeclaredValue(final String actionName, final String expected) {
            assertThat(PfKeyTranslator.actionValue(KeyAction.valueOf(actionName)))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("every one of the sixteen values measures exactly five encoded bytes, which is what "
                + "the work-area field reserves - measured as bytes, not characters")
        void everyValueMeasuresExactlyFiveEncodedBytes() {
            for (final KeyAction action : KeyAction.values()) {
                final String value = PfKeyTranslator.actionValue(action);

                assertThat(encodedByteWidth(value))
                        .as("action value for %s", action.name())
                        .isEqualTo(ACTION_VALUE_BYTE_WIDTH);
            }

            assertThat(KeyAction.values()).hasSize(DISTINCT_ACTION_COUNT);
        }

        @Test
        @DisplayName("the two program-attention values carry two significant trailing spaces and are "
                + "the only two values that do, so padding is not incidental")
        void onlyTheProgramAttentionValuesCarryTrailingSpaces() {
            final List<KeyAction> padded = new ArrayList<>();

            for (final KeyAction action : KeyAction.values()) {
                if (PfKeyTranslator.actionValue(action).endsWith(" ")) {
                    padded.add(action);
                }
            }

            assertThat(padded).containsExactly(KeyAction.PA1, KeyAction.PA2);
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA1)).isEqualTo("PA1  ");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA2)).isEqualTo("PA2  ");
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA1).strip()).hasSize(3);
        }

        @Test
        @DisplayName("the forward accessor agrees with the enumeration's own accessor for every action, "
                + "so the padded literals cannot drift between the two declarations")
        void theForwardAccessorAgreesWithTheEnumerationAccessor() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(PfKeyTranslator.actionValue(action)).isEqualTo(action.getAid());
            }
        }

        @Test
        @DisplayName("the declared width constant is five, matching the value width, so a reader does not "
                + "have to infer the field width from a literal")
        void theDeclaredWidthConstantIsFive() {
            assertThat(PfKeyTranslator.ACTION_VALUE_BYTE_WIDTH).isEqualTo(ACTION_VALUE_BYTE_WIDTH);
        }

        @Test
        @DisplayName("an absent action is rejected as a caller defect, because every declared action has "
                + "a value and so an absent reference cannot be anything else")
        void anAbsentActionIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.actionValue(null))
                    .withMessageContaining("keyAction");
        }
    }

    @Nested
    @DisplayName("Reverse resolution from a work-area value")
    class ReverseResolution {

        @ParameterizedTest(name = "the value {0} resolves to {1}")
        @DisplayName("every declared five-byte value resolves back to its own action, so the two "
                + "directions are mutually inverse")
        @CsvSource({
            "ENTER, ENTER", "CLEAR, CLEAR", "'PA1  ', PA1", "'PA2  ', PA2",
            "PFK01, PFK01", "PFK06, PFK06", "PFK12, PFK12"})
        void everyDeclaredValueResolvesBackToItsAction(final String value, final String actionName) {
            assertThat(PfKeyTranslator.fromActionValue(value))
                    .contains(KeyAction.valueOf(actionName));
        }

        @Test
        @DisplayName("the round trip closes for all sixteen actions in both directions")
        void theRoundTripClosesForAllSixteenActions() {
            for (final KeyAction action : KeyAction.values()) {
                assertThat(PfKeyTranslator.fromActionValue(PfKeyTranslator.actionValue(action)))
                        .contains(action);
            }
        }

        @ParameterizedTest(name = "a value of the wrong width, {0}, does not resolve")
        @DisplayName("a value that is not exactly five encoded bytes is rejected before the lookup is "
                + "attempted, so a trimmed program-attention mnemonic does not resolve")
        @ValueSource(strings = {"PA1", "PA2", "PA1 ", "PA1   ", "ENTE", "ENTER ", "", "PFK1"})
        void aValueOfTheWrongWidthDoesNotResolve(final String value) {
            assertThat(PfKeyTranslator.fromActionValue(value)).isEmpty();
        }

        @ParameterizedTest(name = "a five-byte value that is not declared, {0}, does not resolve")
        @DisplayName("a value of the right width that is not one of the sixteen declared values yields "
                + "an empty result, for the same reason an unrecognised key does - and the width guard "
                + "is therefore not what rejected it")
        @ValueSource(strings = {"PFK00", "PFK13", "pfk01", "ENTRY", "     ", "PA3  ", "pa1  "})
        void aFiveByteValueThatIsNotDeclaredDoesNotResolve(final String value) {
            assertThat(encodedByteWidth(value))
                    .as("this case must exercise the index miss, not the width guard")
                    .isEqualTo(ACTION_VALUE_BYTE_WIDTH);
            assertThat(PfKeyTranslator.fromActionValue(value)).isEmpty();
        }

        @Test
        @DisplayName("resolution performs no case folding and no white-space normalisation on either "
                + "side of the comparison")
        void resolutionPerformsNoFoldingOrNormalisation() {
            assertThat(PfKeyTranslator.fromActionValue("ENTER")).contains(KeyAction.ENTER);
            assertThat(PfKeyTranslator.fromActionValue("enter")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("EnTeR")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("  PA1")).isEmpty();
        }

        @Test
        @DisplayName("an absent reference is rejected as a caller defect rather than treated as an "
                + "unrecognised value")
        void anAbsentReferenceIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PfKeyTranslator.fromActionValue(null))
                    .withMessageContaining("actionValue");
        }
    }

    @Nested
    @DisplayName("Published views are immutable and clause ordered")
    class PublishedViews {

        @Test
        @DisplayName("the recognised-identifier view holds all 28 tokens in the clause order of the "
                + "legacy construct, so iterating it reads as an index of that construct")
        void theIdentifierViewIsClauseOrdered() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .hasSize(RECOGNISED_IDENTIFIER_COUNT)
                    .containsExactlyElementsOf(CLAUSE_ORDERED_IDENTIFIERS);
        }

        @Test
        @DisplayName("the action-value view holds all 16 values in the declaration order of the "
                + "level-88 condition names, padding intact")
        void theActionValueViewIsDeclarationOrdered() {
            assertThat(PfKeyTranslator.actionValues())
                    .hasSize(DISTINCT_ACTION_COUNT)
                    .containsExactlyElementsOf(DECLARATION_ORDERED_ACTION_VALUES);
        }

        @Test
        @DisplayName("the two view sizes differ by exactly twelve, which is the number of identifiers "
                + "that contribute no value of their own")
        void theTwoViewSizesDifferByTwelve() {
            assertThat(PfKeyTranslator.recognisedIdentifiers()).hasSize(RECOGNISED_IDENTIFIER_COUNT);
            assertThat(PfKeyTranslator.actionValues()).hasSize(DISTINCT_ACTION_COUNT);
            assertThat(PfKeyTranslator.recognisedIdentifiers().size()
                    - PfKeyTranslator.actionValues().size()).isEqualTo(FUNCTION_KEYS_PER_BANK);
        }

        @Test
        @DisplayName("no caller can widen the recognised identifier set, so the recognised vocabulary "
                + "cannot be extended from outside")
        void theIdentifierViewCannotBeWidened() {
            final Set<String> identifiers = PfKeyTranslator.recognisedIdentifiers();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> identifiers.add("DFHPF25"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> identifiers.remove("DFHENTER"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(identifiers::clear);
        }

        @Test
        @DisplayName("no caller can narrow the action-value set either, so the sixteen values are fixed")
        void theActionValueViewCannotBeNarrowed() {
            final Set<String> values = PfKeyTranslator.actionValues();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.remove("ENTER"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(values::clear);
        }

        @Test
        @DisplayName("both views are stable across calls, so no per-call collection is built; the "
                + "identifier view hands out the very same instance each time")
        void bothViewsAreStableAcrossCalls() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .isSameAs(PfKeyTranslator.recognisedIdentifiers());
            assertThat(PfKeyTranslator.actionValues())
                    .isEqualTo(PfKeyTranslator.actionValues())
                    .containsExactlyElementsOf(PfKeyTranslator.actionValues());
        }

        @Test
        @DisplayName("every token of the recognised view translates, and every value of the action view "
                + "resolves, so neither view can drift away from the translation it describes")
        void neitherViewCanDriftFromTheTranslation() {
            for (final String identifier : PfKeyTranslator.recognisedIdentifiers()) {
                assertThat(PfKeyTranslator.translate(identifier)).isPresent();
            }
            for (final String value : PfKeyTranslator.actionValues()) {
                assertThat(PfKeyTranslator.fromActionValue(value)).isPresent();
            }
        }

        @Test
        @DisplayName("the action-value view is aligned index for index with the declaration order of the "
                + "actions themselves, so the two orderings agree")
        void theActionValueViewIsAlignedWithTheActionOrder() {
            final List<String> published = new ArrayList<>(PfKeyTranslator.actionValues());

            assertThat(DECLARATION_ORDERED_ACTIONS).hasSize(DISTINCT_ACTION_COUNT);
            assertThat(published).hasSize(DISTINCT_ACTION_COUNT);

            for (int index = 0; index < DISTINCT_ACTION_COUNT; index++) {
                assertThat(PfKeyTranslator.fromActionValue(published.get(index)))
                        .contains(DECLARATION_ORDERED_ACTIONS.get(index));
            }
        }
    }

    @Nested
    @DisplayName("Correspondence with the five legacy inclusion sites")
    class InclusionSiteCorrespondence {

        @Test
        @DisplayName("the exit key of the sign-on and menu screens is the third function key, and it "
                + "resolves identically from either bank")
        void theExitKeyResolvesIdenticallyFromEitherBank() {
            assertThat(PfKeyTranslator.translate("DFHPF3")).contains(KeyAction.PFK03);
            assertThat(PfKeyTranslator.translate("DFHPF15")).contains(KeyAction.PFK03);
            assertThat(PfKeyTranslator.actionValue(KeyAction.PFK03)).isEqualTo("PFK03");
        }

        @Test
        @DisplayName("the backward-paging key of the three paginated screens is the seventh function "
                + "key, and it too resolves identically from either bank")
        void theBackwardPagingKeyResolvesIdenticallyFromEitherBank() {
            assertThat(PfKeyTranslator.translate("DFHPF7")).contains(KeyAction.PFK07);
            assertThat(PfKeyTranslator.translate("DFHPF19")).contains(KeyAction.PFK07);
        }

        @Test
        @DisplayName("the forward-paging key is the eighth function key, distinct from the backward one, "
                + "so paging direction is genuinely two different actions")
        void theForwardAndBackwardPagingKeysAreDistinct() {
            final Optional<KeyAction> backward = PfKeyTranslator.translate("DFHPF7");
            final Optional<KeyAction> forward = PfKeyTranslator.translate("DFHPF8");

            assertThat(backward).contains(KeyAction.PFK07);
            assertThat(forward).contains(KeyAction.PFK08);
            assertThat(backward).isNotEqualTo(forward);
        }

        @Test
        @DisplayName("the twelve function-key actions all report themselves as function keys, while the "
                + "four others do not, so a caller can gate function-key handling on the type")
        void onlyTheTwelveFunctionKeyActionsReportAsFunctionKeys() {
            final List<KeyAction> functionKeys = new ArrayList<>();
            final List<KeyAction> others = new ArrayList<>();

            for (final KeyAction action : KeyAction.values()) {
                if (action.isProgramFunctionKey()) {
                    functionKeys.add(action);
                } else {
                    others.add(action);
                }
            }

            assertThat(functionKeys).hasSize(FUNCTION_KEYS_PER_BANK);
            assertThat(others).containsExactly(
                    KeyAction.ENTER, KeyAction.CLEAR, KeyAction.PA1, KeyAction.PA2);
        }
    }
}
