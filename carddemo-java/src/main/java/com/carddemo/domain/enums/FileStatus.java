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
 * Raw two-character COBOL file-status codes as they occur in the AWS CardDemo estate: a pure value type
 * carrying the code and nothing else, with no framework dependency, no persistence mapping and
 * deliberately no classification of a code into a coarser outcome.
 *
 * <p>The code is text, never a number, so that the leading zero survives and the implementor-defined
 * {@code 9x} class - whose second character is a binary value rather than a digit - is not
 * misrepresented.
 *
 * <p><strong>Declared set: eleven constants, of which nine are source-observed.</strong> A census of the
 * estate's 28 programs found exactly nine distinct status literals, and all nine are declared here; only
 * four of them are genuinely compared against a status-bearing field, the rest occurring in unrelated
 * roles. Two further constants - duplicate key and file not found - are declared because prior
 * specification text cites them, and each is marked on its own declaration as documented but never
 * compared in the source; no behaviour depends on, branches on or special-cases either (decision-log
 * D-22). The observed subset is therefore a proper subset of the declared set, and the difference is
 * documentation reach rather than exercised vocabulary.
 *
 * <p><strong>Why this enum stops at raw codes.</strong> The legacy programs normalize a status into a
 * coarse OK / end-of-file / error result and branch on that, not on the raw code. That tri-state belongs
 * one layer up, in the batch step template, and must never be declared in this package: normalizing here
 * would put translation logic in the domain layer, and collapsing the codes risks folding end-of-file
 * into error - end-of-file is how a sequential read loop terminates normally in nine of the ten batch
 * programs, so erasing the distinction would turn successful jobs into abends (decision-log D-21).
 *
 * <p>{@link #fromCode(String)} answers an undeclared or {@code null} code with an empty
 * {@link Optional} rather than throwing, and there is no synthetic unknown constant: a live data set can
 * return a code these programs never test, and a lookup that threw would break the diagnostic path that
 * exists to report it.
 */
public enum FileStatus {

    SUCCESS("00"),

    SUCCESS_QUALIFIED("01"),

    DUPLICATE_ALTERNATE_KEY("02"),

    RECORD_LENGTH_MISMATCH("04"),

    OPTIONAL_FILE_CREATED("05"),

    /**
     * At end: no next logical record exists. This is the normal termination of a sequential read loop and
     * must never be treated as an error; see {@link #isEndOfFile()}.
     */
    END_OF_FILE("10"),

    AT_END_QUALIFIED("12"),

    /**
     * Documented but unexercised: cited by prior specification text for duplicate-key handling, yet
     * compared nowhere in the legacy source (decision-log D-22).
     */
    DUPLICATE_KEY("22"),

    RECORD_NOT_FOUND("23"),

    PERMANENT_ERROR("31"),

    /**
     * Documented but unexercised: cited by prior specification text for file-not-found handling, yet
     * compared nowhere in the legacy source (decision-log D-22).
     */
    FILE_NOT_FOUND("35");

    private static final Map<String, FileStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(status -> status.code, status -> status));

    private final String code;

    FileStatus(final String statusCode) {
        this.code = statusCode;
    }

    public String getCode() {
        return this.code;
    }

    /**
     * Resolves a raw file status to its constant. Never throws: an empty result means the value falls
     * outside the declared vocabulary, which is a legitimate runtime outcome rather than a programming
     * error, and {@code null} is answered the same way so that an unset status cannot fail the diagnostic
     * path that exists to report it.
     *
     * @param code the raw two-character status to resolve; may be {@code null}
     * @return the matching constant, or an empty {@link Optional}
     */
    public static Optional<FileStatus> fromCode(final String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code));
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isEndOfFile() {
        return this == END_OF_FILE;
    }
}
