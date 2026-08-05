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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.service.JobSubmissionService.SubmissionResult;
import com.carddemo.util.JclCardImageBuilder;
import com.carddemo.util.SqsNamingRules;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.support.GenericMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JobSubmissionService}, the adapter that carries the estate's single
 * online-to-batch bridge.
 *
 * <h2>What is under test</h2>
 *
 * <p>The legacy bridge is one transient-data-queue write loop in the report-request transaction,
 * {@code app/cbl/CORPT00C.cbl} lines 515 to 535, publishing a seventeen-card eighty-column job
 * image one card at a time. The queue it writes to is defined with an eighty-byte fixed record
 * format, append disposition and errors ignored [app/csd/CARDDEMO.CSD:TDQUEUE(JOBS)]. All four
 * properties are asserted here: the card count, the byte width, the ordering, and the
 * ignore-on-error behaviour under which a failed write reports back rather than propagating.</p>
 *
 * <h2>Why the send count is asserted exactly</h2>
 *
 * <p>The legacy write is single-shot: it issues one queue write per card and inspects the response
 * code. This service adds no application-level retry, and the assertions below pin that by
 * verifying the collaborator is invoked exactly once per published card and never again after a
 * failure. A retry introduced at this layer would be invisible to a functional assertion but would
 * duplicate a card on the queue, so the count itself is the contract.</p>
 *
 * <h2>Why submission identity is caller-owned and asserted to be so</h2>
 *
 * <p>A first-in-first-out queue deduplicates on an explicit identifier, and the legacy has no
 * antecedent for one. Deriving it from the reporting period would make a second legitimate
 * submission for the same period silently vanish, so the caller supplies the identity and the
 * service derives one identifier per card from it. The assertions prove that two different
 * submissions of the same period produce disjoint identifier sets, and that repeating one
 * submission identity reproduces the identifiers exactly, which is what makes a retry idempotent
 * rather than duplicating.</p>
 *
 * <h2>Why the whole stream is validated before the first card is sent</h2>
 *
 * <p>A partially published job image is worse than none: the reader would consume an incomplete
 * job stream. The service therefore validates the card count, every card's width and character
 * set, the position of the terminating card and every derived identifier before publishing
 * anything, and the assertions below prove that a malformed stream sends nothing at all.</p>
 *
 * <p>Translated from the CardDemo COBOL estate at checkout commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("JobSubmissionService: the online-to-batch job-submission bridge")
class JobSubmissionServiceBoundaryTest {

    /** The canonical first-in-first-out queue name the module configures. */
    private static final String QUEUE = "JOBS.fifo";

    /** The canonical message group the cards are appended to, preserving their order. */
    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    /** A caller-supplied submission identity. */
    private static final String SUBMISSION = "SUB-0001";

    /** A second, distinct submission identity for the same reporting period. */
    private static final String OTHER_SUBMISSION = "SUB-0002";

    /** Start of the reporting period, at the legacy ten-column slot width. */
    private static final String START_DATE = "2022-01-01";

    /** End of the reporting period, at the legacy ten-column slot width. */
    private static final String END_DATE = "2022-07-06";

    /** Number of cards in the canonical job image. */
    private static final int CARD_COUNT = 17;

    /** Byte width of one job-control card. */
    private static final int CARD_WIDTH = 80;

    /** Longest message deduplication identifier the queue service accepts. */
    private static final int DEDUPLICATION_ID_MAX_LENGTH = 128;

    /** A card image at the correct width, holding only printable characters. */
    private static final String WELL_FORMED_CARD = "//STEP10 EXEC PROC=TRANREPT".concat(
            " ".repeat(CARD_WIDTH - "//STEP10 EXEC PROC=TRANREPT".length()));

    /** Records every option the service sets on each send, so the stream can be asserted. */
    private static final class RecordingOptions implements SqsSendOptions<String> {

        /** Queue named on each send, in order. */
        private final List<String> queues = new ArrayList<>();

        /** Payload supplied on each send, in order. */
        private final List<String> payloads = new ArrayList<>();

        /** Message group named on each send, in order. */
        private final List<String> messageGroupIds = new ArrayList<>();

        /** Deduplication identifier supplied on each send, in order. */
        private final List<String> deduplicationIds = new ArrayList<>();

        @Override
        public SqsSendOptions<String> queue(final String queue) {
            this.queues.add(queue);
            return this;
        }

        @Override
        public SqsSendOptions<String> payload(final String payload) {
            this.payloads.add(payload);
            return this;
        }

        @Override
        public SqsSendOptions<String> header(final String name, final Object value) {
            return this;
        }

        @Override
        public SqsSendOptions<String> headers(final Map<String, Object> headers) {
            return this;
        }

        @Override
        public SqsSendOptions<String> delaySeconds(final Integer delaySeconds) {
            return this;
        }

        @Override
        public SqsSendOptions<String> messageGroupId(final String messageGroupId) {
            this.messageGroupIds.add(messageGroupId);
            return this;
        }

        @Override
        public SqsSendOptions<String> messageDeduplicationId(final String deduplicationId) {
            this.deduplicationIds.add(deduplicationId);
            return this;
        }
    }

    /** The collaborator the service publishes through. */
    private SqsOperations sqsOperations;

    /** The recorder every send is replayed onto. */
    private RecordingOptions options;

    /** The service under test, bound to the canonical queue and message group. */
    private JobSubmissionService service;

    @BeforeEach
    void createService() {
        this.sqsOperations = mock(SqsOperations.class);
        this.options = new RecordingOptions();
        this.service = new JobSubmissionService(this.sqsOperations, QUEUE, MESSAGE_GROUP);
    }

    /**
     * Builds an acknowledgement carrying a fresh identifier, as the queue service would.
     *
     * @return a send acknowledgement
     */
    private static SendResult<String> acknowledgement() {
        return new SendResult<>(UUID.randomUUID(), QUEUE, new GenericMessage<>("card"), Map.of());
    }

    /** Stubs the collaborator to accept every card and replay it onto the recorder. */
    private void acceptEveryCard() {
        when(this.sqsOperations.<String>send(any())).thenAnswer(invocation -> {
            final Consumer<SqsSendOptions<String>> consumer = invocation.getArgument(0);
            consumer.accept(this.options);
            return acknowledgement();
        });
    }

    /**
     * Stubs the collaborator to accept every card except the one at the given ordinal.
     *
     * @param failingOrdinal the one-based ordinal of the card whose send fails
     * @param failure        the transport fault raised for that card
     */
    private void failAt(final int failingOrdinal, final RuntimeException failure) {
        when(this.sqsOperations.<String>send(any())).thenAnswer(invocation -> {
            final Consumer<SqsSendOptions<String>> consumer = invocation.getArgument(0);
            consumer.accept(this.options);
            if (this.options.payloads.size() == failingOrdinal) {
                throw failure;
            }
            return acknowledgement();
        });
    }

    /**
     * Builds a canonical job image with one card replaced.
     *
     * @param ordinal     the one-based ordinal to replace
     * @param replacement the card to put there
     * @return the altered stream
     */
    private static List<String> canonicalImageWith(final int ordinal, final String replacement) {
        final List<String> cards = new ArrayList<>(JclCardImageBuilder.build(START_DATE, END_DATE));
        cards.set(ordinal - 1, replacement);
        return List.copyOf(cards);
    }

    /**
     * Builds the deduplication identifiers a submission of the canonical image must derive.
     *
     * @param submissionId the caller-supplied identity
     * @return one identifier per card, in order
     */
    private static List<String> expectedDeduplicationIds(final String submissionId) {
        final List<String> identifiers = new ArrayList<>(CARD_COUNT);
        for (int ordinal = 1; ordinal <= CARD_COUNT; ordinal++) {
            identifiers.add(submissionId + "-" + ordinal);
        }
        return List.copyOf(identifiers);
    }

    @Nested
    @DisplayName("construction from configuration")
    class Construction {

        @Test
        @DisplayName("a null collaborator is refused")
        void aNullCollaboratorIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new JobSubmissionService(null, QUEUE, MESSAGE_GROUP))
                    .withMessageContaining("sqsOperations");
        }

        @ParameterizedTest(name = "the unconfigured queue name [{0}] is refused")
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank queue name is refused, because the property has no default")
        void aBlankQueueNameIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, candidate,
                            MESSAGE_GROUP))
                    .withMessageContaining("carddemo.aws.sqs.job-queue")
                    .withMessageContaining("has no default");
        }

        @Test
        @DisplayName("an absent queue name is refused")
        void anAbsentQueueNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, null, MESSAGE_GROUP));
        }

        @ParameterizedTest(name = "the non-ordered queue name [{0}] is refused")
        @ValueSource(strings = {"jobs", "JOBS.FIFO", "JOBS.fifo-queue",
            "JOBS"})
        @DisplayName("a queue that is not first-in-first-out is refused, because order is contractual")
        void aNonOrderedQueueIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, candidate,
                            MESSAGE_GROUP))
                    .withMessageContaining(".fifo");
        }

        @ParameterizedTest(name = "the malformed queue name [{0}] is refused")
        @ValueSource(strings = {"JOBS.fifo ", " JOBS.fifo",
            "JOB S.fifo", "JOBS\u00e9.fifo", "JOB/S.fifo",
            "JOB:S.fifo", ".fifo"})
        @DisplayName("a queue name the queue service could not carry is refused before the suffix rule")
        void aMalformedQueueNameIsRefused(String candidate) {
            // These are the values the previous contract admitted: it looked only for printable text
            // and a '.fifo' ending, so a name with a space, an accent or a path separator passed
            // start-up and then failed every publish - which this class deliberately converts into a
            // tolerated partial result, hiding the misconfiguration. The character rule now fires
            // strictly before the suffix rule, which is why a trailing space is reported as the
            // character it is rather than as a missing suffix.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, candidate,
                            MESSAGE_GROUP))
                    .withMessageContaining("carddemo.aws.sqs.job-queue");
        }

        @Test
        @DisplayName("a queue name longer than the queue service accepts is refused")
        void anOverlongQueueNameIsRefused() {
            final String overlong = "a".repeat(SqsNamingRules.QUEUE_NAME_MAX_LENGTH) + ".fifo";
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, overlong,
                            MESSAGE_GROUP))
                    .withMessageContaining("carddemo.aws.sqs.job-queue")
                    .withMessageContaining(String.valueOf(SqsNamingRules.QUEUE_NAME_MAX_LENGTH));
        }

        @ParameterizedTest(name = "the configured destination [{0}] is accepted")
        @ValueSource(strings = {"JOBS.fifo",
            "https://sqs.us-east-1.amazonaws.com/000000000000/JOBS.fifo",
            "http://localhost:4566/000000000000/JOBS.fifo",
            "http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/JOBS.fifo",
            "arn:aws:sqs:us-east-1:000000000000:JOBS.fifo"})
        @DisplayName("all three forms a deployment may configure the queue as are accepted")
        void everyConfigurableDestinationFormIsAccepted(String candidate) {
            // A tightened contract that refused a form the producer has always accepted would be a
            // regression dressed as a fix, so each form is asserted to survive construction.
            assertThatCode(() -> new JobSubmissionService(sqsOperations, candidate, MESSAGE_GROUP))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a message group id longer than the queue service accepts is refused")
        void anOverlongMessageGroupIsRefused() {
            final String overlong = "g".repeat(SqsNamingRules.MESSAGE_GROUP_ID_MAX_LENGTH + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, QUEUE, overlong))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id")
                    .withMessageContaining(
                            String.valueOf(SqsNamingRules.MESSAGE_GROUP_ID_MAX_LENGTH));
        }

        @ParameterizedTest(name = "the malformed message group [{0}] is refused")
        @ValueSource(strings = {"carddemo job submission", "carddemo-job-submission\n",
            "carddemo/job/submission", "carddemo-job-submission\u00e9"})
        @DisplayName("a message group id the queue service could not carry is refused")
        void aMalformedMessageGroupIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, QUEUE, candidate))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id");
        }

        @ParameterizedTest(name = "the unconfigured message group [{0}] is refused")
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank message group is refused, because the property has no default")
        void aBlankMessageGroupIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, QUEUE, candidate))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id");
        }

        @Test
        @DisplayName("an absent message group is refused")
        void anAbsentMessageGroupIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, QUEUE, null));
        }

        @Test
        @DisplayName("the canonical queue name and message group are accepted")
        void theCanonicalConfigurationIsAccepted() {
            acceptEveryCard();

            assertThat(service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE)
                    .complete()).isTrue();
            assertThat(options.queues).containsOnly(QUEUE);
            assertThat(options.messageGroupIds).containsOnly(MESSAGE_GROUP);
        }
    }

    @Nested
    @DisplayName("the seventeen-card submission")
    class SeventeenCardSubmission {

        @Test
        @DisplayName("every card of the canonical image is published, in order and unaltered")
        void everyCardIsPublishedInOrder() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(options.payloads)
                    .containsExactlyElementsOf(JclCardImageBuilder.build(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("the collaborator is invoked exactly once per card, so no card is ever retried")
        void theCollaboratorIsInvokedExactlyOncePerCard() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            verify(sqsOperations, times(CARD_COUNT)).<String>send(any());
        }

        @Test
        @DisplayName("the result reports all seventeen cards requested and all seventeen published")
        void theResultReportsAllSeventeenPublished() {
            acceptEveryCard();

            SubmissionResult result =
                    service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(result.cardsRequested()).isEqualTo(CARD_COUNT);
            assertThat(result.cardsPublished()).isEqualTo(CARD_COUNT);
            assertThat(result.failed()).isFalse();
            assertThat(result.failureMessage()).isEmpty();
            assertThat(result.complete()).isTrue();
            assertThat(result.partial()).isFalse();
        }

        @Test
        @DisplayName("every published card measures the legacy eighty bytes")
        void everyPublishedCardMeasuresTheLegacyWidth() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(options.payloads).allSatisfy(card ->
                    assertThat(card).hasSize(CARD_WIDTH));
        }

        @Test
        @DisplayName("the terminating sentinel card is transmitted rather than merely held")
        void theTerminatingSentinelIsTransmitted() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(options.payloads).hasSize(CARD_COUNT);
            assertThat(options.payloads.get(CARD_COUNT - 1).stripTrailing())
                    .isEqualTo(JclCardImageBuilder.EOF_SENTINEL_CARD);
        }

        @Test
        @DisplayName("both reporting-period dates reach the substituted slots")
        void bothReportingPeriodDatesReachTheSubstitutedSlots() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(options.payloads).anySatisfy(card ->
                    assertThat(card).contains(JclCardImageBuilder.SLOT_PARM_START_DATE, START_DATE));
            assertThat(options.payloads).anySatisfy(card ->
                    assertThat(card).contains(JclCardImageBuilder.SLOT_PARM_END_DATE, END_DATE));
        }

        @Test
        @DisplayName("every card is appended to one message group, so the reader sees them in order")
        void everyCardIsAppendedToOneMessageGroup() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(options.messageGroupIds).hasSize(CARD_COUNT).containsOnly(MESSAGE_GROUP);
        }
    }

    @Nested
    @DisplayName("submission identity and deduplication")
    class SubmissionIdentity {

        @Test
        @DisplayName("one identifier is derived per card, from the caller's identity and the ordinal")
        void oneIdentifierIsDerivedPerCard() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(options.deduplicationIds)
                    .containsExactlyElementsOf(expectedDeduplicationIds(SUBMISSION));
        }

        @Test
        @DisplayName("no two cards of one submission share an identifier")
        void noTwoCardsShareAnIdentifier() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(options.deduplicationIds).doesNotHaveDuplicates().hasSize(CARD_COUNT);
        }

        @Test
        @DisplayName("two submissions of the same period produce disjoint identifier sets")
        void twoSubmissionsOfTheSamePeriodProduceDisjointIdentifiers() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);
            List<String> first = List.copyOf(options.deduplicationIds);
            options.deduplicationIds.clear();
            service.submitTransactionReportJob(OTHER_SUBMISSION, START_DATE, END_DATE);

            assertThat(options.deduplicationIds).doesNotContainAnyElementsOf(first);
        }

        @Test
        @DisplayName("repeating one identity reproduces the identifiers, which is what makes a retry idempotent")
        void repeatingOneIdentityReproducesTheIdentifiers() {
            acceptEveryCard();

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);
            List<String> first = List.copyOf(options.deduplicationIds);
            options.deduplicationIds.clear();
            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(options.deduplicationIds).containsExactlyElementsOf(first);
        }

        @Test
        @DisplayName("a null identity is refused")
        void aNullIdentityIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitTransactionReportJob(null, START_DATE, END_DATE))
                    .withMessageContaining("submissionId");
        }

        @Test
        @DisplayName("a blank identity is refused")
        void aBlankIdentityIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitTransactionReportJob("", START_DATE, END_DATE))
                    .withMessageContaining("submissionId must not be blank");
        }

        @ParameterizedTest(name = "the identity [{0}] holds whitespace and is refused")
        @ValueSource(strings = {"SUB 0001", "SUB\t0001", "SUB\n0001", " SUB0001", "SUB0001 "})
        @DisplayName("an identity holding whitespace is refused, because an identifier may not")
        void anIdentityHoldingWhitespaceIsRefused(String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitTransactionReportJob(candidate, START_DATE,
                            END_DATE))
                    .withMessageContaining("must not hold whitespace");
        }

        @Test
        @DisplayName("an identity long enough to keep every ordinal inside the bound is accepted")
        void anIdentityInsideTheBoundIsAccepted() {
            acceptEveryCard();
            String longest = "X".repeat(DEDUPLICATION_ID_MAX_LENGTH - 1 - 2);

            service.submitTransactionReportJob(longest, START_DATE, END_DATE);

            assertThat(options.deduplicationIds).allSatisfy(identifier ->
                    assertThat(identifier.length()).isLessThanOrEqualTo(DEDUPLICATION_ID_MAX_LENGTH));
        }

        @Test
        @DisplayName("an identity that overflows the bound at the last ordinal is refused outright")
        void anIdentityThatOverflowsAtTheLastOrdinalIsRefused() {
            String tooLong = "X".repeat(DEDUPLICATION_ID_MAX_LENGTH - 2);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitTransactionReportJob(tooLong, START_DATE,
                            END_DATE))
                    .withMessageContaining(String.valueOf(DEDUPLICATION_ID_MAX_LENGTH));
        }

        @Test
        @DisplayName("an overflowing identity sends nothing at all, because identifiers precede sending")
        void anOverflowingIdentitySendsNothing() {
            String tooLong = "X".repeat(DEDUPLICATION_ID_MAX_LENGTH - 2);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitTransactionReportJob(tooLong, START_DATE,
                            END_DATE));

            verify(sqsOperations, never()).<String>send(any());
        }
    }

    @Nested
    @DisplayName("the ignore-on-error failure path")
    class FailurePath {

        @Test
        @DisplayName("a failure on the first card publishes nothing and reports the legacy message")
        void aFailureOnTheFirstCardPublishesNothing() {
            failAt(1, new IllegalStateException("queue unavailable"));

            SubmissionResult result =
                    service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(result.cardsPublished()).isZero();
            assertThat(result.cardsRequested()).isEqualTo(CARD_COUNT);
            assertThat(result.failed()).isTrue();
            assertThat(result.failureMessage()).isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(result.complete()).isFalse();
            assertThat(result.partial()).isFalse();
        }

        @Test
        @DisplayName("a failure on the first card is attempted exactly once, never retried")
        void aFailureOnTheFirstCardIsAttemptedExactlyOnce() {
            failAt(1, new IllegalStateException("queue unavailable"));

            service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            verify(sqsOperations, times(1)).<String>send(any());
        }

        @Test
        @DisplayName("a failure mid-stream stops the remaining cards rather than skipping past it")
        void aFailureMidStreamStopsTheRemainingCards() {
            failAt(9, new IllegalStateException("queue unavailable"));

            SubmissionResult result =
                    service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(result.cardsPublished()).isEqualTo(8);
            assertThat(result.failed()).isTrue();
            assertThat(result.partial()).isTrue();
            verify(sqsOperations, times(9)).<String>send(any());
        }

        @Test
        @DisplayName("a failure on the terminating card leaves the sixteen before it published")
        void aFailureOnTheTerminatingCardLeavesTheRestPublished() {
            failAt(CARD_COUNT, new IllegalStateException("queue unavailable"));

            SubmissionResult result =
                    service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE);

            assertThat(result.cardsPublished()).isEqualTo(CARD_COUNT - 1);
            assertThat(result.failed()).isTrue();
            verify(sqsOperations, times(CARD_COUNT)).<String>send(any());
        }

        @Test
        @DisplayName("a publish failure never propagates, matching the legacy ignore-on-error queue")
        void aPublishFailureNeverPropagates() {
            failAt(1, new IllegalStateException("queue unavailable"));

            assertThat(service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE)
                    .failed()).isTrue();
        }

        @Test
        @DisplayName("a failure carrying no description is still reported, not swallowed silently")
        void aFailureCarryingNoDescriptionIsStillReported() {
            failAt(1, new IllegalStateException());

            assertThat(service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE)
                    .failed()).isTrue();
        }

        @Test
        @DisplayName("a failure carrying a blank description is still reported")
        void aFailureCarryingABlankDescriptionIsStillReported() {
            failAt(1, new IllegalStateException("   "));

            assertThat(service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE)
                    .failed()).isTrue();
        }

        @Test
        @DisplayName("a failure carrying a multi-line description is still reported")
        void aFailureCarryingAMultiLineDescriptionIsStillReported() {
            failAt(1, new IllegalStateException("first line\nsecond line\nthird line"));

            assertThat(service.submitTransactionReportJob(SUBMISSION, START_DATE, END_DATE)
                    .failed()).isTrue();
        }
    }

    @Nested
    @DisplayName("stream prevalidation")
    class StreamPrevalidation {

        // Two entry points and two contracts. The generic publisher takes any well-formed fixed-width
        // stream, because a caller with a legitimate stream of another length is not doing anything
        // wrong; the canonical-image entry point additionally requires the stream to be the image the
        // legacy emitter produced - exactly the canonical card count, the last card the end-of-stream
        // card and no earlier card an end-of-stream card. The canonical assertions below therefore go
        // through the canonical entry point, which is the one the report-submission path uses, and the
        // well-formedness assertions keep going through the generic one.

        @Test
        @DisplayName("the canonical image is accepted through the generic entry point")
        void theCanonicalImageIsAccepted() {
            acceptEveryCard();

            assertThat(service.submitJobStream(SUBMISSION,
                    JclCardImageBuilder.build(START_DATE, END_DATE)).complete()).isTrue();
        }

        @Test
        @DisplayName("the canonical image is accepted through the canonical entry point too")
        void theCanonicalImageIsAcceptedThroughTheCanonicalEntryPoint() {
            acceptEveryCard();

            assertThat(service.submitCanonicalJobImage(SUBMISSION,
                    JclCardImageBuilder.build(START_DATE, END_DATE)).complete()).isTrue();
        }

        @Test
        @DisplayName("a null stream is refused")
        void aNullStreamIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION, null))
                    .withMessageContaining("cardImages");
        }

        @Test
        @DisplayName("an empty stream is refused by both entry points: the canonical one names the canonical card count, the generic one names the minimum")
        void anEmptyStreamIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitCanonicalJobImage(SUBMISSION, List.of()))
                    .withMessageContaining("exactly " + CARD_COUNT + " cards");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION, List.of()))
                    .withMessageContaining("at least one");
        }

        @Test
        @DisplayName("a stream one card short is refused")
        void aStreamOneCardShortIsRefused() {
            List<String> short16 =
                    JclCardImageBuilder.build(START_DATE, END_DATE).subList(0, CARD_COUNT - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitCanonicalJobImage(SUBMISSION, short16))
                    .withMessageContaining("but held 16");
        }

        @Test
        @DisplayName("a stream one card long is refused")
        void aStreamOneCardLongIsRefused() {
            List<String> long18 = new ArrayList<>(JclCardImageBuilder.build(START_DATE, END_DATE));
            long18.add(WELL_FORMED_CARD);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitCanonicalJobImage(SUBMISSION, long18))
                    .withMessageContaining("but held 18");
        }

        @Test
        @DisplayName("a malformed stream sends nothing at all, through either entry point")
        void aMalformedStreamSendsNothing() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION, List.of()));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitCanonicalJobImage(SUBMISSION, List.of()));

            verify(sqsOperations, never()).<String>send(any());
        }

        @Test
        @DisplayName("a null card is refused")
        void aNullCardIsRefused() {
            List<String> cards = new ArrayList<>(JclCardImageBuilder.build(START_DATE, END_DATE));
            cards.set(0, null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION, cards));
        }

        @ParameterizedTest(name = "a card of width {0} is refused")
        @ValueSource(ints = {0, 1, 79, 81, 160})
        @DisplayName("a card that is not eighty bytes wide is refused, naming its measured width")
        void aCardOfTheWrongWidthIsRefused(int width) {
            List<String> cards = canonicalImageWith(1, "X".repeat(width));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION, cards))
                    .withMessageContaining("must be exactly " + CARD_WIDTH + " encoded bytes but was "
                            + width);
        }

        @ParameterizedTest(name = "a card carrying the control byte {0} is refused")
        @ValueSource(chars = {'\r', '\n', '\t', '\u0000', '\u001b', '\u007f', '\u0007'})
        @DisplayName("a card carrying a control byte is refused, because it would break record framing")
        void aCardCarryingAControlByteIsRefused(char control) {
            List<String> cards = canonicalImageWith(1,
                    "//STEP10" + control + " ".repeat(CARD_WIDTH - 9));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION, cards))
                    .withMessageContaining("non-printable character at position 9");
        }

        @Test
        @DisplayName("a card carrying a character no single byte can hold is refused, not transcoded")
        void aCardCarryingAnUnmappableCharacterIsRefused() {
            List<String> cards = canonicalImageWith(1,
                    "//STEP10\u00e9" + " ".repeat(CARD_WIDTH - 9));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION, cards))
                    .withMessageContaining("not representable as a single US-ASCII byte");
        }

        @Test
        @DisplayName("a stream whose final card does not terminate it is refused")
        void aStreamWhoseFinalCardDoesNotTerminateItIsRefused() {
            List<String> cards = canonicalImageWith(CARD_COUNT, WELL_FORMED_CARD);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitCanonicalJobImage(SUBMISSION, cards))
                    .withMessageContaining("must be the end-of-stream card");
        }

        @Test
        @DisplayName("a stream terminated early is refused, because the cards after it would be lost")
        void aStreamTerminatedEarlyIsRefused() {
            List<String> cards = canonicalImageWith(5, " ".repeat(CARD_WIDTH));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitCanonicalJobImage(SUBMISSION, cards))
                    .withMessageContaining("card 5")
                    .withMessageContaining("only the final card may");
        }

        @Test
        @DisplayName("an all-space final card terminates the stream as the sentinel does")
        void anAllSpaceFinalCardTerminatesTheStream() {
            acceptEveryCard();
            List<String> cards = canonicalImageWith(CARD_COUNT, " ".repeat(CARD_WIDTH));

            assertThat(service.submitJobStream(SUBMISSION, cards).complete()).isTrue();
            assertThat(options.payloads).hasSize(CARD_COUNT);
        }
    }

    @Nested
    @DisplayName("the single-card entry point")
    class SingleCardEntryPoint {

        @Test
        @DisplayName("a published card reports success and carries its derived identifier")
        void aPublishedCardReportsSuccess() {
            acceptEveryCard();

            assertThat(service.writeJobSubmissionQueue(SUBMISSION, WELL_FORMED_CARD, 4)).isTrue();
            assertThat(options.payloads).containsExactly(WELL_FORMED_CARD);
            assertThat(options.deduplicationIds).containsExactly(SUBMISSION + "-4");
            assertThat(options.queues).containsExactly(QUEUE);
            assertThat(options.messageGroupIds).containsExactly(MESSAGE_GROUP);
        }

        @Test
        @DisplayName("a refused card reports failure without propagating")
        void aRefusedCardReportsFailureWithoutPropagating() {
            failAt(1, new IllegalStateException("queue unavailable"));

            assertThat(service.writeJobSubmissionQueue(SUBMISSION, WELL_FORMED_CARD, 1)).isFalse();
            verify(sqsOperations, times(1)).<String>send(any());
        }

        @Test
        @DisplayName("a card of the wrong width is refused before anything is sent")
        void aCardOfTheWrongWidthIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(SUBMISSION, "SHORT", 1))
                    .withMessageContaining("must be exactly " + CARD_WIDTH);

            verify(sqsOperations, never()).<String>send(any());
        }

        @Test
        @DisplayName("a null card is refused")
        void aNullCardIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(SUBMISSION, null, 1))
                    .withMessageContaining("cardImage");
        }

        @Test
        @DisplayName("a null identity is refused")
        void aNullIdentityIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(null, WELL_FORMED_CARD, 1))
                    .withMessageContaining("submissionId");
        }

        @ParameterizedTest(name = "the ordinal {0} is refused because ordinals are one-based")
        @ValueSource(ints = {0, -1, -17})
        @DisplayName("an ordinal below one is refused")
        void anOrdinalBelowOneIsRefused(int ordinal) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(SUBMISSION, WELL_FORMED_CARD,
                            ordinal))
                    .withMessageContaining("cardOrdinal is one-based");
        }

        @Test
        @DisplayName("an identity that overflows the identifier bound is refused")
        void anIdentityThatOverflowsTheBoundIsRefused() {
            String tooLong = "X".repeat(DEDUPLICATION_ID_MAX_LENGTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(tooLong, WELL_FORMED_CARD, 1))
                    .withMessageContaining(String.valueOf(DEDUPLICATION_ID_MAX_LENGTH));
        }
    }

    @Nested
    @DisplayName("the submission outcome record")
    class SubmissionOutcomeRecord {

        @Test
        @DisplayName("a complete submission is complete and not partial")
        void aCompleteSubmissionIsCompleteAndNotPartial() {
            SubmissionResult result = new SubmissionResult(SUBMISSION, CARD_COUNT, CARD_COUNT, false, "");

            assertThat(result.complete()).isTrue();
            assertThat(result.partial()).isFalse();
            assertThat(result.failureMessage()).isEmpty();
        }

        @Test
        @DisplayName("a failure after some cards is partial and not complete")
        void aFailureAfterSomeCardsIsPartial() {
            SubmissionResult result = new SubmissionResult(SUBMISSION, CARD_COUNT, 8, true, "");

            assertThat(result.complete()).isFalse();
            assertThat(result.partial()).isTrue();
        }

        @Test
        @DisplayName("a failure before any card is neither complete nor partial")
        void aFailureBeforeAnyCardIsNeitherCompleteNorPartial() {
            SubmissionResult result = new SubmissionResult(SUBMISSION, CARD_COUNT, 0, true, "");

            assertThat(result.complete()).isFalse();
            assertThat(result.partial()).isFalse();
        }

        @Test
        @DisplayName("a failure with no message supplied takes the legacy message")
        void aFailureWithNoMessageTakesTheLegacyMessage() {
            assertThat(new SubmissionResult(SUBMISSION, CARD_COUNT, 0, true, "").failureMessage())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(new SubmissionResult(SUBMISSION, CARD_COUNT, 0, true, null).failureMessage())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a failure with its own message keeps it")
        void aFailureWithItsOwnMessageKeepsIt() {
            assertThat(new SubmissionResult(SUBMISSION, CARD_COUNT, 0, true, "bespoke").failureMessage())
                    .isEqualTo("bespoke");
        }

        @Test
        @DisplayName("a success discards any supplied message, because there is nothing to report")
        void aSuccessDiscardsAnySuppliedMessage() {
            assertThat(new SubmissionResult(SUBMISSION, CARD_COUNT, CARD_COUNT, false, "ignored")
                    .failureMessage()).isEmpty();
            assertThat(new SubmissionResult(SUBMISSION, CARD_COUNT, CARD_COUNT, false, null)
                    .failureMessage()).isEmpty();
        }

        @Test
        @DisplayName("a negative requested count is refused")
        void aNegativeRequestedCountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubmissionResult(SUBMISSION, -1, 0, false, ""))
                    .withMessageContaining("cardsRequested must not be negative");
        }

        @Test
        @DisplayName("a negative published count is refused")
        void aNegativePublishedCountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubmissionResult(SUBMISSION, CARD_COUNT, -1, false, ""))
                    .withMessageContaining("cardsPublished must not be negative");
        }

        @Test
        @DisplayName("more cards published than requested is refused")
        void moreCardsPublishedThanRequestedIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubmissionResult(SUBMISSION, CARD_COUNT, CARD_COUNT + 1, false, ""))
                    .withMessageContaining("must not")
                    .withMessageContaining("exceed cardsRequested");
        }

        @Test
        @DisplayName("a zero-card submission is admitted, because the record is a plain carrier")
        void aZeroCardSubmissionIsAdmitted() {
            assertThat(new SubmissionResult(SUBMISSION, 0, 0, false, "").complete()).isTrue();
        }
    }
}
