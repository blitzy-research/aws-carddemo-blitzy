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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.GenericMessage;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.util.JclCardImageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link JobSubmissionService}, the estate's only online-to-batch bridge.
 *
 * <h2>What this test guards</h2>
 *
 * <p>The legacy estate contains exactly one transient-data-queue write. It lives in the
 * transaction-report request program and hands a seventeen-card job stream from the online tier to
 * the batch tier. Everything about that hand-off is an <strong>external interface contract</strong>:
 * the eighty-byte fixed record, one message per card, the order the cards arrive in, the
 * transmitted end-of-stream sentinel, and the ignore-on-error behaviour that lets a failed write
 * report itself without aborting the operator's request. A single deviation breaks the batch
 * trigger, so all of it is asserted here rather than described.</p>
 *
 * <h2>Three security properties this test exists to pin</h2>
 *
 * <ol>
 *   <li><strong>Nothing hostile reaches a message body.</strong> The queue is the outer boundary of
 *       the bridge, and a card that carries a control byte or control-language punctuation would be
 *       reinterpreted by whatever reconstructs the dataset on the far side. Both entry points are
 *       exercised: a hostile date offered to the report submission, and a hostile card handed
 *       straight to the stream publisher. In every case the assertion is not merely that an
 *       exception is raised but that <em>zero</em> publish attempts were made, because a guard that
 *       throws after publishing the first card would still have leaked it.</li>
 *   <li><strong>A legitimate submission is never suppressed.</strong> Each message's deduplication
 *       identifier must be unique to its submission, so requesting the same reporting period twice
 *       queues two jobs. A period-derived identity would make the second request collide inside the
 *       queue service's deduplication interval and be discarded with a success response, losing a
 *       re-run silently. The tests assert two full submissions, wholly disjoint identifiers, and
 *       &mdash; separately &mdash; that ordering is untouched, because uniqueness and ordering are
 *       independent properties and only the first one changed.</li>
 *   <li><strong>A failure is reported, not thrown.</strong> The legacy queue is defined
 *       ignore-on-error, so a publish failure must leave the caller's request completing normally
 *       while the stream stops short and the result says so.</li>
 * </ol>
 *
 * <h2>The queue double</h2>
 *
 * <p>No queue client, container, network socket or AWS endpoint is involved. The messaging interface
 * is a Mockito double, and the fluent options object handed to it is a small hand-written recorder
 * declared at the foot of this file, so every published message is captured as a typed record of
 * queue, payload, message group and deduplication identifier. Recording the <em>attempt</em> rather
 * than the success is deliberate: it is what lets a test assert that no attempt was made at all.</p>
 *
 * <h2>Where the expected card bytes come from</h2>
 *
 * <p>The expected payloads are taken from the card builder, which is this service's collaborator by
 * design rather than a convenience: the service's contract is that it publishes the builder's cards
 * unaltered. The builder's own bytes are pinned independently, against hand-written literals, in its
 * own test, so composing the two here asserts the hand-off without either test standing in for the
 * other. What this file adds is that the bytes are neither trimmed, padded nor re-encoded on their
 * way to a message body, which is asserted on the encoded bytes rather than on characters.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Legacy estate at checkout SHA 7756d895ffeb65f7ea72aaa609e356d9899afcec, upstream release stamp
 * CardDemo_v1.0-15-g27d6c6f-68 dated 2022-07-19.</p>
 */
@DisplayName("JobSubmissionService :: the online-to-batch job-submission bridge")
class JobSubmissionServiceSecurityTest {

    // CONFIGURATION THE SERVICE IS CONSTRUCTED WITH

    /** A first-in-first-out queue name, whose suffix the service requires. */
    private static final String QUEUE_NAME = "JOBS.fifo";

    /** The single stable message group that carries a submission, which is what preserves order. */
    private static final String MESSAGE_GROUP_ID = "JOBS";

    // INDEPENDENTLY WRITTEN CONTRACT VALUES

    /** The number of cards one complete submission carries. */
    private static final int EXPECTED_CARD_COUNT = 17;

    /** The fixed record width of one card, in encoded bytes. */
    private static final int EXPECTED_CARD_WIDTH = 80;

    /** The longest deduplication identifier the queue service accepts. */
    private static final int EXPECTED_DEDUPLICATION_ID_LIMIT = 128;

    /** The separator between a submission identity and a card ordinal. */
    private static final String EXPECTED_ORDINAL_SEPARATOR = "-";

    /** The leading bytes of the terminating sentinel card. */
    private static final String EXPECTED_SENTINEL_LEAD = "/*EOF";

    /** The frozen operator-facing failure text the legacy paragraph reports. */
    private static final String EXPECTED_FAILURE_TEXT = "Unable to Write TDQ (JOBS)...";

    // DATE SLOT VALUES

    /** A legitimate start-date slot value. */
    private static final String START_DATE = "2022-01-01";

    /** A legitimate end-date slot value. */
    private static final String END_DATE = "2022-07-06";

    // SENTINELS

    /** Ordinal value meaning that no publish attempt should be made to fail. */
    private static final int NEVER_FAIL = -1;

    /** The one-based ordinal of the first card. */
    private static final int FIRST_ORDINAL = 1;

    // TEST STATE

    /** Every publish attempt, in the order it was made, whether it then succeeded or failed. */
    private final List<PublishedMessage> attempts = new ArrayList<>();

    /** The messaging double. */
    private SqsOperations sqsOperations;

    /** The service under test. */
    private JobSubmissionService service;

    /** The one-based attempt number that must fail, or {@link #NEVER_FAIL}. */
    private int failAtAttempt;

    /**
     * Produces the failure a refused attempt raises.
     *
     * <p>Held as a supplier rather than as an instance so that each refusal raises its own object;
     * rethrowing one instance would let a single stack trace accumulate frames across attempts, which
     * is an artefact of the double rather than a property of the service. The default is the
     * innocuous rejection every pre-existing test in this class expects, so a test that cares about
     * the failure's shape replaces it and no other test is disturbed.
     */
    private Supplier<RuntimeException> refusal;

    @BeforeEach
    void createServiceOverARecordingQueue() {
        this.attempts.clear();
        this.failAtAttempt = NEVER_FAIL;
        this.refusal = () -> new IllegalStateException("simulated queue rejection");
        this.sqsOperations = mock(SqsOperations.class);

        when(this.sqsOperations.<String>send(any())).thenAnswer(invocation -> {
            final Consumer<SqsSendOptions<String>> configurer = invocation.getArgument(0);
            final RecordingSendOptions options = new RecordingSendOptions();
            configurer.accept(options);

            // The attempt is recorded before any simulated failure, so a test can distinguish
            // "was never offered to the queue" from "was offered and rejected".
            this.attempts.add(options.recorded());

            if (this.attempts.size() == this.failAtAttempt) {
                // Any runtime failure of the write itself; the service must catch every one of
                // them, because the legacy queue is defined ignore-on-error.
                throw this.refusal.get();
            }
            return new SendResult<>(UUID.randomUUID(), QUEUE_NAME,
                    new GenericMessage<>(options.recorded().payload()), Map.of());
        });

        this.service = new JobSubmissionService(this.sqsOperations, QUEUE_NAME, MESSAGE_GROUP_ID);
    }

    /*
     * ========================================================================================
     * Construction and configuration.
     * ========================================================================================
     */

    @Nested
    @DisplayName("construction and configuration")
    class ConstructionAndConfiguration {

        @Test
        @DisplayName("refuses a null messaging collaborator")
        void refusesANullMessagingCollaborator() {
            assertThatNullPointerException()
                    .as("a null collaborator must be refused at construction")
                    .isThrownBy(() -> new JobSubmissionService(null, QUEUE_NAME, MESSAGE_GROUP_ID));
        }

        @Test
        @DisplayName("refuses a queue that is not first-in-first-out, because a standard queue cannot preserve card order")
        void refusesAQueueThatIsNotFirstInFirstOut() {
            // Order is contractual: the seventeen cards reconstruct a job stream, and a standard
            // queue makes no ordering promise at all. The suffix is the only signal available at
            // configuration time, so it is required.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a queue without the first-in-first-out suffix must be refused")
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, "JOBS",
                            MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-submission-queue");
        }

        @Test
        @DisplayName("refuses an absent or blank queue name, naming the property that is missing")
        void refusesAnAbsentOrBlankQueueName() {
            for (final String unusable : List.of("", "   ")) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("the queue name [%s] must be refused", unusable)
                        .isThrownBy(() -> new JobSubmissionService(sqsOperations, unusable,
                                MESSAGE_GROUP_ID))
                        .withMessageContaining("carddemo.aws.sqs.job-submission-queue");
            }
        }

        @Test
        @DisplayName("refuses an absent or blank message group, naming the property that is missing")
        void refusesAnAbsentOrBlankMessageGroup() {
            for (final String unusable : List.of("", "   ")) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("the message group [%s] must be refused", unusable)
                        .isThrownBy(() -> new JobSubmissionService(sqsOperations, QUEUE_NAME,
                                unusable))
                        .withMessageContaining("carddemo.aws.sqs.message-group-id");
            }
        }
    }

    /*
     * ========================================================================================
     * The seventeen-card submission.
     * ========================================================================================
     */

    @Nested
    @DisplayName("a complete submission")
    class ACompleteSubmission {

        @Test
        @DisplayName("publishes exactly seventeen messages, one per card")
        void publishesExactlySeventeenMessagesOnePerCard() {
            final JobSubmissionService.SubmissionResult result =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(attempts)
                    .as("one message per card, and no message beyond the card stream")
                    .hasSize(EXPECTED_CARD_COUNT);

            assertThat(result.cardsRequested())
                    .as("the stream length reported back to the caller")
                    .isEqualTo(EXPECTED_CARD_COUNT);
            assertThat(result.cardsPublished())
                    .as("every card must have reached the queue")
                    .isEqualTo(EXPECTED_CARD_COUNT);
            assertThat(result.failed()).as("no failure occurred").isFalse();
            assertThat(result.complete()).as("the submission is complete").isTrue();
            assertThat(result.partial()).as("the submission is not partial").isFalse();
            assertThat(result.failureMessage()).as("no failure text on a clean submission").isEmpty();
        }

        @Test
        @DisplayName("publishes the builder's cards unaltered: every payload is exactly eighty encoded bytes and is byte identical to the card it came from")
        void publishesTheBuildersCardsUnaltered() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(payloads())
                    .as("the published bodies must be the card stream, in order, with nothing added"
                            + " and nothing removed")
                    .containsExactlyElementsOf(cards);

            for (final PublishedMessage attempt : attempts) {
                assertThat(encodedWidth(attempt.payload()))
                        .as("every message body must hold the fixed record width")
                        .isEqualTo(EXPECTED_CARD_WIDTH);
            }
        }

        @Test
        @DisplayName("never trims, strips or pads a card, because a fixed-width record's trailing spaces are part of the record")
        void neverTrimsStripsOrPadsACard() {
            service.submitTransactionReportJob(START_DATE, END_DATE);

            // The sentinel card is five significant bytes followed by seventy-five spaces. If
            // anything trimmed a payload this is the message it would be visible on.
            final String sentinel = attempts.get(EXPECTED_CARD_COUNT - 1).payload();

            assertThat(encodedWidth(sentinel))
                    .as("the sentinel must arrive at the full record width, padding included")
                    .isEqualTo(EXPECTED_CARD_WIDTH);
            assertThat(sentinel)
                    .as("the sentinel's own literal must lead the record")
                    .startsWith(EXPECTED_SENTINEL_LEAD);
            assertThat(sentinel.substring(EXPECTED_SENTINEL_LEAD.length()))
                    .as("everything after the sentinel literal must be the ASCII space padding")
                    .isEqualTo(" ".repeat(EXPECTED_CARD_WIDTH - EXPECTED_SENTINEL_LEAD.length()));
        }

        @Test
        @DisplayName("transmits the sentinel card rather than merely stopping on it, which is why a complete submission is seventeen messages and not sixteen")
        void transmitsTheSentinelCardRatherThanMerelyStoppingOnIt() {
            // The legacy loop sets its end-of-stream flag before the write, so the card that ends
            // the stream is itself written. Reversing that ordering would silently drop the batch
            // tier's end-of-stream marker, and the count is what detects it.
            service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(attempts).hasSize(EXPECTED_CARD_COUNT);
            assertThat(attempts.get(EXPECTED_CARD_COUNT - 1).payload())
                    .as("the last message must be the sentinel card")
                    .startsWith(EXPECTED_SENTINEL_LEAD);
        }

        @Test
        @DisplayName("publishes every card into one message group, which is what preserves append order")
        void publishesEveryCardIntoOneMessageGroup() {
            service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(attempts).extracting(PublishedMessage::messageGroupId)
                    .as("a single group for the whole submission, or order is not guaranteed")
                    .containsOnly(MESSAGE_GROUP_ID);
        }

        @Test
        @DisplayName("addresses every message to the configured queue")
        void addressesEveryMessageToTheConfiguredQueue() {
            service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(attempts).extracting(PublishedMessage::queue)
                    .as("every message must be addressed to the configured queue")
                    .containsOnly(QUEUE_NAME);
        }

        @Test
        @DisplayName("gives each card of a submission its own deduplication identifier, ending in the card's one-based ordinal")
        void givesEachCardItsOwnDeduplicationIdentifier() {
            service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> deduplicationIds = deduplicationIds();

            assertThat(deduplicationIds)
                    .as("seventeen distinct identifiers, so no card of a submission is discarded as"
                            + " a duplicate of another")
                    .doesNotHaveDuplicates()
                    .hasSize(EXPECTED_CARD_COUNT);

            for (int ordinal = FIRST_ORDINAL; ordinal <= EXPECTED_CARD_COUNT; ordinal++) {
                assertThat(deduplicationIds.get(ordinal - FIRST_ORDINAL))
                        .as("the identifier of card %d must end in that one-based ordinal", ordinal)
                        .endsWith(EXPECTED_ORDINAL_SEPARATOR + ordinal);
            }
        }

        @Test
        @DisplayName("keeps every deduplication identifier inside the length the queue service accepts")
        void keepsEveryDeduplicationIdentifierInsideTheAcceptedLength() {
            service.submitTransactionReportJob(START_DATE, END_DATE);

            for (final String deduplicationId : deduplicationIds()) {
                assertThat(deduplicationId.length())
                        .as("identifier [%s] must fit the queue service's limit", deduplicationId)
                        .isLessThanOrEqualTo(EXPECTED_DEDUPLICATION_ID_LIMIT);
                assertThat(deduplicationId)
                        .as("identifier [%s] must hold no whitespace", deduplicationId)
                        .doesNotContainAnyWhitespaces();
            }
        }

        @Test
        @DisplayName("keeps the reporting period legible in the submission identity, so a queued submission can be read back to the period that asked for it")
        void keepsTheReportingPeriodLegibleInTheSubmissionIdentity() {
            // The nonce alone would make the identity unique. The dates stay in it because the
            // identity appears in every diagnostic this class emits, and an opaque identity is
            // worth less operationally than one that names its reporting period.
            service.submitTransactionReportJob(START_DATE, END_DATE);

            for (final String deduplicationId : deduplicationIds()) {
                assertThat(deduplicationId)
                        .as("the identity must carry both date slots")
                        .contains(START_DATE)
                        .contains(END_DATE);
            }
        }
    }

    /*
     * ========================================================================================
     * A repeated submission is a second unit of work, not a retry.
     * ========================================================================================
     */

    @Nested
    @DisplayName("a repeated submission is queued rather than suppressed")
    class ARepeatedSubmissionIsQueued {

        @Test
        @DisplayName("two requests for the same reporting period produce two complete submissions, because the legacy queue appended rather than deduplicated")
        void twoRequestsForTheSamePeriodProduceTwoCompleteSubmissions() {
            // The legacy transient-data queue is defined with an append disposition. An operator
            // who asked for the same period twice got two jobs, and that is the point: a report is
            // re-run because the first run was lost, superseded or wanted again.
            final JobSubmissionService.SubmissionResult first =
                    service.submitTransactionReportJob(START_DATE, END_DATE);
            final JobSubmissionService.SubmissionResult second =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(first.complete()).as("the first submission must complete").isTrue();
            assertThat(second.complete()).as("the second submission must complete too").isTrue();

            assertThat(attempts)
                    .as("both submissions must reach the queue in full")
                    .hasSize(2 * EXPECTED_CARD_COUNT);
        }

        @Test
        @DisplayName("the two submissions share no deduplication identifier at all, so the queue service cannot discard the second one inside its deduplication interval")
        void theTwoSubmissionsShareNoDeduplicationIdentifier() {
            // This is the assertion that would have failed before the identity carried a nonce.
            // With a period-derived identity, card N of the second submission carried exactly the
            // identifier card N of the first had used, and the queue service discarded it behind a
            // success response: no job queued, nobody told.
            service.submitTransactionReportJob(START_DATE, END_DATE);
            final Set<String> firstSubmission = new LinkedHashSet<>(deduplicationIds());

            attempts.clear();
            service.submitTransactionReportJob(START_DATE, END_DATE);
            final Set<String> secondSubmission = new LinkedHashSet<>(deduplicationIds());

            assertThat(firstSubmission)
                    .as("the first submission's identifiers")
                    .hasSize(EXPECTED_CARD_COUNT);
            assertThat(secondSubmission)
                    .as("the second submission's identifiers")
                    .hasSize(EXPECTED_CARD_COUNT);
            assertThat(secondSubmission)
                    .as("not one identifier may be shared between two submissions of the same"
                            + " reporting period")
                    .doesNotContainAnyElementsOf(firstSubmission);
        }

        @Test
        @DisplayName("every identifier across many repeated submissions is distinct")
        void everyIdentifierAcrossManyRepeatedSubmissionsIsDistinct() {
            // A single repeat could pass by luck if the identity varied on something coarse, such
            // as a clock with second resolution. Twenty back-to-back submissions inside the same
            // instant is what rules that out.
            final int repeats = 20;

            for (int repeat = 0; repeat < repeats; repeat++) {
                service.submitTransactionReportJob(START_DATE, END_DATE);
            }

            assertThat(attempts).hasSize(repeats * EXPECTED_CARD_COUNT);
            assertThat(deduplicationIds())
                    .as("every identifier of every repeated submission must be distinct")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("ordering is untouched by the uniqueness change: repeated submissions still travel in one message group")
        void orderingIsUntouchedByTheUniquenessChange() {
            // Uniqueness and ordering are independent properties of a first-in-first-out queue and
            // only uniqueness changed. Had the nonce been added to the message group instead, each
            // submission would have become its own group and the cards would no longer have been
            // guaranteed to arrive in order.
            service.submitTransactionReportJob(START_DATE, END_DATE);
            service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(attempts).extracting(PublishedMessage::messageGroupId)
                    .as("one group across both submissions, exactly as with one")
                    .containsOnly(MESSAGE_GROUP_ID);
        }

        @Test
        @DisplayName("card ordinals still distinguish the cards inside one submission, so a genuine double publish of the same card is still caught")
        void cardOrdinalsStillDistinguishTheCardsInsideOneSubmission() {
            // The nonce moved to the submission identity and not to the per-card portion, so the
            // ordinal still does its original job. Publishing the same card of the same submission
            // twice - a programming error rather than an operator action - still yields the same
            // identifier, and the queue service treats it as the duplicate it is.
            final String submissionId = "2022-01-01_2022-07-06_fixedidentityforthistest";
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);

            service.writeJobSubmissionQueue(submissionId, card, FIRST_ORDINAL);
            service.writeJobSubmissionQueue(submissionId, card, FIRST_ORDINAL);

            assertThat(deduplicationIds())
                    .as("the same card of the same submission must carry one identifier twice")
                    .hasSize(2)
                    .containsOnly(submissionId + EXPECTED_ORDINAL_SEPARATOR + FIRST_ORDINAL);
        }
    }

    /*
     * ========================================================================================
     * The queue payload boundary. Nothing hostile may reach a message body.
     * ========================================================================================
     */

    @Nested
    @DisplayName("the queue payload boundary")
    class TheQueuePayloadBoundary {

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.carddemo.service.JobSubmissionServiceSecurityTest#hostileDateSlots")
        @DisplayName("a hostile date never reaches a message body, and no partial submission is left behind")
        void aHostileDateNeverReachesAMessageBody(final String description,
                final String hostileDate) {
            // The report-submission entry point validates through the card builder before a single
            // card exists, so the assertion that matters is not that an exception was raised but
            // that nothing at all was offered to the queue.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s must be refused as a start date", description)
                    .isThrownBy(() -> service.submitTransactionReportJob(hostileDate, END_DATE));

            assertThat(attempts)
                    .as("%s: no message may be published when a slot is refused", description)
                    .isEmpty();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s must be refused as an end date", description)
                    .isThrownBy(() -> service.submitTransactionReportJob(START_DATE, hostileDate));

            assertThat(attempts)
                    .as("%s: still nothing published after the end-slot refusal", description)
                    .isEmpty();
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.carddemo.service.JobSubmissionServiceSecurityTest#cardsCarryingAControlByte")
        @DisplayName("a card carrying a control byte is refused, because a control byte would reframe the eighty-byte record at the consumer")
        void aCardCarryingAControlByteIsRefused(final String description, final char controlByte) {
            // A control byte does not change the record's width, so a width check admits it. Yet a
            // consumer reconstructing a dataset from the queue would read one eighty-byte card as
            // two records, or truncate it, depending on the byte. This is the last place it can be
            // stopped.
            final String card = cardCarrying(controlByte);

            assertThat(encodedWidth(card))
                    .as("%s: the hostile card is exactly the record width, so width cannot reject"
                            + " it", description)
                    .isEqualTo(EXPECTED_CARD_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s must be refused", description)
                    .isThrownBy(() -> service.submitJobStream("submission", List.of(card)))
                    .withMessageContaining("non-printable character")
                    .withMessageContaining("position")
                    .withMessageNotContaining(String.valueOf(controlByte));

            assertThat(attempts)
                    .as("%s: no message may be published", description)
                    .isEmpty();
        }

        @Test
        @DisplayName("a hostile card anywhere in a stream stops the whole stream before anything is published, so no partial submission escapes")
        void aHostileCardAnywhereInAStreamStopsTheWholeStreamBeforePublishing() {
            // Every card is checked before the first is published. Were the check performed inside
            // the emitting loop instead, the sixteen cards before the hostile one would already
            // have reached the queue and the batch tier would hold a half-formed job stream.
            final List<String> cards =
                    new ArrayList<>(JclCardImageBuilder.build(START_DATE, END_DATE));
            cards.set(EXPECTED_CARD_COUNT - 1, cardCarrying((char) 0x0A));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a malformed stream must be refused outright")
                    .isThrownBy(() -> service.submitJobStream("submission", cards));

            assertThat(attempts)
                    .as("not one of the sixteen well-formed cards may have been published")
                    .isEmpty();
        }

        @Test
        @DisplayName("a card holding a character that is not a single US-ASCII byte is refused, because a replacement byte would silently corrupt the record")
        void aCardHoldingANonSingleByteCharacterIsRefused() {
            // The encoder substitutes a replacement byte rather than refusing or expanding, so the
            // encoded width is indistinguishable from a valid card and only an explicit
            // representability check catches this.
            final String card = "A".repeat(EXPECTED_CARD_WIDTH - 1) + "\u00e9";

            assertThat(encodedWidth(card))
                    .as("replacement makes the encoded width look correct")
                    .isEqualTo(EXPECTED_CARD_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream("submission", List.of(card)))
                    .withMessageContaining("US-ASCII");

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("a card of the wrong width is refused in both directions and publishes nothing")
        void aCardOfTheWrongWidthIsRefusedInBothDirections() {
            for (final int width : List.of(EXPECTED_CARD_WIDTH - 1, EXPECTED_CARD_WIDTH + 1)) {
                final String card = "A".repeat(width);

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a card of %d bytes must be refused", width)
                        .isThrownBy(() -> service.submitJobStream("submission", List.of(card)))
                        .withMessageContaining(String.valueOf(EXPECTED_CARD_WIDTH));

                assertThat(attempts).as("no message may be published").isEmpty();
            }
        }

        @Test
        @DisplayName("every one of the seventeen legitimate cards passes the printable boundary, so the guard refuses nothing the legacy program emits")
        void everyLegitimateCardPassesThePrintableBoundary() {
            // The other half of the claim. A guard that refused a legitimate card would break the
            // trigger just as surely as one that admitted a hostile card, so both directions are
            // asserted. Every legacy card is printable literals and space padding, the sentinel
            // included.
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            final JobSubmissionService.SubmissionResult result =
                    service.submitJobStream("submission", cards);

            assertThat(result.complete())
                    .as("the whole legitimate stream must be accepted and published")
                    .isTrue();
            assertThat(attempts).hasSize(EXPECTED_CARD_COUNT);

            for (final String card : cards) {
                for (int index = 0; index < card.length(); index++) {
                    final char character = card.charAt(index);
                    assertThat((int) character)
                            .as("card byte at position %d must be a printable US-ASCII graphic or"
                                    + " the space", index + FIRST_ORDINAL)
                            .isBetween(0x20, 0x7E);
                }
            }
        }

        @Test
        @DisplayName("refuses a submission identity that holds whitespace, because a deduplication identifier may not, and removing it would make two identities collide")
        void refusesASubmissionIdentityThatHoldsWhitespace() {
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.submitJobStream("has a space", List.of(card)))
                    .withMessageContaining("whitespace");

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("refuses a blank or null submission identity, and a null or empty card stream")
        void refusesABlankIdentityAndAnEmptyStream() {
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);

            assertThatNullPointerException()
                    .as("a null identity must be refused")
                    .isThrownBy(() -> service.submitJobStream(null, cards));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a blank identity must be refused")
                    .isThrownBy(() -> service.submitJobStream("   ", cards));

            assertThatNullPointerException()
                    .as("a null stream must be refused")
                    .isThrownBy(() -> service.submitJobStream("submission", null));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("an empty stream must be refused")
                    .isThrownBy(() -> service.submitJobStream("submission", List.of()));

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("refuses a null date on either side of the reporting period")
        void refusesANullDateOnEitherSide() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitTransactionReportJob(null, END_DATE));
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitTransactionReportJob(START_DATE, null));

            assertThat(attempts).as("no message may be published").isEmpty();
        }
    }

    /*
     * ========================================================================================
     * Diagnostic hygiene - decision DL-041.
     * ========================================================================================
     */

    @Nested
    @DisplayName("diagnostic hygiene - a rejection names the defect, never the rejected value")
    class DiagnosticHygiene {

        @Test
        @DisplayName("every entry point taking a caller-supplied identity refuses a terminator-bearing one without repeating it")
        void everyEntryPointRefusesATerminatorBearingIdentityWithoutRepeatingIt() {
            // This service is the estate's only online-to-batch bridge, and all four of these
            // entry points take the submission identity from their caller. A carriage return and a
            // line feed are both whitespace, so the whitespace branch is reached precisely when the
            // identity holds a line terminator - which means it is the one branch where echoing the
            // value would put a forged line into the service log by construction. Every entry point
            // is exercised rather than one, because the guard is only as good as its least-guarded
            // caller.
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String card = cards.get(0);
            final String hostile = HOSTILE_MARKER + terminators() + "FORGED AUDIT ENTRY";

            final List<ThrowingCallable> entryPoints = List.of(
                    () -> service.submitTransactionReportJob(hostile, START_DATE, END_DATE),
                    () -> service.submitCanonicalJobImage(hostile, cards),
                    () -> service.submitJobStream(hostile, List.of(card)),
                    () -> service.writeJobSubmissionQueue(hostile, card, FIRST_ORDINAL));

            for (final ThrowingCallable entryPoint : entryPoints) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a terminator-bearing identity must be refused at every entry point")
                        .isThrownBy(entryPoint)
                        .withMessageContaining("must not hold whitespace")
                        .withMessageNotContaining(HOSTILE_MARKER)
                        .withMessageNotContaining("FORGED AUDIT ENTRY")
                        .satisfies(JobSubmissionServiceSecurityTest::assertCarriesNoRawTerminator);
            }

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("the identity rejection names the offending character's zero-based position and code point, which is what shortening or correcting it requires")
        void theIdentityRejectionNamesThePositionAndCodePoint() {
            // Suppressing the value is only half of the obligation: the diagnostic still has to be
            // actionable. The position and the code point identify exactly one character, which is
            // everything a caller needs to correct the identity and nothing an attacker can use.
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);
            final String hostile = HOSTILE_MARKER + terminators();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.writeJobSubmissionQueue(hostile, card, FIRST_ORDINAL))
                    .withMessage("submissionId must not hold whitespace because a message"
                            + " deduplication identifier may not; the character at zero-based"
                            + " position " + HOSTILE_MARKER.length() + " is code point "
                            + (int) CARRIAGE_RETURN);

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("an identity built only from whitespace takes the older blank path, so a reader knows which of the two rejections to expect")
        void anIdentityBuiltOnlyFromWhitespaceTakesTheBlankPath() {
            // The blank test precedes the character scan, and a carriage return, a line feed and a
            // tab are all whitespace, so an identity made only of terminators is blank as far as
            // String.isBlank is concerned and never reaches the scan. Pinning this keeps the two
            // rejections distinguishable: a caller reading "must not be blank" is not left
            // wondering why the position of the offending character was withheld.
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);

            for (final String whitespaceOnly
                    : List.of(terminators(), Character.toString(TAB), " \t\r\n ")) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a whitespace-only identity must be refused as blank")
                        .isThrownBy(() ->
                                service.writeJobSubmissionQueue(whitespaceOnly, card, FIRST_ORDINAL))
                        .withMessage("submissionId must not be blank")
                        .satisfies(JobSubmissionServiceSecurityTest::assertCarriesNoRawTerminator);
            }

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("the queue rejection names the property and the suffix it requires, and does not repeat the configured value")
        void theQueueRejectionNamesThePropertyAndTheSuffixOnly() {
            // The suffix literal stays because it is this class's own statement of what it expects,
            // not an echo of what it was given. The property key is the actionable fact - it points
            // an operator at the exact configuration entry, where the value can already be read -
            // so repeating the value adds nothing and would carry a deployment-supplied string into
            // a startup log line. The value used here is printable, so the suffix check is what
            // rejects it; the control-character rule is asserted separately below because it is a
            // different branch and it runs first.
            final String suffixlessQueue = "JOBS-" + HOSTILE_MARKER;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a queue name without the first-in-first-out suffix must be refused")
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, suffixlessQueue,
                            MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-submission-queue")
                    .withMessageContaining(".fifo")
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(JobSubmissionServiceSecurityTest::assertCarriesNoRawTerminator);

            // The same obligation applies to the message group, which is bound from configuration
            // in exactly the same way.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a blank message group must be refused without echoing what was bound")
                    .isThrownBy(() -> new JobSubmissionService(sqsOperations, QUEUE_NAME,
                            Character.toString(TAB)))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id")
                    .satisfies(JobSubmissionServiceSecurityTest::assertCarriesNoRawTerminator);
        }

        @Test
        @DisplayName("a configured queue name or message group carrying a control character is refused, because both are written into every log record the service emits")
        void aConfiguredValueCarryingAControlCharacterIsRefused() {
            // This class states the reason itself, in the card guard: a control byte "could forge a
            // line in a log record". The queue name and the message group are substituted into all
            // four of this class's log statements, and parameter substitution escapes nothing, so a
            // carriage return in either value would let a log reader split one record into two.
            // Both values are printable US-ASCII by the queue service's own definition, so the rule
            // refuses nothing a real deployment needs.
            final String forgedQueue = "JOBS" + terminators() + HOSTILE_MARKER + ".fifo";
            final String forgedGroup = "JOBS" + terminators() + HOSTILE_MARKER;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a queue name holding a terminator must be refused even though it carries"
                            + " the required suffix, so the suffix cannot be used to slip one past")
                    .isThrownBy(() ->
                            new JobSubmissionService(sqsOperations, forgedQueue, MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-submission-queue")
                    .withMessageContaining("printable US-ASCII only")
                    .withMessageContaining("position 4")
                    .withMessageContaining("code point " + (int) CARRIAGE_RETURN)
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(JobSubmissionServiceSecurityTest::assertCarriesNoRawTerminator);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a message group holding a terminator must be refused")
                    .isThrownBy(() ->
                            new JobSubmissionService(sqsOperations, QUEUE_NAME, forgedGroup))
                    .withMessageContaining("carddemo.aws.sqs.message-group-id")
                    .withMessageContaining("printable US-ASCII only")
                    .withMessageContaining("code point " + (int) CARRIAGE_RETURN)
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(JobSubmissionServiceSecurityTest::assertCarriesNoRawTerminator);

            // Every printable value the queue service itself accepts must still construct, so the
            // rule is a control-character rule and not an alphanumeric one: a queue URL and a queue
            // ARN both carry punctuation this guard has to let through.
            for (final String legitimate : List.of("JOBS.fifo",
                    "https://sqs.us-east-1.amazonaws.com/000000000000/JOBS.fifo",
                    "arn:aws:sqs:us-east-1:000000000000:JOBS.fifo")) {
                assertThat(new JobSubmissionService(sqsOperations, legitimate, MESSAGE_GROUP_ID))
                        .as("the legitimate configured value [%s] must construct", legitimate)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("the deduplication-length rejection reports the lengths and the ceiling rather than the identity it composed them from")
        void theDeduplicationLengthRejectionReportsLengthsRatherThanTheIdentity() {
            // This guard fires on a caller-supplied identity that is well formed but too long, so
            // no terminator can reach it - the whitespace scan runs first. It is still covered by
            // the same rule: the four numbers a caller needs to shorten the identity are the
            // identity's length, the ordinal, the composed length and the ceiling, and the identity
            // itself is redundant because the caller passed it.
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);
            final String overLong = HOSTILE_MARKER
                    + "x".repeat(EXPECTED_DEDUPLICATION_ID_LIMIT - HOSTILE_MARKER.length());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> service.writeJobSubmissionQueue(overLong, card, FIRST_ORDINAL))
                    .withMessageContaining(String.valueOf(overLong.length()))
                    .withMessageContaining(String.valueOf(EXPECTED_DEDUPLICATION_ID_LIMIT))
                    .withMessageNotContaining(HOSTILE_MARKER);

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("a card carrying a control byte is refused by ordinal, position and code point, never by quoting the card")
        void aCardCarryingAControlByteIsRefusedWithoutQuotingIt() {
            // The card is the queue payload, so this boundary is the one decision DL-042 governs;
            // it is asserted here as well because the same message must satisfy DL-041. A card is
            // eighty bytes of caller-influenced text, and quoting it would be the largest echo this
            // class could make.
            final String legitimate = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);
            final String forged = HOSTILE_MARKER + terminators()
                    + legitimate.substring(HOSTILE_MARKER.length() + terminators().length());

            assertThat(forged.length())
                    .as("the forged card must keep the contractual width, so width is not what"
                            + " rejects it")
                    .isEqualTo(EXPECTED_CARD_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.writeJobSubmissionQueue("submission", forged, FIRST_ORDINAL))
                    .withMessageContaining("non-printable")
                    .withMessageContaining("code point " + (int) CARRIAGE_RETURN)
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(JobSubmissionServiceSecurityTest::assertCarriesNoRawTerminator);

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("refuses a submission identity carrying a control character that is not whitespace, which a whitespace-only scan admitted")
        void refusesASubmissionIdentityCarryingANonWhitespaceControlCharacter() {
            // The defect this pins: NUL, escape and delete are none of them whitespace, so a guard
            // that scanned only for whitespace let all three through - into the submission= field of
            // four log records and into the deduplication identifier published to the queue. Each is
            // exercised separately rather than as one string, because a scan that stops at the first
            // defect would otherwise report only the first and leave the others unproven.
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);

            for (final char control : new char[] {NUL, ESCAPE, DELETE}) {
                final String hostile = HOSTILE_MARKER + control + "FORGED AUDIT ENTRY";

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("code point %d must be refused in a submission identity", (int) control)
                        .isThrownBy(() ->
                                service.writeJobSubmissionQueue(hostile, card, FIRST_ORDINAL))
                        .withMessageContaining("non-printable")
                        .withMessageContaining("zero-based position " + HOSTILE_MARKER.length())
                        .withMessageContaining("code point " + (int) control)
                        .withMessageNotContaining(HOSTILE_MARKER)
                        .withMessageNotContaining("FORGED AUDIT ENTRY")
                        .satisfies(
                                JobSubmissionServiceSecurityTest::assertCarriesNoRawControlCharacter);
            }

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("refuses a submission identity carrying a character that is not representable as a single US-ASCII byte, which is a different question from printability")
        void refusesASubmissionIdentityCarryingANonRepresentableCharacter() {
            // Representability and printability are asked separately because they have different
            // answers: this character is not a control character and would survive any
            // control-character filter, yet it cannot be encoded as one US-ASCII byte, so the bytes
            // published would silently differ from the bytes supplied.
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);
            final String hostile = HOSTILE_MARKER + BEYOND_US_ASCII;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.writeJobSubmissionQueue(hostile, card, FIRST_ORDINAL))
                    .withMessageContaining("not representable as a single US-ASCII byte")
                    .withMessageContaining("zero-based position " + HOSTILE_MARKER.length())
                    .withMessageContaining("code point " + (int) BEYOND_US_ASCII)
                    .withMessageNotContaining(HOSTILE_MARKER)
                    .satisfies(JobSubmissionServiceSecurityTest::assertCarriesNoRawControlCharacter);

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("every entry point refuses a NUL-bearing identity, because the guard is only as good as its least-guarded caller")
        void everyEntryPointRefusesANulBearingIdentity() {
            // The terminator test above proves this for whitespace at all four entry points. The same
            // breadth is required for the non-whitespace controls, or a caller could reach the queue
            // through whichever entry point was left unchecked.
            final List<String> cards = JclCardImageBuilder.build(START_DATE, END_DATE);
            final String card = cards.get(0);
            final String hostile = HOSTILE_MARKER + NUL + "FORGED AUDIT ENTRY";

            final List<ThrowingCallable> entryPoints = List.of(
                    () -> service.submitCanonicalJobImage(hostile, cards),
                    () -> service.submitJobStream(hostile, List.of(card)),
                    () -> service.writeJobSubmissionQueue(hostile, card, FIRST_ORDINAL));

            for (final ThrowingCallable entryPoint : entryPoints) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("a NUL-bearing identity must be refused at every entry point")
                        .isThrownBy(entryPoint)
                        .withMessageContaining("non-printable")
                        .withMessageNotContaining(HOSTILE_MARKER)
                        .withMessageNotContaining("FORGED AUDIT ENTRY")
                        .satisfies(
                                JobSubmissionServiceSecurityTest::assertCarriesNoRawControlCharacter);
            }

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("refuses a reporting date carrying a control character, because condensing whitespace is not sanitising")
        void refusesAReportingDateCarryingAControlCharacter() {
            // The minted identity is composed from the two date slots with their whitespace removed.
            // Removing whitespace is not the same as removing controls, so a NUL in a date reaches the
            // identity intact - and the identity is what every diagnostic prints. Pinning this closes
            // the one path to the submission= field that does not take a caller-supplied identity.
            for (final char control : new char[] {NUL, ESCAPE, DELETE}) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("code point %d must be refused in a reporting date", (int) control)
                        .isThrownBy(() -> service.submitTransactionReportJob(
                                START_DATE + control, END_DATE))
                        .satisfies(
                                JobSubmissionServiceSecurityTest::assertCarriesNoRawControlCharacter);
            }

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("the whitespace rejection still wins for a space, so the more specific diagnostic is not lost to the new one")
        void theWhitespaceRejectionStillWinsForASpace() {
            // A space is the one character both the whitespace test and the printable range accept, so
            // the ordering of the two checks decides which message a caller reads. Whitespace is
            // tested first deliberately: "a deduplication identifier may not hold whitespace" tells a
            // caller strictly more than "non-printable" would, and this pins that ordering so a later
            // refactor cannot silently degrade the diagnostic.
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);
            final String withSpace = HOSTILE_MARKER + " TAIL";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.writeJobSubmissionQueue(withSpace, card, FIRST_ORDINAL))
                    .withMessageContaining("must not hold whitespace")
                    .withMessageNotContaining("non-printable")
                    .withMessageNotContaining(HOSTILE_MARKER);

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("every published deduplication identifier is printable US-ASCII, which is the property the identity guard exists to give it")
        void everyPublishedDeduplicationIdentifierIsPrintableUsAscii() {
            // The complement of the rejection tests: having established what cannot get in, assert
            // the property that holds for what does. A control character in a deduplication
            // identifier is not merely a log-forging vector - it can also make two distinct
            // identifiers compare equal at a consumer that truncates, which would silently drop a
            // card from the reconstructed job stream.
            service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(deduplicationIds()).as("the submission must have published its cards")
                    .isNotEmpty();
            for (final String deduplicationId : deduplicationIds()) {
                assertThat(deduplicationId).as("every card must carry an identifier").isNotNull();
                for (int index = 0; index < deduplicationId.length(); index++) {
                    final char character = deduplicationId.charAt(index);
                    assertThat((int) character)
                            .as("identifier [%s] position %d must be printable US-ASCII",
                                    deduplicationId, index)
                            .isBetween((int) ' ' + 1, (int) '~');
                }
            }
        }
    }


    /*
     * ========================================================================================
     * Diagnostic hygiene on the one value that arrives from outside the module - decision DL-041.
     * ========================================================================================
     */

    @Nested
    @DisplayName("diagnostic hygiene on an external failure - the queue client's own text is echoed no more than a caller's is")
    class ExternalFailureDiagnosticHygiene {

        /**
         * Text a leaked log record would carry.
         *
         * <p>Distinct from {@link JobSubmissionServiceSecurityTest#HOSTILE_MARKER} so that a leak
         * through this path cannot be confused with a leak through a caller-supplied value.
         */
        private static final String EXTERNAL_MARKER = "QAMARKCLIENTLEAK";

        /**
         * A publish-failure description of the kind a verbose or misconfigured queue client really
         * produces: it names a credential, echoes it, and carries both line terminators and a tab.
         *
         * <p>A client that reports a signing failure by quoting the request it signed, or an endpoint
         * failure by quoting the endpoint it was handed, puts deployment-supplied text into an
         * exception message. This class only has to assume such a message can reach the catch block,
         * which it plainly can, since the catch block admits every runtime failure by design.
         */
        private static final String DISCLOSING_DESCRIPTION = "refused: secret=" + EXTERNAL_MARKER
                + " endpoint=https://internal.example" + "\r\n" + "FORGED AUDIT ENTRY" + "\t"
                + "at some.frame.Deeper";

        /** The recorder attached for the duration of one test. */
        private ListAppender<ILoggingEvent> recorder;

        /** The logger the recorder is attached to. */
        private Logger logger;

        @BeforeEach
        void attachRecorder() {
            this.logger = (Logger) LoggerFactory.getLogger(JobSubmissionService.class);
            this.recorder = new ListAppender<>();
            this.recorder.setContext(this.logger.getLoggerContext());
            this.recorder.start();
            this.logger.addAppender(this.recorder);
        }

        @AfterEach
        void detachRecorder() {
            this.logger.detachAppender(this.recorder);
            this.recorder.stop();
        }

        /** The single diagnostic the refused write records, asserted to be the only error record. */
        private ILoggingEvent onlyFailureRecord() {
            final List<ILoggingEvent> errors = this.recorder.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors).as("one refused card records exactly one operator diagnostic")
                    .hasSize(1);
            return errors.getFirst();
        }

        @Test
        @DisplayName("a disclosing publish-failure description reaches no field of the diagnostic, and no rendered trace exists to carry it either")
        void aDisclosingDescriptionReachesNoFieldOfTheDiagnostic() {
            // This class already refuses to echo any caller-supplied value into a diagnostic, and
            // states the reason in its own words: the sink is any channel a human or a tool later
            // reads. The queue client's exception text is the one value written into these records
            // that this module did not author, so it is subject to exactly the same rule - and it is
            // the one place the rule was previously not enforced.
            failAtAttempt = FIRST_ORDINAL;
            refusal = () -> new IllegalStateException(DISCLOSING_DESCRIPTION,
                    new IllegalArgumentException(DISCLOSING_DESCRIPTION));

            final boolean accepted = service.writeJobSubmissionQueue("submission",
                    JclCardImageBuilder.build(START_DATE, END_DATE).get(0), FIRST_ORDINAL);

            assertThat(accepted).as("the write is reported as refused, never raised").isFalse();

            final ILoggingEvent record = onlyFailureRecord();
            assertThat(record.getThrowableProxy())
                    .as("no throwable may be rendered into this record: a stack trace carries"
                            + " getMessage() for every exception in the chain, which would"
                            + " reintroduce the whole description this rule excludes")
                    .isNull();

            final String recorded = record.getFormattedMessage();
            assertThat(recorded).as("the recorded diagnostic")
                    .doesNotContain(EXTERNAL_MARKER)
                    .doesNotContain("secret=")
                    .doesNotContain("internal.example")
                    .doesNotContain("FORGED AUDIT ENTRY")
                    .doesNotContain("some.frame.Deeper");
            assertCarriesNoRawTerminator(recorded);
        }

        @Test
        @DisplayName("the diagnostic stays actionable: it names the failure by type, names the type beneath it, and renders the whole chain of types")
        void theDiagnosticStaysActionable() {
            // Suppressing the value is only half of the obligation - this class says so itself, in
            // the identity-rejection test above. The chain of types is what the suppressed stack
            // trace was diagnostically useful for, and it is composed entirely of names this module
            // sanitises rather than text the client supplied.
            failAtAttempt = FIRST_ORDINAL;
            refusal = () -> new IllegalStateException(DISCLOSING_DESCRIPTION,
                    new UnsupportedOperationException(DISCLOSING_DESCRIPTION,
                            new NumberFormatException(DISCLOSING_DESCRIPTION)));

            service.writeJobSubmissionQueue("submission",
                    JclCardImageBuilder.build(START_DATE, END_DATE).get(0), FIRST_ORDINAL);

            assertThat(onlyFailureRecord().getFormattedMessage()).as("the recorded diagnostic")
                    .startsWith(JobSubmissionException.DEFAULT_MESSAGE)
                    .contains("response=" + IllegalStateException.class.getSimpleName())
                    .contains("reason=" + NumberFormatException.class.getSimpleName())
                    .endsWith("failureChain=" + IllegalStateException.class.getSimpleName()
                            + "<-" + UnsupportedOperationException.class.getSimpleName()
                            + "<-" + NumberFormatException.class.getSimpleName());
        }

        @Test
        @DisplayName("a failure whose type name is itself hostile still yields one unbroken bounded token, because the name is sanitised rather than trusted")
        void aHostileTypeNameIsSanitisedRatherThanTrusted() {
            // A type's simple name is ordinarily a Java identifier, but it is read from a classfile
            // rather than written here, and an anonymous class reports the empty string. Neither may
            // put whitespace, a terminator or an unbounded value into a log record, so the name is
            // sanitised on the way through. An anonymous subclass is the reachable case: it names
            // itself as nothing at all.
            failAtAttempt = FIRST_ORDINAL;
            refusal = () -> new IllegalStateException(DISCLOSING_DESCRIPTION) {
                private static final long serialVersionUID = 1L;
            };

            service.writeJobSubmissionQueue("submission",
                    JclCardImageBuilder.build(START_DATE, END_DATE).get(0), FIRST_ORDINAL);

            final String recorded = onlyFailureRecord().getFormattedMessage();
            assertThat(recorded).as("an unnamed type must still yield a usable response code")
                    .contains("response=UnnamedType")
                    .doesNotContain("response= ")
                    .doesNotContain(EXTERNAL_MARKER);
            assertCarriesNoRawTerminator(recorded);
        }
    }


    /*
     * ========================================================================================
     * The ignore-on-error contract.
     * ========================================================================================
     */

    @Nested
    @DisplayName("a publish failure is reported rather than thrown")
    class APublishFailureIsReported {

        @Test
        @DisplayName("a failing publish does not propagate, because the legacy queue is defined ignore-on-error and the operator's request completes")
        void aFailingPublishDoesNotPropagate() {
            failAtAttempt = 1;

            final JobSubmissionService.SubmissionResult result =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.failed()).as("the failure must be reported").isTrue();
            assertThat(result.failureMessage())
                    .as("the frozen operator-facing text the legacy paragraph reports")
                    .isEqualTo(EXPECTED_FAILURE_TEXT)
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("the stream stops at the failing card: the cards after it are deliberately never sent")
        void theStreamStopsAtTheFailingCard() {
            // The legacy write-error flag appears in the emitting loop's guard, so raising it ends
            // the submission. Continuing past a failure would put a job stream with a hole in it
            // onto the queue, which is worse than a short one.
            final int failingOrdinal = 5;
            failAtAttempt = failingOrdinal;

            final JobSubmissionService.SubmissionResult result =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(attempts)
                    .as("the failing card is attempted; nothing after it is")
                    .hasSize(failingOrdinal);
            assertThat(result.cardsPublished())
                    .as("only the cards before the failure reached the queue")
                    .isEqualTo(failingOrdinal - 1);
            assertThat(result.cardsRequested())
                    .as("the stream length is still reported in full")
                    .isEqualTo(EXPECTED_CARD_COUNT);
            assertThat(result.partial()).as("the submission is partial").isTrue();
            assertThat(result.complete()).as("the submission is not complete").isFalse();
        }

        @Test
        @DisplayName("a failure on the very last card still leaves sixteen published and the submission partial")
        void aFailureOnTheLastCardLeavesTheSubmissionPartial() {
            failAtAttempt = EXPECTED_CARD_COUNT;

            final JobSubmissionService.SubmissionResult result =
                    service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(attempts).hasSize(EXPECTED_CARD_COUNT);
            assertThat(result.cardsPublished()).isEqualTo(EXPECTED_CARD_COUNT - 1);
            assertThat(result.failed()).isTrue();
            assertThat(result.partial()).isTrue();
        }

        @Test
        @DisplayName("the single-card publisher reports success and failure through its return value rather than by throwing")
        void theSingleCardPublisherReportsThroughItsReturnValue() {
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);

            assertThat(service.writeJobSubmissionQueue("submission", card, FIRST_ORDINAL))
                    .as("an accepted card reports true")
                    .isTrue();

            failAtAttempt = 2;
            assertThat(service.writeJobSubmissionQueue("submission", card, 2))
                    .as("a rejected card reports false rather than raising")
                    .isFalse();
        }

        @Test
        @DisplayName("the single-card publisher refuses an ordinal below the first, because the legacy card index is one-based")
        void theSingleCardPublisherRefusesAnOrdinalBelowTheFirst() {
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);

            for (final int ordinal : List.of(0, -1)) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("ordinal %d must be refused", ordinal)
                        .isThrownBy(() ->
                                service.writeJobSubmissionQueue("submission", card, ordinal))
                        .withMessageContaining("one-based");
            }

            assertThat(attempts).as("no message may be published").isEmpty();
        }
    }

    /*
     * ========================================================================================
     * The end-of-stream test, which is the abbreviated combined relation of the legacy source.
     * ========================================================================================
     */

    @Nested
    @DisplayName("the end-of-stream test")
    class TheEndOfStreamTest {

        @Test
        @DisplayName("a blank card ends the stream, because the legacy abbreviated relation expands to a separate equality test against spaces")
        void aBlankCardEndsTheStream() {
            // The legacy test compares the record against three values: the sentinel literal, an
            // all-spaces record and an all-low-values record. The abbreviated combined relation in
            // the source expands to separate equality tests, which is why a blank card terminates
            // the stream rather than being published as ordinary content.
            final String blankCard = " ".repeat(EXPECTED_CARD_WIDTH);
            final List<String> stream = List.of(blankCard, blankCard);

            final JobSubmissionService.SubmissionResult result =
                    service.submitJobStream("submission", stream);

            assertThat(attempts)
                    .as("the blank card is itself published, and it stops the stream, so the second"
                            + " card is never sent")
                    .hasSize(1);
            assertThat(result.cardsPublished()).isEqualTo(1);
            assertThat(result.cardsRequested()).isEqualTo(2);
            assertThat(result.failed())
                    .as("stopping on an end-of-stream card is not a failure")
                    .isFalse();
        }

        @Test
        @DisplayName("the sentinel matches when padded with trailing spaces, because COBOL compares a short literal as though it were space padded")
        void theSentinelMatchesWhenPaddedWithTrailingSpaces() {
            // The sentinel literal is five bytes and the record is eighty. COBOL pads the shorter
            // operand of a comparison with trailing spaces, so the match is against the literal
            // followed by padding and not only against an exact-length value.
            final String paddedSentinel = EXPECTED_SENTINEL_LEAD
                    + " ".repeat(EXPECTED_CARD_WIDTH - EXPECTED_SENTINEL_LEAD.length());
            final String ordinaryCard = "A".repeat(EXPECTED_CARD_WIDTH);

            service.submitJobStream("submission", List.of(paddedSentinel, ordinaryCard));

            assertThat(attempts)
                    .as("the padded sentinel must be recognised and must stop the stream")
                    .hasSize(1);
            assertThat(attempts.get(0).payload()).isEqualTo(paddedSentinel);
        }

        @Test
        @DisplayName("an ordinary card that merely begins with the sentinel bytes does not end the stream")
        void anOrdinaryCardThatMerelyBeginsWithTheSentinelBytesDoesNotEndTheStream() {
            // The comparison is against the whole space-padded record, not a prefix test. A card
            // carrying the sentinel bytes followed by other content is ordinary content.
            final String lookalike = EXPECTED_SENTINEL_LEAD + "X"
                    + " ".repeat(EXPECTED_CARD_WIDTH - EXPECTED_SENTINEL_LEAD.length() - 1);
            final String ordinaryCard = "A".repeat(EXPECTED_CARD_WIDTH);

            service.submitJobStream("submission", List.of(lookalike, ordinaryCard));

            assertThat(attempts)
                    .as("a look-alike card must not terminate the stream")
                    .hasSize(2);
        }
    }

    /*
     * ========================================================================================
     * The deduplication identifier's length budget, which the per-submission nonce consumes part
     * of and which therefore has to be shown to be enforced.
     * ========================================================================================
     */

    @Nested
    @DisplayName("the deduplication identifier length budget")
    class TheDeduplicationIdentifierLengthBudget {

        @Test
        @DisplayName("refuses a submission identity long enough to push the identifier past the length the queue service accepts, rather than letting the queue reject it")
        void refusesAnIdentityThatWouldExceedTheAcceptedLength() {
            // The identity the service derives is comfortably inside the limit, but the stream
            // publisher accepts a caller-supplied identity, so the ceiling has to be enforced here.
            // Failing at the boundary names the problem; letting the queue service reject it would
            // surface as an opaque publish failure and be swallowed by the ignore-on-error path.
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);
            final String overLongIdentity = "x".repeat(EXPECTED_DEDUPLICATION_ID_LIMIT);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() ->
                            service.writeJobSubmissionQueue(overLongIdentity, card, FIRST_ORDINAL))
                    .withMessageContaining(String.valueOf(EXPECTED_DEDUPLICATION_ID_LIMIT));

            assertThat(attempts).as("no message may be published").isEmpty();
        }

        @Test
        @DisplayName("accepts an identity that reaches the limit exactly, so the ceiling is a limit and not an off-by-one")
        void acceptsAnIdentityThatReachesTheLimitExactly() {
            // The identifier is the identity, a separator and the ordinal, so an identity of
            // limit-minus-two characters plus "-1" lands exactly on the limit.
            final String card = JclCardImageBuilder.build(START_DATE, END_DATE).get(0);
            final String exactIdentity = "x".repeat(EXPECTED_DEDUPLICATION_ID_LIMIT
                    - EXPECTED_ORDINAL_SEPARATOR.length() - 1);

            assertThat(service.writeJobSubmissionQueue(exactIdentity, card, FIRST_ORDINAL))
                    .as("an identifier of exactly the limit must be accepted")
                    .isTrue();

            assertThat(deduplicationIds()).hasSize(1);
            assertThat(deduplicationIds().get(0).length())
                    .as("the accepted identifier must be exactly the limit")
                    .isEqualTo(EXPECTED_DEDUPLICATION_ID_LIMIT);
        }

        @Test
        @DisplayName("the identity the service derives for itself leaves ample room for the ordinal, nonce included")
        void theDerivedIdentityLeavesAmpleRoomForTheOrdinal() {
            // The nonce lengthened the derived identity, so this states what the budget now is:
            // two ten-character dates, two separators, a thirty-two-character nonce, a separator
            // and the ordinal.
            service.submitTransactionReportJob(START_DATE, END_DATE);

            final int longestDerived = deduplicationIds().stream()
                    .mapToInt(String::length).max().orElseThrow();

            assertThat(longestDerived)
                    .as("the derived identifier must sit well inside the queue service's limit")
                    .isLessThan(EXPECTED_DEDUPLICATION_ID_LIMIT);
        }
    }

    /*
     * ========================================================================================
     * The submission result's own invariants.
     * ========================================================================================
     */

    @Nested
    @DisplayName("the submission result")
    class TheSubmissionResult {

        @Test
        @DisplayName("refuses a negative count on either side, because a count of cards cannot be negative")
        void refusesANegativeCountOnEitherSide() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a negative requested count must be refused")
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(-1, 0, false, ""))
                    .withMessageContaining("cardsRequested");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a negative published count must be refused")
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(1, -1, false, ""))
                    .withMessageContaining("cardsPublished");
        }

        @Test
        @DisplayName("refuses more cards published than requested, which would be an accounting error rather than a queue outcome")
        void refusesMoreCardsPublishedThanRequested() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(1, 2, false, ""))
                    .withMessageContaining("cardsPublished");
        }

        @Test
        @DisplayName("supplies the frozen operator-facing text when a failure carries none, and clears any text when there was no failure")
        void normalisesTheFailureText() {
            // A failed result must always be able to tell an operator what happened, and a clean
            // result must never carry text that suggests otherwise.
            assertThat(new JobSubmissionService.SubmissionResult(17, 4, true, "").failureMessage())
                    .as("an empty text on a failed result is replaced by the frozen text")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);

            assertThat(new JobSubmissionService.SubmissionResult(17, 4, true, null).failureMessage())
                    .as("a null text on a failed result is replaced by the frozen text")
                    .isEqualTo(JobSubmissionException.DEFAULT_MESSAGE);

            assertThat(new JobSubmissionService.SubmissionResult(17, 17, false, "ignored")
                    .failureMessage())
                    .as("text on a clean result is cleared")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports complete, partial and neither, so the three outcomes are distinguishable without inspecting the counts")
        void reportsCompletePartialAndNeither() {
            final JobSubmissionService.SubmissionResult complete =
                    new JobSubmissionService.SubmissionResult(17, 17, false, "");
            final JobSubmissionService.SubmissionResult partial =
                    new JobSubmissionService.SubmissionResult(17, 4, true, "");

            assertThat(complete.complete()).isTrue();
            assertThat(complete.partial()).isFalse();
            assertThat(partial.complete()).isFalse();
            assertThat(partial.partial()).isTrue();
        }
    }

    /*
     * ========================================================================================
     * Fixture providers.
     * ========================================================================================
     */

    /**
     * Supplies date slot values that must never reach a card, each paired with what it would do if
     * it did. Every value is exactly ten characters and ten encoded bytes, so a width check admits
     * all of them.
     *
     * @return one case per hostile slot value
     */
    static Stream<Arguments> hostileDateSlots() {
        return Stream.of(
                Arguments.of("an apostrophe closing the sort character constant", "2022-01-0'"),
                Arguments.of("a comma separating a sort operand", "2022-01-0,"),
                Arguments.of("a quotation mark", "2022-01-0\""),
                Arguments.of("an ampersand introducing a symbolic substitution", "2022-01-0&"),
                Arguments.of("a carriage return", "2022-01-0" + (char) 0x0D),
                Arguments.of("a line feed", "2022-01-0" + (char) 0x0A),
                Arguments.of("a null byte", "2022-01-0" + (char) 0x00),
                Arguments.of("an in-stream terminator card", "/*EOF!!!!!"),
                Arguments.of("a job-control statement", "//JOBLIB !"),
                Arguments.of("an impossible calendar day", "9999-99-99"),
                Arguments.of("the twenty-ninth of February in a non-leap year", "2023-02-29"));
    }

    /**
     * Supplies the control bytes a card may not carry, each named by what it would do to the record
     * frame at a consumer.
     *
     * @return one case per control byte
     */
    static Stream<Arguments> cardsCarryingAControlByte() {
        return Stream.of(
                Arguments.of("a line feed, which splits one card into two records", (char) 0x0A),
                Arguments.of("a carriage return, which reframes the record", (char) 0x0D),
                Arguments.of("a null byte, which truncates the record", (char) 0x00),
                Arguments.of("a horizontal tab, which a reader may expand", (char) 0x09),
                Arguments.of("a delete byte, which is not a printable graphic", (char) 0x7F),
                Arguments.of("a unit separator", (char) 0x1F));
    }

    /*
     * ========================================================================================
     * Helpers.
     * ========================================================================================
     */

    /**
     * Builds a card of exactly the record width whose final byte is the given character, so the
     * width guard cannot be what rejects it.
     *
     * @param character the character to place in the last position
     * @return a card of exactly {@link #EXPECTED_CARD_WIDTH} characters
     */
    private static String cardCarrying(final char character) {
        return "A".repeat(EXPECTED_CARD_WIDTH - 1) + character;
    }

    /**
     * Measures a value in encoded bytes, which is the unit the record width is defined in.
     *
     * @param value the value to measure
     * @return the number of bytes in the value's US-ASCII encoding
     */
    private static int encodedWidth(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * The bodies of every publish attempt, in order.
     *
     * @return the recorded payloads
     */
    private List<String> payloads() {
        return this.attempts.stream().map(PublishedMessage::payload).toList();
    }

    /**
     * The deduplication identifiers of every publish attempt, in order.
     *
     * @return the recorded identifiers
     */
    private List<String> deduplicationIds() {
        return this.attempts.stream().map(PublishedMessage::messageDeduplicationId).toList();
    }

    /*
     * ========================================================================================
     * Diagnostic-hygiene fixtures and assertions.
     * ========================================================================================
     */

    /** A carriage return: whitespace, and the first half of a forged log record. */
    private static final char CARRIAGE_RETURN = 13;

    /** A line feed: whitespace, and the byte a log reader treats as ending a record. */
    private static final char LINE_FEED = 10;

    /** A tab: whitespace that is not a line terminator, so the two rejections stay separable. */
    private static final char TAB = 9;

    /**
     * A NUL: not whitespace, so a whitespace-only guard admits it.
     *
     * <p>It is the cheapest demonstration that rejecting whitespace is not the same thing as
     * rejecting control characters. A consumer that treats the deduplication identifier as a C string
     * truncates at this byte, so two identities that differ only after it become one.
     */
    private static final char NUL = 0;

    /**
     * An escape: not whitespace, and the most dangerous of the three for a log reader.
     *
     * <p>An escape sequence reaching a terminal-backed viewer can reposition the cursor and overwrite
     * records that were already written, forging history without ever emitting a line feed. A guard
     * that looks only for line terminators therefore misses the stronger attack.
     */
    private static final char ESCAPE = 0x1B;

    /** A delete: not whitespace, and the one control code that sits above the printable range. */
    private static final char DELETE = 0x7F;

    /**
     * A character outside US-ASCII entirely, used to reach the representability branch.
     *
     * <p>It is printable in Unicode terms, which is exactly why the representability question is asked
     * separately from the printability question: this character is not a control character and would
     * pass a control-character filter, yet it cannot be encoded as a single US-ASCII byte, so the
     * bytes published would not be the bytes the caller supplied.
     */
    private static final char BEYOND_US_ASCII = '\u00E9';

    /**
     * Text a forged log record would carry.
     *
     * <p>Asserting on a marker rather than on the terminator alone is what makes a leak visible
     * instead of inferred: if this string reaches a diagnostic, the value was echoed, whatever
     * happened to the control characters around it.
     */
    private static final String HOSTILE_MARKER = "QAMARKFORGEDADMIN";

    /**
     * The two-character sequence that ends a log record.
     *
     * @return a carriage return followed by a line feed
     */
    private static String terminators() {
        return Character.toString(CARRIAGE_RETURN) + Character.toString(LINE_FEED);
    }

    /**
     * Asserts that a rejection's message carries no raw control character.
     *
     * <p>This is the property decision DL-041 exists to protect. A message that contains a raw
     * carriage return, line feed or tab can be split by a log reader into records the service never
     * wrote, so the check is made on the message itself rather than on the value that produced it.
     *
     * @param thrown the rejection to inspect
     */
    private static void assertCarriesNoRawTerminator(final Throwable thrown) {
        final String message = thrown.getMessage();
        assertThat(message).as("a rejection must carry a message").isNotNull();
        assertCarriesNoRawTerminator(message);
    }

    /**
     * Asserts that one diagnostic string carries no raw control character.
     *
     * <p>The rule is about the sink, not about how the value arrived at it, so the same three checks
     * apply to a rejection message and to a recorded log record. This overload exists because a log
     * record is not a throwable and the property being asserted is identical.
     *
     * @param diagnostic the text being examined; must not be {@code null}
     */
    private static void assertCarriesNoRawTerminator(final String diagnostic) {
        assertThat(diagnostic).as("a diagnostic must exist to be examined").isNotNull();
        assertThat(diagnostic.indexOf(CARRIAGE_RETURN))
                .as("a diagnostic must carry no raw carriage return: %s", diagnostic).isEqualTo(-1);
        assertThat(diagnostic.indexOf(LINE_FEED))
                .as("a diagnostic must carry no raw line feed: %s", diagnostic).isEqualTo(-1);
        assertThat(diagnostic.indexOf(TAB))
                .as("a diagnostic must carry no raw tab: %s", diagnostic).isEqualTo(-1);
    }

    /**
     * Asserts that a rejection message carries no control character of any kind.
     *
     * <p>Strictly stronger than {@link #assertCarriesNoRawTerminator(Throwable)}, which names three
     * specific characters. A line-terminator check is the right assertion for a line-forging attempt,
     * but it passes a message that echoed a NUL, an escape or a delete - and those are precisely the
     * characters a whitespace-only guard used to admit. Every control character is refused here so the
     * assertion cannot drift behind the guard it is checking.
     *
     * @param thrown the rejection to inspect
     */
    private static void assertCarriesNoRawControlCharacter(final Throwable thrown) {
        final String message = thrown.getMessage();
        assertThat(message).as("a rejection must carry a message").isNotNull();
        for (int index = 0; index < message.length(); index++) {
            final char character = message.charAt(index);
            assertThat(Character.isISOControl(character))
                    .as("a diagnostic must carry no raw control character, but position %d holds"
                            + " code point %d", index, (int) character)
                    .isFalse();
        }
    }


    /**
     * One recorded publish attempt: everything the service asked the queue to do with one card.
     *
     * @param queue                  the destination the message was addressed to
     * @param payload                the message body, which must be the card exactly as supplied
     * @param messageGroupId         the ordering group the message was placed in
     * @param messageDeduplicationId the identifier the queue service deduplicates on
     */
    private record PublishedMessage(String queue, String payload, String messageGroupId,
                                    String messageDeduplicationId) {
    }

    /**
     * A recording implementation of the fluent send-options object.
     *
     * <p>Hand written rather than mocked because the interface is fluent and every setter has to
     * return the same instance for the service's chained call to work. Recording the values in a
     * typed record, instead of capturing setter arguments, keeps each attempt's four values
     * associated with one another, which is what makes the ordering assertions readable.</p>
     *
     * <p>The three options the service never sets &mdash; headers, a delay, individual headers
     * &mdash; are implemented as no-ops that record nothing, because the assertions are written
     * against what the service <em>does</em> set. Nothing here is a placeholder: each method
     * satisfies the interface with the behaviour this double is specified to have.</p>
     */
    private static final class RecordingSendOptions implements SqsSendOptions<String> {

        /** The destination the service addressed the message to. */
        private String queue;

        /** The message body. */
        private String payload;

        /** The ordering group. */
        private String messageGroupId;

        /** The deduplication identifier. */
        private String messageDeduplicationId;

        @Override
        public SqsSendOptions<String> queue(final String queueValue) {
            this.queue = queueValue;
            return this;
        }

        @Override
        public SqsSendOptions<String> payload(final String payloadValue) {
            this.payload = payloadValue;
            return this;
        }

        @Override
        public SqsSendOptions<String> header(final String name, final Object value) {
            return this;
        }

        @Override
        public SqsSendOptions<String> headers(final Map<String, Object> headerValues) {
            return this;
        }

        @Override
        public SqsSendOptions<String> delaySeconds(final Integer delay) {
            return this;
        }

        @Override
        public SqsSendOptions<String> messageGroupId(final String messageGroupIdValue) {
            this.messageGroupId = messageGroupIdValue;
            return this;
        }

        @Override
        public SqsSendOptions<String> messageDeduplicationId(final String deduplicationIdValue) {
            this.messageDeduplicationId = deduplicationIdValue;
            return this;
        }

        /**
         * Freezes what was recorded into an immutable attempt.
         *
         * @return the recorded attempt
         */
        private PublishedMessage recorded() {
            return new PublishedMessage(this.queue, this.payload, this.messageGroupId,
                    this.messageDeduplicationId);
        }
    }
}
