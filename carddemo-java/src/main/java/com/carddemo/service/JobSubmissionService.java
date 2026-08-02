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
package com.carddemo.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.util.JclCardImageBuilder;

/**
 * The estate's only online-to-batch bridge: it hands a job-submission card stream from the online tier to
 * the batch tier, one fixed-width card per message, in order.
 *
 * <p><strong>Legacy authority.</strong> The estate contains exactly one transient-data-queue write, in the
 * transaction-report request program {@code app/cbl/CORPT00C.cbl} (transaction {@code CR00}). Two regions
 * of that member are the authority for this class. Lines 496 to 509 are the card-emitting loop: a
 * loop-control flag is cleared at 496; the loop is entered at 498 to 499 with a guard testing the one-based
 * card index against the declared array bound, the end-of-stream flag and the write-error flag; the current
 * card is moved into the eighty-character record at 501; the end-of-stream test is made at 502 to 505; and
 * the write is performed at 507. Lines 515 to 535 are the queue-write paragraph: it writes the
 * eighty-character record capturing a response code and a reason code, continues on a normal response, and
 * on any other response writes both codes to its diagnostic channel, raises the write-error flag, places a
 * fixed failure literal in the screen message field, repositions the cursor and re-sends the screen. It
 * does not abend and does not abort the transaction. Every behavioural rule below is read off those two
 * regions rather than chosen.
 *
 * <p><strong>Queue contract.</strong> The destination is declared in {@code app/csd/CARDDEMO.CSD} at lines
 * 499 to 505, and each attribute binds this class. {@code RECORDSIZE(80)} with
 * {@code RECORDFORMAT(FIXED)}: one card is one message and every body is a fixed-width eighty-character
 * image, never concatenated and never variable-length. {@code BLOCKFORMAT(UNBLOCKED)}: each card is an
 * individually addressable record, the second reason cards are published one at a time.
 * {@code DISPOSITION(MOD)}: writes append, so order is significant, and every card of one submission is
 * published into a single first-in-first-out message group. {@code ERROROPTION(IGNORE)}: a write failure is
 * ignored rather than raised &mdash; the decisive attribute, mapped in decision log entry D-36.
 * {@code TYPE(EXTRA)}, {@code DATABUFFERS(1)}, {@code DDNAME(INREADER)} and {@code OPENTIME(INITIAL)}: the
 * destination exists and is open before first use, so this class never creates, configures or describes the
 * queue; provisioning belongs to
 * {@code carddemo-java/localstack/init/01-create-aws-resources.sh} locally and to the deployment elsewhere.
 * {@code TYPEFILE(OUTPUT)}: publish-only from the online tier, so no receive, poll, peek, purge or delete
 * operation is exposed.
 *
 * <p><strong>Exactly seventeen messages, and the sentinel is transmitted.</strong> The legacy card group
 * holds seventeen eighty-byte cards and the last is the end-of-stream sentinel. The loop recognises the
 * sentinel at lines 502 to 505 and performs the write at line 507 &mdash; it sets its termination flag
 * <em>before</em> writing. So <strong>the sentinel card is itself transmitted and the loop then
 * stops</strong>: a complete submission is seventeen messages. Dropping it would break the batch-trigger
 * contract; sending an eighteenth would invent a message the legacy never sent. The legacy array's declared
 * index bound is never reached in practice, so it is reproduced here as a defensive guard only &mdash; not
 * a capacity, a batch size or a tuning value.
 *
 * <p><strong>A failed publish stops the remaining cards, and is non-fatal.</strong> The write-error flag
 * raised inside the queue-write paragraph is also one of the three conditions in the emitting loop's guard
 * at line 499, so setting it terminates the loop and the cards after the failing one are never sent. That
 * partial submission is deliberate legacy behaviour. Three observable consequences follow from
 * {@code ERROROPTION(IGNORE)} and the absence of any abend or re-raise, and all three hold here: the
 * failure is <strong>logged</strong> with its response and reason codes and underlying cause; the
 * <strong>remaining cards are not sent</strong>; and <strong>control returns normally</strong>. A
 * {@code JobSubmissionException} is therefore constructed and logged at the publish boundary and
 * <em>never</em> propagated out of this class; callers receive a {@code SubmissionResult} instead. No retry,
 * backoff, dead-letter redirect or circuit breaker is implemented: none exists in the legacy and each would
 * add a timing characteristic the migrated system must not inherit. The failure text handed back is the
 * frozen literal {@code JobSubmissionException.DEFAULT_MESSAGE} and nothing else; diagnostic detail goes to
 * the log so that text cannot drift.
 *
 * <p><strong>Fixed-width payload.</strong> Every message body is exactly
 * {@code JobSubmissionException.RECORD_SIZE} characters, asserted on the encoded bytes rather than the
 * character count. A card is never trimmed, stripped or right-justified before publishing: the trailing
 * spaces are part of the record. A card that does not satisfy the width, or holds a character not
 * representable as a single US-ASCII byte, is a programming error and is rejected with
 * {@code IllegalArgumentException}. The card content itself &mdash; the seventeen literal images, the four
 * ten-character date substitution slots and the sentinel &mdash; is owned entirely by
 * {@code JclCardImageBuilder}.
 *
 * <p><strong>Resource names arrive from configuration.</strong> No queue name, URL, ARN, account
 * identifier, region or endpoint is written into this class; the destination queue and message group are
 * bound under the {@code carddemo.aws.*} prefix and the region and endpoint belong to the injected
 * messaging client. The queue name <strong>must end in the FIFO suffix</strong>, since a first-in-first-out
 * queue is rejected by the queue service without it and ordering is contractual here, so the suffix is
 * validated when this bean is constructed and a misconfigured deployment fails at startup rather than at
 * the first submission. Neither bound value carries an inline default, matching the production profile,
 * which resolves every secret and resource name from an environment variable with no fallback.
 *
 * <p><strong>Message deduplication is an addition forced by the target technology.</strong> A
 * first-in-first-out queue requires either content-based deduplication on the queue or an explicit
 * identifier per message; the legacy transient-data queue had no deduplication concept at all. An explicit
 * identifier is supplied, composed of the submission's own identity and the one-based card ordinal. The
 * ordinal keeps the seventeen cards of one submission distinct, which content-based deduplication would
 * also achieve; the submission identity makes two submissions of the same reporting period deduplicate
 * against each other, which content-based deduplication would achieve only by accident of the card bytes
 * and a random identifier would defeat entirely. Nothing here is random, so a repeated submission is
 * idempotent within the queue service's deduplication interval. Recorded as a parity exception in
 * {@code docs/decision-log.md} DL-043.
 *
 * <h2>Exactly seventeen messages, and the sentinel is transmitted</h2>
 *
 * <p>The legacy card group holds seventeen eighty-byte cards, and the last of them is the
 * end-of-stream sentinel. The loop recognises the sentinel at lines 502 to 505 and performs the
 * write at line 507 - that is, it sets its termination flag <em>before</em> writing rather than
 * after. The consequence is exact and easy to get wrong: <strong>the sentinel card is itself
 * transmitted and the loop then stops</strong>, so a complete submission is seventeen messages, the
 * seventeenth being the sentinel. Dropping it would break the batch-trigger contract, and sending an
 * eighteenth would invent a message the legacy never sent.
 *
 * <p>The legacy card array is redefined over a group that is only seventeen cards wide, so its
 * declared index bound is never reached in practice; the sentinel always terminates the loop first.
 * That bound is reproduced here as a defensive guard only. It is not a capacity, a batch size or a
 * tuning value, and nothing in this class may be configured through it.
 *
 * <h2>A failed publish stops the remaining cards</h2>
 *
 * <p>The write-error flag raised inside the queue-write paragraph is also one of the three
 * conditions in the emitting loop's guard at line 499. Setting it therefore terminates the loop, so
 * the cards after the failing one are never sent. That partial submission is deliberate legacy
 * behaviour and is preserved here rather than smoothed over: publishing stops at the failing card
 * and the returned result reports how many cards actually reached the queue.
 *
 * <h2>Non-fatal by contract</h2>
 *
 * <p>{@code ERROROPTION(IGNORE)}, together with the absence of any abend or re-raise in the
 * queue-write paragraph, means a failure is non-fatal. Three observable consequences follow, and
 * all three hold here:
 *
 * <ol>
 *   <li>the failure is <strong>logged</strong>, carrying the response and reason codes and the
 *       underlying cause, mirroring the legacy diagnostic write;</li>
 *   <li>the <strong>remaining cards are not sent</strong>;</li>
 *   <li><strong>control returns normally</strong> - the caller's request completes.</li>
 * </ol>
 *
 * <p>A {@code JobSubmissionException} is therefore <em>constructed and logged</em> at the publish
 * boundary and is <em>never</em> propagated out of this class. Callers receive a
 * {@code SubmissionResult} describing the outcome instead. Nothing on the publish path takes part in a
 * database transaction, because this class performs no database work.
 *
 * <h2>Resilience: what this class does, and the one thing the transport does</h2>
 *
 * <p><strong>This class implements no resilience behaviour of its own.</strong> It contains no retry
 * loop, no backoff, no dead-letter redirect, no application-level circuit breaker and no re-send of a
 * card whose publish was refused. A refused card is logged once and the remaining cards are abandoned,
 * because the legacy write-error flag participates in the emitting loop's guard. Adding any of those
 * behaviours here would change the observable outcome of a failed submission, and none has a legacy
 * antecedent.
 *
 * <p><strong>The transport, however, is not single-shot, and that is a documented parity
 * exception.</strong> The queue client is the one auto-configured by the messaging starter, and the
 * AWS SDK's own default strategy classifies certain transport-level faults as retryable and re-issues
 * the request, with jittered backoff and a token-bucket breaker, <em>before</em> the call ever returns
 * to this class. Four considerations make that acceptable rather than a defect to be configured away,
 * and all four belong in the decision log:
 *
 * <ol>
 *   <li>A transport fault has <strong>no legacy antecedent</strong>. The legacy queue was an
 *       extrapartition transient-data queue writing to a sequential data set through a data-definition
 *       name; it could not experience a lost connection or a throttled endpoint. Refusing to retry a
 *       fault the legacy transport could not produce would turn a class of purely-new failures into
 *       observable submission failures the legacy never had, which is a regression against parity, not
 *       a defence of it.</li>
 *   <li>A retried send is <strong>idempotent by construction</strong>. Every message carries an
 *       explicit deduplication identifier composed of the submission identity and the card ordinal, so
 *       a retry of a send that had in fact already reached the queue is collapsed by the queue service
 *       within its deduplication interval instead of appending a seventeenth-plus card. The card image
 *       cannot be corrupted by a retry, which is precisely what the explicit identifier buys.</li>
 *   <li>The client configuration contract for this module <strong>mandates leaving the SDK defaults in
 *       place</strong>: no retry count, no backoff duration and no call timeout may be hardcoded
 *       anywhere, because the migration has no documented legacy baseline from which any such figure
 *       could be derived and the performance gate establishes a baseline rather than testing a
 *       threshold. A literal here would be an invented service level. The strategy that makes one
 *       card write exactly one attempt is supplied by {@code com.carddemo.config.AwsConfig} and is
 *       reasoned in {@code docs/decision-log.md} DL-095.</li>
 *   <li>The failure this class does observe is therefore an <em>exhausted</em> transport failure, which
 *       is the closest available analogue of the legacy bad response code: the write did not happen and
 *       will not happen. That is the condition the ignore-on-error semantics were written for.</li>
 * </ol>
 *
 * <p>No call-level bound is configured for the same reason a retry count is not. Each individual
 * attempt is nevertheless bounded by the transport's own default connect, read and write limits, so a
 * publish cannot block a caller indefinitely; the bounds are the client's, are not restated here, and
 * are deliberately not tuned.
 *
 * <p>The failure text handed back to the caller is the frozen operator-facing literal published as
 * {@code JobSubmissionException.DEFAULT_MESSAGE} and nothing else. Diagnostic detail - response
 * code, reason code, failing card ordinal and the underlying cause - goes to the log, never into the
 * operator-facing text, so that text cannot drift.
 *
 * <h2>Fixed-width payload</h2>
 *
 * <p>Every message body is exactly {@code JobSubmissionException.RECORD_SIZE} characters. The width
 * is asserted on the encoded bytes rather than on the character count, and a card is never trimmed,
 * stripped or right-justified before publishing: the trailing spaces are part of the fixed-width
 * record. A card that does not satisfy the width, or that holds a character which is not
 * representable as a single US-ASCII byte, is a programming error rather than a legacy condition and
 * is rejected with {@code IllegalArgumentException}.
 *
 * <p>The card content itself - the seventeen literal images, the four ten-character date
 * substitution slots and the sentinel - is owned entirely by {@code JclCardImageBuilder}. No card
 * text, no job-control literal and no date substitution appears in this class.
 *
 * <h2>Resource names arrive from configuration</h2>
 *
 * <p>No queue name, queue URL, queue ARN, account identifier, region or endpoint is written into
 * this class. The destination queue and the message group are bound from configuration under the
 * {@code carddemo.aws.*} prefix, and the region and endpoint belong to the injected messaging client
 * rather than to this class.
 *
 * <p>The canonical resource names are mandated rather than chosen, and are recorded here so the
 * agreement between this class and the configuration that feeds it is auditable without leaving the
 * file. They are documentation only: each value reaches this class through the binding on its
 * constructor parameter and none of them is a literal in any expression.
 *
 * <table>
 *   <caption>Canonical resource names</caption>
 *   <tr><th>Resource</th><th>Canonical name</th><th>Referenced here</th></tr>
 *   <tr><td>job-submission queue</td><td>{@code JOBS.fifo}</td><td>yes, as the
 *       destination</td></tr>
 *   <tr><td>message group</td><td>{@code carddemo-job-submission}</td><td>yes, one group per
 *       submission</td></tr>
 *   <tr><td>notification topic</td><td>{@code carddemo-job-notifications}</td><td>no</td></tr>
 *   <tr><td>batch staging bucket</td><td>{@code carddemo-batch-staging}</td><td>no</td></tr>
 * </table>
 *
 * <p>Only the first two are referenced at all, because this class publishes one kind of message and
 * nothing else. The same four names must appear unchanged in the shared configuration, in the local
 * and test overlays, in the container composition and in the emulator bootstrap that creates them; a
 * disagreement produces a deployment that starts cleanly and then fails on its first publish, with
 * no start-up error pointing at the cause.
 *
 * <p>The queue keeps the legacy transient-data queue's own name. The configured value is that name
 * plus the one suffix the queue service demands, and the unsuffixed form survives verbatim where it
 * is externally observable - in the operator-facing failure text and in the default queue-name
 * constant of {@code JobSubmissionException}. The two are not composed from one another: the message
 * text is frozen and is compared character for character, so the suffix on the resource name cannot
 * reach it. An earlier revision namespaced the queue to this module, reasoning that a resource name
 * is not a byte-compared contract; that reasoning does not survive the resource name itself being
 * part of the frozen inventory, and the namespaced value also disagreed with the resource the
 * emulator bootstrap actually creates - the precise misconfiguration the queue-not-found strategy
 * now surfaces at first publish instead of masking by creating a queue nothing consumes. The name is
 * reasoned in {@code docs/decision-log.md} DL-092 and the refusal strategy in DL-093.
 *
 * <p>The queue name <strong>must end in the FIFO suffix</strong>: a first-in-first-out queue is
 * rejected by the queue service unless its name carries that suffix, and ordering is contractual
 * here rather than optional. The suffix is therefore validated when this bean is constructed, so a
 * misconfigured deployment fails at startup rather than at the first submission. Neither bound value
 * carries an inline default, which matches the production profile: it resolves every secret and
 * every resource name from an environment variable with no fallback, so a missing value fails
 * startup rather than silently binding a placeholder.
 *
 * <h2>Message deduplication, and why it must not become idempotency</h2>
 *
 * <p>A first-in-first-out queue requires either content-based deduplication enabled on the queue or
 * an explicit deduplication identifier on every message. The legacy transient-data queue had no
 * deduplication concept at all, so this is an addition forced by the target technology and is
 * recorded as such. Content-based deduplication is deliberately not relied upon, because it would
 * make the collapsing of two messages an accident of the card bytes rather than a stated rule.
 *
 * <p>An explicit per-message identifier is therefore supplied, composed of the submission's own
 * identity and the one-based card ordinal. The ordinal keeps the seventeen cards of one submission
 * distinct from each other, which content-based deduplication would also achieve; the submission
 * identity keeps one submission distinct from every other, which content-based deduplication would
 * not, because two requests for the same reporting period produce byte-identical cards and the queue
 * service would discard the second set as duplicates.
 *
 * <p>The <strong>ordinal</strong> keeps the seventeen cards of one submission distinct from each
 * other. That is not a theoretical concern: the job image contains two pairs of cards whose
 * eighty-character bodies are identical, so with content-based deduplication - which derives the
 * identifier from the body - the second card of each pair would be accepted and silently discarded
 * and the submitted job would arrive four cards short of the seventeen it must have. Content-based
 * deduplication is therefore disabled on the queue and never relied upon.
 *
 * <p>The identity half is the part that is easy to get wrong, and getting it wrong invents behaviour
 * the legacy never had. The queue is defined {@code DISPOSITION(MOD)} at
 * {@code app/csd/CARDDEMO.CSD} lines 499 to 505, which means every write <strong>appends
 * unconditionally</strong>; and the legacy program re-writes all seventeen cards on every pass
 * through its submission driver, with no comparison against anything previously written. So two
 * requests for the same reporting period legitimately enqueue two card streams and legitimately
 * trigger the batch job twice. An identity derived from the reporting period <em>alone</em> would
 * make the queue service silently discard every card of the second request while this class reported
 * a complete submission - a cross-invocation idempotency that is a behavioural regression against
 * {@code DISPOSITION(MOD)}, not a translation of it, and an unrequested feature besides.
 *
 * <p>The identity is consequently <strong>per submission attempt, never per reporting period</strong>.
 * A caller that owns an identity of its own supplies it and the identifiers are then fully determined
 * by that caller; a caller that does not supply one gets an identity derived for that single
 * invocation from the two date slots <em>plus a nonce unique to the call</em>. The dates remain in it
 * so that a submission is still legible in a diagnostic, and the nonce is what reproduces the append
 * semantics. Either way two attempts are two submissions, exactly as they were on the mainframe.
 * Deduplication then does exactly one job - it protects against a genuine double-publish of the same
 * card within one submission, which is a programming error - and it never suppresses legitimate work.
 * Repeat suppression, if a deployment ever wants it, belongs to whatever owns the request; it is not
 * a property of this bridge, because it was not a property of the queue this bridge replaces.
 *
 * <p>The message group is a separate value and carries no nonce, so the cards of one submission still
 * travel as one ordered group. Uniqueness and ordering are independent properties here, and only the
 * first one is affected by the identity.
 *
 * <p>Ownership of the identity is the caller's wherever the caller has one, because only the caller
 * can know whether a call is a new request or a retry of one. That is why the identity-bearing entry
 * point takes the identity as its first argument and generates nothing of its own: given the same
 * arguments it produces the same deduplication identifiers on every call, so a retry of a partially
 * published submission adds exactly the cards that never landed. The convenience entry point that
 * takes only the two dates mints a fresh identity for that one invocation and is consequently a new
 * submission by construction and never a retry; a caller that needs retry semantics must keep the
 * identity of the original attempt and use the identity-bearing form.
 *
 * <h2>Layer position and scope</h2>
 *
 * <p>This is a leaf service: it injects no other service. It holds no confirmation gate, no
 * report-period derivation and no screen-message composition beyond the one frozen failure literal -
 * those belong to the report-request service. It launches no batch job and references no batch type:
 * it publishes a message, and the batch tier consumes it. It reads no repository, executes no SQL
 * and performs no decimal arithmetic.
 *
 * <p>The class is a stateless singleton. It holds no mutable field, accumulates no card buffer and
 * keeps no counter across invocations; every per-submission count lives in the returned result, so
 * concurrent submissions cannot interfere.
 *
 * <h2>Provenance</h2>
 *
 * <p>Translated from the AWS CardDemo z/OS mainframe application at checkout SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19. The legacy tree is read-only reference
 * material: it is never read at runtime and no COBOL or job-control statement is reproduced here.
 * The citations above are references to member names, paragraph names, line numbers, resource
 * attributes and widths, not transcriptions.
 */
@Service
public final class JobSubmissionService {

    /**
     * Diagnostic channel for this service. The legacy program's only instrumentation was a
     * diagnostic write of the response and reason codes inside the queue-write paragraph; that
     * write becomes the structured failure record emitted here.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(JobSubmissionService.class);

    /**
     * Configuration key for the destination queue. Held as a constant so the binding expression on
     * the constructor and the diagnostics that name the key cannot drift apart.
     */
    private static final String JOB_SUBMISSION_QUEUE_PROPERTY = "carddemo.aws.sqs.job-submission-queue";

    /**
     * Configuration key for the message group that carries one submission's cards in order.
     */
    private static final String MESSAGE_GROUP_ID_PROPERTY = "carddemo.aws.sqs.message-group-id";

    /**
     * The suffix a first-in-first-out queue name must carry. Ordering is contractual here, so a
     * destination without this suffix is a misconfiguration rather than a degraded mode.
     */
    private static final String FIFO_QUEUE_NAME_SUFFIX = ".fifo";

    /**
     * Separator between the submission identity and the card ordinal in a deduplication
     * identifier. A hyphen is accepted by the queue service in that position.
     */
    private static final String DEDUPLICATION_ID_SEPARATOR = "-";

    /**
     * Separator between the two date slots, and between the slots and the nonce, when a submission
     * identity is derived rather than supplied. An underscore keeps the derived identity readable
     * without colliding with the hyphen the deduplication identifier uses for the card ordinal.
     */
    private static final String SUBMISSION_ID_PART_SEPARATOR = "_";

    /**
     * The longest deduplication identifier the queue service accepts. This is an interface limit
     * imposed by the messaging service on the identifier it is given, in the same way that the
     * eighty-character record width is a limit imposed by the queue definition; it is not a tuning
     * value and nothing about this class's behaviour is derived from it beyond the rejection of an
     * identifier that could not be transmitted.
     */
    private static final int DEDUPLICATION_ID_MAX_LENGTH = 128;

    /**
     * The highest character value representable as a single US-ASCII byte, used to reject a card
     * whose published bytes would not be the caller's bytes.
     */
    private static final char MAX_US_ASCII_CHARACTER = 0x7F;

    /**
     * The lowest character value a card may carry: the ASCII space, which is also the pad character.
     *
     * <p>Every byte of every legacy card is a printable graphic or a space, so nothing below this is
     * legitimate content. Rejecting the range below it is what stops a control byte - a carriage
     * return, a line feed, a NUL - from being published inside a fixed-width record. Such a byte would
     * not overflow the eighty-byte frame and would therefore pass a width check, but a consumer that
     * reconstructs a dataset from the queue would see one card become two records, or a truncated
     * one, which silently corrupts the batch trigger.
     *
     * <p>Tab, null and escape are refused for the same reason as the carriage return and the line
     * feed: a control character inside a fixed-width job-control record can split or corrupt that
     * record for any consumer that reads the stream, and can forge a line in the diagnostics that
     * name the card. The space itself is contractual padding and is of course permitted.
     */
    private static final char MIN_PRINTABLE_US_ASCII_CHARACTER = 0x20;

    /**
     * The highest printable US-ASCII character, one below the delete control code.
     *
     * <p>{@link #MAX_US_ASCII_CHARACTER} is retained separately because it answers a different
     * question - whether a character survives the single-byte encoding at all - and the two limits are
     * reported with different diagnostics.
     *
     * <p>The one code point between this limit and {@link #MAX_US_ASCII_CHARACTER} is the delete
     * control, which is rejected for the same reason as the C0 range below the space.
     */
    private static final char MAX_PRINTABLE_US_ASCII_CHARACTER = 0x7E;

    /** Number of hexadecimal characters in the per-submission uniqueness nonce. */
    private static final int SUBMISSION_NONCE_LENGTH = 32;

    /** Hexadecimal characters needed to render one sixty-four-bit value with leading zeros kept. */
    private static final int HEX_CHARACTERS_PER_LONG = 16;

    /**
     * The one-based ordinal of the first card, matching the legacy card index, which is one-based.
     */
    private static final int FIRST_CARD_ORDINAL = 1;

    /**
     * The longest a derived diagnostic code may be.
     *
     * <p>The legacy response and reason codes were fixed-width display fields, so an unbounded code
     * has no legacy antecedent. A bound also removes the last way a failure could enlarge a log
     * record: a type name is not attacker-controlled in any deployment this module supports, but it
     * is read from a classfile rather than written here, and a value this class writes into every
     * failure diagnostic is bounded by this class rather than by its source.
     */
    private static final int MAX_DIAGNOSTIC_CODE_LENGTH = 64;

    /**
     * The substitute code recorded when a failure's type has no usable simple name.
     *
     * <p>An anonymous class reports the empty string as its simple name, and a synthetic or
     * generated type may report a name that survives sanitisation as nothing at all. Neither case
     * may leave the response code empty, because an empty response code is the published signal that
     * <em>no</em> code was reported, and a failure that arrived always reports one.
     */
    private static final String UNNAMED_FAILURE_TYPE = "UnnamedType";

    /**
     * The character substituted for any byte a derived diagnostic code may not carry.
     *
     * <p>Chosen because it is already admissible in a Java type name, so a sanitised code is still a
     * single unbroken token that a log reader will not split and a search will match whole.
     */
    private static final char DIAGNOSTIC_CODE_REPLACEMENT = '_';

    /**
     * The greatest number of causes the recorded failure chain names.
     *
     * <p>The chain exists because suppressing the external description costs a diagnostic something,
     * and a bounded list of type names restores it without restoring the disclosure. The depth is
     * capped so that a deep or self-referential cause chain cannot lengthen a log record without
     * limit; a chain longer than this is reported as truncated rather than silently shortened.
     */
    private static final int MAX_FAILURE_CHAIN_DEPTH = 6;

    /** Separates one type name from the cause beneath it in the recorded failure chain. */
    private static final String FAILURE_CHAIN_SEPARATOR = "<-";

    /** Appended to the recorded failure chain when it was cut short at its depth bound. */
    private static final String FAILURE_CHAIN_TRUNCATION_MARKER = "<-...";

    /**
     * The injected messaging operations bean. Declared as the operations interface rather than as a
     * concrete template so that the deployment may supply its own implementation and so that a test
     * may supply a double, and typed from the messaging library rather than from this module's
     * configuration package, which this layer does not depend on.
     */
    private final SqsOperations sqsOperations;

    /**
     * The destination queue, bound from configuration and validated as a first-in-first-out queue.
     * Never {@code null} and never blank.
     */
    private final String queueName;

    /**
     * The message group that carries one submission's cards. All cards of a submission share it, so
     * the queue service preserves their publication order. Never {@code null} and never blank.
     */
    private final String messageGroupId;

    /**
     * Creates the service from its injected collaborator and its configured resource names.
     *
     * <p>Both names are bound from configuration with no inline default, so a deployment that omits
     * either one fails at startup instead of failing at the first submission. The queue name is
     * additionally checked for the first-in-first-out suffix, because message ordering is
     * contractual for this destination and a standard queue cannot honour it.
     *
     * @param sqsOperations  the messaging operations bean used to publish cards; must not be
     *                       {@code null}
     * @param queueName      the destination queue, bound from
     *                       {@code carddemo.aws.sqs.job-submission-queue}; must be non-blank and
     *                       must name a first-in-first-out queue
     * @param messageGroupId the message group that carries one submission's cards in order, bound
     *                       from {@code carddemo.aws.sqs.message-group-id}; must be non-blank
     * @throws NullPointerException     if {@code sqsOperations} is {@code null}
     * @throws IllegalArgumentException if either configured name is absent or blank, or if the queue
     *                                  name does not carry the first-in-first-out suffix
     */
    public JobSubmissionService(
            final SqsOperations sqsOperations,
            @Value("${" + JOB_SUBMISSION_QUEUE_PROPERTY + "}") final String queueName,
            @Value("${" + MESSAGE_GROUP_ID_PROPERTY + "}") final String messageGroupId) {
        this.sqsOperations = Objects.requireNonNull(sqsOperations, "sqsOperations must not be null");
        this.queueName = requireFifoQueueName(queueName);
        this.messageGroupId = requireConfiguredValue(messageGroupId, MESSAGE_GROUP_ID_PROPERTY);
    }

    /**
     * Submits the transaction-report job for one date range: builds the card stream and publishes it.
     *
     * <p>This is the entry point that mirrors the legacy request path. The card images are built by
     * {@code JclCardImageBuilder}, which owns every literal and fills the four ten-character date
     * slots from these two arguments, and the resulting stream is then published by
     * {@code submitJobStream}.
     *
     * <p>An <strong>identity is derived for this one invocation</strong> and used, with each card's
     * ordinal, to form the message deduplication identifiers. It carries the two date slots and a
     * nonce unique to the call, so <strong>every</strong> submission is queued: the queue is defined
     * append-on-write and the legacy program re-writes its whole card stream on every pass, so two
     * requests for the same reporting period are two submissions and must both reach the queue.
     * Deriving the identity from the dates alone would instead have the queue service discard the
     * second request's cards inside its deduplication interval, behind a success response, while this
     * method reported a complete submission - so a re-run would be lost silently. A caller that owns
     * a stable identity of its own passes it to
     * {@code submitTransactionReportJob(String, String, String)} rather than relying on the derived
     * one.
     *
     * <p>The dates are raw ten-character slot values in the fixed year-month-day shape the legacy
     * work field carries, with hyphens at its fifth and eighth positions. They are neither parsed nor
     * reformatted here, but they are not passed through unexamined either: the card builder validates
     * their width, their single-byte encodability and that shape - and that the day named exists -
     * before it composes a single card, because cards 11 and 12 embed the slot inside a sort
     * character constant and card 15 places it on a parameter card, so no malformed or injected slot
     * value can reach a card image or the queue.
     *
     * <p><strong>The submission identity is supplied by the caller and is not derived from the
     * dates.</strong> Deriving it from the reporting period would make every submission of that
     * period share one set of deduplication identifiers, and the queue service would then accept and
     * silently discard the second submission of the same period made inside its deduplication
     * interval. The legacy transient-data queue had no deduplication of any kind: a second request
     * for the same period was appended and the job ran again. Losing that second submission - with no
     * failure reported to the operator, because a deduplicated send succeeds - would be a behavioural
     * regression and a silent loss of work, so the identity is the caller's to choose. See
     * {@code submitJobStream} for what the identity must guarantee.
     *
     * <p>A publish failure does not throw. It is logged and reported through the returned result, in
     * line with the queue's ignore-on-error contract.
     *
     * @param submissionId the identity of this logical submission, used with each card's ordinal to
     *                     form that card's deduplication identifier; must be non-blank, free of
     *                     whitespace, distinct for every distinct submission and identical only
     *                     across retries of this same submission
     * @param startDate    the ten-character start-date slot value; must not be {@code null}
     * @param endDate      the ten-character end-date slot value; must not be {@code null}
     * @return the outcome, reporting how many cards reached the queue, whether a failure occurred
     *         and the frozen failure text when one did; never {@code null}
     * <p>Each call to this form is a new submission: the identity it mints is unique to the call, so
     * two calls for the same reporting period enqueue two card streams exactly as the appending
     * transient-data queue did. A retry of an earlier attempt must instead supply that attempt's
     * identity through {@link #submitTransactionReportJob(String, String, String)}.</p>
     *
     * @throws NullPointerException     if either date is {@code null}
     * @throws IllegalArgumentException if either date is not exactly ten encoded bytes, is not shaped
     *                                  as a hyphen-separated year, month and day, or does not name a
     *                                  day that exists
     */
    public SubmissionResult submitTransactionReportJob(final String startDate, final String endDate) {
        return submitTransactionReportJob(newSubmissionId(startDate, endDate),
                startDate, endDate);
    }

    /**
     * Submits the transaction-report job for one date range under a caller-supplied submission
     * identity.
     *
     * <p>This overload exists so that the identity used for message deduplication is the caller's
     * choice rather than something this class infers. The card images are built by
     * {@code JclCardImageBuilder} and published by {@code submitJobStream}, exactly as in the
     * two-argument form; only the identity differs.
     *
     * <p>The identity must be unique per submission attempt if the legacy append-on-write behaviour
     * is to be preserved, because the queue service collapses two messages that share a
     * deduplication identifier within its own deduplication interval. Supplying the same identity
     * twice is therefore a deliberate request to have the second attempt suppressed, and it is the
     * caller's decision to make - the legacy queue had no such concept, so this class neither
     * imposes it nor prevents it.
     *
     * <h3>The complete stream is validated before the first message is sent</h3>
     *
     * <p>Nothing is published until the whole stream has been checked, and the check covers
     * everything a send could fail on: the card count, the position of the end-of-stream card, the
     * fixed record width and character set of every card, and the deduplication identifier of every
     * card. This is a fail-fast guard rather than a translated behaviour &mdash; the legacy card group
     * is a fixed layout that cannot be malformed, so there is no legacy conduct to preserve &mdash;
     * and it exists for one specific reason: a rejection discovered part way through the loop would
     * leave a partial submission on the queue that is indistinguishable from the deliberate partial
     * submission a publish failure produces. Validating first means a malformed stream publishes
     * nothing at all.</p>
     *
     * <p>The stream this entry point builds is always the canonical job image, and it is checked as
     * one: exactly {@code JclCardImageBuilder.CARD_COUNT} cards, with exactly one end-of-stream card
     * and that card last. Consequently exactly three outcomes are observable and there is no fourth:
     * a complete submission of every card, a failure after some cards were published, and a failure
     * on the very first card.</p>
     *
     * @param submissionId this submission's identity, used with each card ordinal to form the
     *                     message deduplication identifiers; must be non-blank, free of whitespace,
     *                     distinct for every distinct submission and identical only across retries of
     *                     this same submission
     * @param startDate    the ten-character start-date slot value; must not be {@code null}
     * @param endDate      the ten-character end-date slot value; must not be {@code null}
     * @return the outcome, reporting how many cards reached the queue, whether a failure occurred
     *         and the frozen failure text when one did; never {@code null}
     * @throws NullPointerException     if {@code submissionId} or either date is {@code null}
     * @throws IllegalArgumentException if either date is not exactly ten encoded bytes, holds a
     *                                  character that is not a single US-ASCII byte, or does not
     *                                  take the fixed year-month-day slot shape; or if
     *                                  {@code submissionId} is blank, holds whitespace, or would
     *                                  form a deduplication identifier longer than the queue
     *                                  service accepts
     */
    public SubmissionResult submitTransactionReportJob(final String submissionId,
            final String startDate, final String endDate) {
        // The identity is checked before any card is built, so a malformed identity cannot be
        // discovered after the stream exists.
        final String submission = requireSubmissionId(submissionId);
        // The builder validates both slots - width, single-byte encodability and the fixed
        // year-month-day shape - before any card exists, so a malformed or injected date fails here
        // rather than part way through a submission and never reaches a published card.
        final List<String> cardImages = JclCardImageBuilder.build(startDate, endDate);
        return submitCanonicalJobImage(submission, cardImages);
    }

    /**
     * Publishes a complete canonical job image, rejecting anything that is not one.
     *
     * <p>This is the entry point the report-submission path uses, and it is where the whole-stream
     * validation that {@link #submitTransactionReportJob(String, String, String)} documents actually
     * happens. On top of the well-formedness every published stream must satisfy, it requires the
     * stream to be the canonical image the legacy emitter produced: exactly
     * {@code JclCardImageBuilder.CARD_COUNT} cards, the last of them the end-of-stream card, and none
     * of the others. A stream whose end-of-stream card arrives early, or which has none at all, or
     * which is longer or shorter than the canonical image, is rejected rather than published, so the
     * emitting loop's end-of-stream flag can never truncate a submission that was accepted.</p>
     *
     * <p>The check is deliberately here rather than in {@link #submitJobStream(String, List)}, which
     * remains the general fixed-width stream publisher: the canonical shape is a property of the
     * report-submission contract, not of publishing a card, and a caller with a legitimate stream of
     * another length is not doing something wrong. Nothing is published until the whole stream has
     * passed, so a malformed image leaves the queue untouched.</p>
     *
     * @param submissionId this submission's identity, used with each card ordinal to form the message
     *                     deduplication identifiers; must be non-blank, free of whitespace, distinct
     *                     for every distinct submission and identical only across retries of this
     *                     same submission
     * @param cardImages   the ordered canonical job image to publish; must not be {@code null}, must
     *                     hold exactly {@code JclCardImageBuilder.CARD_COUNT} cards, must contain no
     *                     {@code null} element, and must end with the end-of-stream card and hold no
     *                     other
     * @return the outcome, reporting how many cards reached the queue, whether a failure occurred and
     *         the frozen failure text when one did; never {@code null}
     * @throws NullPointerException     if {@code submissionId} or {@code cardImages} is {@code null},
     *                                  or if any element is {@code null}
     * @throws IllegalArgumentException if the identity is unusable, if the stream is not the canonical
     *                                  job image, or if any card is not exactly
     *                                  {@code JobSubmissionException.RECORD_SIZE} encoded bytes
     */
    public SubmissionResult submitCanonicalJobImage(final String submissionId,
            final List<String> cardImages) {
        final String submission = requireSubmissionId(submissionId);
        final List<String> cards =
                List.copyOf(Objects.requireNonNull(cardImages, "cardImages must not be null"));
        requireCanonicalJobImage(cards);
        return submitJobStream(submission, cards);
    }

    /**
     * Publishes an already-built job-submission card stream, one message per card, in order, in a single
     * message group.
     *
     * <p>A faithful translation of the emitting loop at {@code app/cbl/CORPT00C.cbl} lines 496 to 509, whose
     * shape is dictated by that loop rather than chosen: the guard is evaluated before every card, testing
     * the one-based ordinal against both the supplied stream length and the legacy array bound and testing
     * the end-of-stream and write-failure flags; the end-of-stream test is applied to a card <em>before</em>
     * that card is published, which is why the sentinel card is transmitted and a complete submission is
     * seventeen messages; and a failed publish raises the write-failure flag, which the guard then observes,
     * so the remaining cards are not sent and the submission is left partial.
     *
     * <p>Every card is checked for the fixed record width before anything is published, so a malformed
     * stream is rejected outright rather than leaving a partial submission behind. That check guards against
     * a programming error and has no legacy antecedent, because the legacy card group is a fixed layout that
     * cannot be malformed. The stream is copied on entry, so a caller mutating its list afterwards cannot
     * affect a submission in progress.
     *
     * @param submissionId the submission's identity, used with the card ordinal to form each
     *                     message's deduplication identifier; must be non-blank, free of whitespace,
     *                     distinct for every distinct submission and identical only across retries of
     *                     this same submission. A value not used before makes this a new submission,
     *                     which the queue appends; a value used before makes this a retry, so every
     *                     card the queue already accepted deduplicates and only the cards that never
     *                     landed are added
     * @param cardImages   the ordered card stream to publish, each card exactly
     *                     {@code JobSubmissionException.RECORD_SIZE} encoded bytes; must not be
     *                     {@code null}, must not be empty and must contain no {@code null} element
     * @return the outcome, reporting how many cards reached the queue, whether a failure occurred
     *         and the frozen failure text when one did; never {@code null}
     * @throws NullPointerException     if {@code submissionId}, {@code cardImages} or any element of
     *                                  {@code cardImages} is {@code null}
     * @throws IllegalArgumentException if {@code submissionId} is blank or holds whitespace, if the
     *                                  stream does not hold exactly
     *                                  {@code JclCardImageBuilder.CARD_COUNT} cards, if its final
     *                                  card is not the end-of-stream card, if any earlier card is an
     *                                  end-of-stream card, if any card is not exactly
     *                                  {@code JobSubmissionException.RECORD_SIZE} encoded bytes or
     *                                  holds a character the record may not carry, or if any
     *                                  deduplication identifier would exceed the length the queue
     *                                  service accepts
     */
    public SubmissionResult submitJobStream(final String submissionId, final List<String> cardImages) {
        final String submission = requireSubmissionId(submissionId);
        // List.copyOf rejects a null element and yields an immutable snapshot, so the sequence
        // published is exactly the sequence supplied at the moment of the call.
        final List<String> cards =
                List.copyOf(Objects.requireNonNull(cardImages, "cardImages must not be null"));
        requireWellFormedCards(cards);
        // Composing every identifier up front is what removes the last way a send could be refused
        // after an earlier send had already succeeded.
        final List<String> deduplicationIds = deduplicationIds(submission, cards.size());

        int cardsPublished = 0;
        boolean endOfStream = false;
        boolean writeFailed = false;

        // The three guard conditions are the legacy guard at lines 498 to 499: the one-based index
        // against the declared array bound, the end-of-stream flag and the write-error flag. The
        // supplied stream length bounds the iteration; the legacy array bound is carried alongside it
        // as a defensive guard and is never the condition that stops a well-formed submission.
        for (int cardOrdinal = FIRST_CARD_ORDINAL;
                cardOrdinal <= cards.size()
                        && cardOrdinal <= JclCardImageBuilder.OVERSIZED_REDEFINE_CARD_BOUND
                        && !endOfStream
                        && !writeFailed;
                cardOrdinal++) {

            final String cardImage = cards.get(cardOrdinal - FIRST_CARD_ORDINAL);

            // Set before the write, exactly as at lines 502 to 507. This ordering is the reason the
            // sentinel card is published and the submission is seventeen messages rather than
            // sixteen; reversing it would silently drop the batch tier's end-of-stream marker.
            // The flag ends the loop after the card that carries it, which is the legacy conduct for
            // any stream; on the canonical path submitCanonicalJobImage has already established that
            // only the final card can carry it, so it never truncates a canonical submission.
            endOfStream = isEndOfStreamCard(cardImage);

            if (publishCard(submission, cardImage, cardOrdinal,
                    deduplicationIds.get(cardOrdinal - FIRST_CARD_ORDINAL))) {
                cardsPublished++;
            } else {
                // The legacy write-error flag. It appears in the loop guard, so raising it ends the
                // submission and the cards after this one are deliberately never sent.
                writeFailed = true;
            }
        }

        final SubmissionResult result = new SubmissionResult(cards.size(), cardsPublished, writeFailed,
                writeFailed ? JobSubmissionException.DEFAULT_MESSAGE : "");

        if (result.failed()) {
            // The failing card was already logged with its cause at the publish boundary; this
            // record states the submission-level consequence, which is that the stream stopped
            // short. It is a warning rather than an error because control returns normally: the
            // caller's request completes, exactly as the legacy transaction does.
            LOGGER.warn("Job-submission stream stopped early: cardsPublished={} cardsRequested={}"
                            + " submission={} queue={} messageGroup={}",
                    result.cardsPublished(), result.cardsRequested(), submission, this.queueName,
                    this.messageGroupId);
        } else {
            LOGGER.info("Job-submission stream published: cardsPublished={} submission={} queue={}"
                            + " messageGroup={}",
                    result.cardsPublished(), submission, this.queueName, this.messageGroupId);
        }
        return result;
    }

    /**
     * Publishes exactly one job-submission card and reports whether it was accepted, without throwing on a
     * publish failure.
     *
     * <p>Replaces the queue-write paragraph at {@code app/cbl/CORPT00C.cbl} line 515. That paragraph's name
     * is misspelled in the source &mdash; {@code WIRTE-JOBSUB-TDQ} rather than the intended spelling &mdash;
     * which is row 6 of the source anomaly register. The misspelling is deliberately not carried into any
     * Java identifier: the paragraph is misspelled, this method is not.
     *
     * <p>The returned value is the inverse of the legacy write-error flag. A normal response leaves that flag
     * clear and the paragraph simply continues, reported here as {@code true}; any other response raises it,
     * reported as {@code false} &mdash; and because the flag also appears in the emitting loop's guard,
     * {@code false} is what stops the remaining cards.
     *
     * <p>A publish failure never propagates, per decision log entry D-36. A
     * {@code JobSubmissionException} is constructed to carry the queue name, the response and reason codes,
     * the failing card's one-based ordinal and the underlying cause; it is logged with that cause; and
     * {@code false} is returned. No retry, backoff, dead-letter redirect or circuit breaker is attempted.
     * That mirrors the queue definition's ignore-on-error attribute and the emitting paragraph's own
     * behaviour, which reports the failure to the operator and returns control normally. A failure
     * that reaches this method has already exhausted whatever the transport itself was prepared to do
     * with it.
     *
     * <p>A malformed argument is a different matter and does throw: a programming error with no legacy
     * antecedent, so the fixed-width precondition is enforced with {@code IllegalArgumentException} rather
     * than reported as a publish failure. The card is published exactly as supplied &mdash; never trimmed,
     * stripped or padded &mdash; because its trailing spaces are part of the fixed-width record.
     *
     * @param submissionId the submission's identity, combined with {@code cardOrdinal} to form the
     *                     message's deduplication identifier; must be non-blank and free of
     *                     whitespace
     * @param cardImage    the card to publish, exactly {@code JobSubmissionException.RECORD_SIZE}
     *                     encoded bytes; must not be {@code null}
     * @param cardOrdinal  the card's one-based ordinal within its submission, matching the
     *                     one-based legacy card index; must be at least
     *                     {@value #FIRST_CARD_ORDINAL}
     * @return {@code true} when the card was accepted by the queue, {@code false} when the publish
     *         failed and the submission must stop
     * @throws NullPointerException     if {@code submissionId} or {@code cardImage} is {@code null}
     * @throws IllegalArgumentException if {@code submissionId} is blank or holds whitespace, if
     *                                  {@code cardOrdinal} is below the first ordinal, if
     *                                  {@code cardImage} is not exactly
     *                                  {@code JobSubmissionException.RECORD_SIZE} encoded bytes or
     *                                  holds a character that is not a single US-ASCII byte, or if
     *                                  the deduplication identifier would exceed the length the
     *                                  queue service accepts
     */
    public boolean writeJobSubmissionQueue(final String submissionId, final String cardImage,
            final int cardOrdinal) {
        final String submission = requireSubmissionId(submissionId);
        final String card = requireCardImage(cardImage, cardOrdinal);
        return publishCard(submission, card, cardOrdinal, deduplicationId(submission, cardOrdinal));
    }

    /**
     * Publishes one already-validated card and reports whether the queue accepted it.
     *
     * <p>This is the single send site of the class. Both public paths reach it, so there is exactly
     * one place where a message is constructed, one place where a failure is caught, and one place
     * where the failure is logged - which is what keeps the stream path and the single-card path from
     * drifting apart. Every argument has already been validated by the caller, so no rejection can
     * occur here and a partial submission can only ever be the deliberate consequence of a publish
     * failure.
     *
     * @param submission      the validated submission identity, recorded in diagnostics
     * @param card            the validated card, published exactly as supplied
     * @param cardOrdinal     the card's one-based ordinal, recorded in diagnostics
     * @param deduplicationId the validated deduplication identifier for this card
     * @return {@code true} when the card was accepted, {@code false} when the publish failed
     */
    private boolean publishCard(final String submission, final String card, final int cardOrdinal,
            final String deduplicationId) {
        try {
            // One card, one message: an eighty-character fixed-width body, published into the single
            // message group that carries this submission, which is what preserves append order.
            final SendResult<String> sendResult = this.sqsOperations.send(options -> options
                    .queue(this.queueName)
                    .payload(card)
                    .messageGroupId(this.messageGroupId)
                    .messageDeduplicationId(deduplicationId));
            LOGGER.debug("Published job-submission card: ordinal={} submission={} queue={}"
                            + " messageGroup={} messageId={}",
                    cardOrdinal, submission, this.queueName, this.messageGroupId, sendResult.messageId());
            return true;
        } catch (final RuntimeException publishFailure) {
            // The queue is defined ignore-on-error, so every failure of the write itself is caught
            // here. The exception is built for its diagnostic content; it is never rethrown, because
            // rethrowing would abort a request the legacy transaction completes. It keeps the raw
            // failure as its cause so that nothing is lost from the object, and the object does not
            // escape this method.
            final JobSubmissionException failure = new JobSubmissionException(this.queueName,
                    responseCodeOf(publishFailure), reasonCodeOf(publishFailure), cardOrdinal,
                    publishFailure);
            // Mirrors the legacy diagnostic write of the response and reason codes, which the
            // paragraph performs before it reports the failure to the operator, and adds the bounded
            // chain of failure types beneath them.
            //
            // The raw failure is deliberately NOT passed as the logging throwable. Doing so would
            // render its stack trace, and a stack trace carries the description of every exception in
            // the chain - which is precisely the externally-supplied text decision DL-041 keeps out
            // of this log. What the trace contributed to a diagnosis is the shape of the chain, and
            // that travels here as sanitised type names instead.
            LOGGER.error("{} ordinal={} submission={} queue={} messageGroup={} response={} reason={}"
                            + " failureChain={}",
                    JobSubmissionException.DEFAULT_MESSAGE, cardOrdinal, submission, this.queueName,
                    this.messageGroupId, failure.responseCode(), failure.reasonCode(),
                    failureChainOf(publishFailure));
            return false;
        }
    }

    /**
     * Reports whether a card is the one the emitting loop stops on.
     *
     * <p>The legacy test at {@code app/cbl/CORPT00C.cbl} lines 502 to 505 compares the
     * eighty-character record against three values: the sentinel literal, an all-spaces record and
     * an all-low-values record. Two COBOL rules govern the comparison and both are reproduced here.
     * A literal shorter than the field is compared as though padded with trailing spaces, so the
     * sentinel matches the literal followed by trailing spaces rather than only an exact-length
     * value. And the abbreviated combined relation in the source expands to two separate equality
     * tests, one against spaces and one against low values, which is why a blank card and a
     * low-values card both terminate the stream.
     *
     * @param cardImage the card being examined, exactly as supplied
     * @return {@code true} when this card ends the stream, {@code false} otherwise
     */
    private static boolean isEndOfStreamCard(final String cardImage) {
        if (JclCardImageBuilder.EOF_SENTINEL_CARD.equals(trailingSpacesRemoved(cardImage))) {
            return true;
        }
        return isUniformlyFilledWith(cardImage, ' ') || isUniformlyFilledWith(cardImage, '\0');
    }

    /**
     * Removes trailing spaces only, reproducing the space padding COBOL applies to the shorter
     * operand of a comparison.
     *
     * <p>Only the ASCII space is removed. No other whitespace character is treated as padding,
     * because the fixed-width record is padded with spaces and nothing else, and the value returned
     * here is used solely for the end-of-stream comparison - never for the published payload, which
     * is always the untouched card.
     *
     * @param value the value to examine
     * @return {@code value} without its trailing spaces
     */
    private static String trailingSpacesRemoved(final String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Reports whether every character of a non-empty value is the given fill character.
     *
     * @param value the value to examine
     * @param fill  the character the value must consist of
     * @return {@code true} when {@code value} is non-empty and consists solely of {@code fill}
     */
    private static boolean isUniformlyFilledWith(final String value, final char fill) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != fill) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rejects a card stream that is empty or that holds a card the fixed-width record contract does
     * not permit.
     *
     * <p>This is the check every published stream passes, canonical or not. It establishes only what
     * publishing a card requires: that there is at least one card, and that each card is exactly the
     * declared record width and carries none but printable US-ASCII characters. The whole stream is
     * checked before the first send, so a malformed card cannot be discovered after an earlier card
     * has already reached the queue.
     *
     * @param cards the immutable stream snapshot, in card order
     * @throws IllegalArgumentException if the stream is empty, or if any card is not exactly
     *                                  {@code JobSubmissionException.RECORD_SIZE} encoded bytes or
     *                                  carries a character a card may not hold
     */
    private static void requireWellFormedCards(final List<String> cards) {
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("cardImages must hold at least one job-submission card");
        }
        for (int cardOrdinal = FIRST_CARD_ORDINAL; cardOrdinal <= cards.size(); cardOrdinal++) {
            requireCardImage(cards.get(cardOrdinal - FIRST_CARD_ORDINAL), cardOrdinal);
        }
    }

    /**
     * Validates that a stream is the canonical job image, before any of it is published.
     *
     * <p>Three properties are checked, in the order in which a reader of the external contract would
     * check them: the card count, the width and character set of every card, and the position of the
     * end-of-stream card.
     *
     * <p>The count must be exactly {@code JclCardImageBuilder.CARD_COUNT}, which is the number of
     * cards the job image holds and therefore the number of messages one submission is. That single
     * check also settles the legacy array bound carried in the emitting loop's guard: the canonical
     * count is far below it, so the bound can never be the condition that stops a submission this
     * method has accepted.
     *
     * <p>The end-of-stream card must be the last card and must be the only one, because the emitting
     * loop stops on the first card that satisfies that test. An earlier end-of-stream card would end
     * the submission before the remaining cards were sent, and the outcome would be reported as a
     * complete submission because no publish had failed - a silent short delivery. Rejecting the
     * stream instead is what reduces the observable outcomes to three.
     *
     * @param cards the stream to validate, in order
     * @throws IllegalArgumentException if the stream does not hold exactly
     *                                  {@code JclCardImageBuilder.CARD_COUNT} cards, if any card is
     *                                  not exactly {@code JobSubmissionException.RECORD_SIZE} encoded
     *                                  bytes or holds a character the record may not carry, if the
     *                                  final card is not the end-of-stream card, or if any earlier
     *                                  card is one
     */
    private static void requireCanonicalJobImage(final List<String> cards) {
        if (cards.size() != JclCardImageBuilder.CARD_COUNT) {
            throw new IllegalArgumentException("a job-submission stream must hold exactly "
                    + JclCardImageBuilder.CARD_COUNT + " cards, which is the canonical job image,"
                    + " but held " + cards.size());
        }
        requireWellFormedCards(cards);
        final int finalOrdinal = cards.size();
        if (!isEndOfStreamCard(cards.get(finalOrdinal - FIRST_CARD_ORDINAL))) {
            throw new IllegalArgumentException("the final card of a job-submission stream must be the"
                    + " end-of-stream card, which is transmitted, but card " + finalOrdinal
                    + " was not");
        }
        for (int cardOrdinal = FIRST_CARD_ORDINAL; cardOrdinal < finalOrdinal; cardOrdinal++) {
            if (isEndOfStreamCard(cards.get(cardOrdinal - FIRST_CARD_ORDINAL))) {
                throw new IllegalArgumentException("card " + cardOrdinal + " of a job-submission"
                        + " stream ends the stream, but only the final card may: the emitting loop"
                        + " stops on it and the cards after it would never be published");
            }
        }
    }

    /**
     * Composes and validates the deduplication identifier of every card of a submission.
     *
     * <p>Composing them all before the first send is what makes the identifier length a rejection of
     * the whole submission rather than an interruption of one. The returned list is positional: the
     * identifier of the card at one-based ordinal <em>n</em> is at index <em>n</em> minus one.
     *
     * @param submissionId the already validated submission identity
     * @param cardCount    the number of cards in the submission
     * @return the identifiers, in card order; never {@code null}
     * @throws IllegalArgumentException if any composed identifier would exceed the length the queue
     *                                  service accepts
     */
    private static List<String> deduplicationIds(final String submissionId, final int cardCount) {
        final List<String> identifiers = new ArrayList<>(cardCount);
        for (int cardOrdinal = FIRST_CARD_ORDINAL; cardOrdinal <= cardCount; cardOrdinal++) {
            identifiers.add(deduplicationId(submissionId, cardOrdinal));
        }
        return List.copyOf(identifiers);
    }

    /**
     * Validates one card against the fixed record width and returns it unchanged.
     *
     * <p>The width is asserted on the encoded bytes rather than on the character count, because the
     * queue definition fixes a record size in bytes and a character count is not the same
     * measurement. The card is additionally required to consist of characters that each encode as a
     * single US-ASCII byte, so the bytes published are the caller's bytes rather than substitution
     * characters. Nothing is trimmed, stripped or padded: the trailing spaces of a fixed-width record
     * are part of the record.
     *
     * <p>Every byte is further required to be a printable graphic or the space. This is the outer
     * boundary of the online-to-batch bridge, and it is the last place a control byte can be stopped:
     * a carriage return or a line feed inside a card does not change the record's width, so a width
     * check alone admits it, yet a consumer reconstructing a dataset from the queue would then read
     * one eighty-byte card as two records. Every card the legacy program emits is composed of
     * printable literals and space padding, so the restriction refuses nothing legitimate. Together
     * with the shape guard the card builder applies to its date slots, it means no caller-supplied
     * text can carry framing or control-language characters onto the queue, whether the cards were
     * built here or handed in ready-made.
     *
     * @param cardImage   the card to validate
     * @param cardOrdinal the card's one-based ordinal, used only to identify it in a diagnostic
     * @return {@code cardImage}, unchanged
     * @throws NullPointerException     if {@code cardImage} is {@code null}
     * @throws IllegalArgumentException if {@code cardImage} is not exactly
     *                                  {@code JobSubmissionException.RECORD_SIZE} encoded bytes,
     *                                  holds a character that is not a single US-ASCII byte, or holds
     *                                  a character that is neither a printable graphic nor the space
     */
    private static String requireCardImage(final String cardImage, final int cardOrdinal) {
        Objects.requireNonNull(cardImage, "cardImage must not be null");
        final int width = cardImage.getBytes(StandardCharsets.US_ASCII).length;
        if (width != JobSubmissionException.RECORD_SIZE) {
            throw new IllegalArgumentException("job-submission card " + cardOrdinal
                    + " must be exactly " + JobSubmissionException.RECORD_SIZE
                    + " encoded bytes but was " + width);
        }
        for (int index = 0; index < cardImage.length(); index++) {
            final char character = cardImage.charAt(index);
            if (character > MAX_US_ASCII_CHARACTER) {
                throw new IllegalArgumentException("job-submission card " + cardOrdinal
                        + " holds a character at position " + (index + FIRST_CARD_ORDINAL)
                        + " that is not representable as a single US-ASCII byte");
            }
            if (character < MIN_PRINTABLE_US_ASCII_CHARACTER
                    || character > MAX_PRINTABLE_US_ASCII_CHARACTER) {
                throw new IllegalArgumentException("job-submission card " + cardOrdinal
                        + " holds a non-printable character at position "
                        + (index + FIRST_CARD_ORDINAL) + ", code point " + (int) character
                        + "; a fixed-width card may carry only printable US-ASCII graphics and the"
                        + " space, because a control byte would reframe the record at the consumer"
                        + " and could forge a line in a log record that names the card");
            }
        }
        return cardImage;
    }

    /**
     * Validates a submission identity and returns it unchanged.
     *
     * <p>The identity becomes part of every deduplication identifier of the submission, and the queue
     * service does not accept whitespace in that identifier, so whitespace is rejected here rather
     * than silently removed - removing it would make two distinct identities collide.
     *
     * <p>The rejection reports the offending character's zero-based position and code point and never
     * the identity itself, per decision DL-041. That is not a formality here. A carriage return and a
     * line feed are both whitespace, so this branch is reached precisely when the identity holds a
     * line terminator, and the identity is caller-supplied - so echoing it would hand any caller a
     * way to write a forged line into the service log. The position and the code point tell a caller
     * exactly which character to remove, which is everything the caller needs and nothing more.
     *
     * <p><strong>Rejecting whitespace alone is not sufficient, and this method scans for three
     * distinct defects rather than one.</strong> Whitespace is the defect the queue service itself
     * cares about, but it is not the only character class that can forge a diagnostic. A NUL, an
     * escape and a delete are none of them whitespace, so a whitespace-only scan admits every one of
     * them, and the identity is then interpolated into two places that matter: the stream-level log
     * records that name {@code submission=}, and the deduplication identifier published to the queue.
     * An escape sequence reaching a terminal-backed log viewer can reposition the cursor and overwrite
     * the records already written, which forges history without ever emitting a line feed; a NUL can
     * truncate a record for a consumer that reads a C string. The scan therefore requires the whole
     * identity to be printable US-ASCII, which is the same restriction this class already places on
     * every published card, for the same reason and expressed with the same two limits.
     *
     * <p>The three checks are ordered so that the most specific diagnostic wins. Whitespace is tested
     * first, because a caller who supplied a space or a line terminator is best told that a
     * deduplication identifier may not hold whitespace - a strictly more useful statement than
     * "non-printable". Representability is tested next, then the printable range, exactly as
     * {@code requireCardImage} orders the same pair, because the two answer different questions: one
     * asks whether the character survives the single-byte encoding at all, the other whether it is
     * safe once encoded. Note that the space is the one character both the whitespace test and the
     * printable range accept, and it is refused by the former, so the effective allowlist here is the
     * printable graphics excluding the space.
     *
     * @param submissionId the identity to validate
     * @return {@code submissionId}, unchanged
     * @throws NullPointerException     if {@code submissionId} is {@code null}
     * @throws IllegalArgumentException if {@code submissionId} is blank, holds whitespace, holds a
     *                                  character that is not representable as a single US-ASCII byte,
     *                                  or holds a character outside printable US-ASCII
     */
    private static String requireSubmissionId(final String submissionId) {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        if (submissionId.isBlank()) {
            throw new IllegalArgumentException("submissionId must not be blank");
        }
        for (int index = 0; index < submissionId.length(); index++) {
            final char character = submissionId.charAt(index);
            if (Character.isWhitespace(character)) {
                throw new IllegalArgumentException("submissionId must not hold whitespace because a"
                        + " message deduplication identifier may not; the character at zero-based"
                        + " position " + index + " is code point " + (int) character);
            }
            if (character > MAX_US_ASCII_CHARACTER) {
                throw new IllegalArgumentException("submissionId must not hold a character that is"
                        + " not representable as a single US-ASCII byte, because the bytes published"
                        + " would not be the bytes supplied; the character at zero-based position "
                        + index + " is code point " + (int) character);
            }
            if (character < MIN_PRINTABLE_US_ASCII_CHARACTER
                    || character > MAX_PRINTABLE_US_ASCII_CHARACTER) {
                throw new IllegalArgumentException("submissionId must not hold a non-printable"
                        + " character because the identity is written to the service log and to the"
                        + " message deduplication identifier; the character at zero-based position "
                        + index + " is code point " + (int) character);
            }
        }
        return submissionId;
    }

    /**
     * Composes the deduplication identifier for one card.
     *
     * <p>The identifier is the submission identity, a separator and the card's one-based ordinal. This
     * composition is a pure function of its two arguments - nothing random and nothing time-derived is
     * introduced here - so publishing the same card of the same submission twice yields the same
     * identifier and the queue service treats the repetition as the duplicate it is. Whether two
     * <em>submissions</em> collide is therefore decided entirely by whether their identities differ,
     * which is a property of the identity and not of this composition; {@code newSubmissionId}
     * explains why a derived identity is unique per submission, and a caller-supplied identity carries
     * that responsibility itself. Content-based deduplication is deliberately not relied upon, so this
     * identifier is what makes a first-in-first-out publish acceptable to the queue service.
     *
     * <p>The composition cannot make two different cards collide. The ordinal is an unsigned decimal
     * and therefore contains no separator, so the final separator of the composed value always marks
     * the boundary between identity and ordinal: two pairs of inputs produce the same identifier only
     * when both the identity and the ordinal are equal. Distinct submissions consequently never share
     * an identifier, which is precisely what keeps a deliberate second submission from being
     * discarded.
     *
     * @param submissionId the already validated submission identity
     * @param cardOrdinal  the card's one-based ordinal within the submission
     * @return the deduplication identifier for this card
     * @throws IllegalArgumentException if {@code cardOrdinal} is below the first ordinal, or if the
     *                                  composed identifier would exceed the length the queue service
     *                                  accepts
     */
    private static String deduplicationId(final String submissionId, final int cardOrdinal) {
        if (cardOrdinal < FIRST_CARD_ORDINAL) {
            throw new IllegalArgumentException("cardOrdinal is one-based and must be at least "
                    + FIRST_CARD_ORDINAL + " but was " + cardOrdinal);
        }
        final String deduplicationId = submissionId + DEDUPLICATION_ID_SEPARATOR + cardOrdinal;
        if (deduplicationId.length() > DEDUPLICATION_ID_MAX_LENGTH) {
            // The identity is not repeated here, per decision DL-041. It is caller-supplied, and it
            // is also redundant - the caller already holds the value it passed. What the caller
            // cannot work out for itself is the arithmetic that failed, so the message states that
            // instead: the identity's length, the ordinal whose digits were appended, the composed
            // length and the ceiling it passed. Those four numbers are what shortening the identity
            // requires, and none of them is an echo.
            throw new IllegalArgumentException("the message deduplication identifier composed from"
                    + " a submission identity of " + submissionId.length() + " characters and card"
                    + " ordinal " + cardOrdinal + " is " + deduplicationId.length()
                    + " characters, which exceeds the " + DEDUPLICATION_ID_MAX_LENGTH
                    + " the queue service accepts");
        }
        return deduplicationId;
    }

    /**
     * Derives a submission identity from the two date slots that define a reporting period, plus a
     * nonce that makes the identity unique to this submission.
     *
     * <p><strong>Why the nonce is required rather than optional.</strong> The queue's legacy
     * counterpart is a transient-data queue defined with {@code DISPOSITION(MOD)}, which appends. An
     * operator who requested the same reporting period twice got two job submissions, and that was
     * the point: a report is re-run because the first run was lost, superseded, or wanted again.
     * Deriving the identity from the dates alone made the two submissions carry identical
     * deduplication identifiers, and the queue service then discarded the second one inside its
     * deduplication interval - silently, with a success response, so neither the operator nor the
     * caller could tell that no job had been queued. That is not idempotency; the request is not a
     * retry of the first, it is a second unit of work, and suppressing it loses the append semantics
     * the bridge exists to reproduce.
     *
     * <p>The dates are retained in the identity even though the nonce alone would make it unique,
     * because the identity appears in every diagnostic this class emits and a submission that can be
     * read back to its reporting period is worth far more operationally than an opaque one.
     *
     * <p><strong>What the nonce does not change.</strong> The message group is a separate value and is
     * not touched, so the cards of one submission still travel in a single ordered group and arrive in
     * the order they were published - the property that makes the seventeen-card stream reconstructable.
     * The card ordinal still distinguishes the cards within a submission, so a genuine double-publish
     * of the <em>same</em> card within one submission - which would be a programming error rather than
     * an operator action - is still caught by the deduplication identifier.
     *
     * <p>Whitespace is removed from the dates because the slots are fixed-width values that may be
     * space-padded while a deduplication identifier may hold no whitespace; only the derived identity
     * is condensed, never a card, whose padding is contractual. The deviation from content-based
     * deduplication is recorded in {@code docs/decision-log.md}.
     *
     * <p>Condensing whitespace is deliberately not the same thing as sanitising the dates. A date slot
     * carrying a NUL, an escape or a delete still reaches {@link #requireSubmissionId(String)} intact
     * and is refused there, because those characters are not whitespace and stripping them silently
     * would let two distinct periods mint one identity. The minted value is therefore printable
     * US-ASCII in whole or the call fails, which is what makes it safe to interpolate into the
     * {@code submission=} field of every diagnostic this class emits.
     *
     * @param startDate the start-date slot value
     * @param endDate   the end-date slot value
     * @return the derived submission identity, unique to this call
     * @throws NullPointerException     if either date is {@code null}
     * @throws IllegalArgumentException if the minted identity is blank, or if either date carries a
     *                                  character outside printable US-ASCII
     */
    private static String newSubmissionId(final String startDate, final String endDate) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        return requireSubmissionId(withoutWhitespace(startDate) + SUBMISSION_ID_PART_SEPARATOR
                + withoutWhitespace(endDate) + SUBMISSION_ID_PART_SEPARATOR + submissionNonce());
    }

    /**
     * Produces the per-submission uniqueness nonce as {@value #SUBMISSION_NONCE_LENGTH} lower-case
     * hexadecimal characters.
     *
     * <p>Hexadecimal with the group separators removed, rather than the textual form of the
     * identifier, because a deduplication identifier may hold only alphanumerics and a small set of
     * punctuation and must hold no whitespace; restricting the nonce to hexadecimal keeps it inside
     * that set with no escaping and no possibility of a character that the queue service would refuse.
     *
     * @return a whitespace-free hexadecimal nonce, distinct on every call
     */
    private static String submissionNonce() {
        final UUID unique = UUID.randomUUID();
        final StringBuilder nonce = new StringBuilder(SUBMISSION_NONCE_LENGTH);
        appendFixedWidthHex(nonce, unique.getMostSignificantBits());
        appendFixedWidthHex(nonce, unique.getLeastSignificantBits());
        return nonce.toString();
    }

    /**
     * Appends one sixty-four-bit value as exactly sixteen lower-case hexadecimal characters.
     *
     * <p>Zero filled on the left, because {@link Long#toHexString(long)} drops leading zeros and a
     * variable-length nonce would make the composed identifier's length vary between submissions for
     * no reason.
     *
     * @param target the buffer to append to
     * @param value  the value to render
     */
    private static void appendFixedWidthHex(final StringBuilder target, final long value) {
        final String hex = Long.toHexString(value);
        target.append("0".repeat(HEX_CHARACTERS_PER_LONG - hex.length())).append(hex);
    }

    /**
     * Returns a value with every whitespace character removed.
     *
     * @param value the value to condense
     * @return {@code value} without whitespace
     */
    private static String withoutWhitespace(final String value) {
        final StringBuilder condensed = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!Character.isWhitespace(character)) {
                condensed.append(character);
            }
        }
        return condensed.toString();
    }

    /**
     * Validates the configured destination queue and returns it unchanged.
     *
     * <p>The rejection names the property key and the suffix the value has to carry, and does not
     * repeat the configured value, per decision DL-041. The suffix literal stays because it is this
     * class's own statement of what it expects rather than an echo of what it was given - the
     * distinction DL-041 draws when it notes that a message's own prose is not an echo. The property
     * key is the actionable fact: it points an operator at the exact configuration entry to correct,
     * and the operator can already read the value there.
     *
     * @param queueName the configured queue name, queue URL or queue ARN
     * @return {@code queueName}, unchanged
     * @throws IllegalArgumentException if the value is absent, blank, or does not carry the
     *                                  first-in-first-out suffix
     */
    private static String requireFifoQueueName(final String queueName) {
        final String configured = requireConfiguredValue(queueName, JOB_SUBMISSION_QUEUE_PROPERTY);
        if (!configured.endsWith(FIFO_QUEUE_NAME_SUFFIX)) {
            throw new IllegalArgumentException("property " + JOB_SUBMISSION_QUEUE_PROPERTY
                    + " must name a first-in-first-out queue, whose name ends with '"
                    + FIFO_QUEUE_NAME_SUFFIX + "', because job-submission cards must keep their"
                    + " order, but the configured value does not carry that suffix");
        }
        return configured;
    }

    /**
     * Validates that a configured value was supplied and returns it unchanged.
     *
     * <p>Neither bound value carries an inline default, so an absent or blank value is a deployment
     * error and is reported as one at startup. Failing here rather than at the first submission is
     * what keeps a missing resource name from being discovered by an operator instead of by the
     * deployment.
     *
     * <p>Both bound values are additionally required to be printable US-ASCII, for the reason
     * {@code requireCardImage} already gives about the card: a control byte "could forge a line in a
     * log record". The queue name and the message group are written into all four of this class's log
     * statements through parameter substitution, which does not escape a control character, so a
     * carriage return in either value would let a log reader split one record into two. Decision
     * DL-042 draws that boundary for the queue payload and decision D-09 draws it for every value
     * this module emits; applying it here is the same rule, at the only other place this class takes
     * a value it will later write out. The rule costs nothing that a real deployment needs: a queue
     * name, a queue URL, a queue ARN and a message group are each printable US-ASCII by the queue
     * service's own definition.
     *
     * <p>The rejection names the property key, the offending character's zero-based position and its
     * code point, and never the value, per decision DL-041. The property key is what makes it
     * actionable - it points an operator at the exact configuration entry, where the value can
     * already be read.
     *
     * @param value       the bound value, possibly {@code null}
     * @param propertyKey the configuration key it was bound from, named in the diagnostic
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null}, blank, or holds a character
     *                                  outside printable US-ASCII
     */
    private static String requireConfiguredValue(final String value, final String propertyKey) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("property " + propertyKey + " must be configured with"
                    + " a non-blank value; it is resolved from configuration and has no default");
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < MIN_PRINTABLE_US_ASCII_CHARACTER
                    || character > MAX_PRINTABLE_US_ASCII_CHARACTER) {
                throw new IllegalArgumentException("property " + propertyKey + " accepts printable"
                        + " US-ASCII only, that is code points "
                        + (int) MIN_PRINTABLE_US_ASCII_CHARACTER + " to "
                        + (int) MAX_PRINTABLE_US_ASCII_CHARACTER + ", because this value is written"
                        + " into every log record the service emits about a submission; the"
                        + " character at zero-based position " + index + " is code point "
                        + (int) character);
            }
        }
        return value;
    }

    /**
     * Derives the response code recorded for a failed publish.
     *
     * <p>The legacy paragraph captured a numeric response code from the queue write. The messaging
     * client reports a failure as an exception instead, so the analogue recorded here is the
     * failure's own type name, read from the object's identity and then sanitised and bounded by
     * {@link #diagnosticCodeOf(Class)}. No reflective lookup is performed and no type is loaded by
     * name.
     *
     * <p>The type name is derived rather than the failure's description because this value is written
     * into a log record. Decision DL-041 scopes its sink to "any channel a human or a tool later
     * reads", and a log statement is such a channel; the description is supplied by the queue client
     * rather than by this module, so echoing it would carry externally-supplied text into the service
     * log through the one path that is not a caller-supplied value. The type is the classification
     * the client actually offers, and it is this module's own reading of the failure rather than the
     * failure's account of itself.
     *
     * @param publishFailure the failure the publish attempt raised
     * @return the response code, never {@code null} and never empty
     */
    private static String responseCodeOf(final Throwable publishFailure) {
        return diagnosticCodeOf(publishFailure.getClass());
    }

    /**
     * Derives the reason code recorded for a failed publish.
     *
     * <p>The legacy reason code qualified the response code: it said <em>why</em>, beneath the
     * <em>what</em>. The analogue is therefore the type of the deepest cause under the failure, which
     * is the qualification a messaging client's exception chain actually carries - a send failure
     * whose root is a socket timeout is a different operational condition from one whose root is a
     * missing queue, and the two are distinguishable here without either failure's description being
     * repeated.
     *
     * <p>The failure's description is deliberately <em>not</em> used. Decision DL-041 gives two
     * idioms for a guard, chosen by whether it sits on a failing path already, and this one does: a
     * guard on a value that merely <em>labels</em> another failure degrades to a substitute of the
     * same shape rather than throwing, because throwing here would replace the operator's real
     * diagnostic with a second, unrelated one. A sanitised type name is that substitute.
     *
     * <p>The walk is bounded by {@link #MAX_FAILURE_CHAIN_DEPTH} so that a self-referential or
     * mutually-referential cause chain terminates. A chain deeper than the bound yields the deepest
     * cause the bound reaches, which is a truthful qualification of the failure rather than a
     * guess about what lies beneath it.
     *
     * <p>A failure whose {@code getCause} returns the failure itself has nothing genuinely beneath it,
     * so it takes the no-cause path. {@link Throwable#initCause(Throwable)} forbids self-causation but
     * the accessor is overridable, and reporting one type as both the response and the reason would
     * describe a two-element chain where there is one. This keeps the invariant a reader relies on:
     * the reason code is empty exactly when {@link #failureChainOf(Throwable)} names a single type.
     *
     * @param publishFailure the failure the publish attempt raised
     * @return the reason code, never {@code null}; the empty string when the failure carries no
     *         cause, because there is then nothing beneath the response code to report and nothing
     *         may be invented
     */
    private static String reasonCodeOf(final Throwable publishFailure) {
        final Throwable beneathTheFailure = publishFailure.getCause();
        if (beneathTheFailure == null || beneathTheFailure == publishFailure) {
            return "";
        }
        Throwable deepest = beneathTheFailure;
        for (int depth = 1; depth < MAX_FAILURE_CHAIN_DEPTH; depth++) {
            final Throwable beneath = deepest.getCause();
            if (beneath == null || beneath == deepest) {
                break;
            }
            deepest = beneath;
        }
        return diagnosticCodeOf(deepest.getClass());
    }

    /**
     * Renders the chain of failure types recorded alongside the codes.
     *
     * <p>Suppressing the external description costs the diagnostic something real, and this restores
     * it without restoring the disclosure: the shape of the chain - which type wrapped which - is
     * frequently the whole diagnosis, and it is composed entirely of type names this module
     * sanitises. The chain is bounded in depth and every element is bounded in length, so the field
     * this class adds to a log record has a computable maximum size.
     *
     * @param publishFailure the failure the publish attempt raised
     * @return the chain, outermost type first, never {@code null} and never empty
     */
    private static String failureChainOf(final Throwable publishFailure) {
        final StringBuilder chain = new StringBuilder(diagnosticCodeOf(publishFailure.getClass()));
        Throwable current = publishFailure;
        for (int depth = 1; depth < MAX_FAILURE_CHAIN_DEPTH; depth++) {
            final Throwable beneath = current.getCause();
            if (beneath == null || beneath == current) {
                return chain.toString();
            }
            chain.append(FAILURE_CHAIN_SEPARATOR).append(diagnosticCodeOf(beneath.getClass()));
            current = beneath;
        }
        if (current.getCause() != null && current.getCause() != current) {
            chain.append(FAILURE_CHAIN_TRUNCATION_MARKER);
        }
        return chain.toString();
    }

    /**
     * Sanitises and bounds one type name so that it is safe to write into a log record.
     *
     * <p>A type's simple name is ordinarily a Java identifier and needs nothing done to it. Three
     * cases are not ordinary and all three are handled rather than assumed away: an anonymous class
     * reports the empty string, a synthetic or generated type may report a name carrying characters
     * an identifier may not hold, and a generated name may be arbitrarily long. Every character
     * outside the identifier set becomes {@value #DIAGNOSTIC_CODE_REPLACEMENT}, so no whitespace and
     * no control byte can reach the log and the code stays one unbroken token; the result is cut to
     * {@value #MAX_DIAGNOSTIC_CODE_LENGTH} characters; and a result that would be empty becomes
     * {@value #UNNAMED_FAILURE_TYPE}.
     *
     * @param failureType the type being named; must not be {@code null}
     * @return the sanitised code, never {@code null}, never empty, never longer than
     *         {@value #MAX_DIAGNOSTIC_CODE_LENGTH} characters, and holding only ASCII letters,
     *         ASCII digits, {@code $} and {@value #DIAGNOSTIC_CODE_REPLACEMENT}
     */
    private static String diagnosticCodeOf(final Class<?> failureType) {
        final String declared = failureType.getSimpleName();
        if (declared.isEmpty()) {
            return UNNAMED_FAILURE_TYPE;
        }
        final int retained = Math.min(declared.length(), MAX_DIAGNOSTIC_CODE_LENGTH);
        final StringBuilder sanitised = new StringBuilder(retained);
        for (int index = 0; index < retained; index++) {
            final char character = declared.charAt(index);
            sanitised.append(isDiagnosticCodeCharacter(character)
                    ? character
                    : DIAGNOSTIC_CODE_REPLACEMENT);
        }
        return sanitised.toString();
    }

    /**
     * Reports whether one character may appear in a derived diagnostic code.
     *
     * <p>The admissible set is the ASCII subset of what a Java type name may hold: ASCII letters,
     * ASCII digits and the two connectors. It is deliberately narrower than
     * {@link Character#isJavaIdentifierPart(char)}, which admits non-ASCII letters and, notably,
     * several Unicode formatting and ignorable code points - none of which belong in a value written
     * into a log record.
     *
     * @param character the character being examined
     * @return {@code true} when the character may be kept, {@code false} when it must be replaced
     */
    private static boolean isDiagnosticCodeCharacter(final char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '$'
                || character == DIAGNOSTIC_CODE_REPLACEMENT;
    }

    /**
     * The outcome of one job-submission attempt: how many cards reached the queue, whether the attempt
     * failed, and the operator-facing text when it did.
     *
     * <p>This value exists because a failed publish must <em>not</em> be reported by throwing. The queue is
     * defined ignore-on-error and the legacy transaction completes normally after a failed write, so the
     * outcome has to travel back as data. Three states are distinguishable and all three occur: a complete
     * submission, a failure after some cards were published, and a failure on the very first card.
     *
     * <p>{@code failureMessage} is normalised on construction: the frozen literal published by
     * {@code JobSubmissionException.DEFAULT_MESSAGE} when the attempt failed and no text was supplied, and
     * the empty string whenever it succeeded. It therefore never holds diagnostic detail, which belongs in
     * the log, and never holds the text "null".
     *
     * @param cardsRequested the number of cards the submission was asked to publish; never negative
     * @param cardsPublished the number of cards the queue accepted; never negative and never greater
     *                       than {@code cardsRequested}
     * @param failed         {@code true} when a publish failed and the submission stopped short
     * @param failureMessage the operator-facing failure text when {@code failed} is {@code true},
     *                       otherwise the empty string; never {@code null}
     */
    public record SubmissionResult(int cardsRequested, int cardsPublished, boolean failed,
            String failureMessage) {

        /**
         * Validates the counts and normalises the failure text.
         *
         * @throws IllegalArgumentException if either count is negative, or if {@code cardsPublished}
         *                                  exceeds {@code cardsRequested}
         */
        public SubmissionResult {
            if (cardsRequested < 0) {
                throw new IllegalArgumentException("cardsRequested must not be negative but was "
                        + cardsRequested);
            }
            if (cardsPublished < 0) {
                throw new IllegalArgumentException("cardsPublished must not be negative but was "
                        + cardsPublished);
            }
            if (cardsPublished > cardsRequested) {
                throw new IllegalArgumentException("cardsPublished (" + cardsPublished + ") must not"
                        + " exceed cardsRequested (" + cardsRequested + ")");
            }
            if (failed) {
                failureMessage = failureMessage == null || failureMessage.isEmpty()
                        ? JobSubmissionException.DEFAULT_MESSAGE
                        : failureMessage;
            } else {
                failureMessage = "";
            }
        }

        /**
         * Reports whether every requested card reached the queue.
         *
         * @return {@code true} when the attempt did not fail and every requested card was published
         */
        public boolean complete() {
            return !this.failed && this.cardsPublished == this.cardsRequested;
        }

        /**
         * Reports whether the submission stopped part way through, having published some cards but
         * not all of them.
         *
         * <p>This is the deliberate legacy outcome of a failed write: the emitting loop observes the
         * write-error flag and stops, so the cards after the failing one are never sent. It is
         * distinguished from a failure on the first card, where nothing at all reached the queue.
         *
         * @return {@code true} when the attempt failed after at least one card had been published
         */
        public boolean partial() {
            return this.failed && this.cardsPublished > 0;
        }
    }

}
