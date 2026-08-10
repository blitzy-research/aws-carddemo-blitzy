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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.repeat.RepeatStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link AbstractCobolStep}, the Template Method that factors the
 * skeleton every CardDemo batch program shares.
 *
 * <h2>What is under test</h2>
 *
 * <p>All ten batch programs in the estate have the same shape: announce the
 * start, open the files, read until end of file processing each record, close the
 * files, announce the end — and on any input-output failure, display the raw file
 * status and abort through the Language Environment. This class holds that
 * skeleton once so each concrete step supplies only its record processing.</p>
 *
 * <h2>The two-level status model</h2>
 *
 * <p>The batch programs do not branch on the raw two-byte file status. They
 * normalise it first into a coarse result variable and then branch on named
 * conditions. That variable is referenced 223 times across the estate, and it is
 * the coarse value — not the raw code — that the programs actually test. A single
 * flat enumeration would erase the distinction between end of file and error, and
 * every batch read loop depends on that distinction, so the target carries both
 * levels: a raw {@link FileStatus} vocabulary and the tri-state
 * {@link IoOutcome} mirroring the coarse variable's values of 0, 16 and 12.</p>
 *
 * <p>The most consequential asymmetry these tests pin is that end of file is a
 * <em>normal</em> outcome for a read and an <em>error</em> for anything else. A
 * read loop terminates on end of file; an open or a close that reports it has
 * genuinely failed. {@link IoOperation} therefore carries that property per
 * operation rather than applying one rule everywhere.</p>
 *
 * <h2>Determinism</h2>
 *
 * <p>Every timestamp is taken from an injected {@link Clock}. The step has
 * exactly one constructor and it requires that clock, so no execution can quietly
 * fall back to the platform default zone; these tests inject a fixed UTC clock
 * and assert the resulting 26-character timestamp image exactly.</p>
 *
 * <p>Provenance: legacy sources read at commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No legacy source text
 * is reproduced here.</p>
 */
@DisplayName("AbstractCobolStep - the shared open, read-loop, close and abend skeleton")
class AbstractCobolStepBaselineTest {

    /** A legacy program name at the full eight-character culprit width. */
    private static final String PROGRAM_NAME = "CBACT01C";

    /** The resource name a step reports in its diagnostics. */
    private static final String RESOURCE = "ACCTDAT";

    /** The metric the skeleton times every execution with. */
    private static final String STEP_TIMER = "carddemo.batch.cobol.step";

    /** A fixed instant so every timestamp assertion is exact. */
    private static final Instant FIXED_INSTANT = Instant.parse("2024-01-15T10:20:30.456789Z");

    /** The legacy batch timestamp width in bytes. */
    private static final int TIMESTAMP_WIDTH = 26;

    private MeterRegistry meterRegistry;

    private Clock fixedUtcClock;

    @BeforeEach
    void setUp() {
        this.meterRegistry = new SimpleMeterRegistry();
        this.fixedUtcClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }

    /**
     * A concrete step that records its lifecycle so the template's ordering can be
     * asserted, and that can be told to fail at any one of the four extension
     * points. Nothing here is a stub: each override does exactly the observable
     * work the assertions need.
     */
    private static final class RecordingStep extends AbstractCobolStep<String> {

        private final Deque<String> pending = new ArrayDeque<>();
        private final List<String> lifecycle = new ArrayList<>();
        private final List<String> processed = new ArrayList<>();

        private RuntimeException openFailure;
        private RuntimeException processFailure;
        private RuntimeException closeFailure;
        private RuntimeException releaseFailure;
        private boolean readReportsNull;

        private RecordingStep(final String programName, final MeterRegistry meterRegistry,
                final Clock clock) {
            super(programName, meterRegistry, clock);
        }

        private RecordingStep withRecords(final String... records) {
            for (final String record : records) {
                this.pending.addLast(record);
            }
            return this;
        }

        @Override
        protected void openResources() {
            this.lifecycle.add("open");
            if (this.openFailure != null) {
                throw this.openFailure;
            }
        }

        @Override
        protected Optional<String> readNextRecord() {
            this.lifecycle.add("read");
            if (this.readReportsNull) {
                return null;
            }
            return Optional.ofNullable(this.pending.pollFirst());
        }

        @Override
        protected void processRecord(final String record) {
            this.lifecycle.add("process");
            this.processed.add(record);
            if (this.processFailure != null) {
                throw this.processFailure;
            }
        }

        @Override
        protected void closeResources() {
            this.lifecycle.add("close");
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }

        /**
         * Records the failure-path handle release, which the template invokes instead of the close
         * family once anything has thrown.
         *
         * <p>This is a distinct entry in the recorded lifecycle rather than another {@code "close"},
         * because the two are distinct in the legacy: the close family is a sequence of observable
         * {@code CLOSE} paragraphs with normalised statuses and diagnostics, whereas this is a runtime
         * handle release with no legacy antecedent at all.</p>
         */
        @Override
        protected void releaseResources() {
            this.lifecycle.add("release");
            if (this.releaseFailure != null) {
                throw this.releaseFailure;
            }
        }

        /** Exposes the protected read helper so its classification can be asserted. */
        private Optional<String> readVia(final String resource, final IoAction<IoResult<String>> action) {
            return readRecord(resource, action);
        }

        /** Exposes the protected open helper. */
        private void openVia(final String resource, final IoAction<String> action) {
            openResource(resource, action);
        }

        /** Exposes the protected write helper. */
        private void writeVia(final String resource, final IoAction<String> action) {
            writeRecord(resource, action);
        }

        /** Exposes the protected close helper. */
        private void closeVia(final String resource, final IoAction<String> action) {
            closeResource(resource, action);
        }

        /** Exposes the protected status normaliser. */
        private IoOutcome normalise(final IoOperation operation, final String rawStatus) {
            return normaliseStatus(operation, rawStatus);
        }

        /** Exposes the protected unconditional abend. */
        private void abend(final IoOperation operation, final String resource, final String rawStatus) {
            abendOnIoFailure(operation, resource, rawStatus);
        }

        /** Exposes the protected program-name accessor. */
        private String name() {
            return programName();
        }

        /** Exposes the protected clock-derived timestamp. */
        private String timestamp() {
            return currentBatchTimestamp();
        }
    }

    private RecordingStep step() {
        return new RecordingStep(PROGRAM_NAME, this.meterRegistry, this.fixedUtcClock);
    }

    /** Locates the execution timer carrying the given outcome tag, if it was recorded. */
    private Timer timerFor(final String outcome) {
        return this.meterRegistry.find(STEP_TIMER)
                .tag("step", PROGRAM_NAME)
                .tag("outcome", outcome)
                .timer();
    }

    @Nested
    @DisplayName("Construction - the clock is required, so no step can use the platform default zone")
    class Construction {

        @Test
        @DisplayName("accepts a program name, a registry, and a clock")
        void acceptsTheThreeCollaborators() {
            assertThatCode(() -> new RecordingStep(PROGRAM_NAME, meterRegistry, fixedUtcClock))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a null clock, which is what forbids a silent default-zone fallback")
        void refusesANullClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RecordingStep(PROGRAM_NAME, meterRegistry, null))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("refuses a null meter registry")
        void refusesANullMeterRegistry() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RecordingStep(PROGRAM_NAME, null, fixedUtcClock))
                    .withMessageContaining("meterRegistry");
        }

        @Test
        @DisplayName("refuses a null program name")
        void refusesANullProgramName() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RecordingStep(null, meterRegistry, fixedUtcClock))
                    .withMessageContaining("programName");
        }

        @ParameterizedTest(name = "refuses a program name of [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("refuses a blank program name")
        void refusesABlankProgramName(final String programName) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RecordingStep(programName, meterRegistry, fixedUtcClock))
                    .withMessageContaining("must name the legacy batch program");
        }

        @Test
        @DisplayName("refuses a program name wider than the legacy culprit field")
        void refusesAnOverlongProgramName() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new RecordingStep("C".repeat(9), meterRegistry, fixedUtcClock))
                    .withMessageContaining("exceeds the legacy ABEND-CULPRIT width of "
                            + AbendException.CULPRIT_LENGTH);
        }

        @Test
        @DisplayName("exposes the program name it stands in for")
        void exposesTheProgramName() {
            assertThat(step().name()).isEqualTo(PROGRAM_NAME);
        }
    }

    @Nested
    @DisplayName("run - the lifecycle every batch program shares")
    class RunLifecycle {

        @Test
        @DisplayName("opens once, reads until end of file, processes each record, then closes once")
        void runsTheLifecycleInOrder() {
            final RecordingStep step = step().withRecords("first", "second");

            final ExecutionSummary summary = step.run();

            assertThat(step.lifecycle).containsExactly(
                    "open", "read", "process", "read", "process", "read", "close");
            assertThat(step.processed).containsExactly("first", "second");
            assertThat(summary.recordsRead()).isEqualTo(2L);
            assertThat(summary.programName()).isEqualTo(PROGRAM_NAME);
        }

        @Test
        @DisplayName("still opens and closes when the input holds no records at all")
        void handlesAnEmptyInput() {
            final RecordingStep step = step();

            final ExecutionSummary summary = step.run();

            assertThat(step.lifecycle).containsExactly("open", "read", "close");
            assertThat(summary.recordsRead()).isZero();
        }

        @Test
        @DisplayName("stamps both timestamps from the injected clock at the legacy width")
        void stampsTimestampsFromTheInjectedClock() {
            final String expected = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));

            final ExecutionSummary summary = step().run();

            assertThat(summary.startedAt()).isEqualTo(expected).hasSize(TIMESTAMP_WIDTH);
            assertThat(summary.completedAt()).isEqualTo(expected).hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("records a completed execution against the step timer")
        void recordsACompletedExecution() {
            step().run();

            assertThat(timerFor("COMPLETED")).isNotNull();
            assertThat(timerFor("COMPLETED").count()).isEqualTo(1L);
            assertThat(timerFor("ABENDED")).isNull();
        }

        @Test
        @DisplayName("refuses a read that reports null instead of an empty optional")
        void refusesANullReadResult() {
            final RecordingStep step = step();
            step.readReportsNull = true;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining("readNextRecord must report an Optional, never null");
        }

        @Test
        @DisplayName("reports the same summary the tasklet entry point drives")
        void tellsTheTaskletTheStepIsFinished() throws Exception {
            final RecordingStep step = step().withRecords("only");

            final RepeatStatus status = step.execute(null, null);

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(step.processed).containsExactly("only");
            assertThat(step.lifecycle).contains("open", "close");
        }
    }

    @Nested
    @DisplayName("run - failure handling releases resources and re-raises")
    class RunFailureHandling {

        @Test
        @DisplayName("releases the handles it had opened when opening fails, skips the close family, "
                + "then re-raises")
        void releasesWhenOpeningFails() {
            final RecordingStep step = step();
            step.openFailure = new IllegalStateException("cannot open");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .withMessage("cannot open");

            // The finding this asserts is unchanged: a failure must not leak whatever the step opened.
            // What it asserts through has changed, and correctly so. The legacy abend routine ends with
            // the CEE3ABD call [app/cbl/CBACT01C.cbl:L173], which does not return, so the close of
            // 9000-ACCTFILE-CLOSE at line 83 is unreachable once any guarded operation has abended.
            // Driving the observable close family from the failure path would therefore fabricate CLOSE
            // operations, with their statuses and diagnostics, that the mainframe never performs. The
            // template runs the non-observable release instead, and this asserts both halves: the
            // release ran, and the close family did not.
            assertThat(step.lifecycle)
                    .as("the release path runs even though the read loop never started")
                    .containsExactly("open", "release");
            assertThat(step.lifecycle)
                    .as("an abend is terminal, so the observable close family must be skipped")
                    .doesNotContain("close");
        }

        @Test
        @DisplayName("releases the handles when processing fails, skips the close family, then "
                + "re-raises the original failure")
        void releasesWhenProcessingFails() {
            final RecordingStep step = step().withRecords("first");
            step.processFailure = new IllegalStateException("cannot process");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .withMessage("cannot process");

            assertThat(step.lifecycle).containsExactly("open", "read", "process", "release");
            assertThat(step.lifecycle)
                    .as("a failure inside the read loop is as terminal as one in the open")
                    .doesNotContain("close");
        }

        @Test
        @DisplayName("does not close twice when the close itself fails")
        void doesNotCloseTwiceWhenClosingFails() {
            final RecordingStep step = step();
            step.closeFailure = new IllegalStateException("cannot close");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .withMessage("cannot close");

            assertThat(step.lifecycle)
                    .as("the close family is entered exactly once, and the release that follows a "
                            + "failure does not re-enter it")
                    .containsExactly("open", "read", "close", "release");
            assertThat(step.lifecycle.stream().filter("close"::equals).count())
                    .as("a failing close must not be retried")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("retains a secondary release failure as suppressed rather than losing the first")
        void retainsASecondaryFailureAsSuppressed() {
            final RecordingStep step = step();
            step.openFailure = new IllegalStateException("primary");
            step.releaseFailure = new IllegalStateException("secondary");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .withMessage("primary")
                    .satisfies(primary -> assertThat(primary.getSuppressed())
                            .as("the release failure must not mask the real cause")
                            .hasSize(1)
                            .allSatisfy(secondary ->
                                    assertThat(secondary).hasMessage("secondary")));
        }

        @Test
        @DisplayName("records an abended execution against the step timer")
        void recordsAnAbendedExecution() {
            final RecordingStep step = step();
            step.openFailure = new IllegalStateException("cannot open");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(timerFor("ABENDED")).isNotNull();
            assertThat(timerFor("ABENDED").count()).isEqualTo(1L);
            assertThat(timerFor("COMPLETED")).isNull();
        }
    }

    @Nested
    @DisplayName("IoOutcome - the tri-state coarse result the programs actually branch on")
    class IoOutcomeVocabulary {

        @Test
        @DisplayName("declares exactly the three coarse outcomes")
        void declaresThreeOutcomes() {
            assertThat(IoOutcome.values())
                    .containsExactly(IoOutcome.OK, IoOutcome.END_OF_FILE, IoOutcome.ERROR);
        }

        @ParameterizedTest(name = "{0} carries coarse result {1}")
        @CsvSource({"OK, 0", "END_OF_FILE, 16", "ERROR, 12"})
        @DisplayName("maps each outcome to its legacy coarse result value")
        void mapsOutcomesToCoarseResults(final IoOutcome outcome, final int expected) {
            assertThat(outcome.applResult()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "coarse result {0} resolves to {1}")
        @CsvSource({"0, OK", "16, END_OF_FILE", "12, ERROR"})
        @DisplayName("resolves a coarse result back to its outcome")
        void resolvesCoarseResultsBack(final int applResult, final IoOutcome expected) {
            assertThat(IoOutcome.fromApplResult(applResult)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "an unmapped coarse result of {0} is treated as an error")
        @ValueSource(ints = {8, 4, -1, 99})
        @DisplayName("treats any unmapped coarse result as an error rather than as success")
        void treatsUnmappedResultsAsErrors(final int applResult) {
            assertThat(IoOutcome.fromApplResult(applResult)).isEqualTo(IoOutcome.ERROR);
        }
    }

    @Nested
    @DisplayName("IoOperation - end of file is normal for a read and an error for everything else")
    class IoOperationVocabulary {

        @Test
        @DisplayName("declares the four operations the skeleton performs")
        void declaresFourOperations() {
            assertThat(IoOperation.values()).containsExactly(IoOperation.OPEN, IoOperation.READ,
                    IoOperation.WRITE, IoOperation.CLOSE);
        }

        @ParameterizedTest(name = "{0} reports the gerund [{1}]")
        @CsvSource({
            "OPEN,  OPENING",
            "READ,  READING",
            "WRITE, WRITING TO",
            "CLOSE, CLOSING",
        })
        @DisplayName("carries the legacy diagnostic wording for each operation")
        void carriesTheLegacyGerund(final IoOperation operation, final String expected) {
            assertThat(operation.legacyGerund()).isEqualTo(expected);
        }

        @Test
        @DisplayName("treats end of file as normal only for a read")
        void treatsEndOfFileAsNormalOnlyForARead() {
            assertThat(IoOperation.READ.endOfFileTerminatesNormally()).isTrue();
            assertThat(IoOperation.OPEN.endOfFileTerminatesNormally()).isFalse();
            assertThat(IoOperation.WRITE.endOfFileTerminatesNormally()).isFalse();
            assertThat(IoOperation.CLOSE.endOfFileTerminatesNormally()).isFalse();
        }

        @ParameterizedTest(name = "{0} arms the coarse result {1} before the operation runs")
        @CsvSource({"OPEN, 8", "READ, 12", "WRITE, 8", "CLOSE, 8"})
        @DisplayName("arms the coarse result the legacy code presets before each operation")
        void armsTheLegacyPresetResult(final IoOperation operation, final int expected) {
            assertThat(operation.armedApplResult()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("IoResult - a raw status paired with whatever the operation delivered")
    class IoResultRecord {

        @Test
        @DisplayName("pairs a status with a record")
        void pairsAStatusWithARecord() {
            final IoResult<String> result = IoResult.of("00", "payload");

            assertThat(result.rawStatus()).isEqualTo("00");
            assertThat(result.record()).isEqualTo("payload");
        }

        @Test
        @DisplayName("reports end of file with the legacy status and no record")
        void reportsEndOfFileWithNoRecord() {
            final IoResult<String> result = IoResult.endOfFile();

            assertThat(result.rawStatus()).isEqualTo(FileStatus.END_OF_FILE.getCode());
            assertThat(result.record()).isNull();
        }

        @Test
        @DisplayName("refuses a null raw status, because the status is the whole point")
        void refusesANullRawStatus() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new IoResult<String>(null, "payload"))
                    .withMessageContaining("rawStatus");
        }

        @Test
        @DisplayName("refuses a null record on the delivering factory")
        void refusesANullRecordOnTheDeliveringFactory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> IoResult.of("00", null))
                    .withMessageContaining("record");
        }
    }

    @Nested
    @DisplayName("ExecutionSummary - what one program lifecycle reports")
    class ExecutionSummaryRecord {

        @Test
        @DisplayName("keeps the program name, the record count, and both timestamps")
        void keepsItsComponents() {
            final ExecutionSummary summary =
                    new ExecutionSummary(PROGRAM_NAME, 300L, "started", "completed");

            assertThat(summary.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(summary.recordsRead()).isEqualTo(300L);
            assertThat(summary.startedAt()).isEqualTo("started");
            assertThat(summary.completedAt()).isEqualTo("completed");
        }

        @Test
        @DisplayName("accepts a zero record count, which an empty input produces")
        void acceptsAZeroRecordCount() {
            assertThatCode(() -> new ExecutionSummary(PROGRAM_NAME, 0L, "a", "b"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("refuses a negative record count")
        void refusesANegativeRecordCount() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ExecutionSummary(PROGRAM_NAME, -1L, "a", "b"))
                    .withMessageContaining("recordsRead must not be negative");
        }

        @Test
        @DisplayName("refuses a null program name")
        void refusesANullProgramName() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ExecutionSummary(null, 0L, "a", "b"))
                    .withMessageContaining("programName");
        }

        @Test
        @DisplayName("refuses a null start timestamp")
        void refusesANullStartTimestamp() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ExecutionSummary(PROGRAM_NAME, 0L, null, "b"))
                    .withMessageContaining("startedAt");
        }

        @Test
        @DisplayName("refuses a null completion timestamp")
        void refusesANullCompletionTimestamp() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ExecutionSummary(PROGRAM_NAME, 0L, "a", null))
                    .withMessageContaining("completedAt");
        }
    }

    @Nested
    @DisplayName("normaliseStatus - the raw two-byte code becomes a coarse outcome")
    class NormaliseStatus {

        @Test
        @DisplayName("treats only the success code as success")
        void treatsOnlyTheSuccessCodeAsSuccess() {
            assertThat(step().normalise(IoOperation.READ, FileStatus.SUCCESS.getCode()))
                    .isEqualTo(IoOutcome.OK);
        }

        @Test
        @DisplayName("treats end of file as a normal read outcome")
        void treatsEndOfFileAsANormalReadOutcome() {
            assertThat(step().normalise(IoOperation.READ, FileStatus.END_OF_FILE.getCode()))
                    .isEqualTo(IoOutcome.END_OF_FILE);
        }

        @ParameterizedTest(name = "end of file is an error when {0}s")
        @CsvSource({"OPEN", "WRITE", "CLOSE"})
        @DisplayName("treats end of file as an error for every operation other than a read")
        void treatsEndOfFileAsAnErrorElsewhere(final IoOperation operation) {
            assertThat(step().normalise(operation, FileStatus.END_OF_FILE.getCode()))
                    .as("only a read loop may terminate on end of file")
                    .isEqualTo(IoOutcome.ERROR);
        }

        @ParameterizedTest(name = "status [{0}] is an error")
        @ValueSource(strings = {"01", "02", "04", "05", "12", "22", "23", "31", "35"})
        @DisplayName("treats every other recognised status as an error, including the qualified ones")
        void treatsEveryOtherRecognisedStatusAsAnError(final String rawStatus) {
            assertThat(step().normalise(IoOperation.READ, rawStatus)).isEqualTo(IoOutcome.ERROR);
        }

        @ParameterizedTest(name = "unrecognised status [{0}] is an error")
        @ValueSource(strings = {"99", "ZZ", "", "0"})
        @DisplayName("treats a status outside the vocabulary as an error rather than as success")
        void treatsAnUnrecognisedStatusAsAnError(final String rawStatus) {
            assertThat(step().normalise(IoOperation.READ, rawStatus)).isEqualTo(IoOutcome.ERROR);
        }

        @Test
        @DisplayName("treats an absent status as an error")
        void treatsAnAbsentStatusAsAnError() {
            assertThat(step().normalise(IoOperation.READ, null)).isEqualTo(IoOutcome.ERROR);
        }

        @Test
        @DisplayName("refuses a null operation, because the end-of-file rule depends on it")
        void refusesANullOperation() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step().normalise(null, "00"))
                    .withMessageContaining("operation");
        }
    }

    @Nested
    @DisplayName("The protected input-output helpers")
    class IoHelpers {

        @Test
        @DisplayName("a successful read delivers the record")
        void aSuccessfulReadDeliversTheRecord() {
            assertThat(step().readVia(RESOURCE, () -> IoResult.of("00", "payload")))
                    .contains("payload");
        }

        @Test
        @DisplayName("a read reporting end of file delivers nothing, ending the loop normally")
        void aReadReportingEndOfFileDeliversNothing() {
            assertThat(step().readVia(RESOURCE, IoResult::endOfFile)).isEmpty();
        }

        @Test
        @DisplayName("a read reporting an error status abends with the status in the reason")
        void aReadReportingAnErrorStatusAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().readVia(RESOURCE, () -> IoResult.of("23", "payload")))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM_NAME);
                        assertThat(abend.reason()).contains("23").contains(RESOURCE);
                        assertThat(abend.getMessage()).contains(RESOURCE);
                    });
        }

        @Test
        @DisplayName("a read whose action throws abends and keeps the cause")
        void aReadWhoseActionThrowsAbends() {
            final Exception cause = new IllegalStateException("device offline");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().<String>readVia(RESOURCE, () -> {
                        throw cause;
                    }))
                    .satisfies(abend -> assertThat(abend.getCause()).isSameAs(cause));
        }

        @Test
        @DisplayName("an already-diagnosed abend from an action passes through unchanged")
        void anAlreadyDiagnosedAbendPassesThrough() {
            final AbendException diagnosed =
                    new AbendException(PROGRAM_NAME, "ALREADY DIAGNOSED");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().<String>readVia(RESOURCE, () -> {
                        throw diagnosed;
                    }))
                    .isSameAs(diagnosed);
        }

        @Test
        @DisplayName("a read reporting success without a record is refused")
        void aReadReportingSuccessWithoutARecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step().readVia(RESOURCE, () -> new IoResult<String>("00", null)))
                    .withMessageContaining("without delivering a record");
        }

        @Test
        @DisplayName("a read whose action reports no result at all is refused, naming the resource")
        void aReadReportingNoResultAtAllIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step().readVia(RESOURCE, () -> null))
                    .withMessageContaining("read of " + RESOURCE)
                    .withMessageContaining("reported no result");
        }

        @Test
        @DisplayName("a successful open, write, and close each complete quietly")
        void successfulTwoWayOperationsCompleteQuietly() {
            final RecordingStep step = step();

            assertThatCode(() -> {
                step.openVia(RESOURCE, () -> "00");
                step.writeVia(RESOURCE, () -> "00");
                step.closeVia(RESOURCE, () -> "00");
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a failing open abends reporting the opening gerund")
        void aFailingOpenAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().openVia(RESOURCE, () -> "35"))
                    .satisfies(abend -> assertThat(abend.reason())
                            .contains(IoOperation.OPEN.legacyGerund()).contains("35"));
        }

        @Test
        @DisplayName("a failing write abends reporting the writing gerund")
        void aFailingWriteAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().writeVia(RESOURCE, () -> "23"))
                    .satisfies(abend -> assertThat(abend.reason())
                            .contains(IoOperation.WRITE.legacyGerund()));
        }

        @Test
        @DisplayName("a failing close abends reporting the closing gerund")
        void aFailingCloseAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().closeVia(RESOURCE, () -> "31"))
                    .satisfies(abend -> assertThat(abend.reason())
                            .contains(IoOperation.CLOSE.legacyGerund()));
        }

        @Test
        @DisplayName("an unconditional abend reports the operation and resource")
        void anUnconditionalAbendReportsTheContext() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().abend(IoOperation.READ, RESOURCE, "23"))
                    .satisfies(abend -> assertThat(abend.reason())
                            .contains("23").contains(RESOURCE));
        }

        @Test
        @DisplayName("reports an absent status as a marker rather than as the word null")
        void reportsAnAbsentStatusAsAMarker() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().abend(IoOperation.READ, RESOURCE, null))
                    .satisfies(abend -> assertThat(abend.reason())
                            .contains("(none)")
                            .doesNotContain("null"));
        }

        @Test
        @DisplayName("bounds the abend reason and message to their legacy field widths")
        void boundsTheAbendTextToLegacyWidths() {
            final String longResource = "R".repeat(120);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step().abend(IoOperation.READ, longResource, "23"))
                    .satisfies(abend -> {
                        assertThat(abend.reason()).hasSize(AbendException.REASON_LENGTH);
                        assertThat(abend.getMessage()).hasSize(AbendException.MESSAGE_LENGTH);
                    });
        }

        @Test
        @DisplayName("refuses a null resource name")
        void refusesANullResource() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step().openVia(null, () -> "00"))
                    .withMessageContaining("resource");
        }

        @ParameterizedTest(name = "refuses a resource name of [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("refuses a blank resource name")
        void refusesABlankResource(final String resource) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> step().openVia(resource, () -> "00"))
                    .withMessageContaining("must name the file the operation acts on");
        }

        @Test
        @DisplayName("refuses a null action")
        void refusesANullAction() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step().openVia(RESOURCE, null))
                    .withMessageContaining("action");
        }
    }

    @Nested
    @DisplayName("formatBatchTimestamp - the 26-byte legacy timestamp image")
    class BatchTimestamp {

        @Test
        @DisplayName("renders the legacy layout at exactly the declared width")
        void rendersTheLegacyLayout() {
            final String image = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 1, 15, 10, 20, 30, 456_000_000));

            assertThat(image).isEqualTo("2024-01-15-10.20.30.450000").hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("truncates sub-hundredth precision rather than rounding it")
        void truncatesSubHundredthPrecision() {
            final String image = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 1, 15, 10, 20, 30, 999_000_000));

            assertThat(image)
                    .as("the legacy field holds hundredths, and the estate never rounds")
                    .isEqualTo("2024-01-15-10.20.30.990000");
        }

        @Test
        @DisplayName("zero-pads every component to its legacy width")
        void zeroPadsEveryComponent() {
            final String image = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(7, 2, 3, 4, 5, 6, 0));

            assertThat(image).isEqualTo("0007-02-03-04.05.06.000000").hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("derives the step's timestamp from the injected clock alone")
        void derivesTheTimestampFromTheInjectedClock() {
            assertThat(step().timestamp())
                    .isEqualTo(AbstractCobolStep.formatBatchTimestamp(
                            LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("honours the injected zone, so a non-UTC clock is visibly different")
        void honoursTheInjectedZone() {
            final RecordingStep tokyo = new RecordingStep(PROGRAM_NAME, meterRegistry,
                    Clock.fixed(FIXED_INSTANT, ZoneId.of("Asia/Tokyo")));

            assertThat(tokyo.timestamp())
                    .as("the clock is the single source of both instant and zone")
                    .isNotEqualTo(step().timestamp());
        }

        @Test
        @DisplayName("refuses a moment whose year cannot be held in the legacy four-byte field")
        void refusesAnUnrepresentableYear() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(
                            LocalDateTime.of(10_000, 1, 1, 0, 0)))
                    .withMessageContaining("cannot be held in the legacy four-byte year field");
        }

        @Test
        @DisplayName("refuses an absent moment")
        void refusesAnAbsentMoment() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(null))
                    .withMessageContaining("moment");
        }

        @Test
        @DisplayName("publishes the legacy width as a constant")
        void publishesTheLegacyWidth() {
            assertThat(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH).isEqualTo(TIMESTAMP_WIDTH);
        }
    }
}
