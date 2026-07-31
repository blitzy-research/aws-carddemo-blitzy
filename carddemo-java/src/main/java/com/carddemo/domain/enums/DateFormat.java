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
 * Date-format selector passed to the shared date-validation subprogram {@code CSUTLDTC}.
 *
 * <p><strong>The linkage contract being modelled.</strong> {@code CSUTLDTC} declares a
 * three-parameter linkage (member {@code app/cbl/CSUTLDTC.cbl}, lines 83 to 88):
 * <ul>
 *   <li>{@code LS-DATE} &mdash; the candidate date, {@code PIC X(10)};</li>
 *   <li>{@code LS-DATE-FORMAT} &mdash; the date-format selector, {@code PIC X(10)};</li>
 *   <li>{@code LS-RESULT} &mdash; the result block, {@code PIC X(80)}.</li>
 * </ul>
 * This enum models the <em>middle</em> parameter, and nothing else.
 *
 * <p>Callers declare a matching parameter group. In {@code app/cbl/CORPT00C.cbl} (lines 129
 * to 136) {@code CSUTLDTC-PARM} is a 10-character date, a 10-character format selector, and
 * an 80-character result block subdivided into a 4-character severity code, an 11-character
 * filler, a 4-character message number and a 61-character message. Those four widths sum to
 * 80, which is exactly the width the subprogram declares, so caller and callee agree.
 * {@code app/cbl/COTRN02C.cbl} declares the identical group at lines 62 to 69.
 *
 * <p><strong>The 10-character width is contractual, and the padding is part of the value.</strong>
 * The selector always occupies a fixed 10-character parameter slot, so the value carried by
 * every constant here is exactly 10 characters long. One of the two selectors is only eight
 * characters of mask and therefore carries two trailing space characters that fill the slot.
 * Those spaces are part of the transmitted value and are never removed: the construct-mapping
 * requirement covering inter-program calls obliges this migration to preserve data passed
 * between programs, and a lookup keyed on a shortened form would fail to match a value that
 * arrives from a caller's fixed-width work field. Constant values are likewise stored exactly
 * as the source declares them, with no case conversion applied.
 *
 * <p><strong>Exactly two selectors exist in the estate.</strong> They were established by
 * tracing every invocation of the subprogram &mdash; four at program level, two in
 * {@code CORPT00C} (lines 392 and 412) and two in {@code COTRN02C} (lines 393 and 413), plus a
 * fifth inside the procedural copybook {@code app/cpy/CSUTLDPY.cpy} (line 293). No further
 * selector value appears anywhere in the estate, so no further constant may be added here:
 * doing so would create a format the legacy system never passes and the subprogram was never
 * exercised with.
 *
 * <p><strong>Deliberately not modelled here.</strong> The 80-character result block, with its
 * severity code and message number, is a typed result owned by the date-validation service in
 * the service layer. This enum therefore carries no validation, no parsing, no calendar
 * arithmetic and no severity handling, and it builds no Java date-formatter object. It names a
 * legacy selector value and exposes a total lookup over those values; every behavioural
 * concern belongs to the service that invokes it.
 *
 * <p><strong>Unmapped input is reported, never guessed.</strong> {@link #fromValue(String)}
 * returns an empty {@link Optional} rather than throwing, and there is deliberately no
 * synthetic fallback constant. Silently substituting one of the two real selectors would
 * validate a date against the wrong mask and produce a wrong verdict, which is strictly worse
 * than reporting absence and letting the caller decide.
 *
 * <p><strong>Provenance.</strong> Translated from the CardDemo mainframe estate read at commit
 * SHA {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}. No COBOL source text is
 * reproduced in this file; only member names, field names, field widths and the two selector
 * data values are cited.
 *
 * <p>Instances are immutable and this type is therefore thread-safe.
 */
public enum DateFormat {

    /**
     * The hyphenated selector, {@code YYYY-MM-DD}, which is exactly 10 characters and needs no
     * padding.
     *
     * <p>Declared as the initial value of the 10-character format work field
     * {@code WS-DATE-FORMAT} at {@code app/cbl/CORPT00C.cbl} line 72, and identically at
     * {@code app/cbl/COTRN02C.cbl} line 60. All four program-level call sites pass that work
     * field, so this is the selector used by the report date range and by the transaction-add
     * origination and processing date edits.
     */
    YYYY_MM_DD("YYYY-MM-DD"),

    /**
     * The compact selector: the eight characters {@code YYYYMMDD} followed by two space
     * characters, giving the contractual width of 10.
     *
     * <p>The procedural copybook {@code app/cpy/CSUTLDPY.cpy} places the eight-character
     * compact mask in the format work field at line 291 and invokes the subprogram at line 293.
     * The field it uses is declared eight characters wide in the companion working-storage
     * copybook {@code app/cpy/CSUTLDWY.cpy} (lines 58 to 59), while the subprogram addresses
     * that argument through a 10-character linkage item; the two bytes beyond the mask are
     * blank because the storage that follows is cleared immediately before the call. The value
     * the subprogram receives in its 10-character slot is therefore the mask plus two spaces,
     * which is what this constant carries.
     *
     * <p>The two trailing spaces are intentional and load-bearing. Removing them would break
     * the fixed-width parameter contract and would stop this constant from being found by
     * {@link #fromValue(String)}.
     */
    YYYYMMDD("YYYYMMDD  ");

    /**
     * Width, in characters, of the selector parameter slot: {@code LS-DATE-FORMAT} is
     * {@code PIC X(10)}, and both caller groups declare their selector field at the same width.
     * Every constant's value is validated against this width when the enum is initialised.
     */
    private static final int SELECTOR_LENGTH = 10;

    /**
     * Immutable lookup keyed on the exact, fully padded 10-character selector value.
     *
     * <p>Built once during class initialisation, after the constants above have been created.
     * {@link Map#of(Object, Object, Object, Object)} yields an unmodifiable map and rejects
     * duplicate keys, which additionally guarantees that the two selector values stay distinct.
     */
    private static final Map<String, DateFormat> BY_VALUE =
            Map.of(YYYY_MM_DD.value, YYYY_MM_DD,
                   YYYYMMDD.value, YYYYMMDD);

    /**
     * The exact 10-character value transmitted in the selector parameter slot, padding included.
     */
    private final String value;

    /**
     * Binds a constant to its selector value and enforces the contractual width.
     *
     * <p>The width check is a fail-fast invariant rather than input validation: it cannot be
     * triggered by any caller, only by a future edit to one of the two constants above. Making
     * it executable is what stops the padding from being lost silently.
     *
     * @param value the exact selector value, which must be {@value #SELECTOR_LENGTH} characters
     *              long, trailing padding included
     * @throws IllegalArgumentException if the value is not exactly the contractual width
     */
    DateFormat(final String value) {
        if (value.length() != SELECTOR_LENGTH) {
            throw new IllegalArgumentException(
                    "Date-format selector must be exactly " + SELECTOR_LENGTH
                            + " characters to fill the linkage parameter slot, but ["
                            + value + "] is " + value.length());
        }
        this.value = value;
    }

    /**
     * Returns the exact 10-character selector value, trailing padding included.
     *
     * <p>The returned string is what belongs in the fixed-width selector slot. It is returned
     * verbatim: no padding is removed and no case conversion is applied.
     *
     * @return the selector value, never {@code null} and always {@value #SELECTOR_LENGTH}
     *         characters long
     */
    public String getValue() {
        return value;
    }

    /**
     * Resolves a raw selector value to its constant, tolerating anything unrecognised.
     *
     * <p>Matching is exact and is performed against the fully padded 10-character form, because
     * that is the form a caller's fixed-width work field holds. A value that has had its
     * padding removed, or whose case differs, is not a selector this estate ever transmits and
     * is reported as absent rather than repaired.
     *
     * <p>This lookup is total: it never throws, and it never falls back to a default selector.
     * An empty result means the caller supplied something outside the two-value contract, and
     * the decision about how to treat that belongs to the caller.
     *
     * @param value the raw selector value to resolve; may be {@code null}
     * @return the matching constant, or an empty {@link Optional} if the value is {@code null}
     *         or is not one of the two selectors this estate transmits
     */
    public static Optional<DateFormat> fromValue(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_VALUE.get(value));
    }
}
