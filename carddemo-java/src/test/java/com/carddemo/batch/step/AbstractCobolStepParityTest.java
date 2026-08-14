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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.repeat.RepeatStatus;

import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verifies {@link AbstractCobolStep}, the template that stands in for the open, read-loop,
 * status-check, close and abend skeleton shared by all ten legacy batch programs.
 *
 * <p><strong>What the legacy authority is.</strong> Every batch program in the estate repeats one
 * shape. {@code app/cbl/CBACT01C.cbl} is the clearest specimen and is the authority this class
 * asserts against. Four elements of that shape are load bearing:
 *
 * <ul>
 *   <li><strong>A two-level status model.</strong> The program declares
 *       {@code FILE STATUS IS ACCTFILE-STATUS} over a split two-byte field, then normalises that
 *       raw code into a coarser variable before branching: {@code '00'} becomes {@code APPL-RESULT}
 *       zero, {@code '10'} becomes sixteen, and anything else becomes twelve, after which the code
 *       tests the level-88 names {@code APPL-AOK} and {@code APPL-EOF} rather than the raw code
 *       ({@code app/cbl/CBACT01C.cbl} lines 90-114). Collapsing the two levels into one would erase
 *       the end-of-file versus error distinction every read loop depends on, so this class asserts
 *       both levels.</li>
 *   <li><strong>End of file is normal for a read and abnormal for anything else.</strong> A
 *       {@code '10'} on an open, a write or a close is not a graceful ending; it is an error.</li>
 *   <li><strong>The armed value differs by operation.</strong> The programs pre-load
 *       {@code APPL-RESULT} with eight before an open, a write or a close and with twelve before a
 *       read, so that a failure that never reaches the status test still reports a plausible
 *       result.</li>
 *   <li><strong>The terminal path displays and then abends.</strong> The program emits the raw
 *       status, announces the abend, and calls {@code CEE3ABD} with the batch abend code — it never
 *       returns a failure to its caller.</li>
 * </ul>
 *
 * <p><strong>How the template is driven.</strong> The four abstract hooks are supplied by
 * {@link RecordingStep}, a concrete subclass declared inside this file. That subclass records the
 * order in which the template calls it, which is how lifecycle ordering is asserted without
 * reaching into private state. Because the template's nested types and helper methods are
 * {@code protected}, this test lives in the template's own package.
 */
@DisplayName("AbstractCobolStep — the shared batch program skeleton")
class AbstractCobolStepParityTest {

    /** A legacy program name, within the eight-character {@code ABEND-CULPRIT} width. */
    private static final String PROGRAM = "CBACT01C";

    /** A DD name standing in for the file an operation acts on. */
    private static final String RESOURCE = "ACCTFILE";

    /** {@code APPL-RESULT} zero: the {@code APPL-AOK} state. */
    private static final int APPL_AOK = 0;

    /** {@code APPL-RESULT} eight: armed before an open, a write or a close. */
    private static final int APPL_PENDING = 8;

    /** {@code APPL-RESULT} twelve: the terminal error state, and the value armed before a read. */
    private static final int APPL_ERROR = 12;

    /** {@code APPL-RESULT} sixteen: the {@code APPL-EOF} state. */
    private static final int APPL_EOF = 16;

    /** The width of the twenty-six byte batch timestamp the programs build by hand. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** The timer the template publishes for every lifecycle. */
    private static final String METRIC = "carddemo.batch.cobol.step";

    /** A clock frozen at a moment whose components are all distinguishable from one another. */
    private static final Clock FROZEN =
            Clock.fixed(Instant.parse("2024-03-09T04:05:06.078900Z"), ZoneOffset.UTC);

    // TEST DOUBLES

    /**
     * A concrete step that records how the template drove it.
     *
     * <p>Written by hand rather than mocked. The module compiles with {@code -Xlint:all -Werror},
     * and a hand-written double keeps the generic {@code readNextRecord} contract explicit instead of
     * routing it through a mocking framework's raw types.
     */
    private static final class RecordingStep extends AbstractCobolStep<String> {

        /** The lifecycle callbacks the template made, in order. */
        private final List<String> calls = new ArrayList<>();

        /** The records {@code readNextRecord} will hand back before reporting end of file. */
        private final List<String> pending = new ArrayList<>();

        /** A failure to raise from {@code openResources}, or {@code null} for none. */
        private RuntimeException failOnOpen;

        /** A failure to raise from {@code processRecord}, or {@code null} for none. */
        private RuntimeException failOnProcess;

        /** A failure to raise from {@code closeResources}, or {@code null} for none. */
        private RuntimeException failOnClose;

        /** A failure to raise from {@code releaseResources}, or {@code null} for none. */
        private RuntimeException failOnRelease;

        /** When true, {@code readNextRecord} returns {@code null} rather than an {@link Optional}. */
        private boolean returnNullFromRead;

        /** How many times {@code closeResources} has been entered. */
        private int closeAttempts;

        /** How many times {@code releaseResources} has been entered. */
        private int releaseAttempts;

        RecordingStep(final MeterRegistry registry) {
            super(PROGRAM, registry, FROZEN);
        }

        RecordingStep(final String programName, final MeterRegistry registry, final Clock clock) {
            super(programName, registry, clock);
        }

        RecordingStep withRecords(final String... records) {
            this.pending.addAll(List.of(records));
            return this;
        }

        @Override
        protected void openResources() {
            this.calls.add("open");
            if (this.failOnOpen != null) {
                throw this.failOnOpen;
            }
        }

        @Override
        protected Optional<String> readNextRecord() {
            this.calls.add("read");
            if (this.returnNullFromRead) {
                return null;
            }
            if (this.pending.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(this.pending.remove(0));
        }

        @Override
        protected void processRecord(final String record) {
            this.calls.add("process:" + record);
            if (this.failOnProcess != null) {
                throw this.failOnProcess;
            }
        }

        @Override
        protected void closeResources() {
            this.closeAttempts++;
            this.calls.add("close");
            if (this.failOnClose != null) {
                throw this.failOnClose;
            }
        }

        /**
         * Records the failure-path handle release.
         *
         * <p>Recorded under its own label rather than folded in with {@code "close"}, because the
         * legacy distinction between the two is the whole point: {@code 9000-ACCTFILE-CLOSE} is an
         * observable {@code CLOSE} paragraph with a normalised status and a diagnostic, whereas this
         * has no legacy antecedent and produces nothing an operator could observe.</p>
         */
        @Override
        protected void releaseResources() {
            this.releaseAttempts++;
            this.calls.add("release");
            if (this.failOnRelease != null) {
                throw this.failOnRelease;
            }
        }

        /**
         * Exposes the protected status classifier so the two-level model can be asserted directly.
         *
         * @param operation the operation whose status is being classified
         * @param rawStatus the raw two-byte code, possibly {@code null}
         * @return the coarse outcome
         */
        IoOutcome classify(final IoOperation operation, final String rawStatus) {
            return normaliseStatus(operation, rawStatus);
        }

        /**
         * Exposes the protected open helper.
         *
         * @param resource the file name
         * @param status   the status the underlying action reports
         */
        void doOpen(final String resource, final String status) {
            openResource(resource, () -> status);
        }

        /**
         * Exposes the protected write helper.
         *
         * @param resource the file name
         * @param status   the status the underlying action reports
         */
        void doWrite(final String resource, final String status) {
            writeRecord(resource, () -> status);
        }

        /**
         * Exposes the protected close helper.
         *
         * @param resource the file name
         * @param status   the status the underlying action reports
         */
        void doClose(final String resource, final String status) {
            closeResource(resource, () -> status);
        }

        /**
         * Exposes the protected open helper with an action that throws.
         *
         * @param resource the file name
         * @param cause    the exception the underlying action raises
         */
        void doOpenThrowing(final String resource, final Exception cause) {
            openResource(resource, () -> {
                throw cause;
            });
        }

        /**
         * Exposes the protected read helper.
         *
         * @param resource the file name
         * @param result   the outcome the underlying action reports
         * @return the record, or empty at end of file
         */
        Optional<String> doRead(final String resource, final IoResult<String> result) {
            return readRecord(resource, () -> result);
        }

        /**
         * Exposes the protected read helper with an action that reports nothing at all.
         *
         * @param resource the file name
         * @return never returns normally
         */
        Optional<String> doReadReportingNothing(final String resource) {
            return readRecord(resource, () -> null);
        }

        /**
         * Exposes the protected direct-abend helper.
         *
         * @param operation the operation that failed
         * @param resource  the file name
         * @param rawStatus the raw status, possibly {@code null}
         */
        void doAbend(final IoOperation operation, final String resource, final String rawStatus) {
            abendOnIoFailure(operation, resource, rawStatus);
        }

        /**
         * Exposes the protected program-name accessor.
         *
         * @return the legacy program name
         */
        String name() {
            return programName();
        }

        /**
         * Exposes the protected clock-driven timestamp builder.
         *
         * @return the twenty-six character timestamp image
         */
        String timestamp() {
            return currentBatchTimestamp();
        }
    }

    // HELPERS

    /**
     * Reads the count recorded against one outcome of the lifecycle timer.
     *
     * @param registry the registry the step published to
     * @param outcome  the outcome tag value
     * @return the number of lifecycles recorded, or zero when the timer was never registered
     */
    private static long timerCount(final MeterRegistry registry, final String outcome) {
        final Timer timer = registry.find(METRIC).tag("step", PROGRAM).tag("outcome", outcome).timer();
        return (timer == null) ? 0L : timer.count();
    }

    // LIFECYCLE

    /**
     * Verifies the order in which the template drives its four hooks, which is the order the legacy
     * mainline paragraph performs its own: open, then read until end of file processing each record,
     * then close.
     */
    @Nested
    @DisplayName("The mainline lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("open runs first, then a read and a process per record, then one close")
        void theHooksRunInMainlineOrder() {
            final RecordingStep step =
                    new RecordingStep(new SimpleMeterRegistry()).withRecords("A", "B");

            step.run();

            assertThat(step.calls).containsExactly(
                    "open", "read", "process:A", "read", "process:B", "read", "close");
        }

        @Test
        @DisplayName("an empty file still opens, reads once and closes")
        void anEmptyFileIsStillOpenedAndClosed() {
            final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());

            final AbstractCobolStep.ExecutionSummary summary = step.run();

            assertThat(step.calls).containsExactly("open", "read", "close");
            assertThat(summary.recordsRead()).isZero();
        }

        @Test
        @DisplayName("the summary reports the program, the record count and both timestamps")
        void theSummaryReportsTheRun() {
            final RecordingStep step =
                    new RecordingStep(new SimpleMeterRegistry()).withRecords("A", "B", "C");

            final AbstractCobolStep.ExecutionSummary summary = step.run();

            assertThat(summary.programName()).isEqualTo(PROGRAM);
            assertThat(summary.recordsRead()).isEqualTo(3L);
            assertThat(summary.startedAt()).hasSize(TIMESTAMP_WIDTH);
            assertThat(summary.completedAt()).hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the tasklet entry point runs the same lifecycle and finishes in one chunk")
        void theTaskletEntryPointDelegates() throws Exception {
            final RecordingStep step = new RecordingStep(new SimpleMeterRegistry()).withRecords("A");

            final RepeatStatus status = step.execute(null, null);

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(step.calls).containsExactly("open", "read", "process:A", "read", "close");
        }

        @Test
        @DisplayName("a hook that reports no Optional at all is a programming error, not end of file")
        void aNullReadIsRefused() {
            final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());
            step.returnNullFromRead = true;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(step::run)
                    .withMessage("readNextRecord must report an Optional, never null");
        }

        @Test
        @DisplayName("records are counted, not the reads that discover the end of the file")
        void theRecordCountExcludesTheEndOfFileRead() {
            final RecordingStep step =
                    new RecordingStep(new SimpleMeterRegistry()).withRecords("only");

            assertThat(step.run().recordsRead()).isEqualTo(1L);
            assertThat(step.calls).filteredOn("read"::equals).hasSize(2);
        }
    }

    // FAILURE HANDLING

    /**
     * Verifies the release-and-rethrow behaviour around a failure.
     *
     * <p>The legacy programs close what they opened before abending, but they do not attempt a close
     * that has already been attempted. The template reproduces both halves: a failure raised before
     * the close sequence triggers a release, and a failure raised by the close sequence itself does
     * not trigger a second one.
     */
    @Nested
    @DisplayName("Failure handling")
    class FailureHandling {

        @Test
        @DisplayName("a failure while opening still releases resources and then rethrows")
        void anOpenFailureReleasesResources() {
            final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());
            step.failOnOpen = new IllegalStateException("VSAM unavailable");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .withMessage("VSAM unavailable");

            // The parity point is that the observable close family is skipped, not driven. The abend
            // routine ends with the CEE3ABD call [app/cbl/CBACT01C.cbl:L173], which does not return, so
            // 9000-ACCTFILE-CLOSE at line 83 is never reached once a guarded operation abends.
            // What the template does instead is the non-observable handle release, which exists only
            // because this JVM outlives the failed step where the mainframe enclave did not.
            assertThat(step.calls).containsExactly("open", "release");
            assertThat(step.releaseAttempts)
                    .as("the release runs exactly once, so nothing the step opened is leaked")
                    .isEqualTo(1);
            assertThat(step.closeAttempts)
                    .as("an abend is terminal, so no CLOSE paragraph is performed")
                    .isZero();
        }

        @Test
        @DisplayName("a failure while processing releases resources after the records already read")
        void aProcessFailureReleasesResources() {
            final RecordingStep step =
                    new RecordingStep(new SimpleMeterRegistry()).withRecords("A");
            step.failOnProcess = new AbendException(PROGRAM, "POSTING FAILED");

            assertThatExceptionOfType(AbendException.class).isThrownBy(step::run);
            assertThat(step.calls).containsExactly("open", "read", "process:A", "release");
            assertThat(step.closeAttempts)
                    .as("a failure inside the read loop is as terminal as one in the open")
                    .isZero();
        }

        @Test
        @DisplayName("a failure raised by the close sequence is not answered with a second close")
        void aCloseFailureIsNotRetried() {
            final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());
            step.failOnClose = new IllegalStateException("close failed");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .withMessage("close failed");
            assertThat(step.closeAttempts).isEqualTo(1);
            assertThat(step.releaseAttempts)
                    .as("the release that follows any failure does not re-enter the close family")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a secondary failure while releasing is retained on the primary, never thrown")
        void aSecondaryFailureIsSuppressed() {
            final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());
            step.failOnOpen = new IllegalStateException("primary");
            step.failOnRelease = new IllegalStateException("secondary");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .withMessage("primary")
                    .satisfies(thrown -> {
                        assertThat(thrown.getSuppressed()).hasSize(1);
                        assertThat(thrown.getSuppressed()[0]).hasMessage("secondary");
                    });
        }

        @Test
        @DisplayName("no summary is produced for a run that abended")
        void anAbendedRunProducesNoSummary() {
            final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());
            step.failOnOpen = new IllegalStateException("no summary");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);
        }
    }

    // THE TWO-LEVEL STATUS MODEL

    /**
     * Verifies the normalisation described at lines 90-114 of {@code app/cbl/CBACT01C.cbl}.
     *
     * <p>The rule is deliberately narrow. Only {@code '00'} normalises to the all-clear and only
     * {@code '10'} to end of file — and the latter only for a read. Every other code in the raw
     * vocabulary, including the qualified successes {@code '01'}, {@code '02'}, {@code '04'} and
     * {@code '05'} and the qualified at-end {@code '12'}, normalises to the terminal error state,
     * because the legacy catch-all arm normalises to 12 and there is no other arm. Widening the
     * all-clear to cover the qualified successes would be a plausible improvement and a behavioural
     * change, so this class pins the narrow rule.
     */
    @Nested
    @DisplayName("Raw status to APPL-RESULT normalisation")
    class StatusNormalisation {

        private final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());

        @Test
        @DisplayName("only '00' is the all-clear, for every operation")
        void successIsExactlyZeroZero() {
            for (final AbstractCobolStep.IoOperation operation
                    : AbstractCobolStep.IoOperation.values()) {
                assertThat(step.classify(operation, FileStatus.SUCCESS.getCode()))
                        .as("%s with status 00", operation)
                        .isEqualTo(AbstractCobolStep.IoOutcome.OK);
            }
        }

        @Test
        @DisplayName("'10' is end of file for a read")
        void endOfFileIsNormalForARead() {
            assertThat(step.classify(AbstractCobolStep.IoOperation.READ,
                    FileStatus.END_OF_FILE.getCode()))
                    .isEqualTo(AbstractCobolStep.IoOutcome.END_OF_FILE);
        }

        @Test
        @DisplayName("'10' is an error for an open, a write or a close")
        void endOfFileIsAnErrorForEveryOtherOperation() {
            for (final AbstractCobolStep.IoOperation operation : new AbstractCobolStep.IoOperation[] {
                AbstractCobolStep.IoOperation.OPEN,
                AbstractCobolStep.IoOperation.WRITE,
                AbstractCobolStep.IoOperation.CLOSE}) {
                assertThat(step.classify(operation, FileStatus.END_OF_FILE.getCode()))
                        .as("%s with status 10", operation)
                        .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            }
        }

        @Test
        @DisplayName("the qualified successes are errors, because the legacy ELSE has no other arm")
        void qualifiedSuccessesAreErrors() {
            for (final FileStatus status : new FileStatus[] {FileStatus.SUCCESS_QUALIFIED,
                FileStatus.DUPLICATE_ALTERNATE_KEY, FileStatus.RECORD_LENGTH_MISMATCH,
                FileStatus.OPTIONAL_FILE_CREATED}) {
                assertThat(step.classify(AbstractCobolStep.IoOperation.READ, status.getCode()))
                        .as("status %s", status.getCode())
                        .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            }
        }

        @Test
        @DisplayName("the qualified at-end code is an error, not a graceful ending")
        void theQualifiedAtEndCodeIsAnError() {
            assertThat(step.classify(AbstractCobolStep.IoOperation.READ,
                    FileStatus.AT_END_QUALIFIED.getCode()))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("every remaining declared status is an error")
        void theRemainingDeclaredStatusesAreErrors() {
            for (final FileStatus status : new FileStatus[] {FileStatus.DUPLICATE_KEY,
                FileStatus.RECORD_NOT_FOUND, FileStatus.PERMANENT_ERROR, FileStatus.FILE_NOT_FOUND}) {
                assertThat(step.classify(AbstractCobolStep.IoOperation.READ, status.getCode()))
                        .as("status %s", status.getCode())
                        .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            }
        }

        @Test
        @DisplayName("a status outside the declared vocabulary is an error, not a guess")
        void anUnknownStatusIsAnError() {
            assertThat(step.classify(AbstractCobolStep.IoOperation.READ, "99"))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(step.classify(AbstractCobolStep.IoOperation.READ, ""))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("an absent status is an error")
        void anAbsentStatusIsAnError() {
            assertThat(step.classify(AbstractCobolStep.IoOperation.READ, null))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("the classifier refuses an absent operation")
        void theClassifierRefusesAnAbsentOperation() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.classify(null, "00"))
                    .withMessage("operation");
        }
    }

    // THE APPL-RESULT VOCABULARY

    /**
     * Verifies the two protected enumerations against the legacy values they stand for.
     *
     * <p>The numbers matter because the programs move them into a display field and print them, so
     * an operator reads them off a job log. The gerunds matter for the same reason: the diagnostic
     * text the programs emit reads {@code ERROR OPENING ACCTFILE}, and {@code WRITE} contributes
     * {@code WRITING TO} rather than {@code WRITING} so the sentence still parses.
     */
    @Nested
    @DisplayName("The APPL-RESULT and operation vocabularies")
    class Vocabularies {

        @Test
        @DisplayName("the three outcomes carry the legacy APPL-RESULT values")
        void theOutcomeValuesMatchTheLegacy() {
            assertThat(AbstractCobolStep.IoOutcome.OK.applResult()).isEqualTo(APPL_AOK);
            assertThat(AbstractCobolStep.IoOutcome.END_OF_FILE.applResult()).isEqualTo(APPL_EOF);
            assertThat(AbstractCobolStep.IoOutcome.ERROR.applResult()).isEqualTo(APPL_ERROR);
        }

        @Test
        @DisplayName("the outcome vocabulary has exactly three members")
        void thereAreExactlyThreeOutcomes() {
            assertThat(AbstractCobolStep.IoOutcome.values()).containsExactly(
                    AbstractCobolStep.IoOutcome.OK,
                    AbstractCobolStep.IoOutcome.END_OF_FILE,
                    AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("each operation carries the gerund the diagnostic text needs")
        void theGerundsMatchTheLegacyDiagnostics() {
            assertThat(AbstractCobolStep.IoOperation.OPEN.legacyGerund()).isEqualTo("OPENING");
            assertThat(AbstractCobolStep.IoOperation.READ.legacyGerund()).isEqualTo("READING");
            assertThat(AbstractCobolStep.IoOperation.WRITE.legacyGerund()).isEqualTo("WRITING TO");
            assertThat(AbstractCobolStep.IoOperation.CLOSE.legacyGerund()).isEqualTo("CLOSING");
        }

        @Test
        @DisplayName("a read arms twelve and the other three arm eight")
        void theArmedValuesDifferByOperation() {
            assertThat(AbstractCobolStep.IoOperation.READ.armedApplResult()).isEqualTo(APPL_ERROR);
            assertThat(AbstractCobolStep.IoOperation.OPEN.armedApplResult()).isEqualTo(APPL_PENDING);
            assertThat(AbstractCobolStep.IoOperation.WRITE.armedApplResult()).isEqualTo(APPL_PENDING);
            assertThat(AbstractCobolStep.IoOperation.CLOSE.armedApplResult()).isEqualTo(APPL_PENDING);
        }

        @Test
        @DisplayName("only a read treats end of file as a normal ending")
        void onlyAReadEndsNormallyAtEndOfFile() {
            assertThat(AbstractCobolStep.IoOperation.READ.endOfFileTerminatesNormally()).isTrue();
            assertThat(AbstractCobolStep.IoOperation.OPEN.endOfFileTerminatesNormally()).isFalse();
            assertThat(AbstractCobolStep.IoOperation.WRITE.endOfFileTerminatesNormally()).isFalse();
            assertThat(AbstractCobolStep.IoOperation.CLOSE.endOfFileTerminatesNormally()).isFalse();
        }
    }

    // THE ONE-WAY HELPERS

    /**
     * Verifies the open, write and close helpers, each of which performs an action, classifies the
     * status it reports and abends on anything that is not the all-clear.
     */
    @Nested
    @DisplayName("The open, write and close helpers")
    class OneWayHelpers {

        private final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());

        @Test
        @DisplayName("an all-clear status returns quietly")
        void anAllClearStatusIsAccepted() {
            assertThatNoException().isThrownBy(() -> step.doOpen(RESOURCE, "00"));
            assertThatNoException().isThrownBy(() -> step.doWrite(RESOURCE, "00"));
            assertThatNoException().isThrownBy(() -> step.doClose(RESOURCE, "00"));
        }

        @Test
        @DisplayName("an error status abends with the gerund and the raw code in the diagnostic")
        void anErrorStatusAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.doWrite(RESOURCE, "31"))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.reason()).isEqualTo("STATUS 31 WRITING TO " + RESOURCE);
                        assertThat(abend.getMessage())
                                .isEqualTo("ERROR WRITING TO " + RESOURCE
                                        + " - FILE STATUS IS: 31");
                    });
        }

        @Test
        @DisplayName("an end-of-file status on an open abends rather than ending gracefully")
        void endOfFileOnAnOpenAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.doOpen(RESOURCE, "10"))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("STATUS 10 OPENING " + RESOURCE));
        }

        @Test
        @DisplayName("an action that throws is wrapped, and the cause is retained")
        void aThrownActionIsWrapped() {
            final Exception cause = new java.io.IOException("device error");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.doOpenThrowing(RESOURCE, cause))
                    .satisfies(abend -> {
                        assertThat(abend).hasCause(cause);
                        assertThat(abend.reason()).isEqualTo("STATUS (none) OPENING " + RESOURCE);
                        assertThat(abend.getMessage())
                                .isEqualTo("ERROR OPENING " + RESOURCE
                                        + " - FILE STATUS IS: (none)");
                    });
        }

        @Test
        @DisplayName("an already-diagnosed abend passes through instead of being wrapped twice")
        void anAbendIsNotRewrapped() {
            final AbendException original = new AbendException(PROGRAM, "ALREADY DIAGNOSED");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.doOpenThrowing(RESOURCE, original))
                    .isSameAs(original);
        }

        @Test
        @DisplayName("a reason longer than the legacy field is truncated, not rejected")
        void anOverlongReasonIsTruncated() {
            final String longName = "A".repeat(60);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.doWrite(longName, "31"))
                    .satisfies(abend -> {
                        assertThat(abend.reason()).hasSize(AbendException.REASON_LENGTH);
                        assertThat(abend.getMessage()).hasSize(AbendException.MESSAGE_LENGTH);
                    });
        }

        @Test
        @DisplayName("the helpers insist on being told which file they are acting on")
        void theResourceIsMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.doOpen(null, "00"))
                    .withMessage("resource");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> step.doOpen("   ", "00"))
                    .withMessage("resource must name the file the operation acts on");
        }
    }

    // THE READ HELPER

    /**
     * Verifies the read helper, the only one of the four that distinguishes a graceful ending from
     * an error and the only one that carries a record back.
     */
    @Nested
    @DisplayName("The read helper")
    class ReadHelper {

        private final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());

        @Test
        @DisplayName("an all-clear read hands the record back")
        void anAllClearReadDeliversTheRecord() {
            final Optional<String> record =
                    step.doRead(RESOURCE, AbstractCobolStep.IoResult.of("00", "PAYLOAD"));

            assertThat(record).contains("PAYLOAD");
        }

        @Test
        @DisplayName("an end-of-file read reports absence rather than abending")
        void anEndOfFileReadReportsAbsence() {
            assertThat(step.doRead(RESOURCE, AbstractCobolStep.IoResult.endOfFile())).isEmpty();
        }

        @Test
        @DisplayName("an error status on a read abends")
        void anErrorStatusAbends() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.doRead(RESOURCE,
                            new AbstractCobolStep.IoResult<>("23", null)))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("STATUS 23 READING " + RESOURCE));
        }

        @Test
        @DisplayName("an all-clear status with no record is a programming error, not an empty read")
        void anAllClearWithoutARecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.doRead(RESOURCE,
                            new AbstractCobolStep.IoResult<>("00", null)))
                    .withMessage("read of " + RESOURCE
                            + " reported status 00 without delivering a record");
        }

        @Test
        @DisplayName("an action that reports nothing at all is refused")
        void anActionReportingNothingIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.doReadReportingNothing(RESOURCE))
                    .withMessage("read of " + RESOURCE + " reported no result");
        }

        @Test
        @DisplayName("the read helper insists on being told which file it is reading")
        void theResourceIsMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.doRead(null, AbstractCobolStep.IoResult.endOfFile()))
                    .withMessage("resource");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> step.doRead("", AbstractCobolStep.IoResult.endOfFile()))
                    .withMessage("resource must name the file the operation acts on");
        }
    }

    // THE IO RESULT CARRIER

    /**
     * Verifies {@link AbstractCobolStep.IoResult}, the pair of a raw status and an optional record.
     *
     * <p>The two factory methods encode a distinction the legacy read loop depends on: a successful
     * read always carries a record, and an end-of-file read never does. The canonical constructor
     * remains open so a caller can report an error status with no record.
     */
    @Nested
    @DisplayName("IoResult")
    class ResultCarrier {

        @Test
        @DisplayName("a successful result carries both a status and a record")
        void aSuccessfulResultCarriesBoth() {
            final AbstractCobolStep.IoResult<String> result =
                    AbstractCobolStep.IoResult.of("00", "PAYLOAD");

            assertThat(result.rawStatus()).isEqualTo("00");
            assertThat(result.record()).isEqualTo("PAYLOAD");
        }

        @Test
        @DisplayName("the end-of-file result carries the legacy code and no record")
        void theEndOfFileResultCarriesNoRecord() {
            final AbstractCobolStep.IoResult<String> result = AbstractCobolStep.IoResult.endOfFile();

            assertThat(result.rawStatus()).isEqualTo(FileStatus.END_OF_FILE.getCode());
            assertThat(result.record()).isNull();
        }

        @Test
        @DisplayName("a status is always required")
        void theStatusIsMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.IoResult<>(null, "PAYLOAD"))
                    .withMessage("rawStatus");
        }

        @Test
        @DisplayName("the success factory refuses a missing record")
        void theSuccessFactoryRefusesAMissingRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AbstractCobolStep.IoResult.of("00", null))
                    .withMessage("record");
        }

        @Test
        @DisplayName("the canonical constructor still allows an error status with no record")
        void anErrorStatusMayCarryNoRecord() {
            assertThat(new AbstractCobolStep.IoResult<String>("23", null).record()).isNull();
        }
    }

    // THE DIRECT ABEND HELPER

    /**
     * Verifies the helper a concrete step calls when it has diagnosed a failure itself.
     *
     * <p>It always abends: the legacy terminal path never returns a failure to its caller, so there
     * is no arm of this method that reports rather than raises.
     */
    @Nested
    @DisplayName("The direct abend helper")
    class DirectAbend {

        private final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());

        @Test
        @DisplayName("the helper raises the batch abend, naming the operation and the file")
        void theHelperAlwaysRaises() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.doAbend(AbstractCobolStep.IoOperation.READ,
                            RESOURCE, "35"))
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM);
                        assertThat(abend.reason()).isEqualTo("STATUS 35 READING " + RESOURCE);
                        assertThat(abend).hasNoCause();
                    });
        }

        @Test
        @DisplayName("an absent status is reported as absent rather than as a blank code")
        void anAbsentStatusIsNamed() {
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.doAbend(AbstractCobolStep.IoOperation.CLOSE,
                            RESOURCE, null))
                    .satisfies(abend -> assertThat(abend.reason())
                            .isEqualTo("STATUS (none) CLOSING " + RESOURCE));
        }

        @Test
        @DisplayName("the helper insists on an operation and a file")
        void theArgumentsAreMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.doAbend(null, RESOURCE, "35"))
                    .withMessage("operation");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.doAbend(AbstractCobolStep.IoOperation.READ, null, "35"))
                    .withMessage("resource");
        }
    }

    // THE BATCH TIMESTAMP

    /**
     * Verifies the twenty-six character timestamp the legacy programs assemble by hand.
     *
     * <p>The shape is {@code CCYY-MM-DD-hh.mm.ss.tttttt} — a four-digit year, hyphen-separated date
     * parts, a hyphen, then dot-separated time parts, then six fractional digits of which the
     * programs only ever populate the leading two and pad the remaining four with zeros. That
     * padding is why the image is twenty-six characters rather than twenty-two, and why the trailing
     * four zeros are constant.
     */
    @Nested
    @DisplayName("The twenty-six character batch timestamp")
    class BatchTimestamp {

        @Test
        @DisplayName("the image reproduces the legacy shape exactly")
        void theImageIsTheLegacyShape() {
            final String image = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 3, 9, 4, 5, 6, 78_900_000));

            assertThat(image).isEqualTo("2024-03-09-04.05.06.070000");
            assertThat(image).hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the fractional part carries hundredths, with four zeros of padding")
        void onlyHundredthsAreCarried() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 1, 1, 0, 0, 0, 999_999_999)))
                    .isEqualTo("2024-01-01-00.00.00.990000");
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 1, 1, 0, 0, 0, 0)))
                    .isEqualTo("2024-01-01-00.00.00.000000");
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2024, 1, 1, 0, 0, 0, 9_999_999)))
                    .isEqualTo("2024-01-01-00.00.00.000000");
        }

        @Test
        @DisplayName("every component is zero padded to its legacy width")
        void everyComponentIsZeroPadded() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(7, 2, 3, 4, 5, 6)))
                    .isEqualTo("0007-02-03-04.05.06.000000")
                    .hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the earliest and latest representable years both fit the field")
        void theYearBoundsFitTheField() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(LocalDateTime.of(0, 1, 1, 0, 0)))
                    .startsWith("0000-")
                    .hasSize(TIMESTAMP_WIDTH);
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(9999, 12, 31, 23, 59, 59)))
                    .startsWith("9999-")
                    .hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("a year the four-byte field cannot hold is refused, not silently widened")
        void anUnrepresentableYearIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(
                            LocalDateTime.of(10_000, 1, 1, 0, 0)))
                    .withMessage("year 10000 cannot be held in the legacy four-byte year field;"
                            + " expected 0 to 9999");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(
                            LocalDateTime.of(-1, 1, 1, 0, 0)))
                    .withMessageContaining("cannot be held in the legacy four-byte year field");
        }

        @Test
        @DisplayName("an absent moment is refused")
        void anAbsentMomentIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(null))
                    .withMessage("moment");
        }

        @Test
        @DisplayName("the injected clock, not the wall clock, decides the timestamp")
        void theInjectedClockDecides() {
            final RecordingStep step = new RecordingStep(new SimpleMeterRegistry());

            assertThat(step.timestamp()).isEqualTo("2024-03-09-04.05.06.070000");
        }

        @Test
        @DisplayName("the clock's zone is honoured, so the same instant reads differently")
        void theClockZoneIsHonoured() {
            final Clock shifted = Clock.fixed(Instant.parse("2024-03-09T23:30:00Z"),
                    ZoneId.of("Asia/Tokyo"));
            final RecordingStep step =
                    new RecordingStep(PROGRAM, new SimpleMeterRegistry(), shifted);

            assertThat(step.timestamp()).isEqualTo("2024-03-10-08.30.00.000000");
        }

        @Test
        @DisplayName("both timestamps in a summary come from the same injected clock")
        void theSummaryTimestampsComeFromTheClock() {
            final AbstractCobolStep.ExecutionSummary summary =
                    new RecordingStep(new SimpleMeterRegistry()).run();

            assertThat(summary.startedAt()).isEqualTo("2024-03-09-04.05.06.070000");
            assertThat(summary.completedAt()).isEqualTo(summary.startedAt());
        }
    }

    // INSTRUMENTATION

    /**
     * Verifies the lifecycle timer, which is the observability that replaces the legacy
     * {@code DISPLAY} of a start and an end banner.
     *
     * <p>The outcome tag is what makes the timer useful: an abended run and a completed run are both
     * recorded, under different tag values, so a dashboard can show the ratio rather than only the
     * elapsed time of the runs that happened to succeed.
     */
    @Nested
    @DisplayName("Lifecycle instrumentation")
    class Instrumentation {

        @Test
        @DisplayName("a completed run records one timing tagged with the program and the outcome")
        void aCompletedRunIsTimed() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();

            new RecordingStep(registry).withRecords("A").run();

            assertThat(timerCount(registry, "COMPLETED")).isEqualTo(1L);
            assertThat(timerCount(registry, "ABENDED")).isZero();
        }

        @Test
        @DisplayName("an abended run is timed too, under the other outcome")
        void anAbendedRunIsTimed() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            final RecordingStep step = new RecordingStep(registry);
            step.failOnOpen = new IllegalStateException("boom");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(timerCount(registry, "ABENDED")).isEqualTo(1L);
            assertThat(timerCount(registry, "COMPLETED")).isZero();
        }

        @Test
        @DisplayName("repeated runs accumulate against the same timer")
        void repeatedRunsAccumulate() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            final RecordingStep step = new RecordingStep(registry);

            step.run();
            step.run();

            assertThat(timerCount(registry, "COMPLETED")).isEqualTo(2L);
        }

        @Test
        @DisplayName("the timer is registered under the documented name")
        void theTimerNameIsStable() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();

            new RecordingStep(registry).run();

            assertThat(registry.find(METRIC).timers()).isNotEmpty();
        }
    }

    // CONSTRUCTION

    /**
     * Verifies the constructor guards.
     *
     * <p>The program name is not decoration: it becomes the {@code ABEND-CULPRIT} of every abend the
     * step raises, and that field is {@code PIC X(8)}. A longer name would either be truncated in a
     * way that loses which program failed, or rejected later at the point of abend, when the
     * diagnostic is already needed. Rejecting it at construction is the safer of the two.
     */
    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("a well-formed step is accepted and reports its program name")
        void aWellFormedStepIsAccepted() {
            assertThat(new RecordingStep(new SimpleMeterRegistry()).name()).isEqualTo(PROGRAM);
        }

        @Test
        @DisplayName("a step built directly on the base class emits the timestamp at the legacy width")
        void aStepBuiltDirectlyOnTheBaseClassEmitsTheLegacyWidth() {
            // The clock is a constructor argument and there is no form that defaults it, so the
            // width asserted below is produced from a pinned instant rather than from whatever the
            // host's regional settings happen to be.
            final AbstractCobolStep<String> step = new AbstractCobolStep<>(PROGRAM,
                    new SimpleMeterRegistry(), FROZEN) {

                @Override
                protected void openResources() {
                    // Nothing to open: this step exists only to exercise construction on the base.
                }

                @Override
                protected Optional<String> readNextRecord() {
                    return Optional.empty();
                }

                @Override
                protected void processRecord(final String record) {
                    throw new IllegalStateException("no record can be delivered by an empty reader");
                }

                @Override
                protected void closeResources() {
                    // Nothing to close.
                }
            };

            assertThat(step.run().startedAt()).hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("an absent or blank program name is refused")
        void theProgramNameIsMandatory() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RecordingStep(null, registry, FROZEN))
                    .withMessage("programName");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RecordingStep("  ", registry, FROZEN))
                    .withMessage("programName must name the legacy batch program"
                            + " this step stands in for");
        }

        @Test
        @DisplayName("a program name wider than the legacy culprit field is refused")
        void anOverlongProgramNameIsRefused() {
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RecordingStep("CBACT01CX", registry, FROZEN))
                    .withMessage("programName exceeds the legacy ABEND-CULPRIT width of 8"
                            + " characters: CBACT01CX");
        }

        @Test
        @DisplayName("a name of exactly the legacy width is accepted")
        void aNameOfExactlyEightCharactersIsAccepted() {
            assertThat(new RecordingStep("CBTRN02C", new SimpleMeterRegistry(), FROZEN).name())
                    .isEqualTo("CBTRN02C")
                    .hasSize(AbendException.CULPRIT_LENGTH);
        }

        @Test
        @DisplayName("an absent registry or clock is refused")
        void theCollaboratorsAreMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RecordingStep(PROGRAM, null, FROZEN))
                    .withMessage("meterRegistry");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RecordingStep(PROGRAM, new SimpleMeterRegistry(), null))
                    .withMessage("clock");
        }
    }

    // THE EXECUTION SUMMARY

    /**
     * Verifies {@link AbstractCobolStep.ExecutionSummary}, the value a completed lifecycle reports.
     *
     * <p>It stands in for the three {@code DISPLAY} statements every batch program ends with: the
     * start banner, the record count and the end banner. A negative record count is refused because
     * no legacy program can have read a negative number of records, and permitting one would let a
     * mis-wired step publish a nonsensical figure to a dashboard.
     */
    @Nested
    @DisplayName("ExecutionSummary")
    class Summary {

        @Test
        @DisplayName("a well-formed summary is accepted")
        void aWellFormedSummaryIsAccepted() {
            final AbstractCobolStep.ExecutionSummary summary =
                    new AbstractCobolStep.ExecutionSummary(PROGRAM, 300L,
                            "2024-03-09-04.05.06.070000", "2024-03-09-04.05.07.070000");

            assertThat(summary.programName()).isEqualTo(PROGRAM);
            assertThat(summary.recordsRead()).isEqualTo(300L);
            assertThat(summary.startedAt()).isNotEqualTo(summary.completedAt());
        }

        @Test
        @DisplayName("a zero record count is legitimate — an empty file is not an error")
        void zeroRecordsIsLegitimate() {
            assertThatNoException().isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(
                    PROGRAM, 0L, "A", "B"));
        }

        @Test
        @DisplayName("a negative record count is refused")
        void aNegativeRecordCountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(PROGRAM, -1L, "A", "B"))
                    .withMessage("recordsRead must not be negative: -1");
        }

        @Test
        @DisplayName("each text component is required")
        void theTextComponentsAreMandatory() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(null, 0L, "A", "B"))
                    .withMessage("programName");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(PROGRAM, 0L, null, "B"))
                    .withMessage("startedAt");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(PROGRAM, 0L, "A", null))
                    .withMessage("completedAt");
        }
    }
}
