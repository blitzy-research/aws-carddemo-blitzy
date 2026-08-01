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
 * <p><strong>Legacy provenance.</strong> The field is declared {@code PIC X(01)} in the card record
 * copybook {@code CVACT02Y}. Within the 150-byte {@code CARD-RECORD} image it occupies <strong>byte
 * offset 91</strong>, immediately after the five leading fields that together consume the first 90
 * bytes - card number 16, account identifier 11, verification code 3, embossed name 50 and expiry date
 * 10 - and a trailing 59-byte filler occupies the remainder, so the widths sum to exactly 150. That
 * offset is the contract every fixed-width card record mapper slices at.
 *
 * <p><strong>How the {@code Y}/{@code N} vocabulary was established.</strong> This is recorded
 * deliberately, because the vocabulary is <em>not</em> declared by the record layout and could not be
 * read off it. Three findings frame the problem: the copybook attaches no level-88 condition name to
 * the field, so it enumerates no permitted values; the field is never compared against a literal
 * anywhere in the estate, being only moved into a before-image field, compared field-to-field against
 * that before-image for the card-update optimistic-lock check, or moved into a screen output field;
 * and all 50 seeded card records carry {@code Y} at byte offset 91, a single distinct value, so the
 * seed data cannot reveal the vocabulary either.
 *
 * <p>The vocabulary was therefore recovered from the only program that validates the field, the
 * card-update program. It declares a one-character yes/no check field initialised to {@code N} and
 * carrying a level-88 condition name whose two permitted codes are {@code Y} and {@code N}; its
 * card-status editor routes the submitted value through that same check field, after first treating
 * low-values, spaces and zeros as a not-supplied condition. The shared editor in the account-update
 * program corroborates the same two-value vocabulary and rejects any other value with a message naming
 * those two codes. The two constants below are declared in the order the legacy condition name lists
 * its values, and {@link #isActive()} evaluates its cases in that same order, so the translated
 * condition ordering matches the source.
 *
 * <p><strong>Validation-flag states are excluded on purpose.</strong> The account-update program's
 * parallel level-88 group declares two further one-character states beside the pair: a not-OK state
 * coded {@code '0'} and a blank state coded {@code 'B'}. Neither is a card status - both are states of
 * the <em>validation flag</em> that drives field-level error display, recording respectively that a
 * submitted value failed validation and that the field was left blank. Neither is ever stored in the
 * field or written to the 150-byte record, so admitting them here would invent two card statuses the
 * estate does not have. They belong to the field-error surface, which exposes per-field MISSING and
 * INVALID states.
 *
 * <p><strong>Why an unmapped code is tolerated rather than rejected.</strong> Only the online
 * card-update program validates this field; the batch programs that read card records take the status
 * straight from the file and never check it, and the relational column replacing byte 91 is a plain
 * one-character column with no check constraint. A file-sourced value outside the two known codes
 * therefore flows through the legacy system untouched and must flow through this lookup untouched too.
 * Consequently neither lookup ever throws: an unrecognised code yields {@link Optional#empty()}, and
 * there is deliberately no synthetic {@code UNKNOWN}-style constant to absorb a miss, because such a
 * constant would be a value the estate never produces. Callers needing a boolean compose the two
 * members, so an absent or unrecognised code answers {@code false}:
 *
 * <pre>{@code
 * boolean active = CardStatus.fromCode(card.getActiveStatus())
 *                            .map(CardStatus::isActive)
 *                            .orElse(false);
 * }</pre>
 *
 * <p><strong>Deliberately not the persistence type.</strong> This is a pure value type carrying no
 * persistence annotation and no attribute converter, and driving no schema. The card entity keeps its
 * status column as a raw one-character string, so translation from raw code to constant happens in the
 * service layer through the lookups below. Persisting the enum directly would be wrong in either
 * mapping: a string mapping stores the constant name, which a one-character column cannot hold, and an
 * ordinal mapping needs an integer column the record layout does not have.
 *
 * <p><strong>Representation.</strong> The raw code is carried as a {@code char} rather than a
 * single-character {@link String}, because the legacy field is exactly one character wide, so a
 * {@code char} makes that width a property of the type and leaves no room for a longer value that
 * would invite trimming or case folding. No normalization of any kind is applied before lookup, which
 * is faithful to the legacy comparison: it tested the byte as supplied, so a lowercase {@code y} is not
 * an active status. Instances are immutable and the lookup index is built once and unmodifiable, so
 * this type is safe for concurrent use.
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
