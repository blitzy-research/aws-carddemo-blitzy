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
 * Account active-status flag: the migrated vocabulary of {@code ACCT-ACTIVE-STATUS}, declared
 * {@code PIC X(01)} at zero-based offset 11 - one-based byte 12 - of the 300-byte account record,
 * immediately after the eleven-digit key.
 *
 * <p>The code is carried as a {@code char} rather than a one-character {@link String}, which makes
 * an over-length value impossible to construct. Callers holding a raw column value or a fixed-width
 * record slice use {@link #fromCode(String)}, which tolerates null, empty and over-length input.
 *
 * <p>The {@code Y}/{@code N} vocabulary was recovered by investigation rather than read off the
 * record layout, and the two values were not invented: the copybook attaches no condition name to
 * the field, no program compares the field against a literal outside the account-update program,
 * and all 50 seeded records carry {@code Y}. The account-update program is the only validator, and
 * the shared yes/no editor it routes the field through enumerates exactly these two values. That
 * editor's {@code '0'} and {@code 'B'} states are states of the <em>validation flag</em> - failed
 * and left blank - never values stored in the record, so admitting them here would invent an
 * account state the estate does not have; they surface per field as MISSING and INVALID instead.
 *
 * <p>An unmapped code yields an empty {@link Optional} and never throws, and no synthetic fallback
 * constant exists: only the online program validates this field, the batch readers take it straight
 * from the file, and the column carries no check constraint, so a value outside the vocabulary must
 * flow through untouched. No case folding or trimming is applied, so a lowercase {@code y} is not
 * an active status.
 *
 * <p>Pure value type: the account entity keeps the status as a raw one-character column and
 * code-to-constant translation happens in the service layer.
 */
public enum AccountStatus {
    /** Active. Raw code {@code 'Y'}, the only value present in the seeded account data. */
    ACTIVE('Y'),

    /** Not active. Raw code {@code 'N'}, admitted by the legacy editor but absent from the seed. */
    INACTIVE('N');

    private static final Map<Character, AccountStatus> BY_CODE =
            Map.of(ACTIVE.code, ACTIVE, INACTIVE.code, INACTIVE);

    private final char code;

    AccountStatus(final char code) {
        this.code = code;
    }

    /**
     * Returns the raw code this constant is stored as; the constant name is never the stored form.
     *
     * @return {@code 'Y'} for {@link #ACTIVE} and {@code 'N'} for {@link #INACTIVE}
     */
    public char getCode() {
        return this.code;
    }

    /**
     * @return {@code true} only for {@link #ACTIVE}
     */
    public boolean isActive() {
        return switch (this) {
            case ACTIVE -> true;
            case INACTIVE -> false;
        };
    }

    /**
     * Resolves a raw one-character code.
     *
     * @param code the character read from the account record or the status column
     * @return the matching constant, or empty when the character is outside the vocabulary
     */
    public static Optional<AccountStatus> fromCode(final char code) {
        return Optional.ofNullable(BY_CODE.get(Character.valueOf(code)));
    }

    /**
     * Resolves a raw status column value or fixed-width record slice.
     *
     * <p>Absence is tested before the vocabulary, mirroring the legacy editor's evaluation order. An
     * over-length value is rejected rather than truncated, because the legacy system truncates nothing.
     *
     * @param code the raw status value, which may be {@code null}
     * @return the matching constant, or empty when the value is absent, not exactly one character
     *         wide, or outside the vocabulary
     */
    public static Optional<AccountStatus> fromCode(final String code) {
        if (code == null || code.length() != 1) {
            return Optional.empty();
        }
        return fromCode(code.charAt(0));
    }

    /**
     * The predicate for callers holding the raw column value rather than a resolved constant.
     *
     * @param code the raw status value, which may be {@code null}
     * @return {@code true} only when the value resolves to {@link #ACTIVE}
     */
    public static boolean isActiveCode(final String code) {
        final Optional<AccountStatus> resolved = fromCode(code);
        return resolved.isPresent() && resolved.get().isActive();
    }
}
