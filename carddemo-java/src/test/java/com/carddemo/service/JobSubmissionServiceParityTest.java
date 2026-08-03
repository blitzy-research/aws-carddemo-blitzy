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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.util.JclCardImageBuilder;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsReceiveOptions;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the online-to-batch job-submission bridge.
 *
 * <h2>What is under test and why it matters</h2>
 * The class under test is the migrated form of the single {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} site in
 * the whole estate, at {@code app/cbl/CORPT00C.cbl} line 515, together with the emitting loop above it at
 * lines 496 to 509. That one paragraph is the entire mechanism by which the online tier asks the batch tier
 * to do work, so its behaviour is an external interface contract rather than an implementation detail.
 *
 * <p>Four attributes of the legacy transient data queue definition are contractual and each is asserted
 * here rather than assumed:
 * <ul>
 *   <li>{@code RECORDSIZE(80) RECORDFORMAT(FIXED)} becomes one message per card whose body is exactly
 *       eighty encoded bytes, never trimmed and never padded by the publisher.</li>
 *   <li>{@code DISPOSITION(MOD)} - append - becomes a single message group, so the cards keep their
 *       order.</li>
 *   <li>{@code ERROROPTION(IGNORE)} becomes a publish failure that is logged and reported as data. It must
 *       not propagate: the legacy transaction completes and returns to the operator after a failed
 *       write.</li>
 *   <li>The end-of-stream test is applied to a card <em>before</em> that card is written, which is why the
 *       {@code /*EOF} sentinel is transmitted and a complete submission is seventeen messages rather than
 *       sixteen.</li>
 * </ul>
 *
 * <h2>The specific defect these tests exist to prevent</h2>
 * A test may only assert on the behaviour of the class under test. It is possible to write a loop inside a
 * test that catches a failure and carries on, assert that the loop carried on, and conclude that the
 * ignore-on-error contract is honoured - while the production loop does something different. That is not a
 * weak test but an incorrect one, because it can pass while production is wrong, and it can bless behaviour
 * production does not have.
 *
 * <p>The production loop raises a write-failure flag and that flag appears in the loop guard, so a failed
 * publish <em>stops</em> the submission. A test-owned loop that ignores the failure and runs to the end of
 * the stream describes the opposite behaviour. Every assertion in this class is therefore made against the
 * real {@code JobSubmissionService} driven through the real {@code SqsOperations.send} contract, and the
 * failure path is exercised by making that contract fail rather than by simulating a failure.
 *
 * <h2>How these tests are written</h2>
 * <ul>
 *   <li>The messaging collaborator is a hand-written double, not a mock. It genuinely applies the
 *       production lambda to a recording options object, so the queue name, the payload, the message group
 *       and the deduplication identifier are observed as the production code actually sets them rather than
 *       matched against an expectation. Writing it by hand also keeps the whole class free of unchecked
 *       generic operations, which under this module's compiler settings would fail the build rather than
 *       merely warn.</li>
 *   <li>The double implements every method of the messaging interface, and the twelve the class under test
 *       does not use all refuse to run. That is itself an assertion: this service publishes one kind of
 *       message through one method and does nothing else.</li>
 *   <li>Every expected value is a literal declared here, or a card image obtained from the card builder
 *       whose own literals are asserted by its own tests. No expected value is produced by calling the
 *       class under test.</li>
 *   <li>Widths are measured on encoded bytes rather than character count, because the record size is a byte
 *       contract.</li>
 *   <li>This is a plain unit test. It starts no container, opens no queue and loads no application
 *       context.</li>
 * </ul>
 */
@DisplayName("Job submission: the sole online-to-batch bridge, and its non-aborting failure path")
class JobSubmissionServiceParityTest {

    /** A first-in-first-out queue name, which the constructor requires. */
    private static final String QUEUE_NAME = "carddemo-jobs.fifo";

    /** The message group that carries one submission's cards in order. */
    private static final String MESSAGE_GROUP_ID = "carddemo-job-submission";

    /** A submission identity free of whitespace, as a deduplication identifier requires. */
    private static final String SUBMISSION_ID = "2024-01-01_2024-01-31";

    /** A ten-character start-date slot value, the width the card builder requires. */
    private static final String START_DATE = "2024-01-01";

    /** A ten-character end-date slot value. */
    private static final String END_DATE = "2024-01-31";

    /**
     * The number of cards in a complete job-submission stream, declared as a literal rather than read from
     * the builder so that a change to either side is a test failure.
     */
    private static final int EXPECTED_CARD_COUNT = 17;

    /**
     * The one-based ordinal of the sentinel card. It is the last card, and it is transmitted rather than
     * merely detected, which is the single most easily lost property of the legacy loop.
     */
    private static final int SENTINEL_CARD_ORDINAL = 17;

    /** The contractual record size in encoded bytes. */
    private static final int RECORD_SIZE = 80;

    /** The greatest character value that encodes as a single US-ASCII byte. */
    private static final char MAX_US_ASCII_CHARACTER = 0x7F;

    /**
     * The ordinal chosen for the mid-stream failure cases. It is deliberately neither the first nor the
     * last card, so that a failure there leaves cards both published and unsent and the sentinel unreached.
     */
    private static final int MID_STREAM_FAILING_ORDINAL = 9;

    /** The description carried by the simulated publish failure, echoed into the reason code. */
    private static final String PUBLISH_FAILURE_DESCRIPTION = "queue unavailable";

    /** Separator between a submission identity and a card ordinal in a deduplication identifier. */
    private static final String DEDUPLICATION_ID_SEPARATOR = "-";

    /** The greatest deduplication identifier length the queue service accepts. */
    private static final int DEDUPLICATION_ID_MAX_LENGTH = 128;

    /**
     * Builds a card image of exactly the contractual record size from a synthetic body.
     *
     * <p>The content is synthetic on purpose: no legacy card text is reproduced in this module, and only the
     * width is contractual. The space padding is what makes the value a fixed-width record rather than a
     * trimmed string.</p>
     *
     * @param body the leading content, which must be no wider than the record
     * @return the body followed by spaces, exactly {@link #RECORD_SIZE} encoded bytes wide
     */
    private static String cardOfRecordWidth(final String body) {
        return body + " ".repeat(RECORD_SIZE - body.length());
    }

    /**
     * Measures a value's width the way a fixed-width contract has to be measured.
     *
     * @param value the value to measure
     * @return its width in encoded bytes
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Reads a recorded payload as text, having first established that it is text.
     *
     * @param attempt the recorded publish attempt
     * @return the payload as a string
     */
    private static String payloadOf(final PublishAttempt attempt) {
        assertThat(attempt.payload())
                .as("the payload of a job-submission message is the eighty-byte card, which is text")
                .isInstanceOf(String.class);
        return (String) attempt.payload();
    }

    /**
     * Builds the service under test over a recording double.
     *
     * @param queue the messaging double to publish through
     * @return the service, configured with the queue and message group these tests assert on
     */
    private static JobSubmissionService serviceOver(final RecordingSqsOperations queue) {
        return new JobSubmissionService(queue, QUEUE_NAME, MESSAGE_GROUP_ID);
    }

    /**
     * One observed publish attempt, recorded as the production code configured it.
     *
     * @param queue           the destination the production code set
     * @param payload         the body the production code set, recorded untouched
     * @param messageGroupId  the message group the production code set
     * @param deduplicationId the deduplication identifier the production code set
     */
    private record PublishAttempt(String queue, Object payload, String messageGroupId,
            String deduplicationId) {
    }

    /**
     * A recording {@code SqsOperations} that applies the production lambda and can be made to fail on a
     * chosen attempt.
     *
     * <p>Hand-written rather than mocked for three reasons. It applies the production consumer for real, so
     * what is recorded is what the production code set rather than what an expectation was told to match.
     * It has no stubbing to leave unused, so it cannot fall foul of strict stubbing. And it involves no
     * unchecked generic operation, which matters because this module compiles tests with warnings promoted
     * to errors.</p>
     *
     * <p>The twelve methods the class under test does not use all refuse to run, so an accidental widening
     * of the production code's use of the messaging API fails loudly instead of passing silently.</p>
     */
    private static final class RecordingSqsOperations implements SqsOperations {

        /** Every attempt, recorded before any failure is raised. */
        private final List<PublishAttempt> attempts = new ArrayList<>();

        /** Only the attempts the queue accepted. */
        private final List<PublishAttempt> published = new ArrayList<>();

        /** The one-based attempt number that fails, or zero when every attempt succeeds. */
        private final int failingAttempt;

        /** The failure raised on that attempt, standing in for a failure of the publish itself. */
        private final RuntimeException publishFailure;

        /**
         * @param failingAttempt the one-based attempt number that fails, or zero for none
         * @param publishFailure the failure to raise on that attempt
         */
        RecordingSqsOperations(final int failingAttempt, final RuntimeException publishFailure) {
            this.failingAttempt = failingAttempt;
            this.publishFailure = publishFailure;
        }

        /** @return a double that accepts every card */
        static RecordingSqsOperations acceptingEverything() {
            return new RecordingSqsOperations(0, new IllegalStateException("never raised"));
        }

        /**
         * @param ordinal the one-based attempt number that must fail
         * @return a double that fails on exactly that attempt, with a described failure
         */
        static RecordingSqsOperations failingOnAttempt(final int ordinal) {
            return new RecordingSqsOperations(ordinal,
                    new IllegalStateException(PUBLISH_FAILURE_DESCRIPTION));
        }

        /**
         * @param ordinal the one-based attempt number that must fail
         * @param failure the failure to raise, which may carry no description at all
         * @return a double that fails on exactly that attempt with the supplied failure
         */
        static RecordingSqsOperations failingOnAttemptWith(final int ordinal,
                final RuntimeException failure) {
            return new RecordingSqsOperations(ordinal, failure);
        }

        /** @return every attempt observed, in order */
        List<PublishAttempt> attempts() {
            return List.copyOf(this.attempts);
        }

        /** @return the attempts the queue accepted, in order */
        List<PublishAttempt> published() {
            return List.copyOf(this.published);
        }

        @Override
        public <T> SendResult<T> send(final Consumer<SqsSendOptions<T>> options) {
            final RecordingSendOptions<T> recorder = new RecordingSendOptions<>();
            // The production lambda is applied for real, so everything recorded below is what the
            // production code set rather than what this double decided to report.
            options.accept(recorder);

            final PublishAttempt attempt = new PublishAttempt(recorder.queue, recorder.payload,
                    recorder.messageGroupId, recorder.deduplicationId);
            this.attempts.add(attempt);

            if (this.attempts.size() == this.failingAttempt) {
                // A runtime failure of the publish itself, which is the only failure mode the queue's
                // ignore-on-error attribute covers.
                throw this.publishFailure;
            }

            this.published.add(attempt);
            final Message<T> message = new GenericMessage<>(recorder.payload);
            return new SendResult<>(UUID.randomUUID(), recorder.queue, message, Map.of());
        }

        @Override
        public Optional<Message<?>> receive(final Consumer<SqsReceiveOptions> options) {
            throw unsupported("receive(Consumer)");
        }

        @Override
        public <T> Optional<Message<T>> receive(final Consumer<SqsReceiveOptions> options,
                final Class<T> payloadType) {
            throw unsupported("receive(Consumer, Class)");
        }

        @Override
        public Collection<Message<?>> receiveMany(final Consumer<SqsReceiveOptions> options) {
            throw unsupported("receiveMany(Consumer)");
        }

        @Override
        public <T> Collection<Message<T>> receiveMany(final Consumer<SqsReceiveOptions> options,
                final Class<T> payloadType) {
            throw unsupported("receiveMany(Consumer, Class)");
        }

        @Override
        public <T> SendResult<T> send(final T payload) {
            throw unsupported("send(T)");
        }

        @Override
        public <T> SendResult<T> send(final String endpoint, final T payload) {
            throw unsupported("send(String, T)");
        }

        @Override
        public <T> SendResult<T> send(final String endpoint, final Message<T> message) {
            throw unsupported("send(String, Message)");
        }

        @Override
        public <T> SendResult.Batch<T> sendMany(final String endpoint,
                final Collection<Message<T>> messages) {
            throw unsupported("sendMany(String, Collection)");
        }

        @Override
        public Optional<Message<?>> receive() {
            throw unsupported("receive()");
        }

        @Override
        public <T> Optional<Message<T>> receive(final String endpoint, final Class<T> payloadType) {
            throw unsupported("receive(String, Class)");
        }

        @Override
        public Collection<Message<?>> receiveMany() {
            throw unsupported("receiveMany()");
        }

        @Override
        public <T> Collection<Message<T>> receiveMany(final String endpoint,
                final Class<T> payloadType) {
            throw unsupported("receiveMany(String, Class)");
        }

        /**
         * @param signature the method that must never be reached
         * @return the failure to raise
         */
        private static UnsupportedOperationException unsupported(final String signature) {
            return new UnsupportedOperationException("the job-submission bridge must publish one card per"
                    + " message through send(Consumer) and must not call " + signature);
        }
    }

    /**
     * A {@code SqsSendOptions} that records what was set on it instead of building a request.
     *
     * @param <T> the payload type the production call site infers
     */
    private static final class RecordingSendOptions<T> implements SqsSendOptions<T> {

        /** The destination set by the caller. */
        private String queue;

        /** The body set by the caller, recorded untouched. */
        private T payload;

        /** The message group set by the caller. */
        private String messageGroupId;

        /** The deduplication identifier set by the caller. */
        private String deduplicationId;

        @Override
        public SqsSendOptions<T> queue(final String value) {
            this.queue = value;
            return this;
        }

        @Override
        public SqsSendOptions<T> payload(final T value) {
            this.payload = value;
            return this;
        }

        @Override
        public SqsSendOptions<T> header(final String name, final Object value) {
            throw new UnsupportedOperationException("the job-submission bridge sets no message header");
        }

        @Override
        public SqsSendOptions<T> headers(final Map<String, Object> values) {
            throw new UnsupportedOperationException("the job-submission bridge sets no message header");
        }

        @Override
        public SqsSendOptions<T> delaySeconds(final Integer value) {
            throw new UnsupportedOperationException("the job-submission bridge delays no message, because"
                    + " the legacy queue write was immediate");
        }

        @Override
        public SqsSendOptions<T> messageGroupId(final String value) {
            this.messageGroupId = value;
            return this;
        }

        @Override
        public SqsSendOptions<T> messageDeduplicationId(final String value) {
            this.deduplicationId = value;
            return this;
        }
    }

    @Nested
    @DisplayName("A complete submission publishes every card, in order, including the sentinel")
    class CompleteSubmission {

        @Test
        @DisplayName("seventeen cards produce seventeen messages, so the sentinel is transmitted rather "
                + "than merely detected")
        void seventeenCardsProduceSeventeenMessages() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            final JobSubmissionService.SubmissionResult result =
                    serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(queue.attempts())
                    .as("one publish attempt per card, the sentinel included")
                    .hasSize(EXPECTED_CARD_COUNT);
            assertThat(queue.published())
                    .as("every attempt was accepted")
                    .hasSize(EXPECTED_CARD_COUNT);
            assertThat(result.cardsRequested()).isEqualTo(EXPECTED_CARD_COUNT);
            assertThat(result.cardsPublished()).isEqualTo(EXPECTED_CARD_COUNT);
            assertThat(result.failed()).isFalse();
            assertThat(result.complete())
                    .as("a submission in which every requested card reached the queue is complete")
                    .isTrue();
            assertThat(result.partial()).isFalse();
            assertThat(result.failureMessage())
                    .as("a successful submission carries no failure text at all, not even the word null")
                    .isEmpty();
        }

        @Test
        @DisplayName("the final message body is the end-of-stream sentinel card, padded to the record width")
        void theFinalMessageBodyIsTheSentinelCard() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            final PublishAttempt sentinel = queue.published().get(SENTINEL_CARD_ORDINAL - 1);
            assertThat(payloadOf(sentinel))
                    .as("the sentinel is written, so the batch tier receives its end-of-stream marker")
                    .isEqualTo(cardOfRecordWidth(JclCardImageBuilder.EOF_SENTINEL_CARD));
        }

        @Test
        @DisplayName("the published bodies are the builder's cards in the builder's order, untouched")
        void thePublishedBodiesAreTheBuildersCardsInOrder() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> expected = JclCardImageBuilder.build(START_DATE, END_DATE);
            final List<String> actual = new ArrayList<>();
            for (final PublishAttempt attempt : queue.published()) {
                actual.add(payloadOf(attempt));
            }

            assertThat(actual)
                    .as("order is contractual: the queue is append-disposition and the cards are a job "
                            + "stream, so a reordering would submit a different job")
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("every message body is exactly eighty encoded bytes and every character encodes as a "
                + "single US-ASCII byte")
        void everyMessageBodyIsExactlyEightyUsAsciiBytes() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(queue.published()).allSatisfy(attempt -> {
                final String body = payloadOf(attempt);
                assertThat(encodedWidth(body))
                        .as("fixed record format: the body width is a byte contract, not a character count")
                        .isEqualTo(RECORD_SIZE);
                for (int index = 0; index < body.length(); index++) {
                    assertThat(body.charAt(index))
                            .as("character at position %d of '%s'", index + 1, body)
                            .isLessThanOrEqualTo(MAX_US_ASCII_CHARACTER);
                }
            });
        }

        @Test
        @DisplayName("every message names the configured queue and the single configured message group, so "
                + "append order is preserved")
        void everyMessageNamesTheConfiguredQueueAndOneMessageGroup() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(queue.published()).allSatisfy(attempt -> {
                assertThat(attempt.queue()).isEqualTo(QUEUE_NAME);
                assertThat(attempt.messageGroupId())
                        .as("one group for the whole submission is what makes a first-in-first-out queue "
                                + "reproduce the append disposition of the legacy queue")
                        .isEqualTo(MESSAGE_GROUP_ID);
            });
        }

        @Test
        @DisplayName("each message carries the submission identity and its own one-based ordinal as the "
                + "deduplication identifier, within the length the queue service accepts")
        void eachMessageCarriesItsOwnDeduplicationIdentifier() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitJobStream(SUBMISSION_ID,
                    JclCardImageBuilder.build(START_DATE, END_DATE));

            final List<PublishAttempt> published = queue.published();
            for (int ordinal = 1; ordinal <= EXPECTED_CARD_COUNT; ordinal++) {
                assertThat(published.get(ordinal - 1).deduplicationId())
                        .as("card %d", ordinal)
                        .isEqualTo(SUBMISSION_ID + DEDUPLICATION_ID_SEPARATOR + ordinal)
                        .hasSizeLessThanOrEqualTo(DEDUPLICATION_ID_MAX_LENGTH);
            }
        }

        @Test
        @DisplayName("the deduplication identifiers are all distinct, so no card of a submission is "
                + "mistaken for a repeat of another")
        void theDeduplicationIdentifiersAreAllDistinct() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitJobStream(SUBMISSION_ID,
                    JclCardImageBuilder.build(START_DATE, END_DATE));

            final List<String> identifiers = new ArrayList<>();
            for (final PublishAttempt attempt : queue.published()) {
                identifiers.add(attempt.deduplicationId());
            }

            assertThat(identifiers).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("resubmitting the same reporting period reproduces the same seventeen card payloads "
                + "byte for byte and the same seventeen deduplication identifiers, because the identity "
                + "is a pure function of the request and a random one would defeat idempotency")
        void resubmittingTheSamePeriodReproducesTheSameCardsAndTheSameIdentifiers() {
            // Both halves of the submission are a pure function of the two date slots: nothing random
            // and nothing time-derived takes part in composing a card, and nothing does in deriving the
            // identity either. That is what makes a replay safe - a caller repeating an interrupted
            // submission reissues the identifiers the first pass used, so the cards that already landed
            // are collapsed and the ones that never did are added. A submission that is genuinely a
            // second unit of work says so through the identity-bearing entry point instead.
            final RecordingSqsOperations first = RecordingSqsOperations.acceptingEverything();
            final RecordingSqsOperations second = RecordingSqsOperations.acceptingEverything();

            serviceOver(first).submitTransactionReportJob(START_DATE, END_DATE);
            serviceOver(second).submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> firstPayloads = new ArrayList<>();
            final List<String> firstIdentifiers = new ArrayList<>();
            for (final PublishAttempt attempt : first.published()) {
                firstPayloads.add(payloadOf(attempt));
                firstIdentifiers.add(attempt.deduplicationId());
            }
            final List<String> secondPayloads = new ArrayList<>();
            final List<String> secondIdentifiers = new ArrayList<>();
            for (final PublishAttempt attempt : second.published()) {
                secondPayloads.add(payloadOf(attempt));
                secondIdentifiers.add(attempt.deduplicationId());
            }

            assertThat(secondPayloads)
                    .as("the card stream is a pure function of the two date slots")
                    .containsExactlyElementsOf(firstPayloads);
            assertThat(secondIdentifiers)
                    .as("a replay reproduces the identifiers card for card")
                    .containsExactlyElementsOf(firstIdentifiers);
            assertThat(second.published().get(0).deduplicationId())
                    .as("the identity carries the reporting period as a readable prefix")
                    .startsWith(START_DATE);
        }

        @Test
        @DisplayName("a different reporting period produces different identifiers, so two periods are not "
                + "deduplicated against each other")
        void aDifferentPeriodProducesDifferentIdentifiers() {
            final RecordingSqsOperations january = RecordingSqsOperations.acceptingEverything();
            final RecordingSqsOperations february = RecordingSqsOperations.acceptingEverything();

            serviceOver(january).submitTransactionReportJob(START_DATE, END_DATE);
            serviceOver(february).submitTransactionReportJob("2024-02-01", "2024-02-29");

            assertThat(february.published().get(0).deduplicationId())
                    .isNotEqualTo(january.published().get(0).deduplicationId());
        }

        @Test
        @DisplayName("the two ten-character date slots reach the card stream, so the submitted job covers "
                + "the requested period")
        void theTwoDateSlotsReachTheCardStream() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            final StringBuilder wholeStream = new StringBuilder();
            for (final PublishAttempt attempt : queue.published()) {
                wholeStream.append(payloadOf(attempt));
            }

            assertThat(wholeStream.toString())
                    .as("the four substitution slots are filled from the two arguments")
                    .contains(START_DATE)
                    .contains(END_DATE);
        }

        @Test
        @DisplayName("a space-padded date slot never reaches a card or an identity at all, because the slot "
                + "is a ten-column date rather than a padded field; the derived identity is nonetheless "
                + "free of whitespace, which is what the queue service requires of it")
        void aSpacePaddedDateSlotIsRefusedAndTheDerivedIdentityCarriesNoWhitespace() {
            // Two properties are asserted together because they used to be one. A deduplication
            // identifier may hold no whitespace, so the identity derivation condenses what it is given -
            // that condensation is retained and asserted on a well-formed submission below. But a
            // space-padded slot is no longer something that can arrive: the slot occupies ten columns of
            // an eighty-column card and every one of them is fixed by the legacy work field, so a space
            // where a digit belongs is refused before any card is composed. Rejecting it is stronger
            // than condensing it, because a padded slot inside the sort include-condition would have
            // named a window the caller never asked for.
            final RecordingSqsOperations refused = RecordingSqsOperations.acceptingEverything();
            final String paddedStartDate = "2024-01-1 ";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(refused)
                            .submitTransactionReportJob(paddedStartDate, END_DATE))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("position 10");
            assertThat(refused.attempts())
                    .as("publish attempts after a refused slot").isEmpty();

            final RecordingSqsOperations accepted = RecordingSqsOperations.acceptingEverything();
            serviceOver(accepted).submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(accepted.published().get(0).deduplicationId())
                    .as("the identity derived from the slots carries no whitespace, so the queue"
                            + " service accepts it")
                    .doesNotContain(" ")
                    .startsWith(START_DATE)
                    .endsWith(DEDUPLICATION_ID_SEPARATOR + 1);
        }

        @Test
        @DisplayName("a stream of one card is published as one message, so the loop has no minimum length")
        void aStreamOfOneCardIsPublishedAsOneMessage() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            final JobSubmissionService.SubmissionResult result = serviceOver(queue)
                    .submitJobStream(SUBMISSION_ID, List.of(cardOfRecordWidth("//ONLY CARD")));

            assertThat(queue.published()).hasSize(1);
            assertThat(result.complete()).isTrue();
        }

        @Test
        @DisplayName("the stream is snapshotted on entry, so a caller mutating its list afterwards cannot "
                + "change what was submitted")
        void theStreamIsSnapshottedOnEntry() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();
            final List<String> mutable =
                    new ArrayList<>(List.of(cardOfRecordWidth("//CARD ONE"),
                            cardOfRecordWidth("//CARD TWO")));

            final JobSubmissionService.SubmissionResult result =
                    serviceOver(queue).submitJobStream(SUBMISSION_ID, mutable);
            mutable.clear();
            mutable.add(cardOfRecordWidth("//ADDED AFTERWARDS"));

            assertThat(result.cardsRequested()).isEqualTo(2);
            assertThat(queue.published()).hasSize(2);
            assertThat(payloadOf(queue.published().get(0)))
                    .isEqualTo(cardOfRecordWidth("//CARD ONE"));
        }
    }

    @Nested
    @DisplayName("The stream stops early on a blank or low-values card, because the legacy end-of-stream "
            + "test expands to three comparisons")
    class EndOfStreamDetection {

        @Test
        @DisplayName("an all-spaces card ends the stream and is itself transmitted")
        void anAllSpacesCardEndsTheStreamAndIsTransmitted() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            final JobSubmissionService.SubmissionResult result = serviceOver(queue).submitJobStream(
                    SUBMISSION_ID,
                    List.of(cardOfRecordWidth("//FIRST CARD"), " ".repeat(RECORD_SIZE),
                            cardOfRecordWidth("//NEVER SENT")));

            assertThat(queue.published())
                    .as("the terminating card is written before the test stops the loop")
                    .hasSize(2);
            assertThat(payloadOf(queue.published().get(1))).isEqualTo(" ".repeat(RECORD_SIZE));
            assertThat(result.cardsRequested())
                    .as("the requested count is the whole supplied stream, which is what makes an early "
                            + "stop visible in the result")
                    .isEqualTo(3);
            assertThat(result.cardsPublished()).isEqualTo(2);
            assertThat(result.failed())
                    .as("stopping on the end-of-stream marker is not a failure")
                    .isFalse();
            assertThat(result.complete())
                    .as("not every requested card was published, so the submission is not complete")
                    .isFalse();
        }

        @Test
        @DisplayName("an all-low-values card is refused by the payload boundary before the stream-terminating "
                + "test can observe it, so the third comparison of the abbreviated legacy relation is "
                + "retained in the predicate but unreachable through a published stream")
        void anAllLowValuesCardIsRefusedBeforeTheStreamTerminatingTest() {
            // The abbreviated legacy relation expands to three equality tests - the sentinel, spaces and
            // low values - and the translated predicate keeps all three, so it remains faithful. Only two
            // of them are reachable: a card of low values is a card of control bytes, and the payload
            // boundary refuses a control byte before the emitting loop begins, because a control byte
            // leaves the record width unchanged and would reframe the record at the consumer. The
            // submission is therefore refused outright rather than silently truncated, which is the
            // safer of the two outcomes and publishes nothing either way. The two reachable comparisons
            // are asserted by the blank-card and sentinel-card tests either side of this one.
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(SUBMISSION_ID,
                            List.of(cardOfRecordWidth("//FIRST CARD"),
                                    String.valueOf('\0').repeat(RECORD_SIZE),
                                    cardOfRecordWidth("//NEVER SENT"))))
                    .withMessageContaining("non-printable");

            assertThat(queue.attempts())
                    .as("nothing is published, so the stream is refused rather than truncated")
                    .isEmpty();
        }

        @Test
        @DisplayName("the sentinel is recognised with its contractual trailing spaces, because a short "
                + "COBOL literal is compared as though space-padded")
        void theSentinelIsRecognisedWithItsTrailingSpaces() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitJobStream(SUBMISSION_ID,
                    List.of(cardOfRecordWidth(JclCardImageBuilder.EOF_SENTINEL_CARD),
                            cardOfRecordWidth("//NEVER SENT")));

            assertThat(queue.published())
                    .as("the padded sentinel terminates the stream and is transmitted")
                    .hasSize(1);
            assertThat(payloadOf(queue.published().get(0)))
                    .isEqualTo(cardOfRecordWidth(JclCardImageBuilder.EOF_SENTINEL_CARD));
        }

        @Test
        @DisplayName("a card that merely begins with the sentinel text does not end the stream, because the "
                + "comparison is of the whole record")
        void aCardMerelyBeginningWithTheSentinelTextDoesNotEndTheStream() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            serviceOver(queue).submitJobStream(SUBMISSION_ID,
                    List.of(cardOfRecordWidth(JclCardImageBuilder.EOF_SENTINEL_CARD + " TRAILING"),
                            cardOfRecordWidth("//SECOND CARD")));

            assertThat(queue.published()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("A publish failure is non-fatal and stops the stream, which is the ignore-on-error "
            + "attribute of the legacy queue")
    class NonFatalPublishFailure {

        @Test
        @DisplayName("a failure at card nine is attempted, the eight before it are published, and nothing "
                + "propagates to the caller")
        void aFailureAtCardNineStopsTheStreamWithoutThrowing() {
            final RecordingSqsOperations queue =
                    RecordingSqsOperations.failingOnAttempt(MID_STREAM_FAILING_ORDINAL);
            final JobSubmissionService service = serviceOver(queue);

            final JobSubmissionService.SubmissionResult[] captured =
                    new JobSubmissionService.SubmissionResult[1];
            assertThatCode(() -> captured[0] =
                    service.submitTransactionReportJob(START_DATE, END_DATE))
                    .as("the queue is defined ignore-on-error and the legacy transaction returns to the "
                            + "operator after a failed write, so nothing may escape")
                    .doesNotThrowAnyException();

            assertThat(queue.attempts())
                    .as("exactly the failing ordinal's worth of attempts were made: the write-failure flag "
                            + "is in the loop guard, so the loop stops")
                    .hasSize(MID_STREAM_FAILING_ORDINAL);
            assertThat(queue.published())
                    .as("one fewer than the attempts reached the queue")
                    .hasSize(MID_STREAM_FAILING_ORDINAL - 1);

            final JobSubmissionService.SubmissionResult result = captured[0];
            assertThat(result).isNotNull();
            assertThat(result.failed())
                    .as("the failure travels back as data rather than as an exception")
                    .isTrue();
            assertThat(result.cardsRequested()).isEqualTo(EXPECTED_CARD_COUNT);
            assertThat(result.cardsPublished()).isEqualTo(MID_STREAM_FAILING_ORDINAL - 1);
            assertThat(result.partial())
                    .as("some cards reached the queue and some did not, which is the deliberate legacy "
                            + "outcome of a failed write")
                    .isTrue();
            assertThat(result.complete()).isFalse();
            assertThat(result.failureMessage())
                    .as("the operator-facing text is the frozen legacy literal, with no diagnostic detail")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("no card after the failing one is attempted, and the sentinel is therefore never sent")
        void noCardAfterTheFailingOneIsAttempted() {
            final RecordingSqsOperations queue =
                    RecordingSqsOperations.failingOnAttempt(MID_STREAM_FAILING_ORDINAL);

            serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final List<String> attemptedBodies = new ArrayList<>();
            for (final PublishAttempt attempt : queue.attempts()) {
                attemptedBodies.add(payloadOf(attempt));
            }

            assertThat(attemptedBodies)
                    .as("the attempted cards are exactly the leading run up to and including the failing "
                            + "card")
                    .containsExactlyElementsOf(cards.subList(0, MID_STREAM_FAILING_ORDINAL));
            assertThat(attemptedBodies)
                    .as("the sentinel is beyond the failing card, so the batch tier receives no "
                            + "end-of-stream marker for a stream that stopped short")
                    .doesNotContain(cardOfRecordWidth(JclCardImageBuilder.EOF_SENTINEL_CARD));
        }

        @Test
        @DisplayName("a failure on the very first card publishes nothing at all, which is distinguishable "
                + "from a partial submission")
        void aFailureOnTheFirstCardPublishesNothing() {
            final RecordingSqsOperations queue = RecordingSqsOperations.failingOnAttempt(1);

            final JobSubmissionService.SubmissionResult result =
                    serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(queue.attempts()).hasSize(1);
            assertThat(queue.published()).isEmpty();
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.failed()).isTrue();
            assertThat(result.partial())
                    .as("nothing reached the queue, so this is a failure rather than a partial submission")
                    .isFalse();
            assertThat(result.complete()).isFalse();
        }

        @Test
        @DisplayName("a failure on the sentinel leaves the sixteen preceding cards published and still "
                + "returns normally")
        void aFailureOnTheSentinelLeavesThePrecedingCardsPublished() {
            final RecordingSqsOperations queue =
                    RecordingSqsOperations.failingOnAttempt(SENTINEL_CARD_ORDINAL);

            final JobSubmissionService.SubmissionResult result =
                    serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(queue.attempts()).hasSize(SENTINEL_CARD_ORDINAL);
            assertThat(queue.published()).hasSize(SENTINEL_CARD_ORDINAL - 1);
            assertThat(result.failed()).isTrue();
            assertThat(result.partial()).isTrue();
            assertThat(result.cardsPublished()).isEqualTo(EXPECTED_CARD_COUNT - 1);
        }

        @Test
        @DisplayName("the single-card publisher reports a failure as false rather than throwing, which is "
                + "the inverse of the legacy write-error flag")
        void theSingleCardPublisherReportsAFailureAsFalse() {
            final RecordingSqsOperations queue = RecordingSqsOperations.failingOnAttempt(1);

            final boolean accepted = serviceOver(queue)
                    .writeJobSubmissionQueue(SUBMISSION_ID, cardOfRecordWidth("//ONE CARD"), 1);

            assertThat(accepted)
                    .as("false is what the emitting loop observes in its guard, and it is what stops the "
                            + "remaining cards")
                    .isFalse();
            assertThat(queue.attempts()).hasSize(1);
            assertThat(queue.published()).isEmpty();
        }

        @Test
        @DisplayName("a publish failure carrying no description is still absorbed, so the diagnostic path "
                + "cannot itself abort the request")
        void aPublishFailureCarryingNoDescriptionIsStillAbsorbed() {
            final RecordingSqsOperations queue = RecordingSqsOperations
                    .failingOnAttemptWith(1, new IllegalStateException((String) null));
            final JobSubmissionService service = serviceOver(queue);

            final JobSubmissionService.SubmissionResult[] captured =
                    new JobSubmissionService.SubmissionResult[1];
            assertThatCode(() -> captured[0] = service.submitJobStream(SUBMISSION_ID,
                    List.of(cardOfRecordWidth("//ONE CARD"))))
                    .as("the reason code is derived from the failure's own description, so a failure "
                            + "without one must not produce a second failure while it is being reported")
                    .doesNotThrowAnyException();

            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].failed()).isTrue();
            assertThat(captured[0].cardsPublished()).isZero();
            assertThat(captured[0].failureMessage())
                    .as("the operator still sees the frozen literal, never the text null")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a publish failure carrying a blank description is absorbed on the same path")
        void aPublishFailureCarryingABlankDescriptionIsAbsorbed() {
            final RecordingSqsOperations queue =
                    RecordingSqsOperations.failingOnAttemptWith(1, new IllegalStateException("   "));

            final JobSubmissionService.SubmissionResult result = serviceOver(queue)
                    .submitJobStream(SUBMISSION_ID, List.of(cardOfRecordWidth("//ONE CARD")));

            assertThat(result.failed()).isTrue();
            assertThat(result.failureMessage()).isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a multi-line publish description reaches neither the operator-facing text nor "
                + "any derived code, because no code is derived from a description at all")
        void aMultiLinePublishDescriptionIsStillAbsorbed() {
            final RecordingSqsOperations queue = RecordingSqsOperations.failingOnAttemptWith(1,
                    new IllegalStateException("first line\nsecond line\nthird line"));

            final JobSubmissionService.SubmissionResult result = serviceOver(queue)
                    .submitJobStream(SUBMISSION_ID, List.of(cardOfRecordWidth("//ONE CARD")));

            assertThat(result.failed()).isTrue();
            assertThat(result.failureMessage())
                    .as("the legacy screen shows one frozen literal and no diagnostic detail, so the "
                            + "result carries exactly that literal; the response and reason codes are "
                            + "derived from the failure's type rather than its description and reach "
                            + "the log alone")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE)
                    .doesNotContain("first line")
                    .doesNotContain("second line")
                    .doesNotContain("third line");
        }

        @Test
        @DisplayName("the single-card publisher reports acceptance as true and publishes the card untouched")
        void theSingleCardPublisherReportsAcceptanceAsTrue() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();
            final String card = cardOfRecordWidth("//ONE CARD");

            final boolean accepted =
                    serviceOver(queue).writeJobSubmissionQueue(SUBMISSION_ID, card, 4);

            assertThat(accepted).isTrue();
            assertThat(payloadOf(queue.published().get(0)))
                    .as("the trailing spaces of a fixed-width record are part of the record and are never "
                            + "trimmed by the publisher")
                    .isEqualTo(card);
            assertThat(queue.published().get(0).deduplicationId())
                    .as("the ordinal supplied by the caller is the one that reaches the queue")
                    .isEqualTo(SUBMISSION_ID + DEDUPLICATION_ID_SEPARATOR + 4);
        }
    }

    @Nested
    @DisplayName("A malformed stream is rejected outright rather than leaving a partial submission behind")
    class MalformedStreamRejection {

        @Test
        @DisplayName("a card narrower than the record width is refused and nothing is published")
        void aNarrowCardIsRefusedAndNothingIsPublished() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(SUBMISSION_ID,
                            List.of(cardOfRecordWidth("//GOOD CARD"), "//SHORT")))
                    .withMessageContaining("2")
                    .withMessageContaining(String.valueOf(RECORD_SIZE));

            assertThat(queue.attempts())
                    .as("the whole stream is validated before anything is published, so a malformed card "
                            + "cannot leave a partial submission that looks like a publish failure")
                    .isEmpty();
        }

        @Test
        @DisplayName("a card wider than the record width is refused")
        void aWideCardIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(SUBMISSION_ID,
                            List.of(cardOfRecordWidth("//GOOD CARD") + " ")))
                    .withMessageContaining("81");
            assertThat(queue.attempts()).isEmpty();
        }

        @Test
        @DisplayName("a card holding a character that is not a single US-ASCII byte is refused, so the "
                + "bytes published are the caller's bytes")
        void aCardHoldingANonAsciiCharacterIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();
            // One character wide but two bytes when encoded, so a character-count check would miss it.
            final String card = "//CARD \u00e9" + " ".repeat(RECORD_SIZE - 8);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(SUBMISSION_ID, List.of(card)));
            assertThat(queue.attempts()).isEmpty();
        }

        @Test
        @DisplayName("an empty stream is refused, because a submission of nothing is a programming error")
        void anEmptyStreamIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(SUBMISSION_ID, List.of()))
                    .withMessageContaining("at least one");
            assertThat(queue.attempts()).isEmpty();
        }

        @Test
        @DisplayName("a null stream is refused, naming the argument")
        void aNullStreamIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(SUBMISSION_ID, null))
                    .withMessageContaining("cardImages");
        }

        @Test
        @DisplayName("a stream holding a null card is refused")
        void aStreamHoldingANullCardIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();
            final List<String> withNull = new ArrayList<>();
            withNull.add(cardOfRecordWidth("//GOOD CARD"));
            withNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(SUBMISSION_ID, withNull));
            assertThat(queue.attempts()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] the submission identity [{0}] is refused")
        @ValueSource(strings = {"", "   ", "has space", "has\ttab", "has\nnewline"})
        @DisplayName("a blank submission identity, or one holding whitespace, is refused rather than "
                + "silently condensed, because condensing would make two identities collide")
        void aBlankOrWhitespacedSubmissionIdentityIsRefused(final String submissionId) {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(submissionId,
                            List.of(cardOfRecordWidth("//GOOD CARD"))))
                    .withMessageContaining("submissionId");
            assertThat(queue.attempts()).isEmpty();
        }

        @Test
        @DisplayName("a null submission identity is refused, naming the argument")
        void aNullSubmissionIdentityIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(null,
                            List.of(cardOfRecordWidth("//GOOD CARD"))))
                    .withMessageContaining("submissionId");
        }

        @Test
        @DisplayName("a submission identity long enough to overflow the deduplication identifier is "
                + "refused, naming the limit")
        void anOverlongSubmissionIdentityIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();
            final String overlong = "X".repeat(DEDUPLICATION_ID_MAX_LENGTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue).submitJobStream(overlong,
                            List.of(cardOfRecordWidth("//GOOD CARD"))))
                    .withMessageContaining(String.valueOf(DEDUPLICATION_ID_MAX_LENGTH));
        }

        @Test
        @DisplayName("a card ordinal below the first ordinal is refused, because the ordinal is one-based "
                + "like the legacy card index")
        void aCardOrdinalBelowOneIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue).writeJobSubmissionQueue(SUBMISSION_ID,
                            cardOfRecordWidth("//ONE CARD"), 0))
                    .withMessageContaining("one-based");
            assertThat(queue.attempts()).isEmpty();
        }

        @Test
        @DisplayName("a null card is refused by the single-card publisher, naming the argument")
        void aNullCardIsRefusedByTheSingleCardPublisher() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> serviceOver(queue)
                            .writeJobSubmissionQueue(SUBMISSION_ID, null, 1))
                    .withMessageContaining("cardImage");
        }

        @ParameterizedTest(name = "[{index}] a date slot of [{0}] is refused")
        @ValueSource(strings = {"", "2024-1-1", "2024-01-011"})
        @DisplayName("a date slot that is not exactly ten encoded bytes is refused before any card exists")
        void aWronglySizedDateSlotIsRefused(final String startDate) {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> serviceOver(queue)
                            .submitTransactionReportJob(startDate, END_DATE));
            assertThat(queue.attempts()).isEmpty();
        }

        @Test
        @DisplayName("a null date slot is refused")
        void aNullDateSlotIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> serviceOver(queue).submitTransactionReportJob(null, END_DATE));
            assertThat(queue.attempts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The legacy array bound caps the loop, and it is never what stops a well-formed "
            + "submission")
    class LegacyArrayBound {

        @Test
        @DisplayName("the loop bound is the legacy thousand-entry card array, so a stream longer than that "
                + "is truncated rather than published whole")
        void theLoopBoundIsTheLegacyThousandEntryArray() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();
            final int oversized = JclCardImageBuilder.OVERSIZED_REDEFINE_CARD_BOUND + 5;
            final List<String> cards = new ArrayList<>(oversized);
            for (int ordinal = 1; ordinal <= oversized; ordinal++) {
                cards.add(cardOfRecordWidth("//CARD " + ordinal));
            }

            final JobSubmissionService.SubmissionResult result =
                    serviceOver(queue).submitJobStream(SUBMISSION_ID, cards);

            assertThat(queue.published())
                    .as("the guard tests the ordinal against the legacy array bound as well as against the "
                            + "supplied length")
                    .hasSize(JclCardImageBuilder.OVERSIZED_REDEFINE_CARD_BOUND);
            assertThat(result.cardsRequested()).isEqualTo(oversized);
            assertThat(result.failed())
                    .as("reaching the array bound is not a publish failure")
                    .isFalse();
            assertThat(result.complete()).isFalse();
        }

        @Test
        @DisplayName("the seventeen-card stream is far inside the bound, so the bound never explains a "
                + "short submission of a real job")
        void theSeventeenCardStreamIsFarInsideTheBound() {
            assertThat(EXPECTED_CARD_COUNT)
                    .isLessThan(JclCardImageBuilder.OVERSIZED_REDEFINE_CARD_BOUND);
        }
    }

    @Nested
    @DisplayName("Configuration is validated at construction, so a deployment error is found by the "
            + "deployment rather than by an operator")
    class ConstructorValidation {

        @Test
        @DisplayName("a queue name without the first-in-first-out suffix is refused, because job cards "
                + "must keep their order")
        void aQueueNameWithoutTheFifoSuffixIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(queue, "JOBS", MESSAGE_GROUP_ID))
                    .withMessageContaining(".fifo")
                    .withMessageContaining("carddemo.aws.sqs.job-queue");
        }

        @ParameterizedTest(name = "[{index}] a queue name of [{0}] is refused")
        @ValueSource(strings = {"", "   "})
        @DisplayName("an absent or blank queue name is refused, naming the property it is bound from")
        void anAbsentOrBlankQueueNameIsRefused(final String queueName) {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(queue, queueName, MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-queue")
                    .withMessageContaining("no default");
        }

        @Test
        @DisplayName("a null queue name is refused, naming the property rather than reporting a null "
                + "reference")
        void aNullQueueNameIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(queue, null, MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-queue");
        }

        @ParameterizedTest(name = "[{index}] a message group of [{0}] is refused")
        @ValueSource(strings = {"", "   "})
        @DisplayName("an absent or blank message group is refused, naming the property it is bound from")
        void anAbsentOrBlankMessageGroupIsRefused(final String messageGroupId) {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(queue, QUEUE_NAME, messageGroupId))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id");
        }

        @Test
        @DisplayName("a null message group is refused, naming the property")
        void aNullMessageGroupIsRefused() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(queue, QUEUE_NAME, null))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id");
        }

        @Test
        @DisplayName("a null messaging collaborator is refused, naming the collaborator")
        void aNullMessagingCollaboratorIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new JobSubmissionService(null, QUEUE_NAME, MESSAGE_GROUP_ID))
                    .withMessageContaining("sqsOperations");
        }
    }

    @Nested
    @DisplayName("The bridge publishes one kind of message and uses nothing else of the messaging API")
    class MessagingApiUse {

        @Test
        @DisplayName("no message header, delay or batch send is used, so the eighty-byte body is the whole "
                + "of what crosses the bridge")
        void noHeaderDelayOrBatchSendIsUsed() {
            final RecordingSqsOperations queue = RecordingSqsOperations.acceptingEverything();

            // Every messaging method other than send(Consumer) refuses to run, and every send option
            // other than the four the legacy contract needs refuses to be set, so this completing at all
            // is the assertion: the production code touched none of them.
            assertThatCode(() -> serviceOver(queue).submitTransactionReportJob(START_DATE, END_DATE))
                    .doesNotThrowAnyException();

            assertThat(queue.published())
                    .as("only the four contractual options are set on each message")
                    .hasSize(EXPECTED_CARD_COUNT)
                    .allSatisfy(attempt -> {
                        assertThat(attempt.queue()).isNotNull();
                        assertThat(attempt.payload()).isNotNull();
                        assertThat(attempt.messageGroupId()).isNotNull();
                        assertThat(attempt.deduplicationId()).isNotNull();
                    });
        }
    }

    @Nested
    @DisplayName("The submission result is a validated value, because the outcome has to travel back as "
            + "data rather than as an exception")
    class SubmissionResultInvariants {

        @Test
        @DisplayName("a successful result normalises its failure text to the empty string")
        void aSuccessfulResultNormalisesItsFailureTextToEmpty() {
            final JobSubmissionService.SubmissionResult result =
                    new JobSubmissionService.SubmissionResult(17, 17, false, "ignored text");

            assertThat(result.failureMessage()).isEmpty();
            assertThat(result.complete()).isTrue();
            assertThat(result.partial()).isFalse();
        }

        @Test
        @DisplayName("a failed result with no supplied text adopts the frozen operator-facing literal")
        void aFailedResultWithNoTextAdoptsTheFrozenLiteral() {
            final JobSubmissionService.SubmissionResult result =
                    new JobSubmissionService.SubmissionResult(17, 8, true, "");

            assertThat(result.failureMessage()).isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("a failed result never carries the text null, whatever it was constructed with")
        void aFailedResultNeverCarriesTheTextNull() {
            final JobSubmissionService.SubmissionResult result =
                    new JobSubmissionService.SubmissionResult(17, 0, true, null);

            assertThat(result.failureMessage())
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE)
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("a failed result keeps supplied text when text was supplied")
        void aFailedResultKeepsSuppliedText() {
            final JobSubmissionService.SubmissionResult result =
                    new JobSubmissionService.SubmissionResult(17, 3, true, "supplied text");

            assertThat(result.failureMessage()).isEqualTo("supplied text");
        }

        @Test
        @DisplayName("a negative requested count is refused")
        void aNegativeRequestedCountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(-1, 0, false, ""))
                    .withMessageContaining("cardsRequested");
        }

        @Test
        @DisplayName("a negative published count is refused")
        void aNegativePublishedCountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(17, -1, false, ""))
                    .withMessageContaining("cardsPublished");
        }

        @Test
        @DisplayName("publishing more cards than were requested is refused, because the counts describe "
                + "one stream")
        void publishingMoreThanRequestedIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(17, 18, false, ""))
                    .withMessageContaining("exceed");
        }

        @Test
        @DisplayName("the three distinguishable states are distinguishable: complete, partial, and failed "
                + "with nothing published")
        void theThreeDistinguishableStatesAreDistinguishable() {
            final JobSubmissionService.SubmissionResult complete =
                    new JobSubmissionService.SubmissionResult(17, 17, false, "");
            final JobSubmissionService.SubmissionResult partial =
                    new JobSubmissionService.SubmissionResult(17, 8, true, "");
            final JobSubmissionService.SubmissionResult nothingPublished =
                    new JobSubmissionService.SubmissionResult(17, 0, true, "");

            assertThat(complete.complete()).isTrue();
            assertThat(complete.partial()).isFalse();
            assertThat(partial.complete()).isFalse();
            assertThat(partial.partial()).isTrue();
            assertThat(nothingPublished.complete()).isFalse();
            assertThat(nothingPublished.partial())
                    .as("a failure before anything was published is not a partial submission")
                    .isFalse();
        }

        @Test
        @DisplayName("a stream that stopped on the end-of-stream marker is neither complete nor failed, "
                + "which is a fourth, legitimate state")
        void aStreamStoppedOnTheMarkerIsNeitherCompleteNorFailed() {
            final JobSubmissionService.SubmissionResult stoppedEarly =
                    new JobSubmissionService.SubmissionResult(3, 2, false, "");

            assertThat(stoppedEarly.failed()).isFalse();
            assertThat(stoppedEarly.complete()).isFalse();
            assertThat(stoppedEarly.partial()).isFalse();
            assertThat(stoppedEarly.failureMessage()).isEmpty();
        }
    }
}
