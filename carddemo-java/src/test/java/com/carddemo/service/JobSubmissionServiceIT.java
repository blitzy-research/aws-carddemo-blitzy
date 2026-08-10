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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.support.AbstractLocalStackIT;
import com.carddemo.util.JclCardImageBuilder;

import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * Verifies the batch-trigger interface contract against a real first-in-first-out queue.
 *
 * <h2>What is under test</h2>
 * {@link JobSubmissionService} replaces the one online-to-batch bridge the legacy estate has:
 * the transient-data queue write to JOBS, performed card by card by the misspelled
 * {@code WIRTE-JOBSUB-TDQ} paragraph of {@code app/cbl/CORPT00C.cbl} at line 515. The queue that
 * paragraph writes to is defined in {@code app/csd/CARDDEMO.CSD} as
 * {@code RECORDSIZE(80) RECORDFORMAT(FIXED) DISPOSITION(MOD) ERROROPTION(IGNORE)}, and each of those
 * four attributes is a term of the contract asserted here: eighty bytes per record, fixed rather than
 * variable, appended in order, and a failed write that is ignored rather than raised.
 *
 * <h2>Why this test drives a real emulator instead of a recording double</h2>
 * The acceptance criterion for an external interface is that the contract is exercised, not
 * self-certified. A recorded argument proves the caller intended to publish a value; it cannot prove
 * the service accepted it, preserved the order of the group, or preserved the bytes of the body.
 * Every assertion in the first two groups below is therefore made against messages read back out of
 * a queue that a real service created, accepted into, and delivered from - no stand-in participates
 * in the successful path at all.
 *
 * <h2>Why the last group does use an injected fault</h2>
 * A real service cannot be asked to fail on the twelfth message and succeed on the eleven before it,
 * and the legacy behaviour that must be proven is precisely about what happens to the cards
 * <em>after</em> a failed write. The fault is injected at the one call the service makes, while the
 * cards before it travel through the real template into the real queue and are read back from it, so
 * the assertion that eleven messages arrived and six were never sent is still made against the
 * service's own state rather than against a recording. The absent-queue test that opens the group
 * needs no injection: it is a genuine service refusal.
 *
 * <h2>Why the date slots are restated rather than taken from the builder</h2>
 * Comparing the drained bodies with {@code JclCardImageBuilder.build} alone would prove the queue is
 * a faithful pipe but would let a defect in the substituted slots pass unnoticed, because both sides
 * of the comparison would carry it. The three cards that carry the four slots are therefore also
 * asserted against hand-written images assembled here from independent literals.
 *
 * <p>Provenance: {@code app/cbl/CORPT00C.cbl} lines 84 to 125 and 462 to 535, and
 * {@code app/csd/CARDDEMO.CSD}, taken from checkout
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release stamp
 * {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.</p>
 */
@DisplayName("job-submission bridge, verified against a real first-in-first-out queue")
class JobSubmissionServiceIT extends AbstractLocalStackIT {

    /** The canonical queue name, exactly as {@code application.yml} declares it. */
    private static final String QUEUE_NAME = "JOBS.fifo";

    /** The canonical message group, exactly as {@code application.yml} declares it. */
    private static final String MESSAGE_GROUP_ID = "carddemo-job-submission";

    /**
     * The message attribute the messaging framework's own payload converter stamps on every message it
     * sends, naming the Java type of the body.
     *
     * <p>Named here because an assertion about the exact set of attributes on a delivered message has to
     * account for it: it is present on every message, it is not set by this module, and it was present
     * before the reassembly envelope existed. Asserting the set rather than only the presence of the
     * three envelope attributes is what makes a fourth attribute of <em>our</em> making fail here, and
     * that assertion is only possible if the framework's attribute is named rather than tolerated by a
     * looser matcher. The corresponding unit-level guard observes the send options instead of the
     * delivered message, so it sees the three and not this one.
     */
    private static final String FRAMEWORK_PAYLOAD_TYPE_ATTRIBUTE = "JavaType";

    /**
     * A well-formed queue name that names no queue on the emulator, used to reach the ignore-on-error
     * path through a genuine queue-service refusal with no fault injected.
     *
     * <p>This constant used to be a name carrying embedded spaces, because the messaging library's
     * default for an unresolvable queue is to create it, and a name the queue service refused
     * outright was the shortest way to a failed write. That is no longer available and the change is
     * an improvement rather than a loss: the producer now holds its configured destination to the
     * same naming contract the bootstrap script applies, so a name with a space cannot be configured
     * at all and can no longer be absorbed into the tolerated failure path. The refusal is obtained
     * instead from the queue service, by publishing through a template whose queue-not-found strategy
     * is refusal rather than creation - which is also how the shipped configuration behaves.</p>
     *
     * <p>The randomised element keeps the name absent even if a previous run created it, and
     * {@code JobSubmissionQueueBridgeIT} verifies separately that nothing is created behind the
     * refusal.</p>
     */
    private static final String ABSENT_QUEUE_NAME =
            "JOBS-absent-" + UUID.randomUUID() + ".fifo";

    /** Card count the legacy paragraph transmits, sentinel included. */
    private static final int CARD_COUNT = 17;

    /** Record width the queue definition declares. */
    private static final int CARD_WIDTH = 80;

    /** Width of one substituted date slot. */
    private static final int DATE_SLOT_WIDTH = 10;

    /** Reporting-period start, of the shape the report screen collects. */
    private static final String START_DATE = "2022-01-01";

    /** Reporting-period end, of the shape the report screen collects. */
    private static final String END_DATE = "2022-07-06";

    /** One-based ordinal of the card carrying the first start-date slot. */
    private static final int CARD_START_DATE_SYMBOL = 11;

    /** One-based ordinal of the card carrying the first end-date slot. */
    private static final int CARD_END_DATE_SYMBOL = 12;

    /** One-based ordinal of the card carrying the second start-date and end-date slots. */
    private static final int CARD_DATE_PARAMETER = 15;

    /** Leading text of the start-date symbol card, restated independently of production code. */
    private static final String START_SYMBOL_LEAD = "PARM-START-DATE,C'";

    /** Leading text of the end-date symbol card, restated independently of production code. */
    private static final String END_SYMBOL_LEAD = "PARM-END-DATE,C'";

    /** Spaces following the closing quotation mark on the start-date symbol card. */
    private static final int START_SYMBOL_TRAILING_SPACES = 51;

    /** Spaces following the closing quotation mark on the end-date symbol card. */
    private static final int END_SYMBOL_TRAILING_SPACES = 53;

    /** Spaces between the two slots on the date-parameter card. */
    private static final int DATE_PARAMETER_SEPARATOR_SPACES = 1;

    /** Spaces following the second slot on the date-parameter card. */
    private static final int DATE_PARAMETER_TRAILING_SPACES = 59;

    /** The sentinel the legacy paragraph transmits as its final card. */
    private static final String SENTINEL_CONTENT = "/*EOF";

    /** Ordinal after which the injected fault begins refusing. */
    private static final int CARDS_BEFORE_INJECTED_FAULT = 11;

    /** Cards the injected fault leaves unsent. */
    private static final int CARDS_NEVER_SENT = CARD_COUNT - CARDS_BEFORE_INJECTED_FAULT;

    /** URL of the canonical queue, created once for the whole class. */
    private static String queueUrl;

    /** The service under test, wired to the real template over the emulator. */
    private JobSubmissionService service;

    @BeforeAll
    static void createCanonicalQueue() {
        queueUrl = createFifoQueue(QUEUE_NAME);
    }

    @BeforeEach
    void createService() {
        this.service = new JobSubmissionService(
                SqsTemplate.newSyncTemplate(sqsAsyncClient()), QUEUE_NAME, MESSAGE_GROUP_ID);
    }

    @AfterEach
    void emptyQueue() {
        purgeQueue(queueUrl);
    }

    @Nested
    @DisplayName("the published stream, read back out of the queue")
    class PublishedStream {

        @Test
        @DisplayName("delivers exactly seventeen messages, the sentinel included")
        void deliversSeventeenMessages() {
            service.submitTransactionReportJob(submissionId("count"), START_DATE, END_DATE);

            assertThat(drainBodies()).hasSize(CARD_COUNT);
        }

        @Test
        @DisplayName("delivers every body at exactly eighty encoded bytes")
        void everyBodyIsExactlyEightyEncodedBytes() {
            service.submitTransactionReportJob(submissionId("width"), START_DATE, END_DATE);

            final List<String> bodies = drainBodies();
            for (int ordinal = 1; ordinal <= CARD_COUNT; ordinal++) {
                assertThat(usAsciiLength(bodies.get(ordinal - 1)))
                        .as("encoded byte width of delivered card " + ordinal)
                        .isEqualTo(CARD_WIDTH);
            }
        }

        @Test
        @DisplayName("delivers the cards in submission order, byte for byte")
        void deliversTheCardsInSubmissionOrder() {
            service.submitTransactionReportJob(submissionId("order"), START_DATE, END_DATE);

            assertThat(drainBodies())
                    .containsExactlyElementsOf(JclCardImageBuilder.build(START_DATE, END_DATE));
        }

        @Test
        @DisplayName("carries all four substituted date slots into the delivered cards")
        void carriesAllFourDateSlots() {
            service.submitTransactionReportJob(submissionId("slots"), START_DATE, END_DATE);

            final List<String> bodies = drainBodies();

            assertThat(bodies.get(CARD_START_DATE_SYMBOL - 1))
                    .as("delivered card 11, the first start-date slot")
                    .isEqualTo(expectedStartSymbolCard());
            assertThat(bodies.get(CARD_END_DATE_SYMBOL - 1))
                    .as("delivered card 12, the first end-date slot")
                    .isEqualTo(expectedEndSymbolCard());
            assertThat(bodies.get(CARD_DATE_PARAMETER - 1))
                    .as("delivered card 15, the second start-date and end-date slots")
                    .isEqualTo(expectedDateParameterCard());
        }

        @Test
        @DisplayName("transmits the end-of-stream sentinel as the final message")
        void transmitsTheSentinelLast() {
            service.submitTransactionReportJob(submissionId("sentinel"), START_DATE, END_DATE);

            final String finalBody = drainBodies().get(CARD_COUNT - 1);

            assertThat(finalBody).startsWith(SENTINEL_CONTENT);
            assertThat(finalBody.substring(SENTINEL_CONTENT.length())).isBlank();
            assertThat(usAsciiLength(finalBody)).isEqualTo(CARD_WIDTH);
        }

        @Test
        @DisplayName("places every card of one submission in the single declared message group")
        void placesEveryCardInOneMessageGroup() {
            service.submitTransactionReportJob(submissionId("group"), START_DATE, END_DATE);

            final List<Message> messages = drainQueue(queueUrl, CARD_COUNT);

            assertThat(messages).hasSize(CARD_COUNT);
            assertThat(messages)
                    .extracting(message -> message.attributes()
                            .get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
                    .containsOnly(MESSAGE_GROUP_ID);
        }

        @Test
        @DisplayName("stamps a distinct deduplication identifier on every card of a submission")
        void stampsADistinctDeduplicationIdentifierPerCard() {
            service.submitTransactionReportJob(submissionId("dedup"), START_DATE, END_DATE);

            final List<Message> messages = drainQueue(queueUrl, CARD_COUNT);

            assertThat(messages).hasSize(CARD_COUNT);
            assertThat(messages)
                    .extracting(message -> message.attributes()
                            .get(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID))
                    .doesNotContainNull()
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("delivers the reassembly envelope on every card, so a consumer restores the job "
                + "stream by grouping and ordering rather than by trusting arrival order")
        void deliversTheReassemblyEnvelopeOnEveryCard() {
            // The process-local submission lock cannot stop two instances from interleaving their cards
            // in the one message group, and this module adds no coordination service. The envelope is
            // the remedy, so it has to survive the real transport rather than only the send options: an
            // attribute the queue service dropped would leave a consumer with no way to undo an
            // interleaving.
            final String submission = submissionId("envelope");

            service.submitTransactionReportJob(submission, START_DATE, END_DATE);

            final List<Message> messages = drainQueue(queueUrl, CARD_COUNT);
            assertThat(messages).hasSize(CARD_COUNT);
            for (int ordinal = 1; ordinal <= CARD_COUNT; ordinal++) {
                final Map<String, MessageAttributeValue> envelope =
                        messages.get(ordinal - 1).messageAttributes();
                assertThat(envelope)
                        .as("card %s carries the three envelope attributes and, from the messaging "
                                + "framework's own converter, the payload type attribute - and nothing "
                                + "else", ordinal)
                        .containsOnlyKeys(JobSubmissionService.SUBMISSION_ID_HEADER,
                                JobSubmissionService.CARD_ORDINAL_HEADER,
                                JobSubmissionService.CARD_COUNT_HEADER,
                                FRAMEWORK_PAYLOAD_TYPE_ATTRIBUTE);
                assertThat(envelope.get(JobSubmissionService.SUBMISSION_ID_HEADER).stringValue())
                        .as("card %s names its submission", ordinal)
                        .isEqualTo(submission);
                assertThat(envelope.get(JobSubmissionService.CARD_ORDINAL_HEADER).stringValue())
                        .as("card %s carries its one-based position", ordinal)
                        .isEqualTo(Integer.toString(ordinal));
                assertThat(envelope.get(JobSubmissionService.CARD_COUNT_HEADER).stringValue())
                        .as("card %s declares the whole stream's length", ordinal)
                        .isEqualTo(Integer.toString(CARD_COUNT));
            }
        }

        @Test
        @DisplayName("the envelope reassembles two submissions whose cards were published by two "
                + "different threads, which is the interleaving no process-local lock can prevent "
                + "between instances")
        void theEnvelopeReassemblesTwoInterleavedSubmissions() {
            // Published deliberately interleaved, card by card, through the single-card entry point:
            // that is what two instances publishing at once produces, and no lock in this process would
            // have prevented it. The assertion is that grouping by the envelope's submission attribute
            // and ordering by its ordinal restores both streams exactly, whatever order the queue
            // delivered them in.
            final String firstSubmission = submissionId("interleaved-a");
            final String secondSubmission = submissionId("interleaved-b");
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            for (int ordinal = 1; ordinal <= CARD_COUNT; ordinal++) {
                assertThat(service.writeJobSubmissionQueue(firstSubmission, cards.get(ordinal - 1),
                        ordinal)).isTrue();
                assertThat(service.writeJobSubmissionQueue(secondSubmission, cards.get(ordinal - 1),
                        ordinal)).isTrue();
            }

            final List<Message> delivered = drainQueue(queueUrl, 2 * CARD_COUNT);
            assertThat(delivered).hasSize(2 * CARD_COUNT);

            final Map<String, List<Message>> bySubmission = new LinkedHashMap<>();
            for (final Message message : delivered) {
                bySubmission.computeIfAbsent(message.messageAttributes()
                                .get(JobSubmissionService.SUBMISSION_ID_HEADER).stringValue(),
                        key -> new ArrayList<>()).add(message);
            }

            assertThat(bySubmission)
                    .as("the envelope separates the two streams the one message group carried together")
                    .containsOnlyKeys(firstSubmission, secondSubmission);
            bySubmission.forEach((submission, stream) -> {
                stream.sort(Comparator.comparingInt(message -> Integer.parseInt(
                        message.messageAttributes()
                                .get(JobSubmissionService.CARD_ORDINAL_HEADER).stringValue())));
                assertThat(stream).extracting(Message::body)
                        .as("submission %s reassembles to the whole ordered job stream", submission)
                        .containsExactlyElementsOf(cards);
            });
        }

        @Test
        @DisplayName("reports a complete submission of seventeen cards")
        void reportsACompleteSubmission() {
            final JobSubmissionService.SubmissionResult result =
                    service.submitTransactionReportJob(submissionId("result"), START_DATE, END_DATE);

            assertThat(result.cardsRequested()).isEqualTo(CARD_COUNT);
            assertThat(result.cardsPublished()).isEqualTo(CARD_COUNT);
            assertThat(result.failed()).isFalse();
            assertThat(result.failureMessage()).isEmpty();
            assertThat(result.complete()).isTrue();
            assertThat(result.partial()).isFalse();
        }
    }

    @Nested
    @DisplayName("submission identity, as the real queue enforces it")
    class SubmissionIdentity {

        @Test
        @DisplayName("reusing the identity returned by the first attempt adds no second copy")
        void aTrueRetryAddsNoSecondCopy() {
            final JobSubmissionService.SubmissionResult first =
                    service.submitTransactionReportJob(START_DATE, END_DATE);
            assertThat(drainBodies()).hasSize(CARD_COUNT);

            service.submitTransactionReportJob(first.submissionId(), START_DATE, END_DATE);

            assertThat(drainBodies())
                    .as("a resend under the same identity is the retry of one submission,"
                            + " not a second submission")
                    .isEmpty();
        }

        @Test
        @DisplayName("two new requests for the same period mint distinct identities and both are accepted")
        void twoNewRequestsForTheSamePeriodAreAcceptedInFull() {
            final JobSubmissionService.SubmissionResult first =
                    service.submitTransactionReportJob(START_DATE, END_DATE);
            assertThat(drainBodies()).hasSize(CARD_COUNT);

            final JobSubmissionService.SubmissionResult second =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(drainBodies())
                    .as("a second legitimate submission of the same reporting period must not be"
                            + " mistaken for a duplicate")
                    .hasSize(CARD_COUNT);
            assertThat(second.submissionId()).isNotEqualTo(first.submissionId());
        }

        @Test
        @DisplayName("a second REQUEST for one period is accepted in full against the real queue, "
                + "because the identity the bridge mints for it is not the first one's")
        void aSecondRequestForOnePeriodIsAcceptedInFull() {
            // The two tests above supply the identity, which proves what the queue does with a repeated
            // and with a distinct one. This one supplies none, so the bridge's own minting decides -
            // and that is the decision the defect turned the wrong way. Both of the two deduplication
            // windows are covered by the pair, as far as a test can cover them:
            //
            //   INSIDE the interval, which is where these two submissions land, being back to back: a
            //   reused identity is collapsed (proved above) and a newly minted one is not (proved here).
            //   So a legitimate second request is never suppressed, and a deliberate retry always is.
            //
            //   AFTER the interval, deduplication no longer applies to anything, so a retry re-lands the
            //   cards that already landed. That is now a consequence of the caller choosing to reuse an
            //   identity rather than of the bridge deriving one, which is the whole of the change: the
            //   queue service's interval cannot be advanced from a test, so what is asserted is the
            //   property that makes the lapsed case a decision instead of an accident.
            final JobSubmissionService.SubmissionResult first =
                    service.submitTransactionReportJob(START_DATE, END_DATE);
            assertThat(drainBodies()).hasSize(CARD_COUNT);

            final JobSubmissionService.SubmissionResult second =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(second.submissionId())
                    .as("the bridge minted a different identity for the second request")
                    .isNotEqualTo(first.submissionId());
            assertThat(drainBodies())
                    .as("so the real queue accepted the second request in full rather than discarding "
                            + "it behind the first one's success")
                    .hasSize(CARD_COUNT);
        }

        @Test
        @DisplayName("retrying under the identity the outcome reported is collapsed by the real queue, "
                + "so an interrupted stream is completed rather than doubled")
        void retryingUnderTheReportedIdentityIsCollapsed() {
            final JobSubmissionService.SubmissionResult first =
                    service.submitTransactionReportJob(START_DATE, END_DATE);
            assertThat(drainBodies()).hasSize(CARD_COUNT);

            service.submitTransactionReportJob(first.submissionId(), START_DATE, END_DATE);

            assertThat(drainBodies())
                    .as("the retry names the identity the outcome reported, so every card of it is "
                            + "recognised as one already published")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("a failed write, which the queue definition says to ignore")
    class FailedWrite {

        @Test
        @DisplayName("a queue the service refuses fails the submission without raising to the caller")
        void aRefusedQueueFailsWithoutRaising() {
            final JobSubmissionService unreachableQueueService = new JobSubmissionService(
                    refusingTemplate(), ABSENT_QUEUE_NAME, MESSAGE_GROUP_ID);

            final JobSubmissionService.SubmissionResult result = unreachableQueueService
                    .submitTransactionReportJob(submissionId("refused"), START_DATE, END_DATE);

            assertThat(result.cardsRequested()).isEqualTo(CARD_COUNT);
            assertThat(result.cardsPublished()).isZero();
            assertThat(result.failed()).isTrue();
            assertThat(result.failureMessage()).isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
            assertThat(result.complete()).isFalse();
            assertThat(result.partial())
                    .as("nothing was published, so the submission is failed rather than partial")
                    .isFalse();
        }

        @Test
        @DisplayName("a failure part way through leaves the cards after it unsent")
        void aFailurePartWayThroughLeavesTheRestUnsent() {
            final JobSubmissionService faultingService = new JobSubmissionService(
                    faultAfter(CARDS_BEFORE_INJECTED_FAULT), QUEUE_NAME, MESSAGE_GROUP_ID);

            final JobSubmissionService.SubmissionResult result = faultingService
                    .submitTransactionReportJob(submissionId("mid-stream"), START_DATE, END_DATE);

            assertThat(result.cardsPublished()).isEqualTo(CARDS_BEFORE_INJECTED_FAULT);
            assertThat(result.failed()).isTrue();
            assertThat(result.partial()).isTrue();
            assertThat(drainBodies())
                    .as("the queue holds the cards written before the failure and none after it")
                    .containsExactlyElementsOf(JclCardImageBuilder.build(START_DATE, END_DATE)
                            .subList(0, CARDS_BEFORE_INJECTED_FAULT));
        }

        @Test
        @DisplayName("the cards left unsent are the sentinel and the five cards before it")
        void theCardsLeftUnsentAreTheTail() {
            final JobSubmissionService faultingService = new JobSubmissionService(
                    faultAfter(CARDS_BEFORE_INJECTED_FAULT), QUEUE_NAME, MESSAGE_GROUP_ID);

            faultingService.submitTransactionReportJob(
                    submissionId("mid-stream-tail"), START_DATE, END_DATE);

            final List<String> delivered = drainBodies();

            assertThat(CARD_COUNT - delivered.size()).isEqualTo(CARDS_NEVER_SENT);
            assertThat(delivered)
                    .as("the sentinel is never reached, so the batch tier sees no end of stream")
                    .noneMatch(body -> body.startsWith(SENTINEL_CONTENT));
        }
    }

    /**
     * Builds a template bound to the emulator that refuses an absent queue rather than creating it.
     *
     * @return a template configured the way the shipped application's template is
     */
    private static SqsTemplate refusingTemplate() {
        // The messaging library's default is to CREATE a queue it cannot resolve, which would turn an
        // absent destination into a successful publish and never reach the ignore-on-error path. The
        // shipped configuration fixes the strategy to refusal, so this template is configured the way
        // the application's is and the refusal that follows is the queue service's own.
        return SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient())
                .configure(options -> options.queueNotFoundStrategy(QueueNotFoundStrategy.FAIL))
                .build();
    }

    /**
     * Builds an operations facade that publishes through the real template until the given ordinal and
     * refuses every card after it.
     *
     * @param lastAcceptedOrdinal the last one-based ordinal that will be published
     * @return an operations facade that fails after the given ordinal
     */
    private static SqsOperations faultAfter(final int lastAcceptedOrdinal) {
        final SqsTemplate template = SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient())
                .build();
        final SqsOperations faulting = mock(SqsOperations.class);
        final AtomicInteger attempts = new AtomicInteger();
        when(faulting.<String>send(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() > lastAcceptedOrdinal) {
                throw SqsException.builder()
                        .message("injected transport failure for the interface contract test")
                        .build();
            }
            final Consumer<SqsSendOptions<String>> customizer = invocation.getArgument(0);
            return template.<String>send(customizer);
        });
        return faulting;
    }

    /**
     * Reads the queue and returns only the bodies, in delivery order.
     *
     * @return the delivered bodies
     */
    private static List<String> drainBodies() {
        final List<Message> messages = drainQueue(queueUrl, CARD_COUNT);
        final List<String> bodies = new ArrayList<>(messages.size());
        for (final Message message : messages) {
            bodies.add(message.body());
        }
        return bodies;
    }

    /**
     * Builds a submission identity unique to one test, so no test is deduplicated against another.
     *
     * @param label a short label naming the test
     * @return a submission identity
     */
    private static String submissionId(final String label) {
        return "IT-" + label + "-" + System.nanoTime();
    }

    /**
     * Assembles card eleven from independent literals.
     *
     * @return the expected eighty-byte image
     */
    private static String expectedStartSymbolCard() {
        return START_SYMBOL_LEAD + START_DATE + "'" + spaces(START_SYMBOL_TRAILING_SPACES);
    }

    /**
     * Assembles card twelve from independent literals.
     *
     * @return the expected eighty-byte image
     */
    private static String expectedEndSymbolCard() {
        return END_SYMBOL_LEAD + END_DATE + "'" + spaces(END_SYMBOL_TRAILING_SPACES);
    }

    /**
     * Assembles card fifteen from independent literals.
     *
     * @return the expected eighty-byte image
     */
    private static String expectedDateParameterCard() {
        return START_DATE + spaces(DATE_PARAMETER_SEPARATOR_SPACES) + END_DATE
                + spaces(DATE_PARAMETER_TRAILING_SPACES);
    }

    /**
     * Produces a run of spaces.
     *
     * @param count how many spaces; must not be negative
     * @return the run of spaces
     */
    private static String spaces(final int count) {
        return " ".repeat(count);
    }

    /**
     * Measures a value in the encoding the fixed-width contract is defined in.
     *
     * @param value the value to measure
     * @return the encoded byte width
     */
    private static int usAsciiLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Restates the declared slot width so a change to it fails a test rather than passing silently.
     *
     * @return the declared slot width
     */
    private static int declaredDateSlotWidth() {
        assertThat(usAsciiLength(START_DATE)).isEqualTo(DATE_SLOT_WIDTH);
        assertThat(usAsciiLength(END_DATE)).isEqualTo(DATE_SLOT_WIDTH);
        return DATE_SLOT_WIDTH;
    }

    @Test
    @DisplayName("the fixture dates occupy exactly the declared ten-column slot")
    void fixtureDatesOccupyTheDeclaredSlot() {
        assertThat(declaredDateSlotWidth()).isEqualTo(DATE_SLOT_WIDTH);
        assertThat(usAsciiLength(expectedStartSymbolCard())).isEqualTo(CARD_WIDTH);
        assertThat(usAsciiLength(expectedEndSymbolCard())).isEqualTo(CARD_WIDTH);
        assertThat(usAsciiLength(expectedDateParameterCard())).isEqualTo(CARD_WIDTH);
    }
}
