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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.carddemo.domain.enums.KeyAction;

/**
 * The single owner of the translation from a raw 3270 terminal attention-key identifier to a
 * {@link KeyAction}. This class is the Java realisation of the procedural copybook member
 * {@code CSSTRPFY}, and it is the only place in the estate's Java translation where the
 * attention-key mapping is decided.
 *
 * <p><strong>The source is a procedural copybook, not a record layout.</strong>
 * {@code [app/cpy/CSSTRPFY.cpy]} contributes {@code PROCEDURE DIVISION} paragraphs rather than
 * data. It is 85 physical lines long: the Apache-2.0 licence block occupies its
 * {@code L1}&ndash;{@code L16}, the entry paragraph {@code YYYY-STORE-PFKEY} is declared at
 * {@code [app/cpy/CSSTRPFY.cpy:L17]}, a single {@code EVALUATE TRUE} construct opens at
 * {@code [app/cpy/CSSTRPFY.cpy:L21]} and closes at {@code [app/cpy/CSSTRPFY.cpy:L78]} with its 28
 * ordered {@code WHEN} clauses filling {@code L22}&ndash;{@code L77} exactly, and the range exit
 * paragraph {@code YYYY-STORE-PFKEY-EXIT} is declared at {@code [app/cpy/CSSTRPFY.cpy:L80]}.
 *
 * <p>Both paragraphs are units in their own right: they are 2 of the 16 paragraphs contributed by the
 * estate's two procedural copybooks. The legacy invocation range spans exactly one intermediate label -
 * the exit paragraph itself - so it is the trivial paired idiom and collapses to a single Java method
 * with a plain return. It is <em>not</em> one of the three genuinely multi-paragraph ranges in the
 * estate, none of which is reached from here.
 * <p><strong>Five including programs, and twelve that do not include it.</strong> The copybook is
 * pulled in with quoted copy syntax by exactly five online programs:
 * {@code [app/cbl/COACTUPC.cbl]}, {@code [app/cbl/COACTVWC.cbl]}, {@code [app/cbl/COCRDLIC.cbl]},
 * {@code [app/cbl/COCRDSLC.cbl]} and {@code [app/cbl/COCRDUPC.cbl]}. That is the same
 * five-program family which uniquely also includes {@code [app/cpy/CVCRD01Y.cpy]} and which is the
 * only family using the CICS abend handler. The other twelve online programs &mdash; the sign-on,
 * menu, transaction, report, bill-payment and user-administration programs &mdash; do not include
 * it and therefore have no attention-key mapping of their own. Each of the five includes performs
 * the range once, so five legacy call sites collapse into the one invocation of
 * {@link #translate(String)} offered here. That collapse is how construct-mapping row 3 of the migration
 * requirement is honoured for a <em>procedural</em> copybook, and decision D-31 records it: the
 * inclusion becomes a method call, never an import of a shared data type, because the member declares
 * no data.
 *
 * <p><strong>The 28 clauses, in exact source order.</strong> COBOL evaluates the construct top down and
 * stops at the first match, so clause order is part of the contract and is reproduced literally below
 * and in {@link #translate(String)}. This discharges construct-mapping row 8 of the migration
 * requirement - a conditional construct becomes a switch with its condition evaluation order preserved.
 *
 * <pre>
 *    #   attention-key identifier   action value   note
 *   ---  ------------------------   ------------   --------------------------------------
 *     1  DFHENTER                   'ENTER'
 *     2  DFHCLEAR                   'CLEAR'
 *     3  DFHPA1                     'PA1  '       three-character mnemonic, two pad spaces
 *     4  DFHPA2                     'PA2  '       three-character mnemonic, two pad spaces
 *     5  DFHPF1                     'PFK01'
 *     6  DFHPF2                     'PFK02'
 *     7  DFHPF3                     'PFK03'
 *     8  DFHPF4                     'PFK04'
 *     9  DFHPF5                     'PFK05'
 *    10  DFHPF6                     'PFK06'
 *    11  DFHPF7                     'PFK07'
 *    12  DFHPF8                     'PFK08'
 *    13  DFHPF9                     'PFK09'
 *    14  DFHPF10                    'PFK10'
 *    15  DFHPF11                    'PFK11'
 *    16  DFHPF12                    'PFK12'
 *    17  DFHPF13                    'PFK01'       FOLD BEGINS - identical to clause 5
 *    18  DFHPF14                    'PFK02'       folded onto clause 6
 *    19  DFHPF15                    'PFK03'       folded onto clause 7
 *    20  DFHPF16                    'PFK04'       folded onto clause 8
 *    21  DFHPF17                    'PFK05'       folded onto clause 9
 *    22  DFHPF18                    'PFK06'       folded onto clause 10
 *    23  DFHPF19                    'PFK07'       folded onto clause 11
 *    24  DFHPF20                    'PFK08'       folded onto clause 12
 *    25  DFHPF21                    'PFK09'       folded onto clause 13
 *    26  DFHPF22                    'PFK10'       folded onto clause 14
 *    27  DFHPF23                    'PFK11'       folded onto clause 15
 *    28  DFHPF24                    'PFK12'       FOLD ENDS - identical to clause 16
 * </pre>
 *
 * <p><strong>28 recognised inputs collapse to 16 distinct outcomes</strong>, and 16 is exactly the
 * number of level-88 condition names declared beneath the action field at
 * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}. That arithmetic identity is a useful self-check: any
 * future edit that changes either figure without changing the other has broken the mapping. Both
 * figures are factual counts read out of the source, not tuning parameters.
 *
 * <p><strong>Program-function keys 13 through 24 are not distinct actions</strong> (decision D-20).
 * Clauses 17 through 28 fold the high program-function keys back onto the same twelve flags the low
 * keys set, so a caller pressing key 15 receives precisely what key 3 delivers and a caller pressing
 * key 24 receives precisely what key 12 delivers. This is deliberate legacy behaviour and <em>not</em> a
 * source defect: the fold is written out explicitly in the copybook, once per high key, and the estate
 * declares no condition name for keys 13 through 24 anywhere. It is reproduced rather than corrected,
 * and the high keys are given no constants of their own.
 *
 * <p><strong>The fold is written out as twelve explicit arms rather than computed.</strong> No
 * modulo, no numeric parse of the key number and no substring-derived key index appears in this
 * class. An arithmetic fold would hide the very behaviour that most needs review, and it would
 * additionally accept identifiers the legacy construct rejects &mdash; a hypothetical key 25, for
 * instance, has no clause in the source and must not resolve to anything here either.
 *
 * <p><strong>There is no fallback clause, so absence is modelled rather than invented</strong>
 * (decision D-20). A mechanical count over the construct finds <strong>zero {@code WHEN OTHER}
 * clauses</strong>, and the consequence in COBOL is behavioural rather than cosmetic: when the incoming
 * attention key matches none of the 28 clauses no assignment happens at all, and the fixed-width
 * work-area field simply retains whatever value it already held from the previous interaction. There is
 * no sentinel, no unknown value and no error condition. {@link #translate(String)} therefore returns an
 * empty {@link Optional} for an unrecognised identifier: it does not return {@code null}, does not raise
 * and does not log. Returning empty is what lets the caller retain its own prior value, which is the
 * faithful outcome, because the conversation state belongs to the caller. This class deliberately does
 * not cache a previous result to simulate the retention, because a cache would be mutable static state
 * shared across every conversation.
 *
 * <p><strong>A {@code switch} {@code default} clause and a {@code DEFAULT} enum constant are not
 * the same thing, and the difference matters here.</strong> The {@code default} clause inside
 * {@link #translate(String)} is not merely permitted but required, because the selector is a
 * {@link String} rather than an enum and a string switch expression must be total. What is
 * forbidden is a synthetic constant &mdash; {@code UNKNOWN}, {@code NONE}, {@code OTHER},
 * {@code INVALID}, {@code UNMAPPED} or {@code DEFAULT} &mdash; on {@link KeyAction}. Such a
 * constant would manufacture a state the legacy system cannot produce, and no code here declares
 * or references one. {@link #actionValue(KeyAction)}, by contrast, switches over the enum itself
 * and is exhaustive by construction, so it carries no {@code default} clause at all: adding a
 * seventeenth constant would fail compilation rather than silently fall through to a wrong answer.
 *
 * <p><strong>The sixteen action values are five characters wide, and the padding is data.</strong> The
 * values are the level-88 condition-name literals declared on the action field
 * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}, which is {@code PIC X(5)} inside the
 * {@code CC-WORK-AREAS} structure. All sixteen are exactly five characters, and each is asserted at
 * exactly {@value #ACTION_VALUE_BYTE_WIDTH} <em>encoded bytes</em> measured through
 * {@link StandardCharsets#US_ASCII} rather than through a character count:
 * <pre>
 *   'ENTER'  'CLEAR'  'PA1  '  'PA2  '
 *   'PFK01'  'PFK02'  'PFK03'  'PFK04'  'PFK05'  'PFK06'
 *   'PFK07'  'PFK08'  'PFK09'  'PFK10'  'PFK11'  'PFK12'
 * </pre>
 *
 * <p><strong>The PA1 and PA2 values carry two trailing spaces each.</strong> They are
 * three-character mnemonics padded out to the five-byte field, and the padding is part of the
 * external value. It is never trimmed, never shortened to a three-character form and never
 * normalised, here or by any caller. {@link #fromActionValue(String)} consequently matches on the
 * exact five padded bytes; a lookup keyed on a trimmed form would silently fail to resolve those
 * two values. The remaining fourteen literals fill all five positions naturally, the twelve
 * program-function values because their numeric suffix is always zero-padded to two digits.
 *
 * <p><strong>Citation discipline for the work-area copybook.</strong>
 * {@code [app/cpy/CVCRD01Y.cpy]} carries COBOL sequence numbers in columns 1 through 6, so the numbers
 * visible in that member's left margin are sequence numbers and <em>not</em> line numbers. Every
 * citation of it in this class is therefore by member and field name, for example
 * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}, and never by a line number: a line number quoted from that
 * file would really be a sequence number and would send a reader to the wrong place. Row 16 of the
 * source anomaly register records the related duplication - the sequence number {@code 004800} appears
 * twice, at physical lines 40 and 42 - which is documented, is not propagated, and has no effect on the
 * field layout this class depends on.
 *
 * <p><strong>Ownership boundary.</strong> This class answers one question - which action, if any, an
 * attention-key identifier denotes - and answers nothing else. The onward consequences of an action
 * belong elsewhere and must not migrate here: the exit key eventually produces a thank-you message and
 * an unmapped key eventually produces an invalid-key message, but both of those literals, and every
 * other screen message, are owned by the service-layer message catalogue and not one of them appears in
 * this file. Routing likewise belongs to the service-layer navigation component, which owns the route
 * constants that replaced the estate's transfer-control dispatches and pseudo-conversational re-arms.
 * This class returns an action or nothing; it never returns a route, a message or a screen decision,
 * and it holds no dependency on the service, repository, api, batch or config layers.
 *
 * <p>By the same division of labour {@link KeyAction} defines the sixteen constants and nothing more,
 * while the padded five-character value contract and the keys-13-through-24 fold are owned here. This
 * is also the only class in {@code com.carddemo.util} that imports from
 * {@code com.carddemo.domain.enums}; the rest of the package is deliberately enum-free, so this single
 * crossing is the one place where a utility depends on a domain enumeration.
 *
 * <p><strong>Shape and guarantees.</strong> The class is final, cannot be instantiated, holds only
 * static members and has no mutable static state: the two published collections are unmodifiable and
 * their backing instances are unreachable. Every method is pure and side-effect free - no input or
 * output, no clock, no environment access, no randomness and no logging - so results depend on nothing
 * but the argument. Matching is exact throughout: no case folding, no trimming, no stripping, no
 * normalisation and no acceptance of abbreviations, numeric aliases or alternative spellings, because
 * the legacy comparison was against fixed compiler-supplied constants and accepting variants would
 * widen the contract. A {@code null} reference is a caller defect rather than a legitimate legacy
 * outcome and raises {@link NullPointerException} deterministically, which keeps it distinguishable
 * from the empty result that models a genuinely unrecognised key.
 */
public final class PfKeyTranslator {

    /**
     * The encoded-byte width of every action value, taken from the {@code PIC X(5)} declaration of
     * the action field {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}.
     *
     * <p>This is factual record-layout evidence rather than a tuning figure: the legacy field
     * genuinely occupies five bytes, the two shorter mnemonics are padded into it, and a value of
     * any other width cannot have come from that field. Callers that echo an action value back on a
     * fixed-width external contract may rely on it.
     */
    public static final int ACTION_VALUE_BYTE_WIDTH = 5;

    /**
     * The number of attention-key identifiers the legacy construct recognises, counted from its 28
     * ordered clauses at {@code [app/cpy/CSSTRPFY.cpy:L22]} through
     * {@code [app/cpy/CSSTRPFY.cpy:L77]}.
     */
    private static final int RECOGNISED_IDENTIFIER_COUNT = 28;

    /**
     * The number of distinct actions those 28 identifiers can produce, equal to the count of
     * level-88 condition names declared beneath {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}. The gap
     * between this figure and {@link #RECOGNISED_IDENTIFIER_COUNT} is exactly the twelve-key fold.
     */
    private static final int DISTINCT_ACTION_COUNT = 16;

    /* ==================================================================================
     * The 28 recognised attention-key identifier tokens.
     *
     * Each token is declared once, as a compile-time constant, and is then used twice: as a
     * case label in the ordered cascade of translate(String), and as a member of the published
     * RECOGNISED_IDENTIFIERS view. Declaring each token once is what guarantees the cascade and
     * the view can never disagree about which identifiers are recognised.
     *
     * The constants are named after the compiler-supplied condition names the legacy construct
     * compared against, so that the cascade can be audited against the clause table in the class
     * documentation by reading down the case labels.
     * ================================================================================== */

    /** Clause 1: the ENTER key. */
    private static final String DFHENTER = "DFHENTER";

    /** Clause 2: the CLEAR key. */
    private static final String DFHCLEAR = "DFHCLEAR";

    /** Clause 3: program-attention key 1. */
    private static final String DFHPA1 = "DFHPA1";

    /** Clause 4: program-attention key 2. */
    private static final String DFHPA2 = "DFHPA2";

    /** Clause 5: program-function key 1. */
    private static final String DFHPF1 = "DFHPF1";

    /** Clause 6: program-function key 2. */
    private static final String DFHPF2 = "DFHPF2";

    /** Clause 7: program-function key 3. */
    private static final String DFHPF3 = "DFHPF3";

    /** Clause 8: program-function key 4. */
    private static final String DFHPF4 = "DFHPF4";

    /** Clause 9: program-function key 5. */
    private static final String DFHPF5 = "DFHPF5";

    /** Clause 10: program-function key 6. */
    private static final String DFHPF6 = "DFHPF6";

    /** Clause 11: program-function key 7. */
    private static final String DFHPF7 = "DFHPF7";

    /** Clause 12: program-function key 8. */
    private static final String DFHPF8 = "DFHPF8";

    /** Clause 13: program-function key 9. */
    private static final String DFHPF9 = "DFHPF9";

    /** Clause 14: program-function key 10. */
    private static final String DFHPF10 = "DFHPF10";

    /** Clause 15: program-function key 11. */
    private static final String DFHPF11 = "DFHPF11";

    /** Clause 16: program-function key 12. */
    private static final String DFHPF12 = "DFHPF12";

    /** Clause 17: program-function key 13, folded onto the key 1 action. */
    private static final String DFHPF13 = "DFHPF13";

    /** Clause 18: program-function key 14, folded onto the key 2 action. */
    private static final String DFHPF14 = "DFHPF14";

    /** Clause 19: program-function key 15, folded onto the key 3 action. */
    private static final String DFHPF15 = "DFHPF15";

    /** Clause 20: program-function key 16, folded onto the key 4 action. */
    private static final String DFHPF16 = "DFHPF16";

    /** Clause 21: program-function key 17, folded onto the key 5 action. */
    private static final String DFHPF17 = "DFHPF17";

    /** Clause 22: program-function key 18, folded onto the key 6 action. */
    private static final String DFHPF18 = "DFHPF18";

    /** Clause 23: program-function key 19, folded onto the key 7 action. */
    private static final String DFHPF19 = "DFHPF19";

    /** Clause 24: program-function key 20, folded onto the key 8 action. */
    private static final String DFHPF20 = "DFHPF20";

    /** Clause 25: program-function key 21, folded onto the key 9 action. */
    private static final String DFHPF21 = "DFHPF21";

    /** Clause 26: program-function key 22, folded onto the key 10 action. */
    private static final String DFHPF22 = "DFHPF22";

    /** Clause 27: program-function key 23, folded onto the key 11 action. */
    private static final String DFHPF23 = "DFHPF23";

    /** Clause 28: program-function key 24, folded onto the key 12 action. */
    private static final String DFHPF24 = "DFHPF24";

    /* ==================================================================================
     * The 16 action values, each exactly five encoded bytes.
     *
     * These are the level-88 condition-name literals declared on the action field
     * [app/cpy/CVCRD01Y.cpy: CCARD-AID]. Each is declared once and used twice: as the result of
     * actionValue(KeyAction), and as a key of the reverse index. The two PA literals carry two
     * trailing spaces that are part of the value and must survive verbatim.
     * ================================================================================== */

    /** The ENTER action value; five characters, no padding required. */
    private static final String ACTION_ENTER = "ENTER";

    /** The CLEAR action value; five characters, no padding required. */
    private static final String ACTION_CLEAR = "CLEAR";

    /** The program-attention key 1 action value: three characters plus two trailing spaces. */
    private static final String ACTION_PA1 = "PA1  ";

    /** The program-attention key 2 action value: three characters plus two trailing spaces. */
    private static final String ACTION_PA2 = "PA2  ";

    /** The program-function key 1 action value; also the result of the fold from key 13. */
    private static final String ACTION_PFK01 = "PFK01";

    /** The program-function key 2 action value; also the result of the fold from key 14. */
    private static final String ACTION_PFK02 = "PFK02";

    /** The program-function key 3 action value; also the result of the fold from key 15. */
    private static final String ACTION_PFK03 = "PFK03";

    /** The program-function key 4 action value; also the result of the fold from key 16. */
    private static final String ACTION_PFK04 = "PFK04";

    /** The program-function key 5 action value; also the result of the fold from key 17. */
    private static final String ACTION_PFK05 = "PFK05";

    /** The program-function key 6 action value; also the result of the fold from key 18. */
    private static final String ACTION_PFK06 = "PFK06";

    /** The program-function key 7 action value; also the result of the fold from key 19. */
    private static final String ACTION_PFK07 = "PFK07";

    /** The program-function key 8 action value; also the result of the fold from key 20. */
    private static final String ACTION_PFK08 = "PFK08";

    /** The program-function key 9 action value; also the result of the fold from key 21. */
    private static final String ACTION_PFK09 = "PFK09";

    /** The program-function key 10 action value; also the result of the fold from key 22. */
    private static final String ACTION_PFK10 = "PFK10";

    /** The program-function key 11 action value; also the result of the fold from key 23. */
    private static final String ACTION_PFK11 = "PFK11";

    /** The program-function key 12 action value; also the result of the fold from key 24. */
    private static final String ACTION_PFK12 = "PFK12";

    /**
     * The 28 recognised identifier tokens, in the clause order of the legacy construct.
     *
     * <p>Built once during static initialisation and published through an unmodifiable wrapper
     * whose backing instance is unreachable, so there is no mutable static state and no lazily
     * populated cache. Insertion order is preserved so that iterating the view yields the tokens in
     * the order the legacy construct evaluates them.
     */
    private static final Set<String> RECOGNISED_IDENTIFIERS = orderedRecognisedIdentifiers();

    /**
     * Reverse index from an exact five-byte action value to its constant, in the declaration order
     * of the level-88 condition names. Built from {@link #actionValue(KeyAction)}, so the forward
     * accessor is the single authority for the padded literals and the two directions cannot drift
     * apart. Published unmodifiable, with its backing instance unreachable.
     */
    private static final Map<String, KeyAction> ACTION_VALUE_INDEX = indexByActionValue();

    /** Not instantiable: this is a stateless translation point, not a bean and not a value. */
    private PfKeyTranslator() {
        // No instance state exists, so no instance is ever required.
    }

    /**
     * Translates a raw terminal attention-key identifier into the action it denotes.
     *
     * <p>This is the single Java equivalent of the two paragraphs of
     * {@code [app/cpy/CSSTRPFY.cpy:L17]} and {@code [app/cpy/CSSTRPFY.cpy:L80]}, and the one
     * invocation into which the five legacy call sites collapse. The 28 arms below appear in the
     * clause order of {@code [app/cpy/CSSTRPFY.cpy:L21]} through
     * {@code [app/cpy/CSSTRPFY.cpy:L78]}, even though a Java {@code switch} is insensitive to arm
     * order, because that order is the documented contract and a reviewer verifies it by reading
     * down the case labels against the clause table in the class documentation.
     *
     * <p>Arms 17 through 28 are the fold: each high program-function key yields the very same
     * constant as its low-key twin, so the two are indistinguishable to every caller. They are
     * written out one by one rather than derived, so the fold is visible rather than clever and so
     * an identifier the source does not recognise cannot slip through.
     *
     * <p>An unrecognised identifier yields an empty result. That reproduces the legacy construct
     * faithfully: it has no fallback clause, so a non-matching key caused no assignment and left
     * the action field holding whatever it held before. The empty result is the signal to the
     * caller that it should keep its own previously held action; this method neither invents a
     * placeholder action nor raises, and it records nothing. The {@code default} arm exists only
     * because a string switch expression must be total, and it is not a default <em>action</em>.
     *
     * <p>Matching is exact on the token as supplied. Nothing is case folded, trimmed, stripped or
     * normalised, and no abbreviation, numeric alias or alternative spelling is accepted, because
     * the legacy comparison was against fixed compiler-supplied constants.
     *
     * @param attentionKeyIdentifier the raw terminal attention-key identifier, exactly as received;
     *                               must not be {@code null}
     * @return the action the identifier denotes, or an empty {@link Optional} when the identifier
     *         matches none of the 28 recognised tokens
     * @throws NullPointerException if {@code attentionKeyIdentifier} is {@code null}, which is a
     *                              caller defect rather than the legacy no-match outcome
     */
    public static Optional<KeyAction> translate(final String attentionKeyIdentifier) {
        Objects.requireNonNull(
                attentionKeyIdentifier,
                "attentionKeyIdentifier must not be null: an unrecognised key yields an empty "
                        + "result, which is not the same as an absent reference");
        return switch (attentionKeyIdentifier) {
            case DFHENTER -> Optional.of(KeyAction.ENTER);                       // clause 1
            case DFHCLEAR -> Optional.of(KeyAction.CLEAR);                       // clause 2
            case DFHPA1 -> Optional.of(KeyAction.PA1);                           // clause 3
            case DFHPA2 -> Optional.of(KeyAction.PA2);                           // clause 4
            case DFHPF1 -> Optional.of(KeyAction.PFK01);                         // clause 5
            case DFHPF2 -> Optional.of(KeyAction.PFK02);                         // clause 6
            case DFHPF3 -> Optional.of(KeyAction.PFK03);                         // clause 7
            case DFHPF4 -> Optional.of(KeyAction.PFK04);                         // clause 8
            case DFHPF5 -> Optional.of(KeyAction.PFK05);                         // clause 9
            case DFHPF6 -> Optional.of(KeyAction.PFK06);                         // clause 10
            case DFHPF7 -> Optional.of(KeyAction.PFK07);                         // clause 11
            case DFHPF8 -> Optional.of(KeyAction.PFK08);                         // clause 12
            case DFHPF9 -> Optional.of(KeyAction.PFK09);                         // clause 13
            case DFHPF10 -> Optional.of(KeyAction.PFK10);                        // clause 14
            case DFHPF11 -> Optional.of(KeyAction.PFK11);                        // clause 15
            case DFHPF12 -> Optional.of(KeyAction.PFK12);                        // clause 16
            case DFHPF13 -> Optional.of(KeyAction.PFK01);                        // clause 17, fold
            case DFHPF14 -> Optional.of(KeyAction.PFK02);                        // clause 18, fold
            case DFHPF15 -> Optional.of(KeyAction.PFK03);                        // clause 19, fold
            case DFHPF16 -> Optional.of(KeyAction.PFK04);                        // clause 20, fold
            case DFHPF17 -> Optional.of(KeyAction.PFK05);                        // clause 21, fold
            case DFHPF18 -> Optional.of(KeyAction.PFK06);                        // clause 22, fold
            case DFHPF19 -> Optional.of(KeyAction.PFK07);                        // clause 23, fold
            case DFHPF20 -> Optional.of(KeyAction.PFK08);                        // clause 24, fold
            case DFHPF21 -> Optional.of(KeyAction.PFK09);                        // clause 25, fold
            case DFHPF22 -> Optional.of(KeyAction.PFK10);                        // clause 26, fold
            case DFHPF23 -> Optional.of(KeyAction.PFK11);                        // clause 27, fold
            case DFHPF24 -> Optional.of(KeyAction.PFK12);                        // clause 28, fold
            // The legacy construct has no fallback clause. No assignment happened, so no action is
            // produced and the caller keeps whatever action it was already holding.
            default -> Optional.empty();
        };
    }

    /**
     * Returns the exact five-byte action value the legacy work area holds for the given action.
     *
     * <p>The value is the level-88 condition-name literal declared beneath the action field
     * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}, reproduced verbatim including padding. It is the
     * external form that responses echo, which is why the padding rule lives in this one method
     * rather than being applied by each caller. The PA1 and PA2 values carry two trailing spaces;
     * nothing here trims, shortens or normalises them.
     *
     * <p>The arms are enumerated in the declaration order of the condition names and cover every
     * constant, so the expression is exhaustive by construction and deliberately carries no
     * {@code default} arm. Introducing a seventeenth constant would therefore fail compilation
     * here, which is the intended outcome: it forces the new constant's five-byte value to be
     * decided against the source rather than defaulted.
     *
     * @param keyAction the action whose external value is required; must not be {@code null}
     * @return the action value, always exactly {@value #ACTION_VALUE_BYTE_WIDTH} encoded bytes
     * @throws NullPointerException if {@code keyAction} is {@code null}
     */
    public static String actionValue(final KeyAction keyAction) {
        Objects.requireNonNull(
                keyAction,
                "keyAction must not be null: every declared action has an action value, so a null "
                        + "reference can only be a caller defect");
        return switch (keyAction) {
            case ENTER -> ACTION_ENTER;
            case CLEAR -> ACTION_CLEAR;
            case PA1 -> ACTION_PA1;
            case PA2 -> ACTION_PA2;
            case PFK01 -> ACTION_PFK01;
            case PFK02 -> ACTION_PFK02;
            case PFK03 -> ACTION_PFK03;
            case PFK04 -> ACTION_PFK04;
            case PFK05 -> ACTION_PFK05;
            case PFK06 -> ACTION_PFK06;
            case PFK07 -> ACTION_PFK07;
            case PFK08 -> ACTION_PFK08;
            case PFK09 -> ACTION_PFK09;
            case PFK10 -> ACTION_PFK10;
            case PFK11 -> ACTION_PFK11;
            case PFK12 -> ACTION_PFK12;
        };
    }

    /**
     * Resolves an action value read back from a fixed-width work area to its action.
     *
     * <p>This is the inverse of {@link #actionValue(KeyAction)} and exists for callers that receive
     * the five-byte external form rather than a typed action. Matching is on the exact padded
     * bytes: a value of any other encoded width is rejected before the lookup is attempted, so a
     * trimmed three-character PA mnemonic does not resolve, and no case folding or white-space
     * normalisation is performed on either side of the comparison.
     *
     * <p>A value that is the right width but is not one of the sixteen declared values yields an
     * empty result, for the same reason an unrecognised attention key does: no action of this
     * enumeration represents "unrecognised", and inventing one would manufacture a state the legacy
     * system cannot produce.
     *
     * @param actionValue the action value to resolve, exactly as read from the work area; must not
     *                    be {@code null}
     * @return the matching action, or an empty {@link Optional} when the value is not one of the
     *         sixteen declared values or is not exactly
     *         {@value #ACTION_VALUE_BYTE_WIDTH} encoded bytes wide
     * @throws NullPointerException if {@code actionValue} is {@code null}
     */
    public static Optional<KeyAction> fromActionValue(final String actionValue) {
        Objects.requireNonNull(
                actionValue,
                "actionValue must not be null: an unrecognised value yields an empty result, which "
                        + "is not the same as an absent reference");
        if (encodedByteWidth(actionValue) != ACTION_VALUE_BYTE_WIDTH) {
            return Optional.empty();
        }
        return Optional.ofNullable(ACTION_VALUE_INDEX.get(actionValue));
    }

    /**
     * Returns the 28 recognised attention-key identifier tokens, in the clause order of the legacy
     * construct.
     *
     * <p>The returned set is an unmodifiable view over an unreachable backing instance, so no
     * mutable collection escapes and no caller can widen or narrow the recognised set. It is the
     * same token set the arms of {@link #translate(String)} match on, because both are built from
     * the same single declaration of each token.
     *
     * @return an unmodifiable, insertion-ordered set of the 28 recognised identifier tokens
     */
    public static Set<String> recognisedIdentifiers() {
        return RECOGNISED_IDENTIFIERS;
    }

    /**
     * Returns the 16 action values, each exactly {@value #ACTION_VALUE_BYTE_WIDTH} encoded bytes,
     * in the declaration order of the level-88 condition names.
     *
     * <p>The returned set is an unmodifiable view over an unreachable backing instance. Its size
     * being 16 against the 28 recognised identifiers is the fold made visible: twelve identifiers
     * contribute no value of their own.
     *
     * @return an unmodifiable, insertion-ordered set of the 16 action values, padding intact
     */
    public static Set<String> actionValues() {
        return ACTION_VALUE_INDEX.keySet();
    }

    /**
     * Builds the immutable, clause-ordered view of the recognised identifier tokens.
     *
     * <p>The tokens are listed in the clause order of the legacy construct, so the list reads as a
     * transcription-free index of that construct. The size check guards against a duplicated token
     * in the list, which a set would otherwise absorb silently and which would mean one clause of
     * the construct had been lost.
     *
     * @return an unmodifiable, insertion-ordered set of exactly
     *         {@link #RECOGNISED_IDENTIFIER_COUNT} tokens
     * @throws IllegalStateException if the token list does not hold exactly
     *                               {@link #RECOGNISED_IDENTIFIER_COUNT} distinct tokens
     */
    private static Set<String> orderedRecognisedIdentifiers() {
        final List<String> ordered = List.of(
                DFHENTER, DFHCLEAR, DFHPA1, DFHPA2,
                DFHPF1, DFHPF2, DFHPF3, DFHPF4, DFHPF5, DFHPF6,
                DFHPF7, DFHPF8, DFHPF9, DFHPF10, DFHPF11, DFHPF12,
                DFHPF13, DFHPF14, DFHPF15, DFHPF16, DFHPF17, DFHPF18,
                DFHPF19, DFHPF20, DFHPF21, DFHPF22, DFHPF23, DFHPF24);
        final Set<String> distinct = new LinkedHashSet<>(ordered);
        if (ordered.size() != RECOGNISED_IDENTIFIER_COUNT
                || distinct.size() != RECOGNISED_IDENTIFIER_COUNT) {
            throw new IllegalStateException(
                    "the recognised identifier set must hold exactly " + RECOGNISED_IDENTIFIER_COUNT
                            + " distinct tokens, one per clause of the legacy construct, but holds "
                            + distinct.size() + " distinct of " + ordered.size() + " listed");
        }
        return Collections.unmodifiableSet(distinct);
    }

    /**
     * Builds the immutable reverse index from action value to action, and verifies the fixed-width
     * contract while doing so.
     *
     * <p>Every value is measured as encoded bytes rather than as a character count, so a value that
     * looked five characters wide but did not encode to five bytes would be caught. The distinct
     * count is checked against {@link #DISTINCT_ACTION_COUNT} so that two actions sharing a value
     * cannot silently collapse the index; the exhaustive switch in
     * {@link #actionValue(KeyAction)} already prevents a missing value at compile time.
     *
     * @return an unmodifiable map from the exact padded action value to its action, in constant
     *         declaration order
     * @throws IllegalStateException if any value is not exactly
     *                               {@link #ACTION_VALUE_BYTE_WIDTH} encoded bytes, or if the
     *                               index does not hold exactly {@link #DISTINCT_ACTION_COUNT}
     *                               entries
     */
    private static Map<String, KeyAction> indexByActionValue() {
        final Map<String, KeyAction> index = new LinkedHashMap<>();
        for (final KeyAction action : KeyAction.values()) {
            final String value = actionValue(action);
            if (encodedByteWidth(value) != ACTION_VALUE_BYTE_WIDTH) {
                throw new IllegalStateException(
                        "action value for " + action.name() + " must be exactly "
                                + ACTION_VALUE_BYTE_WIDTH + " encoded bytes to match the "
                                + "fixed-width work-area field, but measures "
                                + encodedByteWidth(value));
            }
            index.put(value, action);
        }
        if (index.size() != DISTINCT_ACTION_COUNT) {
            throw new IllegalStateException(
                    "the action value index must hold exactly " + DISTINCT_ACTION_COUNT
                            + " entries, one per level-88 condition name on the work-area action "
                            + "field, but holds " + index.size());
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * Measures a value as encoded bytes in the single-byte character set the legacy fixed-width
     * fields are read and written in.
     *
     * <p>Byte width rather than character count is the meaningful measure for a fixed-width field:
     * the field reserves a byte count, not a character count, and the two diverge for any input
     * outside the single-byte range. Measuring in bytes is therefore what makes the check faithful
     * to the record layout.
     *
     * @param value the value to measure; must already be known to be non-{@code null}
     * @return the number of bytes the value occupies when encoded
     */
    private static int encodedByteWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }
}
