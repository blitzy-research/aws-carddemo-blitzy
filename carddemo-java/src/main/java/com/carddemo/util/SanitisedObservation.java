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
import java.util.function.Supplier;

import io.micrometer.observation.Observation;

/**
 * Runs one outbound call inside an observation, recording only a sanitised classification of a failure on
 * the span instead of the failure itself.
 *
 * <h2>The problem this exists to solve</h2>
 *
 * <p>{@link Observation#observe(Supplier)} starts the observation, opens its scope and - on a failure -
 * hands the <em>raw</em> throwable to {@link Observation#error(Throwable)} before rethrowing it. That
 * third step is the problem, because the tracing bridge turns a recorded error into exported span data:
 * the exception type, its message and its stack trace, all leaving the process for whichever collector
 * the deployment points at.
 *
 * <p>A provider failure is the one value crossing this module's outbound boundaries whose text this
 * module does not author. An object-store refusal names the bucket and key it refused; a queue failure
 * names the queue and frequently the endpoint and the credential's principal; a driver failure names the
 * connection string. {@code util/FailureDiagnostics} exists for exactly that reason, and every boundary
 * <em>log</em> site already publishes a bounded type chain rather than the throwable. The span was the
 * one channel still carrying the unedited object, so the value the log deliberately withheld was being
 * exported anyway, to a destination with a different audience and a different retention period. It is a
 * volume lever as well as a disclosure one: a caller who can provoke a failure whose message they
 * influence chooses how many bytes each of their requests writes into the collector.
 *
 * <h2>What is recorded, and what is withheld</h2>
 *
 * <p>Recorded: a {@link SanitisedBoundaryFailure} whose message is composed only of the bounded type
 * chain from {@link FailureDiagnostics#failureChainOf(Throwable)} and the bounded code origin from
 * {@link FailureDiagnostics#failureOriginOf(Throwable)}. Both are properties of the code - type names and
 * frame metadata fixed at compile time - so neither can carry a value.
 *
 * <p>Withheld: the failure's message, its localised message, its suppressed throwables, its own stack
 * frames and every field it holds. The sanitised stand-in carries <em>no cause</em>, so a tracing handler
 * that walks causes finds nothing beneath it, and it is constructed with its stack trace suppressed, so
 * there is no frame list to render either.
 *
 * <h2>What deliberately does not change</h2>
 *
 * <p>The call's own outcome. The original failure is rethrown, unwrapped and unaltered, to the caller
 * that asked for the call, so every existing handler, non-fatal verdict and sanitised log record at the
 * call site behaves exactly as before. This class edits what the <em>span</em> says and nothing else: the
 * observation is still started, still scoped around the call so a nested observation descends from it,
 * still marked as a failure rather than left looking successful, and still stopped on every path.
 *
 * <h2>Why a helper rather than five hand-written blocks</h2>
 *
 * <p>Five outbound boundaries need this - the queue publish, the notification publish, the two
 * object-store families and the batch publication callback - and a hand-written start/scope/error/stop
 * block at each is five chances to forget the {@code stop()} on one path or to pass the raw throwable at
 * one site. One helper makes the policy a single decision and makes "does any boundary export a raw
 * provider failure" answerable by reading one file. It sits in the utility layer beside
 * {@link ObservationPropagation} and {@link FailureDiagnostics}, the other two halves of the same
 * concern, depends on nothing above it, holds no state, reads no configuration and declares no logger.
 *
 * <p>See {@code docs/decision-log.md} entry DL-341. Nothing here has a legacy antecedent: the estate's
 * only diagnostic channel was a console display statement, which wrote a literal and a named field and
 * had no failure object to export.
 *
 * @since 1.0.0
 */
public final class SanitisedObservation {

    /** Opens the sanitised classification, so a reader can tell it from a provider message at a glance. */
    public static final String CLASSIFICATION_PREFIX = "outbound boundary failure";

    /** Introduces the bounded type chain within the classification. */
    public static final String FAILURE_CHAIN_LABEL = "failureChain=";

    /** Introduces the bounded code origin within the classification. */
    public static final String FAILURE_ORIGIN_LABEL = "failureOrigin=";

    /** Utility holder; never instantiated. */
    private SanitisedObservation() {
        throw new AssertionError("SanitisedObservation is a utility holder and is never instantiated");
    }

    /**
     * Runs a result-returning call inside the supplied observation.
     *
     * <p>The observation is started, its scope is open for the duration of the call so that anything the
     * call observes descends from it, a failure is recorded as a sanitised classification, and the
     * observation is stopped on every path. The failure itself is rethrown unchanged.
     *
     * @param  <T>         the call's result type
     * @param  observation the observation to run inside; must not be {@code null} and must not already be
     *                     started, because this method starts it
     * @param  call        the outbound call; must not be {@code null}
     * @return whatever the call returned, including {@code null} if that is what it answered
     * @throws NullPointerException if either argument is {@code null}
     */
    public static <T> T observe(final Observation observation, final Supplier<T> call) {
        Objects.requireNonNull(observation, "observation must not be null");
        Objects.requireNonNull(call, "call must not be null");
        observation.start();
        // Opened and closed explicitly rather than through a resource declaration, following
        // ObservationPropagation: the scope reference is never read inside the body, which a resource
        // declaration reports as a lint finding and this build treats as an error.
        final Observation.Scope scope = observation.openScope();
        try {
            return call.get();
        } catch (final RuntimeException | Error failure) {
            observation.error(sanitisedFailureFor(failure));
            throw failure;
        } finally {
            // Scope first, then the observation: the scope must be off this thread before the
            // observation ends, which is the order the framework's own convenience form uses.
            scope.close();
            observation.stop();
        }
    }

    /**
     * Runs a call that answers nothing inside the supplied observation.
     *
     * <p>Same contract as {@link #observe(Observation, Supplier)}. A separate name rather than an
     * overload, following the convention {@link ObservationPropagation} sets: a lambda that calls a
     * value-returning method satisfies both shapes, and an overload would let a call site pick the
     * discarding form by accident.
     *
     * @param  observation the observation to run inside; must not be {@code null} and must not already be
     *                     started
     * @param  call        the outbound call; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static void observeRunnable(final Observation observation, final Runnable call) {
        Objects.requireNonNull(call, "call must not be null");
        observe(observation, () -> {
            call.run();
            return null;
        });
    }

    /**
     * Renders one failure as the stand-in that may be recorded on a span.
     *
     * <p>Published rather than private because it is the contract a test asserts against: a canary that
     * proves no boundary exports a provider message needs to name what a boundary <em>does</em> export.
     *
     * @param  failure the failure to classify; must not be {@code null}
     * @return a throwable carrying only the bounded type chain and code origin, with no cause and no
     *         stack trace
     * @throws NullPointerException if {@code failure} is {@code null}
     */
    public static SanitisedBoundaryFailure sanitisedFailureFor(final Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        return new SanitisedBoundaryFailure(CLASSIFICATION_PREFIX + ": "
                + FAILURE_CHAIN_LABEL + FailureDiagnostics.failureChainOf(failure)
                + " " + FAILURE_ORIGIN_LABEL + FailureDiagnostics.failureOriginOf(failure));
    }

    /**
     * The only failure this module records on a span.
     *
     * <p>It carries a message this module composed in full, no cause, and no stack trace. Those three
     * properties are what make it exportable: a tracing handler that reads the message publishes only
     * authored text, one that walks the causes finds nothing, and one that renders frames renders none.
     *
     * <p>Never thrown to a caller and never caught: it exists to be handed to
     * {@link Observation#error(Throwable)} and nowhere else. That is why it extends
     * {@link RuntimeException} rather than a module exception type - it is a diagnostic value in the shape
     * the tracing API takes, not a condition any code handles.
     */
    public static final class SanitisedBoundaryFailure extends RuntimeException {

        /** Serialization identity; this type is never serialized but the compiler asks for it. */
        private static final long serialVersionUID = 1L;

        /**
         * @param classification the authored classification; the whole of what this failure publishes
         */
        private SanitisedBoundaryFailure(final String classification) {
            // No cause, and the stack trace suppressed: the last two arguments of this constructor are
            // the whole point of using it. Suppression is not an optimisation - a stack trace is a list
            // of frames a handler may render, and the only frames available here are this module's own
            // helper, which says nothing a reader needs and which the recorded origin already names in
            // its bounded form.
            super(classification, null, false, false);
        }
    }
}
