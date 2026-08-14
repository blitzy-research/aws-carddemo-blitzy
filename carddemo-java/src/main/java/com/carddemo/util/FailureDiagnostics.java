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
package com.carddemo.util;

import java.util.Objects;

/**
 * Renders a failure as a bounded, sanitised chain of type names, so that a boundary log site can say
 * what failed without publishing anything the failure happened to be carrying.
 *
 * <h2>The problem this exists to solve</h2>
 *
 * <p>A throwable is the one value crossing this module's boundaries whose textual content this module
 * does not author. Its message and the messages of every cause beneath it are composed by whoever
 * raised them, and in practice they carry material that must not reach a collector: a driver failure
 * carries the connection string it could not open, a constraint violation carries the bound values it
 * rejected, an interpolated validation message carries whatever a caller submitted, and a failure
 * provoked by an oversized input carries that input. Handing such an object to a logging appender
 * publishes all of it verbatim, indefinitely, to storage that is searchable by more people than the
 * caller who caused it.
 *
 * <p>It is also a volume lever rather than only a disclosure one. A caller who can provoke a failure
 * whose message they control chooses how many bytes each of their requests writes into centralised
 * logging, which is amplification with no bound but the caller's patience.
 *
 * <p>The remedy is not to log less about the failure. It is to log the part this module authored - the
 * <em>shape</em> of the chain, which type wrapped which - and none of the part it did not. The shape
 * is frequently the whole diagnosis: a data-access failure beneath a service failure beneath a
 * boundary handler says where to look, and it says it without one character of caller-supplied text.
 *
 * <h2>What is published, and what is withheld</h2>
 *
 * <p>Published: the simple type name of the failure, then the simple type name of each cause beneath
 * it, outermost first, joined by {@value #FAILURE_CHAIN_SEPARATOR}. Every name is sanitised to a
 * single unbroken token and bounded in length, and the walk itself is bounded in depth.
 *
 * <p>Withheld: every message, every localised message, every suppressed throwable, every stack frame
 * and every field of every throwable in the chain. None of them is read, so none of them can escape
 * through this class - not by a later edit to a message template, and not by a framework that
 * interpolates a value into a message this module never sees.
 *
 * <h2>Where the bounds come from, and why they are here rather than only in the appender</h2>
 *
 * <p>{@code logback-spring.xml} bounds the machine-readable throwable rendering in both depth and
 * length, which is the backstop for a throwable no boundary site handles - one raised inside the
 * framework, a driver or a library, and rendered by the appender before any of this module's code sees
 * it. That backstop bounds the <em>size</em> of a disclosure; it cannot prevent one, because a bounded
 * rendering of a connection string is still a connection string. This class is the control that
 * prevents it, and the two are complementary rather than alternatives.
 *
 * <h2>Layering</h2>
 *
 * <p>It sits in the utility layer because both the boundary handler in the API layer and the services
 * that catch a defensive failure need the same rendering, and one shared policy is the only way the
 * two cannot drift apart. It depends on nothing above it, holds no state, reads no configuration and
 * declares no logger of its own - a diagnostic helper that logged would be a diagnostic helper with
 * its own disclosure surface.
 *
 * <h2>Messages are never read; code locations are</h2>
 *
 * <p>Every method here refuses to read a throwable's message, its arguments or its suppressed
 * throwables, because those are where a value can hide. {@link #failureOriginOf(Throwable)} does read
 * the stack trace, and the distinction is exact and deliberate: a frame carries a declaring type, a
 * method name and a line, all fixed at compile time and identical for every request, so no value of any
 * kind can travel through one. That is why a location can be published while a rendered stack trace -
 * which carries every message in the chain - still may not be handed to the logger.
 */
public final class FailureDiagnostics {

    /**
     * Separator between one type name and the type of the cause beneath it, read outermost to
     * innermost.
     *
     * <p>The arrow points from the wrapper to the cause, which is the direction a reader diagnoses in:
     * the first token is where the failure surfaced and the last is what actually went wrong.
     */
    public static final String FAILURE_CHAIN_SEPARATOR = "<-";

    /**
     * Marker appended when a chain is deeper than {@link #MAX_FAILURE_CHAIN_DEPTH} elements.
     *
     * <p>Present so that a bound reached is visibly a bound rather than the end of the evidence. A
     * reader who sees it knows to look for the rest, and a reader who does not see it knows the chain
     * is complete.
     */
    public static final String FAILURE_CHAIN_TRUNCATION_MARKER = "<-...";

    /**
     * Substitute reported for a type whose simple name is empty or sanitises to nothing.
     *
     * <p>Two real cases produce it: an anonymous class, whose simple name is the empty string, and a
     * synthetic or generated type whose name holds nothing this class will keep. Neither may render as
     * an empty token, because an empty token silently merges with its separator and makes a
     * two-element chain read as one.
     */
    public static final String UNNAMED_FAILURE_TYPE = "UnnamedType";

    /**
     * Greatest number of elements a rendered chain may name.
     *
     * <p>The bound is what makes the walk terminate on a cyclic or mutually-referential chain, which
     * {@link Throwable#initCause(Throwable)} forbids but an overridden {@link Throwable#getCause()}
     * can still produce. It also fixes the maximum size of the rendered field, which is what removes
     * amplification: depth times per-name length, and nothing else.
     */
    public static final int MAX_FAILURE_CHAIN_DEPTH = 6;

    /**
     * Greatest number of code locations {@link #failureOriginOf(Throwable)} may name.
     *
     * <p>Three is the number that locates a defect without publishing a call graph: the frame that
     * raised the failure, the frame that called it, and one more for the case where the first two are a
     * shared helper and its immediate caller. The bound is also what fixes the rendered field's maximum
     * size, so a failure raised beneath a deep chain costs the same as one raised near the top.
     */
    public static final int MAX_ORIGIN_FRAME_COUNT = 3;

    /**
     * Substitute reported when a failure carries no stack trace at all.
     *
     * <p>A throwable may be constructed with its stack trace suppressed, and a deserialised one may
     * arrive without one. Neither is a failure with no location; it is a failure whose location was not
     * recorded, and an empty field would state the first while meaning the second.
     */
    public static final String UNKNOWN_ORIGIN = "UnknownOrigin";

    /**
     * Greatest number of characters one sanitised type name may occupy.
     *
     * <p>An ordinary type name is far shorter. The bound exists for a generated name - a proxy, a
     * lambda-hosting class or a synthetic wrapper - which can be arbitrarily long and would otherwise
     * set the size of the field on its own.
     */
    public static final int MAX_TYPE_NAME_LENGTH = 64;

    /**
     * Stand-in for an absent value, so a diagnostic never reads {@code null} where a value belongs.
     *
     * <p>Distinguishable from a value that is present and empty, which renders as nothing at all.
     */
    public static final String ABSENT_VALUE = "<absent>";

    /**
     * Marker appended to a rendered value that was cut.
     *
     * <p>Present so that a reader can tell a value that was cut from one that ended where it appears to.
     */
    public static final String RENDERED_VALUE_TRUNCATION_MARKER = "...";

    /**
     * Most characters of a caller-supplied value that a diagnostic renders.
     *
     * <p>Bounds the amount of centralised logging one oversized value can consume. Set well above every
     * field width the estate declares - the widest is the 100-byte statement record - so that a value a
     * diagnostic is genuinely about is rendered whole, and only a value that could not be a legitimate
     * field at all is cut.
     */
    public static final int MAX_RENDERED_VALUE_LENGTH = 200;

    /** Lowest printable ASCII character, the space. */
    private static final char FIRST_PRINTABLE_ASCII = ' ';

    /** Highest printable ASCII character, the tilde. */
    private static final char LAST_PRINTABLE_ASCII = '~';

    /**
     * Upper-case hexadecimal digits, indexed rather than formatted so that no locale participates.
     */
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    /** Hexadecimal digits in the rendering of one UTF-16 code unit. */
    private static final int HEX_DIGITS_PER_CODE_UNIT = 4;

    /** Bits one hexadecimal digit carries. */
    private static final int BITS_PER_HEX_DIGIT = 4;

    /** Mask selecting the low-order hexadecimal digit. */
    private static final int LOW_HEX_DIGIT_MASK = 0xF;

    /** Characters in the {@code U+XXXX} rendering of one code unit. */
    private static final int CODE_POINT_RENDERING_LENGTH = 2 + HEX_DIGITS_PER_CODE_UNIT;

    /**
     * Character every inadmissible character in a type name is replaced by.
     *
     * <p>Chosen because it is itself admissible in a Java type name, so a sanitised name still reads as
     * a name rather than as an escape sequence, and because it introduces no whitespace and so cannot
     * split one token into two.
     */
    private static final char TYPE_NAME_REPLACEMENT = '_';

    /**
     * Refuses instantiation.
     *
     * <p>Every member is static and the type holds no state, so an instance would carry nothing and
     * would only invite a reader to look for the state it does not have.
     *
     * @throws AssertionError always
     */
    private FailureDiagnostics() {
        throw new AssertionError("FailureDiagnostics is a utility holder and must not be instantiated");
    }

    /**
     * Renders the chain of failure types beneath and including one failure, bounded and sanitised.
     *
     * <p>The walk stops at the first of three conditions: a cause that is {@code null}, a cause that is
     * the throwable it is the cause of, or {@link #MAX_FAILURE_CHAIN_DEPTH} elements rendered. The
     * second condition is not hypothetical defensiveness - the accessor is overridable - and treating a
     * self-causing failure as having nothing beneath it keeps the invariant that a single-element chain
     * means a single type.
     *
     * <p>No message, no frame and no suppressed throwable is read. The returned value is composed
     * entirely of sanitised type names and the two separators declared on this class, so it is safe to
     * write into a log record, an exception message or a response body.
     *
     * @param failure the failure to describe; must not be {@code null}
     * @return the chain, outermost type first, never {@code null}, never blank, and never longer than
     *         {@link #MAX_FAILURE_CHAIN_DEPTH} names plus their separators
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    public static String failureChainOf(final Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        final StringBuilder chain = new StringBuilder(typeNameOf(failure.getClass()));
        Throwable current = failure;
        for (int depth = 1; depth < MAX_FAILURE_CHAIN_DEPTH; depth++) {
            final Throwable beneath = current.getCause();
            if (beneath == null || beneath == current) {
                return chain.toString();
            }
            chain.append(FAILURE_CHAIN_SEPARATOR).append(typeNameOf(beneath.getClass()));
            current = beneath;
        }
        if (current.getCause() != null && current.getCause() != current) {
            chain.append(FAILURE_CHAIN_TRUNCATION_MARKER);
        }
        return chain.toString();
    }

    /**
     * Names the type of the deepest cause the depth bound reaches, or the empty string when the failure
     * carries none.
     *
     * <p>The deepest cause is what a reader wants first, so it is published as its own field rather
     * than left to be found at the end of the chain. The empty answer is deliberate and is not a
     * substitute value: a failure with no cause has nothing beneath it, and inventing a token would
     * describe a two-element chain where there is one.
     *
     * @param failure the failure to describe; must not be {@code null}
     * @return the sanitised name of the deepest reachable cause, or the empty string when there is no
     *         cause beneath {@code failure}; never {@code null}
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    public static String rootFailureTypeOf(final Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        final Throwable beneathTheFailure = failure.getCause();
        if (beneathTheFailure == null || beneathTheFailure == failure) {
            return "";
        }
        Throwable deepest = beneathTheFailure;
        for (int depth = 1; depth < MAX_FAILURE_CHAIN_DEPTH; depth++) {
            final Throwable beneath = deepest.getCause();
            if (beneath == null || beneath == deepest) {
                break;
            }
            deepest = beneath;
        }
        return typeNameOf(deepest.getClass());
    }

    /**
     * Names the deepest failure type the depth bound reaches, naming the failure itself when it carries
     * no cause.
     *
     * <p><strong>Why this exists beside {@link #rootFailureTypeOf(Throwable)}.</strong> That method
     * answers the empty string for a failure with no cause, deliberately: it publishes what is
     * <em>beneath</em> a failure, and beneath a cause-less failure there is nothing, so a token there
     * would describe a two-element chain where there is one. A caller that relies on exactly that
     * distinction still needs it - the queue bridge reports a reason code only when there genuinely is
     * one beneath the response code.
     *
     * <p>A boundary log wants the other question answered. It asks "what, at the bottom, went wrong",
     * and for a failure raised directly - which is what a defensive refusal inside this module is - the
     * answer is that failure's own type. An empty field there is not a distinction, it is a diagnostic
     * that names nothing, which is precisely the condition that made an unhandled boundary failure
     * undiagnosable from its own log record. This method answers that question, and the two are kept
     * separate so that neither has to compromise.
     *
     * <p>Reads no message, no frame and no suppressed throwable; the answer is composed entirely of a
     * sanitised type name, so it is safe to write into a log record.
     *
     * @param failure the failure to describe; must not be {@code null}
     * @return the sanitised name of the deepest reachable failure type, which is {@code failure}'s own
     *         type when it carries no cause; never {@code null} and never blank
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    public static String deepestFailureTypeOf(final Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        Throwable deepest = failure;
        for (int depth = 1; depth < MAX_FAILURE_CHAIN_DEPTH; depth++) {
            final Throwable beneath = deepest.getCause();
            if (beneath == null || beneath == deepest) {
                break;
            }
            deepest = beneath;
        }
        return typeNameOf(deepest.getClass());
    }

    /**
     * Renders where a failure was raised, as code locations and nothing else.
     *
     * <p><strong>What it adds, and why it is the one thing missing.</strong> A boundary record already
     * names the failure types involved; what it cannot say is <em>where</em>, and a type on its own does
     * not locate a defect. Two of this module's classes raise the same refusal type from several places,
     * so a record naming only the type leaves a reader to guess between them - which is what happened,
     * and what made a live boundary failure undiagnosable from its log at all.
     *
     * <p><strong>Why passing the throwable to the logger is not the answer.</strong> A rendered stack
     * trace carries every message in the chain, and a message may hold a connection string, a bearer
     * token, a national identifier or a card number - the four things the module's logging controls
     * exist to keep out of a log. So the throwable is still never handed to the logger. What is
     * published here is strictly the frame metadata: the declaring type's sanitised simple name, the
     * method name, and the line. Those are properties of the <em>code</em>, fixed at compile time and
     * identical for every request, so no value of any kind can reach a record through them.
     *
     * <p><strong>Bounds.</strong> At most {@link #MAX_ORIGIN_FRAME_COUNT} frames, outermost first,
     * joined by {@link #FAILURE_CHAIN_SEPARATOR}, each name cut to {@link #MAX_TYPE_NAME_LENGTH} and
     * sanitised by {@link #typeNameOf(Class)}'s own character policy. The rendered size is therefore a
     * fixed maximum rather than a function of how deep a stack happens to be, so a failure raised
     * beneath a deep call chain cannot set the size of a log record.
     *
     * <p>A failure whose stack trace is absent - which a throwable constructed without writable stack
     * trace has, and which a deserialised one may have - renders as {@value #UNKNOWN_ORIGIN}, because an
     * empty field would read as "no location" rather than "location not recorded".
     *
     * @param failure the failure to locate; must not be {@code null}
     * @return the bounded rendering, never {@code null}, never blank, and free of any message, argument
     *         or field value
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    public static String failureOriginOf(final Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        final StackTraceElement[] frames = failure.getStackTrace();
        if (frames == null || frames.length == 0) {
            return UNKNOWN_ORIGIN;
        }
        final int rendered = Math.min(frames.length, MAX_ORIGIN_FRAME_COUNT);
        final StringBuilder origin = new StringBuilder();
        for (int index = 0; index < rendered; index++) {
            if (index > 0) {
                origin.append(FAILURE_CHAIN_SEPARATOR);
            }
            origin.append(frameNameOf(frames[index]));
        }
        return origin.toString();
    }

    /**
     * Renders one frame as declaring type, method and line, with every part sanitised.
     *
     * <p>The declaring type is reduced to its simple name by the same rule a failure type is, so a
     * package name is not published and a generated class name cannot set the field's size. The line is
     * emitted only when the frame carries one; a frame from a native or synthetic method carries none,
     * and an invented zero would read as a real line.
     *
     * @param frame the frame being named; must not be {@code null}
     * @return the sanitised rendering of that frame
     */
    private static String frameNameOf(final StackTraceElement frame) {
        final String qualified = frame.getClassName();
        final int lastDot = qualified.lastIndexOf('.');
        final String declaring = lastDot < 0 ? qualified : qualified.substring(lastDot + 1);
        final StringBuilder name = new StringBuilder()
                .append(sanitisedIdentifier(declaring))
                .append('.')
                .append(sanitisedIdentifier(frame.getMethodName()));
        if (frame.getLineNumber() > 0) {
            name.append(':').append(frame.getLineNumber());
        }
        return name.toString();
    }

    /**
     * Bounds and sanitises one identifier read off a frame, by the same character policy a type name is
     * held to.
     *
     * @param declared the identifier as the frame reports it, possibly empty
     * @return the sanitised identifier, or {@value #UNNAMED_FAILURE_TYPE} when nothing is left
     */
    private static String sanitisedIdentifier(final String declared) {
        if (declared == null || declared.isEmpty()) {
            return UNNAMED_FAILURE_TYPE;
        }
        final int retained = Math.min(declared.length(), MAX_TYPE_NAME_LENGTH);
        final StringBuilder sanitised = new StringBuilder(retained);
        for (int index = 0; index < retained; index++) {
            final char character = declared.charAt(index);
            sanitised.append(isTypeNameCharacter(character) ? character : TYPE_NAME_REPLACEMENT);
        }
        return sanitised.toString();
    }

    /**
     * Sanitises and bounds one type's simple name so that it is safe to write into a log record.
     *
     * <p>A type's simple name is ordinarily already a Java identifier and needs nothing done to it.
     * Three cases are not ordinary and all three are handled rather than assumed away: an anonymous
     * class reports the empty string; a synthetic or generated type may report a name carrying
     * characters an identifier may not hold, including whitespace and control bytes; and a generated
     * name may be arbitrarily long.
     *
     * <p>Every character outside the admissible set becomes {@value #TYPE_NAME_REPLACEMENT}, so the
     * result is one unbroken token that cannot inject a line break, a field separator or a control
     * byte into a structured record. The result is cut to {@link #MAX_TYPE_NAME_LENGTH} characters, and
     * a result that would be empty becomes {@value #UNNAMED_FAILURE_TYPE}.
     *
     * @param failureType the type being named; must not be {@code null}
     * @return the sanitised name, never {@code null}, never empty, never longer than
     *         {@link #MAX_TYPE_NAME_LENGTH} characters, and holding only ASCII letters, ASCII digits,
     *         {@code $} and {@value #TYPE_NAME_REPLACEMENT}
     * @throws NullPointerException if {@code failureType} is {@code null}
     */
    public static String typeNameOf(final Class<?> failureType) {
        Objects.requireNonNull(failureType, "failureType must not be null");
        final String declared = failureType.getSimpleName();
        if (declared.isEmpty()) {
            return UNNAMED_FAILURE_TYPE;
        }
        final int retained = Math.min(declared.length(), MAX_TYPE_NAME_LENGTH);
        final StringBuilder sanitised = new StringBuilder(retained);
        for (int index = 0; index < retained; index++) {
            final char character = declared.charAt(index);
            sanitised.append(isTypeNameCharacter(character) ? character : TYPE_NAME_REPLACEMENT);
        }
        return sanitised.toString();
    }

    /**
     * Reports whether one character may appear in a sanitised type name.
     *
     * <p>The admissible set is the ASCII subset of what a Java type name may hold: ASCII letters, ASCII
     * digits and the two connectors. It is deliberately narrower than
     * {@link Character#isJavaIdentifierPart(char)}, which admits non-ASCII letters and several Unicode
     * formatting and ignorable code points - none of which belong in a value written into a log record,
     * because a code point that renders as nothing can hide the difference between two names.
     *
     * @param character the character being examined
     * @return {@code true} when the character may be kept, {@code false} when it must be replaced
     */
    private static boolean isTypeNameCharacter(final char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '$'
                || character == TYPE_NAME_REPLACEMENT;
    }

    /**
     * Renders a caller-supplied value inert for a diagnostic, keeping every printable character and
     * naming every other one by its code point.
     *
     * <h2>Why a value read out of a record needs this and a value this module authored does not</h2>
     *
     * <p>Some diagnostics have to render the value itself: a job parameter that is not a date, a
     * fixed-width field that is not the width it must be, a character found where a digit belongs. In
     * each of those the value <em>is</em> the diagnosis, so withholding it - as
     * {@link SensitiveLogRedactor#redact(String)} does for an identifier - would leave the reader with
     * nothing to act on. The value is nevertheless not this module's text. It arrived in a 350-byte,
     * 50-byte or 80-byte image, or on a job parameter card, and its bytes are whatever the producer
     * wrote.
     *
     * <p>A control byte among those bytes is not a cosmetic problem. A line terminator ends the record
     * early and makes the remainder of the message read as a record of its own; a carriage return
     * overwrites what a terminal has already drawn; an escape byte begins a sequence a terminal obeys;
     * and a code point that renders as nothing hides the difference between two values that a reader is
     * comparing by eye. Each of those is a caller deciding what a log reader sees, which is the whole of
     * what this class exists to prevent.
     *
     * <h2>What is kept and what is named</h2>
     *
     * <p>Printable ASCII - {@code ' '} through {@code '~'} - is kept exactly, so an ordinary value reads
     * as itself and a comparison against an expected value still works. Everything else, control bytes
     * and every character above the seven-bit range alike, is replaced by {@code U+XXXX}: four
     * upper-case hexadecimal digits naming the UTF-16 code unit. The rendering is therefore reversible
     * by a reader, which is what a diagnostic needs, and inert, which is what a log record needs.
     *
     * <p>The result is bounded at {@link #MAX_RENDERED_VALUE_LENGTH} <em>source</em> characters, after
     * which {@value #RENDERED_VALUE_TRUNCATION_MARKER} is appended. The bound is on the input rather
     * than the output so that the count a reader sees is a count of the value's characters. An absent
     * value renders as {@value #ABSENT_VALUE}, so a diagnostic never reads {@code null} where a value
     * belongs.
     *
     * <p>No locale participates: the hexadecimal digits are indexed out of a fixed table rather than
     * formatted, because a locale-sensitive conversion renders the same character differently under a
     * locale whose default numbering system is not Western Arabic, and a diagnostic that changes with
     * the locale is not a diagnostic.
     *
     * @param  value the value to render, which may be {@code null}
     * @return the rendering, never {@code null}, holding only printable ASCII
     */
    // See docs/decision-log.md entry DL-177.
    public static String printableForm(final String value) {
        if (value == null) {
            return ABSENT_VALUE;
        }
        final int retained = Math.min(value.length(), MAX_RENDERED_VALUE_LENGTH);
        final StringBuilder rendered = new StringBuilder(retained);
        for (int index = 0; index < retained; index++) {
            appendPrintable(rendered, value.charAt(index));
        }
        if (value.length() > retained) {
            rendered.append(RENDERED_VALUE_TRUNCATION_MARKER);
        }
        return rendered.toString();
    }

    /**
     * Renders one caller-supplied character inert for a diagnostic, in quotes when it is printable.
     *
     * <p>The quotes are what a positional comparison needs: they mark where the character begins and
     * ends, so a space found where a hyphen belongs is visible as a space rather than as a gap. A
     * character that is not printable ASCII is named by its code point instead and is not quoted, so the
     * two renderings cannot be confused with one another.
     *
     * @param  value the character to render
     * @return the character in single quotes when printable ASCII, otherwise {@code U+XXXX}
     */
    public static String printableForm(final char value) {
        if (isPrintableAscii(value)) {
            return "'" + value + "'";
        }
        final StringBuilder rendered = new StringBuilder(CODE_POINT_RENDERING_LENGTH);
        appendPrintable(rendered, value);
        return rendered.toString();
    }

    /**
     * Appends one character, kept when printable ASCII and named by its code point when not.
     *
     * @param target    the builder to append to
     * @param character the character to render
     */
    private static void appendPrintable(final StringBuilder target, final char character) {
        if (isPrintableAscii(character)) {
            target.append(character);
            return;
        }
        target.append('U').append('+');
        for (int digit = HEX_DIGITS_PER_CODE_UNIT - 1; digit >= 0; digit--) {
            target.append(HEX_DIGITS[(character >> (digit * BITS_PER_HEX_DIGIT)) & LOW_HEX_DIGIT_MASK]);
        }
    }

    /**
     * Reports whether a character may be written into a diagnostic as itself.
     *
     * <p>The admissible range is printable seven-bit ASCII and nothing else. Deliberately narrower than
     * {@link Character#isISOControl(char)} negated, which would admit every character above the
     * seven-bit range - among them the zero-width and bidirectional formatting code points, which render
     * as nothing or reorder what follows them, and either of which lets a value disguise itself in a
     * record a human is reading.
     *
     * @param  character the character being examined
     * @return {@code true} when the character may be kept as itself
     */
    private static boolean isPrintableAscii(final char character) {
        return character >= FIRST_PRINTABLE_ASCII && character <= LAST_PRINTABLE_ASCII;
    }
}
