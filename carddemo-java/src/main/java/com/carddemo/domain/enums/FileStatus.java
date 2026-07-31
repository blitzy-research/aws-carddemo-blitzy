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

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Raw two-byte COBOL {@code FILE STATUS} codes as they occur in the AWS CardDemo mainframe
 * estate.
 *
 * <p>This enum is the "status enums" half of the migration requirement <em>"FILE STATUS codes
 * to exception handling plus status enums; every file status code mapped to equivalent error
 * handling"</em>. It is a pure value type. It carries the raw code and nothing else: no
 * framework dependency, no persistence mapping, no logging, and deliberately no logic that
 * classifies a code into a coarser outcome, because the legacy programs do that classification
 * one layer up. See <em>Why this enum stops at raw codes</em> below, which is the single most
 * important thing to understand before extending this type.</p>
 *
 * <p><strong>Provenance.</strong> Derived from the COBOL estate at source checkout commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated {@code 2022-07-19}. No COBOL source text is
 * reproduced anywhere in this file; the legacy tree is cited by member name, paragraph name,
 * field name, line number and code value only, and is never copied into this module.</p>
 *
 * <p><strong>Representation.</strong> A COBOL {@code FILE STATUS} data item is two characters
 * wide: status-key-1 carries the outcome class and status-key-2 qualifies it. The estate splits
 * it into two one-character subfields, for example {@code ACCTFILE-STAT1} and
 * {@code ACCTFILE-STAT2} under {@code ACCTFILE-STATUS} in {@code app/cbl/CBACT01C.cbl}
 * (L46-L48). Each constant below therefore carries its code as a two-character {@code String}
 * with the leading zero preserved: the success code is {@code "00"}, never the number zero.
 * Treating a status as a number would lose the leading zero, and would also misrepresent the
 * implementor-defined {@code 9x} class in which status-key-2 is a binary value rather than a
 * digit.</p>
 *
 * <p><strong>Code vocabulary, and how it was established.</strong> A census over
 * {@code app/cbl} enumerated the distinct two-digit quoted literals present in the estate's 28
 * COBOL programs. That census yields exactly nine values, and all nine are declared here:
 * {@code 00}, {@code 01}, {@code 02}, {@code 04}, {@code 05}, {@code 10}, {@code 12},
 * {@code 23} and {@code 31}. Four of the nine are genuinely compared against a status-bearing
 * field:</p>
 * <ul>
 *   <li>{@code 00} is compared on 73 lines that name a {@code *-STATUS} field. It is the
 *       dominant success test of the estate.</li>
 *   <li>{@code 10} is compared on 7 such lines. It is how every batch read loop terminates
 *       normally.</li>
 *   <li>{@code 04} is compared at 9 sites in {@code app/cbl/CBSTM03A.CBL}, always as an
 *       accepted alternative to {@code 00}. It reaches those sites through the subprogram call
 *       linkage: {@code app/cbl/CBSTM03B.CBL} copies each of its four file statuses into
 *       {@code LK-M03B-RC}, a {@code PIC X(02)} field (L109), which the caller receives as
 *       {@code WS-M03B-RC}, also {@code PIC X(02)} (L80).</li>
 *   <li>{@code 23} appears at three comparison sites, of which exactly one branches on it
 *       alone: {@code app/cbl/CBACT04C.cbl} L436, inside paragraph
 *       {@code 1200-GET-INTEREST-RATE}, which is the trigger for the DEFAULT disclosure-group
 *       fallback. The other two sites, {@code app/cbl/CBACT04C.cbl} L422 and
 *       {@code app/cbl/CBTRN02C.cbl} L481, accept {@code 23} alongside {@code 00} rather than
 *       branching on it. That single standalone comparison is load bearing: without it the
 *       interest run has no rate to apply to an account whose disclosure group is absent.</li>
 * </ul>
 * <p>The other five values in the vocabulary, {@code 01}, {@code 02}, {@code 05}, {@code 12}
 * and {@code 31}, are never compared against a status field in the estate; they occur in
 * unrelated roles such as transaction type and category codes and calendar bounds. They are
 * declared here so the enum spans the whole observed vocabulary and so a value that a real
 * data set can legitimately return is nameable rather than anonymous. Their standard COBOL
 * meaning is documented on each constant, and no behaviour in this module branches on any of
 * them.</p>
 *
 * <p><strong>Unrecognised codes never throw.</strong> {@link #fromCode(String)} returns an
 * empty {@link Optional} for any value outside the declared set, and for {@code null}. The
 * declared set is the vocabulary the estate exercises, not the vocabulary the runtime can
 * produce: VSAM and QSAM can return codes these programs never test, and the estate itself
 * proves the point, because paragraph {@code 9910-DISPLAY-IO-STATUS} in
 * {@code app/cbl/CBACT01C.cbl} (L176-L189) has a dedicated path for a status that is not
 * numeric or whose status-key-1 is {@code 9}. A lookup that threw would turn a diagnosable I/O
 * condition into an unrelated failure at exactly the moment the diagnostics matter most. There
 * is deliberately no synthetic {@code UNKNOWN} constant either: absence is modelled as absence,
 * because the legacy code has no default status value to be faithful to.</p>
 *
 * <p><strong>Why this enum stops at raw codes.</strong> The COBOL programs do not branch on the
 * raw two-byte status. They normalise it first and then branch on the coarser result. Paragraph
 * {@code 1000-ACCTFILE-GET-NEXT} in {@code app/cbl/CBACT01C.cbl} (L92-L116) is the exemplar
 * that each of the eight batch programs declaring an {@code APPL-RESULT} item repeats: a raw
 * status of {@code 00} sets {@code APPL-RESULT} to 0, a raw status of {@code 10} sets it to 16,
 * and anything else sets it to 12. The code then
 * tests the level-88 condition names {@code APPL-AOK} (declared {@code VALUE 0} at L62) and
 * {@code APPL-EOF} (declared {@code VALUE 16} at L63). The {@code APPL-EOF} branch sets an
 * end-of-file flag and is a normal loop terminator; the remaining branch displays an error,
 * moves the raw status into a display field and abends. {@code APPL-RESULT} is referenced on
 * 223 lines across {@code app/cbl}, so it, and not the raw code, is what the programs actually
 * test.</p>
 *
 * <p>That coarse OK / EOF / ERROR tri-state therefore belongs one layer up, as a nested type or
 * as behaviour inside the batch step template or the file maintenance service, and it must
 * never be declared in this package. A top-level {@code IoOutcome} type and a
 * {@code FileStatusNormalizer} type are both forbidden anywhere under {@code src/main/java},
 * for two reasons. First, normalising here would put translation logic inside the domain layer,
 * which may depend on nothing above it. Second, and decisively, collapsing the raw codes into a
 * tri-state at this level risks folding end-of-file into error, and end-of-file is how a
 * sequential read loop terminates normally: {@code 10} is tested in nine of the ten batch
 * programs, so erasing the distinction would turn successful jobs into abends. This enum keeps
 * the raw codes, unclassified, precisely so that the distinction survives to the layer entitled
 * to make it.</p>
 *
 * <p><strong>Not a persistent type.</strong> This enum carries no persistence annotation, is
 * never mapped to a column, and drives no schema. String-valued enum persistence would store
 * the constant name rather than the two-character code and would not fit the narrow column the
 * legacy layout implies; ordinal persistence would store a position that carries no meaning in
 * the estate. Translation between a raw code and a constant is a service-layer concern and is
 * performed through {@link #fromCode(String)}.</p>
 *
 * @see #fromCode(String)
 */
public enum FileStatus {

    /**
     * {@code 00} - successful completion. Status class 0.
     *
     * <p>The dominant status test of the estate: compared on 73 lines that name a
     * {@code *-STATUS} field, distributed across the eight batch programs that normalise a file
     * status and spanning their open, read, write, update and close paragraphs. The online tier
     * contributes none of that figure, because no CICS program in the estate declares a
     * {@code FILE STATUS} item; the 17 online programs test the command response condition
     * instead.</p>
     */
    SUCCESS("00"),

    /**
     * {@code 01} - status class 0, successful completion with an implementor-defined qualifier
     * of 1.
     *
     * <p>Present in the estate's two-digit literal vocabulary but never compared against a
     * status field; its occurrences are a transaction type code and a start-of-month day
     * literal. Declared for vocabulary completeness only; no behaviour branches on it.</p>
     */
    SUCCESS_QUALIFIED("01"),

    /**
     * {@code 02} - successful completion, duplicate key detected on a non-unique alternate
     * index. Status class 0.
     *
     * <p>Every alternate index in the estate is defined with {@code NONUNIQUEKEY} and none with
     * {@code UNIQUEKEY}, so a duplicate alternate key is an expected condition there rather than
     * an exceptional one. The code is nevertheless never compared against a status field in the
     * COBOL. Declared for vocabulary completeness only; no behaviour branches on it.</p>
     */
    DUPLICATE_ALTERNATE_KEY("02"),

    /**
     * {@code 04} - successful completion, record length mismatch: the record read does not
     * conform to the fixed length attributes of the file. Status class 0.
     *
     * <p>Compared at 9 sites in {@code app/cbl/CBSTM03A.CBL}, always as an accepted
     * alternative to {@code 00} on the value returned through the {@code CBSTM03B} call
     * linkage. Whether {@code 04} is acceptable is a decision each of those call sites makes
     * for itself, so this enum does not fold it into {@link #isSuccess()}.</p>
     */
    RECORD_LENGTH_MISMATCH("04"),

    /**
     * {@code 05} - successful completion; an optional file was not present when it was opened,
     * so it was created. Status class 0.
     *
     * <p>Present in the estate's two-digit literal vocabulary but never compared against a
     * status field; its single occurrence is a transaction category code. Declared for
     * vocabulary completeness only; no behaviour branches on it.</p>
     */
    OPTIONAL_FILE_CREATED("05"),

    /**
     * {@code 10} - at end: no next logical record exists. Status class 1.
     *
     * <p>Compared on 7 lines that name a {@code *-STATUS} field, and at 4 further sites as a
     * clause of an {@code EVALUATE} over a status or return-code value
     * ({@code app/cbl/CBTRN03C.cbl} L225 and L254, {@code app/cbl/CBSTM03A.CBL} L356 and L841),
     * giving 11 occurrences in total across nine of the ten batch programs. This is the normal
     * termination condition of a sequential read loop, which is why it must never be treated as
     * an error. See {@link #isEndOfFile()}.</p>
     */
    END_OF_FILE("10"),

    /**
     * {@code 12} - status class 1, at end with an implementor-defined qualifier of 2.
     *
     * <p>Present in the estate's two-digit literal vocabulary but never compared against a
     * status field; its occurrences are calendar month bounds. Declared for vocabulary
     * completeness only; no behaviour branches on it.</p>
     */
    AT_END_QUALIFIED("12"),

    /**
     * {@code 22} - invalid key: an attempt was made to write a record that would create a
     * duplicate prime key or a duplicate unique alternate key. Status class 2.
     *
     * <p><strong>Documented but unexercised.</strong> This code is cited by the prior
     * specification for duplicate-key handling, but a census of {@code app/cbl} returns zero
     * occurrences of the literal: it is compared nowhere in the legacy source. It is declared
     * so the documented vocabulary stays discoverable, and no behaviour in this module depends
     * on, branches on, or special-cases it. The discrepancy between the prior specification and
     * the source is recorded in {@code docs/decision-log.md}.</p>
     */
    DUPLICATE_KEY("22"),

    /**
     * {@code 23} - invalid key: no record was found with the specified key. Status class 2.
     *
     * <p>Compared at three sites. Exactly one of them branches on {@code 23} alone,
     * {@code app/cbl/CBACT04C.cbl} L436 in paragraph {@code 1200-GET-INTEREST-RATE}, and that
     * site is the trigger for the DEFAULT disclosure-group fallback: the group key is replaced
     * with the default group and the rate is read again. The other two sites,
     * {@code app/cbl/CBACT04C.cbl} L422 and {@code app/cbl/CBTRN02C.cbl} L481, accept
     * {@code 23} alongside {@code 00} instead of branching on it, so a missing record is not
     * an error on those paths.</p>
     */
    RECORD_NOT_FOUND("23"),

    /**
     * {@code 31} - status class 3, permanent error with an implementor-defined qualifier of 1.
     *
     * <p>Present in the estate's two-digit literal vocabulary but never compared against a
     * status field; its occurrences are calendar day bounds. Declared for vocabulary
     * completeness only; no behaviour branches on it.</p>
     */
    PERMANENT_ERROR("31"),

    /**
     * {@code 35} - permanent error: an attempt was made to open a file that is not optional
     * and is not present. Status class 3.
     *
     * <p><strong>Documented but unexercised.</strong> This code is cited by the prior
     * specification for file-not-found handling, but a census of {@code app/cbl} returns zero
     * occurrences of the literal: it is compared nowhere in the legacy source. It is declared
     * so the documented vocabulary stays discoverable, and no behaviour in this module depends
     * on, branches on, or special-cases it. The discrepancy between the prior specification and
     * the source is recorded in {@code docs/decision-log.md}.</p>
     */
    FILE_NOT_FOUND("35");

    /**
     * Immutable index from raw two-character code to constant, built once while this class is
     * initialised and never mutated afterwards.
     *
     * <p>The index is derived from {@link #values()} instead of being restated as a literal
     * table, so the constants above stay the single source of truth and the lookup cannot drift
     * out of step with them. {@code Collectors.toUnmodifiableMap} is used deliberately: as well
     * as producing a genuinely unmodifiable map, it rejects a duplicate key by throwing while
     * the class initialises, so a copy-and-paste slip that gave two constants the same code
     * would fail loudly at startup rather than silently leaving one of them unreachable by
     * lookup.</p>
     */
    private static final Map<String, FileStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(status -> status.code, status -> status));

    /**
     * The raw COBOL file status this constant represents: exactly two characters, with any
     * leading zero preserved.
     */
    private final String code;

    /**
     * Binds a constant to the raw status code it represents.
     *
     * @param statusCode the two-character COBOL file status; never {@code null}, because every
     *                   value is a literal declared in the constant table above
     */
    FileStatus(final String statusCode) {
        this.code = statusCode;
    }

    /**
     * Returns the raw COBOL file status this constant represents.
     *
     * @return the status code, exactly two characters wide, with any leading zero preserved
     */
    public String getCode() {
        return this.code;
    }

    /**
     * Resolves a raw COBOL file status to its constant.
     *
     * <p>Absence is modelled explicitly and this method never throws. An empty result means the
     * supplied value falls outside the vocabulary declared here, which is a legitimate runtime
     * outcome rather than a programming error: the declared set covers what the estate
     * exercises, while a live data set can return more. {@code null} is answered with an empty
     * result for the same reason, so an unset status field cannot fail the very diagnostic path
     * that exists to report it.</p>
     *
     * @param code the raw two-character status to resolve; may be {@code null}
     * @return the matching constant, or an empty {@link Optional} if {@code code} is
     *         {@code null} or is not one of the declared values
     */
    public static Optional<FileStatus> fromCode(final String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /**
     * Reports whether this is the success status, {@code 00}.
     *
     * <p>True for {@link #SUCCESS} alone. It is deliberately not true for the other status
     * class 0 codes, because the estate does not treat them as interchangeable: the
     * normalisation in {@code app/cbl/CBACT01C.cbl} (L92-L116) maps every status other than
     * {@code 00} and {@code 10} onto the error result, and the only paths that accept
     * {@code 04} alongside {@code 00} do so per call site rather than as a general rule.
     * Widening this predicate would silently convert those errors into successes.</p>
     *
     * @return {@code true} if this constant is {@link #SUCCESS}
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Reports whether this is the at-end status, {@code 10}.
     *
     * <p>True for {@link #END_OF_FILE} alone. This predicate and {@link #isSuccess()} are the
     * two tests that drive every sequential read loop in the batch tier, and they are kept
     * distinct because end of file terminates such a loop normally whereas every other
     * non-success status is terminal.</p>
     *
     * @return {@code true} if this constant is {@link #END_OF_FILE}
     */
    public boolean isEndOfFile() {
        return this == END_OF_FILE;
    }
}
