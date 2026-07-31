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
 * Account active-status flag, translated from the CardDemo COBOL field
 * {@code ACCT-ACTIVE-STATUS}.
 *
 * <h2>Legacy layout</h2>
 *
 * <p>{@code ACCT-ACTIVE-STATUS} is declared {@code PIC X(01)} at
 * {@code app/cpy/CVACT01Y.cpy} line 6. It occupies byte offset 12 of the 300-byte
 * {@code ACCOUNT-RECORD}, immediately after {@code ACCT-ID}, an eleven-digit key
 * that occupies bytes 1 through 11.</p>
 *
 * <p>The single character is carried here as a {@code char} rather than as a
 * one-character {@link String}. That choice is deliberate and uniform across the
 * type: {@code PIC X(01)} is exactly one byte wide, so a {@code char} makes an
 * over-length code impossible to construct and removes the null case from the
 * constant definitions entirely. Callers that hold the raw column value or a
 * fixed-width record slice as a {@link String} use {@link #fromCode(String)},
 * which tolerates a null, empty or over-length value without throwing.</p>
 *
 * <h2>How the Y / N vocabulary was established</h2>
 *
 * <p>The vocabulary is recorded here because it was recovered by investigation
 * rather than read off the record layout, and a future reader should not think
 * the two values were invented. Three negative findings hold across the whole
 * legacy estate:</p>
 *
 * <ul>
 *   <li>{@code app/cpy/CVACT01Y.cpy} attaches no level-88 condition name to
 *       {@code ACCT-ACTIVE-STATUS}, so the copybook enumerates no values at
 *       all.</li>
 *   <li>The field is never compared against a literal anywhere. Its only uses
 *       are a move to a screen output field ({@code app/cbl/COACTVWC.cbl}
 *       line 473), a move to the communication area and to a before-image field
 *       ({@code app/cbl/COACTUPC.cbl} lines 3810 and 3819), a field-to-field
 *       comparison against that before-image for the optimistic-lock check
 *       ({@code app/cbl/COACTUPC.cbl} line 4115), and a diagnostic display
 *       ({@code app/cbl/CBACT01C.cbl} line 120).</li>
 *   <li>All 50 seeded account records in {@code app/data/ASCII/acctdata.txt}
 *       carry {@code Y} at byte 12, a single distinct value, so the seed data
 *       alone cannot reveal the vocabulary either.</li>
 * </ul>
 *
 * <p>The vocabulary lives instead in the account-update program, which is the
 * only program in the estate that validates this field.
 * {@code app/cbl/COACTUPC.cbl} lines 76 through 80 declare the shared yes/no
 * work field {@code WS-EDIT-YES-NO}, {@code PIC X(1)}, whose level-88
 * {@code FLG-YES-NO-ISVALID} enumerates exactly {@code 'Y'} and {@code 'N'}.
 * Lines 192 through 195 declare the account-status validation flag
 * {@code WS-EDIT-ACCT-STATUS} with the matching level-88
 * {@code FLG-ACCT-STATUS-ISVALID} over the same two values. Lines 1472 through
 * 1476 route the submitted active status through that shared editor, and the
 * editor paragraph {@code 1220-EDIT-YESNO} at lines 1856 through 1897 states in
 * its own leading comment that the value must be {@code Y} or {@code N}.</p>
 *
 * <h2>Why '0' and 'B' are not constants here</h2>
 *
 * <p>{@code FLG-ACCT-STATUS-NOT-OK} takes the value {@code '0'} and
 * {@code FLG-ACCT-STATUS-BLANK} takes the value {@code 'B'}, and both sit in the
 * same level-88 group as the two real codes. They are states of the
 * <em>validation flag</em>, not values of the account status: {@code '0'} records
 * that a submitted value failed validation and {@code 'B'} records that the field
 * was left blank. Neither is ever stored in {@code ACCT-ACTIVE-STATUS} and
 * neither is ever written to the 300-byte record, so admitting either one as a
 * status would invent an account state that the estate does not have. Those two
 * states belong to the field-error surface, which exposes them per field as
 * MISSING and INVALID.</p>
 *
 * <h2>Why an unmapped code is tolerated rather than rejected</h2>
 *
 * <p>{@link #fromCode(char)} and {@link #fromCode(String)} return an empty
 * {@link Optional} for any code outside the vocabulary and never throw, and no
 * synthetic fallback constant exists. The reason is specific and verified rather
 * than defensive: only the online update program validates this field. The batch
 * programs that read account records, namely {@code app/cbl/CBACT01C.cbl},
 * {@code app/cbl/CBTRN02C.cbl} and {@code app/cbl/CBACT04C.cbl}, take the status
 * straight from the file without validating it, and the relational column is a
 * plain one-character string with no check constraint. A file-sourced value
 * outside the vocabulary therefore flows through the legacy system untouched, and
 * it must flow through this lookup the same way.</p>
 *
 * <p>Lookup applies no case folding and no trimming. The legacy editor tests the
 * raw character, so a lowercase {@code y} is not an active status.</p>
 *
 * <p>{@link #fromCode(String)} tests absence before it tests the vocabulary,
 * mirroring the evaluation order of {@code 1220-EDIT-YESNO}, which tests for a
 * missing value first and only then tests validity. Both of the editor's failure
 * outcomes converge on an empty {@link Optional} here, because both of the flag
 * states they set are validation states rather than storable statuses.</p>
 *
 * <h2>Not a persistence mapping</h2>
 *
 * <p>This type is a pure value type. It carries no persistence annotation and no
 * attribute converter, and the account entity deliberately keeps
 * {@code acct_active_status} as a raw one-character string column. Translation
 * from raw code to constant happens in the service layer, so nothing in the
 * domain layer depends on this type being persistable.</p>
 *
 * <p>Provenance of the translated source: legacy checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL source text is
 * reproduced here; only member names, field names, paragraph names, widths, byte
 * offsets, line numbers and codes are cited.</p>
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
