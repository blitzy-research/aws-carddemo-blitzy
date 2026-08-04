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
import java.util.concurrent.locks.ReentrantLock;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.util.FailureDiagnostics;
import com.carddemo.util.JclCardImageBuilder;
import com.carddemo.util.SqsNamingRules;

/**
 * The estate's only online-to-batch bridge: it hands a job-submission card stream from the online
 * tier to the batch tier, one fixed-width card per message, in order.
 *
 * <p>Legacy authority is the single transient-data-queue write in the transaction-report request
 * program {@code app/cbl/CORPT00C.cbl} (transaction {@code CR00}). Two regions bind this class: the
 * card-emitting loop at L496-L509, whose guard tests the card index against the declared array
 * bound, the end-of-stream flag and the write-error flag; and the queue-write paragraph at
 * L515-L535, which captures a response and reason code, continues on a normal response and otherwise
 * writes both codes to its diagnostic channel, raises the write-error flag, places a fixed failure
 * literal in the screen message field and re-sends the screen. It neither abends nor aborts the
 * transaction. Every rule below is read off those two regions.
 *
 * <p>The queue's declared attributes in {@code app/csd/CARDDEMO.CSD} L499-L505 each bind something
 * here. {@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)}: one card is one message, every body
 * an eighty-character image, never concatenated. {@code BLOCKFORMAT(UNBLOCKED)}: cards are published
 * one at a time. {@code DISPOSITION(MOD)}: writes append, so order is significant and one
 * submission's cards go to a single first-in-first-out message group. {@code ERROROPTION(IGNORE)}:
 * a write failure is ignored rather than raised - the decisive attribute, mapped as decision D-36.
 * {@code OPENTIME(INITIAL)}: the destination already exists, so this class never creates or
 * configures it. {@code TYPEFILE(OUTPUT)}: publish-only, so no receive, poll, purge or delete
 * operation is exposed.
 *
 * <p>A complete submission is exactly seventeen messages, sentinel included. The legacy loop
 * recognises the sentinel and sets its termination flag <em>before</em> performing the write, so the
 * sentinel card is itself transmitted and the loop then stops. Dropping it would break the
 * batch-trigger contract; sending an eighteenth would invent a message the legacy never sent. The
 * legacy array's index bound is never reached in practice and is reproduced as a defensive guard
 * only - not a capacity or a batch size.
 *
 * <p>A failed publish stops the remaining cards and is non-fatal. The write-error flag is one of the
 * three conditions in the emitting loop's guard, so the cards after the failing one are never sent -
 * a deliberate partial submission. The failure is logged with its response and reason codes and its
 * cause, control returns normally, and the failure text handed back is the frozen
 * {@code JobSubmissionException.DEFAULT_MESSAGE} and nothing else, so diagnostic detail cannot drift
 * into an operator-facing literal. A {@code JobSubmissionException} is constructed and logged at the
 * publish boundary and never propagated; callers read a {@link SubmissionResult}. No retry, backoff,
 * dead-letter redirect or circuit breaker exists, because none exists in the legacy and each would
 * add timing behaviour the migrated system must not inherit.
 *
 * <p>Every message body is exactly {@code JobSubmissionException.RECORD_SIZE} characters, asserted on
 * encoded bytes rather than character count. A card is never trimmed or right-justified before
 * publishing - the trailing spaces are part of the record - and a card of the wrong width, or holding
 * a character that is not a single US-ASCII byte, is rejected as a programming error. The card
 * content itself, including the four ten-character date slots and the sentinel, is owned by
 * {@link JclCardImageBuilder}.
 *
 * <p>No queue name, URL, ARN, account identifier, region or endpoint is written into this class: the
 * destination and message group are bound under the {@code carddemo.aws.*} prefix with no inline
 * default, matching the production profile's no-fallback rule. The queue keeps the legacy
 * transient-data queue's own name plus the one suffix a first-in-first-out queue demands, and the
 * unsuffixed form survives verbatim where it is externally observable - in the operator-facing
 * failure text and in the exception's default queue-name constant, which are frozen and compared
 * character for character, so the suffix cannot reach them. The suffix is validated when this bean is
 * constructed, so a misconfigured deployment fails at start-up rather than at first submission. The
 * name is reasoned in {@code docs/decision-log.md} DL-092 and the queue-not-found refusal strategy in
 * DL-093.
 *
 * <p>The typed settings over the {@code carddemo.aws} namespace validate these same two key paths
 * before this class is constructed, so a deployment that omits the queue or the message group, blanks
 * one of them, or supplies a name that is not a first-in-first-out name is refused at start-up. This
 * class nevertheless binds the two paths it needs directly, by key, because the layering does not
 * permit a service to depend on the configuration layer - a service may depend on the repository,
 * domain, utility and exception layers and on nothing above them. The two paths are therefore stated
 * in two places by necessity, and they are held to each other by a test that starts this class
 * against an environment carrying only the paths the settings type publishes: a path that drifted
 * here would leave the binding unresolved and construction would refuse it, so the agreement is a
 * checked invariant rather than a convention.
 *
 * <p>Message deduplication is an addition forced by the target technology: a first-in-first-out queue
 * requires either content-based deduplication or an explicit identifier per message, and the legacy
 * queue had no such concept. The identifier is the submission's identity plus the one-based card
 * ordinal - the ordinal keeps one submission's seventeen cards distinct, and the identity is what
 * makes two submissions distinguishable. It must not become an idempotency key: the legacy queue
 * appended, so a second request for the same reporting period ran the job again, and deriving the
 * identity from the dates alone would have the queue service discard that second request behind a
 * success response. Recorded as a parity exception in {@code docs/decision-log.md} DL-043.
 */
@Service
public final class JobSubmissionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobSubmissionService.class);

    /**
     * Configuration key for the destination queue. Held as a constant so the binding expression on
     * the constructor and the diagnostics that name the key cannot drift apart.
     */
    private static final String JOB_QUEUE_PROPERTY = "carddemo.aws.sqs.job-queue";

    private static final String MESSAGE_GROUP_ID_PROPERTY = "carddemo.aws.sqs.message-group-id";

    private static final String DEDUPLICATION_ID_SEPARATOR = "-";

    private static final String SUBMISSION_ID_PART_SEPARATOR = "_";

    private static final int DEDUPLICATION_ID_MAX_LENGTH = 128;

    private static final char MAX_US_ASCII_CHARACTER = 0x7F;

    private static final char MIN_PRINTABLE_US_ASCII_CHARACTER = 0x20;

    private static final char MAX_PRINTABLE_US_ASCII_CHARACTER = 0x7E;

    /**
     * The one-based ordinal of the first card, matching the legacy card index, which is one-based.
     */
    private static final int FIRST_CARD_ORDINAL = 1;

    /*
     * The bounds and substitutions that shape a failure diagnostic used to be declared here, five
     * constants and four helpers of them. They now live in FailureDiagnostics, in the utility layer,
     * because this class is no longer the only site that must decide what a failure may publish: the
     * REST boundary handler and the report-request service reached the same requirement and would
     * otherwise have carried a second copy of the same policy. Two copies of a disclosure rule are how
     * one of them is later widened alone, so there is one - and this class reads it rather than
     * restating it. The legacy meanings the codes stand in for, a response code and the reason code
     * beneath it, are documented on the two derivations further down, which is where they belong.
     */

    /**
     * Serialises one whole submission's publishes against every other submission's.
     *
     * <h2>What was wrong without it</h2>
     *
     * <p>A submission is not one message. It is
     * {@code JclCardImageBuilder.CARD_COUNT} messages that are only meaningful as a contiguous, ordered
     * run: the batch tier reads them back as one eighty-column job stream, so a job card followed by
     * another submission's library card is not a degraded stream, it is an unparseable one. This is a
     * singleton service and every card of every submission carries the <em>same</em> message group,
     * which is exactly what preserves append order - and it means the queue faithfully preserves
     * whatever order the sends arrived in. Two request threads publishing concurrently arrive
     * interleaved, and a first-in-first-out queue then guarantees the interleaving rather than
     * repairing it.
     *
     * <p>Deduplication identifiers do not help. They make a <em>retry</em> of one submission idempotent;
     * they say nothing about two distinct submissions, whose identifiers are all distinct by
     * construction and all therefore accepted, in whatever order they land.
     *
     * <h2>Why a lock, and why here</h2>
     *
     * <p>Two remedies were available. One is a message group per submission, which the queue service
     * would then keep separate - but the group is a configured value that must be one stable string
     * precisely because the legacy queue appended, so making it per-submission would trade this defect
     * for the loss of global append order across submissions, and would require every consumer to
     * reassemble groups. The other is to make the whole submission the unit of exclusion, which is what
     * this is: the legacy transaction wrote its card stream inside a single task, and one submission at
     * a time is what that reproduces.
     *
     * <p>It is held for the duration of one stream and released before the outcome is composed, so a
     * caller never holds it across anything of its own. It guarantees non-interleaving <em>within this
     * process</em>, which is the whole of the unit that publishes: nothing outside this service writes
     * to the queue. A deployment running several instances would need the same exclusion between them,
     * and the module introduces no coordination service to provide it - adding one is beyond the
     * migration's scope and is recorded rather than pretended. Fairness is requested so that a stream
     * waiting behind another is admitted in arrival order rather than starved.
     */
    private final ReentrantLock submissionLock = new ReentrantLock(true);

    private final SqsOperations sqsOperations;

    private final String queueName;

    private final String messageGroupId;

    /**
     * Binds the messaging client and the configured destination, validating the queue name's
     * first-in-first-out suffix and the message group identifier at construction time.
     *
     * @param sqsOperations the messaging template this service publishes through
     * @param queueName the configured destination queue name, which must carry the FIFO suffix
     * @param messageGroupId the configured message group identifier, which orders one submission
     */
    public JobSubmissionService(
            final SqsOperations sqsOperations,
            @Value("${" + JOB_QUEUE_PROPERTY + "}") final String queueName,
            @Value("${" + MESSAGE_GROUP_ID_PROPERTY + "}") final String messageGroupId) {
        this.sqsOperations = Objects.requireNonNull(sqsOperations, "sqsOperations must not be null");
        this.queueName = requireFifoQueueName(queueName);
        this.messageGroupId = SqsNamingRules.requireMessageGroupId(
                requireConfiguredValue(messageGroupId, MESSAGE_GROUP_ID_PROPERTY),
                MESSAGE_GROUP_ID_PROPERTY);
    }

    /**
     * Submits the transaction-report job for one date range, minting an identity for this call.
     *
     * <p>Every call to this form is a distinct submission: the identity it mints carries a nonce, so two
     * calls for the same reporting period enqueue two card streams exactly as the appending legacy queue
     * did. A <em>retry</em> of an earlier attempt must instead supply that attempt's identity through
     * {@link #submitTransactionReportJob(String, String, String)}, or the queue service's deduplication
     * interval will discard the retry behind a success response.
     *
     * <p>The dates are raw ten-character slot values in the legacy work field's hyphen-separated shape.
     * They are neither parsed nor reformatted here, but they are not unexamined either: the card builder
     * validates width, single-byte encodability, that shape and that the day named exists before it
     * composes a card, because two cards embed the slot inside a sort constant and one places it on a
     * parameter card, so no malformed slot value can reach a card image or the queue.
     *
     * <p>A publish failure does not throw; it is logged and reported through the result.
     *
     * @param startDate the ten-character start-date slot value
     * @param endDate the ten-character end-date slot value
     * @return the outcome: how many cards reached the queue, whether a failure occurred, and the frozen
     *         failure text when one did; never {@code null}
     * @throws NullPointerException if either date is {@code null}
     * @throws IllegalArgumentException if either date is not exactly ten encoded bytes, is not shaped as
     *                                  a hyphen-separated year, month and day, or does not name a day
     *                                  that exists
     */
    public SubmissionResult submitTransactionReportJob(final String startDate, final String endDate) {
        return submitTransactionReportJob(newSubmissionId(startDate, endDate),
                startDate, endDate);
    }

    /**
     * Submits the transaction-report job for one date range under a caller-supplied identity.
     *
     * <p>The identity is the caller's precisely so that a retry can repeat it and a new request cannot:
     * it is combined with each card's ordinal to form that card's deduplication identifier.
     *
     * @param submissionId the identity of this logical submission; must be non-blank, free of
     *                     whitespace, distinct for every distinct submission and identical only across
     *                     retries of the same submission
     * @param startDate the ten-character start-date slot value
     * @param endDate the ten-character end-date slot value
     * @return the outcome of the submission; never {@code null}
     */
    public SubmissionResult submitTransactionReportJob(final String submissionId,
            final String startDate, final String endDate) {
        final String submission = requireSubmissionId(submissionId);
        final List<String> cardImages = JclCardImageBuilder.build(startDate, endDate);
        return submitCanonicalJobImage(submission, cardImages);
    }

    /**
     * Publishes a card stream that must be the canonical seventeen-card image, sentinel last.
     *
     * <p>The stricter sibling of {@link #submitJobStream(String, List)}: it additionally asserts the
     * card count and the sentinel's position, so a caller that believes it is submitting the legacy job
     * cannot silently submit something else.
     *
     * @param submissionId the identity of this logical submission
     * @param cardImages the seventeen eighty-character card images in order
     * @return the outcome of the submission; never {@code null}
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
     * Publishes an arbitrary card stream, one message per card, in list order.
     *
     * <p>Reproduces the legacy loop: it stops at the sentinel - after transmitting it - at the declared
     * array bound, or at the first publish failure, whichever comes first, and it returns normally in
     * every case.
     *
     * @param submissionId the identity of this logical submission
     * @param cardImages the card images in publication order, each exactly eighty encoded bytes
     * @return the outcome of the submission; never {@code null}
     */
    public SubmissionResult submitJobStream(final String submissionId, final List<String> cardImages) {
        final String submission = requireSubmissionId(submissionId);
        final List<String> cards =
                List.copyOf(Objects.requireNonNull(cardImages, "cardImages must not be null"));
        requireWellFormedCards(cards);
        final List<String> deduplicationIds = deduplicationIds(submission, cards.size());

        int cardsPublished = 0;
        boolean endOfStream = false;
        boolean writeFailed = false;

        // One submission at a time. Everything from the first card to the last is inside the lock,
        // because a submission is meaningful to the batch tier only as a contiguous ordered run and
        // every card of every submission shares one message group - so a queue that faithfully
        // preserves arrival order preserves an interleaving just as faithfully as it preserves a
        // stream. See submissionLock for why the unit of exclusion is the whole stream rather than
        // the card, and why the group is not made per-submission instead.
        //
        // The lock is taken AFTER the stream is validated and the identifiers are composed, so a
        // malformed submission is refused without ever making a well-formed one wait, and the
        // critical section holds nothing but the sends.
        this.submissionLock.lock();
        try {
            // The three guard conditions are the legacy guard at lines 498 to 499: the one-based index
            // against the declared array bound, the end-of-stream flag and the write-error flag. The
            // supplied stream length bounds the iteration; the legacy array bound is carried alongside
            // it as a defensive guard and is never the condition that stops a well-formed submission.
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
                // The flag ends the loop after the card that carries it, which is the legacy conduct
                // for any stream; on the canonical path submitCanonicalJobImage has already established
                // that only the final card can carry it, so it never truncates a canonical submission.
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
        } finally {
            // Released in a finally rather than after the loop: publishCard is documented never to
            // propagate, but a lock that depends on a callee keeping a promise is a lock that a future
            // change can leave held for the lifetime of the process, and every subsequent submission
            // would then block forever.
            this.submissionLock.unlock();
        }

        final SubmissionResult result = new SubmissionResult(cards.size(), cardsPublished, writeFailed,
                writeFailed ? JobSubmissionException.DEFAULT_MESSAGE : "");

        if (result.failed()) {
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
     * Publishes one card: the direct counterpart of the legacy queue-write paragraph, for a caller
     * driving the loop itself.
     *
     * @param submissionId the identity of the submission this card belongs to
     * @param cardImage the eighty-character card image
     * @param cardOrdinal the one-based ordinal of this card within its submission, which makes the
     *                    card's deduplication identifier distinct
     * @return {@code true} when the card reached the queue; {@code false} when the publish failed, which
     *         is logged rather than raised
     */
    public boolean writeJobSubmissionQueue(final String submissionId, final String cardImage,
            final int cardOrdinal) {
        final String submission = requireSubmissionId(submissionId);
        final String card = requireCardImage(cardImage, cardOrdinal);
        return publishCard(submission, card, cardOrdinal, deduplicationId(submission, cardOrdinal));
    }

    private boolean publishCard(final String submission, final String card, final int cardOrdinal,
            final String deduplicationId) {
        try {
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
            final JobSubmissionException failure = new JobSubmissionException(this.queueName,
                    responseCodeOf(publishFailure), reasonCodeOf(publishFailure), cardOrdinal,
                    publishFailure);
            LOGGER.error("{} ordinal={} submission={} queue={} messageGroup={} response={} reason={}"
                            + " failureChain={}",
                    JobSubmissionException.DEFAULT_MESSAGE, cardOrdinal, submission, this.queueName,
                    this.messageGroupId, failure.responseCode(), failure.reasonCode(),
                    failureChainOf(publishFailure));
            return false;
        }
    }

    private static boolean isEndOfStreamCard(final String cardImage) {
        if (JclCardImageBuilder.EOF_SENTINEL_CARD.equals(trailingSpacesRemoved(cardImage))) {
            return true;
        }
        return isUniformlyFilledWith(cardImage, ' ') || isUniformlyFilledWith(cardImage, '\0');
    }

    private static String trailingSpacesRemoved(final String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

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

    private static void requireWellFormedCards(final List<String> cards) {
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("cardImages must hold at least one job-submission card");
        }
        for (int cardOrdinal = FIRST_CARD_ORDINAL; cardOrdinal <= cards.size(); cardOrdinal++) {
            requireCardImage(cards.get(cardOrdinal - FIRST_CARD_ORDINAL), cardOrdinal);
        }
    }

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

    private static List<String> deduplicationIds(final String submissionId, final int cardCount) {
        final List<String> identifiers = new ArrayList<>(cardCount);
        for (int cardOrdinal = FIRST_CARD_ORDINAL; cardOrdinal <= cardCount; cardOrdinal++) {
            identifiers.add(deduplicationId(submissionId, cardOrdinal));
        }
        return List.copyOf(identifiers);
    }

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

    private static String deduplicationId(final String submissionId, final int cardOrdinal) {
        if (cardOrdinal < FIRST_CARD_ORDINAL) {
            throw new IllegalArgumentException("cardOrdinal is one-based and must be at least "
                    + FIRST_CARD_ORDINAL + " but was " + cardOrdinal);
        }
        final String deduplicationId = submissionId + DEDUPLICATION_ID_SEPARATOR + cardOrdinal;
        if (deduplicationId.length() > DEDUPLICATION_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("the message deduplication identifier composed from"
                    + " a submission identity of " + submissionId.length() + " characters and card"
                    + " ordinal " + cardOrdinal + " is " + deduplicationId.length()
                    + " characters, which exceeds the " + DEDUPLICATION_ID_MAX_LENGTH
                    + " the queue service accepts");
        }
        return deduplicationId;
    }

    private static String newSubmissionId(final String startDate, final String endDate) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        return requireSubmissionId(withoutWhitespace(startDate) + SUBMISSION_ID_PART_SEPARATOR
                + withoutWhitespace(endDate));
    }

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

    private static String requireFifoQueueName(final String queueName) {
        final String configured = requireConfiguredValue(queueName, JOB_QUEUE_PROPERTY);
        return SqsNamingRules.requireQueueDestination(configured, JOB_QUEUE_PROPERTY);
    }

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
     * {@link FailureDiagnostics#typeNameOf(Class)}. No reflective lookup is performed and no type is
     * loaded by name.
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
        return FailureDiagnostics.typeNameOf(publishFailure.getClass());
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
     * <p>The walk is bounded by {@link FailureDiagnostics#MAX_FAILURE_CHAIN_DEPTH} so that a
     * self-referential or
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
        return FailureDiagnostics.rootFailureTypeOf(publishFailure);
    }

    private static String failureChainOf(final Throwable publishFailure) {
        return FailureDiagnostics.failureChainOf(publishFailure);
    }

    /**
     * Outcome of one submission.
     *
     * @param cardsRequested the number of cards the caller supplied
     * @param cardsPublished the number that reached the queue, which is lower on a partial submission
     * @param failed whether a publish failed, in which case the remaining cards were not sent
     * @param failureMessage the frozen operator-facing failure text when {@code failed}, otherwise empty
     */
    public record SubmissionResult(int cardsRequested, int cardsPublished, boolean failed,
            String failureMessage) {
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

        public boolean complete() {
            return !this.failed && this.cardsPublished == this.cardsRequested;
        }

        public boolean partial() {
            return this.failed && this.cardsPublished > 0;
        }
    }
}
