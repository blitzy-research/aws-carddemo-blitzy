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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.repeat.RepeatStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies {@code AbstractCobolStep}, the template that absorbs the open / read-loop / status-check /
 * close / abend skeleton shared by all ten batch programs.
 *
 * <p>The skeleton is not invented. It is the shape of {@code app/cbl/CBACT01C.cbl}, whose procedure
 * division opens the file, loops reading until the at-end flag is set, processes each delivered
 * record, closes the file and abends on any terminal status; the other nine programs repeat it with a
 * different loop body and a different number of files.
 *
 * <p><strong>The two-level status model is the thing under test.</strong> The legacy programs never
 * branch on the raw two-character {@code FILE STATUS}. They normalise it into {@code APPL-RESULT}
 * first - zero for success, sixteen for at end, twelve for anything else - and then branch on the
 * condition names {@code APPL-AOK} and {@code APPL-EOF}
 * ({@code app/cbl/CBACT01C.cbl} lines 90 to 114). Collapsing the two levels into one enumeration
 * would erase the end-of-file-versus-error distinction that every batch read loop depends on, so both
 * levels are asserted separately here.
 *
 * <p>Three distinctions carry the most risk and each has its own nested class:
 *
 * <ol>
 *   <li><strong>An at-end status is normal for a read and fatal for everything else.</strong> The
 *       read paragraphs normalise three ways; the open, write and close paragraphs normalise two ways
 *       and have no at-end clause at all. Erasing the distinction would make a failed open look like
 *       an empty file.</li>
 *   <li><strong>The pre-operation sentinel makes an incomplete operation terminal.</strong> The legacy
 *       arms {@code APPL-RESULT} to eight before an open, a write and a close, and eight matches
 *       neither condition name, so an operation that never reports a status falls through to the
 *       terminal branch with no extra check.</li>
 *   <li><strong>The diagnostic is emitted before the abend exists.</strong> Two log records precede
 *       the exception, mirroring {@code 9910-DISPLAY-IO-STATUS} followed by
 *       {@code 9999-ABEND-PROGRAM}, and the raw status travels with the failure so that one file's
 *       status can never be reported for another file's failure - the defect at
 *       {@code app/cbl/CBTRN02C.cbl} line 649.</li>
 * </ol>
 *
 * <p>Because the class is abstract, the tests drive it through {@link ScriptedStep}, a concrete
 * subclass that records what the template asked it to do and replays a scripted sequence of statuses.
 * A hand-written double is used rather than a mocking framework because the template's collaborator is
 * its own abstract method set, and because the generic {@code IoAction} callbacks cannot be stubbed
 * without unchecked warnings under {@code -Werror}.
 */
@DisplayName("AbstractCobolStep: the batch lifecycle template and its two-level status model")
final class AbstractCobolStepTest {

    // =================================================================================================
    // Oracles read from the legacy source, never from the Java under test.
    // =================================================================================================

    /** {@code 88 APPL-AOK VALUE 0}, {@code [app/cbl/CBACT01C.cbl:L90]}. */
    private static final int ORACLE_APPL_RESULT_AOK = 0;

    /** The value armed before an open, a write and a close, {@code [app/cbl/CBACT01C.cbl:L135]}. */
    private static final int ORACLE_APPL_RESULT_PENDING = 8;

    /** The terminal normalised value, {@code MOVE 12 TO APPL-RESULT}. */
    private static final int ORACLE_APPL_RESULT_ERROR = 12;

    /** {@code 88 APPL-EOF VALUE 16}, {@code MOVE 16 TO APPL-RESULT} on an at-end status. */
    private static final int ORACLE_APPL_RESULT_EOF = 16;

    /**
     * Width of the batch timestamp field, {@code [app/cbl/CBTRN02C.cbl:L148]} and its redefinition at
     * lines 159 to 174.
     */
    private static final int ORACLE_BATCH_TIMESTAMP_LENGTH = 26;

    /** The four-character literal tail of the batch timestamp image. */
    private static final String ORACLE_BATCH_TIMESTAMP_TAIL = "0000";

    /** The batch abend code, {@code [app/cpy/CSMSG02Y.cpy]} as carried by {@code AbendException}. */
    private static final String ORACLE_BATCH_ABEND_CODE = "999";

    /** The four legacy gerunds the diagnostics use, so a Java log line reads as the console read. */
    private static final String ORACLE_GERUND_OPEN = "OPENING";

    /** {@code ERROR READING ACCOUNT FILE}. */
    private static final String ORACLE_GERUND_READ = "READING";

    /** {@code ERROR WRITING TO REJECTS FILE}. */
    private static final String ORACLE_GERUND_WRITE = "WRITING TO";

    /** {@code ERROR CLOSING ACCOUNT FILE}. */
    private static final String ORACLE_GERUND_CLOSE = "CLOSING";

    /** Raw success status, {@code 88 ... VALUE '00'}. */
    private static final String STATUS_SUCCESS = "00";

    /** Raw at-end status, compared seven times across the estate. */
    private static final String STATUS_END_OF_FILE = "10";

    /** Raw record-not-found status, compared exactly once, in the interest program. */
    private static final String STATUS_RECORD_NOT_FOUND = "23";

    /** Raw permanent-error status. */
    private static final String STATUS_PERMANENT_ERROR = "31";

    /** A code outside the vocabulary the estate compares anywhere. */
    private static final String STATUS_OUTSIDE_VOCABULARY = "99";

    /** The legacy program name used throughout, and the abend culprit. */
    private static final String PROGRAM_NAME = "CBACT01C";

    /** The file name used throughout, named as the legacy diagnostics name it. */
    private static final String RESOURCE_NAME = "ACCTFILE";

    /** A second file name, so a diagnostic can be shown to name the file that actually failed. */
    private static final String OTHER_RESOURCE_NAME = "DALYREJS";

    /** A pinned instant, so the timestamp image can be asserted character for character. */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-06T14:23:41.87Z");

    /** The image the pinned instant must render to under the batch shape. */
    private static final String PINNED_TIMESTAMP_IMAGE = "2022-07-06-14.23.41.87" + "0000";

    /** A record value the scripted read delivers. */
    private static final String FIRST_RECORD = "RECORD ONE";

    /** A second record value. */
    private static final String SECOND_RECORD = "RECORD TWO";

    /** The registry the lifecycle timer registers with. */
    private MeterRegistry meterRegistry;

    /** The clock pinned to {@link #PINNED_INSTANT} in the UTC zone. */
    private Clock fixedClock;

    @BeforeEach
    void createCollaborators() {
        meterRegistry = new SimpleMeterRegistry();
        fixedClock = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);
    }

    /**
     * Builds a step whose read delivers each supplied record in turn and then reports end of file.
     *
     * @param records the records the read should deliver, in order
     * @return the scripted step
     */
    private ScriptedStep stepDelivering(final String... records) {
        final ScriptedStep step = new ScriptedStep(PROGRAM_NAME, meterRegistry, fixedClock);
        for (final String record : records) {
            step.enqueueRead(AbstractCobolStep.IoResult.of(STATUS_SUCCESS, record));
        }
        return step;
    }

    // =================================================================================================
    // The concrete subclass the tests drive the template through.
    // =================================================================================================

    /**
     * A concrete step that records what the template asked of it and replays a scripted read sequence.
     *
     * <p>Every abstract method is implemented through the guarded helpers, exactly as the class
     * documentation instructs a real step to implement them, so the tests exercise the template's own
     * sentinel, normalisation and diagnostic ordering rather than a bypass of them.
     */
    private static final class ScriptedStep extends AbstractCobolStep<String> {

        /** The order in which the template invoked the lifecycle hooks. */
        private final List<String> lifecycle = new ArrayList<>();

        /** Results the scripted read will report, in order; exhaustion means end of file. */
        private final Deque<IoResult<String>> reads = new ArrayDeque<>();

        /** Records the template handed to {@code processRecord}. */
        private final List<String> processed = new ArrayList<>();

        /** Raw status the scripted open reports. */
        private String openStatus = STATUS_SUCCESS;

        /** Raw status the scripted close reports. */
        private String closeStatus = STATUS_SUCCESS;

        /** When set, the scripted open throws this instead of reporting a status. */
        private Exception openFailure;

        /** When set, the scripted close throws this instead of reporting a status. */
        private RuntimeException closeFailure;

        /** When set, the failure-path handle release throws this. */
        private RuntimeException releaseFailure;

        /** When set, the scripted read throws this instead of reporting a result. */
        private Exception readFailure;

        /** When set, {@code processRecord} throws this on its first invocation. */
        private RuntimeException processFailure;

        /** When set, {@code readNextRecord} returns {@code null} rather than an {@code Optional}. */
        private boolean readReturnsNull;

        /** How many times the close sequence has been entered. */
        private int closeAttempts;

        /** How many times the failure-path handle release has been entered. */
        private int releaseAttempts;

        ScriptedStep(final String programName, final MeterRegistry meterRegistry, final Clock clock) {
            super(programName, meterRegistry, clock);
        }

        void enqueueRead(final IoResult<String> result) {
            reads.addLast(result);
        }

        @Override
        protected void openResources() {
            lifecycle.add("open");
            openResource(RESOURCE_NAME, () -> {
                if (openFailure != null) {
                    throw openFailure;
                }
                return openStatus;
            });
        }

        @Override
        protected Optional<String> readNextRecord() {
            lifecycle.add("read");
            if (readReturnsNull) {
                return null;
            }
            return readRecord(RESOURCE_NAME, () -> {
                if (readFailure != null) {
                    throw readFailure;
                }
                final IoResult<String> next = reads.pollFirst();
                return next == null ? IoResult.endOfFile() : next;
            });
        }

        @Override
        protected void processRecord(final String record) {
            lifecycle.add("process");
            if (processFailure != null) {
                final RuntimeException toThrow = processFailure;
                processFailure = null;
                throw toThrow;
            }
            processed.add(record);
        }

        @Override
        protected void closeResources() {
            lifecycle.add("close");
            closeAttempts++;
            if (closeFailure != null) {
                throw closeFailure;
            }
            closeResource(RESOURCE_NAME, () -> closeStatus);
        }

        /**
         * Records the failure-path handle release.
         *
         * <p>Deliberately does <em>not</em> route through {@link #closeResource(String, IoAction)}: the
         * documented contract on the hook forbids a guarded helper here, because the legacy performs no
         * operation at all at this point and a guarded call would normalise a status and emit a
         * diagnostic that has no antecedent.</p>
         */
        @Override
        protected void releaseResources() {
            lifecycle.add("release");
            releaseAttempts++;
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }

        /** Exposes the protected normalisation for direct assertion. */
        IoOutcome normalise(final IoOperation operation, final String rawStatus) {
            return normaliseStatus(operation, rawStatus);
        }

        /** Exposes the protected terminal path for direct assertion. */
        void abend(final IoOperation operation, final String resource, final String rawStatus) {
            abendOnIoFailure(operation, resource, rawStatus);
        }

        /** Exposes the protected accessor for direct assertion. */
        String name() {
            return programName();
        }

        /** Exposes the protected clock-reading timestamp for direct assertion. */
        String timestamp() {
            return currentBatchTimestamp();
        }

        /** Exposes a guarded write so the write path can be driven without a second subclass. */
        void write(final String resource, final String rawStatus) {
            writeRecord(resource, () -> rawStatus);
        }
    }

    @Nested
    @DisplayName("construction: the program name doubles as the abend culprit and is validated early")
    final class ConstructionContract {

        @Test
        @DisplayName("the program name is reported back verbatim")
        void theProgramNameIsReportedBack() {
            assertThat(new ScriptedStep(PROGRAM_NAME, meterRegistry, fixedClock).name())
                    .isEqualTo(PROGRAM_NAME);
        }

        @Test
        @DisplayName("an absent or blank program name is rejected at construction, not at failure time")
        void anAbsentOrBlankProgramNameIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ScriptedStep(null, meterRegistry, fixedClock))
                    .withMessageContaining("programName");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScriptedStep("   ", meterRegistry, fixedClock))
                    .withMessageContaining("programName");
        }

        @Test
        @DisplayName("a program name longer than the legacy culprit field is rejected")
        void anOverlongProgramNameIsRejected() {
            final String tooLong = "A".repeat(AbendException.CULPRIT_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ScriptedStep(tooLong, meterRegistry, fixedClock))
                    .withMessageContaining(String.valueOf(AbendException.CULPRIT_LENGTH));
        }

        @Test
        @DisplayName("a name of exactly the culprit width is accepted")
        void aNameOfExactlyTheCulpritWidthIsAccepted() {
            final String exact = "A".repeat(AbendException.CULPRIT_LENGTH);

            assertThat(new ScriptedStep(exact, meterRegistry, fixedClock).name()).isEqualTo(exact);
            assertThat(PROGRAM_NAME).hasSize(AbendException.CULPRIT_LENGTH);
        }

        @Test
        @DisplayName("a missing meter registry is rejected")
        void aMissingMeterRegistryIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ScriptedStep(PROGRAM_NAME, null, fixedClock));
        }

        @Test
        @DisplayName("a missing clock is rejected")
        void aMissingClockIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new ScriptedStep(PROGRAM_NAME, meterRegistry, null));
        }
    }

    @Nested
    @DisplayName("the lifecycle: open, then read until at end, then close, in that order")
    final class LifecycleOrdering {

        @Test
        @DisplayName("an empty file opens, reads once, and closes without processing anything")
        void anEmptyFileOpensReadsOnceAndCloses() {
            final ScriptedStep step = stepDelivering();

            final AbstractCobolStep.ExecutionSummary summary = step.run();

            assertThat(step.lifecycle).containsExactly("open", "read", "close");
            assertThat(step.processed).isEmpty();
            assertThat(summary.recordsRead()).isZero();
        }

        @Test
        @DisplayName("each delivered record is processed once, in file order")
        void eachDeliveredRecordIsProcessedOnceInOrder() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD, SECOND_RECORD);

            final AbstractCobolStep.ExecutionSummary summary = step.run();

            assertThat(step.processed).containsExactly(FIRST_RECORD, SECOND_RECORD);
            assertThat(summary.recordsRead()).isEqualTo(2L);
            assertThat(step.lifecycle)
                    .containsExactly("open", "read", "process", "read", "process", "read", "close");
        }

        @Test
        @DisplayName("the read that reports end of file is not processed, which is the inner flag test")
        void theAtEndReadIsNotProcessed() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD);

            step.run();

            assertThat(step.processed).containsExactly(FIRST_RECORD);
            assertThat(step.lifecycle.stream().filter("read"::equals).count())
                    .as("one read per record plus the at-end read")
                    .isEqualTo(2L);
            assertThat(step.lifecycle.stream().filter("process"::equals).count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the summary carries the program name and both timestamps")
        void theSummaryCarriesTheProgramNameAndBothTimestamps() {
            final AbstractCobolStep.ExecutionSummary summary = stepDelivering(FIRST_RECORD).run();

            assertThat(summary.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(summary.startedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(summary.completedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
        }

        @Test
        @DisplayName("the loop keeps no instance state, so one instance may run twice")
        void oneInstanceMayRunTwice() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD);

            final AbstractCobolStep.ExecutionSummary first = step.run();
            final AbstractCobolStep.ExecutionSummary second = step.run();

            assertThat(first.recordsRead()).isEqualTo(1L);
            assertThat(second.recordsRead()).as("the queue is drained, so the second pass sees an empty file")
                    .isZero();
            assertThat(step.closeAttempts).isEqualTo(2);
        }

        @Test
        @DisplayName("a read that reports no Optional at all is refused rather than dereferenced")
        void aReadReportingNoOptionalIsRefused() {
            final ScriptedStep step = stepDelivering();
            step.readReturnsNull = true;

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining("readNextRecord");
        }

        @Test
        @DisplayName("the tasklet adapter runs the lifecycle once and always reports finished")
        void theTaskletAdapterRunsOnceAndReportsFinished() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD);

            assertThat(step.execute(null, null))
                    .as("a legacy batch program processes a whole file in one invocation")
                    .isEqualTo(RepeatStatus.FINISHED);
            assertThat(step.processed).containsExactly(FIRST_RECORD);
        }

        @Test
        @DisplayName("neither tasklet parameter is consulted, so both may be absent")
        void neitherTaskletParameterIsConsulted() {
            assertThat(stepDelivering().execute(null, null))
                    .isEqualTo(RepeatStatus.FINISHED);
        }

        @Test
        @DisplayName("the lifecycle is timed once on the completing path")
        void theLifecycleIsTimedOnceOnTheCompletingPath() {
            stepDelivering(FIRST_RECORD).run();

            assertThat(meterRegistry.find("carddemo.batch.cobol.step")
                    .tag("step", PROGRAM_NAME)
                    .tag("outcome", "COMPLETED")
                    .timer())
                    .isNotNull();
            assertThat(meterRegistry.find("carddemo.batch.cobol.step")
                    .tag("outcome", "COMPLETED").timer().count())
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("the two-level status model: raw code, then APPL-RESULT, then the condition names")
    final class TwoLevelStatusModel {

        @Test
        @DisplayName("success normalises to APPL-AOK for every operation kind")
        void successNormalisesToApplAok() {
            final ScriptedStep step = stepDelivering();

            for (final AbstractCobolStep.IoOperation operation
                    : AbstractCobolStep.IoOperation.values()) {
                assertThat(step.normalise(operation, STATUS_SUCCESS))
                        .as("success on %s", operation)
                        .isEqualTo(AbstractCobolStep.IoOutcome.OK);
            }
        }

        @Test
        @DisplayName("an at-end status normalises to APPL-EOF for a read and to failure for the rest")
        void atEndIsNormalOnlyForARead() {
            final ScriptedStep step = stepDelivering();

            assertThat(step.normalise(AbstractCobolStep.IoOperation.READ, STATUS_END_OF_FILE))
                    .isEqualTo(AbstractCobolStep.IoOutcome.END_OF_FILE);
            for (final AbstractCobolStep.IoOperation operation
                    : EnumSet.of(AbstractCobolStep.IoOperation.OPEN,
                            AbstractCobolStep.IoOperation.WRITE,
                            AbstractCobolStep.IoOperation.CLOSE)) {
                assertThat(step.normalise(operation, STATUS_END_OF_FILE))
                        .as("an at-end status arriving from %s is a failure, not an empty file", operation)
                        .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            }
        }

        @Test
        @DisplayName("every other recognised code normalises to failure, whatever the operation")
        void everyOtherRecognisedCodeIsAFailure() {
            final ScriptedStep step = stepDelivering();

            for (final FileStatus status : FileStatus.values()) {
                if (status.isSuccess() || status.isEndOfFile()) {
                    continue;
                }
                for (final AbstractCobolStep.IoOperation operation
                        : AbstractCobolStep.IoOperation.values()) {
                    assertThat(step.normalise(operation, status.getCode()))
                            .as("%s on %s", status, operation)
                            .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
                }
            }
        }

        @Test
        @DisplayName("a code outside the estate's vocabulary is a failure, never an exception")
        void anUnrecognisedCodeIsAFailureNotAnException() {
            final ScriptedStep step = stepDelivering();

            assertThat(step.normalise(AbstractCobolStep.IoOperation.READ, STATUS_OUTSIDE_VOCABULARY))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(step.normalise(AbstractCobolStep.IoOperation.READ, null))
                    .as("no status at all is also a failure")
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(step.normalise(AbstractCobolStep.IoOperation.READ, ""))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("the three outcomes carry the APPL-RESULT values the source moves")
        void theThreeOutcomesCarryTheirApplResultValues() {
            assertThat(AbstractCobolStep.IoOutcome.OK.applResult()).isEqualTo(ORACLE_APPL_RESULT_AOK);
            assertThat(AbstractCobolStep.IoOutcome.END_OF_FILE.applResult())
                    .isEqualTo(ORACLE_APPL_RESULT_EOF);
            assertThat(AbstractCobolStep.IoOutcome.ERROR.applResult())
                    .isEqualTo(ORACLE_APPL_RESULT_ERROR);
            assertThat(AbstractCobolStep.IoOutcome.values()).hasSize(3);
        }

        @Test
        @DisplayName("normalising requires an operation kind, because the kind decides the at-end clause")
        void normalisingRequiresAnOperationKind() {
            final ScriptedStep step = stepDelivering();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.normalise(null, STATUS_SUCCESS))
                    .withMessageContaining("operation");
        }

        @Test
        @DisplayName("the record-not-found status the interest program accepts normalises to failure here")
        void recordNotFoundNormalisesToFailure() {
            final ScriptedStep step = stepDelivering();

            assertThat(step.normalise(AbstractCobolStep.IoOperation.READ, STATUS_RECORD_NOT_FOUND))
                    .as("the canonical cascade rejects it; a site that accepts it applies its own rule")
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }
    }

    @Nested
    @DisplayName("the four operation kinds and their three differing facts")
    final class OperationKindContract {

        @Test
        @DisplayName("exactly four kinds exist, one per legacy I/O verb")
        void exactlyFourKindsExist() {
            assertThat(AbstractCobolStep.IoOperation.values()).hasSize(4);
            assertThat(EnumSet.allOf(AbstractCobolStep.IoOperation.class))
                    .containsExactly(AbstractCobolStep.IoOperation.OPEN,
                            AbstractCobolStep.IoOperation.READ,
                            AbstractCobolStep.IoOperation.WRITE,
                            AbstractCobolStep.IoOperation.CLOSE);
        }

        @Test
        @DisplayName("each kind carries the gerund its legacy diagnostic used")
        void eachKindCarriesItsLegacyGerund() {
            assertThat(AbstractCobolStep.IoOperation.OPEN.legacyGerund()).isEqualTo(ORACLE_GERUND_OPEN);
            assertThat(AbstractCobolStep.IoOperation.READ.legacyGerund()).isEqualTo(ORACLE_GERUND_READ);
            assertThat(AbstractCobolStep.IoOperation.WRITE.legacyGerund())
                    .as("the write diagnostic reads ERROR WRITING TO REJECTS FILE")
                    .isEqualTo(ORACLE_GERUND_WRITE);
            assertThat(AbstractCobolStep.IoOperation.CLOSE.legacyGerund())
                    .isEqualTo(ORACLE_GERUND_CLOSE);
        }

        @Test
        @DisplayName("only the read declines to arm the sentinel; the other three arm it to eight")
        void onlyTheReadDeclinesToArmTheSentinel() {
            assertThat(AbstractCobolStep.IoOperation.OPEN.armedApplResult())
                    .isEqualTo(ORACLE_APPL_RESULT_PENDING);
            assertThat(AbstractCobolStep.IoOperation.WRITE.armedApplResult())
                    .isEqualTo(ORACLE_APPL_RESULT_PENDING);
            assertThat(AbstractCobolStep.IoOperation.CLOSE.armedApplResult())
                    .isEqualTo(ORACLE_APPL_RESULT_PENDING);
            assertThat(AbstractCobolStep.IoOperation.READ.armedApplResult())
                    .as("the read paragraphs do not arm the sentinel")
                    .isEqualTo(ORACLE_APPL_RESULT_ERROR);
        }

        @Test
        @DisplayName("both armed values are terminal, so an incomplete operation abends either way")
        void bothArmedValuesAreTerminal() {
            final ScriptedStep step = stepDelivering();

            for (final AbstractCobolStep.IoOperation operation
                    : AbstractCobolStep.IoOperation.values()) {
                assertThat(operation.armedApplResult())
                        .as("the armed value of %s must match neither condition name", operation)
                        .isNotIn(ORACLE_APPL_RESULT_AOK, ORACLE_APPL_RESULT_EOF);
            }
            assertThat(step.normalise(AbstractCobolStep.IoOperation.OPEN, null))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("end of file terminates normally for the read alone")
        void endOfFileTerminatesNormallyForTheReadAlone() {
            assertThat(AbstractCobolStep.IoOperation.READ.endOfFileTerminatesNormally()).isTrue();
            assertThat(AbstractCobolStep.IoOperation.OPEN.endOfFileTerminatesNormally()).isFalse();
            assertThat(AbstractCobolStep.IoOperation.WRITE.endOfFileTerminatesNormally()).isFalse();
            assertThat(AbstractCobolStep.IoOperation.CLOSE.endOfFileTerminatesNormally()).isFalse();
        }
    }

    @Nested
    @DisplayName("the terminal path: diagnostic first, then the abend, naming the file that failed")
    final class TerminalPath {

        @Test
        @DisplayName("a failed open abends and never reaches the read loop")
        void aFailedOpenAbendsBeforeTheReadLoop() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD);
            step.openStatus = STATUS_PERMANENT_ERROR;

            assertThatExceptionOfType(AbendException.class).isThrownBy(step::run);

            assertThat(step.processed).isEmpty();
            assertThat(step.lifecycle).doesNotContain("process");
        }

        @Test
        @DisplayName("an at-end status on an open is a failure, not an empty file")
        void anAtEndStatusOnAnOpenIsAFailure() {
            final ScriptedStep step = stepDelivering();
            step.openStatus = STATUS_END_OF_FILE;

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining(ORACLE_GERUND_OPEN);
        }

        @Test
        @DisplayName("a read reporting a non-success, non-at-end status abends")
        void aBadReadStatusAbends() {
            final ScriptedStep step = new ScriptedStep(PROGRAM_NAME, meterRegistry, fixedClock);
            step.enqueueRead(AbstractCobolStep.IoResult.of(STATUS_PERMANENT_ERROR, FIRST_RECORD));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining(ORACLE_GERUND_READ)
                    .withMessageContaining(STATUS_PERMANENT_ERROR);
        }

        @Test
        @DisplayName("a failed close abends, exactly as an open and a read do")
        void aFailedCloseAbends() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD);
            step.closeStatus = STATUS_PERMANENT_ERROR;

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining(ORACLE_GERUND_CLOSE);
            assertThat(step.processed).as("the record was processed before the close failed")
                    .containsExactly(FIRST_RECORD);
        }

        @Test
        @DisplayName("a failed write abends and names the write verb")
        void aFailedWriteAbends() {
            final ScriptedStep step = stepDelivering();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.write(OTHER_RESOURCE_NAME, STATUS_PERMANENT_ERROR))
                    .withMessageContaining(ORACLE_GERUND_WRITE)
                    .withMessageContaining(OTHER_RESOURCE_NAME);
        }

        @Test
        @DisplayName("a successful write returns quietly")
        void aSuccessfulWriteReturnsQuietly() {
            final ScriptedStep step = stepDelivering();

            step.write(OTHER_RESOURCE_NAME, STATUS_SUCCESS);
        }

        @Test
        @DisplayName("the abend carries the batch code and the program name as its culprit")
        void theAbendCarriesTheBatchCodeAndTheCulprit() {
            final ScriptedStep step = stepDelivering();
            step.openStatus = STATUS_PERMANENT_ERROR;

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .satisfies(abend -> {
                        assertThat(abend.code()).isEqualTo(ORACLE_BATCH_ABEND_CODE);
                        assertThat(abend.culprit()).isEqualTo(PROGRAM_NAME);
                        assertThat(abend.reason()).contains(STATUS_PERMANENT_ERROR);
                    });
            assertThat(AbendException.BATCH_ABEND_CODE).isEqualTo(ORACLE_BATCH_ABEND_CODE);
        }

        @Test
        @DisplayName("the abend's bounded fields never exceed the widths the legacy declares")
        void theAbendsBoundedFieldsNeverExceedTheLegacyWidths() {
            final ScriptedStep step = stepDelivering();
            final String longResource = "R".repeat(200);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.abend(AbstractCobolStep.IoOperation.READ, longResource,
                            STATUS_PERMANENT_ERROR))
                    .satisfies(abend -> {
                        assertThat(abend.reason().length())
                                .isLessThanOrEqualTo(AbendException.REASON_LENGTH);
                        assertThat(abend.getMessage().length())
                                .isLessThanOrEqualTo(AbendException.MESSAGE_LENGTH);
                    });
        }

        @Test
        @DisplayName("the diagnostic names the file that failed, not some other file")
        void theDiagnosticNamesTheFileThatFailed() {
            final ScriptedStep step = stepDelivering();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.abend(AbstractCobolStep.IoOperation.CLOSE,
                            OTHER_RESOURCE_NAME, STATUS_PERMANENT_ERROR))
                    .withMessageContaining(OTHER_RESOURCE_NAME)
                    .withMessageNotContaining(RESOURCE_NAME);
        }

        @Test
        @DisplayName("the terminal path always throws, even on a status that reads as success")
        void theTerminalPathAlwaysThrows() {
            final ScriptedStep step = stepDelivering();

            // The caller has already decided the status is fatal, so the helper does not re-judge it.
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.abend(AbstractCobolStep.IoOperation.READ, RESOURCE_NAME,
                            STATUS_SUCCESS))
                    .withMessageContaining(STATUS_SUCCESS);
        }

        @Test
        @DisplayName("an absent status is rendered as absent rather than as a null literal")
        void anAbsentStatusIsRenderedAsAbsent() {
            final ScriptedStep step = stepDelivering();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.abend(AbstractCobolStep.IoOperation.OPEN, RESOURCE_NAME,
                            null))
                    .withMessageContaining("(none)")
                    .withMessageNotContaining("null");
        }

        @Test
        @DisplayName("the terminal path requires an operation kind and a named file")
        void theTerminalPathRequiresAnOperationAndAName() {
            final ScriptedStep step = stepDelivering();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.abend(null, RESOURCE_NAME, STATUS_SUCCESS))
                    .withMessageContaining("operation");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.abend(AbstractCobolStep.IoOperation.READ, null,
                            STATUS_SUCCESS))
                    .withMessageContaining("resource");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> step.abend(AbstractCobolStep.IoOperation.READ, "  ",
                            STATUS_SUCCESS))
                    .withMessageContaining("must name the file");
        }

        @Test
        @DisplayName("the lifecycle is timed on the abending path too, under a distinguishing tag")
        void theLifecycleIsTimedOnTheAbendingPath() {
            final ScriptedStep step = stepDelivering();
            step.openStatus = STATUS_PERMANENT_ERROR;

            assertThatExceptionOfType(AbendException.class).isThrownBy(step::run);

            assertThat(meterRegistry.find("carddemo.batch.cobol.step")
                    .tag("outcome", "ABENDED").timer())
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("the sentinel: an operation that reports no status at all is terminal")
    final class IncompleteOperationSentinel {

        @Test
        @DisplayName("an open that fails outright abends with the status reported as absent")
        void anOpenThatFailsOutrightAbends() {
            final ScriptedStep step = stepDelivering();
            step.openFailure = new IllegalStateException("device not ready");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining("(none)")
                    .withMessageContaining(ORACLE_GERUND_OPEN)
                    .havingCause()
                    .withMessage("device not ready");
        }

        @Test
        @DisplayName("a read that fails outright abends and chains the underlying failure")
        void aReadThatFailsOutrightAbends() {
            final ScriptedStep step = stepDelivering();
            step.readFailure = new IllegalStateException("track read error");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining(ORACLE_GERUND_READ)
                    .havingCause()
                    .withMessage("track read error");
        }

        @Test
        @DisplayName("a checked failure is admitted, because real input and output fails in checked ways")
        void aCheckedFailureIsAdmitted() {
            final ScriptedStep step = stepDelivering();
            step.openFailure = new IOException("data set unavailable");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .havingCause()
                    .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("an abend raised further down is rethrown untouched, so it is diagnosed exactly once")
        void aNestedAbendIsRethrownUntouched() {
            final ScriptedStep step = stepDelivering();
            final AbendException alreadyDiagnosed = new AbendException(ORACLE_BATCH_ABEND_CODE,
                    PROGRAM_NAME, "INNER REASON", "INNER MESSAGE");
            step.openFailure = alreadyDiagnosed;

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(step::run)
                    .isSameAs(alreadyDiagnosed);
        }
    }

    @Nested
    @DisplayName("failure handling: resources are released once and the original failure propagates")
    final class FailureRelease {

        @Test
        @DisplayName("a failure before the close sequence releases the handles exactly once and performs "
                + "no CLOSE, because the legacy abend never reaches one")
        void aFailureBeforeCloseReleasesExactlyOnce() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD);
            step.processFailure = new IllegalStateException("posting failed");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .withMessage("posting failed");

            assertThat(step.releaseAttempts)
                    .as("the JVM outlives the failure and must release its own files")
                    .isEqualTo(1);
            assertThat(step.closeAttempts)
                    .as("9999-ABEND-PROGRAM ends in CALL 'CEE3ABD' and does not return, so the "
                            + "PERFORM of the close family is unreachable [app/cbl/CBACT01C.cbl:L83, L173]")
                    .isZero();
            assertThat(step.lifecycle).containsExactly("open", "read", "process", "release");
        }

        @Test
        @DisplayName("a failure of the close attempt is suppressed onto the original, never substituted")
        void aSecondaryCloseFailureIsSuppressed() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD);
            final IllegalStateException primary = new IllegalStateException("posting failed");
            step.processFailure = primary;
            step.releaseFailure = new IllegalStateException("release failed too");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(step::run)
                    .isSameAs(primary)
                    .satisfies(thrown -> assertThat(thrown.getSuppressed())
                            .as("the secondary failure is retained, not discarded")
                            .hasSize(1));
        }

        @Test
        @DisplayName("a failure inside the close sequence is not followed by a second close attempt")
        void aFailureInsideCloseIsNotRetried() {
            final ScriptedStep step = stepDelivering();
            step.closeFailure = new IllegalStateException("close failed");

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(step::run);

            assertThat(step.closeAttempts).as("the close sequence had already been entered")
                    .isEqualTo(1);
            assertThat(step.releaseAttempts)
                    .as("the release that follows any failure does not re-enter the close family")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a completing run closes exactly once and suppresses nothing")
        void aCompletingRunClosesExactlyOnce() {
            final ScriptedStep step = stepDelivering(FIRST_RECORD);

            step.run();

            assertThat(step.closeAttempts).isEqualTo(1);
            assertThat(step.releaseAttempts)
                    .as("the release hook belongs to the failure path alone")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("IoResult: the raw status always travels with the record")
    final class IoResultContract {

        @Test
        @DisplayName("a success pairs the raw status with the delivered record")
        void aSuccessPairsStatusWithRecord() {
            final AbstractCobolStep.IoResult<String> result =
                    AbstractCobolStep.IoResult.of(STATUS_SUCCESS, FIRST_RECORD);

            assertThat(result.rawStatus()).isEqualTo(STATUS_SUCCESS);
            assertThat(result.record()).isEqualTo(FIRST_RECORD);
        }

        @Test
        @DisplayName("the at-end result carries the at-end code and no record")
        void theAtEndResultCarriesTheCodeAndNoRecord() {
            final AbstractCobolStep.IoResult<String> result = AbstractCobolStep.IoResult.endOfFile();

            assertThat(result.rawStatus()).isEqualTo(STATUS_END_OF_FILE);
            assertThat(result.rawStatus())
                    .as("taken from the enumeration, so the raw vocabulary stays single-sourced")
                    .isEqualTo(FileStatus.END_OF_FILE.getCode());
            assertThat(result.record()).isNull();
        }

        @Test
        @DisplayName("a status is always reported, so a result without one is refused")
        void aStatusIsAlwaysReported() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.IoResult<String>(null, FIRST_RECORD))
                    .withMessageContaining("rawStatus");
        }

        @Test
        @DisplayName("the success factory refuses a missing record")
        void theSuccessFactoryRefusesAMissingRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AbstractCobolStep.IoResult.of(STATUS_SUCCESS, null))
                    .withMessageContaining("record");
        }

        @Test
        @DisplayName("a read reporting success without a record is refused rather than dereferenced")
        void aReadReportingSuccessWithoutARecordIsRefused() {
            final ScriptedStep step = new ScriptedStep(PROGRAM_NAME, meterRegistry, fixedClock);
            step.enqueueRead(new AbstractCobolStep.IoResult<>(STATUS_SUCCESS, null));

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(step::run)
                    .withMessageContaining("without delivering a record");
        }

        @Test
        @DisplayName("the result is a value type")
        void theResultIsAValueType() {
            final AbstractCobolStep.IoResult<String> first =
                    AbstractCobolStep.IoResult.of(STATUS_SUCCESS, FIRST_RECORD);
            final AbstractCobolStep.IoResult<String> second =
                    AbstractCobolStep.IoResult.of(STATUS_SUCCESS, FIRST_RECORD);

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.toString()).contains(STATUS_SUCCESS);
        }
    }

    @Nested
    @DisplayName("ExecutionSummary: what one execution observed, owned entirely by the template")
    final class ExecutionSummaryContract {

        @Test
        @DisplayName("a summary carries its four components verbatim")
        void aSummaryCarriesItsComponents() {
            final AbstractCobolStep.ExecutionSummary summary = new AbstractCobolStep.ExecutionSummary(
                    PROGRAM_NAME, 300L, PINNED_TIMESTAMP_IMAGE, PINNED_TIMESTAMP_IMAGE);

            assertThat(summary.programName()).isEqualTo(PROGRAM_NAME);
            assertThat(summary.recordsRead()).isEqualTo(300L);
            assertThat(summary.startedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(summary.completedAt()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
        }

        @Test
        @DisplayName("a zero count is legitimate: an empty file is not an error")
        void aZeroCountIsLegitimate() {
            assertThat(new AbstractCobolStep.ExecutionSummary(PROGRAM_NAME, 0L,
                    PINNED_TIMESTAMP_IMAGE, PINNED_TIMESTAMP_IMAGE).recordsRead()).isZero();
        }

        @Test
        @DisplayName("a negative count is refused")
        void aNegativeCountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(PROGRAM_NAME, -1L,
                            PINNED_TIMESTAMP_IMAGE, PINNED_TIMESTAMP_IMAGE))
                    .withMessageContaining("recordsRead");
        }

        @Test
        @DisplayName("no string component may be absent")
        void noStringComponentMayBeAbsent() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(null, 0L,
                            PINNED_TIMESTAMP_IMAGE, PINNED_TIMESTAMP_IMAGE))
                    .withMessageContaining("programName");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(PROGRAM_NAME, 0L, null,
                            PINNED_TIMESTAMP_IMAGE))
                    .withMessageContaining("startedAt");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(PROGRAM_NAME, 0L,
                            PINNED_TIMESTAMP_IMAGE, null))
                    .withMessageContaining("completedAt");
        }

        @Test
        @DisplayName("the summary is a value type")
        void theSummaryIsAValueType() {
            final AbstractCobolStep.ExecutionSummary first = stepDelivering(FIRST_RECORD).run();
            final AbstractCobolStep.ExecutionSummary second = stepDelivering(FIRST_RECORD).run();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.toString()).contains(PROGRAM_NAME);
        }
    }

    @Nested
    @DisplayName("the batch timestamp image: YYYY-MM-DD-hh.mm.ss.hh0000, twenty-six bytes")
    final class BatchTimestampImage {

        @Test
        @DisplayName("the image takes the batch shape, with a hyphen where the online form has a space")
        void theImageTakesTheBatchShape() {
            final String image = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 6, 14, 23, 41, 870_000_000));

            assertThat(image).isEqualTo("2022-07-06-14.23.41.870000");
            assertThat(image.charAt(10)).as("the third separator is a hyphen, not a space")
                    .isEqualTo('-');
            assertThat(image).doesNotContain(":");
        }

        @Test
        @DisplayName("the image is exactly twenty-six bytes when encoded")
        void theImageIsExactlyTwentySixBytes() {
            final String image = AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 6, 14, 23, 41, 870_000_000));

            assertThat(image.getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ORACLE_BATCH_TIMESTAMP_LENGTH);
            assertThat(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH)
                    .isEqualTo(ORACLE_BATCH_TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("the tail is the four-character literal the redefinition fixes")
        void theTailIsTheFourCharacterLiteral() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 6, 14, 23, 41, 0)))
                    .endsWith(ORACLE_BATCH_TIMESTAMP_TAIL);
        }

        @Test
        @DisplayName("sub-hundredth precision is truncated, never rounded")
        void subHundredthPrecisionIsTruncated() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 6, 14, 23, 41, 879_999_999)))
                    .as("no arithmetic anywhere in the estate specifies rounding")
                    .isEqualTo("2022-07-06-14.23.41.870000");
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 6, 14, 23, 41, 9_999_999)))
                    .isEqualTo("2022-07-06-14.23.41.000000");
        }

        @Test
        @DisplayName("every field is zero padded to the width its redefinition declares")
        void everyFieldIsZeroPadded() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(LocalDateTime.of(7, 1, 2, 3, 4, 5, 0)))
                    .isEqualTo("0007-01-02-03.04.05.000000");
        }

        @Test
        @DisplayName("a year the four-byte field cannot hold is refused")
        void anUnrepresentableYearIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(
                            LocalDateTime.of(10_000, 1, 1, 0, 0, 0)))
                    .withMessageContaining("four-byte year field");
        }

        @Test
        @DisplayName("the boundary years the field can hold are accepted")
        void theBoundaryYearsAreAccepted() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(9999, 12, 31, 23, 59, 59, 990_000_000)))
                    .isEqualTo("9999-12-31-23.59.59.990000");
        }

        @Test
        @DisplayName("an absent moment is refused")
        void anAbsentMomentIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(null))
                    .withMessageContaining("moment");
        }

        @Test
        @DisplayName("the instance accessor reads the injected clock, so a test can pin the instant")
        void theInstanceAccessorReadsTheInjectedClock() {
            assertThat(stepDelivering().timestamp()).isEqualTo(PINNED_TIMESTAMP_IMAGE);
        }

        @Test
        @DisplayName("a clock in another zone still produces the image at the legacy width, because "
                + "the zone belongs to the supplied clock and never to the host")
        void aClockInAnotherZoneStillProducesAWellFormedImage() {
            // The time source is always supplied: this class has one constructor and its clock is
            // mandatory. A step that defaulted its own clock would read the host's regional
            // settings instead of the single UTC clock the module publishes, so the same run would
            // emit different bytes on two machines and no test could pin the instant. Handing in a
            // clock pinned to the same instant in another zone proves the shape of the image is a
            // property of the format rather than of the zone, while the reading itself moves.
            final ScriptedStep step = new ScriptedStep(PROGRAM_NAME, meterRegistry,
                    Clock.fixed(PINNED_INSTANT, ZoneId.of("Asia/Tokyo")));

            assertThat(step.timestamp().getBytes(StandardCharsets.US_ASCII))
                    .hasSize(ORACLE_BATCH_TIMESTAMP_LENGTH);
            assertThat(step.timestamp()).endsWith(ORACLE_BATCH_TIMESTAMP_TAIL);
            assertThat(step.timestamp())
                    .as("the civil-time reading follows the clock's zone, so it is not the UTC image")
                    .isNotEqualTo(PINNED_TIMESTAMP_IMAGE);
            assertThat(step.run().recordsRead())
                    .as("a step whose clock names another zone runs the same lifecycle")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("guarded helpers refuse an unnamed file, so no diagnostic can omit what failed")
    final class ResourceNamingContract {

        @Test
        @DisplayName("a write refuses an absent or blank file name")
        void aWriteRefusesAnUnnamedFile() {
            final ScriptedStep step = stepDelivering();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> step.write(null, STATUS_SUCCESS))
                    .withMessageContaining("resource");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> step.write("   ", STATUS_SUCCESS))
                    .withMessageContaining("must name the file");
        }

        @Test
        @DisplayName("a named file is reported in the diagnostic verbatim")
        void aNamedFileIsReportedVerbatim() {
            final ScriptedStep step = stepDelivering();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> step.write(OTHER_RESOURCE_NAME, STATUS_OUTSIDE_VOCABULARY))
                    .withMessageContaining(OTHER_RESOURCE_NAME);
        }
    }
}
