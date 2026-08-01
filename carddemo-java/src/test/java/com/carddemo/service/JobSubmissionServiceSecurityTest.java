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
import java.util.stream.Stream;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    @BeforeEach
    void createServiceOverARecordingQueue() {
        this.attempts.clear();
        this.failAtAttempt = NEVER_FAIL;
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
                throw new IllegalStateException("simulated queue rejection");
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
