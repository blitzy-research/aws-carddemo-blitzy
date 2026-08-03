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
 * <h2>Provenance</h2>
 *
 * <p>This class has no legacy antecedent. The migrated estate's only diagnostic channel was the
 * console display statement, and a display statement wrote a literal and a named field rather than a
 * failure object, so there was no throwable to render and no message to withhold. What it preserves is
 * the <em>property</em> that channel had: a legacy diagnostic named the outcome and the status code and
 * never echoed the data that produced them. Legacy estate read at checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19; no legacy source text is reproduced here.
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
     * Greatest number of characters one sanitised type name may occupy.
     *
     * <p>An ordinary type name is far shorter. The bound exists for a generated name - a proxy, a
     * lambda-hosting class or a synthetic wrapper - which can be arbitrarily long and would otherwise
     * set the size of the field on its own.
     */
    public static final int MAX_TYPE_NAME_LENGTH = 64;

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
}
