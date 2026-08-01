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

import java.util.Map;
import java.util.Optional;

/**
 * Account active-status flag, translated from the legacy field {@code ACCT-ACTIVE-STATUS}, which is
 * declared {@code PIC X(01)} and occupies byte offset 12 of the 300-byte account record, immediately
 * after the eleven-digit key.
 *
 * <p>The single character is carried as a {@code char} rather than a one-character {@link String}:
 * {@code PIC X(01)} is exactly one byte wide, so a {@code char} makes an over-length code impossible
 * to construct and removes the null case from the constant definitions entirely. Callers holding the
 * raw column value or a fixed-width record slice as a {@link String} use {@link #fromCode(String)},
 * which tolerates a null, empty or over-length value without throwing.
 *
 * <p><strong>How the Y/N vocabulary was established.</strong> This is recorded because the vocabulary
 * was recovered by investigation rather than read off the record layout, and a future reader should
 * not think the two values were invented. Three negative findings hold across the estate: the record
 * copybook attaches no level-88 condition name to the field, so it enumerates no values at all; the
 * field is never compared against a literal anywhere, its only uses being moves to a screen field, to
 * the communication area and to a before-image field, a field-to-field comparison against that
 * before-image for the optimistic-lock check, and a diagnostic display; and all 50 seeded account
 * records carry {@code Y} at byte 12, a single distinct value, so the seed data cannot reveal the
 * vocabulary either. The vocabulary lives instead in the account-update program, the only program that
 * validates this field: it declares a shared yes/no work field whose level-88 enumerates exactly
 * {@code 'Y'} and {@code 'N'}, declares an account-status validation flag with a matching level-88
 * over the same two values, routes the submitted status through that shared editor, and the editor
 * paragraph states in its own leading comment that the value must be {@code Y} or {@code N}.
 *
 * <p><strong>Why {@code '0'} and {@code 'B'} are not constants here.</strong> The same level-88 group
 * also carries {@code '0'} for not-OK and {@code 'B'} for blank, but those are states of the
 * <em>validation flag</em> rather than values of the account status: {@code '0'} records that a
 * submitted value failed validation and {@code 'B'} that the field was left blank. Neither is ever
 * stored in the field or written to the 300-byte record, so admitting either as a status would invent
 * an account state the estate does not have. Both belong to the field-error surface, which exposes
 * them per field as MISSING and INVALID.
 *
 * <p><strong>Why an unmapped code is tolerated rather than rejected.</strong> Both lookups return an
 * empty {@link Optional} for any code outside the vocabulary and never throw, and no synthetic
 * fallback constant exists. The reason is verified rather than defensive: only the online update
 * program validates this field, the batch programs that read account records take the status straight
 * from the file without validating it, and the relational column is a plain one-character string with
 * no check constraint. A file-sourced value outside the vocabulary therefore flows through the legacy
 * system untouched and must flow through this lookup the same way.
 *
 * <p>Lookup applies no case folding and no trimming, because the legacy editor tests the raw
 * character, so a lowercase {@code y} is not an active status. {@link #fromCode(String)} tests absence
 * before it tests the vocabulary, mirroring the editor's evaluation order; both of the editor's
 * failure outcomes converge on an empty {@link Optional} here, because both of the flag states they
 * set are validation states rather than storable statuses.
 *
 * <p>This is a pure value type carrying no persistence annotation and no attribute converter - the
 * account entity deliberately keeps the status as a raw one-character column, and translation from
 * code to constant happens in the service layer, so nothing in the domain layer depends on this type
 * being persistable.
 */
public enum AccountStatus {

    /**
     * The account is active. Raw code {@code 'Y'}.
     *
     * <p>This is the only value present in the seeded account data, where all 50
     * records carry it at byte 12.</p>
     */
    ACTIVE('Y'),

    /**
     * The account is not active. Raw code {@code 'N'}.
     *
     * <p>Admitted by {@code FLG-ACCT-STATUS-ISVALID} and by the shared yes/no
     * editor, and used as the initial value of the shared yes/no work field, but
     * absent from the seeded account data.</p>
     */
    INACTIVE('N');

    /**
     * Index from raw one-character code to constant.
     *
     * <p>Immutable and built exactly once during class initialisation.
     * {@link Map#of(Object, Object, Object, Object)} returns an unmodifiable map,
     * so there is no mutable static state and no lazily populated cache. Both
     * constants are enumerated explicitly so that the two-value contract of this
     * type is visible at the point of indexing.</p>
     */
    private static final Map<Character, AccountStatus> BY_CODE =
            Map.of(ACTIVE.code, ACTIVE, INACTIVE.code, INACTIVE);

    /** The raw one-character code as it appears at byte 12 of the account record. */
    private final char code;

    /**
     * Binds a constant to its raw one-character code.
     *
     * @param code the single character stored in {@code ACCT-ACTIVE-STATUS}
     */
    AccountStatus(final char code) {
        this.code = code;
    }

    /**
     * Returns the raw one-character code this constant represents.
     *
     * <p>This is the value that is written to and read from byte 12 of the
     * 300-byte account record and from the one-character status column. The
     * constant name is never the stored form.</p>
     *
     * @return {@code 'Y'} for {@link #ACTIVE} and {@code 'N'} for
     *         {@link #INACTIVE}
     */
    public char getCode() {
        return this.code;
    }

    /**
     * Reports whether this constant denotes an active account.
     *
     * <p>The switch covers every constant and therefore needs no default branch;
     * adding a constant without extending this switch would fail compilation
     * rather than silently fall through to a wrong answer. The branches are
     * listed in the order the legacy level-88 lists its values, {@code 'Y'}
     * before {@code 'N'}.</p>
     *
     * @return {@code true} only for {@link #ACTIVE}
     */
    public boolean isActive() {
        return switch (this) {
            case ACTIVE -> true;
            case INACTIVE -> false;
        };
    }

    /**
     * Resolves a raw one-character code to its constant.
     *
     * <p>Never throws. A code outside the vocabulary yields an empty result,
     * which is the faithful translation of a legacy system whose batch readers
     * accept whatever the file holds. No case folding is applied, so a lowercase
     * {@code y} does not resolve.</p>
     *
     * @param code the single character read from byte 12 of the account record
     * @return the matching constant, or an empty {@link Optional} if the
     *         character is not part of the vocabulary
     */
    public static Optional<AccountStatus> fromCode(final char code) {
        return Optional.ofNullable(BY_CODE.get(Character.valueOf(code)));
    }

    /**
     * Resolves a raw status column value or fixed-width record slice to its
     * constant.
     *
     * <p>Never throws. Absence is tested first and the vocabulary second, which
     * mirrors the evaluation order of {@code 1220-EDIT-YESNO}. A null value, an
     * empty value and a value that is not exactly one character wide are all
     * treated as absent and yield an empty result; an over-length value is
     * rejected rather than truncated, because the legacy system performs no such
     * truncation. A one-character value that is not part of the vocabulary,
     * including a blank and including a lowercase letter, also yields an empty
     * result.</p>
     *
     * @param code the raw status value, which may be {@code null}
     * @return the matching constant, or an empty {@link Optional} if the value is
     *         absent, not exactly one character wide, or not part of the
     *         vocabulary
     */
    public static Optional<AccountStatus> fromCode(final String code) {
        if (code == null || code.length() != 1) {
            return Optional.empty();
        }
        return fromCode(code.charAt(0));
    }

    /**
     * Reports whether a raw status column value denotes an active account.
     *
     * <p>Never throws, and answers {@code false} for a value that is absent or
     * outside the vocabulary as well as for an explicitly inactive account. This
     * is the predicate the service layer uses when it holds the raw
     * one-character column value rather than a resolved constant.</p>
     *
     * @param code the raw status value, which may be {@code null}
     * @return {@code true} only when the value resolves to {@link #ACTIVE}
     */
    public static boolean isActiveCode(final String code) {
        final Optional<AccountStatus> resolved = fromCode(code);
        return resolved.isPresent() && resolved.get().isActive();
    }
}
