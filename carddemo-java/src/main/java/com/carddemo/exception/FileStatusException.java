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
package com.carddemo.exception;

/**
 * Carries the raw two-character COBOL {@code FILE STATUS} of a failed file operation and interprets
 * nothing about it.
 *
 * <p>It carries; it does not classify. The legacy programs never branch on the two-byte status
 * directly: each normalises it into a coarse result - {@code "00"} to OK, {@code "10"} to
 * end-of-file, everything else to error - and branches on that instead ({@code app/cbl/CBACT01C.cbl}
 * L92-L116, a variable referenced on roughly 223 lines of the estate). This class models the error
 * arm alone, so it exposes the raw code and its two halves and nothing else: no end-of-file
 * predicate, no severity, no lookup table. Normalising into the tri-state outcome belongs to the
 * layer above, which is what keeps the end-of-file signal out of the error path (decision D-21).
 *
 * <p>That separation is enforced mechanically rather than documented: passing
 * {@link #STATUS_SUCCESS} or {@link #STATUS_END_OF_FILE} to a constructor raises
 * {@link IllegalArgumentException}, while every other well-formed two-character value is accepted.
 *
 * <p>Nine distinct status literals occur across the 28 programs - {@code 00}, {@code 01}, {@code 02},
 * {@code 04}, {@code 05}, {@code 10}, {@code 12}, {@code 23} and {@code 31} - of which only {@code 00},
 * {@code 10} and {@code 23} are ever compared, {@code 23} being the fallback that selects the default
 * disclosure group. That list is documentation and not a whitelist: a status the runtime reports but the
 * legacy source never tested must still be carried rather than swallowed. No code path may depend on a
 * status the source never compares, which specifically excludes {@code 22} and {@code 35} (decision
 * D-22).
 *
 * <p>Caller obligation, and the ordering matters: the legacy sequence at every I/O site is emit the
 * diagnostic, move the raw status into the display field, emit the status, and only then abend.
 * Callers must log the raw two-byte status through SLF4J <em>before</em> raising the abend, never
 * from a {@code catch} block that has already unwound past it and never only by way of this
 * exception's message. {@link #DISPLAY_PREFIX} exists so that status line is recognisable to an
 * operator. This type neither extends nor references the abend type, so the caller decides for
 * itself whether the condition warrants one.
 *
 * <p>The code is a {@link String} rather than an enum so this package imports nothing at all: an
 * enumeration of status codes belongs to the domain layer, and importing it here would invert the
 * layer direction.
 */
public class FileStatusException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final int CODE_LENGTH = 2;

    public static final String STATUS_SUCCESS = "00";

    public static final String STATUS_END_OF_FILE = "10";

    /** Operator-facing prefix of the legacy status line, carrying its four-character status slot. */
    public static final String DISPLAY_PREFIX = "FILE STATUS IS: NNNN";

    private final String code;

    private final String operation;

    private final String resourceName;

    public FileStatusException(String code, String operation, String resourceName) {
        super(validatedMessage(code, operation, resourceName));
        this.code = code;
        this.operation = orEmpty(operation);
        this.resourceName = orEmpty(resourceName);
    }

    public FileStatusException(String code, String operation, String resourceName, Throwable cause) {
        super(validatedMessage(code, operation, resourceName), cause);
        this.code = code;
        this.operation = orEmpty(operation);
        this.resourceName = orEmpty(resourceName);
    }

    public String code() {
        return this.code;
    }

    /**
     * @return the first of the two status bytes, uninterpreted
     */
    public char firstByte() {
        return this.code.charAt(0);
    }

    /**
     * @return the second of the two status bytes, uninterpreted
     */
    public char secondByte() {
        return this.code.charAt(1);
    }

    public String operation() {
        return this.operation;
    }

    public String resourceName() {
        return this.resourceName;
    }

    private static String validatedMessage(String code, String operation, String resourceName) {
        if (code == null) {
            throw new IllegalArgumentException("A COBOL FILE STATUS of exactly " + CODE_LENGTH
                    + " characters is required, but null was supplied.");
        }
        if (code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("A COBOL FILE STATUS must be exactly " + CODE_LENGTH
                    + " characters, but \"" + code + "\" has length " + code.length() + ".");
        }
        if (STATUS_SUCCESS.equals(code)) {
            throw new IllegalArgumentException("File status \"" + STATUS_SUCCESS
                    + "\" reports a successful operation, which is not an error and must not be"
                    + " raised as FileStatusException.");
        }
        if (STATUS_END_OF_FILE.equals(code)) {
            throw new IllegalArgumentException("File status \"" + STATUS_END_OF_FILE
                    + "\" reports end of file, which is a normal outcome and must not be raised as"
                    + " an error; handle it as the end-of-file arm instead.");
        }
        return composeMessage(code, orEmpty(operation), orEmpty(resourceName));
    }

    private static String composeMessage(String code, String normalisedOperation,
            String normalisedResource) {
        StringBuilder message = new StringBuilder(64);
        message.append("fileStatus=").append(code);
        if (!normalisedOperation.isEmpty()) {
            message.append(" operation=").append(normalisedOperation);
        }
        if (!normalisedResource.isEmpty()) {
            message.append(" resource=").append(normalisedResource);
        }
        return message.toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
