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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The CardDemo 3270 attention-key identifier.
 *
 * <p>The Java realisation of the {@code CCARD-AID} field declared {@code PIC X(5)} inside the
 * {@code CC-WORK-AREAS} structure of copybook {@code CVCRD01Y}, which five online programs include.
 * Immediately beneath the field the copybook declares <strong>16 level-88 condition names</strong>, one
 * per attention key, so this enum declares exactly 16 constants - one per condition name, in the same
 * declaration order.
 *
 * <p><strong>Values are five characters wide, and the padding is part of the value.</strong> Because
 * the underlying field is a fixed-width {@code PIC X(5)} work area, every condition-name literal is
 * exactly five characters long, and two of them are shorter than the field and therefore <em>space
 * padded in the source literal itself</em>: the two program-attention identifiers are three characters
 * followed by two spaces. The remaining fourteen - the ENTER and CLEAR identifiers and the twelve
 * program-function identifiers, whose numeric suffix is always zero-padded to two digits - naturally
 * occupy all five positions.
 *
 * <p>The padded five-character form is what this enum stores and what {@link #getAid()} returns, and it
 * must be preserved verbatim with no white-space normalization and no case folding.
 * {@link #fromAid(String)} likewise keys on the exact padded form, because the value it is given
 * originates in a fixed-width work area where trailing spaces are real data rather than incidental
 * formatting; a lookup keyed on a shortened form would silently fail to resolve the two
 * program-attention identifiers.
 *
 * <p><strong>There is deliberately no default constant.</strong> The legacy mapping from a terminal
 * attention-key identifier to one of these values lives in the procedural copybook {@code CSSTRPFY},
 * whose store-function-key paragraph and matching exit paragraph are the member's only two. Between
 * them sits a single evaluation construct with <strong>28 ordered clauses</strong> and, as verified by a
 * mechanical count, <strong>zero catch-all clauses</strong>. That absence is behaviorally significant
 * rather than cosmetic: when the incoming identifier matches none of the 28 clauses, no assignment
 * happens at all and the work-area field <em>retains the value it already held</em> from the prior
 * interaction. Consequently this enum must not, and does not, define a synthetic unknown, none, other,
 * invalid, unmapped or default constant - such a constant would manufacture a state the legacy system
 * cannot produce and would discard the retained value the legacy system relies on. Absence is modelled
 * explicitly by the empty {@link Optional} returned from {@link #fromAid(String)}, which leaves the
 * caller free to keep whatever value it was already holding. Decision log entry D-20 records this.
 *
 * <p><strong>Program-function keys 13 through 24 are not distinct actions.</strong> The same 28-clause
 * construct folds them back onto the same twelve flags as keys 1 through 12 - key 13 sets the flag key
 * 1 sets, and so on through key 24, which sets the twelfth. No condition name exists for them anywhere
 * in the estate, and no constant for them is declared here.
 *
 * <p><strong>Ownership boundary.</strong> This enum defines the constants and nothing more.
 * Translating a raw terminal attention-key identifier into one of them - including performing the
 * keys-13-through-24 fold - is owned by the utility-layer key translator. This enum performs no such
 * translation and holds no dependency on the utility layer.
 *
 * <p><strong>Scope.</strong> The originating copybook declares considerably more than the
 * attention-key identifier: screen-flow fields for the next program, mapset and map, error- and
 * return-message fields with their own condition name, and paired numeric redefinitions of the account
 * identifier, card number and customer identifier. None of that belongs here - those fields are
 * modelled by the screen work-area transfer object in the API layer. This enum models the attention-key
 * identifier alone, and is a pure value type: no framework annotation, no database column, and
 * transient screen-interaction state rather than persisted data.
 */
public enum KeyAction {

    /** The ENTER key. Identifier {@code ENTER} - five characters, no padding required. */
    ENTER("ENTER"),

    /** The CLEAR key. Identifier {@code CLEAR} - five characters, no padding required. */
    CLEAR("CLEAR"),

    /**
     * Program-attention key 1. Identifier {@code PA1} followed by
     * <strong>two trailing spaces</strong>, padding the three-character mnemonic
     * out to the full five-character field width.
     */
    PA1("PA1  "),

    /**
     * Program-attention key 2. Identifier {@code PA2} followed by
     * <strong>two trailing spaces</strong>, padding the three-character mnemonic
     * out to the full five-character field width.
     */
    PA2("PA2  "),

    /** Program-function key 1; also the target of the fold from key 13. */
    PFK01("PFK01"),

    /** Program-function key 2; also the target of the fold from key 14. */
    PFK02("PFK02"),

    /** Program-function key 3; also the target of the fold from key 15. */
    PFK03("PFK03"),

    /** Program-function key 4; also the target of the fold from key 16. */
    PFK04("PFK04"),

    /** Program-function key 5; also the target of the fold from key 17. */
    PFK05("PFK05"),

    /** Program-function key 6; also the target of the fold from key 18. */
    PFK06("PFK06"),

    /** Program-function key 7; also the target of the fold from key 19. */
    PFK07("PFK07"),

    /** Program-function key 8; also the target of the fold from key 20. */
    PFK08("PFK08"),

    /** Program-function key 9; also the target of the fold from key 21. */
    PFK09("PFK09"),

    /** Program-function key 10; also the target of the fold from key 22. */
    PFK10("PFK10"),

    /** Program-function key 11; also the target of the fold from key 23. */
    PFK11("PFK11"),

    /** Program-function key 12; also the target of the fold from key 24. */
    PFK12("PFK12");

    /**
     * Lookup index from the exact five-character identifier to its constant.
     *
     * <p>Built once, eagerly, during static initialisation and published through
     * an unmodifiable wrapper whose backing instance is unreachable, so the index
     * is effectively immutable: there is no mutable static field, no lazily
     * populated cache and no exposed mutator. Insertion order is preserved so
     * that iterating the index yields the constants in the same order the legacy
     * conditional evaluates them.</p>
     *
     * <p>Initialising this field after the constants is safe: an enum's constants
     * are constructed before any subsequent static initialiser runs, so
     * {@code values()} is fully populated by the time the index is built.</p>
     */
    private static final Map<String, KeyAction> BY_AID = indexByAid();

    /**
     * The exact five-character identifier, including any trailing space padding.
     * Never normalised, never shortened.
     */
    private final String aid;

    /**
     * Binds a constant to its exact five-character identifier.
     *
     * @param aid the identifier verbatim, including trailing space padding
     */
    KeyAction(String aid) {
        this.aid = aid;
    }

    /**
     * Returns the exact five-character identifier for this key, including any
     * trailing space padding. Callers must treat the padding as part of the
     * value; it corresponds to real positions in a fixed-width work area.
     *
     * @return the identifier verbatim, always exactly five characters long
     */
    public String getAid() {
        return aid;
    }

    /**
     * Resolves an identifier read from the fixed-width work area to its constant.
     *
     * <p>Matching is exact: the supplied value is compared against the padded
     * five-character form with no white-space normalisation and no case folding.
     * A {@code null} argument, an identifier of any other width, and an
     * identifier that simply is not one of the 16 declared values all yield an
     * empty result.</p>
     *
     * <p>An empty result models the legacy no-default behaviour faithfully. The
     * originating conditional has no fallback clause, so an unrecognised
     * identifier causes no assignment and leaves the previously held value
     * intact. Callers should mirror that: on an empty result, retain whatever
     * key action was already in effect rather than substituting a placeholder.
     * No constant of this enum represents "unrecognised".</p>
     *
     * @param aid the identifier to resolve, exactly as read from the work area;
     *            may be {@code null}
     * @return the matching constant, or an empty {@link Optional} if the
     *         identifier matches none of the 16 declared values
     */
    public static Optional<KeyAction> fromAid(String aid) {
        if (aid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_AID.get(aid));
    }

    /**
     * Indicates whether this key is one of the twelve program-function keys, as
     * distinct from the ENTER key, the CLEAR key and the two program-attention
     * keys.
     *
     * <p>The cases below are enumerated in the same order the legacy conditional
     * evaluates them, and they cover every constant, so the expression is
     * exhaustive by construction and needs no fallback branch. Adding a constant
     * without extending this expression would fail compilation rather than fall
     * through to a silently wrong answer.</p>
     *
     * @return {@code true} for the twelve program-function keys, {@code false}
     *         for the ENTER key, the CLEAR key and the two program-attention keys
     */
    public boolean isProgramFunctionKey() {
        return switch (this) {
            case ENTER, CLEAR, PA1, PA2 -> false;
            case PFK01, PFK02, PFK03, PFK04, PFK05, PFK06,
                 PFK07, PFK08, PFK09, PFK10, PFK11, PFK12 -> true;
        };
    }

    /**
     * Builds the immutable identifier-to-constant lookup index.
     *
     * @return an unmodifiable map keyed on the exact five-character identifier,
     *         in constant declaration order
     */
    private static Map<String, KeyAction> indexByAid() {
        Map<String, KeyAction> index = new LinkedHashMap<>();
        for (KeyAction action : values()) {
            index.put(action.aid, action);
        }
        return Collections.unmodifiableMap(index);
    }
}
