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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * makes two submissions distinguishable. The HTTP caller supplies an identity derived from the date
 * range and a stable logical-request token: a retry repeats that token, while a deliberate new
 * submission receives another. The date-only convenience overload remains a documented compatibility
 * form for retries and must not be used to express a second run of the same period. Recorded as a parity
 * exception in {@code docs/decision-log.md} DL-043.
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

    /** Name of the explicit outbound observation wrapped around each queue publish. */
    public static final String PUBLISH_OBSERVATION_NAME = "carddemo.job.submission.publish";

    public static final String TAG_SYSTEM = "system";
    public static final String TAG_OPERATION = "operation";
    public static final String TAG_QUEUE = "queue";
    public static final String TAG_SUBMISSION = "submission";
    public static final String TAG_CARD_ORDINAL = "cardOrdinal";
    public static final String SYSTEM_SQS = "sqs";
    public static final String OPERATION_SEND = "send";

    /**
     * Message attribute naming the submission every card belongs to.
     *
     * <p>Published on every message, and the key of the reassembly envelope described on
     * {@link #submissionLock}. It is what lets a consumer group a submission's cards back together
     * without depending on the order they arrived in, which is the only remedy available to a
     * deployment running more than one instance: the module introduces no coordination service, so the
     * cards of two concurrent submissions <em>can</em> interleave in the one message group, and the
     * envelope is what makes that interleaving recoverable rather than fatal.
     */
    public static final String SUBMISSION_ID_HEADER = "carddemo-submission-id";

    /**
     * Message attribute carrying the one-based ordinal of this card within its submission.
     *
     * <p>Published on every message. With the submission identity it totally orders a submission's
     * cards, so a consumer restores the eighty-column job stream by sorting on it rather than by
     * trusting arrival order.
     */
    public static final String CARD_ORDINAL_HEADER = "carddemo-card-ordinal";

    /**
     * Message attribute carrying how many cards the submission holds in total.
     *
     * <p>Published only by the entry points that publish a whole stream and therefore know the total. The
     * single-card entry point omits it, because a caller driving its own loop has not declared a total and
     * inventing one would be a claim this service is not entitled to make. A consumer is not left without
     * a completion test in that case: the final card of a legacy job stream is the end-of-stream sentinel,
     * which is transmitted, so the sentinel terminates the stream exactly as it does on the mainframe and
     * this attribute is a cross-check rather than the only marker.
     */
    public static final String CARD_COUNT_HEADER = "carddemo-card-count";

    /**
     * The separator between the date part of a minted identity and its nonce.
     *
     * <p>Distinct from {@link #SUBMISSION_ID_PART_SEPARATOR} so that the nonce is visually separable from
     * the period in a log record; nothing parses either, and no consumer may.
     */
    private static final String SUBMISSION_NONCE_SEPARATOR = "_";

    /** The character a minted nonce strips from the generated value, which the queue need not carry. */
    private static final String NONCE_GROUP_SEPARATOR = "-";

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
     * <h2>Why the whole submission is the unit of exclusion</h2>
     *
     * <p>A submission is not one message. It is
     * {@code JclCardImageBuilder.CARD_COUNT} messages that are only meaningful as a contiguous, ordered
     * run: the batch tier reads them back as one eighty-column job stream, so a job card followed by
     * another submission's library card is not a degraded stream, it is an unparseable one. This is a
     * singleton service and every card of every submission carries the <em>same</em> message group,
     * which is what preserves append order - and it means the queue faithfully preserves whatever order
     * the sends arrived in. Two request threads publishing concurrently would arrive interleaved, and a
     * first-in-first-out queue would then guarantee the interleaving rather than repairing it.
     *
     * <p>Deduplication identifiers do not close that. They make a <em>retry</em> of one submission
     * idempotent; they say nothing about two distinct submissions, whose identifiers are all distinct by
     * construction and all therefore accepted, in whatever order they land.
     *
     * <p>A message group per submission would let the queue service keep the streams apart, but the
     * group is a configured value that must be one stable string precisely because the legacy queue
     * appended: making it per-submission would forfeit global append order across submissions and would
     * require every consumer to reassemble groups. Excluding one whole submission at a time is what
     * reproduces the legacy transaction, which wrote its card stream inside a single task.
     *
     * <h2>What the lock does and does not guarantee</h2>
     *
     * <p>It is held for the duration of one stream and released before the outcome is composed, so a
     * caller never holds it across anything of its own. It guarantees non-interleaving <em>within this
     * process</em>, which is the whole of the unit that publishes: nothing outside this service writes
     * to the queue. Fairness is requested so that a stream waiting behind another is admitted in arrival
     * order rather than starved.
     *
     * <h2>Across instances the lock does nothing, so the envelope does it instead</h2>
     *
     * <p>The lock is a monitor in one heap. A deployment running several instances would need the same
     * exclusion between them, and the module introduces no coordination service to provide it: a
     * distributed or fenced lock is a new piece of infrastructure, and a single-active-publisher election
     * is another, both beyond this migration's scope. Leaving it there would leave a real defect, because
     * two instances publishing concurrently interleave their cards in the one message group and a
     * first-in-first-out queue then preserves the interleaving faithfully.
     *
     * <p>The remedy carried instead is the third of the three the review named: an
     * <strong>atomic, reassemblable envelope keyed by submission</strong>. Every published message carries
     * {@link #SUBMISSION_ID_HEADER} and {@link #CARD_ORDINAL_HEADER}, and every message of a stream whose
     * total this service knows also carries {@link #CARD_COUNT_HEADER}. A consumer therefore groups by
     * submission and orders by ordinal, so it reconstructs each eighty-column job stream exactly whatever
     * order the messages arrived in, and it knows a stream is whole either by counting to the declared
     * total or by reaching the transmitted end-of-stream sentinel. Interleaving becomes a property of the
     * transport that the consumer undoes, rather than a corruption it inherits - and that holds for two
     * instances just as it holds for two threads, which is what makes it the remedy that does not need a
     * coordination service. The lock is kept because within one process it prevents the interleaving in
     * the first place, which is cheaper than undoing it.
     */
    private final SqsOperations sqsOperations;

    private final String queueName;

    private final String messageGroupId;

    /** Deployment-wide guard held for a whole card stream. */
    private final JobSubmissionCoordinator submissionCoordinator;

    /** Persistent logical-submission and per-card delivery state. */
    private final JobSubmissionOutbox submissionOutbox;

    /** Registry for the explicit outbound publish observation. */
    private final ObservationRegistry observationRegistry;

    /**
     * Binds the messaging client and the configured destination, validating the queue name's
     * first-in-first-out suffix and the message group identifier at construction time.
     *
     * @param sqsOperations the messaging template this service publishes through
     * @param queueName the configured destination queue name, which must carry the FIFO suffix
     * @param messageGroupId the configured message group identifier, which orders one submission
     */
    @Autowired
    public JobSubmissionService(
            final SqsOperations sqsOperations,
            @Value("${" + JOB_QUEUE_PROPERTY + "}") final String queueName,
            @Value("${" + MESSAGE_GROUP_ID_PROPERTY + "}") final String messageGroupId,
            final JobSubmissionCoordinator submissionCoordinator,
            final JobSubmissionOutbox submissionOutbox,
            final ObservationRegistry observationRegistry) {
        this.sqsOperations = Objects.requireNonNull(sqsOperations, "sqsOperations must not be null");
        this.queueName = requireFifoQueueName(queueName);
        this.messageGroupId = SqsNamingRules.requireMessageGroupId(
                requireConfiguredValue(messageGroupId, MESSAGE_GROUP_ID_PROPERTY),
                MESSAGE_GROUP_ID_PROPERTY);
        this.submissionCoordinator = Objects.requireNonNull(submissionCoordinator,
                "submissionCoordinator must not be null");
        this.submissionOutbox = Objects.requireNonNull(
                submissionOutbox, "submissionOutbox must not be null");
        this.observationRegistry = Objects.requireNonNull(observationRegistry,
                "observationRegistry must not be null");
    }

    /**
     * Direct-construction seam for tests that supply a coordinator and observation registry.
     */
    JobSubmissionService(final SqsOperations sqsOperations, final String queueName,
            final String messageGroupId, final JobSubmissionCoordinator submissionCoordinator,
            final ObservationRegistry observationRegistry) {
        this(sqsOperations, queueName, messageGroupId, submissionCoordinator,
                JobSubmissionOutbox.direct(), observationRegistry);
    }

    /**
     * Direct-construction seam for focused queue-contract tests.
     */
    JobSubmissionService(final SqsOperations sqsOperations, final String queueName,
            final String messageGroupId) {
        this(sqsOperations, queueName, messageGroupId, submission -> submission.get(),
                JobSubmissionOutbox.direct(), ObservationRegistry.NOOP);
    }

    /**
     * Submits the transaction-report job for one date range under a deterministic compatibility identity.
     *
     * <p>Repeated calls to this convenience form for the same range intentionally reuse one identity and
     * therefore describe retries. A deliberate new submission of that same range must call
     * {@link #submitTransactionReportJob(String, String, String)} with a distinct logical-request token.
     * The HTTP report-request path accepts or mints that token and returns it to the caller, so it can tell
     * retries from genuinely new work without deriving the distinction from the dates alone.
     *
     * <p><strong>The minted identity is reported on the returned outcome</strong>, so a caller that did
     * not mint it can still retry: the nonce makes the identity unrecomputable from the request, and
     * {@link SubmissionResult#submissionId()} is where it travels back.
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
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        return submitTransactionReportJob(newSubmissionIdentity(startDate, endDate),
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
     * Publishes a canonical image as a distinct new logical submission.
     *
     * @param cardImages the canonical card stream
     * @return the outcome carrying the newly minted retry identity
     */
    public SubmissionResult submitCanonicalJobImage(final List<String> cardImages) {
        return submitCanonicalJobImage(newSubmissionId(), cardImages);
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
        validateDeduplicationIdentifiers(submission, cards.size());
        final int terminalOrdinal = terminalOrdinal(cards);

        final SubmissionResult result;
        try {
            result = this.submissionCoordinator.serialize(() -> {
                final JobSubmissionOutbox.DeliveryOutcome delivery =
                        this.submissionOutbox.publish(
                                submission,
                                cards,
                                terminalOrdinal,
                                (pendingSubmission, card, cardOrdinal) -> publishCard(
                                        pendingSubmission,
                                        card,
                                        cardOrdinal,
                                        deduplicationId(pendingSubmission, cardOrdinal),
                                        streamEnvelope(pendingSubmission, cardOrdinal,
                                                cards.size())));
                return new SubmissionResult(submission, cards.size(), delivery.cardsPublished(),
                        delivery.failed(),
                        delivery.failed() ? JobSubmissionException.DEFAULT_MESSAGE : "");
            });
        } catch (final JobSubmissionCoordinator.CoordinationFailure coordinationFailure) {
            LOGGER.error("{} submission={} queue={} messageGroup={} failureChain={}",
                    JobSubmissionException.DEFAULT_MESSAGE, submission, this.queueName,
                    this.messageGroupId, FailureDiagnostics.failureChainOf(coordinationFailure));
            return new SubmissionResult(submission, cards.size(), 0, true,
                    JobSubmissionException.DEFAULT_MESSAGE);
        }

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
        return publishCard(submission, card, cardOrdinal, deduplicationId(submission, cardOrdinal),
                cardEnvelope(submission, cardOrdinal));
    }

    /**
     * The reassembly envelope of a card published as part of a stream whose total this service knows.
     *
     * @param submission the submission identity
     * @param cardOrdinal the one-based ordinal of this card
     * @param cardCount how many cards the submission holds
     * @return the message attributes to publish alongside the card
     */
    private static Map<String, Object> streamEnvelope(final String submission, final int cardOrdinal,
            final int cardCount) {
        return Map.of(SUBMISSION_ID_HEADER, submission,
                CARD_ORDINAL_HEADER, Integer.toString(cardOrdinal),
                CARD_COUNT_HEADER, Integer.toString(cardCount));
    }

    /**
     * The reassembly envelope of a single card published by a caller driving its own loop.
     *
     * <p>It declares no total, because the caller has not declared one and this service may not invent it.
     * See {@link #CARD_COUNT_HEADER}.
     *
     * @param submission the submission identity
     * @param cardOrdinal the one-based ordinal of this card
     * @return the message attributes to publish alongside the card
     */
    private static Map<String, Object> cardEnvelope(final String submission, final int cardOrdinal) {
        return Map.of(SUBMISSION_ID_HEADER, submission,
                CARD_ORDINAL_HEADER, Integer.toString(cardOrdinal));
    }

    private boolean publishCard(final String submission, final String card, final int cardOrdinal,
            final String deduplicationId, final Map<String, Object> envelope) {
        try {
            final SendResult<String> sendResult = Observation
                    .createNotStarted(PUBLISH_OBSERVATION_NAME, this.observationRegistry)
                    .lowCardinalityKeyValue(TAG_SYSTEM, SYSTEM_SQS)
                    .lowCardinalityKeyValue(TAG_OPERATION, OPERATION_SEND)
                    .highCardinalityKeyValue(TAG_QUEUE, this.queueName)
                    .highCardinalityKeyValue(TAG_SUBMISSION, submission)
                    .highCardinalityKeyValue(TAG_CARD_ORDINAL, Integer.toString(cardOrdinal))
                    .observe(() -> this.sqsOperations.send(options -> options
                            .queue(this.queueName)
                            .payload(card)
                            .messageGroupId(this.messageGroupId)
                            .messageDeduplicationId(deduplicationId)
                            .headers(envelope)));
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

    private static void validateDeduplicationIdentifiers(
            final String submissionId, final int cardCount) {
        for (int cardOrdinal = FIRST_CARD_ORDINAL; cardOrdinal <= cardCount; cardOrdinal++) {
            deduplicationId(submissionId, cardOrdinal);
        }
    }

    private static int terminalOrdinal(final List<String> cards) {
        final int lastPossibleOrdinal = Math.min(
                cards.size(), JclCardImageBuilder.OVERSIZED_REDEFINE_CARD_BOUND);
        for (int cardOrdinal = FIRST_CARD_ORDINAL;
                cardOrdinal <= lastPossibleOrdinal;
                cardOrdinal++) {
            if (isEndOfStreamCard(cards.get(cardOrdinal - FIRST_CARD_ORDINAL))) {
                return cardOrdinal;
            }
        }
        return lastPossibleOrdinal;
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

    /**
     * Derives the compatibility identity for the date-only convenience overload.
     *
     * <p>This form is intentionally stable for a repeated date range and therefore models a retry. A
     * deliberate new submission of the same period must use the caller-identity overload with a distinct
     * logical-request token. The HTTP report-request surface does exactly that; this compatibility helper
     * neither claims nor manufactures a nonce.
     *
     * @param startDate the submitted start-date slot
     * @param endDate the submitted end-date slot
     * @return a printable, whitespace-free identity stable for this date range
     */
    private static String newSubmissionId(final String startDate, final String endDate) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        final String identity = withoutWhitespace(startDate) + SUBMISSION_ID_PART_SEPARATOR
                + withoutWhitespace(endDate);
        requireSubmissionId(identity);
        // Composed here rather than at the first publish so that an identity too long to carry a card
        // ordinal is refused when it is minted, not seventeen cards later.
        deduplicationId(identity, JclCardImageBuilder.CARD_COUNT);
        return identity;
    }

    /**
     * Mints the identity of a new logical submission.
     *
     * @return a printable identity that can be supplied again only for a true retry
     */
    public static String newSubmissionId() {
        return requireSubmissionId(UUID.randomUUID().toString());
    }

    /**
     * Mints a new submission identity while retaining the reporting period as a readable prefix.
     *
     * @param startDate the fixed-width start-date slot
     * @param endDate the fixed-width end-date slot
     * @return a unique printable identity suitable for FIFO deduplication
     */
    public static String newSubmissionIdentity(final String startDate, final String endDate) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        final String identity = withoutWhitespace(startDate) + SUBMISSION_ID_PART_SEPARATOR
                + withoutWhitespace(endDate) + SUBMISSION_NONCE_SEPARATOR + newNonce();
        requireSubmissionId(identity);
        deduplicationId(identity, JclCardImageBuilder.CARD_COUNT);
        return identity;
    }

    /**
     * Produces the nonce that distinguishes one minted identity from another.
     *
     * <p>A random universally unique value rendered as its hexadecimal digits, with the grouping hyphens
     * removed: hyphens carry no information here and the identifier is shorter without them. Randomness is
     * the right source because the alternative - a counter - would have to survive a restart and be shared
     * between instances to be unique, which is the coordination service this migration deliberately does
     * not introduce.
     *
     * @return the nonce, hexadecimal and free of separators
     */
    private static String newNonce() {
        return UUID.randomUUID().toString().replace(NONCE_GROUP_SEPARATOR, "");
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
     * <p><strong>The identity is reported back because a retry is impossible without it.</strong> A
     * submission that stopped part way left a prefix of its cards on the queue, and completing it rather
     * than doubling it requires reissuing the <em>same</em> deduplication identifiers - which requires the
     * same identity. Since {@link #newSubmissionIdentity(String, String)} mints a nonce, the identity is
     * not recomputable from the request, so a result that omitted it would make the retry path
     * unreachable for every caller that did not mint the identity itself.
     *
     * @param submissionId the identity of the submission this outcome describes, which a caller keeps in
     *                     order to retry it
     * @param cardsRequested the number of cards the caller supplied
     * @param cardsPublished the number that reached the queue, which is lower on a partial submission
     * @param failed whether a publish failed, in which case the remaining cards were not sent
     * @param failureMessage the frozen operator-facing failure text when {@code failed}, otherwise empty
     */
    public record SubmissionResult(String submissionId, int cardsRequested, int cardsPublished,
            boolean failed, String failureMessage) {

        /**
         * Alternate ordering used by the outbox-facing integration seam.
         */
        public SubmissionResult(final int cardsRequested, final int cardsPublished,
                final boolean failed, final String failureMessage, final String submissionId) {
            this(submissionId, cardsRequested, cardsPublished, failed, failureMessage);
        }

        /**
         * Legacy-visible outcome constructor for callers that do not yet carry the retry identity.
         */
        public SubmissionResult(final int cardsRequested, final int cardsPublished,
                final boolean failed, final String failureMessage) {
            this(newSubmissionId(), cardsRequested, cardsPublished, failed, failureMessage);
        }

        public SubmissionResult {
            requireSubmissionId(submissionId);
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
