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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.service.JobSubmissionService.SubmissionResult;
import com.carddemo.util.JclCardImageBuilder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.GenericMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JobSubmissionService}, the adapter that carries the
 * CardDemo online-to-batch bridge onto a first-in-first-out queue.
 *
 * <h2>What is under test</h2>
 *
 * <p>The reporting transaction {@code app/cbl/CORPT00C.cbl} contains the single
 * online-to-batch bridge in the whole estate: exactly one transient-data-queue
 * write, in the paragraph the source misspells as {@code WIRTE-JOBSUB-TDQ} at
 * lines 515 to 535. It emits a seventeen-card job stream one eighty-byte card at
 * a time. The queue it writes to is defined in
 * {@code app/csd/CARDDEMO.CSD} with three attributes that are contractual rather
 * than incidental, and this service must preserve all three:</p>
 *
 * <ul>
 *   <li><strong>Eighty-byte fixed records.</strong> One card becomes one
 *       message, and the body is exactly eighty encoded bytes. A card of any
 *       other width is refused rather than padded, because a downstream reader
 *       slices by offset.</li>
 *   <li><strong>Append disposition.</strong> Cards must arrive in the order they
 *       were emitted, which is why every card of one submission is published
 *       into a single message group.</li>
 *   <li><strong>Errors ignored.</strong> A failed write is logged and the
 *       transaction completes; it is never rethrown. Rethrowing would abort a
 *       request the legacy transaction finishes normally.</li>
 * </ul>
 *
 * <h2>The ordering detail that makes the stream seventeen messages</h2>
 *
 * <p>The legacy paragraph sets its end-of-stream flag <em>before</em> it writes
 * the card, not after. The consequence is that the terminating sentinel card is
 * itself transmitted and the submission is seventeen messages rather than
 * sixteen. Reversing the two statements would silently drop the batch tier's
 * end-of-stream marker, so a test pins the published count and the final
 * payload.</p>
 *
 * <p>No legacy source text is reproduced here.</p>
 */
@DisplayName("JobSubmissionService - the CORPT00C transient-data-queue bridge onto a FIFO queue")
class JobSubmissionServiceBaselineTest {

    /** The canonical first-in-first-out queue name the configuration fixes. */
    private static final String QUEUE_NAME = "JOBS.fifo";

    /** The canonical message-group identifier that preserves append order. */
    private static final String MESSAGE_GROUP_ID = "carddemo-job-submission";

    /** A representative report start date at the ten-character slot width. */
    private static final String START_DATE = "2024-01-01";

    /** A representative report end date at the ten-character slot width. */
    private static final String END_DATE = "2024-01-31";

    /** A submission identifier free of whitespace, as a deduplication id requires. */
    private static final String SUBMISSION_ID = "SUBMISSION-1";

    private SqsOperations sqsOperations;

    private JobSubmissionService service;

    private List<RecordedPublish> published;

    @BeforeEach
    void setUp() {
        this.sqsOperations = mock(SqsOperations.class);
        this.service = new JobSubmissionService(this.sqsOperations, QUEUE_NAME, MESSAGE_GROUP_ID);
        this.published = new ArrayList<>();
    }

    /**
     * Records what the service asked the queue to send. Capturing the fluent
     * options is the only way to assert the payload, the message group, and the
     * deduplication identifier, all of which are part of the preserved contract.
     */
    private static final class RecordedPublish implements SqsSendOptions<Object> {

        private String queue;
        private Object payload;
        private String messageGroupId;
        private String messageDeduplicationId;

        @Override
        public SqsSendOptions<Object> queue(final String value) {
            this.queue = value;
            return this;
        }

        @Override
        public SqsSendOptions<Object> payload(final Object value) {
            this.payload = value;
            return this;
        }

        @Override
        public SqsSendOptions<Object> header(final String name, final Object value) {
            return this;
        }

        @Override
        public SqsSendOptions<Object> headers(final Map<String, Object> values) {
            return this;
        }

        @Override
        public SqsSendOptions<Object> delaySeconds(final Integer value) {
            return this;
        }

        @Override
        public SqsSendOptions<Object> messageGroupId(final String value) {
            this.messageGroupId = value;
            return this;
        }

        @Override
        public SqsSendOptions<Object> messageDeduplicationId(final String value) {
            this.messageDeduplicationId = value;
            return this;
        }

        private String payloadAsText() {
            return String.valueOf(this.payload);
        }
    }

    /** A plausible successful send outcome; only the message identifier is read. */
    private static SendResult<String> successfulSend() {
        return new SendResult<>(UUID.randomUUID(), QUEUE_NAME,
                new GenericMessage<>("published"), Map.of());
    }

    /** Stubs the queue to accept every card and record what it was asked to send. */
    private void acceptEveryCard() {
        when(this.sqsOperations.send(JobSubmissionServiceBaselineTest.<Object>anyConfigurer()))
                .thenAnswer(invocation -> {
                    final Consumer<SqsSendOptions<Object>> configurer = invocation.getArgument(0);
                    final RecordedPublish recorded = new RecordedPublish();
                    configurer.accept(recorded);
                    this.published.add(recorded);
                    return successfulSend();
                });
    }

    /**
     * Stubs the queue to accept cards until the given one-based ordinal, then to
     * fail every subsequent card the way an unavailable queue would.
     */
    private void failFromCard(final int failingOrdinal) {
        when(this.sqsOperations.send(JobSubmissionServiceBaselineTest.<Object>anyConfigurer()))
                .thenAnswer(invocation -> {
                    final Consumer<SqsSendOptions<Object>> configurer = invocation.getArgument(0);
                    final RecordedPublish recorded = new RecordedPublish();
                    configurer.accept(recorded);
                    if (this.published.size() + 1 >= failingOrdinal) {
                        throw new IllegalStateException("queue unavailable");
                    }
                    this.published.add(recorded);
                    return successfulSend();
                });
    }

    /**
     * Stubs the queue to reject every card with the supplied failure, which is how a
     * queue that is reachable but refusing behaves.
     */
    private void rejectEveryCardWith(final RuntimeException publishFailure) {
        when(this.sqsOperations.send(JobSubmissionServiceBaselineTest.<Object>anyConfigurer()))
                .thenThrow(publishFailure);
    }

    /** A typed matcher for the fluent send configurer, keeping generics explicit. */
    private static <T> Consumer<SqsSendOptions<T>> anyConfigurer() {
        return any();
    }

    /** Builds a card image of exactly the contractual width from a prefix. */
    private static String cardOfWidth(final String prefix) {
        final StringBuilder card = new StringBuilder(prefix);
        while (card.length() < JclCardImageBuilder.CARD_IMAGE_WIDTH) {
            card.append(' ');
        }
        return card.toString();
    }

    @Nested
    @DisplayName("Construction - the queue and message group are configuration, never defaults")
    class Construction {

        @Test
        @DisplayName("refuses a null queue client")
        void refusesANullQueueClient() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            new JobSubmissionService(null, QUEUE_NAME, MESSAGE_GROUP_ID))
                    .withMessageContaining("sqsOperations");
        }

        @ParameterizedTest(name = "refuses a queue name of [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("refuses a blank queue name, which has no default")
        void refusesABlankQueueName(final String queueName) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, queueName,
                            MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-queue")
                    .withMessageContaining("has no default");
        }

        @Test
        @DisplayName("refuses a null queue name")
        void refusesANullQueueName() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new JobSubmissionService(sqsOperations, null, MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-queue");
        }

        @Test
        @DisplayName("refuses a queue that is not first-in-first-out, because order is contractual")
        void refusesANonFifoQueue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new JobSubmissionService(sqsOperations, "JOBS",
                                    MESSAGE_GROUP_ID))
                    .withMessageContaining("first-in-first-out")
                    .withMessageContaining(".fifo");
        }

        @ParameterizedTest(name = "refuses a message group of [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("refuses a blank message-group identifier")
        void refusesABlankMessageGroup(final String messageGroupId) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            new JobSubmissionService(sqsOperations, QUEUE_NAME, messageGroupId))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id");
        }

        @Test
        @DisplayName("accepts the canonical queue name and message group")
        void acceptsTheCanonicalConfiguration() {
            assertThatCode(() ->
                    new JobSubmissionService(sqsOperations, QUEUE_NAME, MESSAGE_GROUP_ID))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("submitJobStream - one card, one message, in order")
    class SubmitJobStream {

        @Test
        @DisplayName("publishes every card once, into one message group, on the configured queue")
        void publishesEveryCardOnce() {
            acceptEveryCard();
            final List<String> cards =
                    List.of(cardOfWidth("//FIRST"), cardOfWidth("//SECOND"), cardOfWidth("/*EOF"));

            final SubmissionResult result = service.submitJobStream(SUBMISSION_ID, cards);

            assertThat(result.cardsRequested()).isEqualTo(3);
            assertThat(result.cardsPublished()).isEqualTo(3);
            assertThat(result.failed()).isFalse();
            assertThat(result.complete()).isTrue();
            assertThat(published).hasSize(3);
            assertThat(published).allSatisfy(recorded -> {
                assertThat(recorded.queue).isEqualTo(QUEUE_NAME);
                assertThat(recorded.messageGroupId).isEqualTo(MESSAGE_GROUP_ID);
            });
        }

        @Test
        @DisplayName("preserves the supplied card order in the published payloads")
        void preservesCardOrder() {
            acceptEveryCard();
            final List<String> cards =
                    List.of(cardOfWidth("//ONE"), cardOfWidth("//TWO"), cardOfWidth("//THREE"));

            service.submitJobStream(SUBMISSION_ID, cards);

            assertThat(published).extracting(RecordedPublish::payloadAsText)
                    .containsExactlyElementsOf(cards);
        }

        @Test
        @DisplayName("derives a one-based deduplication identifier per card so a retry cannot duplicate")
        void derivesOneBasedDeduplicationIdentifiers() {
            acceptEveryCard();

            service.submitJobStream(SUBMISSION_ID,
                    List.of(cardOfWidth("//ONE"), cardOfWidth("//TWO")));

            assertThat(published).extracting(recorded -> recorded.messageDeduplicationId)
                    .containsExactly(SUBMISSION_ID + "-1", SUBMISSION_ID + "-2");
        }

        @Test
        @DisplayName("publishes every payload at exactly the contractual eighty-byte width")
        void publishesAtTheContractualWidth() {
            acceptEveryCard();

            service.submitJobStream(SUBMISSION_ID, List.of(cardOfWidth("//ONE")));

            assertThat(published).first()
                    .extracting(RecordedPublish::payloadAsText)
                    .asString()
                    .hasSize(JclCardImageBuilder.CARD_IMAGE_WIDTH);
        }

        @Test
        @DisplayName("publishes the sentinel card and then stops, because the flag is set before the write")
        void publishesTheSentinelAndThenStops() {
            acceptEveryCard();
            final List<String> cards = List.of(cardOfWidth("//FIRST"),
                    cardOfWidth(JclCardImageBuilder.EOF_SENTINEL_CARD),
                    cardOfWidth("//NEVER-SENT"));

            final SubmissionResult result = service.submitJobStream(SUBMISSION_ID, cards);

            assertThat(result.cardsPublished())
                    .as("the sentinel is transmitted, and nothing after it is")
                    .isEqualTo(2);
            assertThat(published).extracting(RecordedPublish::payloadAsText)
                    .last().asString().startsWith(JclCardImageBuilder.EOF_SENTINEL_CARD);
            assertThat(result.failed()).isFalse();
        }

        @Test
        @DisplayName("treats an all-blank card as the end of the stream")
        void treatsAnAllBlankCardAsEndOfStream() {
            acceptEveryCard();
            final List<String> cards = List.of(cardOfWidth("//FIRST"), cardOfWidth(""),
                    cardOfWidth("//NEVER-SENT"));

            final SubmissionResult result = service.submitJobStream(SUBMISSION_ID, cards);

            assertThat(result.cardsPublished()).isEqualTo(2);
        }

        @Test
        @DisplayName("refuses a null submission identifier")
        void refusesANullSubmissionId() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() ->
                            service.submitJobStream(null, List.of(cardOfWidth("//ONE"))))
                    .withMessageContaining("submissionId");
        }

        @Test
        @DisplayName("refuses a blank submission identifier")
        void refusesABlankSubmissionId() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.submitJobStream("  ", List.of(cardOfWidth("//ONE"))))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("refuses a submission identifier holding whitespace, which a deduplication id cannot")
        void refusesWhitespaceInTheSubmissionId() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.submitJobStream("SUB 1", List.of(cardOfWidth("//ONE"))))
                    .withMessageContaining("must not hold whitespace");
        }

        @Test
        @DisplayName("refuses a null card list")
        void refusesANullCardList() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION_ID, null))
                    .withMessageContaining("cardImages");
        }

        @Test
        @DisplayName("refuses an empty card list")
        void refusesAnEmptyCardList() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.submitJobStream(SUBMISSION_ID, Collections.emptyList()))
                    .withMessageContaining("at least one job-submission card");
        }

        @Test
        @DisplayName("refuses a null card inside the list")
        void refusesANullCardInsideTheList() {
            final List<String> cards = new ArrayList<>();
            cards.add(cardOfWidth("//ONE"));
            cards.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION_ID, cards));
        }

        @ParameterizedTest(name = "refuses a card of width {0}")
        @ValueSource(ints = {0, 1, 79, 81, 160})
        @DisplayName("refuses any card that is not exactly eighty encoded bytes")
        void refusesAWronglySizedCard(final int width) {
            final String card = "x".repeat(width);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION_ID, List.of(card)))
                    .withMessageContaining("must be exactly 80 encoded bytes")
                    .withMessageContaining("was " + width);
        }

        @Test
        @DisplayName("refuses a card holding a character that is not a single US-ASCII byte")
        void refusesANonAsciiCard() {
            final String card = cardOfWidth("//CAF\u00c9");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION_ID, List.of(card)))
                    .withMessageContaining("not representable as a single US-ASCII byte");
        }

        @Test
        @DisplayName("validates every card before publishing any, so a bad card sends nothing")
        void validatesBeforePublishingAnything() {
            final List<String> cards = List.of(cardOfWidth("//GOOD"), "too short");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream(SUBMISSION_ID, cards));

            verify(sqsOperations, never()).send(JobSubmissionServiceBaselineTest.<Object>anyConfigurer());
            assertThat(published).isEmpty();
        }
    }

    @Nested
    @DisplayName("Failure handling - the queue is ignore-on-error, so a write failure never propagates")
    class FailureHandling {

        @Test
        @DisplayName("returns a failed result instead of throwing when a write fails")
        void returnsAFailedResultRatherThanThrowing() {
            failFromCard(1);

            final SubmissionResult[] outcome = new SubmissionResult[1];
            assertThatCode(() -> outcome[0] = service.submitJobStream(SUBMISSION_ID,
                    List.of(cardOfWidth("//ONE"))))
                    .as("rethrowing would abort a request the legacy transaction completes")
                    .doesNotThrowAnyException();

            assertThat(outcome[0].failed()).isTrue();
            assertThat(outcome[0].cardsPublished()).isZero();
            assertThat(outcome[0].failureMessage())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("stops the stream at the failing card and never sends the cards after it")
        void stopsAtTheFailingCard() {
            failFromCard(2);

            final SubmissionResult result = service.submitJobStream(SUBMISSION_ID,
                    List.of(cardOfWidth("//ONE"), cardOfWidth("//TWO"), cardOfWidth("//THREE")));

            assertThat(result.cardsRequested()).isEqualTo(3);
            assertThat(result.cardsPublished()).isEqualTo(1);
            assertThat(result.failed()).isTrue();
            assertThat(result.partial())
                    .as("some cards reached the queue, so the submission is partial")
                    .isTrue();
            assertThat(published).hasSize(1);
        }

        @Test
        @DisplayName("reports a wholly unpublished submission as failed but not partial")
        void reportsAWhollyUnpublishedSubmissionAsNotPartial() {
            failFromCard(1);

            final SubmissionResult result = service.submitJobStream(SUBMISSION_ID,
                    List.of(cardOfWidth("//ONE"), cardOfWidth("//TWO")));

            assertThat(result.failed()).isTrue();
            assertThat(result.partial()).isFalse();
            assertThat(result.complete()).isFalse();
        }
    }

    @Nested
    @DisplayName("writeJobSubmissionQueue - the single-card publish boundary")
    class WriteJobSubmissionQueue {

        @Test
        @DisplayName("reports success as true and publishes the card as given")
        void reportsSuccess() {
            acceptEveryCard();

            final boolean written =
                    service.writeJobSubmissionQueue(SUBMISSION_ID, cardOfWidth("//ONE"), 1);

            assertThat(written).isTrue();
            assertThat(published).hasSize(1);
            assertThat(published.get(0).messageDeduplicationId)
                    .isEqualTo(SUBMISSION_ID + "-1");
        }

        @Test
        @DisplayName("reports a publish failure as false rather than propagating it")
        void reportsFailureAsFalse() {
            failFromCard(1);

            assertThat(service.writeJobSubmissionQueue(SUBMISSION_ID, cardOfWidth("//ONE"), 1))
                    .isFalse();
        }

        @ParameterizedTest(name = "refuses card ordinal {0}, because the index is one-based")
        @ValueSource(ints = {0, -1, -17})
        @DisplayName("refuses a card ordinal below one")
        void refusesAnOrdinalBelowOne(final int cardOrdinal) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(SUBMISSION_ID,
                            cardOfWidth("//ONE"), cardOrdinal))
                    .withMessageContaining("one-based");
        }

        @Test
        @DisplayName("refuses a submission identifier so long that the deduplication id overflows")
        void refusesAnOverlongDeduplicationIdentifier() {
            final String overlongSubmissionId = "S".repeat(200);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(overlongSubmissionId,
                            cardOfWidth("//ONE"), 1))
                    .withMessageContaining("exceeds the 128");
        }

        @Test
        @DisplayName("refuses a wrongly sized card at the single-card boundary too")
        void refusesAWronglySizedCard() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.writeJobSubmissionQueue(SUBMISSION_ID, "short", 1))
                    .withMessageContaining("must be exactly 80 encoded bytes");
        }

        @Test
        @DisplayName("refuses a null card")
        void refusesANullCard() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(SUBMISSION_ID, null, 1))
                    .withMessageContaining("cardImage");
        }
    }

    @Nested
    @DisplayName("submitTransactionReportJob - the seventeen-card stream the report transaction emits")
    class SubmitTransactionReportJob {

        @Test
        @DisplayName("publishes all seventeen cards, sentinel included")
        void publishesAllSeventeenCards() {
            acceptEveryCard();

            final SubmissionResult result =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.cardsRequested()).isEqualTo(JclCardImageBuilder.CARD_COUNT);
            assertThat(result.cardsPublished()).isEqualTo(JclCardImageBuilder.CARD_COUNT);
            assertThat(result.complete()).isTrue();
            assertThat(published).hasSize(JclCardImageBuilder.CARD_COUNT);
        }

        @Test
        @DisplayName("transmits the sentinel card last")
        void transmitsTheSentinelLast() {
            acceptEveryCard();

            service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(published).last()
                    .extracting(RecordedPublish::payloadAsText)
                    .asString()
                    .startsWith(JclCardImageBuilder.EOF_SENTINEL_CARD);
        }

        @Test
        @DisplayName("substitutes both date slots into the sort-symbol and parameter cards")
        void substitutesBothDateSlots() {
            acceptEveryCard();

            service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(published).extracting(RecordedPublish::payloadAsText)
                    .anySatisfy(card -> assertThat(card)
                            .contains(JclCardImageBuilder.SLOT_PARM_START_DATE)
                            .contains(START_DATE))
                    .anySatisfy(card -> assertThat(card)
                            .contains(JclCardImageBuilder.SLOT_PARM_END_DATE)
                            .contains(END_DATE));
        }

        @Test
        @DisplayName("mints a unique submission identifier and carries it in the result")
        void mintsAndCarriesTheSubmissionIdentifier() {
            acceptEveryCard();

            final SubmissionResult result =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.submissionId()).isNotBlank().doesNotContainAnyWhitespaces();
            assertThat(published.get(0).messageDeduplicationId)
                    .isEqualTo(result.submissionId() + "-1");
        }

        @Test
        @DisplayName("a true retry reuses the returned identity while a new request mints another")
        void onlyATrueRetryReusesEveryIdentifier() {
            acceptEveryCard();

            final SubmissionResult first =
                    service.submitTransactionReportJob(START_DATE, END_DATE);
            final List<String> firstPass = published.stream()
                    .map(publish -> publish.messageDeduplicationId)
                    .toList();
            published.clear();
            service.submitTransactionReportJob(first.submissionId(), START_DATE, END_DATE);

            assertThat(published.stream().map(publish -> publish.messageDeduplicationId).toList())
                    .as("the explicit retry reproduces the first pass card for card")
                    .containsExactlyElementsOf(firstPass);

            published.clear();
            final SubmissionResult second =
                    service.submitTransactionReportJob(START_DATE, END_DATE);
            assertThat(second.submissionId()).isNotEqualTo(first.submissionId());
        }

        @Test
        @DisplayName("validates both dates before any card is published")
        void validatesBothDatesBeforePublishing() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitTransactionReportJob("20240101", END_DATE))
                    .withMessageContaining(JclCardImageBuilder.SLOT_PARM_START_DATE);

            verify(sqsOperations, never()).send(JobSubmissionServiceBaselineTest.<Object>anyConfigurer());
        }

        @Test
        @DisplayName("refuses a null start date")
        void refusesANullStartDate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitTransactionReportJob(null, END_DATE));
        }

        @Test
        @DisplayName("refuses a null end date")
        void refusesANullEndDate() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> service.submitTransactionReportJob(START_DATE, null));
        }
    }

    @Nested
    @DisplayName("SubmissionResult - the submission-level outcome")
    class SubmissionResultRecord {

        @Test
        @DisplayName("reports a wholly published submission as complete and not partial")
        void reportsACompleteSubmission() {
            final SubmissionResult result = new SubmissionResult(SUBMISSION_ID, 17, 17, false, "");

            assertThat(result.complete()).isTrue();
            assertThat(result.partial()).isFalse();
            assertThat(result.failureMessage()).isEmpty();
        }

        @Test
        @DisplayName("clears any failure message when the submission did not fail")
        void clearsTheFailureMessageOnSuccess() {
            final SubmissionResult result = new SubmissionResult(SUBMISSION_ID, 17, 17, false, "ignored text");

            assertThat(result.failureMessage()).isEmpty();
        }

        @Test
        @DisplayName("supplies the legacy failure text when a failure carries none")
        void suppliesTheLegacyFailureText() {
            assertThat(new SubmissionResult(SUBMISSION_ID, 17, 3, true, "").failureMessage())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(new SubmissionResult(SUBMISSION_ID, 17, 3, true, null).failureMessage())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("keeps a supplied failure message")
        void keepsASuppliedFailureMessage() {
            assertThat(new SubmissionResult(SUBMISSION_ID, 17, 3, true, "queue unavailable").failureMessage())
                    .isEqualTo("queue unavailable");
        }

        @Test
        @DisplayName("reports a partly published failure as partial but not complete")
        void reportsAPartialSubmission() {
            final SubmissionResult result = new SubmissionResult(SUBMISSION_ID, 17, 3, true, "");

            assertThat(result.partial()).isTrue();
            assertThat(result.complete()).isFalse();
        }

        @Test
        @DisplayName("refuses a negative requested count")
        void refusesANegativeRequestedCount() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubmissionResult(SUBMISSION_ID, -1, 0, false, ""))
                    .withMessageContaining("cardsRequested must not be negative");
        }

        @Test
        @DisplayName("refuses a negative published count")
        void refusesANegativePublishedCount() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubmissionResult(SUBMISSION_ID, 17, -1, false, ""))
                    .withMessageContaining("cardsPublished must not be negative");
        }

        @Test
        @DisplayName("refuses publishing more cards than were requested")
        void refusesPublishingMoreThanRequested() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new SubmissionResult(SUBMISSION_ID, 3, 4, false, ""))
                    .withMessageContaining("must not")
                    .withMessageContaining("exceed");
        }
    }

    @Nested
    @DisplayName("the diagnostic the failed write records, mirroring the legacy response and"
            + " reason codes")
    class PublishFailureDiagnostics {

        /**
         * The label the service writes ahead of the reason code. The legacy paragraph
         * displays the response and reason codes before it reports the failure to the
         * operator, so the diagnostic must carry both even when one of them is empty.
         */
        private static final String REASON_LABEL = "reason=";

        /** The label the service writes ahead of the response code. */
        private static final String RESPONSE_LABEL = "response=";

        /**
         * The label the service writes ahead of the bounded chain of failure types.
         *
         * <p>The chain exists because the raw failure is deliberately not handed to the logger -
         * a rendered stack trace carries the description of every exception in the chain, which is
         * the externally-supplied text decision DL-041 keeps out of this log. The shape of the
         * chain was the diagnostically useful part of that trace, and it travels here instead.
         */
        private static final String CHAIN_LABEL = "failureChain=";

        /**
         * A description of the kind a verbose or misconfigured queue client really produces: it
         * names a credential and echoes it. Its presence anywhere in a diagnostic is a leak.
         */
        private static final String DISCLOSING_DESCRIPTION =
                "refused: secret=QAMARKBASELINELEAK endpoint=https://internal.example";

        private Logger logger;
        private ListAppender<ILoggingEvent> recorder;
        private Level originalLevel;

        @BeforeEach
        void attachRecorder() {
            this.logger = (Logger) LoggerFactory.getLogger(JobSubmissionService.class);
            this.originalLevel = this.logger.getLevel();
            this.recorder = new ListAppender<>();
            this.recorder.setContext(this.logger.getLoggerContext());
            this.recorder.start();
            this.logger.addAppender(this.recorder);
        }

        @AfterEach
        void detachRecorder() {
            this.logger.detachAppender(this.recorder);
            this.recorder.stop();
            this.logger.setLevel(this.originalLevel);
        }

        /** The single diagnostic the failed write records, at error level. */
        private String recordedFailureDiagnostic() {
            final List<ILoggingEvent> errors = this.recorder.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors)
                    .as("one failed card records exactly one operator diagnostic")
                    .hasSize(1);
            return errors.getFirst().getFormattedMessage();
        }

        @Test
        @DisplayName("records an empty reason code when the failure carries no cause at all, because"
                + " there is then nothing beneath the response code to report and nothing may be"
                + " invented")
        void aFailureWithNoCauseYieldsAnEmptyReasonCode() {
            rejectEveryCardWith(new IllegalStateException());

            final boolean published = service.writeJobSubmissionQueue(SUBMISSION_ID,
                    cardOfWidth("//ONE"), 1);

            assertThat(published).isFalse();

            final String diagnostic = recordedFailureDiagnostic();
            assertThat(diagnostic)
                    .startsWith(JobSubmissionException.DEFAULT_MESSAGE)
                    .contains(RESPONSE_LABEL + IllegalStateException.class.getSimpleName())
                    // Both labels are written even when one carries nothing, because the legacy
                    // paragraph displays both codes regardless.
                    .contains(REASON_LABEL + " ")
                    .endsWith(CHAIN_LABEL + IllegalStateException.class.getSimpleName());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "     ", "\t", "\n", "  \n  ", "queue unavailable"})
        @DisplayName("the failure's description changes no part of the diagnostic, whether it is"
                + " absent, blank or informative, because no code is derived from it")
        void theDescriptionChangesNoPartOfTheDiagnostic(final String description) {
            // The point is not that a blank description behaves like an absent one - it is that the
            // description is not consulted at all. Every value in this set, blank or not, must yield
            // the identical diagnostic, which is a stronger claim than any assertion about one of
            // them.
            rejectEveryCardWith(new IllegalStateException(description));

            final boolean published = service.writeJobSubmissionQueue(SUBMISSION_ID,
                    cardOfWidth("//ONE"), 1);

            assertThat(published).isFalse();
            assertThat(recordedFailureDiagnostic())
                    .contains(RESPONSE_LABEL + IllegalStateException.class.getSimpleName())
                    .contains(REASON_LABEL + " ")
                    .endsWith(CHAIN_LABEL + IllegalStateException.class.getSimpleName());
        }

        @Test
        @DisplayName("no part of a description becomes any code, not even its first line, because a"
                + " description is supplied by the queue client rather than by this module")
        void noPartOfADescriptionBecomesAnyCode() {
            // The legacy reason code came from the queue manager's own numeric report, not from
            // free text, so deriving it from a description was never the faithful reading. It is
            // also the one value in this class's log records that arrives from outside the module,
            // which is what makes decision DL-041 apply to it.
            rejectEveryCardWith(new IllegalStateException(
                    "queue unavailable\nat some.frame.Deeper\nat some.frame.Deepest"));

            final boolean published = service.writeJobSubmissionQueue(SUBMISSION_ID,
                    cardOfWidth("//ONE"), 1);

            assertThat(published).isFalse();
            assertThat(recordedFailureDiagnostic())
                    .contains(REASON_LABEL + " ")
                    .doesNotContain("queue unavailable")
                    .doesNotContain("Deeper")
                    .doesNotContain("Deepest");
        }

        @Test
        @DisplayName("names the failure's own type as the response code, which is the only"
                + " classification an SQS client failure carries")
        void theResponseCodeNamesTheFailureType() {
            rejectEveryCardWith(new UnsupportedOperationException("fifo not enabled"));

            service.writeJobSubmissionQueue(SUBMISSION_ID, cardOfWidth("//ONE"), 1);

            assertThat(recordedFailureDiagnostic())
                    .contains(RESPONSE_LABEL + UnsupportedOperationException.class.getSimpleName())
                    .doesNotContain("fifo not enabled");
        }

        @Test
        @DisplayName("names the type of the deepest cause as the reason code, and renders the whole"
                + " chain of types, so what the suppressed stack trace was useful for survives")
        void theReasonCodeNamesTheDeepestCauseTypeAndTheChainIsRendered() {
            rejectEveryCardWith(new IllegalStateException(DISCLOSING_DESCRIPTION,
                    new UnsupportedOperationException(DISCLOSING_DESCRIPTION,
                            new NumberFormatException(DISCLOSING_DESCRIPTION))));

            service.writeJobSubmissionQueue(SUBMISSION_ID, cardOfWidth("//ONE"), 1);

            assertThat(recordedFailureDiagnostic())
                    .contains(RESPONSE_LABEL + IllegalStateException.class.getSimpleName())
                    .contains(REASON_LABEL + NumberFormatException.class.getSimpleName())
                    .endsWith(CHAIN_LABEL + IllegalStateException.class.getSimpleName()
                            + "<-" + UnsupportedOperationException.class.getSimpleName()
                            + "<-" + NumberFormatException.class.getSimpleName());
        }

        @Test
        @DisplayName("a disclosing description is carried by no field of the diagnostic and by no"
                + " rendered trace, which is the property that makes the derived codes worth having")
        void aDisclosingDescriptionIsCarriedByNoField() {
            rejectEveryCardWith(new IllegalStateException(DISCLOSING_DESCRIPTION,
                    new UnsupportedOperationException(DISCLOSING_DESCRIPTION)));

            service.writeJobSubmissionQueue(SUBMISSION_ID, cardOfWidth("//ONE"), 1);

            final List<ILoggingEvent> errors = this.recorder.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors).as("one refused card records one diagnostic").hasSize(1);
            assertThat(errors.getFirst().getThrowableProxy())
                    .as("no throwable may be rendered, because its trace carries every description"
                            + " in the chain")
                    .isNull();
            assertThat(errors.getFirst().getFormattedMessage()).as("the recorded diagnostic")
                    .doesNotContain("QAMARKBASELINELEAK")
                    .doesNotContain("secret=")
                    .doesNotContain("internal.example");
        }
    }
}
