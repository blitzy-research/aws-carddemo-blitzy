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
 * Terminal, unrecoverable failure raised wherever the legacy CardDemo estate abended.
 *
 * <h2>Legacy antecedent</h2>
 *
 * <p>The estate has exactly two abend paths and this single type replaces both. On the batch
 * tier there are <strong>9</strong> static calls to the Language Environment abort routine
 * {@code CEE3ABD}, one in each of {@code CBACT01C}, {@code CBACT02C}, {@code CBACT03C},
 * {@code CBACT04C}, {@code CBCUS01C}, {@code CBTRN01C}, {@code CBTRN02C}, {@code CBTRN03C}
 * and {@code CBSTM03A}. On the online tier there are <strong>4</strong> CICS {@code ABEND}
 * commands, in {@code COACTUPC} (line 4222), {@code COACTVWC} (line 934), {@code COCRDSLC}
 * (line 875) and {@code COCRDUPC} (line 1550). Nine plus four is the entire abend surface of
 * the estate; there is no third path, so there is no third exception type for it.
 *
 * <p>The {@code CANCEL} token that sits immediately above each of the four online abend sites
 * is the CICS {@code HANDLE ABEND} command's {@code CANCEL} option, which deregisters the
 * program's own abend handler just before the abend is issued. It is a CICS command option and
 * <em>not</em> the COBOL {@code CANCEL} statement; the COBOL {@code CANCEL} verb does not occur
 * anywhere in the estate. A reader comparing this class against the legacy source should
 * therefore not expect a cancel-a-load-module equivalent here, because none is required.
 *
 * <h2>The 134-byte abend context</h2>
 *
 * <p>The copybook {@code app/cpy/CSMSG02Y.cpy} declares the group item {@code ABEND-DATA} with
 * exactly four subordinate character fields, every one of them initialised to spaces. Those
 * four pieces of context are carried here as distinct, bounded, immutable values rather than as
 * one flattened string:
 *
 * <ul>
 *   <li>{@code ABEND-CODE}, {@code PIC X(4)}, exposed by {@link #code()}</li>
 *   <li>{@code ABEND-CULPRIT}, {@code PIC X(8)}, exposed by {@link #culprit()}</li>
 *   <li>{@code ABEND-REASON}, {@code PIC X(50)}, exposed by {@link #reason()}</li>
 *   <li>{@code ABEND-MSG}, {@code PIC X(72)}, exposed by the inherited {@link #getMessage()}</li>
 * </ul>
 *
 * <p>4 + 8 + 50 + 72 = 134 bytes, which is why {@link #CONTEXT_LENGTH} is declared as the sum of
 * the four individual width constants rather than as a literal: the arithmetic identity is
 * expressed in code and cannot drift. {@link #toFixedWidthContext()} renders the four values
 * back into that exact 134-character image, mirroring the online routine's transmission of the
 * whole {@code ABEND-DATA} area to the terminal.
 *
 * <p>Only three fields are declared on this class. The fourth piece of context, the operator
 * message, is deliberately <em>not</em> duplicated into a redundant field: it is carried by the
 * inherited {@code Throwable} message and is read back through {@link #getMessage()}. All four
 * pieces are present; three live here and one lives in the superclass.
 *
 * <h2>Null handling and width enforcement</h2>
 *
 * <p>A {@code null} {@link #code()}, {@link #culprit()} or {@link #reason()} is permitted and is
 * normalised to the empty string, because the legacy fields are initialised to spaces and are
 * therefore never absent. A {@code null} or blank message is instead replaced by
 * {@link #DEFAULT_MESSAGE}, faithful to the online abend routine in {@code app/cbl/COACTUPC.cbl}
 * (lines 4205 to 4207), which substitutes that literal when {@code ABEND-MSG} carries no
 * supplied value. The copybook initialises {@code ABEND-MSG} to spaces while the routine tests
 * it against low values, so blank and absent are treated alike here; the alternative would leave
 * an operator staring at an empty terminal-abend message, which the legacy never does.
 *
 * <p>Widths are enforced <strong>loudly</strong>. A value longer than its legacy field raises
 * {@link IllegalArgumentException} naming the field, its legacy picture width and the offending
 * length. Nothing is silently truncated and nothing is silently padded away: the legacy field
 * simply could not hold an over-length value, so accepting one would hide a defect rather than
 * report it.
 *
 * <h2>Emit-then-abend is the caller's contract</h2>
 *
 * <p>This class performs <strong>no logging of its own</strong> and holds no logger. The legacy
 * always emitted the diagnostic <em>before</em> abending, and callers must reproduce that
 * ordering. On the batch tier {@code app/cbl/CBACT01C.cbl} does so at three structurally
 * identical sites -- the read path (lines 110 to 113), the open path (lines 144 to 147) and the
 * close path (lines 162 to 165) -- each of which displays the diagnostic, moves the raw
 * two-byte file status into a display field, displays that status, and only then abends. On the
 * online tier the abend routine in {@code app/cbl/COACTUPC.cbl} sends the whole 134-byte context
 * to the terminal, deregisters the abend handler, and only then abends.
 *
 * <p>Every caller must therefore log the diagnostic through SLF4J, including the raw two-byte
 * file status wherever one exists, <em>before</em> raising this exception. Never rely on a
 * {@code catch} block that has already unwound past the status, and never let the exception
 * message be the only record of it. An operator reading a Java log must see the same diagnostic
 * ordering they saw on the mainframe.
 *
 * <h2>Documented source anomaly</h2>
 *
 * <p>The copybook that defines this context carries a header comment naming the file
 * {@code CABENDD.CPY}, which disagrees with the member name that actually exists,
 * {@code CSMSG02Y.cpy}. The discrepancy is recorded here and in the project decision log; it is
 * deliberately <em>not</em> corrected, because the legacy tree is the parity baseline and must
 * remain byte-identical.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL, JCL, BMS, copybook or CICS
 * resource text is reproduced in this module; the legacy source is cited, never transcribed.
 */
public class AbendException extends RuntimeException {

    /**
     * Fixed serialization identity. Declared explicitly because {@code Throwable} is
     * serializable and the module compiles with {@code -Xlint:all -Werror}, which promotes the
     * missing serial-version-uid warning to a build error.
     */
    private static final long serialVersionUID = 1L;

    /** Legacy width of {@code ABEND-CODE}, declared {@code PIC X(4)}. */
    public static final int CODE_LENGTH = 4;

    /** Legacy width of {@code ABEND-CULPRIT}, declared {@code PIC X(8)}. */
    public static final int CULPRIT_LENGTH = 8;

    /** Legacy width of {@code ABEND-REASON}, declared {@code PIC X(50)}. */
    public static final int REASON_LENGTH = 50;

    /** Legacy width of {@code ABEND-MSG}, declared {@code PIC X(72)}. */
    public static final int MESSAGE_LENGTH = 72;

    /**
     * Total width of the legacy {@code ABEND-DATA} group item, 134 bytes.
     *
     * <p>Derived as the sum of the four field widths rather than written as a literal, so the
     * identity 4 + 8 + 50 + 72 = 134 is expressed in code and cannot drift if a width is ever
     * re-checked against the copybook.
     */
    public static final int CONTEXT_LENGTH =
            CODE_LENGTH + CULPRIT_LENGTH + REASON_LENGTH + MESSAGE_LENGTH;

    /**
     * The operator message the legacy substitutes when no message was supplied, reproduced
     * verbatim from the online abend routine in {@code app/cbl/COACTUPC.cbl} (line 4206).
     *
     * <p>26 characters, so it fits {@code ABEND-MSG} at {@code PIC X(72)} with room to spare.
     */
    public static final String DEFAULT_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /**
     * The abend code the online tier issues, reproduced verbatim from the CICS {@code ABEND}
     * command in {@code app/cbl/COACTUPC.cbl} (line 4223).
     *
     * <p>Exactly 4 characters, which is precisely why {@code ABEND-CODE} is {@code PIC X(4)}.
     */
    public static final String ONLINE_ABEND_CODE = "9999";

    /**
     * The abend code the batch tier sets before calling the Language Environment abort routine,
     * reproduced verbatim from {@code 9999-ABEND-PROGRAM} in {@code app/cbl/CBACT01C.cbl}
     * (line 172).
     *
     * <p>3 characters, one short of the field width, exactly as the legacy leaves it.
     */
    public static final String BATCH_ABEND_CODE = "999";

    /**
     * The abend code, bounded to {@link #CODE_LENGTH} characters.
     *
     * <p>Never {@code null}; a {@code null} argument is normalised to the empty string.
     */
    private final String code;

    /**
     * The originating program or component, bounded to {@link #CULPRIT_LENGTH} characters.
     *
     * <p>The legacy moves the program name into {@code ABEND-CULPRIT}, and the field is
     * {@code PIC X(8)} because a COBOL member name is at most eight characters long. Never
     * {@code null}; a {@code null} argument is normalised to the empty string.
     */
    private final String culprit;

    /**
     * The failure reason, bounded to {@link #REASON_LENGTH} characters.
     *
     * <p>Never {@code null}; a {@code null} argument is normalised to the empty string.
     */
    private final String reason;

    /**
     * Creates a batch-tier abend carrying the batch abend code and the default operator message.
     *
     * <p>Equivalent to calling the four-argument constructor with {@link #BATCH_ABEND_CODE} and
     * {@link #DEFAULT_MESSAGE}. This is the shape the batch programs need, where the code is
     * always the batch literal and the operator text is always the standard one.
     *
     * @param culprit the originating program or component, at most {@value #CULPRIT_LENGTH}
     *                characters; {@code null} is normalised to the empty string
     * @param reason  the failure reason, at most {@value #REASON_LENGTH} characters;
     *                {@code null} is normalised to the empty string
     * @throws IllegalArgumentException if {@code culprit} or {@code reason} is longer than its
     *                                  legacy field
     */
    public AbendException(String culprit, String reason) {
        this(BATCH_ABEND_CODE, culprit, reason, DEFAULT_MESSAGE);
    }

    /**
     * Creates an abend with an explicit code, culprit, reason and operator message, and no
     * chained cause.
     *
     * @param code    the abend code, at most {@value #CODE_LENGTH} characters; {@code null} is
     *                normalised to the empty string. {@link #ONLINE_ABEND_CODE} and
     *                {@link #BATCH_ABEND_CODE} are the two values the legacy uses
     * @param culprit the originating program or component, at most {@value #CULPRIT_LENGTH}
     *                characters; {@code null} is normalised to the empty string
     * @param reason  the failure reason, at most {@value #REASON_LENGTH} characters;
     *                {@code null} is normalised to the empty string
     * @param message the operator message, at most {@value #MESSAGE_LENGTH} characters;
     *                {@code null} or blank is replaced by {@link #DEFAULT_MESSAGE}
     * @throws IllegalArgumentException if any argument is longer than its legacy field
     */
    public AbendException(String code, String culprit, String reason, String message) {
        this(code, culprit, reason, message, null);
    }

    /**
     * Creates an abend with the full 134-byte context and a chained cause. This is the canonical
     * constructor; every other constructor on this class delegates to it, so the width checks
     * live in exactly one place.
     *
     * <p>The cause is always passed through to the superclass and is never dropped, so the
     * original failure remains reachable through {@link #getCause()}.
     *
     * <p>Note on validation ordering: the message is normalised and width-checked first, because
     * {@code super(...)} must be the first statement of a constructor. The three remaining fields
     * are then checked in the order they occupy the legacy record: code, culprit, reason.
     *
     * @param code    the abend code, at most {@value #CODE_LENGTH} characters; {@code null} is
     *                normalised to the empty string
     * @param culprit the originating program or component, at most {@value #CULPRIT_LENGTH}
     *                characters; {@code null} is normalised to the empty string
     * @param reason  the failure reason, at most {@value #REASON_LENGTH} characters;
     *                {@code null} is normalised to the empty string
     * @param message the operator message, at most {@value #MESSAGE_LENGTH} characters;
     *                {@code null} or blank is replaced by {@link #DEFAULT_MESSAGE}
     * @param cause   the underlying failure to chain, or {@code null} when there is none
     * @throws IllegalArgumentException if any of {@code code}, {@code culprit}, {@code reason} or
     *                                  {@code message} is longer than its legacy field
     */
    public AbendException(String code, String culprit, String reason, String message,
            Throwable cause) {
        super(normaliseMessage(message), cause);
        this.code = enforce(code, CODE_LENGTH, "ABEND-CODE", "code");
        this.culprit = enforce(culprit, CULPRIT_LENGTH, "ABEND-CULPRIT", "culprit");
        this.reason = enforce(reason, REASON_LENGTH, "ABEND-REASON", "reason");
    }

    /**
     * Returns the abend code, corresponding to the legacy {@code ABEND-CODE} field.
     *
     * @return the abend code, never {@code null}, never longer than {@value #CODE_LENGTH}
     *         characters, and the empty string when none was supplied
     */
    public String code() {
        return this.code;
    }

    /**
     * Returns the originating program or component, corresponding to the legacy
     * {@code ABEND-CULPRIT} field.
     *
     * @return the culprit, never {@code null}, never longer than {@value #CULPRIT_LENGTH}
     *         characters, and the empty string when none was supplied
     */
    public String culprit() {
        return this.culprit;
    }

    /**
     * Returns the failure reason, corresponding to the legacy {@code ABEND-REASON} field.
     *
     * @return the reason, never {@code null}, never longer than {@value #REASON_LENGTH}
     *         characters, and the empty string when none was supplied
     */
    public String reason() {
        return this.reason;
    }

    /**
     * Renders the four context values as the legacy 134-character {@code ABEND-DATA} image.
     *
     * <p>Each value is left-justified and space-padded to its legacy width, in record order,
     * because a COBOL {@code PIC X} field is left-justified and the copybook initialises all four
     * fields to spaces. The resulting slices are therefore:
     *
     * <ul>
     *   <li>offsets 0 to 4, exclusive: {@link #code()}</li>
     *   <li>offsets 4 to 12, exclusive: {@link #culprit()}</li>
     *   <li>offsets 12 to 62, exclusive: {@link #reason()}</li>
     *   <li>offsets 62 to 134, exclusive: {@link #getMessage()}</li>
     * </ul>
     *
     * <p>This mirrors the online abend routine transmitting the whole {@code ABEND-DATA} area to
     * the terminal. The value is <strong>derived</strong> on every call and is never the store:
     * the three fields plus the inherited message remain the single source of truth, so no code
     * path can drift by writing to a cached image. The length guarantee rests on the
     * constructor's width enforcement, which is what makes each slice exactly its legacy width.
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

    /**
     * Substitutes {@link #DEFAULT_MESSAGE} when no operator message was supplied, then delegates
     * the width check to {@link #enforce(String, int, String, String)} so that all four width
     * checks live in exactly one place.
     *
     * <p>A {@code null} or blank message counts as "not supplied". The legacy online routine
     * substitutes its literal when {@code ABEND-MSG} carries no supplied value, and an abend must
     * never present an operator with an empty message.
     *
     * @param message the caller-supplied operator message, possibly {@code null} or blank
     * @return the message to hand to the superclass, never {@code null} and never blank
     * @throws IllegalArgumentException if a supplied message is longer than {@code ABEND-MSG}
     */
    private static String normaliseMessage(String message) {
        if (message == null || message.isBlank()) {
            return DEFAULT_MESSAGE;
        }
        return enforce(message, MESSAGE_LENGTH, "ABEND-MSG", "message");
    }

    /**
     * The single place where a context value is bounded to its legacy field width.
     *
     * <p>A {@code null} value becomes the empty string, because the legacy fields are initialised
     * to spaces and are therefore never absent. An over-length value is rejected loudly rather
     * than truncated: the legacy field could not have held it, so truncating would hide a defect
     * instead of reporting it.
     *
     * @param value          the caller-supplied value, possibly {@code null}
     * @param legacyWidth    the width of the legacy field, in characters
     * @param legacyFieldName the legacy field name, used verbatim in the failure message so the
     *                       reader can find it in the copybook
     * @param javaFieldName  the name of this class's corresponding value, used to open the
     *                       failure message
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

    /**
     * Appends a value left-justified and space-padded to the given width, reproducing how a COBOL
     * {@code PIC X} field holds a shorter value.
     *
     * @param target the buffer to append to
     * @param value  the value to append, possibly {@code null}, which appends padding only
     * @param width  the legacy field width to pad out to
     */
    private static void appendPadded(StringBuilder target, String value, int width) {
        String text = (value == null) ? "" : value;
        target.append(text);
        int padding = width - text.length();
        if (padding > 0) {
            target.append(" ".repeat(padding));
        }
    }
}
