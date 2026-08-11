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
import java.util.concurrent.Callable;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Carries the submitting thread's observation onto a thread that runs work on its behalf.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>An observation, and therefore a trace context, is thread-bound, so handing work to any plain executor
 * severs it: the work runs with nothing current and whatever it observes becomes a <em>detached root</em>,
 * a trace of one span unconnected to the request or job that caused it. Two places in this module hand
 * work to a bounded worker for reasons that have nothing to do with tracing and everything to do with not
 * blocking a caller - the completion notifier, so a slow topic cannot delay a job's finalisation, and the
 * readiness checks, so an unreachable resource cannot delay a probe past its deadline.
 *
 * <p>Nothing about the executors changes. The queue, ceiling, rejection behaviour and deadline semantics
 * of each are load-bearing and are documented where they are declared; replacing them with a
 * context-propagating variant would change those properties as a side effect of fixing tracing, which is
 * the wrong trade. What changes is only that the work is wrapped: the submitting observation is captured
 * <em>on the submitting thread</em> and its scope is opened around the work on the executing one.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not create an observation, name one, tag one or stop one - the work it wraps observes
 * whatever it already observed, and now does so as a child. It also does not fail when there is nothing to
 * carry: with no current observation the work runs exactly as before, because a caller with no context is
 * a normal case, such as a scheduled probe, a test, or a job launched by something not itself observed.
 *
 * <p>See {@code docs/decision-log.md} entry DL-305. Nothing here has a legacy antecedent: the estate ran
 * one task at a time on one thread and had no context to lose.
 *
 * @since 1.0.0
 */
public final class ObservationPropagation {

    /** Utility holder; never instantiated. */
    private ObservationPropagation() {
        throw new AssertionError("ObservationPropagation is a utility holder and is never instantiated");
    }

    /**
     * Wraps work so that it runs inside the observation that is current <em>now</em>.
     *
     * <p>Call this on the submitting thread and hand the result to the executor. Capturing at call time is
     * the whole point: by the time the executor runs the work, the submitting thread has moved on and its
     * observation is no longer current anywhere.
     *
     * <p>The scope is closed in a {@code finally} block rather than by a resource declaration, so the
     * restoration happens whether the work returns or raises, and no exception from the work is swallowed
     * or replaced.
     *
     * @param  registry the registry to read the current observation from; must not be {@code null}
     * @param  work     the work to wrap; must not be {@code null}
     * @return the same work, wrapped to run inside the captured observation, or {@code work} itself when
     *         there is no current observation to carry
     */
    public static Runnable inCurrentObservation(final ObservationRegistry registry,
            final Runnable work) {
        Objects.requireNonNull(registry, "registry must not be null");
        final Runnable required = Objects.requireNonNull(work, "work must not be null");
        final Observation submitting = registry.getCurrentObservation();
        if (submitting == null) {
            return required;
        }
        return () -> {
            final Observation.Scope scope = submitting.openScope();
            try {
                required.run();
            } finally {
                scope.close();
            }
        };
    }

    /**
     * Wraps result-returning work so that it runs inside the observation that is current <em>now</em>.
     *
     * <p>The same capture-and-reopen as {@link #inCurrentObservation(ObservationRegistry, Runnable)}, for
     * the executor submissions that need an answer back rather than only a completion. Two methods rather
     * than one adapter, because turning a result-returning call into a runnable would mean holding its
     * result somewhere and reading it afterwards, which is exactly the kind of shared mutable handoff this
     * module has no reason to introduce.
     *
     * <p><strong>Deliberately a different name rather than an overload.</strong> A lambda calling a method
     * that returns a value satisfies both {@code Runnable} and {@code Callable}, so an overloaded pair
     * would let a call site select the result-returning form by accident and discard the result silently.
     * Distinct names make the choice explicit at every call site.
     *
     * @param  <T>      the result type
     * @param  registry the registry to read the current observation from; must not be {@code null}
     * @param  work     the work to wrap; must not be {@code null}
     * @return the same work, wrapped to run inside the captured observation, or {@code work} itself when
     *         there is no current observation to carry
     */
    public static <T> Callable<T> callInCurrentObservation(final ObservationRegistry registry,
            final Callable<T> work) {
        Objects.requireNonNull(registry, "registry must not be null");
        final Callable<T> required = Objects.requireNonNull(work, "work must not be null");
        final Observation submitting = registry.getCurrentObservation();
        if (submitting == null) {
            return required;
        }
        return () -> {
            final Observation.Scope scope = submitting.openScope();
            try {
                return required.call();
            } finally {
                scope.close();
            }
        };
    }
}
