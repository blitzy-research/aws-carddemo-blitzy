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
 * Terminal, unrecoverable failure raised wherever the legacy CardDemo estate abended. One type replaces
 * both legacy paths - the nine batch calls to the Language Environment abort routine and the four online
 * terminal abends - because those thirteen sites are the entire abend surface.
 *
 * <p><strong>The 134-character abend context.</strong> {@code app/cpy/CSMSG02Y.cpy} declares four
 * space-initialised character fields whose widths sum to 134: code, culprit, reason and operator message.
 * The first three are carried here as bounded immutable values and the fourth is the inherited
 * {@link Throwable} message, so nothing is duplicated. {@link #CONTEXT_LENGTH} is the sum of the four
 * width constants rather than a literal, and {@link #toFixedWidthContext()} renders the four values back
 * into that exact image, mirroring the online routine's transmission of the whole area to the terminal.
 *
 * <p><strong>Two deliberate departures from legacy move semantics.</strong> A {@code null} code, culprit
 * or reason is normalised to the empty string (decision-log D-07), and an over-length value is rejected
 * with {@link IllegalArgumentException} rather than truncated on the right (decision-log D-06). A
 * {@code null} or blank message is replaced by {@link #DEFAULT_MESSAGE}, faithful to the online abend
 * routine, which treats absent and blank alike rather than leaving an operator with an empty message.
 *
 * <p><strong>Emit-then-abend is the caller's contract.</strong> This class holds no logger and logs
 * nothing. The legacy always emitted its diagnostic - including the raw two-character file status where
 * one existed - immediately before abending, so every caller must log through SLF4J first and must not
 * let the exception message become the only record of the status.
 */
public class AbendException extends RuntimeException {

    /**
     * Pinned so that a future field addition cannot change the identity of serialized instances.
     */
    private static final long serialVersionUID = 1L;

    public static final int CODE_LENGTH = 4;

    public static final int CULPRIT_LENGTH = 8;

    public static final int REASON_LENGTH = 50;

    public static final int MESSAGE_LENGTH = 72;

    /**
     * Total width of the legacy abend context. Expressed as the sum of the four field widths so the
     * arithmetic cannot drift if a width is ever re-checked against the copybook.
     */
    public static final int CONTEXT_LENGTH =
            CODE_LENGTH + CULPRIT_LENGTH + REASON_LENGTH + MESSAGE_LENGTH;

    /**
     * Substituted when no operator message was supplied, reproduced verbatim from the online abend routine
     * in {@code app/cbl/COACTUPC.cbl} line 4206.
     */
    public static final String DEFAULT_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /** Reproduced verbatim from the online abend in {@code app/cbl/COACTUPC.cbl} line 4223. */
    public static final String ONLINE_ABEND_CODE = "9999";

    /**
     * Reproduced verbatim from the batch abend paragraph in {@code app/cbl/CBACT01C.cbl} line 172; it is
     * one character short of the field width, exactly as the legacy leaves it.
     */
    public static final String BATCH_ABEND_CODE = "999";

    private final String code;

    private final String culprit;

    private final String reason;

    /**
     * Creates a batch-tier abend carrying the batch abend code and the default operator message.
     *
     * @param culprit the originating program or component
     * @param reason  the failure reason
     */
    public AbendException(String culprit, String reason) {
        this(BATCH_ABEND_CODE, culprit, reason, DEFAULT_MESSAGE);
    }

    /**
     * Creates an abend with an explicit code, culprit, reason and operator message, and no chained cause.
     *
     * @param code    the abend code
     * @param culprit the originating program or component
     * @param reason  the failure reason
     * @param message the operator message
     */
    public AbendException(String code, String culprit, String reason, String message) {
        this(code, culprit, reason, message, null);
    }

    /**
     * Canonical constructor: every other constructor delegates here, so the width checks live in one
     * place. The message is normalised first because {@code super(...)} must come first; the remaining
     * three are then checked in legacy record order.
     *
     * @param code    the abend code; {@code null} becomes the empty string
     * @param culprit the originating program or component; {@code null} becomes the empty string
     * @param reason  the failure reason; {@code null} becomes the empty string
     * @param message the operator message; {@code null} or blank is replaced by {@link #DEFAULT_MESSAGE}
     * @param cause   the underlying failure to chain, or {@code null}
     * @throws IllegalArgumentException if any value is longer than its legacy field
     */
    public AbendException(String code, String culprit, String reason, String message,
            Throwable cause) {
        super(normaliseMessage(message), cause);
        this.code = enforce(code, CODE_LENGTH, "ABEND-CODE", "code");
        this.culprit = enforce(culprit, CULPRIT_LENGTH, "ABEND-CULPRIT", "culprit");
        this.reason = enforce(reason, REASON_LENGTH, "ABEND-REASON", "reason");
    }

    public String code() {
        return this.code;
    }

    public String culprit() {
        return this.culprit;
    }

    public String reason() {
        return this.reason;
    }

    /**
     * Renders the four context values as the legacy fixed-width abend image, each left-justified and
     * space-padded to its own width in record order. The image is derived on every call and never stored,
     * so no path can drift by writing to a cached copy.
     *
     * @return a string of exactly {@value #CONTEXT_LENGTH} characters
     */
    public String toFixedWidthContext() {
        StringBuilder context = new StringBuilder(CONTEXT_LENGTH);
        appendPadded(context, this.code, CODE_LENGTH);
        appendPadded(context, this.culprit, CULPRIT_LENGTH);
        appendPadded(context, this.reason, REASON_LENGTH);
        appendPadded(context, getMessage(), MESSAGE_LENGTH);
        return context.toString();
    }

    private static String normaliseMessage(String message) {
        if (message == null || message.isBlank()) {
            return DEFAULT_MESSAGE;
        }
        return enforce(message, MESSAGE_LENGTH, "ABEND-MSG", "message");
    }

    /**
     * The single place where a context value is bounded to its legacy field width, applying the two
     * documented departures from legacy move semantics (decision-log D-06 and D-07). The rejection message
     * carries the legacy field name, its width and the length supplied.
     *
     * @param value           the caller-supplied value, possibly {@code null}
     * @param legacyWidth     the width of the legacy field, in characters
     * @param legacyFieldName the legacy field name, quoted so a reader can find it in the copybook
     * @param javaFieldName   the name of this class's corresponding value
     * @return the value, or the empty string when {@code value} was {@code null}
     * @throws IllegalArgumentException if {@code value} is longer than {@code legacyWidth}
     */
    private static String enforce(String value, int legacyWidth, String legacyFieldName,
            String javaFieldName) {
        if (value == null) {
            return "";
        }
        if (value.length() > legacyWidth) {
            throw new IllegalArgumentException(javaFieldName + " exceeds legacy " + legacyFieldName
                    + " PIC X(" + legacyWidth + "): length " + value.length());
        }
        return value;
    }

    private static void appendPadded(StringBuilder target, String value, int width) {
        String text = (value == null) ? "" : value;
        target.append(text);
        int padding = width - text.length();
        if (padding > 0) {
            target.append(" ".repeat(padding));
        }
    }
}
