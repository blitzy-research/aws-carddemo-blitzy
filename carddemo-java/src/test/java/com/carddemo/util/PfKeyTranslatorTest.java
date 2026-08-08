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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.carddemo.domain.enums.KeyAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test for {@link PfKeyTranslator}, the single translation point from a raw 3270 terminal
 * attention-key identifier to a {@link KeyAction}, and the one place in this module where a
 * 28-input dispatch collapses onto 16 outcomes.
 *
 * <p><strong>Provenance.</strong> The behaviour asserted here was read out of the legacy estate at
 * checkout commit SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, whose members carry the
 * upstream release stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. Those two strings
 * are recorded as provenance only. Nothing in this file asserts a stamp on any member, because the
 * stamp is not uniform across the estate: most members carry it, a few carry later stamps, the
 * screen-map definitions differ, and a number of members carry none at all.
 *
 * <p><strong>What is under test, and what is deliberately not.</strong> This file exercises the
 * attention-key translation and nothing downstream of it. The consequences of an action - the
 * thank-you text produced on the exit key, the invalid-key text produced on an unmapped key, and
 * the route a caller takes next - belong to the service-layer message catalogue and the
 * service-layer navigation component respectively. No message text and no routing decision is
 * asserted here, because asserting them here would duplicate another component's contract and
 * would couple this test to a boundary the class under test does not own.
 *
 * <p><strong>MANDATE: every expectation in this file is derived independently of the
 * implementation.</strong> Each expected identifier token, each expected action, each expected
 * five-character value and each expected count below was derived by hand from the legacy source's
 * measured metadata and is declared as a literal in the oracle section of this class. No assertion calls a
 * production method to produce its own expected value, no output is snapshotted, and no expected
 * fold is read back out of the class under test. That discipline is what makes the fold assertions
 * meaningful: an implementation that mapped a folded pair consistently but wrongly - say both
 * {@code DFHPF3} and {@code DFHPF15} onto the fourth function-key action - would satisfy a test
 * that merely compared the two results against each other, and would fail every fold assertion
 * here, because each is anchored to a hand-written action instead.
 *
 * <p><strong>Nothing is computed, either.</strong> The twelve-arm fold is asserted from an explicit
 * table of twelve hand-written pairs rather than from a modulo, a parsed key number or a substring
 * arithmetic. A computed fold would reproduce whatever rule the test author assumed and would pass
 * even against a mis-wired arm, which is precisely the defect the table is there to catch.
 *
 * <h2>The legacy oracle</h2>
 *
 * <p><strong>The source is a procedural copybook, not a record layout.</strong>
 * {@code [app/cpy/CSSTRPFY.cpy]} declares exactly two paragraphs: the entry paragraph
 * {@code YYYY-STORE-PFKEY} and the range exit {@code YYYY-STORE-PFKEY-EXIT}, whose body is a bare
 * exit. Between them sits one unconditional multi-clause selection carrying 28 ordered clauses.
 * Those two paragraphs are the member's only two, and they are 2 of the 16 paragraph units
 * contributed by the estate's two procedural copybooks and 2 of its 544 units overall. The
 * invocation range spans exactly one intermediate label - the exit paragraph
 * itself - so it is the trivial paired idiom that collapses to one method with a plain return, and
 * is not one of the three genuinely multi-paragraph ranges in the estate.
 *
 * <p><strong>Five including programs.</strong> The member is pulled in with quoted copy syntax by
 * exactly five online programs: {@code [app/cbl/COACTUPC.cbl]}, {@code [app/cbl/COACTVWC.cbl]},
 * {@code [app/cbl/COCRDLIC.cbl]}, {@code [app/cbl/COCRDSLC.cbl]} and
 * {@code [app/cbl/COCRDUPC.cbl]} - the same five-program family that uniquely also includes
 * {@code [app/cpy/CVCRD01Y.cpy]} and uniquely uses the abend handler. Each performs the range once,
 * so five legacy call sites collapse into the one invocation under test. The other twelve online
 * programs do not include the member and have no attention-key mapping of their own.
 *
 * <p><strong>The absent fallback clause is the load-bearing fact.</strong> A mechanical count over
 * the construct finds <em>zero</em> catch-all clauses. The consequence in the legacy language is
 * behavioural rather than cosmetic: when the incoming identifier matches none of the 28 clauses, no
 * assignment happens at all, and the fixed-width work-area field simply retains whatever value it
 * already held from the previous interaction. There is no sentinel, no unknown value and no error
 * condition. The faithful translation is therefore an empty {@link Optional} - never a synthetic
 * constant, never a {@code null} return and never a raised exception - which is what leaves the
 * caller free to keep its own prior value. Any of those three alternatives would change observable
 * behaviour, so each is asserted against explicitly below.
 *
 * <p><strong>Citation discipline for the work-area copybook.</strong>
 * {@code [app/cpy/CVCRD01Y.cpy]} carries sequence numbers in columns 1 through 6, so the numbers in
 * that member's left margin are sequence numbers and <em>not</em> line numbers. Every citation of it
 * in this file is therefore by member and field name - {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]} -
 * and never by a line number, which would send a reader to the wrong place. The related recorded
 * anomaly is that the sequence number {@code 004800} appears twice in that member, at physical
 * lines 40 and 42; it is noted here because the member is cited, it has no effect on the field
 * layout this test depends on, and nothing below asserts on it.
 *
 * <h2>Divergences this file locks in place</h2>
 *
 * <p>Each of the following is a point where faithful translation and idiomatic Java part company,
 * and each is recorded in the module decision log. This test is the executable record of the
 * faithful choice; it does not edit that log.
 *
 * <ul>
 *   <li>The absent fallback clause becomes an empty {@link Optional} rather than a sentinel
 *       constant, so absence is modelled instead of invented.</li>
 *   <li>The twelve-arm fold is written out explicitly rather than computed, so a mis-wired arm is
 *       visible rather than absorbed by a rule.</li>
 *   <li>The two program-attention values carry two trailing spaces, which are data rather than
 *       incidental formatting and are never removed.</li>
 *   <li>The translator is stateless, whereas the legacy retained a flag between interactions; the
 *       empty result is what hands that retention back to the caller.</li>
 *   <li>Matching is exact, with no case folding and no white-space normalisation, because the
 *       legacy comparison was against fixed compiler-supplied constants.</li>
 *   <li>The work-area copybook is cited by member and field rather than by line, because its left
 *       margin holds sequence numbers.</li>
 * </ul>
 *
 * <p><strong>One note on where the padding rule lives.</strong> The published contract for this
 * module places the fold and the five-character padding in {@link PfKeyTranslator} and says the
 * enumeration only defines the constants. As actually written, {@link KeyAction} also stores its own
 * padded literal and exposes it, alongside the utility-layer accessor
 * {@code PfKeyTranslator.actionValue}. The implementation is authoritative over the summary, so both
 * are exercised here - and both are anchored to the same hand-written literals rather than compared
 * against one another, which would let a shared error pass unnoticed.
 *
 * <p>This is a pure in-process unit test. It starts no application context, opens no database, no
 * queue, no network socket and no file, and it reaches no container. It is also free of reflective
 * enumeration resolution: {@link KeyAction#values()} is a compiler-generated member of the
 * enumeration rather than a reflective lookup, and no other means of enumerating the constants is
 * used, because the module's unsafe-code audit budgets zero reflection.
 */
@DisplayName("PfKeyTranslator: 28 attention-key clauses, 16 actions, one twelve-arm fold")
final class PfKeyTranslatorTest {

    // THE ORACLE.
    //
    // Everything in this section is derived independently from the measured clause and action
    // metadata of [app/cpy/CSSTRPFY.cpy] and [app/cpy/CVCRD01Y.cpy: CCARD-AID] - identifier,
    // ordinal position, action and field width - and is the sole authority for every expectation in
    // this file. Nothing here is read back from the class under test, and nothing here is computed
    // from anything else here.

    /**
     * The width of the action field, from its declaration at
     * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}. Measured in encoded bytes rather than characters,
     * because a fixed-width field reserves a byte count.
     */
    private static final int ORACLE_ACTION_VALUE_BYTE_WIDTH = 5;

    /**
     * The number of clauses in the legacy construct, counted at
     * {@code [app/cpy/CSSTRPFY.cpy]}.
     */
    private static final int ORACLE_CLAUSE_COUNT = 28;

    /**
     * The number of distinct actions those clauses can produce, equal to the count of condition
     * names declared beneath {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}.
     */
    private static final int ORACLE_DISTINCT_ACTION_COUNT = 16;

    private static final int ORACLE_FOLDED_CLAUSE_COUNT = 12;

    private static final String AID_ENTER = "DFHENTER";

    private static final String AID_CLEAR = "DFHCLEAR";

    private static final String AID_PA1 = "DFHPA1";

    private static final String AID_PA2 = "DFHPA2";

    private static final String AID_PF7 = "DFHPF7";

    private static final String VALUE_ENTER = "ENTER";

    private static final String VALUE_CLEAR = "CLEAR";

    /**
     * The action value the first program-attention clause stores: the three-character mnemonic
     * {@code PA1} followed by <strong>two trailing spaces</strong> that pad it out to the
     * five-character field. The padding is part of the value and is never removed.
     */
    private static final String VALUE_PA1 = "PA1  ";

    /**
     * The action value the second program-attention clause stores: {@code PA2} followed by
     * <strong>two trailing spaces</strong>, on the same reasoning as the first.
     */
    private static final String VALUE_PA2 = "PA2  ";

    /**
     * The shortened form of the first program-attention value, which the legacy field cannot hold
     * because the field is fixed width. Present only so that assertions can prove it is
     * <em>rejected</em>; it is never an expected value.
     */
    private static final String SHORTENED_VALUE_PA1 = "PA1";

    private static final String SHORTENED_VALUE_PA2 = "PA2";

    private static final String UNRECOGNISED_AID = "DFHPF25";

    /**
     * One clause of the legacy construct: the identifier it compares against and the action it
     * sets. Twenty-eight of these, in source order, are the primary oracle.
     *
     * @param identifier the attention-key identifier the clause compares against
     * @param action     the action the clause sets
     */
    private record Clause(String identifier, KeyAction action) {
    }

    /**
     * One folded pair: an upper-bank program-function identifier, the lower-bank identifier twelve
     * clauses earlier that it folds onto, and the single action <em>both</em> must produce.
     *
     * <p>Naming the action explicitly is the point of this record. It is what stops a fold
     * assertion from degenerating into a comparison of two production results, which would pass for
     * a pair that agreed with each other and disagreed with the source.
     *
     * @param lowerBankIdentifier the identifier from clauses 5 through 16
     * @param upperBankIdentifier the identifier from clauses 17 through 28 that folds onto it
     * @param action              the action both identifiers must produce
     */
    private record FoldPair(String lowerBankIdentifier, String upperBankIdentifier,
                            KeyAction action) {
    }

    /**
     * One action and the exact five-character value the legacy work area holds for it.
     *
     * @param action the action
     * @param value  the value verbatim, including any trailing space padding
     */
    private record ActionValue(KeyAction action, String value) {
    }

    /**
     * The 28 clauses of {@code [app/cpy/CSSTRPFY.cpy]} in exact source order, one row per clause.
     *
     * <p>Rows 1 through 16 each set an action of their own. Rows 17 through 28 are the fold: every
     * one repeats the action its counterpart twelve rows earlier sets, so keys 13 through 24 are
     * not distinct actions on this system. The identifier spellings are exactly as the source
     * writes them - single-digit key numbers carry no leading zero and no separator appears
     * anywhere - because matching is exact and a differently spelled token is simply a different
     * token.
     */
    private static final List<Clause> ORACLE_CLAUSES = List.of(
            new Clause(AID_ENTER, KeyAction.ENTER),      // clause 1  [app/cpy/CSSTRPFY.cpy:L22]
            new Clause(AID_CLEAR, KeyAction.CLEAR),      // clause 2  [app/cpy/CSSTRPFY.cpy:L24]
            new Clause(AID_PA1, KeyAction.PA1),          // clause 3  [app/cpy/CSSTRPFY.cpy:L26]
            new Clause(AID_PA2, KeyAction.PA2),          // clause 4  [app/cpy/CSSTRPFY.cpy:L28]
            new Clause("DFHPF1", KeyAction.PFK01),       // clause 5  [app/cpy/CSSTRPFY.cpy:L30]
            new Clause("DFHPF2", KeyAction.PFK02),       // clause 6  [app/cpy/CSSTRPFY.cpy:L32]
            new Clause("DFHPF3", KeyAction.PFK03),       // clause 7  [app/cpy/CSSTRPFY.cpy:L34]
            new Clause("DFHPF4", KeyAction.PFK04),       // clause 8  [app/cpy/CSSTRPFY.cpy:L36]
            new Clause("DFHPF5", KeyAction.PFK05),       // clause 9  [app/cpy/CSSTRPFY.cpy:L38]
            new Clause("DFHPF6", KeyAction.PFK06),       // clause 10 [app/cpy/CSSTRPFY.cpy:L40]
            new Clause(AID_PF7, KeyAction.PFK07),        // clause 11 [app/cpy/CSSTRPFY.cpy:L42]
            new Clause("DFHPF8", KeyAction.PFK08),       // clause 12 [app/cpy/CSSTRPFY.cpy:L44]
            new Clause("DFHPF9", KeyAction.PFK09),       // clause 13 [app/cpy/CSSTRPFY.cpy:L46]
            new Clause("DFHPF10", KeyAction.PFK10),      // clause 14 [app/cpy/CSSTRPFY.cpy:L48]
            new Clause("DFHPF11", KeyAction.PFK11),      // clause 15 [app/cpy/CSSTRPFY.cpy:L50]
            new Clause("DFHPF12", KeyAction.PFK12),      // clause 16 [app/cpy/CSSTRPFY.cpy:L52]
            new Clause("DFHPF13", KeyAction.PFK01),      // clause 17 [app/cpy/CSSTRPFY.cpy:L54] fold
            new Clause("DFHPF14", KeyAction.PFK02),      // clause 18 [app/cpy/CSSTRPFY.cpy:L56] fold
            new Clause("DFHPF15", KeyAction.PFK03),      // clause 19 [app/cpy/CSSTRPFY.cpy:L58] fold
            new Clause("DFHPF16", KeyAction.PFK04),      // clause 20 [app/cpy/CSSTRPFY.cpy:L60] fold
            new Clause("DFHPF17", KeyAction.PFK05),      // clause 21 [app/cpy/CSSTRPFY.cpy:L62] fold
            new Clause("DFHPF18", KeyAction.PFK06),      // clause 22 [app/cpy/CSSTRPFY.cpy:L64] fold
            new Clause("DFHPF19", KeyAction.PFK07),      // clause 23 [app/cpy/CSSTRPFY.cpy:L66] fold
            new Clause("DFHPF20", KeyAction.PFK08),      // clause 24 [app/cpy/CSSTRPFY.cpy:L68] fold
            new Clause("DFHPF21", KeyAction.PFK09),      // clause 25 [app/cpy/CSSTRPFY.cpy:L70] fold
            new Clause("DFHPF22", KeyAction.PFK10),      // clause 26 [app/cpy/CSSTRPFY.cpy:L72] fold
            new Clause("DFHPF23", KeyAction.PFK11),      // clause 27 [app/cpy/CSSTRPFY.cpy:L74] fold
            new Clause("DFHPF24", KeyAction.PFK12));     // clause 28 [app/cpy/CSSTRPFY.cpy:L76] fold

    /**
     * The twelve folded pairs, written out one per upper-bank key.
     *
     * <p>This is a second, independent hand-written enumeration of the same twelve clauses that rows
     * 17 through 28 of {@link #ORACLE_CLAUSES} describe, and the two are cross-checked against each
     * other below. Deriving the fold twice is deliberate: a single derivation that had drifted would
     * be believed by every assertion that used it.
     */
    private static final List<FoldPair> ORACLE_FOLD_PAIRS = List.of(
            new FoldPair("DFHPF1", "DFHPF13", KeyAction.PFK01),
            new FoldPair("DFHPF2", "DFHPF14", KeyAction.PFK02),
            new FoldPair("DFHPF3", "DFHPF15", KeyAction.PFK03),
            new FoldPair("DFHPF4", "DFHPF16", KeyAction.PFK04),
            new FoldPair("DFHPF5", "DFHPF17", KeyAction.PFK05),
            new FoldPair("DFHPF6", "DFHPF18", KeyAction.PFK06),
            new FoldPair(AID_PF7, "DFHPF19", KeyAction.PFK07),
            new FoldPair("DFHPF8", "DFHPF20", KeyAction.PFK08),
            new FoldPair("DFHPF9", "DFHPF21", KeyAction.PFK09),
            new FoldPair("DFHPF10", "DFHPF22", KeyAction.PFK10),
            new FoldPair("DFHPF11", "DFHPF23", KeyAction.PFK11),
            new FoldPair("DFHPF12", "DFHPF24", KeyAction.PFK12));

    /**
     * The 16 action values in the declaration order of the condition names beneath
     * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}, each stated at its full declared width.
     *
     * <p>Every value is exactly five characters. Two of them reach that width only because of
     * trailing spaces; the twelve program-function values reach it because their key number is
     * always written as two digits.
     */
    private static final List<ActionValue> ORACLE_ACTION_VALUES = List.of(
            new ActionValue(KeyAction.ENTER, VALUE_ENTER),
            new ActionValue(KeyAction.CLEAR, VALUE_CLEAR),
            new ActionValue(KeyAction.PA1, VALUE_PA1),
            new ActionValue(KeyAction.PA2, VALUE_PA2),
            new ActionValue(KeyAction.PFK01, "PFK01"),
            new ActionValue(KeyAction.PFK02, "PFK02"),
            new ActionValue(KeyAction.PFK03, "PFK03"),
            new ActionValue(KeyAction.PFK04, "PFK04"),
            new ActionValue(KeyAction.PFK05, "PFK05"),
            new ActionValue(KeyAction.PFK06, "PFK06"),
            new ActionValue(KeyAction.PFK07, "PFK07"),
            new ActionValue(KeyAction.PFK08, "PFK08"),
            new ActionValue(KeyAction.PFK09, "PFK09"),
            new ActionValue(KeyAction.PFK10, "PFK10"),
            new ActionValue(KeyAction.PFK11, "PFK11"),
            new ActionValue(KeyAction.PFK12, "PFK12"));

    /**
     * The names of the 16 constants, in the declaration order of the condition names beneath
     * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}, with the copybook's field-name prefix dropped.
     */
    private static final List<String> ORACLE_CONSTANT_NAMES = List.of(
            "ENTER", "CLEAR", "PA1", "PA2",
            "PFK01", "PFK02", "PFK03", "PFK04", "PFK05", "PFK06",
            "PFK07", "PFK08", "PFK09", "PFK10", "PFK11", "PFK12");

    /**
     * Names a sentinel constant would plausibly be given if one were ever added.
     *
     * <p>These are the names that must <strong>not</strong> exist on the enumeration. They appear
     * here only as strings to be ruled out; none of them is referenced anywhere as a constant of
     * {@link KeyAction}, and none exists to be referenced. A constant with any of these names would
     * manufacture a state the legacy construct cannot produce, because that construct has no
     * catch-all clause and therefore no notion of an unrecognised key. Note that this is a
     * different thing from the {@code default} arm inside a string switch, which is required for
     * totality and is not a sentinel.
     */
    private static final List<String> FORBIDDEN_SENTINEL_NAMES = List.of(
            "UNKNOWN", "NONE", "DEFAULT", "OTHER", "INVALID", "UNMAPPED");

    /**
     * Identifiers the legacy construct does not recognise, each chosen to close off one way a
     * translation might accidentally widen the contract.
     *
     * <ul>
     *   <li>a lower-case spelling, which would resolve if case were folded;</li>
     *   <li>a trailing-space and a leading-space spelling, either of which would resolve if
     *       surrounding white space were removed;</li>
     *   <li>a zero-numbered key, below the range the source names;</li>
     *   <li>a twenty-fifth key, above the range the source names, which would resolve if the fold
     *       were computed by arithmetic rather than written out;</li>
     *   <li>a recognised token with a trailing character, which would resolve under a prefix
     *       match;</li>
     *   <li>the bare mnemonic and the bare key number, either of which would resolve under an
     *       abbreviation or alias rule;</li>
     *   <li>the empty string.</li>
     * </ul>
     */
    private static final List<String> ORACLE_UNRECOGNISED_AIDS = List.of(
            "dfhpf1",
            "DFHPF1 ",
            " DFHPF1",
            "DFHPF0",
            UNRECOGNISED_AID,
            "DFHPF13X",
            "PF1",
            "1",
            "");

    /**
     * Measures a value the way the legacy fixed-width field does: in encoded bytes, in the
     * single-byte character set those fields are read and written in, rather than in characters.
     *
     * <p>The character set is named rather than left to the platform, because a default character
     * set is an environment property and would make the measurement depend on where the test runs.
     *
     * @param value the value to measure
     * @return the number of bytes the value occupies when encoded
     */
    private static int encodedByteWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    @Nested
    @DisplayName("the hand-transcribed oracle itself")
    final class OracleIntegrity {

        @Test
        @DisplayName("holds one row per clause of the legacy construct, all identifiers distinct")
        void holdsOneRowPerClause() {
            final List<String> identifiers = new ArrayList<>();
            for (final Clause clause : ORACLE_CLAUSES) {
                identifiers.add(clause.identifier());
            }

            assertThat(ORACLE_CLAUSES).hasSize(ORACLE_CLAUSE_COUNT);
            assertThat(identifiers).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("holds one fold pair per upper-bank key and one value per condition name")
        void holdsOneFoldPairPerUpperBankKey() {
            assertThat(ORACLE_FOLD_PAIRS).hasSize(ORACLE_FOLDED_CLAUSE_COUNT);
            assertThat(ORACLE_ACTION_VALUES).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
            assertThat(ORACLE_CONSTANT_NAMES).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
        }

        @Test
        @DisplayName("assigns only the sixteen declared actions across the twenty-eight clauses")
        void assignsOnlySixteenDistinctActionsAcrossTheClauses() {
            final Set<KeyAction> assigned = new LinkedHashSet<>();
            for (final Clause clause : ORACLE_CLAUSES) {
                assigned.add(clause.action());
            }

            assertThat(assigned).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
            assertThat(ORACLE_CLAUSE_COUNT - ORACLE_DISTINCT_ACTION_COUNT)
                    .as("the gap between clauses and actions is exactly the fold")
                    .isEqualTo(ORACLE_FOLDED_CLAUSE_COUNT);
        }

        @Test
        @DisplayName("agrees with the separately transcribed fold table, row for row")
        void agreesWithTheSeparatelyTranscribedFoldTable() {
            final List<Clause> lowerBank = ORACLE_CLAUSES.subList(4, 16);
            final List<Clause> upperBank = ORACLE_CLAUSES.subList(16, ORACLE_CLAUSE_COUNT);

            for (int row = 0; row < ORACLE_FOLDED_CLAUSE_COUNT; row++) {
                final FoldPair pair = ORACLE_FOLD_PAIRS.get(row);

                assertThat(lowerBank.get(row).identifier()).isEqualTo(pair.lowerBankIdentifier());
                assertThat(lowerBank.get(row).action()).isEqualTo(pair.action());
                assertThat(upperBank.get(row).identifier()).isEqualTo(pair.upperBankIdentifier());
                assertThat(upperBank.get(row).action()).isEqualTo(pair.action());
            }
        }

        @Test
        @DisplayName("names no unrecognised identifier among its clauses, so the negatives are real")
        void namesNoUnrecognisedIdentifierAmongItsClauses() {
            final List<String> recognised = new ArrayList<>();
            for (final Clause clause : ORACLE_CLAUSES) {
                recognised.add(clause.identifier());
            }

            assertThat(recognised).doesNotContainAnyElementsOf(ORACLE_UNRECOGNISED_AIDS);
        }

        @Test
        @DisplayName("carries only five-character action values, padding included")
        void carriesOnlyFiveCharacterActionValues() {
            for (final ActionValue actionValue : ORACLE_ACTION_VALUES) {
                assertThat(encodedByteWidth(actionValue.value()))
                        .as("transcribed width for %s", actionValue.action())
                        .isEqualTo(ORACLE_ACTION_VALUE_BYTE_WIDTH);
            }
        }
    }

    @Nested
    @DisplayName("translate: all twenty-eight clauses, one assertion per clause")
    final class ClauseByClauseTranslation {

        /**
         * Supplies the 28 clauses as explicit identifier and action pairs, in source order, so the
         * parameter index of each invocation is also its clause number.
         *
         * @return one argument pair per clause of the legacy construct
         */
        static Stream<Arguments> clauseTable() {
            return ORACLE_CLAUSES.stream()
                    .map(clause -> Arguments.of(clause.identifier(), clause.action()));
        }

        @ParameterizedTest(name = "[{index}] clause {index}: \"{0}\" sets {1}")
        @MethodSource("clauseTable")
        @DisplayName("resolves each clause to the action the source sets, in source order")
        void resolvesEachClauseToItsAction(final String identifier, final KeyAction expected) {
            assertThat(PfKeyTranslator.translate(identifier))
                    .as("the clause naming %s", identifier)
                    .contains(expected);
        }

        @Test
        @DisplayName("resolves the ENTER clause, the first of the four non-function-key clauses")
        void resolvesTheEnterClause() {
            assertThat(PfKeyTranslator.translate(AID_ENTER)).contains(KeyAction.ENTER);
        }

        @Test
        @DisplayName("resolves the CLEAR clause")
        void resolvesTheClearClause() {
            assertThat(PfKeyTranslator.translate(AID_CLEAR)).contains(KeyAction.CLEAR);
        }

        @Test
        @DisplayName("resolves the first program-attention clause")
        void resolvesTheFirstProgramAttentionClause() {
            assertThat(PfKeyTranslator.translate(AID_PA1)).contains(KeyAction.PA1);
        }

        @Test
        @DisplayName("resolves the second program-attention clause")
        void resolvesTheSecondProgramAttentionClause() {
            assertThat(PfKeyTranslator.translate(AID_PA2)).contains(KeyAction.PA2);
        }

        @Test
        @DisplayName("collapses the twenty-eight clauses onto exactly sixteen distinct actions")
        void collapsesTheClausesOntoSixteenDistinctActions() {
            final Set<KeyAction> produced = new LinkedHashSet<>();
            for (final Clause clause : ORACLE_CLAUSES) {
                produced.add(PfKeyTranslator.translate(clause.identifier()).orElseThrow());
            }

            assertThat(produced).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
        }

        @Test
        @DisplayName("reaches every declared action, so no clause of the construct is unreachable")
        void reachesEveryDeclaredAction() {
            final Set<KeyAction> produced = new LinkedHashSet<>();
            for (final Clause clause : ORACLE_CLAUSES) {
                produced.add(PfKeyTranslator.translate(clause.identifier()).orElseThrow());
            }

            assertThat(produced).containsExactlyInAnyOrder(KeyAction.ENTER, KeyAction.CLEAR,
                    KeyAction.PA1, KeyAction.PA2, KeyAction.PFK01, KeyAction.PFK02,
                    KeyAction.PFK03, KeyAction.PFK04, KeyAction.PFK05, KeyAction.PFK06,
                    KeyAction.PFK07, KeyAction.PFK08, KeyAction.PFK09, KeyAction.PFK10,
                    KeyAction.PFK11, KeyAction.PFK12);
        }
    }

    @Nested
    @DisplayName("translate: the twelve-arm fold of keys 13 through 24")
    final class TwelveArmFold {

        /**
         * Supplies the twelve folded pairs as explicit triples of lower-bank identifier, upper-bank
         * identifier and the single action both must produce.
         *
         * @return one argument triple per folded pair
         */
        static Stream<Arguments> foldTable() {
            return ORACLE_FOLD_PAIRS.stream()
                    .map(pair -> Arguments.of(pair.lowerBankIdentifier(),
                            pair.upperBankIdentifier(), pair.action()));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" and \"{1}\" both set {2}")
        @MethodSource("foldTable")
        @DisplayName("folds each upper-bank key onto its lower-bank twin, against a written action")
        void foldsEachUpperBankKeyOntoItsLowerBankTwin(final String lowerBankIdentifier,
                final String upperBankIdentifier, final KeyAction expected) {
            final Optional<KeyAction> lower = PfKeyTranslator.translate(lowerBankIdentifier);
            final Optional<KeyAction> upper = PfKeyTranslator.translate(upperBankIdentifier);

            // Each side is checked against the transcribed action first. Only then is the pair
            // compared, so a pair that agreed with each other but disagreed with the source has
            // already failed by this point rather than passing on its own consistency.
            assertThat(lower).as("the lower-bank clause naming %s", lowerBankIdentifier)
                    .contains(expected);
            assertThat(upper).as("the upper-bank clause naming %s", upperBankIdentifier)
                    .contains(expected);
            assertThat(upper).as("the fold makes the pair indistinguishable").isEqualTo(lower);
        }

        @Test
        @DisplayName("adds no action of its own, so keys 13 to 24 add no behaviour")
        void addsNoActionOfItsOwn() {
            final Set<KeyAction> lowerBankActions = new LinkedHashSet<>();
            final Set<KeyAction> upperBankActions = new LinkedHashSet<>();
            for (final FoldPair pair : ORACLE_FOLD_PAIRS) {
                lowerBankActions.add(
                        PfKeyTranslator.translate(pair.lowerBankIdentifier()).orElseThrow());
                upperBankActions.add(
                        PfKeyTranslator.translate(pair.upperBankIdentifier()).orElseThrow());
            }

            assertThat(upperBankActions).containsExactlyElementsOf(lowerBankActions);
            assertThat(upperBankActions).hasSize(ORACLE_FOLDED_CLAUSE_COUNT);
        }

        @Test
        @DisplayName("stops at the twenty-fourth key, because the source names no clause beyond it")
        void stopsAtTheTwentyFourthKey() {
            assertThat(PfKeyTranslator.translate(UNRECOGNISED_AID))
                    .as("a twenty-fifth key would resolve only under a computed fold")
                    .isEmpty();
            assertThat(PfKeyTranslator.translate("DFHPF26")).isEmpty();
            assertThat(PfKeyTranslator.translate("DFHPF36")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the sixteen constants behind the translation")
    final class ConstantCardinality {

        @Test
        @DisplayName("exactly sixteen constants exist, one per condition name on the action field")
        void exactlySixteenConstantsExist() {
            assertThat(KeyAction.values()).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
        }

        @Test
        @DisplayName("the constants are named and ordered as the condition names are")
        void theConstantsAreNamedAndOrderedAsTheConditionNamesAre() {
            final List<String> names = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                names.add(action.name());
            }

            assertThat(names).containsExactlyElementsOf(ORACLE_CONSTANT_NAMES);
        }

        @Test
        @DisplayName("no constant is a sentinel, because the construct has no catch-all clause")
        void noConstantIsASentinel() {
            final List<String> names = new ArrayList<>();
            for (final KeyAction action : KeyAction.values()) {
                names.add(action.name());
            }

            assertThat(names)
                    .as("a sentinel constant would manufacture a state the legacy cannot produce")
                    .doesNotContainAnyElementsOf(FORBIDDEN_SENTINEL_NAMES);
        }
    }

    @Nested
    @DisplayName("actionValue: the five-byte external form, padding intact")
    final class ActionValueWidthAndPadding {

        /**
         * Supplies the 16 actions paired with their transcribed five-character values, in the
         * declaration order of the condition names.
         *
         * @return one argument pair per declared action
         */
        static Stream<Arguments> actionValueTable() {
            return ORACLE_ACTION_VALUES.stream()
                    .map(actionValue -> Arguments.of(actionValue.action(), actionValue.value()));
        }

        @ParameterizedTest(name = "[{index}] {0} stores \"{1}\", five encoded bytes")
        @MethodSource("actionValueTable")
        @DisplayName("returns the transcribed value at exactly five encoded bytes, per action")
        void returnsTheTranscribedValueAtFiveEncodedBytes(final KeyAction action,
                final String expected) {
            final String produced = PfKeyTranslator.actionValue(action);

            assertThat(produced).as("the value stored for %s", action).isEqualTo(expected);
            assertThat(encodedByteWidth(produced))
                    .as("the field reserves bytes, not characters, for %s", action)
                    .isEqualTo(ORACLE_ACTION_VALUE_BYTE_WIDTH);
        }

        @Test
        @DisplayName("the published width constant matches the field's own declared width")
        void thePublishedWidthConstantMatchesTheFieldWidth() {
            assertThat(PfKeyTranslator.ACTION_VALUE_BYTE_WIDTH)
                    .isEqualTo(ORACLE_ACTION_VALUE_BYTE_WIDTH);
        }

        @Test
        @DisplayName("the first program-attention value keeps its two trailing spaces")
        void theFirstProgramAttentionValueKeepsItsTrailingSpaces() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA1))
                    .isEqualTo(VALUE_PA1)
                    .isNotEqualTo(SHORTENED_VALUE_PA1)
                    .endsWith("  ");
            assertThat(encodedByteWidth(PfKeyTranslator.actionValue(KeyAction.PA1)))
                    .isEqualTo(ORACLE_ACTION_VALUE_BYTE_WIDTH);
        }

        @Test
        @DisplayName("the second program-attention value keeps its two trailing spaces")
        void theSecondProgramAttentionValueKeepsItsTrailingSpaces() {
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA2))
                    .isEqualTo(VALUE_PA2)
                    .isNotEqualTo(SHORTENED_VALUE_PA2)
                    .endsWith("  ");
            assertThat(encodedByteWidth(PfKeyTranslator.actionValue(KeyAction.PA2)))
                    .isEqualTo(ORACLE_ACTION_VALUE_BYTE_WIDTH);
        }

        @Test
        @DisplayName("the padded values are the same on both sides of the layer boundary")
        void thePaddedValuesAreTheSameOnBothSidesOfTheLayerBoundary() {
            // Both the utility-layer accessor and the enumeration's own accessor are checked
            // against the same transcribed literal rather than against each other, so a shared
            // error in the two implementations cannot pass unnoticed.
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA1)).isEqualTo(VALUE_PA1);
            assertThat(KeyAction.PA1.getAid()).isEqualTo(VALUE_PA1);
            assertThat(PfKeyTranslator.actionValue(KeyAction.PA2)).isEqualTo(VALUE_PA2);
            assertThat(KeyAction.PA2.getAid()).isEqualTo(VALUE_PA2);
        }

        @Test
        @DisplayName("no two actions share a value, so the reverse direction cannot collide")
        void noTwoActionsShareAValue() {
            final Set<String> values = new LinkedHashSet<>();
            for (final KeyAction action : KeyAction.values()) {
                values.add(PfKeyTranslator.actionValue(action));
            }

            assertThat(values).hasSize(ORACLE_DISTINCT_ACTION_COUNT);
        }

        @Test
        @DisplayName("an absent action is a caller defect, because every action has a value")
        void anAbsentActionIsACallerDefect() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PfKeyTranslator.actionValue(null))
                    .withMessageContaining("keyAction");
        }
    }

    @Nested
    @DisplayName("translate: the absent fallback clause, modelled rather than invented")
    final class UnrecognisedIdentifiers {

        /**
         * Supplies the identifiers the legacy construct does not recognise.
         *
         * @return one argument per unrecognised identifier
         */
        static Stream<Arguments> unrecognisedTable() {
            return ORACLE_UNRECOGNISED_AIDS.stream().map(Arguments::of);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" matches no clause")
        @MethodSource("unrecognisedTable")
        @DisplayName("yields an empty result for every identifier the source does not name")
        void yieldsAnEmptyResultForEveryUnnamedIdentifier(final String identifier) {
            assertThat(PfKeyTranslator.translate(identifier))
                    .as("no clause names %s, so no assignment happens", identifier)
                    .isEmpty();
        }

        @Test
        @DisplayName("matching is exact: neither case nor surrounding blanks are normalised")
        void matchingIsExact() {
            assertThat(PfKeyTranslator.translate("dfhenter"))
                    .as("case is not folded")
                    .isEmpty();
            assertThat(PfKeyTranslator.translate("DFHENTER "))
                    .as("a trailing blank is not removed")
                    .isEmpty();
            assertThat(PfKeyTranslator.translate(" DFHENTER"))
                    .as("a leading blank is not removed")
                    .isEmpty();
            assertThat(PfKeyTranslator.translate("DFHPF01"))
                    .as("the source writes no leading zero, so this is a different token")
                    .isEmpty();
        }

        @Test
        @DisplayName("returns an empty result rather than raising, because there is no error state")
        void returnsAnEmptyResultRatherThanRaising() {
            assertThatNoException()
                    .isThrownBy(() -> PfKeyTranslator.translate(UNRECOGNISED_AID));
            assertThatNoException().isThrownBy(() -> PfKeyTranslator.translate(""));
        }

        @Test
        @DisplayName("returns an empty result rather than an absent reference")
        void returnsAnEmptyResultRatherThanAnAbsentReference() {
            final Optional<KeyAction> resolved = PfKeyTranslator.translate(UNRECOGNISED_AID);

            assertThat(resolved)
                    .as("an absent reference would force every caller to guard before reading")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("an absent reference is a caller defect, distinct from an unrecognised key")
        void anAbsentReferenceIsACallerDefect() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PfKeyTranslator.translate(null))
                    .withMessageContaining("attentionKeyIdentifier");
        }
    }

    @Nested
    @DisplayName("translate: statelessness, in place of the legacy retained flag")
    final class Statelessness {

        @Test
        @DisplayName("an unrecognised key between two recognised ones changes neither of them")
        void anUnrecognisedKeyBetweenTwoRecognisedOnesChangesNeither() {
            final Optional<KeyAction> first = PfKeyTranslator.translate(AID_PF7);
            final Optional<KeyAction> second = PfKeyTranslator.translate(UNRECOGNISED_AID);
            final Optional<KeyAction> third = PfKeyTranslator.translate(AID_PF7);

            // The first and third results are each checked against the transcribed action, so the
            // sequence proves the translator holds no state rather than merely proving it is
            // self-consistent.
            assertThat(first).contains(KeyAction.PFK07);
            assertThat(second)
                    .as("the unrecognised key produces nothing of its own")
                    .isEmpty();
            assertThat(third).contains(KeyAction.PFK07);
            assertThat(third).as("the third call repeats the first exactly").isEqualTo(first);
        }

        @Test
        @DisplayName("an unrecognised key does not inherit the action of the call before it")
        void anUnrecognisedKeyDoesNotInheritTheActionBeforeIt() {
            assertThat(PfKeyTranslator.translate(AID_ENTER)).contains(KeyAction.ENTER);

            assertThat(PfKeyTranslator.translate(UNRECOGNISED_AID))
                    .as("a cached previous result would surface here as the ENTER action")
                    .isEmpty();
        }

        @Test
        @DisplayName("successive recognised keys each yield their own action, in any order")
        void successiveRecognisedKeysEachYieldTheirOwnAction() {
            assertThat(PfKeyTranslator.translate("DFHPF12")).contains(KeyAction.PFK12);
            assertThat(PfKeyTranslator.translate(AID_PA1)).contains(KeyAction.PA1);
            assertThat(PfKeyTranslator.translate("DFHPF13")).contains(KeyAction.PFK01);
            assertThat(PfKeyTranslator.translate(AID_CLEAR)).contains(KeyAction.CLEAR);
            assertThat(PfKeyTranslator.translate("DFHPF12")).contains(KeyAction.PFK12);
        }
    }

    @Nested
    @DisplayName("fromActionValue: reading a stored value back, on exact bytes only")
    final class ReverseResolution {

        /**
         * Supplies the 16 transcribed five-character values paired with the action each denotes.
         *
         * @return one argument pair per declared value
         */
        static Stream<Arguments> reverseTable() {
            return ORACLE_ACTION_VALUES.stream()
                    .map(actionValue -> Arguments.of(actionValue.value(), actionValue.action()));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" resolves to {1}")
        @MethodSource("reverseTable")
        @DisplayName("resolves each transcribed value to its action, padding included")
        void resolvesEachTranscribedValueToItsAction(final String value, final KeyAction expected) {
            assertThat(PfKeyTranslator.fromActionValue(value))
                    .as("the value %s", value)
                    .contains(expected);
        }

        @Test
        @DisplayName("a shortened program-attention value does not resolve, because padding is data")
        void aShortenedProgramAttentionValueDoesNotResolve() {
            assertThat(PfKeyTranslator.fromActionValue(SHORTENED_VALUE_PA1))
                    .as("three characters cannot have come from a five-byte field")
                    .isEmpty();
            assertThat(PfKeyTranslator.fromActionValue(SHORTENED_VALUE_PA2)).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("PA1 "))
                    .as("four characters cannot have come from it either")
                    .isEmpty();
            assertThat(PfKeyTranslator.fromActionValue(VALUE_PA1))
                    .as("only the padded form resolves")
                    .contains(KeyAction.PA1);
        }

        @Test
        @DisplayName("a five-character value that denotes no action yields an empty result")
        void aFiveCharacterValueThatDenotesNoActionYieldsAnEmptyResult() {
            assertThat(PfKeyTranslator.fromActionValue("PFK13"))
                    .as("the fold gives keys 13 to 24 no value of their own")
                    .isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("ZZZZZ")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("     ")).isEmpty();
        }

        @Test
        @DisplayName("a value of any other width is rejected before the lookup is attempted")
        void aValueOfAnyOtherWidthIsRejected() {
            assertThat(PfKeyTranslator.fromActionValue("")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("ENTE")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("ENTER ")).isEmpty();
        }

        @Test
        @DisplayName("no case folding is applied on either side of the comparison")
        void noCaseFoldingIsApplied() {
            assertThat(PfKeyTranslator.fromActionValue("enter")).isEmpty();
            assertThat(PfKeyTranslator.fromActionValue("pfk01")).isEmpty();
        }

        @Test
        @DisplayName("an absent reference is a caller defect, distinct from an unrecognised value")
        void anAbsentReferenceIsACallerDefect() {
            assertThatNullPointerException()
                    .isThrownBy(() -> PfKeyTranslator.fromActionValue(null))
                    .withMessageContaining("actionValue");
        }
    }

    @Nested
    @DisplayName("the published views: clause ordered and unmodifiable")
    final class PublishedViews {

        @Test
        @DisplayName("the identifier view holds the twenty-eight tokens in clause order")
        void theIdentifierViewHoldsTheTwentyEightTokensInClauseOrder() {
            final List<String> expected = new ArrayList<>();
            for (final Clause clause : ORACLE_CLAUSES) {
                expected.add(clause.identifier());
            }

            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .hasSize(ORACLE_CLAUSE_COUNT)
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("the value view holds the sixteen values in declaration order, padding intact")
        void theValueViewHoldsTheSixteenValuesInDeclarationOrder() {
            final List<String> expected = new ArrayList<>();
            for (final ActionValue actionValue : ORACLE_ACTION_VALUES) {
                expected.add(actionValue.value());
            }

            assertThat(PfKeyTranslator.actionValues())
                    .hasSize(ORACLE_DISTINCT_ACTION_COUNT)
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("the two view sizes differ by exactly the twelve folded clauses")
        void theTwoViewSizesDifferByTheFoldedClauses() {
            assertThat(PfKeyTranslator.recognisedIdentifiers().size()
                    - PfKeyTranslator.actionValues().size())
                    .isEqualTo(ORACLE_FOLDED_CLAUSE_COUNT);
        }

        @Test
        @DisplayName("no caller can widen, narrow or empty the identifier view")
        void noCallerCanModifyTheIdentifierView() {
            // Both published views are sets rather than lists, so there is no positional mutator to
            // probe; add, remove and clear are the whole mutation surface.
            final Set<String> identifiers = PfKeyTranslator.recognisedIdentifiers();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> identifiers.add("DFHPF99"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> identifiers.remove(AID_ENTER));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(identifiers::clear);
        }

        @Test
        @DisplayName("no caller can widen, narrow or empty the value view")
        void noCallerCanModifyTheValueView() {
            final Set<String> values = PfKeyTranslator.actionValues();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.add("PFK13"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.remove(VALUE_ENTER));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(values::clear);
        }

        @Test
        @DisplayName("the same view instances are published every call, so nothing is rebuilt")
        void theSameViewInstancesArePublishedEveryCall() {
            assertThat(PfKeyTranslator.recognisedIdentifiers())
                    .isSameAs(PfKeyTranslator.recognisedIdentifiers());
            assertThat(PfKeyTranslator.actionValues())
                    .isSameAs(PfKeyTranslator.actionValues());
        }
    }
}
