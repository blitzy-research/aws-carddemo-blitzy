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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Holds the context carrier to the one property that makes it worth having.
 *
 * <h2>What is actually at stake</h2>
 *
 * <p>Two places in this module hand an outbound provider call to a bounded worker so that a caller is
 * not delayed: the completion notifier and the readiness probes. A plain executor gives that worker a
 * thread with nothing current, so the call it makes becomes a <em>detached root</em> - a trace of one
 * span with no link to the launch or probe that caused it. The failure is silent in the strongest
 * sense: every span still exists, every duration is still right, and nothing anywhere reports an
 * error. Only the connection is missing, and only someone reading a trace notices.
 *
 * <p>So the assertions here are all about the executing thread rather than about a recorder. "The
 * context was carried" is the question "what does the worker find current", and it can only be
 * answered from inside the work. A test that inspected the collected spans afterwards would pass
 * against a carrier that did nothing, because the spans would be there either way.
 *
 * <h2>Why the no-context case is exercised as hard as the context case</h2>
 *
 * <p>Both call sites run in situations where nothing is current - a scheduled probe, a test, a launch
 * that is not itself observed - and in those situations the work must still run. A carrier that threw,
 * or that fabricated an observation to be the parent of, would convert an ordinary caller into a
 * failure or into a misleading trace. The unwrapped return is therefore a contract, not an
 * optimisation, and it is asserted as one.
 *
 * <p>Nothing here has a legacy antecedent: the estate ran one task at a time on one thread and had no
 * context to lose. See {@code docs/decision-log.md} entry DL-305.
 */
@DisplayName("ObservationPropagation - carrying a submitter's context onto a worker")
class ObservationPropagationTest {

    /** Name of the observation a test opens to stand in for a caller's context. */
    private static final String CALLER_OBSERVATION = "test.caller";

    /** How long a test waits for a worker to answer before treating the wait as a failure. */
    private static final long WORKER_TIMEOUT_SECONDS = 5L;

    /**
     * Captures something while a given observation is in scope on the calling thread.
     *
     * <p>Written as an explicit open-and-close rather than a resource declaration because the scope
     * handle is never referenced in the body, and the module compiles with {@code -Xlint:all -Werror},
     * which rejects an unreferenced resource. The behaviour is identical.
     *
     * @param  <T>     what is captured
     * @param  caller  the observation to make current for the duration of the capture
     * @param  capture the capture to perform while it is current
     * @return whatever the capture produced
     */
    private static <T> T whileObserving(final Observation caller, final Supplier<T> capture) {
        final Observation.Scope scope = caller.openScope();
        try {
            return capture.get();
        } finally {
            scope.close();
        }
    }

    /**
     * Builds a registry that mints real observations rather than the shared no-op one.
     *
     * <p>Necessary and not incidental. A registry with no handler answers every {@code start} with the
     * single {@code NOOP} observation, so an assertion that the worker sees "the same observation as the
     * submitter" holds whether anything was carried or not - it is two references to one singleton. With a
     * handler registered the registry mints a distinct observation per start, which is what makes every
     * identity assertion in this file a measurement.
     *
     * @return a registry with one accept-everything handler
     */
    private static ObservationRegistry recordingRegistry() {
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        return registry;
    }

    /**
     * Runs work on a different thread and returns what that thread found current, or {@code null}.
     *
     * <p>A separate thread is essential and not incidental: an observation is thread-bound, so work
     * that runs on the submitting thread would find the submitter's observation current whether it
     * was carried or not, and the test would pass against a carrier that returned its argument
     * untouched.
     *
     * @param  registry the registry the worker interrogates
     * @param  work     the work to run on the worker, already wrapped or deliberately not
     * @return the observation the worker found current, or {@code null} when it found none
     * @throws Exception if the worker does not answer within {@link #WORKER_TIMEOUT_SECONDS}
     */
    private static Observation currentOnAnotherThread(final ObservationRegistry registry,
            final Runnable work) throws Exception {
        final AtomicReference<Observation> seen = new AtomicReference<>();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            final Future<?> done = worker.submit(() -> {
                work.run();
                seen.set(registry.getCurrentObservation());
            });
            done.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
        return seen.get();
    }

    @Nested
    @DisplayName("Wrapping work that only completes")
    class WrappingRunnableWork {

        @Test
        @DisplayName("the worker finds the submitter's observation current, which is the whole point: "
                + "the outbound call becomes a child instead of a detached root")
        void theWorkerRunsInsideTheSubmittersObservation() throws Exception {
            final ObservationRegistry registry = recordingRegistry();
            final Observation caller = Observation.start(CALLER_OBSERVATION, registry);
            final AtomicReference<Observation> insideTheWork = new AtomicReference<>();
            final Runnable wrapped = whileObserving(caller,
                    () -> ObservationPropagation.inCurrentObservation(registry,
                            () -> insideTheWork.set(registry.getCurrentObservation())));
            caller.stop();

            final Observation afterTheWork = currentOnAnotherThread(registry, wrapped);

            assertThat(insideTheWork.get())
                    .as("the work must see the submitter's observation, not some other one and not none")
                    .isSameAs(caller);
            assertThat(afterTheWork)
                    .as("and the scope must be closed again, so the worker thread is not left carrying "
                            + "a finished observation into whatever it is handed next")
                    .isNull();
        }

        @Test
        @DisplayName("capture happens at wrap time, not at run time, because by the time the worker "
                + "runs the submitting thread has moved on")
        void captureHappensWhenWrappedRatherThanWhenRun() throws Exception {
            final ObservationRegistry registry = recordingRegistry();
            final Observation caller = Observation.start(CALLER_OBSERVATION, registry);
            final AtomicReference<Observation> insideTheWork = new AtomicReference<>();
            final Runnable wrapped = whileObserving(caller,
                    () -> ObservationPropagation.inCurrentObservation(registry,
                            () -> insideTheWork.set(registry.getCurrentObservation())));
            caller.stop();

            // Nothing is current anywhere now. A carrier that read the registry when the work ran
            // instead of when it was wrapped would find nothing here and carry nothing.
            assertThat(registry.getCurrentObservation()).isNull();
            currentOnAnotherThread(registry, wrapped);

            assertThat(insideTheWork.get()).isSameAs(caller);
        }

        @Test
        @DisplayName("with nothing current the work is returned unwrapped and still runs, because an "
                + "unobserved caller is an ordinary caller")
        void nothingCurrentReturnsTheWorkUnchanged() {
            final ObservationRegistry registry = recordingRegistry();
            final Runnable work = () -> {
                // Body irrelevant; identity is what is asserted.
            };

            assertThat(ObservationPropagation.inCurrentObservation(registry, work))
                    .as("returning the same instance is the observable form of 'nothing was added'")
                    .isSameAs(work);
        }

        @Test
        @DisplayName("a failure in the work propagates unchanged and the scope still closes, so a "
                + "tracing concern never becomes the caller's error")
        void aFailureInTheWorkPropagatesAndTheScopeStillCloses() throws Exception {
            final ObservationRegistry registry = recordingRegistry();
            final Observation caller = Observation.start(CALLER_OBSERVATION, registry);
            final IllegalStateException thrown = new IllegalStateException("the work failed");
            final Runnable wrapped = whileObserving(caller,
                    () -> ObservationPropagation.inCurrentObservation(registry, () -> {
                        throw thrown;
                    }));
            caller.stop();

            final AtomicReference<Observation> leftCurrent = new AtomicReference<>();
            final Observation observedAfterwards = currentOnAnotherThread(registry, () -> {
                assertThatIllegalStateException()
                        .isThrownBy(wrapped::run)
                        .isSameAs(thrown);
                leftCurrent.set(registry.getCurrentObservation());
            });

            assertThat(leftCurrent.get())
                    .as("the finally block must restore the thread even on the failing path")
                    .isNull();
            assertThat(observedAfterwards).isNull();
        }

        @Test
        @DisplayName("refuses an absent registry or an absent unit of work by name")
        void refusesAbsentArguments() {
            final ObservationRegistry registry = recordingRegistry();

            assertThatNullPointerException()
                    .isThrownBy(() -> ObservationPropagation.inCurrentObservation(null, () -> {
                        // Never invoked.
                    }))
                    .withMessageContaining("registry");
            assertThatNullPointerException()
                    .isThrownBy(() -> ObservationPropagation.inCurrentObservation(registry,
                            (Runnable) null))
                    .withMessageContaining("work");
        }
    }

    @Nested
    @DisplayName("Wrapping work that answers")
    class WrappingCallableWork {

        @Test
        @DisplayName("the worker runs inside the submitter's observation and the answer is returned "
                + "untouched, because a carrier that altered a result would be a defect not a trace")
        void theWorkerRunsInsideTheObservationAndTheAnswerSurvives() throws Exception {
            final ObservationRegistry registry = recordingRegistry();
            final Observation caller = Observation.start(CALLER_OBSERVATION, registry);
            final AtomicReference<Observation> insideTheWork = new AtomicReference<>();
            final Callable<Boolean> wrapped = whileObserving(caller,
                    () -> ObservationPropagation.<Boolean>callInCurrentObservation(registry, () -> {
                        insideTheWork.set(registry.getCurrentObservation());
                        return Boolean.TRUE;
                    }));
            caller.stop();

            final AtomicReference<Boolean> answer = new AtomicReference<>();
            final Observation afterTheWork = currentOnAnotherThread(registry, () -> {
                try {
                    answer.set(wrapped.call());
                } catch (final Exception failure) {
                    throw new AssertionError("the wrapped work must not fail here", failure);
                }
            });

            assertThat(insideTheWork.get()).isSameAs(caller);
            assertThat(answer.get()).isTrue();
            assertThat(afterTheWork).isNull();
        }

        @Test
        @DisplayName("with nothing current the work is returned unwrapped, so an unobserved probe is "
                + "answered exactly as before")
        void nothingCurrentReturnsTheWorkUnchanged() {
            final ObservationRegistry registry = recordingRegistry();
            final Callable<Boolean> work = () -> Boolean.TRUE;

            assertThat(ObservationPropagation.callInCurrentObservation(registry, work))
                    .isSameAs(work);
        }

        @Test
        @DisplayName("a checked failure in the work reaches the executor unchanged, which is what lets "
                + "the caller's own deadline handling stay in charge")
        void aCheckedFailurePropagatesUnchanged() throws Exception {
            final ObservationRegistry registry = recordingRegistry();
            final Observation caller = Observation.start(CALLER_OBSERVATION, registry);
            final Exception thrown = new Exception("the provider refused");
            final Callable<Boolean> wrapped = whileObserving(caller,
                    () -> ObservationPropagation.<Boolean>callInCurrentObservation(registry, () -> {
                        throw thrown;
                    }));
            caller.stop();

            final AtomicReference<Observation> leftCurrent = new AtomicReference<>();
            currentOnAnotherThread(registry, () -> {
                assertThatExceptionOfType(Exception.class)
                        .isThrownBy(wrapped::call)
                        .isSameAs(thrown);
                leftCurrent.set(registry.getCurrentObservation());
            });

            assertThat(leftCurrent.get()).isNull();
        }

        @Test
        @DisplayName("refuses an absent registry or an absent unit of work by name")
        void refusesAbsentArguments() {
            final ObservationRegistry registry = recordingRegistry();

            assertThatNullPointerException()
                    .isThrownBy(() -> ObservationPropagation.callInCurrentObservation(null,
                            () -> Boolean.TRUE))
                    .withMessageContaining("registry");
            assertThatNullPointerException()
                    .isThrownBy(() -> ObservationPropagation.callInCurrentObservation(registry,
                            (Callable<Boolean>) null))
                    .withMessageContaining("work");
        }
    }

    @Nested
    @DisplayName("The holder itself")
    class TheHolderItself {

        @Test
        @DisplayName("is never instantiated, so the carrier has exactly one form and no state")
        void isNeverInstantiated() throws Exception {
            final Constructor<ObservationPropagation> constructor =
                    ObservationPropagation.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(constructor::newInstance)
                    .withCauseInstanceOf(AssertionError.class);
        }
    }
}
