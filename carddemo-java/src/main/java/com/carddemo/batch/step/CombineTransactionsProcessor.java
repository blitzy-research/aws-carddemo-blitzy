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

import com.carddemo.domain.Transaction;
import com.carddemo.util.SensitiveLogRedactor;
import com.carddemo.util.TransactionRecordMapper;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

/**
 * Carries one transaction of the combined stream, unaltered and in the stream position it arrived
 * in, from the combine-transactions job's ordering stage into the transaction master.
 *
 * <h2>What the legacy job did, and what replaces it</h2>
 *
 * <p>The legacy combine job has two steps and <strong>runs no application program in either of
 * them</strong>. The first invokes the platform's external sort utility over two concatenated
 * sequential inputs and writes one new combined generation; the second invokes the platform's copy
 * utility, whose entire control statement is a single copy card naming an input and an output, to
 * load that combined generation into the transaction master. Because neither step runs a COBOL
 * program there is no procedure division, no read loop and no paragraph structure to translate -
 * which is exactly why nothing here derives from {@link AbstractCobolStep}: that template exists to
 * reproduce the open, read-loop, status-normalisation, close and abend skeleton of the ten batch
 * programs, and this job has none of it. Its diagnose-then-fail ordering and its whole-step
 * instrumentation still govern the two steps, but they are applied by the job configuration around a
 * chunk-oriented step rather than inherited by a tasklet.
 *
 * <p>The external sort becomes ordinary in-process ordering inside {@code CombineTransactionsJobConfig}.
 * The copy utility becomes an ordinary reader, this processor, and a
 * writer that saves through {@code com.carddemo.repository.TransactionRepository}. No operating
 * system utility, shell command, process, container command or external tool is invoked anywhere on
 * that path, and nothing on it reads the legacy tree at run time.
 *
 * <h2>Why this class is deliberately narrow</h2>
 *
 * <p>Reader concatenation, the ordering comparator, the sequential wiring of the two steps, the
 * combined generation itself and the repository write are all owned by {@code
 * CombineTransactionsJobConfig}. What is left is strictly per-record, and it is what this class
 * does: hand back the record exactly as received, in the position it was received, and refuse to
 * pass on anything that can no longer be rendered at the contracted record width. Adding a
 * comparator, a merge helper, a staging wrapper, an accumulated list or a second ordering pass here
 * would duplicate the job's own responsibilities and give the module two places where the same
 * decision is taken - which is how the two places eventually disagree.
 *
 * <h2>Input order, and what happens when two keys are equal</h2>
 *
 * <p>The ordering stage reads <strong>two current generations, in this order: the transaction backup
 * current generation first, then the synthesized-transaction current generation</strong>. Both are
 * current inputs. Neither is a new generation - the only new generation in the job is the combined
 * output - so nothing in this pipeline may treat either input as freshly minted or reorder them for
 * recency.
 *
 * <p>The legacy sort declares one key and no summing or duplicate-elimination option whatsoever, so
 * equal-key records are neither removed nor merged: every record on both inputs reaches the output,
 * and records that share an identifier stay in stream order, backup before synthesized. In Java that
 * outcome is guaranteed rather than incidental, because the ordering the parent applies is stable
 * and a stable sort leaves equal elements in their encounter order. <strong>This processor preserves
 * the order it is handed and never sorts, re-sorts, re-groups or re-keys anything.</strong> It never
 * deduplicates, never aggregates, never overwrites and never prefers one origin over the other
 * merely because two identifiers match.
 *
 * <p>If the parent chooses to carry a record's origin through its ordering stage in an item wrapper
 * of its own, it must unwrap before this processor is reached. Origin is a property of the stream,
 * not a field of the record: the layout has no room for it and no meaning for it, so it must never
 * reach the fixed-width image.
 *
 * <h2>The identifier is characters, and the comparison belongs elsewhere</h2>
 *
 * <p>The sort key is the transaction identifier: sixteen characters beginning at one-based position
 * one of the record, compared as characters, ascending, with no secondary key. This class
 * <strong>declares no comparator, no comparator registry, no comparator helper and no static
 * comparison method of any kind</strong>, and it imports none from another job. That is not
 * fastidiousness: a different job in the same estate types the same physical field as zoned decimal
 * in its own sort specification, so a comparator shared between the two would silently hand one of
 * them the other's semantics, and nothing would fail to compile. The comparator therefore stays
 * private to the job that owns its specification.
 *
 * <p>Nor does anything here parse the identifier as a long, a big integer, a zoned decimal value, a
 * universally unique identifier or a date, and nothing trims it or case folds it. Its sixteen
 * characters, leading zeros included, are the contract - {@link Transaction} compares and hashes it
 * character for character for the same reason.
 *
 * <h2>Width fidelity is checked, not assumed</h2>
 *
 * <p>Every record image on both inputs and on the combined output is exactly
 * {@value #COMBINED_RECORD_LENGTH} encoded US-ASCII bytes, and the combined generation inherits that
 * width from its inputs rather than declaring one of its own. Each record therefore keeps every
 * field and every trailing space unchanged: the overpunched sign in the amount's final byte, the
 * card number, both twenty-six character timestamps and the twenty-byte filler run.
 *
 * <p>{@link TransactionRecordMapper} is this module's single authority for that layout, so the check
 * delegates to it: the record is rendered through the mapper and the resulting width is measured on
 * the <strong>encoded byte array</strong>. A character count is not a width - a single non-ASCII
 * character would satisfy one and breach the other - so no character count is taken anywhere in this
 * class. No offset, no slice and no field rule is restated here, and the mapper's own field-level
 * validation is delegated to rather than duplicated.
 *
 * <p>The check earns its cost by catching the one failure that would otherwise pass silently: a
 * record that arrived at the contracted width but whose entity can no longer be rendered at it,
 * because a mapped property is absent, a character value has outgrown its field, a value is not
 * representable in US-ASCII or an amount needs more digits than its field provides. Such a row would
 * load without complaint and then break the byte fidelity of the next backup generation taken from
 * the master - one full cycle away from the code that caused it.
 *
 * <h2>A filtered record is a lost record, so nothing is ever filtered</h2>
 *
 * <p>A processor that returns {@code null} tells Spring Batch to filter the item, and the step
 * completes reporting a filter count rather than a failure. The legacy copy utility copies every
 * record it reads, so a filtered record here would be a record the legacy job loaded and this one
 * silently did not. {@link #process(Transaction)} therefore <strong>either returns the very instance
 * it was given or throws</strong>; it has no third outcome, and it applies no business validation,
 * no fee logic, no filtering and no feature behaviour of any kind. The instance is returned rather
 * than copied because a copy is an opportunity for a field to diverge and buys nothing: the record
 * is not modified.
 *
 * <p>The master's key remains the sixteen-character business identifier the record already carries.
 * No surrogate key is generated, requested or implied here.
 *
 * <h2>Failure is technical, and technical failure is terminal</h2>
 *
 * <p>The legacy combine job carries <strong>no condition-code gate between its two steps</strong>,
 * so none is invented here: a technical failure stops the flow through ordinary Spring Batch failure
 * semantics, and there is no tolerated skip path, no retry budget and no reject route. A technical
 * fault is never converted into a business reject - a reject is a posting-time verdict on a record's
 * content, and this stage renders no verdict on content at all - and it is never swallowed.
 *
 * <p>A mapper fault propagates unwrapped, because the mapper's own diagnostic already names the
 * offending field or byte and re-wrapping it would hide that. The one condition this class raises
 * itself is a breach of its own postcondition, and it raises {@link IllegalStateException} for it
 * after emitting a single diagnostic naming the transaction identifier and the two widths - the same
 * reasoning the mapper records for its own choice of a platform precondition exception: the
 * condition has no legacy antecedent, because legacy records are fixed length by construction, so
 * dressing it as a legacy abend would misattribute it.
 *
 * <p>One neighbouring gate is deliberately <em>not</em> read as licence to add one here. The backup
 * job that mints the first of the two inputs does carry a return-code gate on a later step, and that
 * gate is the strict form the migration plan freezes for all four of the estate's gates: it admits a
 * prior return code of zero and refuses everything above it. That is upstream cycle context for the
 * job that produces an input, not a property of the combine job, and it is recorded here because the
 * two are easy to conflate. The differently spelled literal on that member, and why the plan's form
 * governs it, are recorded in {@code docs/decision-log.md} entry DL-145.
 *
 * <h2>Sequencing, state and instrumentation</h2>
 *
 * <p>Execution is strictly sequential and must stay so: the ordering stage runs to completion before
 * the load stage begins, and within the load stage the reader's order carries through this processor
 * to the writer unchanged. No task executor, partitioning, parallel stream, concurrent writer or
 * asynchronous merge may be introduced on this path, because every one of them interleaves work and
 * an interleaved load destroys the very ordering the first stage exists to establish.
 *
 * <p>The class holds no field, no counter, no accumulated list and no stream position, so it is safe
 * to register once as a singleton and it cannot become the place where a second ordering pass or a
 * hidden deduplication accumulates. Nothing here fixes a chunk size, a skip or retry limit, a
 * timeout, a backoff, a thread count, a heap figure, a connection figure or a throughput target;
 * every one of those is the job configuration's to state, and none of them is stated as a constant
 * anywhere in this file.
 *
 * <p>Instrumentation is coordinated rather than duplicated. Both of the job's real steps are timed
 * where a step is timed - by the batch tier's own step-level meters, which is what makes the
 * ordering stage and the load stage separately visible on the metrics endpoint. Per-item timing here
 * could not substitute for that, since it would measure neither reading nor writing nor the step's
 * own overhead, so this class registers no meter and takes no meter registry: it has no collaborator
 * to inject, and pretending otherwise would put a dependency in a constructor purely to look busy.
 *
 * @see Transaction
 * @see TransactionRecordMapper
 * @see AbstractCobolStep
 * @since 1.0.0
 */
public final class CombineTransactionsProcessor implements ItemProcessor<Transaction, Transaction> {

    /**
     * Diagnostics for this stage. Used at exactly one point - the postcondition breach - because
     * that is the only place a diagnostic is genuinely owed. A record is never logged, in whole or
     * in part beyond its identifier: the amount is financial data and the card number is a primary
     * account number, and neither belongs in a log line.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CombineTransactionsProcessor.class);

    /**
     * Name of the legacy job whose two steps this processor's job replaces.
     *
     * <p>Published so that the job configuration, the traceability matrix and the tests can name the
     * same origin without restating a literal in three places. It is an identifier for a job, never
     * a path, a dataset name or a generation reference, and nothing resolves a resource from it.
     */
    public static final String LEGACY_JOB = "COMBTRAN";

    /**
     * Name of the legacy step that ordered the two concatenated inputs into one combined generation,
     * and whose work is now the parent job's ordering stage.
     */
    public static final String LEGACY_SORT_STEP = "STEP05R";

    /**
     * Name of the legacy step that copied the combined generation into the transaction master, and
     * whose work is now the parent job's repository load stage - the stage this processor sits in.
     */
    public static final String LEGACY_LOAD_STEP = "STEP10";

    /**
     * Fixed width, in encoded US-ASCII bytes, of one transaction record image on either input, on
     * the combined output and therefore on this stage's input.
     *
     * <p>Taken from {@link TransactionRecordMapper#RECORD_LENGTH} rather than restated, because the
     * layout has exactly one authority. The value is not an independent choice of the combine job:
     * the legacy combined generation inherits its record characteristics from its inputs, and the
     * job that mints the backup input declares those characteristics as a fixed
     * {@value #COMBINED_RECORD_LENGTH}-byte record. Naming it here records that inheritance where a
     * reader of this stage will look for it.
     */
    public static final int COMBINED_RECORD_LENGTH = TransactionRecordMapper.RECORD_LENGTH;

    /**
     * Creates the processor.
     *
     * <p>Declared explicitly rather than left implicit so that the absence of any injected
     * collaborator is a documented decision instead of an oversight. There is genuinely nothing to
     * inject: the ordering comparator, the readers, the combined generation, its staging resource and
     * the repository write are all held by the job configuration, and step-level instrumentation is
     * held by the step. Constructor injection is for actual collaborators, and this class has none.
     *
     * <p>The resulting instance is immutable and stateless, which is what makes it safe to register
     * once and reuse for every record of every execution.
     */
    public CombineTransactionsProcessor() {
        // Intentionally empty, and intentionally present: see the constructor documentation. There
        // is no field to initialise, because holding per-record or per-execution state here is the
        // mechanism by which a hidden second ordering pass or a hidden deduplication would arrive.
    }

    /**
     * Returns the supplied transaction unchanged, after confirming that it can still be rendered at
     * the contracted record width.
     *
     * <p>This is the whole of the per-record contract of the load stage, and the deliberate absence
     * of anything else is the point. The record is not modified, not re-keyed, not reordered relative
     * to its neighbours, not compared against any other record, not deduplicated against the master
     * and not validated against any business rule: the legacy step this replaces was a copy utility
     * reading one sequential input and writing one output, and a copy utility inspects nothing.
     *
     * <p><strong>The return value is always the very instance that was passed in.</strong> Returning
     * {@code null} would instruct Spring Batch to filter the item, which would silently drop a record
     * the legacy job loaded, so this method has exactly two outcomes: that instance, or a thrown
     * exception. Returning a copy would be no safer and strictly worse, since a copy is a place where
     * a field can diverge from the record that was read.
     *
     * <p>Order is preserved by construction rather than by effort. A chunk-oriented step presents
     * items to a processor one at a time in the order its reader produced them and writes them in the
     * order the processor returned them, so returning in place preserves the ordering established by
     * the stage before this one - backup-origin records ahead of synthesized-origin records wherever
     * two identifiers are equal. No ordering decision is taken here, and none may be added: a second
     * sort would at best repeat the first and at worst contradict it.
     *
     * <p>The width confirmation delegates entirely to {@link TransactionRecordMapper}, which renders
     * the record and validates every field against the layout it owns. Two failure modes follow from
     * that delegation and neither is caught here. A record whose character value has outgrown its
     * field, whose value is not representable in US-ASCII or whose amount needs more digits than its
     * field provides raises the mapper's own {@link IllegalArgumentException}, and one whose mapped
     * property is absent altogether raises the mapper's own {@link NullPointerException} - a
     * fixed-width record has no concept of an absent field, so the mapper refuses to emit spaces for
     * one. Either propagates unwrapped, because the mapper's message already names the offending
     * field and re-wrapping it would hide that. A rendered image of any width other than the
     * contracted one is a breach of this method's own postcondition and raises
     * {@link IllegalStateException}. All of them are technical failures: they fail the chunk, the
     * step and the job, and none is converted into a business reject or absorbed by a skip policy,
     * because the legacy job admits no gate between its steps.
     *
     * @param  item the transaction read from the combined stream; never {@code null}, as the
     *              framework's own contract for this method guarantees
     * @return exactly the instance supplied, never {@code null} and never a copy
     * @throws NullPointerException     if {@code item} is {@code null}, which would mean the caller
     *                                  breached the framework contract rather than that a record was
     *                                  absent; or, propagated from the mapper, if a mapped property
     *                                  of the record is absent
     * @throws IllegalArgumentException propagated from the mapper if the record cannot be rendered at
     *                                  the layout the mapper owns
     * @throws IllegalStateException    if the rendered image is not exactly
     *                                  {@value #COMBINED_RECORD_LENGTH} encoded bytes wide
     */
    @Override
    public Transaction process(final Transaction item) {
        Objects.requireNonNull(item, LEGACY_JOB + " " + LEGACY_LOAD_STEP
                + " received a null combined record, which the framework contract forbids");
        // Rendering the record through the mapper is the substantive half of the confirmation: the
        // mapper owns the layout and validates every field against it. The rendered image is then
        // handed to the width postcondition and otherwise discarded - the load stage writes rows
        // through the repository, not bytes, and the combined generation was written by the stage
        // before this one, so holding the image here would put record-image ownership in two places.
        requireCombinedRecordWidth(TransactionRecordMapper.toRecordBytes(item), item.getTranId());
        // The same instance, always. Nothing above this line altered it and nothing below may.
        return item;
    }

    /**
     * Confirms that a rendered record image is exactly {@value #COMBINED_RECORD_LENGTH} encoded bytes
     * wide, diagnosing before failing when it is not.
     *
     * <p>The width is measured on the <strong>encoded byte array</strong> and on nothing else. A
     * character count is not a width - a single character outside the seven-bit range satisfies one
     * and breaches the other - so no character count is taken here or anywhere in this class.
     *
     * <p>This is a postcondition on a delegate rather than an expected runtime condition: the mapper
     * builds its buffer at the declared record width and so cannot return another, which is exactly
     * why the check is cheap and why it is worth stating. It is deliberately package-visible rather
     * than private, so that the postcondition can be exercised directly by a test in this package
     * with a mis-sized image, instead of being an assertion nobody can reach. The job configuration
     * sits in another package and therefore cannot call it, so no caller outside this stage acquires
     * a new entry point.
     *
     * <p>The diagnostic precedes the failure, which is the ordering the batch tier uses throughout,
     * and it carries the artefact name, the two widths and a <strong>redacted reference</strong> to the
     * record - and nothing else, because the amount is financial data and the card number is a primary
     * account number.
     *
     * <p>A reference rather than the identifier itself, and a reference rather than nothing at all. The
     * line exists because an exception raised in a processor fails the whole chunk, after which the
     * framework may re-present that chunk item by item; without something that singles out the record at
     * the moment of first detection, the record that actually caused the failure is easily lost in the
     * re-presentation. {@link SensitiveLogRedactor#redact(String)} keeps that property while withholding
     * the value: the reference it returns is stable for a given identifier within a run, so the line and
     * the exception name the same record and a reader can tie them together, and the token is lower-case
     * ASCII hexadecimal, so an identifier read out of a corrupt fixed-width image cannot carry a control
     * byte, a delimiter or a line terminator into either the log record or the message.
     *
     * @param  renderedImage the image the mapper produced for the record
     * @param  tranId        the record's identifier, never rendered - only a redacted reference to it is
     * @throws IllegalStateException if the width differs from {@value #COMBINED_RECORD_LENGTH}
     */
    static void requireCombinedRecordWidth(final byte[] renderedImage, final String tranId) {
        final int renderedWidth = renderedImage.length;
        if (renderedWidth != COMBINED_RECORD_LENGTH) {
            // See docs/decision-log.md entry DL-177.
            final String transactionRef = SensitiveLogRedactor.redact(tranId);
            LOGGER.error("{} {}: {} rendered {} bytes for transaction {}, expected {}", LEGACY_JOB,
                    LEGACY_LOAD_STEP, TransactionRecordMapper.ARTEFACT, renderedWidth, transactionRef,
                    COMBINED_RECORD_LENGTH);
            throw new IllegalStateException(TransactionRecordMapper.ARTEFACT + " rendered "
                    + renderedWidth + " bytes for transaction " + transactionRef
                    + ", but every record of the combined stream is fixed at "
                    + COMBINED_RECORD_LENGTH + " bytes");
        }
    }
}
