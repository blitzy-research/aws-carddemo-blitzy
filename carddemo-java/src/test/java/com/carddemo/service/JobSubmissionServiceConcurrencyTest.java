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

import static org.assertj.core.api.Assertions.assertThat;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.messaging.support.GenericMessage;

/**
 * Proves that two submissions published at the same time reach the queue as two contiguous runs rather
 * than one interleaving.
 *
 * <h2>The defect this holds closed</h2>
 *
 * <p>A submission is not one message. It is {@code JclCardImageBuilder.CARD_COUNT} messages that the
 * batch tier reads back as a single eighty-column job stream, so their contiguity and their order are
 * the contract rather than a nicety: a job card followed by a different submission's library card is
 * not a degraded stream, it is one no reader can parse.
 *
 * <p>Nothing in the queue's own behaviour supplies that contiguity. Every card of every submission
 * carries the <em>same</em> message group - which is exactly what preserves the append ordering the
 * legacy transient-data queue guaranteed - so a first-in-first-out queue faithfully records whatever
 * order the sends arrived in, and faithfully records an interleaving too. The publisher is a singleton,
 * so two request threads submitting concurrently are two threads inside one emitting loop.
 * Deduplication identifiers do not help: they make a <em>retry</em> of one submission idempotent and
 * say nothing about two distinct submissions, whose identifiers are all distinct and all accepted.
 *
 * <h2>Why the test is written this way</h2>
 *
 * <p>A test that merely started two threads and hoped they collided would pass on a fast machine
 * whether or not the exclusion existed, which is worse than no test. So the queue double is made
 * <strong>deliberately hostile</strong>: it blocks the very first send of the first submission until
 * the second submission's thread has certainly been released and has had its chance to run. With no
 * exclusion the second stream's cards land in the middle of the first's and the contiguity assertion
 * fails; with exclusion the second thread waits at the lock and the two runs come out whole.
 *
 * <p>The exclusion the second thread waits at is observed rather than assumed: the coordinator this
 * test builds reports when a caller is enqueued on it, and every proof of non-entry waits for that state
 * instead of sleeping. A sleep followed by "not finished yet" is satisfied by a thread the scheduler has
 * not run, so it would pass with the exclusion deleted.
 *
 * <p>The assertion is on the recorded order of arrival at the queue - which is what a real drain would
 * read back - and it is expressed as contiguity of each submission's ordinals rather than as an
 * expected sequence, because either submission may legitimately be first.
 */
@DisplayName("JobSubmissionService - two submissions at once are two streams, not one interleaving")
class JobSubmissionServiceConcurrencyTest {

    /** The configured destination, a bare first-in-first-out queue name. */
    private static final String QUEUE_NAME = "JOBS.fifo";

    /** The one stable message group every card carries, which is why order is preserved at all. */
    private static final String MESSAGE_GROUP = "carddemo-job-submission";

    /** Identity of the first submission, and the prefix of every card it publishes. */
    private static final String FIRST_SUBMISSION = "submission-A";

    /** Identity of the second submission. */
    private static final String SECOND_SUBMISSION = "submission-B";

    /** Number of cards in each stream under test, matching the legacy card count. */
    private static final int CARDS_PER_SUBMISSION = com.carddemo.util.JclCardImageBuilder.CARD_COUNT;

    /** The fixed record width every card occupies. */
    private static final int CARD_WIDTH = com.carddemo.util.JclCardImageBuilder.CARD_IMAGE_WIDTH;

    /** How long a barrier or a thread is waited on before the test gives up and fails. */
    private static final long WAIT_SECONDS = 10L;

    /** Constructs the fixture. */
    JobSubmissionServiceConcurrencyTest() {
    }

    /**
     * Builds a stream of the contractual width whose every card is attributable to one submission.
     *
     * <p>The identity is written into each card so that the recorded arrival order can be attributed
     * without consulting the deduplication identifier, and the final card carries the end-of-stream
     * sentinel because the emitting loop stops on it.
     *
     * @param submissionId the identity to stamp on every card
     * @return the ordered stream
     */
    private static List<String> streamFor(final String submissionId) {
        final List<String> cards = new ArrayList<>(CARDS_PER_SUBMISSION);
        for (int ordinal = 1; ordinal < CARDS_PER_SUBMISSION; ordinal++) {
            cards.add(pad(submissionId + "#" + ordinal));
        }
        cards.add(pad(com.carddemo.util.JclCardImageBuilder.EOF_SENTINEL_CARD + submissionId));
        return List.copyOf(cards);
    }

    /**
     * Extends a value to the fixed card width with spaces.
     *
     * @param value the value to extend
     * @return exactly {@link #CARD_WIDTH} characters
     */
    private static String pad(final String value) {
        return value + " ".repeat(CARD_WIDTH - value.length());
    }

    /**
     * A plausible successful send outcome; only its presence is read.
     *
     * @return the outcome
     */
    private static SendResult<Object> successfulSend() {
        return new SendResult<>(UUID.randomUUID(), QUEUE_NAME,
                new GenericMessage<>("published"), Map.of());
    }

    /**
     * A queue double that records the order cards arrive in and can be made to block one send.
     *
     * <p>The queue interface itself is mocked rather than implemented, because it declares five
     * operations of which this test drives one; hand-implementing the other four would be four
     * unreachable method bodies. The recording behaviour that matters is supplied as the answer to the
     * single operation the publisher uses, and it synchronises on this object, so the recorded list is
     * the true arrival order however many threads are publishing.
     */
    private static final class RecordingQueue {

        /** The mocked queue the service is constructed over. */
        private final SqsOperations operations = org.mockito.Mockito.mock(SqsOperations.class);

        /** Payloads in the order the queue received them. */
        private final List<String> received = new ArrayList<>();

        /** Released by the test once the second submitter has been started. */
        private final CountDownLatch releaseFirstSend = new CountDownLatch(1);

        /** Counted down as soon as the first send is entered, so the test knows publishing began. */
        private final CountDownLatch firstSendEntered = new CountDownLatch(1);

        /** Whether the blocking behaviour is armed. */
        private volatile boolean blockFirstSend;

        /** Installs the recording answer on the one operation the publisher drives. */
        RecordingQueue() {
            org.mockito.Mockito.when(this.operations.send(anySendConfigurer()))
                    .thenAnswer(invocation -> {
                        final Consumer<SqsSendOptions<Object>> configurer = invocation.getArgument(0);
                        final CapturedSend<Object> captured = new CapturedSend<>();
                        configurer.accept(captured);
                        // Recorded BEFORE the hold, not after. The order under test is the order cards
                        // ARRIVE at the queue, which is what a real drain reads back; the hold models a
                        // slow acknowledgement of a card that has already landed. Recording after the
                        // hold would move the held card to the end of the recorded order and quietly
                        // repair the very interleaving this test exists to observe.
                        record(String.valueOf(captured.payload));
                        holdIfArmed();
                        return successfulSend();
                    });
        }

        /**
         * Holds the calling thread inside its emitting loop when the block is armed.
         *
         * <p>This is what turns a race that might happen into one that certainly would: the first
         * submission is stopped at its first send until the test has started the second submitter and
         * given it time to run, so an absent exclusion is observed rather than hoped for.
         */
        private void holdIfArmed() throws InterruptedException {
            if (!this.blockFirstSend) {
                return;
            }
            this.blockFirstSend = false;
            this.firstSendEntered.countDown();
            if (!this.releaseFirstSend.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the first send");
            }
        }

        /**
         * Records one arrival.
         *
         * @param payload the payload the queue received
         */
        private synchronized void record(final String payload) {
            this.received.add(payload);
        }

        /**
         * Returns a snapshot of the arrival order.
         *
         * @return the payloads received, in order
         */
        private synchronized List<String> receivedInOrder() {
            return List.copyOf(this.received);
        }
    }

    /**
     * A typed matcher for the fluent send configurer, keeping generics explicit so no unchecked
     * conversion is needed.
     *
     * @param <T> payload type the options carry
     * @return the matcher
     */
    private static <T> Consumer<SqsSendOptions<T>> anySendConfigurer() {
        return org.mockito.ArgumentMatchers.any();
    }

    /**
     * Captures the fluent send options so the payload can be recorded.
     *
     * @param <T> payload type the options carry
     */
    private static final class CapturedSend<T> implements SqsSendOptions<T> {

        /** The payload the caller supplied. */
        private T payload;

        @Override
        public SqsSendOptions<T> queue(final String value) {
            return this;
        }

        @Override
        public SqsSendOptions<T> payload(final T value) {
            this.payload = value;
            return this;
        }

        @Override
        public SqsSendOptions<T> header(final String name, final Object value) {
            return this;
        }

        @Override
        public SqsSendOptions<T> headers(final Map<String, Object> values) {
            return this;
        }

        @Override
        public SqsSendOptions<T> delaySeconds(final Integer value) {
            return this;
        }

        @Override
        public SqsSendOptions<T> messageGroupId(final String value) {
            return this;
        }

        @Override
        public SqsSendOptions<T> messageDeduplicationId(final String value) {
            return this;
        }
    }

    /**
     * Reports the one-based positions, in arrival order, of the cards belonging to one submission.
     *
     * @param arrived      the recorded arrival order
     * @param submissionId the identity stamped on the cards of interest
     * @return the positions, ascending
     */
    private static List<Integer> positionsOf(final List<String> arrived, final String submissionId) {
        final List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < arrived.size(); index++) {
            if (arrived.get(index).contains(submissionId)) {
                positions.add(index + 1);
            }
        }
        return positions;
    }

    /**
     * Asserts that a submission's cards occupy an unbroken run of positions.
     *
     * @param positions    the positions the submission's cards occupied
     * @param submissionId the submission, for the failure message
     */
    private static void assertContiguous(final List<Integer> positions, final String submissionId) {
        assertThat(positions)
                .as("%s must have published every card", submissionId)
                .hasSize(CARDS_PER_SUBMISSION);
        for (int index = 1; index < positions.size(); index++) {
            assertThat(positions.get(index))
                    .as("%s must occupy an unbroken run: card %d landed at position %d while the "
                            + "previous card landed at %d, so another submission's card came between "
                            + "them and the job stream the batch tier reads back is unparseable",
                            submissionId, index + 1, positions.get(index), positions.get(index - 1))
                    .isEqualTo(positions.get(index - 1) + 1);
        }
    }

    /**
     * A coordinator that serializes submissions and can be <em>asked</em> whether a caller is waiting.
     *
     * <h3>Why the observability matters</h3>
     *
     * <p>A test that wants to prove a second caller was excluded has to establish that the second caller
     * actually reached the exclusion. Sleeping and then finding it unfinished does not establish that: a
     * thread the scheduler never ran is also unfinished, so the observation passes on a loaded machine even
     * with the exclusion deleted. {@link ReentrantLock#hasQueuedThreads()} answers the question directly -
     * it is true exactly when a thread is enqueued on the lock, which is the boundary the contender has to
     * have reached - so the wait below is a wait for a <em>state</em> rather than for a duration.
     */
    private static final class SerializingCoordinator implements JobSubmissionCoordinator {

        /** The exclusion itself, fair so that a queued caller is admitted in arrival order. */
        private final ReentrantLock lock = new ReentrantLock(true);

        /** Creates the coordinator. */
        SerializingCoordinator() {
            // Intentionally empty: the lock is the whole of the state.
        }

        @Override
        public JobSubmissionService.SubmissionResult serialize(
                final Supplier<JobSubmissionService.SubmissionResult> submission) {
            this.lock.lock();
            try {
                return submission.get();
            } finally {
                this.lock.unlock();
            }
        }

        /**
         * Waits until at least one caller is provably enqueued on the lock.
         *
         * <p>Polls a monotone condition rather than sleeping for a guess: once a thread is enqueued it
         * stays enqueued until the holder releases, so the only thing that varies between machines is how
         * soon the condition is observed, never whether it holds. A machine so loaded that the contender
         * never runs at all fails here with a message that says so, instead of passing as though the
         * exclusion had been observed.
         *
         * @throws InterruptedException if the wait is interrupted
         */
        void awaitAContenderEnqueued() throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (!this.lock.hasQueuedThreads()) {
                assertThat(System.nanoTime() < deadline)
                        .as("no caller reached the exclusion within %d seconds, so nothing about "
                                + "exclusion can be concluded from what follows", WAIT_SECONDS)
                        .isTrue();
                TimeUnit.MILLISECONDS.sleep(5L);
            }
        }
    }

    /**
     * Builds the service over a test-owned coordinator. Production uses the PostgreSQL advisory-lock
     * implementation; this focused unit test supplies the same whole-stream contract without booting a
     * database.
     */
    private static JobSubmissionService serviceOver(final RecordingQueue queue,
            final SerializingCoordinator coordinator) {
        return new JobSubmissionService(queue.operations, QUEUE_NAME, MESSAGE_GROUP, coordinator,
                ObservationRegistry.NOOP);
    }

    @Nested
    @DisplayName("two submissions published at once")
    class TwoSubmissionsAtOnce {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("each set of seventeen cards arrives as one contiguous run, even when the first "
                + "submission is deliberately held inside its emitting loop")
        void eachSubmissionArrivesContiguously() throws Exception {
            final RecordingQueue queue = new RecordingQueue();
            final SerializingCoordinator coordinator = new SerializingCoordinator();
            final JobSubmissionService service = serviceOver(queue, coordinator);
            queue.blockFirstSend = true;

            final ExecutorService submitters = Executors.newFixedThreadPool(2);
            try {
                final var first = submitters.submit(() ->
                        service.submitJobStream(FIRST_SUBMISSION, streamFor(FIRST_SUBMISSION)));

                // Wait until the first submission is provably inside its loop and blocked, so the
                // second submitter starts against a submission in progress rather than a finished one.
                assertThat(queue.firstSendEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                        .as("the first submission must have entered its first send")
                        .isTrue();

                final var second = submitters.submit(() ->
                        service.submitJobStream(SECOND_SUBMISSION, streamFor(SECOND_SUBMISSION)));

                // THE SECOND SUBMITTER IS OBSERVED AT THE EXCLUSION, not merely given time to reach it.
                // A sleep here would have proved nothing: a contender the scheduler had not yet run is
                // also one that has published no card, so the contiguity below would have held on a
                // loaded machine with the exclusion deleted. Waiting until the coordinator reports a
                // queued caller establishes that the contender arrived, tried, and is being held - which
                // is the state whose consequences the assertions after this describe.
                coordinator.awaitAContenderEnqueued();
                assertThat(second.isDone())
                        .as("the contender reached the exclusion and must still be held by it, because "
                                + "the first submission has not left its first send")
                        .isFalse();
                queue.releaseFirstSend.countDown();

                final JobSubmissionService.SubmissionResult firstResult =
                        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
                final JobSubmissionService.SubmissionResult secondResult =
                        second.get(WAIT_SECONDS, TimeUnit.SECONDS);

                assertThat(firstResult.failed()).isFalse();
                assertThat(secondResult.failed()).isFalse();
                assertThat(firstResult.cardsPublished()).isEqualTo(CARDS_PER_SUBMISSION);
                assertThat(secondResult.cardsPublished()).isEqualTo(CARDS_PER_SUBMISSION);
            } finally {
                queue.releaseFirstSend.countDown();
                submitters.shutdownNow();
                assertThat(submitters.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }

            final List<String> arrived = queue.receivedInOrder();
            assertThat(arrived)
                    .as("both submissions must have reached the queue in full")
                    .hasSize(2 * CARDS_PER_SUBMISSION);
            assertContiguous(positionsOf(arrived, FIRST_SUBMISSION), FIRST_SUBMISSION);
            assertContiguous(positionsOf(arrived, SECOND_SUBMISSION), SECOND_SUBMISSION);
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("within each contiguous run the cards keep their own order, so the exclusion "
                + "preserves the append ordering rather than only separating the streams")
        void eachRunKeepsItsOwnCardOrder() throws Exception {
            final RecordingQueue queue = new RecordingQueue();
            final JobSubmissionService service = serviceOver(queue, new SerializingCoordinator());

            final ExecutorService submitters = Executors.newFixedThreadPool(4);
            try {
                final List<java.util.concurrent.Future<JobSubmissionService.SubmissionResult>> runs =
                        new ArrayList<>();
                for (int submitter = 0; submitter < 4; submitter++) {
                    final String identity = "submission-" + submitter;
                    runs.add(submitters.submit(() ->
                            service.submitJobStream(identity, streamFor(identity))));
                }
                for (final var run : runs) {
                    assertThat(run.get(WAIT_SECONDS, TimeUnit.SECONDS).cardsPublished())
                            .isEqualTo(CARDS_PER_SUBMISSION);
                }
            } finally {
                submitters.shutdown();
                assertThat(submitters.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }

            final List<String> arrived = queue.receivedInOrder();
            assertThat(arrived).hasSize(4 * CARDS_PER_SUBMISSION);
            for (int submitter = 0; submitter < 4; submitter++) {
                final String identity = "submission-" + submitter;
                assertContiguous(positionsOf(arrived, identity), identity);

                final List<String> ownCards = arrived.stream()
                        .filter(card -> card.contains(identity))
                        .toList();
                assertThat(ownCards)
                        .as("%s must publish its own cards in the order it supplied them", identity)
                        .containsExactlyElementsOf(streamFor(identity));
            }
        }
    }
}
