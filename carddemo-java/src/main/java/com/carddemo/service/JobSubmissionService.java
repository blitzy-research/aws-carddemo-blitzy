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
import java.util.List;
import java.util.Objects;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.util.JclCardImageBuilder;

/**
 * The estate's only online-to-batch bridge: it hands a job-submission card stream from the online
 * tier to the batch tier, one fixed-width card per message, in order.
 *
 * <h2>Legacy authority</h2>
 *
 * <p>The CardDemo mainframe estate contains exactly one transient-data-queue write. It lives in the
 * transaction-report request program {@code app/cbl/CORPT00C.cbl} (transaction {@code CR00}) and is
 * reached from a single paragraph. Two regions of that member are the authority for this class:
 *
 * <ul>
 *   <li>lines 496 to 509 - the card-emitting loop. A loop-control flag is cleared at line 496; the
 *       loop is entered at lines 498 to 499 with a guard that tests the one-based card index against
 *       the declared array bound, the end-of-stream flag and the write-error flag; the current card
 *       is moved into the eighty-character record at line 501; the end-of-stream test is made at
 *       lines 502 to 505; and the write is performed at line 507.</li>
 *   <li>lines 515 to 535 - the queue-write paragraph itself. It writes the eighty-character record
 *       to the queue capturing a response code and a reason code, continues on a normal response,
 *       and on any other response writes both codes to its diagnostic channel, raises the
 *       write-error flag, places a fixed failure literal in the screen message field, repositions
 *       the cursor and re-sends the screen. It does not abend and it does not abort the
 *       transaction.</li>
 * </ul>
 *
 * <p>Because that single paragraph is the whole of the legacy submission mechanism, this class is
 * its only translation, and every behavioural rule below is read off those two regions rather than
 * chosen.
 *
 * <h2>Queue contract</h2>
 *
 * <p>The destination is declared in {@code app/csd/CARDDEMO.CSD} at lines 499 to 505. Its attributes
 * are contractual rather than incidental, and each one binds this class:
 *
 * <ul>
 *   <li>{@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)} - one card is one message and every
 *       message body is a fixed-width eighty-character image. Cards are never concatenated into one
 *       message and a variable-length body is never sent.</li>
 *   <li>{@code BLOCKFORMAT(UNBLOCKED)} - each card is an individually addressable record, which is
 *       the second reason cards are published one at a time.</li>
 *   <li>{@code DISPOSITION(MOD)} - writes append, so order is significant. Every card of one
 *       submission is published into a single first-in-first-out message group, which is what
 *       preserves that order end to end.</li>
 *   <li>{@code ERROROPTION(IGNORE)} - a write failure is ignored rather than raised. This is the
 *       decisive attribute and it is what makes the failure path of this class non-fatal.</li>
 *   <li>{@code TYPE(EXTRA)}, {@code DATABUFFERS(1)}, {@code DDNAME(INREADER)} and
 *       {@code OPENTIME(INITIAL)} - the destination exists and is open before first use. This class
 *       therefore never creates, configures or describes the queue; provisioning belongs to
 *       {@code carddemo-java/localstack/init/01-create-aws-resources.sh} for local validation and to
 *       the deployment elsewhere.</li>
 *   <li>{@code TYPEFILE(OUTPUT)} - the destination is publish-only from the online tier. This class
 *       exposes no receive, poll, peek, purge or delete operation, and the batch tier is the only
 *       consumer.</li>
 * </ul>
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
 * {@code SubmissionResult} describing the outcome instead. No retry, no backoff, no dead-letter
 * redirect and no circuit breaker is implemented: none exists in the legacy, and each would add a
 * timing characteristic the migrated system must not inherit. Nothing on the publish path takes part
 * in a database transaction, because this class performs no database work.
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
 * rather than to this class. The canonical names are the job-submission FIFO queue, the
 * job-submission message group, the job-notification topic and the batch-staging bucket; only the
 * first two are referenced here, because this class publishes one kind of message and nothing else.
 *
 * <p>The queue name <strong>must end in the FIFO suffix</strong>: a first-in-first-out queue is
 * rejected by the queue service unless its name carries that suffix, and ordering is contractual
 * here rather than optional. The suffix is therefore validated when this bean is constructed, so a
 * misconfigured deployment fails at startup rather than at the first submission. Neither bound value
 * carries an inline default, which matches the production profile: it resolves every secret and
 * every resource name from an environment variable with no fallback, so a missing value fails
 * startup rather than silently binding a placeholder.
 *
 * <h2>Message deduplication</h2>
 *
 * <p>A first-in-first-out queue requires either content-based deduplication enabled on the queue or
 * an explicit deduplication identifier on every message. The legacy transient-data queue had no
 * deduplication concept at all, so this is an addition forced by the target technology and is
 * recorded as such.
 *
 * <p>An explicit per-message identifier is supplied, composed of the submission's own identity and
 * the one-based card ordinal. The composition is deliberate on both halves. The ordinal keeps the
 * seventeen cards of one submission distinct from each other, which content-based deduplication
 * would also achieve; the submission identity makes two submissions of the same reporting period
 * deduplicate against each other, which content-based deduplication would achieve only by accident
 * of the card bytes and which a random identifier would defeat entirely. Nothing here is random, so
 * a repeated submission is idempotent within the queue service's own deduplication interval.
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
     * Separator between the two date slots when a submission identity is derived from them.
     */
    private static final String SUBMISSION_ID_DATE_SEPARATOR = "_";

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
     * The one-based ordinal of the first card, matching the legacy card index, which is one-based.
     */
    private static final int FIRST_CARD_ORDINAL = 1;

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
     * Submits the transaction-report job for one date range: builds the card stream and publishes
     * it.
     *
     * <p>This is the entry point that mirrors the legacy request path. The card images are built by
     * {@code JclCardImageBuilder}, which owns every literal and fills the four ten-character date
     * slots from these two arguments, and the resulting stream is then published by
     * {@code submitJobStream}. The submission identity used for message deduplication is derived
     * from the two date slots, so two submissions of the same reporting period are idempotent while
     * two submissions of different periods are not.
     *
     * <p>The dates are raw ten-character slot values, conventionally formatted year, month and day
     * separated by hyphens. They are neither parsed nor reformatted here; the card builder validates
     * their width, and their calendar validity is the report-request service's concern.
     *
     * <p>A publish failure does not throw. It is logged and reported through the returned result, in
     * line with the queue's ignore-on-error contract.
     *
     * @param startDate the ten-character start-date slot value; must not be {@code null}
     * @param endDate   the ten-character end-date slot value; must not be {@code null}
     * @return the outcome, reporting how many cards reached the queue, whether a failure occurred
     *         and the frozen failure text when one did; never {@code null}
     * @throws NullPointerException     if either date is {@code null}
     * @throws IllegalArgumentException if either date is not exactly ten encoded bytes, or if the
     *                                  identity derived from the two dates cannot form a valid
     *                                  deduplication identifier
     */
    public SubmissionResult submitTransactionReportJob(final String startDate, final String endDate) {
        // The builder validates both slots before any card exists, so a malformed date fails here
        // rather than part way through a submission.
        final List<String> cardImages = JclCardImageBuilder.build(startDate, endDate);
        return submitJobStream(deriveSubmissionId(startDate, endDate), cardImages);
    }

    /**
     * Publishes an already-built job-submission card stream, one message per card, in order, in a
     * single message group.
     *
     * <p>This method is the faithful translation of the emitting loop at
     * {@code app/cbl/CORPT00C.cbl} lines 496 to 509, and its shape is dictated by that loop rather
     * than chosen:
     *
     * <ul>
     *   <li>the guard is evaluated before every card, testing the one-based ordinal against both the
     *       supplied stream length and the legacy array bound, and testing the end-of-stream and
     *       write-failure flags;</li>
     *   <li>the end-of-stream test is applied to a card <em>before</em> that card is published, which
     *       is why the sentinel card is transmitted and a complete submission is seventeen
     *       messages;</li>
     *   <li>a failed publish raises the write-failure flag, which the guard then observes, so the
     *       remaining cards are not sent and the submission is left partial.</li>
     * </ul>
     *
     * <p>Every card in the stream is checked for the fixed record width before anything is
     * published, so a malformed stream is rejected outright rather than leaving a partial submission
     * behind. That check guards against a programming error; it has no legacy antecedent, because the
     * legacy card group is a fixed layout that cannot be malformed.
     *
     * <p>The stream is copied on entry, so a caller mutating its list afterwards cannot affect a
     * submission in progress, and no caller-supplied structure is retained after the call returns.
     *
     * @param submissionId the submission's identity, used with the card ordinal to form each
     *                     message's deduplication identifier; must be non-blank and free of
     *                     whitespace
     * @param cardImages   the ordered card stream to publish, each card exactly
     *                     {@code JobSubmissionException.RECORD_SIZE} encoded bytes; must not be
     *                     {@code null}, must not be empty and must contain no {@code null} element
     * @return the outcome, reporting how many cards reached the queue, whether a failure occurred
     *         and the frozen failure text when one did; never {@code null}
     * @throws NullPointerException     if {@code submissionId}, {@code cardImages} or any element of
     *                                  {@code cardImages} is {@code null}
     * @throws IllegalArgumentException if {@code submissionId} is blank or holds whitespace, if
     *                                  {@code cardImages} is empty, if any card is not exactly
     *                                  {@code JobSubmissionException.RECORD_SIZE} encoded bytes or
     *                                  holds a character that is not a single US-ASCII byte, or if a
     *                                  deduplication identifier would exceed the length the queue
     *                                  service accepts
     */
    public SubmissionResult submitJobStream(final String submissionId, final List<String> cardImages) {
        final String submission = requireSubmissionId(submissionId);
        // List.copyOf rejects a null element and yields an immutable snapshot, so the sequence
        // published is exactly the sequence supplied at the moment of the call.
        final List<String> cards =
                List.copyOf(Objects.requireNonNull(cardImages, "cardImages must not be null"));
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("cardImages must hold at least one job-submission card");
        }
        requireWellFormedCards(cards);

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
            endOfStream = isEndOfStreamCard(cardImage);

            if (writeJobSubmissionQueue(submission, cardImage, cardOrdinal)) {
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
     * Publishes exactly one job-submission card and reports whether it was accepted, without
     * throwing on a publish failure.
     *
     * <p><strong>Traceability.</strong> This method replaces the queue-write paragraph at
     * {@code app/cbl/CORPT00C.cbl} line 515. That paragraph's name is misspelled in the source - it
     * reads {@code WIRTE-JOBSUB-TDQ} rather than the intended spelling - and the misspelling is
     * recorded here, and in the traceability matrix, so the mapping stays findable under the name
     * the source actually uses. It is deliberately not carried into any Java identifier: the
     * paragraph is misspelled, this method is not.
     *
     * <p>The returned value is the inverse of the legacy write-error flag. A normal response leaves
     * that flag clear and the paragraph simply continues, which is reported here as {@code true}. Any
     * other response raises the flag, which is reported as {@code false} - and because the flag also
     * appears in the emitting loop's guard, {@code false} is what stops the remaining cards.
     *
     * <p>A publish failure never propagates. A {@code JobSubmissionException} is constructed to carry
     * the queue name, the response and reason codes, the failing card's one-based ordinal and the
     * underlying cause; it is logged with that cause; and {@code false} is returned. This mirrors the
     * queue definition's ignore-on-error attribute and the paragraph's own behaviour, which reports
     * the failure to the operator and returns control normally. No retry, backoff, dead-letter
     * redirect or circuit breaker is attempted.
     *
     * <p>A malformed argument is a different matter and does throw: it is a programming error with no
     * legacy antecedent, so the fixed-width precondition is enforced with
     * {@code IllegalArgumentException} rather than reported as a publish failure. The card is
     * published exactly as supplied - never trimmed, stripped or padded - because its trailing spaces
     * are part of the fixed-width record.
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
        final String deduplicationId = deduplicationId(submission, cardOrdinal);

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
            // here. The exception is built for its diagnostic content and logged; it is never
            // rethrown, because rethrowing would abort a request the legacy transaction completes.
            final JobSubmissionException failure = new JobSubmissionException(this.queueName,
                    responseCodeOf(publishFailure), reasonCodeOf(publishFailure), cardOrdinal,
                    publishFailure);
            // Mirrors the legacy diagnostic write of the response and reason codes, which the
            // paragraph performs before it reports the failure to the operator.
            LOGGER.error("{} ordinal={} submission={} queue={} messageGroup={} response={} reason={}",
                    JobSubmissionException.DEFAULT_MESSAGE, cardOrdinal, submission, this.queueName,
                    this.messageGroupId, failure.responseCode(), failure.reasonCode(), failure);
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
     * Validates every card of a stream before any of them is published.
     *
     * <p>This is a fail-fast guard rather than a translated behaviour: the legacy card group is a
     * fixed layout and cannot be malformed, so there is no legacy conduct to preserve. Checking the
     * whole stream up front means a malformed card cannot leave a partial submission behind, which
     * would otherwise be indistinguishable from the deliberate partial submission a publish failure
     * produces.
     *
     * @param cards the stream to validate, in order
     * @throws IllegalArgumentException if any card is not exactly
     *                                  {@code JobSubmissionException.RECORD_SIZE} encoded bytes or
     *                                  holds a character that is not a single US-ASCII byte
     */
    private static void requireWellFormedCards(final List<String> cards) {
        for (int cardOrdinal = FIRST_CARD_ORDINAL; cardOrdinal <= cards.size(); cardOrdinal++) {
            requireCardImage(cards.get(cardOrdinal - FIRST_CARD_ORDINAL), cardOrdinal);
        }
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
     * @param cardImage   the card to validate
     * @param cardOrdinal the card's one-based ordinal, used only to identify it in a diagnostic
     * @return {@code cardImage}, unchanged
     * @throws NullPointerException     if {@code cardImage} is {@code null}
     * @throws IllegalArgumentException if {@code cardImage} is not exactly
     *                                  {@code JobSubmissionException.RECORD_SIZE} encoded bytes or
     *                                  holds a character that is not a single US-ASCII byte
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
            if (cardImage.charAt(index) > MAX_US_ASCII_CHARACTER) {
                throw new IllegalArgumentException("job-submission card " + cardOrdinal
                        + " holds a character at position " + (index + FIRST_CARD_ORDINAL)
                        + " that is not representable as a single US-ASCII byte");
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
     * @param submissionId the identity to validate
     * @return {@code submissionId}, unchanged
     * @throws NullPointerException     if {@code submissionId} is {@code null}
     * @throws IllegalArgumentException if {@code submissionId} is blank or holds whitespace
     */
    private static String requireSubmissionId(final String submissionId) {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        if (submissionId.isBlank()) {
            throw new IllegalArgumentException("submissionId must not be blank");
        }
        for (int index = 0; index < submissionId.length(); index++) {
            if (Character.isWhitespace(submissionId.charAt(index))) {
                throw new IllegalArgumentException("submissionId must not hold whitespace because a"
                        + " message deduplication identifier may not: '" + submissionId + "'");
            }
        }
        return submissionId;
    }

    /**
     * Composes the deduplication identifier for one card.
     *
     * <p>The identifier is the submission identity, a separator and the card's one-based ordinal. It
     * is fully determined by its inputs: nothing random and nothing time-derived takes part, so
     * republishing the same submission produces the same identifiers and the queue service treats the
     * repetition as a duplicate rather than as new work. Content-based deduplication is deliberately
     * not relied upon, so this identifier is what makes a first-in-first-out publish acceptable to the
     * queue service.
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
            throw new IllegalArgumentException("the message deduplication identifier derived from"
                    + " submission '" + submissionId + "' and card ordinal " + cardOrdinal + " is "
                    + deduplicationId.length() + " characters, which exceeds the "
                    + DEDUPLICATION_ID_MAX_LENGTH + " the queue service accepts");
        }
        return deduplicationId;
    }

    /**
     * Derives a submission identity from the two date slots that define a reporting period.
     *
     * <p>The two slots fully determine the card stream, so an identity derived from them makes two
     * submissions of the same period deduplicate against each other, which is the idempotency the
     * explicit-identifier strategy exists to provide. Whitespace is removed because the slots are
     * fixed-width values that may be space-padded while a deduplication identifier may hold no
     * whitespace; only the derived identity is condensed, never a card, whose padding is contractual.
     *
     * @param startDate the start-date slot value
     * @param endDate   the end-date slot value
     * @return the derived submission identity
     * @throws NullPointerException     if either date is {@code null}
     * @throws IllegalArgumentException if the derived identity is blank
     */
    private static String deriveSubmissionId(final String startDate, final String endDate) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        return requireSubmissionId(withoutWhitespace(startDate) + SUBMISSION_ID_DATE_SEPARATOR
                + withoutWhitespace(endDate));
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
                    + " order; the configured value was '" + configured + "'");
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
     * @param value       the bound value, possibly {@code null}
     * @param propertyKey the configuration key it was bound from, named in the diagnostic
     * @return {@code value}, unchanged
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank
     */
    private static String requireConfiguredValue(final String value, final String propertyKey) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("property " + propertyKey + " must be configured with"
                    + " a non-blank value; it is resolved from configuration and has no default");
        }
        return value;
    }

    /**
     * Derives the response code recorded for a failed publish.
     *
     * <p>The legacy paragraph captured a numeric response code from the queue write. The messaging
     * client reports a failure as an exception instead, so the analogue recorded here is the
     * failure's own type name, read from the object's identity. No reflective lookup is performed and
     * no type is loaded by name.
     *
     * @param publishFailure the failure the publish attempt raised
     * @return the response code, never {@code null}
     */
    private static String responseCodeOf(final Throwable publishFailure) {
        return publishFailure.getClass().getSimpleName();
    }

    /**
     * Derives the reason code recorded for a failed publish.
     *
     * <p>The analogue of the legacy reason code is the failure's own description. Only its first line
     * is recorded, so the code stays a single readable value; the complete description, and the whole
     * cause chain beneath it, reaches the log through the logged throwable.
     *
     * @param publishFailure the failure the publish attempt raised
     * @return the reason code, never {@code null}; the empty string when the failure carries no
     *         description
     */
    private static String reasonCodeOf(final Throwable publishFailure) {
        final String description = publishFailure.getMessage();
        if (description == null || description.isBlank()) {
            return "";
        }
        return description.lines().findFirst().orElse("");
    }

    /**
     * The outcome of one job-submission attempt: how many cards reached the queue, whether the
     * attempt failed, and the operator-facing text when it did.
     *
     * <p>This value exists because a failed publish must <em>not</em> be reported by throwing. The
     * queue is defined ignore-on-error and the legacy transaction completes normally after a failed
     * write, so the outcome has to travel back as data. Three states are distinguishable and all
     * three occur in practice: a complete submission, a failure after some cards were published, and
     * a failure on the very first card.
     *
     * <p>{@code failureMessage} is normalised on construction: it is the frozen operator-facing
     * literal published by {@code JobSubmissionException.DEFAULT_MESSAGE} when the attempt failed and
     * no text was supplied, and it is the empty string whenever the attempt succeeded. It therefore
     * never holds diagnostic detail, which belongs in the log, and never holds the text "null".
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
