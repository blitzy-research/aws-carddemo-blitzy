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
 * {@link KeyAction}, and the only place in the Java translation where that mapping is decided.
 *
 * <p>The authority is the procedural copybook member {@code [app/cpy/CSSTRPFY.cpy]}, which contributes
 * procedure paragraphs rather than data and is pulled in by five online programs. Its single ordered
 * conditional construct has twenty-eight arms; the legacy invocation range spans only its own exit
 * label, so it collapses to one Java method with a plain return.
 *
 * <p><strong>Arm order is part of the contract.</strong> The legacy construct is evaluated top down and
 * stops at the first match, so the arms of {@link #translate(String)} are written in the source's arm
 * order even though a Java {@code switch} is insensitive to it: the order is what a reviewer verifies
 * by reading down the case labels, and each label carries its arm number for exactly that purpose.</p>
 *
 * <p><strong>Twenty-eight recognised inputs collapse to sixteen distinct outcomes</strong>, and sixteen
 * is exactly the number of action constants declared beneath the work area's action field
 * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}. Both figures are counts read out of the source rather than
 * tuning parameters, and their difference is a useful self-check: an edit that changes one without the
 * other has broken the mapping.</p>
 *
 * <p><strong>Program-function keys 13 through 24 are not distinct actions</strong> (decision D-20).
 * Arms 17 through 28 fold the high keys back onto the same twelve outcomes the low keys produce, so a
 * caller pressing key 15 receives precisely what key 3 delivers. This is deliberate legacy behaviour
 * and not a source defect &mdash; the fold is written out explicitly in the copybook, once per high key,
 * and the estate declares no action for keys 13 through 24 anywhere. It is reproduced rather than
 * corrected, and the high keys are given no constants of their own.</p>
 *
 * <p>The fold is written out as twelve explicit arms rather than computed. No modulo, numeric parse or
 * substring-derived key index appears here: an arithmetic fold would hide the very behaviour that most
 * needs review, and it would additionally accept identifiers the legacy construct rejects &mdash; a
 * hypothetical key 25 has no arm in the source and must not resolve to anything here either.</p>
 *
 * <p><strong>There is no fallback arm, so absence is modelled rather than invented</strong> (decision
 * D-20). The consequence in the legacy is behavioural rather than cosmetic: when the incoming key
 * matches no arm, no assignment happens at all and the fixed-width work-area field simply retains
 * whatever it held from the previous interaction. There is no sentinel, no unknown value and no error
 * condition. {@link #translate(String)} therefore returns an empty {@link Optional} for an unrecognised
 * identifier &mdash; it does not return {@code null}, does not raise and does not log &mdash; because
 * returning empty is what lets the caller retain its own prior value.</p>
 *
 * <p>A {@code switch} {@code default} clause and a synthetic enum constant are not the same thing, and
 * the difference matters here. The {@code default} clause inside {@link #translate(String)} is required
 * rather than merely permitted, because the selector is a {@link String} and a string switch expression
 * must be total. What is forbidden is an {@code UNKNOWN}, {@code NONE}, {@code OTHER} or {@code DEFAULT}
 * constant on {@link KeyAction}, which would manufacture a state the legacy system cannot produce.
 * {@link #actionValue(KeyAction)} switches over the enum itself, so it is exhaustive by construction and
 * deliberately carries no {@code default} arm.</p>
 *
 * <p><strong>The sixteen action values are five bytes wide and the padding is data.</strong> Each is
 * asserted at exactly {@value #ACTION_VALUE_BYTE_WIDTH} <em>encoded bytes</em> measured through
 * {@link StandardCharsets#US_ASCII} rather than as a character count. Two of them are three-character
 * mnemonics padded out to the field width, and that padding is part of the external value: it is never
 * trimmed, shortened or normalised, here or by any caller, and {@link #fromActionValue(String)} matches
 * on the exact padded bytes so that a lookup keyed on a trimmed form cannot silently fail to resolve
 * them. The twelve program-function values fill all five positions naturally because their numeric
 * suffix is always zero-padded to two digits.</p>
 *
 * <p><strong>Citation discipline for the work-area copybook.</strong> {@code [app/cpy/CVCRD01Y.cpy]}
 * carries sequence numbers in its first six columns, so the numbers visible in that member's left
 * margin are sequence numbers and not line numbers. Every citation of it here is therefore by member
 * and field name and never by a line number, because a line number quoted from that file would really
 * be a sequence number and would send a reader to the wrong place.</p>
 *
 * <p><strong>Ownership boundary.</strong> This class answers one question &mdash; which action, if any,
 * an attention-key identifier denotes &mdash; and answers nothing else. The onward consequences belong
 * elsewhere and must not migrate here: screen message text is owned by the service-layer message
 * catalogue and no message literal appears in this file, and routing is owned by the service-layer
 * navigation component. {@link KeyAction} defines the sixteen constants and nothing more, while the
 * padded five-byte value contract and the high-key fold are owned here. This is also the only class in
 * {@code com.carddemo.util} that imports from {@code com.carddemo.domain.enums}; the rest of the package
 * is deliberately enum-free.</p>
 *
 * <p><strong>Shape and guarantees.</strong> The class is final, cannot be instantiated, holds only
 * static members and has no mutable static state: the two published collections are unmodifiable and
 * their backing instances are unreachable. Every method is pure &mdash; no input or output, no clock, no
 * environment access, no randomness and no logging. Matching is exact throughout: no case folding,
 * trimming, stripping or normalisation, and no acceptance of abbreviations, numeric aliases or
 * alternative spellings, because the legacy comparison was against fixed compiler-supplied constants and
 * accepting variants would widen the contract. A {@code null} reference is a caller defect rather than a
 * legitimate legacy outcome and raises rather than resolving to empty.</p>
 */
public final class PfKeyTranslator {

    /**
     * The encoded-byte width of every action value, taken from the width of the work-area action field
     * {@code [app/cpy/CVCRD01Y.cpy: CCARD-AID]}. This is record-layout evidence rather than a tuning
     * figure: two mnemonics are padded into it, a value of any other width cannot have come from that
     * field, and callers echoing an action value on a fixed-width external contract may rely on it.
     */
    public static final int ACTION_VALUE_BYTE_WIDTH = 5;

    /** The number of attention-key identifiers the legacy construct recognises, one per arm. */
    private static final int RECOGNISED_IDENTIFIER_COUNT = 28;

    /**
     * The number of distinct actions those identifiers can produce. Its gap from
     * {@link #RECOGNISED_IDENTIFIER_COUNT} is exactly the twelve-key fold.
     */
    private static final int DISTINCT_ACTION_COUNT = 16;

    /* ==================================================================================
     * The recognised attention-key identifier tokens, named after the compiler-supplied constants the
     * legacy construct compared against. Each is declared once and used twice -- as a case label in
     * the ordered cascade of translate(String) and as a member of the published view -- which is what
     * guarantees the cascade and the view can never disagree about what is recognised.
     * ================================================================================== */

    private static final String DFHENTER = "DFHENTER";

    private static final String DFHCLEAR = "DFHCLEAR";

    private static final String DFHPA1 = "DFHPA1";

    private static final String DFHPA2 = "DFHPA2";

    private static final String DFHPF1 = "DFHPF1";

    private static final String DFHPF2 = "DFHPF2";

    private static final String DFHPF3 = "DFHPF3";

    private static final String DFHPF4 = "DFHPF4";

    private static final String DFHPF5 = "DFHPF5";

    private static final String DFHPF6 = "DFHPF6";

    private static final String DFHPF7 = "DFHPF7";

    private static final String DFHPF8 = "DFHPF8";

    private static final String DFHPF9 = "DFHPF9";

    private static final String DFHPF10 = "DFHPF10";

    private static final String DFHPF11 = "DFHPF11";

    private static final String DFHPF12 = "DFHPF12";

    private static final String DFHPF13 = "DFHPF13";

    private static final String DFHPF14 = "DFHPF14";

    private static final String DFHPF15 = "DFHPF15";

    private static final String DFHPF16 = "DFHPF16";

    private static final String DFHPF17 = "DFHPF17";

    private static final String DFHPF18 = "DFHPF18";

    private static final String DFHPF19 = "DFHPF19";

    private static final String DFHPF20 = "DFHPF20";

    private static final String DFHPF21 = "DFHPF21";

    private static final String DFHPF22 = "DFHPF22";

    private static final String DFHPF23 = "DFHPF23";

    private static final String DFHPF24 = "DFHPF24";

    /* ==================================================================================
     * The action values, each exactly five encoded bytes, as declared on the work-area action field
     * [app/cpy/CVCRD01Y.cpy: CCARD-AID]. Each is declared once and used twice -- as the result of
     * actionValue(KeyAction) and as a key of the reverse index. The two PA literals carry two trailing
     * spaces that are part of the value and must survive verbatim.
     * ================================================================================== */

    private static final String ACTION_ENTER = "ENTER";

    private static final String ACTION_CLEAR = "CLEAR";

    private static final String ACTION_PA1 = "PA1  ";

    private static final String ACTION_PA2 = "PA2  ";

    private static final String ACTION_PFK01 = "PFK01";

    private static final String ACTION_PFK02 = "PFK02";

    private static final String ACTION_PFK03 = "PFK03";

    private static final String ACTION_PFK04 = "PFK04";

    private static final String ACTION_PFK05 = "PFK05";

    private static final String ACTION_PFK06 = "PFK06";

    private static final String ACTION_PFK07 = "PFK07";

    private static final String ACTION_PFK08 = "PFK08";

    private static final String ACTION_PFK09 = "PFK09";

    private static final String ACTION_PFK10 = "PFK10";

    private static final String ACTION_PFK11 = "PFK11";

    private static final String ACTION_PFK12 = "PFK12";

    /**
     * The recognised identifier tokens, in the arm order of the legacy construct. Built once during
     * static initialisation and published unmodifiable over an unreachable backing instance, so there is
     * no mutable static state and no lazily populated cache.
     */
    private static final Set<String> RECOGNISED_IDENTIFIERS = orderedRecognisedIdentifiers();

    /**
     * Reverse index from an exact five-byte action value to its constant. Built from
     * {@link #actionValue(KeyAction)}, so the forward accessor is the single authority for the padded
     * literals and the two directions cannot drift apart. Published unmodifiable.
     */
    private static final Map<String, KeyAction> ACTION_VALUE_INDEX = indexByActionValue();

    /** Not instantiable: this is a stateless translation point, not a bean and not a value. */
    private PfKeyTranslator() {
    // No instance state exists, so no instance is ever required.
    }

    /**
     * Translates a raw terminal attention-key identifier into the action it denotes, in the arm order of
     * the legacy construct, with arms 17 through 28 folding the high program-function keys onto their
     * low-key twins so the two are indistinguishable to every caller.
     *
     * <p>An unrecognised identifier yields an empty result, reproducing a construct that has no fallback
     * arm: a non-matching key caused no assignment and left the action field holding its previous value,
     * so the empty result is the caller's signal to keep the prior action it already holds. This
     * method invents no placeholder action, raises nothing and records nothing. The {@code default} arm
     * exists only because a string switch expression must be total; it is not a default <em>action</em>.
     *
     * @param  attentionKeyIdentifier the raw identifier exactly as received; matched exactly, with no
     *                                case folding, trimming or normalisation; must not be {@code null}
     * @return the action the identifier denotes, or an empty {@link Optional} when it matches no arm
     * @throws NullPointerException if {@code attentionKeyIdentifier} is {@code null}, which is a caller
     *                              defect rather than the legacy no-match outcome
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
            // The legacy construct has no fallback arm: no assignment happened, so no action is
            // produced and the caller keeps whatever action it was already holding.
            default -> Optional.empty();
        };
    }

    /**
     * Returns the exact five-byte action value the legacy work area holds for the given action,
     * reproduced verbatim including padding. This is the external form responses echo, which is why the
     * padding rule lives in this one method rather than being applied by each caller.
     *
     * <p>The arms cover every constant in declaration order, so the expression is exhaustive by
     * construction and deliberately carries no {@code default} arm: introducing a seventeenth constant
     * would fail compilation here, forcing its five-byte value to be decided against the source rather
     * than defaulted.
     *
     * @param  keyAction the action whose external value is required; must not be {@code null}
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
     * Resolves an action value read back from a fixed-width work area to its action, for callers that
     * receive the five-byte external form rather than a typed action.
     *
     * <p>Matching is on the exact padded bytes and a value of any other encoded width is rejected before
     * the lookup, so a trimmed three-character mnemonic does not resolve and neither side is case folded
     * or white-space normalised. A right-width value that is not one of the declared values yields an
     * empty result, for the same reason an unrecognised attention key does: no constant of this
     * enumeration represents "unrecognised", and inventing one would manufacture a state the legacy
     * system cannot produce.
     *
     * @param  actionValue the value exactly as read from the work area; must not be {@code null}
     * @return the matching action, or an empty {@link Optional} when the value is not one of the
     *         declared values or is not exactly {@value #ACTION_VALUE_BYTE_WIDTH} encoded bytes wide
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
     * Returns the recognised attention-key identifier tokens, in the arm order of the legacy construct.
     * The view is unmodifiable over an unreachable backing instance, and it is the same token set the
     * arms of {@link #translate(String)} match on because both are built from one declaration of each
     * token.
     *
     * @return an unmodifiable, insertion-ordered set of the recognised identifier tokens
     */
    public static Set<String> recognisedIdentifiers() {
        return RECOGNISED_IDENTIFIERS;
    }

    /**
     * Returns the action values, each exactly {@value #ACTION_VALUE_BYTE_WIDTH} encoded bytes with
     * padding intact, in constant declaration order. Its size against the count of recognised
     * identifiers is the fold made visible: twelve identifiers contribute no value of their own.
     *
     * @return an unmodifiable, insertion-ordered set of the action values
     */
    public static Set<String> actionValues() {
        return ACTION_VALUE_INDEX.keySet();
    }

    /**
     * Builds the immutable, arm-ordered view of the recognised identifier tokens. The size check guards
     * against a duplicated token, which a set would otherwise absorb silently and which would mean one
     * arm of the construct had been lost.
     *
     * @return an unmodifiable, insertion-ordered set of exactly
     *         {@link #RECOGNISED_IDENTIFIER_COUNT} tokens
     * @throws IllegalStateException if the token list does not hold exactly that many distinct tokens
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
     * Builds the immutable reverse index from action value to action, verifying the fixed-width contract
     * while doing so. Every value is measured as encoded bytes rather than characters, so a value that
     * looked five characters wide but did not encode to five bytes would be caught, and the distinct
     * count is checked so that two actions sharing a value cannot silently collapse the index. A missing
     * value is already prevented at compile time by the exhaustive switch in
     * {@link #actionValue(KeyAction)}.
     *
     * @return an unmodifiable map from the exact padded action value to its action, in declaration order
     * @throws IllegalStateException if any value is not exactly
     *                               {@link #ACTION_VALUE_BYTE_WIDTH} encoded bytes, or if the index does
     *                               not hold exactly {@link #DISTINCT_ACTION_COUNT} entries
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
     * Measures a value as encoded bytes in the single-byte character set the legacy fixed-width fields
     * are read and written in. Byte width rather than character count is the meaningful measure here:
     * the field reserves a byte count, and the two diverge for any input outside the single-byte range.
     *
     * @param  value the value to measure; must already be known to be non-{@code null}
     * @return the number of bytes the value occupies when encoded
     */
    private static int encodedByteWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }
}
