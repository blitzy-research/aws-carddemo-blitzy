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

import com.carddemo.domain.enums.FileStatus;
import com.carddemo.exception.AbendException;
import com.carddemo.util.BatchCancellation;
import com.carddemo.util.FailureDiagnostics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Template Method for the batch tier: the open, read-loop, status-normalisation, record-processing,
 * close, diagnostic and abend skeleton that all ten batch programs of the estate repeat verbatim.
 * {@code app/cbl/CBACT01C.cbl} L70-L87 shows the whole of it - announce start, open, loop until the
 * end-of-file flag flips, close, announce end, return - and the other nine differ only in which files
 * they open and what they do with a record. Three paragraph families are absorbed here: the
 * {@code 0000-}/{@code 0500-} opens, the {@code 1000-} read, and the {@code 9000-}/{@code 9500-}
 * closes that mirror the opens; beneath them sit the status display and the abort routine, named one
 * way in six programs and another in two (decision D-41), reproduced in that order because the order
 * is the contract.
 *
 * <p><strong>The I/O state model has two levels and neither may be collapsed into the other.</strong>
 * The legacy programs never branch on the raw two-byte status: they normalise it into a coarser signed
 * result first - raw {@code 00} to 0, raw {@code 10} to 16, everything else to 12 - and branch on the
 * level-88 names declared over that item ({@code app/cbl/CBACT01C.cbl} L92-L116, an item referenced on
 * 223 lines of the estate). {@link FileStatus} owns the raw vocabulary and stops there,
 * {@link IoOutcome} owns the coarse tri-state and carries the exact normalised value each outcome
 * corresponds to, and {@link IoResult} keeps the raw status beside the record for as long as it takes
 * to diagnose a failure. Collapsing the model would be a defect, not a simplification: nine of the ten
 * programs end their read loop on raw {@code 10}, so folding that into an error would abend every
 * successful job, while treating {@code 10} as end-of-file outside a read would swallow a genuine
 * failure - which is why {@link IoOperation} records that only the read may legitimately report it
 * (decision D-21).
 *
 * <p><strong>Diagnose first, abend second.</strong> The legacy abort paragraph displays
 * {@code ABENDING PROGRAM} and only then calls the Language Environment abort routine, and every
 * failure site reaching it has already displayed its own error text and the raw status.
 * {@link AbendException} holds no logger, so this class is the caller that reproduces the ordering:
 * {@link #abendOnIoFailure(IoOperation, String, String)} logs the operation, the resource and the raw
 * status, then the abend announcement, and only then constructs the exception. Nothing on that path may
 * be reordered.
 *
 * <p><strong>An abend is terminal: the close family never runs after it.</strong> In the legacy an
 * open, read, write or even close failure ends the run where it stands - the close paragraphs and the
 * end-of-execution announcement are never reached - so {@link #closeResources()} is invoked on the
 * completing path only. Invoking it after a failure would emit close diagnostics the legacy never
 * emits and could announce a second abend for a different resource. What the legacy did not have to do
 * and a JVM does is give back the handles it holds, because this process outlives the failed step: that
 * is {@link #releaseResources()}, a deliberately non-observable adaptation which normalises no status,
 * emits no legacy diagnostic and never abends. It must not be turned back into a second close sequence.
 *
 * <p><strong>One source defect is deliberately not propagated.</strong> A close paragraph in
 * {@code app/cbl/CBTRN02C.cbl} (L637-L653) correctly detects that the daily-rejects close failed and
 * then displays a different file's status at L649, while every sibling close in the same program
 * displays its own. It cannot be reproduced here, because the status travels from the failing operation
 * to the diagnostic as a parameter rather than through a shared display field. Recorded as anomaly 30
 * of the source anomaly register.
 *
 * <p><strong>Where this template stops.</strong> Two categories of legacy I/O are supported without
 * being absorbed. Per-call-site status acceptance: a handful of sites accept a status the canonical
 * cascade rejects - {@code 23} alongside {@code 00}, and one site branching on {@code 23} alone to
 * trigger the default disclosure-group fallback - and widening the cascade for everyone would silently
 * convert real errors into successes at the other fifty-odd sites (decision D-22), so such a site
 * normalises for itself through {@link #normaliseStatus(IoOperation, String)} and still reaches the
 * ordered diagnostic through {@link #abendOnIoFailure(IoOperation, String, String)}. And
 * {@code app/cbl/CBSTM03A.CBL}, which is not a read loop but a hand-rolled dispatcher jumping backwards
 * to its start paragraph after each phase: no open-read-close skeleton can express re-entry into a
 * dispatcher after a state change, so it is translated as an explicit state machine elsewhere.
 *
 * <p>Execution is strictly sequential, as a batch program's is: no task executor, partitioning,
 * parallel stream or asynchronous hook may be added, because the estate's read loops carry
 * order-dependent state such as the card-number break in the statement programs. Instance state is the
 * three immutable collaborators supplied at construction and every per-execution value is a local
 * variable, so a concrete step is safe to register as a singleton.
 *
 * <p>Translated from the estate at commit {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} (2022-07-19); the legacy source is cited, never quoted,
 * and is never read at run time.
 *
 * @param <R> the record type the concrete step's read paragraph delivers
 */
public abstract class AbstractCobolStep<R> implements Tasklet {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCobolStep.class);

    private static final int APPL_RESULT_AOK = 0;

    private static final int APPL_RESULT_PENDING = 8;

    private static final int APPL_RESULT_ERROR = 12;

    private static final int APPL_RESULT_EOF = 16;

    protected static final int BATCH_TIMESTAMP_LENGTH = 26;

    private static final String BATCH_TIMESTAMP_TAIL = "0000";

    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    private static final int MIN_REPRESENTABLE_YEAR = 0;

    private static final int MAX_REPRESENTABLE_YEAR = 9999;

    private static final String METRIC_STEP_EXECUTION = "carddemo.batch.cobol.step";

    private static final String TAG_STEP = "step";

    private static final String TAG_OUTCOME = "outcome";

    private static final String OUTCOME_COMPLETED = "COMPLETED";

    private static final String OUTCOME_ABENDED = "ABENDED";

    private static final String OUTCOME_STOPPED = "STOPPED";

    private static final String RAW_STATUS_ABSENT = "(none)";

    private static final String STATUS_UNRECOGNISED = "UNRECOGNISED";

    private final String programName;

    private final MeterRegistry meterRegistry;

    private final Clock clock;

    /**
     * The coarse tri-state the legacy programs actually branch on, each constant carrying the
     * normalised result value the estate moves for it.
     */
    protected enum IoOutcome {
        OK(APPL_RESULT_AOK),

        END_OF_FILE(APPL_RESULT_EOF),

        ERROR(APPL_RESULT_ERROR);

        private final int applResult;

        IoOutcome(final int normalisedResult) {
            this.applResult = normalisedResult;
        }

        public int applResult() {
            return this.applResult;
        }

        static IoOutcome fromApplResult(final int applResult) {
            if (applResult == APPL_RESULT_AOK) {
                return OK;
            }
            if (applResult == APPL_RESULT_EOF) {
                return END_OF_FILE;
            }
            return ERROR;
        }
    }

    /**
     * The four legacy I/O operations, each carrying the gerund its diagnostic uses, the result value
     * the paragraph arms before attempting the operation, and whether end-of-file is a legitimate
     * outcome for it - which is true of the read alone.
     */
    protected enum IoOperation {
        OPEN("OPENING", APPL_RESULT_PENDING, false),

        READ("READING", APPL_RESULT_ERROR, true),

        WRITE("WRITING TO", APPL_RESULT_PENDING, false),

        CLOSE("CLOSING", APPL_RESULT_PENDING, false);

        private final String legacyGerund;

        private final int armedApplResult;

        private final boolean endOfFileTerminatesNormally;

        IoOperation(final String gerund, final int armedResult,
                final boolean endOfFileIsNormalOutcome) {
            this.legacyGerund = gerund;
            this.armedApplResult = armedResult;
            this.endOfFileTerminatesNormally = endOfFileIsNormalOutcome;
        }

        public String legacyGerund() {
            return this.legacyGerund;
        }

        public int armedApplResult() {
            return this.armedApplResult;
        }

        public boolean endOfFileTerminatesNormally() {
            return this.endOfFileTerminatesNormally;
        }
    }

    /** One guarded I/O attempt, returning the raw two-character status the operation reported. */
    @FunctionalInterface
    protected interface IoAction<V> {
        V execute() throws Exception;
    }

    /**
     * A read's raw status together with the record it produced, so a failure can still be diagnosed
     * with the status of the operation that actually failed.
     *
     * @param rawStatus the raw two-character status the operation reported
     * @param record the record read, or {@code null} at end of file
     */
    protected record IoResult<T>(String rawStatus, T record) {
        public IoResult {
            Objects.requireNonNull(rawStatus, "rawStatus");
        }

        public static <T> IoResult<T> of(final String rawStatus, final T record) {
            return new IoResult<>(rawStatus, Objects.requireNonNull(record, "record"));
        }

        public static <T> IoResult<T> endOfFile() {
            return new IoResult<>(FileStatus.END_OF_FILE.getCode(), null);
        }
    }

    /**
     * What a completed step reports back, mirroring the legacy start and end announcements.
     *
     * @param programName the legacy member name this step translates
     * @param recordsRead the number of records the read loop delivered
     * @param startedAt the 26-character batch timestamp taken at start
     * @param completedAt the 26-character batch timestamp taken at completion
     */
    public record ExecutionSummary(String programName, long recordsRead, String startedAt,
            String completedAt) {
        public ExecutionSummary {
            Objects.requireNonNull(programName, "programName");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            if (recordsRead < 0L) {
                throw new IllegalArgumentException("recordsRead must not be negative: " + recordsRead);
            }
        }
    }

    /**
     * @param programName the legacy member name this step translates, used as the culprit in a
     *                    diagnostic and as the metric tag
     * @param meterRegistry the registry the per-step timer is recorded on
     * @param clock the clock the batch timestamps are read from, injected so tests can fix it
     */
    protected AbstractCobolStep(final String programName, final MeterRegistry meterRegistry,
            final Clock clock) {
        this.programName = requireProgramName(programName);
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the whole skeleton in the legacy order: open, read-loop with per-record processing, close,
     * summary. A failure anywhere abends without running the close family.
     *
     * @return the summary of a completed run
     */
    public final ExecutionSummary run() {
        return run(Thread.currentThread()::isInterrupted);
    }

    /**
     * Runs the lifecycle while observing the framework's cooperative stop state between records.
     *
     * @param stepExecution running step whose stop state is observed
     * @return the summary of a completed run
     * @throws JobInterruptedException when the step or its worker thread requests a stop
     */
    public final ExecutionSummary run(final StepExecution stepExecution)
            throws JobInterruptedException {
        try {
            return run(BatchCancellation.requestedBy(stepExecution));
        } catch (final CancellationException stopped) {
            throw BatchCancellation.interrupted(stopped);
        }
    }

    /**
     * Runs the lifecycle with a caller-supplied live stop probe.
     *
     * @param stopRequested probe checked before open and between records
     * @return the summary of a completed run
     * @throws CancellationException when the probe requests a stop
     */
    public final ExecutionSummary run(final BooleanSupplier stopRequested) {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        final String startedAt = currentBatchTimestamp();
        LOGGER.info("START OF EXECUTION OF PROGRAM {} - {}", this.programName, startedAt);

        long recordsRead = 0L;
        try {
            BatchCancellation.checkpoint(stopRequested);
            openResources();

            boolean endOfFile = false;
            while (!endOfFile) {
                BatchCancellation.checkpoint(stopRequested);
                final Optional<R> nextRecord = Objects.requireNonNull(readNextRecord(),
                        "readNextRecord must report an Optional, never null");
                if (nextRecord.isPresent()) {
                    recordsRead++;
                    processRecord(nextRecord.get());
                } else {
                    endOfFile = true;
                }
            }

            closeResources();
        } catch (final CancellationException stopped) {
            releaseAfterFailure(stopped);
            recordExecutionTime(sample, OUTCOME_STOPPED);
            LOGGER.info("EXECUTION OF PROGRAM {} STOPPED AFTER {} RECORD(S) READ",
                    this.programName, recordsRead);
            throw stopped;
        } catch (RuntimeException primary) {
            releaseAfterFailure(primary);
            recordExecutionTime(sample, OUTCOME_ABENDED);
            LOGGER.error("EXECUTION OF PROGRAM {} TERMINATED ABNORMALLY AFTER {} RECORD(S) READ",
                    this.programName, recordsRead);
            throw primary;
        }

        final String completedAt = currentBatchTimestamp();
        recordExecutionTime(sample, OUTCOME_COMPLETED);
        LOGGER.info("PROGRAM {} PROCESSED {} RECORD(S)", this.programName, recordsRead);
        LOGGER.info("END OF EXECUTION OF PROGRAM {} - {}", this.programName, completedAt);
        return new ExecutionSummary(this.programName, recordsRead, startedAt, completedAt);
    }

    /**
     * Adapts {@link #run()} to the batch framework's tasklet contract; the whole program is one
     * invocation because a legacy batch program is not restartable mid-file.
     *
     * @param contribution the framework's per-step contribution
     * @param chunkContext the framework's chunk context
     * @return {@link RepeatStatus#FINISHED} always, since the run is a single indivisible pass
     */
    @Override
    public final RepeatStatus execute(final StepContribution contribution,
            final ChunkContext chunkContext) throws JobInterruptedException {
        if (chunkContext == null) {
            run();
            return RepeatStatus.FINISHED;
        }
        final StepExecution stepExecution = chunkContext.getStepContext().getStepExecution();
        run(stepExecution);
        return RepeatStatus.FINISHED;
    }

    /** The open family: arm the sentinel, open each file, normalise the status, continue or abend. */
    protected abstract void openResources();

    /**
     * The read paragraph: one record per call.
     *
     * @return the record read, or an empty result at end of file, which ends the loop normally
     */
    protected abstract Optional<R> readNextRecord();

    /**
     * The program's own per-record work, invoked once per record in read order.
     *
     * @param record the record just read
     */
    protected abstract void processRecord(R record);

    /**
     * The close family, mirroring the opens and emitting their own diagnostics. Invoked on the
     * completing path only, because a legacy abend never reaches the close paragraphs.
     */
    protected abstract void closeResources();

    /**
     * Non-observable runtime adaptation: hands back handles the JVM would otherwise hold after a
     * failure, where the mainframe enclave released them on its own. It emits no legacy diagnostic,
     * normalises no status and never abends, and must not become a second close sequence.
     */
    protected void releaseResources() {
        LOGGER.debug("PROGRAM {} HOLDS NO HANDLE OF ITS OWN TO RELEASE AFTER FAILURE",
                this.programName);
    }

    /**
     * Performs one guarded open, abending on any status the cascade rejects.
     *
     * @param resource the legacy DD, dataset or file name, as the diagnostic names it
     * @param action the open attempt, returning the raw status it reported
     */
    protected final void openResource(final String resource, final IoAction<String> action) {
        performTwoWay(IoOperation.OPEN, resource, action);
    }

    /**
     * Performs one guarded write. End of file is not a legitimate outcome here, so a status of
     * {@code 10} is an error rather than a normal termination.
     *
     * @param resource the legacy DD, dataset or file name, as the diagnostic names it
     * @param action the write attempt, returning the raw status it reported
     */
    protected final void writeRecord(final String resource, final IoAction<String> action) {
        performTwoWay(IoOperation.WRITE, resource, action);
    }

    /**
     * Performs one guarded close.
     *
     * @param resource the legacy DD, dataset or file name, as the diagnostic names it
     * @param action the close attempt, returning the raw status it reported
     */
    protected final void closeResource(final String resource, final IoAction<String> action) {
        performTwoWay(IoOperation.CLOSE, resource, action);
    }

    /**
     * Performs one guarded read: end of file ends the loop normally, any other rejected status abends.
     *
     * @param <T> the record type
     * @param resource the legacy DD, dataset or file name, as the diagnostic names it
     * @param action the read attempt, returning the raw status and the record
     * @return the record read, or an empty result at end of file
     */
    protected final <T> Optional<T> readRecord(final String resource,
            final IoAction<IoResult<T>> action) {
        final String namedResource = requireResource(resource);
        Objects.requireNonNull(action, "action");

        final IoResult<T> result = attempt(IoOperation.READ, namedResource, action);
        Objects.requireNonNull(result,
                () -> "read of " + namedResource + " reported no result");

        final IoOutcome outcome = classifyOrAbend(IoOperation.READ, namedResource,
                result.rawStatus());
        if (outcome == IoOutcome.END_OF_FILE) {
            return Optional.empty();
        }
        return Optional.of(Objects.requireNonNull(result.record(),
                () -> "read of " + namedResource + " reported status " + result.rawStatus()
                        + " without delivering a record"));
    }

    /**
     * Applies the canonical cascade for a site that must judge an accepted status for itself, so the
     * cascade is never duplicated.
     *
     * @param operation the operation the status came from, which decides whether end of file is legal
     * @param rawStatus the raw two-character status
     * @return the normalised tri-state outcome
     */
    protected final IoOutcome normaliseStatus(final IoOperation operation, final String rawStatus) {
        Objects.requireNonNull(operation, "operation");
        return IoOutcome.fromApplResult(classify(operation, rawStatus));
    }

    /**
     * The one ordered failure path: logs the operation, the resource and the raw status, then the abend
     * announcement, and only then raises. Callers must not log the failure themselves beforehand or
     * reorder these steps.
     *
     * @param operation the operation that failed
     * @param resource the legacy DD, dataset or file name, as the diagnostic names it
     * @param rawStatus the raw two-character status the operation reported
     */
    protected final void abendOnIoFailure(final IoOperation operation, final String resource,
            final String rawStatus) {
        Objects.requireNonNull(operation, "operation");
        throw abendFor(operation, requireResource(resource), rawStatus, APPL_RESULT_ERROR, null);
    }

    protected final String programName() {
        return this.programName;
    }

    protected final String currentBatchTimestamp() {
        return formatBatchTimestamp(LocalDateTime.now(this.clock));
    }

    protected static String formatBatchTimestamp(final LocalDateTime moment) {
        Objects.requireNonNull(moment, "moment");

        final int year = moment.getYear();
        if (year < MIN_REPRESENTABLE_YEAR || year > MAX_REPRESENTABLE_YEAR) {
            throw new IllegalArgumentException("year " + year
                    + " cannot be held in the legacy four-byte year field; expected "
                    + MIN_REPRESENTABLE_YEAR + " to " + MAX_REPRESENTABLE_YEAR);
        }

        final int hundredths = moment.getNano() / NANOS_PER_HUNDREDTH;
        final String image = String.format(Locale.ROOT, "%04d-%02d-%02d-%02d.%02d.%02d.%02d%s",
                year, moment.getMonthValue(), moment.getDayOfMonth(), moment.getHour(),
                moment.getMinute(), moment.getSecond(), hundredths, BATCH_TIMESTAMP_TAIL);

        final int encodedLength = image.getBytes(StandardCharsets.US_ASCII).length;
        if (encodedLength != BATCH_TIMESTAMP_LENGTH) {
            throw new IllegalStateException("batch timestamp image encoded to " + encodedLength
                    + " bytes, expected " + BATCH_TIMESTAMP_LENGTH + ": " + image);
        }
        return image;
    }

    private void performTwoWay(final IoOperation operation, final String resource,
            final IoAction<String> action) {
        final String namedResource = requireResource(resource);
        Objects.requireNonNull(action, "action");

        final String rawStatus = attempt(operation, namedResource, action);
        classifyOrAbend(operation, namedResource, rawStatus);
    }

    private <V> V attempt(final IoOperation operation, final String resource,
            final IoAction<V> action) {
        try {
            return action.execute();
        } catch (AbendException alreadyDiagnosed) {
            throw alreadyDiagnosed;
        } catch (Exception cause) {
            throw abendFor(operation, resource, null, operation.armedApplResult(), cause);
        }
    }

    private IoOutcome classifyOrAbend(final IoOperation operation, final String resource,
            final String rawStatus) {
        final int applResult = classify(operation, rawStatus);
        final IoOutcome outcome = IoOutcome.fromApplResult(applResult);
        if (outcome == IoOutcome.ERROR) {
            throw abendFor(operation, resource, rawStatus, applResult, null);
        }
        return outcome;
    }

    private static int classify(final IoOperation operation, final String rawStatus) {
        final Optional<FileStatus> resolved = FileStatus.fromCode(rawStatus);
        if (resolved.filter(FileStatus::isSuccess).isPresent()) {
            return APPL_RESULT_AOK;
        }
        if (operation.endOfFileTerminatesNormally()
                && resolved.filter(FileStatus::isEndOfFile).isPresent()) {
            return APPL_RESULT_EOF;
        }
        return APPL_RESULT_ERROR;
    }

    private AbendException abendFor(final IoOperation operation, final String resource,
            final String rawStatus, final int applResult, final Throwable cause) {
        final String reportedStatus = (rawStatus == null) ? RAW_STATUS_ABSENT : rawStatus;
        final String statusName = FileStatus.fromCode(rawStatus)
                .map(FileStatus::name)
                .orElse(STATUS_UNRECOGNISED);

        if (cause == null) {
            LOGGER.error("ERROR {} {} - FILE STATUS IS: {} ({}), APPL-RESULT {}",
                    operation.legacyGerund(), resource, reportedStatus, statusName, applResult);
        } else {
            // The legacy diagnostic - the operation, the resource, the RAW two-byte status and the
            // normalised result, in that order - is reproduced verbatim above the substitutions. What
            // is added is the sanitised chain of failure types beneath it, and NOT the failure object.
            // Passing the object would render every message in its chain, and beneath a batch I/O
            // failure that chain is a data-access failure over a driver failure, whose message carries
            // the connection string it could not open and the values it rejected. The chain says which
            // layer failed, which is what the object contributed to a diagnosis, and it says it in type
            // names this module composes. See FailureDiagnostics.
            LOGGER.error("ERROR {} {} - FILE STATUS IS: {} ({}), APPL-RESULT {}, failureChain {}",
                    operation.legacyGerund(), resource, reportedStatus, statusName, applResult,
                    FailureDiagnostics.failureChainOf(cause));
        }
        LOGGER.error("ABENDING PROGRAM {} WITH ABEND CODE {}", this.programName,
                AbendException.BATCH_ABEND_CODE);

        final String reason = bounded("STATUS " + reportedStatus + " " + operation.legacyGerund()
                + " " + resource, AbendException.REASON_LENGTH);
        final String message = bounded("ERROR " + operation.legacyGerund() + " " + resource
                + " - FILE STATUS IS: " + reportedStatus, AbendException.MESSAGE_LENGTH);
        return new AbendException(AbendException.BATCH_ABEND_CODE, this.programName, reason, message,
                cause);
    }

    private void releaseAfterFailure(final RuntimeException primary) {
        try {
            releaseResources();
        } catch (RuntimeException secondary) {
            primary.addSuppressed(secondary);
            LOGGER.warn("SECONDARY FAILURE RELEASING HANDLES OF PROGRAM {}; RETAINED AS SUPPRESSED;"
                    + " failureChain={}", this.programName,
                    FailureDiagnostics.failureChainOf(secondary));
        }
    }

    private void recordExecutionTime(final Timer.Sample sample, final String outcome) {
        sample.stop(Timer.builder(METRIC_STEP_EXECUTION)
                .description("Elapsed time of one legacy CardDemo batch program lifecycle")
                .tag(TAG_STEP, this.programName)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }

    private static String bounded(final String value, final int legacyWidth) {
        if (value.length() <= legacyWidth) {
            return value;
        }
        return value.substring(0, legacyWidth);
    }

    private static String requireResource(final String resource) {
        Objects.requireNonNull(resource, "resource");
        if (resource.isBlank()) {
            throw new IllegalArgumentException("resource must name the file the operation acts on");
        }
        return resource;
    }

    private static String requireProgramName(final String programName) {
        Objects.requireNonNull(programName, "programName");
        if (programName.isBlank()) {
            throw new IllegalArgumentException(
                    "programName must name the legacy batch program this step stands in for");
        }
        if (programName.length() > AbendException.CULPRIT_LENGTH) {
            throw new IllegalArgumentException("programName exceeds the legacy ABEND-CULPRIT width of "
                    + AbendException.CULPRIT_LENGTH + " characters: " + programName);
        }
        return programName;
    }
}
