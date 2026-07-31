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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Card active-status flag, translated from the legacy {@code CARD-ACTIVE-STATUS} field.
 *
 * <h2>Legacy provenance</h2>
 * <p>Migrated from the AWS CardDemo mainframe estate at checkout commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}.</p>
 *
 * <p>The field is declared {@code PIC X(01)} on line 10 of the card record copybook
 * {@code app/cpy/CVACT02Y.cpy}. Within the 150-byte {@code CARD-RECORD} image it occupies
 * <strong>byte offset 91</strong>, immediately after the four leading fields and the expiry
 * field, which together consume the first 90 bytes: card number {@code X(16)} at bytes 1-16,
 * account identifier {@code 9(11)} at bytes 17-27, card verification code {@code 9(03)} at
 * bytes 28-30, embossed name {@code X(50)} at bytes 31-80 and expiry date {@code X(10)} at
 * bytes 81-90. A trailing {@code FILLER X(59)} occupies bytes 92-150, so the widths sum to
 * exactly 150. That offset is the contract every fixed-width card record mapper slices at.</p>
 *
 * <h2>How the {@code Y}/{@code N} vocabulary was established</h2>
 * <p>The vocabulary is deliberately recorded here because it is <em>not</em> declared by the
 * record layout and could not simply be read off it. Three findings frame the problem:</p>
 * <ul>
 *   <li>The copybook attaches no level-88 condition name to {@code CARD-ACTIVE-STATUS}, so it
 *       enumerates no permitted values.</li>
 *   <li>The field is never compared against a literal anywhere in the estate. It is only moved
 *       into a before-image field, compared field-to-field against that before-image as part of
 *       the card-update optimistic-lock check, or moved into a screen output field.</li>
 *   <li>All 50 seeded card records in {@code app/data/ASCII/carddata.txt} carry {@code Y} at
 *       byte offset 91, a single distinct value, so the seed data alone cannot reveal the
 *       vocabulary either.</li>
 * </ul>
 *
 * <p>The vocabulary was therefore recovered from the only program that validates the field, the
 * card-update program {@code app/cbl/COCRDUPC.cbl}. Lines 89-91 declare a one-character yes/no
 * check field, {@code FLG-YES-NO-CHECK}, initialised to {@code N} and carrying the level-88
 * condition name {@code FLG-YES-NO-VALID} whose two permitted codes are {@code Y} and
 * {@code N}. The card-status editor at line 861 routes the submitted status value through that
 * same check field, after first treating low-values, spaces and zeros as a not-supplied
 * condition at lines 850-852. The shared editor {@code 1220-EDIT-YESNO} in the account-update
 * program {@code app/cbl/COACTUPC.cbl}, lines 1856-1897, corroborates the same two-value
 * vocabulary and rejects any other value with a message naming those two codes.</p>
 *
 * <p>The two constants below are declared in the order the legacy condition name lists its
 * values, {@code Y} then {@code N}, and {@link #isActive()} evaluates its cases in that same
 * order so the translated condition ordering matches the source.</p>
 *
 * <h2>Validation-flag states are excluded on purpose</h2>
 * <p>The account-update program's parallel level-88 group declares two further one-character
 * states beside the {@code Y}/{@code N} pair: a not-OK state coded {@code '0'} and a blank state
 * coded {@code 'B'}. Neither is a card status. They are states of the <em>validation flag</em>
 * that drives field-level error display: {@code '0'} records that a submitted value failed
 * validation and {@code 'B'} records that the field was left blank. Neither is ever stored in
 * {@code CARD-ACTIVE-STATUS} and neither is ever written to the 150-byte record, so admitting
 * them here would invent two card statuses the estate does not have. They belong to the
 * field-error surface, which exposes per-field MISSING and INVALID states, and they are modelled
 * there rather than in this enum.</p>
 *
 * <h2>Why an unmapped code is tolerated rather than rejected</h2>
 * <p>Only the online card-update program validates this field. The batch programs that read card
 * records, including the card-file reader {@code app/cbl/CBACT02C.cbl} and the daily posting
 * program {@code app/cbl/CBTRN02C.cbl}, take the status straight from the file and never check
 * it, and the relational column that replaces byte 91 is a plain one-character column with no
 * check constraint. A file-sourced value outside the two known codes therefore flows through the
 * legacy system untouched, and it must flow through this lookup untouched too.</p>
 *
 * <p>Consequently {@link #fromCode(char)} and {@link #fromCode(String)} never throw: an
 * unrecognised code yields {@link Optional#empty()}. There is deliberately no synthetic
 * {@code UNKNOWN}-style constant to absorb a miss, because such a constant would be a value the
 * estate never produces. Callers that need a boolean answer compose the two members, and an
 * absent or unrecognised code then answers {@code false}:</p>
 *
 * <pre>{@code
 * boolean active = CardStatus.fromCode(card.getActiveStatus())
 *                            .map(CardStatus::isActive)
 *                            .orElse(false);
 * }</pre>
 *
 * <h2>Deliberately not the persistence type</h2>
 * <p>This enum is a pure value type. It carries no persistence annotation and no attribute
 * converter, and it drives no schema. The card entity keeps its status column as a raw
 * one-character string, so translation from the raw code to a constant happens in the service
 * layer through the lookups below. Persisting the enum directly would be wrong in either
 * mapping: a string mapping stores the constant name and a one-character column cannot hold it,
 * and an ordinal mapping needs an integer column that the record layout does not have.</p>
 *
 * <h2>Representation choice</h2>
 * <p>The raw code is carried as a {@code char} rather than as a single-character
 * {@link String}. The legacy field is exactly one character wide, so a {@code char} makes that
 * width a property of the type itself and leaves no room for a longer value that would invite
 * trimming or case folding. No normalisation of any kind is applied before lookup, which is
 * faithful to the legacy comparison: it tested the byte as supplied, so a lowercase {@code y} is
 * not an active status.</p>
 *
 * <p>Instances are immutable and the lookup index is built once and unmodifiable, so this type is
 * safe for concurrent use.</p>
 */
public enum CardStatus {

    /**
     * Card is active, raw code {@code Y}.
     *
     * <p>The only code present in the seeded card data: all 50 records carry it at byte offset
     * 91.</p>
     */
    Y('Y'),

    /**
     * Card is not active, raw code {@code N}.
     *
     * <p>Accepted by the legacy card-status editor but absent from the seeded card data.</p>
     */
    N('N');

    /**
     * Raw one-character code exactly as it appears at byte offset 91 of the card record.
     */
    private final char code;

    /**
     * Binds a constant to the raw code it is stored as.
     *
     * @param code the raw one-character code held at byte offset 91 of the card record
     */
    CardStatus(final char code) {
        this.code = code;
    }

    /**
     * Immutable index from raw code to constant, built once from {@link #values()} so it can
     * never drift from the declared constants. Insertion order follows declaration order, which
     * keeps the index deterministic even though lookup does not depend on it.
     */
    private static final Map<Character, CardStatus> BY_CODE;

    static {
        final Map<Character, CardStatus> byCode = new LinkedHashMap<>();
        for (final CardStatus status : values()) {
            byCode.put(status.code, status);
        }
        BY_CODE = Map.copyOf(byCode);
    }

    /**
     * Returns the raw one-character code this constant is stored as, exactly as it appears at
     * byte offset 91 of the 150-byte card record.
     *
     * @return the raw one-character status code
     */
    public char getCode() {
        return this.code;
    }

    /**
     * Reports whether this status means the card is active.
     *
     * <p>The cases are evaluated in the order the legacy condition name lists its values,
     * {@code Y} then {@code N}. The switch is exhaustive over both constants, so adding a third
     * constant would fail compilation rather than silently fall through to a wrong answer.</p>
     *
     * @return {@code true} only for {@link #Y}
     */
    public boolean isActive() {
        return switch (this) {
            case Y -> true;
            case N -> false;
        };
    }

    /**
     * Looks up the constant for a raw one-character code.
     *
     * <p>Never throws. An unrecognised code yields an empty result, because card records reach
     * the application from batch inputs that the legacy system never validated and from a column
     * that carries no check constraint. No normalisation is applied, so a lowercase {@code y}
     * does not resolve.</p>
     *
     * @param code the raw one-character code taken from byte offset 91 of the card record
     * @return the matching constant, or {@link Optional#empty()} if the code is not one of the
     *         two codes the estate defines
     */
    public static Optional<CardStatus> fromCode(final char code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /**
     * Looks up the constant for a raw code held as a string, which is the form the card entity
     * stores and therefore the form the service layer translates from.
     *
     * <p>Never throws. A {@code null} value and a value whose length is not exactly one both
     * yield an empty result: the legacy field is one character wide, so anything else cannot be a
     * status code. No trimming, stripping or case folding is applied before the lookup, so a
     * lowercase {@code y}, a padded value and a blank all yield an empty result.</p>
     *
     * @param code the raw code as stored, possibly {@code null}
     * @return the matching constant, or {@link Optional#empty()} if the value is absent, is not
     *         exactly one character long, or is not one of the two codes the estate defines
     */
    public static Optional<CardStatus> fromCode(final String code) {
        if (code == null || code.length() != 1) {
            return Optional.empty();
        }
        return fromCode(code.charAt(0));
    }
}
