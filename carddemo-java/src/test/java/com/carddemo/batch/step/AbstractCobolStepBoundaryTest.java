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
package com.carddemo.batch.step;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.step.AbstractCobolStep.ExecutionSummary;
import com.carddemo.batch.step.AbstractCobolStep.IoOperation;
import com.carddemo.batch.step.AbstractCobolStep.IoOutcome;
import com.carddemo.batch.step.AbstractCobolStep.IoResult;
import com.carddemo.exception.AbendException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.repeat.RepeatStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link AbstractCobolStep}, the template that carries the lifecycle every one of
 * the ten legacy batch programs shares.
 *
 * <h2>What is under test</h2>
 *
 * <p>The template factors the open, read-loop, status-check, close and abend skeleton that
 * {@code app/cbl/CBACT01C.cbl} establishes and the other nine batch programs repeat. It also
 * carries the two-level status model those programs depend on: a raw two-byte file status is
 * normalised into a coarse result value, and the program branches on the coarse value rather than
 * on the raw code.</p>
 *
 * <h2>Why the failure path is asserted to skip the close family entirely</h2>
 *
 * <p>The legacy terminal path is {@code CALL 'CEE3ABD'}, which does not return. Everything the
 * legacy program would have executed after the abend point — the close paragraphs, the
 * record-count diagnostic and the end-of-execution announcement — therefore never runs. A Java
 * template that closed its resources in a finally block would execute observable work the legacy
 * never executed, so the assertions below prove that a failure at open, at read or at process
 * produces a journal containing no close entry at all.</p>
 *
 * <h2>Why a separate release hook exists and is asserted to be the only cleanup</h2>
 *
 * <p>A JVM process outlives a failed step, whereas an abending z/OS enclave had its data sets
 * released by the operating system. The template therefore offers one non-observable
 * resource-release hook, distinct from the observable close paragraphs, and it is the only thing
 * that runs on the failure path. Its default body does nothing, a subclass may override it, and a
 * failure inside it is retained as a suppressed exception so it can never mask the primary
 * diagnosis. All three properties are asserted.</p>
 *
 * <h2>Why the clock is supplied rather than read from the platform</h2>
 *
 * <p>The template stamps a twenty-six byte start and completion timestamp, so a platform clock
 * would make every assertion below wall-clock dependent. The constructor requires a clock, and
 * these tests pin one, which is what lets the exact timestamp image be asserted character for
 * character rather than merely measured.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("AbstractCobolStep: the shared lifecycle of the ten legacy batch programs")
class AbstractCobolStepBoundaryTest {

    /** Byte width of the legacy batch timestamp, {@code PIC X(26)}. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Coarse result value standing for a clean operation. */
    private static final int APPL_RESULT_AOK = 0;

    /** Coarse result value standing for a diagnosed failure. */
    private static final int APPL_RESULT_ERROR = 12;

    /** Coarse result value standing for the at-end condition. */
    private static final int APPL_RESULT_EOF = 16;

    /** Coarse result value the legacy arms before an operation whose outcome is unknown. */
    private static final int APPL_RESULT_PENDING = 8;

    /** The clean file status. */
    private static final String STATUS_OK = "00";

    /** The at-end file status. */
    private static final String STATUS_END_OF_FILE = "10";

    /** A record-not-found file status, which the coarse model treats as an error. */
    private static final String STATUS_NOT_FOUND = "23";

    /** Name of the program the harness stands in for; within the legacy eight-byte culprit width. */
    private static final String PROGRAM = "CBACT01C";

    /** Name of the resource the harness acts on. */
    private static final String RESOURCE = "ACCTFILE";

    /** The metric the template times every lifecycle with. */
    private static final String METRIC = "carddemo.batch.cobol.step";

    /** A pinned instant, so both stamped timestamps are deterministic. */
    private static final Instant PINNED = Instant.parse("2024-02-29T14:35:12.340Z");

    /** The image the pinned instant must render as. */
    private static final String PINNED_IMAGE = "2024-02-29-14.35.12.340000";

    /** Journal entry written when the open paragraph runs. */
    private static final String OPEN = "open";

    /** Journal entry written when a read is attempted. */
    private static final String READ = "read";

    /** Journal entry written when a record is processed. */
    private static final String PROCESS = "process";

    /** Journal entry written when the observable close paragraphs run. */
    private static final String CLOSE = "close";

    /** Journal entry written when the non-observable release hook runs. */
    private static final String RELEASE = "release";

    /**
     * A concrete step that journals every lifecycle stage and can be told to fail at any of them.
     *
     * <p>It also exposes the template's protected input and output helpers, so the status model can
     * be exercised without a second fixture.</p>
     */
    private static final class HarnessStep extends AbstractCobolStep<String> {

        /** Ordered record of every lifecycle stage that actually executed. */
        private final List<String> journal = new ArrayList<>();

        /** Records the read loop will deliver before reporting the at-end condition. */
        private final Deque<String> pending = new ArrayDeque<>();

        /** Failure raised from the open paragraph, or {@code null} to open cleanly. */
        private RuntimeException openFailure;

        /** Failure raised from the read paragraph, or {@code null} to read cleanly. */
        private RuntimeException readFailure;

        /** Failure raised from the process paragraph, or {@code null} to process cleanly. */
        private RuntimeException processFailure;

        /** Failure raised from the close paragraphs, or {@code null} to close cleanly. */
        private RuntimeException closeFailure;

        /** Failure raised from the release hook, or {@code null} to release cleanly. */
        private RuntimeException releaseFailure;

        /** When set, the read paragraph breaks its contract by reporting no optional at all. */
        private boolean readReportsNull;

        HarnessStep(final MeterRegistry meterRegistry, final Clock clock) {
            super(PROGRAM, meterRegistry, clock);
        }

        @Override
        protected void openResources() {
            this.journal.add(OPEN);
            if (this.openFailure != null) {
                throw this.openFailure;
            }
        }

        @Override
        protected Optional<String> readNextRecord() {
            this.journal.add(READ);
            if (this.readFailure != null) {
                throw this.readFailure;
            }
            if (this.readReportsNull) {
                return null;
            }
            return Optional.ofNullable(this.pending.poll());
        }

        @Override
        protected void processRecord(final String record) {
            this.journal.add(PROCESS);
            if (this.processFailure != null) {
                throw this.processFailure;
            }
        }

        @Override
        protected void closeResources() {
            this.journal.add(CLOSE);
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }

        @Override
        protected void releaseResources() {
            this.journal.add(RELEASE);
            if (this.releaseFailure != null) {
                throw this.releaseFailure;
            }
        }

        void deliver(final String... records) {
            for (final String record : records) {
                this.pending.add(record);
            }
        }

        void openResourceUnderTest(final String resource, final IoAction<String> action) {
            openResource(resource, action);
        }

        void writeRecordUnderTest(final String resource, final IoAction<String> action) {
            writeRecord(resource, action);
        }

        void closeResourceUnderTest(final String resource, final IoAction<String> action) {
            closeResource(resource, action);
        }

        Optional<String> readRecordUnderTest(final String resource,
                final IoAction<IoResult<String>> action) {
            return readRecord(resource, action);
        }

        IoOutcome normaliseUnderTest(final IoOperation operation, final String rawStatus) {
            return normaliseStatus(operation, rawStatus);
        }

        void abendUnderTest(final IoOperation operation, final String resource,
                final String rawStatus) {
            abendOnIoFailure(operation, resource, rawStatus);
        }

        String programNameUnderTest() {
            return programName();
        }

        String currentTimestampUnderTest() {
            return currentBatchTimestamp();
        }
    }

    /**
     * A concrete step that leaves the release hook at its inherited default, so the default body
     * itself is exercised rather than a subclass override.
     */
    private static final class BareStep extends AbstractCobolStep<String> {

        /** Failure raised from the open paragraph, or {@code null} to open cleanly. */
        private RuntimeException openFailure;

        BareStep(final MeterRegistry meterRegistry, final Clock clock) {
            super(PROGRAM, meterRegistry, clock);
        }

        @Override
        protected void openResources() {
            if (this.openFailure != null) {
                throw this.openFailure;
            }
        }

        @Override
        protected Optional<String> readNextRecord() {
            return Optional.empty();
        }

        @Override
        protected void processRecord(final String record) {
            throw new AssertionError("no record is ever delivered to this fixture");
        }

        @Override
        protected void closeResources() {
            // The bare fixture holds nothing, matching a legacy program with no work files.
        }
    }

    /** A registry the assertions read timers back out of. */
    private final MeterRegistry registry = new SimpleMeterRegistry();

    /** A pinned clock, so both stamped timestamps are deterministic. */
    private final Clock clock = Clock.fixed(PINNED, ZoneOffset.UTC);

    /**
     * Builds a harness bound to the pinned clock and the readable registry.
     *
     * @return a fresh harness
     */
    private HarnessStep harness() {
        return new HarnessStep(this.registry, this.clock);
    }

    /**
     * Reads back the number of lifecycle timings recorded under one outcome tag.
     *
     * @param outcome the outcome tag to look for
     * @return the recorded count, or zero when no timer was registered
     */
    private long timedRuns(String outcome) {
        var timer = this.registry.find(METRIC).tag("step", PROGRAM).tag("outcome", outcome).timer();
        return (timer == null) ? 0L : timer.count();
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("the program name is carried through for the abend culprit field")
        void theProgramNameIsCarriedThrough() {
            assertThat(harness().programNameUnderTest()).isEqualTo(PROGRAM);
        }

        @Test
        @DisplayName("a null program name is refused")
        void aNullProgramNameIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new HarnessStepWithName(null, registry, clock))
                    .withMessageContaining("programName");
        }

        @ParameterizedTest(name = "the blank program name [{0}] is refused")
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank program name is refused, because the culprit field must name a program")
        void aBlankProgramNameIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new HarnessStepWithName(candidate, registry, clock))
                    .withMessageContaining("programName must name the legacy batch program");
        }

        @Test
        @DisplayName("a program name wider than the legacy culprit field is refused")
        void aProgramNameWiderThanTheCulpritFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new HarnessStepWithName("CBACT01CX", registry, clock))
                    .withMessageContaining(String.valueOf(AbendException.CULPRIT_LENGTH));
        }

        @Test
        @DisplayName("a program name at exactly the culprit width is accepted")
        void aProgramNameAtExactlyTheCulpritWidthIsAccepted() {
            assertThat(new HarnessStepWithName("CBSTM03A", registry, clock)
                    .programNameUnderTest()).hasSize(AbendException.CULPRIT_LENGTH);
        }

        @Test
        @DisplayName("a null registry is refused, because the lifecycle is always timed")
        void aNullRegistryIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new HarnessStep(null, clock))
                    .withMessageContaining("meterRegistry");
        }

        @Test
        @DisplayName("a null clock is refused, because no platform clock is ever substituted")
        void aNullClockIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new HarnessStep(registry, null))
                    .withMessageContaining("clock");
        }
    }

    /** A harness variant whose program name is supplied, so the name guard can be exercised. */
    private static final class HarnessStepWithName extends AbstractCobolStep<String> {

        HarnessStepWithName(final String programName, final MeterRegistry meterRegistry,
                final Clock clock) {
            super(programName, meterRegistry, clock);
        }

        @Override
        protected void openResources() {
            // Nothing to open; this fixture exists only to exercise the constructor guards.
        }

        @Override
        protected Optional<String> readNextRecord() {
            return Optional.empty();
        }

        @Override
        protected void processRecord(final String record) {
            throw new AssertionError("no record is ever delivered to this fixture");
        }

        @Override
        protected void closeResources() {
            // Nothing to close; this fixture exists only to exercise the constructor guards.
        }

        String programNameUnderTest() {
            return programName();
        }
    }

    @Nested
    @DisplayName("the successful lifecycle")
    class SuccessfulLifecycle {

        @Test
        @DisplayName("an empty input opens, reads once and closes, and never releases")
        void anEmptyInputOpensReadsOnceAndCloses() {
            HarnessStep step = harness();

            step.run();

            assertThat(step.journal).containsExactly(OPEN, READ, CLOSE);
            assertThat(step.journal).doesNotContain(RELEASE);
        }

        @Test
        @DisplayName("one record produces open, read, process, read, close and never releases")
        void oneRecordProducesTheCanonicalJournal() {
            HarnessStep step = harness();
            step.deliver("RECORD-1");

            step.run();

            assertThat(step.journal).containsExactly(OPEN, READ, PROCESS, READ, CLOSE);
            assertThat(step.journal).doesNotContain(RELEASE);
        }

        @Test
        @DisplayName("each delivered record is processed once, in the order it was read")
        void eachDeliveredRecordIsProcessedOnce() {
            HarnessStep step = harness();
            step.deliver("A", "B", "C");

            ExecutionSummary summary = step.run();

            assertThat(step.journal).containsExactly(OPEN, READ, PROCESS, READ, PROCESS,
                    READ, PROCESS, READ, CLOSE);
            assertThat(summary.recordsRead()).isEqualTo(3L);
        }

        @Test
        @DisplayName("the summary names the program and both stamped timestamps")
        void theSummaryNamesTheProgramAndBothTimestamps() {
            ExecutionSummary summary = harness().run();

            assertThat(summary.programName()).isEqualTo(PROGRAM);
            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.startedAt()).isEqualTo(PINNED_IMAGE);
            assertThat(summary.completedAt()).isEqualTo(PINNED_IMAGE);
        }

        @Test
        @DisplayName("both stamped timestamps measure the legacy twenty-six bytes")
        void bothStampedTimestampsMeasureTheLegacyWidth() {
            ExecutionSummary summary = harness().run();

            assertThat(summary.startedAt().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TIMESTAMP_WIDTH);
            assertThat(summary.completedAt().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the lifecycle is timed once under the completed outcome")
        void theLifecycleIsTimedOnceUnderTheCompletedOutcome() {
            harness().run();

            assertThat(timedRuns("COMPLETED")).isEqualTo(1L);
            assertThat(timedRuns("ABENDED")).isZero();
        }

        @Test
        @DisplayName("the tasklet entry point runs the lifecycle and reports it finished")
        void theTaskletEntryPointRunsTheLifecycleAndFinishes() {
            HarnessStep step = harness();
            step.deliver("RECORD-1");

            RepeatStatus status = step.execute(null, null);

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(step.journal).containsExactly(OPEN, READ, PROCESS, READ, CLOSE);
        }

        @Test
        @DisplayName("a read paragraph that reports no optional at all is refused")
        void aReadParagraphThatReportsNoOptionalIsRefused() {
            HarnessStep step = harness();
            step.readReportsNull = true;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining("readNextRecord must report an Optional");
        }
    }

    @Nested
    @DisplayName("the terminal failure path")
    class TerminalFailurePath {

        @Test
        @DisplayName("a failure opening never reaches the observable close paragraphs")
        void aFailureOpeningNeverReachesTheCloseParagraphs() {
            HarnessStep step = harness();
            step.openFailure = new IllegalStateException("open failed");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(step.journal).containsExactly(OPEN, RELEASE);
            assertThat(step.journal).doesNotContain(CLOSE);
        }

        @Test
        @DisplayName("a failure reading never reaches the observable close paragraphs")
        void aFailureReadingNeverReachesTheCloseParagraphs() {
            HarnessStep step = harness();
            step.readFailure = new IllegalStateException("read failed");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(step.journal).containsExactly(OPEN, READ, RELEASE);
            assertThat(step.journal).doesNotContain(CLOSE);
        }

        @Test
        @DisplayName("a failure processing never reaches the observable close paragraphs")
        void aFailureProcessingNeverReachesTheCloseParagraphs() {
            HarnessStep step = harness();
            step.deliver("RECORD-1");
            step.processFailure = new IllegalStateException("process failed");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(step.journal).containsExactly(OPEN, READ, PROCESS, RELEASE);
            assertThat(step.journal).doesNotContain(CLOSE);
        }

        @Test
        @DisplayName("a failure closing releases afterwards, because the close was already reached")
        void aFailureClosingReleasesAfterwards() {
            HarnessStep step = harness();
            step.closeFailure = new IllegalStateException("close failed");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(step.journal).containsExactly(OPEN, READ, CLOSE, RELEASE);
        }

        @Test
        @DisplayName("the release hook runs exactly once on the failure path")
        void theReleaseHookRunsExactlyOnceOnTheFailurePath() {
            HarnessStep step = harness();
            step.openFailure = new IllegalStateException("open failed");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(step.journal).filteredOn(RELEASE::equals).hasSize(1);
        }

        @Test
        @DisplayName("the primary diagnosis is rethrown unchanged, never wrapped")
        void thePrimaryDiagnosisIsRethrownUnchanged() {
            HarnessStep step = harness();
            IllegalStateException primary = new IllegalStateException("open failed");
            step.openFailure = primary;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .isSameAs(primary);
        }

        @Test
        @DisplayName("a diagnosed abend is rethrown as itself, keeping its code and culprit")
        void aDiagnosedAbendIsRethrownAsItself() {
            HarnessStep step = harness();
            step.openFailure = new AbendException(AbendException.BATCH_ABEND_CODE, PROGRAM,
                    "STATUS 23 OPENING ACCTFILE", "ERROR OPENING ACCTFILE");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                    });
        }

        @Test
        @DisplayName("a failure inside the release hook is suppressed onto the primary, never masking it")
        void aFailureInsideTheReleaseHookIsSuppressedOntoThePrimary() {
            HarnessStep step = harness();
            IllegalStateException primary = new IllegalStateException("open failed");
            IllegalStateException secondary = new IllegalStateException("release failed");
            step.openFailure = primary;
            step.releaseFailure = secondary;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .isSameAs(primary)
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(secondary));
        }

        @Test
        @DisplayName("the inherited release hook does nothing and lets the primary through")
        void theInheritedReleaseHookDoesNothing() {
            BareStep step = new BareStep(registry, clock);
            IllegalStateException primary = new IllegalStateException("open failed");
            step.openFailure = primary;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .isSameAs(primary)
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());
        }

        @Test
        @DisplayName("the inherited release hook leaves a clean lifecycle untouched")
        void theInheritedReleaseHookLeavesACleanLifecycleUntouched() {
            assertThat(new BareStep(registry, clock).run().recordsRead()).isZero();
        }

        @Test
        @DisplayName("the lifecycle is timed once under the abended outcome")
        void theLifecycleIsTimedOnceUnderTheAbendedOutcome() {
            HarnessStep step = harness();
            step.openFailure = new IllegalStateException("open failed");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(timedRuns("ABENDED")).isEqualTo(1L);
            assertThat(timedRuns("COMPLETED")).isZero();
        }

        @Test
        @DisplayName("the tasklet entry point propagates the failure rather than reporting finished")
        void theTaskletEntryPointPropagatesTheFailure() {
            HarnessStep step = harness();
            step.openFailure = new IllegalStateException("open failed");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> step.execute(null, null));
        }
    }

    @Nested
    @DisplayName("the coarse result values the batch tier branches on")
    class CoarseResultValues {

        @Test
        @DisplayName("exactly three coarse outcomes exist")
        void exactlyThreeCoarseOutcomesExist() {
            assertThat(IoOutcome.values())
                    .containsExactly(IoOutcome.OK, IoOutcome.END_OF_FILE, IoOutcome.ERROR);
        }

        @Test
        @DisplayName("each outcome carries the legacy coarse result value")
        void eachOutcomeCarriesTheLegacyValue() {
            assertThat(IoOutcome.OK.applResult()).isEqualTo(APPL_RESULT_AOK);
            assertThat(IoOutcome.END_OF_FILE.applResult()).isEqualTo(APPL_RESULT_EOF);
            assertThat(IoOutcome.ERROR.applResult()).isEqualTo(APPL_RESULT_ERROR);
        }

        @Test
        @DisplayName("the at-end value is kept separate from the error value")
        void theAtEndValueIsKeptSeparateFromTheErrorValue() {
            assertThat(IoOutcome.END_OF_FILE.applResult())
                    .isNotEqualTo(IoOutcome.ERROR.applResult());
        }

        @Test
        @DisplayName("each coarse value maps back to its own outcome")
        void eachCoarseValueMapsBackToItsOwnOutcome() {
            assertThat(IoOutcome.fromApplResult(APPL_RESULT_AOK)).isSameAs(IoOutcome.OK);
            assertThat(IoOutcome.fromApplResult(APPL_RESULT_EOF)).isSameAs(IoOutcome.END_OF_FILE);
            assertThat(IoOutcome.fromApplResult(APPL_RESULT_ERROR)).isSameAs(IoOutcome.ERROR);
        }

        @ParameterizedTest(name = "the unmapped coarse value {0} falls to the error outcome")
        @ValueSource(ints = {APPL_RESULT_PENDING, 4, 99, -1})
        @DisplayName("any value the legacy does not name falls to the error outcome")
        void anyUnnamedValueFallsToTheErrorOutcome(int value) {
            assertThat(IoOutcome.fromApplResult(value)).isSameAs(IoOutcome.ERROR);
        }
    }

    @Nested
    @DisplayName("the four operations the template performs")
    class Operations {

        @Test
        @DisplayName("exactly four operations exist")
        void exactlyFourOperationsExist() {
            assertThat(IoOperation.values()).containsExactly(IoOperation.OPEN, IoOperation.READ,
                    IoOperation.WRITE, IoOperation.CLOSE);
        }

        @Test
        @DisplayName("each operation carries the gerund the legacy diagnostic prints")
        void eachOperationCarriesItsLegacyGerund() {
            assertThat(IoOperation.OPEN.legacyGerund()).isEqualTo("OPENING");
            assertThat(IoOperation.READ.legacyGerund()).isEqualTo("READING");
            assertThat(IoOperation.WRITE.legacyGerund()).isEqualTo("WRITING TO");
            assertThat(IoOperation.CLOSE.legacyGerund()).isEqualTo("CLOSING");
        }

        @Test
        @DisplayName("only the read operation treats the at-end condition as a normal outcome")
        void onlyTheReadOperationTreatsAtEndAsNormal() {
            assertThat(IoOperation.READ.endOfFileTerminatesNormally()).isTrue();
            assertThat(IoOperation.OPEN.endOfFileTerminatesNormally()).isFalse();
            assertThat(IoOperation.WRITE.endOfFileTerminatesNormally()).isFalse();
            assertThat(IoOperation.CLOSE.endOfFileTerminatesNormally()).isFalse();
        }

        @Test
        @DisplayName("the read operation arms the error value, the others the pending value")
        void theReadOperationArmsTheErrorValue() {
            assertThat(IoOperation.READ.armedApplResult()).isEqualTo(APPL_RESULT_ERROR);
            assertThat(IoOperation.OPEN.armedApplResult()).isEqualTo(APPL_RESULT_PENDING);
            assertThat(IoOperation.WRITE.armedApplResult()).isEqualTo(APPL_RESULT_PENDING);
            assertThat(IoOperation.CLOSE.armedApplResult()).isEqualTo(APPL_RESULT_PENDING);
        }
    }

    @Nested
    @DisplayName("the two-level status normalisation")
    class StatusNormalisation {

        @Test
        @DisplayName("the clean status normalises to the clean outcome for every operation")
        void theCleanStatusNormalisesToTheCleanOutcome() {
            HarnessStep step = harness();

            for (IoOperation operation : IoOperation.values()) {
                assertThat(step.normaliseUnderTest(operation, STATUS_OK)).isSameAs(IoOutcome.OK);
            }
        }

        @Test
        @DisplayName("the at-end status is a normal outcome only for a read")
        void theAtEndStatusIsANormalOutcomeOnlyForARead() {
            HarnessStep step = harness();

            assertThat(step.normaliseUnderTest(IoOperation.READ, STATUS_END_OF_FILE))
                    .isSameAs(IoOutcome.END_OF_FILE);
            assertThat(step.normaliseUnderTest(IoOperation.OPEN, STATUS_END_OF_FILE))
                    .isSameAs(IoOutcome.ERROR);
            assertThat(step.normaliseUnderTest(IoOperation.WRITE, STATUS_END_OF_FILE))
                    .isSameAs(IoOutcome.ERROR);
            assertThat(step.normaliseUnderTest(IoOperation.CLOSE, STATUS_END_OF_FILE))
                    .isSameAs(IoOutcome.ERROR);
        }

        @ParameterizedTest(name = "the status [{0}] normalises to the error outcome")
        @ValueSource(strings = {"01", "02", "04", "05", "12", "22", "23", "31", "35"})
        @DisplayName("every status other than clean and at-end normalises to the error outcome")
        void everyOtherStatusNormalisesToTheErrorOutcome(String rawStatus) {
            assertThat(harness().normaliseUnderTest(IoOperation.READ, rawStatus))
                    .isSameAs(IoOutcome.ERROR);
        }

        @Test
        @DisplayName("a status the legacy never names normalises to the error outcome")
        void anUnrecognisedStatusNormalisesToTheErrorOutcome() {
            assertThat(harness().normaliseUnderTest(IoOperation.READ, "99"))
                    .isSameAs(IoOutcome.ERROR);
        }

        @Test
        @DisplayName("an absent status normalises to the error outcome rather than being guessed")
        void anAbsentStatusNormalisesToTheErrorOutcome() {
            assertThat(harness().normaliseUnderTest(IoOperation.READ, null))
                    .isSameAs(IoOutcome.ERROR);
        }

        @Test
        @DisplayName("a null operation is refused")
        void aNullOperationIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> harness().normaliseUnderTest(null, STATUS_OK))
                    .withMessageContaining("operation");
        }
    }

    @Nested
    @DisplayName("the two-way operations: open, write and close")
    class TwoWayOperations {

        @Test
        @DisplayName("a clean status lets the open return without diagnosis")
        void aCleanStatusLetsTheOpenReturn() {
            HarnessStep step = harness();

            step.openResourceUnderTest(RESOURCE, () -> STATUS_OK);

            assertThat(step.journal).isEmpty();
        }

        @Test
        @DisplayName("a clean status lets the write return without diagnosis")
        void aCleanStatusLetsTheWriteReturn() {
            harness().writeRecordUnderTest(RESOURCE, () -> STATUS_OK);
        }

        @Test
        @DisplayName("a clean status lets the close return without diagnosis")
        void aCleanStatusLetsTheCloseReturn() {
            harness().closeResourceUnderTest(RESOURCE, () -> STATUS_OK);
        }

        @Test
        @DisplayName("an error status abends the open, naming the operation and the resource")
        void anErrorStatusAbendsTheOpen() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().openResourceUnderTest(RESOURCE,
                            () -> STATUS_NOT_FOUND))
                    .satisfies(abend -> {
                        assertThat(abend.reason()).contains("OPENING", RESOURCE, STATUS_NOT_FOUND);
                        assertThat(abend.getMessage()).contains("OPENING", RESOURCE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                    });
        }

        @Test
        @DisplayName("an error status abends the write, naming the write gerund")
        void anErrorStatusAbendsTheWrite() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().writeRecordUnderTest(RESOURCE,
                            () -> STATUS_NOT_FOUND))
                    .satisfies(abend -> assertThat(abend.reason()).contains("WRITING TO"));
        }

        @Test
        @DisplayName("an error status abends the close, naming the close gerund")
        void anErrorStatusAbendsTheClose() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().closeResourceUnderTest(RESOURCE,
                            () -> STATUS_NOT_FOUND))
                    .satisfies(abend -> assertThat(abend.reason()).contains("CLOSING"));
        }

        @Test
        @DisplayName("the at-end status abends a close, because only a read tolerates it")
        void theAtEndStatusAbendsAClose() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().closeResourceUnderTest(RESOURCE,
                            () -> STATUS_END_OF_FILE));
        }

        @Test
        @DisplayName("a checked failure inside the action becomes an abend that keeps the cause")
        void aCheckedFailureInsideTheActionBecomesAnAbend() {
            Exception cause = new Exception("device unavailable");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().openResourceUnderTest(RESOURCE, () -> {
                        throw cause;
                    }))
                    .withCause(cause);
        }

        @Test
        @DisplayName("an abend already raised inside the action passes through undiagnosed twice")
        void anAbendAlreadyRaisedPassesThrough() {
            AbendException diagnosed = new AbendException(AbendException.BATCH_ABEND_CODE, PROGRAM,
                    "ALREADY DIAGNOSED", "ALREADY DIAGNOSED");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().openResourceUnderTest(RESOURCE, () -> {
                        throw diagnosed;
                    }))
                    .isSameAs(diagnosed);
        }

        @Test
        @DisplayName("an absent status is reported as such rather than printed as a null")
        void anAbsentStatusIsReportedAsSuch() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().openResourceUnderTest(RESOURCE, () -> null))
                    .satisfies(abend -> assertThat(abend.reason()).contains("(none)"));
        }

        @Test
        @DisplayName("a null resource name is refused")
        void aNullResourceNameIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> harness().openResourceUnderTest(null, () -> STATUS_OK))
                    .withMessageContaining("resource");
        }

        @ParameterizedTest(name = "the blank resource name [{0}] is refused")
        @ValueSource(strings = {"", " ", "  "})
        @DisplayName("a blank resource name is refused, because the diagnostic must name the file")
        void aBlankResourceNameIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> harness().openResourceUnderTest(candidate, () -> STATUS_OK))
                    .withMessageContaining("resource must name the file");
        }

        @Test
        @DisplayName("a null action is refused")
        void aNullActionIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> harness().openResourceUnderTest(RESOURCE, null))
                    .withMessageContaining("action");
        }

        @Test
        @DisplayName("an overlong diagnostic is bounded to the legacy reason and message widths")
        void anOverlongDiagnosticIsBoundedToTheLegacyWidths() {
            String longResource = "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS.WITH.A.VERY.LONG.NAME.INDEED";

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().openResourceUnderTest(longResource,
                            () -> STATUS_NOT_FOUND))
                    .satisfies(abend -> {
                        assertThat(abend.reason()).hasSize(AbendException.REASON_LENGTH);
                        assertThat(abend.getMessage()).hasSize(AbendException.MESSAGE_LENGTH);
                    });
        }
    }

    @Nested
    @DisplayName("the read operation")
    class ReadOperation {

        @Test
        @DisplayName("a clean status delivers the record")
        void aCleanStatusDeliversTheRecord() {
            assertThat(harness().readRecordUnderTest(RESOURCE,
                    () -> IoResult.of(STATUS_OK, "RECORD-1"))).contains("RECORD-1");
        }

        @Test
        @DisplayName("the at-end status delivers nothing without abending")
        void theAtEndStatusDeliversNothing() {
            assertThat(harness().readRecordUnderTest(RESOURCE,
                    () -> IoResult.endOfFile())).isEmpty();
        }

        @Test
        @DisplayName("an error status abends the read, naming the read gerund")
        void anErrorStatusAbendsTheRead() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().readRecordUnderTest(RESOURCE,
                            () -> new IoResult<String>(STATUS_NOT_FOUND, null)))
                    .satisfies(abend -> assertThat(abend.reason())
                            .contains("READING", STATUS_NOT_FOUND));
        }

        @Test
        @DisplayName("a clean status carrying no record is refused rather than delivered as absent")
        void aCleanStatusCarryingNoRecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> harness().readRecordUnderTest(RESOURCE,
                            () -> new IoResult<String>(STATUS_OK, null)))
                    .withMessageContaining("without delivering a record");
        }

        @Test
        @DisplayName("an action reporting no result at all is refused")
        void anActionReportingNoResultIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> harness().readRecordUnderTest(RESOURCE, () -> null))
                    .withMessageContaining("reported no result");
        }

        @Test
        @DisplayName("a checked failure inside the read becomes an abend that keeps the cause")
        void aCheckedFailureInsideTheReadBecomesAnAbend() {
            Exception cause = new Exception("device unavailable");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().readRecordUnderTest(RESOURCE, () -> {
                        throw cause;
                    }))
                    .withCause(cause);
        }

        @Test
        @DisplayName("a null resource name is refused")
        void aNullResourceNameIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> harness().readRecordUnderTest(null,
                            () -> IoResult.of(STATUS_OK, "RECORD-1")))
                    .withMessageContaining("resource");
        }

        @Test
        @DisplayName("a null action is refused")
        void aNullActionIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> harness().readRecordUnderTest(RESOURCE, null))
                    .withMessageContaining("action");
        }
    }

    @Nested
    @DisplayName("the read outcome carrier")
    class ReadOutcomeCarrier {

        @Test
        @DisplayName("a delivered outcome carries both the status and the record")
        void aDeliveredOutcomeCarriesBoth() {
            IoResult<String> result = IoResult.of(STATUS_OK, "RECORD-1");

            assertThat(result.rawStatus()).isEqualTo(STATUS_OK);
            assertThat(result.record()).isEqualTo("RECORD-1");
        }

        @Test
        @DisplayName("the at-end outcome carries the at-end status and no record")
        void theAtEndOutcomeCarriesTheAtEndStatus() {
            IoResult<String> result = IoResult.endOfFile();

            assertThat(result.rawStatus()).isEqualTo(STATUS_END_OF_FILE);
            assertThat(result.record()).isNull();
        }

        @Test
        @DisplayName("a null status is refused, because the coarse model always needs one")
        void aNullStatusIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new IoResult<String>(null, "RECORD-1"))
                    .withMessageContaining("rawStatus");
        }

        @Test
        @DisplayName("the delivering factory refuses a null record")
        void theDeliveringFactoryRefusesANullRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> IoResult.of(STATUS_OK, null))
                    .withMessageContaining("record");
        }

        @Test
        @DisplayName("the direct constructor still admits a null record, as the error path needs")
        void theDirectConstructorStillAdmitsANullRecord() {
            assertThat(new IoResult<String>(STATUS_NOT_FOUND, null).record()).isNull();
        }
    }

    @Nested
    @DisplayName("the execution summary")
    class Summary {

        @Test
        @DisplayName("a well-formed summary carries all four values")
        void aWellFormedSummaryCarriesAllFourValues() {
            ExecutionSummary summary =
                    new ExecutionSummary(PROGRAM, 42L, PINNED_IMAGE, PINNED_IMAGE);

            assertThat(summary.programName()).isEqualTo(PROGRAM);
            assertThat(summary.recordsRead()).isEqualTo(42L);
            assertThat(summary.startedAt()).isEqualTo(PINNED_IMAGE);
            assertThat(summary.completedAt()).isEqualTo(PINNED_IMAGE);
        }

        @Test
        @DisplayName("a zero record count is admitted, because an empty input is a normal outcome")
        void aZeroRecordCountIsAdmitted() {
            assertThat(new ExecutionSummary(PROGRAM, 0L, PINNED_IMAGE, PINNED_IMAGE).recordsRead())
                    .isZero();
        }

        @Test
        @DisplayName("a negative record count is refused")
        void aNegativeRecordCountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ExecutionSummary(PROGRAM, -1L, PINNED_IMAGE, PINNED_IMAGE))
                    .withMessageContaining("recordsRead must not be negative");
        }

        @Test
        @DisplayName("a null program name is refused")
        void aNullProgramNameIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ExecutionSummary(null, 0L, PINNED_IMAGE, PINNED_IMAGE))
                    .withMessageContaining("programName");
        }

        @Test
        @DisplayName("a null start stamp is refused")
        void aNullStartStampIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ExecutionSummary(PROGRAM, 0L, null, PINNED_IMAGE))
                    .withMessageContaining("startedAt");
        }

        @Test
        @DisplayName("a null completion stamp is refused")
        void aNullCompletionStampIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ExecutionSummary(PROGRAM, 0L, PINNED_IMAGE, null))
                    .withMessageContaining("completedAt");
        }
    }

    @Nested
    @DisplayName("the twenty-six byte batch timestamp")
    class BatchTimestamp {

        @Test
        @DisplayName("the pinned instant renders as its exact legacy image")
        void thePinnedInstantRendersAsItsExactImage() {
            assertThat(harness().currentTimestampUnderTest()).isEqualTo(PINNED_IMAGE);
        }

        @Test
        @DisplayName("the image measures exactly twenty-six encoded bytes")
        void theImageMeasuresExactlyTwentySixBytes() {
            assertThat(harness().currentTimestampUnderTest()
                    .getBytes(StandardCharsets.US_ASCII)).hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the stamp comes from the supplied clock, never from the platform clock")
        void theStampComesFromTheSuppliedClock() {
            HarnessStep other = new HarnessStep(registry,
                    Clock.fixed(Instant.parse("1999-12-31T23:59:59.990Z"), ZoneOffset.UTC));

            assertThat(other.currentTimestampUnderTest()).isEqualTo("1999-12-31-23.59.59.990000");
        }

        @Test
        @DisplayName("every component is zero padded to its legacy width")
        void everyComponentIsZeroPaddedToItsLegacyWidth() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 1, 2, 3, 4, 5, 60_000_000)))
                    .isEqualTo("2024-01-02-03.04.05.060000");
        }

        @Test
        @DisplayName("sub-hundredth precision is discarded rather than rounded")
        void subHundredthPrecisionIsDiscarded() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 1, 2, 3, 4, 5, 99_999_999)))
                    .isEqualTo("2024-01-02-03.04.05.090000");
        }

        @Test
        @DisplayName("a whole second renders zero hundredths")
        void aWholeSecondRendersZeroHundredths() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 12, 31, 23, 59, 59)))
                    .isEqualTo("2024-12-31-23.59.59.000000");
        }

        @Test
        @DisplayName("the lowest representable year is admitted")
        void theLowestRepresentableYearIsAdmitted() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(LocalDateTime.of(0, 1, 1, 0, 0)))
                    .startsWith("0000-01-01");
        }

        @Test
        @DisplayName("the highest representable year is admitted")
        void theHighestRepresentableYearIsAdmitted() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(9999, 12, 31, 23, 59, 59))).startsWith("9999-12-31");
        }

        @Test
        @DisplayName("a year above the legacy four-byte field is refused")
        void aYearAboveTheLegacyFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(
                            LocalDateTime.of(10_000, 1, 1, 0, 0)))
                    .withMessageContaining("cannot be held in the legacy four-byte year field");
        }

        @Test
        @DisplayName("a year below the legacy four-byte field is refused")
        void aYearBelowTheLegacyFieldIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(
                            LocalDateTime.of(-1, 1, 1, 0, 0)))
                    .withMessageContaining("cannot be held in the legacy four-byte year field");
        }

        @Test
        @DisplayName("a null moment is refused")
        void aNullMomentIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(null))
                    .withMessageContaining("moment");
        }

        @Test
        @DisplayName("the declared width constant is the legacy timestamp width")
        void theDeclaredWidthConstantIsTheLegacyWidth() {
            assertThat(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH).isEqualTo(TIMESTAMP_WIDTH);
        }
    }

    @Nested
    @DisplayName("the explicit abend entry point")
    class ExplicitAbend {

        @Test
        @DisplayName("it always abends, naming the operation, the resource and the raw status")
        void itAlwaysAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().abendUnderTest(IoOperation.WRITE, RESOURCE,
                            STATUS_NOT_FOUND))
                    .satisfies(abend -> {
                        assertThat(abend.reason())
                                .contains("WRITING TO", RESOURCE, STATUS_NOT_FOUND);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.getCause()).isNull();
                    });
        }

        @Test
        @DisplayName("it abends even when the status would otherwise be clean")
        void itAbendsEvenOnACleanStatus() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> harness().abendUnderTest(IoOperation.READ, RESOURCE,
                            STATUS_OK));
        }

        @Test
        @DisplayName("a null operation is refused")
        void aNullOperationIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> harness().abendUnderTest(null, RESOURCE, STATUS_OK))
                    .withMessageContaining("operation");
        }

        @Test
        @DisplayName("a blank resource name is refused")
        void aBlankResourceNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> harness().abendUnderTest(IoOperation.READ, " ", STATUS_OK))
                    .withMessageContaining("resource must name the file");
        }
    }
}
