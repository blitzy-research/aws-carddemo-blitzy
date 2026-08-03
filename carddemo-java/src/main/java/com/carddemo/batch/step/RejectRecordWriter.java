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

import com.carddemo.domain.DailyTransaction;
import com.carddemo.domain.enums.RejectReason;
import com.carddemo.util.DailyTransactionRecordMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.core.io.WritableResource;

/**
 * Writes the daily-transaction reject dataset of the legacy posting program {@code CBTRN02C}, one
 * fixed {@value #REJECT_RECORD_LENGTH}-byte image per rejected transaction.
 *
 * <p>This is the single point in the module at which a reject record acquires its bytes. It assembles
 * the image, guards every width in the image, and hands the result to a destination the owning job
 * configuration supplies. It performs no validation of its own: which transactions are rejected, and
 * for which reason, is decided upstream by the validation cascade, and this writer records that
 * decision rather than re-taking it.
 *
 * <h2>Record geometry</h2>
 *
 * <p>The record is {@value #REJECT_RECORD_LENGTH} bytes and is built from exactly three fields, in
 * this order and with no separator between them:
 *
 * <ol>
 *   <li>{@value #SOURCE_IMAGE_LENGTH} bytes of source record - the daily-transaction image copied
 *       verbatim, produced by {@link DailyTransactionRecordMapper} so that every trailing space, the
 *       overpunched sign of the amount, the origination timestamp and the unmapped filler run survive
 *       unchanged. This writer holds no offset of its own and slices nothing: the layout lives in the
 *       mapper, which is the only place it may live;
 *   <li>{@value #FAIL_REASON_LENGTH} bytes of reason code - the numeric reject reason rendered as four
 *       digits with leading zeros, so reason 100 is emitted as {@code 0100}. A three-character
 *       rendering would shift everything after it by one byte;
 *   <li>{@value #FAIL_REASON_DESC_LENGTH} bytes of description - the reason text exactly as the legacy
 *       program emits it, left-justified and space-padded to the full field width. The text is never
 *       trimmed, folded, reworded or truncated.
 * </ol>
 *
 * <p>Fields two and three together are the 80-byte validation trailer, and
 * {@value #FAIL_REASON_LENGTH} + {@value #FAIL_REASON_DESC_LENGTH} =
 * {@value #VALIDATION_TRAILER_LENGTH} while {@value #SOURCE_IMAGE_LENGTH} +
 * {@value #VALIDATION_TRAILER_LENGTH} = {@value #REJECT_RECORD_LENGTH}. Both sums are checked when
 * this class initialises, so the declared widths cannot drift apart from each other unnoticed.
 *
 * <p>Every width is measured in <strong>encoded US-ASCII bytes</strong>, never in characters. The two
 * agree here only because the image is proved to be pure US-ASCII before it is measured; the purity
 * check is what makes the byte count meaningful, because a charset encoder asked to emit an
 * unmappable character silently substitutes one byte for it and a length check alone would not notice.
 * The charset is stated explicitly at every step and the platform default charset is never consulted.
 *
 * <h2>Fixed unblocked output</h2>
 *
 * <p>The legacy dataset is allocated {@code RECFM=F} with {@code LRECL=430} and {@code BLKSIZE=0} at
 * {@code app/jcl/POSTTRAN.jcl} lines 34 to 38. That is fixed-length <strong>unblocked</strong> - not
 * {@code FB} - so the file is a bare run of {@value #REJECT_RECORD_LENGTH}-byte images with no block
 * structure at all. Accordingly this writer emits no record separator, no platform line ending, no
 * byte-order mark, no block padding and no framing or quoting of any kind, and the length of the
 * finished stream is always an exact multiple of {@value #REJECT_RECORD_LENGTH}. The separator is
 * held at {@link #RECORD_SEPARATOR}, empty, rather than left at the framework default, which is the
 * platform line ending and would append one or two stray bytes to every record.
 *
 * <p>Bytes are buffered for input and output only. Buffering never moves a record boundary: one call
 * to {@link #write(Chunk)} contributes exactly {@code chunk size} whole images and nothing else, and
 * the buffer is flushed and closed through the framework so the last record is complete on disk.
 *
 * <h2>Findings from direct inspection of the legacy estate</h2>
 *
 * <p><strong>The width is 430, stated three times and consistent every time.</strong> Direct
 * inspection found the same geometry in all three places that declare it, so there is no ambiguity to
 * resolve and no reconciliation to document: the file description at {@code app/cbl/CBTRN02C.cbl}
 * lines 81 to 84 declares a 350-byte reject segment followed by an 80-byte trailer segment; the
 * working-storage record at lines 176 to 178 declares the same two segments again, with the trailer
 * itself broken out at lines 180 to 182 into a four-digit numeric reason and a 76-character
 * description; and the job DD at {@code app/jcl/POSTTRAN.jcl} line 36 allocates the dataset at
 * {@code LRECL=430}. 350 + 80 = 430 in the program, and 430 in the allocation.
 *
 * <p><strong>There is no 500-byte reject layout.</strong> A targeted, case-inclusive search of
 * {@code app/cbl/CBTRN02C.cbl}, {@code app/jcl/POSTTRAN.jcl} and {@code app/jcl/DALYREJS.jcl} for a
 * 500-byte reject layout or allocation found no such declaration anywhere: no picture clause, no
 * record length, no record size and no space allocation of 500 exists in any of the three members.
 * The only matches on that digit sequence are paragraph-number prefixes - the transaction-balance
 * open and close paragraphs, the validation paragraph and its two lookup subparagraphs, and the
 * reject-write paragraph itself - which are statement labels and carry no width. A 500-byte reject
 * layout is therefore not a discrepancy that this class reconciles or annotates; it is an artefact
 * that does not exist, and no phantom anomaly is recorded for it.
 *
 * <p><strong>The reject dataset's own definition member carries misleading documentation, and it is
 * documentation only.</strong> {@code app/jcl/DALYREJS.jcl} line 19 carries a banner announcing the
 * deletion of a transaction master file, while the single step it introduces, at lines 21 to 28,
 * defines a generation data group for the reject dataset and deletes nothing whatsoever; the banner
 * also misspells the word "transaction". Both are comment text. Neither the claim nor the spelling
 * error has any effect on the dataset's geometry, on its disposition, or on anything this writer
 * emits, and neither is reproduced here as behaviour. The retention limit declared in that member
 * governs how many generations are kept and has nothing to do with record width.
 *
 * <h2>Failure handling</h2>
 *
 * <p>A technical output failure is not a business outcome and is never recoded as one. The legacy
 * write paragraph, at {@code app/cbl/CBTRN02C.cbl} lines 446 to 465, arms a sentinel, writes, and on
 * any status other than success emits an ordered diagnostic and then terminates the program through
 * the abort routine; it does not, and cannot, turn a failed write into one of the five reject reason
 * codes, because those codes describe the transaction and a failed write describes the dataset.
 *
 * <p>This writer reproduces that separation by propagating. Every failure the destination reports
 * leaves this class unchanged and untranslated, so the owning step and the batch template can run the
 * structured diagnostic-before-abend sequence they own. In particular this class swallows nothing,
 * substitutes no reason code, writes no partial record in place of a failed one, and emits no
 * competing error diagnostic of its own that would disorder the sequence the template emits.
 *
 * <h2>Observability</h2>
 *
 * <p>Whole-step timing belongs to the step, not to the writer: the batch step template times one
 * whole legacy program lifecycle, and that is the figure the performance baseline is stated in. The
 * two meters registered here measure one narrower thing each - how long a single chunk write took,
 * and how many reject records have been written - and are a complement to whole-step timing, never a
 * substitute for it. Both are registered against the injected registry so both are exposed on the
 * same metrics endpoint the step's own timer is.
 *
 * <p>The record count has a direct legacy antecedent. The program counts rejects as it writes them
 * and, at {@code app/cbl/CBTRN02C.cbl} lines 229 to 231, sets a non-zero return code when the count
 * is above zero. {@link #recordsWritten()} exposes the same figure so the owning job configuration
 * can reproduce that outcome without recounting the dataset. The count is a {@code long} throughout;
 * no value in this class is ever a floating-point type, and no decimal is scaled here - the amount
 * carried by the source record is encoded by the mapper and its codec, which own the estate's
 * truncating rounding policy.
 *
 * <p>Logging is deliberately sparse and deliberately incurious. Two lifecycle lines are emitted at
 * debug level, built only from this class's own constants and a record count. No record image, no
 * field of a record, no card number, no monetary amount and no destination path is ever logged, so
 * neither regulated data nor caller-supplied text can reach the log through this class.
 *
 * <h2>Lifecycle, restart and scope</h2>
 *
 * <p>This writer participates in the framework's stream lifecycle so that a restarted step resumes
 * rather than duplicating: the destination position and the count of written records are checkpointed
 * into the step's execution context on update and restored on open, and the destination is truncated
 * back to the last committed position rather than appended to. Nothing about the destination is
 * appended, because the legacy allocation creates a brand-new generation on every run.
 *
 * <p>All per-execution state - the destination handle, its position and the record count - lives
 * inside this instance and nowhere else. There is no static, global or otherwise shared mutable
 * state, and no comparator, buffer or scratch object is shared between instances. Because the
 * instance does hold the position of an open destination, the owning configuration must give each
 * step execution its own writer, either by declaring the bean in step scope or by constructing one
 * per execution; a single instance must not be shared across concurrent step executions. The
 * destination is supplied once, at construction, and cannot be swapped afterwards, which is what
 * makes that requirement checkable.
 *
 * <p>Execution is strictly sequential, as a batch program's is. There is no task executor, no
 * partitioning and no asynchronous hook here, and none may be added: reject records are emitted in
 * the order the transactions were read, and reordering them would change the dataset.
 *
 * <h2>Traceability</h2>
 *
 * <p>Translated from {@code app/cbl/CBTRN02C.cbl} paragraph {@code 2500-WRITE-REJECT-REC}, lines 446
 * to 465, together with the two working-storage moves that fill the record and the trailer. The five
 * reason codes it can carry are raised elsewhere in the same program - at lines 385 to 387, 397 to
 * 399, 410 to 412, 417 to 419 and 556 to 558 - and are modelled by {@link RejectReason}, which this
 * class consumes rather than duplicates.
 *
 * <p>One legacy quirk is recorded here because it explains an apparent redundancy rather than
 * licensing its removal. The last of those five codes is raised while the account balances are being
 * written back, after the program has already chosen between posting and rejecting at lines 211 to
 * 216, and the reason field is cleared again at lines 208 to 209 before the next transaction is
 * validated. In the legacy program that code therefore never actually reaches the reject dataset. It
 * is nonetheless a declared value of the trailer's reason field, it is distinct from the earlier code
 * that carries identical description text, and this writer emits either one faithfully when it is
 * given one. The two are not merged, aliased or collapsed.
 *
 * <h2>Standards</h2>
 *
 * <p>No user-specified rules were provided for this migration, so this file is held to
 * enterprise-standard best practice instead: constructor injection with no field injection, an
 * immutable public surface, explicit imports with no wildcard and no static import, no reflection and
 * no generated code, no raw or dynamically assembled query text, no process invocation, no console
 * stream, and compilation under all lint categories with warnings promoted to errors and with no
 * warning suppressed anywhere.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the CardDemo mainframe estate at commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. No COBOL, JCL, BMS, copybook or CICS
 * resource text is transcribed here: the legacy source is cited by member, paragraph, field and line
 * number and its statements are described rather than quoted, and nothing in this class reads the
 * legacy tree at run time.
 */
public final class RejectRecordWriter implements ItemStreamWriter<RejectRecordWriter.RejectedTransaction> {

    /**
     * Diagnostic channel for this writer, replacing the console display statements that were the
     * legacy program's only instrumentation.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RejectRecordWriter.class);

    /** The legacy batch program whose reject-write paragraph this class stands in for. */
    public static final String LEGACY_PROGRAM = "CBTRN02C";

    /**
     * The legacy DD name of the reject dataset, used as the resource tag on this writer's meters and
     * in its lifecycle diagnostics. It is a fixed constant and never caller-supplied text.
     */
    public static final String LEGACY_DD_NAME = "DALYREJS";

    /**
     * Layout name carried into every diagnostic raised here; it names the record group and the DD
     * that receives it, mirroring the way each record mapper names its own layout.
     */
    public static final String ARTEFACT = "REJECT-RECORD (DALYREJS)";

    /**
     * Width of the source-record segment, taken from the mapper that owns the daily-transaction
     * layout rather than restated, so the two can never disagree.
     */
    public static final int SOURCE_IMAGE_LENGTH = DailyTransactionRecordMapper.RECORD_LENGTH;

    /** Width of the numeric reject-reason field of the validation trailer. */
    public static final int FAIL_REASON_LENGTH = 4;

    /** Width of the fixed-width description field of the validation trailer. */
    public static final int FAIL_REASON_DESC_LENGTH = 76;

    /** Width of the whole validation trailer: the reason field followed by the description field. */
    public static final int VALIDATION_TRAILER_LENGTH = 80;

    /** Width of the whole reject record: the source-record segment followed by the trailer. */
    public static final int REJECT_RECORD_LENGTH = 430;

    /** The character that pads the description field to its fixed width. */
    public static final char PAD_CHARACTER = ' ';

    /** The digit that fills the reason field on the left when the code needs fewer than four digits. */
    public static final char REASON_FILL_DIGIT = '0';

    /**
     * The separator emitted after a record: none.
     *
     * <p>Empty because the dataset is fixed-length unblocked, so records abut one another with no
     * delimiter. Stated as a named constant, and set explicitly on the destination, because the
     * framework's default is the platform line ending and inheriting it would corrupt every record.
     */
    public static final String RECORD_SEPARATOR = "";

    /**
     * Canonical name of the charset every byte of this dataset is encoded in.
     *
     * <p>Named explicitly and never inherited: the platform default charset is not consulted when
     * assembling the image, when measuring a width, or when writing to the destination.
     */
    public static final String OUTPUT_CHARSET_NAME = StandardCharsets.US_ASCII.name();

    /**
     * Key prefix under which the destination position and the written-record count are checkpointed
     * in the step's execution context. Constant, so a restart finds the keys the previous execution
     * saved.
     */
    public static final String EXECUTION_CONTEXT_NAME = "rejectRecordWriter";

    /**
     * Highest code point that survives US-ASCII encoding unchanged.
     *
     * <p>Used to prove purity before a width is measured. An encoder asked to emit a character above
     * this substitutes a single replacement byte for it, which keeps the byte count right and makes
     * the content wrong; rejecting the character instead is the only way a width check can be trusted.
     */
    private static final int HIGHEST_ASCII_CODE_POINT = 0x7F;

    /** The pad character as a string, so the fixed-width padding can be built by repetition. */
    private static final String PAD = String.valueOf(PAD_CHARACTER);

    /** The reason-field fill digit as a string, for the same reason. */
    private static final String REASON_FILL = String.valueOf(REASON_FILL_DIGIT);

    /**
     * Name of the timer measuring one chunk write to the reject dataset.
     *
     * <p>Narrower than the batch step template's lifecycle timer on purpose, and not a replacement for
     * it: this measures the destination write alone, whereas a migration baseline is stated in whole
     * legacy program lifecycles.
     */
    private static final String METRIC_WRITE = "carddemo.batch.reject.write";

    /** Name of the counter accumulating reject records written, the metric form of the legacy count. */
    private static final String METRIC_RECORDS = "carddemo.batch.reject.records";

    /** Tag key naming the legacy dataset the meter refers to. */
    private static final String TAG_RESOURCE = "resource";

    /** Tag key separating a write that completed from one that failed. */
    private static final String TAG_OUTCOME = "outcome";

    /** Tag value for a chunk the destination accepted in full. */
    private static final String OUTCOME_WRITTEN = "WRITTEN";

    /** Tag value for a chunk whose write reported a failure, which is always propagated. */
    private static final String OUTCOME_FAILED = "FAILED";

    /**
     * Checks the declared geometry once, when the class initialises: that the two trailer fields sum
     * to the trailer width, and that the source segment and the trailer sum to the record width.
     *
     * <p>The sums are checked rather than assumed because the source segment's width is taken from
     * another class. A change there that this class had not accounted for would otherwise surface as a
     * silently mis-sized dataset; here it surfaces the first time the class is touched.
     */
    static {
        requireDeclaredSum("validation trailer", VALIDATION_TRAILER_LENGTH,
                FAIL_REASON_LENGTH + FAIL_REASON_DESC_LENGTH);
        requireDeclaredSum("reject record", REJECT_RECORD_LENGTH,
                SOURCE_IMAGE_LENGTH + VALIDATION_TRAILER_LENGTH);
    }

    /**
     * One rejected transaction: the source record exactly as it was read, paired with the reason it
     * was rejected.
     *
     * <p>This is the item type the reject leg of the posting step carries. It is defined here, nested
     * and minimal, rather than as a type of its own, because it has no meaning away from the reject
     * dataset: it exists so that the two values the trailer needs travel together from the point the
     * validation cascade decides on a rejection to the point the record is written. The validation
     * processor produces it and the job configuration wires it, and neither needs anything else from
     * it.
     *
     * <p>It is immutable and total: both components are mandatory, so an item can never reach the
     * writer describing a rejection without saying which record was rejected or without saying why.
     * The source record is carried by reference and is never modified here - the writer only reads it,
     * and reads it through the mapper - so the image written is the image that was read.
     *
     * @param sourceRecord the daily-transaction record that was rejected, never {@code null}
     * @param reason       why it was rejected, never {@code null}
     */
    public record RejectedTransaction(DailyTransaction sourceRecord, RejectReason reason) {

        /**
         * Rejects an incomplete item at the point it is created rather than at the point it is
         * written, so a defect upstream cannot present itself as a malformed dataset downstream.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        public RejectedTransaction {
            Objects.requireNonNull(sourceRecord, ARTEFACT + " source record must not be null");
            Objects.requireNonNull(reason, ARTEFACT + " reject reason must not be null");
        }
    }

    /**
     * The destination this writer emits through, configured once at construction and never replaced.
     *
     * <p>A flat-file destination is used rather than a hand-rolled output stream because the framework
     * implementation already provides exactly the two behaviours that are hard to get right by hand -
     * checkpointing the destination position into the step's execution context, and truncating back to
     * the last committed position on restart - while leaving the bytes entirely under this class's
     * control through the aggregation function. Its separator is emptied and its charset is stated, so
     * the only thing it contributes to the byte stream is the images this class assembles.
     */
    private final FlatFileItemWriter<RejectedTransaction> destination;

    /** The registry the write timer is registered with, at the moment a write finishes. */
    private final MeterRegistry meterRegistry;

    /** Monotonic count of reject records written, registered once and shared across executions. */
    private final Counter rejectRecordCounter;

    /**
     * Reject records written by the current execution, which is the figure the legacy program's own
     * reject counter holds and the figure its return-code rule is stated in.
     *
     * <p>Held in an atomic long so the reference can be final and the value can be read without
     * synchronisation. It is reset when the stream opens, so it always describes the execution in
     * progress and never accumulates across a restart.
     */
    private final AtomicLong recordsWritten = new AtomicLong();

    /**
     * Binds this writer to the destination it emits through and to the registry its meters are
     * registered with.
     *
     * <p>Both collaborators are supplied by the owning job configuration and neither has a default:
     * the destination in particular is never derived, guessed or defaulted here, so no path, dataset
     * name or legacy resource name is embedded in this class. Only the two constants that name the
     * legacy program and its DD are held, and they appear in diagnostics and meter tags rather than in
     * any resolution of where the bytes go.
     *
     * <p>The destination is configured here, in full and once:
     *
     * <ul>
     *   <li>the record separator is emptied, because the dataset is fixed-length unblocked;
     *   <li>the charset is named explicitly, so the platform default is never consulted;
     *   <li>state is saved, so the position is checkpointed and a restart resumes;
     *   <li>appending is disallowed, because the legacy allocation creates a new generation each run;
     *   <li>an empty dataset is kept rather than deleted, because the legacy allocation catalogues the
     *       dataset whether or not any transaction was rejected, so a run with no rejects must leave an
     *       empty dataset rather than none;
     *   <li>writes are transactional, so a chunk that rolls back contributes no partial record;
     *   <li>and the aggregation function is a static method reference, which is also why the
     *       constructor cannot leak a partially built instance.
     * </ul>
     *
     * <p>The destination must be file-addressable, because resuming a partially written dataset at a
     * committed byte position requires a seekable channel rather than an append-only sink. A job that
     * ultimately stages the dataset elsewhere writes it to a file first and stages the finished file,
     * which also keeps the staged object a whole number of records rather than a truncated stream.
     *
     * @param destination   where the reject dataset is written, supplied by the owning job
     *                      configuration
     * @param meterRegistry the registry this writer's two meters are registered with
     * @throws NullPointerException if either argument is {@code null}
     */
    public RejectRecordWriter(final WritableResource destination, final MeterRegistry meterRegistry) {
        Objects.requireNonNull(destination, ARTEFACT + " destination resource must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry,
                ARTEFACT + " meter registry must not be null");
        this.rejectRecordCounter = Counter.builder(METRIC_RECORDS)
                .description("Reject records written to the fixed-length daily-transaction "
                        + "reject dataset")
                .baseUnit("records")
                .tag(TAG_RESOURCE, LEGACY_DD_NAME)
                .register(meterRegistry);
        this.destination = new FlatFileItemWriterBuilder<RejectedTransaction>()
                .name(EXECUTION_CONTEXT_NAME)
                .resource(destination)
                .encoding(OUTPUT_CHARSET_NAME)
                .lineSeparator(RECORD_SEPARATOR)
                .lineAggregator(RejectRecordWriter::rejectRecordImage)
                .saveState(true)
                .append(false)
                .shouldDeleteIfExists(true)
                .shouldDeleteIfEmpty(false)
                .transactional(true)
                .build();
    }

    /**
     * Assembles the whole {@value #REJECT_RECORD_LENGTH}-byte reject record for one rejected
     * transaction.
     *
     * <p>Reproduces {@code app/cbl/CBTRN02C.cbl} paragraph {@code 2500-WRITE-REJECT-REC} lines 447 and
     * 448: the source record is placed into the leading segment and the assembled trailer into the
     * segment that follows it, in that order and with nothing between them. The source segment is
     * produced by the mapper that owns the daily-transaction layout, so no offset, field width or
     * padding rule of that layout is restated here; this method contributes the trailer and the
     * concatenation, and nothing else.
     *
     * <p>Both segments and the finished image are measured in encoded US-ASCII bytes. The record is
     * returned as text carrying no terminator, because record separation belongs to the destination and
     * this dataset has no separator at all.
     *
     * <p>This method is pure and static, so the exact bytes a step would write can be produced and
     * compared byte for byte without opening a destination - which is how the byte-parity fixtures for
     * this dataset are checked.
     *
     * @param  item the rejected transaction to render, never {@code null}
     * @return the reject record image, exactly {@value #REJECT_RECORD_LENGTH} encoded bytes wide and
     *         free of any line terminator
     * @throws NullPointerException  if {@code item} is {@code null}
     * @throws IllegalStateException if either segment or the finished image is not its declared width,
     *                               or if any of it falls outside US-ASCII
     */
    public static String rejectRecordImage(final RejectedTransaction item) {
        Objects.requireNonNull(item, ARTEFACT + " rejected transaction must not be null");
        final String sourceSegment = requireExactWidth("REJECT-TRAN-DATA",
                DailyTransactionRecordMapper.toRecord(item.sourceRecord()), SOURCE_IMAGE_LENGTH);
        final String trailerSegment = validationTrailer(item.reason());
        return requireExactWidth("REJECT-RECORD", sourceSegment + trailerSegment,
                REJECT_RECORD_LENGTH);
    }

    /**
     * Assembles the whole reject record for one rejected transaction and returns it as bytes.
     *
     * <p>Byte-for-byte identical to {@link #rejectRecordImage(RejectedTransaction)} and derived from it
     * rather than assembled independently, so the two can never disagree. The array is fresh and
     * unshared and carries no terminator.
     *
     * @param  item the rejected transaction to render, never {@code null}
     * @return a new array of exactly {@value #REJECT_RECORD_LENGTH} US-ASCII bytes
     * @throws NullPointerException  if {@code item} is {@code null}
     * @throws IllegalStateException on exactly the same conditions as
     *                               {@link #rejectRecordImage(RejectedTransaction)}
     */
    public static byte[] rejectRecordImageBytes(final RejectedTransaction item) {
        final byte[] image = rejectRecordImage(item).getBytes(StandardCharsets.US_ASCII);
        if (image.length != REJECT_RECORD_LENGTH) {
            throw new IllegalStateException(ARTEFACT + " encoded to " + image.length
                    + " bytes rather than the declared " + REJECT_RECORD_LENGTH);
        }
        return image;
    }

    /**
     * Assembles the {@value #VALIDATION_TRAILER_LENGTH}-byte validation trailer for one reject reason.
     *
     * <p>Reproduces the working-storage trailer declared at {@code app/cbl/CBTRN02C.cbl} lines 180 to
     * 182: the four-digit reason field followed immediately by the 76-character description field,
     * with nothing between them and nothing after them.
     *
     * @param  reason the reject reason the trailer reports, never {@code null}
     * @return the trailer image, exactly {@value #VALIDATION_TRAILER_LENGTH} encoded bytes wide
     * @throws NullPointerException  if {@code reason} is {@code null}
     * @throws IllegalStateException if either field or the assembled trailer is not its declared width
     */
    public static String validationTrailer(final RejectReason reason) {
        Objects.requireNonNull(reason, ARTEFACT + " reject reason must not be null");
        return requireExactWidth("WS-VALIDATION-TRAILER",
                failReasonField(reason) + failReasonDescriptionField(reason),
                VALIDATION_TRAILER_LENGTH);
    }

    /**
     * Renders the {@value #FAIL_REASON_LENGTH}-byte numeric reason field of the validation trailer.
     *
     * <p>The field is numeric and fixed-width, so the code is rendered with leading zeros to the full
     * width: reason 100 becomes {@code 0100}. Emitting {@code 100} instead would shift the description
     * one byte to the left and shorten the record, which is why the padding is part of the contract
     * rather than a presentation choice.
     *
     * <p>The digits are produced by locale-independent integer conversion. Locale-sensitive number
     * formatting is deliberately avoided: under a locale whose default numbering system is not
     * Western Arabic it would emit digits outside US-ASCII, and the dataset would become unreadable in
     * a way no width check would reveal.
     *
     * @param  reason the reject reason whose code is rendered, never {@code null}
     * @return the reason field, exactly {@value #FAIL_REASON_LENGTH} encoded bytes wide
     * @throws NullPointerException  if {@code reason} is {@code null}
     * @throws IllegalStateException if the code is negative or needs more digits than the field
     *                               provides, both of which mean the reason contract has changed
     */
    public static String failReasonField(final RejectReason reason) {
        Objects.requireNonNull(reason, ARTEFACT + " reject reason must not be null");
        final int reasonCode = reason.getReasonCode();
        if (reasonCode < 0) {
            throw new IllegalStateException(ARTEFACT + " reject reason " + reason.name()
                    + " carries code " + reasonCode
                    + ", but the trailer's reason field is unsigned and admits no sign byte");
        }
        final String digits = Integer.toString(reasonCode);
        if (digits.length() > FAIL_REASON_LENGTH) {
            throw new IllegalStateException(ARTEFACT + " reject reason " + reason.name()
                    + " carries code " + reasonCode + ", which needs " + digits.length()
                    + " digits and does not fit the " + FAIL_REASON_LENGTH + "-digit reason field");
        }
        return requireExactWidth("WS-VALIDATION-FAIL-REASON",
                REASON_FILL.repeat(FAIL_REASON_LENGTH - digits.length()) + digits,
                FAIL_REASON_LENGTH);
    }

    /**
     * Renders the {@value #FAIL_REASON_DESC_LENGTH}-byte description field of the validation trailer.
     *
     * <p>The description is placed left-justified and the remainder of the field is filled with the pad
     * character, exactly as a fixed-width character field is filled. The text itself is taken verbatim:
     * nothing is trimmed, folded, reworded, abbreviated or truncated, because the text is contractual
     * output that operators and downstream tooling match on.
     *
     * <p>A description wider than the field is refused rather than shortened. There is no
     * source-authorised truncation of this field anywhere in the legacy program - every description it
     * emits is well inside the width - so a value that did not fit could only mean the reason contract
     * had changed, and silently cutting it would corrupt the field it overflowed into.
     *
     * @param  reason the reject reason whose description is rendered, never {@code null}
     * @return the description field, exactly {@value #FAIL_REASON_DESC_LENGTH} encoded bytes wide
     * @throws NullPointerException  if {@code reason} is {@code null}
     * @throws IllegalStateException if the description falls outside US-ASCII, is wider than the field,
     *                               or does not pad to the declared width
     */
    public static String failReasonDescriptionField(final RejectReason reason) {
        Objects.requireNonNull(reason, ARTEFACT + " reject reason must not be null");
        final String description = reason.getDescription();
        final int width = requireUsAsciiWidth("WS-VALIDATION-FAIL-REASON-DESC", description);
        if (width > FAIL_REASON_DESC_LENGTH) {
            throw new IllegalStateException(ARTEFACT + " reject reason " + reason.name()
                    + " carries a description of " + width + " bytes, wider than the "
                    + FAIL_REASON_DESC_LENGTH
                    + "-byte description field, and no truncation of this field is authorised");
        }
        return requireExactWidth("WS-VALIDATION-FAIL-REASON-DESC",
                description + PAD.repeat(FAIL_REASON_DESC_LENGTH - width),
                FAIL_REASON_DESC_LENGTH);
    }

    /**
     * Opens the destination for one step execution, restoring the checkpointed position when the
     * execution is a restart.
     *
     * <p>Stands in for the legacy open paragraph {@code 0300-DALYREJS-OPEN}, with the difference that
     * the legacy program always started a new generation whereas a restarted step resumes the
     * generation it was writing: the destination is positioned at the last committed record boundary
     * and continues from there, so no record is written twice and none is lost.
     *
     * <p>The execution's own record count is reset here, before the destination is touched, so that the
     * count always describes the execution in progress.
     *
     * @param executionContext the step's execution context, from which restart state is read
     * @throws NullPointerException if {@code executionContext} is {@code null}
     * @throws ItemStreamException  if the destination cannot be opened or its restart state cannot be
     *                              restored
     */
    @Override
    public void open(final ExecutionContext executionContext) throws ItemStreamException {
        Objects.requireNonNull(executionContext, ARTEFACT + " execution context must not be null");
        this.recordsWritten.set(0L);
        this.destination.open(executionContext);
        LOGGER.debug("Opened the {}-byte reject dataset behind legacy DD {} of program {}",
                REJECT_RECORD_LENGTH, LEGACY_DD_NAME, LEGACY_PROGRAM);
    }

    /**
     * Writes one chunk of rejected transactions as that many whole reject records.
     *
     * <p>Reproduces the reject leg of the legacy program's main loop, at
     * {@code app/cbl/CBTRN02C.cbl} lines 214 and 215, where each rejected transaction increments the
     * reject count and is written by the reject-write paragraph. The records are emitted in the order
     * the chunk holds them, which is the order the transactions were read; each contributes exactly
     * {@value #REJECT_RECORD_LENGTH} bytes and nothing else, so the length of the stream this method
     * has contributed is always the number of records written multiplied by
     * {@value #REJECT_RECORD_LENGTH}.
     *
     * <p>Failure is propagated, never translated. Anything the destination reports - a failed write, an
     * exhausted volume, a destination that was never opened - leaves this method unchanged so that the
     * owning step and the batch template can emit the ordered diagnostic and abend, exactly as the
     * legacy paragraph does at lines 460 to 463. No reject reason code is ever manufactured from a
     * technical failure, and no partial or substitute record is written in place of one that failed.
     *
     * <p>The chunk's records are counted only once the destination has accepted the whole chunk, so a
     * failed write does not inflate the count. The write is timed either way and the outcome is
     * distinguished by a tag, with the timer stopped on the way out so that a propagating failure is
     * still measured and is still propagated unchanged.
     *
     * @param chunk the rejected transactions to write, never {@code null}; an empty chunk contributes
     *              no bytes
     * @throws NullPointerException if {@code chunk} is {@code null}
     * @throws Exception            whatever the destination reports, unwrapped and unaltered
     */
    @Override
    public void write(final Chunk<? extends RejectedTransaction> chunk) throws Exception {
        Objects.requireNonNull(chunk, ARTEFACT + " chunk must not be null");
        final Timer.Sample sample = Timer.start(this.meterRegistry);
        boolean accepted = false;
        try {
            this.destination.write(chunk);
            accepted = true;
        } finally {
            recordWriteDuration(sample, accepted ? OUTCOME_WRITTEN : OUTCOME_FAILED);
        }
        final int written = chunk.size();
        this.recordsWritten.addAndGet(written);
        this.rejectRecordCounter.increment(written);
    }

    /**
     * Checkpoints the destination position and the written-record count into the step's execution
     * context, so that a restart resumes at the last committed record boundary.
     *
     * @param executionContext the step's execution context, into which restart state is written
     * @throws NullPointerException if {@code executionContext} is {@code null}
     * @throws ItemStreamException  if the destination's position cannot be determined
     */
    @Override
    public void update(final ExecutionContext executionContext) throws ItemStreamException {
        Objects.requireNonNull(executionContext, ARTEFACT + " execution context must not be null");
        this.destination.update(executionContext);
    }

    /**
     * Closes the destination, flushing the last buffered record so that the dataset ends on a whole
     * record boundary.
     *
     * <p>Stands in for the legacy close paragraph {@code 9300-DALYREJS-CLOSE}. A failure to close is
     * terminal in the legacy program and is terminal here too: it propagates, and the completion
     * diagnostic below it is not reached.
     *
     * @throws ItemStreamException if the destination cannot be closed cleanly
     */
    @Override
    public void close() throws ItemStreamException {
        this.destination.close();
        LOGGER.debug("Closed the {}-byte reject dataset behind legacy DD {} after {} reject record(s)",
                REJECT_RECORD_LENGTH, LEGACY_DD_NAME, this.recordsWritten.get());
    }

    /**
     * Returns how many reject records the current execution has written.
     *
     * <p>The Java equivalent of the legacy program's own reject counter, and exposed because the legacy
     * program's outcome depends on it: at {@code app/cbl/CBTRN02C.cbl} lines 229 to 231 a non-zero
     * count raises the job's return code. The owning job configuration reads this rather than
     * recounting the dataset.
     *
     * <p>Counted at the moment the destination accepts a chunk, which is where the legacy program
     * counts as well. The figure covers the current execution only: it is reset when the stream opens,
     * so a restart reports what the restarted execution wrote rather than a running total.
     *
     * @return the number of reject records written by this execution, never negative
     */
    public long recordsWritten() {
        return this.recordsWritten.get();
    }

    /**
     * Stops the write timer, tagged with the dataset it refers to and with whether the write completed.
     *
     * <p>The timer carries no threshold, no percentile and no service level. The migration has no
     * documented performance baseline to compare against, so this establishes one; histogram and
     * percentile configuration is a deployment concern and belongs in configuration, not here.
     *
     * @param sample  the sample started immediately before the write
     * @param outcome whether the destination accepted the chunk or reported a failure
     */
    private void recordWriteDuration(final Timer.Sample sample, final String outcome) {
        sample.stop(Timer.builder(METRIC_WRITE)
                .description("Elapsed time of one chunk write to the fixed-length "
                        + "daily-transaction reject dataset")
                .tag(TAG_RESOURCE, LEGACY_DD_NAME)
                .tag(TAG_OUTCOME, outcome)
                .register(this.meterRegistry));
    }

    /**
     * Checks that a field or a whole image is exactly its declared width in encoded US-ASCII bytes, and
     * returns it unchanged when it is.
     *
     * <p>The width is measured on the encoded form, never on the character count, because the character
     * count is not the contract. Purity is proved first, so the measurement cannot be satisfied by a
     * substitution byte standing in for a character the charset cannot represent.
     *
     * @param  field    the legacy field or record group being checked, named in any diagnostic
     * @param  value    the assembled value
     * @param  expected the declared width in bytes
     * @return {@code value}, unchanged
     * @throws NullPointerException  if {@code value} is {@code null}
     * @throws IllegalStateException if {@code value} is not exactly {@code expected} bytes wide, or if
     *                               any of it falls outside US-ASCII
     */
    private static String requireExactWidth(final String field, final String value,
            final int expected) {
        final int width = requireUsAsciiWidth(field, value);
        if (width != expected) {
            throw new IllegalStateException(ARTEFACT + " field " + field + " is " + width
                    + " encoded bytes wide rather than the declared " + expected);
        }
        return value;
    }

    /**
     * Proves that a value is representable in US-ASCII and returns its encoded width in bytes.
     *
     * <p>Both halves matter. The purity check rejects any character above the highest US-ASCII code
     * point, including one half of a surrogate pair, because an encoder would silently replace it with a
     * single substitution byte; the width is then measured on the encoded form, which is the form the
     * fixed-width contract is stated in.
     *
     * @param  field the legacy field or record group being checked, named in any diagnostic
     * @param  value the value to check
     * @return the encoded width of {@code value} in US-ASCII bytes
     * @throws NullPointerException  if {@code value} is {@code null}
     * @throws IllegalStateException if any character of {@code value} falls outside US-ASCII
     */
    private static int requireUsAsciiWidth(final String field, final String value) {
        Objects.requireNonNull(value, ARTEFACT + " field " + field + " must not be null");
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) > HIGHEST_ASCII_CODE_POINT) {
                throw new IllegalStateException(ARTEFACT + " field " + field + " carries a character "
                        + "outside US-ASCII at position " + position
                        + ", which the fixed-width contract cannot represent");
            }
        }
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Checks that a declared width equals the sum of the fields it is composed of.
     *
     * <p>Invoked only from the static initialiser, so a geometry that does not add up prevents the
     * class from initialising at all rather than producing a mis-sized dataset at run time.
     *
     * @param part     the record group whose width is being checked, named in any diagnostic
     * @param declared the declared width
     * @param summed   the sum of the widths of the fields that make it up
     * @throws IllegalStateException if the two differ
     */
    private static void requireDeclaredSum(final String part, final int declared, final int summed) {
        if (declared != summed) {
            throw new IllegalStateException(ARTEFACT + " declares a " + part + " width of " + declared
                    + " bytes, which does not equal the " + summed + " bytes its fields sum to");
        }
    }
}
