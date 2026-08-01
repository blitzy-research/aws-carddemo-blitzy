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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsOperations;
import io.awspring.cloud.sqs.operations.SqsReceiveOptions;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;

import com.carddemo.exception.JobSubmissionException;
import com.carddemo.util.JclCardImageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies {@link JobSubmissionService}, the estate's only online-to-batch bridge.
 *
 * <p>This is an <strong>external interface contract</strong> test. The service replaces the single
 * transient-data-queue write in the whole mainframe estate, reached from the misspelled paragraph
 * {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl} line 515, and its obligations are read
 * off two authorities rather than chosen: the card-emitting loop at
 * {@code app/cbl/CORPT00C.cbl} lines 496 to 509, and the {@code TDQUEUE(JOBS)} resource definition
 * at {@code app/csd/CARDDEMO.CSD} lines 499 to 505.
 *
 * <h2>Why the collaborator is a hand-written recorder rather than a mock</h2>
 *
 * <p>Every obligation under test is a statement about <em>what reached the queue</em>: how many
 * messages, in what order, with what body, in which message group, under which deduplication
 * identifier, and how many attempts were made. A recording double answers all of those directly and,
 * because it implements the whole messaging interface, it also proves a negative the queue
 * definition demands: {@code TYPEFILE(OUTPUT)} makes the destination publish-only, so every receive,
 * poll and batch-send entry point fails the test outright if it is ever reached.
 *
 * <h2>The four attributes that bind this service</h2>
 *
 * <ul>
 *   <li>{@code RECORDSIZE(80)} with {@code RECORDFORMAT(FIXED)} - one card per message, every body
 *       exactly eighty encoded bytes, never trimmed.</li>
 *   <li>{@code DISPOSITION(MOD)} - writes append, so order is significant and every card of one
 *       submission travels in a single message group. Append also means a repeat submission is
 *       genuinely a second submission, which is what the deduplication tests below pin down.</li>
 *   <li>{@code ERROROPTION(IGNORE)} - a publish failure is logged, stops the remaining cards, and
 *       returns normally rather than propagating.</li>
 *   <li>{@code OPENTIME(INITIAL)} with {@code TYPE(EXTRA)} - the destination pre-exists, so nothing
 *       here creates or describes a queue.</li>
 * </ul>
 *
 * <h2>The oracle is independent by construction</h2>
 *
 * <p>The card count, the card width, the sentinel content, the operator-facing failure text and the
 * deduplication length limit are all hand written here from the legacy authorities and are then
 * asserted against the values the production types publish, so a drift in either direction fails.
 * The only expectation taken from a collaborator is the card sequence itself, which belongs to
 * {@code JclCardImageBuilder} by contract and is verified byte for byte by that class's own test.
 *
 * <p>Provenance: legacy checkout {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, upstream release
 * stamp {@code CardDemo_v1.0-15-g27d6c6f-68} dated 2022-07-19.
 */
@DisplayName("JobSubmissionService: the estate's only online-to-batch bridge")
class JobSubmissionServiceTest {

    /**
     * The canonical destination queue. The first-in-first-out suffix is mandatory: the queue service
     * rejects a first-in-first-out queue whose name lacks it, and ordering is contractual here.
     */
    private static final String QUEUE_NAME = "carddemo-jobs.fifo";

    /** The canonical message group that carries one submission's cards in order. */
    private static final String MESSAGE_GROUP_ID = "carddemo-job-submission";

    /** A well-formed start-date slot value. */
    private static final String START_DATE = "2022-01-01";

    /** A well-formed end-date slot value. */
    private static final String END_DATE = "2022-07-06";

    /** A second well-formed reporting period, distinct from the first in both slots. */
    private static final String OTHER_START_DATE = "2019-11-30";

    /** The end date of the second reporting period. */
    private static final String OTHER_END_DATE = "2020-02-29";

    /**
     * The hand-written card count, from the seventeen eighty-byte entries of the legacy card group
     * at {@code [app/cbl/CORPT00C.cbl:L83-L125]}. The seventeenth is the sentinel and it is
     * transmitted, so a complete submission is seventeen messages and never sixteen.
     */
    private static final int ORACLE_CARD_COUNT = 17;

    /**
     * The hand-written record width, from the {@code PIC X(80)} write buffer at
     * {@code [app/cbl/CORPT00C.cbl:L79]} and the {@code RECORDSIZE(80)} attribute of the queue.
     */
    private static final int ORACLE_CARD_WIDTH = 80;

    /** The hand-written sentinel card content, before space padding. */
    private static final String ORACLE_SENTINEL_CONTENT = "/*EOF";

    /**
     * The hand-written operator-facing failure literal, with exactly three trailing dots, from the
     * queue-write paragraph at {@code [app/cbl/CORPT00C.cbl:L517-L535]}.
     */
    private static final String ORACLE_FAILURE_TEXT = "Unable to Write TDQ (JOBS)...";

    /** The hand-written longest deduplication identifier the queue service accepts. */
    private static final int ORACLE_DEDUPLICATION_ID_MAX_LENGTH = 128;

    /** The separator the service places between a submission identity and a card ordinal. */
    private static final String ORACLE_DEDUPLICATION_ID_SEPARATOR = "-";

    /** Description carried by the failure the recording double raises, for diagnostic assertions. */
    private static final String CAUSE_TEXT = "the queue service rejected the card";

    /** Sentinel meaning the recording double never fails a publish. */
    private static final int NEVER_FAILS = 0;

    /** The one-based ordinal of the first card, matching the one-based legacy card index. */
    private static final int FIRST_CARD_ORDINAL = 1;

    /** A mid-stream card ordinal used to prove that a failure stops the remaining cards. */
    private static final int MID_STREAM_FAILING_ORDINAL = 5;

    /** The empty text a successful submission carries in place of a failure message. */
    private static final String EMPTY_TEXT = "";

    /** The recording collaborator, replaced before each test so no state leaks between them. */
    private RecordingSqsOperations sqsOperations;

    /** The service under test, rebuilt before each test from the canonical configuration. */
    private JobSubmissionService service;

    @BeforeEach
    void createServiceOverAFreshRecorder() {
        this.sqsOperations = new RecordingSqsOperations();
        this.service = new JobSubmissionService(this.sqsOperations, QUEUE_NAME, MESSAGE_GROUP_ID);
    }

    @Nested
    @DisplayName("configuration arrives from properties and is validated at construction")
    class ConstructionContract {

        @Test
        @DisplayName("the destination must name a first-in-first-out queue, because ordering is contractual and a standard queue cannot honour it")
        void theDestinationMustNameAFifoQueue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService(JobSubmissionServiceTest.this
                            .sqsOperations, "carddemo-jobs", MESSAGE_GROUP_ID))
                    .withMessageContaining("carddemo.aws.sqs.job-submission-queue")
                    .withMessageContaining(".fifo");
        }

        @Test
        @DisplayName("an absent or blank queue name fails at construction rather than at the first submission")
        void anAbsentOrBlankQueueNameFailsAtConstruction() {
            for (final String unusable : new String[] {null, EMPTY_TEXT, "   "}) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("queue name: " + unusable)
                        .isThrownBy(() -> new JobSubmissionService(JobSubmissionServiceTest.this
                                .sqsOperations, unusable, MESSAGE_GROUP_ID))
                        .withMessageContaining("carddemo.aws.sqs.job-submission-queue")
                        .withMessageContaining("no default");
            }
        }

        @Test
        @DisplayName("an absent or blank message group fails at construction, because append ordering depends on it")
        void anAbsentOrBlankMessageGroupFailsAtConstruction() {
            for (final String unusable : new String[] {null, EMPTY_TEXT, "   "}) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("message group: " + unusable)
                        .isThrownBy(() -> new JobSubmissionService(JobSubmissionServiceTest.this
                                .sqsOperations, QUEUE_NAME, unusable))
                        .withMessageContaining("carddemo.aws.sqs.message-group-id")
                        .withMessageContaining("no default");
            }
        }

        @Test
        @DisplayName("a missing messaging collaborator is rejected deterministically")
        void aMissingMessagingCollaboratorIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new JobSubmissionService(null, QUEUE_NAME, MESSAGE_GROUP_ID))
                    .withMessageContaining("sqsOperations");
        }

        @Test
        @DisplayName("the configured destination and message group are the ones every message carries")
        void theConfiguredNamesAreTheOnesEveryMessageCarries() {
            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(JobSubmissionServiceTest.this.sqsOperations.published)
                    .as("published messages").hasSize(ORACLE_CARD_COUNT)
                    .allSatisfy(message -> {
                        assertThat(message.queue()).as("destination queue").isEqualTo(QUEUE_NAME);
                        assertThat(message.messageGroupId()).as("message group")
                                .isEqualTo(MESSAGE_GROUP_ID);
                    });
        }
    }

    @Nested
    @DisplayName("a complete submission is seventeen ordered eighty-byte messages, sentinel included")
    class SeventeenMessageContract {

        @Test
        @DisplayName("a complete submission publishes seventeen messages, one per card, in the card group's own order")
        void aCompleteSubmissionPublishesSeventeenOrderedMessages() {
            // The card content is owned by the builder by contract and is verified byte for byte by
            // that class's own independent oracle, so the obligation asserted here is that this
            // service publishes exactly that sequence, one card per message, in order.
            final List<String> expectedCards = JclCardImageBuilder.build(START_DATE, END_DATE);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.cardsRequested()).as("cards requested").isEqualTo(ORACLE_CARD_COUNT);
            assertThat(result.cardsPublished()).as("cards published").isEqualTo(ORACLE_CARD_COUNT);
            assertThat(result.failed()).as("failure indicator").isFalse();
            assertThat(result.complete()).as("complete indicator").isTrue();
            assertThat(result.partial()).as("partial indicator").isFalse();
            assertThat(payloadsOf(JobSubmissionServiceTest.this.sqsOperations.published))
                    .as("published payloads in order").containsExactlyElementsOf(expectedCards);
        }

        @Test
        @DisplayName("the sentinel card is transmitted as the seventeenth message, because the legacy sets its end-of-stream flag before the write")
        void theSentinelCardIsTransmittedLast() {
            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> payloads =
                    payloadsOf(JobSubmissionServiceTest.this.sqsOperations.published);

            assertThat(payloads).as("published payloads").hasSize(ORACLE_CARD_COUNT);
            assertThat(payloads.get(ORACLE_CARD_COUNT - 1)).as("the seventeenth message body")
                    .isEqualTo(oracleCard(ORACLE_SENTINEL_CONTENT));
            assertThat(payloads.subList(0, ORACLE_CARD_COUNT - 1))
                    .as("the sixteen messages before the sentinel")
                    .doesNotContain(oracleCard(ORACLE_SENTINEL_CONTENT));
        }

        @Test
        @DisplayName("no eighteenth message is ever sent: the sentinel stops the stream after it has been published")
        void noEighteenthMessageIsEverSent() {
            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(JobSubmissionServiceTest.this.sqsOperations.sendCallCount)
                    .as("publish attempts for one complete submission").isEqualTo(ORACLE_CARD_COUNT);
        }

        @Test
        @DisplayName("every message body is exactly eighty encoded bytes and keeps its trailing spaces, because the record is fixed width")
        void everyMessageBodyIsExactlyEightyEncodedBytes() {
            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(JobSubmissionServiceTest.this.sqsOperations.published)
                    .as("published messages").hasSize(ORACLE_CARD_COUNT)
                    .allSatisfy(message -> {
                        assertThat(usAsciiLength(message.payload())).as("payload encoded width")
                                .isEqualTo(ORACLE_CARD_WIDTH);
                        assertThat(message.payload()).as("payload trailing spaces")
                                .isNotEqualTo(message.payload().stripTrailing());
                    });
        }

        @Test
        @DisplayName("all cards of one submission share one message group, which is what preserves append order end to end")
        void allCardsShareOneMessageGroup() {
            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(JobSubmissionServiceTest.this.sqsOperations.published)
                    .as("published messages").hasSize(ORACLE_CARD_COUNT)
                    .extracting(PublishedMessage::messageGroupId)
                    .containsOnly(MESSAGE_GROUP_ID);
        }

        @Test
        @DisplayName("no delay and no message header is ever set, because neither has a legacy antecedent and a delay would introduce a timing characteristic")
        void noDelayAndNoHeaderIsEverSet() {
            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(JobSubmissionServiceTest.this.sqsOperations.delaySecondsCallCount)
                    .as("delay settings made by the service").isZero();
            assertThat(JobSubmissionServiceTest.this.sqsOperations.headerCallCount)
                    .as("message headers set by the service").isZero();
        }

        @Test
        @DisplayName("the published card count constant and record width match the hand-written legacy figures")
        void thePublishedContractFiguresMatchTheLegacyFigures() {
            assertThat(JclCardImageBuilder.CARD_COUNT).as("published card count")
                    .isEqualTo(ORACLE_CARD_COUNT);
            assertThat(JobSubmissionException.RECORD_SIZE).as("published record size")
                    .isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(JobSubmissionException.DEFAULT_MESSAGE).as("published failure literal")
                    .isEqualTo(ORACLE_FAILURE_TEXT);
        }
    }

    @Nested
    @DisplayName("deduplication identity: per submission attempt, never per reporting period")
    class DeduplicationIdentityContract {

        @Test
        @DisplayName("two submissions of the same reporting period get entirely different identifiers, because the queue appends on write and the legacy re-wrote every card on every pass")
        void twoSubmissionsOfTheSamePeriodGetDifferentIdentifiers() {
            // The decisive assertion. The queue is defined DISPOSITION(MOD), which appends
            // unconditionally, and the legacy submission driver re-writes all seventeen cards on
            // every pass with no comparison against anything already written. So a second request
            // for the same reporting period is a second submission and both must reach the queue.
            // An identity derived from the two dates would instead make every identifier of the
            // second request identical to the first, and the queue service would discard all
            // seventeen of its cards inside its own deduplication interval while this service
            // reported a complete submission - suppression the mainframe never performed.
            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);
            final List<String> firstAttempt =
                    deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);
            final List<String> bothAttempts =
                    deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published);
            final List<String> secondAttempt =
                    bothAttempts.subList(ORACLE_CARD_COUNT, bothAttempts.size());

            assertThat(firstAttempt).as("identifiers of the first attempt").hasSize(ORACLE_CARD_COUNT);
            assertThat(secondAttempt).as("identifiers of the second attempt")
                    .hasSize(ORACLE_CARD_COUNT);
            assertThat(secondAttempt).as("the second attempt shares no identifier with the first")
                    .doesNotContainAnyElementsOf(firstAttempt);
            assertThat(bothAttempts).as("all thirty-four identifiers of two attempts")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the identity is not derived from the reporting period: no identifier carries either date")
        void theIdentityIsNotDerivedFromTheReportingPeriod() {
            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published))
                    .as("identifiers of one submission")
                    .hasSize(ORACLE_CARD_COUNT);
        }

        @Test
        @DisplayName("a minted identity is not a function of the reporting period: the period appears in it as a readable prefix, but the prefix alone never determines it")
        void theMintedIdentityIsNotAFunctionOfTheReportingPeriod() {
            // The requirement is that two submissions of the same period get different identities, so
            // that a legitimate re-submission is appended rather than silently deduplicated away - the
            // queue this bridge replaces had append disposition, so a repeat was an expected event. A
            // readable period prefix is retained deliberately because it makes a queue-side identifier
            // traceable to the request that produced it; the uniqueness comes from the per-submission
            // nonce that follows it. Both properties are asserted here: the prefix is present, and it
            // is not the whole identity.
            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);
            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> identifiers =
                    deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published);
            final List<String> first = identifiers.subList(0, ORACLE_CARD_COUNT);
            final List<String> second =
                    identifiers.subList(ORACLE_CARD_COUNT, identifiers.size());

            assertThat(identifiers).as("identifiers across two attempts at one period")
                    .hasSize(ORACLE_CARD_COUNT * 2)
                    .doesNotHaveDuplicates();
            assertThat(first.get(0)).as("the identity carries the period as a readable prefix")
                    .startsWith(START_DATE)
                    .contains(END_DATE);
            assertThat(second).as("no identifier of a second attempt repeats one of the first")
                    .doesNotContainAnyElementsOf(first);
            assertThat(second.get(0)).as("the second attempt shares the prefix and nothing more")
                    .startsWith(START_DATE)
                    .isNotEqualTo(first.get(0));
        }

        @Test
        @DisplayName("two submissions of different reporting periods also get different identifiers, so the change is not a special case of one period")
        void twoSubmissionsOfDifferentPeriodsAlsoGetDifferentIdentifiers() {
            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);
            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(OTHER_START_DATE, OTHER_END_DATE);

            assertThat(deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published))
                    .as("identifiers across two differing periods")
                    .hasSize(ORACLE_CARD_COUNT * 2)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("within one submission the seventeen identifiers share one identity and differ only by their one-based card ordinal")
        void withinOneSubmissionTheIdentifiersDifferOnlyByOrdinal() {
            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            final List<String> identifiers =
                    deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published);
            final String first = identifiers.get(0);
            final String identity = first.substring(0,
                    first.lastIndexOf(ORACLE_DEDUPLICATION_ID_SEPARATOR));

            assertThat(identifiers).as("identifiers of one submission").doesNotHaveDuplicates();
            for (int ordinal = FIRST_CARD_ORDINAL; ordinal <= ORACLE_CARD_COUNT; ordinal++) {
                assertThat(identifiers.get(ordinal - FIRST_CARD_ORDINAL))
                        .as("identifier of card " + ordinal)
                        .isEqualTo(identity + ORACLE_DEDUPLICATION_ID_SEPARATOR + ordinal);
            }
        }

        @Test
        @DisplayName("a minted identity carries no whitespace, because a deduplication identifier may not, and stays well inside the length the queue service accepts")
        void aMintedIdentityIsTransmissible() {
            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published))
                    .as("identifiers of one submission")
                    .hasSize(ORACLE_CARD_COUNT)
                    .allSatisfy(identifier -> {
                        assertThat(identifier).as("identifier free of whitespace")
                                .doesNotContainAnyWhitespaces();
                        assertThat(identifier.length()).as("identifier length")
                                .isLessThanOrEqualTo(ORACLE_DEDUPLICATION_ID_MAX_LENGTH);
                    });
        }

        @Test
        @DisplayName("a caller-supplied identity is used verbatim, so the caller decides what two submissions share")
        void aCallerSuppliedIdentityIsUsedVerbatim() {
            final String callerIdentity = "REPORT_REQUEST_0000000001";

            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(callerIdentity, START_DATE, END_DATE);

            final List<String> expected = new ArrayList<>(ORACLE_CARD_COUNT);
            for (int ordinal = FIRST_CARD_ORDINAL; ordinal <= ORACLE_CARD_COUNT; ordinal++) {
                expected.add(callerIdentity + ORACLE_DEDUPLICATION_ID_SEPARATOR + ordinal);
            }

            assertThat(deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published))
                    .as("identifiers under a caller-supplied identity")
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("a caller that supplies the same identity twice is deliberately asking for the repeat to be suppressed, and the identifiers then do repeat")
        void aCallerMayDeliberatelyRepeatAnIdentity() {
            // Repeat suppression is not a property of this bridge, because it was not a property of
            // the queue this bridge replaces. It remains available to whatever owns the request, and
            // this test records that the choice is the caller's and is made explicitly.
            final String stableIdentity = "OPERATOR_SUPPLIED_KEY";

            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(stableIdentity, START_DATE, END_DATE);
            JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(stableIdentity, START_DATE, END_DATE);

            final List<String> identifiers =
                    deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.published);

            assertThat(identifiers).as("identifiers across two attempts under one identity")
                    .hasSize(ORACLE_CARD_COUNT * 2);
            assertThat(identifiers.subList(ORACLE_CARD_COUNT, identifiers.size()))
                    .as("the repeated attempt's identifiers")
                    .containsExactlyElementsOf(identifiers.subList(0, ORACLE_CARD_COUNT));
        }

        @Test
        @DisplayName("a blank identity, or one holding whitespace, is rejected before anything is published")
        void anUnusableCallerSuppliedIdentityIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(null, START_DATE, END_DATE))
                    .withMessageContaining("submissionId");

            for (final String unusable : new String[] {EMPTY_TEXT, "   ", "has space", "has\ttab"}) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("identity: '" + unusable + "'")
                        .isThrownBy(() -> JobSubmissionServiceTest.this.service
                                .submitTransactionReportJob(unusable, START_DATE, END_DATE))
                        .withMessageContaining("submissionId");
            }

            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a rejected identity").isEmpty();
        }

        @Test
        @DisplayName("an identity so long that the first card's identifier would overrun the queue service limit is rejected before anything is published")
        void anOverlongCallerSuppliedIdentityIsRejected() {
            // The composed identifier is the identity, a separator and the ordinal, so the first
            // card alone overruns once the identity reaches the limit less the separator and a
            // single-digit ordinal.
            final int overlongLength =
                    ORACLE_DEDUPLICATION_ID_MAX_LENGTH - ORACLE_DEDUPLICATION_ID_SEPARATOR.length();
            final String overlongIdentity = "X".repeat(overlongLength);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(overlongIdentity, START_DATE, END_DATE))
                    .withMessageContaining(String.valueOf(ORACLE_DEDUPLICATION_ID_MAX_LENGTH));

            assertThat(JobSubmissionServiceTest.this.sqsOperations.published)
                    .as("messages published under an overlong identity").isEmpty();
        }

        @Test
        @DisplayName("the single-card entry point rejects a card ordinal below the one-based legacy index")
        void theSingleCardEntryPointRejectsAnOrdinalBelowOne() {
            final String card = oracleCard(ORACLE_SENTINEL_CONTENT);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .writeJobSubmissionQueue("IDENTITY", card, 0))
                    .withMessageContaining("one-based");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .writeJobSubmissionQueue("IDENTITY", card, -1))
                    .withMessageContaining("one-based");
        }
    }

    @Nested
    @DisplayName("a publish failure is non-fatal, stops the remaining cards, and returns normally")
    class FailureIsNonFatalAndPartial {

        @Test
        @DisplayName("a failure on the first card publishes nothing, reports the failure and still returns normally")
        void aFailureOnTheFirstCardPublishesNothing() {
            JobSubmissionServiceTest.this.sqsOperations.failOnSendCall(FIRST_CARD_ORDINAL);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.cardsRequested()).as("cards requested").isEqualTo(ORACLE_CARD_COUNT);
            assertThat(result.cardsPublished()).as("cards published").isZero();
            assertThat(result.failed()).as("failure indicator").isTrue();
            assertThat(result.partial()).as("partial indicator, nothing having been published")
                    .isFalse();
            assertThat(result.complete()).as("complete indicator").isFalse();
            assertThat(JobSubmissionServiceTest.this.sqsOperations.published)
                    .as("published messages").isEmpty();
        }

        @Test
        @DisplayName("a failure part way through stops the remaining cards, because the legacy write-error flag appears in the emitting loop's own guard")
        void aFailurePartWayThroughStopsTheRemainingCards() {
            JobSubmissionServiceTest.this.sqsOperations.failOnSendCall(MID_STREAM_FAILING_ORDINAL);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.cardsRequested()).as("cards requested").isEqualTo(ORACLE_CARD_COUNT);
            assertThat(result.cardsPublished()).as("cards published before the failing card")
                    .isEqualTo(MID_STREAM_FAILING_ORDINAL - 1);
            assertThat(result.failed()).as("failure indicator").isTrue();
            assertThat(result.partial()).as("partial indicator").isTrue();
            assertThat(result.complete()).as("complete indicator").isFalse();
            assertThat(JobSubmissionServiceTest.this.sqsOperations.sendCallCount)
                    .as("publish attempts, the failing one included")
                    .isEqualTo(MID_STREAM_FAILING_ORDINAL);
        }

        @Test
        @DisplayName("no retry, no backoff and no second attempt: the failing card is attempted exactly once")
        void noRetryIsEverAttempted() {
            JobSubmissionServiceTest.this.sqsOperations.failOnSendCall(MID_STREAM_FAILING_ORDINAL);

            JobSubmissionServiceTest.this.service.submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts").hasSize(MID_STREAM_FAILING_ORDINAL);
            assertThat(deduplicationIdsOf(JobSubmissionServiceTest.this.sqsOperations.attempted))
                    .as("one attempt per card, none repeated").doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a publish failure never propagates, because the queue is defined ignore-on-error and the legacy transaction completed normally after a failed write")
        void aPublishFailureNeverPropagates() {
            JobSubmissionServiceTest.this.sqsOperations.failOnSendCall(FIRST_CARD_ORDINAL);

            assertThatCode(() -> JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE))
                    .as("a failed submission").doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the failure text handed back is the frozen operator literal and carries no diagnostic detail, which belongs in the log")
        void theFailureTextIsTheFrozenOperatorLiteral() {
            JobSubmissionServiceTest.this.sqsOperations.failOnSendCall(MID_STREAM_FAILING_ORDINAL);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.failureMessage()).as("operator-facing failure text")
                    .isEqualTo(ORACLE_FAILURE_TEXT)
                    .doesNotContain(CAUSE_TEXT)
                    .doesNotContain(QUEUE_NAME)
                    .doesNotContain(IllegalStateException.class.getSimpleName())
                    .doesNotContain(String.valueOf(MID_STREAM_FAILING_ORDINAL));
        }

        @Test
        @DisplayName("a successful submission carries no failure text at all, never the word null")
        void aSuccessfulSubmissionCarriesNoFailureText() {
            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitTransactionReportJob(START_DATE, END_DATE);

            assertThat(result.failureMessage()).as("failure text of a successful submission")
                    .isEmpty();
        }

        @Test
        @DisplayName("the single-card entry point reports acceptance as true and a failure as false, and throws in neither case")
        void theSingleCardEntryPointReportsRatherThanThrows() {
            final String card = oracleCard("SINGLE CARD");

            assertThat(JobSubmissionServiceTest.this.service
                    .writeJobSubmissionQueue("IDENTITY", card, FIRST_CARD_ORDINAL))
                    .as("acceptance of a card the queue took").isTrue();

            JobSubmissionServiceTest.this.sqsOperations.failOnSendCall(2);

            assertThat(JobSubmissionServiceTest.this.service
                    .writeJobSubmissionQueue("IDENTITY", card, 2))
                    .as("acceptance of a card the queue refused").isFalse();
            assertThat(JobSubmissionServiceTest.this.sqsOperations.published)
                    .as("published messages after one acceptance and one refusal").hasSize(1);
        }
    }

    @Nested
    @DisplayName("the emitting loop: stream validation and the three stream-terminating card shapes")
    class SubmitJobStreamContract {

        @Test
        @DisplayName("an empty stream is rejected, because a submission of no cards triggers nothing")
        void anEmptyStreamIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream("IDENTITY", List.of()))
                    .withMessageContaining("at least one");
        }

        @Test
        @DisplayName("a null stream, or a stream holding a null card, is rejected deterministically")
        void aNullStreamOrNullCardIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream("IDENTITY", null))
                    .withMessageContaining("cardImages");

            final List<String> streamHoldingNull = new ArrayList<>();
            streamHoldingNull.add(oracleCard("CARD ONE"));
            streamHoldingNull.add(null);

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream("IDENTITY", streamHoldingNull));
            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a rejected stream").isEmpty();
        }

        @Test
        @DisplayName("a card of the wrong encoded width is rejected before anything is published, so a malformed stream leaves no partial submission behind")
        void aMalformedCardIsRejectedBeforeAnythingIsPublished() {
            // One byte short of the record width, which the queue definition fixes at eighty.
            final String oneByteShortCard = "SHORT CARD" + oracleSpaces(ORACLE_CARD_WIDTH - 11);
            assertThat(usAsciiLength(oneByteShortCard)).as("width of the malformed card")
                    .isEqualTo(ORACLE_CARD_WIDTH - 1);
            final List<String> stream = List.of(oracleCard("CARD ONE"), oneByteShortCard);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream("IDENTITY", stream))
                    .withMessageContaining("encoded bytes");
            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a malformed card").isEmpty();
        }

        @Test
        @DisplayName("a card holding a character that is not a single US-ASCII byte is rejected, so the bytes published are the caller's bytes")
        void aNonSingleByteCardIsRejected() {
            final String nonSingleByteCard =
                    "CARD\u00e9" + oracleSpaces(ORACLE_CARD_WIDTH - 5);

            assertThat(nonSingleByteCard.length()).as("character count of the non-single-byte card")
                    .isEqualTo(ORACLE_CARD_WIDTH);
            assertThat(usAsciiLength(nonSingleByteCard))
                    .as("encoded width, which replacement makes indistinguishable from a valid card")
                    .isEqualTo(ORACLE_CARD_WIDTH);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream("IDENTITY", List.of(nonSingleByteCard)))
                    .withMessageContaining("US-ASCII");
            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a non-single-byte card").isEmpty();
        }

        @Test
        @DisplayName("the sentinel card terminates the stream after it has itself been published, so the cards behind it are never sent")
        void theSentinelTerminatesTheStreamAfterBeingPublished() {
            final List<String> stream = List.of(oracleCard("CARD ONE"),
                    oracleCard(ORACLE_SENTINEL_CONTENT), oracleCard("CARD THREE"));

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitJobStream("IDENTITY", stream);

            assertThat(result.cardsRequested()).as("cards requested").isEqualTo(3);
            assertThat(result.cardsPublished()).as("cards published up to and including the sentinel")
                    .isEqualTo(2);
            assertThat(result.failed()).as("failure indicator, the stop being orderly").isFalse();
            assertThat(payloadsOf(JobSubmissionServiceTest.this.sqsOperations.published))
                    .as("published payloads")
                    .containsExactly(oracleCard("CARD ONE"), oracleCard(ORACLE_SENTINEL_CONTENT));
        }

        @Test
        @DisplayName("an all-spaces card also terminates the stream, because the legacy comparison tests the record against spaces as well as against the sentinel")
        void anAllSpacesCardAlsoTerminatesTheStream() {
            final List<String> stream = List.of(oracleCard("CARD ONE"), uniformCard(' '),
                    oracleCard("CARD THREE"));

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitJobStream("IDENTITY", stream);

            assertThat(result.cardsPublished()).as("cards published up to and including the blank")
                    .isEqualTo(2);
            assertThat(JobSubmissionServiceTest.this.sqsOperations.sendCallCount)
                    .as("publish attempts").isEqualTo(2);
        }

        @Test
        @DisplayName("an all-low-values card never reaches the stream-terminating test at all: the payload boundary refuses it first, so the legacy third comparison is unreachable through any published stream")
        void anAllLowValuesCardIsRefusedBeforeTheStreamTerminatingTest() {
            // The legacy abbreviated combined relation expands to three equality tests - against the
            // sentinel, against spaces and against low values - and the translation keeps all three, so
            // the predicate remains faithful. The third of them is nonetheless unreachable through a
            // published stream, because a low-values card is a card of control bytes and the payload
            // boundary refuses those before the emitting loop begins: a control byte does not change a
            // record's width, so a width check alone would admit it, and a smuggled control byte in an
            // eighty-column card reframes the record at the consumer. Refusing the whole submission is
            // strictly safer than silently ending a stream on it, and nothing is published either way.
            // The two reachable comparisons are asserted by the blank-card and sentinel-card tests
            // alongside this one.
            final List<String> stream = List.of(oracleCard("CARD ONE"), uniformCard('\0'),
                    oracleCard("CARD THREE"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitJobStream("IDENTITY", stream))
                    .withMessageContaining("job-submission card 2")
                    .withMessageContaining("non-printable")
                    .withMessageContaining("position 1");

            assertThat(JobSubmissionServiceTest.this.sqsOperations.sendCallCount)
                    .as("nothing is published, so the stream is refused rather than truncated")
                    .isZero();
            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a refused stream").isEmpty();
        }

        @Test
        @DisplayName("a card whose only padding is trailing spaces is still published whole, never trimmed to its content")
        void aCardIsPublishedWholeAndNeverTrimmed() {
            final String card = oracleCard("CARD ONE");

            JobSubmissionServiceTest.this.service.submitJobStream("IDENTITY", List.of(card));

            assertThat(payloadsOf(JobSubmissionServiceTest.this.sqsOperations.published))
                    .as("published payload").containsExactly(card);
        }

        @Test
        @DisplayName("the stream is snapshotted on entry, so a caller mutating its list afterwards cannot alter what was published")
        void theStreamIsSnapshottedOnEntry() {
            final List<String> mutableStream = new ArrayList<>();
            mutableStream.add(oracleCard("CARD ONE"));
            mutableStream.add(oracleCard("CARD TWO"));
            final List<String> snapshotBeforeTheCall = List.copyOf(mutableStream);

            final JobSubmissionService.SubmissionResult result = JobSubmissionServiceTest.this.service
                    .submitJobStream("IDENTITY", mutableStream);
            mutableStream.clear();

            assertThat(result.cardsRequested()).as("cards requested at the moment of the call")
                    .isEqualTo(snapshotBeforeTheCall.size());
            assertThat(payloadsOf(JobSubmissionServiceTest.this.sqsOperations.published))
                    .as("published payloads after the caller cleared its list")
                    .containsExactlyElementsOf(snapshotBeforeTheCall);
        }
    }

    @Nested
    @DisplayName("the submission result: three distinguishable outcomes, normalised on construction")
    class SubmissionResultContract {

        @Test
        @DisplayName("a negative count is rejected on construction")
        void aNegativeCountIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(-1, 0, false,
                            EMPTY_TEXT))
                    .withMessageContaining("cardsRequested");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, -1,
                            false, EMPTY_TEXT))
                    .withMessageContaining("cardsPublished");
        }

        @Test
        @DisplayName("more cards published than requested is rejected on construction")
        void moreCardsPublishedThanRequestedIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new JobSubmissionService.SubmissionResult(1, 2, false,
                            EMPTY_TEXT))
                    .withMessageContaining("must not")
                    .withMessageContaining("exceed");
        }

        @Test
        @DisplayName("a failed outcome with no supplied text takes the frozen operator literal, and never the word null")
        void aFailedOutcomeWithNoTextTakesTheFrozenLiteral() {
            assertThat(new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, 0, true, null)
                    .failureMessage()).as("normalised text for a null message")
                    .isEqualTo(ORACLE_FAILURE_TEXT);
            assertThat(new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, 0, true,
                    EMPTY_TEXT).failureMessage()).as("normalised text for an empty message")
                    .isEqualTo(ORACLE_FAILURE_TEXT);
        }

        @Test
        @DisplayName("a successful outcome discards any supplied text, so a success can never carry a failure message")
        void aSuccessfulOutcomeDiscardsAnySuppliedText() {
            assertThat(new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT,
                    ORACLE_CARD_COUNT, false, ORACLE_FAILURE_TEXT).failureMessage())
                    .as("normalised text for a successful outcome").isEmpty();
        }

        @Test
        @DisplayName("the three outcomes are distinguishable: complete, partial, and failed with nothing published")
        void theThreeOutcomesAreDistinguishable() {
            final JobSubmissionService.SubmissionResult complete =
                    new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, ORACLE_CARD_COUNT,
                            false, EMPTY_TEXT);
            final JobSubmissionService.SubmissionResult partial =
                    new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, 4, true, EMPTY_TEXT);
            final JobSubmissionService.SubmissionResult nothingPublished =
                    new JobSubmissionService.SubmissionResult(ORACLE_CARD_COUNT, 0, true, EMPTY_TEXT);

            assertThat(complete.complete()).as("complete outcome, complete indicator").isTrue();
            assertThat(complete.partial()).as("complete outcome, partial indicator").isFalse();
            assertThat(partial.complete()).as("partial outcome, complete indicator").isFalse();
            assertThat(partial.partial()).as("partial outcome, partial indicator").isTrue();
            assertThat(nothingPublished.complete()).as("failed outcome, complete indicator")
                    .isFalse();
            assertThat(nothingPublished.partial()).as("failed outcome, partial indicator").isFalse();
        }

        @Test
        @DisplayName("an orderly early stop is neither complete nor a failure, which is how a stream shorter than its sentinel is reported")
        void anOrderlyEarlyStopIsNeitherCompleteNorAFailure() {
            final JobSubmissionService.SubmissionResult earlyStop =
                    new JobSubmissionService.SubmissionResult(3, 2, false, EMPTY_TEXT);

            assertThat(earlyStop.failed()).as("failure indicator").isFalse();
            assertThat(earlyStop.complete()).as("complete indicator").isFalse();
            assertThat(earlyStop.partial()).as("partial indicator").isFalse();
            assertThat(earlyStop.failureMessage()).as("failure text").isEmpty();
        }
    }

    @Nested
    @DisplayName("an injected date slot is rejected before any card is composed or published")
    class InjectedDateSlotRejection {

        @Test
        @DisplayName("an apostrophe in a reporting date is rejected and nothing at all is published, because it would close a sort character constant early")
        void anApostropheInAReportingDateIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob("2026-01-'X", END_DATE))
                    .withMessageContaining("PARM-START-DATE");

            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a rejected slot").isEmpty();
            assertThat(JobSubmissionServiceTest.this.sqsOperations.sendCallCount)
                    .as("publish entries after a rejected slot").isZero();
        }

        @Test
        @DisplayName("a record-splitting control byte in a reporting date is rejected and nothing is published")
        void aControlByteInAReportingDateIsRejected() {
            for (final String injected : new String[] {"2026-01-0\n", "2026-01-0\r", "2026-01-0\t",
                    "2026-01-0\u0000", "2026-01-0\u001B", "2026-01-0\u007F"}) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("injected slot carrying US-ASCII 0x"
                                + Integer.toHexString(injected.charAt(injected.length() - 1)))
                        .isThrownBy(() -> JobSubmissionServiceTest.this.service
                                .submitTransactionReportJob(START_DATE, injected))
                        .withMessageContaining("PARM-END-DATE");
            }

            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after rejected slots").isEmpty();
        }

        @Test
        @DisplayName("a misshaped reporting date of the right width is rejected and nothing is published")
        void aMisshapedReportingDateIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob("2022/01/01", END_DATE))
                    .withMessageContaining("PARM-START-DATE");

            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a misshaped slot").isEmpty();
        }

        @Test
        @DisplayName("a reporting date of the wrong width, or an absent one, is rejected and nothing is published")
        void aWrongWidthOrAbsentReportingDateIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob("2022-01-0", END_DATE))
                    .withMessageContaining("encoded bytes");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob(START_DATE, null))
                    .as("an absent slot is named by the parameter it arrived as, because no slot"
                            + " exists yet to name positionally")
                    .withMessageContaining("endDate");

            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a rejected slot").isEmpty();
        }

        @Test
        @DisplayName("the caller-supplied identity overload applies exactly the same slot rejection")
        void theCallerSuppliedIdentityOverloadRejectsTheSameSlots() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob("IDENTITY", "2026-01-'X", END_DATE))
                    .withMessageContaining("PARM-START-DATE");

            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a rejected slot").isEmpty();
        }

        @Test
        @DisplayName("a structurally valid but impossible calendar date is rejected before any card is composed, because both slots are embedded in the sort include-condition and in the report parameter")
        void aStructurallyValidNonCalendarDateIsRejected() {
            // The slot passes the positional allowlist - eight digits and two separators in the right
            // places - so only a calendar check can catch it. Left uncaught it would be embedded in
            // the sort include-condition on cards 11 and 12 and in the report parameter on card 15,
            // producing a job whose date window names no real interval. The check rejects and never
            // converts, so an accepted slot still reaches its card as the caller's own bytes.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> JobSubmissionServiceTest.this.service
                            .submitTransactionReportJob("9999-99-99", "0000-00-00"))
                    .withMessageContaining("PARM-START-DATE")
                    .withMessageContaining("day that exists");

            assertThat(JobSubmissionServiceTest.this.sqsOperations.attempted)
                    .as("publish attempts after a rejected slot").isEmpty();
            assertThat(JobSubmissionServiceTest.this.sqsOperations.sendCallCount)
                    .as("publish entries after a rejected slot").isZero();
        }
    }

    /**
     * Measures a value in encoded bytes using the single-byte encoding the record width is defined
     * in, so no width assertion is ever taken from a character count.
     *
     * @param value the value to measure
     * @return the value's length in encoded bytes
     */
    private static int usAsciiLength(final String value) {
        return value.getBytes(StandardCharsets.US_ASCII).length;
    }

    /**
     * Produces a run of ASCII spaces for a hand-written expectation.
     *
     * @param count the number of spaces
     * @return the padding run
     */
    private static String oracleSpaces(final int count) {
        return " ".repeat(count);
    }

    /**
     * Left justifies hand-written card content in the eighty-byte record frame.
     *
     * @param content the card content, before padding
     * @return the eighty-byte card image
     */
    private static String oracleCard(final String content) {
        final String cardImage = content + oracleSpaces(ORACLE_CARD_WIDTH - usAsciiLength(content));

        assertThat(usAsciiLength(cardImage)).as("hand-written oracle card width for: " + content)
                .isEqualTo(ORACLE_CARD_WIDTH);
        return cardImage;
    }

    /**
     * Produces a card image made entirely of one repeated character, for the two stream-terminating
     * card shapes the legacy comparison recognises beside the sentinel.
     *
     * @param fill the character to fill the record with
     * @return an eighty-byte card image consisting solely of {@code fill}
     */
    private static String uniformCard(final char fill) {
        return String.valueOf(fill).repeat(ORACLE_CARD_WIDTH);
    }

    /**
     * Reads the deduplication identifiers of a recorded message list, in publication order.
     *
     * @param messages the recorded messages
     * @return their deduplication identifiers, in order
     */
    private static List<String> deduplicationIdsOf(final List<PublishedMessage> messages) {
        final List<String> identifiers = new ArrayList<>(messages.size());
        for (final PublishedMessage message : messages) {
            identifiers.add(message.deduplicationId());
        }
        return List.copyOf(identifiers);
    }

    /**
     * Reads the payloads of a recorded message list, in publication order.
     *
     * @param messages the recorded messages
     * @return their payloads, in order
     */
    private static List<String> payloadsOf(final List<PublishedMessage> messages) {
        final List<String> payloads = new ArrayList<>(messages.size());
        for (final PublishedMessage message : messages) {
            payloads.add(message.payload());
        }
        return List.copyOf(payloads);
    }

    /**
     * One message as the recording double observed it, carrying every option the service set.
     *
     * @param queue           the destination the service named
     * @param payload         the message body, exactly as supplied and never trimmed
     * @param messageGroupId  the message group the service named
     * @param deduplicationId the deduplication identifier the service composed
     */
    private record PublishedMessage(String queue, String payload, String messageGroupId,
            String deduplicationId) {
    }

    /**
     * Captures the options a single publish set, so the test can assert on them rather than on a
     * lambda.
     *
     * @param <T> the payload type the service publishes
     */
    private static final class CapturingSendOptions<T> implements SqsSendOptions<T> {

        private String queue;
        private T payload;
        private String messageGroupId;
        private String deduplicationId;
        private int headerCallCount;
        private int delaySecondsCallCount;

        @Override
        public SqsSendOptions<T> queue(final String queueName) {
            this.queue = queueName;
            return this;
        }

        @Override
        public SqsSendOptions<T> payload(final T messagePayload) {
            this.payload = messagePayload;
            return this;
        }

        @Override
        public SqsSendOptions<T> header(final String name, final Object value) {
            this.headerCallCount++;
            return this;
        }

        @Override
        public SqsSendOptions<T> headers(final Map<String, Object> messageHeaders) {
            this.headerCallCount++;
            return this;
        }

        @Override
        public SqsSendOptions<T> delaySeconds(final Integer delay) {
            this.delaySecondsCallCount++;
            return this;
        }

        @Override
        public SqsSendOptions<T> messageGroupId(final String groupId) {
            this.messageGroupId = groupId;
            return this;
        }

        @Override
        public SqsSendOptions<T> messageDeduplicationId(final String identifier) {
            this.deduplicationId = identifier;
            return this;
        }
    }

    /**
     * A recording messaging double that answers what actually reached the queue.
     *
     * <p>Only the options form of the publish entry point is implemented. Every other entry point of
     * the messaging interface - the three convenience send forms, the batch send, and all six
     * receive forms - raises an {@code AssertionError}, because the queue is defined
     * {@code TYPEFILE(OUTPUT)} and the service must never reach for any of them. A publish-only
     * violation therefore fails whichever test provoked it rather than passing unnoticed.
     */
    private static final class RecordingSqsOperations implements SqsOperations {

        /** Every publish attempt, in order, whether it succeeded or failed. */
        private final List<PublishedMessage> attempted = new ArrayList<>();

        /** Only the attempts the queue accepted, in order. */
        private final List<PublishedMessage> published = new ArrayList<>();

        /** How many times the publish entry point was entered, including the failing attempt. */
        private int sendCallCount;

        /** The one-based publish call that must fail, or {@value #NEVER_FAILS} for none. */
        private int failOnSendCall = NEVER_FAILS;

        /** Headers and delays the service set, which for this contract must both stay at zero. */
        private int headerCallCount;

        /** Delay-second settings the service made, which must stay at zero. */
        private int delaySecondsCallCount;

        /**
         * Schedules a publish failure on one attempt.
         *
         * @param oneBasedSendCall the publish attempt that must raise
         */
        void failOnSendCall(final int oneBasedSendCall) {
            this.failOnSendCall = oneBasedSendCall;
        }

        @Override
        public <T> SendResult<T> send(final Consumer<SqsSendOptions<T>> optionsConsumer) {
            this.sendCallCount++;

            final CapturingSendOptions<T> captured = new CapturingSendOptions<>();
            optionsConsumer.accept(captured);
            this.headerCallCount += captured.headerCallCount;
            this.delaySecondsCallCount += captured.delaySecondsCallCount;

            final T messagePayload =
                    Objects.requireNonNull(captured.payload, "the service must set a payload");
            final PublishedMessage attempt = new PublishedMessage(captured.queue,
                    String.valueOf(messagePayload), captured.messageGroupId,
                    captured.deduplicationId);
            this.attempted.add(attempt);

            if (this.sendCallCount == this.failOnSendCall) {
                throw new IllegalStateException(CAUSE_TEXT);
            }

            this.published.add(attempt);
            final Message<T> message = new GenericMessage<>(messagePayload);
            return new SendResult<>(UUID.randomUUID(), captured.queue, message, Map.of());
        }

        @Override
        public <T> SendResult<T> send(final T payload) {
            throw publishOnlyViolation("send(payload)");
        }

        @Override
        public <T> SendResult<T> send(final String queue, final T payload) {
            throw publishOnlyViolation("send(queue, payload)");
        }

        @Override
        public <T> SendResult<T> send(final String queue, final Message<T> message) {
            throw publishOnlyViolation("send(queue, message)");
        }

        @Override
        public <T> SendResult.Batch<T> sendMany(final String queue,
                final Collection<Message<T>> messages) {
            throw publishOnlyViolation("sendMany");
        }

        @Override
        public Optional<Message<?>> receive() {
            throw publishOnlyViolation("receive()");
        }

        @Override
        public <T> Optional<Message<T>> receive(final String queue, final Class<T> payloadType) {
            throw publishOnlyViolation("receive(queue, type)");
        }

        @Override
        public Optional<Message<?>> receive(final Consumer<SqsReceiveOptions> options) {
            throw publishOnlyViolation("receive(options)");
        }

        @Override
        public <T> Optional<Message<T>> receive(final Consumer<SqsReceiveOptions> options,
                final Class<T> payloadType) {
            throw publishOnlyViolation("receive(options, type)");
        }

        @Override
        public Collection<Message<?>> receiveMany() {
            throw publishOnlyViolation("receiveMany()");
        }

        @Override
        public <T> Collection<Message<T>> receiveMany(final String queue,
                final Class<T> payloadType) {
            throw publishOnlyViolation("receiveMany(queue, type)");
        }

        @Override
        public Collection<Message<?>> receiveMany(final Consumer<SqsReceiveOptions> options) {
            throw publishOnlyViolation("receiveMany(options)");
        }

        @Override
        public <T> Collection<Message<T>> receiveMany(final Consumer<SqsReceiveOptions> options,
                final Class<T> payloadType) {
            throw publishOnlyViolation("receiveMany(options, type)");
        }

        /**
         * Builds the failure raised when the service reaches an entry point the queue definition
         * forbids.
         *
         * @param entryPoint the entry point that was reached
         * @return the assertion failure to raise
         */
        private static AssertionError publishOnlyViolation(final String entryPoint) {
            return new AssertionError("the job-submission queue is defined TYPEFILE(OUTPUT), so the"
                    + " service must never call " + entryPoint);
        }
    }
}
