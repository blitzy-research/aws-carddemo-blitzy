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
 * Carries the raw two-character COBOL {@code FILE STATUS} value reported by a failed file operation,
 * and interprets nothing about it.
 *
 * <p><strong>It carries; it does not classify.</strong> In the legacy estate the two-byte status is
 * never branched on directly. Each batch program declares it as a two-character group split into two
 * single-character fields ({@code CBACT01C} lines 46-48, named on the {@code SELECT} at lines 29-33),
 * then normalises it into a coarse numeric result and branches on <em>that</em> ({@code CBACT01C} lines
 * 92-116): {@code "00"} maps to 0, {@code "10"} to 16 and everything else to 12. That coarse variable,
 * not the raw status, is what the read loops test, and it appears on roughly 223 lines of the estate.
 * This class models only the third arm, so it exposes the raw code and its two halves and nothing else:
 * no {@code isEndOfFile()}, no {@code isNotFound()}, no {@code severity()}, no lookup table and no
 * {@code switch} over status values. The legacy display routine behaves the same way &mdash;
 * {@code CBACT01C} lines 176-189 merely reformat the two bytes for the operator, binary-packing the
 * second byte when the pair is non-numeric or the first byte is {@code '9'} and zero-padding it
 * otherwise, and never ask what the code <em>means</em>. Normalising into the coarse
 * OK / end-of-file / error outcome belongs to the layer above, which is what stops the end-of-file
 * signal from being quietly folded into the error path. Recorded as decision log entry
 * <strong>D-21</strong>.
 *
 * <p><strong>This is the error arm only; end of file must never collapse into it.</strong> End of file
 * is a <em>normal</em> outcome of a sequential read, not a failure, and the rule is enforced
 * mechanically rather than merely documented: passing {@link #STATUS_END_OF_FILE} to a constructor
 * raises {@code IllegalArgumentException}. {@link #STATUS_SUCCESS} is rejected for the mirror-image
 * reason. Every other well-formed two-character value is accepted.
 *
 * <p><strong>Observed status vocabulary.</strong> A census across all 28 programs found exactly nine
 * distinct two-character literals: {@code 00}, {@code 01}, {@code 02}, {@code 04}, {@code 05},
 * {@code 10}, {@code 12}, {@code 23} and {@code 31}. Only {@code 00}, {@code 10} and {@code 23} are
 * compared in a status-testing context, {@code 23} being the fallback that selects the default
 * disclosure group in {@code CBACT04C} (lines 422 and 436). That list is documentation, not a
 * whitelist: this class accepts <em>any</em> two-character value, because a status the runtime reports
 * but the legacy source never tested must still be carried rather than swallowed. Statuses {@code 22}
 * and {@code 35} appear in prior documentation but in zero source members, so they are
 * documented-but-unexercised and no code path anywhere may depend on them &mdash; recorded as decision
 * log entry <strong>D-22</strong>.
 *
 * <p><strong>Caller obligation: log the status, then decide about abending.</strong> The legacy
 * sequence is identical at all three I/O sites of {@code CBACT01C} &mdash; the open (lines 144-147),
 * the read (lines 110-113) and the close (lines 162-165): emit the diagnostic, move the raw two-byte
 * status into the display field, emit the status, and only <em>then</em> abend; the online tier has the
 * same shape. Callers must reproduce that ordering: <strong>log the raw two-byte file status through
 * SLF4J before raising {@code AbendException}</strong>, never from inside a {@code catch} block that
 * has already unwound past the status, and never only by way of the exception message.
 * {@link #DISPLAY_PREFIX} exists so the status line is recognisable to an operator. The two types are
 * independent: this class does not extend, wrap, construct or reference {@code AbendException}, so the
 * caller logs first and then decides for itself whether the condition warrants an abend.
 *
 * <p><strong>The code is a {@code String} and not an enum</strong> so that this package stays free of
 * dependencies, importing nothing but the implicitly available {@code java.lang} types. Any
 * <em>enumeration</em> of status codes belongs to {@code com.carddemo.domain.enums} for the layers
 * above to use and must not be referenced from here: importing it would invert the layer direction the
 * service, batch, API and utility layers all rely on.
 *
 * <p>All state is assigned once in the constructor and every field is {@code final}, so instances are
 * immutable and safe to publish across threads. There are no setters and no static mutable state.
 */
public class FileStatusException extends RuntimeException {

    /**
     * Explicit, stable serialisation identity.
     *
     * <p>{@code Throwable} is {@code Serializable}, so the {@code serial} lint category would
     * otherwise emit a missing-{@code serialVersionUID} warning, and the module compiles with
     * {@code -Xlint:all -Werror}, which turns that warning into a build failure.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The exact character length of a COBOL {@code FILE STATUS} value.
     *
     * <p>The legacy declaration is a two-character group of two single-character fields
     * ({@code CBACT01C} lines 46-48), so two is a fixed property of the contract and never a
     * default that a caller may vary.
     */
    public static final int CODE_LENGTH = 2;

    /**
     * The status reported for a successful file operation.
     *
     * <p>Rejected by every constructor: success is not an error condition. Held as a named constant
     * so that the rejection reads as intent rather than as a magic literal.
     */
    public static final String STATUS_SUCCESS = "00";

    /**
     * The status reported at end of file.
     *
     * <p>Rejected by every constructor. End of file is a normal outcome of a sequential read and
     * belongs to the coarse end-of-file arm handled by the layer above; representing it as an error
     * here would erase exactly the distinction that all ten batch read loops depend on.
     */
    public static final String STATUS_END_OF_FILE = "10";

    /**
     * The operator-facing prefix under which the legacy programs emit a file status
     * ({@code CBACT01C} lines 176-189, and the same routine in seven further batch members).
     *
     * <p>Reproduced verbatim as an external-contract literal: the capitalisation, the single space
     * after the colon, and the four literal {@code N} characters are all part of the text an
     * operator already recognises, and the legacy code emits the formatted four-digit status
     * immediately after this prefix rather than substituting it into those {@code N} positions.
     * Callers logging a file status should use this constant unchanged so that a Java log line
     * matches the mainframe diagnostic.
     */
    public static final String DISPLAY_PREFIX = "FILE STATUS IS: NNNN";

    /**
     * The raw two-character status, exactly as reported - never normalised, mapped or reformatted.
     */
    private final String code;

    /**
     * Caller-supplied description of the attempted operation; the empty string when none was given.
     */
    private final String operation;

    /**
     * Caller-supplied name of the resource involved; the empty string when none was given.
     */
    private final String resourceName;

    /**
     * Creates an exception carrying a raw two-character file status.
     *
     * @param code         the raw two-character {@code FILE STATUS} value, exactly as reported;
     *                     must be non-{@code null}, exactly {@value #CODE_LENGTH} characters long,
     *                     and neither {@value #STATUS_SUCCESS} nor {@value #STATUS_END_OF_FILE}
     * @param operation    optional description of the attempted operation, supplied by the caller
     *                     from the wording it already logs; {@code null} becomes the empty string
     * @param resourceName optional name of the resource involved - typically the legacy DD, dataset
     *                     or CICS file name such as {@code ACCTDAT}, {@code DISCGRP} or
     *                     {@code TCATBAL}; {@code null} becomes the empty string
     * @throws IllegalArgumentException if {@code code} is {@code null}, is not exactly
     *                                 {@value #CODE_LENGTH} characters long, or is
     *                                 {@value #STATUS_SUCCESS} or {@value #STATUS_END_OF_FILE}
     */
    public FileStatusException(String code, String operation, String resourceName) {
        super(validatedMessage(code, operation, resourceName));
        this.code = code;
        this.operation = orEmpty(operation);
        this.resourceName = orEmpty(resourceName);
    }

    /**
     * Creates an exception carrying a raw two-character file status together with the underlying
     * cause.
     *
     * @param code         the raw two-character {@code FILE STATUS} value, exactly as reported;
     *                     must be non-{@code null}, exactly {@value #CODE_LENGTH} characters long,
     *                     and neither {@value #STATUS_SUCCESS} nor {@value #STATUS_END_OF_FILE}
     * @param operation    optional description of the attempted operation, supplied by the caller
     *                     from the wording it already logs; {@code null} becomes the empty string
     * @param resourceName optional name of the resource involved - typically the legacy DD, dataset
     *                     or CICS file name such as {@code ACCTDAT}, {@code DISCGRP} or
     *                     {@code TCATBAL}; {@code null} becomes the empty string
     * @param cause        the underlying failure to chain, or {@code null} when there is none
     * @throws IllegalArgumentException if {@code code} is {@code null}, is not exactly
     *                                 {@value #CODE_LENGTH} characters long, or is
     *                                 {@value #STATUS_SUCCESS} or {@value #STATUS_END_OF_FILE}
     */
    public FileStatusException(String code, String operation, String resourceName, Throwable cause) {
        super(validatedMessage(code, operation, resourceName), cause);
        this.code = code;
        this.operation = orEmpty(operation);
        this.resourceName = orEmpty(resourceName);
    }

    /**
     * Returns the raw two-character file status, byte for byte as it was reported.
     *
     * @return the status, always exactly {@value #CODE_LENGTH} characters and never {@code null}
     */
    public String code() {
        return this.code;
    }

    /**
     * Returns the first of the two status characters.
     *
     * <p>Mirrors the leading single-character field of the legacy status group so that a caller can
     * reproduce the original inspection - for example the "first byte is {@code '9'}" test in the
     * display routine - without re-slicing the string.
     *
     * @return the first status character
     */
    public char firstByte() {
        return this.code.charAt(0);
    }

    /**
     * Returns the second of the two status characters.
     *
     * <p>Mirrors the trailing single-character field of the legacy status group, which the display
     * routine treats as a binary value whenever the pair is non-numeric.
     *
     * @return the second status character
     */
    public char secondByte() {
        return this.code.charAt(1);
    }

    /**
     * Returns the caller-supplied description of the attempted operation.
     *
     * @return the operation description, or the empty string when none was supplied; never
     *         {@code null}
     */
    public String operation() {
        return this.operation;
    }

    /**
     * Returns the caller-supplied name of the resource involved.
     *
     * @return the resource name, or the empty string when none was supplied; never {@code null}
     */
    public String resourceName() {
        return this.resourceName;
    }

    /**
     * The single validation funnel for every constructor: checks the status, then composes the
     * detail message.
     *
     * <p>It is invoked from within the {@code super(...)} call so that an invalid argument is
     * rejected before any field is assigned, which keeps the length check and the success and
     * end-of-file rejections in exactly one place.
     *
     * @param code         the candidate raw status
     * @param operation    the optional operation description
     * @param resourceName the optional resource name
     * @return the composed detail message
     * @throws IllegalArgumentException if the status is absent, misshapen, or one of the two
     *                                  non-error statuses
     */
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

    /**
     * Composes the detail message from the status and whatever context the caller supplied.
     *
     * <p>The message is deliberately factual - labelled values only, with no interpretation of the
     * status and no invented operator-facing prose. Context that was not supplied is omitted rather
     * than rendered as an empty label.
     *
     * @param code               the validated raw status
     * @param normalisedOperation the operation description, already normalised, never {@code null}
     * @param normalisedResource  the resource name, already normalised, never {@code null}
     * @return the composed detail message
     */
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

    /**
     * The single normalisation of optional context: {@code null} becomes the empty string.
     *
     * @param value the value to normalise, possibly {@code null}
     * @return the value unchanged, or the empty string when it was {@code null}
     */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
