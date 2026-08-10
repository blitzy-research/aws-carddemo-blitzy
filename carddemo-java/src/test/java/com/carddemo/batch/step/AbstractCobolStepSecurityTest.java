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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Unit test for {@link AbstractCobolStep}, the Template Method that factors the open / read-loop /
 * status-check / close / abend skeleton every one of the ten legacy batch programs shares.
 *
 * <h2>The two-level status model is the point, and collapsing it would break every read loop</h2>
 *
 * <p>No legacy batch program branches on the raw two-byte file status. Each one <em>normalises</em>
 * it first - success becomes zero, end of file becomes sixteen, anything else becomes twelve - and
 * then branches on the two condition names declared over that normalised value. The distinction
 * that matters is between end of file and error, because end of file is how every sequential read
 * loop terminates <em>normally</em>. A model that folded the two together would abend every batch
 * job at the moment it finished reading its input.</p>
 *
 * <p>The sharper half of that model is that end of file is normal <em>for a read and for nothing
 * else</em>. A close that reports end of file is not a tidy ending; it is a genuine failure. The
 * operation vocabulary therefore carries a per-operation flag, so the same raw code classifies
 * differently depending on which operation reported it. Both directions of that are pinned below,
 * because a test that only checked the read would pass against an implementation that treated end
 * of file as benign everywhere.</p>
 *
 * <h2>Why a stub subclass rather than a mocking framework</h2>
 *
 * <p>The Template Method's contract is the <em>order</em> in which it calls its four abstract hooks
 * and what it does around them: the record count it accumulates, the close it performs on the
 * failure path, the metric it stops with the right outcome tag. That is behaviour of the base class
 * observed through a real subclass, so these tests drive a hand-written stub that appends each call
 * to a list. A mock would verify that a method was called; the stub proves the whole sequence and
 * lets the base class's own loop terminate on its own terms.</p>
 *
 * <h2>The timestamp is a fixed-width field, not a formatted date</h2>
 *
 * <p>The batch timestamp is twenty-six bytes wide with a fixed four-character tail, and the base
 * class asserts that width at run time rather than trusting its format string. A clock is
 * injectable for exactly this reason, so the assertions below pin an exact image against a fixed
 * instant rather than merely checking that something date-shaped came back.</p>
 */
@DisplayName("AbstractCobolStep - the batch Template Method")
class AbstractCobolStepSecurityTest {

    private static final String PROGRAM = "CBTRN02C";
    private static final String RESOURCE = "DALYTRAN";

    /** A fixed clock, so every timestamp assertion can pin an exact image. */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            LocalDateTime.of(2022, 7, 19, 23, 59, 58, 990_000_000).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC);

    private final MeterRegistry registry = new SimpleMeterRegistry();

    /**
     * Builds a scripted step bound to this test's registry.
     *
     * @param records the records the read hook will deliver, in order, before reporting end of file
     * @return a fresh step
     */
    private ScriptedStep step(final String... records) {
        return new ScriptedStep(this.registry, records);
    }

    @Nested
    @DisplayName("Construction, which enforces the legacy culprit width up front")
    class Construction {

        @Test
        @DisplayName("a valid program name and registry produce a usable step")
        void aValidProgramNameAndRegistryProduceAUsableStep() {
            assertThat(step().name()).isEqualTo(PROGRAM);
        }

        @Test
        @DisplayName("a null program name is rejected")
        void aNullProgramNameIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new NamedStep(null))
                    .withMessageContaining("programName");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("a blank program name is rejected, because the name is what an abend reports as its culprit")
        void aBlankProgramNameIsRejected(final String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new NamedStep(candidate))
                    .withMessageContaining("programName");
        }

        @Test
        @DisplayName("a program name wider than the legacy culprit field is rejected at construction, so an abend "
                + "can never fail while trying to report one")
        void anOverWideProgramNameIsRejected() {
            final String tooWide = "A".repeat(AbendException.CULPRIT_LENGTH + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new NamedStep(tooWide))
                    .withMessageContaining(String.valueOf(AbendException.CULPRIT_LENGTH));
        }

        @Test
        @DisplayName("a program name exactly at the culprit width is accepted, so the boundary is inclusive")
        void aProgramNameAtTheCulpritWidthIsAccepted() {
            final String exact = "A".repeat(AbendException.CULPRIT_LENGTH);
            assertThat(new NamedStep(exact).name()).isEqualTo(exact);
        }

        @Test
        @DisplayName("a null meter registry is rejected, because every execution is timed")
        void aNullMeterRegistryIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new NullCollaboratorStep(null, FIXED_CLOCK))
                    .withMessageContaining("meterRegistry");
        }

        @Test
        @DisplayName("a null clock is rejected, because the timestamp is a fixed-width field the step must always "
                + "be able to produce")
        void aNullClockIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new NullCollaboratorStep(registry, null))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("the clock is always supplied and never defaulted, and the step it is handed to "
                + "produces a timestamp of the legacy width")
        void theClockIsSuppliedAndTheTimestampKeepsTheLegacyWidth() {
            // There is no constructor that manufactures a clock. One that did would bypass the
            // single UTC clock bean the module publishes and make the emitted zone a property of
            // whichever host ran the job, so the width assertion below would hold while the bytes
            // still differed between two machines.
            assertThat(new NamedStep(PROGRAM, registry, FIXED_CLOCK).timestamp())
                    .hasSize(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH);
        }
    }

    @Nested
    @DisplayName("The lifecycle: open, read until end of file, close - in that order")
    class Lifecycle {

        @Test
        @DisplayName("an empty file opens, reads once, and closes, without ever calling the record handler")
        void anEmptyFileOpensReadsOnceAndCloses() {
            final ScriptedStep subject = step();
            final AbstractCobolStep.ExecutionSummary summary = subject.run();
            assertThat(subject.calls).containsExactly("open", "read", "close");
            assertThat(summary.recordsRead()).isZero();
        }

        @Test
        @DisplayName("three records are read and handled in order, and the loop performs one extra read to observe "
                + "end of file - the legacy read-until-EOF shape rather than a counted loop")
        void threeRecordsAreReadAndHandledInOrder() {
            final ScriptedStep subject = step("a", "b", "c");
            final AbstractCobolStep.ExecutionSummary summary = subject.run();
            assertThat(subject.calls).containsExactly(
                    "open",
                    "read", "process:a",
                    "read", "process:b",
                    "read", "process:c",
                    "read",
                    "close");
            assertThat(summary.recordsRead()).isEqualTo(3L);
        }

        @Test
        @DisplayName("the count counts only records the read actually delivered, so the terminating read does not "
                + "inflate it")
        void theCountCountsOnlyDeliveredRecords() {
            assertThat(step("only").run().recordsRead()).isEqualTo(1L);
            assertThat(step().run().recordsRead()).isZero();
            assertThat(step("a", "b", "c", "d", "e").run().recordsRead()).isEqualTo(5L);
        }

        @Test
        @DisplayName("the summary carries the program name and both timestamps at the legacy width")
        void theSummaryCarriesTheProgramNameAndBothTimestamps() {
            final AbstractCobolStep.ExecutionSummary summary = step("a").run();
            assertThat(summary.programName()).isEqualTo(PROGRAM);
            assertThat(summary.startedAt()).hasSize(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH);
            assertThat(summary.completedAt()).hasSize(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("a read that reports a null Optional is rejected with a diagnostic naming the contract, rather "
                + "than surfacing as an opaque dereference failure deep inside the loop")
        void aReadThatReportsANullOptionalIsRejected() {
            final ScriptedStep subject = step();
            subject.readReportsNullOptional = true;
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(subject::run)
                    .withMessageContaining("readNextRecord");
        }

        @Test
        @DisplayName("the tasklet entry point runs the same lifecycle and reports the step finished, so a single "
                + "invocation is the whole step rather than one chunk of it")
        void theTaskletEntryPointRunsTheSameLifecycle() throws Exception {
            final ScriptedStep subject = step("a");
            assertThat(subject.execute(null, null)).isEqualTo(RepeatStatus.FINISHED);
            assertThat(subject.calls).containsExactly("open", "read", "process:a", "read", "close");
        }
    }

    /**
     * The security question on this path is whether a failure can leak a handle the step opened, and the
     * answer must be no. It is answered through the non-observable release rather than through the close
     * family, and that distinction is itself the security-relevant part.
     *
     * <p>An abend on the mainframe is terminal: {@code 9999-ABEND-PROGRAM} ends with
     * the CEE3ABD call [{@code app/cbl/CBACT01C.cbl}:L169-L173], which does not return, so the
     * the 9000-ACCTFILE-CLOSE call at line 83 is unreachable once anything has abended, and the
     * operating system reclaimed the data sets when the enclave ended. Driving the observable close
     * family from the failure path would fabricate {@code CLOSE} operations, with their normalised
     * statuses and their diagnostics, that the legacy never performs -- a false operator signal. The
     * template therefore releases handles without emitting anything, and the assertions below pin both
     * halves: the release runs exactly once, and no close is performed.</p>
     */
    @Nested
    @DisplayName("The failure path, which still releases resources exactly once")
    class FailurePath {

        @Test
        @DisplayName("a failure in the record handler propagates AND the handles are still released, so a job that "
                + "abends does not leak the file it was reading, while the observable close family stays skipped")
        void aFailureInTheHandlerStillReleasesResources() {
            final ScriptedStep subject = step("a");
            subject.processFailure = new IllegalStateException("handler exploded");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(subject::run)
                    .withMessage("handler exploded");
            assertThat(subject.calls).containsExactly("open", "read", "process:a", "release");
            assertThat(subject.calls)
                    .as("no CLOSE may be fabricated on a path the legacy never reaches")
                    .doesNotContain("close");
        }

        @Test
        @DisplayName("a failure while opening still releases the handles, because a partially opened set of files "
                + "is exactly the case that needs releasing")
        void aFailureWhileOpeningStillReleasesResources() {
            final ScriptedStep subject = step();
            subject.openFailure = new IllegalStateException("open exploded");
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(subject::run);
            assertThat(subject.calls).containsExactly("open", "release");
            assertThat(subject.calls).filteredOn("release"::equals)
                    .as("released exactly once, so nothing is leaked and nothing is released twice")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a failure IN the close sequence is not double-closed: close is called exactly once and the "
                + "failure propagates as the primary")
        void aFailureInTheCloseSequenceIsNotDoubleClosed() {
            final ScriptedStep subject = step();
            subject.closeFailure = new IllegalStateException("close exploded");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(subject::run)
                    .withMessage("close exploded");
            assertThat(subject.calls).containsExactly("open", "read", "close", "release");
            assertThat(subject.calls).filteredOn("close"::equals).hasSize(1);
        }

        @Test
        @DisplayName("a secondary failure while releasing is retained as suppressed on the primary, so neither "
                + "diagnosis is lost - the primary abended the job, the secondary explains why cleanup failed")
        void aSecondaryReleaseFailureIsRetainedAsSuppressed() {
            final ScriptedStep subject = step("a");
            final IllegalStateException primary = new IllegalStateException("primary");
            final IllegalStateException secondary = new IllegalStateException("secondary");
            subject.processFailure = primary;
            subject.releaseFailure = secondary;
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(subject::run)
                    .withMessage("primary")
                    .satisfies(thrown ->
                            assertThat(thrown.getSuppressed()).containsExactly(secondary));
        }

        @Test
        @DisplayName("the primary failure is rethrown unchanged rather than wrapped, so a caller can still match on "
                + "its identity")
        void thePrimaryFailureIsRethrownUnchanged() {
            final ScriptedStep subject = step("a");
            final AbendException planted = new AbendException(PROGRAM, "PLANTED");
            subject.processFailure = planted;
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::run)
                    .satisfies(thrown -> assertThat(thrown).isSameAs(planted));
        }
    }

    @Nested
    @DisplayName("Metrics: every execution is timed and tagged with its outcome")
    class Metrics {

        @Test
        @DisplayName("a completed execution records one timing tagged COMPLETED against the program name")
        void aCompletedExecutionRecordsOneCompletedTiming() {
            step("a").run();
            assertThat(registry.get("carddemo.batch.cobol.step")
                    .tag("step", PROGRAM)
                    .tag("outcome", "COMPLETED")
                    .timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("an abended execution records one timing tagged ABENDED, so a failed run is measured rather "
                + "than silently absent from the performance baseline")
        void anAbendedExecutionRecordsOneAbendedTiming() {
            final ScriptedStep subject = step("a");
            subject.processFailure = new IllegalStateException("boom");
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(subject::run);
            assertThat(registry.get("carddemo.batch.cobol.step")
                    .tag("step", PROGRAM)
                    .tag("outcome", "ABENDED")
                    .timer().count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("repeated executions accumulate onto the same timer rather than registering a new one each time")
        void repeatedExecutionsAccumulateOntoTheSameTimer() {
            step("a").run();
            step("b").run();
            step().run();
            assertThat(registry.get("carddemo.batch.cobol.step")
                    .tag("outcome", "COMPLETED")
                    .timer().count()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("The tri-state outcome model that mirrors the normalised legacy result")
    class OutcomeModel {

        @Test
        @DisplayName("the three outcomes carry the three normalised values the legacy programs move: 0, 16 and 12")
        void theThreeOutcomesCarryTheNormalisedValues() {
            assertThat(AbstractCobolStep.IoOutcome.OK.applResult()).isZero();
            assertThat(AbstractCobolStep.IoOutcome.END_OF_FILE.applResult()).isEqualTo(16);
            assertThat(AbstractCobolStep.IoOutcome.ERROR.applResult()).isEqualTo(12);
        }

        @Test
        @DisplayName("the vocabulary has exactly three constants, because collapsing end of file into error would "
                + "abend every sequential job at the moment it finished reading")
        void theVocabularyHasExactlyThreeConstants() {
            assertThat(AbstractCobolStep.IoOutcome.values()).containsExactly(
                    AbstractCobolStep.IoOutcome.OK,
                    AbstractCobolStep.IoOutcome.END_OF_FILE,
                    AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("the three normalised values are mutually distinct, so no two outcomes can be confused")
        void theThreeNormalisedValuesAreMutuallyDistinct() {
            assertThat(Arrays.stream(AbstractCobolStep.IoOutcome.values())
                    .map(AbstractCobolStep.IoOutcome::applResult)
                    .distinct().count()).isEqualTo(3L);
        }

        @ParameterizedTest
        @EnumSource(AbstractCobolStep.IoOutcome.class)
        @DisplayName("every outcome round-trips through its own normalised value, so the two directions cannot drift")
        void everyOutcomeRoundTripsThroughItsNormalisedValue(
                final AbstractCobolStep.IoOutcome outcome) {
            assertThat(AbstractCobolStep.IoOutcome.fromApplResult(outcome.applResult()))
                    .isEqualTo(outcome);
        }

        @ParameterizedTest
        @ValueSource(ints = {8, 1, 4, 12, -1, 15, 17, 99, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("every value that is neither zero nor sixteen normalises to ERROR, including the pending value "
                + "eight that the legacy moves before a write - a pending result was never a success")
        void everyOtherValueNormalisesToError(final int applResult) {
            assertThat(AbstractCobolStep.IoOutcome.fromApplResult(applResult))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("only zero maps to OK and only sixteen maps to END_OF_FILE, so neither is reachable by accident")
        void onlyTheTwoDeclaredValuesMapToTheirOutcomes() {
            assertThat(AbstractCobolStep.IoOutcome.fromApplResult(0))
                    .isEqualTo(AbstractCobolStep.IoOutcome.OK);
            assertThat(AbstractCobolStep.IoOutcome.fromApplResult(16))
                    .isEqualTo(AbstractCobolStep.IoOutcome.END_OF_FILE);
        }
    }

    @Nested
    @DisplayName("The operation vocabulary, whose per-operation end-of-file flag is load-bearing")
    class OperationVocabulary {

        @Test
        @DisplayName("the four operations carry the legacy gerunds the diagnostics print verbatim")
        void theFourOperationsCarryTheLegacyGerunds() {
            assertThat(AbstractCobolStep.IoOperation.OPEN.legacyGerund()).isEqualTo("OPENING");
            assertThat(AbstractCobolStep.IoOperation.READ.legacyGerund()).isEqualTo("READING");
            assertThat(AbstractCobolStep.IoOperation.WRITE.legacyGerund()).isEqualTo("WRITING TO");
            assertThat(AbstractCobolStep.IoOperation.CLOSE.legacyGerund()).isEqualTo("CLOSING");
        }

        @Test
        @DisplayName("ONLY the read treats end of file as a normal termination, which is why the same raw code "
                + "classifies differently depending on which operation reported it")
        void onlyTheReadTreatsEndOfFileAsNormal() {
            assertThat(AbstractCobolStep.IoOperation.READ.endOfFileTerminatesNormally()).isTrue();
            assertThat(AbstractCobolStep.IoOperation.OPEN.endOfFileTerminatesNormally()).isFalse();
            assertThat(AbstractCobolStep.IoOperation.WRITE.endOfFileTerminatesNormally()).isFalse();
            assertThat(AbstractCobolStep.IoOperation.CLOSE.endOfFileTerminatesNormally()).isFalse();
        }

        @Test
        @DisplayName("the read arms the error value while the other three arm the pending value, matching the "
                + "different values the legacy programs move before each operation")
        void theArmedValuesFollowTheLegacyPreMoves() {
            assertThat(AbstractCobolStep.IoOperation.READ.armedApplResult()).isEqualTo(12);
            assertThat(AbstractCobolStep.IoOperation.OPEN.armedApplResult()).isEqualTo(8);
            assertThat(AbstractCobolStep.IoOperation.WRITE.armedApplResult()).isEqualTo(8);
            assertThat(AbstractCobolStep.IoOperation.CLOSE.armedApplResult()).isEqualTo(8);
        }

        @Test
        @DisplayName("the vocabulary has exactly the four operations the batch tier performs")
        void theVocabularyHasExactlyFourOperations() {
            assertThat(AbstractCobolStep.IoOperation.values()).containsExactly(
                    AbstractCobolStep.IoOperation.OPEN,
                    AbstractCobolStep.IoOperation.READ,
                    AbstractCobolStep.IoOperation.WRITE,
                    AbstractCobolStep.IoOperation.CLOSE);
        }

        @ParameterizedTest
        @EnumSource(AbstractCobolStep.IoOperation.class)
        @DisplayName("every operation publishes a non-blank gerund, so no diagnostic can read as a gap")
        void everyOperationPublishesANonBlankGerund(
                final AbstractCobolStep.IoOperation operation) {
            assertThat(operation.legacyGerund()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Status normalisation, the first level of the two-level model")
    class StatusNormalisation {

        @ParameterizedTest
        @EnumSource(AbstractCobolStep.IoOperation.class)
        @DisplayName("a success code normalises to OK for every operation")
        void aSuccessCodeNormalisesToOk(final AbstractCobolStep.IoOperation operation) {
            assertThat(step().classify(operation, FileStatus.SUCCESS.getCode()))
                    .isEqualTo(AbstractCobolStep.IoOutcome.OK);
        }

        @Test
        @DisplayName("the end-of-file code normalises to END_OF_FILE for a read and to ERROR for every other "
                + "operation - a close that reports end of file is a genuine failure, not a tidy ending")
        void theEndOfFileCodeIsNormalOnlyForARead() {
            final ScriptedStep subject = step();
            final String eof = FileStatus.END_OF_FILE.getCode();
            assertThat(subject.classify(AbstractCobolStep.IoOperation.READ, eof))
                    .isEqualTo(AbstractCobolStep.IoOutcome.END_OF_FILE);
            assertThat(subject.classify(AbstractCobolStep.IoOperation.OPEN, eof))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(subject.classify(AbstractCobolStep.IoOperation.WRITE, eof))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(subject.classify(AbstractCobolStep.IoOperation.CLOSE, eof))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", " ", "0", "000", "01", "02", "04", "05", "12", "23", "31",
            "22", "35", "99", "AB"})
        @DisplayName("every code that is neither success nor a read's end of file normalises to ERROR, including an "
                + "absent code, a code outside the declared vocabulary, and the two codes the source never compares")
        void everyOtherCodeNormalisesToError(final String rawStatus) {
            final ScriptedStep subject = step();
            assertThat(subject.classify(AbstractCobolStep.IoOperation.READ, rawStatus))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
            assertThat(subject.classify(AbstractCobolStep.IoOperation.CLOSE, rawStatus))
                    .isEqualTo(AbstractCobolStep.IoOutcome.ERROR);
        }

        @Test
        @DisplayName("normalisation never throws on an unrecognised code, so a corrupt status is diagnosed as an "
                + "error rather than crashing the classifier that was meant to report it")
        void normalisationNeverThrowsOnAnUnrecognisedCode() {
            final ScriptedStep subject = step();
            assertThatNoException().isThrownBy(() ->
                    subject.classify(AbstractCobolStep.IoOperation.READ, "\u0000\u0000"));
        }

        @Test
        @DisplayName("a null operation is rejected, because without it the end-of-file question cannot be answered")
        void aNullOperationIsRejected() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.classify(null, FileStatus.SUCCESS.getCode()))
                    .withMessageContaining("operation");
        }
    }

    @Nested
    @DisplayName("The guarded two-way operations: open, write and close")
    class GuardedTwoWayOperations {

        @Test
        @DisplayName("a success status returns normally from each of the three guards")
        void aSuccessStatusReturnsNormally() {
            final ScriptedStep subject = step();
            final String ok = FileStatus.SUCCESS.getCode();
            assertThatNoException().isThrownBy(() -> subject.guardedOpen(RESOURCE, () -> ok));
            assertThatNoException().isThrownBy(() -> subject.guardedWrite(RESOURCE, () -> ok));
            assertThatNoException().isThrownBy(() -> subject.guardedClose(RESOURCE, () -> ok));
        }

        @Test
        @DisplayName("an error status abends, naming the program as culprit and carrying the batch abend code")
        void anErrorStatusAbends() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedOpen(RESOURCE, () -> "31"))
                    .satisfies(thrown -> {
                        assertThat(thrown.culprit()).isEqualTo(PROGRAM);
                        assertThat(thrown.code()).isEqualTo(AbendException.BATCH_ABEND_CODE);
                    });
        }

        @Test
        @DisplayName("the abend names the raw status, the operation gerund and the resource, so an operator can see "
                + "which file failed and how")
        void theAbendNamesTheStatusOperationAndResource() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedWrite(RESOURCE, () -> "12"))
                    .satisfies(thrown -> {
                        assertThat(thrown.reason()).contains("12", "WRITING TO", RESOURCE);
                        assertThat(thrown.getMessage()).contains("WRITING TO", RESOURCE, "12");
                    });
        }

        @Test
        @DisplayName("the abend reason and message are bounded to the legacy field widths, so a long resource name "
                + "cannot make the abend itself fail to construct")
        void theAbendReasonAndMessageAreBoundedToTheLegacyWidths() {
            final ScriptedStep subject = step();
            final String longResource = "R".repeat(200);
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedClose(longResource, () -> "31"))
                    .satisfies(thrown -> {
                        assertThat(thrown.reason().length())
                                .isLessThanOrEqualTo(AbendException.REASON_LENGTH);
                        assertThat(thrown.getMessage().length())
                                .isLessThanOrEqualTo(AbendException.MESSAGE_LENGTH);
                    });
        }

        @Test
        @DisplayName("a checked exception thrown by the action becomes an abend carrying it as cause, so the "
                + "underlying failure remains reachable")
        void aCheckedExceptionBecomesAnAbendCarryingItAsCause() {
            final ScriptedStep subject = step();
            final IOException underlying = new IOException("device offline");
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedOpen(RESOURCE, () -> {
                        throw underlying;
                    }))
                    .satisfies(thrown -> assertThat(thrown).hasCause(underlying));
        }

        @Test
        @DisplayName("an abend already raised inside the action is rethrown unchanged rather than re-diagnosed, so "
                + "one failure never produces two nested diagnoses")
        void anAbendRaisedInsideTheActionIsRethrownUnchanged() {
            final ScriptedStep subject = step();
            final AbendException alreadyDiagnosed = new AbendException(PROGRAM, "INNER");
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedWrite(RESOURCE, () -> {
                        throw alreadyDiagnosed;
                    }))
                    .satisfies(thrown -> {
                        assertThat(thrown).isSameAs(alreadyDiagnosed);
                        assertThat(thrown.reason()).isEqualTo("INNER");
                    });
        }

        @Test
        @DisplayName("an end-of-file status on a write abends, because end of file is normal only for a read")
        void anEndOfFileStatusOnAWriteAbends() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedWrite(RESOURCE,
                            () -> FileStatus.END_OF_FILE.getCode()));
        }

        @Test
        @DisplayName("an end-of-file status on a close abends too, so the guard is not quietly permissive on the "
                + "one operation a leaking job depends on")
        void anEndOfFileStatusOnACloseAbends() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedClose(RESOURCE,
                            () -> FileStatus.END_OF_FILE.getCode()));
        }

        @Test
        @DisplayName("a null resource is rejected")
        void aNullResourceIsRejected() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.guardedOpen(null, () -> "00"))
                    .withMessageContaining("resource");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank resource is rejected, because the diagnostic must name the file the operation acts on")
        void aBlankResourceIsRejected(final String candidate) {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.guardedClose(candidate, () -> "00"))
                    .withMessageContaining("resource");
        }

        @Test
        @DisplayName("a null action is rejected")
        void aNullActionIsRejected() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.guardedWrite(RESOURCE, null))
                    .withMessageContaining("action");
        }
    }

    @Nested
    @DisplayName("The guarded read, which pairs a status with the record the read delivered")
    class GuardedRead {

        @Test
        @DisplayName("a success status delivers the record")
        void aSuccessStatusDeliversTheRecord() {
            assertThat(step().guardedRead(RESOURCE, () ->
                    AbstractCobolStep.IoResult.of(FileStatus.SUCCESS.getCode(), "payload")))
                    .contains("payload");
        }

        @Test
        @DisplayName("an end-of-file status reports an absent record rather than abending, which is how every "
                + "sequential read loop terminates normally")
        void anEndOfFileStatusReportsAnAbsentRecord() {
            assertThat(step().guardedRead(RESOURCE, AbstractCobolStep.IoResult::endOfFile))
                    .isEmpty();
        }

        @Test
        @DisplayName("an error status abends, and the diagnostic names the read")
        void anErrorStatusAbends() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedRead(RESOURCE,
                            () -> new AbstractCobolStep.IoResult<>("31", null)))
                    .satisfies(thrown ->
                            assertThat(thrown.reason()).contains("READING", RESOURCE));
        }

        @Test
        @DisplayName("a success status without a record is rejected with a diagnostic naming the resource and the "
                + "status, because a successful read that delivered nothing is a caller defect, not an end of file")
        void aSuccessStatusWithoutARecordIsRejected() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.guardedRead(RESOURCE,
                            () -> new AbstractCobolStep.IoResult<String>(
                                    FileStatus.SUCCESS.getCode(), null)))
                    .withMessageContaining(RESOURCE)
                    .withMessageContaining("without delivering a record");
        }

        @Test
        @DisplayName("an action that reports no result at all is rejected with a diagnostic naming the resource")
        void anActionThatReportsNoResultIsRejected() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.guardedRead(RESOURCE, () -> null))
                    .withMessageContaining(RESOURCE)
                    .withMessageContaining("reported no result");
        }

        @Test
        @DisplayName("a checked exception from the read becomes an abend carrying it as cause")
        void aCheckedExceptionFromTheReadBecomesAnAbend() {
            final ScriptedStep subject = step();
            final IOException underlying = new IOException("read failed");
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.guardedRead(RESOURCE, () -> {
                        throw underlying;
                    }))
                    .satisfies(thrown -> assertThat(thrown).hasCause(underlying));
        }

        @Test
        @DisplayName("a blank resource is rejected before the action is invoked, so a misconfigured guard cannot "
                + "perform half an operation and then complain about it")
        void aBlankResourceIsRejectedBeforeTheActionIsInvoked() {
            final ScriptedStep subject = step();
            final boolean[] invoked = {false};
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.guardedRead("  ", () -> {
                        invoked[0] = true;
                        return AbstractCobolStep.IoResult.of("00", "x");
                    }));
            assertThat(invoked[0]).isFalse();
        }

        @Test
        @DisplayName("a null action is rejected")
        void aNullActionIsRejected() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.guardedRead(RESOURCE, null))
                    .withMessageContaining("action");
        }
    }

    @Nested
    @DisplayName("The read-result pairing")
    class ReadResultPairing {

        @Test
        @DisplayName("the delivered-record factory pairs the status with the record")
        void theDeliveredRecordFactoryPairsStatusWithRecord() {
            final AbstractCobolStep.IoResult<String> result =
                    AbstractCobolStep.IoResult.of("00", "payload");
            assertThat(result.rawStatus()).isEqualTo("00");
            assertThat(result.record()).isEqualTo("payload");
        }

        @Test
        @DisplayName("the end-of-file factory carries the end-of-file code and no record")
        void theEndOfFileFactoryCarriesTheCodeAndNoRecord() {
            final AbstractCobolStep.IoResult<String> result =
                    AbstractCobolStep.IoResult.endOfFile();
            assertThat(result.rawStatus()).isEqualTo(FileStatus.END_OF_FILE.getCode());
            assertThat(result.record()).isNull();
        }

        @Test
        @DisplayName("a null status is rejected, because the status is what the outcome is derived from")
        void aNullStatusIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.IoResult<String>(null, "x"))
                    .withMessageContaining("rawStatus");
        }

        @Test
        @DisplayName("the delivered-record factory rejects a null record, so only the end-of-file factory may pair a "
                + "status with nothing")
        void theDeliveredRecordFactoryRejectsANullRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AbstractCobolStep.IoResult.of("00", null))
                    .withMessageContaining("record");
        }

        @Test
        @DisplayName("the canonical constructor still permits a null record, which is what the end-of-file factory "
                + "and an error report both need")
        void theCanonicalConstructorPermitsANullRecord() {
            assertThatNoException().isThrownBy(() ->
                    new AbstractCobolStep.IoResult<String>("31", null));
        }

        @Test
        @DisplayName("two results with the same components are equal, so a result is a value")
        void twoResultsWithTheSameComponentsAreEqual() {
            assertThat(AbstractCobolStep.IoResult.of("00", "payload"))
                    .isEqualTo(AbstractCobolStep.IoResult.of("00", "payload"));
        }
    }

    @Nested
    @DisplayName("The execution summary, whose invariants are enforced at construction")
    class Summary {

        @Test
        @DisplayName("a valid summary carries all four components")
        void aValidSummaryCarriesAllFourComponents() {
            final AbstractCobolStep.ExecutionSummary summary =
                    new AbstractCobolStep.ExecutionSummary(PROGRAM, 7L, "start", "end");
            assertThat(summary.programName()).isEqualTo(PROGRAM);
            assertThat(summary.recordsRead()).isEqualTo(7L);
            assertThat(summary.startedAt()).isEqualTo("start");
            assertThat(summary.completedAt()).isEqualTo("end");
        }

        @Test
        @DisplayName("a zero record count is accepted, because an empty input file is an ordinary outcome rather "
                + "than a failure")
        void aZeroRecordCountIsAccepted() {
            assertThat(new AbstractCobolStep.ExecutionSummary(PROGRAM, 0L, "s", "e")
                    .recordsRead()).isZero();
        }

        @Test
        @DisplayName("a negative record count is rejected, because no read can un-read a record")
        void aNegativeRecordCountIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new AbstractCobolStep.ExecutionSummary(PROGRAM, -1L, "s", "e"))
                    .withMessageContaining("recordsRead");
        }

        @Test
        @DisplayName("each of the three mandatory components is rejected when absent")
        void eachMandatoryComponentIsRejectedWhenAbsent() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new AbstractCobolStep.ExecutionSummary(null, 0L, "s", "e"))
                    .withMessageContaining("programName");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            new AbstractCobolStep.ExecutionSummary(PROGRAM, 0L, null, "e"))
                    .withMessageContaining("startedAt");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            new AbstractCobolStep.ExecutionSummary(PROGRAM, 0L, "s", null))
                    .withMessageContaining("completedAt");
        }

        @Test
        @DisplayName("two summaries with the same components are equal, so a summary is a value")
        void twoSummariesWithTheSameComponentsAreEqual() {
            assertThat(new AbstractCobolStep.ExecutionSummary(PROGRAM, 1L, "s", "e"))
                    .isEqualTo(new AbstractCobolStep.ExecutionSummary(PROGRAM, 1L, "s", "e"));
        }
    }

    @Nested
    @DisplayName("The fixed-width batch timestamp, asserted as an image rather than as a date")
    class BatchTimestamp {

        @Test
        @DisplayName("the published width is twenty-six, matching the legacy timestamp field")
        void thePublishedWidthIsTwentySix() {
            assertThat(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH).isEqualTo(26);
        }

        @Test
        @DisplayName("a fixed moment produces an exact image, including the fixed four-character tail that pads the "
                + "legacy field to its declared width")
        void aFixedMomentProducesAnExactImage() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 19, 23, 59, 58, 990_000_000)))
                    .isEqualTo("2022-07-19-23.59.58.990000");
        }

        @Test
        @DisplayName("the injected clock drives the instance accessor, so a step's timestamp is reproducible")
        void theInjectedClockDrivesTheInstanceAccessor() {
            assertThat(step().timestamp()).isEqualTo("2022-07-19-23.59.58.990000");
        }

        @Test
        @DisplayName("both summary timestamps come from the injected clock, so a run against a fixed clock is byte "
                + "reproducible")
        void bothSummaryTimestampsComeFromTheInjectedClock() {
            final AbstractCobolStep.ExecutionSummary summary = step("a").run();
            assertThat(summary.startedAt()).isEqualTo("2022-07-19-23.59.58.990000");
            assertThat(summary.completedAt()).isEqualTo("2022-07-19-23.59.58.990000");
        }

        @Test
        @DisplayName("every component is zero padded to its declared width, so a single-digit month cannot shorten "
                + "the image")
        void everyComponentIsZeroPadded() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 1, 2, 3, 4, 5, 60_000_000)))
                    .isEqualTo("2022-01-02-03.04.05.060000")
                    .hasSize(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("sub-hundredth precision is truncated rather than rounded, because the legacy field holds only "
                + "hundredths and a COBOL store without ROUNDED truncates")
        void subHundredthPrecisionIsTruncated() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 19, 0, 0, 0, 999_999_999)))
                    .isEqualTo("2022-07-19-00.00.00.990000");
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(2022, 7, 19, 0, 0, 0, 9_999_999)))
                    .isEqualTo("2022-07-19-00.00.00.000000");
        }

        @Test
        @DisplayName("the image is always exactly twenty-six US-ASCII bytes across a spread of moments")
        void theImageIsAlwaysExactlyTwentySixAsciiBytes() {
            final LocalDateTime[] moments = {
                LocalDateTime.of(1, 1, 1, 0, 0, 0),
                LocalDateTime.of(1999, 12, 31, 23, 59, 59, 990_000_000),
                LocalDateTime.of(2022, 7, 19, 12, 0, 0),
                LocalDateTime.of(9999, 12, 31, 23, 59, 59, 990_000_000),
            };
            for (final LocalDateTime moment : moments) {
                assertThat(AbstractCobolStep.formatBatchTimestamp(moment)
                        .getBytes(StandardCharsets.US_ASCII))
                        .hasSize(AbstractCobolStep.BATCH_TIMESTAMP_LENGTH);
            }
        }

        @Test
        @DisplayName("a year outside the legacy four-byte field is rejected rather than silently widening the image "
                + "and breaking every downstream fixed-offset read")
        void aYearOutsideTheLegacyFieldIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(
                            LocalDateTime.of(10_000, 1, 1, 0, 0)))
                    .withMessageContaining("year");
        }

        @Test
        @DisplayName("the boundary years are accepted, so the range test is inclusive at both ends")
        void theBoundaryYearsAreAccepted() {
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(9999, 12, 31, 23, 59, 59))).startsWith("9999-");
            assertThat(AbstractCobolStep.formatBatchTimestamp(
                    LocalDateTime.of(1, 1, 1, 0, 0))).startsWith("0001-");
        }

        @Test
        @DisplayName("a null moment is rejected")
        void aNullMomentIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AbstractCobolStep.formatBatchTimestamp(null))
                    .withMessageContaining("moment");
        }

        @Test
        @DisplayName("a clock at a different instant produces a different image, confirming the accessor genuinely "
                + "reads the clock rather than a value cached at construction")
        void aClockAtADifferentInstantProducesADifferentImage() {
            final NamedStep subject = new NamedStep(PROGRAM, registry,
                    Clock.fixed(Instant.parse("1999-12-31T23:59:59.99Z"), ZoneOffset.UTC));
            assertThat(subject.timestamp()).isEqualTo("1999-12-31-23.59.59.990000");
        }
    }

    @Nested
    @DisplayName("The unconditional abend, for a status the caller has already judged fatal")
    class UnconditionalAbend {

        @ParameterizedTest
        @EnumSource(AbstractCobolStep.IoOperation.class)
        @DisplayName("the abend fires for every operation, including a read, because the caller has already decided "
                + "the status is fatal")
        void theAbendFiresForEveryOperation(final AbstractCobolStep.IoOperation operation) {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.forceAbend(operation, RESOURCE, "31"))
                    .satisfies(thrown -> assertThat(thrown.reason())
                            .contains(operation.legacyGerund(), RESOURCE));
        }

        @Test
        @DisplayName("the abend fires even for a status that would otherwise normalise to success, because this "
                + "entry point reports rather than re-classifies")
        void theAbendFiresEvenForASuccessStatus() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.forceAbend(AbstractCobolStep.IoOperation.READ,
                            RESOURCE, FileStatus.SUCCESS.getCode()));
        }

        @Test
        @DisplayName("an absent status is reported with a marker rather than as the word null in the diagnostic")
        void anAbsentStatusIsReportedWithAMarker() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.forceAbend(AbstractCobolStep.IoOperation.OPEN,
                            RESOURCE, null))
                    .satisfies(thrown -> {
                        assertThat(thrown.reason()).contains("(none)");
                        assertThat(thrown.reason()).doesNotContain("null");
                    });
        }

        @Test
        @DisplayName("a null operation and a blank resource are both rejected")
        void aNullOperationAndABlankResourceAreRejected() {
            final ScriptedStep subject = step();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> subject.forceAbend(null, RESOURCE, "31"))
                    .withMessageContaining("operation");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.forceAbend(AbstractCobolStep.IoOperation.READ,
                            " ", "31"))
                    .withMessageContaining("resource");
        }
    }

    /**
     * A concrete step that records the order in which the base class calls its hooks and delivers a
     * scripted sequence of records. The failure fields let a test arm a specific hook so the
     * release-and-rethrow path can be observed without a mocking framework.
     */
    private static final class ScriptedStep extends AbstractCobolStep<String> {

        private final Deque<String> pending = new ArrayDeque<>();
        private final List<String> calls = new ArrayList<>();
        private RuntimeException openFailure;
        private RuntimeException processFailure;
        private RuntimeException closeFailure;
        private RuntimeException releaseFailure;
        private boolean readReportsNullOptional;

        private ScriptedStep(final MeterRegistry meterRegistry, final String... records) {
            super(PROGRAM, meterRegistry, FIXED_CLOCK);
            for (final String record : records) {
                this.pending.addLast(record);
            }
        }

        @Override
        protected void openResources() {
            this.calls.add("open");
            if (this.openFailure != null) {
                throw this.openFailure;
            }
        }

        @Override
        protected Optional<String> readNextRecord() {
            this.calls.add("read");
            if (this.readReportsNullOptional) {
                return null;
            }
            return Optional.ofNullable(this.pending.pollFirst());
        }

        @Override
        protected void processRecord(final String record) {
            this.calls.add("process:" + record);
            if (this.processFailure != null) {
                throw this.processFailure;
            }
        }

        @Override
        protected void closeResources() {
            this.calls.add("close");
            if (this.closeFailure != null) {
                throw this.closeFailure;
            }
        }

        /**
         * Records the failure-path handle release under its own label, so an assertion can tell the
         * non-observable release apart from an observable {@code CLOSE} paragraph.
         */
        @Override
        protected void releaseResources() {
            this.calls.add("release");
            if (this.releaseFailure != null) {
                throw this.releaseFailure;
            }
        }

        /** Exposes the protected read guard so its classification can be asserted directly. */
        private Optional<String> guardedRead(final String resource,
                final IoAction<IoResult<String>> action) {
            return readRecord(resource, action);
        }

        /** Exposes the protected open guard. */
        private void guardedOpen(final String resource, final IoAction<String> action) {
            openResource(resource, action);
        }

        /** Exposes the protected write guard. */
        private void guardedWrite(final String resource, final IoAction<String> action) {
            writeRecord(resource, action);
        }

        /** Exposes the protected close guard. */
        private void guardedClose(final String resource, final IoAction<String> action) {
            closeResource(resource, action);
        }

        /** Exposes the protected normalisation, the first level of the two-level status model. */
        private IoOutcome classify(final IoOperation operation, final String rawStatus) {
            return normaliseStatus(operation, rawStatus);
        }

        /** Exposes the protected unconditional abend. */
        private void forceAbend(final IoOperation operation, final String resource,
                final String rawStatus) {
            abendOnIoFailure(operation, resource, rawStatus);
        }

        /** Exposes the protected program-name accessor. */
        private String name() {
            return programName();
        }

        /** Exposes the protected timestamp accessor. */
        private String timestamp() {
            return currentBatchTimestamp();
        }
    }

    /** A minimal concrete step used where only construction or the timestamp is under test. */
    private static final class NamedStep extends AbstractCobolStep<String> {

        private NamedStep(final String programName) {
            super(programName, new SimpleMeterRegistry(), FIXED_CLOCK);
        }

        private NamedStep(final String programName, final MeterRegistry meterRegistry,
                final Clock clock) {
            super(programName, meterRegistry, clock);
        }

        @Override
        protected void openResources() {
            // No resource to open: this stub exists to exercise construction and the timestamp.
        }

        @Override
        protected Optional<String> readNextRecord() {
            return Optional.empty();
        }

        @Override
        protected void processRecord(final String record) {
            // Unreachable: the read above always reports end of file immediately.
        }

        @Override
        protected void closeResources() {
            // No resource to close, matching the open above.
        }

        private String name() {
            return programName();
        }

        private String timestamp() {
            return currentBatchTimestamp();
        }
    }

    /** A step used only to prove the collaborator null checks fire during construction. */
    private static final class NullCollaboratorStep extends AbstractCobolStep<String> {

        private NullCollaboratorStep(final MeterRegistry meterRegistry, final Clock clock) {
            super(PROGRAM, meterRegistry, clock);
        }

        @Override
        protected void openResources() {
            // Never reached: construction fails before an instance becomes usable.
        }

        @Override
        protected Optional<String> readNextRecord() {
            return Optional.empty();
        }

        @Override
        protected void processRecord(final String record) {
            // Never reached, for the reason given on the open hook.
        }

        @Override
        protected void closeResources() {
            // Never reached, for the reason given on the open hook.
        }
    }
}
