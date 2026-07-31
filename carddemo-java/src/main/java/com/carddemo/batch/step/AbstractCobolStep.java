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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Template Method for the batch tier: the open, read-loop, status-normalisation, record-processing,
 * close, diagnostic and abend skeleton that every batch program of the AWS CardDemo mainframe estate
 * repeats verbatim.
 *
 * <h2>What this class replaces</h2>
 *
 * <p>Ten batch programs share one structure. Reading {@code app/cbl/CBACT01C.cbl} is enough to see
 * the whole of it, and the other nine differ only in which files they open and what they do with a
 * record. The program body (lines 70 to 87) announces its own start, performs the open paragraphs,
 * loops until an end-of-file flag flips, performs the close paragraphs, announces its own end and
 * returns. Around that body sit three families of paragraph, and it is those three families that
 * this class absorbs:</p>
 *
 * <ul>
 *   <li>the <strong>open</strong> paragraphs, numbered {@code 0000-} through {@code 0500-}, which
 *       arm a sentinel, open a file, normalise the resulting status and either continue or abend;
 *   <li>the <strong>read</strong> paragraph, numbered {@code 1000-}, which reads one record,
 *       normalises the status three ways and either continues, terminates the loop normally or
 *       abends;
 *   <li>the <strong>close</strong> paragraphs, numbered {@code 9000-} through {@code 9500-}, which
 *       mirror the open paragraphs exactly.
 * </ul>
 *
 * <p>Underneath them sit the two diagnostic paragraphs every program carries: the status display
 * ({@code 9910-DISPLAY-IO-STATUS}, named {@code Z-DISPLAY-IO-STATUS} in {@code CBCUS01C} and
 * {@code CBTRN01C}) and the abort routine ({@code 9999-ABEND-PROGRAM}, named
 * {@code Z-ABEND-PROGRAM} in those same two programs). Both are reproduced here, in that order,
 * because the order is the contract. See <em>Diagnose first, abend second</em> below.</p>
 *
 * <h2>The two-level I/O state model, and why it is two levels</h2>
 *
 * <p>The single most important thing to understand before touching this class is that the legacy
 * programs <strong>never branch on the raw two-byte file status</strong>. They normalise it into a
 * coarser signed integer first and then branch on that. {@code app/cbl/CBACT01C.cbl} lines 92 to
 * 116 is the exemplar: a raw status of {@code 00} moves 0 into {@code APPL-RESULT}, a raw status of
 * {@code 10} moves 16, and every other value moves 12. The code then tests two level-88 condition
 * names declared on that item, {@code APPL-AOK} at value 0 (line 62) and {@code APPL-EOF} at value
 * 16 (line 63). End of file sets the loop's termination flag and is an entirely normal outcome; the
 * remaining branch displays a diagnostic, moves the raw status into a display field, displays it and
 * abends. {@code APPL-RESULT} is referenced on 223 lines of {@code app/cbl}, so it, and not the raw
 * code, is what the estate actually tests.</p>
 *
 * <p>Both levels are therefore modelled here and neither is collapsed into the other.
 * {@link FileStatus} owns the raw two-character vocabulary and deliberately stops there;
 * {@link IoOutcome} owns the coarse tri-state and carries the exact {@code APPL-RESULT} value each
 * outcome corresponds to. The raw status is kept alongside the coarse outcome for as long as it takes
 * to diagnose a failure, which is why {@link IoResult} pairs a status with a record and why the
 * failure diagnostic reports the two characters literally.</p>
 *
 * <p>Collapsing the model would be a defect rather than a simplification. Nine of the ten batch
 * programs terminate their read loop on raw status {@code 10}; folding that into an error would turn
 * every successful job into an abend. Conversely, treating {@code 10} as end of file outside a read
 * would swallow a genuine failure, so {@link IoOperation} records which operations may legitimately
 * report end of file and only the read may.</p>
 *
 * <h2>Diagnose first, abend second</h2>
 *
 * <p>{@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBACT01C.cbl} lines 169 to 173) displays
 * {@code ABENDING PROGRAM}, sets a timing field and an abend code, and only then calls the Language
 * Environment abort routine. Every one of the three failure sites that reaches it has already
 * displayed its own error text and the raw status. The diagnostic is therefore emitted <em>before</em>
 * the program dies, and an operator reading a Java log must see the same ordering.</p>
 *
 * <p>{@link AbendException} holds no logger and states that ordering is the caller's contract. This
 * class is that caller. {@link #abendOnIoFailure(IoOperation, String, String)} logs the operation,
 * the resource and the raw two-byte status, then logs the abend announcement, and only then
 * constructs the exception. Nothing on that path may be reordered, and the exception must never be
 * asked to log on its own behalf.</p>
 *
 * <h2>Documented source defect: do not propagate it</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl} paragraph {@code 9300-DALYREJS-CLOSE} (lines 637 to 653) reports
 * the wrong status. It displays {@code ERROR CLOSING DAILY REJECTS FILE}, correctly tests
 * {@code DALYREJS-STATUS} to decide that the close failed, and then moves {@code XREFFILE-STATUS}
 * into the display field at <strong>line 649</strong>. An operator diagnosing a failed close of the
 * daily-rejects file is therefore shown the cross-reference file's status instead. Every sibling
 * close paragraph in the same program moves its own status: the cross-reference close at line 631,
 * the account close at line 667 and the category-balance close at line 686. The defect is confined
 * to that one line.</p>
 *
 * <p>It is <strong>not</strong> reproduced. This class has no way to report any status other than the
 * one produced by the operation that actually failed, because the status travels as a parameter from
 * the operation to the diagnostic rather than through a shared display field. That structural
 * difference is the fix, and it is recorded here rather than in code because there is no code to
 * point at.</p>
 *
 * <h2>Where this template stops</h2>
 *
 * <p>Two categories of legacy I/O deliberately fall outside the template, and both are supported
 * without being absorbed by it.</p>
 *
 * <p>The first is <strong>per-call-site status acceptance</strong>. A handful of sites accept a
 * status the canonical cascade rejects: {@code app/cbl/CBACT04C.cbl} line 422 and
 * {@code app/cbl/CBTRN02C.cbl} line 481 accept {@code 23} alongside {@code 00}, and
 * {@code app/cbl/CBACT04C.cbl} line 436 branches on {@code 23} alone to trigger the default
 * disclosure-group fallback. Widening the cascade for everyone would silently convert real errors
 * into successes in the other fifty-odd sites, so those decisions stay with the program that makes
 * them. Such a site normalises for itself through
 * {@link #normaliseStatus(IoOperation, String)} and, when it decides the status is terminal, reaches
 * the same ordered diagnostic through {@link #abendOnIoFailure(IoOperation, String, String)}. Neither
 * the cascade nor the ordering is ever duplicated.</p>
 *
 * <p>The second is {@code app/cbl/CBSTM03A.CBL}, which is not a read loop at all but a hand-rolled
 * dispatcher: it holds a data-definition name in a work field and jumps backwards to its start
 * paragraph after each phase, re-branching on the new value (lines 760 to 761 and 849 to 852). No
 * open-read-close skeleton can express re-entry into a dispatcher after a state change, so that
 * program is translated as an explicit state machine elsewhere. Its individual I/O operations are
 * still ordinary guarded operations and may use the helpers here.</p>
 *
 * <h2>Concurrency and state</h2>
 *
 * <p>Execution is strictly sequential, exactly as a batch program's is. There is no task executor,
 * no partitioning, no parallel stream and no asynchronous hook anywhere in this class, and none may
 * be added: the estate's read loops carry order-dependent state such as the card-number break in the
 * statement programs, and reordering records would change output.</p>
 *
 * <p>Instance state is limited to three immutable collaborators supplied at construction. Every piece
 * of per-execution state -- the record count, the end-of-file flag, the raw status of the operation in
 * flight, the normalised result and the start timestamp -- is a local variable of the method that owns
 * it. A concrete step is therefore safe to register as a singleton, and two executions of the same
 * step instance cannot observe each other's counters.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL, JCL, BMS, copybook or CICS
 * resource text is reproduced in this module: the legacy source is cited by member, paragraph, field
 * and line number, and never transcribed. Nothing in this class reads the legacy tree at runtime.</p>
 *
 * @param <R> the record type the concrete step's read paragraph delivers, corresponding to the record
 *            the legacy {@code READ ... INTO} statement moved into working storage
 */
public abstract class AbstractCobolStep<R> implements Tasklet {

    /**
     * Diagnostic channel for the whole batch step tier, replacing the console display statements that
     * were the estate's only instrumentation.
     *
     * <p>Named for this class rather than for the concrete subclass, deliberately: the logger is
     * {@code static}, so it cannot vary per instance, and every message it emits carries the legacy
     * program name as a field, which identifies the originating step unambiguously. The
     * {@code com.carddemo} logger level configured in {@code application.yml} covers this category
     * hierarchically, and the JSON encoder configured in {@code logback-spring.xml} renders it as
     * structured output.</p>
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCobolStep.class);

    /**
     * Normalised result meaning success, the value behind the level-88 condition name
     * {@code APPL-AOK} declared at {@code app/cbl/CBACT01C.cbl} line 62.
     */
    private static final int APPL_RESULT_AOK = 0;

    /**
     * The pre-operation sentinel the legacy arms before an open, a write and a close.
     *
     * <p>{@code app/cbl/CBACT01C.cbl} moves 8 into {@code APPL-RESULT} before opening (line 134) and
     * reaches the same value before closing by adding 8 to zero (line 152);
     * {@code app/cbl/CBTRN02C.cbl} moves 8 before writing a reject record (line 450). The read
     * paragraphs do not arm it. The value is neither {@code APPL-AOK} nor {@code APPL-EOF}, so an
     * operation that never completes leaves behind a value that falls through to the terminal branch.
     * That is the sentinel's entire purpose, and it is why an operation which throws is treated as
     * terminal here rather than retried or ignored.</p>
     */
    private static final int APPL_RESULT_PENDING = 8;

    /**
     * Normalised result meaning failure.
     *
     * <p>Unlike 0 and 16 this value carries <strong>no</strong> level-88 condition name anywhere in
     * the estate. The programs reach it by exhausting the other two tests, which is exactly how
     * {@link IoOutcome#fromApplResult(int)} reaches {@link IoOutcome#ERROR}.</p>
     */
    private static final int APPL_RESULT_ERROR = 12;

    /**
     * Normalised result meaning end of file, the value behind the level-88 condition name
     * {@code APPL-EOF} declared at {@code app/cbl/CBACT01C.cbl} line 63.
     */
    private static final int APPL_RESULT_EOF = 16;

    /**
     * Width of the batch timestamp image, in bytes.
     *
     * <p>The estate declares the field as {@code PIC X(26)} and its subordinate redefinition sums to
     * the same figure: four year digits, three separators, two month digits, two day digits, two hour
     * digits, three separators, two minute digits, two second digits, two hundredths digits and a
     * four-character tail ({@code app/cbl/CBTRN02C.cbl} lines 159 to 174).</p>
     */
    protected static final int BATCH_TIMESTAMP_LENGTH = 26;

    /**
     * The literal tail of the batch timestamp, moved into the last four bytes of the image by
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} ({@code app/cbl/CBTRN02C.cbl} line 701).
     */
    private static final String BATCH_TIMESTAMP_TAIL = "0000";

    /**
     * Nanoseconds in one hundredth of a second, used to reduce a nanosecond-resolution instant to the
     * two hundredths digits the legacy field holds.
     */
    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    /** Lowest year the legacy four-byte year field can hold. */
    private static final int MIN_REPRESENTABLE_YEAR = 0;

    /** Highest year the legacy four-byte year field can hold. */
    private static final int MAX_REPRESENTABLE_YEAR = 9999;

    /**
     * Name of the timer that measures one whole legacy program lifecycle.
     *
     * <p>Distinct from the framework's own step timer on purpose. The framework measures the step it
     * scheduled; this measures the legacy program body the step stands in for, which is the figure a
     * migration baseline is stated in.</p>
     */
    private static final String METRIC_STEP_EXECUTION = "carddemo.batch.cobol.step";

    /**
     * Tag key identifying which step was measured. One legacy batch program maps onto exactly one
     * step, so the program name is the step's identity and is stable across executions.
     */
    private static final String TAG_STEP = "step";

    /** Tag key separating a lifecycle that ran to completion from one that abended. */
    private static final String TAG_OUTCOME = "outcome";

    /** Tag value for a lifecycle that reached its close sequence and its completion diagnostic. */
    private static final String OUTCOME_COMPLETED = "COMPLETED";

    /** Tag value for a lifecycle that terminated through the abend path. */
    private static final String OUTCOME_ABENDED = "ABENDED";

    /**
     * Rendered in place of a raw status when the operation produced none, which happens only when the
     * operation itself failed before returning one.
     */
    private static final String RAW_STATUS_ABSENT = "(none)";

    /**
     * Rendered in place of a status name when the raw code falls outside the vocabulary
     * {@link FileStatus} declares. A live data set can return a code the estate never tested, and a
     * diagnostic must survive that rather than fail on it.
     */
    private static final String STATUS_UNRECOGNISED = "UNRECOGNISED";

    /**
     * The legacy program name this step stands in for, for example {@code CBACT01C}.
     *
     * <p>Doubles as the abend culprit and as the step identity on the timer, which is why it is
     * validated against {@link AbendException#CULPRIT_LENGTH} at construction.</p>
     */
    private final String programName;

    /** Registry the lifecycle timer is registered with. Supplied by the container, never created here. */
    private final MeterRegistry meterRegistry;

    /**
     * Time source behind the batch timestamp.
     *
     * <p>Injected rather than read from a static so that a test can pin an instant and assert the
     * rendered image exactly.</p>
     */
    private final Clock clock;

    /**
     * The coarse I/O state the legacy programs actually branch on: the {@code APPL-RESULT} item
     * reduced to the three outcomes its two level-88 condition names and their absence describe.
     *
     * <p>Nested inside the template on purpose. A top-level type of this shape is forbidden in this
     * module, for the reason {@link FileStatus} sets out: normalisation is not a domain concern, and a
     * type that everyone can reach is a type someone will widen until end of file and error become
     * indistinguishable.</p>
     */
    protected enum IoOutcome {

        /**
         * The operation succeeded: raw status {@code 00}, normalised to 0, matching the level-88
         * condition name {@code APPL-AOK}.
         */
        OK(APPL_RESULT_AOK),

        /**
         * The read reached the end of the file: raw status {@code 10}, normalised to 16, matching the
         * level-88 condition name {@code APPL-EOF}.
         *
         * <p>A normal loop terminator, never a failure. Reachable from a read and from nothing else;
         * see {@link IoOperation#endOfFileTerminatesNormally()}.</p>
         */
        END_OF_FILE(APPL_RESULT_EOF),

        /**
         * The operation failed: any other raw status, normalised to 12, matching no condition name at
         * all. Terminal in every one of the ten batch programs.
         */
        ERROR(APPL_RESULT_ERROR);

        /** The {@code APPL-RESULT} value this outcome corresponds to. */
        private final int applResult;

        /**
         * Binds an outcome to the legacy normalised value it stands for.
         *
         * @param normalisedResult the {@code APPL-RESULT} value, one of 0, 16 or 12
         */
        IoOutcome(final int normalisedResult) {
            this.applResult = normalisedResult;
        }

        /**
         * Returns the legacy normalised result this outcome corresponds to.
         *
         * @return 0 for {@link #OK}, 16 for {@link #END_OF_FILE}, 12 for {@link #ERROR}
         */
        public int applResult() {
            return this.applResult;
        }

        /**
         * Reproduces the legacy condition-name cascade: test {@code APPL-AOK}, then {@code APPL-EOF},
         * then fall through to the terminal branch.
         *
         * <p>Clause order is the contract and is preserved literally from
         * {@code app/cbl/CBACT01C.cbl} lines 104 to 115. The fall-through arm is what makes the
         * pre-operation sentinel work: 8 matches neither test, so an operation that never completed is
         * terminal without any extra check.</p>
         *
         * @param applResult a normalised result, whether produced by the status cascade or left behind
         *                   by an operation that did not complete
         * @return {@link #OK} for 0, {@link #END_OF_FILE} for 16, {@link #ERROR} for anything else
         */
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
     * The kind of legacy I/O verb being performed, carrying the three facts that differ between the
     * paragraph families this template absorbs.
     *
     * <p>Keeping the kind explicit is what prevents the most damaging mistake available here. The read
     * paragraphs normalise three ways and the open, write and close paragraphs normalise two ways: an
     * end-of-file status on an open is a failure, not a quiet success. Erasing the distinction would
     * make a failed open look like an empty file.</p>
     */
    protected enum IoOperation {

        /**
         * An {@code OPEN}, as in {@code app/cbl/CBACT01C.cbl} paragraph {@code 0000-ACCTFILE-OPEN}
         * (lines 133 to 149). Arms the sentinel; two-way normalisation.
         */
        OPEN("OPENING", APPL_RESULT_PENDING, false),

        /**
         * A {@code READ}, as in {@code app/cbl/CBACT01C.cbl} paragraph
         * {@code 1000-ACCTFILE-GET-NEXT} (lines 92 to 116). Does not arm the sentinel; three-way
         * normalisation, and the only operation for which end of file is normal.
         */
        READ("READING", APPL_RESULT_ERROR, true),

        /**
         * A {@code WRITE}, as in {@code app/cbl/CBTRN02C.cbl} paragraph
         * {@code 2500-WRITE-REJECT-REC} (lines 446 to 465). Arms the sentinel; two-way normalisation.
         */
        WRITE("WRITING TO", APPL_RESULT_PENDING, false),

        /**
         * A {@code CLOSE}, as in {@code app/cbl/CBACT01C.cbl} paragraph {@code 9000-ACCTFILE-CLOSE}
         * (lines 151 to 167). Arms the sentinel; two-way normalisation.
         */
        CLOSE("CLOSING", APPL_RESULT_PENDING, false);

        /**
         * The verb form the legacy diagnostic uses, so that a Java log line reads the way the console
         * line read: {@code ERROR OPENING ACCTFILE}, {@code ERROR READING ACCOUNT FILE},
         * {@code ERROR CLOSING ACCOUNT FILE}, {@code ERROR WRITING TO REJECTS FILE}.
         */
        private final String legacyGerund;

        /** The normalised value in force before the operation reports a status. */
        private final int armedApplResult;

        /** Whether an at-end status is a normal outcome of this operation. */
        private final boolean endOfFileTerminatesNormally;

        /**
         * Binds an operation kind to its diagnostic wording and its two normalisation facts.
         *
         * @param gerund                    the legacy diagnostic verb form
         * @param armedResult               the normalised value in force before a status is reported
         * @param endOfFileIsNormalOutcome  whether an at-end status terminates normally
         */
        IoOperation(final String gerund, final int armedResult,
                final boolean endOfFileIsNormalOutcome) {
            this.legacyGerund = gerund;
            this.armedApplResult = armedResult;
            this.endOfFileTerminatesNormally = endOfFileIsNormalOutcome;
        }

        /**
         * Returns the verb form the legacy diagnostic uses for this operation.
         *
         * @return the gerund, never {@code null}
         */
        public String legacyGerund() {
            return this.legacyGerund;
        }

        /**
         * Returns the normalised result in force before this operation reports a status, which is what
         * survives when the operation does not complete.
         *
         * <p>{@link #APPL_RESULT_PENDING} for the three operations the legacy arms, and
         * {@link #APPL_RESULT_ERROR} for the read, whose paragraphs do not arm the sentinel. Both
         * values fall through {@link IoOutcome#fromApplResult(int)} to {@link IoOutcome#ERROR}, so an
         * incomplete operation is terminal either way; the distinction is kept because the source
         * makes it.</p>
         *
         * @return the armed normalised result
         */
        public int armedApplResult() {
            return this.armedApplResult;
        }

        /**
         * Reports whether an at-end status is a legitimate outcome of this operation.
         *
         * @return {@code true} for {@link #READ} alone
         */
        public boolean endOfFileTerminatesNormally() {
            return this.endOfFileTerminatesNormally;
        }
    }

    /**
     * One legacy I/O operation, expressed as something callable that reports what the operation
     * produced.
     *
     * <p>Every legacy I/O verb leaves a two-character {@code FILE STATUS} behind, so every
     * implementation of this interface must report one. An open, a write and a close produce nothing
     * else and are therefore written as an {@code IoAction} of {@link String} returning the raw
     * status; a read also delivers a record and is written as an {@code IoAction} of
     * {@link IoResult}.</p>
     *
     * <p>{@code Exception} is declared because real input and output fails in checked ways that the
     * legacy had no vocabulary for. A failure here is the Java equivalent of an operation that never
     * completed, so it takes the armed-sentinel path: it is diagnosed with the raw status reported as
     * absent, chained as the cause of the abend, and never swallowed.</p>
     *
     * @param <V> what the operation reports: a raw status on its own, or a status paired with a record
     */
    @FunctionalInterface
    protected interface IoAction<V> {

        /**
         * Performs the operation.
         *
         * @return what the operation produced, never {@code null}
         * @throws Exception if the operation could not be completed at all
         */
        V execute() throws Exception;
    }

    /**
     * What one legacy {@code READ ... INTO} delivered: the raw two-byte status, and the record moved
     * into working storage when there was one.
     *
     * <p>The two travel together because the coarse outcome alone cannot be diagnosed. Keeping the raw
     * status attached until the diagnostic has been emitted is precisely what
     * {@code MOVE ACCTFILE-STATUS TO IO-STATUS} did before displaying it, and it is what lets this
     * template avoid the defect at {@code app/cbl/CBTRN02C.cbl} line 649, where a shared display field
     * allowed one file's status to be reported for another file's failure.</p>
     *
     * @param <T>       the record type
     * @param rawStatus the two-character {@code FILE STATUS} the read reported, never {@code null}
     * @param record    the record the read delivered, or {@code null} when it delivered none, which is
     *                  the case at end of file and on failure
     */
    protected record IoResult<T>(String rawStatus, T record) {

        /**
         * Validates the invariant that a status is always reported.
         *
         * @throws NullPointerException if {@code rawStatus} is {@code null}
         */
        public IoResult {
            Objects.requireNonNull(rawStatus, "rawStatus");
        }

        /**
         * Pairs a raw status with the record the read delivered.
         *
         * @param <T>       the record type
         * @param rawStatus the two-character {@code FILE STATUS}, never {@code null}
         * @param record    the record delivered, never {@code null} on this factory
         * @return the paired result
         * @throws NullPointerException if either argument is {@code null}
         */
        public static <T> IoResult<T> of(final String rawStatus, final T record) {
            return new IoResult<>(rawStatus, Objects.requireNonNull(record, "record"));
        }

        /**
         * Reports that the read reached the end of the file, carrying the at-end status and no record.
         *
         * <p>The status is taken from {@link FileStatus#END_OF_FILE} rather than written as a literal,
         * so the raw vocabulary stays single-sourced.</p>
         *
         * @param <T> the record type
         * @return an at-end result
         */
        public static <T> IoResult<T> endOfFile() {
            return new IoResult<>(FileStatus.END_OF_FILE.getCode(), null);
        }
    }

    /**
     * What one execution of the template observed, returned by {@link #run()} and reported by its
     * completion diagnostic.
     *
     * <p>Every component is owned by the template itself, which is what allows the loop to keep no
     * mutable instance state: the count lives in a local variable and leaves the method inside this
     * value. Counters that belong to a particular program, such as the reject count that
     * {@code app/cbl/CBTRN02C.cbl} displays at line 228, belong to that program's step and are not
     * represented here.</p>
     *
     * @param programName the legacy program name the step stands in for
     * @param recordsRead how many records the read paragraph delivered before end of file
     * @param startedAt   the batch timestamp taken before the open sequence
     * @param completedAt the batch timestamp taken after the close sequence
     */
    public record ExecutionSummary(String programName, long recordsRead, String startedAt,
            String completedAt) {

        /**
         * Validates the summary's invariants.
         *
         * @throws NullPointerException     if any string component is {@code null}
         * @throws IllegalArgumentException if {@code recordsRead} is negative
         */
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
     * Creates a step against the platform's default-zone clock.
     *
     * <p>The legacy timestamp routine reads {@code FUNCTION CURRENT-DATE}, which returns local civil
     * time rather than an instant, so the default-zone clock is the faithful equivalent and is
     * constructed explicitly here rather than reached through a static call buried in the timestamp
     * code. Prefer {@link #AbstractCobolStep(String, MeterRegistry, Clock)} wherever the clock is
     * available as a bean or needs pinning in a test.</p>
     *
     * @param programName   the legacy batch program this step stands in for, for example
     *                      {@code CBACT01C}; at most {@value AbendException#CULPRIT_LENGTH}
     *                      characters, because it doubles as the abend culprit
     * @param meterRegistry the registry the lifecycle timer is registered with
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code programName} is blank or too long
     */
    protected AbstractCobolStep(final String programName, final MeterRegistry meterRegistry) {
        this(programName, meterRegistry, Clock.systemDefaultZone());
    }

    /**
     * Creates a step with every collaborator supplied.
     *
     * <p>Constructor injection only: there is no setter, no field injection and no mutable
     * collaborator anywhere on this class. The program name is validated here, at construction, rather
     * than at the point of failure, because it is used as the abend culprit and
     * {@link AbendException} rejects an over-long culprit by throwing. Validating late would let the
     * abend path fail on its own argument and mask the input or output failure that provoked it.</p>
     *
     * @param programName   the legacy batch program this step stands in for; at most
     *                      {@value AbendException#CULPRIT_LENGTH} characters
     * @param meterRegistry the registry the lifecycle timer is registered with
     * @param clock         the time source behind {@link #currentBatchTimestamp()}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code programName} is blank or longer than the legacy
     *                                  culprit field
     */
    protected AbstractCobolStep(final String programName, final MeterRegistry meterRegistry,
            final Clock clock) {
        this.programName = requireProgramName(programName);
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs one whole legacy program lifecycle: announce, open, read until end of file, process each
     * record, close, announce.
     *
     * <p>This is the Template Method and it is {@code final}. The order of the six phases is the
     * contract, taken from the program body at {@code app/cbl/CBACT01C.cbl} lines 70 to 87, and a
     * subclass contributes only what happens inside a phase, never the order of the phases
     * themselves:</p>
     *
     * <ol>
     *   <li>the start announcement, replacing {@code DISPLAY 'START OF EXECUTION OF PROGRAM ...'}
     *       (line 71), a literal that all eight programs carrying it use identically;
     *   <li>{@link #openResources()}, replacing the {@code 0000-} through {@code 0500-} open
     *       paragraphs performed in declaration order;
     *   <li>the read loop, replacing {@code PERFORM UNTIL END-OF-FILE = 'Y'} (lines 74 to 81), which
     *       calls {@link #readNextRecord()} and hands each delivered record to
     *       {@link #processRecord(Object)};
     *   <li>{@link #closeResources()}, replacing the {@code 9000-} through {@code 9500-} close
     *       paragraphs;
     *   <li>the completion diagnostic, replacing the end-of-run displays such as the processed count
     *       at {@code app/cbl/CBTRN02C.cbl} line 227;
     *   <li>the end announcement, replacing {@code DISPLAY 'END OF EXECUTION OF PROGRAM ...'}
     *       (line 85).
     * </ol>
     *
     * <p>The loop is the legacy loop, not a rewrite of it. A read that reports end of file terminates
     * it normally and is neither logged as an error nor allowed to throw; a read that reports any other
     * non-success status has already abended inside {@link #readNextRecord()}'s guarded helper before
     * control returns here. Only a record the read actually delivered is processed, which is the inner
     * {@code IF END-OF-FILE = 'N'} test at line 77.</p>
     *
     * <p>Every counter and flag the loop needs is a local variable, so two executions of the same step
     * instance cannot interfere, and the whole lifecycle is timed once, on both the completing and the
     * abending path, with the outcome distinguished by a tag.</p>
     *
     * <p>On failure the original exception always propagates. If the failure arrived before the close
     * sequence was entered, the close sequence is attempted exactly once so that resources are
     * released; a failure of that attempt is attached to the original as a suppressed exception and
     * logged, never substituted for it.</p>
     *
     * @return what this execution observed
     * @throws AbendException if any guarded operation reported a terminal status, after the diagnostic
     *                        has been emitted
     */
    public final ExecutionSummary run() {
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        final String startedAt = currentBatchTimestamp();
        LOGGER.info("START OF EXECUTION OF PROGRAM {} - {}", this.programName, startedAt);

        long recordsRead = 0L;
        boolean closeSequenceEntered = false;
        try {
            openResources();

            boolean endOfFile = false;
            while (!endOfFile) {
                final Optional<R> nextRecord = Objects.requireNonNull(readNextRecord(),
                        "readNextRecord must report an Optional, never null");
                if (nextRecord.isPresent()) {
                    recordsRead++;
                    processRecord(nextRecord.get());
                } else {
                    endOfFile = true;
                }
            }

            closeSequenceEntered = true;
            closeResources();
        } catch (RuntimeException primary) {
            if (!closeSequenceEntered) {
                releaseAfterFailure(primary);
            }
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
     * Adapts the lifecycle onto the framework's tasklet contract so a job configuration can wire this
     * step directly.
     *
     * <p>A legacy batch program processes a whole file in one invocation, so the lifecycle is
     * whole-file rather than chunk-oriented and completes in a single pass: the returned status is
     * always {@code FINISHED} and this method never asks to be called again.</p>
     *
     * <p>Neither parameter is consulted. There is no partial contribution to report, because the
     * lifecycle either completes in full or abends; and there is no need to reach into the chunk
     * context for identity, because the step's identity is the legacy program name supplied at
     * construction. Step-level timing is recorded by {@link #run()} itself and by the framework's own
     * step instrumentation.</p>
     *
     * <p>This class constructs no step and no job. Step construction belongs to the job configurations
     * in the parent package, which own the step name, the transaction manager and the job repository.
     * Nothing here enables batch processing, launches a job or touches the framework's metadata
     * tables.</p>
     *
     * @param contribution the framework's per-chunk contribution, not consulted
     * @param chunkContext the framework's chunk context, not consulted
     * @return {@link RepeatStatus#FINISHED}, always
     * @throws AbendException if the lifecycle abended, after the diagnostic has been emitted
     */
    @Override
    public final RepeatStatus execute(final StepContribution contribution,
            final ChunkContext chunkContext) {
        run();
        return RepeatStatus.FINISHED;
    }

    /**
     * Opens every resource this step reads or writes, in the order the legacy opened them.
     *
     * <p>Replaces the open paragraph family: {@code 0000-ACCTFILE-OPEN} alone in
     * {@code app/cbl/CBACT01C.cbl} (lines 133 to 149), and up to six paragraphs performed in sequence
     * in the larger programs, such as {@code 0000-DALYTRAN-OPEN} through {@code 0500-TCATBALF-OPEN} in
     * {@code app/cbl/CBTRN02C.cbl} (lines 195 to 200). Order is preserved because the legacy preserved
     * it.</p>
     *
     * <p>Perform each open through {@link #openResource(String, IoAction)} so that the sentinel, the
     * normalisation and the ordered diagnostic are applied uniformly. An implementation must not
     * swallow a failure: a failed open abends, exactly as it did on the mainframe.</p>
     */
    protected abstract void openResources();

    /**
     * Reads the next record from the driving file.
     *
     * <p>Replaces the read paragraph, numbered {@code 1000-} in every batch program, for example
     * {@code 1000-ACCTFILE-GET-NEXT} in {@code app/cbl/CBACT01C.cbl} (lines 92 to 116) and
     * {@code 1000-DALYTRAN-GET-NEXT} in {@code app/cbl/CBTRN02C.cbl} (lines 345 to 369). Reads of
     * secondary files performed while handling a record belong in
     * {@link #processRecord(Object)}, not here, because the legacy performed them from the loop body
     * rather than from the read paragraph.</p>
     *
     * <p>Implement it by delegating to {@link #readRecord(String, IoAction)}, which returns exactly
     * this shape: a record when the read reported success, and nothing when it reported end of file.
     * A non-success, non-at-end status never returns at all, because the helper has already emitted
     * the diagnostic and abended.</p>
     *
     * @return the record the read delivered, or an empty {@link Optional} at end of file; never
     *         {@code null}
     */
    protected abstract Optional<R> readNextRecord();

    /**
     * Handles one record the read delivered.
     *
     * <p>Replaces the body of the legacy read loop, which differs in every program and is the only
     * part of the lifecycle that does: the file-print programs display the record
     * ({@code app/cbl/CBACT01C.cbl} line 78), the posting program validates and either posts or writes
     * a reject ({@code app/cbl/CBTRN02C.cbl} lines 206 to 216), and the extract program looks up the
     * cross-reference and the account ({@code app/cbl/CBTRN01C.cbl} lines 169 to 184).</p>
     *
     * <p>Called once per delivered record, in file order, on the calling thread. Any secondary read,
     * write or update the record requires is performed from here through the guarded helpers, which is
     * how a write failure reaches the same ordered diagnostic as a read failure.</p>
     *
     * @param record the record the read delivered, never {@code null}
     */
    protected abstract void processRecord(R record);

    /**
     * Closes every resource this step opened, in the order the legacy closed them.
     *
     * <p>Replaces the close paragraph family: {@code 9000-ACCTFILE-CLOSE} alone in
     * {@code app/cbl/CBACT01C.cbl} (lines 151 to 167), and up to six paragraphs performed in sequence
     * in the larger programs, such as {@code 9000-DALYTRAN-CLOSE} through {@code 9500-TCATBALF-CLOSE}
     * in {@code app/cbl/CBTRN02C.cbl} (lines 221 to 226).</p>
     *
     * <p>Perform each close through {@link #closeResource(String, IoAction)}. Two calls are possible
     * in one execution only in the sense that this method is invoked either on the normal path or on
     * the failure path, never both, so an implementation does not need to guard against a double
     * close; it should, however, tolerate being called when an earlier open did not complete, because
     * that is the situation the failure path is for.</p>
     */
    protected abstract void closeResources();

    /**
     * Performs one guarded {@code OPEN}.
     *
     * <p>Reproduces the open paragraph in full: arm the sentinel, open, normalise the reported status
     * two ways, and on anything but success emit the ordered diagnostic and abend. An at-end status is
     * a failure here, not an empty file, because the legacy open paragraphs have no at-end branch.</p>
     *
     * @param resource the file the open acts on, named as the legacy diagnostic named it, for example
     *                 {@code ACCTFILE}
     * @param action   performs the open and reports its raw two-character {@code FILE STATUS}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code resource} is blank
     * @throws AbendException           if the open reported anything but success, after the diagnostic
     *                                  has been emitted
     */
    protected final void openResource(final String resource, final IoAction<String> action) {
        performTwoWay(IoOperation.OPEN, resource, action);
    }

    /**
     * Performs one guarded {@code WRITE}.
     *
     * <p>Reproduces {@code app/cbl/CBTRN02C.cbl} paragraph {@code 2500-WRITE-REJECT-REC} (lines 446 to
     * 465) and its peers: arm the sentinel, write, normalise two ways, and on anything but success
     * emit the ordered diagnostic and abend.</p>
     *
     * @param resource the file the write acts on, for example {@code DALYREJS}
     * @param action   performs the write and reports its raw two-character {@code FILE STATUS}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code resource} is blank
     * @throws AbendException           if the write reported anything but success, after the
     *                                  diagnostic has been emitted
     */
    protected final void writeRecord(final String resource, final IoAction<String> action) {
        performTwoWay(IoOperation.WRITE, resource, action);
    }

    /**
     * Performs one guarded {@code CLOSE}.
     *
     * <p>Reproduces the close paragraph in full, including the fact that a close failure is terminal:
     * {@code app/cbl/CBACT01C.cbl} lines 159 to 166 diagnose and abend exactly as the open and read
     * paths do.</p>
     *
     * <p>The status reported in a close diagnostic is always the status of the close that failed. That
     * is a deliberate departure from {@code app/cbl/CBTRN02C.cbl} line 649, where the daily-rejects
     * close reports the cross-reference file's status; see this class's documentation.</p>
     *
     * @param resource the file the close acts on
     * @param action   performs the close and reports its raw two-character {@code FILE STATUS}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code resource} is blank
     * @throws AbendException           if the close reported anything but success, after the
     *                                  diagnostic has been emitted
     */
    protected final void closeResource(final String resource, final IoAction<String> action) {
        performTwoWay(IoOperation.CLOSE, resource, action);
    }

    /**
     * Performs one guarded {@code READ}, the only operation with a three-way outcome.
     *
     * <p>Reproduces the read paragraph in full: read, normalise the reported status three ways, return
     * the delivered record on success, report end of file as an absent record, and on any other status
     * emit the ordered diagnostic and abend. This is the one place in the template where a non-success
     * status is not a failure, and it is gated on the operation kind so that an at-end status arriving
     * from an open, a write or a close cannot take the same path.</p>
     *
     * @param <T>      the record type the read delivers, which need not be the step's driving record
     *                 type: a program may read a secondary file while handling a record
     * @param resource the file the read acts on
     * @param action   performs the read and reports its raw status together with the record it
     *                 delivered, using {@link IoResult#of(String, Object)} on success and
     *                 {@link IoResult#endOfFile()} at end of file
     * @return the delivered record, or an empty {@link Optional} at end of file
     * @throws NullPointerException     if either argument is {@code null}, if the action reports no
     *                                  result, or if it reports success without a record
     * @throws IllegalArgumentException if {@code resource} is blank
     * @throws AbendException           if the read reported a status that is neither success nor at
     *                                  end, after the diagnostic has been emitted
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
     * Normalises a raw two-character status into the coarse outcome the legacy branches on, without
     * acting on the result.
     *
     * <p>Exposed for the handful of sites that accept a status the canonical cascade rejects and must
     * therefore decide for themselves: {@code app/cbl/CBACT04C.cbl} line 422 and
     * {@code app/cbl/CBTRN02C.cbl} line 481 accept a record-not-found status alongside success, and
     * {@code app/cbl/CBACT04C.cbl} line 436 branches on it alone to fall back to the default
     * disclosure group. Such a site normalises here, applies its own acceptance rule, and reaches the
     * terminal path through {@link #abendOnIoFailure(IoOperation, String, String)} when it decides the
     * status is fatal. Neither the cascade nor the diagnostic ordering is ever duplicated.</p>
     *
     * <p>The cascade order is taken literally from {@code app/cbl/CBACT01C.cbl} lines 94 to 103:
     * success first, at end second and only for a read, failure otherwise. An unrecognised code is a
     * failure, and no path here depends on the two codes {@link FileStatus} documents but the estate
     * never compares.</p>
     *
     * @param operation the kind of operation the status came from, which decides whether an at-end
     *                  status is legitimate
     * @param rawStatus the raw two-character status, which may be {@code null} or unrecognised
     * @return {@link IoOutcome#OK}, {@link IoOutcome#END_OF_FILE} or {@link IoOutcome#ERROR}
     * @throws NullPointerException if {@code operation} is {@code null}
     */
    protected final IoOutcome normaliseStatus(final IoOperation operation, final String rawStatus) {
        Objects.requireNonNull(operation, "operation");
        return IoOutcome.fromApplResult(classify(operation, rawStatus));
    }

    /**
     * Emits the terminal diagnostic and abends, in that order.
     *
     * <p>Reproduces the tail of every failure site in the batch tier: the operation's own error text,
     * then the status display of {@code 9910-DISPLAY-IO-STATUS}, then the announcement and abort of
     * {@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBACT01C.cbl} lines 110 to 113 and 169 to 173). The
     * raw two-character status is logged before the exception is constructed, never after and never
     * by the exception itself, which holds no logger precisely so that the ordering cannot drift.</p>
     *
     * <p>This method never returns normally.</p>
     *
     * @param operation the kind of operation that failed, which supplies the diagnostic's verb
     * @param resource  the file that failed, named as the legacy diagnostic named it
     * @param rawStatus the raw two-character status the operation reported; {@code null} is rendered
     *                  as absent, which happens only when the operation could not report one
     * @throws NullPointerException     if {@code operation} or {@code resource} is {@code null}
     * @throws IllegalArgumentException if {@code resource} is blank
     * @throws AbendException           always
     */
    protected final void abendOnIoFailure(final IoOperation operation, final String resource,
            final String rawStatus) {
        Objects.requireNonNull(operation, "operation");
        throw abendFor(operation, requireResource(resource), rawStatus, APPL_RESULT_ERROR, null);
    }

    /**
     * Returns the legacy program name this step stands in for.
     *
     * @return the program name, never {@code null}, never blank, never longer than the legacy abend
     *         culprit field
     */
    protected final String programName() {
        return this.programName;
    }

    /**
     * Renders the current instant as the batch timestamp image the estate stamps records with.
     *
     * <p>Reproduces {@code Z-GET-DB2-FORMAT-TIMESTAMP}, which appears identically in
     * {@code app/cbl/CBTRN02C.cbl} (lines 692 to 705) and {@code app/cbl/CBACT04C.cbl} (lines 613 to
     * 626). Reads the injected clock, so a test can pin the instant and assert the image exactly.</p>
     *
     * @return the image, exactly {@value #BATCH_TIMESTAMP_LENGTH} bytes when encoded as US-ASCII
     */
    protected final String currentBatchTimestamp() {
        return formatBatchTimestamp(LocalDateTime.now(this.clock));
    }

    /**
     * Renders a given civil date and time as the batch timestamp image.
     *
     * <p>The image is {@code YYYY-MM-DD-hh.mm.ss.hh0000}: a four-digit year, a hyphen, a two-digit
     * month, a hyphen, a two-digit day, <strong>a hyphen</strong>, a two-digit hour, a dot, a
     * two-digit minute, a dot, a two-digit second, a dot, two digits of hundredths and the literal
     * tail. The estate's own comment states the shape ({@code app/cbl/CBTRN02C.cbl} line 148) and the
     * redefinition beneath it (lines 159 to 174) fixes every field width, which is where the three
     * hyphens, the three dots and the four-character tail come from.</p>
     *
     * <p>This is <strong>not</strong> the online form. The online tier builds a same-width value with a
     * space where the third hyphen is, colons between the time parts and a six-digit fraction
     * ({@code app/cbl/COBIL00C.cbl} lines 263 to 266). Emitting that shape from a batch step would
     * corrupt every stamped record, so the two are kept apart deliberately and this method produces
     * only the batch punctuation.</p>
     *
     * <p>Hundredths are obtained by integer division of the nanosecond field, so a fraction is
     * truncated rather than rounded. That is faithful in two independent ways: the legacy field is fed
     * from a hundredths-resolution intrinsic that never rounds, and no arithmetic anywhere in the
     * estate specifies rounding, so truncation is the estate-wide rule.</p>
     *
     * <p>The width is asserted on the encoded byte count rather than on the character count, because
     * the legacy field is measured in bytes and only an encoded length can prove the image fits
     * it.</p>
     *
     * @param moment the civil date and time to render
     * @return the image, exactly {@value #BATCH_TIMESTAMP_LENGTH} bytes when encoded as US-ASCII
     * @throws NullPointerException     if {@code moment} is {@code null}
     * @throws IllegalArgumentException if the year falls outside the range the legacy four-byte year
     *                                  field can hold
     * @throws IllegalStateException    if the rendered image is not exactly the legacy width, which
     *                                  would mean this method had been changed incompatibly
     */
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

    /**
     * Performs one operation whose only successful outcome is success: an open, a write or a close.
     *
     * <p>The two-way normalisation of {@code app/cbl/CBACT01C.cbl} lines 136 to 140, where the status
     * is either success or failure and there is no at-end branch. The returned outcome can only be
     * {@link IoOutcome#OK}, because every other outcome has already abended, so it is discarded.</p>
     *
     * @param operation the operation kind
     * @param resource  the file the operation acts on
     * @param action    performs the operation and reports its raw status
     */
    private void performTwoWay(final IoOperation operation, final String resource,
            final IoAction<String> action) {
        final String namedResource = requireResource(resource);
        Objects.requireNonNull(action, "action");

        final String rawStatus = attempt(operation, namedResource, action);
        classifyOrAbend(operation, namedResource, rawStatus);
    }

    /**
     * Runs an operation and translates a failure to complete into the terminal path.
     *
     * <p>This is where the pre-operation sentinel earns its place. The legacy arms
     * {@link #APPL_RESULT_PENDING} before an open, a write and a close so that an operation which
     * never reports a status leaves behind a value matching neither condition name and therefore falls
     * through to the terminal branch. An exception is that same condition expressed in Java: no status
     * was produced, so none can be normalised, so the armed value stands and the operation is
     * terminal. The armed value is reported in the diagnostic, which is how an operator tells a failed
     * operation apart from one that reported a bad status.</p>
     *
     * <p>An abend already raised further down is rethrown untouched, so a nested guarded operation is
     * diagnosed exactly once and its original raw status is never overwritten by an outer frame.</p>
     *
     * @param <V>       what the operation reports
     * @param operation the operation kind
     * @param resource  the file the operation acts on
     * @param action    the operation
     * @return what the operation reported
     */
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

    /**
     * Normalises a reported status and abends if the result is terminal.
     *
     * @param operation the operation kind
     * @param resource  the file the operation acts on
     * @param rawStatus the raw two-character status the operation reported
     * @return {@link IoOutcome#OK}, or {@link IoOutcome#END_OF_FILE} for a read at end of file
     */
    private IoOutcome classifyOrAbend(final IoOperation operation, final String resource,
            final String rawStatus) {
        final int applResult = classify(operation, rawStatus);
        final IoOutcome outcome = IoOutcome.fromApplResult(applResult);
        if (outcome == IoOutcome.ERROR) {
            throw abendFor(operation, resource, rawStatus, applResult, null);
        }
        return outcome;
    }

    /**
     * The status cascade, reproduced clause for clause and in source order.
     *
     * <p>{@code app/cbl/CBACT01C.cbl} lines 94 to 103 test success first, then at end, then fall
     * through to failure; {@code app/cbl/CBTRN03C.cbl} lines 251 to 258 express the same three clauses
     * as an evaluation with an otherwise arm. The order is preserved because the clauses overlap in the
     * sense that reordering them would change which branch an at-end status takes.</p>
     *
     * <p>The at-end clause is additionally gated on the operation kind, which is what the two-way open,
     * write and close paragraphs express by having no at-end clause at all. Resolution goes through
     * {@link FileStatus}, so a code outside the vocabulary the estate exercises resolves to nothing and
     * lands on the failure clause instead of throwing.</p>
     *
     * @param operation the operation kind
     * @param rawStatus the raw two-character status, possibly {@code null} or unrecognised
     * @return 0 for success, 16 for at end on a read, 12 otherwise
     */
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

    /**
     * Emits the terminal diagnostic and builds the abend, in that order.
     *
     * <p>Two log records are written before the exception exists, mirroring the two legacy paragraphs
     * that ran before the abort call. The first carries the operation, the file, the raw two-character
     * status, the name that status resolves to and the normalised result, which is the whole of the
     * two-level state model in one record. The second is the abend announcement.</p>
     *
     * <p>The exception's bounded fields are clamped to the widths {@link AbendException} declares. That
     * class rejects an over-long value by throwing, and an abend that failed while describing itself
     * would destroy the diagnostic it exists to carry; the full text has already been logged
     * unclamped, so nothing is lost. Clamping uses the dependency's own width constants rather than a
     * second copy of them.</p>
     *
     * @param operation  the operation kind
     * @param resource   the file that failed
     * @param rawStatus  the raw status, or {@code null} when the operation reported none
     * @param applResult the normalised result in force, either the classified failure value or the
     *                   armed sentinel
     * @param cause      the underlying failure to chain, or {@code null} when the operation reported a
     *                   bad status rather than failing outright
     * @return the abend the caller must throw immediately
     */
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
            LOGGER.error("ERROR {} {} - FILE STATUS IS: {} ({}), APPL-RESULT {}",
                    operation.legacyGerund(), resource, reportedStatus, statusName, applResult,
                    cause);
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

    /**
     * Releases resources after a failure without letting a second failure replace the first.
     *
     * <p>The legacy never reached its close paragraphs after an abend, because the abort routine ended
     * the task and the operating system released the data sets. A Java process outlives the failure and
     * must release its own resources, so the close sequence is attempted once here. Any failure of that
     * attempt is attached to the original as a suppressed exception and logged as a warning: the
     * original propagates unchanged, and the secondary failure is still visible rather than
     * discarded.</p>
     *
     * @param primary the failure that ended the lifecycle and that must propagate
     */
    private void releaseAfterFailure(final RuntimeException primary) {
        try {
            closeResources();
        } catch (RuntimeException secondary) {
            primary.addSuppressed(secondary);
            LOGGER.warn("SECONDARY FAILURE RELEASING RESOURCES OF PROGRAM {}; RETAINED AS SUPPRESSED",
                    this.programName, secondary);
        }
    }

    /**
     * Stops the lifecycle timer, tagged with the step's identity and how the lifecycle ended.
     *
     * <p>One legacy batch program maps onto exactly one step, so the program name is the step's
     * identity and the tag is stable across executions. The timer carries no threshold, no percentile
     * and no service level: the migration has no documented baseline to compare against, so this
     * establishes one. Histogram and percentile configuration is a deployment concern and lives in
     * {@code application.yml}, not here.</p>
     *
     * @param sample  the sample started at the beginning of the lifecycle
     * @param outcome how the lifecycle ended
     */
    private void recordExecutionTime(final Timer.Sample sample, final String outcome) {
        sample.stop(Timer.builder(METRIC_STEP_EXECUTION)
                .description("Elapsed time of one legacy CardDemo batch program lifecycle")
                .tag(TAG_STEP, this.programName)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }

    /**
     * Clamps a value to a legacy field width declared by {@link AbendException}.
     *
     * @param value       the value to clamp
     * @param legacyWidth the width of the legacy field, taken from the dependency's own constant
     * @return the value, truncated to the width when it was longer
     */
    private static String bounded(final String value, final int legacyWidth) {
        if (value.length() <= legacyWidth) {
            return value;
        }
        return value.substring(0, legacyWidth);
    }

    /**
     * Validates that an operation names the file it acts on, so that no diagnostic can be emitted
     * without saying what failed.
     *
     * @param resource the caller-supplied resource name
     * @return the resource name
     * @throws NullPointerException     if {@code resource} is {@code null}
     * @throws IllegalArgumentException if {@code resource} is blank
     */
    private static String requireResource(final String resource) {
        Objects.requireNonNull(resource, "resource");
        if (resource.isBlank()) {
            throw new IllegalArgumentException("resource must name the file the operation acts on");
        }
        return resource;
    }

    /**
     * Validates the program name at construction, against the width of the legacy abend culprit field.
     *
     * <p>Checked here rather than at the point of failure so that the abend path can never fail on its
     * own argument and hide the input or output failure that provoked it.</p>
     *
     * @param programName the caller-supplied program name
     * @return the program name
     * @throws NullPointerException     if {@code programName} is {@code null}
     * @throws IllegalArgumentException if {@code programName} is blank or longer than the legacy
     *                                  culprit field
     */
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
